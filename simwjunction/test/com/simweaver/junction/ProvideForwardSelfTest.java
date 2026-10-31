package com.simweaver.junction;

import com.simweaver.common.config.SimwProperties;
import com.simweaver.common.hla.codec.HValue;
import com.simweaver.common.hla.codec.HlaCodec;
import com.simweaver.common.hla.session.ConnectSettings;
import com.simweaver.common.hla.session.RtiLibrary;
import com.simweaver.common.hla.session.RtiSession;
import com.simweaver.common.hla.session.RtiSessionListener;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.junction.config.ConfigLoader;
import com.simweaver.junction.config.GatewayConfig;
import com.simweaver.junction.router.IemRouter;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Provide requests the gateway's cache cannot answer are passed on to the object's origin
 * (0.9.19, {@code router.provide.forward}): an HLA 1516-2010 federation (the origin) and an
 * HLA 1516-2025 federation, each on its own isolated test RTI. Run without any HLA API on the
 * class path:
 *
 * <pre>java -cp out/classes:out/test com.simweaver.junction.ProvideForwardSelfTest &lt;fom root&gt; &lt;fake rti folder&gt;</pre>
 */
public final class ProvideForwardSelfTest {

    private static final String AIRCRAFT = "HLAobjectRoot.BaseEntity.PhysicalEntity.Platform.Aircraft";
    private static int checks;
    private static int failures;

    private ProvideForwardSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        File fomRoot = new File(args.length > 0 ? args[0] : "simwrift/rtdir/etc/fom").getAbsoluteFile();
        File rtiDir = new File(args.length > 1 ? args[1] : "fakerti/build").getAbsoluteFile();
        scenario(fomRoot, rtiDir, true);
        scenario(fomRoot, rtiDir, false);
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void scenario(File fomRoot, File rtiDir, boolean forward) throws Exception {
        String sfx = forward ? "" : "2";
        String t = forward ? "forward: " : "no forward: ";
        File rpr2e = new File(fomRoot, "ieee1516-2010/RPR_FOM_v2.0_Link16_1516-2010.xml");
        List<String> rpr2h4 = new ArrayList<String>();
        for (String m : new String[] {"RPR-Base_v2.0.xml", "RPR-Foundation_v2.0.xml", "RPR-Enumerations_v2.0.xml",
                "RPR-Switches_v2.0.xml", "RPR-Physical_v2.0.xml", "RPR-Warfare_v2.0.xml", "RPR-Communication_v2.0.xml"}) {
            rpr2h4.add(new File(fomRoot, "ieee1516-2025/" + m).getPath());
        }
        File jar3 = new File(rtiDir, "fakerti-1516e.jar");
        File jar4 = new File(rtiDir, "fakerti-2025.jar");

        File dir = Files.createTempDirectory("junction-provide").toFile();
        File junctions = new File(dir, "etc/junctions");
        junctions.mkdirs();
        SimwProperties p = new SimwProperties();
        p.set("junction.name", "Blue - Red");
        p.set("junction.endpoints", "blue;red");
        p.set("junction.endpoint.blue.standard", "ieee1516-2010");
        p.set("junction.endpoint.blue.hla.foms", rpr2e.getPath());
        p.set("junction.endpoint.blue.hla.federation", "Blue" + sfx);
        p.set("junction.endpoint.blue.hla.rtijar", jar3.getPath());
        p.set("junction.endpoint.red.standard", "ieee1516-2025");
        p.set("junction.endpoint.red.hla.foms", String.join(";", rpr2h4));
        p.set("junction.endpoint.red.hla.federation", "Red" + sfx);
        p.set("junction.endpoint.red.hla.rtijar", jar4.getPath());
        p.set("junction.router.objects.block", "EmbeddedSystem.*");
        p.set("junction.router.provide.forward", String.valueOf(forward));
        p.set("junction.router.provide.forwardintervalms", "600");
        GatewayConfig gc = new ConfigLoader(junctions, dir).gateway(new File(junctions, "test.junction.settings"), p);
        check(t + "config valid " + gc.getErrors(), gc.isValid());
        check(t + "setting read", gc.getRouter().isProvideForward() == forward
                && gc.getRouter().getProvideForwardIntervalMillis() == 600);

        RtiLibrary lib3 = RtiLibrary.isolated(Collections.singletonList(jar3));
        RtiLibrary lib4 = RtiLibrary.isolated(Collections.singletonList(jar4));
        InformationExchangeModel blueModel = InformationExchangeModel.fromHlaEvolved(Collections.singletonList(rpr2e.getPath()));
        HlaCodec blueCodec = new HlaCodec(blueModel);

        Gateway gw = new Gateway(gc, Collections.<String, String>emptyMap(), dir, null);
        gw.start();
        check(t + "gateway running", gw.getState() == Gateway.State.RUNNING);
        IemRouter router = (IemRouter) gw.getRouter();

        // the red simulator listens
        Fed red = new Fed(lib4, RtiSession.Version.HLA_1516_2025, "Red" + sfx, "RedSim", rpr2h4);
        int rAircraft = red.s.objectClass(AIRCRAFT);
        int rSpatial = red.s.attribute(rAircraft, "Spatial");
        int rMarking = red.s.attribute(rAircraft, "Marking");
        int rEntityId = red.s.attribute(rAircraft, "EntityIdentifier");
        red.s.subscribeObjectClass(rAircraft, new int[] {rSpatial, rMarking, rEntityId});

        // the blue simulator (the origin) sends Spatial only; it hears requests but answers on cue
        Fed blue = new Fed(lib3, RtiSession.Version.HLA_1516E, "Blue" + sfx, "BlueSim", Collections.singletonList(rpr2e.getPath()));
        int bAircraft = blue.s.objectClass(AIRCRAFT);
        int bSpatial = blue.s.attribute(bAircraft, "Spatial");
        int bMarking = blue.s.attribute(bAircraft, "Marking");
        int bEntityId = blue.s.attribute(bAircraft, "EntityIdentifier");
        blue.s.publishObjectClass(bAircraft, new int[] {bSpatial, bMarking, bEntityId});
        blue.reserve("Viper-1");
        long viper = blue.s.registerObject(bAircraft, "Viper-1");
        byte[] spatialValue = blueCodec.encode(blueModel.getClassAttribute(AIRCRAFT, "Spatial").getDataType(), spatialValue(blueCodec, blueModel));
        blue.s.updateAttributes(viper, new int[] {bSpatial}, new byte[][] {spatialValue}, null);

        String[] d = red.take("discovered", 5000);
        check(t + "red discovers the copy", d != null);
        long redCopy = d == null ? 0 : Long.parseLong(d[1]);
        check(t + "red gets Spatial", red.waitValue(rSpatial, 3000) != null);
        sleep(300);
        // the gateway asks the origin for all it subscribes to when it discovers an object
        check(t + "the gateway asked the origin on discovery", blue.requests.size() >= 1);
        blue.requests.clear();

        // red asks for Spatial (cached) and Marking (never sent): Spatial answered from the cache,
        // Marking asked of the origin
        int before = red.count("reflected");
        red.s.requestAttributeUpdate(redCopy, new int[] {rSpatial, rMarking}, null);
        Set<Integer> asked = blue.takeRequest(1500);
        if (forward) {
            check(t + "the origin is asked for the missing attribute only",
                    asked != null && asked.equals(Collections.singleton(bMarking)));
            byte[] markingValue = blueCodec.encode(blueModel.getClassAttribute(AIRCRAFT, "Marking").getDataType(), marking("VIPER1"));
            blue.s.updateAttributes(viper, new int[] {bMarking}, new byte[][] {markingValue}, null);
            check(t + "the origin's answer reaches red", red.waitValue(rMarking, 3000) != null);
            check(t + "providesforwarded counted", router.stats().providesForwarded.get() == 1);

            // now cached: no new forward
            red.s.requestAttributeUpdate(redCopy, new int[] {rMarking}, null);
            check(t + "a cached attribute is not asked of the origin", blue.takeRequest(500) == null);

            // EntityIdentifier: asked once within the interval, again after it
            red.s.requestAttributeUpdate(redCopy, new int[] {rEntityId}, null);
            red.s.requestAttributeUpdate(redCopy, new int[] {rEntityId}, null);
            Set<Integer> first = blue.takeRequest(1000);
            check(t + "EntityIdentifier asked of the origin", first != null && first.equals(Collections.singleton(bEntityId)));
            check(t + "throttled: not asked again within the interval", blue.takeRequest(300) == null);
            sleep(400);
            red.s.requestAttributeUpdate(redCopy, new int[] {rEntityId}, null);
            check(t + "asked again after the interval", blue.takeRequest(1000) != null);
            check(t + "providesforwarded = 3", router.stats().providesForwarded.get() == 3);
        } else {
            check(t + "the origin is not asked", asked == null);
            check(t + "the cached attribute still answered", red.count("reflected") > before);
            check(t + "providesforwarded = 0", router.stats().providesForwarded.get() == 0);
        }
        check(t + "providesanswered counted", router.stats().providesAnswered.get() >= 1);
        check(t + "providesforwarded in the gateway JSON",
                com.simweaver.common.json.Json.toJson(gw).contains("\"providesforwarded\""));

        gw.stop();
        blue.s.close();
        red.s.close();
    }

    private static HValue spatialValue(HlaCodec codec, InformationExchangeModel m) {
        HValue def = codec.defaultValue(m.getDataType("SpatialVariantStruct"));
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

    // ------------------------------------------------------------------ a test federate

    private static final class Fed implements RtiSessionListener {
        final RtiSession s;
        final BlockingQueue<String[]> q = new LinkedBlockingQueue<String[]>();
        final BlockingQueue<Set<Integer>> requests = new LinkedBlockingQueue<Set<Integer>>();
        final Map<Integer, byte[]> values = new ConcurrentHashMap<Integer, byte[]>();
        final Map<String, Integer> counts = new ConcurrentHashMap<String, Integer>();

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

        Set<Integer> takeRequest(long millis) throws InterruptedException {
            return requests.poll(millis, TimeUnit.MILLISECONDS);
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

        int count(String kind) {
            Integer c = counts.get(kind);
            return c == null ? 0 : c;
        }

        @Override
        public void discovered(long instance, int objectClass, String name, String producer) {
            counts.merge("discovered", 1, Integer::sum);
            q.add(new String[] {"discovered", String.valueOf(instance), String.valueOf(objectClass), name});
        }

        @Override
        public void reflected(long instance, int[] attributes, byte[][] vals, byte[] tag, String producer) {
            counts.merge("reflected", 1, Integer::sum);
            for (int i = 0; i < attributes.length; i++) {
                values.put(attributes[i], vals[i]);
            }
        }

        @Override
        public void removed(long instance, byte[] tag, String producer) {
            q.add(new String[] {"removed", String.valueOf(instance)});
        }

        @Override
        public void received(int interactionClass, int[] parameters, byte[][] vals, byte[] tag, String producer) {
        }

        @Override
        public void provideRequested(long instance, int[] attributes, byte[] tag) {
            Set<Integer> a = new HashSet<Integer>();
            for (int x : attributes) {
                a.add(x);
            }
            requests.add(a);
        }

        @Override
        public void nameReserved(String name, boolean success) {
            q.add(new String[] {"name", name, String.valueOf(success)});
        }

        @Override
        public void connectionLost(String reason) {
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
