package com.simweaver.rift.api;

import com.simweaver.common.siso.SisoEnumerations;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 0.11.1: value nodes of an EntityTypeStruct get "entitytype" (SISO-REF-010 names) in the JSON:
 * full and list nodes, other records untouched, kind 0 and no enumerations file: nothing.
 *
 * <pre>java -cp out/classes:out/test com.simweaver.rift.api.EntityTypeJsonSelfTest [SISO-REF-010.xml]</pre>
 */
public final class EntityTypeJsonSelfTest {

    private static int checks;
    private static int failures;

    private EntityTypeJsonSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        RiftJson json = new RiftJson(null, null);
        ValueNode cv90 = entityType(1, 1, 205, 2, 1, 5, 0);
        SisoEnumerations.setGlobal(null, "test");
        check("without the file: no entitytype", !json.node(cv90, true).containsKey("entitytype"));

        SisoEnumerations.setGlobal(SisoEnumerations.load(Paths.get(args.length > 0 ? args[0]
                : "simwrift/rtdir/enumerations/SISO-REF-010.xml")), null);
        Map<String, Object> full = json.node(cv90, true);
        @SuppressWarnings("unchecked")
        Map<String, Object> et = (Map<String, Object>) full.get("entitytype");
        check("full node: " + et, et != null && "CV9040A".equals(et.get("text")) && "SWE".equals(et.get("country"))
                && "Sweden".equals(et.get("countryname")) && Boolean.TRUE.equals(et.get("complete"))
                && "specific".equals(et.get("matched")) && String.valueOf(et.get("source")).startsWith("SISO-REF-010")
                && et.get("path") instanceof List && ((List<?>) et.get("path")).size() == 6);
        @SuppressWarnings("unchecked")
        Map<String, Object> list = (Map<String, Object>) json.node(cv90, false).get("entitytype");
        check("list node: the same, without the path", list != null && "CV9040A".equals(list.get("text")) && !list.containsKey("path"));
        check("the numbers stay as they were", "1.1.205.2.1.5.0".equals(full.get("text")));
        check("kind 0 (an unused alternate type): nothing", !json.node(entityType(0, 0, 0, 0, 0, 0, 0), true).containsKey("entitytype"));
        List<ValueNode.Named> f = new ArrayList<>(((Rec) cv90).fields);
        f.set(6, new ValueNode.Named("Nomenclature", num(0)));
        check("another record with other fields: nothing", !json.node(new Rec("RadioTypeStruct", f), true).containsKey("entitytype"));
        check("a record with fewer fields: nothing", !json.node(new Rec("X", f.subList(0, 3)), true).containsKey("entitytype"));
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    static ValueNode entityType(int... v) {
        String[] names = {"EntityKind", "Domain", "CountryCode", "Category", "Subcategory", "Specific", "Extra"};
        List<ValueNode.Named> f = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            f.add(new ValueNode.Named(names[i], num(v[i])));
        }
        return new Rec("EntityTypeStruct", f);
    }

    static ValueNode num(long v) {
        return new ValueNode() {
            @Override public String kind() { return "INTEGER"; }
            @Override public String text() { return String.valueOf(v); }
            @Override public Object scalar() { return v; }
        };
    }

    static final class Rec implements ValueNode {
        final String type;
        final List<Named> fields;

        Rec(String type, List<Named> fields) {
            this.type = type;
            this.fields = fields;
        }

        @Override public String kind() { return "RECORD"; }
        @Override public String text() {
            StringBuilder b = new StringBuilder();
            for (Named n : fields) b.append(b.length() == 0 ? "" : ".").append(n.node.text());
            return b.toString();
        }
        @Override public Object scalar() { return null; }
        @Override public String type() { return type; }
        @Override public List<Named> fields() { return fields; }
    }

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }
}
