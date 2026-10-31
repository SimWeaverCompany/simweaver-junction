package com.simweaver.common.infra;

import com.simweaver.common.config.SimwProperties;
import com.simweaver.common.json.Json;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The Infra view's collector (0.9.20): every DIS / C-DIS socket and every joined HLA federate of
 * Rift's datasources and Junction's gateways registers a tap here, and the page lists what they
 * have contact with: DIS 6, DIS 7 and C-DIS exercises and HLA 1516-2010 / 1516-2025 federations.
 * The same exercise heard by two sockets is two items (one per source).
 *
 * <p>One per server ({@link #global()}); tests may make their own. Its one daemon thread asks the
 * MOMs for values (fast while someone watches a federation, slowly otherwise) and forgets DIS
 * senders that went quiet long ago.
 *
 * <p>Settings ({@code etc/server.root.settings}): {@code infra.enabled} (true),
 * {@code infra.hla.mom} (true), {@code infra.hla.pollseconds} (5), {@code infra.hla.slowpollseconds}
 * (60), {@code infra.dis.forgetseconds} (600, 0 = never).
 */
public final class InfraRegistry {

    private static final Logger LOG = Logger.getLogger(InfraRegistry.class.getName());

    public static final String DIS6 = "dis6";
    public static final String DIS7 = "dis7";
    public static final String CDIS = "cdis";
    public static final String HLA3 = "hla3";
    public static final String HLA4 = "hla4";
    /** The families in the page's order. */
    public static final List<String> FAMILIES = Collections.unmodifiableList(java.util.Arrays.asList(DIS6, DIS7, CDIS, HLA3, HLA4));

    private static final InfraRegistry GLOBAL = new InfraRegistry();

    private final List<DisTap> dis = new CopyOnWriteArrayList<DisTap>();
    private final List<HlaTap> hla = new CopyOnWriteArrayList<HlaTap>();
    private volatile boolean enabled = true;
    private volatile boolean mom = true;
    private volatile long pollMillis = 5000;
    private volatile long slowPollMillis = 60000;
    private volatile long forgetMillis = 600_000;
    private volatile Set<InetAddress> localAddresses;
    private ScheduledExecutorService executor;

    /** A registry of its own (tests); the server uses {@link #global()}. */
    public InfraRegistry() {
    }

    /** @return the server's registry */
    public static InfraRegistry global() {
        return GLOBAL;
    }

    /** Reads the {@code infra.*} settings. */
    public void configure(SimwProperties p) {
        enabled = p.getBoolean("infra.enabled", true);
        mom = p.getBoolean("infra.hla.mom", true);
        pollMillis = 1000L * Math.max(1, Math.min(3600, p.getInt("infra.hla.pollseconds", 5)));
        slowPollMillis = 1000L * Math.max(pollMillis / 1000, Math.min(3600, p.getInt("infra.hla.slowpollseconds", 60)));
        forgetMillis = 1000L * Math.max(0, p.getInt("infra.dis.forgetseconds", 600));
    }

    public boolean isEnabled() {
        return enabled;
    }

    boolean isMomEnabled() {
        return mom;
    }

    long pollMillis() {
        return pollMillis;
    }

    /** Test hook: the MOM poll intervals in milliseconds. */
    public void setPollMillis(long fast, long slow) {
        this.pollMillis = Math.max(100, fast);
        this.slowPollMillis = Math.max(this.pollMillis, slow);
    }

    // ------------------------------------------------------------------ taps

    /**
     * A DIS or C-DIS socket starts listening.
     *
     * @param where the socket as text, e.g. "UDP port 3000 on all interfaces"
     * @return the tap; with Infra switched off one that ignores everything
     */
    public DisTap openDis(InfraSource source, boolean cdis, int port, String where) {
        DisTap t = new DisTap(this, source, cdis, port, where);
        if (!enabled) {
            t.close();
            return t;
        }
        dis.add(t);
        return t;
    }

    /**
     * A federate has joined.
     *
     * @param connection the LSD / RTI configuration it connected with ("" = the RTI's default)
     * @param port       the MOM access, or null for traffic counts only
     */
    public HlaTap openHla(InfraSource source, boolean hla4, String federation, String federateName, String connection,
                          MomPort port) {
        HlaTap t = new HlaTap(this, source, hla4, federation, federateName, connection, port);
        if (!enabled) {
            t.close();
            return t;
        }
        hla.add(t);
        ensureThread();
        return t;
    }

    void remove(Object tap) {
        dis.remove(tap);
        hla.remove(tap);
    }

    /** Runs a task on the Infra thread (never on the caller's). */
    void execute(Runnable r) {
        ScheduledExecutorService ex = ensureThread();
        try {
            ex.execute(() -> {
                try {
                    r.run();
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "Infra: a task failed", e);
                }
            });
        } catch (RejectedExecutionException ignored) {
            // shutting down
        }
    }

    private synchronized ScheduledExecutorService ensureThread() {
        if (executor == null) {
            ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "simweaver-infra");
                t.setDaemon(true);
                return t;
            });
            ex.setRemoveOnCancelPolicy(true);
            ex.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
            executor = ex;
        }
        return executor;
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (HlaTap t : hla) {
            try {
                t.tick(now, pollMillis, slowPollMillis);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Infra: MOM poll failed", e);
            }
        }
        for (DisTap t : dis) {
            t.prune(now, forgetMillis);
        }
    }

    /** @return true for an address of this computer */
    boolean isLocal(InetAddress a) {
        Set<InetAddress> l = localAddresses;
        if (l == null) {
            l = new HashSet<InetAddress>();
            try {
                for (Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces(); e != null && e.hasMoreElements();) {
                    for (Enumeration<InetAddress> ad = e.nextElement().getInetAddresses(); ad.hasMoreElements();) {
                        l.add(ad.nextElement());
                    }
                }
            } catch (Exception ignored) {
                // none known
            }
            localAddresses = l;
        }
        return a.isLoopbackAddress() || l.contains(a);
    }

    // ------------------------------------------------------------------ items

    /** One exercise of a DIS tap, or one HLA tap's federation. */
    static final class Item {
        final String key;
        final String family;
        final Object tap;
        final int exercise;

        Item(String key, String family, Object tap, int exercise) {
            this.key = key;
            this.family = family;
            this.tap = tap;
            this.exercise = exercise;
        }
    }

    List<Item> items() {
        List<Item> out = new ArrayList<Item>();
        for (DisTap t : dis) {
            out.addAll(t.items());
        }
        for (HlaTap t : hla) {
            String family = t.family();
            out.add(new Item(itemKey(family, family + "|" + t.federation() + "|" + t.source().key()), family, t, 0));
        }
        return out;
    }

    /** A short, stable, URL-safe key: the family and a hash of what identifies the item. */
    static String itemKey(String family, String identity) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(identity.getBytes(StandardCharsets.UTF_8));
            return family + "-" + MomCodec.hex(java.util.Arrays.copyOf(d, 6));
        } catch (java.security.NoSuchAlgorithmException e) {
            return family + "-" + Integer.toHexString(identity.hashCode());
        }
    }

    static String familyLabel(String family) {
        switch (family) {
            case DIS6:
                return "DIS 6";
            case DIS7:
                return "DIS 7";
            case CDIS:
                return "C-DIS";
            case HLA3:
                return "HLA 1516-2010";
            case HLA4:
                return "HLA 1516-2025";
            default:
                return family;
        }
    }

    private Json.JsonObject json(Item it, boolean detail, long now) {
        if (it.tap instanceof DisTap) {
            return ((DisTap) it.tap).json(it.key, it.exercise, it.family, detail, now);
        }
        HlaTap t = (HlaTap) it.tap;
        if (detail) {
            t.viewed(now);
        }
        return t.json(it.key, detail, now);
    }

    /** GET /api/infra: every item, by family. */
    public Json.JsonObject overview() {
        long now = System.currentTimeMillis();
        Map<String, List<Object>> by = new LinkedHashMap<String, List<Object>>();
        for (String f : FAMILIES) {
            by.put(f, new ArrayList<Object>());
        }
        Set<String> sources = new HashSet<String>();
        for (Item it : items()) {
            Json.JsonObject o = json(it, false, now);
            if (o != null) {
                by.get(it.family).add(o);
            }
            sources.add(it.tap instanceof DisTap ? ((DisTap) it.tap).source().key() : ((HlaTap) it.tap).source().key());
        }
        Json.JsonObject o = Json.object();
        o.put("enabled", enabled);
        o.put("now", now);
        o.put("mom", mom);
        o.put("pollseconds", pollMillis / 1000.0);
        Json.JsonObject counts = Json.object();
        for (Map.Entry<String, List<Object>> e : by.entrySet()) {
            o.put(e.getKey(), e.getValue());
            counts.put(e.getKey(), e.getValue().size());
        }
        o.put("counts", counts);
        o.put("listeners", dis.size());
        o.put("federates", hla.size());
        o.put("sources", sources.size());
        return o;
    }

    /**
     * GET /api/infra/items?keys=: the full view of each item; an item that is not there now (its
     * source stopped) is {@code {"key":..,"missing":true}}.
     */
    public Json.JsonObject details(List<String> keys) {
        long now = System.currentTimeMillis();
        Map<String, Item> byKey = new LinkedHashMap<String, Item>();
        for (Item it : items()) {
            byKey.put(it.key, it);
        }
        List<Object> list = new ArrayList<Object>();
        for (String k : keys) {
            Item it = byKey.get(k);
            Json.JsonObject o = it == null ? null : json(it, true, now);
            list.add(o != null ? o : Json.object().put("key", k).put("missing", true));
        }
        return Json.object().put("now", now).put("items", list);
    }

    /** @return the full view of one item, or null */
    public Json.JsonObject detail(String key) {
        long now = System.currentTimeMillis();
        for (Item it : items()) {
            if (it.key.equals(key)) {
                return json(it, true, now);
            }
        }
        return null;
    }

    /** @return the HLA tap of an item key, or null */
    HlaTap hlaTap(String key) {
        for (Item it : items()) {
            if (it.key.equals(key) && it.tap instanceof HlaTap) {
                return (HlaTap) it.tap;
            }
        }
        return null;
    }

    /**
     * Asks the MOM of an HLA item for a federate's publications and subscriptions; the reports
     * appear in the item's detail.
     *
     * @return null when asked; else why not
     */
    public String requestDeclarations(String key, String federateHandle) {
        HlaTap t = hlaTap(key);
        if (t == null) {
            return "no HLA federation " + key;
        }
        java.util.concurrent.CompletableFuture<String> f = new java.util.concurrent.CompletableFuture<String>();
        execute(() -> f.complete(t.requestDeclarations(federateHandle)));
        try {
            return f.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "the request did not go out in time";
        }
    }

    /** Asks the MOM of an HLA item for fresh values now. @return false when there is no such item */
    public boolean refresh(String key) {
        HlaTap t = hlaTap(key);
        if (t == null) {
            return false;
        }
        t.viewed(System.currentTimeMillis());
        execute(t::pollNow);
        return true;
    }
}
