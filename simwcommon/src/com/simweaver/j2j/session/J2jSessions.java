package com.simweaver.j2j.session;

import com.simweaver.common.logging.Events;
import com.simweaver.j2j.J2jNode;
import com.simweaver.j2j.api.J2jViews;
import com.simweaver.j2j.config.J2jConfig;
import com.simweaver.j2j.crypto.J2jCrypto;
import com.simweaver.j2j.route.Router;
import com.simweaver.j2j.wire.ByteIn;
import com.simweaver.j2j.wire.ByteOut;
import com.simweaver.j2j.wire.Envelope;
import com.simweaver.j2j.wire.J2jWire;
import com.simweaver.j2j.wire.WireException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * The sessions of the J2J network (design §9): gateways on different servers sharing traffic.
 * Each gateway joins with a {@code j2j} endpoint; this class is the server's side of all of them.
 * Two kinds, never mixed in one session:
 * <ul>
 * <li><b>PDU sessions</b> (§9.6): DIS PDUs as they are, for the PDU relay;</li>
 * <li><b>object sessions</b> (§9.1–9.5): objects and interactions of an HLA object model (RPR, NETN,
 * Talk …), for the IEM and Talk routers. Ids on the wire are 32-bit hashes of the qualified names,
 * so the members only have to agree on the model (its fingerprint).</li>
 * </ul>
 *
 * <p>It outlives the J2J node: {@link #attach} binds it to a running node (again after a reload),
 * {@link #detach} lets go. The members stay joined meanwhile and simply reach nobody.
 *
 * <p>The messages are described in the wire protocol document, §8 (PDU sessions) and §9 (object
 * sessions).
 */
public final class J2jSessions implements J2jViews.Usage {

    private static final Logger LOG = Logger.getLogger("com.simweaver.j2j");

    public static final int SESSION_STATE = 0x0100;
    public static final int SESSION_PDU = 0x0110;

    public static final int OBJ_CREATE = 0x0200;
    public static final int OBJ_UPDATE = 0x0201;
    public static final int OBJ_DELETE = 0x0202;
    public static final int OBJ_INTERACTION = 0x0203;
    public static final int OBJ_PROVIDE = 0x0204;
    public static final int OBJ_SNAPSHOT = 0x0205;

    static final int T_SESSION = 1;
    static final int T_MEMBER = 2;
    static final int T_GATEWAY = 3;
    static final int T_INSTANCE = 4;
    static final int T_ENDPOINT = 5;
    static final int T_KIND = 6;
    static final int T_MODEL = 7;
    static final int T_SINCE = 8;

    /** Session kinds (TLV KIND). */
    public static final int KIND_PDU = 1;
    public static final int KIND_OBJECTS = 2;

    private static final long REFRESH_MS = 15_000;
    private static final long LOOP_WINDOW_MS = 10_000;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    /** Receives the PDUs of a PDU session for one local member. */
    public interface Delivery {
        /** @param pdu one DIS PDU as the sender put it into the session; the receiver's own copy */
        void pdu(byte[] pdu);
    }

    /** A member of a session, as another member sees it. */
    public static final class Peer {
        public final String node;
        public final long key;
        public final String gateway;
        public final String endpoint;

        Peer(String node, long key, String gateway, String endpoint) {
            this.node = node;
            this.key = key;
            this.gateway = gateway;
            this.endpoint = endpoint;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Peer && ((Peer) o).key == key && ((Peer) o).node.equals(node);
        }

        @Override
        public int hashCode() {
            return node.hashCode() * 31 + Long.hashCode(key);
        }

        @Override
        public String toString() {
            return node + "/" + gateway;
        }
    }

    /**
     * Receives the traffic of an object session for one local member. Called on the J2J worker
     * threads (or the sender's thread for members on the same server); implementations only queue.
     * Ids are the session's wire ids (hashes of the qualified names).
     */
    public interface ObjectDelivery {
        /** A member's object, new to this member (or again: a snapshot); with the values it has. */
        void created(Peer from, long object, int objectClass, String name, int[] attributes, byte[][] values);

        void updated(Peer from, long object, int[] attributes, byte[][] values);

        void deleted(Peer from, long object);

        void interaction(Peer from, int interactionClass, int[] parameters, byte[][] values);

        /** Another member asks this one (the owner) for attribute values of one of its objects. */
        void provideRequested(Peer from, long object, int[] attributes);

        /** A member (new, or back) wants this one's objects: send them with {@link ObjectMember#createTo}. */
        void snapshotRequested(String node);

        /** A member left, was lost, or restarted: its objects are gone. */
        void peerGone(Peer peer);
    }

    private final long lostMillis;
    private final long boot = J2jCrypto.RANDOM.nextLong() & Long.MAX_VALUE;
    private final AtomicLong counter = new AtomicLong();
    private final Map<Long, BaseMember> locals = new ConcurrentHashMap<Long, BaseMember>();         // member key -> local member
    private final Map<String, NodeState> remote = new ConcurrentHashMap<String, NodeState>();        // node id -> its last state
    private final ScheduledExecutorService timer;
    private volatile J2jNode node;
    private volatile Router router;
    private volatile ScheduledFuture<?> pendingAnnounce;
    private volatile boolean closed;

    /** @param lostMillis a server not reachable for this long has its members lost ({@code j2j.session.lostseconds}) */
    public J2jSessions(long lostMillis) {
        this.lostMillis = Math.max(1000, lostMillis);
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "j2j-sessions");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
        timer.scheduleWithFixedDelay(() -> announce(null), REFRESH_MS, REFRESH_MS, TimeUnit.MILLISECONDS);
    }

    /** @return why {@code name} cannot be a session name, or null */
    public static String nameProblem(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "no session name";
        }
        return NAME.matcher(name).matches() ? null : "session name '" + name + "' is not 1-64 of letters, digits, '.', '_' and '-'";
    }

    /** @return the 64-bit key of a session name (wire protocol §6: as a node key) */
    public static long sessionKey(String name) {
        return J2jConfig.nodeKey(name);
    }

    /** @return the wire id of a qualified class, attribute or parameter name: the first 4 octets of its SHA-256 */
    public static int wireId(String qualifiedName) {
        byte[] h = J2jCrypto.sha256(qualifiedName.getBytes(StandardCharsets.UTF_8));
        return ((h[0] & 0xFF) << 24) | ((h[1] & 0xFF) << 16) | ((h[2] & 0xFF) << 8) | (h[3] & 0xFF);
    }

    /** @return "pdu", "objects" or "unknown" */
    public static String kindName(int kind) {
        return kind == KIND_PDU ? "pdu" : kind == KIND_OBJECTS ? "objects" : "unknown";
    }

    // ------------------------------------------------------------------ the node

    /** Binds to a running node (on start and after each reload). */
    public synchronized void attach(J2jNode n) {
        detach();
        if (n == null || !n.isRunning()) {
            return;
        }
        Router r = n.router();
        this.node = n;
        this.router = r;
        r.register(SESSION_STATE, 0x02FF, (src, env) -> {
            if (router == r) {
                receive(src, env);
            }
        });
        r.addReachabilityListener((id, route) -> {
            if (router == r && route != null) {
                post(() -> announce(id));
            }
        });
        announceSoon();
    }

    /** Lets go of the node (stop, or before a reload). Remote members are forgotten. */
    public void detach() {
        List<RemoteMember> gone = new ArrayList<RemoteMember>();
        synchronized (this) {
            node = null;
            router = null;
            for (NodeState ns : remote.values()) {
                gone.addAll(ns.members);
            }
            remote.clear();
        }
        for (RemoteMember m : gone) {
            peerGone(m);
        }
    }

    /** Stops for good. */
    public void close() {
        closed = true;
        detach();
        timer.shutdownNow();
    }

    private void post(Runnable r) {
        try {
            timer.execute(r);
        } catch (RejectedExecutionException ignored) {
            // closed
        }
    }

    private String selfId() {
        J2jNode n = node;
        return n == null ? "" : n.config().selfId();
    }

    // ------------------------------------------------------------------ local members

    /** What every local member has. */
    public abstract class BaseMember {
        final long key = J2jCrypto.RANDOM.nextLong();
        final int kind;
        final String session;
        final long sessionKey;
        final String gateway;
        final String instance;
        final String endpoint;
        final String model;
        final boolean reliableOnly;
        final long since = System.currentTimeMillis();
        final int stream;
        public final AtomicLong sent = new AtomicLong();
        public final AtomicLong received = new AtomicLong();
        public final AtomicLong notSent = new AtomicLong();
        public final AtomicLong copies = new AtomicLong();
        volatile boolean left;

        BaseMember(int kind, String session, String gateway, String instance, String endpoint, String model, boolean reliableOnly) {
            String p = nameProblem(session);
            if (p != null) {
                throw new IllegalArgumentException(p);
            }
            this.kind = kind;
            this.session = session;
            this.sessionKey = sessionKey(session);
            this.gateway = gateway;
            this.instance = instance == null ? "" : instance;
            this.endpoint = endpoint;
            this.model = model;
            this.reliableOnly = reliableOnly;
            this.stream = 2 + (int) ((sessionKey >>> 34) & 0x3FFFFFFF);         // 1 is the admin stream
        }

        public String session() {
            return session;
        }

        public String model() {
            return model;
        }

        /** @return true when {@code m} is of the same session, kind and model */
        boolean matches(int k, long sk, String mdl) {
            return kind == k && sessionKey == sk && model.equals(mdl);
        }

        /** Leaves the session. */
        public void leave() {
            if (left) {
                return;
            }
            left = true;
            locals.remove(key);
            Events.notice(LOG, "j2j.session.left", "J2J session " + session + ": " + gateway + " / " + endpoint + " left here",
                    "session", session, "gateway", gateway, "endpoint", endpoint);
            Peer me = new Peer(selfId(), key, gateway, endpoint);
            for (BaseMember o : locals.values()) {
                if (o instanceof ObjectMember && o.matches(kind, sessionKey, model)) {
                    ((ObjectMember) o).gone(me);
                }
            }
            announceSoon();
        }

        /** @return the other members of the session: {node, gateway, instance, endpoint, state, model …} */
        public List<Map<String, Object>> peers() {
            List<Map<String, Object>> l = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> x : membersOf(session)) {
                if (!Boolean.TRUE.equals(x.get("local")) || !Long.valueOf(key).equals(x.get("key"))) {
                    Map<String, Object> y = new LinkedHashMap<String, Object>(x);
                    y.remove("key");
                    l.add(y);
                }
            }
            return l;
        }

        /** The common start of every session message: session key | member key | flags. */
        ByteOut head(int capacity) {
            return new ByteOut(17 + capacity).u64(sessionKey).u64(key).u8(0);
        }

        /** Sends to every server with a member of the same model. */
        void toNodes(byte[] payload, int type, boolean bestEffort, byte[] supersede) {
            Router r = router;
            if (r == null) {
                return;
            }
            for (String nodeId : nodesWith(kind, sessionKey, model)) {
                to(r, nodeId, payload, type, bestEffort, supersede);
            }
        }

        void to(Router r, String nodeId, byte[] payload, int type, boolean bestEffort, byte[] supersede) {
            boolean ok = bestEffort ? r.send(nodeId, J2jWire.CLASS_BEST_EFFORT, type, 0, payload, supersede)
                    : r.send(nodeId, J2jWire.CLASS_RELIABLE, type, stream, payload, null);
            if (ok) {
                copies.incrementAndGet();
            } else {
                notSent.incrementAndGet();
            }
        }
    }

    /**
     * Joins a PDU session.
     *
     * @param session      the session name (see {@link #nameProblem})
     * @param gateway      the gateway's runtime name
     * @param instance     its instance
     * @param endpoint     the endpoint id
     * @param model        what the members must agree on, e.g. "DIS 7, exercise 1"
     * @param reliableOnly every PDU reliable (no best effort)
     * @param delivery     takes the session's PDUs
     */
    public Member join(String session, String gateway, String instance, String endpoint, String model, boolean reliableOnly,
                       Delivery delivery) {
        Member m = new Member(session, gateway, instance, endpoint, model, reliableOnly, delivery);
        joined(m);
        return m;
    }

    /**
     * Joins an object session.
     *
     * @param model    what the members must agree on: the model's fingerprint and a description
     * @param delivery takes the session's traffic
     */
    public ObjectMember joinObjects(String session, String gateway, String instance, String endpoint, String model,
                                    boolean reliableOnly, ObjectDelivery delivery) {
        ObjectMember m = new ObjectMember(session, gateway, instance, endpoint, model, reliableOnly, delivery);
        joined(m);
        // the members of this server in the same session: swap objects at once
        for (BaseMember o : locals.values()) {
            if (o != m && o instanceof ObjectMember && o.matches(m.kind, m.sessionKey, m.model)) {
                ((ObjectMember) o).delivery.snapshotRequested(selfId());
            }
        }
        // and from every other server with members of the session: they must know the new member
        // first (its SESSION_STATE goes ahead: admin before reliable on every link)
        announce(null);
        for (String nodeId : nodesWith(m.kind, m.sessionKey, m.model)) {
            m.requestSnapshot(nodeId, 0);
        }
        return m;
    }

    private void joined(BaseMember m) {
        locals.put(m.key, m);
        Events.notice(LOG, "j2j.session.joined", "J2J session " + m.session + ": " + m.gateway + " / " + m.endpoint + " joined here ("
                + m.model + ")", "session", m.session, "gateway", m.gateway, "endpoint", m.endpoint, "kind", kindName(m.kind), "model", m.model);
        for (RemoteMember r : remoteMembers()) {
            if (r.sessionKey == m.sessionKey) {
                checkModel(r);
            }
        }
        announceSoon();
    }

    /** One local member of a PDU session: a gateway's {@code j2j} endpoint in a PDU relay. */
    public final class Member extends BaseMember {
        final Delivery delivery;
        private final Map<Long, Long> recent = new LinkedHashMap<Long, Long>(256, 0.75f, false);   // PDU hash -> when delivered
        public final AtomicLong loopsDropped = new AtomicLong();

        Member(String session, String gateway, String instance, String endpoint, String model, boolean reliableOnly, Delivery delivery) {
            super(KIND_PDU, session, gateway, instance, endpoint, model, reliableOnly);
            this.delivery = delivery;
        }

        /**
         * Sends a PDU into the session: to the other local members and to every server with a
         * member of the same model. A PDU this member received from the session within the last
         * 10 s is not sent back (another bridge on the same DIS network would loop it).
         */
        public void send(byte[] pdu) {
            if (left || pdu.length < 12) {
                return;
            }
            if (seenRecently(hash(pdu))) {
                loopsDropped.incrementAndGet();
                return;
            }
            sent.incrementAndGet();
            for (BaseMember o : locals.values()) {
                if (o != this && !o.left && o instanceof Member && o.matches(kind, sessionKey, model)) {
                    ((Member) o).deliver(pdu.clone());
                }
            }
            byte[] payload = head(pdu.length).bytes(pdu).toBytes();
            int type = pdu[2] & 0xFF;
            boolean bestEffort = !reliableOnly && (type == 1 || type == 67) && pdu.length >= 18;
            byte[] supersede = bestEffort ? new ByteOut(22).u64(sessionKey).u64(key).bytes(pdu, 12, 6).toBytes() : null;
            toNodes(payload, SESSION_PDU, bestEffort, supersede);
        }

        void deliver(byte[] pdu) {
            if (left) {
                return;
            }
            remember(hash(pdu));
            received.incrementAndGet();
            try {
                delivery.pdu(pdu);
            } catch (RuntimeException e) {
                LOG.fine("J2J session " + session + ": delivery to " + gateway + " / " + endpoint + " failed: " + e);
            }
        }

        private synchronized boolean seenRecently(long h) {
            Long at = recent.get(h);
            return at != null && System.currentTimeMillis() - at < LOOP_WINDOW_MS;
        }

        private synchronized void remember(long h) {
            long now = System.currentTimeMillis();
            recent.remove(h);
            recent.put(h, now);
            Iterator<Map.Entry<Long, Long>> i = recent.entrySet().iterator();
            while (i.hasNext()) {
                Map.Entry<Long, Long> e = i.next();
                if (recent.size() <= 8192 && now - e.getValue() < LOOP_WINDOW_MS) {
                    break;
                }
                i.remove();
            }
        }
    }

    /** The hash of a PDU without its version and exercise octets (a relay rewrites them). */
    static long hash(byte[] pdu) {
        long h = 0xcbf29ce484222325L;
        for (int i = 2; i < pdu.length; i++) {
            h ^= pdu[i] & 0xFF;
            h *= 0x100000001b3L;
        }
        return h;
    }

    /** One local member of an object session: a gateway's {@code j2j} endpoint in an IEM or Talk gateway. */
    public final class ObjectMember extends BaseMember {
        final ObjectDelivery delivery;
        public final AtomicLong unknownObjects = new AtomicLong();

        ObjectMember(String session, String gateway, String instance, String endpoint, String model, boolean reliableOnly,
                     ObjectDelivery delivery) {
            super(KIND_OBJECTS, session, gateway, instance, endpoint, model, reliableOnly);
            this.delivery = delivery;
        }

        Peer self() {
            return new Peer(selfId(), key, gateway, endpoint);
        }

        /** The other members on this server, of the same session and model. */
        private List<ObjectMember> neighbours() {
            List<ObjectMember> l = new ArrayList<ObjectMember>();
            for (BaseMember o : locals.values()) {
                if (o != this && !o.left && o instanceof ObjectMember && o.matches(kind, sessionKey, model)) {
                    l.add((ObjectMember) o);
                }
            }
            return l;
        }

        /** A new object of this member, with the values it has (reliable). */
        public void create(long object, int objectClass, String name, int[] attributes, byte[][] values) {
            createTo(null, object, objectClass, name, attributes, values);
        }

        /**
         * An object of this member for one server only (a snapshot); null = every member.
         * Creating an object the others know already only updates its values.
         */
        public void createTo(String nodeId, long object, int objectClass, String name, int[] attributes, byte[][] values) {
            if (left) {
                return;
            }
            sent.incrementAndGet();
            boolean local = nodeId == null || nodeId.equals(selfId());
            if (local) {
                for (ObjectMember o : neighbours()) {
                    o.received.incrementAndGet();
                    o.delivery.created(self(), object, objectClass, name, attributes, values);
                }
            }
            if (nodeId != null && local) {
                return;
            }
            byte[] n = name.getBytes(StandardCharsets.UTF_8);
            ByteOut o = head(16 + n.length + size(values)).u64(object).u32(objectClass & 0xFFFFFFFFL).u16(n.length).bytes(n);
            values(o, attributes, values);
            byte[] payload = o.toBytes();
            if (nodeId == null) {
                toNodes(payload, OBJ_CREATE, false, null);
            } else {
                Router r = router;
                if (r != null) {
                    to(r, nodeId, payload, OBJ_CREATE, false, null);
                }
            }
        }

        /**
         * New values of one of this member's objects.
         *
         * @param reliable false: best effort, superseded per (object, attribute set)
         */
        public void update(long object, int[] attributes, byte[][] values, boolean reliable) {
            if (left || attributes.length == 0) {
                return;
            }
            sent.incrementAndGet();
            for (ObjectMember o : neighbours()) {
                o.received.incrementAndGet();
                o.delivery.updated(self(), object, attributes, values);
            }
            ByteOut b = head(10 + size(values)).u64(object);
            values(b, attributes, values);
            boolean be = !reliable && !reliableOnly;
            byte[] supersede = null;
            if (be) {
                ByteOut k = new ByteOut(28).u64(sessionKey).u64(key).u64(object);
                long h = 0;
                for (int a : attributes) {
                    h = h * 31 + a;
                }
                supersede = k.u32(h & 0xFFFFFFFFL).toBytes();
            }
            toNodes(b.toBytes(), OBJ_UPDATE, be, supersede);
        }

        /** One of this member's objects is gone. */
        public void delete(long object) {
            if (left) {
                return;
            }
            sent.incrementAndGet();
            for (ObjectMember o : neighbours()) {
                o.received.incrementAndGet();
                o.delivery.deleted(self(), object);
            }
            toNodes(head(8).u64(object).toBytes(), OBJ_DELETE, false, null);
        }

        /** An interaction (best effort when {@code reliable} is false). */
        public void interaction(int interactionClass, int[] parameters, byte[][] values, boolean reliable) {
            if (left) {
                return;
            }
            sent.incrementAndGet();
            for (ObjectMember o : neighbours()) {
                o.received.incrementAndGet();
                o.delivery.interaction(self(), interactionClass, parameters, values);
            }
            ByteOut b = head(6 + size(values)).u32(interactionClass & 0xFFFFFFFFL);
            values(b, parameters, values);
            toNodes(b.toBytes(), OBJ_INTERACTION, !reliable && !reliableOnly, null);
        }

        /** Asks the owner of an object for attribute values. */
        public void requestProvide(Peer owner, long object, int[] attributes) {
            if (left) {
                return;
            }
            if (owner.node.equals(selfId())) {
                BaseMember o = locals.get(owner.key);
                if (o instanceof ObjectMember) {
                    ((ObjectMember) o).delivery.provideRequested(self(), object, attributes);
                }
                return;
            }
            ByteOut b = head(18 + 4 * attributes.length).u64(owner.key).u64(object).u16(attributes.length);
            for (int a : attributes) {
                b.u32(a & 0xFFFFFFFFL);
            }
            Router r = router;
            if (r != null) {
                to(r, owner.node, b.toBytes(), OBJ_PROVIDE, false, null);
            }
        }

        /** Asks the members on {@code nodeId} for their objects (target 0: all of them). */
        void requestSnapshot(String nodeId, long target) {
            Router r = router;
            if (r != null && !left) {
                to(r, nodeId, head(8).u64(target).toBytes(), OBJ_SNAPSHOT, false, null);
            }
        }

        void gone(Peer p) {
            try {
                delivery.peerGone(p);
            } catch (RuntimeException e) {
                LOG.fine("J2J session " + session + ": " + e);
            }
        }
    }

    private static int size(byte[][] values) {
        int n = 0;
        for (byte[] v : values) {
            n += 8 + v.length;
        }
        return n;
    }

    /** u16 n | n × (u32 id | u32 length | value) */
    private static void values(ByteOut o, int[] ids, byte[][] values) {
        o.u16(ids.length);
        for (int i = 0; i < ids.length; i++) {
            o.u32(ids[i] & 0xFFFFFFFFL).u32(values[i].length).bytes(values[i]);
        }
    }

    // ------------------------------------------------------------------ remote members

    static final class NodeState {
        long boot;
        long counter;
        List<RemoteMember> members = new ArrayList<RemoteMember>();
        long unreachableSince;
        boolean lost;
    }

    static final class RemoteMember {
        String node;
        String session;
        long sessionKey;
        long key;
        String gateway;
        String instance;
        String endpoint;
        int kind;
        String model;
        long since;
        boolean warnedMismatch;

        Peer peer() {
            return new Peer(node, key, gateway, endpoint);
        }
    }

    private synchronized List<RemoteMember> remoteMembers() {
        List<RemoteMember> l = new ArrayList<RemoteMember>();
        for (NodeState ns : remote.values()) {
            l.addAll(ns.members);
        }
        return l;
    }

    private List<String> nodesWith(int kind, long sessionKey, String model) {
        List<String> l = new ArrayList<String>();
        for (Map.Entry<String, NodeState> e : remote.entrySet()) {
            NodeState ns = e.getValue();
            if (ns.lost) {
                continue;
            }
            for (RemoteMember m : ns.members) {
                if (m.sessionKey == sessionKey && m.kind == kind && model.equals(m.model)) {
                    l.add(e.getKey());
                    break;
                }
            }
        }
        return l;
    }

    /** @return the sender, when it is a known member (not lost) of that session */
    private RemoteMember sender(String src, long sKey, long mKey) {
        NodeState ns = remote.get(src);
        if (ns == null || ns.lost) {
            return null;
        }
        for (RemoteMember m : ns.members) {
            if (m.key == mKey && m.sessionKey == sKey) {
                return m;
            }
        }
        return null;
    }

    private void receive(String src, Envelope env) {
        try {
            if (env.type == SESSION_STATE) {
                receiveState(src, env.payload);
                return;
            }
            ByteIn in = new ByteIn(env.payload);
            long sKey = in.u64();
            long mKey = in.u64();
            in.u8();                                              // flags: none defined
            RemoteMember from = sender(src, sKey, mKey);
            if (from == null) {
                return;                                           // not (yet) a known member
            }
            if (env.type == SESSION_PDU) {
                byte[] pdu = in.rest();
                if (pdu.length < 12 || from.kind != KIND_PDU) {
                    return;
                }
                for (BaseMember m : locals.values()) {
                    if (m instanceof Member && m.matches(KIND_PDU, sKey, from.model)) {
                        ((Member) m).deliver(pdu.clone());
                    }
                }
                return;
            }
            if (from.kind != KIND_OBJECTS) {
                return;
            }
            receiveObjects(env.type, from, sKey, in);
        } catch (WireException e) {
            LOG.fine("J2J: a malformed session message from " + src + ": " + e.getMessage());
        }
    }

    private void receiveObjects(int type, RemoteMember from, long sKey, ByteIn in) throws WireException {
        Peer p = from.peer();
        List<ObjectMember> to = new ArrayList<ObjectMember>();
        for (BaseMember m : locals.values()) {
            if (m instanceof ObjectMember && !m.left && m.matches(KIND_OBJECTS, sKey, from.model)) {
                to.add((ObjectMember) m);
            }
        }
        switch (type) {
            case OBJ_CREATE: {
                long object = in.u64();
                int cls = (int) in.u32();
                String name = new String(in.bytes(in.u16()), StandardCharsets.UTF_8);
                int n = in.u16();
                int[] ids = new int[n];
                byte[][] vals = new byte[n][];
                readValues(in, ids, vals);
                for (ObjectMember m : to) {
                    m.received.incrementAndGet();
                    m.delivery.created(p, object, cls, name, ids, vals);
                }
                break;
            }
            case OBJ_UPDATE: {
                long object = in.u64();
                int n = in.u16();
                int[] ids = new int[n];
                byte[][] vals = new byte[n][];
                readValues(in, ids, vals);
                for (ObjectMember m : to) {
                    m.received.incrementAndGet();
                    m.delivery.updated(p, object, ids, vals);
                }
                break;
            }
            case OBJ_DELETE: {
                long object = in.u64();
                for (ObjectMember m : to) {
                    m.received.incrementAndGet();
                    m.delivery.deleted(p, object);
                }
                break;
            }
            case OBJ_INTERACTION: {
                int cls = (int) in.u32();
                int n = in.u16();
                int[] ids = new int[n];
                byte[][] vals = new byte[n][];
                readValues(in, ids, vals);
                for (ObjectMember m : to) {
                    m.received.incrementAndGet();
                    m.delivery.interaction(p, cls, ids, vals);
                }
                break;
            }
            case OBJ_PROVIDE: {
                long target = in.u64();
                long object = in.u64();
                int n = in.u16();
                int[] ids = new int[n];
                for (int i = 0; i < n; i++) {
                    ids[i] = (int) in.u32();
                }
                BaseMember m = locals.get(target);
                if (m instanceof ObjectMember && to.contains(m)) {
                    ((ObjectMember) m).delivery.provideRequested(p, object, ids);
                }
                break;
            }
            case OBJ_SNAPSHOT: {
                long target = in.u64();
                for (ObjectMember m : to) {
                    if (target == 0 || target == m.key) {
                        m.delivery.snapshotRequested(p.node);
                    }
                }
                break;
            }
            default:
                break;                                            // a later type: skipped
        }
    }

    private static void readValues(ByteIn in, int[] ids, byte[][] vals) throws WireException {
        for (int i = 0; i < ids.length; i++) {
            ids[i] = (int) in.u32();
            long len = in.u32();
            if (len > in.remaining()) {
                throw new WireException("a value longer than the message");
            }
            vals[i] = in.bytes((int) len);
        }
    }

    private void receiveState(String src, byte[] payload) throws WireException {
        ByteIn in = new ByteIn(payload);
        long b = in.u64();
        long c = in.u64();
        int n = in.u16();
        List<RemoteMember> list = new ArrayList<RemoteMember>();
        for (int i = 0; i < n; i++) {
            int len = in.u16();
            Map<Integer, byte[]> t = new ByteIn(in.bytes(len)).tlvs(T_SESSION, T_MEMBER, T_GATEWAY, T_INSTANCE, T_ENDPOINT, T_KIND, T_MODEL, T_SINCE);
            if (t.get(T_SESSION) == null || t.get(T_MEMBER) == null || t.get(T_KIND) == null || t.get(T_KIND).length < 1) {
                continue;
            }
            RemoteMember m = new RemoteMember();
            m.node = src;
            m.session = ByteIn.utf8(t.get(T_SESSION));
            m.sessionKey = sessionKey(m.session);
            m.key = ByteIn.u64(t.get(T_MEMBER));
            m.gateway = str(t.get(T_GATEWAY));
            m.instance = str(t.get(T_INSTANCE));
            m.endpoint = str(t.get(T_ENDPOINT));
            m.kind = t.get(T_KIND)[0] & 0xFF;
            m.model = str(t.get(T_MODEL));
            m.since = t.get(T_SINCE) == null ? 0 : ByteIn.u64(t.get(T_SINCE));
            if (m.kind == KIND_PDU || m.kind == KIND_OBJECTS) {
                list.add(m);                                      // other kinds: a later version
            }
        }
        List<RemoteMember> joined = new ArrayList<RemoteMember>();
        List<RemoteMember> left = new ArrayList<RemoteMember>();
        boolean lost;
        synchronized (this) {
            NodeState ns = remote.get(src);
            if (ns != null && ns.boot == b && c <= ns.counter) {
                return;                                           // an older state
            }
            if (ns == null) {
                ns = new NodeState();
                remote.put(src, ns);
            }
            Map<Long, RemoteMember> before = new HashMap<Long, RemoteMember>();
            for (RemoteMember m : ns.members) {
                before.put(m.key, m);
            }
            for (RemoteMember m : list) {
                RemoteMember old = before.remove(m.key);
                if (old == null) {
                    joined.add(m);
                } else {
                    m.warnedMismatch = old.warnedMismatch;
                }
            }
            left.addAll(before.values());
            ns.boot = b;
            ns.counter = c;
            ns.members = list;
            lost = ns.lost;
        }
        for (RemoteMember m : left) {
            Events.notice(LOG, "j2j.session.left", "J2J session " + m.session + ": " + src + " / " + m.gateway + " left",
                    "session", m.session, "member", src, "gateway", m.gateway, "endpoint", m.endpoint);
            peerGone(m);
        }
        for (RemoteMember m : joined) {
            Events.notice(LOG, "j2j.session.joined", "J2J session " + m.session + ": " + src + " / " + m.gateway + " joined (" + m.model + ")",
                    "session", m.session, "member", src, "gateway", m.gateway, "endpoint", m.endpoint, "kind", kindName(m.kind), "model", m.model);
            checkModel(m);
            if (!lost) {
                askSnapshot(m);
            }
        }
    }

    /** A remote object member (new, or back): the local members of its session ask it for its objects. */
    private void askSnapshot(RemoteMember r) {
        if (r.kind != KIND_OBJECTS) {
            return;
        }
        for (BaseMember m : locals.values()) {
            if (m instanceof ObjectMember && !m.left && m.matches(KIND_OBJECTS, r.sessionKey, r.model)) {
                ((ObjectMember) m).requestSnapshot(r.node, r.key);
            }
        }
    }

    /** A remote member left, was lost or restarted: the local object members of its session forget its objects. */
    private void peerGone(RemoteMember r) {
        if (r.kind != KIND_OBJECTS) {
            return;
        }
        Peer p = r.peer();
        for (BaseMember m : locals.values()) {
            if (m instanceof ObjectMember && m.matches(KIND_OBJECTS, r.sessionKey, r.model)) {
                ((ObjectMember) m).gone(p);
            }
        }
    }

    private void checkModel(RemoteMember m) {
        for (BaseMember l : locals.values()) {
            if (l.sessionKey == m.sessionKey && (l.kind != m.kind || !l.model.equals(m.model)) && !m.warnedMismatch) {
                m.warnedMismatch = true;
                String theirs = kindName(m.kind) + ": " + m.model;
                String ours = kindName(l.kind) + ": " + l.model;
                Events.warning(LOG, "j2j.session.modelmismatch", "J2J session " + m.session + ": " + m.node + " / " + m.gateway
                        + " has " + theirs + ", " + l.gateway + " here has " + ours + ": nothing between them",
                        "session", m.session, "member", m.node, "gateway", m.gateway, "theirs", theirs, "ours", ours);
            }
        }
    }

    private static String str(byte[] v) {
        String s = ByteIn.utf8(v);
        return s == null ? "" : s;
    }

    // ------------------------------------------------------------------ announcing, lost members

    private void announceSoon() {
        ScheduledFuture<?> p = pendingAnnounce;
        if (p != null && !p.isDone()) {
            return;
        }
        try {
            pendingAnnounce = timer.schedule(() -> announce(null), 100, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // closed
        }
    }

    /** Sends this server's members to {@code to}, or to every reachable server when null. */
    private void announce(String to) {
        Router r = router;
        J2jNode n = node;
        if (r == null || n == null || closed) {
            return;
        }
        byte[] payload = encodeState();
        if (to != null) {
            r.sendUnsequenced(to, J2jWire.CLASS_ADMIN, SESSION_STATE, payload);
            return;
        }
        for (String id : r.routes().keySet()) {
            r.sendUnsequenced(id, J2jWire.CLASS_ADMIN, SESSION_STATE, payload);
        }
    }

    byte[] encodeState() {
        List<BaseMember> ms = new ArrayList<BaseMember>(locals.values());
        ByteOut o = new ByteOut(32 + 128 * ms.size());
        o.u64(boot).u64(counter.incrementAndGet()).u16(ms.size());
        for (BaseMember m : ms) {
            byte[] e = new ByteOut(128).tlv(T_SESSION, m.session).tlvU64(T_MEMBER, m.key).tlv(T_GATEWAY, m.gateway)
                    .tlv(T_INSTANCE, m.instance).tlv(T_ENDPOINT, m.endpoint).tlv(T_KIND, new byte[] {(byte) m.kind})
                    .tlv(T_MODEL, m.model).tlvU64(T_SINCE, m.since).toBytes();
            o.u16(e.length).bytes(e);
        }
        return o.toBytes();
    }

    private void tick() {
        Router r = router;
        if (r == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, NodeState> e : remote.entrySet()) {
            String id = e.getKey();
            NodeState ns = e.getValue();
            boolean reachable = r.route(id) != null;
            List<RemoteMember> ms;
            boolean lostNow = false;
            boolean back = false;
            synchronized (this) {
                if (reachable) {
                    back = ns.lost;
                    ns.lost = false;
                    ns.unreachableSince = 0;
                } else if (ns.unreachableSince == 0) {
                    ns.unreachableSince = now;
                } else if (!ns.lost && now - ns.unreachableSince >= lostMillis) {
                    ns.lost = true;
                    lostNow = true;
                }
                ms = new ArrayList<RemoteMember>(ns.members);
            }
            for (RemoteMember m : ms) {
                if (lostNow) {
                    Events.warning(LOG, "j2j.session.memberlost", "J2J session " + m.session + ": " + id + " / " + m.gateway
                            + " lost (not reachable for " + lostMillis / 1000 + " s)", "session", m.session, "member", id, "gateway", m.gateway);
                    peerGone(m);
                } else if (back) {
                    Events.notice(LOG, "j2j.session.memberback", "J2J session " + m.session + ": " + id + " / " + m.gateway + " is back",
                            "session", m.session, "member", id, "gateway", m.gateway);
                    askSnapshot(m);
                }
            }
        }
    }

    // ------------------------------------------------------------------ views (J2J page, design §13.7)

    private static String iso(long ms) {
        return ms <= 0 ? null : Instant.ofEpochMilli(ms).toString();
    }

    /** The members of a session, this server's first: {node, gateway, instance, endpoint, kind, model, state, statemessage, local, since} */
    synchronized List<Map<String, Object>> membersOf(String session) {
        long sk = sessionKey(session);
        String self = selfId();
        String ours = null;
        int ourKind = 0;
        List<Map<String, Object>> l = new ArrayList<Map<String, Object>>();
        for (BaseMember m : sortedLocals()) {
            if (m.sessionKey != sk) {
                continue;
            }
            if (ours == null) {
                ours = m.model;
                ourKind = m.kind;
            }
            Map<String, Object> x = new LinkedHashMap<String, Object>();
            x.put("node", self);
            x.put("gateway", m.gateway);
            x.put("instance", m.instance);
            x.put("endpoint", m.endpoint);
            x.put("kind", kindName(m.kind));
            x.put("model", m.model);
            boolean mismatch = !m.model.equals(ours) || m.kind != ourKind;
            x.put("state", mismatch ? "mismatch" : "active");
            x.put("statemessage", mismatch ? m.model + " (the session here: " + ours + ")" : null);
            x.put("local", true);
            x.put("since", iso(m.since));
            x.put("key", m.key);
            l.add(x);
        }
        for (Map.Entry<String, NodeState> e : new TreeMap<String, NodeState>(remote).entrySet()) {
            for (RemoteMember m : e.getValue().members) {
                if (m.sessionKey != sk) {
                    continue;
                }
                Map<String, Object> x = new LinkedHashMap<String, Object>();
                x.put("node", m.node);
                x.put("gateway", m.gateway);
                x.put("instance", m.instance);
                x.put("endpoint", m.endpoint);
                x.put("kind", kindName(m.kind));
                x.put("model", m.model);
                boolean mismatch = ours != null && (!ours.equals(m.model) || ourKind != m.kind);
                String st = e.getValue().lost ? "lost" : mismatch ? "mismatch" : "active";
                x.put("state", st);
                x.put("statemessage", "lost".equals(st) ? "not reachable" : "mismatch".equals(st) ? m.model + " (ours: " + ours + ")" : null);
                x.put("local", false);
                x.put("since", iso(m.since));
                l.add(x);
            }
        }
        return l;
    }

    private List<BaseMember> sortedLocals() {
        List<BaseMember> l = new ArrayList<BaseMember>(locals.values());
        l.sort((a, b) -> a.since != b.since ? Long.compare(a.since, b.since) : a.gateway.compareTo(b.gateway));
        return l;
    }

    private synchronized Set<String> sessionNames() {
        Set<String> s = new LinkedHashSet<String>();
        for (BaseMember m : sortedLocals()) {
            s.add(m.session);
        }
        for (NodeState ns : new TreeMap<String, NodeState>(remote).values()) {
            for (RemoteMember m : ns.members) {
                s.add(m.session);
            }
        }
        return s;
    }

    @Override
    public List<Map<String, Object>> sessions() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (String name : sessionNames()) {
            List<Map<String, Object>> ms = membersOf(name);
            String model = null;
            String kind = null;
            long since = Long.MAX_VALUE;
            for (Map<String, Object> m : ms) {
                m.remove("key");
                if (model == null && Boolean.TRUE.equals(m.get("local"))) {
                    model = (String) m.get("model");
                    kind = (String) m.get("kind");
                }
            }
            for (BaseMember l : locals.values()) {
                if (l.session.equals(name)) {
                    since = Math.min(since, l.since);
                }
            }
            if (model == null && !ms.isEmpty()) {
                model = (String) ms.get(0).get("model");
                kind = (String) ms.get(0).get("kind");
            }
            Map<String, Object> s = new LinkedHashMap<String, Object>();
            s.put("name", name);
            s.put("kind", kind);
            s.put("model", model);
            s.put("members", ms);
            s.put("since", since == Long.MAX_VALUE ? null : iso(since));
            out.add(s);
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> gatewaysWith(String member) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        NodeState ns = remote.get(member);
        if (ns == null) {
            return out;
        }
        for (BaseMember l : sortedLocals()) {
            int with = 0;
            synchronized (this) {
                for (RemoteMember m : ns.members) {
                    if (l.matches(m.kind, m.sessionKey, m.model)) {
                        with++;
                    }
                }
            }
            if (with > 0) {
                Map<String, Object> g = new LinkedHashMap<String, Object>();
                g.put("gateway", l.gateway);
                g.put("instance", l.instance);
                g.put("endpoint", l.endpoint);
                g.put("session", l.session);
                g.put("remotegateways", with);
                out.add(g);
            }
        }
        return out;
    }

    @Override
    public List<String> sessionsOf(String member) {
        Set<String> s = new LinkedHashSet<String>();
        NodeState ns = remote.get(member);
        if (ns != null) {
            synchronized (this) {
                for (RemoteMember m : ns.members) {
                    s.add(m.session);
                }
            }
        }
        return new ArrayList<String>(s);
    }

    /** @return this server's members, for tests and diagnostics */
    public Map<Long, BaseMember> locals() {
        return Collections.unmodifiableMap(locals);
    }
}
