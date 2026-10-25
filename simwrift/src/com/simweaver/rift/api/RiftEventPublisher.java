package com.simweaver.rift.api;

import com.simweaver.common.rest.Jsons;
import com.simweaver.common.rest.XrSseHub;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Publishes the live events of one datasource to the {@link XrSseHub} of
 * com.simweaver.common.rest, following the datasource page contract (section 7) and
 * docs/xrest-eventhub-examples.md (the hub examples; written when the hub was still xrest).
 *
 * One instance per datasource. Call it from the RiftDatabase listener:
 *
 * <pre>
 *   objectDiscovered(o)               -> ds:{dsid}:objects {iid: listView}, ds:{dsid}:classes {class: n}
 *   attributesUpdated(o, changed)     -> obj:{dsid}:{iid} {attr: full, @rev, @lastupdated, @updatecount}
 *                                        (+ ds:{dsid}:objects when a list attribute changed)
 *   objectRemoved(o, seq)             -> ds:{dsid}:objects {iid: null}, obj:... {@state: removed}, classes
 *   relatedChanged(o, related)        -> obj:{dsid}:{iid} {@related: {...}}
 *   interactionReceived(ix)           -> ds:{dsid}:interactions event "interaction" (capped per second)
 *   tick()                            -> ds:{dsid} event "interaction.stats" (call once per second)
 *   stateChanged(state, message)      -> ds:{dsid} event "datasource.state"
 *   datasourceRemoved()               -> ds:{dsid} event "datasource.removed"
 * </pre>
 *
 * Threading: meant for the single RiftDatabase worker thread, but safe to call from
 * several threads (the class counts are concurrent and the interaction window is locked).
 * Payloads for a topic are only built when someone subscribes to it
 * ({@link XrSseHub#hasSubscribers}), so off-screen objects cost a map lookup per update.
 *
 * Class counts: the publisher counts objects per class from the discovered / removed calls,
 * so every discovery and removal must go through it (or call {@link #resetClassCounts}).
 */
public final class RiftEventPublisher {

    /** Default cap of interaction events per datasource and second (contract 8.3). */
    public static final int DEFAULT_MAX_INTERACTIONS_PER_SECOND = 50;

    private final XrSseHub hub;
    private final String dsid;
    private final RiftJson json;
    private final int maxInteractionsPerSecond;

    private final String topicDs, topicObjects, topicClasses, topicInteractions;

    private final ConcurrentHashMap<String, Integer> classCounts = new ConcurrentHashMap<>();
    /** Objects with computed attributes (e.g. DRSpatial), refreshed by tick() for pages showing them. */
    private final ConcurrentHashMap<String, RiftObjectView> computedObjects = new ConcurrentHashMap<>();

    // interaction window, guarded by windowLock
    private final Object windowLock = new Object();
    private final Map<String, Integer> receivedInWindow = new TreeMap<>();
    private int sentInWindow;
    private int droppedInWindow;
    private long windowStartNanos = System.nanoTime();

    public RiftEventPublisher(XrSseHub hub, String dsid, RiftJson json) {
        this(hub, dsid, json, DEFAULT_MAX_INTERACTIONS_PER_SECOND);
    }

    public RiftEventPublisher(XrSseHub hub, String dsid, RiftRefResolver resolver, RiftListProfiles profiles) {
        this(hub, dsid, new RiftJson(profiles, resolver), DEFAULT_MAX_INTERACTIONS_PER_SECOND);
    }

    public RiftEventPublisher(XrSseHub hub, String dsid, RiftJson json, int maxInteractionsPerSecond) {
        if (hub == null) throw new IllegalArgumentException("hub must not be null");
        if (dsid == null || dsid.isEmpty()) throw new IllegalArgumentException("dsid must not be empty");
        if (maxInteractionsPerSecond < 0) throw new IllegalArgumentException("maxInteractionsPerSecond < 0");
        this.hub = hub;
        this.dsid = dsid;
        this.json = json != null ? json : new RiftJson(null, null);
        this.maxInteractionsPerSecond = maxInteractionsPerSecond;
        this.topicDs = RiftTopics.ds(dsid);
        this.topicObjects = RiftTopics.objects(dsid);
        this.topicClasses = RiftTopics.classes(dsid);
        this.topicInteractions = RiftTopics.interactions(dsid);
    }

    public String dsid() {
        return dsid;
    }

    public RiftJson json() {
        return json;
    }

    // ------------------------------------------------------------------ objects

    /** A new object instance was discovered (its first reflect may follow later). */
    public void objectDiscovered(RiftObjectView o) {
        String cq = o.classNameQ();
        int n = classCounts.merge(cq, 1, Integer::sum);
        if (hub.hasSubscribers(topicObjects)) {
            hub.publishPatch(topicObjects, RiftJson.single(o.externalId(), json.listView(o)));
        }
        publishClassCount(cq, n);
    }

    /**
     * Attributes of an object were updated. {@code changed} holds the names of the attributes
     * in this update (all of them already stored in {@code o.attributes()}).
     */
    public void attributesUpdated(RiftObjectView o, Collection<String> changed) {
        if (changed == null || changed.isEmpty()) return;
        String iid = o.externalId();
        String topic = RiftTopics.object(dsid, iid);
        if (hub.hasSubscribers(topic)) {
            Map<String, ? extends RiftMemberView> attrs = o.attributes();
            Map<String, Object> patch = new LinkedHashMap<>();
            for (String name : changed) {
                RiftMemberView a = attrs.get(name);
                if (a != null) patch.put(name, json.member(a, true));
            }
            if (o.hasComputedAttributes()) putComputed(patch, o, System.currentTimeMillis());
            putMeta(patch, o.rev(), o.lastUpdated(), o.updateCount());
            hub.publishPatch(topic, patch);
        }
        if (o.hasComputedAttributes()) computedObjects.put(iid, o);
        if (hub.hasSubscribers(topicObjects)
                && (changed.contains(RiftJson.LABEL_ATTRIBUTE)                // the row label
                    || json.profiles().affectsListView(o.classNameQ(), changed, o.attributes().keySet()))) {
            hub.publishPatch(topicObjects, RiftJson.single(iid, json.listView(o)));
        }
    }

    /**
     * An object was removed from the federation (or the datasource resigned).
     *
     * @param seq the seq of the removal (becomes the object's last rev)
     */
    public void objectRemoved(RiftObjectView o, long seq) {
        String iid = o.externalId();
        computedObjects.remove(iid);
        String cq = o.classNameQ();
        Integer n = classCounts.computeIfPresent(cq, (k, v) -> v > 1 ? v - 1 : null);

        if (hub.hasSubscribers(topicObjects)) {
            Map<String, Object> gone = new HashMap<>();
            gone.put(iid, null);                                    // explicit null = removed
            hub.publishPatch(topicObjects, gone);
        }
        String topic = RiftTopics.object(dsid, iid);
        if (hub.hasSubscribers(topic)) {
            Map<String, Object> marker = new LinkedHashMap<>();
            marker.put("@state", "removed");
            marker.put("@rev", seq);
            marker.put("@lastupdated", RiftJson.iso(Instant.now()));
            hub.publishPatch(topic, marker);
        }
        publishClassCount(cq, n == null ? 0 : n);
    }

    /** The relations of an object changed (contract 4.2 "related" block). */
    public void relatedChanged(RiftObjectView o, Map<String, Object> related) {
        String topic = RiftTopics.object(dsid, o.externalId());
        if (!hub.hasSubscribers(topic)) return;
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("@related", related);
        putMeta(patch, o.rev(), o.lastUpdated(), o.updateCount());
        hub.publishPatch(topic, patch);
    }

    private void publishClassCount(String classNameQ, int count) {
        if (hub.hasSubscribers(topicClasses)) {
            hub.publishPatch(topicClasses, RiftJson.single(classNameQ, count));
        }
    }

    private static void putMeta(Map<String, Object> patch, long rev, Instant lastUpdated, long updateCount) {
        patch.put("@rev", rev);
        patch.put("@lastupdated", RiftJson.iso(lastUpdated));
        patch.put("@updatecount", updateCount);
    }

    /** Current objects per class (removed ones not counted); also handy for GET .../classes. */
    public Map<String, Integer> classCounts() {
        return Collections.unmodifiableMap(new TreeMap<>(classCounts));
    }

    /**
     * Replaces the class counts, e.g. after rebuilding the database, and publishes them.
     * Classes that disappear are published with 0.
     */
    public void resetClassCounts(Map<String, Integer> counts) {
        Map<String, Object> patch = new LinkedHashMap<>();
        for (String cq : classCounts.keySet()) {
            if (counts == null || !counts.containsKey(cq)) patch.put(cq, 0);
        }
        classCounts.clear();
        if (counts != null) {
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                if (e.getValue() != null && e.getValue() > 0) {
                    classCounts.put(e.getKey(), e.getValue());
                    patch.put(e.getKey(), e.getValue());
                }
            }
        }
        if (!patch.isEmpty() && hub.hasSubscribers(topicClasses)) hub.publishPatch(topicClasses, patch);
    }

    // ------------------------------------------------------------------ interactions

    /**
     * An interaction was received (and stored in the ring buffer). Sent as one "interaction"
     * event with shallow parameters, unless this second's cap is reached; then it is only
     * counted as dropped in the next "interaction.stats".
     */
    public void interactionReceived(RiftInteractionView ix) {
        boolean send;
        synchronized (windowLock) {
            long now = System.nanoTime();
            // tick() not called for a while: roll the window here so the cap does not stick
            if (now - windowStartNanos >= 1_500_000_000L) rollWindow(now);
            receivedInWindow.merge(ix.classNameQ(), 1, Integer::sum);
            send = sentInWindow < maxInteractionsPerSecond;
            if (send) sentInWindow++;
            else droppedInWindow++;
        }
        if (send && hub.hasSubscribers(topicInteractions)) {
            hub.publish(topicInteractions, "interaction", Jsons.toJson(json.interaction(ix, false)));
        }
    }

    /**
     * Call once per second (ScheduledExecutorService or a timer event on the database queue).
     * Publishes "interaction.stats" for the past window when anything was received or dropped,
     * and opens a new window.
     */
    public void tick() {
        synchronized (windowLock) {
            rollWindow(System.nanoTime());
        }
        publishComputed(System.currentTimeMillis());
    }

    /**
     * Pushes the computed attributes (e.g. DRSpatial) of the objects that a page shows, so a
     * dead-reckoned position keeps moving between updates. The patch carries no "@rev": the
     * object did not change, only time passed.
     */
    void publishComputed(long nowMillis) {
        for (RiftObjectView o : computedObjects.values()) {
            if (o.removed()) {
                computedObjects.remove(o.externalId(), o);
                continue;
            }
            String topic = RiftTopics.object(dsid, o.externalId());
            if (!hub.hasSubscribers(topic)) continue;
            Map<String, Object> patch = new LinkedHashMap<>();
            putComputed(patch, o, nowMillis);
            if (!patch.isEmpty()) hub.publishPatch(topic, patch);
        }
    }

    private void putComputed(Map<String, Object> patch, RiftObjectView o, long nowMillis) {
        for (RiftMemberView c : o.computedAttributes(nowMillis)) patch.put(c.name(), json.member(c, true));
    }

    private void rollWindow(long now) {    // windowLock held
        if (!receivedInWindow.isEmpty() || droppedInWindow > 0) {
            double seconds = Math.max(0.1, Math.round((now - windowStartNanos) / 1e8) / 10.0);
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("window", seconds);
            stats.put("counts", new LinkedHashMap<>(receivedInWindow));   // all received, per class
            stats.put("dropped", droppedInWindow);
            if (hub.hasSubscribers(topicDs)) hub.publish(topicDs, "interaction.stats", Jsons.toJson(stats));
        }
        receivedInWindow.clear();
        sentInWindow = 0;
        droppedInWindow = 0;
        windowStartNanos = now;
    }

    // ------------------------------------------------------------------ datasource

    /**
     * The datasource state changed, e.g. "JOINED", "RECONNECTING", "FAILED".
     *
     * @param message explanation for the user, or null when all is well
     */
    public void stateChanged(String state, String message) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("state", state);
        d.put("statemessage", message);
        hub.publish(topicDs, "datasource.state", Jsons.toJson(d));
    }

    /** The datasource was removed (after DELETE /api/datasources/{id}); the page goes back to the overview. */
    public void datasourceRemoved() {
        hub.publish(topicDs, "datasource.removed", "{}");
    }
}
