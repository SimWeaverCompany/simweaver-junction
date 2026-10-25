package com.simweaver.junction.router;

import com.simweaver.common.json.Json;
import com.simweaver.junction.config.RouterConfig;
import com.simweaver.junction.endpoint.Endpoint;
import com.simweaver.junction.endpoint.EndpointException;
import com.simweaver.junction.endpoint.EndpointListener;
import com.simweaver.junction.endpoint.EndpointState;
import com.simweaver.junction.endpoint.PduPort;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Passes DIS PDUs between DIS 6, DIS 7 and C-DIS endpoints as they are, not through the RPR model
 * ({@code router.kind=pdu}, 0.9.18): every PDU one endpoint hears goes to the others, with their
 * protocol version and exercise. A C-DIS endpoint decodes to DIS 7 and encodes what it sends,
 * so DIS 7 ⇄ C-DIS carries all 22 PDU types C-DIS has.
 *
 * <p>Echoes: each endpoint drops what its own sender sent (by the source port), so a PDU relayed
 * onto a network does not come back. {@code router.pdu.types} limits the PDU types passed.
 */
public final class PduRelayRouter implements Router {

    private static final Logger LOG = Logger.getLogger(PduRelayRouter.class.getName());

    private final RouterConfig cfg;
    private final String gatewayId;
    private final Set<Integer> types;
    private final RouterStats stats = new RouterStats();
    private final AtomicLong filtered = new AtomicLong();
    private final Map<String, AtomicLong> relayed = new ConcurrentHashMap<String, AtomicLong>();
    private final Map<Integer, AtomicLong> byType = new ConcurrentHashMap<Integer, AtomicLong>();
    private volatile List<PduPort> ports = Collections.emptyList();
    private volatile boolean running;

    public PduRelayRouter(RouterConfig cfg, String gatewayId) {
        this.cfg = cfg;
        this.gatewayId = gatewayId;
        this.types = new HashSet<Integer>(cfg.getPduTypes());
    }

    @Override
    public EndpointListener inbound() {
        return new EndpointListener() {
            @Override
            public void discovered(Endpoint from, long key, int objectClass, String name, String producer) {
            }

            @Override
            public void reflected(Endpoint from, long key, int[] attributes, byte[][] values, byte[] tag, String producer) {
            }

            @Override
            public void removed(Endpoint from, long key, byte[] tag) {
            }

            @Override
            public void received(Endpoint from, int interactionClass, int[] parameters, byte[][] values, byte[] tag, String producer) {
            }

            @Override
            public void provideRequested(Endpoint from, long key, int[] attributes) {
            }

            @Override
            public void registered(Endpoint from, long gwId, long key, String name) {
            }

            @Override
            public void registrationFailed(Endpoint from, long gwId, String reason) {
            }

            @Override
            public void stateChanged(Endpoint from, EndpointState state, String message) {
                if (state == EndpointState.FAILED || state == EndpointState.LOST) {
                    LOG.warning("Junction " + gatewayId + ": PDU relay endpoint " + from.id() + " is " + state
                            + (message == null ? "" : ": " + message));
                }
            }
        };
    }

    @Override
    public void start(List<Endpoint> endpoints, Map<String, ModelTools> tools) throws EndpointException {
        List<PduPort> list = new ArrayList<PduPort>();
        for (Endpoint e : endpoints) {
            if (!(e instanceof PduPort)) {
                throw new EndpointException(EndpointException.CONFIG_INVALID, "a PDU relay takes DIS and C-DIS endpoints only; "
                        + e.id() + " is " + e.type().label(), null);
            }
            list.add((PduPort) e);
        }
        this.ports = Collections.unmodifiableList(list);
        running = true;
        for (PduPort p : list) {
            p.relayTo(this::relay);
        }
        LOG.info("Junction " + gatewayId + ": PDU relay between " + endpointIds(list)
                + (types.isEmpty() ? "" : ", PDU types " + cfg.getPduTypes()));
    }

    private static String endpointIds(List<PduPort> list) {
        List<String> ids = new ArrayList<String>();
        for (PduPort p : list) {
            ids.add(p.id());
        }
        return String.join(", ", ids);
    }

    private void relay(PduPort from, byte[] pdu) {
        if (!running) {
            return;
        }
        int type = pdu[2] & 0xFF;
        if (!types.isEmpty() && !types.contains(type)) {
            filtered.incrementAndGet();
            return;
        }
        byType.computeIfAbsent(type, k -> new AtomicLong()).incrementAndGet();
        for (PduPort to : ports) {
            if (to != from) {
                to.sendPdu(pdu);
                relayed.computeIfAbsent(from.id() + " -> " + to.id(), k -> new AtomicLong()).incrementAndGet();
                if (type == 1 || type == 2 || type == 3) {
                    stats.updatesRouted.incrementAndGet();
                } else {
                    stats.interactionsRouted.incrementAndGet();
                }
            }
        }
    }

    @Override
    public void stop() {
        running = false;
        for (PduPort p : ports) {
            p.relayTo(null);
        }
    }

    @Override
    public RouterStats stats() {
        return stats;
    }

    @Override
    public Json.Writable compatibility() {
        return () -> {
            Json.JsonObject o = Json.object();
            o.put("kind", "pdu");
            o.put("note", "PDUs are passed as they are (not through the RPR model)");
            o.put("pdutypes", cfg.getPduTypes().isEmpty() ? "all" : cfg.getPduTypes());
            return o;
        };
    }

    @Override
    public Json.Writable objects() {
        return () -> {
            Json.JsonObject o = Json.object();
            Map<String, Object> routes = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, AtomicLong> e : relayed.entrySet()) {
                routes.put(e.getKey(), e.getValue().get());
            }
            Map<String, Object> perType = new java.util.TreeMap<String, Object>();
            for (Map.Entry<Integer, AtomicLong> e : byType.entrySet()) {
                perType.put(String.format("%03d", e.getKey()), e.getValue().get());
            }
            o.put("relayed", routes);
            o.put("pdutypes", perType);
            o.put("filtered", filtered.get());
            return o;
        };
    }

    @Override
    public int queueDepth() {
        return 0;
    }
}
