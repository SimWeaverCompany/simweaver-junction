package com.simweaver.junction.router;

import com.simweaver.common.json.Json;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** The routes from one endpoint to another. Built by {@link RouteCompiler}, then read-only. */
public final class RouteTable implements Json.Writable {

    final String source;
    final String destination;
    final String rulesDescription;
    final Map<Integer, ClassRoute> objects = new HashMap<Integer, ClassRoute>();
    final Map<Integer, ClassRoute> interactions = new HashMap<Integer, ClassRoute>();
    final RouteCompiler.Report report = new RouteCompiler.Report();

    RouteTable(String source, String destination, String rulesDescription) {
        this.source = source;
        this.destination = destination;
        this.rulesDescription = rulesDescription;
    }

    /** @return the route of a source object class, or null when it does not pass */
    public ClassRoute objectRoute(int sourceClass) {
        return objects.get(sourceClass);
    }

    /** @return the route of a source interaction class, or null when it does not pass */
    public ClassRoute interactionRoute(int sourceClass) {
        return interactions.get(sourceClass);
    }

    /** @return object routes by source class */
    public Map<Integer, ClassRoute> objectRoutes() {
        return Collections.unmodifiableMap(objects);
    }

    /** @return interaction routes by source class */
    public Map<Integer, ClassRoute> interactionRoutes() {
        return Collections.unmodifiableMap(interactions);
    }

    /** @return the report */
    public RouteCompiler.Report report() {
        return report;
    }

    @Override
    public Object toJson() {
        return Json.object().put("from", source).put("to", destination).put("conversion", rulesDescription).put("report", report);
    }
}
