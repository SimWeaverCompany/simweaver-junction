package com.simweaver.junction.config;

import com.simweaver.common.config.SimwProperties;
import com.simweaver.common.json.Json;
import com.simweaver.kafka.client.KafkaSettings;
import com.simweaver.kafka.record.RecordBatches;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The {@code endpoint.kafka.*} settings of a Kafka endpoint (0.13.0): where the cluster is, how to
 * reach it, the topics, the consumer group and what the producer does. Passwords are never part of
 * {@link #toJson}.
 */
public final class KafkaEndpointSettings {

    /** Default topics when {@code endpoint.kafka.topic} is not given. */
    public static final String DEFAULT_PDU_TOPIC = "simweaver.dis";
    public static final String DEFAULT_OBJECT_TOPIC = "simweaver.objects";

    private final String bootstrapText;
    private final List<String> bootstrap;
    private final String topic;
    private final String interactionTopic;
    private final String group;
    private final boolean startEarliest;
    private final boolean produce;
    private final boolean consume;
    private final int acks;
    private final int compression;
    private final int lingerMs;
    private final boolean fullState;
    private final int objectTimeoutSecs;
    private final boolean autoCreateTopics;
    private final boolean entityKeys;
    private final String clientId;
    private final KafkaSettings.Security security;
    private final String saslMechanism;
    private final String username;
    private final String password;
    private final String trustStore;
    private final String trustStorePassword;
    private final String keyStore;
    private final String keyStorePassword;
    private final boolean hostnameVerification;
    private final List<String> errors = new ArrayList<String>();

    public KafkaEndpointSettings(String id, SimwProperties p, File baseDir) {
        String pre = "endpoint.kafka.";
        this.bootstrapText = p.getString(pre + "bootstrap", "").trim();
        this.bootstrap = KafkaSettings.parseServers(bootstrapText);
        this.topic = p.getString(pre + "topic", "").trim();
        this.interactionTopic = p.getString(pre + "interactiontopic", "").trim();
        this.group = p.getString(pre + "group", "").trim();
        String start = p.getString(pre + "start", "latest").trim().toLowerCase(Locale.ROOT);
        this.startEarliest = "earliest".equals(start);
        String direction = p.getString(pre + "direction", "both").trim().toLowerCase(Locale.ROOT);
        this.produce = !"consume".equals(direction);
        this.consume = !"produce".equals(direction);
        String acksText = p.getString(pre + "acks", "all").trim().toLowerCase(Locale.ROOT);
        this.acks = "all".equals(acksText) || "-1".equals(acksText) ? -1 : "1".equals(acksText) ? 1 : "0".equals(acksText) ? 0 : -2;
        this.compression = RecordBatches.compressionFromSetting(p.getString(pre + "compression", "none"));
        this.lingerMs = p.getInt(pre + "lingerms", 5);
        String values = p.getString(pre + "values", "full").trim().toLowerCase(Locale.ROOT);
        this.fullState = !"changed".equals(values);
        this.objectTimeoutSecs = p.getInt(pre + "objecttimeoutseconds", 0);
        this.autoCreateTopics = p.getBoolean(pre + "autocreatetopics", true);
        String key = p.getString(pre + "key", "entity").trim().toLowerCase(Locale.ROOT);
        this.entityKeys = !"none".equals(key);
        this.clientId = p.getString(pre + "clientid", "simweaver-junction").trim();
        this.security = KafkaSettings.Security.fromSetting(p.getString(pre + "security", "plaintext"));
        this.saslMechanism = p.getString(pre + "sasl.mechanism", "PLAIN").trim();
        this.username = p.getString(pre + "sasl.username", "").trim();
        this.password = p.getString(pre + "sasl.password", "");
        this.trustStore = file(p.getString(pre + "ssl.truststore", "").trim(), baseDir);
        this.trustStorePassword = p.getString(pre + "ssl.truststorepassword", "");
        this.keyStore = file(p.getString(pre + "ssl.keystore", "").trim(), baseDir);
        this.keyStorePassword = p.getString(pre + "ssl.keystorepassword", "");
        this.hostnameVerification = p.getBoolean(pre + "ssl.hostnameverification", true);

        String e = "endpoint " + id + ": ";
        boolean tokens = GroupVariables.TOKEN.matcher(bootstrapText).find();
        if (bootstrap.isEmpty()) {
            errors.add(e + "endpoint.kafka.bootstrap is empty (the Kafka brokers, host:port, comma separated)");
        } else if (!tokens) {
            for (String b : bootstrap) {
                if (KafkaSettings.hostPort(b) == null) {
                    errors.add(e + "endpoint.kafka.bootstrap: '" + b + "' is not host:port");
                }
            }
        }
        if (!"latest".equals(start) && !"earliest".equals(start)) {
            errors.add(e + "endpoint.kafka.start must be latest or earliest");
        }
        if (!"both".equals(direction) && !"produce".equals(direction) && !"consume".equals(direction)) {
            errors.add(e + "endpoint.kafka.direction must be both, produce or consume");
        }
        if (acks == -2) {
            errors.add(e + "endpoint.kafka.acks must be all, 1 or 0");
        }
        if (compression < 0) {
            errors.add(e + "endpoint.kafka.compression must be none or gzip (snappy, lz4 are read, not written)");
        }
        if (lingerMs < 0 || lingerMs > 10_000) {
            errors.add(e + "endpoint.kafka.lingerms must be 0-10000");
        }
        if (!"full".equals(values) && !"changed".equals(values)) {
            errors.add(e + "endpoint.kafka.values must be full or changed");
        }
        if (!"entity".equals(key) && !"none".equals(key)) {
            errors.add(e + "endpoint.kafka.key must be entity or none");
        }
        if (security == null) {
            errors.add(e + "endpoint.kafka.security must be plaintext, ssl, sasl_plaintext or sasl_ssl");
        } else if (security.sasl()) {
            if (KafkaSettings.mechanism(saslMechanism) == null) {
                errors.add(e + "endpoint.kafka.sasl.mechanism must be PLAIN, SCRAM-SHA-256 or SCRAM-SHA-512");
            }
            if (username.isEmpty()) {
                errors.add(e + "SASL needs endpoint.kafka.sasl.username");
            }
        }
        if (!trustStore.isEmpty() && !new File(trustStore).isFile()) {
            errors.add(e + "endpoint.kafka.ssl.truststore not found: " + trustStore);
        }
        if (!keyStore.isEmpty() && !new File(keyStore).isFile()) {
            errors.add(e + "endpoint.kafka.ssl.keystore not found: " + keyStore);
        }
        for (String t : new String[] {topic, interactionTopic}) {
            String why = topicProblem(t);
            if (why != null && !GroupVariables.TOKEN.matcher(t).find()) {
                errors.add(e + why);
            }
        }
    }

    private static String file(String s, File baseDir) {
        if (s.isEmpty()) {
            return s;
        }
        File f = new File(s);
        if (!f.isAbsolute() && baseDir != null) {
            f = new File(baseDir, s);
        }
        return f.getPath();
    }

    /** @return why a topic name cannot be one (Kafka's rule: 1-249 of a-z A-Z 0-9 . _ -), or null; empty is fine (default) */
    public static String topicProblem(String t) {
        if (t == null || t.isEmpty()) {
            return null;
        }
        if (t.length() > 249 || !t.matches("[a-zA-Z0-9._-]+") || ".".equals(t) || "..".equals(t)) {
            return "'" + t + "' is not a Kafka topic name (1-249 characters of a-z A-Z 0-9 . _ -)";
        }
        return null;
    }

    /** @return the client settings for the cluster */
    public KafkaSettings client(String clientIdSuffix) {
        KafkaSettings s = new KafkaSettings();
        s.bootstrap = new ArrayList<String>(bootstrap);
        s.clientId = clientId + (clientIdSuffix == null ? "" : "-" + clientIdSuffix);
        s.security = security;
        s.saslMechanism = KafkaSettings.mechanism(saslMechanism) == null ? saslMechanism : KafkaSettings.mechanism(saslMechanism);
        s.username = username;
        s.password = password;
        s.trustStore = trustStore;
        s.trustStorePassword = trustStorePassword;
        s.keyStore = keyStore;
        s.keyStorePassword = keyStorePassword;
        s.hostnameVerification = hostnameVerification;
        return s;
    }

    /** @return the topic setting, empty when not given (the endpoint's kind picks the default) */
    public String getTopic() {
        return topic;
    }

    /** @return the topic of interactions (object endpoints); empty = the objects' topic */
    public String getInteractionTopic() {
        return interactionTopic;
    }

    /** @return the bootstrap servers as written */
    public String getBootstrap() {
        return bootstrapText;
    }

    /** @return the consumer group setting: empty = the default (per gateway and endpoint), "none" = no group */
    public String getGroup() {
        return group;
    }

    public boolean isStartEarliest() {
        return startEarliest;
    }

    public boolean isProduce() {
        return produce;
    }

    public boolean isConsume() {
        return consume;
    }

    public int getAcks() {
        return acks;
    }

    public int getCompression() {
        return compression;
    }

    public int getLingerMs() {
        return lingerMs;
    }

    /** @return {@code values=full}: every object record has all the object's attributes (else only the changed ones) */
    public boolean isFullState() {
        return fullState;
    }

    /** @return seconds without a record after which a consumed object is removed; 0 = never */
    public int getObjectTimeoutSecs() {
        return objectTimeoutSecs;
    }

    public boolean isAutoCreateTopics() {
        return autoCreateTopics;
    }

    /** @return PDU records keyed by the PDU's first entity id (else no key) */
    public boolean isEntityKeys() {
        return entityKeys;
    }

    public List<String> getErrors() {
        return Collections.unmodifiableList(errors);
    }

    /** For the configuration JSON: never the passwords. */
    public Json.JsonObject toJson() {
        Json.JsonObject o = Json.object();
        o.put("bootstrap", bootstrapText);
        o.put("topic", topic);
        o.put("interactiontopic", interactionTopic);
        o.put("group", group);
        o.put("start", startEarliest ? "earliest" : "latest");
        o.put("direction", produce && consume ? "both" : produce ? "produce" : "consume");
        o.put("acks", acks == -1 ? "all" : String.valueOf(acks));
        o.put("compression", RecordBatches.compressionName(Math.max(0, compression)));
        o.put("values", fullState ? "full" : "changed");
        o.put("security", security == null ? "?" : security.name().toLowerCase(Locale.ROOT));
        if (security != null && security.sasl()) {
            o.put("saslmechanism", saslMechanism);
            o.put("saslusername", username);
        }
        return o;
    }
}
