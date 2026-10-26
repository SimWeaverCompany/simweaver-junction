package com.simweaver.common.infra;

import com.simweaver.common.json.Json;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What one DIS or C-DIS socket hears, for the Infra view: per protocol and exercise, the senders
 * (address and port) with the site and application numbers in their PDUs, the PDU types and the
 * activity. The receiver hands every datagram over before its own version and exercise filters,
 * so a DIS 7 datasource listening to exercise 1 also reports the DIS 6 exercise 3 on its port.
 *
 * <p>Called on the receiver thread; read by the API. Each call is short (headers only).
 */
public final class DisTap {

    /** An exercise with no PDU for this long is "quiet". */
    static final long QUIET_MILLIS = 5000;
    /** ... and for this long "silent". */
    static final long SILENT_MILLIS = 60000;
    private static final int MAX_ENTITIES = 20000;
    private static final int MAX_SITE_APPS = 64;
    private static final int MAX_SENDERS = 2000;
    private static final int HEADER = 12;

    private final InfraRegistry registry;
    private final InfraSource source;
    private final boolean cdis;
    private final int port;
    private final String where;
    private final long opened = System.currentTimeMillis();
    private final Map<String, Exercise> exercises = new LinkedHashMap<String, Exercise>();
    private volatile String note;
    private volatile boolean closed;
    private long datagrams;
    private long malformed;

    DisTap(InfraRegistry registry, InfraSource source, boolean cdis, int port, String where) {
        this.registry = registry;
        this.source = source;
        this.cdis = cdis;
        this.port = port;
        this.where = where == null ? "" : where;
    }

    /** @param text what the owner does with the exercise, e.g. "listens to exercise 1, sends as exercise 1" */
    public void setNote(String text) {
        this.note = text;
    }

    /**
     * A DIS datagram: one PDU or several bundled ones (DIS 7 pads them to 8 octets).
     *
     * @param from sender address
     * @param fromPort sender port
     */
    public synchronized void datagram(InetAddress from, int fromPort, byte[] data, int offset, int length, long now) {
        if (closed || cdis) {
            return;
        }
        datagrams++;
        int pos = offset;
        int end = offset + length;
        boolean any = false;
        while (end - pos >= HEADER) {
            if (data[pos] == 0) {                                    // padding between bundled PDUs
                int next = offset + (((pos - offset) + 8) & ~7);
                if (next <= pos || next >= end) {
                    break;
                }
                pos = next;
                continue;
            }
            int len = ((data[pos + 8] & 0xFF) << 8) | (data[pos + 9] & 0xFF);
            if (len < HEADER || len > end - pos) {
                malformed++;
                return;
            }
            pdu(from, fromPort, data, pos, len, len, now);
            any = true;
            pos += len;
        }
        if (!any) {
            malformed++;
        }
    }

    /**
     * A C-DIS datagram, as the codec decoded it.
     *
     * @param octets the datagram's length (counted as the traffic)
     * @param pdus   the DIS 7 PDUs it held
     */
    public synchronized void decoded(InetAddress from, int fromPort, int octets, List<byte[]> pdus, long now) {
        if (closed || !cdis) {
            return;
        }
        datagrams++;
        if (pdus.isEmpty()) {
            malformed++;
            return;
        }
        int share = octets / pdus.size();
        for (byte[] p : pdus) {
            if (p.length >= HEADER) {
                pdu(from, fromPort, p, 0, p.length, share, now);
            }
        }
    }

    private void pdu(InetAddress from, int fromPort, byte[] d, int pos, int len, int octets, long now) {
        int version = d[pos] & 0xFF;
        int exercise = d[pos + 1] & 0xFF;
        int type = d[pos + 2] & 0xFF;
        String family = cdis ? InfraRegistry.CDIS : version >= 7 ? InfraRegistry.DIS7 : InfraRegistry.DIS6;
        String k = family + ":" + exercise;
        Exercise ex = exercises.get(k);
        if (ex == null) {
            ex = new Exercise(family, exercise, now);
            exercises.put(k, ex);
        }
        ex.versions.add(version);
        ex.meter.add(now, 1, octets);
        ex.types.merge(type, 1L, Long::sum);
        ex.last = now;
        String sk = from.getHostAddress() + ":" + fromPort;
        Sender s = ex.senders.get(sk);
        if (s == null) {
            if (ex.senders.size() >= MAX_SENDERS) {
                ex.droppedSenders++;
                return;
            }
            s = new Sender(from, fromPort, registry.isLocal(from), now);
            ex.senders.put(sk, s);
        }
        s.meter.add(now, 1, octets);
        s.types.merge(type, 1L, Long::sum);
        s.last = now;
        int site;
        int app;
        long entity;
        if (len < HEADER + 6) {
            return;
        }
        int b = pos + HEADER;
        if (DisPduTypes.liveEntity(type)) {
            site = d[b] & 0xFF;
            app = d[b + 1] & 0xFF;
            entity = ((d[b + 2] & 0xFF) << 8) | (d[b + 3] & 0xFF);
        } else {
            site = ((d[b] & 0xFF) << 8) | (d[b + 1] & 0xFF);
            app = ((d[b + 2] & 0xFF) << 8) | (d[b + 3] & 0xFF);
            entity = ((d[b + 4] & 0xFF) << 8) | (d[b + 5] & 0xFF);
        }
        int sa = (site << 16) | app;
        Long c = s.siteApps.get(sa);
        if (c != null || s.siteApps.size() < MAX_SITE_APPS) {
            s.siteApps.put(sa, c == null ? 1L : c + 1);
        }
        if (DisPduTypes.entityState(type)) {
            long id = ((long) sa << 16) | entity;
            if (s.entities.size() < MAX_ENTITIES) {
                s.entities.add(id);
            }
            if (ex.entities.size() < MAX_ENTITIES) {
                ex.entities.add(id);
            }
        }
    }

    /** Drops senders, and exercises without senders, not heard for {@code forgetMillis}. */
    synchronized void prune(long now, long forgetMillis) {
        if (forgetMillis <= 0) {
            return;
        }
        for (java.util.Iterator<Exercise> it = exercises.values().iterator(); it.hasNext();) {
            Exercise ex = it.next();
            ex.senders.values().removeIf(s -> now - s.last > forgetMillis);
            if (ex.senders.isEmpty() && now - ex.last > forgetMillis) {
                it.remove();
            }
        }
    }

    /** Stops reporting; the registry forgets the tap. */
    public void close() {
        closed = true;
        registry.remove(this);
    }

    boolean isClosed() {
        return closed;
    }

    InfraSource source() {
        return source;
    }

    // ------------------------------------------------------------------ items

    /** One item per exercise heard. */
    synchronized List<InfraRegistry.Item> items() {
        List<InfraRegistry.Item> out = new ArrayList<InfraRegistry.Item>();
        for (Exercise ex : exercises.values()) {
            String key = InfraRegistry.itemKey(ex.family, ex.family + "|" + ex.exercise + "|" + port + "|" + source.key());
            out.add(new InfraRegistry.Item(key, ex.family, this, ex.exercise));
        }
        return out;
    }

    /** @return the overview row (detail=false) or the full view of one exercise; null when gone */
    synchronized Json.JsonObject json(String key, int exercise, String family, boolean detail, long now) {
        Exercise ex = exercises.get(family + ":" + exercise);
        if (ex == null) {
            return null;
        }
        Json.JsonObject o = Json.object();
        o.put("key", key);
        o.put("family", family);
        o.put("protocol", InfraRegistry.familyLabel(family));
        o.put("name", "Exercise " + exercise);
        o.put("exercise", exercise);
        o.put("versions", new ArrayList<Integer>(new java.util.TreeSet<Integer>(ex.versions)));
        o.put("port", port);
        o.put("where", where);
        o.put("source", source.toJson());
        o.putIfNotNull("note", note);
        o.put("senders", ex.senders.size());
        o.put("entities", ex.entities.size());
        o.put("pdus", ex.meter.total());
        o.put("octets", ex.meter.totalOctets());
        o.put("rate", ActivityMeter.round(ex.meter.rate(now)));
        o.put("octetrate", Math.round(ex.meter.octetRate(now)));
        o.put("first", ex.first);
        o.put("last", ex.last);
        o.put("status", status(ex.last, now));
        o.put("history", ex.meter.history(now));
        if (detail) {
            o.put("pdutypes", types(ex.types));
            o.put("datagrams", datagrams);
            o.put("malformed", malformed);
            o.put("listeningsince", opened);
            if (ex.droppedSenders > 0) {
                o.put("senderslimited", ex.droppedSenders);
            }
            List<Sender> list = new ArrayList<Sender>(ex.senders.values());
            list.sort((a, b) -> {
                int c = Long.compare(b.last / 1000, a.last / 1000);
                return c != 0 ? c : (a.address.getHostAddress() + a.port).compareTo(b.address.getHostAddress() + b.port);
            });
            List<Object> senders = new ArrayList<Object>();
            for (Sender s : list) {
                senders.add(s.json(now));
            }
            o.put("senderlist", senders);
        }
        return o;
    }

    static String status(long last, long now) {
        long quiet = now - last;
        return quiet <= QUIET_MILLIS ? "active" : quiet <= SILENT_MILLIS ? "quiet" : "silent";
    }

    private static List<Object> types(Map<Integer, Long> m) {
        List<Map.Entry<Integer, Long>> l = new ArrayList<Map.Entry<Integer, Long>>(m.entrySet());
        l.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        List<Object> out = new ArrayList<Object>();
        for (Map.Entry<Integer, Long> e : l) {
            out.add(Json.object().put("type", e.getKey()).put("name", DisPduTypes.name(e.getKey())).put("pdus", e.getValue()));
        }
        return out;
    }

    // ------------------------------------------------------------------ state

    private static final class Exercise {
        final String family;
        final int exercise;
        final long first;
        long last;
        final Set<Integer> versions = new HashSet<Integer>();
        final ActivityMeter meter = new ActivityMeter();
        final Map<Integer, Long> types = new HashMap<Integer, Long>();
        final Map<String, Sender> senders = new LinkedHashMap<String, Sender>();
        final Set<Long> entities = new HashSet<Long>();
        long droppedSenders;

        Exercise(String family, int exercise, long now) {
            this.family = family;
            this.exercise = exercise;
            this.first = now;
            this.last = now;
        }
    }

    private static final class Sender {
        final InetAddress address;
        final int port;
        final boolean local;
        final long first;
        long last;
        final ActivityMeter meter = new ActivityMeter();
        final Map<Integer, Long> types = new HashMap<Integer, Long>();
        final Map<Integer, Long> siteApps = new TreeMap<Integer, Long>();
        final Set<Long> entities = new HashSet<Long>();

        Sender(InetAddress address, int port, boolean local, long now) {
            this.address = address;
            this.port = port;
            this.local = local;
            this.first = now;
            this.last = now;
        }

        Json.JsonObject json(long now) {
            List<Object> sa = new ArrayList<Object>();
            for (Map.Entry<Integer, Long> e : siteApps.entrySet()) {
                sa.add(Json.object().put("site", e.getKey() >>> 16).put("app", e.getKey() & 0xFFFF).put("pdus", e.getValue()));
            }
            return Json.object()
                    .put("address", address.getHostAddress())
                    .put("port", port)
                    .put("local", local)
                    .put("siteapps", sa)
                    .put("entities", entities.size())
                    .put("pdutypes", types(types))
                    .put("pdus", meter.total())
                    .put("rate", ActivityMeter.round(meter.rate(now)))
                    .put("octetrate", Math.round(meter.octetRate(now)))
                    .put("first", first)
                    .put("last", last)
                    .put("status", status(last, now))
                    .put("history", meter.history(now));
        }
    }
}
