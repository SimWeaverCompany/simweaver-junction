package com.simweaver.junction.endpoint.j2j;

import com.simweaver.common.iem.Attribute;
import com.simweaver.common.iem.FomFamily;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.common.iem.InteractionClass;
import com.simweaver.common.iem.ObjectClass;
import com.simweaver.common.iem.Parameter;
import com.simweaver.common.iem.Transportation;
import com.simweaver.j2j.crypto.J2jCrypto;
import com.simweaver.j2j.session.J2jSessions;
import com.simweaver.junction.config.EndpointConfig;
import com.simweaver.junction.config.EndpointType;
import com.simweaver.junction.endpoint.Endpoint;
import com.simweaver.junction.endpoint.EndpointException;
import com.simweaver.junction.endpoint.EndpointListener;
import com.simweaver.junction.endpoint.EndpointState;
import com.simweaver.junction.endpoint.EndpointStats;
import com.simweaver.junction.endpoint.Interest;
import com.simweaver.junction.endpoint.NameCandidates;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * A gateway's side of a J2J object session (0.10.0, design §9.1–9.5): the objects and interactions
 * of an HLA object model (RPR, NETN, Talk …) shared with gateways on other SimWeaver servers. To the
 * router it is an endpoint like an HLA federation: objects of the other members are discovered here,
 * objects the router creates here are the session's. For the IEM and Talk routers.
 *
 * <p>The model comes from {@code endpoint.hla.foms} (default RPR FOM v2.0); the members must have the
 * same one (its fingerprint: the qualified names of every class and member with their datatypes).
 * Ids on the wire are 32-bit hashes of the qualified names.
 *
 * <p>Attribute updates whose FOM transportation is {@code HLAbestEffort} go best effort (the newest
 * wins on a busy link); everything else reliably and in order. When a member joins or comes back it
 * gets a snapshot of every member's objects (this endpoint keeps the last values it sent); a lost or
 * leaving member's objects are removed.
 */
public final class J2jObjectEndpoint implements Endpoint {

    private static final Logger LOG = Logger.getLogger(J2jObjectEndpoint.class.getName());


    /** An object the router created here (one of this member's). */
    private static final class Own {
        final int wireClass;
        final String name;
        final Map<Integer, byte[]> values = new LinkedHashMap<Integer, byte[]>();     // wire attribute id -> last value

        Own(int wireClass, String name) {
            this.wireClass = wireClass;
            this.name = name;
        }
    }

    /** An object of another member, discovered here. */
    private static final class Remote {
        final J2jSessions.Peer peer;
        final long object;
        final long key;

        Remote(J2jSessions.Peer peer, long object, long key) {
            this.peer = peer;
            this.object = object;
            this.key = key;
        }
    }

    private final EndpointConfig cfg;
    private final String session;
    private final String gatewayId;
    private final EndpointStats stats = new EndpointStats();
    private final AtomicLong ownKeys = new AtomicLong();
    private final AtomicLong remoteKeys = new AtomicLong(1L << 40);
    private final Map<Long, Own> own = new ConcurrentHashMap<Long, Own>();
    private final Map<String, Remote> remote = new ConcurrentHashMap<String, Remote>();        // node/member/object -> remote
    private final Map<Long, Remote> remoteByKey = new ConcurrentHashMap<Long, Remote>();
    private final AtomicLong unknownIds = new AtomicLong();
    private final ExecutorService callbacks;
    private volatile InformationExchangeModel model;
    private volatile String modelText;
    private volatile String fingerprint;
    // id maps: IEM id <-> wire id (per kind, as the IEM ids are)
    private final Map<Integer, Integer> toWire = new HashMap<Integer, Integer>();
    private final Map<Integer, Integer> classIn = new HashMap<Integer, Integer>();
    private final Map<Integer, Integer> attributeIn = new HashMap<Integer, Integer>();
    private final Map<Integer, Integer> interactionIn = new HashMap<Integer, Integer>();
    private final Map<Integer, Integer> parameterIn = new HashMap<Integer, Integer>();
    private final Map<Integer, Boolean> reliableAttribute = new HashMap<Integer, Boolean>();   // wire attribute id
    private final Map<Integer, Boolean> reliableInteraction = new HashMap<Integer, Boolean>(); // wire class id
    private volatile EndpointState state = EndpointState.NEW;
    private volatile String stateMessage;
    private volatile EndpointListener listener;
    private volatile J2jSessions.ObjectMember member;
    private volatile J2jSessions hub;

    public J2jObjectEndpoint(EndpointConfig cfg, String session, String gatewayId) throws EndpointException {
        if (cfg.getType() != EndpointType.J2J) {
            throw new EndpointException(EndpointException.CONFIG_INVALID, "endpoint " + cfg.getId() + " is not a J2J endpoint", null);
        }
        String why = J2jSessions.nameProblem(session);
        if (why != null) {
            throw new EndpointException(EndpointException.CONFIG_INVALID, "endpoint " + cfg.getId() + ": " + why, null);
        }
        this.cfg = cfg;
        this.session = session;
        this.gatewayId = gatewayId;
        this.callbacks = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "junction-j2j-" + gatewayId + "-" + cfg.getId());
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public String id() {
        return cfg.getId();
    }

    @Override
    public EndpointConfig config() {
        return cfg;
    }

    @Override
    public EndpointType type() {
        return EndpointType.J2J;
    }

    @Override
    public InformationExchangeModel model() {
        return model;
    }

    @Override
    public String target() {
        return session;
    }

    @Override
    public EndpointState state() {
        return state;
    }

    @Override
    public String stateMessage() {
        return stateMessage;
    }

    // ------------------------------------------------------------------ opening: the model and its wire ids

    @Override
    public void open(EndpointListener l) throws EndpointException {
        this.listener = l;
        setState(EndpointState.CONNECTING, null);
        try {
            model = InformationExchangeModel.fromHlaEvolved(cfg.getJ2jFomPaths());
        } catch (Exception e) {
            setState(EndpointState.FAILED, e.getMessage());
            throw new EndpointException(EndpointException.FOM_INVALID, "endpoint " + id() + ": " + e.getMessage(), e);
        }
        String problem = mapIds();
        if (problem != null) {
            setState(EndpointState.FAILED, problem);
            throw new EndpointException(EndpointException.FOM_INVALID, "endpoint " + id() + ": " + problem, null);
        }
        J2jSessions hub = J2jEndpoint.hub(gatewayId);
        if (hub == null) {
            String m = "J2J does not run on this server (etc/j2j/network.settings, install guide 2.8): no session " + session;
            setState(EndpointState.FAILED, m);
            throw new EndpointException(EndpointException.CONNECT_FAILED, "endpoint " + id() + ": " + m, null);
        }
        this.hub = hub;
        setState(EndpointState.JOINED, null);
    }

    /** Builds the wire ids and the model's fingerprint. @return a problem (a hash collision), or null */
    private String mapIds() {
        TreeSet<String> lines = new TreeSet<String>();
        for (ObjectClass c : model.getObjectClasses()) {
            String q = c.getQualifiedName();
            if (put(c.getId(), q, classIn) != null) {
                return "two object classes have the same J2J id: " + q;
            }
            lines.add("O|" + q);
        }
        for (Attribute a : model.getAttributes()) {
            String q = a.getQualifiedName();
            int w = J2jSessions.wireId(q);
            if (put(a.getId(), q, attributeIn) != null) {
                return "two attributes have the same J2J id: " + q;
            }
            Transportation t = a.getTransportation();
            reliableAttribute.put(w, t == null || t.isReliable());
            lines.add("A|" + q + "|" + a.getDataTypeName());
        }
        for (InteractionClass c : model.getInteractionClasses()) {
            String q = c.getQualifiedName();
            int w = J2jSessions.wireId(q);
            if (put(c.getId(), q, interactionIn) != null) {
                return "two interaction classes have the same J2J id: " + q;
            }
            Transportation t = c.getEffectiveTransportation();
            reliableInteraction.put(w, t == null || t.isReliable());
            lines.add("I|" + q);
        }
        for (Parameter p : model.getParameters()) {
            String q = p.getQualifiedName();
            if (put(p.getId(), q, parameterIn) != null) {
                return "two parameters have the same J2J id: " + q;
            }
            lines.add("P|" + q + "|" + p.getDataTypeName());
        }
        byte[] h = J2jCrypto.sha256(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        fingerprint = J2jCrypto.hex(java.util.Arrays.copyOf(h, 4));
        List<String> fam = new ArrayList<String>();
        for (FomFamily f : model.getFomFamilies()) {
            if (f != FomFamily.STANDARD_MIM) {
                fam.add(f.name());
            }
        }
        Collections.sort(fam);
        modelText = "HLA objects " + fingerprint + (fam.isEmpty() ? "" : " (" + String.join(", ", fam) + ")");
        return null;
    }

    /** @return the IEM id that already has this wire id (a collision), or null */
    private Integer put(int iemId, String qualified, Map<Integer, Integer> in) {
        int w = J2jSessions.wireId(qualified);
        toWire.put(iemId, w);
        return in.putIfAbsent(w, iemId);
    }

    private void setState(EndpointState s, String message) {
        state = s;
        stateMessage = message;
        EndpointListener l = listener;
        if (l != null) {
            l.stateChanged(this, s, message);
        }
    }

    private int[] wire(int[] ids) {
        int[] w = new int[ids.length];
        for (int i = 0; i < ids.length; i++) {
            Integer x = toWire.get(ids[i]);
            w[i] = x == null ? 0 : x;
        }
        return w;
    }

    // ------------------------------------------------------------------ from the router into the session

    /**
     * Joins the session: only now, when the router is ready, so the snapshot of the other members'
     * objects that the join brings is not lost. The session takes everything; the router filters.
     */
    @Override
    public void declare(Interest publish, Interest subscribe) {
        J2jSessions h = hub;
        if (member == null && h != null) {
            member = h.joinObjects(session, gatewayId, "", id(), modelText, cfg.isJ2jReliableOnly(), new Delivery());
            LOG.info("Junction " + gatewayId + "/" + id() + ": joined J2J session " + session + " (" + modelText + ")");
        }
    }

    @Override
    public void createObject(long gwId, int objectClass, NameCandidates names) {
        J2jSessions.ObjectMember m = member;
        Integer cls = toWire.get(objectClass);
        EndpointListener l = listener;
        if (m == null || cls == null) {
            if (l != null) {
                async(() -> l.registrationFailed(this, gwId, m == null ? "not in the session" : "the class is not in the session's model"));
            }
            return;
        }
        long key = ownKeys.incrementAndGet();
        String name = names == null || names.isAny() || names.getNames().isEmpty()
                ? "Junction-" + gatewayId + "-" + key : names.getNames().get(0);
        own.put(key, new Own(cls, name));
        m.create(key, cls, name, new int[0], new byte[0][]);
        stats.registeredOut.incrementAndGet();
        if (l != null) {
            async(() -> l.registered(this, gwId, key, name));          // never from the dispatcher's own call
        }
    }

    @Override
    public void updateObject(long key, int[] attributes, byte[][] values, byte[] tag) {
        J2jSessions.ObjectMember m = member;
        Own o = own.get(key);
        if (m == null || o == null) {
            return;
        }
        int[] w = wire(attributes);
        List<Integer> rel = new ArrayList<Integer>();
        List<Integer> be = new ArrayList<Integer>();
        synchronized (o) {
            for (int i = 0; i < w.length; i++) {
                if (w[i] == 0) {
                    continue;
                }
                o.values.put(w[i], values[i]);
                (Boolean.FALSE.equals(reliableAttribute.get(w[i])) ? be : rel).add(i);
            }
        }
        int octets = 0;
        for (byte[] v : values) {
            octets += v.length;
        }
        stats.markOut(octets);
        stats.updatesOut.incrementAndGet();
        send(m, key, w, values, rel, true);
        send(m, key, w, values, be, false);
    }

    private static void send(J2jSessions.ObjectMember m, long key, int[] w, byte[][] values, List<Integer> which, boolean reliable) {
        if (which.isEmpty()) {
            return;
        }
        int[] ids = new int[which.size()];
        byte[][] vals = new byte[which.size()][];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = w[which.get(i)];
            vals[i] = values[which.get(i)];
        }
        m.update(key, ids, vals, reliable);
    }

    @Override
    public void deleteObject(long key, byte[] tag) {
        J2jSessions.ObjectMember m = member;
        if (own.remove(key) != null && m != null) {
            m.delete(key);
            stats.deletedOut.incrementAndGet();
        }
    }

    @Override
    public void sendInteraction(int interactionClass, int[] parameters, byte[][] values, byte[] tag) {
        J2jSessions.ObjectMember m = member;
        Integer cls = toWire.get(interactionClass);
        if (m == null || cls == null) {
            return;
        }
        int octets = 0;
        for (byte[] v : values) {
            octets += v.length;
        }
        stats.markOut(octets);
        stats.interactionsOut.incrementAndGet();
        m.interaction(cls, wire(parameters), values, !Boolean.FALSE.equals(reliableInteraction.get(cls)));
    }

    @Override
    public void requestUpdate(long key, int[] attributes) {
        J2jSessions.ObjectMember m = member;
        Remote r = remoteByKey.get(key);
        if (m != null && r != null) {
            m.requestProvide(r.peer, r.object, wire(attributes));
        }
    }

    @Override
    public void close(boolean deleteObjects) {
        J2jSessions.ObjectMember m = member;
        member = null;
        hub = null;
        if (m != null) {
            m.leave();                                          // the others remove this member's objects
        }
        callbacks.shutdown();
        if (state != EndpointState.CLOSED) {
            setState(EndpointState.CLOSED, null);
        }
    }

    @Override
    public EndpointStats stats() {
        return stats;
    }

    private void async(Runnable r) {
        try {
            callbacks.execute(r);
        } catch (RejectedExecutionException closed) {
            // closing
        }
    }

    // ------------------------------------------------------------------ from the session to the router

    private static String remoteKey(J2jSessions.Peer p, long object) {
        return p.node + "/" + Long.toHexString(p.key) + "/" + Long.toHexString(object);
    }

    private static String producer(J2jSessions.Peer p) {
        return p.node + "/" + p.gateway;
    }

    /** Translates wire ids to IEM ids, leaving out unknown ones. */
    private void reflect(EndpointListener l, Remote r, int[] ids, byte[][] values) {
        List<Integer> keep = new ArrayList<Integer>(ids.length);
        for (int i = 0; i < ids.length; i++) {
            if (attributeIn.containsKey(ids[i])) {
                keep.add(i);
            } else {
                unknownIds.incrementAndGet();
            }
        }
        if (keep.isEmpty()) {
            return;
        }
        int[] a = new int[keep.size()];
        byte[][] v = new byte[keep.size()][];
        int octets = 0;
        for (int i = 0; i < a.length; i++) {
            a[i] = attributeIn.get(ids[keep.get(i)]);
            v[i] = values[keep.get(i)];
            octets += v[i].length;
        }
        stats.markIn(octets);
        stats.updatesIn.incrementAndGet();
        l.reflected(this, r.key, a, v, null, producer(r.peer));
    }

    private final class Delivery implements J2jSessions.ObjectDelivery {

        @Override
        public void created(J2jSessions.Peer from, long object, int objectClass, String name, int[] attributes, byte[][] values) {
            EndpointListener l = listener;                     // (member may not be set yet: a snapshot can come
            if (l == null || state == EndpointState.CLOSED) {  //  back before joinObjects returns)
                return;
            }
            String rk = remoteKey(from, object);
            Remote r = remote.get(rk);
            if (r == null) {
                Integer cls = classIn.get(objectClass);
                if (cls == null) {
                    unknownIds.incrementAndGet();
                    return;
                }
                r = new Remote(from, object, remoteKeys.incrementAndGet());
                remote.put(rk, r);
                remoteByKey.put(r.key, r);
                stats.discoveredIn.incrementAndGet();
                l.discovered(J2jObjectEndpoint.this, r.key, cls, name, producer(from));
            }
            reflect(l, r, attributes, values);
        }

        @Override
        public void updated(J2jSessions.Peer from, long object, int[] attributes, byte[][] values) {
            EndpointListener l = listener;
            Remote r = remote.get(remoteKey(from, object));
            if (l == null || r == null) {
                if (member != null) {
                    member.unknownObjects.incrementAndGet();        // best effort before its create: the next one comes
                }
                return;
            }
            reflect(l, r, attributes, values);
        }

        @Override
        public void deleted(J2jSessions.Peer from, long object) {
            Remote r = remote.remove(remoteKey(from, object));
            EndpointListener l = listener;
            if (r != null) {
                remoteByKey.remove(r.key);
                stats.removedIn.incrementAndGet();
                if (l != null) {
                    l.removed(J2jObjectEndpoint.this, r.key, null);
                }
            }
        }

        @Override
        public void interaction(J2jSessions.Peer from, int interactionClass, int[] parameters, byte[][] values) {
            EndpointListener l = listener;
            Integer cls = interactionIn.get(interactionClass);
            if (l == null || cls == null) {
                unknownIds.incrementAndGet();
                return;
            }
            List<Integer> keep = new ArrayList<Integer>();
            for (int i = 0; i < parameters.length; i++) {
                if (parameterIn.containsKey(parameters[i])) {
                    keep.add(i);
                }
            }
            int[] p = new int[keep.size()];
            byte[][] v = new byte[keep.size()][];
            int octets = 0;
            for (int i = 0; i < p.length; i++) {
                p[i] = parameterIn.get(parameters[keep.get(i)]);
                v[i] = values[keep.get(i)];
                octets += v[i].length;
            }
            stats.markIn(octets);
            stats.interactionsIn.incrementAndGet();
            l.received(J2jObjectEndpoint.this, cls, p, v, null, producer(from));
        }

        @Override
        public void provideRequested(J2jSessions.Peer from, long object, int[] attributes) {
            Own o = own.get(object);
            J2jSessions.ObjectMember m = member;
            if (o == null || m == null) {
                return;
            }
            // answered from what this endpoint sent last; what it never had is asked of the router
            List<Integer> have = new ArrayList<Integer>();
            List<byte[]> vals = new ArrayList<byte[]>();
            List<Integer> missing = new ArrayList<Integer>();
            synchronized (o) {
                for (int a : attributes) {
                    byte[] v = o.values.get(a);
                    if (v != null) {
                        have.add(a);
                        vals.add(v);
                    } else if (attributeIn.containsKey(a)) {
                        missing.add(attributeIn.get(a));
                    }
                }
            }
            if (!have.isEmpty()) {
                int[] ids = new int[have.size()];
                for (int i = 0; i < ids.length; i++) {
                    ids[i] = have.get(i);
                }
                m.update(object, ids, vals.toArray(new byte[0][]), true);
            }
            EndpointListener l = listener;
            if (!missing.isEmpty() && l != null) {
                int[] ids = new int[missing.size()];
                for (int i = 0; i < ids.length; i++) {
                    ids[i] = missing.get(i);
                }
                l.provideRequested(J2jObjectEndpoint.this, object, ids);
            }
        }

        @Override
        public void snapshotRequested(String node) {
            J2jSessions.ObjectMember m = member;
            if (m == null) {
                return;
            }
            for (Map.Entry<Long, Own> e : own.entrySet()) {
                Own o = e.getValue();
                int[] ids;
                byte[][] vals;
                synchronized (o) {
                    ids = new int[o.values.size()];
                    vals = new byte[ids.length][];
                    int i = 0;
                    for (Map.Entry<Integer, byte[]> v : o.values.entrySet()) {
                        ids[i] = v.getKey();
                        vals[i++] = v.getValue();
                    }
                }
                m.createTo(node, e.getKey(), o.wireClass, o.name, ids, vals);
            }
        }

        @Override
        public void peerGone(J2jSessions.Peer peer) {
            EndpointListener l = listener;
            for (Remote r : new ArrayList<Remote>(remote.values())) {
                if (r.peer.equals(peer)) {
                    remote.remove(remoteKey(r.peer, r.object));
                    remoteByKey.remove(r.key);
                    stats.removedIn.incrementAndGet();
                    if (l != null) {
                        l.removed(J2jObjectEndpoint.this, r.key, null);
                    }
                }
            }
        }
    }

    /** For the gateway JSON: the session, its model, the other members, objects and counters. */
    public Map<String, Object> diagnostics() {
        Map<String, Object> d = new LinkedHashMap<String, Object>();
        d.put("session", session);
        d.put("kind", "objects");
        d.put("model", modelText);
        d.put("fingerprint", fingerprint);
        d.put("transport", cfg.isJ2jReliableOnly() ? "reliable" : "auto");
        d.put("ownobjects", own.size());
        d.put("remoteobjects", remote.size());
        d.put("unknownids", unknownIds.get());
        J2jSessions.ObjectMember m = member;
        if (m != null) {
            d.put("members", m.peers());
            d.put("sent", m.sent.get());
            d.put("received", m.received.get());
            d.put("copies", m.copies.get());
            d.put("notsent", m.notSent.get());
            d.put("unknownobjects", m.unknownObjects.get());
        }
        return d;
    }
}
