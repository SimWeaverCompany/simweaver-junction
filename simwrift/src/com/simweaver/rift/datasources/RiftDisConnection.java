package com.simweaver.rift.datasources;

import com.simweaver.common.SimwTimeStamp;
import com.simweaver.common.dis.cdis.CdisCodec;
import com.simweaver.common.hla.decoders.HlaDataDecoder;
import com.simweaver.common.hla.decoders.RawValues;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.common.json.Json;
import com.simweaver.rift.DatasourceConfig;
import com.simweaver.rift.DatasourceManager;
import com.simweaver.rift.api.DataSource;
import com.simweaver.rift.api.DataSourceCallbackSink;
import com.simweaver.rift.api.DatasourceFactory;
import com.simweaver.rift.datasources.dis.DisEndpoint;
import com.simweaver.rift.datasources.dis.DisEntityId;
import com.simweaver.rift.datasources.dis.DisEntityState;
import com.simweaver.rift.datasources.dis.DisFormatException;
import com.simweaver.rift.datasources.dis.DisPduHeader;
import com.simweaver.rift.datasources.dis.DisReader;
import com.simweaver.rift.datasources.dis.DisToRpr;
import com.simweaver.rift.datasources.dis.IemRprModel;
import com.simweaver.rift.datasources.dis.RprModel;
import com.simweaver.rift.db.RiftAttribute;
import com.simweaver.rift.db.RiftDatabase;
import com.simweaver.rift.db.RiftParameter;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A DIS datasource: receives DIS PDUs over UDP and feeds the {@link RiftDatabase} the way the
 * HLA datasources do, as RPR FOM objects and interactions (see {@link DisToRpr}). The FOM of the
 * configuration (the HLA 1516-2010 FOM settings) is the reference for the classes, attributes and
 * datatypes, so the decoder, references and the web UI work exactly as for HLA.
 *
 * <ul>
 * <li>An entity is created (discovered) with its first Entity State PDU. Its instance name is
 *     its entity id, {@code "site-application-entity"}; references to it use the same text.</li>
 * <li>Only changed attributes are passed on, like an HLA federate updating what changed.</li>
 * <li>An entity is removed when an Entity State PDU marks it deactivated (appearance State bit),
 *     or when nothing was heard from it for the entity timeout.</li>
 * <li>Radio transmitters and receivers are objects too, created and timed out the same way.</li>
 * <li>Only PDUs of the configured exercise (0 = every exercise) and of the accepted protocol
 *     versions are used.</li>
 * </ul>
 *
 * One receiver thread owns all state: it receives, maps, hands over to the database (which
 * queues and decodes on its own threads) and checks the timeouts once a second.
 */
public abstract class RiftDisConnection implements DataSource {

    private static final Logger LOG = Logger.getLogger(RiftDisConnection.class.getName());

    /** Entity timeout when the configuration has none: 12 s (DIS default: 2.4 x the 5 s heartbeat). */
    public static final int DEFAULT_ENTITY_TIMEOUT_SECS = 12;
    private static final int READ_TIMEOUT_MILLIS = 250;
    private static final long TIMEOUT_CHECK_MILLIS = 1000;
    private static final int MAX_DATAGRAM = 65536;

    private final UUID id = UUID.randomUUID();
    private final String name;
    private final DatasourceConfig config;
    private final String exerciseText;
    private final DataSourceCallbackSink callbackSink;
    private final RiftDatabase db;
    private final int version;
    private final DisToRpr mapper6 = new DisToRpr(6);
    private final DisToRpr mapper7 = new DisToRpr(7);
    /** C-DIS datasources (0.9.18): datagrams are decoded from C-DIS to DIS 7 first; null for DIS. */
    private final CdisCodec cdis;

    // set at start
    private volatile RprModel model;
    private volatile int exerciseId;
    private volatile long timeoutMillis;
    private volatile DisEndpoint endpoint;
    private volatile MulticastSocket socket;
    private volatile Thread receiver;
    private volatile boolean running;
    private final Map<Integer, String> interactionNames = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    // receiver thread only
    private final Map<Long, Tracked> entities = new HashMap<>();
    private final Map<String, Tracked> radios = new HashMap<>();
    private final Map<String, Tracked> emitters = new HashMap<>();             // systems and beams, by name
    private final Map<String, Tracked> designators = new HashMap<>();
    private final Map<String, Tracked> iffs = new HashMap<>();

    /** IFF PDUs have a 10 s heartbeat (DIS): IFF objects are kept at least 2.4 times that. */
    static final long IFF_TIMEOUT_MILLIS = 24_000;
    private final Map<String, java.util.Set<String>> beamsOf = new HashMap<>(); // system -> beam names
    private final Map<Long, java.util.Set<String>> systemsOf = new HashMap<>();   // host key -> system names
    private long lastTimeoutCheck;

    // statistics (any thread)
    private final AtomicLong datagrams = new AtomicLong();
    private final AtomicLong pdus = new AtomicLong();
    private final AtomicLong otherExercise = new AtomicLong();
    private final AtomicLong otherVersion = new AtomicLong();
    private final AtomicLong malformed = new AtomicLong();
    private final AtomicLong unhandled = new AtomicLong();
    private final AtomicLong unknownEntityUpdates = new AtomicLong();
    private final AtomicLong interactions = new AtomicLong();
    private final AtomicLong discovered = new AtomicLong();
    private final AtomicLong timedOut = new AtomicLong();
    private final AtomicLong deactivated = new AtomicLong();
    private final Map<Integer, AtomicLong> byType = new ConcurrentHashMap<>();

    /** An object this datasource created in the database. */
    private static final class Tracked {
        final int dbId;
        final String className;
        long lastHeard;
        DisEntityState state;                                  // entities only
        final Map<String, byte[]> sent = new HashMap<>();      // last value per attribute

        Tracked(int dbId, String className) {
            this.dbId = dbId;
            this.className = className;
        }
    }

    /**
     * @param config       the datasource configuration (DIS settings plus the 1516-2010 FOM settings)
     * @param exercise     the exercise id as text, in place of a federation name; null or blank =
     *                     the configuration's exercise id
     * @param callbackSink told when the socket fails while running
     * @param version      6 or 7
     */
    protected RiftDisConnection(DatasourceConfig config, String exercise, DataSourceCallbackSink callbackSink, int version) {
        this(config, exercise, callbackSink, version, null);
    }

    /**
     * @param cdis a C-DIS decoder: the datasource listens for C-DIS (SISO-STD-023-2024) and decodes
     *             it to DIS 7; null for DIS
     */
    protected RiftDisConnection(DatasourceConfig config, String exercise, DataSourceCallbackSink callbackSink, int version,
                                CdisCodec cdis) {
        if (config == null) throw new IllegalArgumentException("config must not be null");
        if (version != 6 && version != 7) throw new IllegalArgumentException("DIS version must be 6 or 7");
        if (cdis != null && version != 7) throw new IllegalArgumentException("C-DIS decodes to DIS 7");
        this.cdis = cdis;
        this.config = config;
        this.callbackSink = callbackSink;
        this.version = version;
        this.exerciseText = exercise == null || exercise.trim().isEmpty() ? String.valueOf(config.getDisExId()) : exercise.trim();
        this.name = DatasourceFactory.generateDataSourceName(config.getName(), "exercise " + exerciseText);
        this.db = new RiftDatabase(this.name);
    }

    /** The DIS version of this datasource (6 or 7). */
    public int getDisVersion() {
        return version;
    }

    /**
     * Protocol versions accepted. DIS 6: 5 and 6 (IEEE 1278.1-1995 and 1278.1a-1998 share the
     * PDU layouts used here). DIS 7: 6 and 7 (IEEE 1278.1-2012 is backward compatible, and DIS 7
     * networks often carry DIS 6 PDUs).
     */
    protected boolean acceptsVersion(int protocolVersion) {
        return version == 7 ? protocolVersion == 6 || protocolVersion == 7 : protocolVersion == 5 || protocolVersion == 6;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public RiftDatabase getDatabase() {
        return db;
    }

    @Override
    public Object toJson() {
        return Json.object().put("id", this.id).put("name", this.name);
    }

    // ================================================================== DataSource lifecycle

    /** Loads the FOM, opens the UDP socket and starts receiving. */
    @Override
    public synchronized DatasourceManager.OpResult connectJoinSub() {
        if (running) return new DatasourceManager.OpResult(true, "Already listening on " + endpoint);
        try {
            InformationExchangeModel iem = InformationExchangeModel.fromHlaEvolved(config.getHlafoms());
            db.setInformationExchangeModel(iem);
            db.setDecoder(HlaDataDecoder.create(iem));
            return start(new IemRprModel(iem));
        } catch (Exception e) {
            return new DatasourceManager.OpResult(false, "Loading the FOM failed: " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    /** Starts with an already loaded model (the database's decoder must be set). Package private for tests. */
    synchronized DatasourceManager.OpResult start(RprModel rprModel) {
        if (running) return new DatasourceManager.OpResult(true, "Already listening on " + endpoint);
        int ex;
        try {
            ex = Integer.parseInt(exerciseText);
        } catch (NumberFormatException e) {
            return new DatasourceManager.OpResult(false, "The DIS exercise id must be a number 0-255 (0 = all exercises), got '" + exerciseText + "'");
        }
        if (ex < 0 || ex > 255) {
            return new DatasourceManager.OpResult(false, "The DIS exercise id must be 0-255 (0 = all exercises), got " + ex);
        }
        if (rprModel.objectClassId(DisToRpr.BASE_ENTITY) == RprModel.NO_ID) {
            return new DatasourceManager.OpResult(false, "The FOM has no " + DisToRpr.BASE_ENTITY
                    + ": a DIS datasource needs the RPR FOM in its HLA 1516-2010 FOM settings");
        }
        DisEndpoint ep;
        try {
            ep = DisEndpoint.parse(config.getDisInterface(), config.getDisPort());
        } catch (IllegalArgumentException e) {
            return new DatasourceManager.OpResult(false, e.getMessage());
        }
        MulticastSocket s;
        try {
            s = ep.open(READ_TIMEOUT_MILLIS);
        } catch (IOException | RuntimeException e) {
            return new DatasourceManager.OpResult(false, "Cannot listen on " + ep + ": " + e.getClass().getName() + ": " + e.getMessage());
        }
        if (ep.note() != null) LOG.info("DIS datasource " + name + ": " + ep.note());

        int secs = config.getDisEntityTimeOutSecs();
        this.model = rprModel;
        this.exerciseId = ex;
        this.timeoutMillis = 1000L * (secs > 0 ? secs : DEFAULT_ENTITY_TIMEOUT_SECS);
        this.endpoint = ep;
        this.socket = s;
        db.setInteractionClassNames(interactionNames::get);
        running = true;
        lastTimeoutCheck = System.currentTimeMillis();
        Thread t = new Thread(this::receiveLoop, (cdis != null ? "rift-cdis-" : "rift-dis" + version + "-") + ep.port());
        t.setDaemon(true);
        receiver = t;
        t.start();
        return new DatasourceManager.OpResult(true, "Listening for " + protocolLabel() + ", exercise "
                + (ex == 0 ? "all" : String.valueOf(ex)) + ", on " + ep);
    }

    /** @return "DIS 6", "DIS 7" or "C-DIS" */
    private String protocolLabel() {
        return cdis != null ? "C-DIS" : "DIS " + version;
    }

    /** Stops receiving and closes the socket. The objects stay in the database until it is closed. */
    @Override
    public synchronized DatasourceManager.OpResult disconnect() {
        if (!running && socket == null) return new DatasourceManager.OpResult(true, "Already stopped");
        running = false;
        MulticastSocket s = socket;
        socket = null;
        if (s != null) s.close();
        Thread t = receiver;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return new DatasourceManager.OpResult(true, "Stopped listening on " + endpoint);
    }

    // ================================================================== receiving

    private void receiveLoop() {
        byte[] buf = new byte[MAX_DATAGRAM];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        while (running) {
            MulticastSocket s = socket;
            if (s == null) break;
            try {
                packet.setLength(buf.length);
                s.receive(packet);
                InetSocketAddress from = (InetSocketAddress) packet.getSocketAddress();
                String producer = from.getAddress().getHostAddress() + ":" + from.getPort();
                long now = System.currentTimeMillis();
                if (cdis != null) {
                    handleCdis(buf, packet.getOffset(), packet.getLength(), producer, now);
                } else {
                    handleDatagram(buf, packet.getOffset(), packet.getLength(), producer, now);
                }
            } catch (SocketTimeoutException quiet) {
                // fall through to the timeout check
            } catch (IOException e) {
                if (running) {
                    LOG.log(Level.SEVERE, "DIS datasource " + name + ": receiving failed, stopping", e);
                    running = false;
                    s.close();
                    if (callbackSink != null) {
                        try {
                            callbackSink.connectionLost(id);
                        } catch (RuntimeException sinkFailure) {
                            LOG.log(Level.WARNING, "DIS datasource " + name + ": callback failed", sinkFailure);
                        }
                    }
                }
                break;
            } catch (RuntimeException e) {                     // never let one datagram end the thread
                warnOnce("crash " + e.getClass().getName(), "unexpected error on a datagram: " + e);
            }
            long now = System.currentTimeMillis();
            if (now - lastTimeoutCheck >= TIMEOUT_CHECK_MILLIS) {
                lastTimeoutCheck = now;
                checkTimeouts(now);
                if (cdis != null) {
                    cdis.expire(now);
                }
            }
        }
    }

    /** A C-DIS datagram: decoded to DIS 7 PDUs, each handled as a datagram. Receiver thread; package private for tests. */
    void handleCdis(byte[] data, int offset, int length, String producer, long now) {
        long errors = cdis.stats().getDecodeErrors();
        for (byte[] pdu : cdis.decode(data, offset, length, now)) {
            handleDatagram(pdu, 0, pdu.length, producer, now);
        }
        if (cdis.stats().getDecodeErrors() != errors) {
            malformed.incrementAndGet();
            warnOnce("cdis", "a C-DIS datagram from " + producer + " cannot be decoded: " + cdis.stats().getLastError());
        }
    }

    /**
     * Handles one datagram: one PDU, or several bundled ones (each with its length; DIS 7 pads
     * bundled PDUs to 8-octet boundaries). Receiver thread; package private for tests.
     */
    void handleDatagram(byte[] data, int offset, int length, String producer, long now) {
        datagrams.incrementAndGet();
        int pos = offset, end = offset + length;
        while (end - pos >= DisPduHeader.SIZE) {
            if (data[pos] == 0) {                                // padding between bundled PDUs
                int next = offset + (((pos - offset) + 8) & ~7);
                if (next <= pos || next >= end) break;
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
            handlePdu(h, new DisReader(data, pos, h.length), producer, now);
            pos += h.length;
        }
    }

    private void handlePdu(DisPduHeader h, DisReader r, String producer, long now) {
        pdus.incrementAndGet();
        if (!acceptsVersion(h.protocolVersion)) {
            otherVersion.incrementAndGet();
            warnOnce("version " + h.protocolVersion, "PDUs of protocol version " + h.protocolVersion + " (from " + producer
                    + ") are ignored by this DIS " + version + " datasource");
            return;
        }
        if (exerciseId != 0 && h.exerciseId != exerciseId) {
            otherExercise.incrementAndGet();
            return;
        }
        byType.computeIfAbsent(h.pduType, k -> new AtomicLong()).incrementAndGet();
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
                    embedded(mapper.designator(h, r), designators, producer, now);
                    break;
                case DisPduHeader.IFF:
                    embedded(mapper.iff(h, r), iffs, producer, now);
                    break;
                default:
                    unhandled.incrementAndGet();
                    break;
            }
        } catch (DisFormatException | IllegalArgumentException e) {
            malformed.incrementAndGet();
            warnOnce("malformed " + h.pduType, "malformed PDU type " + h.pduType + " from " + producer + ": " + e.getMessage());
        }
    }

    // ================================================================== entities and radios

    private void entityState(DisEntityState s, DisToRpr mapper, String producer, long now) {
        Tracked t = entities.get(s.id.key());
        if (s.isDeactivated()) {                                     // the explicit "leaves the exercise"
            if (t != null) {
                entities.remove(s.id.key());
                db.removeInstance(t.dbId);
                deactivated.incrementAndGet();
            }
            return;
        }
        if (t == null) {
            t = create(DisToRpr.entityClass(s.type), s.id.text(), producer, now);
            if (t == null) return;
            entities.put(s.id.key(), t);
        }
        t.lastHeard = now;
        t.state = s;
        publish(t, mapper.entityAttributes(s), producer, now);
    }

    private void entityStateUpdate(DisReader r, DisToRpr mapper, String producer, long now) {
        int at = r.position();
        DisEntityId eid = DisEntityId.read(r);
        r.seek(at);
        Tracked t = entities.get(eid.key());
        if (t == null || t.state == null) {                          // created by an Entity State PDU only
            unknownEntityUpdates.incrementAndGet();
            return;
        }
        entityState(t.state.withUpdate(r), mapper, producer, now);
    }

    private void radio(DisToRpr.Radio radio, String producer, long now) {
        Tracked t = radios.get(radio.instanceName);
        if (t == null) {
            t = create(radio.className, radio.instanceName, producer, now);
            if (t == null) return;
            radios.put(radio.instanceName, t);
        }
        t.lastHeard = now;
        publish(t, radio.attributes, producer, now);
    }

    /**
     * Emitter systems and beams of one entity. Beams of a listed system that the PDU no longer
     * lists are removed (the beam was switched off); with a heartbeat (all systems listed), so
     * are systems that are not listed. Everything else times out like the entities.
     */
    private void emission(DisToRpr.Emission e, String producer, long now) {
        java.util.Set<String> systems = systemsOf.computeIfAbsent(e.host.key(), k -> new java.util.HashSet<>());
        java.util.Set<String> listed = new java.util.HashSet<>();
        for (DisToRpr.Embedded sys : e.systems) {
            if (!upsertEmitter(sys, producer, now)) continue;
            listed.add(sys.instanceName);
            systems.add(sys.instanceName);
            java.util.Set<String> beams = beamsOf.computeIfAbsent(sys.instanceName, k -> new java.util.HashSet<>());
            java.util.Set<String> active = new java.util.HashSet<>();
            for (DisToRpr.Embedded b : e.beams.getOrDefault(sys.instanceName, java.util.Collections.emptyList())) {
                if (upsertEmitter(b, producer, now)) active.add(b.instanceName);
            }
            for (String gone : new java.util.ArrayList<>(beams)) {
                if (!active.contains(gone)) removeEmitter(gone);
            }
            beams.clear();
            beams.addAll(active);
        }
        if (e.complete) {
            for (String sys : new java.util.ArrayList<>(systems)) {
                if (listed.contains(sys)) continue;
                for (String b : beamsOf.getOrDefault(sys, java.util.Collections.emptySet())) removeEmitter(b);
                beamsOf.remove(sys);
                removeEmitter(sys);
                systems.remove(sys);
            }
        }
    }

    /** A designator or IFF system: one object per name, updated with what changed. */
    private void embedded(DisToRpr.Embedded o, Map<String, Tracked> table, String producer, long now) {
        Tracked t = table.get(o.instanceName);
        if (t == null) {
            t = create(o.className, o.instanceName, producer, now);
            if (t == null) return;
            table.put(o.instanceName, t);
        }
        t.lastHeard = now;
        publish(t, o.attributes, producer, now);
    }

    private boolean upsertEmitter(DisToRpr.Embedded o, String producer, long now) {
        Tracked t = emitters.get(o.instanceName);
        if (t == null) {
            t = create(o.className, o.instanceName, producer, now);
            if (t == null) return false;
            emitters.put(o.instanceName, t);
        }
        t.lastHeard = now;
        publish(t, o.attributes, producer, now);
        return true;
    }

    private void removeEmitter(String name) {
        Tracked t = emitters.remove(name);
        if (t != null) db.removeInstance(t.dbId);
    }

    /** Discovers a new object of the class (or its nearest superclass in the FOM). */
    private Tracked create(String wantedClass, String instanceName, String producer, long now) {
        String cls = model.nearestObjectClass(wantedClass);
        if (cls == null) {
            warnOnce("class " + wantedClass, "the FOM has no " + wantedClass + " (nor a superclass): such objects are ignored");
            return null;
        }
        if (!cls.equals(wantedClass)) warnOnce("class " + wantedClass, "the FOM has no " + wantedClass + ": using " + cls);
        Integer dbId = db.discoverInstance(model.objectClassId(cls), cls, instanceName, producer);
        if (dbId == null || dbId == RiftDatabase.NO_ID) return null;   // database closed
        discovered.incrementAndGet();
        Tracked t = new Tracked(dbId, cls);
        t.lastHeard = now;
        return t;
    }

    /**
     * Passes the attributes that the class has and that changed since the last update to the
     * database. An empty value (e.g. no articulated parameters) is sent only to clear an earlier
     * non-empty one.
     */
    private void publish(Tracked t, Map<String, byte[]> values, String producer, long now) {
        int classId = model.objectClassId(t.className);
        ArrayList<RiftAttribute> attrs = new ArrayList<>(values.size());
        int[] ids = new int[values.size()];
        byte[][] raw = new byte[values.size()][];
        SimwTimeStamp ts = new SimwTimeStamp(now);
        int n = 0;
        for (Map.Entry<String, byte[]> e : values.entrySet()) {
            String attribute = e.getKey();
            byte[] value = e.getValue();
            byte[] before = t.sent.get(attribute);
            if (before == null ? value.length == 0 : Arrays.equals(before, value)) continue;
            int attrId = model.attributeId(t.className, attribute);
            if (attrId == RprModel.NO_ID) {
                warnOnce("attr " + t.className + "." + attribute, "the FOM has no attribute " + attribute + " on " + t.className + ": not sent");
                continue;
            }
            t.sent.put(attribute, value);
            attrs.add(new RiftAttribute(attrId, attribute, value, producer, ts, null));
            ids[n] = attrId;
            raw[n] = value;
            n++;
        }
        if (n == 0) return;
        db.reflectObjectUpdate(t.dbId, attrs, new RawValues(RawValues.Kind.ATTRIBUTES, classId, now,
                Arrays.copyOf(ids, n), new String[n], Arrays.copyOf(raw, n)));
    }

    private void interaction(DisToRpr.Interaction ix, String producer) {
        if (ix == null) return;
        String cls = ix.className;
        int classId = model.interactionClassId(cls);
        if (classId == RprModel.NO_ID && ix.fallback != null) {
            String named = model.interactionClassNamed(ix.simpleName());        // the Link-16 module elsewhere or spelled otherwise
            if (named != null) {
                cls = named;
                classId = model.interactionClassId(cls);
            }
        }
        if (classId == RprModel.NO_ID && ix.fallback != null) {
            warnOnce("ix " + ix.className, "the FOM has no " + ix.simpleName() + " (Link-16 module): sent as " + ix.fallback.className);
            interaction(ix.fallback, producer);
            return;
        }
        if (classId == RprModel.NO_ID) {
            warnOnce("ix " + ix.className, "the FOM has no " + ix.className + ": those PDUs are ignored");
            return;
        }
        interactionNames.putIfAbsent(classId, cls);
        ArrayList<RiftParameter> params = new ArrayList<>(ix.parameters.size());
        for (Map.Entry<String, byte[]> e : ix.parameters.entrySet()) {
            int pid = model.parameterId(cls, e.getKey());
            if (pid == RprModel.NO_ID) {
                if (!DisToRpr.OPTIONAL_PARAMETERS.contains(e.getKey())) {
                    warnOnce("param " + cls + "." + e.getKey(), "the FOM has no parameter " + e.getKey() + " on " + cls + ": not sent");
                }
                continue;
            }
            params.add(new RiftParameter(pid, e.getKey(), e.getValue()));
        }
        interactions.incrementAndGet();
        db.reflectInteractionEvent(classId, params, producer, null);
    }

    /** Removes the objects not heard from for the entity timeout. Receiver thread; package private for tests. */
    void checkTimeouts(long now) {
        long limit = now - timeoutMillis;
        expire(entities.values().iterator(), limit);
        expire(radios.values().iterator(), limit);
        expire(emitters.values().iterator(), limit);
        expire(designators.values().iterator(), limit);
        expire(iffs.values().iterator(), now - Math.max(timeoutMillis, IFF_TIMEOUT_MILLIS));
    }

    private void expire(Iterator<Tracked> it, long limit) {
        while (it.hasNext()) {
            Tracked t = it.next();
            if (t.lastHeard < limit) {
                it.remove();
                db.removeInstance(t.dbId);
                timedOut.incrementAndGet();
            }
        }
    }

    // ================================================================== diagnostics

    /**
     * Counters since start, e.g. for a status page: datagrams, PDUs, ignored ones (other exercise,
     * other protocol version, unhandled type, malformed), objects discovered / timed out /
     * deactivated, interactions, and PDUs per type.
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("disversion", version);
        if (cdis != null) {
            m.put("protocol", "C-DIS");
            m.put("cdissettings", cdis.settings().toMap());
            m.put("cdis", cdis.stats().toMap());
        }
        m.put("exercise", exerciseId);
        m.put("listening", running ? String.valueOf(endpoint) : null);
        m.put("datagrams", datagrams.get());
        m.put("pdus", pdus.get());
        m.put("otherexercise", otherExercise.get());
        m.put("otherversion", otherVersion.get());
        m.put("unhandledtype", unhandled.get());
        m.put("malformed", malformed.get());
        m.put("discovered", discovered.get());
        m.put("timedout", timedOut.get());
        m.put("deactivated", deactivated.get());
        m.put("unknownentityupdates", unknownEntityUpdates.get());
        m.put("interactions", interactions.get());
        Map<String, Long> types = new TreeMap<>();
        for (Map.Entry<Integer, AtomicLong> e : byType.entrySet()) types.put(String.valueOf(e.getKey()), e.getValue().get());
        m.put("pdusbytype", types);
        return m;
    }

    private void warnOnce(String key, String message) {
        if (warned.add(key)) LOG.warning("DIS datasource " + name + ": " + message);
    }
}
