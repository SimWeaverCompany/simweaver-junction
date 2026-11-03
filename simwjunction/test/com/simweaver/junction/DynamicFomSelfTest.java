package com.simweaver.junction;

import com.simweaver.common.config.SimwProperties;
import com.simweaver.common.hla.codec.HValue;
import com.simweaver.common.hla.codec.HlaCodec;
import com.simweaver.common.hla.session.ConnectSettings;
import com.simweaver.common.hla.session.DynamicFom;
import com.simweaver.common.hla.session.RtiLibrary;
import com.simweaver.common.hla.session.RtiSession;
import com.simweaver.common.hla.session.RtiSessionListener;
import com.simweaver.common.iem.FomFamily;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.junction.config.ConfigLoader;
import com.simweaver.junction.config.EndpointConfig;
import com.simweaver.junction.config.GatewayConfig;

import java.io.File;
import java.nio.charset.StandardCharsets;
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
 * The FOM taken from the federation ({@code endpoint.hla.foms=dynamic}): HLAunicodeString,
 * building the model from an FDD (RPR v2 / v3 recognised from the classes when the merged FDD
 * names no module family; the MIM fallback), the settings, and gateways on the test RTIs (IEEE
 * 1516-2010 and 1516-2025) whose endpoints read HLAcurrentFDD after joining: objects bridged,
 * the FOM source reported, a later joiner's extra module noticed, and a missing federation.
 *
 * <pre>java -cp out/classes:out/test com.simweaver.junction.DynamicFomSelfTest [&lt;fom root&gt; [&lt;fake rti folder&gt;]]</pre>
 */
public final class DynamicFomSelfTest {

    private static final String AIRCRAFT = "HLAobjectRoot.BaseEntity.PhysicalEntity.Platform.Aircraft";
    private static int checks;
    private static int failures;

    private DynamicFomSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        File fomRoot = new File(args.length > 0 ? args[0] : "simwrift/rtdir/etc/fom").getAbsoluteFile();
        File rtiDir = new File(args.length > 1 ? args[1] : "fakerti/build").getAbsoluteFile();
        values(fomRoot);
        settings();
        gateway(fomRoot, rtiDir, false, false);
        gateway(fomRoot, rtiDir, true, false);
        // an HLA 4 RTI serving 1516-2010 federates may report the FDD in the 1516-2025 format
        gateway(fomRoot, rtiDir, false, true);
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ values and models

    private static void values(File fomRoot) throws Exception {
        String s = "Övning ÅÄÖ ✓ 😀";
        byte[] enc = DynamicFom.encodeUnicodeString(s);
        check("HLAunicodeString: count of UTF-16 units, then the units", enc.length == 4 + 2 * s.length() && enc[3] == s.length());
        check("and back", s.equals(DynamicFom.decodeUnicodeString(enc)));
        boolean bad;
        try {
            DynamicFom.decodeUnicodeString(new byte[] {0, 0, 0, 9, 0, 65});
            bad = false;
        } catch (IllegalArgumentException e) {
            bad = true;
        }
        check("a value shorter than its count is refused", bad);
        check("the setting: dynamic, alone, any case", DynamicFom.isDynamic(new String[] {" Dynamic "})
                && !DynamicFom.isDynamic(new String[] {"dynamic", "x.xml"}) && !DynamicFom.isDynamic(new String[] {"rpr.xml"}));
        check("fingerprint: stable, 12 hex digits", DynamicFom.fingerprint("abc").equals(DynamicFom.fingerprint("abc"))
                && DynamicFom.fingerprint("abc").matches("[0-9a-f]{12}") && !DynamicFom.fingerprint("abd").equals(DynamicFom.fingerprint("abc")));

        // an RPR v2 FDD whose identification no longer says RPR: recognised from its classes
        String v2 = new String(Files.readAllBytes(new File(fomRoot, "ieee1516-2010/RPR_FOM_v2.0_Link16_1516-2010.xml").toPath()), StandardCharsets.UTF_8);
        String merged = renamed(v2);
        InformationExchangeModel m2 = DynamicFom.toModel(merged, false);
        check("RPR v2 recognised from the classes " + m2.getFamiliesFromContent(), m2.getRprFomVersion() == 2
                && m2.getFamiliesFromContent().contains(FomFamily.RPR_FOM_V2));
        check("a FOM that names its modules is not marked as recognised from content",
                InformationExchangeModel.fromHlaEvolved(Collections.singletonList(new File(fomRoot,
                        "ieee1516-2010/RPR_FOM_v2.0_Link16_1516-2010.xml").getPath())).getFamiliesFromContent().isEmpty());
        // RPR v3 modules, all renamed
        List<String> v3 = new ArrayList<String>();
        File[] mods = new File(fomRoot, "ieee1516-2010/rpr3").listFiles();
        Arrays.sort(mods);
        for (File f : mods) {
            v3.add(renamed(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
        }
        InformationExchangeModel m3 = InformationExchangeModel.fromHlaEvolved(v3);
        check("RPR v3 recognised from the classes (Fire.WeaponFire)", m3.getRprFomVersion() == 3 && m3.getFamiliesFromContent().contains(FomFamily.RPR_FOM_V3));

        // an FDD that restates the MIM with a different datatype: built without the HLAmanager classes
        String odd = v2.replace("<name>HLAobjectRoot</name>", "<name>HLAobjectRoot</name>\n<objectClass><name>HLAmanager</name><sharing>Neither</sharing>"
                + "<objectClass><name>HLAfederation</name><sharing>Neither</sharing><attribute><name>HLAcurrentFDD</name>"
                + "<dataType>HLAfloat64BE</dataType><updateType>Conditional</updateType><updateCondition>x</updateCondition>"
                + "<ownership>NoTransfer</ownership><sharing>Publish</sharing><transportation>HLAreliable</transportation>"
                + "<order>Receive</order></attribute></objectClass></objectClass>");
        boolean built;
        try {
            built = DynamicFom.toModel(odd, false).getObjectClass(AIRCRAFT) != null;
        } catch (Exception e) {
            built = false;
            System.out.println("   " + e.getMessage());
        }
        check("an FDD whose MIM differs from the standard MIM is still read", built);
        boolean empty;
        try {
            DynamicFom.toModel(" ", false);
            empty = false;
        } catch (Exception e) {
            empty = e.getMessage().contains("empty");
        }
        check("an empty FDD is refused", empty);

        // the FDD is read in the format it is written in, whatever the API
        String ns10 = "http://standards.ieee.org/IEEE1516-2010", ns25 = "http://standards.ieee.org/IEEE1516-2025";
        check("format: 1516-2010", DynamicFom.format(v2) == com.simweaver.common.iem.HlaVersion.HLA_EVOLVED);
        check("format: 1516-2025, after another namespace declaration, and with a prefix",
                DynamicFom.format("<?xml version=\"1.0\"?><objectModel xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns=\"" + ns25 + "\">")
                        == com.simweaver.common.iem.HlaVersion.HLA_4
                && DynamicFom.format("<omt:objectModel xmlns:omt='" + ns25 + "'>") == com.simweaver.common.iem.HlaVersion.HLA_4);
        check("format: none named", DynamicFom.format("<objectModel>") == null && DynamicFom.format("<x/>") == null);
        String as2025 = merged.replace(ns10, ns25);
        InformationExchangeModel m25 = null;
        try {
            m25 = DynamicFom.toModel(as2025, false);
        } catch (Exception e) {
            System.out.println("   " + e.getMessage());
        }
        check("a 1516-2025 FDD for a 1516-2010 federate is read as 1516-2025, RPR v2 recognised",
                m25 != null && m25.getHlaVersion() == com.simweaver.common.iem.HlaVersion.HLA_4
                        && m25.getObjectClass(AIRCRAFT) != null && m25.getRprFomVersion() == 2);
        check("the format note: only when the format is not the API's",
                DynamicFom.formatNote(as2025, false).equals(", written in the IEEE 1516-2025 format")
                        && DynamicFom.formatNote(as2025, true).isEmpty() && DynamicFom.formatNote(merged, false).isEmpty());

        // 0.11.1 fix: an RTI's FDD restates the standard MIM a little differently (seen with a real RTI:
        // HLAunsignedInteger8 Little endian, HLAtoken with cardinality 0): read, the standard definitions kept
        String mimCopy = "<basicData><name>HLAunsignedInteger8</name><size>8</size><interpretation>u8</interpretation>"
                + "<endian>Little</endian><encoding>8-bit unsigned integer</encoding></basicData>";
        String tokenCopy = "<arrayData><name>HLAtoken</name><dataType>HLAbyte</dataType><cardinality>0</cardinality>"
                + "<encoding>HLAvariableArray</encoding><semantics>Token</semantics></arrayData>";
        String rti25 = as2025.replaceFirst("<basicDataRepresentations>", "<basicDataRepresentations>" + mimCopy)
                .replaceFirst("<arrayDataTypes>", "<arrayDataTypes>" + tokenCopy);
        InformationExchangeModel r25 = null;
        try {
            r25 = DynamicFom.toModel(rti25, false);
        } catch (Exception e) {
            System.out.println("   " + e.getMessage());
        }
        check("an RTI's 1516-2025 FDD with its own MIM copy (HLAunsignedInteger8 Little, HLAtoken cardinality 0) is read",
                r25 != null && r25.getObjectClass(AIRCRAFT) != null);
        String warned = r25 == null ? "" : r25.getWarnings().toString();
        check("HLAtoken: a warning, the standard MIM definition kept; HLAunsignedInteger8: nothing (one octet has no byte order) "
                + warned, warned.contains("'HLAtoken'") && warned.contains("standard MIM definition is kept")
                && !warned.contains("'HLAunsignedInteger8'"));
        String rti10 = merged.replaceFirst("<arrayDataTypes>", "<arrayDataTypes>" + tokenCopy);
        boolean read10;
        try {
            read10 = DynamicFom.toModel(rti10, false).getObjectClass(AIRCRAFT) != null;
        } catch (Exception e) {
            read10 = false;
            System.out.println("   " + e.getMessage());
        }
        check("the same in a 1516-2010 FDD", read10);
        // between the user's own modules a conflicting datatype is still an error
        String conflicting = "<?xml version=\"1.0\"?><objectModel xmlns=\"" + ns10 + "\"><modelIdentification><name>Other</name>"
                + "<type>FOM</type><version>1</version></modelIdentification><dataTypes><arrayDataTypes>"
                + "<arrayData><name>EntityIdentifierStructLengthlessArray</name><dataType>HLAbyte</dataType><cardinality>4</cardinality>"
                + "<encoding>HLAfixedArray</encoding></arrayData></arrayDataTypes></dataTypes></objectModel>";
        String firstDef = "<?xml version=\"1.0\"?><objectModel xmlns=\"" + ns10 + "\"><modelIdentification><name>Mine</name>"
                + "<type>FOM</type><version>1</version></modelIdentification><dataTypes><arrayDataTypes>"
                + "<arrayData><name>EntityIdentifierStructLengthlessArray</name><dataType>HLAbyte</dataType><cardinality>Dynamic</cardinality>"
                + "<encoding>HLAvariableArray</encoding></arrayData></arrayDataTypes></dataTypes></objectModel>";
        boolean stillError;
        try {
            InformationExchangeModel.fromHlaEvolved(Arrays.asList(firstDef, conflicting));
            stillError = false;
        } catch (Exception e) {
            stillError = e.getMessage().contains("conflicts with its definition");
        }
        check("two of the user's own modules that disagree on a datatype: still an error", stillError);
    }

    /** The module identification renamed, as in a merged FDD. */
    private static String renamed(String xml) {
        int a = xml.indexOf("<modelIdentification");
        int n = xml.indexOf("<name>", a);
        int e = xml.indexOf("</name>", n);
        return xml.substring(0, n + 6) + "Merged FDD" + xml.substring(e);
    }

    // ------------------------------------------------------------------ settings

    private static void settings() {
        SimwProperties p = new SimwProperties();
        p.set("endpoint.standard", "ieee1516-2010");
        p.set("endpoint.hla.foms", "dynamic");
        p.set("endpoint.hla.federation", "X");
        EndpointConfig e = new EndpointConfig("a", p, "test", null);
        check("dynamic endpoint: valid, no files, does not create the federation " + e.getErrors(),
                e.getErrors().isEmpty() && e.isDynamicFom() && e.getFomPaths().isEmpty() && !e.isCreateFederation());
        check("defaults: 15 s, the MOM class and attribute", e.getFddTimeoutSecs() == 15
                && DynamicFom.FEDERATION_CLASS.equals(e.getFddClass()) && DynamicFom.FDD_ATTRIBUTE.equals(e.getFddAttribute()));
        SimwProperties d = new SimwProperties();
        d.set("endpoint.standard", "dis7");
        d.set("endpoint.hla.foms", "dynamic");
        EndpointConfig de = new EndpointConfig("d", d, "test", null);
        check("a DIS endpoint cannot be dynamic", de.getErrors().toString().contains("cannot take its FOM from a federation") && !de.isDynamicFom());
    }

    // ------------------------------------------------------------------ gateways

    private static void gateway(File fomRoot, File rtiDir, boolean hla4, boolean fdd2025) throws Exception {
        String tag = (hla4 ? "HLA 4" : "HLA 3") + (fdd2025 ? ", FDD in the 1516-2025 format" : "") + ": ";
        if (fdd2025) {
            System.setProperty("fakerti.fddformat", "2025");
        } else {
            System.clearProperty("fakerti.fddformat");
        }
        String std = hla4 ? "ieee1516-2025" : "ieee1516-2010";
        File jar = new File(rtiDir, hla4 ? "fakerti-2025.jar" : "fakerti-1516e.jar");
        List<String> foms = new ArrayList<String>();
        if (hla4) {
            File[] mods = new File(fomRoot, "ieee1516-2025").listFiles((dir, n) -> n.endsWith("_v2.0.xml"));
            Arrays.sort(mods);
            for (File f : mods) {
                foms.add(f.getPath());
            }
        } else {
            foms.add(new File(fomRoot, "ieee1516-2010/RPR_FOM_v2.0_Link16_1516-2010.xml").getPath());
        }
        String pre = (hla4 ? "Dyn4" : "Dyn3") + (fdd2025 ? "x" : "");
        String fa = pre + "-A", fb = pre + "-B";
        RtiLibrary lib = RtiLibrary.isolated(Collections.singletonList(jar));
        RtiSession.Version ver = hla4 ? RtiSession.Version.HLA_1516_2025 : RtiSession.Version.HLA_1516E;
        // the federations exist (created with their FOM by the simulations) before the gateway joins
        Fed simA = new Fed(lib, ver, fa, "SimA", foms, true);
        Fed simB = new Fed(lib, ver, fb, "SimB", foms, true);
        InformationExchangeModel m = hla4 ? InformationExchangeModel.fromHla4(foms) : InformationExchangeModel.fromHlaEvolved(foms);
        HlaCodec codec = new HlaCodec(m);

        File dir = Files.createTempDirectory("junction-dynamic").toFile();
        File junctions = new File(dir, "etc/junctions");
        junctions.mkdirs();
        SimwProperties p = new SimwProperties();
        p.set("junction.name", "Dynamic");
        p.set("junction.endpoints", "a;b");
        for (String[] e : new String[][] {{"a", fa}, {"b", fb}}) {
            p.set("junction.endpoint." + e[0] + ".standard", std);
            p.set("junction.endpoint." + e[0] + ".hla.foms", "dynamic");
            p.set("junction.endpoint." + e[0] + ".hla.federation", e[1]);
            p.set("junction.endpoint." + e[0] + ".hla.rtijar", jar.getPath());
            p.set("junction.endpoint." + e[0] + ".hla.fddtimeoutseconds", "5");
        }
        GatewayConfig gc = new ConfigLoader(junctions, dir).gateway(new File(junctions, "dyn.junction.settings"), p);
        check(tag + "config valid " + gc.getErrors(), gc.isValid());
        Gateway gw = new Gateway(gc, Collections.<String, String>emptyMap(), dir, null);
        try {
            gw.start();
        } catch (GatewayException e) {
            check(tag + "gateway starts: " + e.getMessage(), false);
            simA.s.close();
            simB.s.close();
            return;
        }
        check(tag + "gateway running with both FOMs from the federations", gw.getState() == Gateway.State.RUNNING);
        InformationExchangeModel em = gw.endpoint("a").model();
        check(tag + "the endpoint's model has the RPR classes, RPR v2 recognised from the merged FDD",
                em.getObjectClass(AIRCRAFT) != null && em.getRprFomVersion() == 2 && !em.getFamiliesFromContent().isEmpty());
        String js = com.simweaver.common.json.Json.toJson(gw);
        check(tag + "the gateway reports the FOM source", js.contains("\"dynamicfom\":true") && js.contains("from the federation: FDD "));
        if (fdd2025) {
            check(tag + "the RTI really gave a 1516-2025 FDD", em.getHlaVersion() == com.simweaver.common.iem.HlaVersion.HLA_4);
            check(tag + "and the FOM source says so", js.contains("written in the IEEE 1516-2025 format"));
        }

        // an aircraft in A arrives in B
        int ac = simA.s.objectClass(AIRCRAFT);
        int[] at = {simA.s.attribute(ac, "EntityType")};
        simA.s.publishObjectClass(ac, at);
        int acB = simB.s.objectClass(AIRCRAFT);
        simB.s.subscribeObjectClass(acB, new int[] {simB.s.attribute(acB, "EntityType")});
        simA.reserve("Hawk-1");
        long hawk = simA.s.registerObject(ac, "Hawk-1");
        simA.s.updateAttributes(hawk, at, new byte[][] {codec.encode(m.getClassAttribute(AIRCRAFT, "EntityType").getDataType(), entityType())}, null);
        String[] d = simB.take("discovered", 4000);
        check(tag + "an aircraft in A is discovered in B as an aircraft", d != null && "Hawk-1".equals(d[3]) && d[2].equals(String.valueOf(acB)));
        check(tag + "the MOM federation object is not bridged", !"HLAfederation.".equals(d == null ? "" : d[3].replaceAll("[^.]*$", "")));

        // a federate joins A with one more module: the FDD changes, the endpoint says so
        File extra = new File(dir, "extra.xml");
        Files.write(extra.toPath(), extraModule(hla4).getBytes(StandardCharsets.UTF_8));
        Fed late = new Fed(lib, ver, fa, "Late", Collections.singletonList(extra.getPath()), false);
        String notice = null;
        for (int i = 0; i < 30 && notice == null; i++) {
            sleep(100);
            notice = ((com.simweaver.junction.endpoint.hla.HlaEndpoint) gw.endpoint("a")).fomNotice();
        }
        check(tag + "a later module is noticed: " + notice, notice != null && notice.contains("restart the gateway"));
        check(tag + "and reported", com.simweaver.common.json.Json.toJson(gw).contains("\"fomnotice\""));
        gw.stop();
        late.s.close();

        // a dynamic endpoint cannot create a federation
        SimwProperties q = new SimwProperties();
        q.set("junction.endpoints", "a;b");
        q.set("junction.endpoint.a.standard", std);
        q.set("junction.endpoint.a.hla.foms", "dynamic");
        q.set("junction.endpoint.a.hla.federation", "NoSuchFederation");
        q.set("junction.endpoint.a.hla.rtijar", jar.getPath());
        q.set("junction.endpoint.b.standard", std);
        q.set("junction.endpoint.b.hla.foms", "dynamic");
        q.set("junction.endpoint.b.hla.federation", fb);
        q.set("junction.endpoint.b.hla.rtijar", jar.getPath());
        GatewayConfig gq = new ConfigLoader(junctions, dir).gateway(new File(junctions, "none.junction.settings"), q);
        Gateway none = new Gateway(gq, Collections.<String, String>emptyMap(), dir, null);
        String why = null;
        try {
            none.start();
        } catch (GatewayException e) {
            why = e.getMessage();
        }
        check(tag + "a missing federation: the start fails and says what to do: " + why, why != null
                && why.contains("endpoint.hla.createfoms"));

        // 0.9.21: with endpoint.hla.createfoms the endpoint creates the missing federation and still reads its FDD
        q.set("junction.endpoint.a.hla.federation", pre + "-Created");
        q.set("junction.endpoint.a.hla.createfoms", String.join(";", foms));
        GatewayConfig gc2 = new ConfigLoader(junctions, dir).gateway(new File(junctions, "create.junction.settings"), q);
        check(tag + "createfoms: valid, creates", gc2.isValid() && gc2.getEndpoints().get("a").isCreateFederation());
        Gateway created = new Gateway(gc2, Collections.<String, String>emptyMap(), dir, null);
        String why2 = null;
        try {
            created.start();
        } catch (GatewayException e) {
            why2 = e.getMessage();
        }
        check(tag + "createfoms: the missing federation is created and joined " + why2, created.getState() == Gateway.State.RUNNING);
        check(tag + "createfoms: the FOM still comes from the federation", com.simweaver.common.json.Json.toJson(created)
                .contains("from the federation: FDD"));
        created.stop();
        simA.s.close();
        simB.s.close();
        System.clearProperty("fakerti.fddformat");
    }

    /** A small extra module: a subclass of Aircraft. */
    private static String extraModule(boolean hla4) {
        String ns = hla4 ? "http://standards.ieee.org/IEEE1516-2025" : "http://standards.ieee.org/IEEE1516-2010";
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<objectModel xmlns=\"" + ns + "\">\n"
                + "<modelIdentification><name>Extra</name><type>FOM</type><version>1.0</version></modelIdentification>\n"
                + "<objects><objectClass><name>HLAobjectRoot</name><objectClass><name>BaseEntity</name><objectClass><name>PhysicalEntity</name>"
                + "<objectClass><name>Platform</name><objectClass><name>Aircraft</name><objectClass><name>Drone</name><sharing>PublishSubscribe</sharing>"
                + "</objectClass></objectClass></objectClass></objectClass></objectClass></objectClass></objects>\n</objectModel>\n";
    }

    private static HValue entityType() {
        Map<String, HValue> t = new LinkedHashMap<String, HValue>();
        t.put("EntityKind", HValue.ofInt(1));
        t.put("Domain", HValue.ofInt(2));
        t.put("CountryCode", HValue.ofInt(225));
        t.put("Category", HValue.ofInt(1));
        t.put("Subcategory", HValue.ofInt(3));
        t.put("Specific", HValue.ofInt(0));
        t.put("Extra", HValue.ofInt(0));
        return HValue.ofRecord(t);
    }

    // ------------------------------------------------------------------ a test federate

    private static final class Fed implements RtiSessionListener {
        final RtiSession s;
        final BlockingQueue<String[]> q = new LinkedBlockingQueue<String[]>();

        Fed(RtiLibrary lib, RtiSession.Version v, String federation, String name, List<String> foms, boolean create) throws Exception {
            s = lib.createSession(v);
            s.connect(ConnectSettings.defaults(), this);
            s.join(federation, "Tester", name, foms, create);
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

        @Override
        public void discovered(long instance, int objectClass, String name, String producer) {
            q.add(new String[] {"discovered", String.valueOf(instance), String.valueOf(objectClass), name});
        }

        @Override
        public void reflected(long instance, int[] attributes, byte[][] vals, byte[] tag, String producer) {
        }

        @Override
        public void removed(long instance, byte[] tag, String producer) {
        }

        @Override
        public void received(int interactionClass, int[] parameters, byte[][] vals, byte[] tag, String producer) {
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
