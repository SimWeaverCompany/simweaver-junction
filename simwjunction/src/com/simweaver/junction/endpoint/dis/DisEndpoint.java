package com.simweaver.junction.endpoint.dis;

import com.simweaver.common.dis.DisEntityId;
import com.simweaver.common.dis.DisEntityState;
import com.simweaver.common.dis.DisFormatException;
import com.simweaver.common.dis.DisNetwork;
import com.simweaver.common.dis.DisPduHeader;
import com.simweaver.common.dis.DisReader;
import com.simweaver.common.dis.DisToRpr;
import com.simweaver.common.dis.IemRprModel;
import com.simweaver.common.dis.RprModel;
import com.simweaver.common.dis.RprToDis;
import com.simweaver.common.dis.cdis.CdisCodec;
import com.simweaver.common.hla.codec.HValue;
import com.simweaver.common.hla.codec.HlaCodec;
import com.simweaver.common.iem.Attribute;
import com.simweaver.common.iem.ClassParameter;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.common.iem.InteractionClass;
import com.simweaver.common.iem.ObjectClass;
import com.simweaver.common.iem.Parameter;
import com.simweaver.junction.config.EndpointConfig;
import com.simweaver.junction.config.EndpointType;
import com.simweaver.junction.endpoint.Endpoint;
import com.simweaver.junction.endpoint.EndpointException;
import com.simweaver.junction.endpoint.EndpointListener;
import com.simweaver.junction.endpoint.EndpointState;
import com.simweaver.junction.endpoint.EndpointStats;
import com.simweaver.junction.endpoint.Interest;
import com.simweaver.junction.endpoint.NameCandidates;
import com.simweaver.junction.endpoint.PduPort;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A DIS 6 or DIS 7 endpoint: a DIS network (UDP port, interface, exercise) seen as an RPR FOM
 * v2.0 federation. Its {@link #model()} is the RPR v2 FOM ({@code endpoint.hla.foms}, by default
 * {@code RPR_FOM_v2.0_Link16_1516-2010.xml}); the router converts to and from the other
 * endpoints' FOMs like for any HLA endpoint.
 *
 * <h2>Receiving (DIS to the router)</h2>
 * Like Rift's DIS datasource ({@link DisToRpr}): entities, radios and emitters become objects
 * named by their DIS ids ({@code "1-2-3"}, {@code "1-2-3/radio-1"}, {@code "1-2-3/emitter-1"},
 * {@code "1-2-3/emitter-1/beam-2"}; Electromagnetic Emission PDUs give EmitterSystem and
 * RadarBeam / JammerBeam objects, as the RPR GRIM maps them), only changed attributes are passed
 * on, objects go away when deactivated or not heard from for {@code entitytimeoutsecs}. Like an
 * RTI, only subscribed classes are discovered: an object or interaction is handed over as the
 * nearest subscribed superclass, with the attributes / parameters that class has.
 *
 * <h2>Sending (the router to DIS)</h2>
 * {@link RprToDis}: objects of the router become entities (Entity State PDUs, sent on every
 * update and as a heartbeat every {@code heartbeatsecs}), radio transmitters and receivers,
 * and emitters: an entity's EmitterSystem objects and their beams become one Electromagnetic
 * Emission PDU for that entity, sent on every change (all its systems and beams) and as a
 * heartbeat. Interactions become Fire, Detonation, Collision, Signal and simulation management
 * PDUs. Removing an object sends it deactivated (entities), off (radios), or leaves it out of
 * the next Emission PDU (systems, beams; the last system gone: a PDU with no systems).
 *
 * <p>DIS names its objects by their entity ids, so the endpoint chooses the names, not the
 * router: an entity gets a provisional id of this endpoint ({@code siteid}-{@code appid}-n)
 * and, with {@code entityids=keep}, its EntityIdentifier once that is known and free; a radio
 * gets {@code <host>/radio-<RadioIndex>} once its host is known, an emitter system
 * {@code <host>/emitter-<EmitterIndex>}, a beam {@code <system>/beam-<BeamIdentifier>} (free
 * numbers when those attributes are missing). The router learns the final
 * name through {@link EndpointListener#renamed}; references to the old name still resolve here.
 *
 * <p>PDUs are sent from a socket of their own (an ephemeral port), so this endpoint recognises
 * its own broadcasts and multicasts when they come back, and drops them.
 *
 * <h2>Threads</h2>
 * The receiver thread (natives), the router's dispatcher (sending), and one ticker thread
 * (heartbeats, timeouts, and the registration callbacks, which are never made on the
 * dispatcher's own thread). Natives and sent objects have a lock each.
 */
public final class DisEndpoint implements Endpoint, PduPort {

    private static final Logger LOG = Logger.getLogger(DisEndpoint.class.getName());
    private static final int READ_TIMEOUT_MILLIS = 250;
    private static final int MAX_DATAGRAM = 65536;
    private static final long TICK_MILLIS = 1000;

    private static final String AGGREGATE = DisToRpr.BASE_ENTITY + ".AggregateEntity";
    private static final String RAW_BINARY = DisToRpr.RAW_BINARY_SIGNAL;

    private final EndpointConfig cfg;
    private final String gatewayId;
    private final String target;
    private final int protocolVersion;
    private final int exerciseIn;
    private final EndpointStats stats = new EndpointStats();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private final AtomicLong keys = new AtomicLong();
    private final ScheduledThreadPoolExecutor ticker;
    private final DisToRpr mapper6 = new DisToRpr(6);
    private final DisToRpr mapper7 = new DisToRpr(7);
    /** C-DIS endpoints: the encoder/decoder between the DIS 7 inside and the network; null for DIS. */
    private final CdisCodec cdis;

    private volatile InformationExchangeModel iem;
    private volatile HlaCodec codec;
    private volatile RprModel rpr;
    private volatile RprToDis writer;
    private volatile EndpointListener listener;
    private volatile EndpointState state = EndpointState.NEW;
    private volatile String stateMessage;
    private volatile boolean running;

    private volatile DisNetwork network;
    private volatile MulticastSocket rx;
    private volatile MulticastSocket tx;
    private volatile InetSocketAddress destination;
    private volatile Set<InetAddress> localAddresses = Collections.emptySet();
    private volatile Thread receiver;

    private volatile Interest publish = new Interest();
    private volatile Interest subscribe = new Interest();
    private final Map<String, Integer> subscribedObjectClass = new ConcurrentHashMap<String, Integer>();
    private final Map<String, Integer> subscribedInteraction = new ConcurrentHashMap<String, Integer>();

    // natives: DIS objects heard (receiver thread and ticker)
    private final Object inLock = new Object();
    private final Map<Long, Native> nativeEntities = new HashMap<Long, Native>();
    private final Map<String, Native> nativeRadios = new HashMap<String, Native>();
    private final Map<String, Native> nativeEmitters = new HashMap<String, Native>();          // systems and beams
    private final Map<String, Native> nativeIffs = new HashMap<String, Native>();
    /** IFF PDUs have a 10 s heartbeat (DIS): IFF systems are kept at least 2.4 times that. */
    private static final long IFF_TIMEOUT_MILLIS = 24_000;
    private final Map<String, Set<String>> nativeBeamsOf = new HashMap<String, Set<String>>(); // system -> beams
    private final Map<Long, Set<String>> nativeSystemsOf = new HashMap<Long, Set<String>>();   // host -> systems
    private final Set<Long> nativeIds = ConcurrentHashMap.newKeySet();

    // sent objects: the router's (dispatcher and ticker)
    private final Object outLock = new Object();
    private final Map<Long, Out> outByKey = new HashMap<Long, Out>();
    private final Map<String, Out> outByName = new HashMap<String, Out>();
    private final Map<Long, Out> outByEntityId = new HashMap<Long, Out>();
    private final Map<Long, Long> emissionSentAt = new HashMap<Long, Long>();     // host key -> last Emission PDU
    private final Map<Long, DisEntityId> emittingHosts = new HashMap<Long, DisEntityId>();
    private int nextEntityNumber;
    private int emissionEvents;
    private int iffEvents;

    /** The PDU relay router's sink: PDUs go there instead of the model; null otherwise. */
    private volatile PduSink relay;
    private final AtomicLong relayedIn = new AtomicLong();
    private final AtomicLong relayedOut = new AtomicLong();

    private final AtomicLong echoes = new AtomicLong();
    private final AtomicLong otherExercise = new AtomicLong();
    private final AtomicLong malformed = new AtomicLong();

    /**
     * @param cfg       the endpoint configuration (DIS6 or DIS7)
     * @param target    the exercise id to receive ("0" = all); also the one sent with (0: the configured one, else 1)
     * @param gatewayId the gateway's id (thread names, logs)
     */
    public DisEndpoint(EndpointConfig cfg, String target, String gatewayId) {
        if (cfg.getType() == null || !cfg.getType().isDis()) {
            throw new IllegalArgumentException("not a DIS endpoint: " + cfg.getType());
        }
        this.cfg = cfg;
        this.gatewayId = gatewayId;
        this.target = target == null ? "" : target.trim();
        this.protocolVersion = cfg.getType() == EndpointType.DIS6 ? 6 : 7;
        this.cdis = cfg.getType().isCdis() ? new CdisCodec(cfg.getCdisSettings()) : null;
        int ex;
        try {
            ex = this.target.isEmpty() ? cfg.getDisExerciseId() : Integer.parseInt(this.target.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            ex = -1;
        }
        this.exerciseIn = ex;
        ScheduledThreadPoolExecutor t = new ScheduledThreadPoolExecutor(1, r -> {
            Thread th = new Thread(r, "junction-" + gatewayId + "-" + cfg.getId() + "-tick");
            th.setDaemon(true);
            return th;
        });
        t.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.ticker = t;
    }

    // ================================================================== Endpoint

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
        return cfg.getType();
    }

    @Override
    public InformationExchangeModel model() {
        return iem;
    }

    @Override
    public String target() {
        return target;
    }

    @Override
    public EndpointState state() {
        return state;
    }

    @Override
    public String stateMessage() {
        return stateMessage;
    }

    @Override
    public EndpointStats stats() {
        return stats;
    }

    /** @return where PDUs go, e.g. "255.255.255.255:3000" (after open) */
    public String destinationText() {
        InetSocketAddress d = destination;
        return d == null ? "" : d.getAddress().getHostAddress() + ":" + d.getPort();
    }

    /** @return the exercise id PDUs are sent with (after open) */
    public int sendExercise() {
        RprToDis w = writer;
        return w == null ? 0 : w.exercise();
    }

    @Override
    public void open(EndpointListener l) throws EndpointException {
        this.listener = l;
        setState(EndpointState.CONNECTING, null, false);
        if (exerciseIn < 0 || exerciseIn > 255) {
            fail(new EndpointException(EndpointException.CONFIG_INVALID, "endpoint " + id() + ": exercise must be 0-255, got '" + target + "'", null));
        }
        try {
            iem = InformationExchangeModel.fromHlaEvolved(cfg.getFomPaths());
            codec = new HlaCodec(iem);
            rpr = new IemRprModel(iem);
        } catch (Exception e) {
            fail(new EndpointException(EndpointException.FOM_INVALID, "endpoint " + id() + ": " + e.getMessage(), e));
        }
        if (iem.getObjectClass(DisToRpr.BASE_ENTITY) == null) {
            fail(new EndpointException(EndpointException.FOM_INVALID, "endpoint " + id() + ": the FOM has no "
                    + DisToRpr.BASE_ENTITY + " (DIS endpoints need the RPR FOM v2.0)", null));
        }
        int sendExercise = exerciseIn != 0 ? exerciseIn : cfg.getDisExerciseId() != 0 ? cfg.getDisExerciseId() : 1;
        writer = new RprToDis(protocolVersion, sendExercise, cfg.getDisSiteId(), cfg.getDisApplicationId());
        try {
            network = DisNetwork.parse(cfg.getDisInterface(), cfg.getDisPort());
            destination = network.destination(cfg.getDisSendAddress());
        } catch (IllegalArgumentException e) {
            fail(new EndpointException(EndpointException.CONFIG_INVALID, "endpoint " + id() + ": " + e.getMessage(), e));
        }
        try {
            rx = network.open(READ_TIMEOUT_MILLIS);
            tx = network.openSender(cfg.getDisTtl());
        } catch (IOException e) {
            closeSockets();
            fail(new EndpointException(EndpointException.CONNECT_FAILED, "endpoint " + id() + ": cannot use " + network + ": " + e.getMessage(), e));
        }
        localAddresses = localAddresses();
        if (network.note() != null) {
            LOG.info("Junction " + gatewayId + "/" + id() + ": " + network.note());
        }
        running = true;
        Thread t = new Thread(this::receiveLoop, "junction-" + gatewayId + "-" + cfg.getId() + "-rx");
        t.setDaemon(true);
        receiver = t;
        t.start();
        ticker.scheduleWithFixedDelay(this::tick, TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS);
        LOG.info("Junction " + gatewayId + "/" + id() + ": " + protocolLabel() + " on " + network + ", exercise "
                + (exerciseIn == 0 ? "any" : String.valueOf(exerciseIn)) + ", sending to " + destinationText()
                + " as exercise " + sendExercise);
        setState(EndpointState.JOINED, null, false);
    }

    /** @return "DIS 6", "DIS 7" or "C-DIS (full updates)" */
    private String protocolLabel() {
        return cdis == null ? "DIS " + protocolVersion
                : "C-DIS (" + cdis.settings().getUpdateMode().name().toLowerCase(java.util.Locale.ROOT) + " updates)";
    }

    private void fail(EndpointException e) throws EndpointException {
        setState(EndpointState.FAILED, e.getMessage(), false);
        throw e;
    }

    private static Set<InetAddress> localAddresses() {
        Set<InetAddress> s = new HashSet<InetAddress>();
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                s.addAll(Collections.list(n.getInetAddresses()));
            }
        } catch (SocketException ignored) {
            // loopback only
        }
        s.add(InetAddress.getLoopbackAddress());
        return s;
    }

    @Override
    public void declare(Interest pub, Interest sub) {
        this.publish = pub;
        this.subscribe = sub;
        subscribedObjectClass.clear();
        subscribedInteraction.clear();
    }

    // ================================================================== sending: objects

    private enum Kind { ENTITY, TRANSMITTER, RECEIVER, EMITTER, BEAM, DESIGNATOR, IFF }

    private static final String EMITTER_BEAM = "HLAobjectRoot.EmitterBeam";

    /** An object of the router sent to DIS. */
    private static final class Out {
        final long gwId;
        final long key;
        final int objectClass;
        final Kind kind;
        final Map<String, HValue> values = new HashMap<String, HValue>();
        final Set<String> aliases = new HashSet<String>();
        String name;
        DisEntityId id;          // entity: its id; radio, emitter: the host (beam: unused)
        Out hostOut;             // radio, emitter: the host when it is one of ours
        Out systemOut;           // beam: its emitter system (one of ours)
        int radioId;             // radio: radio number; emitter: emitter id number; beam: beam id number;
                                 // IFF: system designator + 1; designator: 1 once placed
        boolean jammer;          // beam: a JammerBeam
        String className;        // IFF: the object's class (decides the parameters)
        int iffType;             // IFF: the system type it is sent with
        boolean sent;
        long lastSent;

        Out(long gwId, long key, int objectClass, Kind kind) {
            this.gwId = gwId;
            this.key = key;
            this.objectClass = objectClass;
            this.kind = kind;
        }

        DisEntityId host() {
            if (systemOut != null) {
                return systemOut.host();
            }
            return hostOut != null ? hostOut.id : id;
        }
    }

    private static Kind kindOf(String className) {
        if (className.startsWith(DisToRpr.BASE_ENTITY) && !className.startsWith(AGGREGATE)) {
            return Kind.ENTITY;
        }
        if (className.startsWith(DisToRpr.RADIO_TRANSMITTER)) {
            return Kind.TRANSMITTER;
        }
        if (className.startsWith(DisToRpr.RADIO_RECEIVER)) {
            return Kind.RECEIVER;
        }
        if (className.startsWith(DisToRpr.EMITTER_SYSTEM)) {
            return Kind.EMITTER;
        }
        if (className.equals(EMITTER_BEAM) || className.startsWith(EMITTER_BEAM + ".")) {
            return Kind.BEAM;
        }
        if (className.startsWith(DisToRpr.DESIGNATOR)) {
            return Kind.DESIGNATOR;
        }
        if (className.equals(DisToRpr.IFF) || className.startsWith(DisToRpr.IFF + ".")) {
            return Kind.IFF;
        }
        return null;
    }

    @Override
    public void createObject(long gwId, int objectClass, NameCandidates names) {
        ObjectClass oc = iem == null ? null : iem.getObjectClass(objectClass);
        Kind kind = oc == null ? null : kindOf(oc.getQualifiedName());
        if (!state.isUp() || kind == null) {
            stats.registrationFailures.incrementAndGet();
            String reason = !state.isUp() ? "endpoint not open"
                    : "DIS has no PDU for " + (oc == null ? "class " + objectClass : oc.getQualifiedName());
            deliver(l -> l.registrationFailed(this, gwId, reason));
            return;
        }
        Out o = new Out(gwId, keys.incrementAndGet(), objectClass, kind);
        synchronized (outLock) {
            if (kind == Kind.ENTITY) {
                o.id = allocateEntityId();
                o.name = o.id.text();
                outByEntityId.put(o.id.key(), o);
            } else {
                String k = kind.name().toLowerCase(java.util.Locale.ROOT);
                o.name = id() + "-" + k + "-" + o.key;          // until the host (system) is known
                o.jammer = kind == Kind.BEAM && oc.getQualifiedName().startsWith(DisToRpr.JAMMER_BEAM);
                o.className = oc.getQualifiedName();
            }
            outByKey.put(o.key, o);
            outByName.put(o.name, o);
        }
        stats.registeredOut.incrementAndGet();
        String name = o.name;
        deliver(l -> l.registered(this, gwId, o.key, name));
    }

    /** A free id of this endpoint: siteid-appid-n. Under outLock. */
    private DisEntityId allocateEntityId() {
        for (int i = 0; i < 65534; i++) {
            nextEntityNumber = nextEntityNumber >= 65534 ? 1 : nextEntityNumber + 1;
            DisEntityId id = new DisEntityId(cfg.getDisSiteId(), cfg.getDisApplicationId(), nextEntityNumber);
            if (!outByEntityId.containsKey(id.key()) && !nativeIds.contains(id.key())) {
                return id;
            }
        }
        throw new IllegalStateException("no free entity numbers");
    }

    @Override
    public void updateObject(long key, int[] attributes, byte[][] values, byte[] tag) {
        long now = System.currentTimeMillis();
        List<byte[]> pdus = new ArrayList<byte[]>(2);
        synchronized (outLock) {
            Out o = outByKey.get(key);
            if (o == null) {
                return;
            }
            for (int i = 0; i < attributes.length; i++) {
                Attribute a = iem.getAttribute(attributes[i]);
                if (a == null || values[i] == null) {
                    continue;
                }
                HValue v = decode(a.getDataType(), values[i], a.getName());
                if (v != null) {
                    o.values.put(a.getName(), v);
                }
            }
            if (o.kind == Kind.ENTITY) {
                adoptEntityIdentifier(o, pdus, now);
                if (RprToDis.canSendEntity(o.values::get)) {
                    pdus.add(writer.entityState(o.id, o.values::get, false, now));
                    o.sent = true;
                    o.lastSent = now;
                }
            } else if (o.kind == Kind.EMITTER || o.kind == Kind.BEAM) {
                Set<Long> hosts = new HashSet<Long>();
                if (o.kind == Kind.EMITTER) {
                    placeEmitter(o, hosts);
                } else {
                    placeBeam(o, hosts);
                }
                DisEntityId h = o.host();
                if (h != null) {
                    hosts.add(h.key());
                    emittingHosts.put(h.key(), h);
                }
                emissions(hosts, false, pdus, now);
            } else if (o.kind == Kind.DESIGNATOR || o.kind == Kind.IFF) {
                placeSystem(o, pdus, now);
                byte[] p = objectPdu(o, false, now);
                if (p != null) {
                    pdus.add(p);
                    o.sent = true;
                    o.lastSent = now;
                }
            } else {
                placeRadio(o, pdus, now);
                byte[] p = radioPdu(o, false, now);
                if (p != null) {
                    pdus.add(p);
                    o.sent = true;
                    o.lastSent = now;
                }
            }
        }
        for (byte[] p : pdus) {
            send(p);
        }
        if (!pdus.isEmpty()) {
            stats.updatesOut.incrementAndGet();
        }
    }

    /** entityids=keep: takes the HLA EntityIdentifier as the DIS id when it is free. Under outLock. */
    private void adoptEntityIdentifier(Out o, List<byte[]> pdus, long now) {
        if (!cfg.isDisKeepEntityIds()) {
            return;
        }
        DisEntityId wanted = RprToDis.entityId(o.values.get("EntityIdentifier"));
        if (wanted.isNone() || wanted.entity == 0 || wanted.equals(o.id)) {
            return;
        }
        Out other = outByEntityId.get(wanted.key());
        if ((other != null && other != o) || nativeIds.contains(wanted.key())) {
            warnOnce("idtaken " + wanted.text(), "entity id " + wanted.text() + " is already on the DIS network: "
                    + o.name + " keeps its id");
            return;
        }
        if (o.sent) {                                   // the old id leaves the exercise
            pdus.add(writer.entityState(o.id, o.values::get, true, now));
        }
        outByEntityId.remove(o.id.key());
        o.id = wanted;
        outByEntityId.put(wanted.key(), o);
        rename(o, wanted.text());
        // radios and emitters hosted by it get the new host id
        Set<Long> hosts = new HashSet<Long>();
        for (Out r : outByKey.values()) {
            if (r.hostOut == o && (r.kind == Kind.TRANSMITTER || r.kind == Kind.RECEIVER)) {
                placeRadio(r, pdus, now);
            } else if (r.hostOut == o && (r.kind == Kind.DESIGNATOR || r.kind == Kind.IFF)) {
                placeSystem(r, pdus, now);
            } else if (r.hostOut == o && r.kind == Kind.EMITTER) {
                placeEmitter(r, hosts);
            }
        }
        if (!hosts.isEmpty()) {
            hosts.add(o.id.key());
            emittingHosts.put(o.id.key(), o.id);
            emissions(hosts, false, pdus, now);
        }
    }

    /** Gives an object a new name; the old one stays an alias. Under outLock. */
    private void rename(Out o, String newName) {
        if (newName.equals(o.name)) {
            return;
        }
        o.aliases.add(o.name);
        o.name = newName;
        outByName.put(newName, o);
        String n = newName;
        long k = o.key;
        deliver(l -> l.renamed(this, k, n));
    }

    /** Works out the host and radio number of a transmitter or receiver; renames it when they change. Under outLock. */
    private void placeRadio(Out o, List<byte[]> pdus, long now) {
        HValue hostRef = o.values.get("HostObjectIdentifier");
        Out hostOut = hostRef == null ? null : outByName.get(hostRef.asTrimmedText());
        DisEntityId host = null;
        if (hostOut != null && hostOut.kind == Kind.ENTITY) {
            host = hostOut.id;
        } else {
            hostOut = null;
            if (hostRef != null) {
                host = RprToDis.parseEntityName(hostRef.asTrimmedText());
            }
            if (host == null) {
                DisEntityId e = RprToDis.entityId(o.values.get("EntityIdentifier"));
                host = e.isNone() ? null : e;
            }
        }
        if (host == null) {
            return;
        }
        HValue index = o.values.get("RadioIndex");
        int radio = index != null ? (int) index.asLong() & 0xFFFF : o.radioId != 0 ? o.radioId : freeRadioId(host, o);
        boolean moved = o.sent && (!host.equals(o.host()) || radio != o.radioId);
        if (moved) {
            byte[] off = radioPdu(o, true, now);          // the old radio goes off
            if (off != null) {
                pdus.add(off);
            }
        }
        o.hostOut = hostOut;
        o.id = host;
        o.radioId = radio;
        rename(o, o.kind == Kind.TRANSMITTER ? DisToRpr.transmitterName(host, radio) : DisToRpr.receiverName(host, radio));
    }

    private int freeRadioId(DisEntityId host, Out self) {
        for (int r = 1; r < 65535; r++) {
            boolean used = false;
            for (Out o : outByKey.values()) {
                if (o != self && o.kind == self.kind && o.radioId == r && host.equals(o.host())) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                return r;
            }
        }
        return 1;
    }

    /** The Transmitter / Receiver PDU of a radio, or null while its host is not known. Under outLock. */
    private byte[] radioPdu(Out o, boolean off, long now) {
        DisEntityId host = o.host();
        if (host == null || o.radioId == 0) {
            return null;
        }
        if (o.kind == Kind.TRANSMITTER) {
            return writer.transmitter(host, o.radioId, o.values::get, off, now);
        }
        DisEntityId txHost = null;
        int txRadio = 0;
        HValue ref = o.values.get("ReceivedTransmitterIdentifier");
        if (ref != null) {
            int[] r = resolveRadio(ref.asTrimmedText());
            if (r != null) {
                txHost = new DisEntityId(r[0], r[1], r[2]);
                txRadio = r[3];
            }
        }
        return writer.receiver(host, o.radioId, o.values::get, txHost, txRadio, off, now);
    }

    /** The host entity of a radio or an emitter system: ours, or by DIS name / EntityIdentifier. Under outLock. */
    private DisEntityId hostOf(Out o, Out[] hostOutHolder) {
        HValue hostRef = o.values.get("HostObjectIdentifier");
        Out hostOut = hostRef == null ? null : outByName.get(hostRef.asTrimmedText());
        if (hostOut != null && hostOut.kind == Kind.ENTITY) {
            hostOutHolder[0] = hostOut;
            return hostOut.id;
        }
        hostOutHolder[0] = null;
        DisEntityId host = hostRef == null ? null : RprToDis.parseEntityName(hostRef.asTrimmedText());
        if (host == null) {
            DisEntityId e = RprToDis.entityId(o.values.get("EntityIdentifier"));
            host = e.isNone() ? null : e;
        }
        return host;
    }

    /**
     * Works out the host of a designator or IFF system (and an IFF system's type and DIS 7 system
     * designator) and names it {@code <host>/designator} or {@code <host>/iff-<type>[-<n>]}. When
     * it moves, the old one is sent off. Under outLock.
     */
    private void placeSystem(Out o, List<byte[]> pdus, long now) {
        Out[] h = new Out[1];
        DisEntityId host = hostOf(o, h);
        if (host == null) {
            return;
        }
        int type = 0;
        int number = 1;
        if (o.kind == Kind.IFF) {
            HValue st = o.values.get("SystemType");
            type = st != null ? (int) st.asLong() & 0xFFFF : DisToRpr.iffSystemType(o.className);
            if (o.radioId != 0 && host.equals(o.host()) && type == o.iffType) {
                number = o.radioId;
            } else {
                number = freeIffNumber(host, type, o);
            }
        } else {
            for (Out x : outByKey.values()) {
                if (x != o && x.kind == Kind.DESIGNATOR && x.radioId != 0 && host.equals(x.host())) {
                    warnOnce("designators " + host.text(), "two designators on " + host.text() + ": DIS has one per entity, they share it");
                }
            }
        }
        boolean moved = o.sent && (!host.equals(o.host()) || type != o.iffType || number != o.radioId);
        if (moved) {
            byte[] off = objectPdu(o, true, now);           // the old one goes off
            if (off != null) {
                pdus.add(off);
            }
        }
        o.hostOut = h[0];
        o.id = host;
        o.iffType = type;
        o.radioId = number;
        rename(o, o.kind == Kind.IFF ? DisToRpr.iffName(host, type, number - 1) : DisToRpr.designatorName(host));
    }

    /** The first free DIS 7 system designator (+ 1) for an IFF system type on a host. Under outLock. */
    private int freeIffNumber(DisEntityId host, int type, Out self) {
        for (int n = 1; n < 256; n++) {
            boolean used = false;
            for (Out x : outByKey.values()) {
                if (x != self && x.kind == Kind.IFF && x.radioId == n && x.iffType == type && host.equals(x.host())) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                if (n > 1 && protocolVersion < 7) {
                    warnOnce("iff2 " + host.text(), "a second IFF system of type " + type + " on " + host.text()
                            + ": DIS 6 cannot tell them apart");
                }
                return n;
            }
        }
        return 1;
    }

    /** The PDU of an object (not emitters: those go per host), or null while it cannot be sent. Under outLock. */
    private byte[] objectPdu(Out o, boolean off, long now) {
        switch (o.kind) {
            case ENTITY:
                return writer.entityState(o.id, o.values::get, off, now);
            case TRANSMITTER:
            case RECEIVER:
                return radioPdu(o, off, now);
            case DESIGNATOR:
                return o.radioId == 0 || o.host() == null ? null : writer.designator(o.host(), o.values::get, this::resolveEntity, off, now);
            case IFF:
                if (o.radioId == 0 || o.host() == null) {
                    return null;
                }
                iffEvents = (iffEvents + 1) & 0xFFFF;
                return writer.iff(o.host(), o.className, o.radioId - 1, o.values::get, iffEvents, off, now);
            default:
                return null;
        }
    }

    /**
     * Works out the host and emitter id number of an emitter system and names it
     * {@code <host>/emitter-<n>}; its beams follow. Hosts whose Emission PDU changes are added
     * to {@code hosts}. Under outLock.
     */
    private void placeEmitter(Out o, Set<Long> hosts) {
        Out[] h = new Out[1];
        DisEntityId host = hostOf(o, h);
        if (host == null) {
            return;
        }
        HValue index = o.values.get("EmitterIndex");
        int number = index != null && (index.asLong() & 0xFF) != 0 ? (int) index.asLong() & 0xFF
                : o.radioId != 0 && host.equals(o.host()) ? o.radioId : freeNumber(Kind.EMITTER, host, null, o);
        DisEntityId before = o.radioId == 0 ? null : o.host();
        if (before != null && !before.equals(host)) {
            hosts.add(before.key());                      // it leaves the old host's PDU
        }
        o.hostOut = h[0];
        o.id = host;
        o.radioId = number;
        emittingHosts.put(host.key(), host);
        rename(o, DisToRpr.emitterName(host, number));
        for (Out b : outByKey.values()) {                 // beams: renamed with it, or waiting for it
            if (b.kind == Kind.BEAM && (b.systemOut == o || b.systemOut == null)) {
                placeBeam(b, hosts);
            }
        }
    }

    /** Finds a beam's emitter system (EmitterSystemIdentifier) and names it {@code <system>/beam-<n>}. Under outLock. */
    private void placeBeam(Out o, Set<Long> hosts) {
        HValue ref = o.values.get("EmitterSystemIdentifier");
        Out sys = ref == null ? null : outByName.get(ref.asTrimmedText());
        if (sys == null || sys.kind != Kind.EMITTER || sys.radioId == 0) {
            if (o.systemOut != null && o.radioId != 0 && o.host() != null) {
                hosts.add(o.host().key());               // it leaves its old system
            }
            o.systemOut = null;
            o.radioId = 0;
            return;
        }
        if (o.systemOut != null && o.systemOut != sys && o.host() != null) {
            hosts.add(o.host().key());
        }
        HValue bid = o.values.get("BeamIdentifier");
        int number = bid != null && (bid.asLong() & 0xFF) != 0 ? (int) bid.asLong() & 0xFF
                : o.radioId != 0 && o.systemOut == sys ? o.radioId : freeNumber(Kind.BEAM, null, sys, o);
        o.systemOut = sys;
        o.radioId = number;
        rename(o, DisToRpr.beamName(sys.name, number));
    }

    /** A free emitter id on a host, or beam id in a system (1-255). Under outLock. */
    private int freeNumber(Kind kind, DisEntityId host, Out system, Out self) {
        for (int n = 1; n < 256; n++) {
            boolean used = false;
            for (Out x : outByKey.values()) {
                if (x == self || x.kind != kind || x.radioId != n) {
                    continue;
                }
                if (kind == Kind.EMITTER ? host.equals(x.host()) : x.systemOut == system) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                return n;
            }
        }
        return 255;
    }

    /** Adds the Emission PDU of each host (all its systems and beams; none: an empty one once). Under outLock. */
    private void emissions(Set<Long> hostKeys, boolean heartbeat, List<byte[]> pdus, long now) {
        for (Long key : hostKeys) {
            DisEntityId host = emittingHosts.get(key);
            if (host == null) {
                continue;
            }
            List<RprToDis.EmitterOut> systems = new ArrayList<RprToDis.EmitterOut>();
            List<Out> members = new ArrayList<Out>();
            for (Out sys : outByKey.values()) {
                if (sys.kind != Kind.EMITTER || sys.radioId == 0 || !host.equals(sys.host())) {
                    continue;
                }
                List<RprToDis.BeamOut> beams = new ArrayList<RprToDis.BeamOut>();
                for (Out b : outByKey.values()) {
                    if (b.kind == Kind.BEAM && b.systemOut == sys && b.radioId != 0) {
                        beams.add(new RprToDis.BeamOut(b.radioId, b.values::get, b.jammer));
                        members.add(b);
                    }
                }
                beams.sort((x, y) -> Integer.compare(x.beamId, y.beamId));
                systems.add(new RprToDis.EmitterOut(sys.radioId, sys.values::get, beams));
                members.add(sys);
            }
            systems.sort((x, y) -> Integer.compare(x.emitterId, y.emitterId));
            boolean wasSent = emissionSentAt.containsKey(key);
            if (systems.isEmpty() && !wasSent) {
                emittingHosts.remove(key);
                continue;
            }
            emissionEvents = (emissionEvents + 1) & 0xFFFF;
            // no systems left: a heartbeat-type PDU with none, so receivers drop them all
            pdus.add(writer.emission(host, systems, heartbeat || systems.isEmpty(), emissionEvents, this::resolveEntity, now));
            for (Out m : members) {
                m.sent = true;
                m.lastSent = now;
            }
            if (systems.isEmpty()) {
                emissionSentAt.remove(key);
                emittingHosts.remove(key);
            } else {
                emissionSentAt.put(key, now);
            }
        }
    }

    /** {site, application, entity, radio} of a transmitter by name (ours, or a DIS name). Under outLock. */
    private int[] resolveRadio(String name) {
        Out o = outByName.get(name);
        if (o != null && o.kind != Kind.ENTITY && o.host() != null && o.radioId != 0) {
            DisEntityId h = o.host();
            return new int[] {h.site, h.application, h.entity, o.radioId};
        }
        return RprToDis.parseRadioName(name);
    }

    @Override
    public void deleteObject(long key, byte[] tag) {
        byte[] pdu = null;
        synchronized (outLock) {
            Out o = outByKey.remove(key);
            if (o == null) {
                return;
            }
            forget(o);
            if (o.kind == Kind.EMITTER || o.kind == Kind.BEAM) {
                DisEntityId h = o.host();
                if (o.kind == Kind.EMITTER) {
                    for (Out b : outByKey.values()) {         // its beams wait for a system again
                        if (b.systemOut == o) {
                            b.systemOut = null;
                            b.radioId = 0;
                        }
                    }
                }
                if (h != null) {
                    List<byte[]> pdus = new ArrayList<byte[]>(1);
                    emissions(Collections.singleton(h.key()), false, pdus, System.currentTimeMillis());
                    pdu = pdus.isEmpty() ? null : pdus.get(0);
                }
            } else if (o.sent) {
                pdu = objectPdu(o, true, System.currentTimeMillis());
            }
        }
        if (pdu != null) {
            send(pdu);
        }
        stats.deletedOut.incrementAndGet();
    }

    /** Removes an object's names and id. Under outLock. */
    private void forget(Out o) {
        outByName.remove(o.name);
        for (String a : o.aliases) {
            if (outByName.get(a) == o) {
                outByName.remove(a);
            }
        }
        if (o.kind == Kind.ENTITY && outByEntityId.get(o.id.key()) == o) {
            outByEntityId.remove(o.id.key());
        }
    }

    @Override
    public void requestUpdate(long key, int[] attributes) {
        // DIS has no such request: entities are sent periodically anyway
    }

    // ================================================================== sending: interactions

    @Override
    public void sendInteraction(int interactionClass, int[] parameters, byte[][] values, byte[] tag) {
        InteractionClass ic = iem == null ? null : iem.getInteractionClass(interactionClass);
        if (ic == null || !state.isUp()) {
            return;
        }
        final Map<String, HValue> p = new HashMap<String, HValue>();
        for (int i = 0; i < parameters.length; i++) {
            Parameter d = iem.getParameter(parameters[i]);
            if (d == null || values[i] == null) {
                continue;
            }
            HValue v = decode(d.getDataType(), values[i], d.getName());
            if (v != null) {
                p.put(d.getName(), v);
            }
        }
        String cls = ic.getQualifiedName();
        long now = System.currentTimeMillis();
        Function<String, HValue> get = p::get;
        byte[] pdu;
        synchronized (outLock) {
            RprToDis.Names names = this::resolveEntity;
            if (is(cls, DisToRpr.WEAPON_FIRE)) {
                pdu = writer.fire(get, names, now);
            } else if (is(cls, DisToRpr.MUNITION_DETONATION)) {
                pdu = writer.detonation(get, names, now);
            } else if (is(cls, DisToRpr.COLLISION)) {
                pdu = writer.collision(get, names, now);
            } else if (is(cls, DisToRpr.ENCODED_AUDIO_SIGNAL)) {
                pdu = audio(p.get("AudioData"), now);
            } else if (DisToRpr.link16MessageType(cls) >= 0 || is(cls, RAW_BINARY) || is(cls, DisToRpr.APPLICATION_SPECIFIC_SIGNAL)
                    || is(cls, DisToRpr.DATABASE_INDEX_SIGNAL)) {
                HValue host = p.get("HostRadioIndex");
                int[] r = host == null ? null : resolveRadio(host.asTrimmedText());
                int mti = DisToRpr.link16MessageType(cls);
                if (r == null) {
                    warnOnce("signalhost", "a " + cls + " without a known HostRadioIndex is not sent");
                    pdu = null;
                } else {
                    DisEntityId h = new DisEntityId(r[0], r[1], r[2]);
                    pdu = mti >= 0 ? writer.link16Signal(h, r[3], mti, get, now)                  // Link 16: SISO-STD-002
                            : is(cls, DisToRpr.DATABASE_INDEX_SIGNAL) ? writer.databaseIndexSignal(h, r[3], get, now)
                            : writer.binarySignal(h, r[3], is(cls, DisToRpr.APPLICATION_SPECIFIC_SIGNAL), get, now);
                }
            } else if (is(cls, DisToRpr.START_RESUME)) {
                pdu = writer.startResume(get, now);
            } else if (is(cls, DisToRpr.STOP_FREEZE)) {
                pdu = writer.stopFreeze(get, now);
            } else if (is(cls, DisToRpr.ACKNOWLEDGE)) {
                pdu = writer.acknowledge(get, now);
            } else {
                warnOnce("ix " + cls, "DIS has no PDU for " + cls + ": not sent");
                pdu = null;
            }
        }
        if (pdu != null) {
            send(pdu);
            stats.interactionsOut.incrementAndGet();
        }
    }

    private static boolean is(String cls, String base) {
        return cls.equals(base) || cls.startsWith(base + ".");
    }

    /** The DIS id of a named object: ours (aliases included), else a DIS name. Under outLock. */
    private DisEntityId resolveEntity(String name) {
        Out o = outByName.get(name);
        if (o != null && o.kind == Kind.ENTITY) {
            return o.id;
        }
        return RprToDis.parseEntityName(name);
    }

    /** Encoded audio: the transmitter by its StreamTag (ours, or a DIS one packed by DisToRpr). Under outLock. */
    private byte[] audio(HValue audio, long now) {
        if (audio == null) {
            return null;
        }
        HValue t = audio.field("StreamTag");
        long tag = t == null ? 0 : t.asLong();
        for (Out o : outByKey.values()) {
            if (o.kind == Kind.TRANSMITTER && o.host() != null && o.radioId != 0) {
                HValue st = o.values.get("StreamTag");
                if (st != null && st.asLong() == tag) {
                    return writer.encodedAudio(o.host(), o.radioId, audio, now);
                }
            }
        }
        int[] u = DisToRpr.unpackStreamTag(tag);
        DisEntityId host = new DisEntityId(u[0], u[1], u[2]);
        if (host.site == 0 || host.application == 0 || u[3] == 0) {
            warnOnce("streamtag", "audio with a StreamTag of no known transmitter is not sent (e.g. " + Long.toHexString(tag) + ")");
            return null;
        }
        return writer.encodedAudio(host, u[3], audio, now);
    }

    private HValue decode(com.simweaver.common.iem.DataType type, byte[] value, String what) {
        if (type == null) {
            return null;
        }
        try {
            return codec.decode(type, value);
        } catch (RuntimeException e) {
            warnOnce("decode " + what, "cannot decode " + what + " (" + type.getName() + "): " + e.getMessage());
            stats.sendErrors.incrementAndGet();
            return null;
        }
    }

    private void send(byte[] pdu) {
        MulticastSocket s = tx;
        InetSocketAddress d = destination;
        if (s == null || d == null || !running) {
            return;
        }
        if (cdis != null) {
            byte[] c = cdis.encode(pdu, 0, pdu.length, System.currentTimeMillis());
            if (c == null) {
                int type = pdu.length > 2 ? pdu[2] & 0xFF : -1;
                if (CdisCodec.isSupported(type)) {
                    stats.sendErrors.incrementAndGet();
                    warnOnce("cdis encode " + type, "a PDU of type " + type + " cannot be sent as C-DIS: "
                            + cdis.stats().getLastError());
                } else {
                    warnOnce("cdis type " + type, "PDUs of type " + type + " have no C-DIS form and are not sent");
                }
                return;
            }
            pdu = c;
        }
        try {
            s.send(new DatagramPacket(pdu, pdu.length, d));
            stats.markOut(pdu.length);
        } catch (IOException e) {
            stats.sendErrors.incrementAndGet();
            warnOnce("send", "sending to " + destinationText() + " fails: " + e.getMessage());
        }
    }

    // ================================================================== ticker: heartbeats and timeouts

    private void tick() {
        long now = System.currentTimeMillis();
        try {
            heartbeats(now);
            timeouts(now);
            if (cdis != null) {
                cdis.expire(now);
            }
            stats.sample(now);
        } catch (RuntimeException e) {
            warnOnce("tick", "heartbeat failed: " + e);
        }
    }

    private void heartbeats(long now) {
        long period = 1000L * cfg.getDisHeartbeatSecs();
        List<byte[]> pdus = new ArrayList<byte[]>();
        synchronized (outLock) {
            for (Out o : outByKey.values()) {
                if (!o.sent || now - o.lastSent < period || o.kind == Kind.EMITTER || o.kind == Kind.BEAM) {
                    continue;
                }
                byte[] p = objectPdu(o, false, now);
                if (p != null) {
                    pdus.add(p);
                    o.lastSent = now;
                }
            }
            Set<Long> due = new HashSet<Long>();
            for (Map.Entry<Long, Long> e : emissionSentAt.entrySet()) {
                if (now - e.getValue() >= period) {
                    due.add(e.getKey());
                }
            }
            emissions(due, true, pdus, now);
        }
        for (byte[] p : pdus) {
            send(p);
        }
    }

    private void timeouts(long now) {
        long limit = now - 1000L * Math.max(1, cfg.getDisEntityTimeoutSecs());
        List<Native> gone = new ArrayList<Native>();
        synchronized (inLock) {
            expire(nativeEntities.values().iterator(), limit, gone, true);
            expire(nativeRadios.values().iterator(), limit, gone, false);
            expire(nativeEmitters.values().iterator(), limit, gone, false);
            expire(nativeIffs.values().iterator(), Math.min(limit, now - IFF_TIMEOUT_MILLIS), gone, false);
        }
        for (Native n : gone) {
            if (n.objectClass != 0) {
                stats.removedIn.incrementAndGet();
                listener.removed(this, n.key, null);
            }
        }
    }

    private void expire(Iterator<Native> it, long limit, List<Native> gone, boolean entities) {
        while (it.hasNext()) {
            Native n = it.next();
            if (n.lastHeard < limit) {
                it.remove();
                if (entities && n.state != null) {
                    nativeIds.remove(n.state.id.key());
                }
                gone.add(n);
            }
        }
    }

    // ================================================================== receiving

    /** A DIS object heard on the network. */
    private static final class Native {
        final long key;
        final int objectClass;           // 0: no subscribed class, ignored
        final String className;
        final Map<String, byte[]> sent = new HashMap<String, byte[]>();
        long lastHeard;
        DisEntityState state;
        String wantedClass;              // emitters: the DIS-side class (RadarBeam or JammerBeam)

        Native(long key, int objectClass, String className) {
            this.key = key;
            this.objectClass = objectClass;
            this.className = className;
        }
    }

    private void receiveLoop() {
        byte[] buf = new byte[MAX_DATAGRAM];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        while (running) {
            MulticastSocket s = rx;
            if (s == null) {
                break;
            }
            try {
                packet.setLength(buf.length);
                s.receive(packet);
                InetSocketAddress from = (InetSocketAddress) packet.getSocketAddress();
                if (isOwn(from)) {
                    echoes.incrementAndGet();
                    continue;
                }
                String producer = from.getAddress().getHostAddress() + ":" + from.getPort();
                long now = System.currentTimeMillis();
                if (cdis != null) {
                    long errors = cdis.stats().getDecodeErrors();
                    for (byte[] pdu : cdis.decode(buf, packet.getOffset(), packet.getLength(), now)) {
                        handleDatagram(pdu, 0, pdu.length, producer, now);
                    }
                    if (cdis.stats().getDecodeErrors() != errors) {
                        malformed.incrementAndGet();
                        warnOnce("cdis decode", "a C-DIS datagram from " + producer + " cannot be decoded: "
                                + cdis.stats().getLastError());
                    }
                } else {
                    handleDatagram(buf, packet.getOffset(), packet.getLength(), producer, now);
                }
            } catch (SocketTimeoutException quiet) {
                // nothing heard
            } catch (IOException e) {
                if (running) {
                    LOG.log(Level.SEVERE, "Junction " + gatewayId + "/" + id() + ": receiving failed", e);
                    running = false;
                    closeSockets();
                    setState(EndpointState.FAILED, "receiving failed: " + e.getMessage(), true);
                }
                break;
            } catch (RuntimeException e) {
                warnOnce("crash " + e.getClass().getName(), "unexpected error on a datagram: " + e);
            }
        }
    }

    /** Our own PDUs coming back: from our sender's port on one of this computer's addresses. */
    private boolean isOwn(InetSocketAddress from) {
        MulticastSocket s = tx;
        return s != null && from.getPort() == s.getLocalPort() && localAddresses.contains(from.getAddress());
    }

    /**
     * One datagram: one PDU or several bundled ones. Receiver thread; package private for tests.
     */
    void handleDatagram(byte[] data, int offset, int length, String producer, long now) {
        int pos = offset;
        int end = offset + length;
        while (end - pos >= DisPduHeader.SIZE) {
            if (data[pos] == 0) {                                // padding between bundled PDUs
                int next = offset + (((pos - offset) + 8) & ~7);
                if (next <= pos || next >= end) {
                    break;
                }
                pos = next;
                continue;
            }
            DisPduHeader h = DisPduHeader.read(new DisReader(data, pos, end - pos));
            if (h.length < DisPduHeader.SIZE || h.length > end - pos) {
                malformed.incrementAndGet();
                warnOnce("length", "PDU type " + h.pduType + " from " + producer + " claims " + h.length
                        + " octets, the datagram has " + (end - pos) + ": rest of the datagram dropped");
                return;
            }
            PduSink sink = relay;
            if (sink != null) {
                relayPdu(sink, h, data, pos, producer);
            } else {
                handlePdu(h, new DisReader(data, pos, h.length), producer, now, h.length);
            }
            pos += h.length;
        }
    }

    // ================================================================== PDU relay (router.kind=pdu)

    @Override
    public void relayTo(PduSink sink) {
        this.relay = sink;
    }

    private void relayPdu(PduSink sink, DisPduHeader h, byte[] data, int pos, String producer) {
        if (!acceptsVersion(h.protocolVersion)) {
            warnOnce("version " + h.protocolVersion, "PDUs of protocol version " + h.protocolVersion + " (from " + producer
                    + ") are ignored by this DIS " + protocolVersion + " endpoint");
            stats.ignoredIn.incrementAndGet();
            return;
        }
        if (exerciseIn != 0 && h.exerciseId != exerciseIn) {
            otherExercise.incrementAndGet();
            return;
        }
        stats.markIn(h.length);
        (isObjectPdu(h.pduType) ? stats.updatesIn : stats.interactionsIn).incrementAndGet();
        relayedIn.incrementAndGet();
        try {
            sink.pdu(this, java.util.Arrays.copyOfRange(data, pos, pos + h.length));
        } catch (RuntimeException e) {
            warnOnce("relay", "relaying a PDU of type " + h.pduType + " failed: " + e);
        }
    }

    @Override
    public void sendPdu(byte[] pdu) {
        if (pdu.length < DisPduHeader.SIZE) {
            return;
        }
        byte[] out = pdu.clone();
        int from = out[0] & 0xFF;
        out[0] = (byte) protocolVersion;
        out[1] = (byte) sendExercise();
        if (protocolVersion < 7 && from >= 7) {
            out[10] = 0;                                        // the DIS 7 PDU status is padding in DIS 6
        }
        relayedOut.incrementAndGet();
        (isObjectPdu(out[2] & 0xFF) ? stats.updatesOut : stats.interactionsOut).incrementAndGet();
        send(out);
    }

    /** PDUs that carry the state of an object (counted as updates; the others as interactions). */
    private static boolean isObjectPdu(int type) {
        switch (type) {
            case DisPduHeader.ENTITY_STATE:
            case DisPduHeader.ENTITY_STATE_UPDATE:
            case DisPduHeader.ELECTROMAGNETIC_EMISSION:
            case DisPduHeader.DESIGNATOR:
            case DisPduHeader.TRANSMITTER:
            case DisPduHeader.RECEIVER:
            case DisPduHeader.IFF:
                return true;
            default:
                return false;
        }
    }

    private boolean acceptsVersion(int v) {
        return protocolVersion == 7 ? v == 6 || v == 7 : v == 5 || v == 6;
    }

    private void handlePdu(DisPduHeader h, DisReader r, String producer, long now, int octets) {
        if (!acceptsVersion(h.protocolVersion)) {
            warnOnce("version " + h.protocolVersion, "PDUs of protocol version " + h.protocolVersion + " (from " + producer
                    + ") are ignored by this DIS " + protocolVersion + " endpoint");
            stats.ignoredIn.incrementAndGet();
            return;
        }
        if (exerciseIn != 0 && h.exerciseId != exerciseIn) {
            otherExercise.incrementAndGet();
            return;
        }
        stats.markIn(octets);
        DisToRpr mapper = h.protocolVersion >= 7 ? mapper7 : mapper6;
        r.seek(DisPduHeader.SIZE);
        try {
            switch (h.pduType) {
                case DisPduHeader.ENTITY_STATE:
                    entityState(DisEntityState.read(r), mapper, producer, now);
                    break;
                case DisPduHeader.ENTITY_STATE_UPDATE:
                    entityStateUpdate(r, mapper, producer, now);
                    break;
                case DisPduHeader.FIRE:
                    interaction(mapper.fire(h, r), producer);
                    break;
                case DisPduHeader.DETONATION:
                    interaction(mapper.detonation(h, r), producer);
                    break;
                case DisPduHeader.COLLISION:
                    interaction(mapper.collision(h, r), producer);
                    break;
                case DisPduHeader.SIGNAL:
                    interaction(mapper.signal(h, r), producer);
                    break;
                case DisPduHeader.START_RESUME:
                    interaction(mapper.startResume(h, r), producer);
                    break;
                case DisPduHeader.STOP_FREEZE:
                    interaction(mapper.stopFreeze(h, r), producer);
                    break;
                case DisPduHeader.ACKNOWLEDGE:
                    interaction(mapper.acknowledge(h, r), producer);
                    break;
                case DisPduHeader.TRANSMITTER:
                    radio(mapper.transmitter(h, r), producer, now);
                    break;
                case DisPduHeader.RECEIVER:
                    radio(mapper.receiver(h, r), producer, now);
                    break;
                case DisPduHeader.ELECTROMAGNETIC_EMISSION:
                    emission(mapper.emission(h, r), producer, now);
                    break;
                case DisPduHeader.DESIGNATOR:
                    DisToRpr.Embedded d = mapper.designator(h, r);
                    embedded(d.className, d.instanceName, d.attributes, nativeRadios, producer, now);
                    break;
                case DisPduHeader.IFF:
                    DisToRpr.Embedded f = mapper.iff(h, r);
                    embedded(f.className, f.instanceName, f.attributes, nativeIffs, producer, now);
                    break;
                default:
                    stats.ignoredIn.incrementAndGet();
                    break;
            }
        } catch (DisFormatException | IllegalArgumentException e) {
            malformed.incrementAndGet();
            warnOnce("malformed " + h.pduType, "malformed PDU type " + h.pduType + " from " + producer + ": " + e.getMessage());
        }
    }

    private void entityState(DisEntityState s, DisToRpr mapper, String producer, long now) {
        Native n;
        boolean isNew = false;
        synchronized (inLock) {
            n = nativeEntities.get(s.id.key());
            if (s.isDeactivated()) {
                if (n != null) {
                    nativeEntities.remove(s.id.key());
                    nativeIds.remove(s.id.key());
                }
            } else {
                if (n == null) {
                    n = create(DisToRpr.entityClass(s.type));
                    nativeEntities.put(s.id.key(), n);
                    nativeIds.add(s.id.key());
                    isNew = true;
                }
                n.lastHeard = now;
                n.state = s;
            }
        }
        if (s.isDeactivated()) {
            if (n != null && n.objectClass != 0) {
                stats.removedIn.incrementAndGet();
                listener.removed(this, n.key, null);
            }
            return;
        }
        if (n.objectClass == 0) {
            return;
        }
        if (isNew) {
            stats.discoveredIn.incrementAndGet();
            listener.discovered(this, n.key, n.objectClass, s.id.text(), producer);
        }
        publish(n, mapper.entityAttributes(s), producer);
    }

    private void entityStateUpdate(DisReader r, DisToRpr mapper, String producer, long now) {
        int at = r.position();
        DisEntityId eid = DisEntityId.read(r);
        r.seek(at);
        DisEntityState before;
        synchronized (inLock) {
            Native n = nativeEntities.get(eid.key());
            before = n == null ? null : n.state;
        }
        if (before == null) {                                        // created by an Entity State PDU only
            stats.ignoredIn.incrementAndGet();
            return;
        }
        entityState(before.withUpdate(r), mapper, producer, now);
    }

    private void radio(DisToRpr.Radio radio, String producer, long now) {
        embedded(radio.className, radio.instanceName, radio.attributes, nativeRadios, producer, now);
    }

    /** A radio, designator or IFF system heard: one native per name, updated with what changed. */
    private void embedded(String className, String instanceName, Map<String, byte[]> attributes, Map<String, Native> table,
                          String producer, long now) {
        Native n;
        boolean isNew = false;
        synchronized (inLock) {
            n = table.get(instanceName);
            if (n == null) {
                n = create(className);
                table.put(instanceName, n);
                isNew = true;
            }
            n.lastHeard = now;
        }
        if (n.objectClass == 0) {
            return;
        }
        if (isNew) {
            stats.discoveredIn.incrementAndGet();
            listener.discovered(this, n.key, n.objectClass, instanceName, producer);
        }
        publish(n, attributes, producer);
    }

    /**
     * Emitter systems and beams of one entity. A beam of a listed system that the PDU no longer
     * lists is removed (switched off); a heartbeat (all systems listed) also removes the systems
     * it leaves out. Everything else times out like the entities.
     */
    private void emission(DisToRpr.Emission e, String producer, long now) {
        List<Native> gone = new ArrayList<Native>();
        List<Object[]> seen = new ArrayList<Object[]>();          // {Native, isNew, Embedded}
        synchronized (inLock) {
            Set<String> systems = nativeSystemsOf.computeIfAbsent(e.host.key(), k -> new HashSet<String>());
            Set<String> listed = new HashSet<String>();
            for (DisToRpr.Embedded sys : e.systems) {
                seen.add(touchEmitter(sys, now, gone));
                listed.add(sys.instanceName);
                systems.add(sys.instanceName);
                Set<String> beams = nativeBeamsOf.computeIfAbsent(sys.instanceName, k -> new HashSet<String>());
                Set<String> active = new HashSet<String>();
                for (DisToRpr.Embedded b : e.beams.getOrDefault(sys.instanceName, Collections.<DisToRpr.Embedded>emptyList())) {
                    seen.add(touchEmitter(b, now, gone));
                    active.add(b.instanceName);
                }
                for (String old : beams) {
                    if (!active.contains(old)) {
                        Native n = nativeEmitters.remove(old);
                        if (n != null) gone.add(n);
                    }
                }
                beams.clear();
                beams.addAll(active);
            }
            if (e.complete) {
                for (Iterator<String> it = systems.iterator(); it.hasNext(); ) {
                    String sys = it.next();
                    if (listed.contains(sys)) continue;
                    it.remove();
                    for (String b : nativeBeamsOf.getOrDefault(sys, Collections.<String>emptySet())) {
                        Native n = nativeEmitters.remove(b);
                        if (n != null) gone.add(n);
                    }
                    nativeBeamsOf.remove(sys);
                    Native n = nativeEmitters.remove(sys);
                    if (n != null) gone.add(n);
                }
            }
        }
        // outside the lock: removals first (a renamed-class beam goes before it comes back), then
        // systems before their beams (seen is in that order)
        for (Native n : gone) {
            if (n.objectClass != 0) {
                stats.removedIn.incrementAndGet();
                listener.removed(this, n.key, null);
            }
        }
        for (Object[] x : seen) {
            Native n = (Native) x[0];
            DisToRpr.Embedded o = (DisToRpr.Embedded) x[2];
            if (n.objectClass == 0) continue;
            if ((Boolean) x[1]) {
                stats.discoveredIn.incrementAndGet();
                listener.discovered(this, n.key, n.objectClass, o.instanceName, producer);
            }
            publish(n, o.attributes, producer);
        }
    }

    /** {Native, isNew, Embedded} for an emitter system or beam heard now. Under inLock. */
    private Object[] touchEmitter(DisToRpr.Embedded o, long now, List<Native> gone) {
        Native n = nativeEmitters.get(o.instanceName);
        boolean isNew = false;
        if (n != null && !o.className.equals(n.wantedClass)) {
            gone.add(n);                                    // a beam that became a jammer (or back): a new object
            n = null;
        }
        if (n == null) {
            n = create(o.className);                        // new, or a beam that became a jammer (or back)
            n.wantedClass = o.className;
            nativeEmitters.put(o.instanceName, n);
            isNew = true;
        }
        n.lastHeard = now;
        return new Object[] {n, isNew, o};
    }

    /** A new native as its nearest subscribed class (or ignored: class 0). */
    private Native create(String wantedClass) {
        String cls = subscribedObjectClassFor(wantedClass);
        int id = cls == null ? 0 : rpr.objectClassId(cls);
        return new Native(keys.incrementAndGet(), id, cls);
    }

    /** The class itself or its nearest superclass that the router subscribed, or null. */
    private String subscribedObjectClassFor(String wanted) {
        Integer cached = subscribedObjectClass.get(wanted);
        if (cached == null) {
            int found = 0;
            String c = rpr.nearestObjectClass(wanted);
            ObjectClass oc = c == null ? null : iem.getObjectClass(c);
            while (oc != null) {
                if (!subscribe.attributesOf(oc.getId()).isEmpty()) {
                    found = oc.getId();
                    break;
                }
                oc = oc.getParentClass();
            }
            cached = found;
            subscribedObjectClass.put(wanted, cached);
        }
        return cached == 0 ? null : iem.getObjectClass(cached).getQualifiedName();
    }

    /** Hands the subscribed attributes that changed to the router. */
    private void publish(Native n, Map<String, byte[]> values, String producer) {
        Set<Integer> wanted = subscribe.attributesOf(n.objectClass);
        int[] ids = new int[values.size()];
        byte[][] raw = new byte[values.size()][];
        int count = 0;
        int octets = 0;
        synchronized (inLock) {
            for (Map.Entry<String, byte[]> e : values.entrySet()) {
                byte[] value = e.getValue();
                byte[] before = n.sent.get(e.getKey());
                if (before == null ? value.length == 0 : Arrays.equals(before, value)) {
                    continue;
                }
                int attr = rpr.attributeId(n.className, e.getKey());
                if (attr == RprModel.NO_ID || !wanted.contains(attr)) {
                    continue;
                }
                n.sent.put(e.getKey(), value);
                ids[count] = attr;
                raw[count] = value;
                octets += value.length;
                count++;
            }
        }
        if (count == 0) {
            return;
        }
        stats.updatesIn.incrementAndGet();
        listener.reflected(this, n.key, Arrays.copyOf(ids, count), Arrays.copyOf(raw, count), null, producer);
    }

    private void interaction(DisToRpr.Interaction ix, String producer) {
        if (ix == null) {
            return;
        }
        String cls = subscribedInteractionFor(ix.className);
        if (ix.fallback != null && (cls == null || !cls.equals(ix.className))) {
            InteractionClass named = interactionClassNamed(ix.simpleName());     // the Link-16 module elsewhere or spelled otherwise
            String exact = named == null ? null : subscribedInteractionFor(named.getQualifiedName());
            if (exact != null && exact.equals(named.getQualifiedName())) {
                cls = exact;
            } else {
                interaction(ix.fallback, producer);                               // no Link-16 module: raw binary
                return;
            }
        }
        if (cls == null) {
            stats.ignoredIn.incrementAndGet();
            return;
        }
        InteractionClass ic = iem.getInteractionClass(cls);
        Map<Integer, byte[]> out = new LinkedHashMap<Integer, byte[]>();
        for (Map.Entry<String, byte[]> e : ix.parameters.entrySet()) {
            ClassParameter p = ic.getParameter(e.getKey());
            if (p != null) {
                out.put(p.getId(), e.getValue());
            }
        }
        int[] ids = new int[out.size()];
        byte[][] vals = new byte[out.size()][];
        int i = 0;
        for (Map.Entry<Integer, byte[]> e : out.entrySet()) {
            ids[i] = e.getKey();
            vals[i] = e.getValue();
            i++;
        }
        stats.interactionsIn.incrementAndGet();
        listener.received(this, ic.getId(), ids, vals, null, producer);
    }

    /** An interaction class of the endpoint's model with this simple name (ignoring case), or null. */
    private InteractionClass interactionClassNamed(String simpleName) {
        for (InteractionClass c : iem.getInteractionClasses()) {
            String q = c.getQualifiedName();
            if (q.substring(q.lastIndexOf('.') + 1).equalsIgnoreCase(simpleName)) {
                return c;
            }
        }
        return null;
    }

    private String subscribedInteractionFor(String wanted) {
        Integer cached = subscribedInteraction.get(wanted);
        if (cached == null) {
            int found = 0;
            String c = wanted;
            while (c != null) {
                InteractionClass ic = iem.getInteractionClass(c);
                if (ic != null && subscribe.getInteractionClasses().contains(ic.getId())) {
                    found = ic.getId();
                    break;
                }
                int dot = c.lastIndexOf('.');
                c = dot < 0 ? null : c.substring(0, dot);
            }
            cached = found;
            subscribedInteraction.put(wanted, cached);
        }
        return cached == 0 ? null : iem.getInteractionClass(cached).getQualifiedName();
    }

    // ================================================================== closing and state

    @Override
    public void close(boolean deleteObjects) {
        if (state == EndpointState.CLOSED) {
            return;
        }
        if (deleteObjects && running) {
            List<byte[]> pdus = new ArrayList<byte[]>();
            long now = System.currentTimeMillis();
            synchronized (outLock) {
                for (Out o : outByKey.values()) {
                    if (o.sent && o.kind != Kind.EMITTER && o.kind != Kind.BEAM) {
                        byte[] p = objectPdu(o, true, now);
                        if (p != null) {
                            pdus.add(p);
                        }
                    }
                }
                for (Map.Entry<Long, DisEntityId> e : new ArrayList<Map.Entry<Long, DisEntityId>>(emittingHosts.entrySet())) {
                    if (emissionSentAt.containsKey(e.getKey())) {        // the host emits nothing any more
                        emissionEvents = (emissionEvents + 1) & 0xFFFF;
                        pdus.add(writer.emission(e.getValue(), Collections.<RprToDis.EmitterOut>emptyList(), true, emissionEvents,
                                this::resolveEntity, now));
                    }
                }
            }
            for (byte[] p : pdus) {
                send(p);
            }
        }
        running = false;
        ticker.shutdownNow();
        closeSockets();
        Thread t = receiver;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(2 * READ_TIMEOUT_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (outLock) {
            outByKey.clear();
            outByName.clear();
            outByEntityId.clear();
            emissionSentAt.clear();
            emittingHosts.clear();
        }
        synchronized (inLock) {
            nativeEntities.clear();
            nativeRadios.clear();
            nativeEmitters.clear();
            nativeIffs.clear();
            nativeBeamsOf.clear();
            nativeSystemsOf.clear();
            nativeIds.clear();
        }
        setState(EndpointState.CLOSED, null, true);
    }

    private void closeSockets() {
        MulticastSocket a = rx;
        MulticastSocket b = tx;
        rx = null;
        tx = null;
        if (a != null) {
            a.close();
        }
        if (b != null) {
            b.close();
        }
    }

    private void setState(EndpointState s, String message, boolean tell) {
        state = s;
        stateMessage = message;
        EndpointListener l = listener;
        if (tell && l != null) {
            l.stateChanged(this, s, message);
        }
    }

    /**
     * Calls the listener from the ticker thread: registrations happen on the router's
     * dispatcher, which must not wait for its own queue.
     */
    private void deliver(java.util.function.Consumer<EndpointListener> call) {
        EndpointListener l = listener;
        if (l == null) {
            return;
        }
        try {
            ticker.execute(() -> call.accept(l));
        } catch (RejectedExecutionException closing) {
            // closed meanwhile
        }
    }

    /** Diagnostics for the status page: echoes dropped, other exercises, malformed PDUs, objects. */
    public Map<String, Object> diagnostics() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("disversion", protocolVersion);
        if (cdis != null) {
            m.put("protocol", "C-DIS");
            m.put("cdissettings", cdis.settings().toMap());
            m.put("cdis", cdis.stats().toMap());
            m.put("cdisobjects", cdis.decoderObjects());
        }
        m.put("exercise", exerciseIn);
        m.put("sendexercise", sendExercise());
        m.put("destination", destinationText());
        m.put("echoes", echoes.get());
        m.put("otherexercise", otherExercise.get());
        m.put("malformed", malformed.get());
        if (relay != null) {
            m.put("relayedin", relayedIn.get());
            m.put("relayedout", relayedOut.get());
        }
        synchronized (inLock) {
            m.put("nativeentities", nativeEntities.size());
            m.put("nativeradios", nativeRadios.size());
            m.put("nativeemitters", nativeEmitters.size());
            m.put("nativeiffs", nativeIffs.size());
        }
        synchronized (outLock) {
            m.put("sentobjects", outByKey.size());
        }
        return m;
    }

    private void warnOnce(String kind, String message) {
        if (warned.add(kind)) {
            LOG.warning("Junction " + gatewayId + "/" + id() + ": " + message + " (reported once)");
        } else if (LOG.isLoggable(Level.FINE)) {
            LOG.fine("Junction " + gatewayId + "/" + id() + ": " + message);
        }
    }
}
