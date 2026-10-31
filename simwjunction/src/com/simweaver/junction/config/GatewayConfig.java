package com.simweaver.junction.config;

import com.simweaver.common.json.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A gateway (junction) definition: a name, two or more endpoints and a router, from one
 * {@code etc/junctions/*.junction.settings} file. Immutable.
 */
public final class GatewayConfig implements Json.Writable {

    private final String id;
    private final String name;
    private final String description;
    private final String file;
    private final Map<String, EndpointConfig> endpoints;
    private final RouterConfig router;
    private final boolean autostart;
    private final List<String> errors;
    private final List<String> warnings;
    private final String group;
    private final Map<String, String> variableDefaults;
    private final Map<String, String> variableValues;
    private final Map<String, String> variableDescriptions;
    private final List<String> unresolved;
    private final List<String> variableErrors;

    GatewayConfig(String id, String name, String description, String file, Map<String, EndpointConfig> endpoints,
                  RouterConfig router, boolean autostart, List<String> errors, List<String> warnings) {
        this(id, name, description, file, endpoints, router, autostart, errors, warnings, "",
                Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), Collections.<String>emptySet());
    }

    GatewayConfig(String id, String name, String description, String file, Map<String, EndpointConfig> endpoints,
                  RouterConfig router, boolean autostart, List<String> errors, List<String> warnings, String group,
                  Map<String, String> variableDefaults, Map<String, String> variableValues, java.util.Set<String> unresolved) {
        this(id, name, description, file, endpoints, router, autostart, errors, warnings, group, variableDefaults, variableValues,
                unresolved, Collections.<String, String>emptyMap());
    }

    GatewayConfig(String id, String name, String description, String file, Map<String, EndpointConfig> endpoints,
                  RouterConfig router, boolean autostart, List<String> errors, List<String> warnings, String group,
                  Map<String, String> variableDefaults, Map<String, String> variableValues, java.util.Set<String> unresolved,
                  Map<String, String> variableDescriptions) {
        this.variableDescriptions = Collections.unmodifiableMap(new LinkedHashMap<String, String>(variableDescriptions));
        this.group = group == null ? "" : group;
        this.variableDefaults = Collections.unmodifiableMap(new LinkedHashMap<String, String>(variableDefaults));
        this.variableValues = Collections.unmodifiableMap(new LinkedHashMap<String, String>(variableValues));
        this.unresolved = Collections.unmodifiableList(new ArrayList<String>(unresolved));
        this.id = id;
        this.name = name;
        this.description = description;
        this.file = file;
        this.endpoints = Collections.unmodifiableMap(new LinkedHashMap<String, EndpointConfig>(endpoints));
        this.router = router;
        this.autostart = autostart;
        List<String> all = new ArrayList<String>(errors);
        for (EndpointConfig e : endpoints.values()) {
            all.addAll(e.getErrors());
        }
        if (router != null) {
            all.addAll(router.getErrors());
        }
        // what is wrong only because a variable has no value yet (e.g. a FOM named GROUPVAR2.xml)
        List<String> pending = new ArrayList<String>();
        if (!unresolved.isEmpty()) {
            for (java.util.Iterator<String> it = all.iterator(); it.hasNext();) {
                String e = it.next();
                for (String v : unresolved) {
                    if (e.contains(v)) {
                        pending.add(e);
                        it.remove();
                        break;
                    }
                }
            }
        }
        this.variableErrors = Collections.unmodifiableList(pending);
        this.errors = Collections.unmodifiableList(all);
        List<String> w = new ArrayList<String>(warnings);
        if (router != null) {
            w.addAll(router.getWarnings());
            for (String scope : router.getNameScopes()) {
                EndpointConfig ep = endpoints == null ? null : endpoints.get(scope);
                if (ep == null) {
                    w.add("router.names.template / maxlength ." + scope + ": the gateway has no endpoint '" + scope + "'");
                } else if (ep.getType() != null && ep.getType().isDis()) {
                    w.add("router.names.template / maxlength ." + scope + ": DIS names its objects by entity id; not used there");
                }
            }
        }
        this.warnings = Collections.unmodifiableList(w);
    }

    /** @return the id: the file name without {@code .junction.settings} */
    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getFile() {
        return file;
    }

    /** @return the endpoints by id, in configuration order */
    public Map<String, EndpointConfig> getEndpoints() {
        return endpoints;
    }

    public RouterConfig getRouter() {
        return router;
    }

    /** @return start this gateway when the server starts */
    public boolean isAutostart() {
        return autostart;
    }

    /** @return what prevents starting the gateway (file, endpoints) */
    public List<String> getErrors() {
        return errors;
    }

    /** @return settings that were not understood (defaults used) */
    public List<String> getWarnings() {
        return warnings;
    }

    /** @return the group ({@code junction.group} or the router kit's {@code router.group}), "" = none */
    public String getGroup() {
        return group;
    }

    /** @return the group variables the settings use, with their defaults (null = none) */
    public Map<String, String> getVariableDefaults() {
        return variableDefaults;
    }

    /** @return the values the variables were replaced with */
    public Map<String, String> getVariableValues() {
        return variableValues;
    }

    /**
     * @return the variable descriptions of this file ({@code junction.groupvar.GROUPVAR1.desc}), also of
     *         variables only other gateways of the group use: labels for people, they change nothing else
     */
    public Map<String, String> getVariableDescriptions() {
        return variableDescriptions;
    }

    /** @return the variables without a value: they must be given at the start */
    public List<String> getUnresolved() {
        return unresolved;
    }

    /** @return true when the gateway can be started */
    public boolean isValid() {
        return errors.isEmpty() && router != null && endpoints.size() >= 2;
    }

    @Override
    public Object toJson() {
        Json.JsonObject o = Json.object();
        o.put("id", id);
        o.put("name", name);
        o.put("description", description);
        o.put("file", file);
        o.put("valid", isValid());
        Json.JsonArray eps = Json.array();
        for (EndpointConfig e : endpoints.values()) {
            eps.add(e);
        }
        o.put("endpoints", eps);
        o.put("router", router);
        o.put("autostart", autostart);
        o.put("errors", errors);
        o.put("warnings", warnings);
        o.put("group", group);
        Json.JsonArray vars = Json.array();
        for (Map.Entry<String, String> e : variableDefaults.entrySet()) {
            vars.add(Json.object().put("name", e.getKey()).putIfNotNull("default", e.getValue())
                    .putIfNotNull("value", variableValues.get(e.getKey()))
                    .putIfNotNull("description", variableDescriptions.get(e.getKey())));
        }
        o.put("variables", vars);
        o.put("unresolved", unresolved);
        o.put("variableerrors", variableErrors);
        return o;
    }
}
