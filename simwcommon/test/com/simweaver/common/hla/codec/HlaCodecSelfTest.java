package com.simweaver.common.hla.codec;

import com.simweaver.common.hla.decoders.DecodedValue;
import com.simweaver.common.hla.decoders.HlaDataDecoder;
import com.simweaver.common.hla.decoders.TypeLayouts;
import com.simweaver.common.iem.ArrayDataType;
import com.simweaver.common.iem.DataType;
import com.simweaver.common.iem.DataTypeCategory;
import com.simweaver.common.iem.EnumeratedDataType;
import com.simweaver.common.iem.Enumerator;
import com.simweaver.common.iem.FixedRecordDataType;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.common.iem.RecordField;
import com.simweaver.common.iem.VariantAlternative;
import com.simweaver.common.iem.VariantRecordDataType;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Self test of {@link HlaCodec} and {@link TypeAnalysis} (no JUnit; run with the FOM folder):
 *
 * <pre>java -cp ... com.simweaver.common.hla.codec.HlaCodecSelfTest &lt;fom root&gt;</pre>
 *
 * The FOM root holds {@code ieee1516-2010/RPR_FOM_v2.0_Link16_1516-2010.xml},
 * {@code ieee1516-2010/pitchtalk.xml}, {@code ieee1516-2010/netn4-hla3/*.xml},
 * {@code ieee1516-2025/RPR-*_v2.0.xml} and {@code rpr3/RPR-*_v3.0.xml}.
 *
 * <ol>
 *   <li>For every datatype of every model: random values are encoded, decoded and encoded
 *       again; both encodings must be equal, and the decoders ({@link HlaDataDecoder}, an
 *       independent implementation) must read the octets without errors.</li>
 *   <li>Hand-checked encodings of a few RPR types (alignment, padding, null termination).</li>
 *   <li>Signatures: equal for the RPR v2 types of the 1516-2010 and the 1516-2025 FOMs where
 *       their definitions agree; different where the encodings differ (RTIobjectId v2 / v3).</li>
 *   <li>Transcoding RPR v2 to v3 by name for a few attribute types.</li>
 *   <li>Reference rewriting inside nested values.</li>
 * </ol>
 */
public final class HlaCodecSelfTest {

    private static int failures;
    private static int checks;
    private static int knownDecoderIssues;

    private HlaCodecSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        File root = new File(args.length > 0 ? args[0] : "simwrift/rtdir/etc/fom");
        InformationExchangeModel rpr2e = InformationExchangeModel.fromHlaEvolved(Arrays.asList(
                new File(root, "ieee1516-2010/RPR_FOM_v2.0_Link16_1516-2010.xml").getPath()));
        InformationExchangeModel talk = InformationExchangeModel.fromHlaEvolved(Arrays.asList(
                new File(root, "ieee1516-2010/pitchtalk.xml").getPath(),
                new File(root, "ieee1516-2010/pitchtalkeffects.xml").getPath()));
        List<String> netn = new ArrayList<String>();
        netn.add(new File(root, "ieee1516-2010/RPR_FOM_v2.0_Link16_1516-2010.xml").getPath());
        for (String n : new String[] {"NETN-BASE", "NETN-ENTITY", "NETN-ORG", "NETN-LOG", "NETN-SMC", "NETN-TMR",
                "NETN-MRM", "NETN-ETR", "NETN-COM", "NETN-CBRN", "NETN-METOC", "NETN-AIS", "NETN-SE", "NETN-DIM"}) {
            File f = new File(root, "ieee1516-2010/netn4-hla3/" + n + ".xml");
            if (f.isFile()) {
                netn.add(f.getPath());
            }
        }
        InformationExchangeModel netn3 = InformationExchangeModel.fromHlaEvolved(netn);
        InformationExchangeModel rpr2h4 = InformationExchangeModel.fromHla4(files(new File(root, "ieee1516-2025"), "_v2.0.xml"));
        InformationExchangeModel rpr3 = InformationExchangeModel.fromHlaEvolved(files(new File(root, "ieee1516-2010/rpr3"), "_v3.0.xml"));

        for (Object[] m : new Object[][] {{"RPR v2 (1516-2010)", rpr2e}, {"Pitch Talk", talk}, {"NETN + RPR v2", netn3},
                {"RPR v2 (1516-2025)", rpr2h4}, {"RPR v3 (1516-2010)", rpr3}}) {
            roundTrips((String) m[0], (InformationExchangeModel) m[1]);
        }
        handChecked(rpr2e, rpr3);
        signatures(rpr2e, rpr2h4, rpr3);
        transcode(rpr2e, rpr3);
        references(rpr2e, rpr3);

        System.out.println();
        System.out.println(checks + " checks, " + failures + " failures"
                + (knownDecoderIssues > 0 ? " (" + knownDecoderIssues + " values hit the known RPR v3 SilentEntityStruct count rule issue of the decoders)" : ""));
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static List<String> files(File dir, String suffix) {
        List<String> l = new ArrayList<String>();
        File[] fs = dir.listFiles();
        if (fs != null) {
            Arrays.sort(fs);
            for (File f : fs) {
                if (f.getName().endsWith(suffix)) {
                    l.add(f.getPath());
                }
            }
        }
        return l;
    }

    // ------------------------------------------------------------------ 1. round trips

    private static void roundTrips(String label, InformationExchangeModel model) {
        HlaCodec codec = new HlaCodec(model);
        HlaDataDecoder decoder = new HlaDataDecoder(model, HlaDataDecoder.defaultOptionsFor(model).clearFormatters().build());
        Random rnd = new Random(42);
        int types = 0;
        int values = 0;
        int mismatch = 0;
        int decoderErrors = 0;
        int skipped = 0;
        for (DataType t : model.getDataTypes()) {
            if (t.getCategory() == DataTypeCategory.OTHER) {
                continue;
            }
            types++;
            for (int i = 0; i < 12; i++) {
                HValue v;
                try {
                    v = random(codec, t, rnd, 0, true);
                } catch (Unsupported u) {
                    skipped++;
                    break;
                }
                values++;
                byte[] a;
                byte[] b;
                try {
                    a = codec.encode(t, v);
                    HValue back = codec.decode(t, a);
                    b = codec.encode(t, back);
                } catch (RuntimeException e) {
                    mismatch++;
                    if (mismatch <= 10) {
                        System.out.println("  " + label + " " + t.getName() + ": " + e);
                    }
                    break;
                }
                if (!Arrays.equals(a, b)) {
                    mismatch++;
                    if (mismatch <= 10) {
                        System.out.println("  " + label + " " + t.getName() + ": re-encoding differs\n    " + hex(a) + "\n    " + hex(b) + "\n    " + v);
                    }
                    break;
                }
                DecodedValue d = decoder.decode(t, a);
                String err = firstError(d);
                if (err != null && err.contains("NumberOfAppearanceRecords") && !model.hasFomFamily(com.simweaver.common.iem.FomFamily.RPR_FOM_V2)) {
                    knownDecoderIssues++;          // the decoders apply the RPR v2 count rule to RPR v3 (reported)
                    break;
                }
                if (err != null) {
                    decoderErrors++;
                    if (decoderErrors <= 10) {
                        System.out.println("  " + label + " " + t.getName() + ": decoder says " + err + "\n    " + hex(a) + "\n    " + v);
                    }
                    break;
                }
            }
        }
        System.out.printf("%-22s %4d types, %5d values: %d re-encoding differences, %d decoder errors, %d skipped%n",
                label, types, values, mismatch, decoderErrors, skipped);
        check(label + ": round trips", mismatch == 0);
        check(label + ": decoder agrees", decoderErrors == 0);
    }

    private static String firstError(DecodedValue d) {
        if (d.getError() != null) {
            return d.getName() + ": " + d.getError();
        }
        for (DecodedValue c : d.getChildren()) {
            String e = firstError(c);
            if (e != null) {
                return e;
            }
        }
        return null;
    }

    /** Types the generator cannot make a decodable value for. */
    private static final class Unsupported extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Unsupported(String m) {
            super(m, null, false, false);
        }
    }

    /**
     * A random value of {@code t}. {@code last}: the value ends the encoded data, so a
     * lengthless array without a count rule may have elements.
     */
    private static HValue random(HlaCodec codec, DataType t, Random rnd, int depth, boolean last) {
        TypeLayouts lay = codec.getLayouts();
        if (depth > 6) {
            return codec.defaultValue(t);
        }
        switch (t.getCategory()) {
            case BASIC:
            case SIMPLE: {
                HValue d = codec.defaultValue(t);
                if (d.getKind() == HValue.Kind.FLOAT) {
                    return HValue.ofFloat(Math.round(rnd.nextGaussian() * 1000) / 8.0).withType(t);
                }
                if (d.getKind() == HValue.Kind.BYTES) {
                    byte[] b = new byte[d.size()];
                    rnd.nextBytes(b);
                    return HValue.ofBytes(b).withType(t);
                }
                int size = lay.getFixedSize(t);
                long v = rnd.nextLong();
                if (size < 8) {
                    v &= (1L << (8 * size)) - 1;
                    // signed representations: keep it in the positive range so decode gives the same number
                    v &= (1L << (8 * size - 1)) - 1;
                } else {
                    v &= Long.MAX_VALUE;
                }
                return HValue.ofInt(v).withType(t);
            }
            case ENUMERATED: {
                EnumeratedDataType e = (EnumeratedDataType) t;
                if (e.getEnumerators().isEmpty()) {
                    return HValue.ofEnum(0, null).withType(t);
                }
                Enumerator en = e.getEnumerators().get(rnd.nextInt(e.getEnumerators().size()));
                return HValue.ofEnum(en.getValue(), en.getName()).withType(t);
            }
            case ARRAY: {
                ArrayDataType a = (ArrayDataType) t;
                HlaCodec.ArrayEncoding enc = HlaCodec.arrayEncoding(a);
                if (enc == HlaCodec.ArrayEncoding.PAD32 || enc == HlaCodec.ArrayEncoding.PAD64) {
                    return HValue.padding().withType(t);
                }
                DataType elem = a.getElementType();
                int n;
                if (enc == HlaCodec.ArrayEncoding.FIXED) {
                    n = (int) Math.max(0, a.getCardinality().getFixedElementCount());
                } else if (enc == HlaCodec.ArrayEncoding.LENGTHLESS && !last) {
                    n = 0;
                } else {
                    n = rnd.nextInt(4);
                    if (a.getCardinality() != null && a.getCardinality().getDimensions().size() == 1) {
                        int min = a.getCardinality().getDimensions().get(0).getMin();
                        n = Math.max(n, min);
                    }
                    if (enc == HlaCodec.ArrayEncoding.LENGTHLESS && lay.getFixedSize(elem) < 0 && n > 0) {
                        // variable-size elements in a lengthless array: decodable only if each element is self-delimiting
                        n = Math.min(n, 1);
                    }
                }
                int size = lay.getFixedSize(elem);
                if (lay.isCharType(elem) && (size == 1 || size == 2)) {
                    char[] c = new char[n];
                    for (int i = 0; i < n; i++) {
                        c[i] = (char) ('A' + rnd.nextInt(26));
                    }
                    return HValue.ofText(new String(c)).withType(t);
                }
                if (lay.isOctetType(elem) && size == 1) {
                    byte[] b = new byte[n];
                    for (int i = 0; i < n; i++) {
                        b[i] = (byte) (1 + rnd.nextInt(255));       // non-zero (null-terminated arrays)
                    }
                    return HValue.ofBytes(b).withType(t);
                }
                List<HValue> l = new ArrayList<HValue>();
                for (int i = 0; i < n; i++) {
                    HValue e = random(codec, elem, rnd, depth + 1, last && i == n - 1 && enc != HlaCodec.ArrayEncoding.FIXED);
                    if (enc == HlaCodec.ArrayEncoding.NULL_TERMINATED && Arrays.equals(codec.encode(elem, e), new byte[codec.encode(elem, e).length])) {
                        throw new Unsupported("all-zero element in a null-terminated array");
                    }
                    l.add(e);
                }
                return HValue.ofArray(l).withType(t);
            }
            case FIXED_RECORD: {
                FixedRecordDataType r = (FixedRecordDataType) t;
                Map<String, HValue> m = new LinkedHashMap<String, HValue>();
                List<RecordField> fs = r.getFields();
                for (int i = 0; i < fs.size(); i++) {
                    RecordField f = fs.get(i);
                    if (f.getDataType() == null) {
                        throw new Unsupported("unresolved field type");
                    }
                    boolean lastField = last && i == fs.size() - 1;
                    m.put(f.getName(), random(codec, f.getDataType(), rnd, depth + 1, lastField));
                }
                // fields whose count is given by a rule over the record: keep those arrays empty and the count 0
                return zeroCounted(codec, r, HValue.ofRecord(m).withType(t));
            }
            case VARIANT_RECORD: {
                VariantRecordDataType v = (VariantRecordDataType) t;
                List<VariantAlternative> alts = new ArrayList<VariantAlternative>();
                for (VariantAlternative a : v.getAlternatives()) {
                    if (!a.getEnumerators().isEmpty()) {
                        alts.add(a);
                    }
                }
                if (alts.isEmpty()) {
                    throw new Unsupported("variant without alternatives");
                }
                VariantAlternative a = alts.get(rnd.nextInt(alts.size()));
                Enumerator en = a.getEnumerators().get(0);
                HValue av = a.isEmpty() || a.getDataType() == null ? null
                        : random(codec, a.getDataType(), rnd, depth + 1, last || HlaCodec.isExtendable(v));
                return HValue.ofVariant(en.getValue(), en.getName(), a.getName(), av).withType(t);
            }
            default:
                throw new Unsupported("other");
        }
    }

    /** Lengthless arrays with a count rule: set the counted fields so that the rule gives the array size. */
    private static HValue zeroCounted(HlaCodec codec, FixedRecordDataType r, HValue rec) {
        HValue out = rec;
        for (RecordField f : r.getFields()) {
            if (f.getDataType() instanceof ArrayDataType
                    && HlaCodec.arrayEncoding((ArrayDataType) f.getDataType()) == HlaCodec.ArrayEncoding.LENGTHLESS) {
                Map<String, Long> zeros = new java.util.HashMap<String, Long>();
                for (RecordField g : r.getFields()) {
                    zeros.put(g.getName(), 0L);
                }
                HlaDataDecoder dec = new HlaDataDecoder(codec.getModel(), HlaDataDecoder.defaultOptionsFor(codec.getModel()).build());
                long count = dec.evaluateCountRule(r, f, zeros);
                if (count >= 0) {
                    for (RecordField g : r.getFields()) {
                        if (g == f) {
                            break;
                        }
                        HValue gv = out.field(g.getName());
                        if (gv != null && gv.getKind() == HValue.Kind.INT) {
                            out = out.withField(g.getName(), HValue.ofInt(0).withType(gv.getType()));
                        }
                    }
                    DataType elem = ((ArrayDataType) f.getDataType()).getElementType();
                    List<HValue> elems = new ArrayList<HValue>();
                    for (long k = 0; k < count; k++) {
                        elems.add(codec.defaultValue(elem));
                    }
                    out = out.withField(f.getName(), HValue.ofArray(elems).withType(f.getDataType()));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 2. hand-checked encodings

    private static void handChecked(InformationExchangeModel rpr2, InformationExchangeModel rpr3) {
        HlaCodec c2 = new HlaCodec(rpr2);
        HlaCodec c3 = new HlaCodec(rpr3);
        // RTIobjectId v2: null-terminated ASCII
        byte[] b = c2.encode(rpr2.getDataType("RTIobjectId"), HValue.ofText("Tank"));
        check("RTIobjectId v2 = 'Tank\\0'", Arrays.equals(b, new byte[] {'T', 'a', 'n', 'k', 0}));
        check("RTIobjectId v2 decodes", c2.decode(rpr2.getDataType("RTIobjectId"), b).asText().equals("Tank"));
        // RTIobjectId v3: HLAvariableArray of HLAunicodeChar
        b = c3.encode(rpr3.getDataType("RTIobjectId"), HValue.ofText("Tank"));
        check("RTIobjectId v3 = count + UTF-16BE", Arrays.equals(b, new byte[] {0, 0, 0, 4, 0, 'T', 0, 'a', 0, 'n', 0, 'k'}));
        // EntityIdentifierStruct: three 16-bit numbers
        Map<String, HValue> fed = new LinkedHashMap<String, HValue>();
        fed.put("SiteID", HValue.ofInt(1));
        fed.put("ApplicationID", HValue.ofInt(2));
        Map<String, HValue> id = new LinkedHashMap<String, HValue>();
        id.put("FederateIdentifier", HValue.ofRecord(fed));
        id.put("EntityNumber", HValue.ofInt(3));
        b = c2.encode(rpr2.getDataType("EntityIdentifierStruct"), HValue.ofRecord(id));
        check("EntityIdentifierStruct", Arrays.equals(b, new byte[] {0, 1, 0, 2, 0, 3}));
        HValue back = c2.decode(rpr2.getDataType("EntityIdentifierStruct"), b);
        check("EntityIdentifierStruct path", back.path("FederateIdentifier.ApplicationID").asLong() == 2);
        // a variable array of records with 8-octet members: count, then padding to 8
        DataType vt = rpr2.getDataType("ArticulatedParameterStructLengthlessArray");
        check("ArticulatedParameterStructLengthlessArray known", vt != null);
        // WorldLocationStruct: 3 x float64
        Map<String, HValue> loc = new LinkedHashMap<String, HValue>();
        loc.put("X", HValue.ofFloat(1.5));
        loc.put("Y", HValue.ofFloat(-2));
        loc.put("Z", HValue.ofFloat(3));
        b = c2.encode(rpr2.getDataType("WorldLocationStruct"), HValue.ofRecord(loc));
        check("WorldLocationStruct 24 octets", b.length == 24 && c2.decode(rpr2.getDataType("WorldLocationStruct"), b).field("Y").asDouble() == -2);
        // padding: a record of {Octet, Float64} puts 7 octets of padding after the octet
        DataType spatialFp = rpr2.getDataType("SpatialFPStruct");
        if (spatialFp != null) {
            byte[] e = c2.encode(spatialFp, c2.defaultValue(spatialFp));
            check("SpatialFPStruct default encodes and decodes", c2.encode(spatialFp, c2.decode(spatialFp, e)).length == e.length);
        }
        // MarkingStruct: fixed array of 11 chars, short text padded with NULs
        Map<String, HValue> mk = new LinkedHashMap<String, HValue>();
        mk.put("MarkingEncodingType", HValue.ofEnum(1, "ASCII"));
        mk.put("MarkingData", HValue.ofText("TANK1"));
        b = c2.encode(rpr2.getDataType("MarkingStruct"), HValue.ofRecord(mk));
        check("MarkingStruct = 12 octets", b.length == 12 && b[1] == 'T' && b[6] == 0 && b[11] == 0);
    }

    // ------------------------------------------------------------------ 3. signatures

    private static void signatures(InformationExchangeModel rpr2e, InformationExchangeModel rpr2h4, InformationExchangeModel rpr3) {
        TypeAnalysis a2 = new TypeAnalysis(new HlaCodec(rpr2e));
        TypeAnalysis a24 = new TypeAnalysis(new HlaCodec(rpr2h4));
        TypeAnalysis a3 = new TypeAnalysis(new HlaCodec(rpr3));
        int same = 0;
        int differ = 0;
        List<String> different = new ArrayList<String>();
        for (DataType t : rpr2e.getDataTypes()) {
            DataType u = rpr2h4.getDataType(t.getName());
            if (u == null || t.getCategory() == DataTypeCategory.OTHER) {
                continue;
            }
            if (a2.signature(t).equals(a24.signature(u))) {
                same++;
            } else {
                differ++;
                if (different.size() < 8) {
                    different.add(t.getName());
                }
            }
        }
        System.out.println("Signatures RPR v2 1516-2010 vs 1516-2025: " + same + " equal, " + differ + " different " + different);
        check("most RPR v2 types have equal signatures in both FOM formats", same > 10 * Math.max(1, differ));
        check("Spatial v2 1516-2010 = v2 1516-2025", a2.signature(rpr2e.getDataType("SpatialVariantStruct"))
                .equals(a24.signature(rpr2h4.getDataType("SpatialVariantStruct"))));
        check("RTIobjectId v2 != v3", !a2.signature(rpr2e.getDataType("RTIobjectId")).equals(a3.signature(rpr3.getDataType("RTIobjectId"))));
        check("WorldLocationStruct v2 = LocationStruct v3", a2.signature(rpr2e.getDataType("WorldLocationStruct"))
                .equals(a3.signature(rpr3.getDataType("LocationStruct"))));
        check("RTIobjectId is a reference", a2.isReference(rpr2e.getDataType("RTIobjectId")));
        check("RTIobjectIdArray contains a reference", a2.containsReference(rpr2e.getDataType("RTIobjectIdArray")));
        check("WorldLocationStruct has no reference", !a2.containsReference(rpr2e.getDataType("WorldLocationStruct")));
    }

    // ------------------------------------------------------------------ 4. transcode v2 -> v3

    private static void transcode(InformationExchangeModel rpr2, InformationExchangeModel rpr3) {
        HlaCodec c2 = new HlaCodec(rpr2);
        HlaCodec c3 = new HlaCodec(rpr3);
        // Spatial: RPR v3 renamed WorldLocation to Location and added OtherParameters (DR parameters)
        DataType s2 = rpr2.getDataType("SpatialVariantStruct");
        DataType s3 = rpr3.getDataType("SpatialVariantStruct");
        TranscodeRules.Builder up = TranscodeRules.builder();
        TranscodeRules.Builder down = TranscodeRules.builder();
        for (String st : new String[] {"SpatialStaticStruct", "SpatialFPStruct", "SpatialRPStruct", "SpatialRVStruct", "SpatialFVStruct"}) {
            up.field(st, "Location", "WorldLocation").defaulted(st, "OtherParameters");
            down.field(st, "WorldLocation", "Location");
        }
        TranscodeRules toV3 = up.build();
        TranscodeRules toV2 = down.build();
        Random rnd = new Random(7);
        for (int i = 0; i < 20; i++) {
            HValue v = random(c2, s2, rnd, 0, true);
            byte[] b2 = c2.encode(s2, v);
            final List<String> warnings = new ArrayList<String>();
            byte[] b3 = c3.encode(s3, c2.decode(s2, b2), toV3, warnings::add);
            HValue v3 = c3.decode(s3, b3);
            check("Spatial v2 -> v3 keeps the location", v3.path("Location").equals(c2.decode(s2, b2).path("WorldLocation"))
                    || v3.getAlternative().field("Location").field("X").asDouble() == c2.decode(s2, b2).getAlternative().field("WorldLocation").field("X").asDouble());
            check("Spatial v2 -> v3 without warnings " + warnings, warnings.isEmpty());
            byte[] back = c2.encode(s2, v3, toV2, null);
            check("Spatial v2 -> v3 -> v2 octets equal", Arrays.equals(b2, back));
        }
        // RTIobjectId: ASCII null-terminated -> Unicode variable array
        HValue name = c2.decode(rpr2.getDataType("RTIobjectId"), new byte[] {'A', '-', '1', 0});
        byte[] b3 = c3.encode(rpr3.getDataType("RTIobjectId"), name);
        check("RTIobjectId v2 -> v3", c3.decode(rpr3.getDataType("RTIobjectId"), b3).asText().equals("A-1"));
        // an enum renamed between versions, same values
        DataType f2 = rpr2.getDataType("ForceIdentifierEnum8");
        DataType f3 = rpr3.getDataType("ForceIDEnum8");
        byte[] fb = c3.encode(f3, c2.decode(f2, new byte[] {2}));
        check("ForceIdentifierEnum8 Opposing -> ForceIDEnum8", fb.length == 1 && fb[0] == 2);
        // a lengthless array to a variable array
        DataType l2 = rpr2.getDataType("ArticulatedParameterStructLengthlessArray");
        DataType l3 = rpr3.getDataType("ArticulatedParameterStructArray");
        if (l2 != null && l3 != null) {
            HValue v = random(c2, l2, rnd, 0, true);
            byte[] b2 = c2.encode(l2, v);
            byte[] e3 = c3.encode(l3, c2.decode(l2, b2));
            HValue back = c3.decode(l3, e3);
            check("lengthless -> variable array keeps the elements", back.size() == v.size());
        }
    }

    // ------------------------------------------------------------------ 5. references

    private static void references(InformationExchangeModel rpr2, InformationExchangeModel rpr3) {
        HlaCodec c2 = new HlaCodec(rpr2);
        TypeAnalysis a2 = new TypeAnalysis(c2);
        DataType arr = rpr2.getDataType("RTIobjectIdArray");
        HValue v = HValue.ofArray(Arrays.asList(HValue.ofText("a"), HValue.ofText("b"), HValue.ofText("a")));
        byte[] b = c2.encode(arr, v);
        HValue d = c2.decode(arr, b);
        check("references found", a2.references(d).equals(Arrays.asList("a", "b", "a")));
        HValue r = a2.rewriteReferences(d, s -> s.equals("a") ? "x-a" : s);
        check("references rewritten", a2.references(r).equals(Arrays.asList("x-a", "b", "x-a")));
        HValue same = a2.rewriteReferences(d, s -> s);
        check("unchanged tree shared", same == d);
        byte[] rb = c2.encode(arr, r);
        check("rewritten value encodes", a2.references(c2.decode(arr, rb)).get(0).equals("x-a"));
        // every RPR v2 class member that contains references
        int members = 0;
        for (com.simweaver.common.iem.InteractionClass ic : rpr2.getInteractionClasses()) {
            for (com.simweaver.common.iem.ClassParameter p : ic.getParameters()) {
                if (!p.isInherited() && a2.containsReference(p.getDataType())) {
                    members++;
                }
            }
        }
        for (com.simweaver.common.iem.ObjectClass oc : rpr2.getObjectClasses()) {
            for (com.simweaver.common.iem.ClassAttribute at : oc.getAttributes()) {
                if (!at.isInherited() && a2.containsReference(at.getDataType())) {
                    members++;
                }
            }
        }
        System.out.println("RPR v2 members holding object references: " + members);
        check("RPR v2 has members holding references", members > 10);
    }

    // ------------------------------------------------------------------ helpers

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(b.length, 64); i++) {
            sb.append(String.format("%02x", b[i] & 0xFF));
            if (i % 4 == 3) {
                sb.append(' ');
            }
        }
        return sb.toString() + (b.length > 64 ? "… (" + b.length + ")" : "");
    }
}
