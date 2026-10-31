package com.simweaver.rift.map;

import com.simweaver.common.config.SimwProperties;
import com.simweaver.rift.map.shape.Dbf;
import com.simweaver.rift.map.shape.Projection;
import com.simweaver.rift.map.shape.ShapeSource;
import com.simweaver.rift.map.shape.Shapefile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Shapefile overlays: the projections (against Snyder's worked example and by round trips over
 * the SWEREF 99 TM zone), the .shp / .dbf / .prj / .cpg reader, labels and their order, and
 * overlay tiles (points, lines, a polygon with a hole) through the MapService, and layers read
 * straight from zip packages.
 * No arguments. Prints "N checks, M failures".
 */
public final class OverlaySelfTest {

    private static int checks, failures;

    static final String SWEREF99TM = "PROJCS[\"SWEREF99_TM\",GEOGCS[\"GCS_SWEREF99\",DATUM[\"D_SWEREF99\",SPHEROID[\"GRS_1980\",6378137.0,298.257222101]],"
            + "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]],PROJECTION[\"Transverse_Mercator\"],PARAMETER[\"False_Easting\",500000.0],"
            + "PARAMETER[\"False_Northing\",0.0],PARAMETER[\"Central_Meridian\",15.0],PARAMETER[\"Scale_Factor\",0.9996],"
            + "PARAMETER[\"Latitude_Of_Origin\",0.0],UNIT[\"Meter\",1.0]]";

    public static void main(String[] args) throws Exception {
        projections();
        File dir = Files.createTempDirectory("rift-overlay-test").toFile();
        files(dir);
        service(dir);
        zips(dir);
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ projections

    private static void projections() {
        // Snyder, Map Projections - A Working Manual, p. 269: Clarke 1866, phi 40.5 N, lambda 73.5 W, lambda0 75 W, k0 0.9996
        String clarke = "PROJCS[\"Snyder\",GEOGCS[\"NAD27\",DATUM[\"North_American_Datum_1927\",SPHEROID[\"Clarke 1866\",6378206.4,294.978698214]]],"
                + "PROJECTION[\"Transverse_Mercator\"],PARAMETER[\"central_meridian\",-75],PARAMETER[\"scale_factor\",0.9996],"
                + "PARAMETER[\"false_easting\",0],PARAMETER[\"false_northing\",0],UNIT[\"metre\",1]]";
        Projection.TransverseMercator s = (Projection.TransverseMercator) Projection.fromWkt(clarke);
        double[] xy = s.fromLonLat(-73.5, 40.5);
        check("Snyder's example: x 127106.5, y 4484124.4 (got " + xy[0] + ", " + xy[1] + ")",
                Math.abs(xy[0] - 127106.5) < 0.1 && Math.abs(xy[1] - 4484124.4) < 0.1);
        double[] ll = new double[2];
        s.toLonLat(127106.5, 4484124.4, ll);
        check("and back to 73.5 W, 40.5 N", Math.abs(ll[0] + 73.5) < 2e-6 && Math.abs(ll[1] - 40.5) < 2e-6);
        check("NAD27 gets a datum warning", s.warning != null && s.warning.contains("datum"));

        Projection.TransverseMercator t = (Projection.TransverseMercator) Projection.fromWkt(SWEREF99TM);
        check("SWEREF 99 TM read: " + t.description, t.description.contains("SWEREF99_TM") && t.description.contains("Transverse Mercator") && t.warning == null);
        double worst = 0;
        for (double lon = 10; lon <= 25; lon += 0.5) {
            for (double lat = 55; lat <= 69.5; lat += 0.5) {
                double[] en = t.fromLonLat(lon, lat);
                t.toLonLat(en[0], en[1], ll);
                worst = Math.max(worst, Math.max(Math.abs(ll[0] - lon) * 111000 * Math.cos(Math.toRadians(lat)), Math.abs(ll[1] - lat) * 111000));
            }
        }
        check("SWEREF 99 TM round trips over Sweden within a millimetre (worst " + worst + " m)", worst < 0.001);
        double[] cm = t.fromLonLat(15, 0);
        check("central meridian at the equator: E 500000, N 0", Math.abs(cm[0] - 500000) < 1e-6 && Math.abs(cm[1]) < 1e-6);
        double[] sthlm = t.fromLonLat(18.0686, 59.3293);
        check("Stockholm about E 674000, N 6580000 (" + Math.round(sthlm[0]) + ", " + Math.round(sthlm[1]) + ")",
                Math.abs(sthlm[0] - 674000) < 2000 && Math.abs(sthlm[1] - 6580000) < 2000);

        Projection g = Projection.fromWkt("GEOGCS[\"GCS_WGS_1984\",DATUM[\"D_WGS_1984\",SPHEROID[\"WGS_1984\",6378137.0,298.257223563]],"
                + "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]]");
        g.toLonLat(18.5, 59.25, ll);
        check("WGS 84 geographic: as it is", ll[0] == 18.5 && ll[1] == 59.25 && g.warning == null);
        Projection w = Projection.fromWkt("PROJCS[\"WGS_1984_Web_Mercator_Auxiliary_Sphere\",GEOGCS[\"GCS_WGS_1984\",DATUM[\"D_WGS_1984\","
                + "SPHEROID[\"WGS_1984\",6378137.0,298.257223563]]],PROJECTION[\"Mercator_Auxiliary_Sphere\"],PARAMETER[\"False_Easting\",0.0],"
                + "PARAMETER[\"False_Northing\",0.0],PARAMETER[\"Central_Meridian\",0.0],UNIT[\"Meter\",1.0]]");
        w.toLonLat(2011369.4, 8250000, ll);
        check("Web Mercator: 18.07 E, 59.33 N (" + ll[0] + ", " + ll[1] + ")", Math.abs(ll[0] - 18.0686) < 1e-3 && Math.abs(ll[1] - 59.3293) < 0.2);
        boolean refused;
        try {
            Projection.fromWkt("PROJCS[\"x\",GEOGCS[\"g\",DATUM[\"d\",SPHEROID[\"s\",6378137,298.257]]],PROJECTION[\"Lambert_Conformal_Conic\"]]");
            refused = false;
        } catch (IllegalArgumentException e) {
            refused = e.getMessage().contains("Lambert_Conformal_Conic") && e.getMessage().contains("not supported");
        }
        check("an unsupported projection is refused by name", refused);
    }

    // ------------------------------------------------------------------ the files

    static final String[] TOWNS = {"Stockholm", "Göteborg", "Malmö", "Kiruna"};
    static final double[][] TOWN_LONLAT = {{18.0686, 59.3293}, {11.9746, 57.7089}, {13.0038, 55.6050}, {20.2253, 67.8558}};
    static final int[] POP = {975551, 583056, 351749, 22423};

    private static void files(File dir) throws IOException {
        Projection.TransverseMercator t = (Projection.TransverseMercator) Projection.fromWkt(SWEREF99TM);
        // towns: points in SWEREF 99 TM, names in windows-1252 without a .cpg
        List<double[][]> pts = new ArrayList<>();
        for (double[] ll : TOWN_LONLAT) {
            pts.add(new double[][] {t.fromLonLat(ll[0], ll[1])});
        }
        writeShp(new File(dir, "towns.shp"), 1, pts, null);
        Files.write(new File(dir, "towns.prj").toPath(), SWEREF99TM.getBytes(StandardCharsets.US_ASCII));
        String[][] rows = new String[TOWNS.length][];
        for (int i = 0; i < TOWNS.length; i++) {
            rows[i] = new String[] {TOWNS[i], String.valueOf(POP[i])};
        }
        writeDbf(new File(dir, "towns.dbf"), new String[] {"NAME", "POP"}, new char[] {'C', 'N'}, rows, Charset.forName("windows-1252"), 0);

        // a road: one line, WGS 84 without .prj, names in UTF-8 with a .cpg
        List<double[][]> line = new ArrayList<>();
        line.add(new double[][] {{18.0686, 59.3293}, {17.0, 59.0}, {15.0, 58.5}, {11.9746, 57.7089}});
        writeShp(new File(dir, "roads.shp"), 3, line, null);
        writeDbf(new File(dir, "roads.dbf"), new String[] {"NAME"}, new char[] {'C'}, new String[][] {{"E20 Göteborg–Stockholm"}}, StandardCharsets.UTF_8, 0);
        Files.write(new File(dir, "roads.cpg").toPath(), "UTF-8".getBytes(StandardCharsets.US_ASCII));

        // an area: a square around Stockholm with a hole in the middle, WGS 84 with .prj
        List<double[][]> poly = new ArrayList<>();
        poly.add(new double[][] {{17.5, 59.0}, {17.5, 59.6}, {18.6, 59.6}, {18.6, 59.0}, {17.5, 59.0}});    // outer, clockwise
        poly.add(new double[][] {{17.9, 59.2}, {18.2, 59.2}, {18.2, 59.4}, {17.9, 59.4}, {17.9, 59.2}});    // hole
        writeShp(new File(dir, "zone.shp"), 5, Collections.singletonList(null), poly);
        Files.write(new File(dir, "zone.prj").toPath(), ("GEOGCS[\"GCS_WGS_1984\",DATUM[\"D_WGS_1984\",SPHEROID[\"WGS_1984\",6378137.0,298.257223563]],"
                + "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]]").getBytes(StandardCharsets.US_ASCII));
        writeDbf(new File(dir, "zone.dbf"), new String[] {"ID"}, new char[] {'C'}, new String[][] {{"ESR 1"}}, StandardCharsets.US_ASCII, 0);

        // a broken file: not a shapefile
        Files.write(new File(dir, "broken.shp").toPath(), new byte[200]);

        Shapefile towns = Shapefile.read(new File(dir, "towns.shp"), Arrays.asList("NAME", "POP"));
        check("towns: 4 points, SWEREF 99 TM", towns.features.size() == 4 && towns.features.get(0).kind == Shapefile.Kind.POINT
                && towns.projection.description.contains("Transverse Mercator"));
        Shapefile.Feature g = towns.features.get(1);
        check("Göteborg back at 11.9746 E, 57.7089 N (float precision)", Math.abs(g.coords[0] - 11.9746) < 2e-5 && Math.abs(g.coords[1] - 57.7089) < 2e-5);
        check("windows-1252 names without a .cpg: " + towns.dbf.get("name", 2), "Malmö".equals(towns.dbf.get("NAME", 2)) && "Göteborg".equals(towns.dbf.get("name", 1)));
        Shapefile roads = Shapefile.read(new File(dir, "roads.shp"), Collections.singletonList("NAME"));
        check("roads: a line without .prj, taken as longitude/latitude", roads.features.size() == 1 && roads.features.get(0).kind == Shapefile.Kind.LINE
                && roads.warnings.toString().contains("no .prj"));
        check("UTF-8 with a .cpg: " + roads.dbf.get("NAME", 0), "E20 Göteborg–Stockholm".equals(roads.dbf.get("NAME", 0)) && roads.dbf.encoding.equals("UTF-8"));
        Shapefile zone = Shapefile.read(new File(dir, "zone.shp"), Collections.<String>emptyList());
        check("zone: a polygon with 2 rings", zone.features.size() == 1 && zone.features.get(0).kind == Shapefile.Kind.POLYGON
                && zone.features.get(0).parts.length == 2);
        float[] a = VectorOverlay.anchor(zone.features.get(0));
        check("its label anchor is near the middle of the outer ring", Math.abs(a[0] - 18.05) < 0.01 && Math.abs(a[1] - 59.3) < 0.01);
        boolean bad;
        try {
            Shapefile.read(new File(dir, "broken.shp"), null);
            bad = false;
        } catch (IOException e) {
            bad = e.getMessage().contains("not a shapefile");
        }
        check("a file that is not a shapefile is refused", bad);
        Dbf d = Dbf.read(new File(dir, "towns.dbf"), Arrays.asList("NAME", "MISSING"));
        check("dbf: fields listed, a missing one is just absent", d.fields.equals(Arrays.asList("NAME", "POP")) && !d.has("MISSING") && d.rows() == 4);
    }

    // ------------------------------------------------------------------ through the service

    private static void service(File dir) throws Exception {
        SimwProperties cfg = new SimwProperties();
        cfg.set("map.dir", new File(dir, "no-rasters").getPath());
        cfg.set("map.cache.dir", new File(dir, "cache").getPath());
        cfg.set("map.overlays", new File(dir, "towns.shp").getPath() + "; " + new File(dir, "missing.shp").getPath());
        cfg.set("map.overlays.dir", dir.getPath());
        cfg.set("map.overlay.towns.name", "Towns");
        cfg.set("map.overlay.towns.label", "NAME");
        cfg.set("map.overlay.towns.labelrank", "POP");
        cfg.set("map.overlay.towns.point", "4");
        cfg.set("map.overlay.towns.color", "#c0392b");
        cfg.set("map.overlay.zone.fill", "#3498db80");
        cfg.set("map.overlay.zone.label", "ID");
        cfg.set("map.overlay.zone.labelminzoom", "3");
        cfg.set("map.overlay.roads.width", "3");
        cfg.set("map.overlay.roads.visible", "false");
        MapService ms = new MapService(cfg, dir);
        Map<String, VectorOverlay> loaded = ms.overlaysLoaded();
        check("overlays: towns first (map.overlays), then the folder sorted, broken failed " + loaded.keySet(),
                new ArrayList<>(loaded.keySet()).equals(Arrays.asList("towns", "broken", "roads", "zone")) && loaded.get("broken") == null
                && loaded.get("towns") != null);
        Map<String, Object> listing = ms.listing();
        String js = com.simweaver.common.rest.Jsons.toJson(listing);
        check("listing: missing.shp and broken.shp are problems", js.contains("missing.shp not found") && js.contains("broken.shp: cannot be read"));
        check("listing: towns ready with tiles, labels and its colour", js.contains("\"id\":\"towns\"") && js.contains("\"state\":\"ready\"")
                && js.contains("maps/overlays/towns/tiles/{z}/{x}/{y}.png?v=") && js.contains("maps/overlays/towns/labels") && js.contains("#c0392b"));
        check("listing: roads hidden by default, broken failed", js.contains("\"visible\":false") && js.contains("\"state\":\"failed\""));

        VectorOverlay towns = loaded.get("towns");
        check("labels win by population: " + towns.labelTexts(), towns.labelTexts().equals(Arrays.asList("Stockholm", "Göteborg", "Malmö", "Kiruna")));
        Map<String, Object> l = ms.overlayLabels("towns", new double[] {10, 55, 20, 60}, 6);
        @SuppressWarnings("unchecked") List<Map<String, Object>> ls = (List<Map<String, Object>>) l.get("labels");
        check("labels in southern Sweden at zoom 6: three, no Kiruna", ls.size() == 3 && ls.get(0).get("t").equals("Stockholm") && "point".equals(ls.get(0).get("k")));
        @SuppressWarnings("unchecked") List<Object> none = (List<Object>) ms.overlayLabels("towns", new double[] {10, 55, 20, 60}, 5).get("labels");
        check("none below labelminzoom (6)", none.isEmpty());
        @SuppressWarnings("unchecked") List<Map<String, Object>> zl = (List<Map<String, Object>>) ms.overlayLabels("zone", new double[] {17, 58, 19, 60}, 3).get("labels");
        check("zone label at zoom 3", zl.size() == 1 && "ESR 1".equals(zl.get(0).get("t")) && "polygon".equals(zl.get(0).get("k")));

        // tiles: the Stockholm point at zoom 6; the empty sea west of Ireland
        int z = 6;
        double world = 256 * Math.pow(2, z);
        double px = VectorOverlay.lonToX(18.0686, world), py = VectorOverlay.latToY(59.3293, world);
        int tx = (int) (px / 256), ty = (int) (py / 256);
        byte[] png = ms.overlayTile("towns", z, tx, ty);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        int argb = img.getRGB((int) (px - tx * 256), (int) (py - ty * 256));
        check("the Stockholm dot is drawn in its tile, in #c0392b", img.getWidth() == 256 && (argb >>> 24) > 200
                && Math.abs(((argb >> 16) & 0xFF) - 0xc0) < 8 && Math.abs((argb & 0xFF) - 0x2b) < 8);
        byte[] empty = ms.overlayTile("towns", z, 26, 20);
        check("a tile with nothing is the 1 x 1 transparent PNG", Arrays.equals(empty, MapService.EMPTY_PNG));
        check("the same tile again comes from the cache", ms.overlayTile("towns", z, tx, ty) == png);
        check("out of range: null", ms.overlayTile("towns", 19, 0, 0) == null && ms.overlayTile("nosuch", 1, 0, 0) == null);

        // the polygon with its hole at zoom 9: filled ring, transparent hole
        z = 9;
        world = 256 * Math.pow(2, z);
        double rx = VectorOverlay.lonToX(17.7, world), ry = VectorOverlay.latToY(59.5, world);         // in the ring
        double hx = VectorOverlay.lonToX(18.05, world), hy = VectorOverlay.latToY(59.3, world);        // in the hole
        tx = (int) (rx / 256);
        ty = (int) (ry / 256);
        img = ImageIO.read(new ByteArrayInputStream(ms.overlayTile("zone", z, tx, ty)));
        int ring = img.getRGB((int) (rx - tx * 256), (int) (ry - ty * 256));
        check("polygon: the ring is filled (half transparent blue)", (ring >>> 24) > 90 && (ring >>> 24) < 170 && (ring & 0xFF) > 150);
        int htx = (int) (hx / 256), hty = (int) (hy / 256);
        img = ImageIO.read(new ByteArrayInputStream(ms.overlayTile("zone", z, htx, hty)));
        int hole = img.getRGB((int) (hx - htx * 256), (int) (hy - hty * 256));
        check("polygon: the hole is not", (hole >>> 24) == 0);

        // the road drawn 3 px wide along its first segment
        z = 7;
        world = 256 * Math.pow(2, z);
        double mx = VectorOverlay.lonToX((18.0686 + 17.0) / 2, world), my = VectorOverlay.latToY((59.3293 + 59.0) / 2, world);
        tx = (int) (mx / 256);
        ty = (int) (my / 256);
        img = ImageIO.read(new ByteArrayInputStream(ms.overlayTile("roads", z, tx, ty)));
        check("the road is drawn along its line", (img.getRGB((int) (mx - tx * 256), (int) (my - ty * 256)) >>> 24) > 100);
        check("tiles were written to the disk cache", new File(dir, "cache/overlays/towns").isDirectory());
        ms.shutdown();
    }

    // ------------------------------------------------------------------ zip packages

    private static void zips(File dir) throws Exception {
        File z = new File(dir, "zips");
        z.mkdirs();
        // two layers in one package: one in a sub folder, one with upper case extensions, plus clutter
        zip(new File(z, "pack.zip"), new String[][] {
            {"data/zone.shp", "zone.shp"}, {"data/zone.dbf", "zone.dbf"}, {"data/zone.prj", "zone.prj"},
            {"roads.SHP", "roads.shp"}, {"roads.DBF", "roads.dbf"}, {"roads.cpg", "roads.cpg"},
            {"__MACOSX/._roads.SHP", "broken.shp"}, {"README.html", null}}, dir);
        // the towns both packed and unpacked: the unpacked files are used
        zip(new File(z, "towns.zip"), new String[][] {{"towns.shp", "towns.shp"}, {"towns.dbf", "towns.dbf"}, {"towns.prj", "towns.prj"}}, dir);
        for (String ext : new String[] {".shp", ".dbf", ".prj"}) {
            Files.copy(new File(dir, "towns" + ext).toPath(), new File(z, "towns" + ext).toPath());
        }
        zip(new File(z, "empty.zip"), new String[][] {{"README.txt", null}}, dir);
        Files.write(new File(z, "notazip.zip").toPath(), "not a zip".getBytes(StandardCharsets.US_ASCII));

        List<ShapeSource> layers = ShapeSource.zip(new File(z, "pack.zip"));
        check("pack.zip has two layers, the __MACOSX clutter is not one: " + layers, layers.size() == 2
                && layers.get(0).shortName().equals("pack.zip!data/zone.shp") && layers.get(1).baseName.equals("roads"));
        Shapefile zone = Shapefile.read(layers.get(0), Collections.<String>emptyList());
        check("zone from the zip: a polygon with 2 rings, WGS 84 .prj from the same folder", zone.features.size() == 1
                && zone.features.get(0).parts.length == 2 && zone.projection.description.contains("WGS"));
        Shapefile roads = Shapefile.read(layers.get(1), Collections.singletonList("NAME"));
        check("roads from the zip: .SHP / .DBF found, UTF-8 by its .cpg: " + roads.dbf.get("NAME", 0),
                roads.features.size() == 1 && "E20 Göteborg–Stockholm".equals(roads.dbf.get("NAME", 0)) && roads.dbf.encoding.equals("UTF-8"));
        boolean refused;
        try {
            ShapeSource.zip(new File(z, "notazip.zip"));
            refused = false;
        } catch (IOException e) {
            refused = true;
        }
        check("a file that is not a zip is refused", refused);

        SimwProperties cfg = new SimwProperties();
        cfg.set("map.dir", new File(z, "no-rasters").getPath());
        cfg.set("map.cache.dir", new File(z, "cache").getPath());
        cfg.set("map.overlays.dir", z.getPath());
        cfg.set("map.overlay.roads.label", "NAME");
        cfg.set("map.overlay.roads.labelminzoom", "4");
        cfg.set("map.overlay.zone.fill", "#3498db80");
        MapService ms = new MapService(cfg, z);
        Map<String, VectorOverlay> loaded = ms.overlaysLoaded();
        check("ids: the unpacked towns, then pack.zip's layers " + loaded.keySet(),
                new ArrayList<>(loaded.keySet()).equals(Arrays.asList("towns", "zone", "roads")) && !loaded.containsValue(null));
        check("towns are the unpacked files", loaded.get("towns").source.shortName().equals("towns.shp"));
        String js = com.simweaver.common.rest.Jsons.toJson(ms.listing());
        check("listing: the zip entry as the file", js.contains("\"file\":\"pack.zip!data/zone.shp\""));
        check("problems: towns.zip not used, empty.zip and notazip.zip", js.contains("towns.zip!towns.shp: not used, it is the same layer as towns.shp")
                && js.contains("empty.zip has no .shp file") && js.contains("notazip.zip: cannot be read as a zip package"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> rl = (List<Map<String, Object>>) ms.overlayLabels("roads", new double[] {10, 55, 20, 60}, 5).get("labels");
        check("labels from the zipped .dbf", rl.size() == 1 && "E20 Göteborg–Stockholm".equals(rl.get(0).get("t")));
        int zz = 9;
        double world = 256 * Math.pow(2, zz);
        double rx = VectorOverlay.lonToX(17.7, world), ry = VectorOverlay.latToY(59.5, world);
        int tx = (int) (rx / 256), ty = (int) (ry / 256);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(ms.overlayTile("zone", zz, tx, ty)));
        int ring = img.getRGB((int) (rx - tx * 256), (int) (ry - ty * 256));
        check("tiles from the zipped polygon: the ring is filled", (ring >>> 24) > 90 && (ring & 0xFF) > 150);
        ms.shutdown();
    }

    /** Writes a zip: {entry name, file in dir to copy (null: a few bytes of text)}. */
    static void zip(File zip, String[][] entries, File dir) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            for (String[] e : entries) {
                out.putNextEntry(new ZipEntry(e[0]));
                out.write(e[1] == null ? "readme".getBytes(StandardCharsets.US_ASCII) : Files.readAllBytes(new File(dir, e[1]).toPath()));
                out.closeEntry();
            }
        }
    }

    // ------------------------------------------------------------------ writers (test data)

    /** Writes a .shp: type 1 points (one per entry), 3 one line, 5 one polygon of rings. */
    static void writeShp(File f, int type, List<double[][]> features, List<double[][]> polygonRings) throws IOException {
        ByteArrayOutputStream recs = new ByteArrayOutputStream();
        double[] bb = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        int recNo = 0;
        List<double[][]> records = new ArrayList<>();
        if (type == 5) {
            records.add(null);
        } else if (type == 3) {
            records.addAll(features);
        } else {
            records.addAll(features);
        }
        for (double[][] r : records) {
            ByteBuffer b;
            if (type == 1) {
                b = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
                b.putInt(1).putDouble(r[0][0]).putDouble(r[0][1]);
                grow(bb, r[0]);
            } else {
                List<double[][]> parts = type == 5 ? polygonRings : Collections.singletonList(r);
                int n = 0;
                for (double[][] p : parts) {
                    n += p.length;
                }
                b = ByteBuffer.allocate(44 + 4 * parts.size() + 16 * n).order(ByteOrder.LITTLE_ENDIAN);
                double[] pb = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
                for (double[][] p : parts) {
                    for (double[] q : p) {
                        grow(pb, q);
                        grow(bb, q);
                    }
                }
                b.putInt(type).putDouble(pb[0]).putDouble(pb[1]).putDouble(pb[2]).putDouble(pb[3]).putInt(parts.size()).putInt(n);
                int at = 0;
                for (double[][] p : parts) {
                    b.putInt(at);
                    at += p.length;
                }
                for (double[][] p : parts) {
                    for (double[] q : p) {
                        b.putDouble(q[0]).putDouble(q[1]);
                    }
                }
            }
            byte[] content = b.array();
            ByteBuffer h = ByteBuffer.allocate(8);
            h.putInt(++recNo).putInt(content.length / 2);
            recs.write(h.array());
            recs.write(content);
        }
        ByteBuffer head = ByteBuffer.allocate(100);
        head.putInt(0, 9994);
        head.putInt(24, (100 + recs.size()) / 2);
        head.order(ByteOrder.LITTLE_ENDIAN);
        head.putInt(28, 1000);
        head.putInt(32, type);
        head.putDouble(36, bb[0]).putDouble(44, bb[1]).putDouble(52, bb[2]).putDouble(60, bb[3]);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(head.array());
        out.write(recs.toByteArray());
        Files.write(f.toPath(), out.toByteArray());
    }

    private static void grow(double[] bb, double[] p) {
        bb[0] = Math.min(bb[0], p[0]);
        bb[1] = Math.min(bb[1], p[1]);
        bb[2] = Math.max(bb[2], p[0]);
        bb[3] = Math.max(bb[3], p[1]);
    }

    /** Writes a dBASE III table with character (40) and numeric (12) fields. */
    static void writeDbf(File f, String[] names, char[] types, String[][] rows, Charset cs, int ldid) throws IOException {
        int[] len = new int[names.length];
        int recLen = 1;
        for (int i = 0; i < names.length; i++) {
            len[i] = types[i] == 'N' ? 12 : 40;
            recLen += len[i];
        }
        int headLen = 32 + 32 * names.length + 1;
        ByteBuffer b = ByteBuffer.allocate(headLen + recLen * rows.length + 1).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 3).put((byte) 126).put((byte) 10).put((byte) 23).putInt(rows.length).putShort((short) headLen).putShort((short) recLen);
        b.position(29);
        b.put((byte) ldid);
        b.position(32);
        for (int i = 0; i < names.length; i++) {
            byte[] n = Arrays.copyOf(names[i].getBytes(StandardCharsets.US_ASCII), 11);
            b.put(n).put((byte) types[i]).putInt(0).put((byte) len[i]).put((byte) 0);
            b.put(new byte[14]);
        }
        b.put((byte) 0x0D);
        for (String[] r : rows) {
            b.put((byte) ' ');
            for (int i = 0; i < names.length; i++) {
                byte[] v = r[i].getBytes(cs);
                byte[] cell = new byte[len[i]];
                Arrays.fill(cell, (byte) ' ');
                if (types[i] == 'N') {
                    System.arraycopy(v, 0, cell, len[i] - v.length, v.length);
                } else {
                    System.arraycopy(v, 0, cell, 0, Math.min(v.length, len[i]));
                }
                b.put(cell);
            }
        }
        b.put((byte) 0x1A);
        Files.write(f.toPath(), b.array());
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what);
        }
    }
}
