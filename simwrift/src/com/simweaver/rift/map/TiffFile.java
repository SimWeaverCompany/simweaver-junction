package com.simweaver.rift.map;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * The first image directory of a TIFF or BigTIFF file: its tags, read without decoding pixels.
 * Also the GeoTIFF tags (ModelPixelScale, ModelTiepoint, ModelTransformation, GeoKeyDirectory).
 */
final class TiffFile {

    static final int IMAGE_WIDTH = 256, IMAGE_LENGTH = 257, BITS_PER_SAMPLE = 258, COMPRESSION = 259,
            PHOTOMETRIC = 262, STRIP_OFFSETS = 273, SAMPLES_PER_PIXEL = 277, ROWS_PER_STRIP = 278,
            STRIP_BYTE_COUNTS = 279, PLANAR_CONFIGURATION = 284, COLOR_MAP = 320, TILE_WIDTH = 322,
            TILE_LENGTH = 323, TILE_OFFSETS = 324, TILE_BYTE_COUNTS = 325, SAMPLE_FORMAT = 339,
            MODEL_PIXEL_SCALE = 33550, MODEL_TIEPOINT = 33922, MODEL_TRANSFORMATION = 34264, GEO_KEY_DIRECTORY = 34735;

    final Path path;
    final ByteOrder order;
    final boolean big;
    private final Map<Integer, Object> tags = new HashMap<>();     // long[] or double[] or String

    private TiffFile(Path path, ByteOrder order, boolean big) {
        this.path = path;
        this.order = order;
        this.big = big;
    }

    static TiffFile read(Path path) throws IOException {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer h = read(ch, 0, 16, ByteOrder.LITTLE_ENDIAN);
            ByteOrder order;
            if (h.get(0) == 'I' && h.get(1) == 'I') {
                order = ByteOrder.LITTLE_ENDIAN;
            } else if (h.get(0) == 'M' && h.get(1) == 'M') {
                order = ByteOrder.BIG_ENDIAN;
            } else {
                throw new IOException("not a TIFF file");
            }
            h.order(order);
            int magic = h.getShort(2) & 0xFFFF;
            boolean big = magic == 43;
            if (magic != 42 && !big) {
                throw new IOException("not a TIFF file (magic " + magic + ")");
            }
            TiffFile t = new TiffFile(path, order, big);
            long ifd = big ? h.getLong(8) : h.getInt(4) & 0xFFFFFFFFL;
            t.readDirectory(ch, ifd);
            return t;
        }
    }

    private void readDirectory(FileChannel ch, long ifd) throws IOException {
        int countSize = big ? 8 : 2, entrySize = big ? 20 : 12;
        ByteBuffer c = read(ch, ifd, countSize, order);
        long n = big ? c.getLong(0) : c.getShort(0) & 0xFFFF;
        if (n <= 0 || n > 4096) {
            throw new IOException("bad TIFF directory");
        }
        ByteBuffer d = read(ch, ifd + countSize, (int) (n * entrySize), order);
        for (int i = 0; i < n; i++) {
            int at = i * entrySize;
            int tag = d.getShort(at) & 0xFFFF;
            int type = d.getShort(at + 2) & 0xFFFF;
            long count = big ? d.getLong(at + 4) : d.getInt(at + 4) & 0xFFFFFFFFL;
            int size = typeSize(type);
            if (size == 0 || count <= 0 || count > 50_000_000L) {
                continue;
            }
            long bytes = count * size;
            int inline = big ? 8 : 4;
            ByteBuffer v;
            if (bytes <= inline) {
                v = ByteBuffer.allocate(inline).order(order);
                for (int k = 0; k < inline; k++) {
                    v.put(k, d.get(at + (big ? 12 : 8) + k));
                }
            } else {
                long off = big ? d.getLong(at + 12) : d.getInt(at + 8) & 0xFFFFFFFFL;
                v = read(ch, off, (int) bytes, order);
            }
            tags.put(tag, decode(v, type, (int) count));
        }
    }

    private static int typeSize(int type) {
        switch (type) {
            case 1: case 2: case 6: case 7: return 1;
            case 3: case 8: return 2;
            case 4: case 9: case 11: return 4;
            case 5: case 10: case 12: case 16: case 17: return 8;
            default: return 0;
        }
    }

    private static Object decode(ByteBuffer v, int type, int count) {
        if (type == 2) {
            byte[] b = new byte[count];
            for (int i = 0; i < count; i++) {
                b[i] = v.get(i);
            }
            return new String(b, java.nio.charset.StandardCharsets.ISO_8859_1).replace("\0", "").trim();
        }
        if (type == 11 || type == 12 || type == 5 || type == 10) {
            double[] out = new double[count];
            for (int i = 0; i < count; i++) {
                switch (type) {
                    case 11: out[i] = v.getFloat(i * 4); break;
                    case 12: out[i] = v.getDouble(i * 8); break;
                    case 5: out[i] = (v.getInt(i * 8) & 0xFFFFFFFFL) / (double) Math.max(1, v.getInt(i * 8 + 4) & 0xFFFFFFFFL); break;
                    default: out[i] = v.getInt(i * 8) / (double) (v.getInt(i * 8 + 4) == 0 ? 1 : v.getInt(i * 8 + 4));
                }
            }
            return out;
        }
        long[] out = new long[count];
        for (int i = 0; i < count; i++) {
            switch (type) {
                case 1: case 7: out[i] = v.get(i) & 0xFF; break;
                case 6: out[i] = v.get(i); break;
                case 3: out[i] = v.getShort(i * 2) & 0xFFFF; break;
                case 8: out[i] = v.getShort(i * 2); break;
                case 4: out[i] = v.getInt(i * 4) & 0xFFFFFFFFL; break;
                case 9: out[i] = v.getInt(i * 4); break;
                default: out[i] = v.getLong(i * 8);
            }
        }
        return out;
    }

    static ByteBuffer read(FileChannel ch, long pos, int len, ByteOrder order) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(len).order(order);
        while (b.hasRemaining()) {
            int r = ch.read(b, pos + b.position());
            if (r < 0) {
                throw new IOException("unexpected end of the TIFF file");
            }
        }
        b.flip();
        return b;
    }

    boolean has(int tag) {
        return tags.containsKey(tag);
    }

    long[] longs(int tag) {
        Object o = tags.get(tag);
        if (o instanceof long[]) {
            return (long[]) o;
        }
        if (o instanceof double[]) {
            double[] d = (double[]) o;
            long[] l = new long[d.length];
            for (int i = 0; i < d.length; i++) {
                l[i] = (long) d[i];
            }
            return l;
        }
        return null;
    }

    long first(int tag, long dflt) {
        long[] l = longs(tag);
        return l == null || l.length == 0 ? dflt : l[0];
    }

    double[] doubles(int tag) {
        Object o = tags.get(tag);
        if (o instanceof double[]) {
            return (double[]) o;
        }
        if (o instanceof long[]) {
            long[] l = (long[]) o;
            double[] d = new double[l.length];
            for (int i = 0; i < l.length; i++) {
                d[i] = l[i];
            }
            return d;
        }
        return null;
    }

    /** A GeoTIFF key from the GeoKeyDirectory (short values only), or -1. */
    int geoKey(int key) {
        long[] k = longs(GEO_KEY_DIRECTORY);
        if (k == null || k.length < 4) {
            return -1;
        }
        int n = (int) k[3];
        for (int i = 0; i < n && 4 + i * 4 + 3 < k.length; i++) {
            if (k[4 + i * 4] == key && k[4 + i * 4 + 1] == 0) {
                return (int) k[4 + i * 4 + 3];
            }
        }
        return -1;
    }
}
