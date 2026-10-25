package com.simweaver.rift.map;

import com.simweaver.common.config.SimwProperties;
import com.simweaver.rift.map.shape.ShapeSource;
import com.simweaver.rift.map.shape.Shapefile;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One shapefile drawn over the background map: transparent PNG tiles (Web Mercator, made on
 * demand) with its points, lines and polygons in the colours of its server settings, and label
 * anchors (from a .dbf field) that the browser draws, so labels are never cut at tile edges.
 *
 * <p>Settings, by the overlay's id (its .shp file name without .shp, also inside a zip package;
 * see {@link Style}):
 * <pre>
 * map.overlay.&lt;id&gt;.name=          display name (default: the file name)
 * map.overlay.&lt;id&gt;.color=#d35400   lines and outlines, #rrggbb or #rrggbbaa
 * map.overlay.&lt;id&gt;.width=1.2       line width in pixels
 * map.overlay.&lt;id&gt;.fill=           polygon fill (#rrggbbaa; default none)
 * map.overlay.&lt;id&gt;.point=3         point radius in pixels (0: no symbol)
 * map.overlay.&lt;id&gt;.pointfill=      point fill (default: the colour)
 * map.overlay.&lt;id&gt;.minzoom=0 / .maxzoom=18     zoom levels it is drawn at
 * map.overlay.&lt;id&gt;.label=          .dbf field with the label text (e.g. NAME); none = no labels
 * map.overlay.&lt;id&gt;.labelminzoom=6  from this zoom level on
 * map.overlay.&lt;id&gt;.labelrank=      numeric .dbf field: which labels win when they overlap
 * map.overlay.&lt;id&gt;.labelorder=desc desc: a higher value wins (population), asc: a lower one (rank 1 first)
 * map.overlay.&lt;id&gt;.labelcolor=#1b2631
 * map.overlay.&lt;id&gt;.visible=true    shown when the user has not chosen
 * </pre>
 * Thread safe after {@link #load}.
 */
public final class VectorOverlay {

    static final int MAX_ZOOM = 18;
    static final int MAX_LABELS = 600;
    private static final int TILE = RasterMap.TILE;
    private static final Color[] PALETTE = {
        new Color(0xd35400), new Color(0x1f618d), new Color(0x7d3c98), new Color(0x117a65), new Color(0xb03a2e), new Color(0x5d6d7e)};

    /** The drawing settings of an overlay. */
    public static final class Style {
        final String name;
        final Color color;
        final float width;
        final Color fill;
        final float point;
        final Color pointFill;
        final int minZoom;
        final int maxZoom;
        final String labelField;
        final int labelMinZoom;
        final String rankField;
        final boolean rankDescending;
        final String labelColor;
        final boolean visible;
        final String text;

        Style(SimwProperties cfg, String id, String defaultName, int index) {
            String p = "map.overlay." + id + ".";
            this.name = cfg.getString(p + "name", defaultName).trim();
            this.color = colour(cfg.getString(p + "color", ""), PALETTE[index % PALETTE.length]);
            this.width = (float) clamp(number(cfg.getString(p + "width", "1.2"), 1.2), 0, 20);
            this.fill = colour(cfg.getString(p + "fill", ""), null);
            this.point = (float) clamp(number(cfg.getString(p + "point", "3"), 3), 0, 30);
            this.pointFill = colour(cfg.getString(p + "pointfill", ""), color);
            this.minZoom = (int) clamp(cfg.getInt(p + "minzoom", 0), 0, MAX_ZOOM);
            this.maxZoom = (int) clamp(cfg.getInt(p + "maxzoom", MAX_ZOOM), minZoom, MAX_ZOOM);
            String lf = cfg.getString(p + "label", "").trim();
            this.labelField = lf.isEmpty() ? null : lf;
            this.labelMinZoom = (int) clamp(cfg.getInt(p + "labelminzoom", 6), 0, MAX_ZOOM);
            String rf = cfg.getString(p + "labelrank", "").trim();
            this.rankField = rf.isEmpty() ? null : rf;
            this.rankDescending = !cfg.getString(p + "labelorder", "desc").trim().equalsIgnoreCase("asc");
            Color lc = colour(cfg.getString(p + "labelcolor", ""), new Color(0x1b2631));
            this.labelColor = hex(lc);
            this.visible = cfg.getBoolean(p + "visible", true);
            this.text = name + "|" + hex(color) + "|" + width + "|" + (fill == null ? "" : hex(fill)) + "|" + point + "|"
                    + hex(pointFill) + "|" + minZoom + "|" + maxZoom;
        }
    }

    /** A label: its text and where it goes. */
    static final class Label {
        final String text;
        final float lon;
        final float lat;
        final double rank;
        final Shapefile.Kind kind;

        Label(String text, float lon, float lat, double rank, Shapefile.Kind kind) {
            this.text = text;
            this.lon = lon;
            this.lat = lat;
            this.rank = rank;
            this.kind = kind;
        }
    }

    final String id;
    final ShapeSource source;
    final Style style;
    final Shapefile shape;
    private final List<Label> labels;
    private final int[][] grid;
    private final int gridN;
    private final float[] bbox;
    final List<String> warnings = new ArrayList<>();

    private VectorOverlay(String id, ShapeSource source, Style style, Shapefile shape, List<Label> labels, int[][] grid, int gridN, float[] bbox) {
        this.id = id;
        this.source = source;
        this.style = style;
        this.shape = shape;
        this.labels = labels;
        this.grid = grid;
        this.gridN = gridN;
        this.bbox = bbox;
        warnings.addAll(shape.warnings);
    }

    /** Reads the shapefile and builds the index and the labels. */
    static VectorOverlay load(String id, ShapeSource shp, Style style) throws IOException {
        List<String> fields = new ArrayList<>();
        if (style.labelField != null) {
            fields.add(style.labelField);
        }
        if (style.rankField != null) {
            fields.add(style.rankField);
        }
        Shapefile s = Shapefile.read(shp, fields);
        List<String> extra = new ArrayList<>();
        if (style.labelField != null && s.dbf != null && !s.dbf.has(style.labelField)) {
            extra.add("map.overlay." + id + ".label: no field " + style.labelField + " in the .dbf (it has " + s.dbf.fields + ")");
        }
        if (style.rankField != null && s.dbf != null && !s.dbf.has(style.rankField)) {
            extra.add("map.overlay." + id + ".labelrank: no field " + style.rankField + " in the .dbf");
        }
        float[] bb = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (Shapefile.Feature f : s.features) {
            bb[0] = Math.min(bb[0], f.bbox[0]);
            bb[1] = Math.min(bb[1], f.bbox[1]);
            bb[2] = Math.max(bb[2], f.bbox[2]);
            bb[3] = Math.max(bb[3], f.bbox[3]);
        }
        if (s.features.isEmpty()) {
            bb = new float[] {0, 0, 0, 0};
        }
        int n = (int) clamp(Math.sqrt(s.features.size()) * 2, 4, 256);
        int[][] grid = index(s.features, bb, n);
        List<Label> labels = new ArrayList<>();
        if (style.labelField != null && s.dbf != null && s.dbf.has(style.labelField)) {
            for (Shapefile.Feature f : s.features) {
                String t = s.dbf.get(style.labelField, f.row);
                if (t == null || t.isEmpty()) {
                    continue;
                }
                double rank = Double.NaN;
                if (style.rankField != null) {
                    String r = s.dbf.get(style.rankField, f.row);
                    rank = number(r, Double.NaN);
                }
                float[] at = anchor(f);
                labels.add(new Label(t, at[0], at[1], rank, f.kind));
            }
            // the order labels win in: by rank, then file order
            final boolean desc = style.rankDescending;
            List<Label> sorted = new ArrayList<>(labels);
            Collections.sort(sorted, (a, b) -> {
                boolean an = Double.isNaN(a.rank), bn = Double.isNaN(b.rank);
                if (an || bn) {
                    return an == bn ? 0 : an ? 1 : -1;
                }
                return desc ? Double.compare(b.rank, a.rank) : Double.compare(a.rank, b.rank);
            });
            labels = sorted;
        }
        VectorOverlay o = new VectorOverlay(id, shp, style, s, Collections.unmodifiableList(labels), grid, n, bb);
        o.warnings.addAll(extra);
        return o;
    }

    /** Feature numbers per grid cell over the overlay's bounding box. */
    private static int[][] index(List<Shapefile.Feature> fs, float[] bb, int n) {
        int[] counts = new int[n * n];
        double cw = Math.max(1e-9, (bb[2] - bb[0]) / n), ch = Math.max(1e-9, (bb[3] - bb[1]) / n);
        for (Shapefile.Feature f : fs) {
            int x0 = cell(f.bbox[0], bb[0], cw, n), x1 = cell(f.bbox[2], bb[0], cw, n);
            int y0 = cell(f.bbox[1], bb[1], ch, n), y1 = cell(f.bbox[3], bb[1], ch, n);
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    counts[y * n + x]++;
                }
            }
        }
        int[][] g = new int[n * n][];
        for (int i = 0; i < g.length; i++) {
            g[i] = new int[counts[i]];
            counts[i] = 0;
        }
        for (int k = 0; k < fs.size(); k++) {
            Shapefile.Feature f = fs.get(k);
            int x0 = cell(f.bbox[0], bb[0], cw, n), x1 = cell(f.bbox[2], bb[0], cw, n);
            int y0 = cell(f.bbox[1], bb[1], ch, n), y1 = cell(f.bbox[3], bb[1], ch, n);
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    int c = y * n + x;
                    g[c][counts[c]++] = k;
                }
            }
        }
        return g;
    }

    private static int cell(double v, double origin, double size, int n) {
        return (int) clamp(Math.floor((v - origin) / size), 0, n - 1);
    }

    /** Features whose bounding box meets west..east, south..north. */
    private BitSet query(double w, double s, double e, double n) {
        BitSet hit = new BitSet(shape.features.size());
        if (e < bbox[0] || w > bbox[2] || n < bbox[1] || s > bbox[3]) {
            return hit;
        }
        double cw = Math.max(1e-9, (bbox[2] - bbox[0]) / gridN), ch = Math.max(1e-9, (bbox[3] - bbox[1]) / gridN);
        int x0 = cell(w, bbox[0], cw, gridN), x1 = cell(e, bbox[0], cw, gridN);
        int y0 = cell(s, bbox[1], ch, gridN), y1 = cell(n, bbox[1], ch, gridN);
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                for (int k : grid[y * gridN + x]) {
                    if (!hit.get(k)) {
                        float[] b = shape.features.get(k).bbox;
                        if (b[2] >= w && b[0] <= e && b[3] >= s && b[1] <= n) {
                            hit.set(k);
                        }
                    }
                }
            }
        }
        return hit;
    }

    /** Where a feature's label goes: the point; the middle of the longest line part; the centre of the largest ring. */
    static float[] anchor(Shapefile.Feature f) {
        float[] c = f.coords;
        if (f.kind == Shapefile.Kind.POINT) {
            return new float[] {c[0], c[1]};
        }
        int best = 0;
        double bestSize = -1;
        for (int p = 0; p < f.parts.length; p++) {
            int a = f.parts[p], len = f.partLength(p);
            double size = 0;
            if (f.kind == Shapefile.Kind.LINE) {
                for (int i = a + 1; i < a + len; i++) {
                    size += Math.hypot(c[2 * i] - c[2 * i - 2], c[2 * i + 1] - c[2 * i - 1]);
                }
            } else {
                size = Math.abs(ringArea(c, a, len));
            }
            if (size > bestSize) {
                bestSize = size;
                best = p;
            }
        }
        int a = f.parts[best], len = f.partLength(best);
        if (f.kind == Shapefile.Kind.LINE) {
            int mid = a + len / 2;
            return new float[] {c[2 * mid], c[2 * mid + 1]};
        }
        double area = ringArea(c, a, len), cx = 0, cy = 0;
        if (Math.abs(area) < 1e-12) {
            return new float[] {(f.bbox[0] + f.bbox[2]) / 2, (f.bbox[1] + f.bbox[3]) / 2};
        }
        for (int i = a; i < a + len; i++) {
            int j = i + 1 < a + len ? i + 1 : a;
            double cross = (double) c[2 * i] * c[2 * j + 1] - (double) c[2 * j] * c[2 * i + 1];
            cx += (c[2 * i] + c[2 * j]) * cross;
            cy += (c[2 * i + 1] + c[2 * j + 1]) * cross;
        }
        return new float[] {(float) (cx / (6 * area)), (float) (cy / (6 * area))};
    }

    private static double ringArea(float[] c, int a, int len) {
        double s = 0;
        for (int i = a; i < a + len; i++) {
            int j = i + 1 < a + len ? i + 1 : a;
            s += (double) c[2 * i] * c[2 * j + 1] - (double) c[2 * j] * c[2 * i + 1];
        }
        return s / 2;
    }

    // ------------------------------------------------------------------ tiles

    /** @return a PNG tile, or null when nothing of the overlay is in it */
    byte[] render(int z, int tx, int ty) throws IOException {
        if (z < style.minZoom || z > style.maxZoom || shape.features.isEmpty()) {
            return null;
        }
        double world = TILE * Math.pow(2, z);
        double margin = style.point + style.width + 2;
        double ox = tx * TILE, oy = ty * TILE;
        double w = xToLon(ox - margin, world), e = xToLon(ox + TILE + margin, world);
        double n = yToLat(oy - margin, world), s = yToLat(oy + TILE + margin, world);
        BitSet hit = query(w, s, e, n);
        if (hit.isEmpty()) {
            return null;
        }
        BufferedImage img = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            BasicStroke stroke = new BasicStroke(style.width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
            boolean drawn = false;
            // polygons first, then lines, then points on top
            for (Shapefile.Kind kind : new Shapefile.Kind[] {Shapefile.Kind.POLYGON, Shapefile.Kind.LINE, Shapefile.Kind.POINT}) {
                for (int k = hit.nextSetBit(0); k >= 0; k = hit.nextSetBit(k + 1)) {
                    Shapefile.Feature f = shape.features.get(k);
                    if (f.kind != kind) {
                        continue;
                    }
                    drawn |= draw(g, f, world, ox, oy, stroke);
                }
            }
            if (!drawn) {
                return null;
            }
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private boolean draw(Graphics2D g, Shapefile.Feature f, double world, double ox, double oy, BasicStroke stroke) {
        float[] c = f.coords;
        if (f.kind == Shapefile.Kind.POINT) {
            if (style.point <= 0) {
                return false;
            }
            boolean any = false;
            double r = style.point;
            for (int i = 0; i + 1 < c.length; i += 2) {
                double x = lonToX(c[i], world) - ox, y = latToY(c[i + 1], world) - oy;
                if (x < -r - 2 || y < -r - 2 || x > TILE + r + 2 || y > TILE + r + 2) {
                    continue;
                }
                Ellipse2D dot = new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r);
                g.setColor(style.pointFill);
                g.fill(dot);
                g.setStroke(new BasicStroke(Math.max(0.8f, style.width * 0.7f)));
                g.setColor(darker(style.color));
                g.draw(dot);
                any = true;
            }
            return any;
        }
        // too small to see at this zoom: nothing (a polygon) or a dot (a line)
        double bw = (lonToX(f.bbox[2], world) - lonToX(f.bbox[0], world));
        double bh = (latToY(f.bbox[1], world) - latToY(f.bbox[3], world));
        if (bw < 0.4 && bh < 0.4) {
            return false;
        }
        Path2D.Double path = new Path2D.Double(Path2D.WIND_EVEN_ODD);
        boolean polygon = f.kind == Shapefile.Kind.POLYGON;
        for (int p = 0; p < f.parts.length; p++) {
            int a = f.parts[p], len = f.partLength(p);
            if (len < 2) {
                continue;
            }
            double lx = 0, ly = 0;
            for (int i = 0; i < len; i++) {
                int q = 2 * (a + i);
                double x = lonToX(c[q], world) - ox, y = latToY(c[q + 1], world) - oy;
                if (i == 0) {
                    path.moveTo(x, y);
                } else if (i == len - 1 || Math.abs(x - lx) >= 0.6 || Math.abs(y - ly) >= 0.6) {
                    path.lineTo(x, y);                         // points closer than 0.6 px are left out
                } else {
                    continue;
                }
                lx = x;
                ly = y;
            }
            if (polygon) {
                path.closePath();
            }
        }
        if (polygon && style.fill != null) {
            g.setColor(style.fill);
            g.fill(path);
        }
        if (style.width > 0) {
            g.setStroke(stroke);
            g.setColor(style.color);
            g.draw(path);
        }
        return true;
    }

    // ------------------------------------------------------------------ labels

    /**
     * The labels in a box at a zoom level, in the order they win when they overlap.
     *
     * @return {t: text, lat, lon, k: "point"/"line"/"polygon"}, at most {@link #MAX_LABELS}
     */
    List<Map<String, Object>> labels(double w, double s, double e, double n, int zoom) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (labels.isEmpty() || zoom < style.labelMinZoom || zoom < style.minZoom || zoom > style.maxZoom) {
            return out;
        }
        boolean wraps = w > e;                                 // across 180
        for (Label l : labels) {
            boolean in = l.lat >= s && l.lat <= n && (wraps ? (l.lon >= w || l.lon <= e) : (l.lon >= w && l.lon <= e));
            if (!in) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("t", l.text);
            m.put("lat", Math.round(l.lat * 1e5) / 1e5);
            m.put("lon", Math.round(l.lon * 1e5) / 1e5);
            m.put("k", l.kind.name().toLowerCase(Locale.ROOT));
            out.add(m);
            if (out.size() >= MAX_LABELS) {
                break;
            }
        }
        return out;
    }

    boolean hasLabels() {
        return !labels.isEmpty();
    }

    int labelCount() {
        return labels.size();
    }

    /** "point", "line", "polygon" or "mixed" */
    String kind() {
        Shapefile.Kind k = null;
        for (Shapefile.Feature f : shape.features) {
            if (k == null) {
                k = f.kind;
            } else if (k != f.kind) {
                return "mixed";
            }
        }
        return k == null ? "empty" : k.name().toLowerCase(Locale.ROOT);
    }

    float[] bounds() {
        return bbox.clone();
    }

    /** Version of the tiles: the files (size, time) and the style. */
    String stamp(String cacheVersion) {
        long h = source.stamp();
        h = h * 31 + (cacheVersion + "|" + style.text).hashCode();
        return Long.toHexString(h);
    }

    // ------------------------------------------------------------------ helpers

    static double lonToX(double lon, double world) {
        return (lon + 180) / 360 * world;
    }

    static double latToY(double lat, double world) {
        double l = Math.toRadians(clamp(lat, -85.05113, 85.05113));
        return (1 - Math.log(Math.tan(l) + 1 / Math.cos(l)) / Math.PI) / 2 * world;
    }

    static double xToLon(double x, double world) {
        return x / world * 360 - 180;
    }

    static double yToLat(double y, double world) {
        double n = Math.PI * (1 - 2 * y / world);
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }

    private static Color darker(Color c) {
        return new Color(Math.max(0, (int) (c.getRed() * 0.6)), Math.max(0, (int) (c.getGreen() * 0.6)),
                Math.max(0, (int) (c.getBlue() * 0.6)), Math.max(c.getAlpha(), 200));
    }

    static Color colour(String s, Color def) {
        String t = s == null ? "" : s.trim().replace("#", "");
        if (t.matches("[0-9A-Fa-f]{6}")) {
            return new Color(Integer.parseInt(t, 16));
        }
        if (t.matches("[0-9A-Fa-f]{8}")) {
            long v = Long.parseLong(t, 16);
            return new Color((int) (v >>> 24) & 0xFF, (int) (v >>> 16) & 0xFF, (int) (v >>> 8) & 0xFF, (int) v & 0xFF);
        }
        return def;
    }

    static String hex(Color c) {
        return c.getAlpha() == 255 ? String.format(Locale.ROOT, "#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue())
                : String.format(Locale.ROOT, "#%02x%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue(), c.getAlpha());
    }

    private static double number(String s, double d) {
        try {
            return s == null ? d : Double.parseDouble(s.trim());
        } catch (RuntimeException e) {
            return d;
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    /** For tests: the label texts in winning order. */
    List<String> labelTexts() {
        List<String> l = new ArrayList<>();
        for (Label x : labels) {
            l.add(x.text);
        }
        return l;
    }

    @Override
    public String toString() {
        return id + ": " + source.name() + ", " + shape.features.size() + " " + kind() + " features ("
                + Shapefile.typeName(shape.shapeType) + "), " + shape.projection.description
                + (labels.isEmpty() ? "" : ", " + labels.size() + " labels (" + style.labelField + ")")
                + (Arrays.equals(bbox, new float[4]) ? "" : String.format(Locale.ROOT, ", %.3f..%.3f E, %.3f..%.3f N",
                        bbox[0], bbox[2], bbox[1], bbox[3]));
    }
}
