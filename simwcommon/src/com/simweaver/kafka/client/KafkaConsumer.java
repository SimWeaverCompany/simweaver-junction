package com.simweaver.kafka.client;

import com.simweaver.kafka.KafkaException;
import com.simweaver.kafka.record.KafkaRecord;
import com.simweaver.kafka.record.RecordBatches;
import com.simweaver.kafka.wire.Api;
import com.simweaver.kafka.wire.KReader;
import com.simweaver.kafka.wire.KWriter;
import com.simweaver.kafka.wire.KafkaErrors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads topics from one thread of its own and hands the records to a {@link Listener} on that
 * thread.
 *
 * <p>With a group id it is a member of a consumer group (the classic group protocol: JoinGroup,
 * SyncGroup, Heartbeat, OffsetCommit; protocol type {@code consumer}, assignor {@code range}, so it
 * can share a group with the Java client and others): the group's partitions are shared among its
 * members, positions are committed to Kafka (every {@code autoCommitMs}, before a rebalance and when
 * closing), and a restart goes on where the group left off. A partition with no committed position
 * starts at the latest or the earliest offset ({@code startEarliest}).
 *
 * <p>Without a group id it reads every partition of the topics from the latest (or earliest) offset
 * at each start and commits nothing.
 */
public final class KafkaConsumer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(KafkaConsumer.class.getName());

    /** Receives records, on the consumer's thread. */
    public interface Listener {
        /** Records of one partition, in offset order. */
        void records(List<KafkaRecord> records);

        /** The consumer now reads these partitions (topic -> partitions); after a rebalance too. */
        default void assigned(Map<String, List<Integer>> partitions) {
        }
    }

    /** What to read and how. */
    public static final class Options {
        public List<String> topics = new ArrayList<String>();
        /** consumer group id; empty: no group (all partitions, nothing committed) */
        public String group = "";
        /** where a partition without a committed position starts */
        public boolean startEarliest = false;
        public int sessionTimeoutMs = 45_000;
        public int rebalanceTimeoutMs = 60_000;
        public int heartbeatMs = 3_000;
        public int autoCommitMs = 5_000;
        public int fetchMaxWaitMs = 500;
        public int fetchMaxBytes = 50 * 1024 * 1024;
        public int partitionMaxBytes = 1024 * 1024;
        /** create topics that do not exist (when the broker allows it) */
        public boolean autoCreateTopics = true;
    }

    public final AtomicLong received = new AtomicLong();
    public final AtomicLong receivedBytes = new AtomicLong();
    public final AtomicLong fetches = new AtomicLong();
    public final AtomicLong rebalances = new AtomicLong();
    public final AtomicLong commits = new AtomicLong();
    public final AtomicLong unsupportedBatches = new AtomicLong();
    public final AtomicLong corruptBatches = new AtomicLong();
    public final AtomicLong resets = new AtomicLong();

    private final KafkaCluster cluster;
    private final Options options;
    private final Listener listener;
    private final Thread thread;
    private volatile boolean running = true;
    private volatile String state = "connecting";
    private volatile String lastError;
    private volatile long lastErrorAt;

    // group state (consumer thread only, diagnostics read the volatile copies)
    private BrokerConnection coordinator;
    private String memberId = "";
    private int generation = -1;
    private boolean leader;
    private volatile String memberIdShown = "";
    private volatile int generationShown = -1;
    private long nextHeartbeat;
    private long nextCommit;
    private long nextMetadataCheck;
    private Map<String, Integer> partitionCounts = new HashMap<String, Integer>();
    private boolean assignedNoGroup;

    /** assigned partition -> next offset to read (-1: not known yet) */
    private final Map<String, Long> positions = new TreeMap<String, Long>();
    /** assigned partition -> last committed offset */
    private final Map<String, Long> committed = new HashMap<String, Long>();
    private final Map<String, Long> highWatermarks = new HashMap<String, Long>();
    private volatile Map<String, Object> shown = Collections.emptyMap();

    public KafkaConsumer(KafkaSettings settings, Options options, Listener listener, String threadName) {
        this.cluster = new KafkaCluster(settings);
        this.options = options;
        this.listener = listener;
        this.thread = new Thread(this::run, threadName);
        thread.setDaemon(true);
        thread.start();
    }

    /** @return connecting, joining, consuming or failing */
    public String state() {
        return state;
    }

    public String lastError() {
        return lastError;
    }

    private boolean grouped() {
        return options.group != null && !options.group.isEmpty();
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
        try {
            thread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        cluster.close();
    }

    // ------------------------------------------------------------------ the consumer thread

    private void run() {
        long backoff = 200;
        while (running) {
            try {
                if (grouped() ? generation < 0 : !assignedNoGroup) {
                    if (grouped()) {
                        join();
                    } else {
                        assignAll();
                    }
                    if (!running) {
                        break;
                    }
                }
                resolvePositions();
                state = "consuming";
                fetchRound();
                housekeeping();
                backoff = 200;
            } catch (KafkaException e) {
                if (!running) {
                    break;
                }
                problem(e.getMessage());
                if (e.code() == KafkaErrors.NETWORK_EXCEPTION || e.code() == KafkaErrors.NOT_COORDINATOR
                        || e.code() == KafkaErrors.COORDINATOR_NOT_AVAILABLE) {
                    coordinator = null;
                }
                sleep(backoff);
                backoff = Math.min(5000, backoff * 2);
            } catch (RuntimeException e) {
                if (!running) {
                    break;
                }
                LOG.log(Level.WARNING, "Kafka consumer: unexpected failure", e);
                problem(String.valueOf(e));
                sleep(backoff);
                backoff = Math.min(5000, backoff * 2);
            }
        }
        if (grouped() && generation >= 0) {
            try {
                commit();
            } catch (KafkaException | RuntimeException e) {
                // closing
            }
            try {
                leave();
            } catch (KafkaException | RuntimeException e) {
                // closing
            }
        }
        state = "closed";
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            // closing
        }
    }

    private void problem(String m) {
        state = "failing";
        if (!m.equals(lastError) || System.currentTimeMillis() - lastErrorAt > 60_000) {
            LOG.warning("Kafka consumer" + (grouped() ? " (group " + options.group + ")" : "") + ": " + m);
        }
        lastError = m;
        lastErrorAt = System.currentTimeMillis();
    }

    private static String tp(String topic, int partition) {
        return topic + "/" + partition;
    }

    private static String topicOf(String tp) {
        return tp.substring(0, tp.lastIndexOf('/'));
    }

    private static int partitionOf(String tp) {
        return Integer.parseInt(tp.substring(tp.lastIndexOf('/') + 1));
    }

    /** Reads the topics' partition counts (creating topics when allowed). */
    private Map<String, Integer> readPartitionCounts(List<String> topics) throws KafkaException {
        Map<String, KafkaCluster.TopicInfo> got = cluster.refresh(topics, options.autoCreateTopics);
        Map<String, Integer> counts = new TreeMap<String, Integer>();
        for (String t : topics) {
            KafkaCluster.TopicInfo ti = got.get(t);
            if (ti == null) {
                continue;
            }
            if (ti.error != KafkaErrors.NONE && !KafkaErrors.isRetriable(ti.error)) {
                throw new KafkaException(ti.error, "topic " + t + ": " + KafkaErrors.name(ti.error));
            }
            if (ti.partitions() > 0) {
                counts.put(t, ti.partitions());
            }
        }
        return counts;
    }

    /** No group: every partition of the topics. */
    private void assignAll() throws KafkaException {
        state = "connecting";
        Map<String, Integer> counts = readPartitionCounts(options.topics);
        if (counts.isEmpty()) {
            throw new KafkaException(KafkaErrors.UNKNOWN_TOPIC_OR_PARTITION, "topics " + options.topics + " have no partitions (yet)");
        }
        partitionCounts = counts;
        Map<String, List<Integer>> a = new TreeMap<String, List<Integer>>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            List<Integer> ps = new ArrayList<Integer>();
            for (int p = 0; p < e.getValue(); p++) {
                ps.add(p);
                positions.put(tp(e.getKey(), p), -1L);
            }
            a.put(e.getKey(), ps);
        }
        nextMetadataCheck = System.currentTimeMillis() + 30_000;
        assignedNoGroup = true;
        listener.assigned(a);
    }

    // ------------------------------------------------------------------ group membership

    private BrokerConnection coordinator() throws KafkaException {
        if (coordinator != null && !coordinator.isClosed()) {
            return coordinator;
        }
        BrokerConnection any = cluster.any();
        int v = any.version(Api.FIND_COORDINATOR);
        KWriter w = new KWriter();
        w.string(options.group);
        w.int8(0);                                           // group (not transaction)
        KReader r;
        try {
            r = any.request(Api.FIND_COORDINATOR, v, w);
        } catch (KafkaException e) {
            cluster.drop(any.nodeId());
            throw e;
        }
        r.int32();                                           // throttle (v1+)
        int err = r.int16();
        String text = r.string();
        int node = r.int32();
        String host = r.string();
        int port = r.int32();
        if (err != KafkaErrors.NONE) {
            throw new KafkaException(err, "group coordinator for " + options.group + ": " + KafkaErrors.name(err) + (text == null ? "" : " (" + text + ")"));
        }
        coordinator = cluster.connection(node, host, port);
        return coordinator;
    }

    /** Joins the group (again) and takes its assignment. */
    private void join() throws KafkaException {
        state = "joining";
        generation = -1;
        generationShown = -1;
        positions.clear();
        Map<String, Integer> counts = readPartitionCounts(options.topics);
        partitionCounts = counts;
        while (running) {
            BrokerConnection c = coordinator();
            int v = c.version(Api.JOIN_GROUP);
            KWriter meta = new KWriter();
            meta.int16(0);
            meta.strings(options.topics);
            meta.bytes(null);
            KWriter w = new KWriter();
            w.string(options.group);
            w.int32(options.sessionTimeoutMs);
            w.int32(options.rebalanceTimeoutMs);
            w.string(memberId);
            if (v >= 5) {
                w.string(null);                              // no static membership
            }
            w.string("consumer");
            w.count(1);
            w.string("range");
            w.bytes(meta.toBytes());
            KReader r = request(c, Api.JOIN_GROUP, v, w, options.rebalanceTimeoutMs);
            if (v >= 2) {
                r.int32();
            }
            int err = r.int16();
            int gen = r.int32();
            String protocol = r.string();
            String leaderId = r.string();
            String mine = r.string();
            if (err == KafkaErrors.MEMBER_ID_REQUIRED) {
                memberId = mine;                             // join again with it
                continue;
            }
            if (err == KafkaErrors.UNKNOWN_MEMBER_ID) {
                memberId = "";
                continue;
            }
            if (err == KafkaErrors.REBALANCE_IN_PROGRESS) {
                continue;
            }
            if (err != KafkaErrors.NONE) {
                if (err == KafkaErrors.NOT_COORDINATOR || err == KafkaErrors.COORDINATOR_NOT_AVAILABLE || err == KafkaErrors.COORDINATOR_LOAD_IN_PROGRESS) {
                    coordinator = null;
                }
                throw new KafkaException(err, "joining group " + options.group + ": " + KafkaErrors.name(err));
            }
            memberId = mine;
            generation = gen;
            leader = mine.equals(leaderId);
            Map<String, List<String>> subscriptions = new TreeMap<String, List<String>>();
            int nm = r.count();
            for (int i = 0; i < nm; i++) {
                String member = r.string();
                if (v >= 5) {
                    r.string();                              // instance id
                }
                byte[] md = r.bytes();
                subscriptions.put(member, memberTopics(md));
            }
            Map<String, byte[]> assignments = new TreeMap<String, byte[]>();
            if (leader) {
                if (!"range".equals(protocol)) {
                    throw new KafkaException(KafkaErrors.INCONSISTENT_GROUP_PROTOCOL, "group " + options.group + " chose assignor " + protocol);
                }
                assignments = assign(subscriptions);
            }
            byte[] mineAssigned = sync(c, assignments);
            if (mineAssigned == null) {
                continue;                                    // rebalance again
            }
            rebalances.incrementAndGet();
            Map<String, List<Integer>> a = parseAssignment(mineAssigned);
            for (Map.Entry<String, List<Integer>> e : a.entrySet()) {
                for (int p : e.getValue()) {
                    positions.put(tp(e.getKey(), p), -1L);
                }
            }
            memberIdShown = memberId;
            generationShown = generation;
            long now = System.currentTimeMillis();
            nextHeartbeat = now + options.heartbeatMs;
            nextCommit = now + options.autoCommitMs;
            nextMetadataCheck = now + 30_000;
            committed.clear();
            LOG.info("Kafka consumer: group " + options.group + " generation " + generation + (leader ? " (leader)" : "") + ": " + a);
            listener.assigned(a);
            return;
        }
    }

    private KReader request(BrokerConnection c, int api, int v, KWriter w, int waitMs) throws KafkaException {
        try {
            return c.request(api, v, w, waitMs);
        } catch (KafkaException e) {
            coordinator = null;
            throw e;
        }
    }

    /** @return the topics of a member's ConsumerMemberMetadata (version int16, topics [string], …) */
    static List<String> memberTopics(byte[] md) {
        List<String> l = new ArrayList<String>();
        if (md == null || md.length < 2) {
            return l;
        }
        try {
            KReader r = new KReader(md);
            r.int16();
            l.addAll(r.strings());
        } catch (KReader.KafkaProtocolException e) {
            // a member we cannot read gets nothing
        }
        return l;
    }

    /** The range assignor: per topic, its subscribers sorted by member id get consecutive ranges. */
    Map<String, byte[]> assign(Map<String, List<String>> subscriptions) throws KafkaException {
        TreeSet<String> all = new TreeSet<String>();
        for (List<String> l : subscriptions.values()) {
            all.addAll(l);
        }
        Map<String, Integer> counts = readPartitionCounts(new ArrayList<String>(all));
        partitionCounts = counts;
        return rangeAssign(subscriptions, counts);
    }

    static Map<String, byte[]> rangeAssign(Map<String, List<String>> subscriptions, Map<String, Integer> counts) {
        Map<String, Map<String, List<Integer>>> per = new TreeMap<String, Map<String, List<Integer>>>();
        for (String m : subscriptions.keySet()) {
            per.put(m, new TreeMap<String, List<Integer>>());
        }
        for (Map.Entry<String, Integer> t : counts.entrySet()) {
            List<String> members = new ArrayList<String>();
            for (Map.Entry<String, List<String>> s : subscriptions.entrySet()) {
                if (s.getValue().contains(t.getKey())) {
                    members.add(s.getKey());
                }
            }
            Collections.sort(members);
            if (members.isEmpty()) {
                continue;
            }
            int n = t.getValue();
            int each = n / members.size();
            int extra = n % members.size();
            int p = 0;
            for (int i = 0; i < members.size(); i++) {
                int take = each + (i < extra ? 1 : 0);
                List<Integer> ps = new ArrayList<Integer>();
                for (int k = 0; k < take; k++) {
                    ps.add(p++);
                }
                if (!ps.isEmpty()) {
                    per.get(members.get(i)).put(t.getKey(), ps);
                }
            }
        }
        Map<String, byte[]> out = new TreeMap<String, byte[]>();
        for (Map.Entry<String, Map<String, List<Integer>>> e : per.entrySet()) {
            KWriter w = new KWriter();
            w.int16(0);
            w.count(e.getValue().size());
            for (Map.Entry<String, List<Integer>> t : e.getValue().entrySet()) {
                w.string(t.getKey());
                w.int32s(t.getValue());
            }
            w.bytes(null);
            out.put(e.getKey(), w.toBytes());
        }
        return out;
    }

    /** @return topic -> partitions of a ConsumerMemberAssignment */
    static Map<String, List<Integer>> parseAssignment(byte[] b) {
        Map<String, List<Integer>> m = new TreeMap<String, List<Integer>>();
        if (b == null || b.length < 2) {
            return m;
        }
        KReader r = new KReader(b);
        r.int16();
        int n = r.count();
        for (int i = 0; i < n; i++) {
            String t = r.string();
            m.put(t, r.int32s());
        }
        return m;
    }

    /** @return this member's assignment, or null when the group rebalances again */
    private byte[] sync(BrokerConnection c, Map<String, byte[]> assignments) throws KafkaException {
        int v = c.version(Api.SYNC_GROUP);
        KWriter w = new KWriter();
        w.string(options.group);
        w.int32(generation);
        w.string(memberId);
        if (v >= 3) {
            w.string(null);
        }
        w.count(assignments.size());
        for (Map.Entry<String, byte[]> e : assignments.entrySet()) {
            w.string(e.getKey());
            w.bytes(e.getValue());
        }
        KReader r = request(c, Api.SYNC_GROUP, v, w, options.rebalanceTimeoutMs);
        r.int32();                                           // throttle (v1+)
        int err = r.int16();
        byte[] a = r.bytes();
        if (err == KafkaErrors.REBALANCE_IN_PROGRESS || err == KafkaErrors.ILLEGAL_GENERATION) {
            return null;
        }
        if (err == KafkaErrors.UNKNOWN_MEMBER_ID) {
            memberId = "";
            return null;
        }
        if (err != KafkaErrors.NONE) {
            throw new KafkaException(err, "syncing group " + options.group + ": " + KafkaErrors.name(err));
        }
        return a == null ? new byte[0] : a;
    }

    private void heartbeat() throws KafkaException {
        BrokerConnection c = coordinator();
        int v = c.version(Api.HEARTBEAT);
        KWriter w = new KWriter();
        w.string(options.group);
        w.int32(generation);
        w.string(memberId);
        if (v >= 3) {
            w.string(null);
        }
        KReader r = request(c, Api.HEARTBEAT, v, w, 0);
        r.int32();
        int err = r.int16();
        if (err == KafkaErrors.NONE) {
            return;
        }
        if (err == KafkaErrors.REBALANCE_IN_PROGRESS) {
            commit();
            generation = -1;                                 // join again (keeping the member id)
            return;
        }
        if (err == KafkaErrors.NOT_COORDINATOR || err == KafkaErrors.COORDINATOR_NOT_AVAILABLE || err == KafkaErrors.COORDINATOR_LOAD_IN_PROGRESS) {
            coordinator = null;
            throw new KafkaException(err, "heartbeat: " + KafkaErrors.name(err));
        }
        // ILLEGAL_GENERATION, UNKNOWN_MEMBER_ID, GROUP_ID_NOT_FOUND (the coordinator restarted), …: join again
        if (err != KafkaErrors.ILLEGAL_GENERATION) {
            memberId = "";
        }
        generation = -1;
        if (err != KafkaErrors.ILLEGAL_GENERATION && err != KafkaErrors.UNKNOWN_MEMBER_ID && err != KafkaErrors.GROUP_ID_NOT_FOUND
                && err != KafkaErrors.FENCED_INSTANCE_ID) {
            throw new KafkaException(err, "heartbeat: " + KafkaErrors.name(err) + ": joining the group again");
        }
    }

    /** Commits the positions that changed since the last commit. */
    private void commit() throws KafkaException {
        if (!grouped() || generation < 0) {
            return;
        }
        Map<String, Map<Integer, Long>> todo = new TreeMap<String, Map<Integer, Long>>();
        for (Map.Entry<String, Long> e : positions.entrySet()) {
            long pos = e.getValue();
            Long c = committed.get(e.getKey());
            if (pos >= 0 && (c == null || c != pos)) {
                todo.computeIfAbsent(topicOf(e.getKey()), k -> new TreeMap<Integer, Long>()).put(partitionOf(e.getKey()), pos);
            }
        }
        if (todo.isEmpty()) {
            return;
        }
        BrokerConnection c = coordinator();
        int v = c.version(Api.OFFSET_COMMIT);
        KWriter w = new KWriter();
        w.string(options.group);
        w.int32(generation);
        w.string(memberId);
        if (v >= 7) {
            w.string(null);
        }
        if (v <= 4) {
            w.int64(-1);                                     // retention: the broker's
        }
        w.count(todo.size());
        for (Map.Entry<String, Map<Integer, Long>> t : todo.entrySet()) {
            w.string(t.getKey());
            w.count(t.getValue().size());
            for (Map.Entry<Integer, Long> p : t.getValue().entrySet()) {
                w.int32(p.getKey());
                w.int64(p.getValue());
                if (v >= 6) {
                    w.int32(-1);
                }
                w.string("");
            }
        }
        KReader r = request(c, Api.OFFSET_COMMIT, v, w, 0);
        if (v >= 3) {
            r.int32();
        }
        int nt = r.count();
        int firstErr = KafkaErrors.NONE;
        for (int i = 0; i < nt; i++) {
            String topic = r.string();
            int np = r.count();
            for (int k = 0; k < np; k++) {
                int part = r.int32();
                int err = r.int16();
                if (err == KafkaErrors.NONE) {
                    Long pos = todo.containsKey(topic) ? todo.get(topic).get(part) : null;
                    if (pos != null) {
                        committed.put(tp(topic, part), pos);
                    }
                } else if (firstErr == KafkaErrors.NONE) {
                    firstErr = err;
                }
            }
        }
        commits.incrementAndGet();
        if (firstErr == KafkaErrors.REBALANCE_IN_PROGRESS || firstErr == KafkaErrors.ILLEGAL_GENERATION || firstErr == KafkaErrors.UNKNOWN_MEMBER_ID
                || firstErr == KafkaErrors.GROUP_ID_NOT_FOUND) {
            generation = -1;
            if (firstErr != KafkaErrors.REBALANCE_IN_PROGRESS && firstErr != KafkaErrors.ILLEGAL_GENERATION) {
                memberId = "";
            }
        } else if (firstErr != KafkaErrors.NONE) {
            throw new KafkaException(firstErr, "committing offsets: " + KafkaErrors.name(firstErr));
        }
    }

    private void leave() throws KafkaException {
        BrokerConnection c = coordinator();
        int v = c.version(Api.LEAVE_GROUP);
        KWriter w = new KWriter();
        w.string(options.group);
        w.string(memberId);
        KReader r = c.request(Api.LEAVE_GROUP, v, w);
        r.int32();
        r.int16();
    }

    // ------------------------------------------------------------------ positions and fetching

    /** Committed positions (group), else the latest / earliest offsets. */
    private void resolvePositions() throws KafkaException {
        List<String> unknown = new ArrayList<String>();
        for (Map.Entry<String, Long> e : positions.entrySet()) {
            if (e.getValue() < 0) {
                unknown.add(e.getKey());
            }
        }
        if (unknown.isEmpty()) {
            return;
        }
        if (grouped()) {
            fetchCommitted(unknown);
        }
        List<String> still = new ArrayList<String>();
        for (String tp : unknown) {
            if (positions.get(tp) < 0) {
                still.add(tp);
            }
        }
        if (!still.isEmpty()) {
            listOffsets(still, options.startEarliest ? -2L : -1L);
        }
    }

    private void fetchCommitted(List<String> tps) throws KafkaException {
        BrokerConnection c = coordinator();
        int v = c.version(Api.OFFSET_FETCH);
        Map<String, List<Integer>> byTopic = group(tps);
        KWriter w = new KWriter();
        w.string(options.group);
        w.count(byTopic.size());
        for (Map.Entry<String, List<Integer>> e : byTopic.entrySet()) {
            w.string(e.getKey());
            w.int32s(e.getValue());
        }
        KReader r = request(c, Api.OFFSET_FETCH, v, w, 0);
        if (v >= 3) {
            r.int32();
        }
        int nt = r.count();
        for (int i = 0; i < nt; i++) {
            String topic = r.string();
            int np = r.count();
            for (int k = 0; k < np; k++) {
                int part = r.int32();
                long off = r.int64();
                if (v >= 5) {
                    r.int32();
                }
                r.string();
                int err = r.int16();
                String key = tp(topic, part);
                if (err == KafkaErrors.NONE && off >= 0 && positions.containsKey(key)) {
                    positions.put(key, off);
                    committed.put(key, off);
                }
            }
        }
        if (v >= 2) {
            int err = r.int16();
            if (err != KafkaErrors.NONE) {
                if (err == KafkaErrors.NOT_COORDINATOR || err == KafkaErrors.COORDINATOR_LOAD_IN_PROGRESS) {
                    coordinator = null;
                }
                throw new KafkaException(err, "reading committed offsets: " + KafkaErrors.name(err));
            }
        }
    }

    private static Map<String, List<Integer>> group(List<String> tps) {
        Map<String, List<Integer>> m = new TreeMap<String, List<Integer>>();
        for (String tp : tps) {
            m.computeIfAbsent(topicOf(tp), k -> new ArrayList<Integer>()).add(partitionOf(tp));
        }
        return m;
    }

    /** Asks the partitions' leaders for their latest (-1) or earliest (-2) offsets. */
    private void listOffsets(List<String> tps, long timestamp) throws KafkaException {
        Map<Integer, List<String>> byLeader = byLeader(tps);
        for (Map.Entry<Integer, List<String>> e : byLeader.entrySet()) {
            BrokerConnection c;
            try {
                c = cluster.connection(e.getKey());
            } catch (KafkaException x) {
                cluster.drop(e.getKey());
                throw x;
            }
            int v = c.version(Api.LIST_OFFSETS);
            Map<String, List<Integer>> byTopic = group(e.getValue());
            KWriter w = new KWriter();
            w.int32(-1);                                     // replica id
            if (v >= 2) {
                w.int8(0);                                   // read uncommitted
            }
            w.count(byTopic.size());
            for (Map.Entry<String, List<Integer>> t : byTopic.entrySet()) {
                w.string(t.getKey());
                w.count(t.getValue().size());
                for (int p : t.getValue()) {
                    w.int32(p);
                    if (v >= 4) {
                        w.int32(-1);
                    }
                    w.int64(timestamp);
                }
            }
            KReader r;
            try {
                r = c.request(Api.LIST_OFFSETS, v, w);
            } catch (KafkaException x) {
                cluster.drop(e.getKey());
                throw x;
            }
            if (v >= 2) {
                r.int32();
            }
            int nt = r.count();
            for (int i = 0; i < nt; i++) {
                String topic = r.string();
                int np = r.count();
                for (int k = 0; k < np; k++) {
                    int part = r.int32();
                    int err = r.int16();
                    r.int64();                               // timestamp
                    long off = r.int64();
                    if (v >= 4) {
                        r.int32();
                    }
                    String key = tp(topic, part);
                    if (err == KafkaErrors.NONE && positions.containsKey(key)) {
                        positions.put(key, off);
                    } else if (KafkaErrors.isLeaderMoved(err)) {
                        cluster.invalidate(topic);
                    } else if (err != KafkaErrors.NONE) {
                        throw new KafkaException(err, "offsets of " + key + ": " + KafkaErrors.name(err));
                    }
                }
            }
        }
    }

    /** @return the partitions grouped by leader (reading metadata for leaders not known) */
    private Map<Integer, List<String>> byLeader(List<String> tps) throws KafkaException {
        List<String> missing = new ArrayList<String>();
        for (String tp : tps) {
            KafkaCluster.TopicInfo ti = cluster.topic(topicOf(tp));
            int p = partitionOf(tp);
            if (ti == null || p >= ti.partitions() || ti.leaders[p] < 0) {
                if (!missing.contains(topicOf(tp))) {
                    missing.add(topicOf(tp));
                }
            }
        }
        if (!missing.isEmpty()) {
            cluster.refresh(missing, false);
        }
        Map<Integer, List<String>> m = new LinkedHashMap<Integer, List<String>>();
        for (String tp : tps) {
            KafkaCluster.TopicInfo ti = cluster.topic(topicOf(tp));
            int p = partitionOf(tp);
            if (ti != null && p < ti.partitions() && ti.leaders[p] >= 0) {
                m.computeIfAbsent(ti.leaders[p], k -> new ArrayList<String>()).add(tp);
            }
        }
        return m;
    }

    /** One Fetch per leader of the assigned partitions. */
    private void fetchRound() throws KafkaException {
        List<String> ready = new ArrayList<String>();
        for (Map.Entry<String, Long> e : positions.entrySet()) {
            if (e.getValue() >= 0) {
                ready.add(e.getKey());
            }
        }
        if (ready.isEmpty()) {
            sleep(100);
            return;
        }
        Map<Integer, List<String>> byLeader = byLeader(ready);
        if (byLeader.isEmpty()) {
            sleep(200);
            return;
        }
        int wait = byLeader.size() > 1 ? Math.min(options.fetchMaxWaitMs, 100) : options.fetchMaxWaitMs;
        wait = (int) Math.max(0, Math.min(wait, nextDue() - System.currentTimeMillis()));
        for (Map.Entry<Integer, List<String>> e : byLeader.entrySet()) {
            fetch(e.getKey(), e.getValue(), wait);
            if (!running || (grouped() && generation < 0)) {
                return;
            }
        }
    }

    private long nextDue() {
        if (!grouped()) {
            return System.currentTimeMillis() + options.fetchMaxWaitMs;
        }
        return Math.min(nextHeartbeat, nextCommit);
    }

    private void fetch(int leader, List<String> tps, int waitMs) throws KafkaException {
        BrokerConnection c;
        try {
            c = cluster.connection(leader);
        } catch (KafkaException x) {
            cluster.drop(leader);
            throw x;
        }
        int v = c.version(Api.FETCH);
        Map<String, List<Integer>> byTopic = group(tps);
        KWriter w = new KWriter();
        w.int32(-1);                                         // replica id
        w.int32(waitMs);
        w.int32(1);                                          // min bytes
        w.int32(options.fetchMaxBytes);                      // v3+
        w.int8(0);                                           // read uncommitted (v4+)
        if (v >= 7) {
            w.int32(0);                                      // no fetch session
            w.int32(-1);
        }
        w.count(byTopic.size());
        for (Map.Entry<String, List<Integer>> t : byTopic.entrySet()) {
            w.string(t.getKey());
            w.count(t.getValue().size());
            for (int p : t.getValue()) {
                w.int32(p);
                if (v >= 9) {
                    w.int32(-1);                             // current leader epoch
                }
                w.int64(positions.get(tp(t.getKey(), p)));
                if (v >= 5) {
                    w.int64(-1);                             // log start offset
                }
                w.int32(options.partitionMaxBytes);
            }
        }
        if (v >= 7) {
            w.count(0);                                      // forgotten topics
        }
        if (v >= 11) {
            w.string("");                                    // rack
        }
        KReader r;
        try {
            r = c.request(Api.FETCH, v, w, waitMs);
        } catch (KafkaException x) {
            cluster.drop(leader);
            throw x;
        }
        fetches.incrementAndGet();
        r.int32();                                           // throttle
        if (v >= 7) {
            int err = r.int16();
            r.int32();                                       // session id
            if (err != KafkaErrors.NONE) {
                throw new KafkaException(err, "fetch: " + KafkaErrors.name(err));
            }
        }
        List<String> outOfRange = new ArrayList<String>();
        boolean refresh = false;
        int nt = r.count();
        for (int i = 0; i < nt; i++) {
            String topic = r.string();
            int np = r.count();
            for (int k = 0; k < np; k++) {
                int part = r.int32();
                int err = r.int16();
                long hw = r.int64();
                r.int64();                                   // last stable offset (v4+)
                if (v >= 5) {
                    r.int64();                               // log start offset
                }
                int na = r.count();                          // aborted transactions (v4+)
                for (int x = 0; x < na; x++) {
                    r.int64();
                    r.int64();
                }
                if (v >= 11) {
                    r.int32();                               // preferred read replica
                }
                byte[] data = r.bytes();
                String key = tp(topic, part);
                if (err == KafkaErrors.OFFSET_OUT_OF_RANGE) {
                    outOfRange.add(key);
                    continue;
                }
                if (err != KafkaErrors.NONE) {
                    if (KafkaErrors.isLeaderMoved(err)) {
                        refresh = true;
                        cluster.invalidate(topic);
                    } else {
                        problem("fetch " + key + ": " + KafkaErrors.name(err));
                    }
                    continue;
                }
                highWatermarks.put(key, hw);
                Long pos = positions.get(key);
                if (pos == null || data == null || data.length == 0) {
                    continue;
                }
                List<KafkaRecord> recs = new ArrayList<KafkaRecord>();
                RecordBatches.ReadResult res = RecordBatches.read(data, topic, part, pos, recs::add);
                if (res.unsupportedCompression + res.oldFormat > 0) {
                    unsupportedBatches.addAndGet(res.unsupportedCompression + res.oldFormat);
                    problem(key + ": " + res.problem);
                }
                if (res.corrupt > 0) {
                    corruptBatches.addAndGet(res.corrupt);
                    problem(key + ": " + res.problem);
                }
                if (res.nextOffset > pos) {
                    positions.put(key, res.nextOffset);
                }
                if (!recs.isEmpty()) {
                    received.addAndGet(recs.size());
                    receivedBytes.addAndGet(data.length);
                    try {
                        listener.records(recs);
                    } catch (RuntimeException ex) {
                        LOG.log(Level.WARNING, "Kafka consumer: the listener failed", ex);
                    }
                }
            }
        }
        if (!outOfRange.isEmpty()) {
            resets.addAndGet(outOfRange.size());
            LOG.info("Kafka consumer: offsets out of range for " + outOfRange + ", starting at the " + (options.startEarliest ? "earliest" : "latest"));
            listOffsets(outOfRange, options.startEarliest ? -2L : -1L);
        }
        if (refresh) {
            sleep(100);
        }
        updateShown();
    }

    /** Heartbeat, commit, and (no group / leader) whether partitions were added. */
    private void housekeeping() throws KafkaException {
        long now = System.currentTimeMillis();
        if (grouped() && generation >= 0) {
            if (now >= nextHeartbeat) {
                nextHeartbeat = now + options.heartbeatMs;
                heartbeat();
            }
            if (generation >= 0 && now >= nextCommit) {
                nextCommit = now + options.autoCommitMs;
                commit();
            }
        }
        if (now >= nextMetadataCheck && (!grouped() || leader) && (!grouped() || generation >= 0)) {
            nextMetadataCheck = now + 30_000;
            Map<String, Integer> counts = readPartitionCounts(options.topics);
            if (!counts.equals(partitionCounts)) {
                LOG.info("Kafka consumer: partitions changed " + partitionCounts + " -> " + counts);
                if (grouped()) {
                    generation = -1;                         // the leader joins again: a new assignment
                } else {
                    for (Map.Entry<String, Integer> e : counts.entrySet()) {
                        for (int p = 0; p < e.getValue(); p++) {
                            positions.putIfAbsent(tp(e.getKey(), p), -1L);         // resolved by the start policy
                        }
                    }
                    partitionCounts = counts;
                }
            }
        }
    }

    private void updateShown() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        long lag = 0;
        for (Map.Entry<String, Long> e : positions.entrySet()) {
            Long hw = highWatermarks.get(e.getKey());
            if (hw != null && e.getValue() >= 0) {
                lag += Math.max(0, hw - e.getValue());
            }
        }
        m.put("partitions", new ArrayList<String>(positions.keySet()));
        m.put("lag", lag);
        shown = m;
    }

    /** @return counters and state for diagnostics */
    public Map<String, Object> diagnostics() {
        Map<String, Object> d = new LinkedHashMap<String, Object>();
        d.put("state", state);
        if (grouped()) {
            d.put("group", options.group);
            d.put("generation", generationShown);
            d.put("memberid", memberIdShown);
        }
        d.put("topics", options.topics);
        d.putAll(shown);
        d.put("received", received.get());
        d.put("receivedbytes", receivedBytes.get());
        d.put("fetches", fetches.get());
        d.put("rebalances", rebalances.get());
        d.put("commits", commits.get());
        d.put("resets", resets.get());
        d.put("unsupportedbatches", unsupportedBatches.get());
        d.put("corruptbatches", corruptBatches.get());
        if (lastError != null) {
            d.put("lasterror", lastError);
            d.put("lasterrorat", lastErrorAt);
        }
        return d;
    }
}
