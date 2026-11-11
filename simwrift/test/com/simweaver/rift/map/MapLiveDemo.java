package com.simweaver.rift.map;

import com.simweaver.common.dis.DisEntityType;
import com.simweaver.common.dis.DisWriter;
import com.simweaver.common.geo.Ellipsoid;
import com.simweaver.common.geo.EulerAngles;
import com.simweaver.common.geo.Geodetic;
import com.simweaver.common.geo.LocalFrame;
import com.simweaver.common.geo.Vec3;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A live map demo for trying the map in a browser and taking screenshots (test only): runs the
 * whole server in this JVM (run it in the runtime folder), creates the DIS6 datasource through the
 * REST API and sends a small DIS 6 exercise around Gotland to UDP port 3000: moving aircraft, ships
 * and ground units of two forces, with radar and jammer beams (Electromagnetic Emission PDUs).
 *
 * Every nine seconds one of them fires (Fire PDU) and four seconds later the shot detonates at its
 * target (Detonation PDU).
 *
 * <pre>cd simwrift/rtdir; java -cp ../../out/classes:../../out/test com.simweaver.rift.map.MapLiveDemo</pre>
 *
 * {@code -Ddemo.server=false} only sends (to servers running on their own, no datasource is made);
 * {@code -Ddemo.ports=3000,3001} sends every PDU to each of these UDP ports (default 3000), for
 * example to gateways' DIS endpoints.
 */
public final class MapLiveDemo {

    private MapLiveDemo() {
    }

    /** A beam: centre (degrees from the nose, or from the bearing to a target), half sweep, rotation. */
    static final class Beam {
        final int function;            // 1 search, 4 tracking, 12 jammer
        final double centreDeg, halfDeg, rotateDegPerS;
        Unit target;                   // a jammer beam points at its target

        Beam(int function, double centreDeg, double halfDeg, double rotateDegPerS) {
            this.function = function;
            this.centreDeg = centreDeg;
            this.halfDeg = halfDeg;
            this.rotateDegPerS = rotateDegPerS;
        }
    }

    /** A unit: straight leg (radius 0) or a clockwise orbit around (lat, lon). */
    static final class Unit {
        final int site, number, force;
        final DisEntityType type;
        final String marking;
        final double lat, lon, alt, speed, radius, heading0, phase;
        final double roll;
        final List<Beam> beams = new ArrayList<Beam>();
        double nowLat, nowLon, nowHeading, vn, ve;

        Unit(int site, int number, int force, DisEntityType type, String marking, double lat, double lon, double alt,
                double speed, double radius, double heading0, double phase, double roll) {
            this.site = site;
            this.number = number;
            this.force = force;
            this.type = type;
            this.marking = marking;
            this.lat = lat;
            this.lon = lon;
            this.alt = alt;
            this.speed = speed;
            this.radius = radius;
            this.heading0 = heading0;
            this.phase = phase;
            this.roll = roll;
        }

        Unit beam(Beam b) {
            beams.add(b);
            return this;
        }

        void at(double t) {
            double n, e, h;
            if (radius > 0) {
                double a = Math.toRadians(phase) + speed / radius * t;     // clockwise from north
                n = radius * Math.cos(a);
                e = radius * Math.sin(a);
                h = Math.toDegrees(a) + 90;
            } else {
                h = heading0;
                n = speed * t * Math.cos(Math.toRadians(h));
                e = speed * t * Math.sin(Math.toRadians(h));
            }
            nowLat = lat + n / 111_320.0;
            nowLon = lon + e / (111_320.0 * Math.cos(Math.toRadians(lat)));
            nowHeading = ((h % 360) + 360) % 360;
            vn = speed * Math.cos(Math.toRadians(nowHeading));
            ve = speed * Math.sin(Math.toRadians(nowHeading));
        }

        double bearingTo(Unit o) {
            double dn = (o.nowLat - nowLat) * 111_320.0;
            double de = (o.nowLon - nowLon) * 111_320.0 * Math.cos(Math.toRadians(nowLat));
            return Math.toDegrees(Math.atan2(de, dn));
        }
    }

    private static DisEntityType t(int domain, int country, int category, int sub) {
        return new DisEntityType(1, domain, country, category, sub, 0, 0);
    }

    public static void main(String[] args) throws Exception {
        // -Ddemo.server=false: only send (to a server of its own); -Ddemo.ports=3000,3001: where to send
        boolean ownServer = Boolean.parseBoolean(System.getProperty("demo.server", "true"));
        List<Integer> ports = new ArrayList<Integer>();
        for (String p : System.getProperty("demo.ports", "3000").split(",")) ports.add(Integer.parseInt(p.trim()));
        if (ownServer) {
            Thread server = new Thread(() -> {
                try {
                    com.simweaver.rift.Server.main(new String[0]);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }, "demo-server");
            server.setDaemon(true);
            server.start();
            Thread.sleep(6000);
        }

        final int blue = 1, red = 2, neutral = 3;
        List<Unit> units = new ArrayList<Unit>();
        // Swedish: a Gripen pair on CAP east of Gotland, an AEW&C west of it, two corvettes, units on the island
        Unit g11 = new Unit(1, 11, blue, t(2, 205, 1, 3), "GRIPEN 11", 57.80, 19.55, 7000, 230, 16_000, 0, 20, -30)
                .beam(new Beam(4, 0, 30, 0));
        Unit g12 = new Unit(1, 12, blue, t(2, 205, 1, 3), "GRIPEN 12", 57.80, 19.55, 7000, 230, 16_000, 0, 200, -30)
                .beam(new Beam(4, 0, 30, 0));
        units.add(g11);
        units.add(g12);
        units.add(new Unit(1, 21, blue, t(2, 205, 8, 1), "ARGUS 21", 57.35, 17.55, 6500, 140, 22_000, 0, 300, -20)
                .beam(new Beam(1, 90, 60, 0)).beam(new Beam(1, -90, 60, 0)));
        units.add(new Unit(1, 31, blue, t(3, 205, 50, 3), "K31 VISBY", 57.22, 18.95, 0, 9, 0, 55, 0, 0)
                .beam(new Beam(1, 0, 12, 36)));
        units.add(new Unit(1, 32, blue, t(3, 205, 50, 3), "K32 HELSINGBORG", 57.98, 19.35, 0, 8, 0, 120, 0, 0)
                .beam(new Beam(1, 0, 12, -30)));
        units.add(new Unit(1, 41, blue, t(1, 205, 2, 1), "CV90 41", 57.60, 18.42, 0, 7, 0, 115, 0, 0));
        units.add(new Unit(1, 42, blue, t(1, 205, 2, 1), "CV90 42", 57.59, 18.40, 0, 7, 0, 115, 0, 0));
        units.add(new Unit(1, 43, blue, t(1, 205, 1, 1), "STRV 43", 57.53, 18.55, 0, 5, 0, 150, 0, 0));
        units.add(new Unit(1, 51, blue, t(1, 205, 28, 3), "LV 51", 57.67, 18.36, 0, 0, 0, 0, 0, 0)
                .beam(new Beam(1, 0, 20, 24)));
        units.add(new Unit(1, 52, blue, t(1, 205, 28, 3), "LV 52", 57.42, 18.62, 0, 0, 0, 0, 0, 0)
                .beam(new Beam(1, 0, 20, -24)));
        // opposing: a fighter pair, an EW aircraft jamming the Gripens, a frigate and a corvette
        units.add(new Unit(2, 1, red, t(2, 222, 1, 3), "RED 01", 57.75, 21.05, 8000, 240, 18_000, 0, 230, -30)
                .beam(new Beam(4, 0, 25, 0)));
        units.add(new Unit(2, 2, red, t(2, 222, 1, 3), "RED 02", 57.75, 21.05, 8000, 240, 18_000, 0, 50, -30)
                .beam(new Beam(4, 0, 25, 0)));
        Unit ew = new Unit(2, 21, red, t(2, 222, 6, 1), "RED 21", 57.30, 21.35, 7500, 150, 20_000, 0, 260, -20);
        Beam jam = new Beam(12, 0, 8, 0);
        jam.target = g11;
        ew.beam(jam);
        units.add(ew);
        units.add(new Unit(2, 31, red, t(3, 222, 6, 2), "RED 31", 57.05, 20.55, 0, 8, 0, 300, 0, 0)
                .beam(new Beam(1, 0, 12, 30)));
        units.add(new Unit(2, 32, red, t(3, 222, 6, 7), "RED 32", 58.20, 20.35, 0, 10, 0, 240, 0, 0)
                .beam(new Beam(1, 0, 12, -40)));
        // neutral traffic
        units.add(new Unit(3, 1, neutral, t(2, 205, 86, 22), "FLT 412", 57.10, 17.40, 5000, 120, 0, 40, 0, 0));
        units.add(new Unit(3, 2, neutral, t(3, 205, 16, 3), "MV NORDIC", 56.95, 18.20, 0, 7, 0, 20, 0, 0));
        units.add(new Unit(3, 3, neutral, t(3, 205, 16, 3), "MV BALTICA", 58.15, 18.05, 0, 6, 0, 200, 0, 0));

        if (ownServer) {
            String configs = get("/api/datasourceconfigs");
            create(idOf(configs, "DIS6"), "1");
            System.out.println("DEMO datasources: " + get("/api/datasources"));
        }

        // now and then a shot: Fire, then the Detonation at the target a few seconds later
        Unit[][] shots = {{g11, units.get(10)}, {units.get(13), units.get(3)}, {units.get(8), units.get(11)}, {g12, units.get(11)}};
        DatagramSocket udp = new DatagramSocket();
        long start = System.currentTimeMillis();
        int event = 1, shot = 0;
        Unit[] pending = null;
        int pendingEvent = 0;
        while (true) {
            double t = (System.currentTimeMillis() - start) / 1000.0;
            for (Unit u : units) u.at(t);
            List<byte[]> pdus = new ArrayList<byte[]>();
            for (Unit u : units) {
                pdus.add(entity(u));
                if (!u.beams.isEmpty()) pdus.add(emission(u, t, event++));
            }
            long second = Math.round(t);
            if (second % 9 == 3) {
                pending = shots[shot++ % shots.length];
                pendingEvent = event++;
                pdus.add(fire(pending[0], pending[1], pendingEvent));
            } else if (second % 9 == 7 && pending != null) {
                pdus.add(detonation(pending[0], pending[1], pendingEvent));
                pending = null;
            }
            for (byte[] pdu : pdus) for (int port : ports) send(udp, pdu, port);
            Thread.sleep(1000);
        }
    }

    private static final DisEntityType MISSILE = new DisEntityType(2, 2, 205, 1, 1, 0, 0);

    /** Fire PDU (DIS 6, 96 octets). */
    private static byte[] fire(Unit shooter, Unit target, int event) {
        Vec3 p = Ellipsoid.WGS84.toEcef(shooter.nowLat, shooter.nowLon, shooter.alt);
        DisWriter w = new DisWriter(96).header(6, 1, 2, 2, 0, 0);
        w.entityId(shooter.site, 1, shooter.number).entityId(target.site, 1, target.number).entityId(shooter.site, 1, 500 + (event & 0xFF));
        w.entityId(shooter.site, 1, event & 0xFFFF).u32(0);
        w.f64(p.getX()).f64(p.getY()).f64(p.getZ());
        w.entityType(MISSILE).u16(1000).u16(1000).u16(1).u16(0);
        w.f32(300).f32(0).f32(0).f32(20_000);
        return w.finish();
    }

    /** Detonation PDU (DIS 6, 104 octets) at the target. */
    private static byte[] detonation(Unit shooter, Unit target, int event) {
        Vec3 p = Ellipsoid.WGS84.toEcef(target.nowLat, target.nowLon, target.alt);
        DisWriter w = new DisWriter(104).header(6, 1, 3, 2, 0, 0);
        w.entityId(shooter.site, 1, shooter.number).entityId(target.site, 1, target.number).entityId(shooter.site, 1, 500 + (event & 0xFF));
        w.entityId(shooter.site, 1, event & 0xFFFF);
        w.f32(300).f32(0).f32(0);
        w.f64(p.getX()).f64(p.getY()).f64(p.getZ());
        w.entityType(MISSILE).u16(1000).u16(1000).u16(1).u16(0);
        w.f32(0).f32(0).f32(0).u8(5).u8(0).u16(0);       // result 5: detonation (no hit)
        return w.finish();
    }

    private static byte[] entity(Unit u) {
        Geodetic g = Geodetic.ofDegrees(u.nowLat, u.nowLon, u.alt);
        Vec3 p = Ellipsoid.WGS84.toEcef(u.nowLat, u.nowLon, u.alt);
        LocalFrame f = LocalFrame.at(g);
        Vec3 v = f.fromNed(Vec3.of(u.vn, u.ve, 0));
        EulerAngles o = f.toEcef(EulerAngles.ofDegrees(u.nowHeading, 0, u.roll));
        DisWriter w = new DisWriter(144).header(6, 1, 1, 1, 0, 0);
        w.entityId(u.site, 1, u.number).u8(u.force).u8(0).entityType(u.type).zeros(8);
        w.f32((float) v.getX()).f32((float) v.getY()).f32((float) v.getZ());
        w.f64(p.getX()).f64(p.getY()).f64(p.getZ());
        w.f32((float) o.getPsi()).f32((float) o.getTheta()).f32((float) o.getPhi());
        w.u32(0).u8(2).zeros(15).zeros(24).u8(1).text(u.marking, 11).u32(0);
        return w.finish();
    }

    /** One emitter system with the unit's beams; angles in radians, the sweep a half angle. */
    private static byte[] emission(Unit u, double t, int event) {
        int beamOctets = 0;
        for (Beam b : u.beams) beamOctets += 52 + (b.target != null ? 8 : 0);
        int sysLen = 20 + beamOctets;
        DisWriter w = new DisWriter(28 + sysLen).header(6, 1, 23, 6, 0, 0);
        w.entityId(u.site, 1, u.number);
        w.entityId(u.site, 1, event & 0xFFFF);
        w.u8(0).u8(1).u16(0);
        w.u8(sysLen / 4).u8(u.beams.size()).u16(0);
        w.u16(u.beams.get(0).function == 12 ? 45 : 9200).u8(u.beams.get(0).function == 12 ? 6 : 2).u8(1).f32x3(new float[] {0, 0, -5});
        int id = 1;
        for (Beam b : u.beams) {
            double c = b.target != null ? u.bearingTo(b.target) - u.nowHeading : b.centreDeg + b.rotateDegPerS * t;
            c = ((c + 180) % 360 + 360) % 360 - 180;
            w.u8((52 + (b.target != null ? 8 : 0)) / 4).u8(id++).u16(0);
            w.f32(b.function == 12 ? 9.4e9f : 3.1e9f).f32(0).f32(70).f32(1000).f32(1);
            w.f32((float) Math.toRadians(c)).f32((float) Math.toRadians(b.halfDeg)).f32(0).f32((float) Math.toRadians(20)).f32(0);
            w.u8(b.function).u8(b.target != null ? 1 : 0).u8(0).u8(0).u32(0);
            if (b.target != null) w.entityId(b.target.site, 1, b.target.number).u8(0).u8(0);
        }
        return w.finish();
    }

    private static void send(DatagramSocket s, byte[] pdu, int port) throws Exception {
        s.send(new DatagramPacket(pdu, pdu.length, InetAddress.getLoopbackAddress(), port));
    }

    // ------------------------------------------------------------------ REST (test only: any certificate)

    private static javax.net.ssl.SSLSocketFactory trustAll() throws Exception {
        javax.net.ssl.TrustManager[] tm = {new javax.net.ssl.X509TrustManager() {
            public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) { }
            public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) { }
            public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
        }};
        javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
        ctx.init(null, tm, new java.security.SecureRandom());
        return ctx.getSocketFactory();
    }

    private static javax.net.ssl.HttpsURLConnection open(String path) throws Exception {
        javax.net.ssl.HttpsURLConnection c = (javax.net.ssl.HttpsURLConnection) new URL("https://127.0.0.1:8843" + path).openConnection();
        c.setSSLSocketFactory(trustAll());
        c.setHostnameVerifier((h, x) -> true);
        return c;
    }

    private static String get(String path) throws Exception {
        javax.net.ssl.HttpsURLConnection c = open(path);
        try (InputStream in = c.getInputStream()) {
            return read(in);
        }
    }

    private static void create(String configId, String federation) throws Exception {
        javax.net.ssl.HttpsURLConnection c = open("/api/datasources");
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream o = c.getOutputStream()) {
            o.write(("{\"configid\":\"" + configId + "\",\"federation\":\"" + federation + "\"}").getBytes(StandardCharsets.UTF_8));
        }
        InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream();
        System.out.println("DEMO create " + configId + ": " + c.getResponseCode() + " " + read(in));
    }

    private static String idOf(String configs, String name) {
        Matcher mm = Pattern.compile("\\{[^{}]*?\"id\":(-?\\d+|\"[^\"]+\")[^{}]*?\"name\":\"" + Pattern.quote(name) + "\"").matcher(configs);
        if (mm.find()) return mm.group(1).replace("\"", "");
        mm = Pattern.compile("\"name\":\"" + Pattern.quote(name) + "\"[^{}]*?\"id\":(-?\\d+|\"[^\"]+\")").matcher(configs);
        return mm.find() ? mm.group(1).replace("\"", "") : "?";
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while (in != null && (r = in.read(buf)) > 0) b.write(buf, 0, r);
        return b.toString("UTF-8");
    }
}
