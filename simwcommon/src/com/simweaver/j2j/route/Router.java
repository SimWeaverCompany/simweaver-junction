package com.simweaver.j2j.route;

import com.simweaver.common.logging.Events;
import com.simweaver.j2j.config.J2jConfig;
import com.simweaver.j2j.config.J2jMember;
import com.simweaver.j2j.crypto.J2jCrypto;
import com.simweaver.j2j.link.Link;
import com.simweaver.j2j.link.LinkManager;
import com.simweaver.j2j.wire.ByteIn;
import com.simweaver.j2j.wire.ByteOut;
import com.simweaver.j2j.wire.Envelope;
import com.simweaver.j2j.wire.J2jWire;
import com.simweaver.j2j.wire.WireException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Routing and end-to-end delivery (design §5.3, §7):
 * <ul>
 * <li>link state: every server floods an LSA (its neighbours and costs); every server runs
 *     Dijkstra over the two-way edges and keeps a next hop per destination, changing a working
 *     route only for one at least 20 % and 5 better (hysteresis);</li>
 * <li>forwarding by destination and ttl;</li>
 * <li>sequenced messages (admin and reliable, sequence ≠ 0) are acknowledged end to end per
 *     (source, destination, stream), resent until acknowledged (also after a reroute), delivered
 *     once and in order; a sender's epoch (top 24 bits of the sequence) changes when the receiver
 *     cannot place its numbers (STREAM_RESET) or restarts;</li>
 * <li>echo requests for the path ping.</li>
 * </ul>
 */
public final class Router implements LinkManager.Listener {

    private static final Logger LOG = Logger.getLogger("com.simweaver.j2j");

    public static final int TTL = 16;
    static final int HOP_COST = 5;
    static final int LSA_LIFETIME_S = 120;
    static final long LSA_REFRESH_MS = 30_000;
    static final int EXT_SUPERSEDE = 1;               // envelope extension TLV: best-effort supersede key

    /** Receives messages addressed to this server, by type range. */
    public interface Receiver {
        void receive(String sourceId, Envelope env);
    }

    /** A route to one destination. */
    public static final class Route {
        public final String destination;
        public final String nextHop;
        public final long cost;
        public final List<String> path;               // from this server (excluded) to the destination (included)

        Route(String destination, String nextHop, long cost, List<String> path) {
            this.destination = destination;
            this.nextHop = nextHop;
            this.cost = cost;
            this.path = Collections.unmodifiableList(path);
        }
    }

    private final J2jConfig cfg;
    private final LinkManager links;
    private final long boot;
    private final String self;
    private final long selfKey;
    private final Map<Long, String> idByKey = new HashMap<Long, String>();
    private final Map<String, NetMsgs.Lsa> lsdb = new ConcurrentHashMap<String, NetMsgs.Lsa>();
    private volatile Map<String, Route> routes = Collections.emptyMap();
    private final Map<String, long[]> ownCosts = new LinkedHashMap<String, long[]>();   // advertised
    private long ownSeq;
    private long lastOwnLsa;
    private final ScheduledExecutorService worker;
    private final List<int[]> receiverRanges = new CopyOnWriteArrayList<int[]>();
    private final List<Receiver> receivers = new CopyOnWriteArrayList<Receiver>();
    private final List<ReachabilityListener> reachability = new CopyOnWriteArrayList<ReachabilityListener>();
    private final Map<String, SendStreams> send = new ConcurrentHashMap<String, SendStreams>();
    private final Map<String, Map<Long, RecvStream>> recv = new ConcurrentHashMap<String, Map<Long, RecvStream>>();
    private final Map<String, Map<Long, Long>> pendingAcks = new HashMap<String, Map<Long, Long>>();
    private final Map<Long, CompletableFuture<Double>> echoes = new ConcurrentHashMap<Long, CompletableFuture<Double>>();
    private final Map<Long, Long> echoStarts = new ConcurrentHashMap<Long, Long>();     // id -> System.nanoTime() when sent
    private final Map<String, Long> bootOf = new ConcurrentHashMap<String, Long>();
    private final AtomicLong echoIds = new AtomicLong();
    private final long reliableBufferBytes;
    private volatile boolean stopped;

    // counters
    public final AtomicLong forwarded = new AtomicLong();
    public final AtomicLong droppedNoRoute = new AtomicLong();
    public final AtomicLong droppedTtl = new AtomicLong();
    public final AtomicLong resent = new AtomicLong();
    public final AtomicLong duplicates = new AtomicLong();
    public final AtomicLong unknownTypes = new AtomicLong();

    /** Told when a destination becomes reachable or unreachable, or its route changes. */
    public interface ReachabilityListener {
        void changed(String nodeId, Route route);
    }

    public Router(J2jConfig cfg, LinkManager links, long boot) {
        this.cfg = cfg;
        this.links = links;
        this.boot = boot;
        this.self = cfg.selfId();
        this.selfKey = J2jConfig.nodeKey(self);
        for (J2jMember m : cfg.members().values()) {
            idByKey.put(m.key(), m.id());
        }
        this.reliableBufferBytes = 32L * 1024 * 1024;
        this.worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "j2j-route");
            t.setDaemon(true);
            return t;
        });
        links.addListener(this);
    }

    public void start() {
        worker.scheduleWithFixedDelay(this::tick, 200, 200, TimeUnit.MILLISECONDS);
        post(() -> originate(true));
    }

    public void stop() {
        stopped = true;
        worker.shutdownNow();
    }

    /** Runs on the routing thread; ignored once stopped. */
    private void post(Runnable r) {
        if (stopped) {
            return;
        }
        try {
            worker.execute(r);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // stopping
        }
    }

    /** Registers a receiver for message types {@code from}..{@code to} (inclusive). */
    public void register(int from, int to, Receiver r) {
        receiverRanges.add(new int[] {from, to});
        receivers.add(r);
    }

    public void addReachabilityListener(ReachabilityListener l) {
        reachability.add(l);
    }

    // ------------------------------------------------------------------ sending

    /**
     * Sends a message to another server.
     *
     * @param cls       J2jWire.CLASS_*; admin and reliable are sequenced on {@code stream} and delivered for sure
     * @param stream    1..2^32-1 for sequenced classes
     * @param supersede best effort: a newer message with the same key replaces a queued one (may be null)
     * @return false when the destination is unknown, the reliable buffer is full (back pressure),
     *         or (best effort) there is no route or the queue is full
     */
    public boolean send(String destination, int cls, int type, long stream, byte[] payload, byte[] supersede) {
        if (stopped || destination.equals(self) || cfg.member(destination) == null) {
            return false;
        }
        long dstKey = J2jConfig.nodeKey(destination);
        if (cls == J2jWire.CLASS_BEST_EFFORT) {
            byte[] ext = supersede == null ? null : new ByteOut(4 + supersede.length).tlv(EXT_SUPERSEDE, supersede).toBytes();
            Envelope env = new Envelope(cls, 0, TTL, selfKey, dstKey, stream, 0, type, ext, payload);
            return forward(destination, env, supersede == null ? null : self + "/" + destination + "/" + J2jCrypto.hex(supersede));
        }
        SendStreams ss = send.computeIfAbsent(destination, d -> new SendStreams());
        Pending p;
        synchronized (ss) {
            if (ss.bytes + payload.length > reliableBufferBytes) {
                return false;
            }
            SendStream s = ss.streams.computeIfAbsent(stream, k -> new SendStream(newEpoch()));
            long seq = NetMsgs.sequence(s.epoch, ++s.counter);
            p = new Pending(new Envelope(cls, 0, TTL, selfKey, dstKey, stream, seq, type, null, payload));
            s.pending.put(NetMsgs.counter(seq), p);
            ss.bytes += payload.length;
        }
        transmit(destination, p);
        return true;
    }

    /** Sends an unsequenced message (no acknowledgement, no retransmission). */
    public boolean sendUnsequenced(String destination, int cls, int type, byte[] payload) {
        Envelope env = new Envelope(cls, 0, TTL, selfKey, J2jConfig.nodeKey(destination), 0, 0, type, null, payload);
        return forward(destination, env, null);
    }

    private void transmit(String destination, Pending p) {
        p.lastSent = System.currentTimeMillis();
        p.attempts++;
        forward(destination, p.env, null);
    }

    private boolean forward(String destination, Envelope env, String supersedeKey) {
        Route r = routes.get(destination);
        Link l = r == null ? links.link(destination) : links.link(r.nextHop);
        if (l == null) {
            droppedNoRoute.incrementAndGet();
            return false;
        }
        return l.send(env, supersedeKey);
    }

    private static int newEpoch() {
        int e;
        do {
            e = J2jCrypto.RANDOM.nextInt() & 0xFFFFFF;
        } while (e == 0);
        return e;
    }

    // ------------------------------------------------------------------ receiving

    @Override
    public void message(Link link, Envelope env) {
        if (env.type == NetMsgs.LSA && env.destination == 0) {
            byte[] p = env.payload;
            post(() -> onLsa(link.peerId(), p));
            return;
        }
        if (env.destination != selfKey) {
            String dst = idByKey.get(env.destination);
            if (dst == null) {
                droppedNoRoute.incrementAndGet();
                return;
            }
            if (env.ttl <= 1) {
                droppedTtl.incrementAndGet();
                return;
            }
            String supersede = null;
            if (env.cls == J2jWire.CLASS_BEST_EFFORT && env.ext.length > 0) {
                byte[] k = extTlv(env.ext, EXT_SUPERSEDE);
                supersede = k == null ? null : idByKey.get(env.source) + "/" + dst + "/" + J2jCrypto.hex(k);
            }
            if (forward(dst, env.withTtl(env.ttl - 1), supersede)) {
                forwarded.incrementAndGet();
            }
            return;
        }
        String src = idByKey.get(env.source);
        if (src == null) {
            return;
        }
        if (env.sequence == 0) {
            local(src, env);
            return;
        }
        receiveSequenced(src, env);
    }

    private static byte[] extTlv(byte[] ext, int tag) {
        try {
            return new ByteIn(ext).tlvs(tag).get(tag);
        } catch (WireException e) {
            return null;
        }
    }

    private void receiveSequenced(String src, Envelope env) {
        Map<Long, RecvStream> m = recv.computeIfAbsent(src, s -> new ConcurrentHashMap<Long, RecvStream>());
        RecvStream rs = m.computeIfAbsent(env.stream, s -> new RecvStream());
        List<Envelope> deliver = new ArrayList<Envelope>();
        long ack;
        boolean reset = false;
        synchronized (rs) {
            int epoch = NetMsgs.epoch(env.sequence);
            long c = NetMsgs.counter(env.sequence);
            if (epoch != rs.epoch) {
                if (c == 1) {
                    if (rs.epoch != 0) {
                        rs.retired.add(rs.epoch);
                    }
                    rs.epoch = epoch;
                    rs.expected = 1;
                    rs.buffer.clear();
                    Map<Long, Envelope> early = rs.otherEpochs.remove(epoch);
                    if (early != null) {
                        rs.buffer.putAll(early);
                    }
                    rs.otherEpochs.clear();
                } else if (rs.retired.contains(epoch)) {
                    duplicates.incrementAndGet();
                    return;
                } else {
                    Map<Long, Envelope> o = rs.otherEpochs.computeIfAbsent(epoch, k -> new TreeMap<Long, Envelope>());
                    if (o.size() < 20000) {
                        o.put(c, env);
                    }
                    long now = System.currentTimeMillis();
                    if (rs.unplacedSince == 0) {
                        rs.unplacedSince = now;
                    }
                    if (now - rs.unplacedSince > 3000 && now - rs.lastReset > 1000) {
                        rs.lastReset = now;
                        reset = true;
                    }
                    if (!reset) {
                        return;
                    }
                }
            }
            if (!reset) {
                rs.unplacedSince = 0;
                if (c < rs.expected) {
                    duplicates.incrementAndGet();
                } else if (c == rs.expected) {
                    deliver.add(env);
                    rs.expected++;
                    Envelope next;
                    while ((next = rs.buffer.remove(rs.expected)) != null) {
                        deliver.add(next);
                        rs.expected++;
                    }
                } else if (rs.buffer.size() < 20000) {
                    rs.buffer.put(c, env);
                }
            }
            ack = rs.expected > 1 ? NetMsgs.sequence(rs.epoch, rs.expected - 1) : 0;
        }
        if (reset) {
            sendUnsequenced(src, J2jWire.CLASS_ADMIN, NetMsgs.STREAM_RESET, NetMsgs.streamReset(env.stream, env.sequence));
            return;
        }
        for (Envelope e : deliver) {
            local(src, e);
        }
        if (ack != 0) {
            synchronized (pendingAcks) {
                pendingAcks.computeIfAbsent(src, s -> new LinkedHashMap<Long, Long>()).put(env.stream, ack);
            }
        }
    }

    private void local(String src, Envelope env) {
        switch (env.type) {
            case NetMsgs.ACK:
                onAck(src, env.payload);
                return;
            case NetMsgs.STREAM_RESET:
                onReset(src, env.payload);
                return;
            case NetMsgs.ECHO_REQUEST:
                if (env.payload.length >= 12) {
                    sendUnsequenced(src, J2jWire.CLASS_ADMIN, NetMsgs.ECHO_REPLY, NetMsgs.echoReply(env.payload, System.currentTimeMillis()));
                }
                return;
            case NetMsgs.ECHO_REPLY:
                try {
                    ByteIn in = new ByteIn(env.payload);
                    long id = in.u32();
                    long sent = in.u64();
                    CompletableFuture<Double> f = echoes.remove(id);
                    Long start = echoStarts.remove(id);
                    if (f != null) {
                        // the wire carries UTC ms (design); the local clock measures finer
                        f.complete(start != null ? (System.nanoTime() - start) / 1e6 : (double) (System.currentTimeMillis() - sent));
                    }
                } catch (WireException ignored) {
                    // malformed: ignored
                }
                return;
            default:
                for (int i = 0; i < receivers.size(); i++) {
                    int[] r = receiverRanges.get(i);
                    if (env.type >= r[0] && env.type <= r[1]) {
                        receivers.get(i).receive(src, env);
                        return;
                    }
                }
                unknownTypes.incrementAndGet();          // design §6.3: skipped (must-understand: reported in a later revision)
        }
    }

    private void onAck(String src, byte[] payload) {
        SendStreams ss = send.get(src);
        if (ss == null) {
            return;
        }
        try {
            for (long[] e : NetMsgs.ackEntries(payload)) {
                synchronized (ss) {
                    SendStream s = ss.streams.get(e[0]);
                    if (s == null || NetMsgs.epoch(e[1]) != s.epoch) {
                        continue;
                    }
                    long upTo = NetMsgs.counter(e[1]);
                    Iterator<Map.Entry<Long, Pending>> i = s.pending.entrySet().iterator();
                    while (i.hasNext()) {
                        Map.Entry<Long, Pending> p = i.next();
                        if (p.getKey() > upTo) {
                            break;
                        }
                        ss.bytes -= p.getValue().env.payload.length;
                        i.remove();
                    }
                }
            }
        } catch (WireException ignored) {
            // malformed ACK: ignored
        }
    }

    /** The receiver cannot place our numbers (it restarted): renumber what is pending in a new epoch. */
    private void onReset(String src, byte[] payload) {
        try {
            ByteIn in = new ByteIn(payload);
            long stream = in.u32();
            long seq = in.u64();
            SendStreams ss = send.get(src);
            if (ss == null) {
                return;
            }
            List<Pending> again;
            synchronized (ss) {
                SendStream s = ss.streams.get(stream);
                if (s == null || NetMsgs.epoch(seq) != s.epoch) {
                    return;
                }
                again = renumber(s);
            }
            for (Pending p : again) {
                transmit(src, p);
            }
        } catch (WireException ignored) {
            // malformed: ignored
        }
    }

    private List<Pending> renumber(SendStream s) {
        s.epoch = newEpoch();
        s.counter = 0;
        TreeMap<Long, Pending> old = s.pending;
        s.pending = new TreeMap<Long, Pending>();
        List<Pending> r = new ArrayList<Pending>();
        for (Pending p : old.values()) {
            Envelope e = p.env;
            long seq = NetMsgs.sequence(s.epoch, ++s.counter);
            Pending q = new Pending(new Envelope(e.cls, e.flags, TTL, e.source, e.destination, e.stream, seq, e.type, e.ext, e.payload));
            s.pending.put(NetMsgs.counter(seq), q);
            r.add(q);
        }
        return r;
    }

    // ------------------------------------------------------------------ link events

    @Override
    public void linkUp(Link link) {
        post(() -> {
            Long before = bootOf.put(link.peerId(), link.peerBoot());
            if (before != null && before != link.peerBoot()) {
                peerRestarted(link.peerId());
            }
            for (NetMsgs.Lsa l : lsdb.values()) {                // database sync for the new neighbour
                link.send(lsaEnvelope(l), null);
            }
            originate(true);
        });
    }

    @Override
    public void linkDown(Link link, String reason) {
        post(() -> originate(true));
    }

    /** A destination restarted: its receive state is gone, so our streams to it start a new epoch. */
    private void peerRestarted(String id) {
        recv.remove(id);
        SendStreams ss = send.get(id);
        if (ss == null) {
            return;
        }
        List<Pending> again = new ArrayList<Pending>();
        synchronized (ss) {
            for (SendStream s : ss.streams.values()) {
                again.addAll(renumber(s));
            }
        }
        for (Pending p : again) {
            transmit(id, p);
        }
    }

    // ------------------------------------------------------------------ link state

    private Envelope lsaEnvelope(NetMsgs.Lsa l) {
        return new Envelope(J2jWire.CLASS_ADMIN, 0, 1, selfKey, 0, 0, 0, NetMsgs.LSA, null, l.encode());
    }

    /** Builds this server's LSA; floods it when it changed (or {@code force}) or is due for refresh. */
    private void originate(boolean force) {
        if (stopped) {
            return;
        }
        Map<String, long[]> nb = new LinkedHashMap<String, long[]>();
        boolean changed = false;
        for (Link l : links.links()) {
            double rtt = l.rttMs();
            long cost = (rtt < 0 ? 100 : Math.max(1, Math.round(rtt))) + HOP_COST;
            long[] old = ownCosts.get(l.peerId());
            if (old != null && Math.abs(old[0] - cost) <= Math.max(3, old[0] / 5)) {
                cost = old[0];                               // hysteresis: small changes are not advertised
            } else {
                changed = true;
            }
            nb.put(l.peerId(), new long[] {cost, rtt < 0 ? 0 : Math.round(rtt * 1000)});
        }
        changed |= !nb.keySet().equals(ownCosts.keySet());
        long now = System.currentTimeMillis();
        if (!force && !changed && now - lastOwnLsa < LSA_REFRESH_MS) {
            return;
        }
        ownCosts.clear();
        ownCosts.putAll(nb);
        ownSeq = Math.max(ownSeq + 1, now);
        lastOwnLsa = now;
        NetMsgs.Lsa lsa = new NetMsgs.Lsa(ownSeq, boot, LSA_LIFETIME_S, self, nb);
        lsa.receivedAt = now;
        lsdb.put(self, lsa);
        flood(lsa, null);
        recompute();
    }

    private void flood(NetMsgs.Lsa lsa, String except) {
        Envelope env = lsaEnvelope(lsa);
        for (Link l : links.links()) {
            if (!l.peerId().equals(except)) {
                l.send(env, null);
            }
        }
    }

    private void onLsa(String from, byte[] payload) {
        NetMsgs.Lsa lsa;
        try {
            lsa = NetMsgs.Lsa.decode(payload);
        } catch (WireException e) {
            return;
        }
        if (cfg.member(lsa.origin) == null) {
            return;                                           // not a member: ignored
        }
        if (lsa.origin.equals(self)) {
            if (lsa.boot != boot && lsa.sequence >= ownSeq) {
                ownSeq = lsa.sequence;                        // an older run's LSA still around: outdo it
                originate(true);
            }
            return;
        }
        NetMsgs.Lsa old = lsdb.get(lsa.origin);
        if (old != null && lsa.sequence <= old.sequence) {
            return;
        }
        lsa.receivedAt = System.currentTimeMillis();
        lsdb.put(lsa.origin, lsa);
        Long before = bootOf.put(lsa.origin, lsa.boot);
        if (before != null && before != lsa.boot) {
            peerRestarted(lsa.origin);
        }
        flood(lsa, from);
        recompute();
    }

    private void tick() {
        try {
            long now = System.currentTimeMillis();
            boolean expired = false;
            for (Iterator<NetMsgs.Lsa> i = lsdb.values().iterator(); i.hasNext(); ) {
                NetMsgs.Lsa l = i.next();
                if (!l.origin.equals(self) && now - l.receivedAt > l.lifetimeSeconds * 1000L) {
                    i.remove();
                    expired = true;
                }
            }
            originate(false);
            if (expired) {
                recompute();
            }
            retransmit(now);
            flushAcks();
            for (Iterator<Map.Entry<Long, CompletableFuture<Double>>> i = echoes.entrySet().iterator(); i.hasNext(); ) {
                Map.Entry<Long, CompletableFuture<Double>> e = i.next();
                if (e.getValue().isDone()) {
                    i.remove();
                    echoStarts.remove(e.getKey());
                }
            }
        } catch (RuntimeException e) {
            LOG.log(java.util.logging.Level.WARNING, "J2J routing tick failed", e);
        }
    }

    /** Dijkstra over two-way edges; this server's own edges come from its live links. */
    private void recompute() {
        Map<String, Map<String, Long>> graph = new HashMap<String, Map<String, Long>>();
        Map<String, Long> mine = new HashMap<String, Long>();
        for (Link l : links.links()) {
            long[] c = ownCosts.get(l.peerId());
            double rtt = l.rttMs();
            mine.put(l.peerId(), c != null ? c[0] : (rtt < 0 ? 100 : Math.round(rtt)) + HOP_COST);
        }
        graph.put(self, mine);
        for (NetMsgs.Lsa l : lsdb.values()) {
            if (l.origin.equals(self)) {
                continue;
            }
            Map<String, Long> e = new HashMap<String, Long>();
            for (Map.Entry<String, long[]> n : l.neighbours.entrySet()) {
                String v = n.getKey();
                if (cfg.member(v) == null) {
                    continue;
                }
                boolean twoWay = v.equals(self) ? mine.containsKey(l.origin)
                        : lsdb.containsKey(v) && lsdb.get(v).neighbours.containsKey(l.origin);
                if (twoWay) {
                    e.put(v, Math.max(1, n.getValue()[0]));
                }
            }
            graph.put(l.origin, e);
        }
        Map<String, Long> dist = new HashMap<String, Long>();
        Map<String, String> prev = new HashMap<String, String>();
        PriorityQueue<Object[]> q = new PriorityQueue<Object[]>((a, b) -> Long.compare((Long) a[1], (Long) b[1]));
        dist.put(self, 0L);
        q.add(new Object[] {self, 0L});
        Set<String> done = new HashSet<String>();
        while (!q.isEmpty()) {
            Object[] top = q.poll();
            String u = (String) top[0];
            if (!done.add(u)) {
                continue;
            }
            Map<String, Long> e = graph.get(u);
            if (e == null) {
                continue;
            }
            for (Map.Entry<String, Long> n : e.entrySet()) {
                long nd = dist.get(u) + n.getValue();
                Long cur = dist.get(n.getKey());
                if (cur == null || nd < cur || (nd == cur && u.compareTo(prev.get(n.getKey())) < 0)) {
                    dist.put(n.getKey(), nd);
                    prev.put(n.getKey(), u);
                    q.add(new Object[] {n.getKey(), nd});
                }
            }
        }
        Map<String, Route> old = routes;
        Map<String, Route> next = new LinkedHashMap<String, Route>();
        for (String dst : cfg.members().keySet()) {
            if (dst.equals(self) || !dist.containsKey(dst)) {
                continue;
            }
            List<String> path = new ArrayList<String>();
            for (String v = dst; !v.equals(self); v = prev.get(v)) {
                path.add(0, v);
            }
            Route best = new Route(dst, path.get(0), dist.get(dst), path);
            Route cur = old.get(dst);
            if (cur != null && !cur.nextHop.equals(best.nextHop) && valid(cur, graph)) {
                long curCost = cost(cur, graph);
                boolean muchBetter = best.cost < curCost * 0.8 && curCost - best.cost >= 5;
                boolean shorter = best.path.size() < cur.path.size() && best.cost <= curCost;    // fewer hops, no dearer
                if (!muchBetter && !shorter) {
                    best = new Route(dst, cur.nextHop, curCost, cur.path);      // hysteresis: keep the working route
                }
            }
            next.put(dst, best);
        }
        routes = Collections.unmodifiableMap(next);
        for (String dst : cfg.members().keySet()) {
            if (dst.equals(self)) {
                continue;
            }
            Route a = old.get(dst);
            Route b = next.get(dst);
            if (a == null && b == null) {
                continue;
            }
            if (a == null || b == null || !a.nextHop.equals(b.nextHop) || !a.path.equals(b.path)) {
                routeChanged(dst, a, b);
            }
        }
    }

    private boolean valid(Route r, Map<String, Map<String, Long>> g) {
        return cost(r, g) >= 0;
    }

    /** @return the route's cost in graph {@code g}, or -1 when one of its edges is gone */
    private long cost(Route r, Map<String, Map<String, Long>> g) {
        long c = 0;
        String u = self;
        for (String v : r.path) {
            Map<String, Long> e = g.get(u);
            Long w = e == null ? null : e.get(v);
            if (w == null) {
                return -1;
            }
            c += w;
            u = v;
        }
        return c;
    }

    private void routeChanged(String dst, Route a, Route b) {
        boolean direct = b != null && b.path.size() == 1;
        if (a == null && b != null && !direct) {
            Events.notice(LOG, "j2j.node.reachable", "J2J: " + dst + " reachable via " + b.nextHop + " (" + b.path.size() + " hops)",
                    "member", dst, "via", b.nextHop, "hops", b.path.size(), "by", "j2j");
        } else if (a != null && b == null) {
            Events.warning(LOG, "j2j.node.unreachable", "J2J: " + dst + " unreachable: no path", "member", dst, "by", "j2j");
        } else if (a != null && b != null && !a.nextHop.equals(b.nextHop)) {
            Events.notice(LOG, "j2j.route.changed", "J2J: " + dst + " now via " + b.nextHop + " (" + b.path.size() + " hops, cost " + b.cost + ")",
                    "member", dst, "via", b.nextHop, "hops", b.path.size(), "cost", b.cost, "by", "j2j");
        }
        if (b != null) {
            resendAll(dst);                                   // a new way: what was in flight on the old one goes again
        }
        for (ReachabilityListener l : reachability) {
            l.changed(dst, b);
        }
    }

    // ------------------------------------------------------------------ retransmission and acks

    private void retransmit(long now) {
        for (Map.Entry<String, SendStreams> e : send.entrySet()) {
            String dst = e.getKey();
            if (!routes.containsKey(dst) && links.link(dst) == null) {
                continue;
            }
            List<Pending> due = new ArrayList<Pending>();
            Route r = routes.get(dst);
            long rto = Math.max(1000, (r == null ? 200 : r.cost) * 4);
            synchronized (e.getValue()) {
                for (SendStream s : e.getValue().streams.values()) {
                    for (Pending p : s.pending.values()) {
                        if (now - p.lastSent > Math.min(30_000, rto << Math.min(5, p.attempts - 1))) {
                            due.add(p);
                        }
                    }
                }
            }
            for (Pending p : due) {
                resent.incrementAndGet();
                transmit(dst, p);
            }
        }
    }

    private void resendAll(String dst) {
        SendStreams ss = send.get(dst);
        if (ss == null) {
            return;
        }
        List<Pending> all = new ArrayList<Pending>();
        synchronized (ss) {
            for (SendStream s : ss.streams.values()) {
                all.addAll(s.pending.values());
            }
        }
        for (Pending p : all) {
            resent.incrementAndGet();
            transmit(dst, p);
        }
    }

    private void flushAcks() {
        Map<String, Map<Long, Long>> acks;
        synchronized (pendingAcks) {
            if (pendingAcks.isEmpty()) {
                return;
            }
            acks = new HashMap<String, Map<Long, Long>>(pendingAcks);
            pendingAcks.clear();
        }
        for (Map.Entry<String, Map<Long, Long>> e : acks.entrySet()) {
            sendUnsequenced(e.getKey(), J2jWire.CLASS_ADMIN, NetMsgs.ACK, NetMsgs.ack(e.getValue()));
        }
    }

    // ------------------------------------------------------------------ status

    /** @return the current routes, by destination */
    public Map<String, Route> routes() {
        return routes;
    }

    public Route route(String destination) {
        return routes.get(destination);
    }

    /** @return the link-state database: origin -> LSA (this server's included) */
    public Map<String, NetMsgs.Lsa> lsdb() {
        return Collections.unmodifiableMap(lsdb);
    }

    /** @return {pending messages, pending octets} of the reliable buffers towards a destination */
    public long[] pending(String destination) {
        SendStreams ss = send.get(destination);
        if (ss == null) {
            return new long[] {0, 0};
        }
        synchronized (ss) {
            long n = 0;
            for (SendStream s : ss.streams.values()) {
                n += s.pending.size();
            }
            return new long[] {n, ss.bytes};
        }
    }

    /** Measures the round trip to a server over its route; completes with the RTT in ms, or exceptionally. */
    public CompletableFuture<Double> echo(String destination, long timeoutMs) {
        CompletableFuture<Double> f = new CompletableFuture<Double>();
        long id = echoIds.incrementAndGet() & 0xFFFFFFFFL;
        echoes.put(id, f);
        echoStarts.put(id, System.nanoTime());
        if (!sendUnsequenced(destination, J2jWire.CLASS_ADMIN, NetMsgs.ECHO_REQUEST, NetMsgs.echoRequest(id, System.currentTimeMillis()))) {
            echoes.remove(id);
            echoStarts.remove(id);
            f.completeExceptionally(new IllegalStateException("UNREACHABLE"));
            return f;
        }
        worker.schedule(() -> {
            echoStarts.remove(id);
            if (echoes.remove(id) != null) {
                f.completeExceptionally(new java.util.concurrent.TimeoutException("PING_TIMEOUT"));
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);
        return f;
    }

    // ------------------------------------------------------------------ state classes

    static final class Pending {
        final Envelope env;
        long lastSent;
        int attempts;

        Pending(Envelope env) {
            this.env = env;
        }
    }

    static final class SendStream {
        int epoch;
        long counter;
        TreeMap<Long, Pending> pending = new TreeMap<Long, Pending>();

        SendStream(int epoch) {
            this.epoch = epoch;
        }
    }

    static final class SendStreams {
        final Map<Long, SendStream> streams = new HashMap<Long, SendStream>();
        long bytes;
    }

    static final class RecvStream {
        int epoch;
        long expected = 1;
        final TreeMap<Long, Envelope> buffer = new TreeMap<Long, Envelope>();
        final Map<Integer, Map<Long, Envelope>> otherEpochs = new HashMap<Integer, Map<Long, Envelope>>();
        final Set<Integer> retired = new HashSet<Integer>();
        long unplacedSince;
        long lastReset;
    }
}
