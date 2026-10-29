package com.simweaver.junction.endpoint.j2j;

import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.j2j.J2jService;
import com.simweaver.j2j.session.J2jSessions;
import com.simweaver.junction.config.EndpointConfig;
import com.simweaver.junction.config.EndpointType;
import com.simweaver.junction.endpoint.Endpoint;
import com.simweaver.junction.endpoint.EndpointException;
import com.simweaver.junction.endpoint.EndpointListener;
import com.simweaver.junction.endpoint.EndpointState;
import com.simweaver.junction.endpoint.EndpointStats;
import com.simweaver.junction.endpoint.Interest;
import com.simweaver.junction.endpoint.NameCandidates;
import com.simweaver.junction.endpoint.PduPort;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * A gateway's side of a J2J PDU session (0.10.0, design §9.6): the gateway's other endpoints
 * (DIS 6, DIS 7, C-DIS) share their PDUs, as they are, with gateways on other SimWeaver servers
 * that joined the same session. For the PDU relay ({@code router.kind=pdu}) only.
 *
 * <p>What goes into the session is rewritten to the session's DIS version and exercise
 * ({@code endpoint.j2j.dis}, {@code endpoint.j2j.exid}); the members must agree on both. The
 * target is the session name ({@code endpoint.j2j.session} by default).
 */
public final class J2jEndpoint implements Endpoint, PduPort {

    private static final Logger LOG = Logger.getLogger(J2jEndpoint.class.getName());

    /** gateway id -> the server's PDU sessions (null: J2J does not run); tests replace it */
    private static volatile Function<String, J2jSessions> hubs = gatewayId -> {
        J2jService s = J2jService.global();
        return s == null || !"running".equals(s.state()) ? null : s.sessions();
    };

    /** @return the server's sessions for a gateway's J2J endpoints, or null when J2J does not run */
    static J2jSessions hub(String gatewayId) {
        return hubs.apply(gatewayId);
    }

    /** For tests: where J2J endpoints (PDU and object sessions) find the sessions, per gateway id. */
    public static void setHubs(Function<String, J2jSessions> f) {
        hubs = f;
    }

    private final EndpointConfig cfg;
    private final String session;
    private final String gatewayId;
    private final String model;
    private final EndpointStats stats = new EndpointStats();
    private volatile EndpointState state = EndpointState.NEW;
    private volatile String stateMessage;
    private volatile PduSink sink;
    private volatile J2jSessions.Member member;
    private volatile EndpointListener listener;

    public J2jEndpoint(EndpointConfig cfg, String session, String gatewayId) throws EndpointException {
        if (cfg.getType() != EndpointType.J2J) {
            throw new EndpointException(EndpointException.CONFIG_INVALID, "endpoint " + cfg.getId() + " is not a J2J endpoint", null);
        }
        String why = J2jSessions.nameProblem(session);
        if (why != null) {
            throw new EndpointException(EndpointException.CONFIG_INVALID, "endpoint " + cfg.getId() + ": " + why, null);
        }
        this.cfg = cfg;
        this.session = session;
        this.gatewayId = gatewayId;
        this.model = "DIS " + cfg.getJ2jDisVersion() + ", exercise " + cfg.getJ2jExerciseId();
    }

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
        return EndpointType.J2J;
    }

    /** @return null: a PDU session has no object model */
    @Override
    public InformationExchangeModel model() {
        return null;
    }

    @Override
    public String target() {
        return session;
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
    public void open(EndpointListener l) throws EndpointException {
        this.listener = l;
        setState(EndpointState.CONNECTING, null);
        J2jSessions hub = hubs.apply(gatewayId);
        if (hub == null) {
            String m = "J2J does not run on this server (etc/j2j/network.settings, install guide 2.8): no session " + session;
            setState(EndpointState.FAILED, m);
            throw new EndpointException(EndpointException.CONNECT_FAILED, "endpoint " + id() + ": " + m, null);
        }
        member = hub.join(session, gatewayId, "", id(), model, cfg.isJ2jReliableOnly(), this::fromSession);
        setState(EndpointState.JOINED, null);
        LOG.info("Junction " + gatewayId + "/" + id() + ": joined J2J session " + session + " (" + model + ")");
    }

    private void setState(EndpointState s, String message) {
        state = s;
        stateMessage = message;
        EndpointListener l = listener;
        if (l != null) {
            l.stateChanged(this, s, message);
        }
    }

    /** A PDU of another member: to the relay, which sends it to the gateway's other endpoints. */
    private void fromSession(byte[] pdu) {
        stats.markIn(pdu.length);
        (isObjectPdu(pdu[2] & 0xFF) ? stats.updatesIn : stats.interactionsIn).incrementAndGet();
        PduSink s = sink;
        if (s != null) {
            s.pdu(this, pdu);
        }
    }

    @Override
    public void relayTo(PduSink s) {
        this.sink = s;
    }

    /** A PDU of the gateway's other endpoints: into the session, as DIS of the session's version and exercise. */
    @Override
    public void sendPdu(byte[] pdu) {
        J2jSessions.Member m = member;
        if (m == null || pdu.length < 12) {
            return;
        }
        byte[] out = pdu.clone();
        int from = out[0] & 0xFF;
        out[0] = (byte) cfg.getJ2jDisVersion();
        out[1] = (byte) cfg.getJ2jExerciseId();
        if (cfg.getJ2jDisVersion() < 7 && from >= 7) {
            out[10] = 0;                                            // the DIS 7 PDU status is padding in DIS 6
        }
        stats.markOut(out.length);
        (isObjectPdu(out[2] & 0xFF) ? stats.updatesOut : stats.interactionsOut).incrementAndGet();
        m.send(out);
    }

    private static boolean isObjectPdu(int type) {
        return type == 1 || type == 67 || type == 23 || type == 24 || type == 25 || type == 27 || type == 28;
    }

    @Override
    public void declare(Interest publish, Interest subscribe) {
        // a PDU session declares nothing
    }

    @Override
    public void createObject(long gwId, int objectClass, NameCandidates names) {
        EndpointListener l = listener;
        if (l != null) {
            l.registrationFailed(this, gwId, "a J2J session endpoint has no objects");
        }
    }

    @Override
    public void updateObject(long key, int[] attributes, byte[][] values, byte[] tag) {
    }

    @Override
    public void deleteObject(long key, byte[] tag) {
    }

    @Override
    public void sendInteraction(int interactionClass, int[] parameters, byte[][] values, byte[] tag) {
    }

    @Override
    public void requestUpdate(long key, int[] attributes) {
    }

    @Override
    public void close(boolean deleteObjects) {
        J2jSessions.Member m = member;
        member = null;
        sink = null;
        if (m != null) {
            m.leave();
        }
        if (state != EndpointState.CLOSED) {
            setState(EndpointState.CLOSED, null);
        }
    }

    @Override
    public EndpointStats stats() {
        return stats;
    }

    /** For the gateway JSON: the session, its model, the other members and the counters. */
    public Map<String, Object> diagnostics() {
        Map<String, Object> d = new LinkedHashMap<String, Object>();
        d.put("session", session);
        d.put("kind", "pdu");
        d.put("model", model);
        d.put("transport", cfg.isJ2jReliableOnly() ? "reliable" : "auto");
        J2jSessions.Member m = member;
        if (m != null) {
            List<Map<String, Object>> peers = m.peers();
            d.put("members", peers);
            d.put("sent", m.sent.get());
            d.put("received", m.received.get());
            d.put("copies", m.copies.get());
            d.put("notsent", m.notSent.get());
            d.put("loopsdropped", m.loopsDropped.get());
        }
        return d;
    }
}
