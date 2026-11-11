package com.simweaver.kafka.client;

import com.simweaver.kafka.KafkaException;
import com.simweaver.kafka.record.KafkaRecord;
import com.simweaver.kafka.record.RecordBatches;
import com.simweaver.kafka.wire.Api;
import com.simweaver.kafka.wire.KReader;
import com.simweaver.kafka.wire.KWriter;
import com.simweaver.kafka.wire.KafkaErrors;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sends records to Kafka from one thread of its own. {@link #send} never blocks: records go into a
 * bounded queue (full: the record is dropped and counted), and the sender collects them for
 * {@code lingerMs}, groups them per partition (keyed: the Java client's partition for the key;
 * no key: one partition per topic until its batch went out) and writes one Produce request per
 * leader broker. Retriable errors (leader moved, broker down) keep the records until their
 * delivery timeout; the order within a partition is kept.
 */
public final class KafkaProducer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(KafkaProducer.class.getName());

    /** What the producer is told to do besides where the cluster is. */
    public static final class Options {
        /** -1 (all in-sync replicas), 1 (the leader) or 0 (no answer) */
        public int acks = -1;
        public int compression = RecordBatches.NONE;
        public int lingerMs = 5;
        /** records waiting to go (beyond: dropped) */
        public int queueRecords = 100_000;
        /** octets of one partition's batch */
        public int batchBytes = 1_000_000;
        /** how long a record may wait for its broker before it is dropped */
        public int deliveryTimeoutMs = 30_000;
        /** create topics that do not exist (when the broker allows it) */
        public boolean autoCreateTopics = true;
    }

    /** Counters (any thread may read them). */
    public final AtomicLong sent = new AtomicLong();
    public final AtomicLong sentBytes = new AtomicLong();
    public final AtomicLong requests = new AtomicLong();
    public final AtomicLong dropped = new AtomicLong();
    public final AtomicLong expired = new AtomicLong();
    public final AtomicLong failed = new AtomicLong();
    public final AtomicLong retries = new AtomicLong();
    /** records queued, and records done with (sent, failed, expired): flush waits for them to meet */
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong settled = new AtomicLong();

    private static final class Pending {
        final String topic;
        final KafkaRecord record;
        final long since;

        Pending(String topic, KafkaRecord record, long since) {
            this.topic = topic;
            this.record = record;
            this.since = since;
        }
    }

    private final KafkaCluster cluster;
    private final Options options;
    private final BlockingQueue<Pending> queue;
    private final Map<String, ArrayDeque<Pending>> partitions = new LinkedHashMap<String, ArrayDeque<Pending>>();   // topic/partition
    private final Map<String, ArrayDeque<Pending>> unplaced = new LinkedHashMap<String, ArrayDeque<Pending>>();      // topic -> waiting for metadata
    private final Map<String, Integer> sticky = new HashMap<String, Integer>();
    private final Thread thread;
    private final Object flushLock = new Object();
    private volatile boolean running = true;
    private volatile String state = "idle";
    private volatile String lastError;
    private volatile long lastErrorAt;
    private long backoffUntil;
    private int backoffMs = 200;

    public KafkaProducer(KafkaSettings settings, Options options, String threadName) {
        this.cluster = new KafkaCluster(settings);
        this.options = options;
        this.queue = new ArrayBlockingQueue<Pending>(Math.max(16, options.queueRecords));
        this.thread = new Thread(this::run, threadName);
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Queues a record. Never blocks.
     *
     * @return false when the queue was full (the record is dropped and counted)
     */
    public boolean send(String topic, KafkaRecord record) {
        if (!running) {
            return false;
        }
        accepted.incrementAndGet();
        if (!queue.offer(new Pending(topic, record, System.currentTimeMillis()))) {
            accepted.decrementAndGet();
            dropped.incrementAndGet();
            return false;
        }
        return true;
    }

    /** @return "idle" (nothing sent yet), "connected" or "failing" */
    public String state() {
        return state;
    }

    /** @return the last error, or null */
    public String lastError() {
        return lastError;
    }

    public long lastErrorAt() {
        return lastErrorAt;
    }

    /** @return records waiting (queued, collected or held for a broker) */
    public int waiting() {
        return (int) Math.max(0, accepted.get() - settled.get());
    }

    /** Waits until everything queued so far went out (or failed), at most {@code timeoutMs}. */
    public boolean flush(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        long target = accepted.get();
        synchronized (flushLock) {
            while (settled.get() < target && System.currentTimeMillis() < end) {
                try {
                    flushLock.wait(Math.max(1, Math.min(50, end - System.currentTimeMillis())));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return settled.get() >= target;
    }

    @Override
    public void close() {
        close(2000);
    }

    /** Sends what is waiting for at most {@code drainMs}, then stops. */
    public void close(long drainMs) {
        if (drainMs > 0 && running) {
            flush(drainMs);
        }
        running = false;
        thread.interrupt();
        try {
            thread.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        cluster.close();
    }

    // ------------------------------------------------------------------ the sender thread

    private void run() {
        while (running) {
            try {
                Pending first = queue.poll(waiting() > 0 ? 50 : 500, TimeUnit.MILLISECONDS);
                if (first != null) {
                    if (options.lingerMs > 0) {
                        Thread.sleep(options.lingerMs);
                    }
                    List<Pending> got = new ArrayList<Pending>();
                    got.add(first);
                    queue.drainTo(got);
                    synchronized (partitions) {
                        for (Pending p : got) {
                            unplaced.computeIfAbsent(p.topic, t -> new ArrayDeque<Pending>()).add(p);
                        }
                    }
                }
                expire();
                if (System.currentTimeMillis() < backoffUntil) {
                    continue;
                }
                place();
                sendAll();
                synchronized (flushLock) {
                    flushLock.notifyAll();
                }
            } catch (InterruptedException e) {
                if (!running) {
                    break;
                }
            } catch (KafkaException e) {
                problem(e.getMessage());
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Kafka producer: unexpected failure", e);
                problem(String.valueOf(e));
            }
        }
    }

    private void problem(String message) {
        state = "failing";
        if (!message.equals(lastError) || System.currentTimeMillis() - lastErrorAt > 60_000) {
            LOG.warning("Kafka producer: " + message);
        }
        lastError = message;
        lastErrorAt = System.currentTimeMillis();
        backoffUntil = System.currentTimeMillis() + backoffMs;
        backoffMs = Math.min(5000, backoffMs * 2);
    }

    private void ok() {
        state = "connected";
        backoffMs = 200;
    }

    /** Drops records that waited longer than the delivery timeout. */
    private void expire() {
        long limit = System.currentTimeMillis() - options.deliveryTimeoutMs;
        synchronized (partitions) {
            for (Map<String, ArrayDeque<Pending>> m : java.util.Arrays.asList(partitions, unplaced)) {
                for (ArrayDeque<Pending> d : m.values()) {
                    while (!d.isEmpty() && d.peekFirst().since < limit) {
                        d.pollFirst();
                        expired.incrementAndGet();
                        settled.incrementAndGet();
                    }
                }
            }
        }
    }

    /** Gives records without a partition one (reading metadata for new topics). */
    private void place() throws KafkaException {
        List<String> topics;
        synchronized (partitions) {
            topics = new ArrayList<String>();
            for (Map.Entry<String, ArrayDeque<Pending>> e : unplaced.entrySet()) {
                if (!e.getValue().isEmpty()) {
                    topics.add(e.getKey());
                }
            }
        }
        if (topics.isEmpty()) {
            return;
        }
        List<String> unknown = new ArrayList<String>();
        for (String t : topics) {
            if (cluster.topic(t) == null) {
                unknown.add(t);
            }
        }
        if (!unknown.isEmpty()) {
            Map<String, KafkaCluster.TopicInfo> got = cluster.refresh(unknown, options.autoCreateTopics);
            for (String t : unknown) {
                KafkaCluster.TopicInfo ti = got.get(t);
                if (ti != null && ti.error != KafkaErrors.NONE && KafkaErrors.isRetriable(ti.error)) {
                    state = "failing";
                    problemNoBackoff("topic " + t + ": " + KafkaErrors.name(ti.error) + " (records wait for it until their delivery timeout;"
                            + (options.autoCreateTopics ? " does the broker create topics?)" : " create the topic)"));
                }
                if (ti != null && ti.error != KafkaErrors.NONE && !KafkaErrors.isRetriable(ti.error)) {
                    synchronized (partitions) {
                        ArrayDeque<Pending> d = unplaced.remove(t);
                        failed.addAndGet(d == null ? 0 : d.size());
                        settled.addAndGet(d == null ? 0 : d.size());
                    }
                    throw new KafkaException(ti.error, "topic " + t + ": " + KafkaErrors.name(ti.error));
                }
            }
        }
        synchronized (partitions) {
            for (String t : topics) {
                KafkaCluster.TopicInfo ti = cluster.topic(t);
                if (ti == null || ti.partitions() == 0) {
                    continue;                                // not there yet (being created): next round
                }
                ArrayDeque<Pending> d = unplaced.get(t);
                while (d != null && !d.isEmpty()) {
                    Pending p = d.pollFirst();
                    int part = p.record.key != null ? Partitioner.partition(p.record.key, ti.partitions()) : stickyPartition(t, ti.partitions());
                    partitions.computeIfAbsent(t + "/" + part, k -> new ArrayDeque<Pending>()).add(p);
                }
            }
        }
    }

    private int stickyPartition(String topic, int n) {
        Integer s = sticky.get(topic);
        if (s == null || s >= n) {
            s = (int) (Math.random() * n);
            sticky.put(topic, s);
        }
        return s;
    }

    /** One Produce request per leader broker with a batch per partition. */
    private void sendAll() throws KafkaException {
        Map<Integer, Map<String, List<Pending>>> byLeader = new LinkedHashMap<Integer, Map<String, List<Pending>>>();
        synchronized (partitions) {
            for (Map.Entry<String, ArrayDeque<Pending>> e : partitions.entrySet()) {
                ArrayDeque<Pending> d = e.getValue();
                if (d.isEmpty()) {
                    continue;
                }
                String tp = e.getKey();
                int slash = tp.lastIndexOf('/');
                String topic = tp.substring(0, slash);
                int part = Integer.parseInt(tp.substring(slash + 1));
                KafkaCluster.TopicInfo ti = cluster.topic(topic);
                int leader = ti == null || part >= ti.partitions() ? -1 : ti.leaders[part];
                if (leader < 0) {
                    continue;
                }
                List<Pending> batch = new ArrayList<Pending>();
                int size = 0;
                Iterator<Pending> it = d.iterator();
                while (it.hasNext()) {
                    Pending p = it.next();
                    int s = p.record.estimatedSize();
                    if (!batch.isEmpty() && size + s > options.batchBytes) {
                        break;
                    }
                    batch.add(p);
                    size += s;
                }
                byLeader.computeIfAbsent(leader, k -> new LinkedHashMap<String, List<Pending>>()).put(tp, batch);
            }
        }
        boolean refresh = false;
        for (Map.Entry<Integer, Map<String, List<Pending>>> e : byLeader.entrySet()) {
            refresh |= produce(e.getKey(), e.getValue());
        }
        boolean leaderless = false;
        synchronized (partitions) {
            for (Map.Entry<String, ArrayDeque<Pending>> e : partitions.entrySet()) {
                if (!e.getValue().isEmpty()) {
                    String tp = e.getKey();
                    String topic = tp.substring(0, tp.lastIndexOf('/'));
                    KafkaCluster.TopicInfo ti = cluster.topic(topic);
                    int part = Integer.parseInt(tp.substring(tp.lastIndexOf('/') + 1));
                    if (ti == null || part >= ti.partitions() || ti.leaders[part] < 0) {
                        leaderless = true;
                        cluster.invalidate(topic);
                    }
                }
            }
        }
        if (refresh || leaderless) {
            List<String> topics = new ArrayList<String>();
            synchronized (partitions) {
                for (String tp : partitions.keySet()) {
                    String t = tp.substring(0, tp.lastIndexOf('/'));
                    if (!topics.contains(t)) {
                        topics.add(t);
                    }
                }
            }
            cluster.refresh(topics, options.autoCreateTopics);
            backoffUntil = System.currentTimeMillis() + 100;
        }
    }

    /** @return true when metadata should be read again */
    private boolean produce(int leader, Map<String, List<Pending>> batches) throws KafkaException {
        BrokerConnection c;
        try {
            c = cluster.connection(leader);
        } catch (KafkaException e) {
            cluster.drop(leader);
            for (String tp : batches.keySet()) {
                cluster.invalidate(tp.substring(0, tp.lastIndexOf('/')));
            }
            throw e;
        }
        int v = c.version(Api.PRODUCE);
        Map<String, Map<Integer, byte[]>> byTopic = new LinkedHashMap<String, Map<Integer, byte[]>>();
        int octets = 0;
        for (Map.Entry<String, List<Pending>> e : batches.entrySet()) {
            String tp = e.getKey();
            String topic = tp.substring(0, tp.lastIndexOf('/'));
            int part = Integer.parseInt(tp.substring(tp.lastIndexOf('/') + 1));
            List<KafkaRecord> recs = new ArrayList<KafkaRecord>(e.getValue().size());
            for (Pending p : e.getValue()) {
                recs.add(p.record);
            }
            byte[] b = RecordBatches.encode(recs, options.compression);
            octets += b.length;
            byTopic.computeIfAbsent(topic, k -> new LinkedHashMap<Integer, byte[]>()).put(part, b);
        }
        KWriter w = new KWriter(octets + 128);
        w.string(null);                                      // transactional id
        w.int16(options.acks);
        w.int32(options.deliveryTimeoutMs);
        w.count(byTopic.size());
        for (Map.Entry<String, Map<Integer, byte[]>> t : byTopic.entrySet()) {
            w.string(t.getKey());
            w.count(t.getValue().size());
            for (Map.Entry<Integer, byte[]> p : t.getValue().entrySet()) {
                w.int32(p.getKey());
                w.bytes(p.getValue());
            }
        }
        requests.incrementAndGet();
        if (options.acks == 0) {
            try {
                c.send(Api.PRODUCE, v, w);
            } catch (KafkaException e) {
                cluster.drop(leader);
                throw e;
            }
            for (Map.Entry<String, List<Pending>> e : batches.entrySet()) {
                done(e.getKey(), e.getValue().size(), true);
            }
            sentBytes.addAndGet(octets);
            ok();
            return false;
        }
        KReader r;
        try {
            r = c.request(Api.PRODUCE, v, w, options.deliveryTimeoutMs);
        } catch (KafkaException e) {
            cluster.drop(leader);
            throw e;
        }
        boolean refresh = false;
        String firstError = null;
        int nt = r.count();
        for (int i = 0; i < nt; i++) {
            String topic = r.string();
            int np = r.count();
            for (int k = 0; k < np; k++) {
                int part = r.int32();
                int err = r.int16();
                r.int64();                                   // base offset
                if (v >= 2) {
                    r.int64();                               // log append time
                }
                if (v >= 5) {
                    r.int64();                               // log start offset
                }
                String text = null;
                if (v >= 8) {
                    int ne = r.count();
                    for (int x = 0; x < ne; x++) {
                        r.int32();
                        r.string();
                    }
                    text = r.string();
                }
                String tp = topic + "/" + part;
                List<Pending> b = batches.get(tp);
                int n = b == null ? 0 : b.size();
                if (err == KafkaErrors.NONE) {
                    done(tp, n, true);
                } else if (KafkaErrors.isRetriable(err)) {
                    retries.incrementAndGet();
                    if (KafkaErrors.isLeaderMoved(err)) {
                        cluster.invalidate(topic);
                        refresh = true;
                    }
                    if (firstError == null) {
                        firstError = topic + "/" + part + ": " + KafkaErrors.name(err) + (text == null ? "" : " (" + text + ")");
                    }
                } else {
                    done(tp, n, false);
                    String m = "topic " + topic + " partition " + part + ": " + KafkaErrors.name(err) + (text == null ? "" : " (" + text + ")");
                    problemNoBackoff(m);
                }
            }
        }
        sentBytes.addAndGet(octets);
        if (firstError != null) {
            problem(firstError);
        } else {
            ok();
        }
        return refresh;
    }

    private void problemNoBackoff(String m) {
        if (!m.equals(lastError) || System.currentTimeMillis() - lastErrorAt > 60_000) {
            LOG.warning("Kafka producer: " + m);
        }
        lastError = m;
        lastErrorAt = System.currentTimeMillis();
    }

    /** Takes a partition's first n records off its queue. */
    private void done(String tp, int n, boolean delivered) {
        synchronized (partitions) {
            ArrayDeque<Pending> d = partitions.get(tp);
            for (int i = 0; i < n && d != null && !d.isEmpty(); i++) {
                d.pollFirst();
            }
            String topic = tp.substring(0, tp.lastIndexOf('/'));
            sticky.remove(topic);                            // records without a key: the next batch elsewhere
        }
        (delivered ? sent : failed).addAndGet(n);
        settled.addAndGet(n);
    }

    /** @return counters for diagnostics */
    public Map<String, Object> diagnostics() {
        Map<String, Object> d = new LinkedHashMap<String, Object>();
        d.put("state", state);
        d.put("sent", sent.get());
        d.put("sentbytes", sentBytes.get());
        d.put("requests", requests.get());
        d.put("waiting", waiting());
        d.put("dropped", dropped.get());
        d.put("expired", expired.get());
        d.put("failed", failed.get());
        d.put("retries", retries.get());
        if (lastError != null) {
            d.put("lasterror", lastError);
            d.put("lasterrorat", lastErrorAt);
        }
        return Collections.unmodifiableMap(d);
    }
}
