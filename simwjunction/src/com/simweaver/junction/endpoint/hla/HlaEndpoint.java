package com.simweaver.junction.endpoint.hla;

import com.simweaver.common.hla.session.ConnectSettings;
import com.simweaver.common.hla.session.RtiLibrary;
import com.simweaver.common.hla.session.RtiSession;
import com.simweaver.common.hla.session.RtiSessionException;
import com.simweaver.common.hla.session.RtiSessionListener;
import com.simweaver.common.iem.ClassAttribute;
import com.simweaver.common.iem.ClassParameter;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.common.iem.InteractionClass;
import com.simweaver.common.iem.ObjectClass;
import com.simweaver.common.logging.Events;
import com.simweaver.junction.config.EndpointConfig;
import com.simweaver.junction.config.EndpointType;
import com.simweaver.junction.endpoint.Endpoint;
import com.simweaver.junction.endpoint.EndpointException;
import com.simweaver.junction.endpoint.EndpointListener;
import com.simweaver.junction.endpoint.EndpointState;
import com.simweaver.junction.endpoint.EndpointStats;
import com.simweaver.junction.endpoint.Interest;
import com.simweaver.junction.endpoint.NameCandidates;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * An HLA endpoint (1516-2010, 1516-2025, 1516-2025 over the Federate Protocol, Talk),
 * on an {@link RtiSession} from the endpoint's {@link RtiLibrary} (the class path RTI, or
 * {@code endpoint.hla.rtijar} in isolation).
 *
 * <h2>Threads</h2>
 * <ul>
 *   <li>RTI callback threads: convert and hand over to the router's listener, nothing else.</li>
 *   <li>One worker thread per endpoint: everything a callback starts that needs the RTI
 *       (registering after a name reservation, requesting attribute values of a discovered
 *       object) and the reconnect loop.</li>
 *   <li>The router's dispatcher: updates, interactions, deletes, creates.</li>
 * </ul>
 * RTI services are called under one lock per endpoint, so the RTI sees one caller at a time.
 *
 * <h2>Reconnect</h2>
 * When the RTI connection is lost and {@code endpoint.hla.reconnect} is on, the endpoint
 * reports {@link EndpointState#LOST}, forgets its objects, and tries again every
 * {@code reconnectintervalseconds}. On success it declares again and reports
 * {@link EndpointState#JOINED}; the router then creates its objects again and the RTI
 * discovers the other federates' objects anew.
 */
public final class HlaEndpoint implements Endpoint {

    private static final Logger LOG = Logger.getLogger(HlaEndpoint.class.getName());
    private static final byte[] NO_TAG = new byte[0];

    private final EndpointConfig cfg;
    private final String gatewayId;
    private final String federation;
    private final File runtimeDir;
    private final RtiSession.Version version;
    private final EndpointStats stats = new EndpointStats();
    private final ReentrantLock rtiLock = new ReentrantLock();
    private final ScheduledExecutorService worker;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private final AtomicLong keys = new AtomicLong();

    private volatile InformationExchangeModel iem;
    /** endpoint.hla.foms=dynamic: reads HLAcurrentFDD on the current session */
    private volatile com.simweaver.common.hla.session.DynamicFom.Reader fddReader;
    /** fingerprint of the FDD the model was built from (dynamic) */
    private volatile String fddFingerprint;
    private volatile String fddFormatNote = "";
    private final String lsd;
    /** set when the federation's FDD differs from the one the model was built from */
    private volatile String fomNotice;
    private volatile RtiLibrary library;
    private volatile RtiSession session;
    private volatile EndpointListener listener;
    private volatile EndpointState state = EndpointState.NEW;
    private volatile String stateMessage;
    private volatile String federateName = "";
    private volatile boolean closing;
    /** 0.9.20: the Infra view's tap on the current session */
    private volatile com.simweaver.common.infra.HlaTap infra;

    private volatile Interest publish = new Interest();
    private volatile Interest subscribe = new Interest();
    private volatile Mapping map = new Mapping();

    // discovered objects: session instance <-> key
    private final Map<Long, Long> discoveredKey = new ConcurrentHashMap<Long, Long>();
    private final Map<Long, Discovered> discovered = new ConcurrentHashMap<Long, Discovered>();
    // created objects
    private final Map<Long, Created> createdByKey = new ConcurrentHashMap<Long, Created>();
    private final Map<Long, Created> createdByInstance = new ConcurrentHashMap<Long, Created>();
    private final Map<String, Created> pendingNames = new ConcurrentHashMap<String, Created>();

    /**
     * @param cfg        the endpoint configuration (an HLA type)
     * @param federation the federation to join
     * @param gatewayId  the gateway's id (thread names, federate name)
     * @param runtimeDir the runtime folder (relative RTI jar paths)
     */
    public HlaEndpoint(EndpointConfig cfg, String federation, String gatewayId, File runtimeDir) {
        this(cfg, federation, null, gatewayId, runtimeDir);
    }

    /**
     * @param lsd the LSD / RTI configuration name to connect with; null = the configured default
     */
    public HlaEndpoint(EndpointConfig cfg, String federation, String lsd, String gatewayId, File runtimeDir) {
        if (cfg.getType() == null || !cfg.getType().isHla()) {
            throw new IllegalArgumentException("not an HLA endpoint: " + cfg.getType());
        }
        this.cfg = cfg;
        this.federation = federation;
        this.lsd = lsd == null ? cfg.getLsd() : lsd.trim();
        this.gatewayId = gatewayId;
        this.runtimeDir = runtimeDir;
        this.version = cfg.isHla4() ? RtiSession.Version.HLA_1516_2025 : RtiSession.Version.HLA_1516E;
        ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "junction-" + gatewayId + "-" + cfg.getId());
            t.setDaemon(true);
            return t;
        });
        ex.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.worker = ex;
    }

    /**
     * Why an endpoint of this configuration cannot run here, checked without connecting.
     *
     * @return the reason, or null when its RTI library is available
     */
    public static String unavailableReason(EndpointConfig cfg, File runtimeDir) {
        RtiSession.Version v = cfg.isHla4() ? RtiSession.Version.HLA_1516_2025 : RtiSession.Version.HLA_1516E;
        try {
            return RtiLibrary.fromSetting(cfg.getRtiJar(), runtimeDir).unavailableReason(v);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
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
        return federation;
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

    /** @return the RTI library description ("class path" or jar names) */
    public String libraryDescription() {
        RtiLibrary l = library;
        return l == null ? "" : l.describe();
    }

    /** @return the federate name the RTI gave this endpoint */
    public String federateName() {
        return federateName;
    }

    @Override
    public void open(EndpointListener l) throws EndpointException {
        this.listener = l;
        setState(EndpointState.CONNECTING, null, false);
        if (federation == null || federation.trim().isEmpty()) {
            fail(new EndpointException(EndpointException.CONFIG_INVALID, "endpoint " + id() + ": no federation given", null));
        }
        if (!cfg.isDynamicFom()) {
            try {
                iem = cfg.isHla4() ? InformationExchangeModel.fromHla4(cfg.getFomPaths())
                        : InformationExchangeModel.fromHlaEvolved(cfg.getFomPaths());
            } catch (Exception e) {
                fail(new EndpointException(EndpointException.FOM_INVALID, "endpoint " + id() + ": " + e.getMessage(), e));
            }
        }
        try {
            library = RtiLibrary.fromSetting(cfg.getRtiJar(), runtimeDir);
        } catch (IllegalArgumentException e) {
            fail(new EndpointException(EndpointException.CONFIG_INVALID, "endpoint " + id() + ": " + e.getMessage(), e));
        }
        String reason = library.unavailableReason(version);
        if (reason != null) {
            fail(new EndpointException(EndpointException.RTI_NOT_AVAILABLE, "endpoint " + id() + ": " + reason, null));
        }
        try {
            connectAndJoin();
        } catch (RtiSessionException e) {
            String code = "RtiNotAvailable".equals(e.getRtiExceptionName()) ? EndpointException.RTI_NOT_AVAILABLE
                    : EndpointException.CONNECT_FAILED;
            String hint = cfg.isDynamicFom() && String.valueOf(e.getRtiExceptionName()).contains("DoesNotExist")
                    ? missingFederationHint() : "";
            fail(new EndpointException(code, "endpoint " + id() + " (" + federation + "): " + e.getMessage() + hint, e));
        }
        if (cfg.isDynamicFom()) {
            readFdd();
        }
        setState(EndpointState.JOINED, null, false);
        openInfra(session);
    }

    // ------------------------------------------------------------------ Infra (0.9.20)

    /** Registers the session's federation with the Infra view (MOM through the session, under the RTI lock). */
    private void openInfra(final RtiSession s) {
        closeInfra();
        com.simweaver.common.infra.InfraRegistry reg = com.simweaver.common.infra.InfraRegistry.global();
        if (s == null || !reg.isEnabled()) {
            return;
        }
        com.simweaver.common.infra.SessionMomPort port = new com.simweaver.common.infra.SessionMomPort(s, rtiLock, this::ownAttributes);
        infra = reg.openHla(com.simweaver.common.infra.InfraSource.junction(gatewayId, id()), cfg.isHla4(), federation,
                federateName, lsd, port);
    }

    private void closeInfra() {
        com.simweaver.common.infra.HlaTap t = infra;
        infra = null;
        if (t != null) {
            t.close();
        }
    }

    /** What the endpoint itself subscribes of a session class (its routes, the FDD reader). */
    private int[] ownAttributes(int sessionClass) {
        java.util.Set<Integer> all = new java.util.LinkedHashSet<Integer>();
        int[] routed = map.subscribed.get(sessionClass);
        if (routed != null) {
            for (int a : routed) {
                all.add(a);
            }
        }
        com.simweaver.common.hla.session.DynamicFom.Reader r = fddReader;
        if (r != null && r.objectClass() == sessionClass && r.attribute() != 0) {
            all.add(r.attribute());
        }
        return all.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Dynamic FOM: reads the federation's FDD on the new session and builds the model from it. */
    private void readFdd() throws EndpointException {
        RtiSession s = session;
        String text;
        try {
            com.simweaver.common.hla.session.DynamicFom.Reader r = watchFdd(s);
            text = r.await(cfg.getFddTimeoutSecs() * 1000L);
        } catch (RtiSessionException e) {
            closeAfterFailure();
            fail(new EndpointException(EndpointException.FOM_INVALID, "endpoint " + id() + " (" + federation
                    + "): cannot take the FOM from the federation: " + e.getMessage(), e));
            return;
        }
        try {
            iem = com.simweaver.common.hla.session.DynamicFom.toModel(text, cfg.isHla4());
        } catch (Exception e) {
            closeAfterFailure();
            fail(new EndpointException(EndpointException.FOM_INVALID, "endpoint " + id() + " (" + federation
                    + "): the federation's FDD cannot be read as a FOM: " + e.getMessage(), e));
            return;
        }
        fddFingerprint = com.simweaver.common.hla.session.DynamicFom.fingerprint(text);
        fddFormatNote = com.simweaver.common.hla.session.DynamicFom.formatNote(text, cfg.isHla4());
        fomNotice = null;
        LOG.info("Junction " + gatewayId + "/" + id() + ": FOM taken from federation " + federation + " (FDD " + fddFingerprint
                + ", " + text.length() + " characters, " + iem.getObjectClasses().size() + " object classes, "
                + iem.getInteractionClasses().size() + " interaction classes" + fddFormatNote + rprNote(iem) + ")");
    }

    private static String rprNote(InformationExchangeModel m) {
        int v = m.getRprFomVersion();
        return v == 0 ? "" : ", RPR v" + v + (m.getFamiliesFromContent().isEmpty() ? "" : " recognised from its classes");
    }

    /** Subscribes HLAcurrentFDD on the session; later changes become the endpoint's notice. */
    private com.simweaver.common.hla.session.DynamicFom.Reader watchFdd(RtiSession s) throws RtiSessionException {
        com.simweaver.common.hla.session.DynamicFom.Reader r =
                new com.simweaver.common.hla.session.DynamicFom.Reader(s, cfg.getFddClass(), cfg.getFddAttribute());
        r.onValue(text -> {
            String known = fddFingerprint;
            String now = com.simweaver.common.hla.session.DynamicFom.fingerprint(text);
            if (known != null && !known.equals(now)) {
                fomNotice = "The federation's FDD has changed (a federate joined with more FOM modules): restart the gateway"
                        + " to bridge the new classes. Model from FDD " + known + ", federation now at " + now + ".";
                warnOnce("fdd-" + now, fomNotice);
            }
        });
        fddReader = r;
        rtiLock.lock();
        try {
            r.start();
        } finally {
            rtiLock.unlock();
        }
        return r;
    }

    private void closeAfterFailure() {
        RtiSession s = session;
        session = null;
        fddReader = null;
        if (s != null) {
            s.close();
        }
    }

    /** @return where the FOM came from: the module file names, or the federation's FDD */
    public String fomSource() {
        if (!cfg.isDynamicFom()) {
            return String.join(", ", cfg.getFomNames());
        }
        InformationExchangeModel m = iem;
        return fddFingerprint == null ? "from the federation (not read yet)" : "from the federation: FDD " + fddFingerprint
                + fddFormatNote + (m == null ? "" : rprNote(m));
    }

    /** @return the LSD / RTI configuration name this endpoint connects with ("" = the RTI's default) */
    public String lsd() {
        return lsd;
    }

    /** @return a notice about the FOM (dynamic: the federation's FDD changed), or null */
    public String fomNotice() {
        return fomNotice;
    }

    private void fail(EndpointException e) throws EndpointException {
        setState(EndpointState.FAILED, e.getMessage(), false);
        throw e;
    }

    private void connectAndJoin() throws RtiSessionException {
        RtiSession s = library.createSession(version);
        ConnectSettings settings = ConnectSettings.of(lsd)
                .withRtiAddress(cfg.getRtiAddress())
                .withAdditionalSettings(cfg.getRtiSettings())
                .withFactoryName(cfg.getRtiFactory());
        rtiLock.lock();
        try {
            // one connect / join at a time on this RTI library (0.9.16): parallel joins can fail inside the RTI
            synchronized (library.connectLock()) {
                s.connect(settings, new SessionCallbacks(s));
                try {
                    federateName = join(s);
                } catch (RtiSessionException e) {
                    s.disconnect();
                    throw e;
                }
            }
            session = s;
        } finally {
            rtiLock.unlock();
        }
    }

    /**
     * Joins; a dynamic endpoint joins without modules and, when the federation is not running and
     * {@code endpoint.hla.createfoms} names modules (0.9.21), creates it with them.
     */
    private String join(RtiSession s) throws RtiSessionException {
        String name = cfg.getFederateName().isEmpty() ? null : cfg.getFederateName();
        if (!cfg.isDynamicFom()) {
            return s.join(federation, cfg.getFederateType(), name, cfg.getFomPaths(), cfg.isCreateFederation());
        }
        try {
            return s.join(federation, cfg.getFederateType(), name, java.util.Collections.<String>emptyList(), false);
        } catch (RtiSessionException e) {
            boolean missing = String.valueOf(e.getRtiExceptionName()).contains("DoesNotExist");
            if (!missing || !cfg.isCreateFederation() || cfg.getCreateFomPaths().isEmpty()) {
                throw e;
            }
            LOG.info("Junction " + gatewayId + "/" + id() + ": federation " + federation + " is not running; creating it with "
                    + String.join(", ", cfg.getCreateFomNames()) + " (endpoint.hla.createfoms)");
            return s.join(federation, cfg.getFederateType(), name, cfg.getCreateFomPaths(), true);
        }
    }

    /** Why a dynamic endpoint could not join a federation that is not running, and what to do. */
    private String missingFederationHint() {
        if (!cfg.getCreateFomPaths().isEmpty() && !cfg.isCreateFederation()) {
            return " (endpoint.hla.createfederation=false: the endpoint does not create it)";
        }
        if (cfg.getType() == com.simweaver.junction.config.EndpointType.COMMS) {
            return " (an HLA Comms endpoint takes the HLA Comms FOM from the running federation: start the HLA Comms federation"
                    + " first, or put your HLA Comms FOM modules in " + cfg.getFomRootDir() + " and name them in endpoint.hla.createfoms so that"
                    + " the endpoint can create the federation)";
        }
        return " (an endpoint with endpoint.hla.foms=dynamic takes its FOM from the running federation: start the federation"
                + " first, or name FOM modules in endpoint.hla.createfoms so that the endpoint can create it)";
    }

    @Override
    public void declare(Interest pub, Interest sub) throws EndpointException {
        this.publish = pub;
        this.subscribe = sub;
        try {
            applyDeclarations();
        } catch (RtiSessionException e) {
            throw new EndpointException(EndpointException.CONNECT_FAILED, "endpoint " + id() + ": declaring failed: " + e.getMessage(), e);
        }
    }

    /** Resolves handles and publishes / subscribes, on the current session. */
    private void applyDeclarations() throws RtiSessionException {
        RtiSession s = session;
        if (s == null) {
            throw new RtiSessionException("not connected");
        }
        Mapping m = new Mapping();
        List<String> missing = new ArrayList<String>();
        rtiLock.lock();
        try {
            // object classes: union of both, publish and subscribe separately
            Map<Integer, Set<Integer>> all = new HashMap<Integer, Set<Integer>>();
            mergeInto(all, publish.getObjectClasses());
            mergeInto(all, subscribe.getObjectClasses());
            for (Map.Entry<Integer, Set<Integer>> e : all.entrySet()) {
                ObjectClass oc = iem.getObjectClass(e.getKey());
                if (oc == null) {
                    continue;
                }
                int sc;
                try {
                    sc = s.objectClass(oc.getQualifiedName());
                } catch (RtiSessionException ex) {
                    missing.add(oc.getQualifiedName());
                    continue;
                }
                m.putClass(oc.getId(), sc);
                for (Integer attrId : e.getValue()) {
                    ClassAttribute ca = oc.getAttribute(attrId);
                    if (ca == null) {
                        continue;
                    }
                    try {
                        m.putAttribute(sc, ca.getId(), s.attribute(sc, ca.getName()));
                    } catch (RtiSessionException ex) {
                        missing.add(oc.getQualifiedName() + "." + ca.getName());
                    }
                }
            }
            // interaction handles
            java.util.Set<Integer> ics = new java.util.LinkedHashSet<Integer>(publish.getInteractionClasses());
            ics.addAll(subscribe.getInteractionClasses());
            List<Integer> icOrder = new ArrayList<Integer>();
            for (Integer icId : ics) {
                InteractionClass ic = iem.getInteractionClass(icId);
                if (ic == null) {
                    continue;
                }
                int si;
                try {
                    si = s.interactionClass(ic.getQualifiedName());
                } catch (RtiSessionException ex) {
                    missing.add(ic.getQualifiedName());
                    continue;
                }
                m.putInteraction(ic.getId(), si);
                for (ClassParameter p : ic.getParameters()) {
                    try {
                        m.putParameter(si, p.getId(), s.parameter(si, p.getName()));
                    } catch (RtiSessionException ex) {
                        missing.add(ic.getQualifiedName() + "." + p.getName());
                    }
                }
                icOrder.add(icId);
            }
            // the mapping is complete before the first declaration: an RTI may deliver
            // discoveries and interactions while the subscriptions are still being made
            for (Map.Entry<Integer, Set<Integer>> e : publish.getObjectClasses().entrySet()) {
                int sc = m.sessionClass(e.getKey());
                if (sc != 0 && m.sessionAttributes(e.getValue()).length > 0) {
                    m.published.put(e.getKey(), new java.util.HashSet<Integer>(e.getValue()));
                }
            }
            for (Map.Entry<Integer, Set<Integer>> e : subscribe.getObjectClasses().entrySet()) {
                int sc = m.sessionClass(e.getKey());
                int[] attrs = m.sessionAttributes(e.getValue());
                if (sc != 0 && attrs.length > 0) {
                    m.subscribed.put(sc, attrs);
                }
            }
            for (Integer icId : icOrder) {
                if (publish.getInteractionClasses().contains(icId)) {
                    m.publishedInteractions.add(icId);
                }
            }
            this.map = m;
            for (Map.Entry<Integer, Set<Integer>> e : publish.getObjectClasses().entrySet()) {
                int sc = m.sessionClass(e.getKey());
                int[] attrs = m.sessionAttributes(e.getValue());
                if (sc != 0 && attrs.length > 0) {
                    s.publishObjectClass(sc, attrs);
                }
            }
            for (Integer icId : icOrder) {
                int si = m.sessionInteraction(icId);
                if (publish.getInteractionClasses().contains(icId)) {
                    s.publishInteractionClass(si);
                }
                if (subscribe.getInteractionClasses().contains(icId)) {
                    s.subscribeInteractionClass(si);
                }
            }
            // most specific classes first: an RTI discovers an object that already exists with the first
            // subscribed class it belongs to, and that known class never changes. Subscribing a superclass
            // first would make every object already in the federation a BaseEntity (say) for the gateway,
            // and its copies would be registered as that.
            List<Integer> subOrder = new ArrayList<Integer>(subscribe.getObjectClasses().keySet());
            final InformationExchangeModel model = iem;
            subOrder.sort((x, y) -> Integer.compare(depthOf(model, y), depthOf(model, x)));
            for (Integer classId : subOrder) {
                int sc = m.sessionClass(classId);
                int[] attrs = m.subscribed.get(sc);
                if (sc != 0 && attrs != null) {
                    s.subscribeObjectClass(sc, attrs);
                }
            }
        } finally {
            rtiLock.unlock();
        }
        if (!missing.isEmpty()) {
            LOG.warning("Junction " + gatewayId + "/" + id() + ": the RTI does not know " + missing.size()
                    + " classes or members of the FOM (skipped), e.g. " + missing.subList(0, Math.min(5, missing.size())));
        }
    }

    /** @return how deep the class is in the hierarchy (HLAobjectRoot = 0) */
    private static int depthOf(InformationExchangeModel model, int classId) {
        com.simweaver.common.iem.ObjectClass oc = model == null ? null : model.getObjectClass(classId);
        if (oc == null) {
            return 0;
        }
        String q = oc.getQualifiedName();
        int d = 0;
        for (int i = 0; i < q.length(); i++) {
            if (q.charAt(i) == '.') {
                d++;
            }
        }
        return d;
    }

    private static void mergeInto(Map<Integer, Set<Integer>> into, Map<Integer, Set<Integer>> from) {
        for (Map.Entry<Integer, Set<Integer>> e : from.entrySet()) {
            Set<Integer> s = into.get(e.getKey());
            if (s == null) {
                s = new java.util.TreeSet<Integer>();
                into.put(e.getKey(), s);
            }
            s.addAll(e.getValue());
        }
    }

    // ------------------------------------------------------------------ objects

    @Override
    public void createObject(final long gwId, int objectClass, NameCandidates names) {
        RtiSession s = session;
        Mapping m = map;
        int sc = m.sessionClass(objectClass);
        if (s == null || !state.isUp() || sc == 0 || !m.published.containsKey(objectClass)) {
            stats.registrationFailures.incrementAndGet();
            EndpointListener l = listener;
            if (l != null) {
                l.registrationFailed(this, gwId, sc == 0 ? "class not known to the RTI" : "endpoint not joined");
            }
            return;
        }
        final Created c = new Created(gwId, objectClass, sc, names.getNames(), s);
        if (names.isAny()) {
            worker.execute(() -> register(c, null));
            return;
        }
        reserveNext(c);
    }

    /** Reserves the next candidate name, or reports the failure when there is none. */
    private void reserveNext(Created c) {
        String name = c.nextName();
        if (name == null) {
            stats.registrationFailures.incrementAndGet();
            listener.registrationFailed(this, c.gwId, "all names taken: " + c.names);
            return;
        }
        pendingNames.put(name, c);
        rtiLock.lock();
        try {
            c.session.reserveName(name);
        } catch (RtiSessionException e) {
            pendingNames.remove(name);
            warnOnce("reserve", "reserving names fails: " + e.getMessage());
            stats.registrationFailures.incrementAndGet();
            listener.registrationFailed(this, c.gwId, e.getMessage());
        } finally {
            rtiLock.unlock();
        }
    }

    /** On the worker: the reservation answer. */
    private void nameReserved(String name, boolean ok) {
        Created c = pendingNames.remove(name);
        if (c == null || c.session != session) {
            return;                                  // from an old session
        }
        if (ok) {
            register(c, name);
        } else {
            reserveNext(c);
        }
    }

    /** On the worker: registers with a reserved name (or none). */
    private void register(Created c, String name) {
        long inst;
        RtiSessionException failure = null;
        rtiLock.lock();
        try {
            if (c.session != session) {
                return;                              // the session was replaced meanwhile
            }
            inst = c.session.registerObject(c.sessionClass, name);
        } catch (RtiSessionException e) {
            inst = 0;
            failure = e;
            if (name != null) {
                c.session.releaseName(name);
            }
        } finally {
            rtiLock.unlock();
        }
        if (failure != null) {
            stats.registrationFailures.incrementAndGet();
            warnOnce("register", "registering objects fails: " + failure.getMessage());
            listener.registrationFailed(this, c.gwId, failure.getMessage());
            return;
        }
        c.instance = inst;
        c.key = keys.incrementAndGet();
        String n = name != null ? name : c.session.objectName(inst);
        createdByKey.put(c.key, c);
        createdByInstance.put(inst, c);
        stats.registeredOut.incrementAndGet();
        stats.markOut(0);
        listener.registered(this, c.gwId, c.key, n == null ? "" : n);
    }

    @Override
    public void updateObject(long key, int[] attributes, byte[][] values, byte[] tag) {
        Created c = createdByKey.get(key);
        Mapping m = map;
        if (c == null || c.session != session) {
            return;
        }
        Set<Integer> pub = m.published.get(c.objectClass);
        int n = 0;
        int[] sa = new int[attributes.length];
        byte[][] sv = new byte[attributes.length][];
        int octets = 0;
        for (int i = 0; i < attributes.length; i++) {
            int h = m.sessionAttribute(attributes[i]);
            if (h != 0 && pub != null && pub.contains(attributes[i]) && values[i] != null) {
                sa[n] = h;
                sv[n] = values[i];
                octets += values[i].length;
                n++;
            }
        }
        if (n == 0) {
            return;
        }
        if (n < sa.length) {
            sa = java.util.Arrays.copyOf(sa, n);
            sv = java.util.Arrays.copyOf(sv, n);
        }
        rtiLock.lock();
        try {
            c.session.updateAttributes(c.instance, sa, sv, tag == null ? NO_TAG : tag);
            stats.updatesOut.incrementAndGet();
            stats.markOut(octets);
        } catch (RtiSessionException e) {
            stats.sendErrors.incrementAndGet();
            warnOnce("update", "updating attributes fails: " + e.getMessage());
        } finally {
            rtiLock.unlock();
        }
    }

    @Override
    public void deleteObject(long key, byte[] tag) {
        Created c = createdByKey.remove(key);
        if (c == null) {
            return;
        }
        createdByInstance.remove(c.instance);
        if (c.session != session) {
            return;
        }
        rtiLock.lock();
        try {
            c.session.deleteObject(c.instance, tag == null ? NO_TAG : tag);
            stats.deletedOut.incrementAndGet();
            stats.markOut(0);
        } catch (RtiSessionException e) {
            stats.sendErrors.incrementAndGet();
            warnOnce("delete", "deleting objects fails: " + e.getMessage());
        } finally {
            rtiLock.unlock();
        }
    }

    @Override
    public void sendInteraction(int interactionClass, int[] parameters, byte[][] values, byte[] tag) {
        RtiSession s = session;
        Mapping m = map;
        int si = m.sessionInteraction(interactionClass);
        if (s == null || si == 0 || !m.publishedInteractions.contains(interactionClass) || !state.isUp()) {
            return;
        }
        int n = 0;
        int[] sp = new int[parameters.length];
        byte[][] sv = new byte[parameters.length][];
        int octets = 0;
        for (int i = 0; i < parameters.length; i++) {
            int h = m.sessionParameter(si, parameters[i]);
            if (h != 0 && values[i] != null) {
                sp[n] = h;
                sv[n] = values[i];
                octets += values[i].length;
                n++;
            }
        }
        if (n < sp.length) {
            sp = java.util.Arrays.copyOf(sp, n);
            sv = java.util.Arrays.copyOf(sv, n);
        }
        rtiLock.lock();
        try {
            s.sendInteraction(si, sp, sv, tag == null ? NO_TAG : tag);
            stats.interactionsOut.incrementAndGet();
            stats.markOut(octets);
        } catch (RtiSessionException e) {
            stats.sendErrors.incrementAndGet();
            warnOnce("send", "sending interactions fails: " + e.getMessage());
        } finally {
            rtiLock.unlock();
        }
    }

    @Override
    public void requestUpdate(long key, int[] attributes) {
        Discovered d = discovered.get(key);
        if (d == null || d.session != session) {
            return;
        }
        int[] sa = map.sessionAttributes(toSet(attributes));
        if (sa.length == 0) {
            return;
        }
        rtiLock.lock();
        try {
            d.session.requestAttributeUpdate(d.instance, sa, NO_TAG);
        } catch (RtiSessionException e) {
            warnOnce("request", "requesting attribute values fails: " + e.getMessage());
        } finally {
            rtiLock.unlock();
        }
    }

    private static Set<Integer> toSet(int[] a) {
        Set<Integer> s = new java.util.LinkedHashSet<Integer>();
        for (int x : a) {
            s.add(x);
        }
        return s;
    }

    @Override
    public void close(boolean deleteObjects) {
        closing = true;
        closeInfra();
        worker.shutdownNow();
        RtiSession s = session;
        session = null;
        if (s != null) {
            rtiLock.lock();
            try {
                if (deleteObjects) {
                    for (Created c : createdByKey.values()) {
                        try {
                            s.deleteObject(c.instance, NO_TAG);
                        } catch (RtiSessionException ignored) {
                            // resign deletes the rest
                        }
                    }
                }
                s.resign(cfg.isDestroyFederation());
                s.disconnect();
            } finally {
                rtiLock.unlock();
            }
        }
        createdByKey.clear();
        createdByInstance.clear();
        discovered.clear();
        discoveredKey.clear();
        pendingNames.clear();
        setState(EndpointState.CLOSED, null, true);
    }

    // ------------------------------------------------------------------ state and reconnect

    private void setState(EndpointState s, String message, boolean tell) {
        state = s;
        stateMessage = message;
        EndpointListener l = listener;
        if (tell && l != null) {
            l.stateChanged(this, s, message);
        }
    }

    private void connectionLost(RtiSession from, String reason) {
        if (from != session || closing) {
            return;
        }
        session = null;
        closeInfra();
        discovered.clear();
        discoveredKey.clear();
        createdByKey.clear();
        createdByInstance.clear();
        pendingNames.clear();
        Events.warning(LOG, "endpoint.connectionlost", "Junction " + gatewayId + "/" + id() + ": RTI connection lost: " + reason
                + (cfg.isReconnect() ? "; reconnecting every " + cfg.getReconnectIntervalSecs() + " s" : ""),
                "gateway", gatewayId, "endpoint", id(), "federation", federation, "by", "rti");
        setState(EndpointState.LOST, "RTI connection lost: " + reason, true);
        if (cfg.isReconnect()) {
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (closing) {
            return;
        }
        try {
            worker.schedule(this::reconnect, cfg.getReconnectIntervalSecs(), TimeUnit.SECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // closing
        }
    }

    private void reconnect() {
        if (closing) {
            return;
        }
        setState(EndpointState.RECONNECTING, stateMessage, true);
        try {
            connectAndJoin();
            if (cfg.isDynamicFom()) {
                watchFdd(session);                    // the model stays; a different FDD becomes the notice
            }
            applyDeclarations();
            stats.reconnects.incrementAndGet();
            Events.notice(LOG, "endpoint.reconnected", "Junction " + gatewayId + "/" + id() + ": joined " + federation + " again",
                    "gateway", gatewayId, "endpoint", id(), "federation", federation, "by", "reconnect");
            setState(EndpointState.JOINED, null, true);
            openInfra(session);
        } catch (RtiSessionException e) {
            RtiSession s = session;
            if (s != null) {
                s.close();
                session = null;
            }
            setState(EndpointState.RECONNECTING, "reconnect failed: " + e.getMessage(), true);
            scheduleReconnect();
        }
    }

    private void warnOnce(String kind, String message) {
        if (warned.add(kind)) {
            LOG.warning("Junction " + gatewayId + "/" + id() + ": " + message + " (reported once)");
        } else if (LOG.isLoggable(Level.FINE)) {
            LOG.fine("Junction " + gatewayId + "/" + id() + ": " + message);
        }
    }

    // ================================================================== callbacks

    /** Callbacks of one session; ignored once that session is replaced. */
    private final class SessionCallbacks implements RtiSessionListener {
        private final RtiSession owner;

        SessionCallbacks(RtiSession owner) {
            this.owner = owner;
        }

        private boolean current() {
            return owner == session && !closing;
        }

        @Override
        public void discovered(long instance, int objectClass, String name, String producer) {
            if (!current()) {
                return;
            }
            com.simweaver.common.infra.HlaTap tap = infra;
            boolean mom = tap != null && tap.discovered(instance, objectClass, name);
            com.simweaver.common.hla.session.DynamicFom.Reader r = fddReader;
            if ((r != null && r.discovered(instance, objectClass)) || mom) {
                return;
            }
            Mapping m = map;
            if (m == null) {
                stats.ignoredIn.incrementAndGet();
                return;
            }
            int iemClass = m.iemClass(objectClass);
            if (iemClass == 0) {
                stats.ignoredIn.incrementAndGet();
                return;
            }
            long key = keys.incrementAndGet();
            discoveredKey.put(instance, key);
            discovered.put(key, new Discovered(instance, iemClass, objectClass, owner));
            stats.discoveredIn.incrementAndGet();
            stats.markIn(0);
            listener.discovered(HlaEndpoint.this, key, iemClass, name, producer);
            // ask for the current values, so the router can pass on a complete object
            final int[] attrs = m.subscribed.get(objectClass);
            if (attrs != null && attrs.length > 0) {
                try {
                    worker.execute(() -> {
                        rtiLock.lock();
                        try {
                            if (owner == session) {
                                owner.requestAttributeUpdate(instance, attrs, NO_TAG);
                            }
                        } catch (RtiSessionException e) {
                            warnOnce("request", "requesting attribute values fails: " + e.getMessage());
                        } finally {
                            rtiLock.unlock();
                        }
                    });
                } catch (java.util.concurrent.RejectedExecutionException ignored) {
                    // closing
                }
            }
        }

        @Override
        public void reflected(long instance, int[] attributes, byte[][] values, byte[] tag, String producer) {
            if (!current()) {
                return;
            }
            com.simweaver.common.infra.HlaTap tap = infra;
            boolean mom = tap != null && tap.reflected(instance, attributes, values);
            com.simweaver.common.hla.session.DynamicFom.Reader r = fddReader;
            if ((r != null && r.reflected(instance, attributes, values)) || mom) {
                return;
            }
            Long key = discoveredKey.get(instance);
            Discovered dsc = key == null ? null : discovered.get(key);
            if (dsc == null) {
                stats.ignoredIn.incrementAndGet();
                return;
            }
            Mapping m = map;
            int n = 0;
            int octets = 0;
            int[] ids = new int[attributes.length];
            byte[][] vals = new byte[attributes.length][];
            for (int i = 0; i < attributes.length; i++) {
                int a = m.iemAttribute(dsc.sessionClass, attributes[i]);
                if (a != 0) {
                    ids[n] = a;
                    vals[n] = values[i];
                    octets += values[i] == null ? 0 : values[i].length;
                    n++;
                }
            }
            if (n == 0) {
                return;
            }
            if (n < ids.length) {
                ids = java.util.Arrays.copyOf(ids, n);
                vals = java.util.Arrays.copyOf(vals, n);
            }
            stats.updatesIn.incrementAndGet();
            stats.markIn(octets);
            listener.reflected(HlaEndpoint.this, key, ids, vals, tag, producer);
            if (tap != null) {
                tap.observed(producer, false, octets);
            }
        }

        @Override
        public void removed(long instance, byte[] tag, String producer) {
            if (!current()) {
                return;
            }
            com.simweaver.common.infra.HlaTap tap = infra;
            boolean mom = tap != null && tap.removed(instance);
            com.simweaver.common.hla.session.DynamicFom.Reader r = fddReader;
            if ((r != null && r.removed(instance)) || mom) {
                return;
            }
            Long key = discoveredKey.remove(instance);
            if (key == null) {
                return;
            }
            discovered.remove(key);
            stats.removedIn.incrementAndGet();
            stats.markIn(0);
            listener.removed(HlaEndpoint.this, key, tag);
        }

        @Override
        public void received(int interactionClass, int[] parameters, byte[][] values, byte[] tag, String producer) {
            if (!current()) {
                return;
            }
            com.simweaver.common.infra.HlaTap tap = infra;
            if (tap != null && tap.received(interactionClass, parameters, values)) {
                return;                                  // a MOM report the Infra view asked for
            }
            Mapping m = map;
            int ic = m.iemInteraction(interactionClass);
            if (ic == 0) {
                stats.ignoredIn.incrementAndGet();
                return;
            }
            int n = 0;
            int octets = 0;
            int[] ids = new int[parameters.length];
            byte[][] vals = new byte[parameters.length][];
            for (int i = 0; i < parameters.length; i++) {
                int p = m.iemParameter(interactionClass, parameters[i]);
                if (p != 0) {
                    ids[n] = p;
                    vals[n] = values[i];
                    octets += values[i] == null ? 0 : values[i].length;
                    n++;
                }
            }
            if (n < ids.length) {
                ids = java.util.Arrays.copyOf(ids, n);
                vals = java.util.Arrays.copyOf(vals, n);
            }
            stats.interactionsIn.incrementAndGet();
            stats.markIn(octets);
            listener.received(HlaEndpoint.this, ic, ids, vals, tag, producer);
            if (tap != null) {
                tap.observed(producer, true, octets);
            }
        }

        @Override
        public void provideRequested(long instance, int[] attributes, byte[] tag) {
            if (!current()) {
                return;
            }
            Created c = createdByInstance.get(instance);
            if (c == null) {
                return;
            }
            Mapping m = map;
            int[] ids = new int[attributes.length];
            int n = 0;
            for (int a : attributes) {
                int id = m.iemAttribute(c.sessionClass, a);
                if (id != 0) {
                    ids[n++] = id;
                }
            }
            if (n > 0) {
                listener.provideRequested(HlaEndpoint.this, c.key, java.util.Arrays.copyOf(ids, n));
            }
        }

        @Override
        public void nameReserved(final String name, final boolean success) {
            if (!current()) {
                return;
            }
            try {
                worker.execute(() -> HlaEndpoint.this.nameReserved(name, success));
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // closing
            }
        }

        @Override
        public void connectionLost(final String reason) {
            try {
                worker.execute(() -> HlaEndpoint.this.connectionLost(owner, reason));
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // closing
            }
        }
    }

    // ================================================================== bookkeeping

    /** A discovered object. */
    private static final class Discovered {
        final long instance;
        final int objectClass;
        final int sessionClass;
        final RtiSession session;

        Discovered(long instance, int objectClass, int sessionClass, RtiSession session) {
            this.instance = instance;
            this.objectClass = objectClass;
            this.sessionClass = sessionClass;
            this.session = session;
        }
    }

    /** An object the router asked for. */
    private static final class Created {
        final long gwId;
        final int objectClass;
        final int sessionClass;
        final List<String> names;
        final RtiSession session;
        int nextName;
        volatile long instance;
        volatile long key;

        Created(long gwId, int objectClass, int sessionClass, List<String> names, RtiSession session) {
            this.gwId = gwId;
            this.objectClass = objectClass;
            this.sessionClass = sessionClass;
            this.names = names;
            this.session = session;
        }

        synchronized String nextName() {
            return nextName < names.size() ? names.get(nextName++) : null;
        }
    }

    /** IEM ids to session handles and back, for one session. Built once per declare, then read-only. */
    static final class Mapping {
        final Map<Integer, Integer> classToSession = new HashMap<Integer, Integer>();
        final Map<Integer, Integer> classFromSession = new HashMap<Integer, Integer>();
        final Map<Integer, Integer> attributeToSession = new HashMap<Integer, Integer>();
        final Map<Long, Integer> attributeFromSession = new HashMap<Long, Integer>();     // (session class << 32 | session attr)
        final Map<Integer, Integer> interactionToSession = new HashMap<Integer, Integer>();
        final Map<Integer, Integer> interactionFromSession = new HashMap<Integer, Integer>();
        final Map<Long, Integer> parameterToSession = new HashMap<Long, Integer>();   // (session ic << 32 | iem param)
        final Map<Long, Integer> parameterFromSession = new HashMap<Long, Integer>();     // (session ic << 32 | session param)
        final Map<Integer, Set<Integer>> published = new HashMap<Integer, Set<Integer>>();        // iem class -> iem attrs
        final Map<Integer, int[]> subscribed = new HashMap<Integer, int[]>();                     // session class -> session attrs
        final Set<Integer> publishedInteractions = new java.util.HashSet<Integer>();              // iem ids

        void putClass(int iem, int session) {
            classToSession.put(iem, session);
            classFromSession.put(session, iem);
        }

        /** Handles are only unique within a class (hierarchy), so the way back is per class. */
        void putAttribute(int sessionClass, int iem, int session) {
            attributeToSession.put(iem, session);
            attributeFromSession.put(pair(sessionClass, session), iem);
        }

        static long pair(int a, int b) {
            return ((long) a << 32) | (b & 0xFFFFFFFFL);
        }

        void putInteraction(int iem, int session) {
            interactionToSession.put(iem, session);
            interactionFromSession.put(session, iem);
        }

        void putParameter(int sessionIc, int iem, int session) {
            parameterToSession.put(pair(sessionIc, iem), session);
            parameterFromSession.put(pair(sessionIc, session), iem);
        }

        int sessionClass(int iem) {
            Integer s = classToSession.get(iem);
            return s == null ? 0 : s;
        }

        int iemClass(int session) {
            Integer s = classFromSession.get(session);
            return s == null ? 0 : s;
        }

        int sessionAttribute(int iem) {
            Integer s = attributeToSession.get(iem);
            return s == null ? 0 : s;
        }

        int iemAttribute(int sessionClass, int session) {
            Integer s = attributeFromSession.get(pair(sessionClass, session));
            return s == null ? 0 : s;
        }

        int[] sessionAttributes(Set<Integer> iem) {
            int[] r = new int[iem.size()];
            int n = 0;
            for (Integer a : iem) {
                int h = sessionAttribute(a);
                if (h != 0) {
                    r[n++] = h;
                }
            }
            return java.util.Arrays.copyOf(r, n);
        }

        int sessionInteraction(int iem) {
            Integer s = interactionToSession.get(iem);
            return s == null ? 0 : s;
        }

        int iemInteraction(int session) {
            Integer s = interactionFromSession.get(session);
            return s == null ? 0 : s;
        }

        int sessionParameter(int sessionIc, int iem) {
            Integer s = parameterToSession.get(pair(sessionIc, iem));
            return s == null ? 0 : s;
        }

        int iemParameter(int sessionIc, int session) {
            Integer s = parameterFromSession.get(pair(sessionIc, session));
            return s == null ? 0 : s;
        }
    }
}
