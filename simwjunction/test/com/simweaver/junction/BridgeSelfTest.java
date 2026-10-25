package com.simweaver.junction;

import com.simweaver.common.config.SimwProperties;
import com.simweaver.common.hla.codec.HValue;
import com.simweaver.common.hla.codec.HlaCodec;
import com.simweaver.common.hla.session.ConnectSettings;
import com.simweaver.common.hla.session.RtiLibrary;
import com.simweaver.common.hla.session.RtiSession;
import com.simweaver.common.hla.session.RtiSessionListener;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.junction.config.ClassFilter;
import com.simweaver.junction.config.ConfigLoader;
import com.simweaver.junction.config.GatewayConfig;
import com.simweaver.junction.router.IemRouter;
import com.simweaver.junction.router.MemberRoute;
import com.simweaver.junction.router.RouteTable;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end test of a gateway between an IEEE 1516-2010 federation (RPR v2) and an IEEE
 * 1516-2025 federation (RPR v2, HLA 4 modules), each on its own isolated test RTI
 * ({@code endpoint.hla.rtijar}). Run without any HLA API on the class path:
 *
 * <pre>java -cp out/classes:out/test com.simweaver.junction.BridgeSelfTest &lt;fom root&gt; &lt;fake rti folder&gt;</pre>
 */
public final class BridgeSelfTest {

    private static final String AIRCRAFT = "HLAobjectRoot.BaseEntity.PhysicalEntity.Platform.Aircraft";
    private static final String WEAPON_FIRE = "HLAinteractionRoot.WeaponFire";
    private static int checks;
    private static int failures;

    private BridgeSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        File fomRoot = new File(args.length > 0 ? args[0] : "simwrift/rtdir/etc/fom").getAbsoluteFile();
        File rtiDir = new File(args.length > 1 ? args[1] : "fakerti/build").getAbsoluteFile();
        filterTests();
        bridgeTest(fomRoot, rtiDir);
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ filter

    private static void filterTests() {
        ClassFilter f = new ClassFilter(Collections.singletonList("*"),
                Arrays.asList("EmbeddedSystem.*", "BaseEntity.PhysicalEntity.Platform.Aircraft"),
                Arrays.asList("WeaponFire", "RadioSignal.*"), Collections.<String>emptyList());
        check("filter: platform allowed", f.allowsObjectClass("HLAobjectRoot.BaseEntity.PhysicalEntity.Platform"));
        check("filter: aircraft blocked (exact)", !f.allowsObjectClass(AIRCRAFT));
        check("filter: embedded subtree blocked", !f.allowsObjectClass("HLAobjectRoot.EmbeddedSystem.RadioTransmitter"));
        check("filter: embedded itself blocked", !f.allowsObjectClass("HLAobjectRoot.EmbeddedSystem"));
        check("filter: root never", !f.allowsObjectClass("HLAobjectRoot"));
        check("filter: MOM never", !f.allowsObjectClass("HLAobjectRoot.HLAmanager.HLAfederate"));
        check("filter: weapon fire allowed", f.allowsInteractionClass(WEAPON_FIRE));
        check("filter: radio signal subclass allowed", f.allowsInteractionClass("HLAinteractionRoot.RadioSignal.EncodedAudioRadioSignal"));
        check("filter: collision not in allow list", !f.allowsInteractionClass("HLAinteractionRoot.Collision"));
        ClassFilter g = new ClassFilter(Collections.<String>emptyList(), Collections.singletonList("**.Aircraft"),
                Collections.<String>emptyList(), Collections.<String>emptyList());
        check("filter: ** matches any depth", !g.allowsObjectClass(AIRCRAFT) && g.allowsObjectClass("HLAobjectRoot.BaseEntity"));
        ClassFilter h = new ClassFilter(Collections.singletonList("BaseEntity.*"), Collections.singletonList("BaseEntity.PhysicalEntity.*"),
                Collections.<String>emptyList(), Collections.<String>emptyList());
        check("filter: more specific block wins", !h.allowsObjectClass(AIRCRAFT) && h.allowsObjectClass("HLAobjectRoot.BaseEntity.AggregateEntity"));
        ClassFilter k = new ClassFilter(Collections.singletonList("BaseEntity.PhysicalEntity.Platform.*"), Collections.singletonList("BaseEntity.*"),
                Collections.<String>emptyList(), Collections.<String>emptyList());
        check("filter: more specific allow wins", k.allowsObjectClass(AIRCRAFT) && !k.allowsObjectClass("HLAobjectRoot.BaseEntity.AggregateEntity"));
        ClassFilter glob = new ClassFilter(Collections.singletonList("BaseEntity.PhysicalEntity.Platform.*Vehicle"), Collections.<String>emptyList(),
                Collections.<String>emptyList(), Collections.<String>emptyList());
        check("filter: segment glob", glob.allowsObjectClass("HLAobjectRoot.BaseEntity.PhysicalEntity.Platform.GroundVehicle")
                && !glob.allowsObjectClass(AIRCRAFT));
    }

    // ------------------------------------------------------------------ bridge

    private static void bridgeTest(File fomRoot, File rtiDir) throws Exception {
        File rpr2e = new File(fomRoot, "ieee1516-2010/RPR_FOM_v2.0_Link16_1516-2010.xml");
        List<String> rpr2h4 = new ArrayList<String>();
        for (String m : new String[] {"RPR-Base_v2.0.xml", "RPR-Foundation_v2.0.xml", "RPR-Enumerations_v2.0.xml",
                "RPR-Switches_v2.0.xml", "RPR-Physical_v2.0.xml", "RPR-Warfare_v2.0.xml", "RPR-Communication_v2.0.xml"}) {
            rpr2h4.add(new File(fomRoot, "ieee1516-2025/" + m).getPath());
        }
        File jar3 = new File(rtiDir, "fakerti-1516e.jar");
        File jar4 = new File(rtiDir, "fakerti-2025.jar");

        File dir = Files.createTempDirectory("junction-test").toFile();
        File junctions = new File(dir, "etc/junctions");
        junctions.mkdirs();
        SimwProperties p = new SimwProperties();
        p.set("junction.name", "Blue - Red");
        p.set("junction.endpoints", "blue;red");
        p.set("junction.endpoint.blue.standard", "ieee1516-2010");
        p.set("junction.endpoint.blue.hla.foms", rpr2e.getPath());
        p.set("junction.endpoint.blue.hla.federation", "Blue");
        p.set("junction.endpoint.blue.hla.rtijar", jar3.getPath());
        p.set("junction.endpoint.red.standard", "ieee1516-2025");
        p.set("junction.endpoint.red.hla.foms", String.join(";", rpr2h4));
        p.set("junction.endpoint.red.hla.federation", "Red");
        p.set("junction.endpoint.red.hla.rtijar", jar4.getPath());
        p.set("junction.endpoint.red.hla.reconnectintervalseconds", "1");
        p.set("junction.router.objects.block", "EmbeddedSystem.*");
        GatewayConfig gc = new ConfigLoader(junctions, dir).gateway(new File(junctions, "test.junction.settings"), p);
        check("config valid " + gc.getErrors(), gc.isValid());

        RtiLibrary lib3 = RtiLibrary.isolated(Collections.singletonList(jar3));
        RtiLibrary lib4 = RtiLibrary.isolated(Collections.singletonList(jar4));
        InformationExchangeModel blueModel = InformationExchangeModel.fromHlaEvolved(Collections.singletonList(rpr2e.getPath()));
        InformationExchangeModel redModel = InformationExchangeModel.fromHla4(rpr2h4);
        HlaCodec blueCodec = new HlaCodec(blueModel);
        HlaCodec redCodec = new HlaCodec(redModel);

        // Red already has an object named Viper-1 (of a class the gateway does not bridge)
        Fed red = new Fed(lib4, RtiSession.Version.HLA_1516_2025, "Red", "RedSim", rpr2h4);
        int other = red.s.objectClass("HLAobjectRoot.SomethingElse");
        red.s.publishObjectClass(other, new int[] {red.s.attribute(other, "X")});
        red.reserve("Viper-1");
        red.s.registerObject(other, "Viper-1");

        Gateway gw = new Gateway(gc, Collections.<String, String>emptyMap(), dir, null);
        gw.start();
        check("gateway running", gw.getState() == Gateway.State.RUNNING);

        IemRouter router = (IemRouter) gw.getRouter();
        RouteTable br = router.routeTable("blue", "red");
        check("route table blue->red", br != null);
        int blueAircraft = blueModel.getObjectClass(AIRCRAFT).getId();
        check("aircraft routed", br.objectRoute(blueAircraft) != null);
        MemberRoute spatial = br.objectRoute(blueAircraft).bySource(blueModel.getClassAttribute(AIRCRAFT, "Spatial").getId());
        check("Spatial passes as is (same encoding in both FOM formats)", spatial != null && spatial.getOp() == MemberRoute.Op.PASS);
        int blueFire = blueModel.getInteractionClass(WEAPON_FIRE).getId();
        MemberRoute firing = br.interactionRoute(blueFire).bySource(blueModel.getClassParameter(WEAPON_FIRE, "FiringObjectIdentifier").getId());
        check("FiringObjectIdentifier rewrites references", firing != null && firing.getOp() == MemberRoute.Op.REWRITE_REFS);
        check("embedded systems blocked", br.objectRoute(blueModel.getObjectClass("HLAobjectRoot.EmbeddedSystem.RadioTransmitter").getId()) == null);

        // the red simulator listens
        int rAircraft = red.s.objectClass(AIRCRAFT);
        int rSpatial = red.s.attribute(rAircraft, "Spatial");
        int rMarking = red.s.attribute(rAircraft, "Marking");
        int rEntityId = red.s.attribute(rAircraft, "EntityIdentifier");
        red.s.subscribeObjectClass(rAircraft, new int[] {rSpatial, rMarking, rEntityId});
        int rFire = red.s.interactionClass(WEAPON_FIRE);
        red.s.subscribeInteractionClass(rFire);

        // the blue simulator publishes an aircraft
        Fed blue = new Fed(lib3, RtiSession.Version.HLA_1516E, "Blue", "BlueSim", Collections.singletonList(rpr2e.getPath()));
        int bAircraft = blue.s.objectClass(AIRCRAFT);
        int bSpatial = blue.s.attribute(bAircraft, "Spatial");
        int bMarking = blue.s.attribute(bAircraft, "Marking");
        int bEntityId = blue.s.attribute(bAircraft, "EntityIdentifier");
        blue.s.publishObjectClass(bAircraft, new int[] {bSpatial, bMarking, bEntityId});
        blue.reserve("Viper-1");
        long viper = blue.s.registerObject(bAircraft, "Viper-1");
        byte[] spatialValue = blueCodec.encode(blueModel.getClassAttribute(AIRCRAFT, "Spatial").getDataType(), spatialValue(blueCodec, blueModel));
        byte[] markingValue = blueCodec.encode(blueModel.getClassAttribute(AIRCRAFT, "Marking").getDataType(), marking("VIPER1"));
        blue.s.updateAttributes(viper, new int[] {bSpatial, bMarking}, new byte[][] {spatialValue, markingValue}, null);

        String[] d = red.take("discovered", 5000);
        check("red discovers the bridged aircraft, renamed because Viper-1 is taken there",
                d != null && "Viper-1@blue".equals(d[3]) && d[2].equals(String.valueOf(rAircraft)));
        long redCopy = d == null ? 0 : Long.parseLong(d[1]);
        String[] r = red.takeReflect(rSpatial, 5000);
        check("red gets Spatial, octets unchanged", r != null && Arrays.equals(red.lastValue(rSpatial), spatialValue));
        check("red gets Marking", red.waitValue(rMarking, 3000) != null);

        // an interaction that refers to the aircraft by name
        int bFire = blue.s.interactionClass(WEAPON_FIRE);
        blue.s.publishInteractionClass(bFire);
        DataType_ ids = new DataType_(blueModel, WEAPON_FIRE);
        int pFiring = blue.s.parameter(bFire, "FiringObjectIdentifier");
        int pTarget = blue.s.parameter(bFire, "TargetObjectIdentifier");
        int pQuantity = blue.s.parameter(bFire, "QuantityFired");
        blue.s.sendInteraction(bFire, new int[] {pFiring, pTarget, pQuantity}, new byte[][] {
            blueCodec.encode(ids.param("FiringObjectIdentifier"), HValue.ofText("Viper-1")),
            blueCodec.encode(ids.param("TargetObjectIdentifier"), HValue.ofText("Unknown-9")),
            blueCodec.encode(ids.param("QuantityFired"), HValue.ofInt(4))}, null);
        String[] rx = red.take("received", 5000);
        check("red receives WeaponFire", rx != null);
        if (rx != null) {
            DataType_ rids = new DataType_(redModel, WEAPON_FIRE);
            int rpFiring = red.s.parameter(rFire, "FiringObjectIdentifier");
            int rpTarget = red.s.parameter(rFire, "TargetObjectIdentifier");
            int rpQuantity = red.s.parameter(rFire, "QuantityFired");
            Map<Integer, byte[]> params = red.lastInteraction;
            check("firing object renamed to the copy's name",
                    "Viper-1@blue".equals(redCodec.decode(rids.param("FiringObjectIdentifier"), params.get(rpFiring)).asTrimmedText()));
            check("unknown target kept", "Unknown-9".equals(redCodec.decode(rids.param("TargetObjectIdentifier"), params.get(rpTarget)).asTrimmedText()));
            check("quantity passed", redCodec.decode(rids.param("QuantityFired"), params.get(rpQuantity)).asLong() == 4);
        }

        // provide: red asks, the gateway answers from its cache
        int before = red.count("reflected");
        red.s.requestAttributeUpdate(redCopy, new int[] {rSpatial}, null);
        sleep(500);
        check("red's request answered from the cache", red.count("reflected") > before);

        // a late joiner in red gets the object and its values
        Fed late = new Fed(lib4, RtiSession.Version.HLA_1516_2025, "Red", "LateSim", rpr2h4);
        int lAircraft = late.s.objectClass(AIRCRAFT);
        int lSpatial = late.s.attribute(lAircraft, "Spatial");
        late.s.subscribeObjectClass(lAircraft, new int[] {lSpatial});
        String[] ld = late.take("discovered", 3000);
        check("late joiner discovers the copy", ld != null && "Viper-1@blue".equals(ld[3]));
        late.s.requestAttributeUpdate(Long.parseLong(ld[1]), new int[] {lSpatial}, null);
        check("late joiner gets the values", late.waitValue(lSpatial, 3000) != null && Arrays.equals(late.lastValue(lSpatial), spatialValue));

        // the other way: red's aircraft into blue
        blue.s.subscribeObjectClass(bAircraft, new int[] {bSpatial, bMarking});
        red.s.publishObjectClass(rAircraft, new int[] {rSpatial, rMarking});
        red.reserve("Hawk-2");
        long hawk = red.s.registerObject(rAircraft, "Hawk-2");
        red.s.updateAttributes(hawk, new int[] {rMarking}, new byte[][] {
            redCodec.encode(redModel.getClassAttribute(AIRCRAFT, "Marking").getDataType(), marking("HAWK2"))}, null);
        String[] bd = blue.take("discovered", 3000);
        check("blue discovers red's aircraft with its own name", bd != null && "Hawk-2".equals(bd[3]));
        check("blue gets Hawk-2's marking", blue.waitValue(bMarking, 3000) != null);
        check("the gateway's own copies are not bridged back", blue.count("discovered") == 1);

        // removal
        blue.s.deleteObject(viper, null);
        check("red sees the copy removed", red.take("removed", 3000) != null);

        // connection loss of the red side: the gateway reconnects and rebuilds its copies
        blue.reserve("Viper-3");
        long viper3 = blue.s.registerObject(bAircraft, "Viper-3");
        blue.s.updateAttributes(viper3, new int[] {bMarking}, new byte[][] {markingValue}, null);
        sleep(300);
        Class<?> bus4 = Class.forName("fakerti.core.Bus", true, red.s.getClass().getClassLoader());
        Method kill = bus4.getMethod("kill", String.class);
        kill.invoke(null, "Red");
        sleep(400);
        check("gateway degraded while red is down", gw.getState() == Gateway.State.DEGRADED);
        long until = System.currentTimeMillis() + 6000;
        while (gw.getState() != Gateway.State.RUNNING && System.currentTimeMillis() < until) {
            sleep(100);
        }
        check("gateway running again after the reconnect", gw.getState() == Gateway.State.RUNNING);
        Fed red2 = new Fed(lib4, RtiSession.Version.HLA_1516_2025, "Red", "RedSim2", rpr2h4);
        int r2Aircraft = red2.s.objectClass(AIRCRAFT);
        int r2Marking = red2.s.attribute(r2Aircraft, "Marking");
        red2.s.subscribeObjectClass(r2Aircraft, new int[] {r2Marking});
        String[] rd2 = red2.take("discovered", 4000);
        check("the copy exists again in red", rd2 != null && "Viper-3".equals(rd2[3]));
        if (rd2 != null) {
            red2.s.requestAttributeUpdate(Long.parseLong(rd2[1]), new int[] {r2Marking}, null);
            check("and has its values", red2.waitValue(r2Marking, 3000) != null);
        }
        check("blue lost red's Hawk-2 copy when red went down", blue.take("removed", 3000) != null);

        System.out.println("gateway: " + com.simweaver.common.json.Json.toJson(gw));
        gw.stop();
        check("gateway stopped", gw.getState() == Gateway.State.STOPPED);
        check("red sees the gateway's copy deleted on stop", red2.take("removed", 3000) != null);
        blue.s.close();
        red.s.close();
        red2.s.close();
        late.s.close();
    }

    private static HValue spatialValue(HlaCodec codec, InformationExchangeModel m) {
        HValue def = codec.defaultValue(m.getDataType("SpatialVariantStruct"));
        // DRM_FPW with a location
        Map<String, HValue> loc = new LinkedHashMap<String, HValue>();
        loc.put("X", HValue.ofFloat(3100000.5));
        loc.put("Y", HValue.ofFloat(1000000.25));
        loc.put("Z", HValue.ofFloat(5400000.125));
        HValue fp = codec.defaultValue(m.getDataType("SpatialFPStruct")).withField("WorldLocation", HValue.ofRecord(loc));
        return HValue.ofVariant(2, "DRM_FPW", "SpatialFPW", fp).withType(def.getType());
    }

    private static HValue marking(String text) {
        Map<String, HValue> mk = new LinkedHashMap<String, HValue>();
        mk.put("MarkingEncodingType", HValue.ofEnum(1, "ASCII"));
        mk.put("MarkingData", HValue.ofText(text));
        return HValue.ofRecord(mk);
    }

    /** Parameter datatypes of an interaction class. */
    private static final class DataType_ {
        final InformationExchangeModel m;
        final String cls;

        DataType_(InformationExchangeModel m, String cls) {
            this.m = m;
            this.cls = cls;
        }

        com.simweaver.common.iem.DataType param(String p) {
            return m.getClassParameter(cls, p).getDataType();
        }
    }

    // ------------------------------------------------------------------ a test federate

    private static final class Fed implements RtiSessionListener {
        final RtiSession s;
        final BlockingQueue<String[]> q = new LinkedBlockingQueue<String[]>();
        final Map<Integer, byte[]> values = new java.util.concurrent.ConcurrentHashMap<Integer, byte[]>();
        final Map<String, Integer> counts = new java.util.concurrent.ConcurrentHashMap<String, Integer>();
        volatile Map<Integer, byte[]> lastInteraction;

        Fed(RtiLibrary lib, RtiSession.Version v, String federation, String name, List<String> foms) throws Exception {
            s = lib.createSession(v);
            s.connect(ConnectSettings.defaults(), this);
            s.join(federation, "Tester", name, foms, true);
        }

        void reserve(String name) throws Exception {
            s.reserveName(name);
            String[] r = take("name", 3000);
            if (r == null || !r[2].equals("true")) {
                throw new IllegalStateException("could not reserve " + name);
            }
        }

        String[] take(String kind, long millis) throws InterruptedException {
            long end = System.currentTimeMillis() + millis;
            List<String[]> skipped = new ArrayList<String[]>();
            try {
                while (System.currentTimeMillis() < end) {
                    String[] e = q.poll(Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                    if (e == null) {
                        return null;
                    }
                    if (e[0].equals(kind)) {
                        return e;
                    }
                    skipped.add(e);
                }
                return null;
            } finally {
                q.addAll(skipped);
            }
        }

        String[] takeReflect(int attr, long millis) throws InterruptedException {
            return waitValue(attr, millis) == null ? null : new String[] {"reflected"};
        }

        byte[] waitValue(int attr, long millis) {
            long end = System.currentTimeMillis() + millis;
            while (System.currentTimeMillis() < end) {
                byte[] v = values.get(attr);
                if (v != null) {
                    return v;
                }
                sleep(20);
            }
            return null;
        }

        byte[] lastValue(int attr) {
            return values.get(attr);
        }

        int count(String kind) {
            Integer c = counts.get(kind);
            return c == null ? 0 : c;
        }

        private void inc(String kind) {
            counts.merge(kind, 1, Integer::sum);
        }

        @Override
        public void discovered(long instance, int objectClass, String name, String producer) {
            inc("discovered");
            q.add(new String[] {"discovered", String.valueOf(instance), String.valueOf(objectClass), name});
        }

        @Override
        public void reflected(long instance, int[] attributes, byte[][] vals, byte[] tag, String producer) {
            inc("reflected");
            for (int i = 0; i < attributes.length; i++) {
                values.put(attributes[i], vals[i]);
            }
        }

        @Override
        public void removed(long instance, byte[] tag, String producer) {
            inc("removed");
            q.add(new String[] {"removed", String.valueOf(instance)});
        }

        @Override
        public void received(int interactionClass, int[] parameters, byte[][] vals, byte[] tag, String producer) {
            Map<Integer, byte[]> m = new LinkedHashMap<Integer, byte[]>();
            for (int i = 0; i < parameters.length; i++) {
                m.put(parameters[i], vals[i]);
            }
            lastInteraction = m;
            q.add(new String[] {"received", String.valueOf(interactionClass)});
        }

        @Override
        public void provideRequested(long instance, int[] attributes, byte[] tag) {
        }

        @Override
        public void nameReserved(String name, boolean success) {
            q.add(new String[] {"name", name, String.valueOf(success)});
        }

        @Override
        public void connectionLost(String reason) {
            q.add(new String[] {"lost", reason});
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what);
        }
    }
}
