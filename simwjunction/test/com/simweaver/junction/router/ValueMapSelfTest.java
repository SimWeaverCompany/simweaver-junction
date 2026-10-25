package com.simweaver.junction.router;

import com.simweaver.common.config.SimwProperties;
import com.simweaver.common.dis.DisEntityId;
import com.simweaver.common.dis.DisEntityState;
import com.simweaver.common.dis.DisEntityType;
import com.simweaver.common.dis.DisPduHeader;
import com.simweaver.common.dis.DisReader;
import com.simweaver.common.dis.DisWriter;
import com.simweaver.common.hla.codec.HValue;
import com.simweaver.common.hla.codec.HlaCodec;
import com.simweaver.common.hla.session.ConnectSettings;
import com.simweaver.common.hla.session.RtiLibrary;
import com.simweaver.common.hla.session.RtiSession;
import com.simweaver.common.hla.session.RtiSessionListener;
import com.simweaver.common.iem.ArrayDataType;
import com.simweaver.common.iem.DataType;
import com.simweaver.common.iem.FixedRecordDataType;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.common.iem.RecordField;
import com.simweaver.junction.Gateway;
import com.simweaver.junction.config.ConfigLoader;
import com.simweaver.junction.config.GatewayConfig;
import com.simweaver.junction.config.RouterConfig;
import com.simweaver.junction.config.ValueRule;
import com.simweaver.junction.router.convert.RprConversions;

import java.io.File;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * The router's value rules ({@code router.valuemap.*}): reading the settings, the rules in the
 * RPR v2 and v3 models, routes (as is, RPR v2 -> v3, interaction parameters), and a DIS - HLA
 * gateway that replaces entity types both ways.
 *
 * <pre>java -cp out/classes:out/test com.simweaver.junction.router.ValueMapSelfTest &lt;fom root&gt; &lt;fake rti folder&gt; [mappings]</pre>
 */
public final class ValueMapSelfTest {

    private static final String AIRCRAFT = "HLAobjectRoot.BaseEntity.PhysicalEntity.Platform.Aircraft";
    private static final String GROUND = "HLAobjectRoot.BaseEntity.PhysicalEntity.Platform.GroundVehicle";
    private static final String FIRE2 = "HLAinteractionRoot.WeaponFire";
    private static int checks;
    private static int failures;

    private ValueMapSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        File fomRoot = new File(args.length > 0 ? args[0] : "simwrift/rtdir/etc/fom").getAbsoluteFile();
        File rtiDir = new File(args.length > 1 ? args[1] : "fakerti/build").getAbsoluteFile();
        File mappings = new File(args.length > 2 ? args[2] : "simwrift/rtdir/etc/junctions/mappings").getAbsoluteFile();
        String rpr2 = new File(fomRoot, "ieee1516-2010/RPR_FOM_v2.0_Link16_1516-2010.xml").getPath();
        List<String> rpr3 = new ArrayList<String>();
        for (File f : new File(fomRoot, "ieee1516-2010/rpr3").listFiles()) {
            if (f.getName().endsWith(".xml")) rpr3.add(f.getPath());
        }
        Collections.sort(rpr3);

        settings();
        InformationExchangeModel m2 = InformationExchangeModel.fromHlaEvolved(Collections.singletonList(rpr2));
        InformationExchangeModel m3 = InformationExchangeModel.fromHlaEvolved(rpr3);
        ModelTools t2 = new ModelTools(m2, Collections.singletonList("RTIobjectId"));
        ModelTools t3 = new ModelTools(m3, Collections.singletonList("RTIobjectId"));
        models(t2, t3);
        routes(t2, t3, mappings);
        gateway(rpr2, new File(rtiDir, "fakerti-1516e.jar"));

        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ settings

    private static void settings() {
        SimwProperties p = new SimwProperties();
        p.set("router.valuemap.EntityType.1", "1.2.225.1.3.0.0 -> 1.2.222.1.3.0.0");
        p.set("router.valuemap.EntityType.2", "1.1.225.1.1.3.0 -> 1.1.225.1.1.4.0; 2.9.225.2.1.0.0 > 2.9.222.2.1.0.0");
        p.set("router.valuemap.EntityTypeStruct.same", "1.2.225.1.3.0.0 -> 1.2.222.1.3.0.0");     // the same rule again
        p.set("router.valuemap.EntityType.noop", "3.0.0.0.0.0.0 -> 3.0.0.0.0.0.0");
        RouterConfig rc = new RouterConfig("r", p, "test", null);
        List<ValueRule> rules = rc.getValueRules();
        check("three rules, no errors " + rc.getErrors(), rules.size() == 3 && rc.getErrors().isEmpty());
        check("EntityType means EntityTypeStruct", rules.get(0).getDataType().equals("EntityTypeStruct"));
        check("A and B read in dot notation", Arrays.equals(rules.get(0).getFrom(), new long[] {1, 2, 225, 1, 3, 0, 0})
                && Arrays.equals(rules.get(0).getTo(), new long[] {1, 2, 222, 1, 3, 0, 0}));
        check("';' separates pairs, '>' works as '->'", rules.get(2).toString().equals("EntityTypeStruct: 2.9.225.2.1.0.0 -> 2.9.222.2.1.0.0"));

        String[][] bad = {
            {"router.valuemap.EntityType.1", "1.2.225 -> 1.2", "same number of fields"},
            {"router.valuemap.EntityType.1", "one -> two", "numbers in dot notation"},
            {"router.valuemap.EntityType.1", "1.2.225.1.3.0.0", "is not <value> -> <new value>"},
            {"router.valuemap.EntityType", "", "no rule"},
        };
        for (String[] b : bad) {
            SimwProperties q = new SimwProperties();
            q.set(b[0], b[1]);
            RouterConfig r = new RouterConfig("r", q, "test", null);
            check("error for '" + b[1] + "': " + r.getErrors(), r.getErrors().size() == 1 && r.getErrors().get(0).contains(b[2]));
        }
        SimwProperties q = new SimwProperties();
        q.set("router.valuemap.EntityType.1", "1.2.225.1.3.0.0 -> 1.2.222.1.3.0.0");
        q.set("router.valuemap.EntityType.2", "1.2.225.1.3.0.0 -> 1.2.9.1.3.0.0");
        q.set("router.valuemap.EntityType.3", "1.2.3 -> 1.2.4");
        RouterConfig r = new RouterConfig("r", q, "test", null);
        check("A with two different B is an error " + r.getErrors(), r.getErrors().size() == 2 && r.getErrors().get(0).contains("already becomes 1.2.222.1.3.0.0"));
        check("and so are values of one datatype with different field counts", r.getErrors().get(1).contains("have 7 fields"));
    }

    // ------------------------------------------------------------------ the rules in a model

    private static void models(ModelTools t2, ModelTools t3) {
        List<ValueRule> rules = rules("1.2.225.1.3.0.0 -> 1.2.222.1.3.0.0; 1.2.222.1.3.0.0 -> 1.2.9.1.3.0.0");
        ValueMap v2 = ValueMap.compile(rules, t2, "v2");
        ValueMap v3 = ValueMap.compile(rules, t3, "v3");
        InformationExchangeModel m2 = t2.model();
        DataType et = m2.getDataType("EntityTypeStruct");
        check("v2 and v3 both have the rules", !v2.isEmpty() && !v3.isEmpty() && v3.touches(t3.model().getDataType("EntityTypeStruct")));
        check("touches EntityTypeStruct, not Spatial or RTIobjectId", v2.touches(et) && !v2.touches(m2.getDataType("SpatialVariantStruct"))
                && !v2.touches(m2.getDataType("RTIobjectId")));
        DataType nested = null;
        for (DataType d : m2.getDataTypes()) {
            if (d instanceof ArrayDataType && ((ArrayDataType) d).getElementType() instanceof FixedRecordDataType) {
                for (RecordField f : ((FixedRecordDataType) ((ArrayDataType) d).getElementType()).getFields()) {
                    if (f.getDataType() == et) nested = d;
                }
            }
        }
        check("touches an array of records holding an EntityTypeStruct (" + (nested == null ? null : nested.getName()) + ")",
                nested != null && v2.touches(nested));

        HlaCodec c = t2.codec();
        HValue a = c.decode(et, c.encode(et, entityType(1, 2, 225, 1, 3, 0, 0)));
        HValue b = v2.apply(a, null);
        check("A becomes B (once: B -> C is not applied to it)", Arrays.equals(c.encode(et, b), c.encode(et, entityType(1, 2, 222, 1, 3, 0, 0))));
        HValue other = c.decode(et, c.encode(et, entityType(1, 2, 225, 1, 4, 0, 0)));
        check("another value is left as it is (the same instance)", v2.apply(other, null) == other);
        check("B is not turned back into A: B -> C applies", Arrays.equals(c.encode(et, v2.apply(b, null)), c.encode(et, entityType(1, 2, 9, 1, 3, 0, 0))));
        if (nested != null) {
            ArrayDataType arr = (ArrayDataType) nested;
            FixedRecordDataType rec = (FixedRecordDataType) arr.getElementType();
            HValue one = c.defaultValue(rec);
            for (RecordField f : rec.getFields()) {
                if (f.getDataType() == et) one = one.withField(f.getName(), entityType(1, 2, 225, 1, 3, 0, 0));
            }
            HValue list = c.decode(arr, c.encode(arr, HValue.ofArray(Arrays.asList(one, one))));
            HValue out = v2.apply(list, null);
            boolean ok = out != list && out.size() == 2;
            for (int i = 0; ok && i < 2; i++) {
                for (RecordField f : rec.getFields()) {
                    if (f.getDataType() == et) ok = out.get(i).field(f.getName()).field("CountryCode").asLong() == 222;
                }
            }
            check("inside the array: both elements replaced", ok);
        }

        check("a rule with 6 fields does not fit EntityTypeStruct", throwsIae(() -> ValueMap.compile(rules("1.2.225.1.3.0 -> 1.2.222.1.3.0"), t2, "v2"), "has 7 fields"));
        check("a country code above 16 bits does not fit", throwsIae(() -> ValueMap.compile(rules("1.2.70000.1.3.0.0 -> 1.2.222.1.3.0.0"), t2, "v2"), "does not fit"));
        SimwProperties p = new SimwProperties();
        p.set("router.valuemap.HLAfloat32BE.1", "1 -> 2");
        check("only integers, enumerations and records of them", throwsIae(() -> ValueMap.compile(new RouterConfig("r", p, "t", null).getValueRules(), t2, "v2"), "only integers"));
        SimwProperties q = new SimwProperties();
        q.set("router.valuemap.NoSuchType.1", "1 -> 2");
        check("a datatype the model does not have: no rules there", ValueMap.compile(new RouterConfig("r", q, "t", null).getValueRules(), t2, "v2").isEmpty());
        SimwProperties e = new SimwProperties();
        e.set("router.valuemap.ForceIdentifierEnum8.1", "1 -> 2");
        ValueMap f = ValueMap.compile(new RouterConfig("r", e, "t", null).getValueRules(), t2, "v2");
        DataType fe = m2.getDataType("ForceIdentifierEnum8");
        check("an enumeration: Friendly -> Opposing", c.decode(fe, c.encode(fe, f.apply(c.decode(fe, c.encode(fe, HValue.ofInt(1))), null))).asLong() == 2);
    }

    // ------------------------------------------------------------------ routes

    private static void routes(ModelTools t2, ModelTools t3, File mappings) {
        List<ValueRule> rules = rules("1.2.225.1.3.0.0 -> 1.2.222.1.3.0.0; 2.9.225.2.1.0.0 -> 2.9.222.2.1.0.0");
        InformationExchangeModel m2 = t2.model(), m3 = t3.model();
        HlaCodec c2 = t2.codec(), c3 = t3.codec();
        DataType et2 = m2.getDataType("EntityTypeStruct");
        RouterStats stats = new RouterStats();

        // RPR v2 -> RPR v2 (as is)
        RouteCompiler rc = new RouteCompiler(null, true, ConversionRules.Provider.NONE);
        RouteTable t = rc.compile(new RouteCompiler.Side("a", t2, null, null, ValueMap.compile(rules, t2, "a")),
                new RouteCompiler.Side("b", t2, null, null, ValueMap.compile(rules, t2, "b")));
        ClassRoute ac = t.objectRoute(m2.getObjectClass(AIRCRAFT).getId());
        MemberRoute type = ac.bySource(m2.getClassAttribute(AIRCRAFT, "EntityType").getId());
        MemberRoute spatial = ac.bySource(m2.getClassAttribute(AIRCRAFT, "Spatial").getId());
        check("v2 -> v2: EntityType passes as is, with value rules", type.getOp() == MemberRoute.Op.PASS && type.hasValueRules());
        check("Spatial has none", !spatial.hasValueRules());
        byte[] a = c2.encode(et2, entityType(1, 2, 225, 1, 3, 0, 0));
        byte[] other = c2.encode(et2, entityType(1, 2, 225, 1, 3, 1, 0));
        check("A -> B", Arrays.equals(type.convert(a, x -> x, stats), c2.encode(et2, entityType(1, 2, 222, 1, 3, 0, 0))));
        check("another type: the same octets", type.convert(other, x -> x, stats) == other);
        check("AlternateEntityType too", Arrays.equals(ac.bySource(m2.getClassAttribute(AIRCRAFT, "AlternateEntityType").getId()).convert(a, x -> x, stats),
                c2.encode(et2, entityType(1, 2, 222, 1, 3, 0, 0))));
        check("counted: 2 replaced", stats.valuesReplaced.get() == 2);
        check("the report lists the members " + t.report().valueRuleMembers.size(), t.report().valueRuleCount > 0
                && t.report().valueRuleMembers.containsKey(AIRCRAFT + ".EntityType"));
        ClassRoute fire = t.interactionRoute(m2.getInteractionClass(FIRE2).getId());
        MemberRoute mt = fire.bySource(m2.getInteractionClass(FIRE2).getParameter("MunitionType").getId());
        check("WeaponFire.MunitionType: 2.9.225.2.1.0.0 -> 2.9.222.2.1.0.0", Arrays.equals(mt.convert(c2.encode(et2, entityType(2, 9, 225, 2, 1, 0, 0)), x -> x, stats),
                c2.encode(et2, entityType(2, 9, 222, 2, 1, 0, 0))));

        // RPR v2 -> RPR v3 (transcoded): the rule in v2 terms, B arrives in v3
        RouteCompiler rc23 = new RouteCompiler(null, true, new RprConversions(mappings));
        RouteTable u = rc23.compile(new RouteCompiler.Side("v2", t2, null, null, ValueMap.compile(rules, t2, "v2")),
                new RouteCompiler.Side("v3", t3, null, null, ValueMap.compile(rules, t3, "v3")));
        ClassRoute ac3 = u.objectRoute(m2.getObjectClass(AIRCRAFT).getId());
        MemberRoute type3 = ac3 == null ? null : ac3.bySource(m2.getClassAttribute(AIRCRAFT, "EntityType").getId());
        check("v2 -> v3: the aircraft's EntityType route has value rules", type3 != null && type3.hasValueRules());
        if (type3 != null) {
            byte[] out = type3.convert(a, x -> x, stats);
            DataType et3 = m3.getClassAttribute(ac3.destinationName, "EntityType").getDataType();
            HValue v = out == null ? null : c3.decode(et3, out);
            check("v3 gets B", v != null && v.field("CountryCode").asLong() == 222 && v.field("EntityKind").asLong() == 1 && v.field("Subcategory").asLong() == 3);
        }
        // and RPR v3 -> v2: the v3 model's own rules
        RouteTable w = rc23.compile(new RouteCompiler.Side("v3", t3, null, null, ValueMap.compile(rules, t3, "v3")),
                new RouteCompiler.Side("v2", t2, null, null, ValueMap.compile(rules, t2, "v2")));
        int ac3id = m3.getObjectClass(ac3 == null ? AIRCRAFT : ac3.destinationName).getId();
        ClassRoute back = w.objectRoute(ac3id);
        MemberRoute type2 = back == null ? null : back.bySource(m3.getClassAttribute(ac3.destinationName, "EntityType").getId());
        if (type2 != null) {
            DataType et3 = m3.getClassAttribute(ac3.destinationName, "EntityType").getDataType();
            byte[] out = type2.convert(c3.encode(et3, entityType(1, 2, 225, 1, 3, 0, 0)), x -> x, stats);
            check("v3 -> v2: A from v3 arrives as B in v2", out != null && c2.decode(et2, out).field("CountryCode").asLong() == 222);
        } else {
            check("v3 -> v2 route of the aircraft's EntityType", false);
        }
    }

    // ------------------------------------------------------------------ a DIS - HLA gateway

    private static void gateway(String rpr2, File jar) throws Exception {
        int port;
        try (DatagramSocket x = new DatagramSocket(0)) {
            port = x.getLocalPort();
        }
        DatagramSocket sim = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        sim.setSoTimeout(200);
        File dir = Files.createTempDirectory("junction-valuemap-test").toFile();
        File junctions = new File(dir, "etc/junctions");
        junctions.mkdirs();
        SimwProperties p = new SimwProperties();
        p.set("junction.name", "HLA - DIS with value rules");
        p.set("junction.endpoints", "hla;dis");
        p.set("junction.endpoint.hla.standard", "ieee1516-2010");
        p.set("junction.endpoint.hla.hla.foms", rpr2);
        p.set("junction.endpoint.hla.hla.federation", "Values");
        p.set("junction.endpoint.hla.hla.rtijar", jar.getPath());
        p.set("junction.endpoint.dis.standard", "dis7");
        p.set("junction.endpoint.dis.hla.foms", rpr2);
        p.set("junction.endpoint.dis.dis.interface", "127.0.0.1");
        p.set("junction.endpoint.dis.dis.port", String.valueOf(port));
        p.set("junction.endpoint.dis.dis.exid", "3");
        p.set("junction.endpoint.dis.dis.sendaddress", "127.0.0.1:" + sim.getLocalPort());
        p.set("junction.endpoint.dis.dis.heartbeatsecs", "1");
        p.set("junction.router.valuemap.EntityType.tank", "1.1.225.1.1.3.0 -> 1.1.225.1.1.4.0");
        p.set("junction.router.valuemap.EntityType.f16", "1.2.225.1.3.0.0 -> 1.2.222.1.3.0.0");
        p.set("junction.router.valuemap.EntityType.round", "2.9.225.2.1.0.0 -> 2.9.222.2.1.0.0");
        GatewayConfig gc = new ConfigLoader(junctions, dir).gateway(new File(junctions, "values.junction.settings"), p);
        check("gateway file with inline value rules is valid " + gc.getErrors(), gc.isValid() && gc.getRouter().getValueRules().size() == 3);
        SimwProperties broken = new SimwProperties();
        for (String k : p.getKeysSorted()) broken.set(k, p.getString(k));
        broken.set("junction.router.valuemap.EntityType.bad", "1.2 -> x");
        GatewayConfig gb = new ConfigLoader(junctions, dir).gateway(new File(junctions, "bad.junction.settings"), broken);
        check("a broken rule stops the gateway from starting " + gb.getErrors(), !gb.isValid());

        Gateway gw = new Gateway(gc, Collections.<String, String>emptyMap(), dir, null);
        gw.start();
        check("gateway running: " + gw.getState() + " " + gw.getMessage(), gw.getState() == Gateway.State.RUNNING);
        RtiLibrary lib = RtiLibrary.isolated(Collections.singletonList(jar));
        InformationExchangeModel m = InformationExchangeModel.fromHlaEvolved(Collections.singletonList(rpr2));
        HlaCodec codec = new HlaCodec(m);
        Fed fed = new Fed(lib, "Values", "ValueSim", rpr2);

        // DIS -> HLA: a tank of type A (and A as its alternate type) arrives as B
        int gv = fed.s.objectClass(GROUND);
        int[] gh = {fed.s.attribute(gv, "EntityType"), fed.s.attribute(gv, "AlternateEntityType"), fed.s.attribute(gv, "Marking")};
        fed.s.subscribeObjectClass(gv, gh);
        sleep(300);
        InetSocketAddress gwAddr = new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
        byte[] tank = esPdu(new DisEntityId(1, 2, 3), new DisEntityType(1, 1, 225, 1, 1, 3, 0), "TANK A");
        sim.send(new DatagramPacket(tank, tank.length, gwAddr));
        byte[] tankB = esPdu(new DisEntityId(1, 2, 4), new DisEntityType(1, 1, 225, 1, 1, 4, 0), "TANK B");
        sim.send(new DatagramPacket(tankB, tankB.length, gwAddr));
        Map<String, Long> found = new LinkedHashMap<String, Long>();
        for (int i = 0; i < 2; i++) {
            String[] d = fed.take("discovered", 4000);
            if (d != null) found.put(d[3], Long.parseLong(d[1]));
        }
        DataType et = m.getDataType("EntityTypeStruct");
        if (found.containsKey("1-2-3")) {
            byte[] t = fed.waitValue(found.get("1-2-3"), gh[0], 3000), alt = fed.waitValue(found.get("1-2-3"), gh[1], 3000);
            check("DIS tank 1.1.225.1.1.3.0 arrives in HLA as 1.1.225.1.1.4.0", t != null && codec.decode(et, t).field("Specific").asLong() == 4
                    && codec.decode(et, t).field("Subcategory").asLong() == 1);
            check("its AlternateEntityType too", alt != null && codec.decode(et, alt).field("Specific").asLong() == 4);
        } else {
            check("HLA discovers the DIS tank 1-2-3 " + found.keySet(), false);
        }
        if (found.containsKey("1-2-4")) {
            byte[] t = fed.waitValue(found.get("1-2-4"), gh[0], 3000);
            check("a tank that is B already stays B", t != null && codec.decode(et, t).field("Specific").asLong() == 4);
        }

        // HLA -> DIS: an aircraft of type A goes to DIS as B, one of another type unchanged
        int ac = fed.s.objectClass(AIRCRAFT);
        int[] ah = {fed.s.attribute(ac, "EntityIdentifier"), fed.s.attribute(ac, "EntityType"), fed.s.attribute(ac, "Spatial")};
        fed.s.publishObjectClass(ac, ah);
        fed.reserve("F16");
        long f16 = fed.s.registerObject(ac, "F16");
        fed.s.updateAttributes(f16, ah, new byte[][] {
            codec.encode(m.getClassAttribute(AIRCRAFT, "EntityIdentifier").getDataType(), entityId(5, 6, 7)),
            codec.encode(et, entityType(1, 2, 225, 1, 3, 0, 0)),
            codec.encode(m.getClassAttribute(AIRCRAFT, "Spatial").getDataType(), spatial(codec, m, 3100000, 1000000, 5400000))}, null);
        fed.reserve("F15");
        long f15 = fed.s.registerObject(ac, "F15");
        fed.s.updateAttributes(f15, ah, new byte[][] {
            codec.encode(m.getClassAttribute(AIRCRAFT, "EntityIdentifier").getDataType(), entityId(5, 6, 8)),
            codec.encode(et, entityType(1, 2, 225, 1, 2, 0, 0)),
            codec.encode(m.getClassAttribute(AIRCRAFT, "Spatial").getDataType(), spatial(codec, m, 3100000, 1000000, 5400000))}, null);
        DisEntityState es = entityStateFrom(sim, s -> s.id.equals(new DisEntityId(5, 6, 7)), 4000);
        check("HLA aircraft 1.2.225.1.3.0.0 goes to DIS as 1.2.222.1.3.0.0", es != null && es.type.country == 222 && es.type.kind == 1
                && es.type.domain == 2 && es.type.category == 1 && es.type.subcategory == 3);
        DisEntityState es2 = entityStateFrom(sim, s -> s.id.equals(new DisEntityId(5, 6, 8)), 4000);
        check("an aircraft of another type keeps it", es2 != null && es2.type.country == 225 && es2.type.subcategory == 2);

        // HLA -> DIS: the munition type of a WeaponFire
        int fire = fed.s.interactionClass(FIRE2);
        fed.s.publishInteractionClass(fire);
        sleep(300);
        fed.s.sendInteraction(fire, new int[] {fed.s.parameter(fire, "FiringObjectIdentifier"), fed.s.parameter(fire, "MunitionType")}, new byte[][] {
            codec.encode(m.getInteractionClass(FIRE2).getParameter("FiringObjectIdentifier").getDataType(), HValue.ofText("F16")),
            codec.encode(et, entityType(2, 9, 225, 2, 1, 0, 0))}, null);
        byte[] fpdu = pduWhere(sim, DisPduHeader.FIRE, b -> true, 4000);
        check("WeaponFire munition 2.9.225.2.1.0.0 goes to DIS as 2.9.222.2.1.0.0", fpdu != null && fpdu.length >= 72
                && (fpdu[64] & 0xFF) == 2 && (fpdu[65] & 0xFF) == 9 && (((fpdu[66] & 0xFF) << 8) | (fpdu[67] & 0xFF)) == 222 && (fpdu[68] & 0xFF) == 2);

        RouterStats st = gw.getRouter().stats();
        check("router counts the replaced values (" + st.valuesReplaced.get() + ") and knows its 3 rules", st.valuesReplaced.get() >= 4 && st.valueRules == 3);
        gw.stop();
        fed.s.close();
        sim.close();
    }

    // ------------------------------------------------------------------ helpers

    private static List<ValueRule> rules(String pairs) {
        SimwProperties p = new SimwProperties();
        p.set("router.valuemap.EntityType.1", pairs);
        RouterConfig rc = new RouterConfig("r", p, "test", null);
        if (!rc.getErrors().isEmpty()) throw new IllegalStateException(rc.getErrors().toString());
        return rc.getValueRules();
    }

    private static boolean throwsIae(Runnable r, String text) {
        try {
            r.run();
            return false;
        } catch (IllegalArgumentException e) {
            if (!e.getMessage().contains(text)) System.out.println("  message: " + e.getMessage());
            return e.getMessage().contains(text);
        }
    }

    private static byte[] esPdu(DisEntityId id, DisEntityType type, String marking) {
        DisWriter w = new DisWriter(144).header(7, 3, 1, 1, 0, 0);
        w.entityId(id).u8(2).u8(0);
        w.entityType(type).entityType(type);
        w.f32(0).f32(0).f32(0).f64(3900000).f64(1000000).f64(4900000).f32(0).f32(0).f32(0);
        w.i32(0).u8(2).zeros(15).zeros(24);
        w.u8(1).text(marking, 11).i32(0);
        return w.finish();
    }

    private static DisEntityState entityStateFrom(DatagramSocket s, Predicate<DisEntityState> want, long millis) throws Exception {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            byte[] pdu = pduWhere(s, DisPduHeader.ENTITY_STATE, b -> true, end - System.currentTimeMillis());
            if (pdu == null) return null;
            DisReader r = new DisReader(pdu, 0, pdu.length);
            r.seek(DisPduHeader.SIZE);
            DisEntityState es = DisEntityState.read(r);
            if (want.test(es)) return es;
        }
        return null;
    }

    private static byte[] pduWhere(DatagramSocket s, int type, Predicate<byte[]> want, long millis) throws Exception {
        long end = System.currentTimeMillis() + millis;
        byte[] buf = new byte[2048];
        while (System.currentTimeMillis() < end) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                s.receive(p);
            } catch (SocketTimeoutException e) {
                continue;
            }
            if (p.getLength() >= 12 && (buf[2] & 0xFF) == type) {
                byte[] b = Arrays.copyOf(buf, p.getLength());
                if (want.test(b)) return b;
            }
        }
        return null;
    }

    private static HValue entityType(int kind, int domain, int country, int category, int sub, int specific, int extra) {
        Map<String, HValue> t = new LinkedHashMap<String, HValue>();
        t.put("EntityKind", HValue.ofInt(kind));
        t.put("Domain", HValue.ofInt(domain));
        t.put("CountryCode", HValue.ofInt(country));
        t.put("Category", HValue.ofInt(category));
        t.put("Subcategory", HValue.ofInt(sub));
        t.put("Specific", HValue.ofInt(specific));
        t.put("Extra", HValue.ofInt(extra));
        return HValue.ofRecord(t);
    }

    private static HValue entityId(int site, int app, int entity) {
        Map<String, HValue> f = new LinkedHashMap<String, HValue>();
        f.put("SiteID", HValue.ofInt(site));
        f.put("ApplicationID", HValue.ofInt(app));
        Map<String, HValue> e = new LinkedHashMap<String, HValue>();
        e.put("FederateIdentifier", HValue.ofRecord(f));
        e.put("EntityNumber", HValue.ofInt(entity));
        return HValue.ofRecord(e);
    }

    private static HValue spatial(HlaCodec codec, InformationExchangeModel m, double x, double y, double z) {
        HValue def = codec.defaultValue(m.getDataType("SpatialVariantStruct"));
        Map<String, HValue> loc = new LinkedHashMap<String, HValue>();
        loc.put("X", HValue.ofFloat(x));
        loc.put("Y", HValue.ofFloat(y));
        loc.put("Z", HValue.ofFloat(z));
        HValue fp = codec.defaultValue(m.getDataType("SpatialFPStruct")).withField("WorldLocation", HValue.ofRecord(loc));
        return HValue.ofVariant(2, "DRM_FPW", "SpatialFPW", fp).withType(def.getType());
    }

    private static final class Fed implements RtiSessionListener {
        final RtiSession s;
        final BlockingQueue<String[]> q = new LinkedBlockingQueue<String[]>();
        final Map<Long, Map<Integer, byte[]>> byInstance = new ConcurrentHashMap<Long, Map<Integer, byte[]>>();

        Fed(RtiLibrary lib, String federation, String name, String fom) throws Exception {
            s = lib.createSession(RtiSession.Version.HLA_1516E);
            s.connect(ConnectSettings.defaults(), this);
            s.join(federation, "Tester", name, Collections.singletonList(fom), true);
        }

        void reserve(String name) throws Exception {
            s.reserveName(name);
            String[] r = take("name", 3000);
            if (r == null || !r[2].equals("true")) throw new IllegalStateException("could not reserve " + name);
        }

        String[] take(String kind, long millis) throws InterruptedException {
            long end = System.currentTimeMillis() + millis;
            List<String[]> skipped = new ArrayList<String[]>();
            try {
                while (System.currentTimeMillis() < end) {
                    String[] e = q.poll(Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                    if (e == null) return null;
                    if (e[0].equals(kind)) return e;
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
                if (v != null) return v;
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
            for (int i = 0; i < attributes.length; i++) mine.put(attributes[i], vals[i]);
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
