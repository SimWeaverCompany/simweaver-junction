package com.simweaver.rift.map;

import com.simweaver.common.config.SimwProperties;
import com.simweaver.rift.map.shape.ShapeSource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The background maps of the map view: every raster in the map folder, served as Web Mercator
 * JPEG tiles that are made when first asked for and kept in memory and on disk.
 *
 * <p>Server settings ({@code etc/server.root.settings}):
 * <pre>
 * map.enabled=true                 false: no map anywhere (API, datasource page)
 * map.file=                        the default map: an image file anywhere (relative: to the runtime folder)
 * map.files=                       more maps outside map.dir, ';'-separated files
 * map.dir=maps/raster              one sub folder (or file) per map: *.tif, *.tiff (also *.png, *.jpg)
 * map.cache.dir=maps/cache         tiles on disk (rebuilt when the raster file changes)
 * map.cache.memorymb=48            tiles kept in memory
 * map.maxzoom=0                    0 = from the raster (Natural Earth 10 m: 6); deeper zooms scale the tiles
 * map.default=                     id of the default map, when map.file is not set (default: the first)
 * map.jpegquality=0.85
 * map.background=#9db7c9           where the raster has no data
 * map.threads=2                    tile makers (low priority)
 * map.overlays=                    shapefiles drawn over the map, ';'-separated .shp files or .zip packages
 * map.overlays.dir=maps/overlays   every .shp and .zip package in it (and its sub folders) is an overlay too
 * map.overlay.&lt;id&gt;.*              the style of an overlay (see {@link VectorOverlay})
 * </pre>
 */
public final class MapService {

    private static final String CACHE_VERSION = "1";

    private final boolean enabled;
    private final File dir;
    private final File defaultFile;
    private final List<File> extraFiles = new ArrayList<>();
    private final File cacheDir;
    private final float quality;
    private final int background;
    private final int maxZoomSetting;
    private String defaultId;
    private final long memoryBudget;
    private final Map<String, RasterMap> maps = new LinkedHashMap<>();
    private final List<String> problems = new ArrayList<>();
    private final ExecutorService makers;
    private final Map<String, CompletableFuture<byte[]>> inflight = new ConcurrentHashMap<>();
    private final Map<String, byte[]> memory = new LinkedHashMap<>(256, 0.75f, true);
    private long memoryBytes;
    private final AtomicLong tilesMade = new AtomicLong();
    private final AtomicLong tilesServed = new AtomicLong();
    /** The shapefile overlays by id, in the order of the overlay menu; each loads in the background. */
    private final Map<String, OverlayEntry> overlays = new LinkedHashMap<>();
    private final ExecutorService overlayLoader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rift-map-overlays");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        return t;
    });
    private final AtomicLong overlayTilesMade = new AtomicLong();
    /** A 1 x 1 transparent PNG: the answer for a tile where an overlay has nothing. */
    static final byte[] EMPTY_PNG = emptyPng();

    /** An overlay being loaded or loaded. */
    static final class OverlayEntry {
        final String id;
        final ShapeSource source;
        final VectorOverlay.Style style;
        final CompletableFuture<VectorOverlay> loaded;

        OverlayEntry(String id, ShapeSource source, VectorOverlay.Style style, CompletableFuture<VectorOverlay> loaded) {
            this.id = id;
            this.source = source;
            this.style = style;
            this.loaded = loaded;
        }

        /** @return the overlay when it is loaded, else null */
        VectorOverlay now() {
            return loaded.isDone() && !loaded.isCompletedExceptionally() ? loaded.join() : null;
        }
    }

    /**
     * @param cfg        the server settings
     * @param runtimeDir relative paths are relative to it
     */
    public MapService(SimwProperties cfg, File runtimeDir) {
        this.enabled = cfg.getBoolean("map.enabled", true);
        this.dir = resolve(runtimeDir, cfg.getString("map.dir", "maps/raster"));
        String file = cfg.getString("map.file", "").trim();
        this.defaultFile = file.isEmpty() ? null : resolve(runtimeDir, file);
        for (String f : cfg.getString("map.files", "").split(";")) {
            if (!f.trim().isEmpty()) {
                extraFiles.add(resolve(runtimeDir, f.trim()));
            }
        }
        this.cacheDir = resolve(runtimeDir, cfg.getString("map.cache.dir", "maps/cache"));
        this.quality = (float) Math.max(0.3, Math.min(1.0, parseDouble(cfg.getString("map.jpegquality", "0.85"), 0.85)));
        this.background = parseColour(cfg.getString("map.background", "#9db7c9"), 0x9db7c9);
        this.maxZoomSetting = cfg.getInt("map.maxzoom", 0);
        this.defaultId = cfg.getString("map.default", "").trim();
        this.memoryBudget = Math.max(4, cfg.getInt("map.cache.memorymb", 48)) * 1024L * 1024L;
        int threads = Math.max(1, Math.min(8, cfg.getInt("map.threads", 2)));
        AtomicInteger n = new AtomicInteger();
        this.makers = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "rift-map-tiles-" + n.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY + 1);
            return t;
        });
        if (enabled) {
            discover();
            discoverOverlays(cfg, runtimeDir);
        }
    }

    /**
     * map.overlays first, then the .shp files and .zip packages under map.overlays.dir, sorted;
     * each layer is loaded in the background. A layer that is in a zip package and also unpacked
     * (same .shp name) is used once: the unpacked files.
     */
    private void discoverOverlays(SimwProperties cfg, File runtimeDir) {
        List<ShapeSource> sources = new ArrayList<>();
        for (String f : cfg.getString("map.overlays", "").split(";")) {
            if (f.trim().isEmpty()) {
                continue;
            }
            File file = resolve(runtimeDir, f.trim());
            String n = file.getName().toLowerCase(Locale.ROOT);
            if (file.isFile() && n.endsWith(".shp")) {
                sources.add(ShapeSource.file(file));
            } else if (file.isFile() && n.endsWith(".zip")) {
                addZip(file, sources, true);
            } else {
                problems.add("map.overlays: " + file.getPath() + (file.isFile() ? " is not a .shp or .zip file" : " not found"));
            }
        }
        File odir = resolve(runtimeDir, cfg.getString("map.overlays.dir", "maps/overlays"));
        List<File> found = new ArrayList<>();
        collectShp(odir, found, 0);
        for (File f : found) {                                // unpacked layers before packed ones
            if (!f.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                sources.add(ShapeSource.file(f));
            }
        }
        for (File f : found) {
            if (f.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                addZip(f, sources, false);
            }
        }
        List<ShapeSource> used = new ArrayList<>();
        java.util.Map<String, ShapeSource> byName = new java.util.HashMap<>();
        for (ShapeSource src : sources) {
            if (used.stream().anyMatch(u -> u.sameAs(src))) {
                continue;
            }
            ShapeSource twin = byName.get(src.baseName.toLowerCase(Locale.ROOT));
            if (twin != null && twin.getClass() != src.getClass()) {
                problems.add(src.shortName() + ": not used, it is the same layer as " + twin.shortName());
                continue;
            }
            byName.putIfAbsent(src.baseName.toLowerCase(Locale.ROOT), src);
            used.add(src);
        }
        int index = 0;
        for (ShapeSource src : used) {
            String base = src.baseName.replaceAll("[^A-Za-z0-9_.-]", "_");
            String id = base;
            for (int i = 2; overlays.containsKey(id) || maps.containsKey(id); i++) {
                id = base + "-" + i;
            }
            VectorOverlay.Style style = new VectorOverlay.Style(cfg, id, src.baseName.replace('_', ' '), index++);
            final String fid = id;
            CompletableFuture<VectorOverlay> load = CompletableFuture.supplyAsync(() -> {
                try {
                    VectorOverlay o = VectorOverlay.load(fid, src, style);
                    System.out.println("Map overlay:   " + o);
                    for (String w : o.warnings) {
                        System.out.println("Map overlay:   " + fid + ": " + w);
                    }
                    return o;
                } catch (IOException | RuntimeException e) {
                    String m = src.shortName() + ": cannot be read as an overlay (" + e.getMessage() + ")";
                    synchronized (problems) {
                        problems.add(m);
                    }
                    System.out.println("Map overlay:   " + m);
                    throw new java.util.concurrent.CompletionException(e);
                }
            }, overlayLoader);
            overlays.put(id, new OverlayEntry(id, src, style, load));
        }
    }

    /** The layers of a zip package; a package without any, or that cannot be read, is a problem. */
    private void addZip(File zip, List<ShapeSource> out, boolean named) {
        try {
            List<ShapeSource> layers = ShapeSource.zip(zip);
            if (layers.isEmpty()) {
                problems.add((named ? "map.overlays: " : "") + zip.getName() + " has no .shp file");
            }
            out.addAll(layers);
        } catch (IOException | RuntimeException e) {
            problems.add((named ? "map.overlays: " : "") + zip.getName() + ": cannot be read as a zip package (" + e.getMessage() + ")");
        }
    }

    /** The .shp and .zip files of a folder and its sub folders (3 levels). */
    private static void collectShp(File dir, List<File> out, int depth) {
        File[] fs = dir.listFiles();
        if (fs == null || depth > 3) {
            return;
        }
        Arrays.sort(fs, Comparator.comparing(File::getName));
        for (File f : fs) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (f.isDirectory()) {
                collectShp(f, out, depth + 1);
            } else if (n.endsWith(".shp") || n.endsWith(".zip")) {
                out.add(f);
            }
        }
    }

    private static byte[] emptyPng() {
        try {
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static File resolve(File base, String path) {
        File f = new File(path);
        return f.isAbsolute() ? f : new File(base, path);
    }

    /**
     * The maps, in the order the picker shows them: {@code map.file} first, then {@code map.files},
     * then what {@code map.dir} holds (one per sub folder). A file named twice is opened once.
     */
    private void discover() {
        List<File> files = new ArrayList<>();
        if (defaultFile != null) {
            if (isImage(defaultFile)) {
                files.add(defaultFile);
            } else {
                problems.add("map.file " + defaultFile.getPath() + (defaultFile.isFile() ? " is not a .tif, .tiff, .png or .jpg" : " not found"));
            }
        }
        for (File f : extraFiles) {
            if (isImage(f)) {
                files.add(f);
            } else {
                problems.add("map.files: " + f.getPath() + (f.isFile() ? " is not a .tif, .tiff, .png or .jpg" : " not found"));
            }
        }
        if (dir.isDirectory()) {
            File[] top = dir.listFiles();
            if (top != null) {
                Arrays.sort(top, Comparator.comparing(File::getName));
                for (File f : top) {
                    if (f.isDirectory()) {
                        File[] inner = f.listFiles();
                        if (inner != null) {
                            Arrays.sort(inner, Comparator.comparing(File::getName));
                            for (File g : inner) {
                                if (isImage(g)) {
                                    files.add(g);
                                    break;                     // one map per folder
                                }
                            }
                        }
                    } else if (isImage(f)) {
                        files.add(f);
                    }
                }
            }
        } else if (files.isEmpty()) {
            problems.add("map folder " + dir.getPath() + " not found: the map has no background");
        }
        java.util.Set<File> seen = new java.util.HashSet<>();
        for (File f : files) {
            File canon = canonical(f);
            if (!seen.add(canon)) {
                continue;
            }
            String base = f.getName().replaceFirst("\\.[^.]+$", "").replaceAll("[^A-Za-z0-9_.-]", "_");
            String id = base;
            for (int i = 2; maps.containsKey(id); i++) {
                id = base + "-" + i;                       // same file name in two places
            }
            try {
                RasterMap m = RasterMap.open(id, nameOf(f), f, maxZoomSetting);
                maps.put(id, m);
                problems.addAll(m.warnings);
                if (defaultFile != null && canon.equals(canonical(defaultFile))) {
                    defaultId = id;                        // map.file wins over map.default
                }
            } catch (IOException | RuntimeException e) {
                problems.add(f.getName() + ": cannot be read as a map (" + e.getMessage() + ")");
            }
        }
        if (!defaultId.isEmpty() && !maps.containsKey(defaultId)) {
            problems.add("map.default=" + defaultId + " is not one of the maps; the first is used");
        }
        if (maps.isEmpty() && problems.isEmpty()) {
            problems.add("no map in " + dir.getPath() + " (a folder per map with a .tif), and no map.file");
        }
    }

    private static File canonical(File f) {
        try {
            return f.getCanonicalFile();
        } catch (IOException e) {
            return f.getAbsoluteFile();
        }
    }

    private static boolean isImage(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        return f.isFile() && (n.endsWith(".tif") || n.endsWith(".tiff") || n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg"));
    }

    private static String nameOf(File f) {
        String base = f.getName().replaceFirst("\\.[^.]+$", "");
        if (base.startsWith("HYP_")) {
            return "Natural Earth " + (base.contains("_HR_") ? "cross blended hypso (high res)" : base.contains("_LR_") ? "cross blended hypso (low res)" : "cross blended hypso");
        }
        if (base.startsWith("NE1_") || base.startsWith("NE2_")) {
            return "Natural Earth " + base.substring(0, 3);
        }
        return base.replace('_', ' ');
    }

    // ------------------------------------------------------------------ state

    public boolean isEnabled() {
        return enabled;
    }

    /** For the server log. */
    public List<String> describe() {
        List<String> l = new ArrayList<>();
        if (!enabled) {
            l.add("off (map.enabled=false)");
            return l;
        }
        String def = defaultMapId();
        for (RasterMap m : maps.values()) {
            l.add(m.id + (m.id.equals(def) && maps.size() > 1 ? " (default)" : "") + ": " + m.file.getPath() + ", " + m.raster.width() + " x " + m.raster.height() + ", " + m.raster.describe()
                    + "; " + m.geo.source + "; zoom 0-" + m.maxZoom);
        }
        for (OverlayEntry o : overlays.values()) {
            l.add("overlay " + o.id + ": " + o.source.name() + " (loading)");
        }
        synchronized (problems) {
            l.addAll(problems);
        }
        return l;
    }

    /** GET /api/maps */
    public Map<String, Object> listing() {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("enabled", enabled);
        List<Object> list = new ArrayList<>();
        for (RasterMap m : maps.values()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", m.id);
            e.put("name", m.name);
            e.put("file", m.file.getName());
            e.put("width", m.raster.width());
            e.put("height", m.raster.height());
            double[] b = m.bounds();
            Map<String, Object> bounds = new LinkedHashMap<>();
            bounds.put("west", b[0]);
            bounds.put("south", b[1]);
            bounds.put("east", b[2]);
            bounds.put("north", b[3]);
            e.put("bounds", bounds);
            e.put("minzoom", 0);
            e.put("maxzoom", m.maxZoom);
            e.put("tilesize", RasterMap.TILE);
            e.put("format", "jpg");
            String v = stamp(m);
            e.put("version", v);
            e.put("tiles", "maps/" + m.id + "/tiles/{z}/{x}/{y}.jpg?v=" + v);
            e.put("reader", m.raster.describe());
            e.put("georeference", m.geo.source);
            e.put("attribution", m.file.getName().startsWith("HYP_") || m.file.getName().startsWith("NE") ? "Natural Earth" : "");
            list.add(e);
        }
        o.put("maps", list);
        o.put("default", defaultMapId());
        o.put("overlays", overlayListing());
        synchronized (problems) {
            o.put("problems", new ArrayList<>(problems));
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("tilesmade", tilesMade.get());
        stats.put("tilesserved", tilesServed.get());
        synchronized (memory) {
            stats.put("memorytiles", memory.size());
            stats.put("memorybytes", memoryBytes);
        }
        stats.put("overlaytilesmade", overlayTilesMade.get());
        o.put("stats", stats);
        return o;
    }

    /** The overlays for GET /api/maps: style, state, and the URLs of their tiles and labels. */
    private List<Object> overlayListing() {
        List<Object> list = new ArrayList<>();
        for (OverlayEntry oe : overlays.values()) {
            VectorOverlay.Style st = oe.style;
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", oe.id);
            e.put("name", st.name);
            e.put("file", oe.source.shortName());
            e.put("visible", st.visible);
            e.put("color", VectorOverlay.hex(st.color));
            if (st.fill != null) {
                e.put("fill", VectorOverlay.hex(st.fill));
            }
            e.put("minzoom", st.minZoom);
            e.put("maxzoom", st.maxZoom);
            VectorOverlay v = oe.now();
            if (v == null) {
                e.put("state", oe.loaded.isCompletedExceptionally() ? "failed" : "loading");
            } else {
                e.put("state", "ready");
                e.put("kind", v.kind());
                e.put("features", v.shape.features.size());
                e.put("crs", v.shape.projection.description);
                float[] b = v.bounds();
                Map<String, Object> bounds = new LinkedHashMap<>();
                bounds.put("west", b[0]);
                bounds.put("south", b[1]);
                bounds.put("east", b[2]);
                bounds.put("north", b[3]);
                e.put("bounds", bounds);
                String ver = v.stamp(CACHE_VERSION);
                e.put("version", ver);
                e.put("tiles", "maps/overlays/" + oe.id + "/tiles/{z}/{x}/{y}.png?v=" + ver);
                if (v.hasLabels()) {
                    e.put("labels", "maps/overlays/" + oe.id + "/labels");
                    e.put("labelcount", v.labelCount());
                    e.put("labelminzoom", st.labelMinZoom);
                    e.put("labelcolor", st.labelColor);
                }
                if (!v.warnings.isEmpty()) {
                    e.put("warnings", v.warnings);
                }
            }
            list.add(e);
        }
        return list;
    }

    boolean hasOverlay(String id) {
        return overlays.containsKey(id);
    }

    /**
     * An overlay tile as PNG: from memory, from disk, or drawn now. An empty tile is
     * {@link #EMPTY_PNG}.
     *
     * @return the bytes, or null when the overlay or tile does not exist
     */
    public byte[] overlayTile(String id, int z, int x, int y) throws IOException {
        OverlayEntry oe = enabled ? overlays.get(id) : null;
        if (oe == null || z < 0 || z > VectorOverlay.MAX_ZOOM || x < 0 || y < 0 || x >= (1 << z) || y >= (1 << z)) {
            return null;
        }
        VectorOverlay v = overlay(oe);
        String key = "overlay:" + id + "/" + z + "/" + x + "/" + y;
        byte[] b;
        synchronized (memory) {
            b = memory.get(key);
        }
        if (b == null) {
            CompletableFuture<byte[]> f = inflight.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> {
                try {
                    return loadOverlayTile(v, z, x, y);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }, makers));
            try {
                b = f.get(60, TimeUnit.SECONDS);
            } catch (Exception e) {
                Throwable c = e.getCause() != null ? e.getCause() : e;
                throw new IOException("overlay tile " + key + ": " + c.getMessage(), c);
            } finally {
                inflight.remove(key, f);
            }
            remember(key, b);
        }
        tilesServed.incrementAndGet();
        return b;
    }

    /** The overlay, waiting for it to be loaded (at most a minute). */
    private static VectorOverlay overlay(OverlayEntry oe) throws IOException {
        try {
            return oe.loaded.get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            throw new IOException("overlay " + oe.id + " is not available: " + (c.getCause() != null ? c.getCause().getMessage() : c.getMessage()), c);
        }
    }

    private byte[] loadOverlayTile(VectorOverlay v, int z, int x, int y) throws IOException {
        Path dirOf = cacheDir.toPath().resolve("overlays").resolve(v.id).resolve(v.stamp(CACHE_VERSION));
        Path p = dirOf.resolve(z + "/" + x + "/" + y + ".png");
        Path none = dirOf.resolve(z + "/" + x + "/" + y + ".none");
        if (Files.isRegularFile(p)) {
            return Files.readAllBytes(p);
        }
        if (Files.isRegularFile(none)) {
            return EMPTY_PNG;
        }
        byte[] b = v.render(z, x, y);
        overlayTilesMade.incrementAndGet();
        try {
            if (overlayCacheChecked.add(v.id)) {
                cleanOld(dirOf.getParent(), dirOf.getFileName().toString());
            }
            Files.createDirectories(p.getParent());
            if (b == null) {
                Files.write(none, new byte[0]);
            } else {
                Path tmp = p.resolveSibling(p.getFileName() + "." + Thread.currentThread().getId() + ".tmp");
                Files.write(tmp, b);
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (IOException | UnsupportedOperationException e) {
            // the disk cache is optional
        }
        return b == null ? EMPTY_PNG : b;
    }

    private final java.util.Set<String> overlayCacheChecked = ConcurrentHashMap.newKeySet();

    /**
     * The labels of an overlay in a box, for GET /api/maps/overlays/{id}/labels.
     *
     * @param bbox west, south, east, north
     * @return {"labels":[{t, lat, lon, k}], "truncated"}, or null for an unknown overlay
     */
    public Map<String, Object> overlayLabels(String id, double[] bbox, int zoom) throws IOException {
        OverlayEntry oe = enabled ? overlays.get(id) : null;
        if (oe == null) {
            return null;
        }
        VectorOverlay v = overlay(oe);
        List<Map<String, Object>> l = v.labels(bbox[0], bbox[1], bbox[2], bbox[3], zoom);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("overlay", id);
        m.put("zoom", zoom);
        m.put("labels", l);
        m.put("truncated", l.size() >= VectorOverlay.MAX_LABELS);
        return m;
    }

    /** The map shown when the user has not picked one: map.file, else map.default, else the first. */
    public String defaultMapId() {
        return maps.containsKey(defaultId) ? defaultId : maps.isEmpty() ? null : maps.keySet().iterator().next();
    }

    boolean hasMap(String id) {
        return maps.containsKey(id);
    }

    int maxZoom(String id) {
        RasterMap m = maps.get(id);
        return m == null ? -1 : m.maxZoom;
    }

    // ------------------------------------------------------------------ tiles

    /**
     * A tile as JPEG: from memory, from disk, or made now (on a tile maker thread).
     *
     * @return the bytes, or null when the map or tile does not exist
     */
    public byte[] tile(String id, int z, int x, int y) throws IOException {
        RasterMap m = enabled ? maps.get(id) : null;
        if (m == null || z < 0 || z > m.maxZoom || x < 0 || y < 0 || x >= (1 << z) || y >= (1 << z)) {
            return null;
        }
        String key = id + "/" + z + "/" + x + "/" + y;
        byte[] b;
        synchronized (memory) {
            b = memory.get(key);
        }
        if (b == null) {
            CompletableFuture<byte[]> f = inflight.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> {
                try {
                    return load(m, z, x, y);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }, makers));
            try {
                b = f.get(60, TimeUnit.SECONDS);
            } catch (Exception e) {
                Throwable c = e.getCause() != null ? e.getCause() : e;
                throw new IOException("tile " + key + ": " + c.getMessage(), c);
            } finally {
                inflight.remove(key, f);
            }
            remember(key, b);
        }
        tilesServed.incrementAndGet();
        return b;
    }

    private byte[] load(RasterMap m, int z, int x, int y) throws IOException {
        Path p = tileDir(m).resolve(z + "/" + x + "/" + y + ".jpg");
        if (Files.isRegularFile(p)) {
            return Files.readAllBytes(p);
        }
        byte[] b = RasterMap.jpeg(m.render(z, x, y, background), quality);
        tilesMade.incrementAndGet();
        try {
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling(p.getFileName() + "." + Thread.currentThread().getId() + ".tmp");
            Files.write(tmp, b);
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | UnsupportedOperationException e) {
            // the disk cache is optional: a read-only folder only costs time
        }
        return b;
    }

    private final Map<String, Path> tileDirs = new ConcurrentHashMap<>();

    /** maps/cache/<id>/<stamp>: the stamp changes with the raster file and the tile settings. */
    private Path tileDir(RasterMap m) {
        return tileDirs.computeIfAbsent(m.id, id -> {
            String stamp = stamp(m);
            Path base = cacheDir.toPath().resolve(id);
            Path d = base.resolve(stamp);
            cleanOld(base, stamp);
            return d;
        });
    }

    /**
     * Version of a map's tiles: changes with the raster file (size, time) and the tile settings.
     * Names the disk cache folder, and goes into the tile URLs ({@code ?v=}) so that browsers
     * do not show tiles of a replaced file from their own cache.
     */
    private String stamp(RasterMap m) {
        return Long.toHexString((m.file.length() * 31 + m.file.lastModified()) * 31
                + (CACHE_VERSION + "|" + quality + "|" + background + "|" + m.maxZoom).hashCode());
    }

    /** Deletes the tiles of an older version of a raster (our own cache folders only). */
    private static void cleanOld(Path base, String keep) {
        File[] old = base.toFile().listFiles();
        if (old == null) {
            return;
        }
        for (File f : old) {
            if (f.isDirectory() && !f.getName().equals(keep) && f.getName().matches("[0-9a-f]+")) {
                deleteTree(f);
            }
        }
    }

    private static void deleteTree(File f) {
        File[] c = f.listFiles();
        if (c != null) {
            for (File x : c) {
                deleteTree(x);
            }
        }
        if (!f.delete()) {
            f.deleteOnExit();
        }
    }

    private void remember(String key, byte[] b) {
        synchronized (memory) {
            if (memory.put(key, b) == null) {
                memoryBytes += b.length;
            }
            java.util.Iterator<Map.Entry<String, byte[]>> it = memory.entrySet().iterator();
            while (memoryBytes > memoryBudget && it.hasNext()) {
                memoryBytes -= it.next().getValue().length;
                it.remove();
            }
        }
    }

    /** Makes the coarse zoom levels in the background, so the first view is quick. */
    public void prewarm(int upToZoom) {
        if (!enabled) {
            return;
        }
        for (RasterMap m : maps.values()) {
            for (int z = 0; z <= Math.min(upToZoom, m.maxZoom); z++) {
                int n = 1 << z;
                for (int x = 0; x < n; x++) {
                    for (int y = 0; y < n; y++) {
                        final int fz = z, fx = x, fy = y;
                        // done on the maker thread itself: waiting for another maker could deadlock
                        makers.submit(() -> {
                            String key = m.id + "/" + fz + "/" + fx + "/" + fy;
                            synchronized (memory) {
                                if (memory.containsKey(key)) {
                                    return;
                                }
                            }
                            CompletableFuture<byte[]> f = new CompletableFuture<>();
                            if (inflight.putIfAbsent(key, f) != null) {
                                return;
                            }
                            try {
                                byte[] b = load(m, fz, fx, fy);
                                remember(key, b);
                                f.complete(b);
                            } catch (IOException | RuntimeException e) {
                                f.completeExceptionally(e);        // made again when asked for
                            } finally {
                                inflight.remove(key, f);
                            }
                        });
                    }
                }
            }
        }
    }

    public void shutdown() {
        makers.shutdownNow();
        overlayLoader.shutdownNow();
        for (RasterMap m : maps.values()) {
            try {
                m.raster.close();
            } catch (IOException e) {
                // closing anyway
            }
        }
    }

    // ------------------------------------------------------------------ parsing

    private static double parseDouble(String s, double d) {
        try {
            return Double.parseDouble(s.trim());
        } catch (RuntimeException e) {
            return d;
        }
    }

    static int parseColour(String s, int d) {
        String t = s == null ? "" : s.trim().replace("#", "");
        if (t.matches("[0-9A-Fa-f]{6}")) {
            return Integer.parseInt(t, 16);
        }
        return d;
    }

    /** For tests: waits for the overlays to load and returns them by id (null = failed). */
    Map<String, VectorOverlay> overlaysLoaded() {
        Map<String, VectorOverlay> m = new LinkedHashMap<>();
        for (OverlayEntry oe : overlays.values()) {
            try {
                m.put(oe.id, oe.loaded.get(60, TimeUnit.SECONDS));
            } catch (Exception e) {
                m.put(oe.id, null);
            }
        }
        return m;
    }

    /** For tests: the map ids. */
    List<String> ids() {
        return Collections.unmodifiableList(new ArrayList<>(maps.keySet()));
    }
}
