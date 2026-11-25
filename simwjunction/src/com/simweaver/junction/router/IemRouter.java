package com.simweaver.junction.router;

import com.simweaver.common.json.Json;
import com.simweaver.junction.config.RouterConfig;
import com.simweaver.junction.endpoint.Endpoint;
import com.simweaver.junction.endpoint.EndpointException;
import com.simweaver.junction.endpoint.EndpointListener;
import com.simweaver.junction.endpoint.EndpointState;
import com.simweaver.junction.endpoint.Interest;
import com.simweaver.junction.endpoint.NameCandidates;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The router for information models: objects and interactions between HLA (and DIS, as RPR
 * v2) endpoints, class filtering, renaming of object instances, references and RPR version
 * conversion. See the design, §5 and §7.
 *
 * <h2>Threads</h2>
 * Endpoints hand events to a bounded queue; one dispatcher thread does all the work, so the
 * object registry needs no locks and the order of events per object is kept. Attribute
 * updates are dropped (and counted) when the queue is full; everything else waits up to a
 * second for room.
 *
 * <h2>Objects</h2>
 * An object discovered in endpoint S is created in every other endpoint D its class routes
 * to, with a name from the name policy. Its attribute values are kept (in S's encoding):
 * updates that arrive before D has granted the name are sent once it has, and requests from
 * D to provide values are answered from them - never forwarded to S.
 */
public final class IemRouter implements Router {

    private static final Logger LOG = Logger.getLogger(IemRouter.class.getName());
    private static final long TICK_MILLIS = 100;

    /**
     * Adds behaviour to the router without changing how it bridges (the Talk router uses
     * it for its channel tables and the radio bridge). Every method is called on the dispatcher
     * thread, so an extension needs no locks of its own. Objects the extension creates use
     * negative gateway ids ({@link #createObject}).
     */
    public interface Extension {
        /**
         * First of all, with the endpoints (open: their models are known). An extension that has
         * nothing to do for these endpoints answers false and is left out.
         */
        default boolean prepare(List<Endpoint> endpoints, Map<String, ModelTools> tools) {
            return true;
        }

        /** Before the declarations: add what the extension publishes and subscribes. */
        default void declare(Endpoint e, ModelTools tools, Interest publish, Interest subscribe) {
        }

        /** After the declarations, before traffic flows. */
        default void started(IemRouter router, List<Endpoint> endpoints, Map<String, ModelTools> tools) {
        }

        /** @return true when the extension takes the object (it is then not bridged) */
        default boolean discovered(Endpoint s, long key, int objectClass, String name) {
            return false;
        }

        /** @return true when the object is the extension's */
        default boolean reflected(Endpoint s, long key, int[] attributes, byte[][] values) {
            return false;
        }

        /** @return true when the object is the extension's */
        default boolean removed(Endpoint s, long key) {
            return false;
        }

        /** An interaction arrived (before it is routed). */
        default void received(Endpoint s, int interactionClass, int[] parameters, byte[][] values) {
        }

        /**
         * A routed interaction, converted for {@code d}.
         *
         * @return the values to send (the same array, changed, or a new one), or null to not send
         */
        default byte[][] rewrite(Endpoint s, Endpoint d, int destinationClass, int[] parameters, byte[][] values) {
            return values;
        }

        /** @return true when {@code gwId} is the extension's (negative ids) */
        default boolean registered(Endpoint d, long gwId, long key, String name) {
            return gwId < 0;
        }

        /** @return true when {@code gwId} is the extension's */
        default boolean registrationFailed(Endpoint d, long gwId, String reason) {
            return gwId < 0;
        }

        /** @return true when the object is the extension's */
        default boolean provide(Endpoint d, long key, int[] attributes) {
            return false;
        }

        /** An endpoint's state changed. */
        default void stateChanged(Endpoint e, EndpointState state) {
        }

        /** About ten times a second. */
        default void tick(long now) {
        }

        /** A discovered object is bridged by the router (watching only). */
        default void bridged(Endpoint s, long key, int objectClass, String name) {
        }

        /** A bridged object was updated (after the router passed the update on). */
        default void observed(Endpoint s, long key, int[] attributes, byte[][] values) {
        }

        /** A bridged object went away (removed, or its endpoint was lost). */
        default void unbridged(Endpoint s, long key) {
        }

        /** The copy of a bridged object is now registered in {@code d} under {@code name}. */
        default void copied(Endpoint origin, long originKey, Endpoint d, String name) {
        }

        /** The router stops (endpoints are still open). */
        default void stopping() {
        }

        /** Adds the extension's counters to the router's {@code objects} JSON (any thread; 0.12.0). */
        default void describe(Map<String, Object> into) {
        }
    }

    /** No extension. */
    private static final Extension NONE = new Extension() {
    };

    private final RouterConfig cfg;
    private final String gatewayId;
    private final RouteCompiler compiler;
    private final BlockingQueue<Runnable> queue;
    private final RouterStats stats = new RouterStats();
    private final Inbound inbound = new Inbound();
    private Extension ext;
    private long nextExtensionId;
    private long lastTick;
    private volatile Thread dispatcher;
    private volatile boolean running;
    private volatile boolean stopped;

    // set by start
    private List<Endpoint> endpoints = Collections.emptyList();
    private final Map<Endpoint, Map<Endpoint, RouteTable>> tables = new IdentityHashMap<Endpoint, Map<Endpoint, RouteTable>>();

    // registry: dispatcher thread only
    private final Map<Long, GwObject> objects = new HashMap<Long, GwObject>();
    private final Map<Endpoint, Map<Long, GwObject>> byOrigin = new IdentityHashMap<Endpoint, Map<Long, GwObject>>();
    private final Map<Endpoint, Map<Long, Proxy>> byProxyKey = new IdentityHashMap<Endpoint, Map<Long, Proxy>>();
    private final Map<Endpoint, Map<String, GwObject>> byName = new IdentityHashMap<Endpoint, Map<String, GwObject>>();
    private long nextId;

    // counts for the page (any thread)
    private final AtomicInteger bridged = new AtomicInteger();
    private final AtomicInteger proxiesLive = new AtomicInteger();
    private final AtomicInteger proxiesPending = new AtomicInteger();
    private final AtomicInteger proxiesFailed = new AtomicInteger();

    /**
     * @param cfg       the router configuration
     * @param gatewayId the gateway id (thread name, logs)
     * @param rules     RPR conversion rules per pair of models
     */
    public IemRouter(RouterConfig cfg, String gatewayId, ConversionRules.Provider rules) {
        this(cfg, gatewayId, rules, cfg.getFilter(), null);
    }

    /**
     * @param cfg       the router configuration
     * @param gatewayId the gateway id (thread name, logs)
     * @param rules     RPR conversion rules per pair of models
     * @param filter    the class filter to use instead of the configuration's
     * @param extension added behaviour, or null
     */
    public IemRouter(RouterConfig cfg, String gatewayId, ConversionRules.Provider rules, com.simweaver.junction.config.ClassFilter filter,
                     Extension extension) {
        this.cfg = cfg;
        this.gatewayId = gatewayId;
        this.compiler = new RouteCompiler(filter, cfg.isPromote(), rules);
        this.queue = new ArrayBlockingQueue<Runnable>(cfg.getQueueCapacity());
        this.ext = extension == null ? NONE : extension;
    }

    /** @return the gateway id */
    public String gatewayId() {
        return gatewayId;
    }

    /**
     * For extensions (dispatcher thread): creates an object of the extension in an endpoint;
     * the answer goes to {@link Extension#registered} with the returned id.
     *
     * @return the (negative) gateway id of the object
     */
    public long createObject(Endpoint d, int objectClass, NameCandidates names) {
        long id = --nextExtensionId;
        d.createObject(id, objectClass, names);
        return id;
    }

    /**
     * For extensions (dispatcher thread): the name of the copy that a bridged object has in
     * {@code d}, or null while there is none (not routed there, not registered yet, failed).
     */
    public String copyName(Endpoint origin, long originKey, Endpoint d) {
        Proxy p = liveProxy(origin, originKey, d);
        return p == null ? null : p.name;
    }

    /** For extensions (dispatcher thread): the class of that copy in {@code d}, or -1. */
    public int copyClass(Endpoint origin, long originKey, Endpoint d) {
        Proxy p = liveProxy(origin, originKey, d);
        return p == null ? -1 : p.route.destinationClass;
    }

    /**
     * For extensions (dispatcher thread): the key of the object that {@code s} itself has under
     * {@code name} (one it discovered, not a copy the router made there), or null.
     */
    public Long bridgedKey(Endpoint s, String name) {
        Map<String, GwObject> m = byName.get(s);
        GwObject g = m == null || name == null ? null : m.get(name);
        return g == null || g.origin != s ? null : g.originKey;
    }

    /**
     * For extensions (dispatcher thread): attribute values (of {@code d}'s model) that the copy
     * of a bridged object in {@code d} gets on top of what the router converts. They are kept,
     * and sent again when the copy is registered anew and when {@code d} asks for them.
     *
     * @return true when the copy is live and got them now
     */
    public boolean updateCopy(Endpoint origin, long originKey, Endpoint d, int[] attributes, byte[][] values) {
        Map<Long, GwObject> mine = byOrigin.get(origin);
        GwObject g = mine == null ? null : mine.get(originKey);
        if (g == null || attributes.length == 0) {
            return false;
        }
        Map<Integer, byte[]> extra = g.extra.computeIfAbsent(d, k -> new LinkedHashMap<Integer, byte[]>());
        for (int i = 0; i < attributes.length; i++) {
            extra.put(attributes[i], values[i]);
        }
        Proxy p = g.proxies.get(d);
        if (p == null || p.state != Proxy.State.LIVE) {
            return false;
        }
        d.updateObject(p.key, attributes, values, null);
        stats.updatesRouted.incrementAndGet();
        return true;
    }

    /**
     * For extensions (dispatcher thread): the class that objects of {@code originClass} from
     * {@code origin} get in {@code d} (the route's destination class), or -1 when they are not
     * routed there. Known before any copy exists (0.12.0).
     */
    public int routedClass(Endpoint origin, int originClass, Endpoint d) {
        RouteTable t = table(origin, d);
        ClassRoute cr = t == null ? null : t.objectRoute(originClass);
        return cr == null ? -1 : cr.destinationClass;
    }

    /**
     * For extensions (dispatcher thread): the GUID of a bridged object, the one of the
     * {@code {guid}} copy-name token: from this gateway, the origin endpoint and the object's name,
     * so the same object gets the same GUID after a reconnect or a restart (0.12.0). Null for an
     * object the router does not bridge.
     */
    public java.util.UUID objectGuid(Endpoint origin, long originKey) {
        Map<Long, GwObject> mine = byOrigin.get(origin);
        GwObject g = mine == null ? null : mine.get(originKey);
        if (g == null) {
            return null;
        }
        return guidOf(g, g.originName.isEmpty() ? g.origin.id() + "-" + g.id : g.originName);
    }

    private Proxy liveProxy(Endpoint origin, long originKey, Endpoint d) {
        Map<Long, GwObject> mine = byOrigin.get(origin);
        GwObject g = mine == null ? null : mine.get(originKey);
        Proxy p = g == null ? null : g.proxies.get(d);
        return p == null || p.state != Proxy.State.LIVE ? null : p;
    }

    /** Sends the extension's values of a copy (all, or those of {@code only}). */
    private void sendExtra(GwObject g, Proxy p, int[] only) {
        Map<Integer, byte[]> extra = g.extra.get(p.endpoint);
        if (extra == null || extra.isEmpty()) {
            return;
        }
        List<Integer> ids = new ArrayList<Integer>();
        if (only == null) {
            ids.addAll(extra.keySet());
        } else {
            for (int a : only) {
                if (extra.containsKey(a)) ids.add(a);
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        int[] a = new int[ids.size()];
        byte[][] v = new byte[a.length][];
        for (int i = 0; i < a.length; i++) {
            a[i] = ids.get(i);
            v[i] = extra.get(a[i]);
        }
        p.endpoint.updateObject(p.key, a, v, null);
    }

    /** For extensions (any thread): runs a task on the dispatcher thread. */
    public void execute(Runnable task) {
        enqueue(task, false);
    }

    @Override
    public EndpointListener inbound() {
        return inbound;
    }

    @Override
    public RouterStats stats() {
        return stats;
    }

    @Override
    public int queueDepth() {
        return queue.size();
    }

    // ================================================================== start / stop

    @Override
    public void start(List<Endpoint> eps, Map<String, ModelTools> tools) throws EndpointException {
        this.endpoints = new ArrayList<Endpoint>(eps);
        if (ext != NONE && !ext.prepare(endpoints, tools)) {
            ext = NONE;
        }
        Map<Endpoint, RouteCompiler.Side> sides = new IdentityHashMap<Endpoint, RouteCompiler.Side>();
        stats.valueRules = cfg.getValueRules().size();
        for (Endpoint e : endpoints) {
            ValueMap vm;
            try {
                vm = ValueMap.compile(cfg.getValueRules(), tools.get(e.id()), e.id());
            } catch (IllegalArgumentException ex) {
                throw new EndpointException(EndpointException.CONFIG_INVALID, ex.getMessage(), ex);
            }
            if (!vm.isEmpty()) {
                LOG.info("Junction gateway " + gatewayId + ": value rules for what " + e.id() + " sends: " + vm.describe());
            }
            sides.put(e, new RouteCompiler.Side(e.id(), tools.get(e.id()), e.config().getInFilter(), e.config().getOutFilter(), vm));
            byOrigin.put(e, new HashMap<Long, GwObject>());
            byProxyKey.put(e, new HashMap<Long, Proxy>());
            byName.put(e, new HashMap<String, GwObject>());
        }
        Map<Endpoint, Interest> publish = new IdentityHashMap<Endpoint, Interest>();
        Map<Endpoint, Interest> subscribe = new IdentityHashMap<Endpoint, Interest>();
        for (Endpoint e : endpoints) {
            publish.put(e, new Interest());
            subscribe.put(e, new Interest());
        }
        for (Endpoint s : endpoints) {
            Map<Endpoint, RouteTable> row = new IdentityHashMap<Endpoint, RouteTable>();
            for (Endpoint d : endpoints) {
                if (s == d) {
                    continue;
                }
                RouteTable t = compiler.compile(sides.get(s), sides.get(d));
                row.put(d, t);
                for (ClassRoute cr : t.objectRoutes().values()) {
                    List<Integer> src = new ArrayList<Integer>();
                    List<Integer> dst = new ArrayList<Integer>();
                    for (MemberRoute m : cr.members()) {
                        src.add(m.sourceMember);
                        dst.add(m.destinationMember);
                    }
                    subscribe.get(s).addAttributes(cr.sourceClass, src);
                    publish.get(d).addAttributes(cr.destinationClass, dst);
                }
                for (ClassRoute cr : t.interactionRoutes().values()) {
                    subscribe.get(s).addInteraction(cr.sourceClass);
                    publish.get(d).addInteraction(cr.destinationClass);
                }
            }
            tables.put(s, row);
        }
        // declare everywhere first: discoveries triggered by the subscriptions wait in the queue
        // until every endpoint publishes what it may be asked to create
        for (Endpoint e : endpoints) {
            ext.declare(e, tools.get(e.id()), publish.get(e), subscribe.get(e));
        }
        for (Endpoint e : endpoints) {
            e.declare(publish.get(e), subscribe.get(e));
        }
        ext.started(this, endpoints, tools);
        Thread t = new Thread(this::dispatch, "junction-router-" + gatewayId);
        t.setDaemon(true);
        running = true;
        dispatcher = t;
        t.start();
    }

    @Override
    public void stop() {
        if (ext != NONE && running) {
            // the extension cleans up on the dispatcher, while the endpoints are open
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            enqueue(() -> {
                try {
                    ext.stopping();
                } finally {
                    done.countDown();
                }
            }, false);
            try {
                done.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        stopped = true;
        running = false;
        Thread t = dispatcher;
        if (t != null) {
            t.interrupt();
            try {
                t.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        queue.clear();
    }

    private void dispatch() {
        while (running) {
            Runnable r;
            try {
                r = queue.poll(ext == NONE ? 500 : TICK_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break;
            }
            try {
                if (r != null) {
                    r.run();
                }
                if (ext != NONE) {
                    long now = System.currentTimeMillis();
                    if (now - lastTick >= TICK_MILLIS) {
                        lastTick = now;
                        ext.tick(now);
                    }
                }
            } catch (RuntimeException e) {
                stats.problem("router: " + e);
                LOG.log(Level.WARNING, "Junction " + gatewayId + ": routing failed", e);
            }
        }
    }

    private void enqueue(Runnable r, boolean mayDrop) {
        if (stopped) {
            return;
        }
        boolean ok;
        if (mayDrop) {
            ok = queue.offer(r);
        } else {
            try {
                ok = queue.offer(r, 1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ok = false;
            }
        }
        if (!ok) {
            stats.queueDrops.incrementAndGet();
        }
        int depth = queue.size();
        if (depth > stats.queueHighWater.get()) {
            stats.queueHighWater.set(depth);
        }
    }

    // ================================================================== registry types

    /** One bridged object, as it exists at its origin. */
    private static final class GwObject {
        final long id;
        final Endpoint origin;
        final long originKey;
        final String originName;
        final int originClass;
        final Map<Integer, byte[]> cache = new LinkedHashMap<Integer, byte[]>();
        final Map<Endpoint, Proxy> proxies = new IdentityHashMap<Endpoint, Proxy>();
        final Map<Endpoint, Map<Integer, byte[]>> extra = new IdentityHashMap<Endpoint, Map<Integer, byte[]>>();   // Extension#updateCopy
        /** Source members last asked of the origin for a provide request, and when (forwarding throttle). */
        final Map<Integer, Long> asked = new HashMap<Integer, Long>();
        boolean removed;

        GwObject(long id, Endpoint origin, long originKey, String originName, int originClass) {
            this.id = id;
            this.origin = origin;
            this.originKey = originKey;
            this.originName = originName;
            this.originClass = originClass;
        }
    }

    /** The copy of an object in another endpoint. */
    private static final class Proxy {
        enum State { PENDING, LIVE, FAILED }

        final GwObject object;
        final Endpoint endpoint;
        final ClassRoute route;
        final String preferredName;
        State state = State.PENDING;
        long key;
        String name;

        Proxy(GwObject object, Endpoint endpoint, ClassRoute route, String preferredName) {
            this.object = object;
            this.endpoint = endpoint;
            this.route = route;
            this.preferredName = preferredName;
        }
    }

    // ================================================================== events (dispatcher thread)

    private RouteTable table(Endpoint s, Endpoint d) {
        Map<Endpoint, RouteTable> row = tables.get(s);
        return row == null ? null : row.get(d);
    }

    private void onDiscovered(Endpoint s, long key, int objectClass, String name) {
        Map<Long, GwObject> mine = byOrigin.get(s);
        if (mine == null || mine.containsKey(key) || ext.discovered(s, key, objectClass, name)) {
            return;
        }
        GwObject g = new GwObject(++nextId, s, key, name == null ? "" : name, objectClass);
        objects.put(g.id, g);
        mine.put(key, g);
        if (!g.originName.isEmpty()) {
            byName.get(s).put(g.originName, g);
        }
        bridged.incrementAndGet();
        ext.bridged(s, key, objectClass, g.originName);
        for (Endpoint d : endpoints) {
            if (d != s && d.state().isUp()) {
                createProxy(g, d);
            }
        }
    }

    private void createProxy(GwObject g, Endpoint d) {
        RouteTable t = table(g.origin, d);
        ClassRoute cr = t == null ? null : t.objectRoute(g.originClass);
        if (cr == null) {
            return;
        }
        NameCandidates names = candidates(g, d);
        Proxy p = new Proxy(g, d, cr, names.isAny() ? null : names.getNames().get(0));
        g.proxies.put(d, p);
        proxiesPending.incrementAndGet();
        d.createObject(g.id, cr.destinationClass, names);
    }

    private NameCandidates candidates(GwObject g, Endpoint d) {
        String original = g.originName.isEmpty() ? g.origin.id() + "-" + g.id : g.originName;
        int max = cfg.getNameMaxLength(d.id());
        String template = cfg.getNameTemplate(d.id());
        if (template != null && cfg.getNameCriteria(d.id()).matches(originClassName(g), g.originName)) {
            // router.names.template[.<destination>]; 0.14.0: only for the objects router.names.select / except let through
            return new NameCandidates(CopyNames.fromTemplate(template, max, original, guidOf(g, original), g.origin.id()));
        }
        String n = CopyNames.unreserved(original, g.origin.id());   // "HLA..." names are reserved for the RTI
        List<String> names;
        switch (cfg.getNamePolicy()) {
            case RTI:
                return NameCandidates.ANY;
            case SAME_ONLY:
                names = Collections.singletonList(n);
                break;
            case PREFIX: {
                String base = cfg.getNamePrefix() + n;
                names = Arrays.asList(base, base + "#2", base + "#3", base + "#4");
                break;
            }
            default: {
                String alt = n + "@" + g.origin.id();
                names = Arrays.asList(n, alt, alt + "#2", alt + "#3", alt + "#4");
            }
        }
        return new NameCandidates(CopyNames.limited(names, max));
    }

    /** The qualified name of the object's class in the model it comes from, or null. */
    private static String originClassName(GwObject g) {
        try {
            com.simweaver.common.iem.ObjectClass c = g.origin.model() == null ? null : g.origin.model().getObjectClass(g.originClass);
            return c == null ? null : c.getQualifiedName();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The GUID of a bridged object for {hexguid} / {guid}: derived from this gateway, the origin
     * endpoint and the object's name, so a copy gets the same name again after a reconnect or a
     * restart, and two instances of a gateway give different ones.
     */
    private java.util.UUID guidOf(GwObject g, String original) {
        return java.util.UUID.nameUUIDFromBytes((gatewayId + "|" + g.origin.id() + "|" + original)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void onRegistered(Endpoint d, long gwId, long key, String name) {
        if (ext.registered(d, gwId, key, name)) {
            return;
        }
        GwObject g = objects.get(gwId);
        Proxy p = g == null ? null : g.proxies.get(d);
        if (g == null || g.removed || p == null || p.state != Proxy.State.PENDING) {
            d.deleteObject(key, null);                    // the object went away meanwhile
            return;
        }
        p.state = Proxy.State.LIVE;
        p.key = key;
        p.name = name;
        proxiesPending.decrementAndGet();
        proxiesLive.incrementAndGet();
        byProxyKey.get(d).put(key, p);
        if (name != null && !name.isEmpty()) {
            byName.get(d).put(name, g);
            if (!name.equals(g.originName)) {
                stats.renamed.incrementAndGet();
            }
        }
        // what arrived before the name was granted
        if (!g.cache.isEmpty()) {
            sendCached(g, p, null);
        }
        sendExtra(g, p, null);
        ext.copied(g.origin, g.originKey, d, name);
    }

    private void onRenamed(Endpoint d, long key, String newName) {
        Map<Long, Proxy> m = byProxyKey.get(d);
        Proxy p = m == null ? null : m.get(key);
        if (p == null || p.state != Proxy.State.LIVE || newName == null || newName.isEmpty() || newName.equals(p.name)) {
            return;
        }
        Map<String, GwObject> names = byName.get(d);
        if (p.name != null && names.get(p.name) == p.object) {
            names.remove(p.name);
        }
        boolean wasRenamed = p.name != null && !p.name.equals(p.object.originName);
        p.name = newName;
        names.put(newName, p.object);
        boolean isRenamed = !newName.equals(p.object.originName);
        if (isRenamed != wasRenamed) {
            if (isRenamed) {
                stats.renamed.incrementAndGet();
            } else {
                stats.renamed.decrementAndGet();
            }
        }
    }

    private void onRegistrationFailed(Endpoint d, long gwId, String reason) {
        if (ext.registrationFailed(d, gwId, reason)) {
            return;
        }
        GwObject g = objects.get(gwId);
        Proxy p = g == null ? null : g.proxies.get(d);
        if (p != null && p.state == Proxy.State.PENDING) {
            p.state = Proxy.State.FAILED;
            proxiesPending.decrementAndGet();
            proxiesFailed.incrementAndGet();
            stats.problem("could not create " + g.originName + " in " + d.id() + ": " + reason);
        }
    }

    private void onReflected(Endpoint s, long key, int[] attrs, byte[][] values, byte[] tag) {
        Map<Long, GwObject> mine = byOrigin.get(s);
        GwObject g = mine == null ? null : mine.get(key);
        if (g == null) {
            if (!ext.reflected(s, key, attrs, values)) {
                stats.unknownObjects.incrementAndGet();
            }
            return;
        }
        for (int i = 0; i < attrs.length; i++) {
            if (values[i] != null) {
                g.cache.put(attrs[i], values[i]);
            }
        }
        for (Proxy p : g.proxies.values()) {
            if (p.state != Proxy.State.LIVE) {
                continue;
            }
            Endpoint d = p.endpoint;
            ClassRoute.Converted c = p.route.convert(attrs, values, renamer(s, d), stats);
            if (c != null) {
                d.updateObject(p.key, c.ids, c.values, tag);
                stats.updatesRouted.incrementAndGet();
            }
        }
        ext.observed(s, key, attrs, values);
    }

    /** Sends the cached values (all, or only {@code onlyDestination} members) to one copy. */
    private void sendCached(GwObject g, Proxy p, int[] onlyDestination) {
        int[] ids;
        byte[][] vals;
        if (onlyDestination == null) {
            ids = new int[g.cache.size()];
            vals = new byte[g.cache.size()][];
            int i = 0;
            for (Map.Entry<Integer, byte[]> e : g.cache.entrySet()) {
                ids[i] = e.getKey();
                vals[i] = e.getValue();
                i++;
            }
        } else {
            List<Integer> l = new ArrayList<Integer>();
            for (int dm : onlyDestination) {
                MemberRoute m = p.route.byDestination(dm);
                if (m != null && g.cache.containsKey(m.sourceMember)) {
                    l.add(m.sourceMember);
                }
            }
            ids = new int[l.size()];
            vals = new byte[l.size()][];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = l.get(i);
                vals[i] = g.cache.get(ids[i]);
            }
        }
        if (ids.length == 0) {
            return;
        }
        ClassRoute.Converted c = p.route.convert(ids, vals, renamer(g.origin, p.endpoint), stats);
        if (c != null) {
            p.endpoint.updateObject(p.key, c.ids, c.values, null);
            stats.updatesRouted.incrementAndGet();
        }
    }

    private void onRemoved(Endpoint s, long key, byte[] tag) {
        Map<Long, GwObject> mine = byOrigin.get(s);
        GwObject g = mine == null ? null : mine.remove(key);
        if (g != null) {
            ext.unbridged(s, key);
            removeEverywhere(g, tag, true);
        } else {
            ext.removed(s, key);
        }
    }

    private void removeEverywhere(GwObject g, byte[] tag, boolean deleteCopies) {
        g.removed = true;
        objects.remove(g.id);
        bridged.decrementAndGet();
        Map<String, GwObject> names = byName.get(g.origin);
        if (names != null && names.get(g.originName) == g) {
            names.remove(g.originName);
        }
        for (Proxy p : g.proxies.values()) {
            forgetProxy(p);
            if (deleteCopies && p.state == Proxy.State.LIVE && p.endpoint.state().isUp()) {
                p.endpoint.deleteObject(p.key, tag);
            }
        }
        g.proxies.clear();
    }

    private void forgetProxy(Proxy p) {
        switch (p.state) {
            case LIVE:
                proxiesLive.decrementAndGet();
                byProxyKey.get(p.endpoint).remove(p.key);
                if (p.name != null) {
                    Map<String, GwObject> n = byName.get(p.endpoint);
                    if (n.get(p.name) == p.object) {
                        n.remove(p.name);
                    }
                }
                break;
            case PENDING:
                proxiesPending.decrementAndGet();
                break;
            default:
                proxiesFailed.decrementAndGet();
        }
    }

    private void onReceived(Endpoint s, int ic, int[] params, byte[][] values, byte[] tag) {
        ext.received(s, ic, params, values);
        Map<Endpoint, RouteTable> row = tables.get(s);
        if (row == null) {
            return;
        }
        for (Endpoint d : endpoints) {
            if (d == s || !d.state().isUp()) {
                continue;
            }
            RouteTable t = row.get(d);
            ClassRoute cr = t == null ? null : t.interactionRoute(ic);
            if (cr == null) {
                continue;
            }
            ClassRoute.Converted c = cr.convert(params, values, renamer(s, d), stats);
            if (c == null && params.length > 0 && !cr.isEmpty()) {
                continue;                                   // every parameter failed: do not send an empty one
            }
            if (c == null) {
                c = new ClassRoute.Converted(new int[0], new byte[0][]);
            }
            byte[][] out = ext.rewrite(s, d, cr.destinationClass, c.ids, c.values);
            if (out == null) {
                continue;
            }
            d.sendInteraction(cr.destinationClass, c.ids, out, tag);
            stats.interactionsRouted.incrementAndGet();
        }
    }

    private void onProvide(Endpoint d, long key, int[] attrs) {
        Map<Long, Proxy> m = byProxyKey.get(d);
        Proxy p = m == null ? null : m.get(key);
        if (p == null || p.state != Proxy.State.LIVE) {
            ext.provide(d, key, attrs);
            return;
        }
        sendCached(p.object, p, attrs);
        sendExtra(p.object, p, attrs);
        stats.providesAnswered.incrementAndGet();
        forwardMisses(p, attrs);
    }

    /**
     * 0.9.19: asks the object's origin for the requested attributes the cache has no value of
     * (yet). The answer comes back as an ordinary update and goes to every copy. The same
     * attribute of an object is asked for at most every {@code router.provide.forwardintervalms};
     * DIS origins are not asked (DIS has no such request; its heartbeats bring the values).
     */
    private void forwardMisses(Proxy p, int[] destinationMembers) {
        GwObject g = p.object;
        Endpoint o = g.origin;
        if (!cfg.isProvideForward() || g.removed || !o.state().isUp() || o.type().isDis()) {
            return;
        }
        long now = System.currentTimeMillis();
        int interval = cfg.getProvideForwardIntervalMillis();
        int[] ask = new int[destinationMembers.length];
        int n = 0;
        for (int dm : destinationMembers) {
            MemberRoute m = p.route.byDestination(dm);
            if (m == null || g.cache.containsKey(m.sourceMember)) {
                continue;
            }
            Long last = g.asked.get(m.sourceMember);
            if (last != null && now - last < interval) {
                continue;
            }
            boolean dup = false;
            for (int i = 0; i < n; i++) {
                dup |= ask[i] == m.sourceMember;
            }
            if (!dup) {
                ask[n++] = m.sourceMember;
            }
        }
        if (n == 0) {
            return;
        }
        int[] members = java.util.Arrays.copyOf(ask, n);
        for (int sm : members) {
            g.asked.put(sm, now);
        }
        o.requestUpdate(g.originKey, members);
        stats.providesForwarded.incrementAndGet();
    }

    private void onStateChanged(Endpoint e, EndpointState state) {
        ext.stateChanged(e, state);
        if (state == EndpointState.JOINED) {
            // after a reconnect: create the copies again in this endpoint
            for (GwObject g : new ArrayList<GwObject>(objects.values())) {
                if (g.origin != e && !g.proxies.containsKey(e)) {
                    createProxy(g, e);
                }
            }
            return;
        }
        if (state == EndpointState.LOST || state == EndpointState.FAILED || state == EndpointState.CLOSED) {
            // the endpoint forgot everything: its objects are gone, its copies too
            Map<Long, GwObject> mine = byOrigin.get(e);
            if (mine != null) {
                for (GwObject g : new ArrayList<GwObject>(mine.values())) {
                    ext.unbridged(e, g.originKey);
                    removeEverywhere(g, null, true);
                }
                mine.clear();
            }
            for (GwObject g : objects.values()) {
                Proxy p = g.proxies.remove(e);
                if (p != null) {
                    forgetProxy(p);
                }
            }
            Map<Long, Proxy> pk = byProxyKey.get(e);
            if (pk != null) {
                pk.clear();
            }
            Map<String, GwObject> names = byName.get(e);
            if (names != null) {
                names.clear();
            }
        }
    }

    /** Object instance names from s as they are known in d. */
    private UnaryOperator<String> renamer(final Endpoint s, final Endpoint d) {
        return name -> {
            if (name == null || name.isEmpty()) {
                return name;
            }
            Map<String, GwObject> m = byName.get(s);
            GwObject g = m == null ? null : m.get(name);
            if (g == null) {
                stats.unresolvedReferences.incrementAndGet();
                return name;
            }
            if (g.origin == d) {
                return g.originName;
            }
            Proxy p = g.proxies.get(d);
            if (p == null || p.state == Proxy.State.FAILED) {
                stats.unresolvedReferences.incrementAndGet();
                return name;
            }
            return p.name != null ? p.name : p.preferredName != null ? p.preferredName : name;
        };
    }

    // ================================================================== reports

    @Override
    public Json.Writable compatibility() {
        return () -> {
            Json.JsonArray a = Json.array();
            for (Endpoint s : endpoints) {
                for (Endpoint d : endpoints) {
                    RouteTable t = table(s, d);
                    if (t != null) {
                        a.add(t);
                    }
                }
            }
            return a;
        };
    }

    @Override
    public Json.Writable objects() {
        return () -> {
            Json.JsonObject o = Json.object().put("bridged", bridged.get()).put("copies", proxiesLive.get())
                    .put("pending", proxiesPending.get()).put("failed", proxiesFailed.get());
            Map<String, Object> more = new LinkedHashMap<String, Object>();
            ext.describe(more);
            for (Map.Entry<String, Object> e : more.entrySet()) {
                o.put(e.getKey(), e.getValue());
            }
            return o;
        };
    }

    /** @return the route tables, for tests */
    public RouteTable routeTable(String from, String to) {
        for (Endpoint s : endpoints) {
            if (s.id().equals(from)) {
                for (Endpoint d : endpoints) {
                    if (d.id().equals(to)) {
                        return table(s, d);
                    }
                }
            }
        }
        return null;
    }

    // ================================================================== inbound

    /** Endpoint threads: queue only. */
    private final class Inbound implements EndpointListener {
        @Override
        public void discovered(Endpoint from, long key, int objectClass, String name, String producer) {
            enqueue(() -> onDiscovered(from, key, objectClass, name), false);
        }

        @Override
        public void reflected(Endpoint from, long key, int[] attributes, byte[][] values, byte[] tag, String producer) {
            enqueue(() -> onReflected(from, key, attributes, values, tag), true);
        }

        @Override
        public void removed(Endpoint from, long key, byte[] tag) {
            enqueue(() -> onRemoved(from, key, tag), false);
        }

        @Override
        public void received(Endpoint from, int interactionClass, int[] parameters, byte[][] values, byte[] tag, String producer) {
            enqueue(() -> onReceived(from, interactionClass, parameters, values, tag), false);
        }

        @Override
        public void provideRequested(Endpoint from, long key, int[] attributes) {
            enqueue(() -> onProvide(from, key, attributes), false);
        }

        @Override
        public void registered(Endpoint from, long gwId, long key, String name) {
            enqueue(() -> onRegistered(from, gwId, key, name), false);
        }

        @Override
        public void renamed(Endpoint from, long key, String newName) {
            enqueue(() -> onRenamed(from, key, newName), false);
        }

        @Override
        public void registrationFailed(Endpoint from, long gwId, String reason) {
            enqueue(() -> onRegistrationFailed(from, gwId, reason), false);
        }

        @Override
        public void stateChanged(Endpoint from, EndpointState state, String message) {
            enqueue(() -> onStateChanged(from, state), false);
        }
    }
}
