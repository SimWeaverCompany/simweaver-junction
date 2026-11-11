package com.simweaver.junction.endpoint.kafka;

import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.junction.config.EndpointConfig;
import com.simweaver.junction.config.EndpointType;
import com.simweaver.junction.config.KafkaEndpointSettings;
import com.simweaver.junction.endpoint.Endpoint;
import com.simweaver.junction.endpoint.EndpointException;
import com.simweaver.junction.endpoint.EndpointListener;
import com.simweaver.junction.endpoint.EndpointState;
import com.simweaver.junction.endpoint.EndpointStats;
import com.simweaver.junction.endpoint.Interest;
import com.simweaver.junction.endpoint.NameCandidates;
import com.simweaver.junction.endpoint.PduPort;
import com.simweaver.kafka.client.KafkaConsumer;
import com.simweaver.kafka.client.KafkaProducer;
import com.simweaver.kafka.client.KafkaSettings;
import com.simweaver.kafka.record.KafkaRecord;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * A gateway's side of a Kafka topic carrying DIS PDUs (0.13.0), for the PDU relay
 * ({@code router.kind=pdu}): each PDU of the gateway's other endpoints (DIS 6, DIS 7, C-DIS, J2J)
 * becomes one record, as it is; each record of the topic goes to them as a PDU (they rewrite version
 * and exercise to their own).
 *
 * <p>Records are keyed by the PDU's first entity id ({@code site:application:entity}, octets
 * 12-17: the entity of an Entity State PDU, the shooter of a Fire PDU, the emitter of an Emission
 * PDU …), so an entity's PDUs stay in one partition and in order; {@code endpoint.kafka.key=none}
 * writes no key. The target is the topic.
 */
public final class KafkaPduEndpoint implements Endpoint, PduPort, KafkaEndpoints.Diagnosable {

    private static final Logger LOG = Logger.getLogger(KafkaPduEndpoint.class.getName());
    static final String CONTENT_TYPE = "application/x-dis";

    private final EndpointConfig cfg;
    private final KafkaEndpointSettings kafka;
    private final String topic;
    private final String gatewayId;
    private final String group;
    private final byte[] sourceId = KafkaEndpoints.newSourceId().getBytes(StandardCharsets.UTF_8);
    private final List<KafkaRecord.Header> headers;
    private final EndpointStats stats = new EndpointStats();
    private final AtomicLong malformed = new AtomicLong();
    private final AtomicLong ownDropped = new AtomicLong();
    private volatile EndpointState state = EndpointState.NEW;
    private volatile String stateMessage;
    private volatile EndpointListener listener;
    private volatile PduSink sink;
    private volatile KafkaProducer producer;
    private volatile KafkaConsumer consumer;

    public KafkaPduEndpoint(EndpointConfig cfg, String topic, String gatewayId) throws EndpointException {
        if (cfg.getType() != EndpointType.KAFKA) {
            throw new EndpointException(EndpointException.CONFIG_INVALID, "endpoint " + cfg.getId() + " is not a Kafka endpoint", null);
        }
        String why = KafkaEndpointSettings.topicProblem(topic);
        if (why != null || topic == null || topic.isEmpty()) {
            throw new EndpointException(EndpointException.CONFIG_INVALID, "endpoint " + cfg.getId() + ": " + (why == null ? "no topic" : why), null);
        }
        this.cfg = cfg;
        this.kafka = cfg.getKafka();
        this.topic = topic;
        this.gatewayId = gatewayId;
        this.group = KafkaEndpoints.group(kafka, gatewayId, cfg.getId());
        this.headers = KafkaEndpoints.headers(sourceId, CONTENT_TYPE);
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
        return EndpointType.KAFKA;
    }

    /** @return null: PDUs have no object model here */
    @Override
    public InformationExchangeModel model() {
        return null;
    }

    @Override
    public String target() {
        return topic;
    }

    @Override
    public EndpointState state() {
        return state;
    }

    @Override
    public String stateMessage() {
        String p = KafkaEndpoints.problem(producer, consumer);
        return p != null ? p : stateMessage;
    }

    @Override
    public void open(EndpointListener l) throws EndpointException {
        this.listener = l;
        setState(EndpointState.CONNECTING, null);
        KafkaSettings s = kafka.client(gatewayId);
        try {
            KafkaEndpoints.check(cfg, s, Collections.singletonList(topic));
        } catch (EndpointException e) {
            setState(EndpointState.FAILED, e.getMessage());
            throw e;
        }
        String t = "junction-kafka-" + gatewayId + "-" + cfg.getId();
        if (kafka.isProduce()) {
            producer = KafkaEndpoints.producer(kafka, s, t + "-producer");
        }
        if (kafka.isConsume()) {
            consumer = KafkaEndpoints.consumer(kafka, s, Collections.singletonList(topic), group, new KafkaConsumer.Listener() {
                @Override
                public void records(List<KafkaRecord> records) {
                    fromTopic(records);
                }
            }, t + "-consumer");
        }
        setState(EndpointState.JOINED, null);
        LOG.info("Junction " + gatewayId + "/" + id() + ": Kafka topic " + topic + " at " + kafka.getBootstrap()
                + (consumer != null && !group.isEmpty() ? ", group " + group : ""));
    }

    private void setState(EndpointState s, String message) {
        state = s;
        stateMessage = message;
        EndpointListener l = listener;
        if (l != null) {
            l.stateChanged(this, s, message);
        }
    }

    private void fromTopic(List<KafkaRecord> records) {
        PduSink s = sink;
        for (KafkaRecord r : records) {
            if (KafkaEndpoints.isOwn(r, sourceId)) {
                ownDropped.incrementAndGet();
                continue;
            }
            byte[] pdu = r.value;
            if (pdu == null || pdu.length < 12 || (pdu[0] != 6 && pdu[0] != 7) || ((pdu[8] & 0xFF) << 8 | (pdu[9] & 0xFF)) > pdu.length) {
                malformed.incrementAndGet();
                continue;
            }
            stats.markIn(pdu.length);
            (isObjectPdu(pdu[2] & 0xFF) ? stats.updatesIn : stats.interactionsIn).incrementAndGet();
            if (s != null) {
                s.pdu(this, pdu);
            }
        }
    }

    @Override
    public void relayTo(PduSink s) {
        this.sink = s;
    }

    /** A PDU of the gateway's other endpoints: one record, the PDU as it is. */
    @Override
    public void sendPdu(byte[] pdu) {
        KafkaProducer p = producer;
        if (p == null || pdu.length < 12) {
            return;
        }
        byte[] key = kafka.isEntityKeys() && pdu.length >= 18 ? entityKey(pdu) : null;
        if (p.send(topic, new KafkaRecord(key, pdu, headers, System.currentTimeMillis()))) {
            stats.markOut(pdu.length);
            (isObjectPdu(pdu[2] & 0xFF) ? stats.updatesOut : stats.interactionsOut).incrementAndGet();
        }
    }

    /** @return {@code site:application:entity} of octets 12-17 */
    static byte[] entityKey(byte[] pdu) {
        int site = (pdu[12] & 0xFF) << 8 | (pdu[13] & 0xFF);
        int app = (pdu[14] & 0xFF) << 8 | (pdu[15] & 0xFF);
        int ent = (pdu[16] & 0xFF) << 8 | (pdu[17] & 0xFF);
        return (site + ":" + app + ":" + ent).getBytes(StandardCharsets.US_ASCII);
    }

    private static boolean isObjectPdu(int type) {
        return type == 1 || type == 67 || type == 23 || type == 24 || type == 25 || type == 27 || type == 28;
    }

    @Override
    public void declare(Interest publish, Interest subscribe) {
        // PDUs: nothing to declare
    }

    @Override
    public void createObject(long gwId, int objectClass, NameCandidates names) {
        EndpointListener l = listener;
        if (l != null) {
            l.registrationFailed(this, gwId, "a Kafka PDU endpoint has no objects");
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
        sink = null;
        KafkaConsumer c = consumer;
        consumer = null;
        if (c != null) {
            c.close();
        }
        KafkaProducer p = producer;
        producer = null;
        if (p != null) {
            p.close(2000);
        }
        if (state != EndpointState.CLOSED) {
            setState(EndpointState.CLOSED, null);
        }
    }

    @Override
    public EndpointStats stats() {
        return stats;
    }

    @Override
    public Map<String, Object> diagnostics() {
        Map<String, Object> d = KafkaEndpoints.diagnostics("pdu", kafka, Collections.singletonList(topic), group, producer, consumer);
        d.put("owndropped", ownDropped.get());
        d.put("malformed", malformed.get());
        return d;
    }
}
