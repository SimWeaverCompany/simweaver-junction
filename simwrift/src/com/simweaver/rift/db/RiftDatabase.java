package com.simweaver.rift.db;

import com.simweaver.common.hla.decoders.HlaDataDecoder;
import com.simweaver.common.hla.decoders.RawValues;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.rift.api.RiftRefResolver;
import com.simweaver.rift.api.RiftRelation;
import com.simweaver.rift.api.RiftRelations;
import com.simweaver.rift.api.ValueNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * In-memory store of one Rift datasource: object instances, their latest attribute values,
 * the reference indexes and a ring buffer of recent interactions.
 *
 * <h2>Writing (datasource side)</h2>
 * The datasource pushes RTI callbacks with {@link #discoverInstance}, {@link #reflectObjectUpdate},
 * {@link #removeInstance} and {@link #reflectInteractionEvent}. These never block and never
 * throw on the RTI callback thread: they stamp the receive time, queue the event and return.
 *
 * <h2>Threading</h2>
 * <ul>
 *   <li><b>One worker thread</b> ({@code rift-db-<name>}) applies all events from one FIFO queue,
 *       so changes happen exactly in RTI callback order: a reflect after a discover always finds
 *       its instance, a remove always comes after the updates before it, and a newer value is
 *       never overwritten by an older one.</li>
 *   <li><b>Decoding runs ahead in parallel</b> on a small pool ({@code rift-decode-<name>-n}).
 *       Each queued update carries its decode task; the worker waits for the result of the
 *       event at the head of the queue, so decoding is parallel but applying stays ordered.
 *       With 0 decode threads the worker decodes itself.</li>
 *   <li>Readers (REST, SSE) never lock: instances are published with volatile fields and an
 *       immutable attribute map that is replaced on each update; the indexes are concurrent
 *       maps. {@link RiftDatabaseListener}s run on the worker thread after each change.</li>
 *   <li>The queue is unbounded: blocking the RTI callback thread is worse than memory growth.
 *       Watch {@link #getQueueHighWaterMark()}.</li>
 * </ul>
 *
 * <h2>Ids and seq</h2>
 * Instance ids are allocated synchronously in {@link #discoverInstance} (so the federate
 * ambassador can map the object handle to the id at once), start at 1 and are never reused.
 * {@link #getSeq()} grows by one for every applied change (contract 1); an instance's rev is the
 * seq of its last change.
 */
public final class RiftDatabase implements AutoCloseable {

    /** "No instance": returned by {@link #discoverInstance} when the database is closed. */
    public static final int NO_ID = 0;

    public static final int DEFAULT_INTERACTION_BUFFER = 10_000;
    public static final long DEFAULT_REMOVED_RETENTION_MILLIS = 30_000;
    public static final int DEFAULT_MAX_REMOVED = 1_000;
    public static final int DEFAULT_INCOMING_CAP = 100;

    /** Identity attributes of RPR v2/v3 (contract 4.1): their EntityIdentifierStruct is the object's own key. */
    public static final List<String> RPR_IDENTITY_ATTRIBUTES = Collections.unmodifiableList(Arrays.asList(
            "BaseEntity.EntityIdentifier", "Minefield.MinefieldIdentifier", "EnvironmentObject.ObjectIdentifier",
            "EnvironmentProcess.ProcessIdentifier", "GriddedData.GridIdentifier"));

    public enum State { RUNNING, STOPPING, STOPPED, FAILED }

    private static final Logger LOG = Logger.getLogger(RiftDatabase.class.getName());
    private static final int MAX_REF_DEPTH = 16;
    private static final int MAX_REFS_PER_ATTRIBUTE = 1_000;
    private static final long TICK_MILLIS = 1_000;

    private final String name;
    private final Thread worker;
    private final ThreadPoolExecutor decodePool;              // null = decode on the worker
    private final LinkedBlockingQueue<Event> queue = new LinkedBlockingQueue<>();
    private final Object lifecycleLock = new Object();
    private final CopyOnWriteArrayList<RiftDatabaseListener> listeners = new CopyOnWriteArrayList<>();

    private volatile State state = State.RUNNING;
    private volatile RiftValueDecoder valueDecoder;
    private volatile InformationExchangeModel iem;
    private volatile IntFunction<String> interactionClassNames;
    private volatile java.util.function.BiFunction<String, List<RiftParameter>, String> interactionSummary =
            RiftInteractionSummaries.DEFAULT;
    private volatile Map<String, List<String>> identityRules = rules(RPR_IDENTITY_ATTRIBUTES);
    private volatile long removedRetentionMillis = DEFAULT_REMOVED_RETENTION_MILLIS;
    private volatile int maxRemoved = DEFAULT_MAX_REMOVED;

    private final AtomicInteger nextId = new AtomicInteger(1);
    private final AtomicLong seq = new AtomicLong();

    // ---- state, written by the worker only, readable by anyone
    private final ConcurrentHashMap<Integer, RiftInstance> instances = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RiftInstance> byName = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RiftInstance> byEntityId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<RiftEdge>> incoming = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> classCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, String> interactionNameCache = new ConcurrentHashMap<>();
    private final InteractionRing interactions;
    private final ArrayDeque<RiftInstance> removedOrder = new ArrayDeque<>();     // worker only

    // ---- statistics
    private final AtomicInteger queueHighWater = new AtomicInteger();
    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong decodeErrors = new AtomicLong();
    private final AtomicLong unknownInstance = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong listenerErrors = new AtomicLong();
    private final AtomicLong emptyUpdates = new AtomicLong();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    /** Database named "rift" with the default number of decode threads. */
    public RiftDatabase() {
        this("rift");
    }

    /** @param name used in thread names and log messages, e.g. the datasource name */
    public RiftDatabase(String name) {
        this(name, defaultDecodeThreads(), DEFAULT_INTERACTION_BUFFER);
    }

    /**
     * @param decodeThreads    threads decoding ahead of the worker; 0 = the worker decodes
     * @param interactionBuffer interactions kept in the ring buffer (contract 8.2)
     */
    public RiftDatabase(String name, int decodeThreads, int interactionBuffer) {
        if (decodeThreads < 0) throw new IllegalArgumentException("decodeThreads < 0");
        if (interactionBuffer < 1) throw new IllegalArgumentException("interactionBuffer < 1");
        this.name = name == null || name.isEmpty() ? "rift" : name;
        this.interactions = new InteractionRing(interactionBuffer);
        if (decodeThreads > 0) {
            AtomicInteger n = new AtomicInteger();
            decodePool = new ThreadPoolExecutor(decodeThreads, decodeThreads, 0, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(), r -> {
                        Thread t = new Thread(r, "rift-decode-" + this.name + "-" + n.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    });
        } else {
            decodePool = null;
        }
        worker = new Thread(this::runWorker, "rift-db-" + this.name);
        worker.setDaemon(true);
        worker.start();
    }

    /** min(4, cores - 1), at least 1. */
    public static int defaultDecodeThreads() {
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
    }

    // ================================================================== configuration

    public void setInformationExchangeModel(InformationExchangeModel iem) {
        this.iem = iem;
    }

    public InformationExchangeModel getInformationExchangeModel() {
        return iem;
    }

    /** Decode with the HLA decoder (one decode per update). May be set or replaced at any time. */
    public void setDecoder(HlaDataDecoder decoder) {
        this.valueDecoder = decoder == null ? null : RiftValueDecoder.of(decoder);
    }

    /** Decode with any {@link RiftValueDecoder} (tests, DIS). */
    public void setValueDecoder(RiftValueDecoder decoder) {
        this.valueDecoder = decoder;
    }

    /**
     * Maps an interaction class IEM id to its qualified name ("HLAinteractionRoot.WeaponFire"),
     * e.g. {@code id -> iem.<your lookup>(id)}. Results are cached. Without it, interactions are
     * named "HLAinteractionRoot.Interaction&lt;id&gt;".
     */
    public void setInteractionClassNames(IntFunction<String> names) {
        this.interactionClassNames = names;
        interactionNameCache.clear();
    }

    /**
     * One-line summary per interaction for the log, from the class name and the decoded
     * parameters; null = none. Default {@link RiftInteractionSummaries#DEFAULT} (Link 16).
     */
    public void setInteractionSummary(java.util.function.BiFunction<String, List<RiftParameter>, String> summary) {
        this.interactionSummary = summary;
    }

    /**
     * Identity attributes, as "Class.Attribute" where Class is one segment of the qualified class
     * name ("BaseEntity.EntityIdentifier" applies to every BaseEntity subclass). Their value is
     * the object's own key in the entity-id index, not a reference. Default:
     * {@link #RPR_IDENTITY_ATTRIBUTES}. Applies to later updates.
     */
    public void setIdentityAttributes(Collection<String> classDotAttribute) {
        this.identityRules = rules(classDotAttribute);
    }

    /** How long removed instances stay readable (contract 9; default 30 s), and how many at most. */
    public void setRemovedRetention(long millis, int maxRemovedInstances) {
        if (millis < 0 || maxRemovedInstances < 0) throw new IllegalArgumentException("negative retention");
        this.removedRetentionMillis = millis;
        this.maxRemoved = maxRemovedInstances;
    }

    public void addListener(RiftDatabaseListener listener) {
        if (listener != null) listeners.addIfAbsent(listener);
    }

    public void removeListener(RiftDatabaseListener listener) {
        listeners.remove(listener);
    }

    public String getName() {
        return name;
    }

    // ================================================================== datasource side (RTI callback thread)

    /**
     * A new object instance was discovered (or registered). Allocates and returns the instance id
     * at once; the instance becomes visible when the worker applies the event.
     *
     * @param className qualified class name, e.g. "HLAobjectRoot.BaseEntity.PhysicalEntity.Platform.Aircraft"
     * @param objectName the HLA object instance name
     * @return the database id, or {@link #NO_ID} when the database is closed (or after 2^31 - 1
     *         discoveries; ids are never reused)
     */
    public Integer discoverInstance(Integer iemClassId, String className, String objectName, String producingFederateName) {
        long now = System.currentTimeMillis();
        if (state != State.RUNNING) {
            rejected.incrementAndGet();
            return NO_ID;
        }
        int id = nextId.getAndIncrement();
        if (id <= 0) {                                   // 2^31 discoveries: sticky, never reuse ids
            nextId.set(Integer.MIN_VALUE);
            if (rejected.getAndIncrement() == 0) LOG.severe("RiftDatabase " + name + ": instance ids exhausted");
            return NO_ID;
        }
        String cls = qualifiedObjectClass(className);
        enqueue(new DiscoverEvent(now, new RiftInstance(objectName, id, iemClassId, cls, producingFederateName, now)));
        return id;
    }

    /**
     * The instance was removed from the federation (deleted, or out of interest). It stays
     * readable as removed for the retention time.
     */
    public void removeInstance(Integer dbInstanceId) {
        long now = System.currentTimeMillis();
        if (dbInstanceId == null) return;
        if (state != State.RUNNING) {
            rejected.incrementAndGet();
            return;
        }
        enqueue(new RemoveEvent(now, dbInstanceId));
    }

    /**
     * An attribute update was received.
     *
     * @param dbAttributes the received attributes, one RiftAttribute each (with the raw octets); null
     *                     or empty = build them from {@code raw} (one per captured value, named
     *                     from the FOM)
     * @param raw          the same update as captured by the binding, for the decoder
     */
    public void reflectObjectUpdate(Integer dbInstanceId, ArrayList<RiftAttribute> dbAttributes, RawValues raw) {
        long now = System.currentTimeMillis();
        if (dbInstanceId == null) return;
        boolean given = dbAttributes != null && !dbAttributes.isEmpty();
        if (!given && (raw == null || raw.size() == 0)) {
            emptyUpdates.incrementAndGet();
            warnOnce("empty", "reflectObjectUpdate for o" + dbInstanceId + " without attributes and without raw values: ignored");
            return;
        }
        if (state != State.RUNNING) {
            rejected.incrementAndGet();
            return;
        }
        // the caller may reuse its list; without attributes the decode task fills this list from raw
        List<RiftAttribute> attrs = given ? new ArrayList<>(dbAttributes) : new ArrayList<>();
        enqueue(new UpdateEvent(now, dbInstanceId, attrs,
                decodeTask(() -> given ? decodeAttributes(raw, attrs) : decodeCaptured(raw, attrs))));
    }

    /**
     * An interaction was received.
     *
     * @param interactionId IEM id of the interaction class
     */
    public void reflectInteractionEvent(Integer interactionId, ArrayList<RiftParameter> parameters,
                                        String producingFederate, byte[] userSuppliedTag) {
        long now = System.currentTimeMillis();
        if (state != State.RUNNING) {
            rejected.incrementAndGet();
            return;
        }
        List<RiftParameter> params = parameters == null ? new ArrayList<>() : new ArrayList<>(parameters);
        enqueue(new InteractionEvent(now, interactionId, params, producingFederate, userSuppliedTag,
                decodeTask(() -> decodeParameters(interactionId, params, now))));
    }

    /**
     * Keeps at most {@code threshold} removed instances (oldest purged first). Runs on the worker;
     * removed instances are also purged automatically (see {@link #setRemovedRetention}).
     */
    public void cleanupRemoved(int threshold) {
        int keep = Math.max(0, threshold);
        if (state == State.RUNNING) enqueue(new TaskEvent(() -> purgeRemoved(System.currentTimeMillis(), keep)));
    }

    // ================================================================== reading (any thread)

    /** Seq of the last applied change; send it with every snapshot (contract 1). */
    public long getSeq() {
        return seq.get();
    }

    public RiftInstance getInstance(int dbId) {
        return instances.get(dbId);
    }

    /** By external id "o1042"; null if unknown or purged. */
    public RiftInstance getInstance(String externalId) {
        int id = RiftInstance.parseExternalId(externalId);
        return id == NO_ID ? null : instances.get(id);
    }

    /** All instances (active and recently removed), sorted by id. Read {@link #getSeq()} first. */
    public List<RiftInstance> getInstances() {
        return getInstances(null);
    }

    /** Instances matching the filter (null = all), sorted by id. */
    public List<RiftInstance> getInstances(Predicate<? super RiftInstance> filter) {
        List<RiftInstance> out = new ArrayList<>(instances.size());
        for (RiftInstance i : instances.values()) if (filter == null || filter.test(i)) out.add(i);
        out.sort(Comparator.comparingInt(RiftInstance::getDbId));
        return out;
    }

    /** Active (not removed) instances, sorted by id: the rows for GET …/objects. */
    public List<RiftInstance> getActiveInstances() {
        return getInstances(i -> !i.isRemoved());
    }

    /** Number of active (not removed) instances. */
    public int getInstanceCount() {
        int n = 0;
        for (Integer c : classCounts.values()) n += c;
        return n;
    }

    /** Active instances per qualified class name (exact class), for GET …/classes. */
    public Map<String, Integer> getClassCounts() {
        return Collections.unmodifiableMap(new TreeMap<>(classCounts));
    }

    /** Active instance with this HLA instance name, or null. */
    public RiftInstance findByInstanceName(String instanceName) {
        return instanceName == null ? null : byName.get(instanceName);
    }

    /** Active instance whose identity attribute has this key ("205-3-42"), or null. */
    public RiftInstance findByEntityId(String key) {
        return key == null ? null : byEntityId.get(key);
    }

    /** Reference resolver for RiftJson / RiftEventPublisher: "objectname" and "entityid". */
    public RiftRefResolver resolver() {
        return this::resolve;
    }

    private RiftInstance resolve(String kind, String key) {
        if (key == null) return null;
        if ("objectname".equals(kind)) return byName.get(key);
        if ("entityid".equals(kind)) return byEntityId.get(key);
        return null;
    }

    /**
     * Outgoing references of an instance (targets resolved now, null while unknown) and the
     * instances referring to it (active ones, capped at {@code incomingCap}, sorted by id).
     */
    public RiftRelations getRelations(RiftInstance instance, int incomingCap) {
        if (instance == null) return RiftRelations.NONE;
        List<RiftRelation> out = new ArrayList<>();
        for (List<RiftEdge> edges : instance.outgoing().values()) {
            for (RiftEdge e : edges) out.add(new RiftRelation(e.attribute, e.path, e.kind, e.key, resolve(e.kind, e.key)));
        }
        List<RiftEdge> in = new ArrayList<>();
        for (String k : ownKeys(instance)) {
            Set<RiftEdge> set = incoming.get(k);
            if (set == null) continue;
            for (RiftEdge e : set) if (e.source != instance && !e.source.isRemoved()) in.add(e);
        }
        in.sort(Comparator.comparingInt((RiftEdge e) -> e.source.getDbId()).thenComparing(e -> e.path));
        List<RiftRelation> inRel = new ArrayList<>();
        for (int i = 0; i < in.size() && i < Math.max(0, incomingCap); i++) {
            RiftEdge e = in.get(i);
            inRel.add(new RiftRelation(e.attribute, e.path, e.kind, e.key, e.source));
        }
        return new RiftRelations(out, inRel, in.size());
    }

    /** NEW (map): the outgoing references of one object (no incoming scan). */
    public List<RiftRelation> getOutgoing(RiftInstance instance) {
        if (instance == null) return java.util.Collections.emptyList();
        List<RiftRelation> out = new ArrayList<>();
        for (List<RiftEdge> edges : instance.outgoing().values()) {
            for (RiftEdge e : edges) out.add(new RiftRelation(e.attribute, e.path, e.kind, e.key, resolve(e.kind, e.key)));
        }
        return out;
    }

    private static List<String> ownKeys(RiftInstance instance) {
        List<String> keys = new ArrayList<>(2);
        if (instance.getInstanceName() != null) keys.add(refKey("objectname", instance.getInstanceName()));
        if (instance.entityKey() != null) keys.add(refKey("entityid", instance.entityKey()));
        return keys;
    }

    /**
     * Interactions, newest first (contract 8.2).
     *
     * @param beforeSeq only interactions with a smaller seq; &lt;= 0 = from the newest
     * @param limit     at most this many (ask for limit + 1 to know whether there are "more")
     * @param filter    null = all
     */
    public List<RiftInteraction> getInteractions(long beforeSeq, int limit, Predicate<? super RiftInteraction> filter) {
        return interactions.page(beforeSeq, limit, filter);
    }

    /**
     * Interactions newer than a seq, oldest first: the newest {@code limit} of those that pass the
     * filter (the map's fire and detonation flashes).
     *
     * @param afterSeq only interactions with a larger seq
     * @param limit    at most this many
     * @param filter   null = all
     */
    public List<RiftInteraction> getInteractionsAfter(long afterSeq, int limit, Predicate<? super RiftInteraction> filter) {
        return interactions.after(afterSeq, limit, filter);
    }

    /** Seq of the newest interaction in the ring buffer, 0 when there is none. */
    public long getLatestInteractionSeq() {
        return interactions.latestSeq();
    }

    /** One interaction by seq ("i48230" → 48230), or null once it has left the ring buffer. */
    public RiftInteraction getInteraction(long seq) {
        return interactions.get(seq);
    }

    /** True if the interaction references the instance (by instance name or entity id). */
    public boolean involves(RiftInteraction interaction, RiftInstance instance) {
        if (interaction == null || instance == null) return false;
        for (String k : ownKeys(instance)) if (interaction.refKeys().contains(k)) return true;
        return false;
    }

    /** Filter for {@link #getInteractions}: interactions referencing the instance. */
    public Predicate<RiftInteraction> involving(RiftInstance instance) {
        return ix -> involves(ix, instance);
    }

    // ================================================================== lifecycle

    public State getState() {
        return state;
    }

    /**
     * Blocks until every event submitted before this call is applied and its listeners have run.
     *
     * @return false on timeout or when the database is not running
     */
    public boolean awaitDrained(long timeout, TimeUnit unit) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        synchronized (lifecycleLock) {
            if (state != State.RUNNING) return false;
            enqueue(new TaskEvent(latch::countDown));
        }
        return latch.await(timeout, unit);
    }

    /**
     * Stops accepting events; the backlog is still applied. A push racing with this call may be
     * dropped (it is never half applied).
     */
    public void shutdown() {
        synchronized (lifecycleLock) {
            if (state != State.RUNNING) return;
            state = State.STOPPING;
            queue.add(STOP);
        }
    }

    /** Stops at once: drops the backlog. */
    public void shutdownNow() {
        synchronized (lifecycleLock) {
            if (state == State.RUNNING) state = State.STOPPING;
            queue.clear();
            queue.add(STOP);
        }
        if (decodePool != null) decodePool.shutdownNow();
        worker.interrupt();
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        worker.join(Math.max(1, unit.toMillis(timeout)));
        return !worker.isAlive();
    }

    /** {@link #shutdown()}, wait up to 5 s for the backlog, then {@link #shutdownNow()}. */
    @Override
    public void close() {
        shutdown();
        try {
            if (!awaitTermination(5, TimeUnit.SECONDS)) {
                LOG.warning("RiftDatabase " + name + ": backlog not applied within 5 s, dropping " + queue.size() + " events");
                shutdownNow();
                awaitTermination(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ================================================================== statistics

    public int getQueueSize() {
        return queue.size();
    }

    public int getQueueHighWaterMark() {
        return queueHighWater.get();
    }

    /** Events applied (all kinds). */
    public long getProcessedCount() {
        return processed.get();
    }

    /** Events that failed while being applied (logged). */
    public long getFailedCount() {
        return failed.get();
    }

    /** Updates and interactions whose decoding threw (stored with an ERROR value). */
    public long getDecodeErrorCount() {
        return decodeErrors.get();
    }

    /** Updates or removals for ids that are unknown, already removed or purged. */
    public long getUnknownInstanceCount() {
        return unknownInstance.get();
    }

    /** Events refused because the database was closed. */
    public long getRejectedCount() {
        return rejected.get();
    }

    public long getListenerErrorCount() {
        return listenerErrors.get();
    }

    /** For GET /api/datasources/{id} "stats" (all values are JSON safe). */
    public Map<String, Object> getStats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", state.name());
        m.put("seq", getSeq());
        m.put("instances", getInstanceCount());
        m.put("removedretained", instances.size() - getInstanceCount());
        m.put("interactionsbuffered", interactions.size());
        m.put("queue", getQueueSize());
        m.put("queuehighwater", getQueueHighWaterMark());
        m.put("processed", getProcessedCount());
        m.put("failed", getFailedCount());
        m.put("decodeerrors", getDecodeErrorCount());
        m.put("unknowninstance", getUnknownInstanceCount());
        m.put("rejected", getRejectedCount());
        m.put("emptyupdates", emptyUpdates.get());
        return m;
    }

    // ================================================================== worker

    private void enqueue(Event e) {
        queue.add(e);
        int size = queue.size();
        int hw;
        while (size > (hw = queueHighWater.get()) && !queueHighWater.compareAndSet(hw, size)) {
            // retry
        }
    }

    private <T> FutureTask<T> decodeTask(Callable<T> work) {
        FutureTask<T> task = new FutureTask<>(work);
        if (decodePool != null) {
            try {
                decodePool.execute(task);
            } catch (RejectedExecutionException closing) {
                // left for the worker to run (or drop) itself
            }
        }
        return task;
    }

    private void runWorker() {
        long nextTick = System.currentTimeMillis() + TICK_MILLIS;
        try {
            while (true) {
                long wait = nextTick - System.currentTimeMillis();
                Event e;
                try {
                    e = wait > 0 ? queue.poll(wait, TimeUnit.MILLISECONDS) : queue.poll();
                } catch (InterruptedException ie) {
                    if (state == State.RUNNING) continue;           // stray interrupt: ignore
                    break;
                }
                if (e == STOP) break;
                if (e != null) {
                    try {
                        e.apply();
                    } catch (RuntimeException ex) {
                        failed.incrementAndGet();
                        LOG.log(Level.WARNING, "RiftDatabase " + name + ": failed to apply " + e, ex);
                    }
                    processed.incrementAndGet();
                }
                long now = System.currentTimeMillis();
                if (now >= nextTick) {
                    nextTick = now + TICK_MILLIS;
                    tick(now);
                }
            }
            state = State.STOPPED;
        } catch (Error fatal) {
            state = State.FAILED;
            LOG.log(Level.SEVERE, "RiftDatabase " + name + ": worker died", fatal);
            throw fatal;
        } finally {
            if (decodePool != null) decodePool.shutdownNow();
        }
    }

    private void tick(long now) {
        try {
            purgeRemoved(now, maxRemoved);
        } catch (RuntimeException ex) {
            failed.incrementAndGet();
            LOG.log(Level.WARNING, "RiftDatabase " + name + ": purge failed", ex);
        }
        for (RiftDatabaseListener l : listeners) {
            try {
                l.tick(now);
            } catch (RuntimeException ex) {
                listenerError(ex);
            }
        }
    }

    /** Logs the first occurrence of a kind of datasource mistake; later ones are only counted. */
    private void warnOnce(String kind, String message) {
        if (warned.add(kind)) LOG.warning("RiftDatabase " + name + ": " + message + " (logged once; see getStats())");
    }

    /**
     * Qualified object class name for the class tree and the list profiles: "HLAobjectRoot." is
     * added when missing ("BaseEntity.PhysicalEntity.Platform.Aircraft" is accepted).
     */
    private String qualifiedObjectClass(String className) {
        String c = className == null ? "" : className.trim();
        if (c.isEmpty()) {
            warnOnce("noclass", "discoverInstance without a class name: shown as HLAobjectRoot. Pass the qualified"
                    + " class name, e.g. HLAobjectRoot.BaseEntity.PhysicalEntity.Platform.Aircraft");
            return "HLAobjectRoot";
        }
        return qualify(c);
    }

    /** "HLAobjectRoot." + name unless the name already starts with the root. */
    public static String qualify(String className) {
        if (className.equals("HLAobjectRoot") || className.startsWith("HLAobjectRoot.")) return className;
        return "HLAobjectRoot." + className;
    }

    private void listenerError(RuntimeException ex) {
        listenerErrors.incrementAndGet();
        LOG.log(Level.WARNING, "RiftDatabase " + name + ": listener failed", ex);
    }

    /** The result of a decode task; runs it here if no decode thread took it. */
    private <T> T await(FutureTask<T> task) throws ExecutionException {
        task.run();                    // no-op if a decode thread already ran or is running it
        while (true) {
            try {
                return task.get();
            } catch (InterruptedException ie) {
                if (state != State.RUNNING) throw new ExecutionException("interrupted while stopping", ie);
            }
        }
    }

    // ---- decoding (decode threads)

    private Map<String, ValueNode> decodeAttributes(RawValues raw, List<RiftAttribute> attrs) {
        RiftValueDecoder d = valueDecoder;
        if (d == null) throw new IllegalStateException("no decoder set");
        return d.decodeAttributes(raw, attrs);
    }

    /** No RiftAttributes given: one per captured value, named by the decoder; fills attrs. */
    private Map<String, ValueNode> decodeCaptured(RawValues raw, List<RiftAttribute> attrs) {
        RiftValueDecoder d = valueDecoder;
        if (d == null) throw new IllegalStateException("no decoder set");
        List<RiftValueDecoder.Member> members = d.decodeCaptured(raw);
        if (members.isEmpty()) {
            throw new IllegalStateException("the decoder cannot decode an update without RiftAttributes");
        }
        Map<String, ValueNode> nodes = new HashMap<>();
        for (RiftValueDecoder.Member m : members) {
            attrs.add(new RiftAttribute(m.iemId, m.name, m.raw, null, null, null));
            nodes.put(m.name, m.value);
        }
        return nodes;
    }

    private List<ValueNode> decodeParameters(Integer classId, List<RiftParameter> params, long now) {
        RiftValueDecoder d = valueDecoder;
        if (d == null) throw new IllegalStateException("no decoder set");
        List<ValueNode> out = new ArrayList<>(params.size());
        for (RiftParameter p : params) {
            ValueNode v;
            try {
                v = d.decodeParameter(classId, p, now);
            } catch (RuntimeException ex) {
                decodeErrors.incrementAndGet();
                v = ErrorNode.of(ex);
            }
            out.add(v);
        }
        return out;
    }

    // ---- applying (worker thread)

    private void applyDiscover(RiftInstance inst) {
        inst.setRev(seq.incrementAndGet());
        instances.put(inst.getDbId(), inst);
        if (inst.getInstanceName() != null) byName.put(inst.getInstanceName(), inst);
        classCounts.merge(inst.getClassName(), 1, Integer::sum);
        for (RiftDatabaseListener l : listeners) {
            try {
                l.instanceDiscovered(inst);
            } catch (RuntimeException ex) {
                listenerError(ex);
            }
        }
    }

    private void applyUpdate(UpdateEvent u) {
        RiftInstance inst = instances.get(u.dbId);
        if (inst == null || inst.isRemoved()) {
            u.decoded.cancel(false);
            unknownInstance.incrementAndGet();
            if (inst == null) {
                warnOnce("unknown", "attribute update for unknown instance id " + u.dbId
                        + ": pass the id that discoverInstance returned for that object");
            }
            return;
        }
        Map<String, ValueNode> nodes;
        ValueNode failure = null;
        try {
            nodes = await(u.decoded);
        } catch (ExecutionException | java.util.concurrent.CancellationException ex) {
            decodeErrors.incrementAndGet();
            nodes = Collections.emptyMap();
            failure = ErrorNode.of(ex instanceof ExecutionException && ex.getCause() != null ? ex.getCause() : ex);
        }
        if (u.attrs.isEmpty()) {                     // built from raw, and that failed: nothing to apply
            warnOnce("captured", "could not decode an update without RiftAttributes: " + (failure != null ? failure.text() : "no values"));
            return;
        }
        Set<String> names = new LinkedHashSet<>();
        for (RiftAttribute a : u.attrs) {
            ValueNode v = nodes.get(a.getName());
            a.setDecoded(v != null ? v : failure, u.receivedMillis);
            names.add(a.getName());
        }
        inst.applyAttributes(u.attrs, seq.incrementAndGet(), u.receivedMillis);
        for (RiftAttribute a : u.attrs) updateIndexes(inst, a);
        for (RiftDatabaseListener l : listeners) {
            try {
                l.instanceUpdated(inst, names);
            } catch (RuntimeException ex) {
                listenerError(ex);
            }
        }
    }

    private void applyRemove(RemoveEvent r) {
        RiftInstance inst = instances.get(r.dbId);
        if (inst == null || inst.isRemoved()) {
            unknownInstance.incrementAndGet();
            return;
        }
        long s = seq.incrementAndGet();
        inst.markRemoved(s, r.receivedMillis);
        if (inst.getInstanceName() != null) byName.remove(inst.getInstanceName(), inst);
        if (inst.entityKey() != null) byEntityId.remove(inst.entityKey(), inst);
        for (List<RiftEdge> edges : inst.outgoing().values()) unindex(edges);   // its own outgoing list stays
        classCounts.computeIfPresent(inst.getClassName(), (k, v) -> v > 1 ? v - 1 : null);
        removedOrder.addLast(inst);
        for (RiftDatabaseListener l : listeners) {
            try {
                l.instanceRemoved(inst, s);
            } catch (RuntimeException ex) {
                listenerError(ex);
            }
        }
    }

    private void applyInteraction(InteractionEvent ev) {
        List<ValueNode> nodes;
        ValueNode failure = null;
        try {
            nodes = await(ev.decoded);
        } catch (ExecutionException | java.util.concurrent.CancellationException ex) {
            decodeErrors.incrementAndGet();
            nodes = Collections.emptyList();
            failure = ErrorNode.of(ex instanceof ExecutionException && ex.getCause() != null ? ex.getCause() : ex);
        }
        Set<String> refKeys = new HashSet<>();
        String correlation = null;
        for (int i = 0; i < ev.params.size(); i++) {
            RiftParameter p = ev.params.get(i);
            ValueNode v = i < nodes.size() ? nodes.get(i) : failure;
            p.setDecoded(v);
            scanRefs(v, p.getName(), 0, new int[1], (path, kind, key) -> refKeys.add(refKey(kind, key)));
            if (v != null && "EventIdentifier".equals(p.getName()) && v.text() != null && !v.text().isEmpty()) {
                correlation = v.text();
            }
        }
        String className = interactionClassName(ev.classId);
        String summary = null;
        java.util.function.BiFunction<String, List<RiftParameter>, String> sum = interactionSummary;
        if (sum != null) {
            try {
                summary = sum.apply(className, ev.params);
            } catch (RuntimeException ex) {
                LOG.log(Level.FINE, "interaction summary failed for " + className, ex);
            }
        }
        RiftInteraction ix = new RiftInteraction(seq.incrementAndGet(), ev.classId, className,
                ev.receivedMillis, ev.producer, ev.params, ev.tag, refKeys, correlation, summary);
        interactions.add(ix);
        for (RiftDatabaseListener l : listeners) {
            try {
                l.interactionReceived(ix);
            } catch (RuntimeException ex) {
                listenerError(ex);
            }
        }
    }

    private String interactionClassName(Integer classId) {
        int id = classId == null ? 0 : classId;
        String n = interactionNameCache.get(id);
        if (n != null) return n;
        IntFunction<String> f = interactionClassNames;
        try {
            n = f == null ? null : f.apply(id);
        } catch (RuntimeException ex) {
            LOG.log(Level.FINE, "interaction class name lookup failed for " + id, ex);
        }
        if (n == null || n.isEmpty()) n = "HLAinteractionRoot.Interaction" + id;
        interactionNameCache.put(id, n);
        return n;
    }

    /** Identity key and outgoing references of one updated attribute. */
    private void updateIndexes(RiftInstance inst, RiftAttribute a) {
        ValueNode v = a.getValue();
        if (isIdentity(inst.getClassName(), a.getName())) {
            String key = entityKeyOf(v);
            String old = inst.entityKey();
            if (key != null && !key.equals(old)) {
                if (old != null) byEntityId.remove(old, inst);
                inst.setEntityKey(key);
                byEntityId.put(key, inst);
            }
            return;                                      // an identity attribute is never a reference
        }
        List<RiftEdge> edges = new ArrayList<>();
        scanRefs(v, a.getName(), 0, new int[1], (path, kind, key) -> edges.add(new RiftEdge(inst, a.getName(), path, kind, key)));
        Map<String, List<RiftEdge>> cur = inst.outgoing();
        List<RiftEdge> old = cur.get(a.getName());
        if (edges.isEmpty() && old == null) return;
        if (old != null) unindex(old);
        for (RiftEdge e : edges) incoming.computeIfAbsent(e.indexKey, k -> ConcurrentHashMap.newKeySet()).add(e);
        Map<String, List<RiftEdge>> next = new LinkedHashMap<>(cur);
        if (edges.isEmpty()) next.remove(a.getName());
        else next.put(a.getName(), Collections.unmodifiableList(edges));
        inst.setOutgoing(Collections.unmodifiableMap(next));
    }

    private void unindex(List<RiftEdge> edges) {
        for (RiftEdge e : edges) {
            Set<RiftEdge> set = incoming.get(e.indexKey);
            if (set != null) {
                set.remove(e);
                if (set.isEmpty()) incoming.remove(e.indexKey, set);
            }
        }
    }

    private void purgeRemoved(long now, int keepAtMost) {
        long retention = removedRetentionMillis;
        while (!removedOrder.isEmpty()) {
            RiftInstance oldest = removedOrder.peekFirst();
            if (removedOrder.size() <= keepAtMost && oldest.getRemovedMillis() + retention > now) break;
            removedOrder.pollFirst();
            instances.remove(oldest.getDbId(), oldest);
        }
    }

    // ================================================================== helpers

    /** Index key of a reference: kind + ':' + key. */
    static String refKey(String kind, String key) {
        return kind + ':' + key;
    }

    private interface RefSink {
        void ref(String path, String kind, String key);
    }

    /**
     * Depth-first walk over a value tree reporting every reference with its path. The decoder tags
     * an RTIobjectIdArray as a whole (all keys) and each element (its key); a key already found
     * deeper is reported only there, with the more precise path ("Attr[3]").
     */
    private static void scanRefs(ValueNode v, String path, int depth, int[] count, RefSink sink) {
        if (v == null || depth > MAX_REF_DEPTH || count[0] >= MAX_REFS_PER_ATTRIBUTE) return;
        List<String[]> below = new ArrayList<>();                       // {path, kind, key}
        RefSink collect = (p, k, key) -> below.add(new String[] {p, k, key});
        for (ValueNode.Named f : v.fields()) {
            if (f != null) scanRefs(f.node, path + "." + f.name, depth + 1, count, collect);
        }
        List<ValueNode> items = v.items();
        for (int i = 0; i < items.size() && count[0] < MAX_REFS_PER_ATTRIBUTE; i++) {
            scanRefs(items.get(i), path + "[" + i + "]", depth + 1, count, collect);
        }
        String kind = v.refKind();
        if (kind != null) {
            Set<String> covered = new HashSet<>();
            for (String[] r : below) covered.add(refKey(r[1], r[2]));
            for (String key : v.refKeys()) {
                if (key == null || key.isEmpty() || covered.contains(refKey(kind, key))) continue;
                if (count[0]++ >= MAX_REFS_PER_ATTRIBUTE) break;
                sink.ref(path, kind, key);
            }
        }
        for (String[] r : below) sink.ref(r[0], r[1], r[2]);
    }

    /**
     * Key of an identity value: its integer leaves joined with '-' ("205-3-42" for an RPR
     * EntityIdentifierStruct), so it matches the decoder's entityid reference keys whatever the
     * display separator; else the value text.
     */
    static String entityKeyOf(ValueNode v) {
        if (v == null || "ERROR".equals(v.kind())) return null;
        List<String> parts = new ArrayList<>(3);
        integerLeaves(v, parts, 0);
        if (!parts.isEmpty()) return String.join("-", parts);
        String t = v.text();
        return t == null || t.trim().isEmpty() ? null : t.trim();
    }

    private static void integerLeaves(ValueNode v, List<String> out, int depth) {
        if (v == null || depth > 8) return;
        if ("INTEGER".equals(v.kind()) && v.scalar() instanceof Number) {
            out.add(Long.toString(((Number) v.scalar()).longValue()));
            return;
        }
        for (ValueNode.Named f : v.fields()) if (f != null) integerLeaves(f.node, out, depth + 1);
    }

    private boolean isIdentity(String classNameQ, String attribute) {
        List<String> classes = identityRules.get(attribute);
        if (classes == null) return false;
        String dotted = "." + classNameQ + ".";
        for (String c : classes) if (dotted.contains("." + c + ".")) return true;
        return false;
    }

    private static Map<String, List<String>> rules(Collection<String> classDotAttribute) {
        Map<String, List<String>> m = new HashMap<>();
        if (classDotAttribute != null) {
            for (String s : classDotAttribute) {
                int dot = s == null ? -1 : s.lastIndexOf('.');
                if (dot <= 0 || dot == s.length() - 1) throw new IllegalArgumentException("expected Class.Attribute: " + s);
                m.computeIfAbsent(s.substring(dot + 1), k -> new ArrayList<>()).add(s.substring(0, dot));
            }
        }
        return m;
    }

    // ================================================================== events

    private abstract static class Event {
        final long receivedMillis;

        Event(long receivedMillis) {
            this.receivedMillis = receivedMillis;
        }

        abstract void apply();
    }

    private static final Event STOP = new Event(0) {
        @Override
        void apply() { }

        @Override
        public String toString() {
            return "STOP";
        }
    };

    private final class DiscoverEvent extends Event {
        final RiftInstance instance;

        DiscoverEvent(long t, RiftInstance instance) {
            super(t);
            this.instance = instance;
        }

        @Override
        void apply() {
            applyDiscover(instance);
        }

        @Override
        public String toString() {
            return "discover " + instance;
        }
    }

    private final class UpdateEvent extends Event {
        final int dbId;
        final List<RiftAttribute> attrs;
        final FutureTask<Map<String, ValueNode>> decoded;

        UpdateEvent(long t, int dbId, List<RiftAttribute> attrs, FutureTask<Map<String, ValueNode>> decoded) {
            super(t);
            this.dbId = dbId;
            this.attrs = attrs;
            this.decoded = decoded;
        }

        @Override
        void apply() {
            applyUpdate(this);
        }

        @Override
        public String toString() {
            return "update o" + dbId + " " + attrs;
        }
    }

    private final class RemoveEvent extends Event {
        final int dbId;

        RemoveEvent(long t, int dbId) {
            super(t);
            this.dbId = dbId;
        }

        @Override
        void apply() {
            applyRemove(this);
        }

        @Override
        public String toString() {
            return "remove o" + dbId;
        }
    }

    private final class InteractionEvent extends Event {
        final Integer classId;
        final List<RiftParameter> params;
        final String producer;
        final byte[] tag;
        final FutureTask<List<ValueNode>> decoded;

        InteractionEvent(long t, Integer classId, List<RiftParameter> params, String producer, byte[] tag,
                         FutureTask<List<ValueNode>> decoded) {
            super(t);
            this.classId = classId;
            this.params = params;
            this.producer = producer;
            this.tag = tag;
            this.decoded = decoded;
        }

        @Override
        void apply() {
            applyInteraction(this);
        }

        @Override
        public String toString() {
            return "interaction " + classId + " " + params;
        }
    }

    private static final class TaskEvent extends Event {
        final Runnable task;

        TaskEvent(Runnable task) {
            super(System.currentTimeMillis());
            this.task = task;
        }

        @Override
        void apply() {
            task.run();
        }

        @Override
        public String toString() {
            return "task";
        }
    }

    /** Value node standing in for a value that could not be decoded. */
    private static final class ErrorNode implements ValueNode {
        private final String message;

        private ErrorNode(String message) {
            this.message = message;
        }

        static ErrorNode of(Throwable t) {
            String m = t.getMessage();
            return new ErrorNode(t.getClass().getSimpleName() + (m != null ? ": " + m : ""));
        }

        @Override
        public String kind() {
            return "ERROR";
        }

        @Override
        public String text() {
            return "decode failed: " + message;
        }

        @Override
        public Object scalar() {
            return null;
        }

        @Override
        public String error() {
            return message;
        }
    }

    /** Fixed-size ring of the newest interactions, ordered by seq; guarded by its own lock. */
    private static final class InteractionRing {
        private final RiftInteraction[] ring;
        private int start;      // index of the oldest
        private int size;

        InteractionRing(int capacity) {
            ring = new RiftInteraction[capacity];
        }

        synchronized int size() {
            return size;
        }

        synchronized void add(RiftInteraction ix) {
            if (size < ring.length) {
                ring[(start + size) % ring.length] = ix;
                size++;
            } else {
                ring[start] = ix;
                start = (start + 1) % ring.length;
            }
        }

        private RiftInteraction at(int i) {               // i = 0 oldest .. size-1 newest
            return ring[(start + i) % ring.length];
        }

        /** Index of the first entry with seq &gt;= s (size if none). */
        private int lowerBound(long s) {
            int lo = 0, hi = size;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (at(mid).seq() < s) lo = mid + 1;
                else hi = mid;
            }
            return lo;
        }

        synchronized RiftInteraction get(long s) {
            int i = lowerBound(s);
            return i < size && at(i).seq() == s ? at(i) : null;
        }

        synchronized long latestSeq() {
            return size == 0 ? 0 : at(size - 1).seq();
        }

        synchronized List<RiftInteraction> after(long afterSeq, int limit, Predicate<? super RiftInteraction> filter) {
            ArrayDeque<RiftInteraction> out = new ArrayDeque<>();
            for (int i = lowerBound(afterSeq + 1); i < size; i++) {
                RiftInteraction ix = at(i);
                if (filter == null || filter.test(ix)) {
                    out.addLast(ix);
                    if (out.size() > limit) out.removeFirst();
                }
            }
            return new ArrayList<>(out);
        }

        synchronized List<RiftInteraction> page(long beforeSeq, int limit, Predicate<? super RiftInteraction> filter) {
            List<RiftInteraction> out = new ArrayList<>(Math.max(0, Math.min(limit, 1024)));
            int i = (beforeSeq <= 0 ? size : lowerBound(beforeSeq)) - 1;
            for (; i >= 0 && out.size() < limit; i--) {
                RiftInteraction ix = at(i);
                if (filter == null || filter.test(ix)) out.add(ix);
            }
            return out;
        }
    }
}
