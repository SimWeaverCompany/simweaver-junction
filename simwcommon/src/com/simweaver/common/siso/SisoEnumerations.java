package com.simweaver.common.siso;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SISO-REF-010, the DIS / RPR enumerations (0.11.1): read from the XML that SISO publishes
 * ({@code etc/enumerations/SISO-REF-010.xml}), for naming entity types.
 *
 * <p>{@link #describe} turns the seven numbers of an entity type (kind, domain, country, category,
 * subcategory, specific, extra) into words, as deep as the file knows them: the Cross-Reference
 * Entity Types table (CET, uid 30) gives kind/domain/country → category → subcategory → specific
 * → extra; where it has no entry, the generic tables give at least the kind, the domain and the
 * category (Platform-Land Category, Munition Category, …). The country comes from the Country
 * table (uid 29), whose names end in their ISO 3166 alpha-3 code: "Sweden (SWE)".</p>
 *
 * <p>The file is read with StAX, without DTDs or external entities (its schema and stylesheets
 * are never fetched). Results are cached per entity type.</p>
 */
public final class SisoEnumerations {

    /** The levels of an entity type, in order. */
    public static final String[] LEVELS = {"kind", "domain", "country", "category", "subcategory", "specific", "extra"};

    private static final Pattern ISO3 = Pattern.compile("^(.*\\S)\\s*\\(([A-Z]{3})\\)\\s*$");
    private static final int CET_ENTITY_TYPES = 30;
    private static final int KIND = 7;
    private static final int PLATFORM_DOMAIN = 8;
    private static final int MUNITION_DOMAIN = 14;
    private static final int COUNTRY = 29;

    /** One level of the CET tree: its name, and the entries below it. */
    static final class Node {
        final String description;
        final Map<Integer, Node> children = new HashMap<>(4);
        List<Range> ranges;
        /** subcategory_xref: the subcategory values are the rows of this enum table */
        int xref;

        Node(String description) {
            this.description = description;
        }

        Node child(int value) {
            Node n = children.get(value);
            if (n == null && ranges != null) {
                for (Range r : ranges) {
                    if (value >= r.min && value <= r.max) {
                        return r.node;
                    }
                }
            }
            return n;
        }
    }

    static final class Range {
        final int min;
        final int max;
        final Node node;

        Range(int min, int max, Node node) {
            this.min = min;
            this.max = max;
            this.node = node;
        }
    }

    /** What the file says about one entity type. */
    public static final class EntityTypeInfo {
        private final String text;
        private final String context;
        private final String countryCode;
        private final String countryName;
        private final List<String[]> path;
        private final String matched;
        private final boolean complete;

        EntityTypeInfo(String text, String context, String countryCode, String countryName, List<String[]> path,
                       String matched, boolean complete) {
            this.text = text;
            this.context = context;
            this.countryCode = countryCode;
            this.countryName = countryName;
            this.path = path;
            this.matched = matched;
            this.complete = complete;
        }

        /** @return the most specific name known, e.g. "CV9040A"; or "Land platform" */
        public String text() {
            return text;
        }

        /** @return the broader name that goes with it (the category, or the domain and kind), or null */
        public String context() {
            return context;
        }

        /** @return the ISO 3166 alpha-3 code of the country (e.g. "SWE"), or null */
        public String countryCode() {
            return countryCode;
        }

        /** @return the country's name (e.g. "Sweden"), or null for 0 (Other) or an unknown country */
        public String countryName() {
            return countryName;
        }

        /** @return {level, value, name} for each level the file names, in order */
        public List<String[]> path() {
            return path;
        }

        /** @return the deepest level the file named: kind … extra */
        public String matched() {
            return matched;
        }

        /** @return true when every non-zero level was found */
        public boolean complete() {
            return complete;
        }
    }

    private final String title;
    private final String date;
    private final Map<Integer, Map<Integer, String>> enums;
    private final Map<Long, Node> entities;
    private final int entityCount;
    private final Map<Long, EntityTypeInfo> cache = new ConcurrentHashMap<>();

    private SisoEnumerations(String title, String date, Map<Integer, Map<Integer, String>> enums, Map<Long, Node> entities, int count) {
        this.title = title;
        this.date = date;
        this.enums = enums;
        this.entities = entities;
        this.entityCount = count;
    }

    /** @return the file's title, e.g. "SISO-REF-010-v37" */
    public String title() {
        return title;
    }

    /** @return its date, e.g. "2026-05-25" */
    public String date() {
        return date;
    }

    /** @return the number of named entity types in the CET (every level counted) */
    public int entityTypeCount() {
        return entityCount;
    }

    /** @return a row of an enumeration table, or null */
    public String enumName(int table, int value) {
        Map<Integer, String> t = enums.get(table);
        return t == null ? null : t.get(value);
    }

    // ------------------------------------------------------------------ reading

    /** Reads the file. */
    public static SisoEnumerations load(Path file) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            return read(in);
        }
    }

    /** Reads SISO-REF-010 XML. */
    public static SisoEnumerations read(InputStream in) throws IOException {
        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        f.setProperty(XMLInputFactory.IS_COALESCING, true);
        String title = null;
        String date = null;
        Map<Integer, Map<Integer, String>> enums = new HashMap<>();
        Map<Long, Node> entities = new HashMap<>();
        int count = 0;
        try {
            XMLStreamReader r = f.createXMLStreamReader(in);
            Map<Integer, String> enumTable = null;     // inside <enum>
            boolean inCet = false;
            Deque<Node> stack = new ArrayDeque<>();     // inside <cet uid=30>: entity, category, ...
            int skipDepth = 0;                          // an element of the CET we do not follow
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String name = r.getLocalName();
                    if (skipDepth > 0) {
                        skipDepth++;
                        continue;
                    }
                    switch (name) {
                        case "ebv":
                            title = r.getAttributeValue(null, "title");
                            date = r.getAttributeValue(null, "date");
                            break;
                        case "enum":
                            enumTable = enums.computeIfAbsent(intAttr(r, "uid", -1), k -> new HashMap<>());
                            break;
                        case "enumrow":
                            if (enumTable != null) {
                                int v = intAttr(r, "value", -1);
                                String d = r.getAttributeValue(null, "description");
                                if (v >= 0 && d != null) {
                                    enumTable.putIfAbsent(v, d);
                                }
                            }
                            break;
                        case "cet":
                            inCet = intAttr(r, "uid", -1) == CET_ENTITY_TYPES;
                            break;
                        case "entity":
                            if (inCet) {
                                stack.push(entities.computeIfAbsent(key3(intAttr(r, "kind", 0), intAttr(r, "domain", 0),
                                        intAttr(r, "country", 0)), k -> new Node(null)));
                            }
                            break;
                        case "category": case "subcategory": case "specific": case "extra":
                        case "category_range": case "subcategory_range": case "specific_range": case "extra_range":
                        case "subcategory_xref":
                            if (!inCet || stack.isEmpty()) {
                                break;
                            }
                            Node parent = stack.peek();
                            Node n = new Node(r.getAttributeValue(null, "description"));
                            count++;
                            if (name.equals("subcategory_xref")) {
                                parent.xref = intAttr(r, "xref", 0);
                            } else if (name.endsWith("_range")) {
                                if (parent.ranges == null) {
                                    parent.ranges = new ArrayList<>(2);
                                }
                                parent.ranges.add(new Range(intAttr(r, "value_min", 0), intAttr(r, "value_max", -1), n));
                            } else {
                                parent.children.putIfAbsent(intAttr(r, "value", -1), n);
                            }
                            stack.push(n);
                            break;
                        default:
                            if (inCet && !stack.isEmpty()) {
                                skipDepth = 1;                  // cr, cr_range, description: nothing for us
                            }
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    if (skipDepth > 0) {
                        skipDepth--;
                        continue;
                    }
                    switch (r.getLocalName()) {
                        case "enum":
                            enumTable = null;
                            break;
                        case "cet":
                            inCet = false;
                            stack.clear();
                            break;
                        case "entity": case "category": case "subcategory": case "specific": case "extra":
                        case "category_range": case "subcategory_range": case "specific_range": case "extra_range":
                        case "subcategory_xref":
                            if (inCet && !stack.isEmpty()) {
                                stack.pop();
                            }
                            break;
                        default:
                    }
                }
            }
            r.close();
        } catch (XMLStreamException e) {
            throw new IOException("not readable as SISO-REF-010 XML: " + e.getMessage(), e);
        }
        if (entities.isEmpty() || !enums.containsKey(COUNTRY)) {
            throw new IOException("no Entity Types (CET uid 30) or Country table (uid 29): not a SISO-REF-010 file?");
        }
        return new SisoEnumerations(title, date, enums, entities, count);
    }

    private static int intAttr(XMLStreamReader r, String name, int def) {
        String v = r.getAttributeValue(null, name);
        if (v == null) {
            return def;
        }
        try {
            long l = Long.parseLong(v.trim());
            return l > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) l;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long key3(int kind, int domain, int country) {
        return ((long) (kind & 0xFF) << 24) | ((long) (domain & 0xFF) << 16) | (country & 0xFFFF);
    }

    // ------------------------------------------------------------------ naming

    /**
     * @return what the file says about this entity type; never null (at least the kind, when the
     *         file knows it; text "" when it knows nothing)
     */
    public EntityTypeInfo describe(int kind, int domain, int country, int category, int subcategory, int specific, int extra) {
        long key = ((long) (kind & 0xFF) << 56) | ((long) (domain & 0xFF) << 48) | ((long) (country & 0xFFFF) << 32)
                | ((long) (category & 0xFF) << 24) | ((long) (subcategory & 0xFF) << 16) | ((specific & 0xFF) << 8) | (extra & 0xFF);
        return cache.computeIfAbsent(key, k -> compute(kind & 0xFF, domain & 0xFF, country & 0xFFFF, category & 0xFF,
                subcategory & 0xFF, specific & 0xFF, extra & 0xFF));
    }

    private EntityTypeInfo compute(int kind, int domain, int country, int category, int subcategory, int specific, int extra) {
        if (kind == 0) {                                         // kind 0 = Other: nothing to name (e.g. an unused alternate type)
            return new EntityTypeInfo("", null, null, null, Collections.<String[]>emptyList(), "none", false);
        }
        List<String[]> path = new ArrayList<>(7);
        String kindName = enumName(KIND, kind);
        String domainName = enumName(kind == 2 ? MUNITION_DOMAIN : PLATFORM_DOMAIN, domain);
        String countryRow = country == 0 ? null : enumName(COUNTRY, country);      // 0 = Other: no country
        String countryName = null;
        String iso = null;
        if (countryRow != null) {
            Matcher m = ISO3.matcher(countryRow);
            if (m.matches()) {
                countryName = m.group(1);
                iso = m.group(2);
            } else {
                countryName = countryRow;
            }
        }
        add(path, "kind", kind, kindName);
        add(path, "domain", domain, domainName);
        if (country != 0) {
            add(path, "country", country, countryName);
        }
        int[] values = {category, subcategory, specific, extra};
        String[] names = new String[4];
        int known = -1;                                          // the deepest CET level named (0 = category)
        boolean missing = false;                                 // a non-zero level not in the file
        Node n = entities.get(key3(kind, domain, country));
        for (int i = 0; i < 4 && n != null; i++) {
            if (values[i] == 0) {
                break;                                           // 0 = not specified: the end of the type
            }
            Node c = n.child(values[i]);
            if (c == null && i == 1 && n.xref > 0) {             // subcategory_xref: the values are another table's rows
                String d = enumName(n.xref, values[i]);
                if (d != null) {
                    names[i] = d;
                    known = i;
                }
                n = null;
                break;
            }
            if (c == null) {
                missing = true;
                n = null;
                break;
            }
            names[i] = c.description;
            known = i;
            n = c;
        }
        if (known < 0 && category != 0) {                        // not in the CET: the generic category table
            String generic = genericCategory(kind, domain, category);
            if (generic != null) {
                names[0] = generic;
                known = 0;
                missing = subcategory != 0;
            } else {
                missing = true;
            }
        }
        for (int i = 0; i <= known; i++) {
            add(path, LEVELS[3 + i], values[i], names[i]);
        }
        if (!missing) {                                          // a non-zero level below the deepest named one
            for (int i = known + 1; i < 4; i++) {
                if (values[i] != 0) {
                    missing = true;
                    break;
                }
            }
        }
        boolean complete = !missing && kindName != null && (domain == 0 || domainName != null) && (country == 0 || countryRow != null);
        String broad = broad(kind, domain, kindName, domainName);
        String text;
        String context;
        if (known >= 1) {
            text = names[known];
            if (known == 3 && names[2] != null) {                // an extra is a variant of its specific
                text = names[2] + " – " + names[3];
            }
            context = names[0];
        } else if (known == 0) {
            text = names[0];
            context = broad;
        } else {
            text = broad == null ? "" : broad;
            context = null;
        }
        String matched = kindName == null ? "none" : known >= 0 ? LEVELS[3 + known] : country != 0 && countryRow != null ? "country"
                : domainName != null && domain != 0 ? "domain" : "kind";
        return new EntityTypeInfo(text, context, iso, countryName, Collections.unmodifiableList(path), matched, complete);
    }

    /** "Land platform", "Anti-Armor munition", "Life form" … */
    private static String broad(int kind, int domain, String kindName, String domainName) {
        if (kindName == null) {
            return null;
        }
        String k = kindName.toLowerCase(Locale.ROOT);
        if (kind == 2 && domainName != null && domain != 0) {
            return domainName + " " + k;
        }
        if (domainName == null || domain == 0 || kind == 0) {
            return kindName;
        }
        return domainName + " " + k;
    }

    private String genericCategory(int kind, int domain, int category) {
        int table;
        switch (kind) {
            case 1:
                table = domain >= 1 && domain <= 5 ? 8 + domain : 0;          // Platform-Land … -Space Category (9-13)
                break;
            case 2:
                table = 15;                                                   // Munition Category
                break;
            case 7:
                table = 22;                                                   // Radio Category
                break;
            case 8:
                table = domain == 2 ? 25 : domain == 3 ? 26 : domain == 4 ? 27 : 0;   // Expendable-Air/-Surface/-Subsurface
                break;
            case 9:
                table = 28;                                                   // Sensor/Emitter Category
                break;
            default:
                table = 0;
        }
        return table == 0 ? null : enumName(table, category);
    }

    private static void add(List<String[]> path, String level, int value, String name) {
        if (name != null) {
            path.add(new String[] {level, String.valueOf(value), name});
        }
    }

    // ------------------------------------------------------------------ the server's copy

    private static volatile SisoEnumerations global;
    private static volatile String globalProblem = "not loaded";

    /** @return the server's enumerations, or null when the file is missing or could not be read */
    public static SisoEnumerations global() {
        return global;
    }

    /** @return why {@link #global()} is null, or null when it is loaded */
    public static String globalProblem() {
        return globalProblem;
    }

    /** Sets the server's enumerations (null with the reason when none). */
    public static void setGlobal(SisoEnumerations e, String problem) {
        global = e;
        globalProblem = e == null ? problem : null;
    }

}
