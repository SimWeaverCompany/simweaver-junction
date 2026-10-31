package com.simweaver.junction.router;

import com.simweaver.common.hla.codec.TranscodeRules;
import com.simweaver.common.iem.ArrayDataType;
import com.simweaver.common.iem.DataType;
import com.simweaver.common.iem.EnumeratedDataType;
import com.simweaver.common.iem.Enumerator;
import com.simweaver.common.iem.FixedRecordDataType;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.common.iem.RecordField;
import com.simweaver.common.iem.VariantAlternative;
import com.simweaver.common.iem.VariantRecordDataType;
import com.simweaver.junction.config.ClassFilter;
import com.simweaver.junction.router.convert.RprConversions;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reviews {@code rpr2-rpr3.mapping}: compiles the routes RPR v2.0 -> v3.0 and back with it and
 * lists what is not mapped: classes without a counterpart, dropped attributes and parameters,
 * and, inside the transcoded datatypes, record fields without a source, source fields that
 * are lost, and enumerators / variant alternatives without a counterpart.
 *
 * <pre>java -cp out/classes:out/test com.simweaver.junction.router.RprMappingLint &lt;fom root&gt; &lt;mappings dir&gt; [classes]</pre>
 *
 * With {@code classes}, only the class and member level is listed.
 */
public final class RprMappingLint {

    private RprMappingLint() {
    }

    public static void main(String[] args) throws Exception {
        File fomRoot = new File(args.length > 0 ? args[0] : "simwrift/rtdir/etc/fom");
        File mappings = new File(args.length > 1 ? args[1] : "simwrift/rtdir/etc/junctions/mappings");
        boolean classesOnly = args.length > 2 && "classes".equals(args[2]);
        InformationExchangeModel v2 = InformationExchangeModel.fromHlaEvolved(Collections.singletonList(
                new File(fomRoot, "ieee1516-2010/RPR_FOM_v2.0_Link16_1516-2010.xml").getPath()));
        List<String> v3files = new ArrayList<String>();
        File[] fs = new File(fomRoot, "ieee1516-2010/rpr3").listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (f.getName().endsWith(".xml")) {
                    v3files.add(f.getPath());
                }
            }
        }
        Collections.sort(v3files);
        InformationExchangeModel v3 = InformationExchangeModel.fromHlaEvolved(v3files);
        RouteCompiler.REPORT_CAP = 100000;
        RprConversions conv = new RprConversions(mappings);
        lint("RPR v2.0 -> v3.0", v2, v3, conv, classesOnly);
        lint("RPR v3.0 -> v2.0", v3, v2, conv, classesOnly);
    }

    private static void lint(String title, InformationExchangeModel s, InformationExchangeModel d, RprConversions conv, boolean classesOnly) {
        ConversionRules rules = conv.rulesFor(s, d);
        RouteCompiler rc = new RouteCompiler(ClassFilter.ALL, true, conv);
        ModelTools st = new ModelTools(s, Collections.singletonList("RTIobjectId"));
        ModelTools dt = new ModelTools(d, Collections.singletonList("RTIobjectId"));
        RouteTable t = rc.compile(new RouteCompiler.Side("s", st, null, null), new RouteCompiler.Side("d", dt, null, null));
        System.out.println("################ " + title + " (" + rules.describe() + ")");
        RouteCompiler.Report rep = t.report();
        System.out.println("routes: " + rep.objectRoutes + " object classes, " + rep.interactionRoutes + " interaction classes; members pass "
                + rep.passMembers + ", rewrite " + rep.rewriteMembers + ", transcode " + rep.transcodeMembers + ", dropped " + rep.droppedCount);
        System.out.println("---- no counterpart (" + rep.noCounterpartCount + ")");
        for (String n : rep.noCounterpart) {
            System.out.println("  " + n);
        }
        System.out.println("---- promoted (" + rep.promoted.size() + ")");
        for (String n : rep.promoted) {
            System.out.println("  " + n);
        }
        System.out.println("---- dropped members (" + rep.droppedCount + ")");
        for (Map.Entry<String, String> e : rep.droppedMembers.entrySet()) {
            System.out.println("  " + e.getKey() + ": " + e.getValue());
        }
        if (classesOnly) {
            return;
        }
        // types of the transcoded members
        Set<String> seen = new HashSet<String>();
        Set<String> out = new TreeSet<String>();
        Map<String, String> transcoded = rep.transcodedMembers;
        for (Map.Entry<String, String> e : transcoded.entrySet()) {
            String[] p = String.valueOf(e.getValue()).split(" -> ");
            DataType a = s.getDataType(p[0]);
            DataType b = d.getDataType(p[1]);
            if (a != null && b != null) {
                walk(a, b, rules.transcodeRules(), seen, out, e.getKey());
            }
        }
        System.out.println("---- inside transcoded datatypes (" + out.size() + ")");
        for (String o : out) {
            System.out.println(o);
        }
    }

    private static void walk(DataType a, DataType b, TranscodeRules r, Set<String> seen, Set<String> out, String where) {
        if (!seen.add(a.getName() + ">" + b.getName())) {
            return;
        }
        if (r.hasAdapter(a.getName(), b.getName())) {
            out.add("adapter " + a.getName() + " -> " + b.getName() + ": converted in code   [" + where + "]");
            return;
        }
        if (a instanceof FixedRecordDataType && b instanceof FixedRecordDataType) {
            FixedRecordDataType ra = (FixedRecordDataType) a;
            FixedRecordDataType rb = (FixedRecordDataType) b;
            Set<String> used = new HashSet<String>();
            for (RecordField f : rb.getFields()) {
                String src = r.sourceField(rb.getName(), f.getName());
                RecordField sf = field(ra, src != null ? src : f.getName(), src == null);
                if (sf == null) {
                    if (!r.isDefaulted(rb.getName(), f.getName()) && !f.getName().startsWith("Padding")) {
                        out.add("field   " + ra.getName() + " -> " + rb.getName() + "." + f.getName() + ": no source field   [" + where + "]");
                    }
                    continue;
                }
                used.add(sf.getName());
                if (sf.getDataType() != null && f.getDataType() != null) {
                    walk(sf.getDataType(), f.getDataType(), r, seen, out, where);
                }
            }
            for (RecordField f : ra.getFields()) {
                if (!used.contains(f.getName()) && !f.getName().startsWith("Padding")) {
                    out.add("lost    " + ra.getName() + "." + f.getName() + " -> " + rb.getName() + ": not written   [" + where + "]");
                }
            }
        } else if (a instanceof VariantRecordDataType && b instanceof VariantRecordDataType) {
            VariantRecordDataType va = (VariantRecordDataType) a;
            VariantRecordDataType vb = (VariantRecordDataType) b;
            EnumeratedDataType da = va.getDiscriminantType();
            EnumeratedDataType db = vb.getDiscriminantType();
            for (VariantAlternative alt : va.getAlternatives()) {
                for (Enumerator en : alt.getEnumerators()) {
                    Long mapped = mapEnum(da, db, en, r);
                    VariantAlternative to = mapped == null ? null : vb.getAlternativeForValue(mapped);
                    if (to == null) {
                        out.add("variant " + va.getName() + "." + en.getName() + " -> " + vb.getName() + ": no alternative   [" + where + "]");
                        continue;
                    }
                    if (alt.getDataType() != null && to.getDataType() != null) {
                        walk(alt.getDataType(), to.getDataType(), r, seen, out, where);
                    } else if (alt.getDataType() != null && to.isEmpty()) {
                        out.add("variant " + va.getName() + "." + alt.getName() + " -> " + vb.getName() + "." + to.getName() + ": alternative empty   [" + where + "]");
                    }
                }
            }
        } else if (a instanceof EnumeratedDataType && b instanceof EnumeratedDataType) {
            EnumeratedDataType ea = (EnumeratedDataType) a;
            EnumeratedDataType eb = (EnumeratedDataType) b;
            if (eb.getEnumerators().isEmpty()) {
                return;
            }
            List<String> miss = new ArrayList<String>();
            for (Enumerator en : ea.getEnumerators()) {
                if (mapEnum(ea, eb, en, r) == null) {
                    miss.add(en.getName() + "=" + en.getValue());
                }
            }
            if (!miss.isEmpty()) {
                out.add("enum    " + ea.getName() + " -> " + eb.getName() + ": " + miss.size() + " of " + ea.getEnumerators().size()
                        + " without counterpart " + (miss.size() > 8 ? miss.subList(0, 8) + "..." : miss) + "   [" + where + "]");
            }
        } else if (a instanceof ArrayDataType && b instanceof ArrayDataType) {
            DataType ea = ((ArrayDataType) a).getElementType();
            DataType eb = ((ArrayDataType) b).getElementType();
            if (ea != null && eb != null) {
                walk(ea, eb, r, seen, out, where);
            }
        } else if (a.getCategory() != b.getCategory()) {
            boolean numeric = isNumber(a) && isNumber(b);
            if (!numeric) {
                out.add("kind    " + a.getName() + " (" + a.getCategory() + ") -> " + b.getName() + " (" + b.getCategory() + ")   [" + where + "]");
            }
        }
    }

    private static boolean isNumber(DataType t) {
        switch (t.getCategory()) {
            case BASIC:
            case SIMPLE:
            case ENUMERATED:
                return true;
            default:
                return false;
        }
    }

    private static RecordField field(FixedRecordDataType r, String name, boolean ignoreCase) {
        for (RecordField f : r.getFields()) {
            if (f.getName().equals(name) || ignoreCase && f.getName().equalsIgnoreCase(name)) {
                return f;
            }
        }
        return null;
    }

    private static Long mapEnum(EnumeratedDataType a, EnumeratedDataType b, Enumerator en, TranscodeRules r) {
        if (a == b) {
            return en.getValue();
        }
        Long m = r.enumValue(a.getName(), b.getName(), en.getValue());
        if (m != null) {
            return m;
        }
        Enumerator byName = b.getEnumerator(en.getName());
        if (byName != null) {
            return byName.getValue();
        }
        return b.getEnumeratorByValue(en.getValue()) != null ? en.getValue() : null;
    }
}
