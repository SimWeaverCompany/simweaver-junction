package com.simweaver.kafka.client;

import com.simweaver.kafka.KafkaException;
import com.simweaver.kafka.wire.Api;
import com.simweaver.kafka.wire.KReader;
import com.simweaver.kafka.wire.KWriter;
import com.simweaver.kafka.wire.KafkaErrors;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one user (a producer's sender, a consumer's thread) knows of a cluster: the brokers, the
 * partitions and leaders of the topics it uses (Metadata), and one connection per broker. Not thread
 * safe: each user has its own, and calls it from its one thread.
 */
public final class KafkaCluster implements AutoCloseable {

    /** A broker of the cluster. */
    public static final class Broker {
        public final int id;
        public final String host;
        public final int port;

        Broker(int id, String host, int port) {
            this.id = id;
            this.host = host;
            this.port = port;
        }

        @Override
        public String toString() {
            return host + ":" + port + " (node " + id + ")";
        }
    }

    /** A topic's partitions: leader node per partition (-1: none now). */
    public static final class TopicInfo {
        public final String name;
        public final int error;
        public final int[] leaders;

        TopicInfo(String name, int error, int[] leaders) {
            this.name = name;
            this.error = error;
            this.leaders = leaders;
        }

        public int partitions() {
            return leaders.length;
        }
    }

    private final KafkaSettings settings;
    private final Map<Integer, Broker> brokers = new LinkedHashMap<Integer, Broker>();
    private final Map<String, TopicInfo> topics = new HashMap<String, TopicInfo>();
    private final Map<Integer, BrokerConnection> connections = new HashMap<Integer, BrokerConnection>();
    private BrokerConnection bootstrap;
    private String clusterId;
    private int bootstrapIndex;
    private String lastProblem;

    public KafkaCluster(KafkaSettings settings) {
        this.settings = settings;
    }

    public KafkaSettings settings() {
        return settings;
    }

    /** @return the cluster id (Metadata v2+), or null before the first refresh */
    public String clusterId() {
        return clusterId;
    }

    public Collection<Broker> brokers() {
        return Collections.unmodifiableCollection(brokers.values());
    }

    /** @return what went wrong last when connecting, or null */
    public String lastProblem() {
        return lastProblem;
    }

    /** @return a connection to some broker: a known one, else the bootstrap servers in turn */
    public BrokerConnection any() throws KafkaException {
        for (BrokerConnection c : connections.values()) {
            if (!c.isClosed()) {
                return c;
            }
        }
        if (bootstrap != null && !bootstrap.isClosed()) {
            return bootstrap;
        }
        List<String> tried = new ArrayList<String>();
        for (Broker b : new ArrayList<Broker>(brokers.values())) {
            try {
                return connection(b.id);
            } catch (KafkaException e) {
                tried.add(e.getMessage());
            }
        }
        KafkaException last = null;
        for (int i = 0; i < settings.bootstrap.size(); i++) {
            String s = settings.bootstrap.get((bootstrapIndex + i) % settings.bootstrap.size());
            String[] hp = KafkaSettings.hostPort(s);
            if (hp == null) {
                continue;
            }
            try {
                bootstrap = BrokerConnection.open(hp[0], Integer.parseInt(hp[1]), -1, settings);
                bootstrapIndex = (bootstrapIndex + i) % settings.bootstrap.size();
                lastProblem = null;
                return bootstrap;
            } catch (KafkaException e) {
                last = e;
                tried.add(e.getMessage());
            }
        }
        bootstrapIndex++;
        lastProblem = tried.isEmpty() ? "no bootstrap servers" : String.join("; ", tried);
        if (last != null && last.code() != KafkaErrors.NETWORK_EXCEPTION && last.code() != -1) {
            throw new KafkaException(last.code(), lastProblem, last);
        }
        throw new KafkaException(KafkaErrors.NETWORK_EXCEPTION, "no Kafka broker reachable: " + lastProblem, last);
    }

    /** @return the connection to a broker by id (opened when needed) */
    public BrokerConnection connection(int nodeId) throws KafkaException {
        BrokerConnection c = connections.get(nodeId);
        if (c != null && !c.isClosed()) {
            return c;
        }
        Broker b = brokers.get(nodeId);
        if (b == null) {
            throw new KafkaException(KafkaErrors.LEADER_NOT_AVAILABLE, "Kafka broker " + nodeId + " is not known");
        }
        c = BrokerConnection.open(b.host, b.port, b.id, settings);
        connections.put(nodeId, c);
        return c;
    }

    /** @return a connection to a host and port the cluster named (a group coordinator) */
    public BrokerConnection connection(int nodeId, String host, int port) throws KafkaException {
        Broker known = brokers.get(nodeId);
        if (known == null || !known.host.equals(host) || known.port != port) {
            brokers.put(nodeId, new Broker(nodeId, host, port));
            BrokerConnection old = connections.remove(nodeId);
            if (old != null) {
                old.close();
            }
        }
        return connection(nodeId);
    }

    /**
     * Reads the metadata of these topics (creating them when the broker auto-creates topics).
     *
     * @return the topics' partitions and leaders
     */
    public Map<String, TopicInfo> refresh(Collection<String> names, boolean allowAutoCreate) throws KafkaException {
        BrokerConnection c = any();
        int v = c.version(Api.METADATA);
        KWriter w = new KWriter();
        if (names == null) {
            w.count(-1);
        } else {
            w.count(names.size());
            for (String n : names) {
                w.string(n);
            }
        }
        if (v >= 4) {
            w.bool(allowAutoCreate);
        }
        KReader r = c.request(Api.METADATA, v, w);
        if (v >= 3) {
            r.int32();                                       // throttle
        }
        int nb = r.count();
        Map<Integer, Broker> bs = new LinkedHashMap<Integer, Broker>();
        for (int i = 0; i < nb; i++) {
            int id = r.int32();
            String host = r.string();
            int port = r.int32();
            r.string();                                      // rack (v1+)
            bs.put(id, new Broker(id, host, port));
        }
        if (v >= 2) {
            clusterId = r.string();
        }
        r.int32();                                           // controller (v1+)
        int nt = r.count();
        Map<String, TopicInfo> got = new LinkedHashMap<String, TopicInfo>();
        for (int i = 0; i < nt; i++) {
            int err = r.int16();
            String name = r.string();
            r.bool();                                        // internal (v1+)
            int np = r.count();
            int[] leaders = new int[np];
            java.util.Arrays.fill(leaders, -1);
            List<int[]> parts = new ArrayList<int[]>();
            for (int k = 0; k < np; k++) {
                int perr = r.int16();
                int pid = r.int32();
                int leader = r.int32();
                if (v >= 7) {
                    r.int32();                               // leader epoch
                }
                r.int32s();                                  // replicas
                r.int32s();                                  // isr
                if (v >= 5) {
                    r.int32s();                              // offline replicas
                }
                parts.add(new int[] {pid, perr == KafkaErrors.NONE ? leader : -1});
            }
            int max = -1;
            for (int[] p : parts) {
                max = Math.max(max, p[0]);
            }
            leaders = new int[max + 1];
            java.util.Arrays.fill(leaders, -1);
            for (int[] p : parts) {
                if (p[0] >= 0) {
                    leaders[p[0]] = p[1];
                }
            }
            TopicInfo t = new TopicInfo(name, err, leaders);
            got.put(name, t);
            if (err == KafkaErrors.NONE && leaders.length > 0) {
                topics.put(name, t);
            }
        }
        // brokers that left: close their connections
        for (Integer id : new ArrayList<Integer>(connections.keySet())) {
            if (!bs.containsKey(id)) {
                connections.remove(id).close();
            }
        }
        brokers.clear();
        brokers.putAll(bs);
        return got;
    }

    /** @return the partitions of a topic as last read, or null */
    public TopicInfo topic(String name) {
        return topics.get(name);
    }

    /** Forgets a topic's leaders (after a leader moved), so the next use reads them again. */
    public void invalidate(String topic) {
        topics.remove(topic);
    }

    /** Closes a broker's connection (it failed). */
    public void drop(int nodeId) {
        BrokerConnection c = connections.remove(nodeId);
        if (c != null) {
            c.close();
        }
        if (nodeId < 0 && bootstrap != null) {
            bootstrap.close();
            bootstrap = null;
        }
    }

    @Override
    public void close() {
        for (BrokerConnection c : connections.values()) {
            c.close();
        }
        connections.clear();
        if (bootstrap != null) {
            bootstrap.close();
            bootstrap = null;
        }
    }
}
