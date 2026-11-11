package com.simweaver.junction.endpoint.kafka;

import com.simweaver.junction.config.EndpointConfig;
import com.simweaver.junction.config.KafkaEndpointSettings;
import com.simweaver.junction.endpoint.EndpointException;
import com.simweaver.kafka.KafkaException;
import com.simweaver.kafka.client.KafkaCluster;
import com.simweaver.kafka.client.KafkaConsumer;
import com.simweaver.kafka.client.KafkaProducer;
import com.simweaver.kafka.client.KafkaSettings;
import com.simweaver.kafka.record.KafkaRecord;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the two Kafka endpoints share (0.13.0): checking the cluster when opening, the producer and
 * consumer from the settings, the loop filter header and the diagnostics.
 *
 * <p>Every record a Junction endpoint writes has the header {@value #SOURCE_HEADER} with an id made
 * when the endpoint opens; it drops records with its own id, so an endpoint that writes and reads
 * one topic does not get its own records back.
 */
public final class KafkaEndpoints {

    private KafkaEndpoints() {
    }

    /** Header: the writing endpoint's id (loop filter). */
    public static final String SOURCE_HEADER = "simweaver.source";
    /** Header: what the value is ({@code application/x-dis} or {@code application/json}). */
    public static final String CONTENT_TYPE_HEADER = "content-type";

    /** An endpoint with Kafka diagnostics for the gateway JSON. */
    public interface Diagnosable {
        Map<String, Object> diagnostics();
    }

    /** @return a new id for an endpoint's records */
    static String newSourceId() {
        return UUID.randomUUID().toString();
    }

    /** @return true when the record was written by the endpoint with this id */
    static boolean isOwn(KafkaRecord r, byte[] sourceId) {
        byte[] s = r.header(SOURCE_HEADER);
        return s != null && Arrays.equals(s, sourceId);
    }

    /** @return the default consumer group of an endpoint, or "" for none */
    static String group(KafkaEndpointSettings k, String gatewayId, String endpointId) {
        String g = k.getGroup();
        if ("none".equalsIgnoreCase(g)) {
            return "";
        }
        return g.isEmpty() ? "simweaver-junction-" + gatewayId + "-" + endpointId : g;
    }

    /**
     * Reads the topics' metadata once (creating them when allowed), so a gateway does not start
     * against a cluster it cannot reach.
     */
    static void check(EndpointConfig cfg, KafkaSettings s, List<String> topics) throws EndpointException {
        KafkaSettings quick = copy(s);
        quick.requestTimeoutMs = Math.min(quick.requestTimeoutMs, 10_000);
        try (KafkaCluster c = new KafkaCluster(quick)) {
            Map<String, KafkaCluster.TopicInfo> got = c.refresh(new ArrayList<String>(new LinkedHashSet<String>(topics)),
                    cfg.getKafka().isAutoCreateTopics());
            for (String t : topics) {
                KafkaCluster.TopicInfo ti = got.get(t);
                if (ti != null && ti.error != 0 && !com.simweaver.kafka.wire.KafkaErrors.isRetriable(ti.error)) {
                    throw new EndpointException(EndpointException.CONNECT_FAILED, "endpoint " + cfg.getId() + ": topic " + t + ": "
                            + com.simweaver.kafka.wire.KafkaErrors.name(ti.error), null);
                }
            }
        } catch (KafkaException e) {
            throw new EndpointException(EndpointException.CONNECT_FAILED, "endpoint " + cfg.getId() + ": " + e.getMessage(), e);
        }
    }

    private static KafkaSettings copy(KafkaSettings s) {
        KafkaSettings c = new KafkaSettings();
        c.bootstrap = new ArrayList<String>(s.bootstrap);
        c.clientId = s.clientId;
        c.security = s.security;
        c.saslMechanism = s.saslMechanism;
        c.username = s.username;
        c.password = s.password;
        c.trustStore = s.trustStore;
        c.trustStorePassword = s.trustStorePassword;
        c.keyStore = s.keyStore;
        c.keyStorePassword = s.keyStorePassword;
        c.hostnameVerification = s.hostnameVerification;
        c.connectTimeoutMs = s.connectTimeoutMs;
        c.requestTimeoutMs = s.requestTimeoutMs;
        return c;
    }

    static KafkaProducer producer(KafkaEndpointSettings k, KafkaSettings s, String thread) {
        KafkaProducer.Options o = new KafkaProducer.Options();
        o.acks = k.getAcks();
        o.compression = k.getCompression();
        o.lingerMs = k.getLingerMs();
        o.autoCreateTopics = k.isAutoCreateTopics();
        return new KafkaProducer(s, o, thread);
    }

    static KafkaConsumer consumer(KafkaEndpointSettings k, KafkaSettings s, List<String> topics, String group,
                                  KafkaConsumer.Listener l, String thread) {
        KafkaConsumer.Options o = new KafkaConsumer.Options();
        o.topics = new ArrayList<String>(new LinkedHashSet<String>(topics));
        o.group = group;
        o.startEarliest = k.isStartEarliest();
        o.autoCreateTopics = k.isAutoCreateTopics();
        return new KafkaConsumer(s, o, l, thread);
    }

    static List<KafkaRecord.Header> headers(byte[] sourceId, String contentType) {
        List<KafkaRecord.Header> h = new ArrayList<KafkaRecord.Header>(2);
        h.add(new KafkaRecord.Header(SOURCE_HEADER, sourceId));
        h.add(new KafkaRecord.Header(CONTENT_TYPE_HEADER, contentType.getBytes(StandardCharsets.UTF_8)));
        return h;
    }

    /** The diagnostics both endpoints give. */
    static Map<String, Object> diagnostics(String kind, KafkaEndpointSettings k, List<String> topics, String group,
                                           KafkaProducer p, KafkaConsumer c) {
        Map<String, Object> d = new LinkedHashMap<String, Object>();
        d.put("kind", kind);
        d.put("bootstrap", k.getBootstrap());
        d.put("topics", topics);
        d.put("direction", k.isProduce() && k.isConsume() ? "both" : k.isProduce() ? "produce" : "consume");
        if (c != null) {
            d.put("group", group.isEmpty() ? null : group);
        }
        if (p != null) {
            d.put("producer", p.diagnostics());
        }
        if (c != null) {
            d.put("consumer", c.diagnostics());
        }
        return d;
    }

    /** @return the first problem of the producer or consumer for the endpoint's state message, or null */
    static String problem(KafkaProducer p, KafkaConsumer c) {
        if (p != null && "failing".equals(p.state()) && p.lastError() != null) {
            return "producer: " + p.lastError();
        }
        if (c != null && "failing".equals(c.state()) && c.lastError() != null) {
            return "consumer: " + c.lastError();
        }
        return null;
    }
}
