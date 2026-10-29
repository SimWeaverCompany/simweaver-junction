package com.simweaver.junction;

import com.simweaver.common.SimwTimeStamp;
import com.simweaver.common.json.Json;
import com.simweaver.common.logging.Events;
import com.simweaver.junction.config.EndpointConfig;
import com.simweaver.junction.config.GatewayConfig;
import com.simweaver.junction.config.RouterConfig;
import com.simweaver.junction.endpoint.Endpoint;
import com.simweaver.junction.endpoint.EndpointException;
import com.simweaver.junction.endpoint.EndpointFactory;
import com.simweaver.junction.endpoint.EndpointState;
import com.simweaver.junction.endpoint.hla.HlaEndpoint;
import com.simweaver.junction.router.ConversionRules;
import com.simweaver.junction.router.IemRouter;
import com.simweaver.junction.router.ModelTools;
import com.simweaver.junction.router.PduRelayRouter;
import com.simweaver.junction.router.Router;
import com.simweaver.junction.router.talk.TalkRouter;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * A running gateway: the endpoints of one {@link GatewayConfig} and its router.
 *
 * <p>{@link #start()} opens all endpoints in parallel (HLA joins take a while), builds the
 * model tools, and starts the router. When any endpoint fails, the others are closed again
 * and the start fails as a whole.
 */
public final class Gateway implements Json.Writable {

    private static final Logger LOG = Logger.getLogger(Gateway.class.getName());

    /** Life of a gateway. */
    public enum State { STARTING, RUNNING, DEGRADED, STOPPING, STOPPED, FAILED }

    private final String id = UUID.randomUUID().toString();
    private final GatewayConfig cfg;
    private final Map<String, String> targets;
    private final Map<String, String> lsds;
    private final File runtimeDir;
    private final ConversionRules.Provider rules;
    private final String created = new SimwTimeStamp().toString();
    private final List<Endpoint> endpoints = new ArrayList<Endpoint>();
    private final Map<String, String> inspectDatasources = new ConcurrentHashMap<String, String>();
    private volatile Router router;
    private volatile State state = State.STARTING;
    private volatile String message;
    private final String instance;

    /** The instance name a start gets when none is given and nothing else of its kind runs. */
    public static final String FIRST_INSTANCE = "1";

    /**
     * @param cfg        the gateway configuration (valid)
     * @param targets    endpoint id to target (federation / exercise); missing = the default
     * @param runtimeDir the runtime folder
     * @param rules      RPR conversion rules
     */
    public Gateway(GatewayConfig cfg, Map<String, String> targets, File runtimeDir, ConversionRules.Provider rules) {
        this(cfg, targets, runtimeDir, rules, FIRST_INSTANCE);
    }

    /**
     * @param instance the instance name (several gateways of one configuration or group run as
     *                 instances with different names); null or blank = {@link #FIRST_INSTANCE}
     */
    public Gateway(GatewayConfig cfg, Map<String, String> targets, File runtimeDir, ConversionRules.Provider rules, String instance) {
        this(cfg, targets, null, runtimeDir, rules, instance);
    }

    /**
     * @param lsds HLA endpoint id to the LSD / RTI configuration name to connect with (any text,
     *             also one not among the configured choices; "" = the RTI's default); missing =
     *             the configured default
     */
    public Gateway(GatewayConfig cfg, Map<String, String> targets, Map<String, String> lsds, File runtimeDir,
                   ConversionRules.Provider rules, String instance) {
        this.lsds = Collections.unmodifiableMap(new LinkedHashMap<String, String>(lsds == null
                ? Collections.<String, String>emptyMap() : lsds));
        this.instance = instance == null || instance.trim().isEmpty() ? FIRST_INSTANCE : instance.trim();
        this.cfg = cfg;
        this.targets = Collections.unmodifiableMap(new LinkedHashMap<String, String>(targets == null
                ? Collections.<String, String>emptyMap() : targets));
        this.runtimeDir = runtimeDir;
        this.rules = rules == null ? ConversionRules.Provider.NONE : rules;
    }

    /** @return the running gateway's id (a UUID) */
    public String getId() {
        return id;
    }

    /** @return the instance name ("1" unless the start named another) */
    public String getInstance() {
        return instance;
    }

    /**
     * @return the configuration id, with the instance name for every instance but the first: what
     *         this gateway is called in the objects it registers itself (Talk channels,
     *         clients and radios: {@code Junction-<name>-...}), in thread names and in the log
     */
    public String runtimeName() {
        return FIRST_INSTANCE.equals(instance) ? cfg.getId() : cfg.getId() + "-" + instance.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    /** @return the configuration */
    public GatewayConfig getConfig() {
        return cfg;
    }

    /** @return the endpoints */
    public List<Endpoint> getEndpoints() {
        return Collections.unmodifiableList(endpoints);
    }

    /** @return the endpoint with this id, or null */
    public Endpoint endpoint(String endpointId) {
        for (Endpoint e : endpoints) {
            if (e.id().equals(endpointId)) {
                return e;
            }
        }
        return null;
    }

    /** @return the router (after start) */
    public Router getRouter() {
        return router;
    }

    /** @return endpoint id to the Rift datasource created for inspecting it */
    public Map<String, String> getInspectDatasources() {
        return inspectDatasources;
    }

    /** @return the LSD of each HLA endpoint, as it will be / was used ("" = the RTI's default) */
    public Map<String, String> effectiveLsds() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (EndpointConfig e : cfg.getEndpoints().values()) {
            if (e.getType() != null && e.getType().isHla()) {
                String l = lsds.get(e.getId());
                m.put(e.getId(), l == null ? e.getLsd() : l.trim());
            }
        }
        return m;
    }

    /** @return the target of each endpoint, as it will be / was used */
    public Map<String, String> effectiveTargets() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (EndpointConfig e : cfg.getEndpoints().values()) {
            String t = targets.get(e.getId());
            m.put(e.getId(), t == null || t.trim().isEmpty() ? e.getDefaultTarget() : t.trim());
        }
        return m;
    }

    /**
     * Opens the endpoints and starts the router.
     *
     * @throws GatewayException when an endpoint cannot be opened or declared
     */
    public void start() throws GatewayException {
        try {
            open();
        } catch (GatewayException e) {
            Events.warning(LOG, "gateway.startfailed", "Junction gateway " + label() + " did not start: " + e.getMessage(),
                    eventFields("code", e.getCode()));
            throw e.markLogged();
        }
        Events.notice(LOG, "gateway.started", "Junction gateway " + label() + " started: " + effectiveTargets(),
                eventFields("targets", targetsText()));
    }

    /** "config" or "config instance 2" for the log. */
    private String label() {
        return cfg.getId() + (FIRST_INSTANCE.equals(instance) ? "" : " instance " + instance);
    }

    /** gateway, instance, group (if any), then {@code more}, for an event. */
    private Object[] eventFields(Object... more) {
        List<Object> l = new ArrayList<Object>();
        l.add("gateway");
        l.add(cfg.getId());
        l.add("instance");
        l.add(instance);
        if (cfg.getGroup() != null && !cfg.getGroup().isEmpty()) {
            l.add("group");
            l.add(cfg.getGroup());
        }
        Collections.addAll(l, more);
        return l.toArray();
    }

    /** {@code endpoint:target,endpoint:target} */
    private String targetsText() {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> t : effectiveTargets().entrySet()) {
            if (b.length() > 0) {
                b.append(',');
            }
            b.append(t.getKey()).append(':').append(t.getValue() == null ? "" : t.getValue());
        }
        return b.toString();
    }

    private void open() throws GatewayException {
        state = State.STARTING;
        Map<String, String> eff = effectiveTargets();
        Map<String, String> effLsd = effectiveLsds();
        try {
            for (EndpointConfig ec : cfg.getEndpoints().values()) {
                endpoints.add(EndpointFactory.create(ec, eff.get(ec.getId()), effLsd.get(ec.getId()), runtimeName(), runtimeDir));
            }
        } catch (EndpointException e) {
            fail(e.getMessage());
            throw new GatewayException(e.getCode(), e.getMessage(), e);
        }
        Router r;
        switch (cfg.getRouter().getKind()) {
            case TALK:
                r = new TalkRouter(cfg.getRouter(), runtimeName());
                break;
            case PDU:
                r = new PduRelayRouter(cfg.getRouter(), runtimeName());
                break;
            default:
                r = new IemRouter(cfg.getRouter(), runtimeName(), rules, cfg.getRouter().getFilter(),
                        new com.simweaver.junction.router.convert.RprIffBridge());     // IFF modes RPR v2 <-> v3 (when both are there)
        }
        this.router = r;

        // open in parallel
        final Map<String, EndpointException> failures = new ConcurrentHashMap<String, EndpointException>();
        List<Thread> openers = new ArrayList<Thread>();
        for (final Endpoint e : endpoints) {
            Thread t = new Thread(() -> {
                try {
                    e.open(r.inbound());
                } catch (EndpointException ex) {
                    failures.put(e.id(), ex);
                } catch (RuntimeException ex) {
                    failures.put(e.id(), new EndpointException(EndpointException.CONNECT_FAILED, e.id() + ": " + ex, ex));
                }
            }, "junction-open-" + runtimeName() + "-" + e.id());
            t.setDaemon(true);
            openers.add(t);
            t.start();
        }
        for (Thread t : openers) {
            try {
                t.join();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        if (!failures.isEmpty()) {
            for (Map.Entry<String, EndpointException> f : failures.entrySet()) {
                // the whole cause, so a failure inside an RTI can be traced in the log
                LOG.log(java.util.logging.Level.WARNING, "Junction gateway " + cfg.getId() + " instance " + instance
                        + ", endpoint " + f.getKey() + " did not open: " + f.getValue().getMessage(), f.getValue());
            }
            EndpointException first = failures.values().iterator().next();
            closeEndpoints(false);
            StringBuilder sb = new StringBuilder();
            for (EndpointException ex : failures.values()) {
                if (sb.length() > 0) {
                    sb.append(" | ");
                }
                sb.append(ex.getMessage());
            }
            fail(sb.toString());
            throw new GatewayException(first.getCode(), sb.toString(), first);
        }
        try {
            Map<String, ModelTools> tools = new LinkedHashMap<String, ModelTools>();
            for (Endpoint e : endpoints) {
                if (e.model() != null) {                         // a J2J session has none (PDU relay)
                    tools.put(e.id(), new ModelTools(e.model(), e.config().getReferenceTypes()));
                }
            }
            r.start(endpoints, tools);
        } catch (EndpointException e) {
            r.stop();
            closeEndpoints(false);
            fail(e.getMessage());
            throw new GatewayException(e.getCode(), e.getMessage(), e);
        } catch (RuntimeException e) {
            r.stop();
            closeEndpoints(false);
            fail(e.toString());
            throw new GatewayException(EndpointException.CONFIG_INVALID, e.toString(), e);
        }
        state = State.RUNNING;
    }

    private void fail(String m) {
        state = State.FAILED;
        message = m;
    }

    /** Stops the router and closes the endpoints (deleting the bridged objects when configured). */
    public void stop() {
        if (state == State.STOPPED || state == State.STOPPING) {
            return;
        }
        state = State.STOPPING;
        Router r = router;
        if (r != null) {
            r.stop();
        }
        closeEndpoints(cfg.getRouter().isDeleteOnStop());
        state = State.STOPPED;
        Events.notice(LOG, "gateway.stopped", "Junction gateway " + label() + " stopped", eventFields());
    }

    private void closeEndpoints(boolean deleteObjects) {
        for (Endpoint e : endpoints) {
            try {
                e.close(deleteObjects);
            } catch (RuntimeException ex) {
                LOG.warning("Junction " + runtimeName() + "/" + e.id() + ": close failed: " + ex);
            }
        }
    }

    /** @return the state, DEGRADED while an endpoint is down */
    public State getState() {
        State s = state;
        if (s == State.RUNNING) {
            for (Endpoint e : endpoints) {
                if (!e.state().isUp()) {
                    return State.DEGRADED;
                }
            }
        }
        return s;
    }

    /** @return the message of the state (the failure), or null */
    public String getMessage() {
        return message;
    }

    /** Computes the traffic rates; called once a second. */
    public void sample(long now) {
        for (Endpoint e : endpoints) {
            e.stats().sample(now);
        }
    }

    @Override
    public Object toJson() {
        Json.JsonObject o = Json.object();
        o.put("id", id);
        o.put("configid", cfg.getId());
        o.put("name", cfg.getName());
        o.put("state", getState().name());
        o.put("message", message);
        o.put("created", created);
        o.put("router", cfg.getRouter().getKind().name().toLowerCase(Locale.ROOT));
        o.put("group", cfg.getGroup());
        o.put("instance", instance);
        o.put("vars", new java.util.LinkedHashMap<String, Object>(cfg.getVariableValues()));
        java.util.Map<String, Object> vd = new java.util.LinkedHashMap<String, Object>();
        for (String v : cfg.getVariableValues().keySet()) {
            if (cfg.getVariableDescriptions().containsKey(v)) {
                vd.put(v, cfg.getVariableDescriptions().get(v));
            }
        }
        o.put("vardescriptions", vd);
        Json.JsonArray eps = Json.array();
        long now = System.currentTimeMillis();
        for (Endpoint e : endpoints) {
            Json.JsonObject j = Json.object();
            j.put("id", e.id());
            j.put("name", e.config().getName());
            j.put("standard", e.type().setting());
            j.put("standardlabel", e.type().label());
            j.put("target", e.target());
            j.put("state", e.state().name());
            j.putIfNotNull("message", e.stateMessage());
            if (e instanceof HlaEndpoint) {
                j.put("library", ((HlaEndpoint) e).libraryDescription());
                j.put("federatename", ((HlaEndpoint) e).federateName());
                j.put("lsd", ((HlaEndpoint) e).lsd());
                j.put("lsdoverridden", lsds.containsKey(e.id()) && !((HlaEndpoint) e).lsd().equals(e.config().getLsd()));
                j.put("fom", ((HlaEndpoint) e).fomSource());
                j.put("dynamicfom", e.config().isDynamicFom());
                j.putIfNotNull("fomnotice", ((HlaEndpoint) e).fomNotice());
            }
            if (e instanceof com.simweaver.junction.endpoint.dis.DisEndpoint) {
                // 0.9.18: DIS diagnostics, with the C-DIS settings and counters of a C-DIS endpoint
                j.put("dis", ((com.simweaver.junction.endpoint.dis.DisEndpoint) e).diagnostics());
            }
            if (e instanceof com.simweaver.junction.endpoint.j2j.J2jEndpoint) {
                // 0.10.0: the J2J session, its other members and counters
                j.put("j2j", ((com.simweaver.junction.endpoint.j2j.J2jEndpoint) e).diagnostics());
            }
            if (e instanceof com.simweaver.junction.endpoint.j2j.J2jObjectEndpoint) {
                j.put("j2j", ((com.simweaver.junction.endpoint.j2j.J2jObjectEndpoint) e).diagnostics());
            }
            long lastIn = e.stats().lastIn();
            long lastOut = e.stats().lastOut();
            j.put("flowingin", lastIn > 0 && now - lastIn < 5000);
            j.put("flowingout", lastOut > 0 && now - lastOut < 5000);
            j.put("stats", e.stats());
            j.putIfNotNull("inspectdatasource", inspectDatasources.get(e.id()));
            eps.add(j);
        }
        o.put("endpoints", eps);
        Router r = router;
        if (r != null) {
            o.put("objects", r.objects());
            o.put("routerstats", r.stats());
            o.put("queue", r.queueDepth());
        }
        return o;
    }

    /** True when an endpoint state means traffic cannot flow. */
    static boolean isDown(EndpointState s) {
        return !s.isUp();
    }
}
