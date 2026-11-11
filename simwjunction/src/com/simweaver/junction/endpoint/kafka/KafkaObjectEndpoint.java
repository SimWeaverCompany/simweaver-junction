package com.simweaver.junction.endpoint.kafka;

import com.simweaver.common.hla.codec.HValue;
import com.simweaver.common.hla.codec.HValueJson;
import com.simweaver.common.hla.codec.HlaCodec;
import com.simweaver.common.iem.Attribute;
import com.simweaver.common.iem.ClassAttribute;
import com.simweaver.common.iem.ClassParameter;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.common.iem.InteractionClass;
import com.simweaver.common.iem.ObjectClass;
import com.simweaver.common.iem.Parameter;
import com.simweaver.common.json.Json;
import com.simweaver.common.rest.JsonParser;
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
import com.simweaver.kafka.client.KafkaConsumer;
import com.simweaver.kafka.client.KafkaProducer;
import com.simweaver.kafka.client.KafkaSettings;
import com.simweaver.kafka.record.KafkaRecord;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * A gateway's side of Kafka topics carrying the objects and interactions of an HLA object model as
 * JSON (0.13.0), for IEM gateways. To the router it is an endpoint like a federation: objects in the
 * topic are discovered here, objects the router creates here are written to the topic.
 *
 * <p>Object records (key: the object's name):
 * <pre>
 * {"v":1,"kind":"object","op":"update","class":"HLAobjectRoot.BaseEntity.PhysicalEntity.Platform.Aircraft",
 *  "name":"GRIPEN 11","source":"stockholm/kafka","time":"2026-11-11T15:00:00.123Z",
 *  "attributes":{"Marking":{"MarkingEncodingType":"ASCII","MarkingData":"GRIPEN 11"}, ...}}
 * {"v":1,"kind":"object","op":"remove","class":"…","name":"GRIPEN 11","source":"…","time":"…"}
 * </pre>
 * With {@code endpoint.kafka.values=full} (the default) every update has all attributes the object
 * has had, so each record stands on its own (and a compacted topic holds every object's state);
 * {@code changed} writes only the attributes of the update. Interaction records (no key) go to
 * {@code endpoint.kafka.interactiontopic} (default: the same topic):
 * <pre>
 * {"v":1,"kind":"interaction","class":"HLAinteractionRoot.WeaponFire","source":"…","time":"…","parameters":{…}}
 * </pre>
 * Values follow {@link HValueJson}. Reading takes class names with or without the root, any subset of
 * attributes, and records from any producer; an update of an object not seen before discovers it.
 * The model comes from {@code endpoint.hla.foms} (default RPR FOM v2.0).
 */
public final class KafkaObjectEndpoint implements Endpoint, KafkaEndpoints.Diagnosable {

    private static final Logger LOG = Logger.getLogger(KafkaObjectEndpoint.class.getName());
    static final String CONTENT_TYPE = "application/json";

    /** An object the router created here. */
    private static final class Own {
        final ObjectClass cls;
        final String name;
        final byte[] key;
        final Map<String, Object> values = new LinkedHashMap<String, Object>();   // attribute name -> JSON value
        boolean written;

        Own(ObjectClass cls, String name) {
            this.cls = cls;
            this.name = name;
            this.key = name.getBytes(StandardCharsets.UTF_8);
        }
    }

    /** An object of the topic, discovered here. */
    private static final class Remote {
        final long key;
        final ObjectClass cls;
        volatile long seen;

        Remote(long key, ObjectClass cls) {
            this.key = key;
            this.cls = cls;
            this.seen = System.currentTimeMillis();
        }
    }

    private final EndpointConfig cfg;
    private final KafkaEndpointSettings kafka;
    private final String topic;
    private final String interactionTopic;
    private final String gatewayId;
    private final String group;
    private final String sourceText;
    private final byte[] sourceId = KafkaEndpoints.newSourceId().getBytes(StandardCharsets.UTF_8);
    private final List<KafkaRecord.Header> headers;
    private final EndpointStats stats = new EndpointStats();
    private final AtomicLong ownKeys = new AtomicLong();
    private final AtomicLong remoteKeys = new AtomicLong(1L << 40);
    private final Map<Long, Own> own = new ConcurrentHashMap<Long, Own>();
    private final Map<String, Remote> remote = new ConcurrentHashMap<String, Remote>();       // source|name -> remote
    private final AtomicLong malformed = new AtomicLong();
    private final AtomicLong unknownClasses = new AtomicLong();
    private final AtomicLong valueIssues = new AtomicLong();
    private final AtomicLong ownDropped = new AtomicLong();
    private final AtomicLong timedOut = new AtomicLong();
    private final ScheduledExecutorService callbacks;
    private volatile String lastIssue;
    private volatile InformationExchangeModel model;
    private volatile HlaCodec codec;
    private volatile EndpointState state = EndpointState.NEW;
    private volatile String stateMessage;
    private volatile EndpointListener listener;
    private volatile KafkaProducer producer;
    private volatile KafkaConsumer consumer;
    private volatile KafkaSettings settings;

    public KafkaObjectEndpoint(EndpointConfig cfg, String topic, String gatewayId) throws EndpointException {
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
        this.interactionTopic = kafka.getInteractionTopic().isEmpty() ? topic : kafka.getInteractionTopic();
        this.gatewayId = gatewayId;
        this.group = KafkaEndpoints.group(kafka, gatewayId, cfg.getId());
        this.sourceText = gatewayId + "/" + cfg.getId();
        this.headers = KafkaEndpoints.headers(sourceId, CONTENT_TYPE);
        this.callbacks = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "junction-kafka-" + gatewayId + "-" + cfg.getId());
            t.setDaemon(true);
            return t;
        });
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

    @Override
    public InformationExchangeModel model() {
        return model;
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

    private List<String> topics() {
        return topic.equals(interactionTopic) ? java.util.Collections.singletonList(topic) : Arrays.asList(topic, interactionTopic);
    }

    // ------------------------------------------------------------------ opening

    @Override
    public void open(EndpointListener l) throws EndpointException {
        this.listener = l;
        setState(EndpointState.CONNECTING, null);
        try {
            model = InformationExchangeModel.fromHlaEvolved(cfg.getJ2jFomPaths());
            codec = new HlaCodec(model);
        } catch (Exception e) {
            setState(EndpointState.FAILED, e.getMessage());
            throw new EndpointException(EndpointException.FOM_INVALID, "endpoint " + id() + ": " + e.getMessage(), e);
        }
        settings = kafka.client(gatewayId);
        try {
            KafkaEndpoints.check(cfg, settings, topics());
        } catch (EndpointException e) {
            setState(EndpointState.FAILED, e.getMessage());
            throw e;
        }
        if (kafka.isProduce()) {
            producer = KafkaEndpoints.producer(kafka, settings, "junction-kafka-" + gatewayId + "-" + cfg.getId() + "-producer");
        }
        if (kafka.getObjectTimeoutSecs() > 0) {
            callbacks.scheduleWithFixedDelay(this::expire, 1, 1, TimeUnit.SECONDS);
        }
        setState(EndpointState.JOINED, null);
    }

    /** Starts reading only now, when the router is ready for what the topic holds. */
    @Override
    public void declare(Interest publish, Interest subscribe) {
        if (consumer == null && kafka.isConsume() && settings != null) {
            consumer = KafkaEndpoints.consumer(kafka, settings, topics(), group, new KafkaConsumer.Listener() {
                @Override
                public void records(List<KafkaRecord> records) {
                    fromTopic(records);
                }
            }, "junction-kafka-" + gatewayId + "-" + cfg.getId() + "-consumer");
        }
        LOG.info("Junction " + gatewayId + "/" + id() + ": Kafka topic " + topics() + " at " + kafka.getBootstrap()
                + (consumer != null && !group.isEmpty() ? ", group " + group : "") + " (JSON objects)");
    }

    private void setState(EndpointState s, String message) {
        state = s;
        stateMessage = message;
        EndpointListener l = listener;
        if (l != null) {
            l.stateChanged(this, s, message);
        }
    }

    private void async(Runnable r) {
        try {
            callbacks.execute(r);
        } catch (RejectedExecutionException closed) {
            // closing
        }
    }

    private void issue(String m) {
        valueIssues.incrementAndGet();
        lastIssue = m;
    }

    // ------------------------------------------------------------------ from the router into the topic

    @Override
    public void createObject(long gwId, int objectClass, NameCandidates names) {
        ObjectClass cls = model == null ? null : model.getObjectClass(objectClass);
        EndpointListener l = listener;
        if (cls == null || producer == null) {
            if (l != null) {
                async(() -> l.registrationFailed(this, gwId, producer == null ? "this Kafka endpoint only reads (endpoint.kafka.direction=consume)"
                        : "the class is not in the model"));
            }
            return;
        }
        long key = ownKeys.incrementAndGet();
        String name = names == null || names.isAny() || names.getNames().isEmpty()
                ? "Junction-" + gatewayId + "-" + key : names.getNames().get(0);
        own.put(key, new Own(cls, name));
        stats.registeredOut.incrementAndGet();
        if (l != null) {
            async(() -> l.registered(this, gwId, key, name));          // never from the dispatcher's own call
        }
    }

    @Override
    public void updateObject(long key, int[] attributes, byte[][] values, byte[] tag) {
        Own o = own.get(key);
        KafkaProducer p = producer;
        if (o == null || p == null) {
            return;
        }
        Map<String, Object> changed = new LinkedHashMap<String, Object>();
        int octets = 0;
        for (int i = 0; i < attributes.length; i++) {
            Attribute a = model.getAttribute(attributes[i]);
            if (a == null) {
                continue;
            }
            try {
                Object j = HValueJson.toJson(codec.decode(a.getDataType(), values[i]));
                changed.put(a.getName(), j);
                octets += values[i].length;
            } catch (RuntimeException e) {
                issue(o.name + "." + a.getName() + ": not decoded: " + e.getMessage());
            }
        }
        if (changed.isEmpty()) {
            return;
        }
        Map<String, Object> out;
        synchronized (o) {
            o.values.putAll(changed);
            out = kafka.isFullState() ? new LinkedHashMap<String, Object>(o.values) : changed;
            o.written = true;
        }
        Json.JsonObject rec = Json.object().put("v", 1).put("kind", "object").put("op", "update")
                .put("class", o.cls.getQualifiedName()).put("name", o.name).put("source", sourceText)
                .put("time", Instant.now().toString()).put("attributes", out);
        if (send(topic, o.key, rec)) {
            stats.markOut(octets);
            stats.updatesOut.incrementAndGet();
        }
    }

    private boolean send(String t, byte[] key, Json.JsonObject rec) {
        KafkaProducer p = producer;
        return p != null && p.send(t, new KafkaRecord(key, rec.toString().getBytes(StandardCharsets.UTF_8), headers, System.currentTimeMillis()));
    }

    @Override
    public void deleteObject(long key, byte[] tag) {
        Own o = own.remove(key);
        if (o == null) {
            return;
        }
        Json.JsonObject rec = Json.object().put("v", 1).put("kind", "object").put("op", "remove")
                .put("class", o.cls.getQualifiedName()).put("name", o.name).put("source", sourceText).put("time", Instant.now().toString());
        if (send(topic, o.key, rec)) {
            stats.deletedOut.incrementAndGet();
        }
    }

    @Override
    public void sendInteraction(int interactionClass, int[] parameters, byte[][] values, byte[] tag) {
        InteractionClass ic = model == null ? null : model.getInteractionClass(interactionClass);
        if (ic == null || producer == null) {
            return;
        }
        Map<String, Object> ps = new LinkedHashMap<String, Object>();
        int octets = 0;
        for (int i = 0; i < parameters.length; i++) {
            Parameter p = model.getParameter(parameters[i]);
            if (p == null) {
                continue;
            }
            try {
                ps.put(p.getName(), HValueJson.toJson(codec.decode(p.getDataType(), values[i])));
                octets += values[i].length;
            } catch (RuntimeException e) {
                issue(ic.getName() + "." + p.getName() + ": not decoded: " + e.getMessage());
            }
        }
        Json.JsonObject rec = Json.object().put("v", 1).put("kind", "interaction").put("class", ic.getQualifiedName())
                .put("source", sourceText).put("time", Instant.now().toString()).put("parameters", ps);
        if (send(interactionTopic, null, rec)) {
            stats.markOut(octets);
            stats.interactionsOut.incrementAndGet();
        }
    }

    /** Kafka has no owner to ask: the next record brings the values. */
    @Override
    public void requestUpdate(long key, int[] attributes) {
    }

    // ------------------------------------------------------------------ from the topic to the router

    private void fromTopic(List<KafkaRecord> records) {
        EndpointListener l = listener;
        if (l == null || state == EndpointState.CLOSED) {
            return;
        }
        for (KafkaRecord r : records) {
            if (KafkaEndpoints.isOwn(r, sourceId)) {
                ownDropped.incrementAndGet();
                continue;
            }
            if (r.value == null) {
                continue;                                     // a tombstone (compacted topic): the remove record came before
            }
            Map<String, Object> m;
            try {
                m = JsonParser.parseObject(new String(r.value, StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                malformed.incrementAndGet();
                lastIssue = r.topic + "/" + r.partition + "@" + r.offset + ": not a JSON object: " + e.getMessage();
                continue;
            }
            String kind = String.valueOf(m.get("kind"));
            String source = r.header(KafkaEndpoints.SOURCE_HEADER) != null
                    ? new String(r.header(KafkaEndpoints.SOURCE_HEADER), StandardCharsets.UTF_8) : String.valueOf(m.get("source"));
            String producerText = m.get("source") instanceof String ? "kafka:" + m.get("source") : "kafka:" + r.topic;
            if ("interaction".equals(kind)) {
                interaction(l, m, producerText, r.value.length);
            } else if ("object".equals(kind)) {
                object(l, m, source, producerText, r.value.length);
            } else {
                malformed.incrementAndGet();
                lastIssue = r.topic + "/" + r.partition + "@" + r.offset + ": no \"kind\" object or interaction";
            }
        }
    }

    private ObjectClass objectClass(Object name) {
        if (!(name instanceof String)) {
            return null;
        }
        String n = (String) name;
        ObjectClass c = model.getObjectClass(n);
        if (c == null && !n.startsWith("HLAobjectRoot")) {
            c = model.getObjectClass("HLAobjectRoot." + n);
        }
        return c;
    }

    private InteractionClass interactionClass(Object name) {
        if (!(name instanceof String)) {
            return null;
        }
        String n = (String) name;
        InteractionClass c = model.getInteractionClass(n);
        if (c == null && !n.startsWith("HLAinteractionRoot")) {
            c = model.getInteractionClass("HLAinteractionRoot." + n);
        }
        return c;
    }

    private void object(EndpointListener l, Map<String, Object> m, String source, String producerText, int octets) {
        Object nameObj = m.get("name");
        if (!(nameObj instanceof String) || ((String) nameObj).isEmpty()) {
            malformed.incrementAndGet();
            lastIssue = "an object record without a name";
            return;
        }
        String name = (String) nameObj;
        String rk = source + "|" + name;
        Remote r = remote.get(rk);
        if ("remove".equals(m.get("op"))) {
            if (r != null) {
                remote.remove(rk);
                stats.removedIn.incrementAndGet();
                l.removed(this, r.key, null);
            }
            return;
        }
        ObjectClass cls = objectClass(m.get("class"));
        if (cls == null) {
            unknownClasses.incrementAndGet();
            lastIssue = "class " + m.get("class") + " is not in the model";
            return;
        }
        if (r != null && r.cls != cls) {                       // the same name with another class: a new object
            remote.remove(rk);
            stats.removedIn.incrementAndGet();
            l.removed(this, r.key, null);
            r = null;
        }
        if (r == null) {
            r = new Remote(remoteKeys.incrementAndGet(), cls);
            remote.put(rk, r);
            stats.discoveredIn.incrementAndGet();
            l.discovered(this, r.key, cls.getId(), name, producerText);
        }
        r.seen = System.currentTimeMillis();
        Object attrs = m.get("attributes");
        if (!(attrs instanceof Map)) {
            return;
        }
        List<Integer> ids = new ArrayList<Integer>();
        List<byte[]> vals = new ArrayList<byte[]>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) attrs).entrySet()) {
            String an = String.valueOf(e.getKey());
            ClassAttribute ca = cls.getAttribute(an);
            if (ca == null) {
                issue(cls.getName() + "." + an + ": no such attribute");
                continue;
            }
            Attribute a = ca.getAttribute();
            try {
                HValue v = HValueJson.fromJson(codec, a.getDataType(), e.getValue(), (p, msg) -> issue(name + "." + p + ": " + msg));
                vals.add(codec.encode(a.getDataType(), v));
                ids.add(a.getId());
            } catch (RuntimeException ex) {
                issue(name + "." + an + ": " + ex.getMessage());
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        int[] a = new int[ids.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = ids.get(i);
        }
        stats.markIn(octets);
        stats.updatesIn.incrementAndGet();
        l.reflected(this, r.key, a, vals.toArray(new byte[0][]), null, producerText);
    }

    private void interaction(EndpointListener l, Map<String, Object> m, String producerText, int octets) {
        InteractionClass ic = interactionClass(m.get("class"));
        if (ic == null) {
            unknownClasses.incrementAndGet();
            lastIssue = "interaction class " + m.get("class") + " is not in the model";
            return;
        }
        List<Integer> ids = new ArrayList<Integer>();
        List<byte[]> vals = new ArrayList<byte[]>();
        Object ps = m.get("parameters");
        if (ps instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) ps).entrySet()) {
                String pn = String.valueOf(e.getKey());
                ClassParameter cp = ic.getParameter(pn);
                if (cp == null) {
                    issue(ic.getName() + "." + pn + ": no such parameter");
                    continue;
                }
                Parameter p = cp.getParameter();
                try {
                    HValue v = HValueJson.fromJson(codec, p.getDataType(), e.getValue(), (path, msg) -> issue(ic.getName() + "." + path + ": " + msg));
                    vals.add(codec.encode(p.getDataType(), v));
                    ids.add(p.getId());
                } catch (RuntimeException ex) {
                    issue(ic.getName() + "." + pn + ": " + ex.getMessage());
                }
            }
        }
        int[] a = new int[ids.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = ids.get(i);
        }
        stats.markIn(octets);
        stats.interactionsIn.incrementAndGet();
        l.received(this, ic.getId(), a, vals.toArray(new byte[0][]), null, producerText);
    }

    /** Removes objects not heard of within {@code endpoint.kafka.objecttimeoutseconds}. */
    private void expire() {
        EndpointListener l = listener;
        long limit = System.currentTimeMillis() - kafka.getObjectTimeoutSecs() * 1000L;
        for (Map.Entry<String, Remote> e : new ArrayList<Map.Entry<String, Remote>>(remote.entrySet())) {
            if (e.getValue().seen < limit && remote.remove(e.getKey(), e.getValue())) {
                timedOut.incrementAndGet();
                stats.removedIn.incrementAndGet();
                if (l != null) {
                    l.removed(this, e.getValue().key, null);
                }
            }
        }
    }

    // ------------------------------------------------------------------ closing

    @Override
    public void close(boolean deleteObjects) {
        KafkaConsumer c = consumer;
        consumer = null;
        if (c != null) {
            c.close();
        }
        KafkaProducer p = producer;
        if (p != null && deleteObjects) {
            for (Long k : new ArrayList<Long>(own.keySet())) {
                Own o = own.get(k);
                if (o != null && o.written) {
                    deleteObject(k, null);                     // the readers remove them
                }
            }
        }
        producer = null;
        if (p != null) {
            p.close(2000);
        }
        callbacks.shutdown();
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
        Map<String, Object> d = KafkaEndpoints.diagnostics("objects", kafka, topics(), group, producer, consumer);
        d.put("values", kafka.isFullState() ? "full" : "changed");
        d.put("ownobjects", own.size());
        d.put("topicobjects", remote.size());
        d.put("owndropped", ownDropped.get());
        d.put("malformed", malformed.get());
        d.put("unknownclasses", unknownClasses.get());
        d.put("valueissues", valueIssues.get());
        d.put("timedout", timedOut.get());
        d.put("lastissue", lastIssue);
        return d;
    }
}
