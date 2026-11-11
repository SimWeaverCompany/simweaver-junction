package com.simweaver.junction;

import com.simweaver.common.json.Json;
import com.simweaver.common.logging.Events;
import com.simweaver.common.rest.RequestContext;
import com.simweaver.common.rest.XrSseHub;
import com.simweaver.junction.config.ConfigLoader;
import com.simweaver.junction.config.EndpointConfig;
import com.simweaver.junction.config.EndpointType;
import com.simweaver.junction.config.GatewayConfig;
import com.simweaver.junction.config.GroupVariables;
import com.simweaver.junction.endpoint.Endpoint;
import com.simweaver.junction.endpoint.EndpointFactory;
import com.simweaver.junction.endpoint.hla.HlaEndpoint;
import com.simweaver.junction.router.ConversionRules;
import com.simweaver.junction.router.convert.RprConversions;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SimWeaver Junction: the gateway configurations in {@code etc/junctions}, the running
 * gateways, and what the REST API ({@link com.simweaver.junction.api.JunktionApi}) and the
 * page need. One per server.
 *
 * <p>Live updates go to the server's {@link XrSseHub} once a second, while the page listens:
 * topic {@code junktion}, event {@code gateways} (the list), and topic {@code junktion:<gid>},
 * event {@code gateway} (one gateway in full).
 */
public final class JunctionManager {

    private static final Logger LOG = Logger.getLogger(JunctionManager.class.getName());

    /** SSE topic of the gateway list. */
    public static final String TOPIC = "junction";

    private final File runtimeDir;
    private final ConfigLoader loader;
    private final XrSseHub hub;
    private final Inspector inspector;
    private final RprConversions conversions;
    private final Map<String, Gateway> gateways = new ConcurrentHashMap<String, Gateway>();
    private final Set<String> starting = ConcurrentHashMap.newKeySet();
    /** Instances being started: "cfg|<config id>|<instance>" and "group|<group>|<instance>". Under itself. */
    private final Set<String> reservedInstances = new java.util.HashSet<String>();
    private final ScheduledExecutorService ticker;
    private volatile ConfigLoader.Result configs;

    /**
     * @param runtimeDir   the runtime folder (FOMs, RTI jars, etc/junctions are relative to it)
     * @param junctionsDir the configuration folder (normally runtimeDir/etc/junctions)
     * @param hub          the server's event hub, or null
     * @param inspector    the host's inspection service ({@link Inspector#NONE} without Rift)
     */
    public JunctionManager(File runtimeDir, File junctionsDir, XrSseHub hub, Inspector inspector) {
        this.runtimeDir = runtimeDir;
        this.loader = new ConfigLoader(junctionsDir, runtimeDir);
        this.hub = hub;
        this.inspector = inspector == null ? Inspector.NONE : inspector;
        this.conversions = new RprConversions(new File(junctionsDir, "mappings"));
        this.configs = loader.load();
        this.ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "junction-ticker");
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);
        this.inspector.advertise(this::advertised);
    }

    /** @return the configuration folder */
    public File getJunctionsDir() {
        return loader.getDirectory();
    }

    /** @return the runtime folder */
    public File getRuntimeDir() {
        return runtimeDir;
    }

    /** The datasource that inspects an endpoint. */
    public static final class InspectResult implements Json.Writable {
        public final String datasource;
        public final String page;
        public final boolean created;

        InspectResult(String datasource, String page, boolean created) {
            this.datasource = datasource;
            this.page = page;
            this.created = created;
        }

        @Override
        public Object toJson() {
            return Json.object().put("datasource", datasource).put("page", page).put("created", created);
        }
    }

    /** Reads etc/junctions again. */
    public ConfigLoader.Result reload() {
        configs = loader.load();
        return configs;
    }

    /** @return the configuration as last read */
    public ConfigLoader.Result configs() {
        return configs;
    }

    /** @return the running (and failed-but-listed) gateways */
    public Collection<Gateway> gateways() {
        return gateways.values();
    }

    /** @return a running gateway, or null */
    public Gateway gateway(String id) {
        return id == null ? null : gateways.get(id);
    }

    /** @return the RPR conversion rules */
    public ConversionRules.Provider conversions() {
        return conversions;
    }

    // ------------------------------------------------------------------ start / stop

    /**
     * Starts a gateway. Blocks until all endpoints are open (or one failed).
     *
     * @param configId the gateway configuration id
     * @param targets  endpoint id to target; missing ones use the configured default
     * @return the running gateway
     * @throws GatewayException CONFIG_NOT_FOUND, CONFIG_INVALID, TARGET_CONFLICT, RTI_NOT_AVAILABLE, CONNECT_FAILED ...
     */
    public Gateway start(String configId, Map<String, String> targets) throws GatewayException {
        return start(configId, targets, Collections.<String, String>emptyMap());
    }

    /**
     * Starts a gateway with group variable values.
     *
     * @param vars GROUPVARn to value (on top of the configuration's defaults)
     * @throws GatewayException also VARIABLES_REQUIRED when a variable the settings use has no value
     */
    public Gateway start(String configId, Map<String, String> targets, Map<String, String> vars) throws GatewayException {
        return start(configId, targets, vars, null);
    }

    /**
     * Starts a gateway as a named instance. Several instances of one configuration can run at
     * once (usually with other targets or variable values); each needs a name of its own.
     *
     * @param instance the instance name; null or blank = the lowest free number ("1", "2", ...)
     * @throws GatewayException also INSTANCE_INVALID (not a usable name) and INSTANCE_RUNNING
     *                          (an instance of that name of this configuration runs)
     */
    public Gateway start(String configId, Map<String, String> targets, Map<String, String> vars, String instance) throws GatewayException {
        return start(configId, targets, vars, instance, null);
    }

    /**
     * Starts a gateway as a named instance, with LSDs of its own.
     *
     * @param lsds HLA endpoint id to the LSD / RTI configuration name to connect with: one of
     *             the configured choices ({@code endpoint.hla.lsd=a;b;c}) or any other; "" = the
     *             RTI's default; missing = the configured default (the first choice). Group
     *             variables in it are replaced.
     * @throws GatewayException also LSD_INVALID (not an HLA endpoint of the gateway, or not a usable LSD)
     */
    public Gateway start(String configId, Map<String, String> targets, Map<String, String> vars, String instance,
                         Map<String, String> lsds) throws GatewayException {
        try {
            return startChecked(configId, targets, vars, instance, lsds);
        } catch (GatewayException e) {
            if (!e.isLogged()) {                     // refused before the gateway tried to open
                Events.warning(LOG, "gateway.startfailed", "Junction gateway " + configId
                        + (instance == null || instance.trim().isEmpty() ? "" : " instance " + instance.trim())
                        + " did not start: " + e.getMessage(), "gateway", configId,
                        "instance", instance == null || instance.trim().isEmpty() ? null : instance.trim(), "code", e.getCode());
                e.markLogged();
            }
            throw e;
        }
    }

    private Gateway startChecked(String configId, Map<String, String> targets, Map<String, String> vars, String instance,
                                 Map<String, String> lsds) throws GatewayException {
        String wanted = checkInstanceName(instance);
        reload();
        if (configs.gateway(configId) == null) {
            throw new GatewayException("CONFIG_NOT_FOUND", "No gateway configuration '" + configId + "'", null);
        }
        for (String v : vars.keySet()) {
            if (!GroupVariables.isName(v)) {
                throw new GatewayException("CONFIG_INVALID", "'" + v + "' is not a group variable (GROUPVAR1, GROUPVAR2, ...)", null);
            }
        }
        GatewayConfig cfg = loader.gateway(configId, vars);
        if (cfg == null) {
            throw new GatewayException("CONFIG_NOT_FOUND", "No gateway configuration '" + configId + "'", null);
        }
        if (!cfg.getUnresolved().isEmpty()) {
            throw new GatewayException("VARIABLES_REQUIRED", "Gateway " + configId + " needs values for " + String.join(", ", cfg.getUnresolved()), null);
        }
        if (!cfg.isValid()) {
            throw new GatewayException("CONFIG_INVALID", String.join("; ", cfg.getErrors()), null);
        }
        Map<String, String> resolved = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> t : targets.entrySet()) {
            resolved.put(t.getKey(), GroupVariables.apply(t.getValue(), cfg.getVariableValues()));
        }
        targets = resolved;
        for (String k : targets.keySet()) {
            if (!cfg.getEndpoints().containsKey(k)) {
                throw new GatewayException("CONFIG_INVALID", "The gateway has no endpoint '" + k + "'", null);
            }
        }
        Map<String, String> lsdResolved = new LinkedHashMap<String, String>();
        Map<String, String> lsdVars = new LinkedHashMap<String, String>(vars);      // also variables only the LSD uses
        lsdVars.putAll(cfg.getVariableValues());
        if (lsds != null) {
            for (Map.Entry<String, String> l : lsds.entrySet()) {
                EndpointConfig ec = cfg.getEndpoints().get(l.getKey());
                if (ec == null || ec.getType() == null || !ec.getType().isHla()) {
                    throw new GatewayException("LSD_INVALID", "The gateway has no HLA endpoint '" + l.getKey() + "' to give an LSD", null);
                }
                String v = l.getValue() == null ? "" : GroupVariables.apply(l.getValue(), lsdVars).trim();
                String why = com.simweaver.common.hla.session.LsdOptions.problem(v);
                if (why != null) {
                    throw new GatewayException("LSD_INVALID", "endpoint " + l.getKey() + ": " + why, null);
                }
                lsdResolved.put(l.getKey(), v);
            }
        }
        String inst;
        String key;
        synchronized (reservedInstances) {
            inst = wanted != null ? wanted : nextInstance(configKeys(Collections.singletonList(configId)), g -> g.getConfig().getId().equals(configId));
            key = "cfg|" + configId + "|" + inst;
            final String in = inst;
            if (reservedInstances.contains(key) || gateways.values().stream().anyMatch(g -> g.getConfig().getId().equals(configId) && g.getInstance().equals(in))) {
                throw new GatewayException("INSTANCE_RUNNING", "Instance " + inst + " of gateway " + configId + " is already running", null);
            }
            reservedInstances.add(key);
        }
        try {
            Gateway g = new Gateway(cfg, targets, lsdResolved, runtimeDir, conversions, inst);
            String conflict = conflictWithin(g);
            if (conflict != null) {
                throw new GatewayException("TARGET_CONFLICT", conflict, null);
            }
            starting.add(g.getId());
            try {
                g.start();
            } finally {
                starting.remove(g.getId());
            }
            gateways.put(g.getId(), g);
            publishList();
            return g;
        } finally {
            synchronized (reservedInstances) {
                reservedInstances.remove(key);
            }
        }
    }

    /** Longest instance name. */
    public static final int MAX_INSTANCE_LENGTH = 40;

    /**
     * @return the trimmed name, or null for "pick one"
     * @throws GatewayException INSTANCE_INVALID: longer than 40, or other than letters, digits, space, '_', '-', '.'
     */
    static String checkInstanceName(String instance) throws GatewayException {
        if (instance == null || instance.trim().isEmpty()) {
            return null;
        }
        String t = instance.trim();
        if (t.length() > MAX_INSTANCE_LENGTH || !t.matches("[\\p{L}\\p{N} _.-]+")) {
            throw new GatewayException("INSTANCE_INVALID", "Instance name '" + instance + "': at most " + MAX_INSTANCE_LENGTH
                    + " letters, digits, spaces, '_', '-' or '.'", null);
        }
        return t;
    }

    /** The reservation keys of these configurations, without the instance. */
    private static List<String> configKeys(List<String> configIds) {
        List<String> l = new ArrayList<String>();
        for (String c : configIds) {
            l.add("cfg|" + c + "|");
        }
        return l;
    }

    /** The lowest number that no gateway matching {@code mine} runs as, and no reservation with these prefixes holds. Under reservedInstances. */
    private String nextInstance(List<String> prefixes, java.util.function.Predicate<Gateway> mine) {
        for (int n = 1; ; n++) {
            String inst = String.valueOf(n);
            boolean taken = false;
            for (String p : prefixes) {
                taken |= reservedInstances.contains(p + inst);
            }
            for (Gateway g : gateways.values()) {
                taken |= mine.test(g) && g.getInstance().equals(inst);
            }
            if (!taken) {
                return inst;
            }
        }
    }

    /** @return the name the next start of this configuration gets when none is given */
    public String nextInstanceOfConfig(String configId) {
        synchronized (reservedInstances) {
            return nextInstance(configKeys(Collections.singletonList(configId)), g -> g.getConfig().getId().equals(configId));
        }
    }

    /** @return the name the next start of this group gets when none is given */
    public String nextInstanceOfGroup(String group) {
        synchronized (reservedInstances) {
            return nextInstance(groupKeys(group), g -> group.equals(g.getConfig().getGroup()));
        }
    }

    private List<String> groupKeys(String group) {
        List<String> ids = new ArrayList<String>();
        for (GatewayConfig c : configs.getGateways()) {
            if (group.equals(c.getGroup())) {
                ids.add(c.getId());
            }
        }
        List<String> l = configKeys(ids);
        l.add("group|" + group + "|");
        return l;
    }

    /** Two endpoints of one gateway on the same federation / DIS network would loop. */
    private static String conflictWithin(Gateway g) {
        Map<String, String> seen = new LinkedHashMap<String, String>();
        Map<String, String> eff = g.effectiveTargets();
        Map<String, String> lsd = g.effectiveLsds();
        for (EndpointConfig e : g.getConfig().getEndpoints().values()) {
            String k = targetKey(e, lsd.get(e.getId()), eff.get(e.getId()));
            String other = seen.put(k, e.getId());
            if (other != null) {
                return "Endpoints " + other + " and " + e.getId() + " are on the same "
                        + (e.getType().isDis() ? "DIS network and exercise" : e.getType().isJ2j() ? "J2J session" : e.getType().isKafka() ? "Kafka topic" : "federation")
                        + " (" + eff.get(e.getId())
                        + "): traffic would loop";
            }
        }
        return null;
    }

    /** Identifies where an endpoint is: equal keys = the same federation / DIS network. */
    static String targetKey(EndpointConfig e, String target) {
        return targetKey(e, null, target);
    }

    /** @param lsd the LSD the endpoint connects with; null = its configured default */
    static String targetKey(EndpointConfig e, String lsd, String target) {
        EndpointType t = e.getType();
        if (t != null && t.isDis()) {
            return "dis|" + e.getDisInterface().trim().toLowerCase(Locale.ROOT) + "|" + e.getDisPort() + "|" + target;
        }
        if (t != null && t.isJ2j()) {
            return "j2j|" + target;
        }
        if (t != null && t.isKafka()) {
            return "kafka|" + e.getKafka().getBootstrap().trim().toLowerCase(Locale.ROOT) + "|" + target;
        }
        return "hla|" + (e.isHla4() ? "4" : "3") + "|" + (lsd == null ? e.getLsd() : lsd).trim() + "|" + e.getRtiAddress().trim() + "|"
                + e.getRtiJar().trim() + "|" + target;
    }

    /** @return a warning when another running gateway is on the same targets, or null */
    public String sharedTargetWarning(Gateway g) {
        Map<String, String> eff = g.effectiveTargets();
        Map<String, String> lsd = g.effectiveLsds();
        for (Gateway o : gateways.values()) {
            if (o == g || o.getState() == Gateway.State.STOPPED || o.getState() == Gateway.State.FAILED) {
                continue;
            }
            Map<String, String> oe = o.effectiveTargets();
            Map<String, String> ol = o.effectiveLsds();
            int shared = 0;
            for (EndpointConfig e : g.getConfig().getEndpoints().values()) {
                for (EndpointConfig f : o.getConfig().getEndpoints().values()) {
                    if (targetKey(e, lsd.get(e.getId()), eff.get(e.getId())).equals(targetKey(f, ol.get(f.getId()), oe.get(f.getId())))) {
                        shared++;
                    }
                }
            }
            if (shared >= 2) {
                return "Gateway '" + o.getConfig().getName() + "' bridges " + shared
                        + " of the same targets: traffic may loop between the two gateways";
            }
        }
        return null;
    }

    /**
     * Stops and removes a gateway, and the datasources created for inspecting it.
     *
     * @return false when there is no such gateway
     */
    public boolean stop(String id) {
        Gateway g = gateways.remove(id);
        if (g == null) {
            return false;
        }
        for (String ds : g.getInspectDatasources().values()) {
            inspector.remove(ds);
        }
        g.stop();
        publishList();
        if (hub != null) {
            hub.publish(TOPIC + ":" + id, "gateway.removed", Json.object().put("id", id).toString());
        }
        return true;
    }

    /** The outcome of starting one gateway of a group. */
    public static final class GroupStart implements Json.Writable {
        public final String configId;
        public final String instance;
        public final Gateway gateway;
        public final GatewayException error;

        GroupStart(String configId, String instance, Gateway gateway, GatewayException error) {
            this.configId = configId;
            this.instance = instance;
            this.gateway = gateway;
            this.error = error;
        }

        @Override
        public Object toJson() {
            Json.JsonObject o = Json.object().put("configid", configId).put("instance", instance).put("success", error == null);
            if (gateway != null) {
                o.put("gateway", gateway);
            }
            if (error != null) {
                o.put("error", Json.object().put("code", error.getCode()).put("message", error.getMessage()));
            }
            return o;
        }
    }

    /** @return the configurations of a group, in file order */
    public List<GatewayConfig> groupConfigs(String group) {
        List<GatewayConfig> l = new ArrayList<GatewayConfig>();
        for (GatewayConfig c : reload().getGateways()) {
            if (!group.isEmpty() && group.equals(c.getGroup())) {
                l.add(c);
            }
        }
        return l;
    }

    /**
     * Starts every gateway of a group with the same variable values; one failing does not stop
     * the others.
     *
     * @return one result per gateway of the group (empty: no such group)
     */
    public List<GroupStart> startGroup(String group, Map<String, String> vars) {
        try {
            return startGroup(group, vars, null);
        } catch (GatewayException e) {
            throw new IllegalStateException(e);           // a picked instance name is always free
        }
    }

    /**
     * Starts every gateway of a group as one named instance of the group, with the same variable
     * values. Several instances of a group can run at once, each with its own name.
     *
     * @param instance the instance name; null or blank = the lowest free number ("1", "2", ...)
     * @return one result per gateway of the group (empty: no such group)
     * @throws GatewayException INSTANCE_INVALID, or INSTANCE_RUNNING when a gateway of the group
     *                          runs as that instance (nothing is started then)
     */
    public List<GroupStart> startGroup(String group, Map<String, String> vars, String instance) throws GatewayException {
        return startGroup(group, vars, instance, null);
    }

    /**
     * Starts every gateway of a group as one named instance, with LSDs of their own.
     *
     * @param lsds gateway configuration id to (HLA endpoint id to LSD), as for {@link #start};
     *             gateways or endpoints left out use their configured default
     * @throws GatewayException also LSD_INVALID when a configuration id is not a gateway of the
     *                          group (nothing is started then)
     */
    public List<GroupStart> startGroup(String group, Map<String, String> vars, String instance,
                                       Map<String, Map<String, String>> lsds) throws GatewayException {
        String wanted = checkInstanceName(instance);
        List<GatewayConfig> members = groupConfigs(group);
        List<GroupStart> out = new ArrayList<GroupStart>();
        if (members.isEmpty()) {
            return out;
        }
        if (lsds != null) {
            for (String c : lsds.keySet()) {
                if (members.stream().noneMatch(m -> m.getId().equals(c))) {
                    throw new GatewayException("LSD_INVALID", "Gateway '" + c + "' is not in group " + group, null);
                }
            }
        }
        String inst;
        String key;
        synchronized (reservedInstances) {
            List<String> prefixes = groupKeys(group);
            inst = wanted != null ? wanted : nextInstance(prefixes, g -> group.equals(g.getConfig().getGroup()));
            final String in = inst;
            boolean taken = gateways.values().stream().anyMatch(g -> group.equals(g.getConfig().getGroup()) && g.getInstance().equals(in));
            for (String p : prefixes) {
                taken |= reservedInstances.contains(p + inst);
            }
            if (taken) {
                throw new GatewayException("INSTANCE_RUNNING", "Instance " + inst + " of group " + group + " is already running", null);
            }
            key = "group|" + group + "|" + inst;
            reservedInstances.add(key);
        }
        try {
            for (GatewayConfig c : members) {
                try {
                    out.add(new GroupStart(c.getId(), inst, start(c.getId(), new LinkedHashMap<String, String>(), vars, inst,
                            lsds == null ? null : lsds.get(c.getId())), null));
                } catch (GatewayException e) {
                    out.add(new GroupStart(c.getId(), inst, null, e));
                }
            }
        } finally {
            synchronized (reservedInstances) {
                reservedInstances.remove(key);
            }
        }
        int ok = 0;
        for (GroupStart gs : out) {
            ok += gs.error == null ? 1 : 0;
        }
        Events.log(LOG, ok > 0 ? Events.NOTICE : Level.WARNING, ok > 0 ? "group.started" : "group.startfailed",
                "Junction group " + group + " instance " + inst + ": " + ok + " of " + out.size() + " gateways started",
                "group", group, "instance", inst, "started", ok, "failed", out.size() - ok);
        return out;
    }

    /** Stops the running gateways of a group, every instance. @return how many were stopped */
    public int stopGroup(String group) {
        return stopGroup(group, null);
    }

    /**
     * Stops the running gateways of one instance of a group.
     *
     * @param instance the instance name; null = every instance
     * @return how many were stopped
     */
    public int stopGroup(String group, String instance) {
        int n = 0;
        for (Gateway g : new ArrayList<Gateway>(gateways.values())) {
            if (!group.isEmpty() && group.equals(g.getConfig().getGroup())
                    && (instance == null || instance.trim().equals(g.getInstance())) && stop(g.getId())) {
                n++;
            }
        }
        if (n > 0) {
            Events.notice(LOG, "group.stopped", "Junction group " + group + (instance == null ? "" : " instance " + instance.trim())
                    + ": " + n + " gateways stopped", "group", group, "instance", instance == null ? null : instance.trim(), "stopped", n);
        }
        return n;
    }

    /** @return true when a gateway of the group runs as this instance */
    public boolean groupInstanceRuns(String group, String instance) {
        return gateways.values().stream().anyMatch(g -> group.equals(g.getConfig().getGroup()) && g.getInstance().equals(instance.trim()));
    }

    /**
     * Starts the gateways marked {@code junction.autostart=true}, in the background. Their log
     * events name who called this ({@code RequestContext}; the server calls it as "autostart").
     */
    public void autostart() {
        final String by = RequestContext.actor();
        for (final GatewayConfig c : configs.getGateways()) {
            if (c.isAutostart() && c.isValid()) {
                Thread t = new Thread(() -> RequestContext.runAs(by, () -> {
                    try {
                        start(c.getId(), new LinkedHashMap<String, String>());
                    } catch (GatewayException e) {
                        // logged as gateway.startfailed (by=autostart)
                    }
                }), "junction-autostart-" + c.getId());
                t.setDaemon(true);
                t.start();
            }
        }
    }

    /** Stops every gateway (server shutdown). */
    public void shutdown() {
        ticker.shutdownNow();
        for (String id : new ArrayList<String>(gateways.keySet())) {
            try {
                stop(id);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Junction: stopping " + id + " failed", e);
            }
        }
    }

    // ------------------------------------------------------------------ inspect

    /**
     * Creates (or finds) the host datasource that inspects one endpoint of a running gateway.
     *
     * @return the datasource (created now, or the one created before)
     * @throws Inspector.InspectException when the host cannot create it
     */
    public InspectResult inspect(String gatewayId, String endpointId) throws Inspector.InspectException {
        Gateway g = gateways.get(gatewayId);
        if (g == null) {
            throw new Inspector.InspectException("GATEWAY_UNKNOWN", 404, "No running gateway " + gatewayId);
        }
        Endpoint e = g.endpoint(endpointId);
        if (e == null) {
            throw new Inspector.InspectException("ENDPOINT_UNKNOWN", 404, "The gateway has no endpoint " + endpointId);
        }
        String existing = g.getInspectDatasources().get(endpointId);
        if (existing != null && inspector.exists(existing)) {
            return new InspectResult(existing, inspector.pageOf(existing), false);
        }
        String ds = inspector.inspect(new Inspector.EndpointRef(g.getConfig().getId() + "/" + endpointId, e.config(),
                e.target(), g.getConfig().getName() + (Gateway.FIRST_INSTANCE.equals(g.getInstance()) ? "" : " [" + g.getInstance() + "]")
                + " / " + e.config().getName(), e instanceof HlaEndpoint ? ((HlaEndpoint) e).lsd() : null));
        g.getInspectDatasources().put(endpointId, ds);
        return new InspectResult(ds, inspector.pageOf(ds), true);
    }

    /** The endpoints of every valid gateway configuration, for the host's configuration list. */
    private List<Inspector.EndpointRef> advertised() {
        List<Inspector.EndpointRef> l = new ArrayList<Inspector.EndpointRef>();
        ConfigLoader.Result r = configs;
        for (GatewayConfig g : r.getGateways()) {
            for (EndpointConfig e : g.getEndpoints().values()) {
                if (e.getType() != null && e.getErrors().isEmpty()) {
                    l.add(new Inspector.EndpointRef(g.getId() + "/" + e.getId(), e, e.getDefaultTarget(),
                            g.getName() + " / " + e.getName()));
                }
            }
        }
        return l;
    }

    // ------------------------------------------------------------------ JSON

    /** GET /junktion/configs */
    public Json.JsonObject jsonConfigs() {
        ConfigLoader.Result r = reload();
        Json.JsonArray gws = Json.array();
        for (GatewayConfig g : r.getGateways()) {
            Json.JsonObject o = (Json.JsonObject) g.toJson();
            Json.JsonObject avail = Json.object();
            for (EndpointConfig e : g.getEndpoints().values()) {
                String why = EndpointFactory.unavailableReason(e, runtimeDir);
                avail.put(e.getId(), why == null ? Json.object().put("available", true)
                        : Json.object().put("available", false).put("reason", why));
            }
            o.put("availability", avail);
            int running = 0;
            List<String> instances = new ArrayList<String>();
            for (Gateway gw : gateways.values()) {
                if (gw.getConfig().getId().equals(g.getId())) {
                    running++;
                    instances.add(gw.getInstance());
                }
            }
            instances.sort(INSTANCE_ORDER);
            o.put("running", running);
            o.put("instances", instances);
            o.put("nextinstance", nextInstanceOfConfig(g.getId()));
            gws.add(o);
        }
        Json.JsonArray eps = Json.array();
        for (EndpointConfig e : r.getEndpointKits()) {
            Json.JsonObject o = (Json.JsonObject) e.toJson();
            String why = EndpointFactory.unavailableReason(e, runtimeDir);
            o.put("available", why == null);
            o.putIfNotNull("reason", why);
            eps.add(o);
        }
        return Json.object().put("gateways", gws).put("groups", jsonGroups(r)).put("endpointkits", eps)
                .put("routerkits", r.getRouterKits()).put("problems", r.getProblems())
                .put("directory", loader.getDirectory().getPath());
    }

    /** GET /junktion/groups */
    public Json.JsonArray jsonGroups() {
        return jsonGroups(reload());
    }

    /** The groups: their gateways, the variables they use (with defaults) and how many run. */
    private Json.JsonArray jsonGroups(ConfigLoader.Result r) {
        Map<String, List<GatewayConfig>> groups = new java.util.TreeMap<String, List<GatewayConfig>>();
        for (GatewayConfig g : r.getGateways()) {
            if (!g.getGroup().isEmpty()) {
                groups.computeIfAbsent(g.getGroup(), k -> new ArrayList<GatewayConfig>()).add(g);
            }
        }
        Json.JsonArray a = Json.array();
        for (Map.Entry<String, List<GatewayConfig>> e : groups.entrySet()) {
            Map<String, String> vars = new LinkedHashMap<String, String>();
            Map<String, String> descs = new LinkedHashMap<String, String>();
            Json.JsonArray ids = Json.array();
            for (GatewayConfig g : e.getValue()) {
                ids.add(g.getId());
                for (Map.Entry<String, String> d : g.getVariableDescriptions().entrySet()) {
                    descs.putIfAbsent(d.getKey(), d.getValue());      // the first gateway of the group that describes it
                }
                for (Map.Entry<String, String> v : g.getVariableDefaults().entrySet()) {
                    if (!vars.containsKey(v.getKey()) || vars.get(v.getKey()) == null) {
                        vars.put(v.getKey(), v.getValue());
                    }
                }
            }
            Json.JsonArray va = Json.array();
            for (String n : GroupVariables.sorted(vars.keySet())) {
                va.add(Json.object().put("name", n).putIfNotNull("default", vars.get(n)).putIfNotNull("description", descs.get(n)));
            }
            int running = 0;
            Map<String, List<Gateway>> byInstance = new java.util.TreeMap<String, List<Gateway>>(INSTANCE_ORDER);
            for (Gateway g : gateways.values()) {
                if (e.getKey().equals(g.getConfig().getGroup())) {
                    running++;
                    byInstance.computeIfAbsent(g.getInstance(), k -> new ArrayList<Gateway>()).add(g);
                }
            }
            Json.JsonArray inst = Json.array();
            for (Map.Entry<String, List<Gateway>> i : byInstance.entrySet()) {
                List<Gateway> l = i.getValue();
                List<String> order = new ArrayList<String>();
                for (GatewayConfig c : e.getValue()) {
                    order.add(c.getId());
                }
                l.sort(java.util.Comparator.comparingInt(g -> order.indexOf(g.getConfig().getId())));
                Json.JsonArray gl = Json.array();
                Map<String, String> ivars = new LinkedHashMap<String, String>();
                for (Gateway g : l) {
                    gl.add(Json.object().put("id", g.getId()).put("configid", g.getConfig().getId()).put("name", g.getConfig().getName())
                            .put("state", g.getState().name()));
                    for (Map.Entry<String, String> v : g.getConfig().getVariableValues().entrySet()) {
                        ivars.putIfAbsent(v.getKey(), v.getValue());
                    }
                }
                inst.add(Json.object().put("instance", i.getKey()).put("running", l.size()).put("complete", l.size() >= ids.size())
                        .put("vars", new LinkedHashMap<String, Object>(ivars)).put("gateways", gl));
            }
            a.add(Json.object().put("group", e.getKey()).put("gateways", ids).put("variables", va).put("running", running)
                    .put("instances", inst).put("nextinstance", nextInstanceOfGroup(e.getKey())));
        }
        return a;
    }

    /** Running gateways: grouped ones by group, then instance, then configuration; the groupless after them by configuration and instance. */
    private static final java.util.Comparator<Gateway> RUNNING_ORDER = (x, y) -> {
        String gx = x.getConfig().getGroup(), gy = y.getConfig().getGroup();
        if (gx.isEmpty() != gy.isEmpty()) {
            return gx.isEmpty() ? 1 : -1;
        }
        int c = gx.compareToIgnoreCase(gy);
        if (c == 0 && !gx.isEmpty()) {
            c = JunctionManager.INSTANCE_ORDER.compare(x.getInstance(), y.getInstance());
        }
        if (c == 0) {
            c = x.getConfig().getId().compareToIgnoreCase(y.getConfig().getId());
        }
        if (c == 0) {
            c = JunctionManager.INSTANCE_ORDER.compare(x.getInstance(), y.getInstance());
        }
        return c;
    };

    /** Instance names: numbers in numeric order first, then the others alphabetically. */
    public static final java.util.Comparator<String> INSTANCE_ORDER = (a, b) -> {
        boolean na = a.matches("\\d{1,9}"), nb = b.matches("\\d{1,9}");
        if (na && nb) {
            return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
        }
        if (na != nb) {
            return na ? -1 : 1;
        }
        int c = a.compareToIgnoreCase(b);
        return c != 0 ? c : a.compareTo(b);
    };

    /** GET /junktion/gateways */
    public Json.JsonObject jsonGateways() {
        Json.JsonArray a = Json.array();
        List<Gateway> l = new ArrayList<Gateway>(gateways.values());
        l.sort(RUNNING_ORDER);
        for (Gateway g : l) {
            a.add(g);
        }
        return Json.object().put("gateways", a);
    }

    private void tick() {
        try {
            long now = System.currentTimeMillis();
            for (Gateway g : gateways.values()) {
                g.sample(now);
            }
            if (hub == null) {
                return;
            }
            if (hub.hasSubscribers(TOPIC)) {
                publishList();
            }
            for (Gateway g : gateways.values()) {
                String topic = TOPIC + ":" + g.getId();
                if (hub.hasSubscribers(topic)) {
                    hub.publish(topic, "gateway", Json.object().put("gateway", g).toString());
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "Junction ticker", e);
        }
    }

    private void publishList() {
        if (hub != null) {
            hub.publish(TOPIC, "gateways", jsonGateways().toString());
        }
    }
}
