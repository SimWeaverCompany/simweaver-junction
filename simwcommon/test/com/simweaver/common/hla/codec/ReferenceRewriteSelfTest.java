package com.simweaver.common.hla.codec;

import com.simweaver.common.iem.Attribute;
import com.simweaver.common.iem.DataType;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.common.iem.InteractionClass;
import com.simweaver.common.iem.ObjectClass;
import com.simweaver.common.iem.Parameter;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 0.14.0: object instance names (RTIobjectId) are found and rewritten in <b>every</b> attribute and
 * parameter that can hold one, in RPR v2 (1516-2010 and 1516-2025), RPR v3 and NETN v4: simple
 * references, arrays of them, records, records in arrays, and variant records (the RPR v3
 * VariableParameterVariantStruct and DamageDescriptionVariantStruct, also in arrays). For each
 * member a value with a distinct name in every reference place is encoded, decoded, its names
 * listed, rewritten, encoded and decoded again, and rewritten back to the original octets.
 * The gateway rewrites with the same {@link TypeAnalysis} (MemberRoute), so this covers what a
 * renamed copy's references become in other federations.
 *
 * <pre>java -cp out/classes:out/test com.simweaver.common.hla.codec.ReferenceRewriteSelfTest [fom root]</pre>
 */
public final class ReferenceRewriteSelfTest {

    private static int checks;
    private static int failures;

    private ReferenceRewriteSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        File root = new File(args.length > 0 ? args[0] : "simwrift/rtdir/etc/fom").getAbsoluteFile();
        File e = new File(root, "ieee1516-2010");
        model("RPR v2 (1516-2010)", InformationExchangeModel.fromHlaEvolved(Collections.singletonList(new File(e, "RPR_FOM_v2.0_Link16_1516-2010.xml").getPath())),
                56, Collections.<String>emptyList());
        model("RPR v2 (1516-2025)", InformationExchangeModel.fromHla4(Collections.singletonList(
                new File(root, "ieee1516-2025/RPR_FOM_v2.0_Link16_1516-2025.xml").getPath())), 56, Collections.<String>emptyList());
        List<String> v3 = files(new File(e, "rpr3"));
        model("RPR v3", InformationExchangeModel.fromHlaEvolved(v3), 75, Arrays.asList(
                "BaseEntity.PhysicalEntity.VariableParametersArray", "BaseEntity.PhysicalEntity.ExtendedDamageStatus",
                "Detonation.VariableParametersArray"));
        List<String> netn = new ArrayList<>();
        netn.add(new File(e, "RPR_FOM_v2.0_Link16_1516-2010.xml").getPath());
        for (String n : new String[] {"NETN-BASE", "NETN-ENTITY", "NETN-ORG", "NETN-LOG", "NETN-SMC", "NETN-TMR",
                "NETN-MRM", "NETN-ETR", "NETN-COM", "NETN-CBRN", "NETN-METOC", "NETN-AIS", "NETN-SE", "NETN-DIM"}) {
            File f = new File(e, "netn4-hla3/" + n + ".xml");
            if (f.isFile()) {
                netn.add(f.getPath());
            }
        }
        model("RPR v2 + NETN v4", InformationExchangeModel.fromHlaEvolved(netn), 57, Collections.<String>emptyList());
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    /**
     * @param atLeast  members with references expected at least
     * @param variants members whose references lie inside a variant record (checked by name)
     */
    private static void model(String label, InformationExchangeModel m, int atLeast, List<String> variants) {
        HlaCodec codec = new HlaCodec(m);
        TypeAnalysis ta = new TypeAnalysis(codec);
        Map<String, Integer> byPath = new TreeMap<>();
        int members = 0;
        int bad = 0;
        List<String> viaVariant = new ArrayList<>();
        for (ObjectClass c : m.getObjectClasses()) {
            for (Attribute a : c.getDeclaredAttributes()) {
                if (ta.containsReference(a.getDataType())) {
                    members++;
                    String name = c.getQualifiedNameWithoutRoot() + "." + a.getName();
                    bad += member(label, name, a.getDataType(), codec, ta, byPath, viaVariant) ? 0 : 1;
                }
            }
        }
        for (InteractionClass c : m.getInteractionClasses()) {
            for (Parameter p : c.getDeclaredParameters()) {
                if (ta.containsReference(p.getDataType())) {
                    members++;
                    String name = c.getQualifiedNameWithoutRoot() + "." + p.getName();
                    bad += member(label, name, p.getDataType(), codec, ta, byPath, viaVariant) ? 0 : 1;
                }
            }
        }
        check(label + ": " + members + " attributes and parameters hold references, every one rewritten both ways " + byPath,
                members >= atLeast && bad == 0);
        check(label + ": the variant record cases " + viaVariant, viaVariant.containsAll(variants));
    }

    private static boolean member(String label, String name, DataType t, HlaCodec codec, TypeAnalysis ta,
                                  Map<String, Integer> byPath, List<String> viaVariant) {
        List<String> path = RefSamples.path(ta, t);
        String kind = path.isEmpty() ? "simple" : String.join(">", new java.util.LinkedHashSet<>(path));
        byPath.merge(kind, 1, Integer::sum);
        if (path.contains("VARIANT")) {
            viaVariant.add(name);
        }
        int[] n = {0};
        List<String> put = new ArrayList<>();
        HValue v = new RefSamples(codec, ta, () -> {
            String s = "Obj-" + (++n[0]);
            put.add(s);
            return s;
        }).sample(t);
        try {
            byte[] octets = codec.encode(t, v);
            HValue d = codec.decode(t, octets);
            List<String> found = ta.references(d);
            if (!found.equals(put)) {
                System.out.println("  " + label + " " + name + ": put " + put + ", found " + found);
                return false;
            }
            HValue renamed = ta.rewriteReferences(d, s -> "Copy-" + s);
            HValue d2 = codec.decode(t, codec.encode(t, renamed));
            List<String> after = ta.references(d2);
            List<String> want = new ArrayList<>();
            for (String s : put) {
                want.add("Copy-" + s);
            }
            if (!after.equals(want)) {
                System.out.println("  " + label + " " + name + ": rewritten " + after + ", wanted " + want);
                return false;
            }
            byte[] back = codec.encode(t, ta.rewriteReferences(d2, s -> s.startsWith("Copy-") ? s.substring(5) : s));
            if (!Arrays.equals(back, octets)) {
                System.out.println("  " + label + " " + name + ": rewriting back does not give the original octets");
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            System.out.println("  " + label + " " + name + ": " + e);
            return false;
        }
    }

    private static List<String> files(File dir) {
        List<String> l = new ArrayList<>();
        File[] fs = dir.listFiles((d, n) -> n.endsWith(".xml"));
        if (fs != null) {
            Arrays.sort(fs);
            for (File f : fs) {
                l.add(f.getPath());
            }
        }
        return l;
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (ok) {
            System.out.println("  ok   " + what);
        } else {
            failures++;
            System.out.println("  FAIL " + what);
        }
    }
}
