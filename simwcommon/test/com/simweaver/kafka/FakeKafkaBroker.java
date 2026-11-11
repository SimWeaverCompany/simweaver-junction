package com.simweaver.kafka;

import com.simweaver.kafka.wire.Api;
import com.simweaver.kafka.wire.KReader;
import com.simweaver.kafka.wire.KWriter;
import com.simweaver.kafka.wire.KafkaErrors;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A single-node Kafka broker in memory, for the self tests (no Kafka needed): the API versions the
 * client writes (each between its lowest and highest, or only the lowest with {@code oldest}),
 * topics created on first use, record batches stored as written (base offset assigned), fetches that
 * wait for data, offsets, the classic group protocol (join with MEMBER_ID_REQUIRED, rebalances on
 * join / leave / session timeout, sync, heartbeat, commit) and SASL PLAIN. The client was also
 * checked against a real broker implementation and other clients; this broker is what the tests
 * run against everywhere.
 */
public final class FakeKafkaBroker implements AutoCloseable {

    private static final int[] APIS = {Api.PRODUCE, Api.FETCH, Api.LIST_OFFSETS, Api.METADATA, Api.OFFSET_COMMIT, Api.OFFSET_FETCH,
        Api.FIND_COORDINATOR, Api.JOIN_GROUP, Api.HEARTBEAT, Api.LEAVE_GROUP, Api.SYNC_GROUP, Api.SASL_HANDSHAKE, Api.API_VERSIONS,
        Api.SASL_AUTHENTICATE};

    private final ServerSocket server;
    private final int partitions;
    private final boolean oldest;
    private final Map<String, String> plainUsers = new HashMap<String, String>();
    private final Map<String, List<Partition>> topics = new TreeMap<String, List<Partition>>();
    private final Map<String, Group> groups = new HashMap<String, Group>();
    private final List<Socket> sockets = new CopyOnWriteArrayList<Socket>();
    private volatile boolean autoCreate = true;
    private volatile boolean running = true;
    public final AtomicInteger produceRequests = new AtomicInteger();
    public final AtomicInteger fetchRequests = new AtomicInteger();
    public final Map<Integer, Integer> versionsSeen = new java.util.concurrent.ConcurrentHashMap<Integer, Integer>();

    private static final class Partition {
        final List<long[]> index = new ArrayList<long[]>();      // {baseOffset, count}
        final List<byte[]> batches = new ArrayList<byte[]>();
        long next;
    }

    private static final class Member {
        final String id;
        byte[] metadata;
        long lastSeen = System.currentTimeMillis();
        int sessionTimeout;
        byte[] assignment;
        boolean joined;          // joined the current rebalance

        Member(String id) {
            this.id = id;
        }
    }

    private static final class Group {
        final Map<String, Member> members = new LinkedHashMap<String, Member>();
        final Map<String, Long> offsets = new HashMap<String, Long>();
        int generation;
        boolean rebalancing;
        boolean synced;
        long rebalanceDeadline;
        String leader = "";
        String protocol = "range";
    }

    /**
     * @param partitions partitions of topics created on first use
     * @param oldest     answer ApiVersions with only the lowest versions the client writes
     */
    public FakeKafkaBroker(int partitions, boolean oldest) throws IOException {
        this.partitions = partitions;
        this.oldest = oldest;
        this.server = new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        Thread t = new Thread(this::accept, "fake-kafka-accept");
        t.setDaemon(true);
        t.start();
    }

    /** Requires SASL PLAIN with this user from now on. */
    public FakeKafkaBroker plainUser(String user, String password) {
        plainUsers.put(user, password);
        return this;
    }

    public FakeKafkaBroker autoCreate(boolean b) {
        autoCreate = b;
        return this;
    }

    public int port() {
        return server.getLocalPort();
    }

    public String bootstrap() {
        return "127.0.0.1:" + port();
    }

    /** @return the records' count in a topic (all partitions) */
    public synchronized long count(String topic) {
        List<Partition> ps = topics.get(topic);
        long n = 0;
        if (ps != null) {
            for (Partition p : ps) {
                n += p.next;
            }
        }
        return n;
    }

    /** @return a group's committed offset of a partition, or -1 */
    public synchronized long committed(String group, String topic, int partition) {
        Group g = groups.get(group);
        Long o = g == null ? null : g.offsets.get(topic + "/" + partition);
        return o == null ? -1 : o;
    }

    /** Creates a topic with this many partitions (or keeps it). */
    public synchronized void createTopic(String topic, int n) {
        if (!topics.containsKey(topic)) {
            List<Partition> l = new ArrayList<Partition>();
            for (int i = 0; i < n; i++) {
                l.add(new Partition());
            }
            topics.put(topic, l);
        }
    }

    /** Drops every connection (clients must reconnect). */
    public void dropConnections() {
        for (Socket s : sockets) {
            try {
                s.close();
            } catch (IOException e) {
                // gone
            }
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            server.close();
        } catch (IOException e) {
            // closing
        }
        dropConnections();
    }

    private void accept() {
        while (running) {
            try {
                Socket s = server.accept();
                sockets.add(s);
                Thread t = new Thread(() -> serve(s), "fake-kafka-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket s) {
        boolean authenticated = plainUsers.isEmpty();
        try (Socket sock = s) {
            DataInputStream in = new DataInputStream(new BufferedInputStream(sock.getInputStream()));
            OutputStream out = sock.getOutputStream();
            while (running) {
                int size = in.readInt();
                byte[] b = new byte[size];
                in.readFully(b);
                KReader r = new KReader(b);
                int api = r.int16();
                int version = r.int16();
                int corr = r.int32();
                r.string();                                    // client id
                versionsSeen.merge(api, version, Math::max);
                KWriter w = new KWriter();
                w.int32(0);
                w.int32(corr);
                if (!authenticated && api != Api.API_VERSIONS && api != Api.SASL_HANDSHAKE && api != Api.SASL_AUTHENTICATE) {
                    return;                                    // like Kafka: close
                }
                if (api == Api.SASL_AUTHENTICATE) {
                    byte[] msg = r.bytes();
                    String[] parts = new String(msg, StandardCharsets.UTF_8).split("\u0000", -1);
                    boolean ok = parts.length == 3 && plainUsers.containsKey(parts[1]) && plainUsers.get(parts[1]).equals(parts[2]);
                    w.int16(ok ? 0 : KafkaErrors.SASL_AUTHENTICATION_FAILED);
                    w.string(ok ? null : "Authentication failed: Invalid username or password");
                    w.bytes(new byte[0]);
                    if (version >= 1) {
                        w.int64(0);
                    }
                    authenticated = ok;
                } else if (!handle(api, version, r, w)) {
                    continue;                                  // no response (acks 0)
                }
                w.patchInt32(0, w.position() - 4);
                out.write(w.buffer(), 0, w.position());
                out.flush();
            }
        } catch (IOException | RuntimeException e) {
            // connection gone
        } finally {
            sockets.remove(s);
        }
    }

    /** @return false when the request has no response */
    private boolean handle(int api, int v, KReader r, KWriter w) {
        switch (api) {
            case Api.API_VERSIONS:
                w.int16(0);
                w.count(APIS.length);
                for (int a : APIS) {
                    w.int16(a);
                    w.int16(Api.minVersion(a));
                    w.int16(oldest ? Api.minVersion(a) : Api.maxVersion(a));
                }
                return true;
            case Api.SASL_HANDSHAKE: {
                String mech = r.string();
                w.int16("PLAIN".equals(mech) ? 0 : KafkaErrors.UNSUPPORTED_SASL_MECHANISM);
                w.strings(java.util.Collections.singletonList("PLAIN"));
                return true;
            }
            case Api.METADATA:
                metadata(v, r, w);
                return true;
            case Api.PRODUCE:
                return produce(v, r, w);
            case Api.FETCH:
                fetch(v, r, w);
                return true;
            case Api.LIST_OFFSETS:
                listOffsets(v, r, w);
                return true;
            case Api.FIND_COORDINATOR:
                r.string();
                r.int8();
                w.int32(0);
                w.int16(0);
                w.string(null);
                w.int32(0);
                w.string("127.0.0.1");
                w.int32(port());
                return true;
            case Api.JOIN_GROUP:
                join(v, r, w);
                return true;
            case Api.SYNC_GROUP:
                sync(v, r, w);
                return true;
            case Api.HEARTBEAT:
                heartbeat(v, r, w);
                return true;
            case Api.LEAVE_GROUP:
                leave(r, w);
                return true;
            case Api.OFFSET_COMMIT:
                commit(v, r, w);
                return true;
            case Api.OFFSET_FETCH:
                offsetFetch(v, r, w);
                return true;
            default:
                throw new IllegalStateException("api " + api);
        }
    }

    // ------------------------------------------------------------------ topics

    private synchronized List<Partition> topic(String t, boolean create) {
        List<Partition> l = topics.get(t);
        if (l == null && create && autoCreate) {
            createTopic(t, partitions);
            l = topics.get(t);
        }
        return l;
    }

    private void metadata(int v, KReader r, KWriter w) {
        int n = r.count();
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < n; i++) {
            names.add(r.string());
        }
        boolean create = v < 4 || r.bool();
        if (n < 0) {
            synchronized (this) {
                names.addAll(topics.keySet());
            }
        }
        if (v >= 3) {
            w.int32(0);
        }
        w.count(1);
        w.int32(0);
        w.string("127.0.0.1");
        w.int32(port());
        w.string(null);
        if (v >= 2) {
            w.string("fake-cluster");
        }
        w.int32(0);
        w.count(names.size());
        for (String t : names) {
            List<Partition> ps = topic(t, create);
            w.int16(ps == null ? KafkaErrors.UNKNOWN_TOPIC_OR_PARTITION : 0);
            w.string(t);
            w.bool(false);
            int np = ps == null ? 0 : ps.size();
            w.count(np);
            for (int p = 0; p < np; p++) {
                w.int16(0);
                w.int32(p);
                w.int32(0);
                if (v >= 7) {
                    w.int32(0);
                }
                w.int32s(java.util.Collections.singletonList(0));
                w.int32s(java.util.Collections.singletonList(0));
                if (v >= 5) {
                    w.int32s(java.util.Collections.<Integer>emptyList());
                }
            }
        }
    }

    private boolean produce(int v, KReader r, KWriter w) {
        produceRequests.incrementAndGet();
        r.string();
        int acks = r.int16();
        r.int32();
        int nt = r.count();
        Map<String, Map<Integer, long[]>> res = new LinkedHashMap<String, Map<Integer, long[]>>();
        for (int i = 0; i < nt; i++) {
            String t = r.string();
            int np = r.count();
            Map<Integer, long[]> m = new LinkedHashMap<Integer, long[]>();
            res.put(t, m);
            for (int k = 0; k < np; k++) {
                int p = r.int32();
                byte[] data = r.bytes();
                List<Partition> ps = topic(t, true);
                if (ps == null || p >= ps.size()) {
                    m.put(p, new long[] {KafkaErrors.UNKNOWN_TOPIC_OR_PARTITION, -1});
                    continue;
                }
                synchronized (this) {
                    Partition part = ps.get(p);
                    long base = part.next;
                    int pos = 0;
                    while (pos + 61 <= data.length) {
                        KReader h = new KReader(data, pos, data.length - pos);
                        h.int64();
                        int len = h.int32();
                        byte[] batch = java.util.Arrays.copyOfRange(data, pos, pos + 12 + len);
                        int lastDelta = new KReader(batch, 23, 4).int32();
                        KWriter b = new KWriter(8);
                        b.int64(part.next);
                        System.arraycopy(b.toBytes(), 0, batch, 0, 8);
                        part.index.add(new long[] {part.next, lastDelta + 1});
                        part.batches.add(batch);
                        part.next += lastDelta + 1;
                        pos += 12 + len;
                    }
                    m.put(p, new long[] {0, base});
                    notifyAll();
                }
            }
        }
        if (acks == 0) {
            return false;
        }
        w.count(res.size());
        for (Map.Entry<String, Map<Integer, long[]>> t : res.entrySet()) {
            w.string(t.getKey());
            w.count(t.getValue().size());
            for (Map.Entry<Integer, long[]> p : t.getValue().entrySet()) {
                w.int32(p.getKey());
                w.int16((int) p.getValue()[0]);
                w.int64(p.getValue()[1]);
                if (v >= 2) {
                    w.int64(-1);
                }
                if (v >= 5) {
                    w.int64(0);
                }
                if (v >= 8) {
                    w.count(0);
                    w.string(null);
                }
            }
        }
        w.int32(0);
        return true;
    }

    private void fetch(int v, KReader r, KWriter w) {
        fetchRequests.incrementAndGet();
        r.int32();
        int maxWait = r.int32();
        r.int32();
        r.int32();
        r.int8();
        if (v >= 7) {
            r.int32();
            r.int32();
        }
        Map<String, Map<Integer, long[]>> want = new LinkedHashMap<String, Map<Integer, long[]>>();
        int nt = r.count();
        for (int i = 0; i < nt; i++) {
            String t = r.string();
            int np = r.count();
            Map<Integer, long[]> m = new LinkedHashMap<Integer, long[]>();
            for (int k = 0; k < np; k++) {
                int p = r.int32();
                if (v >= 9) {
                    r.int32();
                }
                long off = r.int64();
                if (v >= 5) {
                    r.int64();
                }
                int maxBytes = r.int32();
                m.put(p, new long[] {off, maxBytes});
            }
            want.put(t, m);
        }
        long end = System.currentTimeMillis() + Math.min(maxWait, 2000);
        synchronized (this) {
            while (!hasData(want) && System.currentTimeMillis() < end) {
                try {
                    wait(Math.max(1, end - System.currentTimeMillis()));
                } catch (InterruptedException e) {
                    break;
                }
            }
            w.int32(0);
            if (v >= 7) {
                w.int16(0);
                w.int32(0);
            }
            w.count(want.size());
            for (Map.Entry<String, Map<Integer, long[]>> t : want.entrySet()) {
                w.string(t.getKey());
                w.count(t.getValue().size());
                List<Partition> ps = topics.get(t.getKey());
                for (Map.Entry<Integer, long[]> p : t.getValue().entrySet()) {
                    w.int32(p.getKey());
                    Partition part = ps == null || p.getKey() >= ps.size() ? null : ps.get(p.getKey());
                    long off = p.getValue()[0];
                    int err = part == null ? KafkaErrors.UNKNOWN_TOPIC_OR_PARTITION : off > part.next || off < 0 ? KafkaErrors.OFFSET_OUT_OF_RANGE : 0;
                    w.int16(err);
                    w.int64(part == null ? -1 : part.next);
                    w.int64(part == null ? -1 : part.next);
                    if (v >= 5) {
                        w.int64(0);
                    }
                    w.count(-1);
                    if (v >= 11) {
                        w.int32(-1);
                    }
                    if (err != 0) {
                        w.bytes(null);
                        continue;
                    }
                    KWriter data = new KWriter();
                    for (int i = 0; i < part.batches.size(); i++) {
                        long[] ix = part.index.get(i);
                        if (ix[0] + ix[1] <= off) {
                            continue;
                        }
                        if (data.position() > 0 && data.position() + part.batches.get(i).length > p.getValue()[1]) {
                            break;
                        }
                        data.raw(part.batches.get(i));
                    }
                    w.bytes(data.toBytes());
                }
            }
        }
    }

    private boolean hasData(Map<String, Map<Integer, long[]>> want) {
        for (Map.Entry<String, Map<Integer, long[]>> t : want.entrySet()) {
            List<Partition> ps = topics.get(t.getKey());
            for (Map.Entry<Integer, long[]> p : t.getValue().entrySet()) {
                if (ps == null || p.getKey() >= ps.size() || ps.get(p.getKey()).next != p.getValue()[0]) {
                    return true;
                }
            }
        }
        return false;
    }

    private void listOffsets(int v, KReader r, KWriter w) {
        r.int32();
        if (v >= 2) {
            r.int8();
        }
        int nt = r.count();
        if (v >= 2) {
            w.int32(0);
        }
        w.count(nt);
        for (int i = 0; i < nt; i++) {
            String t = r.string();
            int np = r.count();
            w.string(t);
            w.count(np);
            for (int k = 0; k < np; k++) {
                int p = r.int32();
                if (v >= 4) {
                    r.int32();
                }
                long ts = r.int64();
                List<Partition> ps = topic(t, false);
                w.int32(p);
                if (ps == null || p >= ps.size()) {
                    w.int16(KafkaErrors.UNKNOWN_TOPIC_OR_PARTITION);
                    w.int64(-1);
                    w.int64(-1);
                } else {
                    w.int16(0);
                    w.int64(-1);
                    synchronized (this) {
                        w.int64(ts == -2 ? 0 : ps.get(p).next);
                    }
                }
                if (v >= 4) {
                    w.int32(0);
                }
            }
        }
    }

    // ------------------------------------------------------------------ groups

    private void expireMembers(Group g) {
        long now = System.currentTimeMillis();
        boolean gone = false;
        for (Member m : new ArrayList<Member>(g.members.values())) {
            if (now - m.lastSeen > m.sessionTimeout) {
                g.members.remove(m.id);
                gone = true;
            }
        }
        if (gone && !g.members.isEmpty()) {
            startRebalance(g);
        }
    }

    private void startRebalance(Group g) {
        if (!g.rebalancing) {
            g.rebalancing = true;
            g.synced = false;
            g.rebalanceDeadline = System.currentTimeMillis() + 5000;      // the others heartbeat every 3 s
            for (Member m : g.members.values()) {
                m.joined = false;
            }
        }
    }

    private void join(int v, KReader r, KWriter w) {
        String gid = r.string();
        int session = r.int32();
        r.int32();
        String memberId = r.string();
        if (v >= 5) {
            r.string();
        }
        r.string();
        int np = r.count();
        String protocol = null;
        byte[] md = null;
        for (int i = 0; i < np; i++) {
            String name = r.string();
            byte[] b = r.bytes();
            if (protocol == null) {
                protocol = name;
                md = b;
            }
        }
        synchronized (this) {
            Group g = groups.computeIfAbsent(gid, k -> new Group());
            expireMembers(g);
            if (memberId.isEmpty() && v >= 4) {
                String id = "member-" + UUID.randomUUID();
                Member m = new Member(id);
                m.sessionTimeout = session;
                m.metadata = md;
                g.members.put(id, m);
                startRebalance(g);
                joinResponse(v, w, KafkaErrors.MEMBER_ID_REQUIRED, -1, "", "", id, null);
                return;
            }
            Member m = g.members.get(memberId);
            if (m == null) {
                if (!memberId.isEmpty()) {
                    joinResponse(v, w, KafkaErrors.UNKNOWN_MEMBER_ID, -1, "", "", memberId, null);
                    return;
                }
                m = new Member("member-" + UUID.randomUUID());
                g.members.put(m.id, m);
            }
            m.sessionTimeout = session;
            m.metadata = md;
            m.lastSeen = System.currentTimeMillis();
            startRebalance(g);
            m.joined = true;
            g.protocol = protocol;
            int myGeneration = g.generation;
            // wait until every member joined (or the deadline: the others are dropped)
            while (g.rebalancing && g.generation == myGeneration) {
                boolean all = true;
                for (Member x : g.members.values()) {
                    all &= x.joined;
                }
                if (all || System.currentTimeMillis() > g.rebalanceDeadline) {
                    g.members.values().removeIf(x -> !x.joined);
                    g.generation++;
                    g.rebalancing = false;
                    g.leader = g.members.keySet().iterator().next();
                    notifyAll();
                    break;
                }
                try {
                    wait(20);
                } catch (InterruptedException e) {
                    return;
                }
            }
            if (!g.members.containsKey(m.id)) {
                joinResponse(v, w, KafkaErrors.UNKNOWN_MEMBER_ID, -1, "", "", m.id, null);
                return;
            }
            joinResponse(v, w, 0, g.generation, g.protocol, g.leader, m.id, m.id.equals(g.leader) ? g : null);
        }
    }

    private static void joinResponse(int v, KWriter w, int err, int gen, String protocol, String leader, String member, Group g) {
        if (v >= 2) {
            w.int32(0);
        }
        w.int16(err);
        w.int32(gen);
        w.string(protocol);
        w.string(leader);
        w.string(member);
        if (g == null) {
            w.count(0);
            return;
        }
        w.count(g.members.size());
        for (Member m : g.members.values()) {
            w.string(m.id);
            if (v >= 5) {
                w.string(null);
            }
            w.bytes(m.metadata);
        }
    }

    private void sync(int v, KReader r, KWriter w) {
        String gid = r.string();
        int gen = r.int32();
        String memberId = r.string();
        if (v >= 3) {
            r.string();
        }
        int n = r.count();
        Map<String, byte[]> a = new HashMap<String, byte[]>();
        for (int i = 0; i < n; i++) {
            a.put(r.string(), r.bytes());
        }
        synchronized (this) {
            Group g = groups.get(gid);
            w.int32(0);
            if (g == null || !g.members.containsKey(memberId)) {
                w.int16(KafkaErrors.UNKNOWN_MEMBER_ID);
                w.bytes(new byte[0]);
                return;
            }
            if (gen != g.generation || g.rebalancing) {
                w.int16(g.rebalancing ? KafkaErrors.REBALANCE_IN_PROGRESS : KafkaErrors.ILLEGAL_GENERATION);
                w.bytes(new byte[0]);
                return;
            }
            if (memberId.equals(g.leader)) {
                for (Member m : g.members.values()) {
                    m.assignment = a.get(m.id);
                }
                g.synced = true;
                notifyAll();
            }
            long end = System.currentTimeMillis() + 3000;
            while (!g.synced && gen == g.generation && System.currentTimeMillis() < end) {
                try {
                    wait(20);
                } catch (InterruptedException e) {
                    break;
                }
            }
            if (!g.synced || gen != g.generation) {
                w.int16(KafkaErrors.REBALANCE_IN_PROGRESS);
                w.bytes(new byte[0]);
                return;
            }
            Member m = g.members.get(memberId);
            w.int16(0);
            w.bytes(m == null || m.assignment == null ? new byte[0] : m.assignment);
        }
    }

    private void heartbeat(int v, KReader r, KWriter w) {
        String gid = r.string();
        int gen = r.int32();
        String memberId = r.string();
        if (v >= 3) {
            r.string();
        }
        synchronized (this) {
            w.int32(0);
            Group g = groups.get(gid);
            if (g == null) {
                w.int16(KafkaErrors.GROUP_ID_NOT_FOUND);
                return;
            }
            expireMembers(g);
            Member m = g.members.get(memberId);
            if (m == null) {
                w.int16(KafkaErrors.UNKNOWN_MEMBER_ID);
                return;
            }
            m.lastSeen = System.currentTimeMillis();
            w.int16(g.rebalancing ? KafkaErrors.REBALANCE_IN_PROGRESS : gen != g.generation ? KafkaErrors.ILLEGAL_GENERATION : 0);
        }
    }

    private void leave(KReader r, KWriter w) {
        String gid = r.string();
        String memberId = r.string();
        synchronized (this) {
            Group g = groups.get(gid);
            if (g != null && g.members.remove(memberId) != null && !g.members.isEmpty()) {
                startRebalance(g);
            }
            w.int32(0);
            w.int16(0);
        }
    }

    private void commit(int v, KReader r, KWriter w) {
        String gid = r.string();
        int gen = r.int32();
        String memberId = r.string();
        if (v >= 7) {
            r.string();
        }
        if (v <= 4) {
            r.int64();
        }
        int nt = r.count();
        synchronized (this) {
            Group g = groups.get(gid);
            int err = g == null || !g.members.containsKey(memberId) ? KafkaErrors.UNKNOWN_MEMBER_ID
                    : gen != g.generation ? KafkaErrors.ILLEGAL_GENERATION : 0;
            if (v >= 3) {
                w.int32(0);
            }
            w.count(nt);
            for (int i = 0; i < nt; i++) {
                String t = r.string();
                int np = r.count();
                w.string(t);
                w.count(np);
                for (int k = 0; k < np; k++) {
                    int p = r.int32();
                    long off = r.int64();
                    if (v >= 6) {
                        r.int32();
                    }
                    r.string();
                    if (err == 0) {
                        g.offsets.put(t + "/" + p, off);
                    }
                    w.int32(p);
                    w.int16(err);
                }
            }
        }
    }

    private void offsetFetch(int v, KReader r, KWriter w) {
        String gid = r.string();
        int nt = r.count();
        synchronized (this) {
            Group g = groups.get(gid);
            if (v >= 3) {
                w.int32(0);
            }
            w.count(Math.max(0, nt));
            for (int i = 0; i < nt; i++) {
                String t = r.string();
                List<Integer> ps = r.int32s();
                w.string(t);
                w.count(ps.size());
                for (int p : ps) {
                    Long off = g == null ? null : g.offsets.get(t + "/" + p);
                    w.int32(p);
                    w.int64(off == null ? -1 : off);
                    if (v >= 5) {
                        w.int32(-1);
                    }
                    w.string("");
                    w.int16(0);
                }
            }
            if (v >= 2) {
                w.int16(0);
            }
        }
    }
}
