package com.simweaver.common.rest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A small, dependency-free Server-Sent Events (SSE) hub for XrServer.
 *
 * Clients connect to the configured endpoint with a comma-separated "topics"
 * query parameter, for example /api/events?topics=123,456. Topics can be card
 * IDs or any other application-defined key.
 *
 * Publish JSON payloads with publish(topic, eventName, jsonData). A message is
 * delivered only to clients subscribed to that topic. Slow clients have a
 * bounded queue; if it fills, the connection is closed so EventSource can
 * reconnect. Applications should refresh current state after reconnecting if
 * missing an intermediate update would otherwise leave the UI stale.
 *
 * This class does not own or inspect application state. It only transports
 * events. All methods are compatible with Java 8 and use no third-party libs.
 */
public final class XrSseHub implements HttpHandler {
    private static final int DEFAULT_CLIENT_QUEUE_CAPACITY = 256;
    private static final long HEARTBEAT_SECONDS = 15L;
    private static final long DEFAULT_PATCH_BATCH_INTERVAL_MILLIS = 50L;
    /** Reconnect delay suggested to EventSource ("retry:" field) when a stream drops. */
    private static final long RETRY_MILLIS = 3000L;

    private final Set<Client> clients = new CopyOnWriteArraySet<>();
    private final int queueCapacity;
    private final long patchBatchIntervalMillis;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public XrSseHub() {
        this(DEFAULT_CLIENT_QUEUE_CAPACITY, DEFAULT_PATCH_BATCH_INTERVAL_MILLIS);
    }

    /** @param queueCapacity maximum queued non-coalescible events per client; must be positive */
    public XrSseHub(int queueCapacity) {
        this(queueCapacity, DEFAULT_PATCH_BATCH_INTERVAL_MILLIS);
    }

    /**
     * @param queueCapacity maximum queued non-coalescible events per client; must be positive
     * @param patchBatchIntervalMillis maximum interval before pending state patches are sent; must be positive
     */
    public XrSseHub(int queueCapacity, long patchBatchIntervalMillis) {
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        if (patchBatchIntervalMillis < 1) {
            throw new IllegalArgumentException("patchBatchIntervalMillis must be positive");
        }
        this.queueCapacity = queueCapacity;
        this.patchBatchIntervalMillis = patchBatchIntervalMillis;
    }

    /**
     * Publish a pre-serialized JSON payload to clients subscribed to topic.
     * eventName should be a simple SSE event name, such as "card-update".
     */
    public void publish(String topic, String eventName, String jsonData) {
        if (closed.get()) return;
        if (topic == null || topic.trim().isEmpty()) {
            throw new IllegalArgumentException("topic must not be empty");
        }
        String safeEventName = validateEventName(eventName);
        String payload = jsonData == null ? "null" : jsonData;
        SseEvent event = new SseEvent(topic, safeEventName, payload);
        for (Client client : clients) {
            if (client.topics.contains(topic)) {
                if (!client.queue.offer(event)) {
                    // Do not let a slow browser consume unbounded memory.
                    // Closing causes the browser's EventSource to reconnect.
                    client.close();
                }
            }
        }
    }

    /** Serialize an object using the built-in JSON serializer (Jsons) and publish it. */
    public void publishJson(String topic, String eventName, Object data) {
        publish(topic, eventName, Jsons.toJson(data));
    }

    /**
     * Publish changed fields for a stateful topic, such as a card ID. Updates are
     * coalesced per client, topic and field until the next batch flush. If the same
     * field changes several times within one interval, only its latest value is sent.
     * Different fields changed during the interval are merged rather than lost.
     *
     * Each SSE event is named "cards-changed" and its data is a JSON array like:
     * [{"id":"123","patch":{"speed":275,"altitude":1200}}].
     * Clients should merge each patch into their cached card state. A field explicitly
     * set to null is retained in the patch; omitted fields mean "not changed".
     *
     * Use publish()/publishJson() for events that must each be delivered individually.
     */
    public void publishPatch(String topic, Map<String, ?> changedFields) {
        if (closed.get()) return;
        if (topic == null || topic.trim().isEmpty()) {
            throw new IllegalArgumentException("topic must not be empty");
        }
        if (changedFields == null || changedFields.isEmpty()) return;
        // Serialize each value once, now: the caller may change its objects after this call
        // (the patch is written later on the client threads), and with several clients the
        // same value would otherwise be serialized once per client.
        Map<String, Object> frozen = null;
        for (Client client : clients) {
            if (client.topics.contains(topic) && !client.closed.get()) {
                if (frozen == null) frozen = freeze(changedFields);
                client.mergePatch(topic, frozen);
            }
        }
    }

    private static Map<String, Object> freeze(Map<String, ?> fields) {
        Map<String, Object> frozen = new LinkedHashMap<>();
        for (Map.Entry<String, ?> field : fields.entrySet()) {
            Object v = field.getValue();
            frozen.put(field.getKey(), (v == null || v instanceof XrRawJson) ? v : new XrRawJson(Jsons.toJson(v)));
        }
        return frozen;
    }

    /**
     * True if at least one connected client subscribes to topic. Lets publishers skip
     * building payloads nobody receives (e.g. per-object topics of off-screen objects).
     */
    public boolean hasSubscribers(String topic) {
        if (topic == null || closed.get()) return false;
        for (Client client : clients) {
            if (!client.closed.get() && client.topics.contains(topic)) return true;
        }
        return false;
    }

    /** Number of currently connected clients (useful for diagnostics). */
    public int getClientCount() {
        return clients.size();
    }

    /** Close all active SSE connections and reject new ones. */
    public void close() {
        if (closed.compareAndSet(false, true)) {
            for (Client client : clients) client.close();
        }
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (closed.get()) {
            sendError(exchange, 503, "Event stream is closed");
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET");
            sendError(exchange, 405, "Method Not Allowed");
            return;
        }

        Set<String> topics;
        try {
            topics = parseTopics(exchange.getRequestURI().getRawQuery());
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, e.getMessage());
            return;
        }
        if (topics.isEmpty()) {
            sendError(exchange, 400, "Specify one or more topics using ?topics=id1,id2");
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-transform");
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0); // chunked response; leave it open

        Client client = new Client(topics, queueCapacity);
        clients.add(client);
        final long intervalNanos = TimeUnit.MILLISECONDS.toNanos(patchBatchIntervalMillis);
        final long heartbeatNanos = TimeUnit.SECONDS.toNanos(HEARTBEAT_SECONDS);
        try (OutputStream out = exchange.getResponseBody()) {
            writeUtf8(out, ": connected\nretry: " + RETRY_MILLIS + "\n\n");
            out.flush();
            long now = System.nanoTime();
            long nextPatchFlush = now + intervalNanos;
            long lastWrite = now;
            while (!closed.get() && !client.closed.get()) {
                long waitNanos = Math.max(TimeUnit.MILLISECONDS.toNanos(1), nextPatchFlush - System.nanoTime());
                SseEvent event;
                try {
                    event = client.queue.poll(waitNanos, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                boolean wrote = false;
                if (event != null) {
                    writeEvent(out, event.eventName, event.jsonData);
                    wrote = true;
                }

                // Stateful patches are drained as a single JSON-array event per batch
                // interval - on a fixed schedule, so ordinary events arriving in between
                // do not flush (and thereby un-coalesce) the patches early.
                now = System.nanoTime();
                if (now - nextPatchFlush >= 0) {
                    nextPatchFlush = now + intervalNanos;
                    Map<String, Map<String, Object>> patches = client.drainPatches();
                    if (!patches.isEmpty()) {
                        java.util.List<Map<String, Object>> batch = new java.util.ArrayList<>();
                        for (Map.Entry<String, Map<String, Object>> entry : patches.entrySet()) {
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("id", entry.getKey());
                            item.put("patch", entry.getValue());
                            batch.add(item);
                        }
                        writeEvent(out, "cards-changed", Jsons.toJson(batch));
                        wrote = true;
                    }
                }

                if (wrote) {
                    lastWrite = now;
                } else if (now - lastWrite >= heartbeatNanos) {
                    // Heartbeat only after HEARTBEAT_SECONDS of silence (not every interval).
                    writeUtf8(out, ": keepalive\n\n");
                    lastWrite = now;
                    wrote = true;
                }
                if (wrote) out.flush();
            }
        } catch (IOException ignored) {
            // Normal when the browser closes the page or loses connectivity.
        } finally {
            client.close();
            clients.remove(client);
            exchange.close();
        }
    }

    private static void writeEvent(OutputStream out, String eventName, String jsonData) throws IOException {
        writeUtf8(out, "event: " + eventName + "\n");
        // SSE data fields are line-oriented. Split defensively for arbitrary payloads.
        String[] lines = jsonData.split("\\r?\\n", -1);
        for (String line : lines) writeUtf8(out, "data: " + line + "\n");
        writeUtf8(out, "\n");
    }

    private static Set<String> parseTopics(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) return Collections.emptySet();
        Set<String> result = new HashSet<>();
        for (String parameter : rawQuery.split("&")) {
            int equals = parameter.indexOf('=');
            String key = equals < 0 ? parameter : parameter.substring(0, equals);
            String value = equals < 0 ? "" : parameter.substring(equals + 1);
            key = decode(key);
            if (!"topics".equals(key)) continue;
            value = decode(value);
            for (String topic : value.split(",")) {
                String trimmed = topic.trim();
                if (!trimmed.isEmpty()) result.add(trimmed);
            }
        }
        return result;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String validateEventName(String eventName) {
        if (eventName == null || eventName.trim().isEmpty()) return "message";
        String value = eventName.trim();
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("eventName must not contain line breaks");
        }
        return value;
    }

    private static void writeUtf8(OutputStream out, String value) throws IOException {
        out.write(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static final class SseEvent {
        private final String topic;
        private final String eventName;
        private final String jsonData;

        private SseEvent(String topic, String eventName, String jsonData) {
            this.topic = topic;
            this.eventName = eventName;
            this.jsonData = jsonData;
        }
    }

    private static final class Client {
        private final Set<String> topics;
        private final BlockingQueue<SseEvent> queue;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        // topic -> changed field values; guarded by this object's monitor.
        private final Map<String, Map<String, Object>> pendingPatches = new LinkedHashMap<>();

        private Client(Set<String> topics, int capacity) {
            this.topics = Collections.unmodifiableSet(new HashSet<>(topics));
            this.queue = new ArrayBlockingQueue<>(capacity);
        }

        private synchronized void mergePatch(String topic, Map<String, ?> changedFields) {
            if (closed.get()) return;
            Map<String, Object> fields = pendingPatches.get(topic);
            if (fields == null) {
                fields = new LinkedHashMap<>();
                pendingPatches.put(topic, fields);
            }
            for (Map.Entry<String, ?> field : changedFields.entrySet()) {
                fields.put(field.getKey(), field.getValue());
            }
        }

        private synchronized Map<String, Map<String, Object>> drainPatches() {
            if (pendingPatches.isEmpty()) return Collections.emptyMap();
            Map<String, Map<String, Object>> drained = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Object>> entry : pendingPatches.entrySet()) {
                drained.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
            }
            pendingPatches.clear();
            return drained;
        }

        private void close() {
            closed.set(true);
            synchronized (this) {
                pendingPatches.clear();
            }
        }
    }
}
