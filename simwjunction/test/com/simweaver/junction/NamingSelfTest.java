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
import com.simweaver.junction.config.NameCriteria;
import com.simweaver.junction.config.RouterConfig;
import com.simweaver.junction.router.CopyNames;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Name templates for copies ({@code router.names.template[.endpoint]}, {@code router.names.maxlength}):
 * the template, its tokens, cutting to a length, the settings and their mistakes, and a gateway
 * between two HLA Evolved federations (test RTI) that renames only into one of them, with
 * references mapped both ways in a simple attribute, an array, a record and interaction parameters.
 * 0.14.0: which objects get the template ({@code router.names.select / except .classes / .names}),
 * the others keeping their name (with the usual suffix on a clash), and references inside RPR v3
 * variant records (VariableParametersArray) through a gateway, both ways.
 *
 * <pre>java -cp out/classes:out/test com.simweaver.junction.NamingSelfTest [&lt;fom root&gt; [&lt;fake rti folder&gt;]]</pre>
 */
public final class NamingSelfTest {

    private static final String AIRCRAFT = "HLAobjectRoot.BaseEntity.PhysicalEntity.Platform.Aircraft";
    private static final String AGGREGATE = "HLAobjectRoot.BaseEntity.AggregateEntity";
    private static final String TX = "HLAobjectRoot.EmbeddedSystem.RadioTransmitter";
    private static final String FIRE = "HLAinteractionRoot.WeaponFire";
    private static final String DETONATION = "HLAinteractionRoot.MunitionDetonation";
    private static int checks;
    private static int failures;

    private NamingSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        File fomRoot = new File(args.length > 0 ? args[0] : "simwrift/rtdir/etc/fom").getAbsoluteFile();
        File rtiDir = new File(args.length > 1 ? args[1] : "fakerti/build").getAbsoluteFile();
        templates();
        settings();
        criteriaSettings();
        gateway(fomRoot, rtiDir);
        criteria(fomRoot, rtiDir);
        variants(fomRoot, rtiDir);
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ the template

    private static void templates() {
        UUID u = UUID.fromString("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");
        check("a good template", CopyNames.check("RED-{name}-{hexguid}").isEmpty());
        check("unknown token", CopyNames.check("RED-{nam}").toString().contains("unknown token {nam}"));
        check("'{' without '}'", CopyNames.check("RED-{name").toString().contains("has no '}'"));
        check("'}' without '{'", CopyNames.check("RED-name}").toString().contains("has no '{'"));
        check("tokens: " + CopyNames.expand("{name}|{hexguid}|{HEXGUID}|{guid}|{origin}", "Tank-12", u, "int"),
                CopyNames.expand("{name}|{hexguid}|{HEXGUID}|{guid}|{origin}", "Tank-12", u, "int")
                        .equals("Tank-12|0f1e2d3c4b5a69788796a5b4c3d2e1f0|0F1E2D3C4B5A69788796A5B4C3D2E1F0|0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0|int"));
        check("no limit", CopyNames.fit("ABCDEFGHIJ", "", 0).equals("ABCDEFGHIJ"));
        check("cut to 6", CopyNames.fit("ABCDEFGHIJ", "", 6).equals("ABCDEF"));
        check("cut to 6 with #2 inside: " + CopyNames.fit("ABCDEFGHIJ", "#2", 6), CopyNames.fit("ABCDEFGHIJ", "#2", 6).equals("ABCD#2"));
        check("cut counts characters, not octets", CopyNames.fit("ÅÄÖåäö", "", 3).equals("ÅÄÖ"));
        check("a surrogate pair is not split", CopyNames.fit("a😀b", "", 2).equals("a😀"));
        List<String> c = CopyNames.fromTemplate("RED-{name}", 0, "Tank-12", u, "int");
        check("candidates: the name, then #2..#4 " + c, c.equals(Arrays.asList("RED-Tank-12", "RED-Tank-12#2", "RED-Tank-12#3", "RED-Tank-12#4")));
        c = CopyNames.fromTemplate("RED-{name}", 8, "VeryLongName", u, "int");
        check("candidates within 8 " + c, c.equals(Arrays.asList("RED-Very", "RED-Ve#2", "RED-Ve#3", "RED-Ve#4")));
        check("a result starting with HLA gets the origin in front",
                CopyNames.fromTemplate("HLA{name}", 0, "x", u, "int").get(0).equals("int.HLAx"));
        check("an empty result falls back to the name", CopyNames.fromTemplate("{origin}", 0, "x", u, "").get(0).equals("x"));
        check("policy names cut, numbering kept " + CopyNames.limited(Arrays.asList("LongVehicle", "LongVehicle@int", "LongVehicle@int#2"), 10),
                CopyNames.limited(Arrays.asList("LongVehicle", "LongVehicle@int", "LongVehicle@int#2"), 10)
                        .equals(Arrays.asList("LongVehicl", "LongVe@int", "Long@int#2")));
    }

    // ------------------------------------------------------------------ the settings

    private static void settings() throws Exception {
        SimwProperties p = new SimwProperties();
        p.set("router.names.template", "ALL-{name}");
        p.set("router.names.template.ext", "RED-{name}-{hexguid}");
        p.set("router.names.template.int", "");
        p.set("router.names.maxlength", "30");
        p.set("router.names.maxlength.ext", "24");
        RouterConfig r = new RouterConfig("t", p, "inline", null);
        check("valid " + r.getErrors(), r.getErrors().isEmpty());
        check("per endpoint template wins", "RED-{name}-{hexguid}".equals(r.getNameTemplate("ext")));
        check("an empty one cancels the general one", r.getNameTemplate("int") == null);
        check("others get the general one", "ALL-{name}".equals(r.getNameTemplate("third")));
        check("lengths: per endpoint, else general", r.getNameMaxLength("ext") == 24 && r.getNameMaxLength("int") == 30);
        check("scopes " + r.getNameScopes(), r.getNameScopes().toString().equals("[ext, int]"));

        SimwProperties bad = new SimwProperties();
        bad.set("router.names.template.ext", "RED-{nam}");
        bad.set("router.names.maxlength", "abc");
        bad.set("router.names.maxlength.int", "-2");
        bad.set("router.names.template.b", "FIXED");
        RouterConfig rb = new RouterConfig("t", bad, "inline", null);
        String e = rb.getErrors().toString();
        check("mistakes are errors " + e, e.contains("unknown token {nam}") && e.contains("'abc' is not a length") && e.contains("'-2' is not a length"));
        check("a template without {name} or a GUID warns", rb.getWarnings().toString().contains("every copy gets the same name"));

        // gateway warnings: an endpoint the gateway lacks, and a DIS endpoint
        File dir = Files.createTempDirectory("junction-naming-cfg").toFile();
        File j = new File(dir, "etc/junctions");
        j.mkdirs();
        Files.write(new File(j, "w.junction.settings").toPath(), ("junction.endpoints=a;d\n"
                + "junction.endpoint.a.standard=ieee1516-2010\njunction.endpoint.a.hla.federation=A\n"
                + "junction.endpoint.d.standard=dis7\n"
                + "junction.router.names.template.d=X-{name}\njunction.router.names.template.nosuch=Y-{name}\n").getBytes(StandardCharsets.UTF_8));
        GatewayConfig g = new ConfigLoader(j, dir).load().gateway("w");
        String w = g.getWarnings().toString();
        check("warnings for a DIS endpoint and a missing one " + w, w.contains("DIS names its objects by entity id") && w.contains("no endpoint 'nosuch'"));
    }

    // ------------------------------------------------------------------ a gateway

    private static void gateway(File fomRoot, File rtiDir) throws Exception {
        String rpr2 = new File(fomRoot, "ieee1516-2010/RPR_FOM_v2.0_Link16_1516-2010.xml").getPath();
        File jar = new File(rtiDir, "fakerti-1516e.jar");
        File dir = Files.createTempDirectory("junction-naming").toFile();
        File junctions = new File(dir, "etc/junctions");
        junctions.mkdirs();
        SimwProperties p = new SimwProperties();
        p.set("junction.name", "Naming");
        p.set("junction.endpoints", "int;ext");
        for (String[] e : new String[][] {{"int", "NameInt"}, {"ext", "NameExt"}}) {
            p.set("junction.endpoint." + e[0] + ".standard", "ieee1516-2010");
            p.set("junction.endpoint." + e[0] + ".hla.foms", rpr2);
            p.set("junction.endpoint." + e[0] + ".hla.federation", e[1]);
            p.set("junction.endpoint." + e[0] + ".hla.rtijar", jar.getPath());
        }
        p.set("junction.router.names.template.ext", "RED-{name}-{hexguid}");     // only copies in ext are renamed
        p.set("junction.router.names.maxlength.ext", "24");
        GatewayConfig gc = new ConfigLoader(junctions, dir).gateway(new File(junctions, "naming.junction.settings"), p);
        check("gateway config valid " + gc.getErrors(), gc.isValid());
        RtiLibrary lib = RtiLibrary.isolated(Collections.singletonList(jar));
        InformationExchangeModel m = InformationExchangeModel.fromHlaEvolved(Collections.singletonList(rpr2));
        HlaCodec codec = new HlaCodec(m);

        Gateway gw = new Gateway(gc, Collections.<String, String>emptyMap(), dir, null);
        gw.start();
        check("gateway running", gw.getState() == Gateway.State.RUNNING);

        Fed in = new Fed(lib, "NameInt", "IntSim", rpr2);
        Fed ex = new Fed(lib, "NameExt", "ExtSim", rpr2);
        int acI = in.s.objectClass(AIRCRAFT), agI = in.s.objectClass(AGGREGATE), txI = in.s.objectClass(TX);
        int[] acAttr = {in.s.attribute(acI, "EntityType")};
        int[] agAttr = {in.s.attribute(agI, "EntityIdentifiers")};
        int[] txAttr = {in.s.attribute(txI, "HostObjectIdentifier"), in.s.attribute(txI, "RadioIndex")};
        in.s.publishObjectClass(acI, acAttr);
        in.s.publishObjectClass(agI, agAttr);
        in.s.publishObjectClass(txI, txAttr);
        int acE = ex.s.objectClass(AIRCRAFT), agE = ex.s.objectClass(AGGREGATE), txE = ex.s.objectClass(TX);
        ex.s.subscribeObjectClass(acE, new int[] {ex.s.attribute(acE, "EntityType")});
        ex.s.subscribeObjectClass(agE, new int[] {ex.s.attribute(agE, "EntityIdentifiers")});
        ex.s.subscribeObjectClass(txE, new int[] {ex.s.attribute(txE, "HostObjectIdentifier"), ex.s.attribute(txE, "RadioIndex")});
        ex.s.publishObjectClass(acE, new int[] {ex.s.attribute(acE, "EntityType")});
        in.s.subscribeObjectClass(acI, acAttr);
        for (Fed f : new Fed[] {in, ex}) {
            for (String c : new String[] {FIRE, DETONATION}) {
                int ic = f.s.interactionClass(c);
                f.s.publishInteractionClass(ic);
                f.s.subscribeInteractionClass(ic);
            }
        }
        sleep(500);

        // int: an aircraft, a radio on it, an aggregate holding it
        in.reserve("Tank-12");
        long tank = in.s.registerObject(acI, "Tank-12");
        in.s.updateAttributes(tank, acAttr, new byte[][] {encode(codec, m, AIRCRAFT, "EntityType", entityType())}, null);
        in.reserve("Tank-12-radio");
        long radio = in.s.registerObject(txI, "Tank-12-radio");
        in.s.updateAttributes(radio, txAttr, new byte[][] {encode(codec, m, TX, "HostObjectIdentifier", HValue.ofText("Tank-12")),
            encode(codec, m, TX, "RadioIndex", HValue.ofInt(1))}, null);
        in.reserve("Platoon-1");
        long platoon = in.s.registerObject(agI, "Platoon-1");
        in.s.updateAttributes(platoon, agAttr, new byte[][] {encode(codec, m, AGGREGATE, "EntityIdentifiers",
                HValue.ofArray(Collections.singletonList(HValue.ofText("Tank-12"))))}, null);
        // ext: an aircraft of its own
        ex.reserve("Blue-1");
        long blue = ex.s.registerObject(acE, "Blue-1");
        ex.s.updateAttributes(blue, new int[] {ex.s.attribute(acE, "EntityType")}, new byte[][] {encode(codec, m, AIRCRAFT, "EntityType", entityType())}, null);

        String tankCopy = expected("naming", "int", "Tank-12", 24);
        String radioCopy = expected("naming", "int", "Tank-12-radio", 24);
        String platoonCopy = expected("naming", "int", "Platoon-1", 24);
        Map<String, Long> seen = new LinkedHashMap<String, Long>();
        for (int i = 0; i < 3; i++) {
            String[] d = ex.take("discovered", 4000);
            if (d != null) {
                seen.put(d[3], Long.parseLong(d[1]));
            }
        }
        check("ext discovers the copies under the template, within 24 characters " + seen.keySet(),
                seen.containsKey(tankCopy) && seen.containsKey(radioCopy) && seen.containsKey(platoonCopy)
                && seen.keySet().stream().allMatch(n -> n.length() <= 24 && n.startsWith("RED-")));
        String[] d = in.take("discovered", 4000);
        check("int discovers ext's aircraft under its own name (no template toward int): " + (d == null ? null : d[3]), d != null && "Blue-1".equals(d[3]));

        // references in attributes: simple (HostObjectIdentifier) and in an array (EntityIdentifiers)
        Long rk = seen.get(radioCopy), pk = seen.get(platoonCopy);
        byte[] host = rk == null ? null : ex.waitValue(rk, ex.s.attribute(txE, "HostObjectIdentifier"), 3000);
        check("the radio's HostObjectIdentifier names the aircraft's copy", host != null
                && tankCopy.equals(codec.decode(m.getClassAttribute(TX, "HostObjectIdentifier").getDataType(), host).asTrimmedText()));
        byte[] ids = pk == null ? null : ex.waitValue(pk, ex.s.attribute(agE, "EntityIdentifiers"), 3000);
        HValue idv = ids == null ? null : codec.decode(m.getClassAttribute(AGGREGATE, "EntityIdentifiers").getDataType(), ids);
        check("the aggregate's EntityIdentifiers array names the copy", idv != null && idv.getValues().size() == 1
                && tankCopy.equals(idv.get(0).asTrimmedText()));

        // ext -> int: a detonation at the copy, issued by ext's own aircraft; int sees the originals
        int detE = ex.s.interactionClass(DETONATION), detI = in.s.interactionClass(DETONATION);
        HValue event = codec.defaultValue(m.getClassParameter(DETONATION, "EventIdentifier").getDataType())
                .withField("IssuingObjectIdentifier", HValue.ofText("Blue-1"));
        ex.s.sendInteraction(detE, new int[] {ex.s.parameter(detE, "TargetObjectIdentifier"), ex.s.parameter(detE, "FiringObjectIdentifier"),
            ex.s.parameter(detE, "EventIdentifier")}, new byte[][] {
            codec.encode(m.getClassParameter(DETONATION, "TargetObjectIdentifier").getDataType(), HValue.ofText(tankCopy)),
            codec.encode(m.getClassParameter(DETONATION, "FiringObjectIdentifier").getDataType(), HValue.ofText("Blue-1")),
            codec.encode(m.getClassParameter(DETONATION, "EventIdentifier").getDataType(), event)}, null);
        String[] rx = in.take("received", 4000);
        check("int receives the detonation", rx != null);
        if (rx != null) {
            Map<Integer, byte[]> pv = in.lastInteraction;
            String target = text(codec, m, DETONATION, "TargetObjectIdentifier", pv.get(in.s.parameter(detI, "TargetObjectIdentifier")));
            String firing = text(codec, m, DETONATION, "FiringObjectIdentifier", pv.get(in.s.parameter(detI, "FiringObjectIdentifier")));
            byte[] ev = pv.get(in.s.parameter(detI, "EventIdentifier"));
            String issuer = ev == null ? null : codec.decode(m.getClassParameter(DETONATION, "EventIdentifier").getDataType(), ev)
                    .field("IssuingObjectIdentifier").asTrimmedText();
            check("target mapped back to Tank-12 (got " + target + ")", "Tank-12".equals(target));
            check("firing entity Blue-1 as it is (got " + firing + ")", "Blue-1".equals(firing));
            check("the record's IssuingObjectIdentifier as it is (got " + issuer + ")", "Blue-1".equals(issuer));
        }

        // int -> ext: fire from Tank-12 at Blue-1; ext sees the copy's name as the shooter
        int fireI = in.s.interactionClass(FIRE), fireE = ex.s.interactionClass(FIRE);
        HValue ev2 = codec.defaultValue(m.getClassParameter(FIRE, "EventIdentifier").getDataType())
                .withField("IssuingObjectIdentifier", HValue.ofText("Tank-12"));
        in.s.sendInteraction(fireI, new int[] {in.s.parameter(fireI, "FiringObjectIdentifier"), in.s.parameter(fireI, "TargetObjectIdentifier"),
            in.s.parameter(fireI, "EventIdentifier")}, new byte[][] {
            codec.encode(m.getClassParameter(FIRE, "FiringObjectIdentifier").getDataType(), HValue.ofText("Tank-12")),
            codec.encode(m.getClassParameter(FIRE, "TargetObjectIdentifier").getDataType(), HValue.ofText("Blue-1")),
            codec.encode(m.getClassParameter(FIRE, "EventIdentifier").getDataType(), ev2)}, null);
        rx = ex.take("received", 4000);
        check("ext receives the fire", rx != null);
        if (rx != null) {
            Map<Integer, byte[]> pv = ex.lastInteraction;
            String firing = text(codec, m, FIRE, "FiringObjectIdentifier", pv.get(ex.s.parameter(fireE, "FiringObjectIdentifier")));
            String target = text(codec, m, FIRE, "TargetObjectIdentifier", pv.get(ex.s.parameter(fireE, "TargetObjectIdentifier")));
            byte[] ev = pv.get(ex.s.parameter(fireE, "EventIdentifier"));
            String issuer = ev == null ? null : codec.decode(m.getClassParameter(FIRE, "EventIdentifier").getDataType(), ev)
                    .field("IssuingObjectIdentifier").asTrimmedText();
            check("shooter is the copy's name (got " + firing + ")", tankCopy.equals(firing));
            check("target Blue-1 as it is (got " + target + ")", "Blue-1".equals(target));
            check("the record's IssuingObjectIdentifier is the copy's name (got " + issuer + ")", tankCopy.equals(issuer));
        }

        // the names are the same after a restart of the gateway
        gw.stop();
        int removed = 0;
        for (int i = 0; i < 3 && ex.take("removed", 3000) != null; i++) {
            removed++;
        }
        check("copies removed when the gateway stops (" + removed + ")", removed == 3);
        Gateway again = new Gateway(gc, Collections.<String, String>emptyMap(), dir, null);
        again.start();
        in.s.updateAttributes(tank, acAttr, new byte[][] {encode(codec, m, AIRCRAFT, "EntityType", entityType())}, null);
        List<String> second = new ArrayList<String>();
        for (int i = 0; i < 3; i++) {
            String[] x = ex.take("discovered", 4000);
            if (x != null) {
                second.add(x[3]);
            }
        }
        check("after a restart the copies get the same names, also for objects that existed before the gateway started (discovered at their own class) " + second, second.contains(tankCopy) && second.contains(radioCopy));
        again.stop();
        in.s.close();
        ex.s.close();
    }


    // ------------------------------------------------------------------ 0.14.0: which objects get the template

    private static void criteriaSettings() throws Exception {
        NameCriteria all = NameCriteria.ALL;
        check("no rules: everything matches", all.isAll() && all.matches("HLAobjectRoot.X", "x"));
        NameCriteria c = new NameCriteria(Arrays.asList("BaseEntity.PhysicalEntity.Platform.*"), Arrays.asList("BaseEntity.PhysicalEntity.Platform.Spacecraft"),
                Collections.<String>emptyList(), Arrays.asList("*-keep", "re:(?i)^test.*"));
        check("a class in the subtree", c.matches(AIRCRAFT, "Tank-12"));
        check("the root may be left out", c.matches("BaseEntity.PhysicalEntity.Platform.Aircraft", "Tank-12"));
        check("the more specific except wins", !c.matches("HLAobjectRoot.BaseEntity.PhysicalEntity.Platform.Spacecraft", "S1"));
        check("a class outside the select list", !c.matches(TX, "Tank-12-radio"));
        check("an excepted name (glob)", !c.matches(AIRCRAFT, "Hawk-keep"));
        check("an excepted name (regex, any case)", !c.matches(AIRCRAFT, "TEST-1") && c.matches(AIRCRAFT, "Atest"));
        NameCriteria n = new NameCriteria(Collections.<String>emptyList(), Collections.<String>emptyList(), Arrays.asList("Blue-?", "re:^[A-Z]{3}\\d+$"),
                Collections.<String>emptyList());
        check("select names: glob ? is one character, globs match the whole name",
                n.matches(AIRCRAFT, "Blue-1") && !n.matches(AIRCRAFT, "Blue-12") && !n.matches(AIRCRAFT, "xBlue-1"));
        check("select names: regex", n.matches(TX, "ABC123") && !n.matches(TX, "AB123"));
        check("globs are case-sensitive and dots literal", !n.matches(AIRCRAFT, "blue-1")
                && new NameCriteria(Collections.<String>emptyList(), Collections.<String>emptyList(), Arrays.asList("a.b"), Collections.<String>emptyList()).matches(AIRCRAFT, "a.b")
                && !new NameCriteria(Collections.<String>emptyList(), Collections.<String>emptyList(), Arrays.asList("a.b"), Collections.<String>emptyList()).matches(AIRCRAFT, "axb"));
        NameCriteria onlyExcept = new NameCriteria(Collections.<String>emptyList(), Arrays.asList("EmbeddedSystem.*"),
                Collections.<String>emptyList(), Collections.<String>emptyList());
        check("only an except list: everything else matches", onlyExcept.matches(AIRCRAFT, "a") && !onlyExcept.matches(TX, "r"));

        SimwProperties p = new SimwProperties();
        p.set("router.names.template", "RED-{name}");
        p.set("router.names.select.classes", "BaseEntity.PhysicalEntity.*");
        p.set("router.names.except.names", "*-keep");
        p.set("router.names.select.classes.ext", "EmbeddedSystem.*");
        p.set("router.names.except.names.int", "");
        RouterConfig r = new RouterConfig("t", p, "inline", null);
        check("criteria valid " + r.getErrors() + " " + r.getWarnings(), r.getErrors().isEmpty() && r.getWarnings().isEmpty());
        check("general criteria for others", r.getNameCriteria("third").matches(AIRCRAFT, "A") && !r.getNameCriteria("third").matches(AIRCRAFT, "A-keep")
                && !r.getNameCriteria("third").matches(TX, "R"));
        check("a scoped class list replaces the general one, the general name list stays", r.getNameCriteria("ext").matches(TX, "R")
                && !r.getNameCriteria("ext").matches(AIRCRAFT, "A") && !r.getNameCriteria("ext").matches(TX, "R-keep"));
        check("an empty scoped list cancels the general one", r.getNameCriteria("int").matches(AIRCRAFT, "A-keep"));
        check("criteria scopes count as name scopes " + r.getNameScopes(), r.getNameScopes().containsAll(Arrays.asList("ext", "int")));
        String json = com.simweaver.common.json.Json.toJson(r);
        check("shown in the router JSON " + json, json.contains("\"namecriteria\"") && json.contains("\"selectclasses\":[\"EmbeddedSystem.*\"]"));

        SimwProperties bad = new SimwProperties();
        bad.set("router.names.select.names", "re:([unclosed");
        bad.set("router.names.except.classes.x", "Platform.*");
        RouterConfig rb = new RouterConfig("t", bad, "inline", null);
        check("a bad regex is an error " + rb.getErrors(), rb.getErrors().toString().contains("router.names.select.names") && rb.getErrors().toString().contains("regular expression"));
        SimwProperties nt = new SimwProperties();
        nt.set("router.names.except.classes.x", "Platform.*");
        RouterConfig rn = new RouterConfig("t", nt, "inline", null);
        check("criteria without a template warn " + rn.getWarnings(), rn.getWarnings().toString().contains("have no effect: no router.names.template for x"));
    }

    /**
     * int -> ext with RED-{name} for platforms, but not for names ending in -keep: the aircraft is
     * renamed, the excepted aircraft and the radio keep their names, the radio's host reference names
     * the renamed copy, and an unmatched name taken in ext gets the usual @origin suffix.
     */
    private static void criteria(File fomRoot, File rtiDir) throws Exception {
        String rpr2 = new File(fomRoot, "ieee1516-2010/RPR_FOM_v2.0_Link16_1516-2010.xml").getPath();
        File jar = new File(rtiDir, "fakerti-1516e.jar");
        File dir = Files.createTempDirectory("junction-naming-crit").toFile();
        File junctions = new File(dir, "etc/junctions");
        junctions.mkdirs();
        SimwProperties p = new SimwProperties();
        p.set("junction.name", "Criteria");
        p.set("junction.endpoints", "int;ext");
        for (String[] e : new String[][] {{"int", "CritInt"}, {"ext", "CritExt"}}) {
            p.set("junction.endpoint." + e[0] + ".standard", "ieee1516-2010");
            p.set("junction.endpoint." + e[0] + ".hla.foms", rpr2);
            p.set("junction.endpoint." + e[0] + ".hla.federation", e[1]);
            p.set("junction.endpoint." + e[0] + ".hla.rtijar", jar.getPath());
        }
        p.set("junction.router.names.template.ext", "RED-{name}");
        p.set("junction.router.names.select.classes.ext", "BaseEntity.PhysicalEntity.Platform.*");
        p.set("junction.router.names.except.names", "*-keep");
        GatewayConfig gc = new ConfigLoader(junctions, dir).gateway(new File(junctions, "crit.junction.settings"), p);
        check("criteria gateway valid " + gc.getErrors() + " " + gc.getWarnings(), gc.isValid());
        RtiLibrary lib = RtiLibrary.isolated(Collections.singletonList(jar));
        InformationExchangeModel m = InformationExchangeModel.fromHlaEvolved(Collections.singletonList(rpr2));
        HlaCodec codec = new HlaCodec(m);
        Fed in = new Fed(lib, "CritInt", "IntSim", rpr2);
        Fed ex = new Fed(lib, "CritExt", "ExtSim", rpr2);
        int acI = in.s.objectClass(AIRCRAFT), txI = in.s.objectClass(TX);
        int[] acAttr = {in.s.attribute(acI, "EntityType")};
        int[] txAttr = {in.s.attribute(txI, "HostObjectIdentifier"), in.s.attribute(txI, "RadioIndex")};
        in.s.publishObjectClass(acI, acAttr);
        in.s.publishObjectClass(txI, txAttr);
        int acE = ex.s.objectClass(AIRCRAFT), txE = ex.s.objectClass(TX);
        ex.s.subscribeObjectClass(acE, new int[] {ex.s.attribute(acE, "EntityType")});
        ex.s.subscribeObjectClass(txE, new int[] {ex.s.attribute(txE, "HostObjectIdentifier"), ex.s.attribute(txE, "RadioIndex")});
        ex.s.publishObjectClass(txE, new int[] {ex.s.attribute(txE, "HostObjectIdentifier"), ex.s.attribute(txE, "RadioIndex")});
        // both federations already have an object named Spare-1 when the gateway starts
        ex.reserve("Spare-1");
        ex.s.registerObject(txE, "Spare-1");
        in.reserve("Spare-1");
        long spare = in.s.registerObject(txI, "Spare-1");
        Gateway gw = new Gateway(gc, Collections.<String, String>emptyMap(), dir, null);
        gw.start();
        check("criteria gateway running", gw.getState() == Gateway.State.RUNNING);
        sleep(300);

        in.reserve("Tank-12");
        long tank = in.s.registerObject(acI, "Tank-12");
        in.s.updateAttributes(tank, acAttr, new byte[][] {encode(codec, m, AIRCRAFT, "EntityType", entityType())}, null);
        in.reserve("Hawk-keep");
        long hawk = in.s.registerObject(acI, "Hawk-keep");
        in.s.updateAttributes(hawk, acAttr, new byte[][] {encode(codec, m, AIRCRAFT, "EntityType", entityType())}, null);
        in.reserve("Tank-12-radio");
        long radio = in.s.registerObject(txI, "Tank-12-radio");
        in.s.updateAttributes(radio, txAttr, new byte[][] {encode(codec, m, TX, "HostObjectIdentifier", HValue.ofText("Tank-12")),
            encode(codec, m, TX, "RadioIndex", HValue.ofInt(1))}, null);
        in.s.updateAttributes(spare, txAttr, new byte[][] {encode(codec, m, TX, "HostObjectIdentifier", HValue.ofText("Hawk-keep")),
            encode(codec, m, TX, "RadioIndex", HValue.ofInt(2))}, null);
        Map<String, Long> seen = new LinkedHashMap<String, Long>();
        for (int i = 0; i < 4; i++) {
            String[] d = ex.take("discovered", 4000);
            if (d != null) {
                seen.put(d[3], Long.parseLong(d[1]));
            }
        }
        check("ext: the platform renamed, the excepted platform and the radio as they are, a clash with @int " + seen.keySet(),
                seen.keySet().equals(new java.util.HashSet<String>(Arrays.asList("RED-Tank-12", "Hawk-keep", "Tank-12-radio", "Spare-1@int"))));
        Long rk = seen.get("Tank-12-radio");
        byte[] host = rk == null ? null : ex.waitValue(rk, ex.s.attribute(txE, "HostObjectIdentifier"), 3000);
        check("the unrenamed radio's host reference names the renamed copy", host != null
                && "RED-Tank-12".equals(codec.decode(m.getClassAttribute(TX, "HostObjectIdentifier").getDataType(), host).asTrimmedText()));
        Long sk = seen.get("Spare-1@int");
        byte[] host2 = sk == null ? null : ex.waitValue(sk, ex.s.attribute(txE, "HostObjectIdentifier"), 3000);
        check("a reference to an object that kept its name stays as it is", host2 != null
                && "Hawk-keep".equals(codec.decode(m.getClassAttribute(TX, "HostObjectIdentifier").getDataType(), host2).asTrimmedText()));
        gw.stop();
        in.s.close();
        ex.s.close();
    }

    /**
     * RPR v3, int -> ext renamed (V3-{name}): an aircraft's VariableParametersArray (an array of
     * VariableParameterVariantStruct) holds references in its EntityAssociation and Separation
     * alternatives; they name the copies in ext. A Detonation's VariableParametersArray from ext
     * naming the copies arrives in int with the originals.
     */
    private static void variants(File fomRoot, File rtiDir) throws Exception {
        List<String> v3 = new ArrayList<String>();
        File[] fs = new File(fomRoot, "ieee1516-2010/rpr3").listFiles((d, n) -> n.endsWith(".xml"));
        Arrays.sort(fs);
        for (File f : fs) {
            v3.add(f.getPath());
        }
        String aircraft = "HLAobjectRoot.BaseEntity.PhysicalEntity.Platform.Aircraft";
        String det = "HLAinteractionRoot.Detonation";
        File jar = new File(rtiDir, "fakerti-1516e.jar");
        File dir = Files.createTempDirectory("junction-naming-v3").toFile();
        File junctions = new File(dir, "etc/junctions");
        junctions.mkdirs();
        SimwProperties p = new SimwProperties();
        p.set("junction.name", "Variants");
        p.set("junction.endpoints", "int;ext");
        for (String[] e : new String[][] {{"int", "V3Int"}, {"ext", "V3Ext"}}) {
            p.set("junction.endpoint." + e[0] + ".standard", "ieee1516-2010");
            p.set("junction.endpoint." + e[0] + ".hla.foms", String.join(";", v3));
            p.set("junction.endpoint." + e[0] + ".hla.federation", e[1]);
            p.set("junction.endpoint." + e[0] + ".hla.rtijar", jar.getPath());
        }
        p.set("junction.router.names.template.ext", "V3-{name}");
        GatewayConfig gc = new ConfigLoader(junctions, dir).gateway(new File(junctions, "v3.junction.settings"), p);
        check("RPR v3 gateway valid " + gc.getErrors(), gc.isValid());
        RtiLibrary lib = RtiLibrary.isolated(Collections.singletonList(jar));
        InformationExchangeModel m = InformationExchangeModel.fromHlaEvolved(v3);
        HlaCodec codec = new HlaCodec(m);
        com.simweaver.common.hla.codec.TypeAnalysis ta = new com.simweaver.common.hla.codec.TypeAnalysis(codec);
        Gateway gw = new Gateway(gc, Collections.<String, String>emptyMap(), dir, null);
        gw.start();
        check("RPR v3 gateway running", gw.getState() == Gateway.State.RUNNING);
        Fed in = new Fed(lib, "V3Int", "IntSim", v3);
        Fed ex = new Fed(lib, "V3Ext", "ExtSim", v3);
        int acI = in.s.objectClass(aircraft);
        int[] acAttr = {in.s.attribute(acI, "EntityType"), in.s.attribute(acI, "VariableParametersArray")};
        in.s.publishObjectClass(acI, acAttr);
        int acE = ex.s.objectClass(aircraft);
        int vpE = ex.s.attribute(acE, "VariableParametersArray");
        ex.s.subscribeObjectClass(acE, new int[] {ex.s.attribute(acE, "EntityType"), vpE});
        for (Fed f : new Fed[] {in, ex}) {
            int ic = f.s.interactionClass(det);
            f.s.publishInteractionClass(ic);
            f.s.subscribeInteractionClass(ic);
        }
        sleep(500);
        in.reserve("Lead");
        long lead = in.s.registerObject(acI, "Lead");
        in.s.updateAttributes(lead, new int[] {acAttr[0]}, new byte[][] {encode(codec, m, aircraft, "EntityType", entityType())}, null);
        in.reserve("Wing");
        long wing = in.s.registerObject(acI, "Wing");
        com.simweaver.common.iem.DataType vpType = m.getClassAttribute(aircraft, "VariableParametersArray").getDataType();
        HValue vp = new com.simweaver.common.hla.codec.RefSamples(codec, ta, () -> "Lead").sample(vpType);
        check("the test value holds references in variant alternatives " + ta.references(vp), ta.references(vp).size() >= 2);
        in.s.updateAttributes(wing, acAttr, new byte[][] {encode(codec, m, aircraft, "EntityType", entityType()), codec.encode(vpType, vp)}, null);
        Map<String, Long> seen = new LinkedHashMap<String, Long>();
        for (int i = 0; i < 2; i++) {
            String[] d = ex.take("discovered", 4000);
            if (d != null) {
                seen.put(d[3], Long.parseLong(d[1]));
            }
        }
        check("ext discovers V3-Lead and V3-Wing " + seen.keySet(), seen.containsKey("V3-Lead") && seen.containsKey("V3-Wing"));
        Long wk = seen.get("V3-Wing");
        byte[] got = wk == null ? null : ex.waitValue(wk, vpE, 3000);
        List<String> refs = got == null ? Collections.<String>emptyList() : ta.references(codec.decode(vpType, got));
        check("the references inside the variant records name the copy " + refs, !refs.isEmpty() && refs.stream().allMatch("V3-Lead"::equals)
                && refs.size() == ta.references(vp).size());

        // ext -> int: a Detonation whose VariableParametersArray names the copies; int sees the originals
        int detE = ex.s.interactionClass(det), detI = in.s.interactionClass(det);
        com.simweaver.common.iem.DataType dvt = m.getClassParameter(det, "VariableParametersArray").getDataType();
        HValue dv = new com.simweaver.common.hla.codec.RefSamples(codec, ta, () -> "V3-Wing").sample(dvt);
        ex.s.sendInteraction(detE, new int[] {ex.s.parameter(detE, "VariableParametersArray")}, new byte[][] {codec.encode(dvt, dv)}, null);
        String[] rx = in.take("received", 4000);
        byte[] pv = rx == null ? null : in.lastInteraction.get(in.s.parameter(detI, "VariableParametersArray"));
        List<String> back = pv == null ? Collections.<String>emptyList() : ta.references(codec.decode(dvt, pv));
        check("int receives the detonation with the original names in its variant records " + back,
                !back.isEmpty() && back.stream().allMatch("Wing"::equals));
        gw.stop();
        in.s.close();
        ex.s.close();
    }

    /** The name the template gives: RED-{name}-{hexguid}, the GUID derived from gateway, origin and name. */
    private static String expected(String gateway, String origin, String name, int max) {
        UUID g = UUID.nameUUIDFromBytes((gateway + "|" + origin + "|" + name).getBytes(StandardCharsets.UTF_8));
        return CopyNames.fit("RED-" + name + "-" + g.toString().replace("-", ""), "", max);
    }

    private static String text(HlaCodec codec, InformationExchangeModel m, String cls, String param, byte[] v) {
        return v == null ? null : codec.decode(m.getClassParameter(cls, param).getDataType(), v).asTrimmedText();
    }

    private static byte[] encode(HlaCodec c, InformationExchangeModel m, String cls, String attr, HValue v) {
        return c.encode(m.getClassAttribute(cls, attr).getDataType(), v);
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
        final Map<Long, Map<Integer, byte[]>> byInstance = new ConcurrentHashMap<Long, Map<Integer, byte[]>>();
        volatile Map<Integer, byte[]> lastInteraction;

        Fed(RtiLibrary lib, String federation, String name, String fom) throws Exception {
            this(lib, federation, name, Collections.singletonList(fom));
        }

        Fed(RtiLibrary lib, String federation, String name, List<String> foms) throws Exception {
            s = lib.createSession(RtiSession.Version.HLA_1516E);
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

        byte[] waitValue(long instance, int attr, long millis) {
            long end = System.currentTimeMillis() + millis;
            while (System.currentTimeMillis() < end) {
                Map<Integer, byte[]> mine = byInstance.get(instance);
                byte[] v = mine == null ? null : mine.get(attr);
                if (v != null) {
                    return v;
                }
                sleep(20);
            }
            return null;
        }

        @Override
        public void discovered(long instance, int objectClass, String name, String producer) {
            q.add(new String[] {"discovered", String.valueOf(instance), String.valueOf(objectClass), name});
        }

        @Override
        public void reflected(long instance, int[] attributes, byte[][] vals, byte[] tag, String producer) {
            Map<Integer, byte[]> mine = byInstance.computeIfAbsent(instance, k -> new ConcurrentHashMap<Integer, byte[]>());
            for (int i = 0; i < attributes.length; i++) {
                mine.put(attributes[i], vals[i]);
            }
        }

        @Override
        public void removed(long instance, byte[] tag, String producer) {
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
