package com.simweaver.junction.router;

import com.simweaver.common.json.Json;
import com.simweaver.junction.endpoint.Endpoint;
import com.simweaver.junction.endpoint.EndpointException;
import com.simweaver.junction.endpoint.EndpointListener;

import java.util.List;
import java.util.Map;

/**
 * Moves traffic between the endpoints of one gateway. The gateway opens the endpoints with
 * {@link #inbound()} as their listener, then calls {@link #start}; events that arrive in
 * between are queued.
 */
public interface Router {

    /** @return the listener to open the endpoints with */
    EndpointListener inbound();

    /**
     * Compiles the routes, declares publications and subscriptions on the endpoints and
     * starts moving traffic.
     *
     * @param endpoints the opened endpoints
     * @param tools     each endpoint's model tools, by endpoint id
     * @throws EndpointException when an endpoint's declarations fail
     */
    void start(List<Endpoint> endpoints, Map<String, ModelTools> tools) throws EndpointException;

    /** Stops moving traffic (the gateway closes the endpoints afterwards). */
    void stop();

    /** @return the counters */
    RouterStats stats();

    /** @return route tables (the compatibility report), as JSON */
    Json.Writable compatibility();

    /** @return object counts: bridged objects, live / pending / failed copies */
    Json.Writable objects();

    /** @return queued events now */
    int queueDepth();
}
