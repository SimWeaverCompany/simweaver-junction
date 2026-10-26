package com.simweaver.common.infra;

import com.simweaver.common.json.Json;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One joined federate's view of its federation for the Infra page: the other federates and their
 * activity, from the MOM ({@code HLAmanager.HLAfederate} objects, asked for with attribute update
 * requests while someone watches) and from what the federate itself receives from each of them.
 * Publications and subscriptions come from the MOM's report interactions, on request.
 *
 * <p>The owner forwards its RTI callbacks ({@link #discovered}, {@link #reflected},
 * {@link #removed}, {@link #received}: they return true when the callback was about the MOM,
 * so the owner can skip it) and counts received traffic per producer ({@link #observed}). The
 * RTI is called through the {@link MomPort} only on the Infra thread. Nothing is sent that changes
 * the federation (no HLAsetTiming): only requests and request interactions.
 */
public final class HlaTap {

    private static final Logger LOG = Logger.getLogger(HlaTap.class.getName());

    static final String FEDERATE = "HLAobjectRoot.HLAmanager.HLAfederate";
    static final String FEDERATION = "HLAobjectRoot.HLAmanager.HLAfederation";
    private static final String REQUEST = "HLAinteractionRoot.HLAmanager.HLAfederate.HLArequest.";
    private static final String REPORT = "HLAinteractionRoot.HLAmanager.HLAfederate.HLAreport.";
    static final String REQ_PUBLICATIONS = REQUEST + "HLArequestPublications";
    static final String REQ_SUBSCRIPTIONS = REQUEST + "HLArequestSubscriptions";
    static final String REP_OBJECT_PUBLICATION = REPORT + "HLAreportObjectClassPublication";
    static final String REP_INTERACTION_PUBLICATION = REPORT + "HLAreportInteractionPublication";
    static final String REP_OBJECT_SUBSCRIPTION = REPORT + "HLAreportObjectClassSubscription";
    static final String REP_INTERACTION_SUBSCRIPTION = REPORT + "HLAreportInteractionSubscription";
    static final String REP_EXCEPTION = REPORT + "HLAreportMOMexception";

    /** HLAfederate attributes asked for on discovery and now and then. */
    static final String[] FEDERATE_STATIC = {
        "HLAfederateHandle", "HLAfederateName", "HLAfederateType", "HLAfederateHost", "HLARTIversion",
        "HLAFOMmoduleDesignatorList", "HLAasynchronousDelivery", "HLAlookahead", "HLAconveyRegionDesignatorSets",
        "HLAconveyProducingFederate"
    };
    /** HLAfederate attributes asked for at every poll. */
    static final String[] FEDERATE_DYNAMIC = {
        "HLAfederateState", "HLAtimeManagerState", "HLAtimeConstrained", "HLAtimeRegulating", "HLAlogicalTime",
        "HLAGALT", "HLALITS", "HLAROlength", "HLATSOlength", "HLAreflectionsReceived", "HLAupdatesSent",
        "HLAinteractionsReceived", "HLAinteractionsSent", "HLAobjectInstancesThatCanBeDeleted",
        "HLAobjectInstancesUpdated", "HLAobjectInstancesReflected", "HLAobjectInstancesDeleted",
        "HLAobjectInstancesRemoved", "HLAobjectInstancesRegistered", "HLAobjectInstancesDiscovered",
        "HLAtimeGrantedTime", "HLAtimeAdvancingTime"
    };
    static final String[] FEDERATION_ATTRIBUTES = {
        "HLAfederationName", "HLAfederatesInFederation", "HLARTIversion", "HLAMIMdesignator",
        "HLAFOMmoduleDesignatorList", "HLAtimeImplementationName", "HLAlastSaveName", "HLAlastSaveTime",
        "HLAnextSaveName", "HLAnextSaveTime", "HLAautoProvide"
    };
    /** Counters with a rate. */
    private static final String[] RATED = {"HLAupdatesSent", "HLAreflectionsReceived", "HLAinteractionsSent", "HLAinteractionsReceived"};
    /** Resigned federates are listed this long. */
    private static final long RESIGNED_KEPT_MILLIS = 300_000;
    private static final long STATIC_EVERY_MILLIS = 30_000;

    private final InfraRegistry registry;
    private final InfraSource source;
    private final boolean hla4;
    private final String federation;
    private final String connection;
    private final long opened = System.currentTimeMillis();
    private volatile String ownFederate;
    private volatile MomPort port;
    private volatile boolean closed;

    // resolved MOM handles (written on the Infra thread before any callback can match them)
    private volatile int federateClass;
    private volatile int federationClass;
    private final Map<Integer, String> federateAttr = new ConcurrentHashMap<Integer, String>();
    private final Map<Integer, String> federationAttr = new ConcurrentHashMap<Integer, String>();
    private volatile int[] staticAttrs = new int[0];
    private volatile int[] dynamicAttrs = new int[0];
    private volatile int[] federationAttrs = new int[0];
    private final Map<Integer, String> reportClasses = new ConcurrentHashMap<Integer, String>();
    private final Map<Integer, Map<Integer, String>> reportParams = new ConcurrentHashMap<Integer, Map<Integer, String>>();
    private int reqPublications;
    private int reqSubscriptions;
    private int reqParamPub;
    private int reqParamSub;
    private boolean requestsPublished;

    // state (guarded by this)
    private String momStatus = "waiting";
    private String momProblem;
    private final List<String> momMissing = new ArrayList<String>();
    private boolean started;
    private long lastPoll;
    private long lastStatic;
    private volatile long viewedAt;
    private final Map<Long, Fed> byInstance = new LinkedHashMap<Long, Fed>();
    private final Map<String, Fed> byName = new LinkedHashMap<String, Fed>();
    private final Map<String, Observed> observed = new ConcurrentHashMap<String, Observed>();
    private final Map<String, Object> federationInfo = new LinkedHashMap<String, Object>();
    private long federationInstance = -1;
    private String timeImplementation;
    private final List<String> momExceptions = new ArrayList<String>();

    HlaTap(InfraRegistry registry, InfraSource source, boolean hla4, String federation, String ownFederate,
           String connection, MomPort port) {
        this.registry = registry;
        this.source = source;
        this.hla4 = hla4;
        this.federation = federation == null ? "" : federation;
        this.ownFederate = ownFederate == null ? "" : ownFederate;
        this.connection = connection;
        this.port = port;
    }

    /** @param name this federate's name in the federation (shown as "this federate") */
    public void setOwnFederate(String name) {
        this.ownFederate = name == null ? "" : name;
    }

    /** Stops: the registry forgets the tap. Call when the federate resigns or loses its connection. */
    public void close() {
        closed = true;
        port = null;
        registry.remove(this);
    }

    boolean isClosed() {
        return closed;
    }

    InfraSource source() {
        return source;
    }

    String family() {
        return hla4 ? InfraRegistry.HLA4 : InfraRegistry.HLA3;
    }

    String federation() {
        return federation;
    }

    void viewed(long now) {
        viewedAt = now;
    }

    // ================================================================== traffic the owner receives

    /**
     * Counts an update or interaction the owner received.
     *
     * @param producer the producing federate's name ("" when the RTI does not tell)
     */
    public void observed(String producer, boolean interaction, int octets) {
        if (closed) {
            return;
        }
        String p = producer == null || producer.isEmpty() ? "" : producer;
        Observed o = observed.get(p);
        if (o == null) {
            o = observed.computeIfAbsent(p, k -> new Observed());
        }
        synchronized (o) {
            long now = System.currentTimeMillis();
            (interaction ? o.interactions : o.updates).add(now, 1, octets);
            o.last = now;
        }
    }

    // ================================================================== RTI callbacks (owner's thread)

    /** @return true when the object is a MOM federate or federation object this tap follows */
    public boolean discovered(long instance, int objectClass, String name) {
        if (closed || objectClass == 0) {
            return false;
        }
        if (objectClass == federateClass) {
            synchronized (this) {
                Fed f = byInstance.get(instance);
                if (f == null) {
                    f = new Fed(instance, name);
                    byInstance.put(instance, f);
                }
            }
            final int[] all = concat(staticAttrs, dynamicAttrs);
            registry.execute(() -> request(instance, all));
            return true;
        }
        if (objectClass == federationClass) {
            synchronized (this) {
                federationInstance = instance;
            }
            final int[] attrs = federationAttrs;
            registry.execute(() -> request(instance, attrs));
            return true;
        }
        return false;
    }

    /** @return true when the values were of a MOM object this tap follows */
    public boolean reflected(long instance, int[] attributes, byte[][] values) {
        if (closed) {
            return false;
        }
        synchronized (this) {
            Fed f = byInstance.get(instance);
            if (f != null) {
                long now = System.currentTimeMillis();
                for (int i = 0; i < attributes.length; i++) {
                    String n = federateAttr.get(attributes[i]);
                    if (n != null && values[i] != null) {
                        f.set(n, values[i], now, timeImplementation);
                    }
                }
                f.momUpdated = now;
                if (f.name != null && !f.name.isEmpty()) {
                    byName.put(f.name, f);
                }
                return true;
            }
            if (instance == federationInstance) {
                for (int i = 0; i < attributes.length; i++) {
                    String n = federationAttr.get(attributes[i]);
                    if (n != null && values[i] != null) {
                        setFederation(n, values[i]);
                    }
                }
                return true;
            }
        }
        return false;
    }

    /** @return true when the object was a MOM object this tap follows */
    public boolean removed(long instance) {
        if (closed) {
            return false;
        }
        synchronized (this) {
            Fed f = byInstance.get(instance);
            if (f != null) {
                f.resigned = System.currentTimeMillis();
                return true;
            }
            if (instance == federationInstance) {
                federationInstance = -1;
                return true;
            }
        }
        return false;
    }

    /** @return true when the interaction was a MOM report this tap asked for */
    public boolean received(int interactionClass, int[] parameters, byte[][] values) {
        if (closed) {
            return false;
        }
        String report = reportClasses.get(interactionClass);
        if (report == null) {
            return false;
        }
        Map<Integer, String> names = reportParams.get(interactionClass);
        Map<String, byte[]> p = new HashMap<String, byte[]>();
        for (int i = 0; i < parameters.length; i++) {
            String n = names == null ? null : names.get(parameters[i]);
            if (n != null && values[i] != null) {
                p.put(n, values[i]);
            }
        }
        registry.execute(() -> report(report, p));        // names are looked up on the Infra thread, not in the callback
        return true;
    }

    // ================================================================== the Infra thread

    /** Called once a second by the registry (Infra thread). */
    void tick(long now, long pollMillis, long slowPollMillis) {
        MomPort pt = port;
        if (closed || pt == null || !registry.isMomEnabled()) {
            return;
        }
        if (!started) {
            start(pt);
            return;
        }
        if (federateClass == 0) {
            return;
        }
        boolean watched = now - viewedAt < 30_000;
        long interval = watched ? pollMillis : slowPollMillis;
        if (now - lastPoll < interval) {
            pruneResigned(now);
            return;
        }
        lastPoll = now;
        boolean withStatic = now - lastStatic >= STATIC_EVERY_MILLIS;
        try {
            pt.requestClassUpdate(federateClass, withStatic ? concat(staticAttrs, dynamicAttrs) : dynamicAttrs);
            if (withStatic) {
                lastStatic = now;
                if (federationClass != 0 && federationAttrs.length > 0) {
                    pt.requestClassUpdate(federationClass, federationAttrs);
                }
            }
        } catch (Exception e) {
            problem("requesting MOM values fails: " + message(e));
        }
        pruneResigned(now);
    }

    /** Asks for a fresh MOM sample at once (the page's refresh). */
    void pollNow() {
        lastPoll = 0;
        lastStatic = 0;
    }

    private void start(MomPort pt) {
        started = true;
        List<String> missing = new ArrayList<String>();
        try {
            int fc = pt.objectClass(FEDERATE);
            List<Integer> st = new ArrayList<Integer>();
            List<Integer> dy = new ArrayList<Integer>();
            resolve(pt, fc, FEDERATE_STATIC, federateAttr, st, missing);
            resolve(pt, fc, FEDERATE_DYNAMIC, federateAttr, dy, missing);
            staticAttrs = toArray(st);
            dynamicAttrs = toArray(dy);
            federateClass = fc;
            pt.subscribeObjectClass(fc, concat(staticAttrs, dynamicAttrs));
        } catch (Exception e) {
            federateClass = 0;
            synchronized (this) {
                momStatus = "unavailable";
                momProblem = "the RTI has no MOM class " + FEDERATE + " (" + message(e) + ")";
            }
            LOG.info("Infra: " + source.label() + " (" + federation + "): " + momProblem);
            return;
        }
        try {
            int fc = pt.objectClass(FEDERATION);
            List<Integer> fa = new ArrayList<Integer>();
            resolve(pt, fc, FEDERATION_ATTRIBUTES, federationAttr, fa, missing);
            federationAttrs = toArray(fa);
            federationClass = fc;
            if (federationAttrs.length > 0) {
                pt.subscribeObjectClass(fc, federationAttrs);
            }
        } catch (Exception e) {
            missing.add(FEDERATION);
        }
        for (String r : new String[] {REP_OBJECT_PUBLICATION, REP_INTERACTION_PUBLICATION, REP_OBJECT_SUBSCRIPTION,
            REP_INTERACTION_SUBSCRIPTION, REP_EXCEPTION}) {
            try {
                int ic = pt.interactionClass(r);
                Map<Integer, String> params = new HashMap<Integer, String>();
                for (String pn : new String[] {"HLAfederate", "HLAnumberOfClasses", "HLAobjectClass", "HLAattributeList",
                    "HLAinteractionClassList", "HLAactive", "HLAservice", "HLAexception", "HLAparameterError"}) {
                    try {
                        params.put(pt.parameter(ic, pn), pn);
                    } catch (Exception notInThisReport) {
                        // each report has some of them
                    }
                }
                reportParams.put(ic, params);
                reportClasses.put(ic, r.substring(REPORT.length()));
                pt.subscribeInteractionClass(ic);
            } catch (Exception e) {
                missing.add(r.substring(r.lastIndexOf('.') + 1));
            }
        }
        try {
            reqPublications = pt.interactionClass(REQ_PUBLICATIONS);
            reqParamPub = pt.parameter(reqPublications, "HLAfederate");
            reqSubscriptions = pt.interactionClass(REQ_SUBSCRIPTIONS);
            reqParamSub = pt.parameter(reqSubscriptions, "HLAfederate");
        } catch (Exception e) {
            reqPublications = 0;
            reqSubscriptions = 0;
            missing.add("HLArequestPublications / HLArequestSubscriptions");
        }
        synchronized (this) {
            momStatus = "ok";
            momMissing.clear();
            momMissing.addAll(missing);
        }
        try {
            pt.requestClassUpdate(federateClass, concat(staticAttrs, dynamicAttrs));
            if (federationClass != 0 && federationAttrs.length > 0) {
                pt.requestClassUpdate(federationClass, federationAttrs);
            }
            lastPoll = System.currentTimeMillis();
            lastStatic = lastPoll;
        } catch (Exception e) {
            problem("requesting MOM values fails: " + message(e));
        }
    }

    private static void resolve(MomPort pt, int cls, String[] names, Map<Integer, String> into, List<Integer> ids,
                                List<String> missing) {
        for (String n : names) {
            try {
                int a = pt.attribute(cls, n);
                into.put(a, n);
                ids.add(a);
            } catch (Exception e) {
                missing.add(n);
            }
        }
    }

    private void request(long instance, int[] attrs) {
        MomPort pt = port;
        if (pt == null || closed || attrs.length == 0) {
            return;
        }
        try {
            pt.requestUpdate(instance, attrs);
        } catch (Exception e) {
            problem("requesting MOM values fails: " + message(e));
        }
    }

    /**
     * Asks the MOM for a federate's publications and subscriptions (Infra thread).
     *
     * @param federateHandle the federate's handle as hex (from the JSON)
     * @return null when asked, else why not
     */
    String requestDeclarations(String federateHandle) {
        MomPort pt = port;
        if (pt == null || closed) {
            return "the connection is closed";
        }
        if (reqPublications == 0 || reqSubscriptions == 0) {
            return "this RTI's MOM has no publication / subscription requests";
        }
        byte[] inner = MomCodec.unhex(federateHandle);
        Fed f;
        synchronized (this) {
            f = null;
            for (Fed x : byInstance.values()) {
                if (x.handle != null && Arrays.equals(x.handle, inner)) {
                    f = x;
                }
            }
            if (f == null) {
                return "no federate with handle " + federateHandle;
            }
            f.declarationsRequested = System.currentTimeMillis();
            f.pubObjects.clear();
            f.pubInteractions.clear();
            f.subObjects.clear();
            f.subInteractions.clear();
            f.pubReceived = false;
            f.subReceived = false;
        }
        byte[] value = MomCodec.encodeHandle(inner);
        try {
            if (!requestsPublished) {
                pt.publishInteractionClass(reqPublications);
                pt.publishInteractionClass(reqSubscriptions);
                requestsPublished = true;
            }
            pt.sendInteraction(reqPublications, new int[] {reqParamPub}, new byte[][] {value});
            pt.sendInteraction(reqSubscriptions, new int[] {reqParamSub}, new byte[][] {value});
            return null;
        } catch (Exception e) {
            String m = "asking the MOM fails: " + message(e);
            problem(m);
            return m;
        }
    }

    private synchronized void problem(String text) {
        momProblem = text;
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine("Infra: " + source.label() + " (" + federation + "): " + text);
        }
    }

    private static String message(Exception e) {
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    private synchronized void pruneResigned(long now) {
        for (java.util.Iterator<Fed> it = byInstance.values().iterator(); it.hasNext();) {
            Fed f = it.next();
            if (f.resigned > 0 && now - f.resigned > RESIGNED_KEPT_MILLIS) {
                it.remove();
                if (f.name != null) {
                    byName.remove(f.name, f);
                }
            }
        }
    }

    // ================================================================== MOM reports

    private void report(String report, Map<String, byte[]> p) {
        if ("HLAreportMOMexception".equals(report)) {
            String s = MomCodec.unicodeString(p.get("HLAservice"));
            String x = MomCodec.unicodeString(p.get("HLAexception"));
            synchronized (this) {
                momExceptions.add((s == null ? "?" : s) + ": " + (x == null ? "?" : x));
                while (momExceptions.size() > 10) {
                    momExceptions.remove(0);
                }
            }
            return;
        }
        byte[] fh = MomCodec.handle(p.get("HLAfederate"));
        if (fh == null) {
            return;
        }
        MomPort pt = port;
        synchronized (this) {
            Fed f = null;
            for (Fed x : byInstance.values()) {
                if (x.handle != null && Arrays.equals(x.handle, fh)) {
                    f = x;
                }
            }
            if (f == null) {
                return;
            }
            f.declarationsReceived = System.currentTimeMillis();
            switch (report) {
                case "HLAreportObjectClassPublication":
                case "HLAreportObjectClassSubscription": {
                    boolean pub = report.endsWith("Publication");
                    byte[] cls = MomCodec.handle(p.get("HLAobjectClass"));
                    if (cls == null) {
                        return;
                    }
                    List<String> attrs = new ArrayList<String>();
                    for (byte[] a : MomCodec.handleList(p.get("HLAattributeList"))) {
                        String n = pt == null ? null : pt.attributeName(cls, a);
                        attrs.add(n == null ? "attribute " + MomCodec.hex(a) : n);
                    }
                    String cn = pt == null ? null : pt.objectClassName(cls);
                    String name = cn == null ? "object class " + MomCodec.hex(cls) : cn;
                    Boolean active = MomCodec.bool(p.get("HLAactive"));
                    Map<String, Object> entry = new LinkedHashMap<String, Object>();
                    entry.put("class", name);
                    if (!pub && active != null) {
                        entry.put("active", active);
                    }
                    entry.put("attributes", attrs);
                    (pub ? f.pubObjects : f.subObjects).put(name, entry);
                    Integer total = MomCodec.int32(p.get("HLAnumberOfClasses"));
                    if (pub) {
                        f.pubObjectTotal = total;
                        f.pubReceived = true;
                    } else {
                        f.subObjectTotal = total;
                        f.subReceived = true;
                    }
                    break;
                }
                case "HLAreportInteractionPublication": {
                    f.pubInteractions.clear();
                    for (byte[] ic : MomCodec.handleList(p.get("HLAinteractionClassList"))) {
                        String n = pt == null ? null : pt.interactionClassName(ic);
                        Map<String, Object> entry = new LinkedHashMap<String, Object>();
                        entry.put("class", n == null ? "interaction class " + MomCodec.hex(ic) : n);
                        f.pubInteractions.add(entry);
                    }
                    f.pubReceived = true;
                    break;
                }
                case "HLAreportInteractionSubscription": {
                    f.subInteractions.clear();
                    for (MomCodec.InteractionSub s : MomCodec.interactionSubList(p.get("HLAinteractionClassList"))) {
                        String n = pt == null ? null : pt.interactionClassName(s.handle);
                        Map<String, Object> entry = new LinkedHashMap<String, Object>();
                        entry.put("class", n == null ? "interaction class " + MomCodec.hex(s.handle) : n);
                        entry.put("active", s.active);
                        f.subInteractions.add(entry);
                    }
                    f.subReceived = true;
                    break;
                }
                default:
                    break;
            }
        }
    }

    private void setFederation(String name, byte[] v) {
        switch (name) {
            case "HLAfederatesInFederation":
                federationInfo.put("federatesinfederation", MomCodec.handleList(v).size());
                break;
            case "HLAFOMmoduleDesignatorList":
                federationInfo.put("fommodules", MomCodec.stringList(v));
                break;
            case "HLAautoProvide": {
                Integer i = MomCodec.int32(v);
                if (i != null) {
                    federationInfo.put("autoprovide", i == 1);
                }
                break;
            }
            case "HLAlastSaveTime":
            case "HLAnextSaveTime":
                federationInfo.put(name.substring(3).toLowerCase(java.util.Locale.ROOT), MomCodec.time(v, timeImplementation));
                break;
            default: {
                String s = MomCodec.unicodeString(v);
                if (s != null) {
                    if ("HLAtimeImplementationName".equals(name)) {
                        timeImplementation = s;
                    }
                    federationInfo.put(name.startsWith("HLA") ? name.substring(3).toLowerCase(java.util.Locale.ROOT) : name, s);
                }
            }
        }
    }

    // ================================================================== JSON

    synchronized Json.JsonObject json(String key, boolean detail, long now) {
        Json.JsonObject o = Json.object();
        o.put("key", key);
        o.put("family", family());
        o.put("protocol", InfraRegistry.familyLabel(family()));
        o.put("name", federation);
        o.put("federation", federation);
        o.put("source", source.toJson());
        o.put("connection", connection == null || connection.isEmpty() ? "RTI default" : connection);
        o.put("ownfederate", ownFederate);
        o.put("mom", momStatus);
        o.putIfNotNull("momproblem", momProblem);

        // the federates: MOM objects, plus producers seen only by their traffic
        List<Fed> feds = new ArrayList<Fed>(byInstance.values());
        Map<String, Observed> obs = new TreeMap<String, Observed>(observed);
        int live = 0;
        for (Fed f : feds) {
            if (f.resigned == 0) {
                live++;
            }
        }
        int extra = 0;
        for (String p : obs.keySet()) {
            if (!p.isEmpty() && !byName.containsKey(p)) {
                extra++;
            }
        }
        o.put("federates", live + extra);
        // traffic from the others, as this federate receives it
        long last = 0;
        double rate = 0;
        double octets = 0;
        int[] history = new int[ActivityMeter.SECONDS - 1];
        for (Observed ob : obs.values()) {
            synchronized (ob) {
                rate += ob.updates.rate(now) + ob.interactions.rate(now);
                octets += ob.updates.octetRate(now) + ob.interactions.octetRate(now);
                int[] h1 = ob.updates.history(now);
                int[] h2 = ob.interactions.history(now);
                for (int i = 0; i < history.length; i++) {
                    history[i] += h1[i] + h2[i];
                }
                last = Math.max(last, ob.last);
            }
        }
        o.put("rate", ActivityMeter.round(rate));
        o.put("octetrate", Math.round(octets));
        o.put("history", history);
        o.put("last", last);
        o.put("joined", opened);
        o.put("status", last == 0 ? "joined" : DisTap.status(last, now));
        Object rti = federationInfo.get("rtiversion");
        o.putIfNotNull("rtiversion", rti);
        if (!detail) {
            return o;
        }
        o.put("federationinfo", new LinkedHashMap<String, Object>(federationInfo));
        o.put("timeimplementation", timeImplementation);
        if (!momMissing.isEmpty()) {
            o.put("mommissing", new ArrayList<String>(momMissing));
        }
        if (!momExceptions.isEmpty()) {
            o.put("momexceptions", new ArrayList<String>(momExceptions));
        }
        o.put("declarationsavailable", reqPublications != 0 && reqSubscriptions != 0);
        o.put("pollseconds", registry.pollMillis() / 1000.0);
        List<Object> list = new ArrayList<Object>();
        feds.sort((a, b) -> {
            int c = Boolean.compare(a.resigned != 0, b.resigned != 0);
            return c != 0 ? c : String.valueOf(a.name).compareToIgnoreCase(String.valueOf(b.name));
        });
        for (Fed f : feds) {
            Observed ob = f.name == null ? null : obs.get(f.name);
            list.add(f.json(ownFederate, ob, now));
        }
        for (Map.Entry<String, Observed> e : obs.entrySet()) {
            if (!byName.containsKey(e.getKey())) {
                Json.JsonObject x = Json.object();
                x.put("name", e.getKey().isEmpty() ? "(producer not told)" : e.getKey());
                x.put("self", e.getKey().equals(ownFederate));
                x.put("frommom", false);
                x.put("observed", observedJson(e.getValue(), now));
                list.add(x);
            }
        }
        o.put("federatelist", list);
        return o;
    }

    static Json.JsonObject observedJson(Observed ob, long now) {
        synchronized (ob) {
            int[] h1 = ob.updates.history(now);
            int[] h2 = ob.interactions.history(now);
            int[] h = new int[h1.length];
            for (int i = 0; i < h.length; i++) {
                h[i] = h1[i] + h2[i];
            }
            return Json.object()
                    .put("updates", ob.updates.total())
                    .put("interactions", ob.interactions.total())
                    .put("updaterate", ActivityMeter.round(ob.updates.rate(now)))
                    .put("interactionrate", ActivityMeter.round(ob.interactions.rate(now)))
                    .put("octetrate", Math.round(ob.updates.octetRate(now) + ob.interactions.octetRate(now)))
                    .put("last", ob.last)
                    .put("history", h);
        }
    }

    // ================================================================== state

    static final class Observed {
        final ActivityMeter updates = new ActivityMeter();
        final ActivityMeter interactions = new ActivityMeter();
        long last;
    }

    /** One HLAfederate MOM object. */
    private static final class Fed {
        final long instance;
        final String objectName;
        byte[] handle;
        String name;
        long momUpdated;
        long resigned;
        final Map<String, Object> values = new LinkedHashMap<String, Object>();
        final Map<String, long[]> samples = new HashMap<String, long[]>();     // counter -> {value, time}
        final Map<String, Double> rates = new LinkedHashMap<String, Double>();
        long declarationsRequested;
        long declarationsReceived;
        boolean pubReceived;
        boolean subReceived;
        Integer pubObjectTotal;
        Integer subObjectTotal;
        final Map<String, Object> pubObjects = new TreeMap<String, Object>();
        final List<Object> pubInteractions = new ArrayList<Object>();
        final Map<String, Object> subObjects = new TreeMap<String, Object>();
        final List<Object> subInteractions = new ArrayList<Object>();

        Fed(long instance, String objectName) {
            this.instance = instance;
            this.objectName = objectName;
        }

        void set(String attr, byte[] v, long now, String timeImpl) {
            switch (attr) {
                case "HLAfederateHandle":
                    handle = MomCodec.handle(v);
                    return;
                case "HLAfederateName":
                    name = MomCodec.unicodeString(v);
                    return;
                case "HLAfederateType":
                case "HLAfederateHost":
                case "HLARTIversion":
                    values.put(attr, MomCodec.unicodeString(v));
                    return;
                case "HLAFOMmoduleDesignatorList":
                    values.put(attr, MomCodec.stringList(v));
                    return;
                case "HLAtimeConstrained":
                case "HLAtimeRegulating":
                case "HLAasynchronousDelivery":
                case "HLAconveyRegionDesignatorSets":
                case "HLAconveyProducingFederate":
                    values.put(attr, MomCodec.bool(v));
                    return;
                case "HLAfederateState":
                case "HLAtimeManagerState":
                    values.put(attr, MomCodec.int32(v));
                    return;
                case "HLAlogicalTime":
                case "HLAlookahead":
                case "HLAGALT":
                case "HLALITS":
                    values.put(attr, MomCodec.time(v, timeImpl));
                    return;
                default: {
                    Long c = MomCodec.count(v);
                    values.put(attr, c);
                    if (c != null && Arrays.asList(RATED).contains(attr)) {
                        long[] prev = samples.get(attr);
                        if (prev != null && now - prev[1] >= 500 && c >= prev[0]) {
                            rates.put(attr, ActivityMeter.round((c - prev[0]) * 1000.0 / (now - prev[1])));
                        }
                        if (prev == null || now - prev[1] >= 500 || c < prev[0]) {
                            samples.put(attr, new long[] {c, now});
                        }
                    }
                }
            }
        }

        Json.JsonObject json(String own, Observed ob, long now) {
            Json.JsonObject o = Json.object();
            o.put("name", name == null ? objectName : name);
            o.put("frommom", true);
            o.put("handle", handle == null ? null : MomCodec.hex(handle));
            o.put("self", name != null && name.equals(own));
            o.put("type", values.get("HLAfederateType"));
            o.put("host", values.get("HLAfederateHost"));
            o.put("rtiversion", values.get("HLARTIversion"));
            Integer st = (Integer) values.get("HLAfederateState");
            o.put("state", st == null ? null : federateState(st));
            Integer tm = (Integer) values.get("HLAtimeManagerState");
            o.put("timestate", tm == null ? null : tm == 0 ? "granted" : tm == 1 ? "advancing" : String.valueOf(tm));
            o.put("timeregulating", values.get("HLAtimeRegulating"));
            o.put("timeconstrained", values.get("HLAtimeConstrained"));
            o.put("logicaltime", values.get("HLAlogicalTime"));
            o.put("lookahead", values.get("HLAlookahead"));
            Map<String, Object> rest = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, Object> e : values.entrySet()) {
                rest.put(e.getKey(), e.getValue());
            }
            o.put("mom", rest);
            Map<String, Object> r = new LinkedHashMap<String, Object>();
            for (String k : RATED) {
                r.put(k.substring(3).toLowerCase(java.util.Locale.ROOT), rates.get(k));
            }
            o.put("momrates", r);
            o.put("momupdated", momUpdated);
            if (resigned > 0) {
                o.put("resigned", resigned);
            }
            if (ob != null) {
                o.put("observed", observedJson(ob, now));
            }
            Json.JsonObject d = Json.object();
            d.put("requested", declarationsRequested);
            d.put("received", declarationsReceived);
            if (pubReceived) {
                d.put("publications", Json.object().put("objects", new ArrayList<Object>(pubObjects.values()))
                        .put("objectclasses", pubObjectTotal).put("interactions", new ArrayList<Object>(pubInteractions)));
            }
            if (subReceived) {
                d.put("subscriptions", Json.object().put("objects", new ArrayList<Object>(subObjects.values()))
                        .put("objectclasses", subObjectTotal).put("interactions", new ArrayList<Object>(subInteractions)));
            }
            o.put("declarations", d);
            return o;
        }

        /** HLAfederateState of IEEE 1516.1-2010 (1 active, 3 save in progress, 5 restore in progress). */
        private static String federateState(int s) {
            switch (s) {
                case 1:
                    return "active";
                case 3:
                    return "saving";
                case 5:
                    return "restoring";
                default:
                    return "state " + s;
            }
        }
    }

    private static int[] toArray(List<Integer> l) {
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = l.get(i);
        }
        return a;
    }

    static int[] concat(int[] a, int[] b) {
        int[] c = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }
}
