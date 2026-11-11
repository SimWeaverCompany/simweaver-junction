package com.simweaver.kafka.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * How to reach a Kafka cluster: bootstrap servers, client id, security (plaintext, TLS, SASL PLAIN /
 * SCRAM-SHA-256 / SCRAM-SHA-512, both over TLS or not) and timeouts. Mutable while being built, then
 * handed to a {@link KafkaCluster}; {@link #problems()} lists what cannot work.
 */
public final class KafkaSettings {

    /** Like the Java client's {@code security.protocol}. */
    public enum Security {
        PLAINTEXT, SSL, SASL_PLAINTEXT, SASL_SSL;

        public boolean tls() {
            return this == SSL || this == SASL_SSL;
        }

        public boolean sasl() {
            return this == SASL_PLAINTEXT || this == SASL_SSL;
        }

        /** @return the value for a setting (plaintext, ssl, sasl_plaintext, sasl_ssl; '-' for '_', any case), or null */
        public static Security fromSetting(String s) {
            if (s == null || s.trim().isEmpty()) {
                return PLAINTEXT;
            }
            String n = s.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            if ("TLS".equals(n)) {
                return SSL;
            }
            if ("SASL_TLS".equals(n)) {
                return SASL_SSL;
            }
            try {
                return valueOf(n);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    public List<String> bootstrap = new ArrayList<String>();
    public String clientId = "simweaver";
    public Security security = Security.PLAINTEXT;
    /** PLAIN, SCRAM-SHA-256 or SCRAM-SHA-512 */
    public String saslMechanism = "PLAIN";
    public String username = "";
    public String password = "";
    /** trust store (JKS / PKCS12) for TLS; empty: the JVM's default trust */
    public String trustStore = "";
    public String trustStorePassword = "";
    /** key store for TLS client certificates; empty: none */
    public String keyStore = "";
    public String keyStorePassword = "";
    /** check that the broker's certificate names the host connected to */
    public boolean hostnameVerification = true;
    public int connectTimeoutMs = 10000;
    public int requestTimeoutMs = 30000;

    /** @return the bootstrap servers parsed from a comma, semicolon or space separated list */
    public static List<String> parseServers(String s) {
        List<String> l = new ArrayList<String>();
        if (s == null) {
            return l;
        }
        for (String part : s.split("[,;\\s]+")) {
            if (!part.trim().isEmpty()) {
                l.add(part.trim());
            }
        }
        return l;
    }

    /** @return the mechanism in upper case, or null when it is not one this client speaks */
    public static String mechanism(String s) {
        String m = s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
        return "PLAIN".equals(m) || "SCRAM-SHA-256".equals(m) || "SCRAM-SHA-512".equals(m) ? m : null;
    }

    /** @return what cannot work, empty when fine */
    public List<String> problems() {
        List<String> p = new ArrayList<String>();
        if (bootstrap.isEmpty()) {
            p.add("no bootstrap servers");
        }
        for (String b : bootstrap) {
            if (hostPort(b) == null) {
                p.add("bootstrap server '" + b + "' is not host:port");
            }
        }
        if (security == null) {
            p.add("security must be plaintext, ssl, sasl_plaintext or sasl_ssl");
        } else if (security.sasl()) {
            if (mechanism(saslMechanism) == null) {
                p.add("SASL mechanism must be PLAIN, SCRAM-SHA-256 or SCRAM-SHA-512");
            }
            if (username == null || username.isEmpty()) {
                p.add("SASL needs a user name");
            }
        }
        return Collections.unmodifiableList(p);
    }

    /** @return {host, port} of "host:port" ("[v6]:port" too), or null */
    public static String[] hostPort(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        int colon = t.lastIndexOf(':');
        if (colon <= 0 || colon == t.length() - 1) {
            return null;
        }
        String host = t.substring(0, colon);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        try {
            int port = Integer.parseInt(t.substring(colon + 1));
            if (port < 1 || port > 65535) {
                return null;
            }
            return new String[] {host, String.valueOf(port)};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
