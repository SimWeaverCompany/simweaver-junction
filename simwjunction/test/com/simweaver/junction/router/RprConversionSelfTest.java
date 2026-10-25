package com.simweaver.junction.router;

import com.simweaver.common.hla.codec.HValue;
import com.simweaver.common.hla.codec.HlaCodec;
import com.simweaver.common.iem.DataType;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.common.iem.ObjectClass;
import com.simweaver.junction.config.ClassFilter;
import com.simweaver.junction.router.convert.RprConversions;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * RPR v2.0 -> v3.0 -> v2.0 with the delivered {@code rpr2-rpr3.mapping}: values set in v2 must
 * arrive in v3 where the rules say and come back unchanged, for entities (spatial, appearance
 * moved to subclasses, enumerators that changed value, articulated parameters that became
 * variable parameters), radios, warfare interactions (new base classes, references) and
 * aggregates (count fields).
 *
 * <pre>java -cp out/classes:out/test com.simweaver.junction.router.RprConversionSelfTest &lt;fom root&gt; &lt;mappings dir&gt;</pre>
 */
public final class RprConversionSelfTest {

    private static final String GROUND = "HLAobjectRoot.BaseEntity.PhysicalEntity.Platform.GroundVehicle";
    private static final String HUMAN = "HLAobjectRoot.BaseEntity.PhysicalEntity.Lifeform.Human";
    private static final String TX = "HLAobjectRoot.EmbeddedSystem.RadioTransmitter";
    private static final String AGGREGATE = "HLAobjectRoot.BaseEntity.AggregateEntity";
    private static final String FIRE2 = "HLAinteractionRoot.WeaponFire";
    private static final String FIRE3 = "HLAinteractionRoot.Fire.WeaponFire";
    private static final String DET2 = "HLAinteractionRoot.MunitionDetonation";
    private static final String DET3 = "HLAinteractionRoot.Detonation.MunitionDetonation";

    private static int checks;
    private static int failures;

    private static InformationExchangeModel v2;
    private static InformationExchangeModel v3;
    private static HlaCodec c2;
    private static HlaCodec c3;
    private static RouteTable up;
    private static RouteTable down;
    private static final RouterStats STATS = new RouterStats();
    private static final UnaryOperator<String> SAME = n -> n;

    private RprConversionSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        File fomRoot = new File(args.length > 0 ? args[0] : "simwrift/rtdir/etc/fom");
        File mappings = new File(args.length > 1 ? args[1] : "simwrift/rtdir/etc/junctions/mappings");
        v2 = InformationExchangeModel.fromHlaEvolved(Collections.singletonList(
                new File(fomRoot, "ieee1516-2010/RPR_FOM_v2.0_Link16_1516-2010.xml").getPath()));
        List<String> files = new ArrayList<String>();
        for (File f : new File(fomRoot, "ieee1516-2010/rpr3").listFiles()) {
            if (f.getName().endsWith(".xml")) {
                files.add(f.getPath());
            }
        }
        Collections.sort(files);
        v3 = InformationExchangeModel.fromHlaEvolved(files);
        c2 = new HlaCodec(v2);
        c3 = new HlaCodec(v3);
        RprConversions conv = new RprConversions(mappings);
        RouteCompiler rc = new RouteCompiler(ClassFilter.ALL, true, conv);
        RouteCompiler.Side s2 = new RouteCompiler.Side("v2", new ModelTools(v2, Collections.singletonList("RTIobjectId")), null, null);
        RouteCompiler.Side s3 = new RouteCompiler.Side("v3", new ModelTools(v3, Collections.singletonList("RTIobjectId")), null, null);
        up = rc.compile(s2, s3);
        down = rc.compile(s3, s2);

        groundVehicle();
        human();
        transmitter();
        weaponFire();
        detonation();
        aggregate();
        emitters();
        designatorAndIff();
        check("no conversion failures: " + STATS.recentProblems(), STATS.conversionFailures.get() == 0);
        System.out.println(checks + " checks, " + failures + " failures (" + STATS.conversionWarnings.get() + " warnings)");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ objects

    private static void groundVehicle() {
        Map<String, HValue> in = new LinkedHashMap<String, HValue>();
        in.put("Spatial", spatial());
        in.put("DamageState", HValue.ofInt(2));                 // moderate
        in.put("CamouflageType", HValue.ofInt(3));              // v2 forest = v3 2
        in.put("HatchState", HValue.ofInt(4));                  // open
        in.put("HeadLightsOn", HValue.ofInt(1));
        in.put("TrailingEffectsCode", HValue.ofInt(2));         // v3 GroundVehicle: TrailingDustCloud
        in.put("Immobilized", HValue.ofInt(1));
        in.put("ForceIdentifier", HValue.ofInt(2));
        in.put("ArticulatedParametersArray", articulations());
        Map<String, HValue> v3v = object(GROUND, in, GROUND);
        check("ground: Damage", v3v.containsKey("Damage") && v3v.get("Damage").asLong() == 2);
        check("ground: CamouflageType forest = 2 in v3", v3v.containsKey("CamouflageType") && v3v.get("CamouflageType").asLong() == 2);
        check("ground: PrimaryHatch", v3v.containsKey("PrimaryHatch") && v3v.get("PrimaryHatch").asLong() == 4);
        check("ground: TrailingDustCloud", v3v.containsKey("TrailingDustCloud") && v3v.get("TrailingDustCloud").asLong() == 2);
        check("ground: MobilityKilled", v3v.containsKey("MobilityKilled") && v3v.get("MobilityKilled").asLong() == 1);
        HValue loc = v3v.get("Spatial") == null ? null : v3v.get("Spatial").path("Location.X");
        check("ground: Spatial Location.X", loc != null && loc.asDouble() == 3912345.25);
        HValue vp = v3v.get("VariableParametersArray");
        check("ground: two variable parameters", vp != null && vp.size() == 2);
        if (vp != null && vp.size() == 2) {
            HValue a = vp.get(0).getAlternative();
            check("ground: articulated part class / metric / value",
                    vp.get(0).asLong() == 0 && a.field("ParameterTypeClass").asLong() == 4096
                            && a.field("ParameterTypeMetric").asLong() == 11 && a.field("Value").asDouble() == 0.75);
            HValue b = vp.get(1).getAlternative();
            check("ground: attached part station and store",
                    vp.get(1).asLong() == 1 && b.field("ParameterType").asLong() == 2 && b.path("AttachedPartType.EntityKind").asLong() == 2);
        }
    }

    private static void human() {
        Map<String, HValue> in = new LinkedHashMap<String, HValue>();
        in.put("DamageState", HValue.ofInt(3));                 // -> Health fatal
        in.put("StanceCode", HValue.ofInt(5));                  // prone -> LifeFormPosture
        in.put("PrimaryWeaponState", HValue.ofInt(2));          // -> WeaponImplement1
        in.put("IsConcealed", HValue.ofInt(1));                 // -> ConcealedStationary
        Map<String, HValue> v3v = object(HUMAN, in, HUMAN);
        check("human: Health", v3v.containsKey("Health") && v3v.get("Health").asLong() == 3);
        check("human: LifeFormPosture", v3v.containsKey("LifeFormPosture") && v3v.get("LifeFormPosture").asLong() == 5);
        check("human: WeaponImplement1", v3v.containsKey("WeaponImplement1") && v3v.get("WeaponImplement1").asLong() == 2);
        check("human: ConcealedStationary", v3v.containsKey("ConcealedStationary"));
    }

    private static void transmitter() {
        Map<String, HValue> in = new LinkedHashMap<String, HValue>();
        Map<String, HValue> rt = new LinkedHashMap<String, HValue>();
        rt.put("EntityKind", HValue.ofInt(7));
        rt.put("Domain", HValue.ofInt(1));
        rt.put("CountryCode", HValue.ofInt(225));
        rt.put("Category", HValue.ofInt(2));
        rt.put("NomenclatureVersion", HValue.ofInt(3));
        rt.put("Nomenclature", HValue.ofInt(0x1234));
        in.put("RadioSystemType", HValue.ofRecord(rt));
        in.put("TransmittedPower", HValue.ofFloat(40.0));
        in.put("Frequency", HValue.ofInt(251_000_000L));
        in.put("HostObjectIdentifier", HValue.ofText("Tank-1"));
        in.put("WorldLocation", location());
        Map<String, HValue> v3v = object(TX, in, TX);
        HValue t = v3v.get("RadioSystemType");
        check("radio: DIS 7 radio type (subcategory, specific, extra)", t != null && t.field("Subcategory").asLong() == 3
                && t.field("Specific").asLong() == 0x12 && t.field("Extra").asLong() == 0x34);
        check("radio: Power", v3v.containsKey("Power") && v3v.get("Power").asDouble() == 40.0);
        check("radio: host reference", v3v.containsKey("HostObjectIdentifier") && "Tank-1".equals(v3v.get("HostObjectIdentifier").asTrimmedText()));
    }

    /** EmitterSystem, RadarBeam, JammerBeam: v2 -> v3 -> v2 (the jamming technique converted in code). */
    private static void emitters() {
        String es = "HLAobjectRoot.EmbeddedSystem.EmitterSystem";
        String radar = "HLAobjectRoot.EmitterBeam.RadarBeam";
        String jammer = "HLAobjectRoot.EmitterBeam.JammerBeam";
        Map<String, HValue> sys = new LinkedHashMap<String, HValue>();
        sys.put("HostObjectIdentifier", HValue.ofText("Ship-1"));
        sys.put("EmitterType", HValue.ofInt(9200));
        sys.put("EmitterFunctionCode", HValue.ofInt(2));
        sys.put("EmitterIndex", HValue.ofBytes(new byte[] {1}));
        Map<String, HValue> rel = new LinkedHashMap<String, HValue>();
        rel.put("BodyXDistance", HValue.ofFloat(1));
        rel.put("BodyYDistance", HValue.ofFloat(0));
        rel.put("BodyZDistance", HValue.ofFloat(-12));
        sys.put("RelativePosition", HValue.ofRecord(rel));
        Map<String, HValue> s3 = object(es, sys, es);
        check("emitter: type and host", s3.containsKey("EmitterType") && s3.get("EmitterType").asLong() == 9200
                && "Ship-1".equals(s3.get("HostObjectIdentifier").asTrimmedText()));

        Map<String, HValue> rb = new LinkedHashMap<String, HValue>();
        rb.put("EmitterSystemIdentifier", HValue.ofText("Ship-1/emitter-1"));
        rb.put("BeamIdentifier", HValue.ofBytes(new byte[] {1}));
        rb.put("BeamFunctionCode", HValue.ofInt(4));
        rb.put("BeamAzimuthCenter", HValue.ofFloat(0.5));
        rb.put("BeamAzimuthSweep", HValue.ofFloat(0.25));
        rb.put("EmissionFrequency", HValue.ofFloat(9.4e9));
        rb.put("EffectiveRadiatedPower", HValue.ofFloat(72));
        rb.put("HighDensityTrack", HValue.ofInt(1));
        rb.put("TrackObjectIdentifiers", HValue.ofArray(Arrays.asList(HValue.ofText("Bear-1"), HValue.ofText("Bear-2"))));
        Map<String, HValue> r3 = object(radar, rb, radar);
        check("radar: azimuth and function", r3.containsKey("BeamAzimuthCenter") && r3.get("BeamAzimuthCenter").asDouble() == 0.5
                && r3.containsKey("BeamFunctionCode") && r3.get("BeamFunctionCode").asLong() == 4);
        check("radar: two tracked objects (v3 Unicode RTIobjectIds)", r3.containsKey("TrackObjectIdentifiers")
                && r3.get("TrackObjectIdentifiers").size() == 2 && "Bear-2".equals(r3.get("TrackObjectIdentifiers").get(1).asTrimmedText()));

        Map<String, HValue> jb = new LinkedHashMap<String, HValue>();
        jb.put("EmitterSystemIdentifier", HValue.ofText("Ship-1/emitter-1"));
        jb.put("BeamFunctionCode", HValue.ofInt(12));
        jb.put("JammingModeSequence", HValue.ofInt(0x01020304L));
        jb.put("JammedObjectIdentifiers", HValue.ofArray(Arrays.asList(HValue.ofText("Bear-1"))));
        Map<String, HValue> j3 = object(jammer, jb, jammer);
        HValue t = j3.get("JammingModeSequence");
        check("jammer: v3 jamming technique 1.2.3.4", t != null && t.field("Kind").asLong() == 1 && t.field("Category").asLong() == 2
                && t.field("Subcategory").asLong() == 3 && t.field("Specific").asLong() == 4);
    }

    /** Designator; IFF system attributes (the modes go through RprIffBridge, tested in IffBridgeSelfTest). */
    private static void designatorAndIff() {
        String des = "HLAobjectRoot.EmbeddedSystem.Designator";
        Map<String, HValue> d = new LinkedHashMap<String, HValue>();
        d.put("HostObjectIdentifier", HValue.ofText("Jet-1"));
        d.put("CodeName", HValue.ofInt(1100));
        d.put("DesignatedObjectIdentifier", HValue.ofText("Tank-1"));
        d.put("DesignatorCode", HValue.ofInt(1688));
        d.put("DesignatorEmissionWavelength", HValue.ofFloat(1.064));
        d.put("DesignatorOutputPower", HValue.ofFloat(2.5));
        d.put("DesignatorSpotLocation", location());
        d.put("DeadReckoningAlgorithm", HValue.ofInt(2));
        Map<String, HValue> d3 = object(des, d, des);
        check("designator: spot location (v3 LocationStruct)", d3.containsKey("DesignatorSpotLocation")
                && d3.get("DesignatorSpotLocation").field("X").asDouble() == 3912345.25);
        check("designator: designated object", d3.containsKey("DesignatedObjectIdentifier")
                && "Tank-1".equals(d3.get("DesignatedObjectIdentifier").asTrimmedText()));

        String t2 = "HLAobjectRoot.EmbeddedSystem.IFF.NatoIFF.NatoIFFTransponder";
        String t3 = "HLAobjectRoot.EmbeddedSystem.IFFSystem.Transponder";
        Map<String, HValue> i = new LinkedHashMap<String, HValue>();
        i.put("HostObjectIdentifier", HValue.ofText("Jet-1"));
        i.put("SystemType", HValue.ofInt(1));
        i.put("SystemName", HValue.ofInt(7));
        i.put("SystemMode", HValue.ofInt(3));
        i.put("SystemIsOn", HValue.ofInt(1));
        i.put("SystemIsOperational", HValue.ofInt(1));
        i.put("Layer2DataAvailable", HValue.ofInt(1));
        i.put("EmergencyOn", HValue.ofInt(1));
        Map<String, HValue> i3 = object(t2, i, t3);
        check("IFF: SystemIsOperational true = v3 OperationalStatus Operational (0)", i3.containsKey("OperationalStatus")
                && i3.get("OperationalStatus").asLong() == 0);
        check("IFF: Layer2DataAvailable = EmissionsDataAvailable", i3.containsKey("EmissionsDataAvailable")
                && i3.get("EmissionsDataAvailable").asLong() == 1);
        i.put("SystemIsOperational", HValue.ofInt(0));
        Map<String, HValue> failed = object(t2, i, t3);
        check("IFF: not operational = SystemFailed (1)", failed.containsKey("OperationalStatus") && failed.get("OperationalStatus").asLong() == 1);
    }

    private static void aggregate() {
        DataType arr = v2.getClassAttribute(AGGREGATE, "SilentEntities").getDataType();
        HValue def = c2.defaultValue(arr);
        Map<String, HValue> se = new LinkedHashMap<String, HValue>();
        se.put("NumberOfEntitiesOfThisType", HValue.ofInt(4));
        se.put("NumberOfAppearanceRecords", HValue.ofInt(2));
        se.put("EntityType", c2.defaultValue(v2.getDataType("EntityTypeStruct")));
        se.put("EntityAppearance", HValue.ofArray(Arrays.asList(HValue.ofInt(7), HValue.ofInt(9))));
        Map<String, HValue> in = new LinkedHashMap<String, HValue>();
        in.put("SilentEntities", HValue.ofArray(Collections.singletonList(HValue.ofRecord(se))).withType(def.getType()));
        object(AGGREGATE, in, AGGREGATE);
    }

    /**
     * Sends v2 attribute values up and back down; checks the way back gives the same octets.
     *
     * @return the v3 values by attribute name
     */
    private static Map<String, HValue> object(String cls2, Map<String, HValue> values, String cls3) {
        ObjectClass oc2 = v2.getObjectClass(cls2);
        ObjectClass oc3 = v3.getObjectClass(cls3);
        ClassRoute r = up.objectRoute(oc2.getId());
        ClassRoute back = down.objectRoute(oc3.getId());
        check(cls2 + " routed up to " + cls3, r != null && r.getDestinationClass() == oc3.getId());
        check(cls3 + " routed down", back != null && back.getDestinationClass() == oc2.getId());
        Map<String, HValue> out = new LinkedHashMap<String, HValue>();
        if (r == null || back == null) {
            return out;
        }
        int n = values.size();
        int[] ids = new int[n];
        byte[][] raw = new byte[n][];
        int i = 0;
        for (Map.Entry<String, HValue> e : values.entrySet()) {
            DataType t = v2.getClassAttribute(cls2, e.getKey()).getDataType();
            ids[i] = v2.getClassAttribute(cls2, e.getKey()).getId();
            raw[i] = c2.encode(t, e.getValue());
            i++;
        }
        ClassRoute.Converted c = r.convert(ids, raw, SAME, STATS);
        check(cls2 + ": converted", c != null);
        if (c == null) {
            return out;
        }
        for (int k = 0; k < c.ids.length; k++) {
            com.simweaver.common.iem.Attribute a = v3.getAttribute(c.ids[k]);
            out.put(a.getName(), c3.decode(a.getDataType(), c.values[k]));
        }
        ClassRoute.Converted b = back.convert(c.ids, c.values, SAME, STATS);
        Map<Integer, byte[]> backById = new LinkedHashMap<Integer, byte[]>();
        for (int k = 0; b != null && k < b.ids.length; k++) {
            backById.put(b.ids[k], b.values[k]);
        }
        for (int k = 0; k < n; k++) {
            String name = v2.getAttribute(ids[k]).getName();
            byte[] got = backById.get(ids[k]);
            check(cls2 + "." + name + " comes back unchanged", got != null && Arrays.equals(got, raw[k]));
        }
        return out;
    }

    // ------------------------------------------------------------------ interactions

    private static void weaponFire() {
        Map<String, HValue> in = new LinkedHashMap<String, HValue>();
        in.put("FiringObjectIdentifier", HValue.ofText("Tank-1"));
        in.put("TargetObjectIdentifier", HValue.ofText("Truck-2"));
        in.put("FiringLocation", location());
        in.put("QuantityFired", HValue.ofInt(3));
        in.put("WarheadType", HValue.ofInt(1000));
        in.put("FuseType", HValue.ofInt(1100));
        Map<String, HValue> e = new LinkedHashMap<String, HValue>();
        e.put("EventCount", HValue.ofInt(17));
        e.put("IssuingObjectIdentifier", HValue.ofText("Tank-1"));
        in.put("EventIdentifier", HValue.ofRecord(e));
        UnaryOperator<String> rename = n -> "Tank-1".equals(n) ? "Tank-1@blue" : n;
        Map<String, HValue> v3v = interaction(FIRE2, FIRE3, in, rename);
        check("fire: firing object renamed", v3v.containsKey("FiringObjectIdentifier")
                && "Tank-1@blue".equals(v3v.get("FiringObjectIdentifier").asTrimmedText()));
        check("fire: event issuer renamed inside the record", v3v.containsKey("EventIdentifier")
                && "Tank-1@blue".equals(v3v.get("EventIdentifier").field("IssuingObjectIdentifier").asTrimmedText()));
        check("fire: location", v3v.containsKey("FiringLocation") && v3v.get("FiringLocation").field("X").asDouble() == 3912345.25);
        check("fire: quantity", v3v.containsKey("QuantityFired") && v3v.get("QuantityFired").asLong() == 3);
    }

    private static void detonation() {
        Map<String, HValue> in = new LinkedHashMap<String, HValue>();
        in.put("DetonationLocation", location());
        in.put("DetonationResultCode", HValue.ofInt(1));
        in.put("ArticulatedPartData", articulations());
        Map<String, HValue> v3v = interaction(DET2, DET3, in, SAME);
        check("detonation: result", v3v.containsKey("DetonationResultCode") && v3v.get("DetonationResultCode").asLong() == 1);
        check("detonation: variable parameters", v3v.containsKey("VariableParametersArray") && v3v.get("VariableParametersArray").size() == 2);
    }

    private static Map<String, HValue> interaction(String cls2, String cls3, Map<String, HValue> values, UnaryOperator<String> rename) {
        int ic2 = v2.getInteractionClass(cls2).getId();
        int ic3 = v3.getInteractionClass(cls3).getId();
        ClassRoute r = up.interactionRoute(ic2);
        ClassRoute back = down.interactionRoute(ic3);
        check(cls2 + " routed up to " + cls3, r != null && r.getDestinationClass() == ic3);
        check(cls3 + " routed down", back != null && back.getDestinationClass() == ic2);
        Map<String, HValue> out = new LinkedHashMap<String, HValue>();
        if (r == null || back == null) {
            return out;
        }
        int n = values.size();
        int[] ids = new int[n];
        byte[][] raw = new byte[n][];
        int i = 0;
        for (Map.Entry<String, HValue> e : values.entrySet()) {
            com.simweaver.common.iem.ClassParameter p = v2.getClassParameter(cls2, e.getKey());
            ids[i] = p.getId();
            raw[i] = c2.encode(p.getDataType(), e.getValue());
            i++;
        }
        ClassRoute.Converted c = r.convert(ids, raw, rename, STATS);
        check(cls2 + ": converted", c != null);
        if (c == null) {
            return out;
        }
        for (int k = 0; k < c.ids.length; k++) {
            com.simweaver.common.iem.Parameter p = v3.getParameter(c.ids[k]);
            out.put(p.getName(), c3.decode(p.getDataType(), c.values[k]));
        }
        if (rename == SAME) {
            ClassRoute.Converted b = back.convert(c.ids, c.values, SAME, STATS);
            Map<Integer, byte[]> backById = new LinkedHashMap<Integer, byte[]>();
            for (int k = 0; b != null && k < b.ids.length; k++) {
                backById.put(b.ids[k], b.values[k]);
            }
            for (int k = 0; k < n; k++) {
                String name = v2.getParameter(ids[k]).getName();
                check(cls2 + "." + name + " comes back unchanged", Arrays.equals(backById.get(ids[k]), raw[k]));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ values

    private static HValue location() {
        Map<String, HValue> l = new LinkedHashMap<String, HValue>();
        l.put("X", HValue.ofFloat(3912345.25));
        l.put("Y", HValue.ofFloat(1012345.5));
        l.put("Z", HValue.ofFloat(4912345.75));
        return HValue.ofRecord(l);
    }

    private static HValue spatial() {
        HValue def = c2.defaultValue(v2.getDataType("SpatialVariantStruct"));
        Map<String, HValue> o = new LinkedHashMap<String, HValue>();
        o.put("Psi", HValue.ofFloat(0.5));
        o.put("Theta", HValue.ofFloat(-0.25));
        o.put("Phi", HValue.ofFloat(1.75));
        HValue fp = c2.defaultValue(v2.getDataType("SpatialFPStruct")).withField("WorldLocation", location())
                .withField("Orientation", HValue.ofRecord(o));
        return HValue.ofVariant(2, "DRM_FPW", "SpatialFPW", fp).withType(def.getType());
    }

    private static HValue articulations() {
        Map<String, HValue> part = new LinkedHashMap<String, HValue>();
        part.put("Class", HValue.ofInt(4096));
        part.put("TypeMetric", HValue.ofInt(11));
        part.put("Value", HValue.ofFloat(0.75));
        Map<String, HValue> a = new LinkedHashMap<String, HValue>();
        a.put("ArticulatedParameterChange", HValue.ofBytes(new byte[] {1}));
        a.put("PartAttachedTo", HValue.ofInt(0));
        a.put("ParameterValue", HValue.ofVariant(0, "ArticulatedPart", "ArticulatedParts", HValue.ofRecord(part)));
        Map<String, HValue> store = new LinkedHashMap<String, HValue>();
        store.put("EntityKind", HValue.ofBytes(new byte[] {2}));
        store.put("Domain", HValue.ofBytes(new byte[] {9}));
        store.put("CountryCode", HValue.ofInt(225));
        store.put("Category", HValue.ofBytes(new byte[] {1}));
        store.put("Subcategory", HValue.ofBytes(new byte[] {1}));
        store.put("Specific", HValue.ofBytes(new byte[] {0}));
        store.put("Extra", HValue.ofBytes(new byte[] {0}));
        Map<String, HValue> att = new LinkedHashMap<String, HValue>();
        att.put("Station", HValue.ofInt(2));
        att.put("StoreType", HValue.ofRecord(store));
        Map<String, HValue> b = new LinkedHashMap<String, HValue>();
        b.put("ArticulatedParameterChange", HValue.ofBytes(new byte[] {0}));
        b.put("PartAttachedTo", HValue.ofInt(0));
        b.put("ParameterValue", HValue.ofVariant(1, "AttachedPart", "AttachedParts", HValue.ofRecord(att)));
        return HValue.ofArray(Arrays.asList(HValue.ofRecord(a), HValue.ofRecord(b)));
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what);
        }
    }
}
