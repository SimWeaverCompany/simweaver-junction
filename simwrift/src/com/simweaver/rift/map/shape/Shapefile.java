package com.simweaver.rift.map.shape;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * An ESRI shapefile ({@code .shp}, with its {@code .dbf}, {@code .prj} and {@code .cpg}), read
 * into longitude/latitude geometry (ESRI Shapefile Technical Description, 1998), from a folder
 * or straight out of a zip package ({@link ShapeSource}).
 *
 * <p>Shape types: Point, MultiPoint, PolyLine and Polygon, also their Z and M variants (Z and M
 * are ignored). MultiPatch records and records that cannot be read are skipped and counted. The
 * file is read record by record (any size); coordinates are kept as floats (about a metre).
 */
public final class Shapefile {

    /** What a feature is drawn as. */
    public enum Kind { POINT, LINE, POLYGON }

    /** One record: its parts as longitude/latitude pairs. */
    public static final class Feature {
        public final Kind kind;
        /** The shape record number - 1, which is also the .dbf row. */
        public final int row;
        /** lon0, lat0, lon1, lat1, ... of all parts. */
        public final float[] coords;
        /** Start of each part in points (not floats); a point feature has one part per point. */
        public final int[] parts;
        /** west, south, east, north */
        public final float[] bbox;

        Feature(Kind kind, int row, float[] coords, int[] parts) {
            this.kind = kind;
            this.row = row;
            this.coords = coords;
            this.parts = parts;
            float w = Float.MAX_VALUE, s = Float.MAX_VALUE, e = -Float.MAX_VALUE, n = -Float.MAX_VALUE;
            for (int i = 0; i + 1 < coords.length; i += 2) {
                w = Math.min(w, coords[i]);
                e = Math.max(e, coords[i]);
                s = Math.min(s, coords[i + 1]);
                n = Math.max(n, coords[i + 1]);
            }
            this.bbox = new float[] {w, s, e, n};
        }

        /** @return number of points */
        public int points() {
            return coords.length / 2;
        }

        /** @return points of part i */
        public int partLength(int i) {
            return (i + 1 < parts.length ? parts[i + 1] : points()) - parts[i];
        }
    }

    /** Where the layer's files are (a folder or a zip package). */
    public final ShapeSource source;
    public final List<Feature> features;
    public final Dbf dbf;
    public final Projection projection;
    /** Notes for the log (no .prj, skipped records, datum). */
    public final List<String> warnings;
    /** The shape type of the file header (1 point, 3 line, 5 polygon, 8 multipoint, +10 Z, +20 M). */
    public final int shapeType;

    private Shapefile(ShapeSource source, List<Feature> features, Dbf dbf, Projection projection, List<String> warnings, int shapeType) {
        this.source = source;
        this.features = Collections.unmodifiableList(features);
        this.dbf = dbf;
        this.projection = projection;
        this.warnings = Collections.unmodifiableList(warnings);
        this.shapeType = shapeType;
    }

    /** @return "point", "line", "polygon", "multipoint" (+ " Z" / " M"), "multipatch" or "null" */
    public static String typeName(int t) {
        int base = t % 10;
        String z = t >= 30 ? "" : t >= 20 ? " M" : t >= 10 ? " Z" : "";
        switch (t == 31 ? 31 : base) {
            case 0: return "null";
            case 1: return "point" + z;
            case 3: return "line" + z;
            case 5: return "polygon" + z;
            case 8: return "multipoint" + z;
            case 31: return "multipatch";
            default: return "type " + t;
        }
    }

    /**
     * Reads a shapefile.
     *
     * @param shp    the .shp file
     * @param fields .dbf fields to keep (labels, ranks); the .dbf is optional
     * @throws IOException when the .shp cannot be read, or its coordinate system is not supported
     */
    public static Shapefile read(File shp, Collection<String> fields) throws IOException {
        return read(ShapeSource.file(shp), fields);
    }

    /**
     * Reads a shapefile from a folder or a zip package.
     *
     * @param src    the layer's files
     * @param fields .dbf fields to keep (labels, ranks); the .dbf is optional
     * @throws IOException when the .shp cannot be read, or its coordinate system is not supported
     */
    public static Shapefile read(ShapeSource src, Collection<String> fields) throws IOException {
        List<String> warnings = new ArrayList<>();
        String shpName = src.baseName + ".shp";
        Projection proj = projectionOf(src, shpName);
        List<Feature> out = new ArrayList<>();
        int skipped = 0;
        int headerType;
        double[] lonlat = new double[2];
        InputStream shpIn = src.open(".shp");
        if (shpIn == null) {
            throw new FileNotFoundException(src.name() + " not found");
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(shpIn, 1 << 16))) {
            byte[] h = new byte[100];
            in.readFully(h);
            ByteBuffer hb = ByteBuffer.wrap(h);
            if (hb.getInt(0) != 9994) {
                throw new IOException(shpName + " is not a shapefile (file code " + hb.getInt(0) + ")");
            }
            long fileBytes = (hb.getInt(24) & 0xFFFFFFFFL) * 2;
            hb.order(ByteOrder.LITTLE_ENDIAN);
            headerType = hb.getInt(32);
            double xmin = hb.getDouble(36), ymin = hb.getDouble(44), xmax = hb.getDouble(52), ymax = hb.getDouble(60);
            if (proj == null) {
                if (xmin >= -180.5 && xmax <= 180.5 && ymin >= -90.5 && ymax <= 90.5) {
                    proj = Projection.geographic("longitude/latitude (no .prj)", null);
                    warnings.add("no .prj: the coordinates look like longitude/latitude and are taken as such");
                } else {
                    throw new IOException(shpName + ": no .prj, and the coordinates (" + (long) xmin + ".." + (long) xmax
                            + ", " + (long) ymin + ".." + (long) ymax + ") are not longitude/latitude");
                }
            }
            long pos = 100;
            byte[] content = new byte[0];
            while (fileBytes <= 0 || pos < fileBytes) {
                int recNo;
                int words;
                try {
                    recNo = in.readInt();
                    words = in.readInt();
                } catch (EOFException e) {
                    break;
                }
                pos += 8;
                int len = words * 2;
                if (len < 4 || len > 512 * 1024 * 1024) {
                    warnings.add("record " + recNo + ": bad length " + len + "; the rest of the file is skipped");
                    break;
                }
                if (content.length < len) {
                    content = new byte[Math.max(len, content.length * 2)];
                }
                try {
                    in.readFully(content, 0, len);
                } catch (EOFException e) {
                    warnings.add("the file ends inside record " + recNo);
                    break;
                }
                pos += len;
                ByteBuffer b = ByteBuffer.wrap(content, 0, len).order(ByteOrder.LITTLE_ENDIAN);
                try {
                    Feature f = record(b, len, recNo - 1, proj, lonlat);
                    if (f != null) {
                        out.add(f);
                    } else if (b.getInt(0) != 0) {
                        skipped++;
                    }
                } catch (RuntimeException e) {
                    skipped++;
                }
            }
        }
        if (skipped > 0) {
            warnings.add(skipped + " records skipped (multipatch or unreadable)");
        }
        if (proj.warning != null) {
            warnings.add(proj.warning);
        }
        Dbf dbf = null;
        if (fields != null && !fields.isEmpty()) {
            try (InputStream dbfIn = src.open(".dbf")) {
                if (dbfIn == null) {
                    warnings.add("no .dbf: no labels");
                } else {
                    dbf = Dbf.read(dbfIn, text(src, ".cpg", StandardCharsets.ISO_8859_1), fields, src.baseName + ".dbf");
                }
            }
        }
        return new Shapefile(src, out, dbf, proj, warnings, headerType);
    }

    private static Projection projectionOf(ShapeSource src, String shpName) throws IOException {
        String wkt = text(src, ".prj", StandardCharsets.UTF_8);
        if (wkt == null) {
            return null;
        }
        try {
            return Projection.fromWkt(wkt);
        } catch (IllegalArgumentException e) {
            throw new IOException(shpName + ": " + e.getMessage());
        }
    }

    /** A small text file of the layer (.prj, .cpg), or null when there is none. */
    private static String text(ShapeSource src, String ext, java.nio.charset.Charset cs) throws IOException {
        try (InputStream in = src.open(ext)) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) {
                b.write(buf, 0, n);
                if (b.size() > 1 << 20) {
                    throw new IOException(src.baseName + ext + " is too large");
                }
            }
            return new String(b.toByteArray(), cs);
        }
    }

    private static Feature record(ByteBuffer b, int len, int row, Projection proj, double[] ll) {
        int type = b.getInt(0);
        switch (type) {
            case 0:
                return null;
            case 1: case 11: case 21: {
                proj.toLonLat(b.getDouble(4), b.getDouble(12), ll);
                return new Feature(Kind.POINT, row, new float[] {(float) ll[0], (float) ll[1]}, new int[] {0});
            }
            case 8: case 18: case 28: {
                int n = b.getInt(36);
                if (n < 0 || 40 + 16L * n > len) {
                    throw new IllegalArgumentException("bad multipoint");
                }
                float[] c = new float[2 * n];
                int[] parts = new int[n];
                for (int i = 0; i < n; i++) {
                    proj.toLonLat(b.getDouble(40 + 16 * i), b.getDouble(48 + 16 * i), ll);
                    c[2 * i] = (float) ll[0];
                    c[2 * i + 1] = (float) ll[1];
                    parts[i] = i;
                }
                return n == 0 ? null : new Feature(Kind.POINT, row, c, parts);
            }
            case 3: case 13: case 23: case 5: case 15: case 25: {
                int np = b.getInt(36), n = b.getInt(40);
                if (np < 1 || n < 1 || 44 + 4L * np + 16L * n > len) {
                    throw new IllegalArgumentException("bad polyline/polygon");
                }
                int[] parts = new int[np];
                for (int i = 0; i < np; i++) {
                    parts[i] = b.getInt(44 + 4 * i);
                    if (parts[i] < 0 || parts[i] >= n || (i > 0 && parts[i] < parts[i - 1])) {
                        throw new IllegalArgumentException("bad part index");
                    }
                }
                int at = 44 + 4 * np;
                float[] c = new float[2 * n];
                for (int i = 0; i < n; i++) {
                    proj.toLonLat(b.getDouble(at + 16 * i), b.getDouble(at + 8 + 16 * i), ll);
                    c[2 * i] = (float) ll[0];
                    c[2 * i + 1] = (float) ll[1];
                }
                int base = type % 10;
                return new Feature(base == 5 ? Kind.POLYGON : Kind.LINE, row, c, parts);
            }
            default:
                return null;                               // 31 multipatch, or unknown
        }
    }
}
