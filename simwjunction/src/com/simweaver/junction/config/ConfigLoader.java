package com.simweaver.junction.config;

import com.simweaver.common.config.SimwProperties;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the Junction configuration folder (default {@code etc/junctions}):
 *
 * <pre>
 * etc/junctions/
 *   *.junction.settings            gateways
 *   endpoints/*.endpoint.settings  endpoint kits
 *   routers/*.router.settings      router kits
 *   mappings/                      conversion tables
 * </pre>
 *
 * A gateway file:
 * <pre>
 * junction.name=Blue - Red
 * junction.endpoints=blue;red
 * junction.endpoint.blue=endpoints/blue.endpoint.settings     # a kit ...
 * junction.endpoint.blue.hla.federation=Blue-2                 # ... with overrides
 * junction.endpoint.red.standard=ieee1516-2025                 # or inline
 * junction.endpoint.red.hla.foms=RPR-Base_v2.0.xml;RPR-Foundation_v2.0.xml
 * junction.router=routers/all.router.settings                  # a kit, and/or inline router.* keys:
 * junction.router.objects.block=EmbeddedSystem.*
 * </pre>
 * Kit references are relative to the junctions folder. Inline keys {@code junction.endpoint.<id>.<key>}
 * become {@code endpoint.<key>}, {@code junction.router.<key>} become {@code router.<key>},
 * {@code junction.talk.<key>} become {@code talk.<key>}.
 *
 * <p>Groups: {@code junction.group=<name>} (or {@code router.group} in the router kit) puts
 * gateways in a group that starts and stops together. {@code GROUPVAR1}, {@code GROUPVAR2}, ...
 * in any value (gateway file, endpoint kits, router kit) are replaced by the values given at the
 * start ({@link GroupVariables}); {@code junction.groupvar.GROUPVAR1=<value>} gives a default.
 *
 * <p>Read again on every call (the UI's reload); never throws for a bad file, it reports problems.
 */
public final class ConfigLoader {

    /** File name endings. */
    public static final String GATEWAY_SUFFIX = ".junction.settings";
    public static final String ENDPOINT_SUFFIX = ".endpoint.settings";
    public static final String ROUTER_SUFFIX = ".router.settings";

    private final File dir;
    private final File runtimeDir;

    /**
     * @param junctionsDir the junctions folder
     * @param runtimeDir   the runtime folder (FOMs and RTI jars are relative to it)
     */
    public ConfigLoader(File junctionsDir, File runtimeDir) {
        this.dir = junctionsDir;
        this.runtimeDir = runtimeDir;
    }

    /** @return the junctions folder */
    public File getDirectory() {
        return dir;
    }

    /** Everything found. */
    public static final class Result {
        private final List<GatewayConfig> gateways;
        private final List<EndpointConfig> endpointKits;
        private final List<RouterConfig> routerKits;
        private final List<String> problems;

        Result(List<GatewayConfig> g, List<EndpointConfig> e, List<RouterConfig> r, List<String> p) {
            gateways = Collections.unmodifiableList(g);
            endpointKits = Collections.unmodifiableList(e);
            routerKits = Collections.unmodifiableList(r);
            problems = Collections.unmodifiableList(p);
        }

        public List<GatewayConfig> getGateways() {
            return gateways;
        }

        public List<EndpointConfig> getEndpointKits() {
            return endpointKits;
        }

        public List<RouterConfig> getRouterKits() {
            return routerKits;
        }

        /** @return folder level problems (unreadable files) */
        public List<String> getProblems() {
            return problems;
        }

        /** @return the gateway with this id, or null */
        public GatewayConfig gateway(String id) {
            for (GatewayConfig g : gateways) {
                if (g.getId().equals(id)) {
                    return g;
                }
            }
            return null;
        }
    }

    /** @return the configuration as it is on disk now */
    public Result load() {
        List<String> problems = new ArrayList<String>();
        List<EndpointConfig> eps = new ArrayList<EndpointConfig>();
        for (File f : files(new File(dir, "endpoints"), ENDPOINT_SUFFIX)) {
            SimwProperties p = read(f, problems);
            if (p != null) {
                eps.add(new EndpointConfig(stem(f, ENDPOINT_SUFFIX), p, "endpoints/" + f.getName(), runtimeDir));
            }
        }
        List<RouterConfig> routers = new ArrayList<RouterConfig>();
        for (File f : files(new File(dir, "routers"), ROUTER_SUFFIX)) {
            SimwProperties p = read(f, problems);
            if (p != null) {
                routers.add(new RouterConfig(stem(f, ROUTER_SUFFIX), p, "routers/" + f.getName(), dir));
            }
        }
        List<GatewayConfig> gws = new ArrayList<GatewayConfig>();
        for (File f : files(dir, GATEWAY_SUFFIX)) {
            SimwProperties p = read(f, problems);
            if (p != null) {
                gws.add(gateway(f, p));
            }
        }
        return new Result(gws, eps, routers, problems);
    }

    /**
     * Builds a gateway from properties (also used by tests and the REST API).
     *
     * @param file the gateway file (for its id and messages)
     * @param p    its properties
     * @return the gateway
     */
    public GatewayConfig gateway(File file, SimwProperties p) {
        return gateway(file, p, null);
    }

    /**
     * One gateway from disk, with group variable values.
     *
     * @param id   the gateway id
     * @param vars variable name to value (on top of the file's defaults), or null
     * @return the gateway, or null when there is no such file
     */
    public GatewayConfig gateway(String id, Map<String, String> vars) {
        File f = new File(dir, id + GATEWAY_SUFFIX);
        if (!id.matches("[A-Za-z0-9_.-]+") || !f.isFile()) {
            return null;
        }
        List<String> problems = new ArrayList<String>();
        SimwProperties p = read(f, problems);
        return p == null ? null : gateway(f, p, vars);
    }

    /**
     * Builds a gateway from properties, with group variables replaced.
     *
     * @param file the gateway file (for its id and messages)
     * @param raw  its properties
     * @param vars variable name to value (on top of the file's {@code junction.groupvar.*} defaults), or null
     * @return the gateway
     */
    public GatewayConfig gateway(File file, SimwProperties raw, Map<String, String> vars) {
        String id = stem(file, GATEWAY_SUFFIX);
        List<String> problems = new ArrayList<String>();      // errors
        List<String> warnings = new ArrayList<String>();
        Map<String, String> defaults = new LinkedHashMap<String, String>();
        Map<String, String> descriptions = new LinkedHashMap<String, String>();
        for (String key : raw.getKeysWithPrefix("junction.groupvar.")) {
            String var = key.substring("junction.groupvar.".length());
            if (GroupVariables.isDescriptionKey(key)) {
                // 0.9.16: junction.groupvar.GROUPVAR1.desc=Home city - a label for people, nothing else
                String dv = var.substring(0, var.length() - GroupVariables.DESCRIPTION_SUFFIX.length()).toUpperCase(java.util.Locale.ROOT);
                String text = raw.getString(key, "").trim();
                if (!GroupVariables.isName(dv)) {
                    warnings.add(key + ": variables are named GROUPVAR1, GROUPVAR2, ...");
                } else if (!text.isEmpty()) {
                    descriptions.put(dv, text);
                }
                continue;
            }
            if (GroupVariables.isName(var)) {
                defaults.put(var, raw.getString(key));
            } else {
                warnings.add(key + ": variables are named GROUPVAR1, GROUPVAR2, ...");
            }
        }
        Map<String, String> values = new LinkedHashMap<String, String>(defaults);
        if (vars != null) {
            for (Map.Entry<String, String> e : vars.entrySet()) {
                if (e.getValue() != null && !e.getValue().trim().isEmpty()) {
                    values.put(e.getKey(), e.getValue().trim());
                }
            }
        }
        java.util.Set<String> used = new java.util.TreeSet<String>();
        GroupVariables.collect(raw, used);
        SimwProperties p = GroupVariables.apply(raw, values);
        String name = p.getString("junction.name", id);
        String description = p.getString("junction.description", "");
        String[] ids = p.getStringArray("junction.endpoints");
        Map<String, EndpointConfig> endpoints = new LinkedHashMap<String, EndpointConfig>();
        List<String> idList = new ArrayList<String>();
        for (String s : ids) {
            if (!s.trim().isEmpty()) {
                idList.add(s.trim());
            }
        }
        if (idList.isEmpty()) {
            problems.add("junction.endpoints lists no endpoints");
        }
        for (String epId : idList) {
            if (!epId.matches("[A-Za-z0-9_-]+")) {
                problems.add("endpoint id '" + epId + "' may only have letters, digits, '-' and '_'");
                continue;
            }
            if (endpoints.containsKey(epId)) {
                problems.add("endpoint id '" + epId + "' is listed twice");
                continue;
            }
            SimwProperties ep = new SimwProperties();
            SimwProperties kitRaw = null;
            String kit = p.getString("junction.endpoint." + epId);
            String source = file.getName();
            if (kit != null && !kit.trim().isEmpty()) {
                File kf = new File(kit.trim());
                if (!kf.isAbsolute()) {
                    kf = new File(dir, kit.trim());
                }
                SimwProperties kp = read(kf, problems);
                if (kp == null) {
                    continue;
                }
                GroupVariables.collect(kp, used);
                ep.merge(GroupVariables.apply(kp, values));
                source = kit.trim();
                kitRaw = kp;
            }
            String prefix = "junction.endpoint." + epId + ".";
            for (String key : p.getKeysWithPrefix(prefix)) {
                ep.set("endpoint." + key.substring(prefix.length()), p.getString(key), file.getName());
            }
            if (!ep.hasValue("endpoint.standard")) {
                problems.add("endpoint " + epId + ": no endpoint.standard (inline or in a kit)");
            }
            EndpointConfig ec = new EndpointConfig(epId, ep, source, runtimeDir);
            // the target as written, for the page's Discover of variable values (GROUPVAR1-Talk + Alpha-Talk -> Alpha)
            String tkey = ec.getType() != null && ec.getType().isDis() ? "dis.exid"
                    : ec.getType() == EndpointType.J2J ? "j2j.session" : "hla.federation";
            String tmpl = kitRaw == null ? null : kitRaw.getString("endpoint." + tkey, null);
            String inline = raw.getString("junction.endpoint." + epId + "." + tkey, null);
            if (inline != null) {
                tmpl = inline;
            }
            if (tmpl != null && GroupVariables.TOKEN.matcher(tmpl).find()) {
                ec.setTargetTemplate(tmpl.trim());
            }
            endpoints.put(epId, ec);
        }
        if (endpoints.size() < 2 && !idList.isEmpty()) {
            problems.add("a gateway needs at least two endpoints");
        }

        SimwProperties rp = new SimwProperties();
        String rkit = p.getString("junction.router");
        String rsource = file.getName();
        if (rkit != null && !rkit.trim().isEmpty()) {
            File rf = new File(rkit.trim());
            if (!rf.isAbsolute()) {
                rf = new File(dir, rkit.trim());
            }
            SimwProperties kp = read(rf, problems);
            if (kp != null) {
                GroupVariables.collect(kp, used);
                rp.merge(GroupVariables.apply(kp, values));
                rsource = rkit.trim();
            }
        }
        for (String key : p.getKeysWithPrefix("junction.router.")) {
            rp.set("router." + key.substring("junction.router.".length()), p.getString(key), file.getName());
        }
        for (String key : p.getKeysWithPrefix("junction.talk.")) {
            rp.set("talk." + key.substring("junction.talk.".length()), p.getString(key), file.getName());
        }
        RouterConfig router = new RouterConfig(id, rp, rsource, dir);

        if (router.getKind() == RouterConfig.Kind.TALK) {
            int talk = 0;
            for (EndpointConfig e : endpoints.values()) {
                if (e.getType() == EndpointType.TALK || e.getType() == EndpointType.J2J) {
                    talk++;                                       // 0.10.0: a J2J session can be a Talk side
                }
                if (e.getType() == EndpointType.J2J && !e.isJ2jFomsGiven()) {
                    problems.add("a J2J endpoint (" + e.getId() + ") in a Talk gateway needs the Talk FOM files in endpoint.hla.foms");
                }
            }
            if (talk < 2 && router.getTalkRadioEndpoint().isEmpty()) {
                problems.add("a talk router needs two talk endpoints, or one and talk.radio.endpoint");
            }
            if (!router.getTalkRadioEndpoint().isEmpty() && !endpoints.containsKey(router.getTalkRadioEndpoint())) {
                problems.add("talk.radio.endpoint '" + router.getTalkRadioEndpoint() + "' is not an endpoint of this gateway");
            }
        }
        if (router.getKind() == RouterConfig.Kind.PDU) {
            for (EndpointConfig e : endpoints.values()) {
                if (e.getType() != null && !e.getType().isPduPort()) {
                    problems.add("a PDU relay (router.kind=pdu) takes DIS 6, DIS 7, C-DIS and J2J endpoints only; "
                            + e.getId() + " is " + e.getType().label());
                }
            }
            if (endpoints.size() < 2) {
                problems.add("a PDU relay needs two endpoints");
            }
        }
        for (EndpointConfig e : endpoints.values()) {
            if (e.getType() == EndpointType.J2J) {
                e.setJ2jObjects(router.getKind() != RouterConfig.Kind.PDU);   // 0.10.0: objects in IEM / Talk gateways
            }
        }
        String group = p.getString("junction.group", rp.getString("router.group", "")).trim();
        // every description is kept: in a group, another gateway may use the variable this file describes
        if (group.isEmpty()) {
            for (String d : descriptions.keySet()) {
                if (!used.contains(d)) {
                    warnings.add("junction.groupvar." + d + ".desc describes a variable the settings do not use");
                }
            }
        }
        Map<String, String> usedDefaults = new LinkedHashMap<String, String>();
        Map<String, String> usedValues = new LinkedHashMap<String, String>();
        java.util.Set<String> unresolved = new java.util.LinkedHashSet<String>();
        for (String v : GroupVariables.sorted(used)) {
            usedDefaults.put(v, defaults.get(v));
            if (values.containsKey(v)) {
                usedValues.put(v, values.get(v));
            } else {
                unresolved.add(v);
            }
        }
        boolean autostart = p.getBoolean("junction.autostart", false);
        if (autostart && !unresolved.isEmpty()) {
            warnings.add("junction.autostart needs defaults for " + unresolved + " (junction.groupvar.<name>)");
        }
        return new GatewayConfig(id, name, description, file.getName(), endpoints, router, autostart, problems, warnings,
                group, usedDefaults, usedValues, unresolved, descriptions);
    }

    private static SimwProperties read(File f, List<String> problems) {
        if (!f.isFile()) {
            problems.add("file not found: " + f.getPath());
            return null;
        }
        try {
            SimwProperties p = new SimwProperties();
            p.load(f);
            return p;
        } catch (IOException | RuntimeException e) {
            problems.add("cannot read " + f.getPath() + ": " + e.getMessage());
            return null;
        }
    }

    private static List<File> files(File d, String suffix) {
        File[] fs = d.listFiles((x, n) -> n.endsWith(suffix));
        if (fs == null) {
            return Collections.emptyList();
        }
        Arrays.sort(fs);
        List<File> l = new ArrayList<File>();
        for (File f : fs) {
            if (f.isFile()) {
                l.add(f);
            }
        }
        return l;
    }

    private static String stem(File f, String suffix) {
        String n = f.getName();
        return n.endsWith(suffix) ? n.substring(0, n.length() - suffix.length()) : n;
    }
}
