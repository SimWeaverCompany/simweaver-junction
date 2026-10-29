package com.simweaver.j2j.link;

import com.simweaver.j2j.wire.Envelope;
import com.simweaver.j2j.wire.J2jWire;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The scheduler of one link (design §5.2): ADMIN has strict priority; RELIABLE and BEST_EFFORT
 * share the rest by deficit round robin with weights 70 / 30 (in octets), unused share going to
 * the other. RELIABLE is bounded by octets and refuses when full (back pressure); BEST_EFFORT is
 * bounded too, drops when full, replaces a queued message with the same supersede key in place,
 * and drops messages older than the age limit. Thread safe.
 */
final class SendQueue {

    static final class Item {
        final Envelope env;
        final byte[] encoded;
        final String supersede;
        long queuedAt;

        Item(Envelope env, String supersede, long now) {
            this.env = env;
            this.encoded = env.encode();
            this.supersede = supersede;
            this.queuedAt = now;
        }
    }

    private static final int QUANTUM = 1500;
    private static final int WEIGHT_RELIABLE = 70;
    private static final int WEIGHT_BEST_EFFORT = 30;

    private final ArrayDeque<Item> admin = new ArrayDeque<Item>();
    private final ArrayDeque<Item> reliable = new ArrayDeque<Item>();
    private final LinkedHashMap<Object, Item> bestEffort = new LinkedHashMap<Object, Item>();
    private final long reliableMaxBytes;
    private final int bestEffortMax;
    private final long bestEffortMaxAgeMs;
    private long reliableBytes;
    private long adminBytes;
    private long deficitReliable;
    private long deficitBestEffort;
    private long anon;
    private boolean closed;
    private boolean kicked;

    // counters (guarded by this)
    long dropped;
    long superseded;
    long expired;

    SendQueue(long reliableMaxBytes, int bestEffortMax, long bestEffortMaxAgeMs) {
        this.reliableMaxBytes = reliableMaxBytes;
        this.bestEffortMax = bestEffortMax;
        this.bestEffortMaxAgeMs = bestEffortMaxAgeMs;
    }

    /**
     * @param supersede best effort only: a key; a queued message with the same key is replaced
     * @return false when the class's queue is full (reliable: back pressure; best effort: dropped)
     *         or the link is closing
     */
    synchronized boolean offer(Envelope env, String supersede) {
        if (closed) {
            return false;
        }
        long now = System.currentTimeMillis();
        Item it = new Item(env, supersede, now);
        switch (env.cls) {
            case J2jWire.CLASS_ADMIN:
                if (adminBytes > 64L * 1024 * 1024) {
                    return false;
                }
                admin.add(it);
                adminBytes += it.encoded.length;
                break;
            case J2jWire.CLASS_RELIABLE:
                if (reliableBytes + it.encoded.length > reliableMaxBytes && !reliable.isEmpty()) {
                    return false;
                }
                reliable.add(it);
                reliableBytes += it.encoded.length;
                break;
            default:
                Object key = supersede != null ? supersede : Long.valueOf(anon++);
                Item old = bestEffort.get(key);
                if (old != null) {
                    bestEffort.put(key, it);          // same place in the order (LinkedHashMap keeps it)
                    superseded++;
                } else {
                    if (bestEffort.size() >= bestEffortMax) {
                        dropped++;
                        return false;
                    }
                    bestEffort.put(key, it);
                }
        }
        notifyAll();
        return true;
    }

    /** @return the next item, or null after {@code waitMs} without one (or when closed) */
    synchronized Item poll(long waitMs) throws InterruptedException {
        long end = System.currentTimeMillis() + waitMs;
        while (!closed) {
            Item it = next();
            if (it != null) {
                return it;
            }
            if (kicked) {
                kicked = false;
                return null;                          // the writer has something else to do (a PONG)
            }
            long left = end - System.currentTimeMillis();
            if (left <= 0) {
                return null;
            }
            wait(left);
        }
        return null;
    }

    private Item next() {
        if (!admin.isEmpty()) {
            Item it = admin.poll();
            adminBytes -= it.encoded.length;
            return it;
        }
        expire();
        boolean r = !reliable.isEmpty();
        boolean b = !bestEffort.isEmpty();
        if (!r && !b) {
            deficitReliable = 0;
            deficitBestEffort = 0;
            return null;
        }
        if (r && !b) {
            return takeReliable();
        }
        if (b && !r) {
            return takeBestEffort();
        }
        for (int round = 0; round < 64; round++) {
            Item hr = reliable.peek();
            if (deficitReliable >= hr.encoded.length) {
                deficitReliable -= hr.encoded.length;
                return takeReliable();
            }
            Item hb = bestEffort.values().iterator().next();
            if (deficitBestEffort >= hb.encoded.length) {
                deficitBestEffort -= hb.encoded.length;
                return takeBestEffort();
            }
            deficitReliable += (long) QUANTUM * WEIGHT_RELIABLE / 10;
            deficitBestEffort += (long) QUANTUM * WEIGHT_BEST_EFFORT / 10;
        }
        return takeReliable();
    }

    private Item takeReliable() {
        Item it = reliable.poll();
        reliableBytes -= it.encoded.length;
        return it;
    }

    private Item takeBestEffort() {
        Iterator<Map.Entry<Object, Item>> i = bestEffort.entrySet().iterator();
        Item it = i.next().getValue();
        i.remove();
        return it;
    }

    private void expire() {
        if (bestEffort.isEmpty()) {
            return;
        }
        long limit = System.currentTimeMillis() - bestEffortMaxAgeMs;
        Iterator<Map.Entry<Object, Item>> i = bestEffort.entrySet().iterator();
        while (i.hasNext()) {
            if (i.next().getValue().queuedAt < limit) {
                i.remove();
                expired++;
            }
        }
    }

    /** Moves every queued item to {@code other} (in order: admin, reliable, best effort). */
    void drainTo(SendQueue other) {
        List<Item> items = new ArrayList<Item>();
        synchronized (this) {
            items.addAll(admin);
            items.addAll(reliable);
            items.addAll(bestEffort.values());
            admin.clear();
            reliable.clear();
            bestEffort.clear();
            adminBytes = 0;
            reliableBytes = 0;
        }
        for (Item it : items) {
            other.offer(it.env, it.supersede);
        }
    }

    synchronized int[] sizes() {
        return new int[] {admin.size(), reliable.size(), bestEffort.size()};
    }

    synchronized void close() {
        closed = true;
        notifyAll();
    }

    synchronized void wake() {
        kicked = true;
        notifyAll();
    }
}
