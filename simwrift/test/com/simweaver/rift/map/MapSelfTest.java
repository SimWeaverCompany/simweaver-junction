package com.simweaver.rift.map;

import com.simweaver.common.config.SimwProperties;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Iterator;

/**
 * Map tiles from synthetic rasters whose colour encodes the position (red = longitude, green =
 * latitude), so a tile pixel can be checked against where it should be on the globe.
 *
 * <pre>java -cp out/classes:out/test com.simweaver.rift.map.MapSelfTest [full]</pre>
 * "full" also times a 21600 x 10800 uncompressed raster (the size of Natural Earth 10 m, 667 MB).
 */
public final class MapSelfTest {

    private static int checks, failures;

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("rift-map-test").toFile();

        // ---- whole globe, uncompressed strips of 7 rows, 720 x 360 (0.5 degree pixels)
        File strips = new File(dir, "globe-strips.tif");
        writeUncompressed(strips, 720, 360, 7, null);
        RasterMap a = RasterMap.open("a", "a", strips, 0);
        check("uncompressed reader used: " + a.raster.describe(), a.raster instanceof UncompressedTiffRaster);
        check("whole globe by default: " + a.geo, near(a.geo.west, -180, 1e-9) && near(a.geo.north, 90, 1e-9) && near(a.geo.dLon, 0.5, 1e-12));
        check("native max zoom of a 720 px globe is 1 (log2 2.8 = 1.49)", a.maxZoom == 1);
        int[] t0 = a.render(0, 0, 0, 0x000000);
        checkPos("z0 centre is 0 N 0 E", t0, 128, 128, 0, 0, 4);
        checkPos("z0 at 90 W, 45 N", t0, 64, mercY(45, 0) , -90, 45, 4);
        int[] t2 = a.render(2, 3, 1, 0x000000);                // x 3 of 4 = 90..180 E, y 1 = 0..66.5 N
        checkPos("z2 tile 3/1 pixel (128,128) is 135 E", t2, 128, 128, 135, latOf(2, 1, 128), 4);

        // ---- the same image as tiles of 64 x 64 (uncompressed) and as LZW (ImageIO fallback)
        File tiled = new File(dir, "globe-tiled.tif");
        writeImageIo(tiled, image(720, 360), null, 64);
        RasterMap b = RasterMap.open("b", "b", tiled, 0);
        check("tiled TIFF read directly: " + b.raster.describe(), b.raster instanceof UncompressedTiffRaster && b.raster.describe().contains("tiles"));
        check("tiled gives the same tile", same(t0, b.render(0, 0, 0, 0), 2));
        File lzw = new File(dir, "globe-lzw.tif");
        writeImageIo(lzw, image(720, 360), "LZW", 0);
        RasterMap c = RasterMap.open("c", "c", lzw, 0);
        check("LZW read with ImageIO: " + c.raster.describe(), c.raster instanceof ImageIoRaster);
        check("LZW gives the same tile", same(t0, c.render(0, 0, 0, 0), 2));

        // ---- part of the world (Scandinavia, 4 x 4 km-ish pixels), placed by a world file
        File part = new File(dir, "nordic.tif");
        int pw = 300, ph = 300;                               // 10..25 E, 70..55 N: 0.05 degree pixels
        writeUncompressed(part, pw, ph, 64, (col, row) -> rgbOf(10 + (col + 0.5) * 0.05, 70 - (row + 0.5) * 0.05));
        Files.write(new File(dir, "nordic.tfw").toPath(), "0.05\n0\n0\n-0.05\n10.025\n69.975\n".getBytes());
        RasterMap d = RasterMap.open("d", "d", part, 0);
        check("world file used: " + d.geo.source, d.geo.source.contains("nordic.tfw") && near(d.geo.west, 10, 1e-9) && near(d.geo.north, 70, 1e-9));
        // Stockholm 18.07 E 59.33 N at zoom 6
        int z = 6;
        double[] px = pixelOf(z, 18.07, 59.33);
        int tx = (int) (px[0] / 256), ty = (int) (px[1] / 256);
        int[] tile = d.render(z, tx, ty, 0x123456);
        checkPos("Stockholm at zoom 6", tile, (int) px[0] % 256, (int) px[1] % 256, 18.07, 59.33, 3);
        int[] outside = d.render(z, 0, 0, 0x123456);
        check("outside the raster: background", outside[0] == 0x123456 && outside[255 * 256 + 255] == 0x123456);

        // ---- the service: discovery, listing, tiles on disk and in memory, off switch
        File runtime = new File(dir, "rt");
        File mapDir = new File(runtime, "maps/raster/GLOBE");
        mapDir.mkdirs();
        Files.copy(strips.toPath(), new File(mapDir, "GLOBE.tif").toPath());
        SimwProperties p = new SimwProperties();
        MapService s = new MapService(p, runtime);
        check("one map found", s.ids().size() == 1 && s.ids().get(0).equals("GLOBE"));
        check("listing: enabled, bounds, max zoom", s.listing().get("enabled").equals(Boolean.TRUE) && s.maxZoom("GLOBE") == 1);
        byte[] j = s.tile("GLOBE", 1, 1, 0);
        check("a JPEG tile", j != null && (j[0] & 0xFF) == 0xFF && (j[1] & 0xFF) == 0xD8);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(j));
        check("256 x 256", img.getWidth() == 256 && img.getHeight() == 256);
        File[] cache = new File(runtime, "maps/cache/GLOBE").listFiles();
        check("written to the disk cache", cache != null && cache.length == 1 && new File(cache[0], "1/1/0.jpg").isFile());
        check("same bytes from memory", s.tile("GLOBE", 1, 1, 0) == j || java.util.Arrays.equals(s.tile("GLOBE", 1, 1, 0), j));
        check("no tile beyond the max zoom", s.tile("GLOBE", 2, 0, 0) == null);
        check("no tile outside the grid", s.tile("GLOBE", 1, 2, 0) == null && s.tile("NOPE", 0, 0, 0) == null);
        s.prewarm(1);
        Thread.sleep(1500);
        check("prewarm made the coarse zooms", new File(cache[0], "0/0/0.jpg").isFile() && new File(cache[0], "1/1/1.jpg").isFile());
        s.shutdown();
        SimwProperties off = new SimwProperties();
        off.set("map.enabled", "false");
        MapService o = new MapService(off, runtime);
        check("map.enabled=false: off, no maps, no tiles", !o.isEnabled() && o.ids().isEmpty() && o.tile("GLOBE", 0, 0, 0) == null
                && o.listing().get("enabled").equals(Boolean.FALSE));

        // ---- map.file / map.files / map.default: which map is the default, files outside map.dir
        File elsewhere = new File(dir, "elsewhere");
        elsewhere.mkdirs();
        Files.copy(part.toPath(), new File(elsewhere, "nordic.tif").toPath());
        Files.copy(new File(dir, "nordic.tfw").toPath(), new File(elsewhere, "nordic.tfw").toPath());
        Files.copy(strips.toPath(), new File(elsewhere, "GLOBE.tif").toPath());
        SimwProperties pf = new SimwProperties();
        pf.set("map.file", elsewhere.getAbsolutePath() + File.separator + "nordic.tif");
        MapService sf = new MapService(pf, runtime);
        check("map.file outside map.dir is listed first: " + sf.ids(), sf.ids().equals(java.util.Arrays.asList("nordic", "GLOBE")));
        check("map.file is the default", "nordic".equals(sf.listing().get("default")) && "nordic".equals(sf.defaultMapId()));
        check("map.file tiles", sf.tile("nordic", 0, 0, 0) != null);
        sf.shutdown();
        SimwProperties pr = new SimwProperties();
        pr.set("map.file", "maps/raster/GLOBE/GLOBE.tif");            // relative, and also in map.dir
        pr.set("map.files", "../elsewhere/GLOBE.tif; ../elsewhere/nordic.tif");
        pr.set("map.default", "nordic");                              // map.file wins
        MapService sr = new MapService(pr, runtime);
        check("named twice = opened once; same file name in two places gets its own id: " + sr.ids(),
                sr.ids().equals(java.util.Arrays.asList("GLOBE", "GLOBE-2", "nordic")));
        check("relative map.file is the default over map.default", "GLOBE".equals(sr.defaultMapId()));
        sr.shutdown();
        SimwProperties pd = new SimwProperties();
        pd.set("map.files", "../elsewhere/nordic.tif");
        pd.set("map.default", "GLOBE");
        MapService sd = new MapService(pd, runtime);
        check("without map.file, map.default picks: " + sd.ids(), "GLOBE".equals(sd.defaultMapId()) && sd.ids().size() == 2);
        sd.shutdown();
        SimwProperties pm = new SimwProperties();
        pm.set("map.file", "nowhere/missing.tif");
        pm.set("map.default", "nope");
        MapService sm = new MapService(pm, runtime);
        @SuppressWarnings("unchecked")
        java.util.List<String> probs = (java.util.List<String>) sm.listing().get("problems");
        check("a missing map.file is reported, the folder still works: " + probs, sm.ids().equals(java.util.Collections.singletonList("GLOBE"))
                && "GLOBE".equals(sm.defaultMapId())
                && probs.stream().anyMatch(x -> x.contains("map.file") && x.contains("not found"))
                && probs.stream().anyMatch(x -> x.contains("map.default=nope")));
        sm.shutdown();
        SimwProperties pn = new SimwProperties();
        pn.set("map.dir", "no-such-dir");
        pn.set("map.file", "../elsewhere/nordic.tif");
        MapService sn = new MapService(pn, runtime);
        check("map.file alone, without a map folder: no problem reported", sn.ids().equals(java.util.Collections.singletonList("nordic"))
                && ((java.util.List<?>) sn.listing().get("problems")).isEmpty());
        sn.shutdown();

        if (args.length > 0 && args[0].equals("full")) {
            full(dir);
        }
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** A 21600 x 10800 uncompressed RGB raster (667 MB), like Natural Earth 10 m. */
    private static void full(File dir) throws Exception {
        File f = new File(dir, "HYP_HR_SR_W_DR.tif");
        long t = System.nanoTime();
        writeUncompressed(f, 21600, 10800, 1, null);
        System.out.printf("wrote %d MB in %.1f s%n", f.length() >> 20, (System.nanoTime() - t) / 1e9);
        RasterMap m = RasterMap.open("full", "full", f, 0);
        check("full size: native zoom 6", m.maxZoom == 6);
        for (int[] zxy : new int[][] {{0, 0, 0}, {2, 1, 1}, {4, 8, 4}, {6, 35, 18}, {6, 63, 63}}) {
            t = System.nanoTime();
            int[] px = m.render(zxy[0], zxy[1], zxy[2], 0);
            byte[] j = RasterMap.jpeg(px, 0.85f);
            System.out.printf("tile %d/%d/%d: %.0f ms, %d KB%n", zxy[0], zxy[1], zxy[2], (System.nanoTime() - t) / 1e6, j.length / 1024);
        }
        int[] t0 = m.render(0, 0, 0, 0);
        checkPos("full size z0 centre", t0, 128, 128, 0, 0, 3);
        m.raster.close();
        f.delete();
    }

    // ------------------------------------------------------------------ the test rasters

    interface Pixel {
        int rgb(int col, int row);
    }

    private static int rgbOf(double lon, double lat) {
        int r = (int) Math.round((lon + 180) / 360 * 255);
        int g = (int) Math.round((lat + 90) / 180 * 255);
        return (r << 16) | (g << 8) | 0x40;
    }

    private static BufferedImage image(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, rgbOf(-180 + (x + 0.5) * 360.0 / w, 90 - (y + 0.5) * 180.0 / h));
            }
        }
        return img;
    }

    /** A baseline uncompressed RGB TIFF with strips, written row by row (any size); px null = whole globe. */
    private static void writeUncompressed(File f, int w, int h, int rowsPerStrip, Pixel px) throws IOException {
        Pixel p = px;
        int strips = (h + rowsPerStrip - 1) / rowsPerStrip;
        long rowBytes = (long) w * 3;
        int entries = 10;
        long ifd = 8;
        long ifdSize = 2 + entries * 12 + 4;
        long bitsAt = ifd + ifdSize;
        long offsetsAt = bitsAt + 6;
        long countsAt = offsetsAt + 4L * strips;
        long dataAt = countsAt + 4L * strips;
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.setLength(0);
            ByteBuffer hb = ByteBuffer.allocate((int) dataAt).order(ByteOrder.LITTLE_ENDIAN);
            hb.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt((int) ifd);
            hb.putShort((short) entries);
            entry(hb, 256, 4, 1, w);
            entry(hb, 257, 4, 1, h);
            entry(hb, 258, 3, 3, (int) bitsAt);
            entry(hb, 259, 3, 1, 1);
            entry(hb, 262, 3, 1, 2);
            entry(hb, 273, 4, strips, strips == 1 ? (int) dataAt : (int) offsetsAt);
            entry(hb, 277, 3, 1, 3);
            entry(hb, 278, 4, 1, rowsPerStrip);
            entry(hb, 279, 4, strips, strips == 1 ? (int) (rowBytes * h) : (int) countsAt);
            entry(hb, 284, 3, 1, 1);
            hb.putInt(0);
            hb.putShort((short) 8).putShort((short) 8).putShort((short) 8);
            for (int s = 0; s < strips; s++) {
                hb.putInt((int) (dataAt + s * rowsPerStrip * rowBytes));
            }
            for (int s = 0; s < strips; s++) {
                int rows = Math.min(rowsPerStrip, h - s * rowsPerStrip);
                hb.putInt((int) (rows * rowBytes));
            }
            raf.write(hb.array(), 0, hb.position());
            byte[] row = new byte[(int) rowBytes];
            for (int y = 0; y < h; y++) {
                double lat = 90 - (y + 0.5) * 180.0 / h;
                for (int x = 0; x < w; x++) {
                    int c = p != null ? p.rgb(x, y) : rgbOf(-180 + (x + 0.5) * 360.0 / w, lat);
                    row[x * 3] = (byte) (c >> 16);
                    row[x * 3 + 1] = (byte) (c >> 8);
                    row[x * 3 + 2] = (byte) c;
                }
                raf.write(row);
            }
        }
    }

    private static void entry(ByteBuffer b, int tag, int type, int count, int value) {
        b.putShort((short) tag).putShort((short) type).putInt(count);
        if (type == 3 && count == 1) {
            b.putShort((short) value).putShort((short) 0);
        } else {
            b.putInt(value);
        }
    }

    private static void writeImageIo(File f, BufferedImage img, String compression, int tile) throws IOException {
        Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("tiff");
        ImageWriter w = it.next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(f)) {
            w.setOutput(out);
            ImageWriteParam p = w.getDefaultWriteParam();
            if (compression == null) {
                p.setCompressionMode(ImageWriteParam.MODE_DISABLED);
            } else {
                p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                p.setCompressionType(compression);
            }
            if (tile > 0) {
                p.setTilingMode(ImageWriteParam.MODE_EXPLICIT);
                p.setTiling(tile, tile, 0, 0);
            }
            w.write(null, new IIOImage(img, null, null), p);
        } finally {
            w.dispose();
        }
    }

    // ------------------------------------------------------------------ positions

    /** Global pixel of a position at zoom z. */
    private static double[] pixelOf(int z, double lon, double lat) {
        double n = 256.0 * (1 << z);
        double x = (lon + 180) / 360 * n;
        double s = Math.sin(Math.toRadians(lat));
        double y = (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * n;
        return new double[] {x, y};
    }

    private static int mercY(double lat, int z) {
        return (int) pixelOf(z, 0, lat)[1];
    }

    private static double latOf(int z, int ty, int py) {
        return RasterMap.lat((ty * 256 + py + 0.5) / (256.0 * (1 << z)));
    }

    private static void checkPos(String what, int[] tile, int x, int y, double lon, double lat, int tol) {
        int c = tile[y * 256 + x];
        int want = rgbOf(lon, lat);
        int dr = Math.abs(((c >> 16) & 0xFF) - ((want >> 16) & 0xFF)), dg = Math.abs(((c >> 8) & 0xFF) - ((want >> 8) & 0xFF));
        check(what + String.format(" (got %06x, want %06x)", c, want), dr <= tol && dg <= tol);
    }

    private static boolean same(int[] a, int[] b, int tol) {
        for (int i = 0; i < a.length; i++) {
            for (int s = 0; s < 24; s += 8) {
                if (Math.abs(((a[i] >> s) & 0xFF) - ((b[i] >> s) & 0xFF)) > tol) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean near(double a, double b, double eps) {
        return Math.abs(a - b) <= eps;
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what);
        }
    }
}
