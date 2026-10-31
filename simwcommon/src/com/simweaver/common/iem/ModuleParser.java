package com.simweaver.common.iem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses one OMT DIF document (IEEE 1516.2-2010 or -2025 XML) into a raw, unmerged module.
 * <p>
 * The parser is deliberately tolerant about elements it does not know (they are kept as
 * "extensions" or generic properties) and strict about what the merge depends on: names,
 * class structure, the XML namespace and the datatype tables.
 */
final class ModuleParser {

    // ------------------------------------------------------------------ raw structures

    /** One parsed FOM module, before merging. */
    static final class RawModule {
        int index;
        String label;
        String location;
        HlaVersion version;
        boolean standardMim;
        final Map<String, String> identification = new LinkedHashMap<String, String>();
        final List<String> dependencies = new ArrayList<String>();
        RawClass objectRoot;
        RawClass interactionRoot;
        final List<RawDataType> dataTypes = new ArrayList<RawDataType>();
        final List<RawEntry> dimensions = new ArrayList<RawEntry>();
        final List<RawEntry> transportations = new ArrayList<RawEntry>();
        final List<RawEntry> updateRates = new ArrayList<RawEntry>();
        final List<RawEntry> synchronizationPoints = new ArrayList<RawEntry>();
        final Map<String, RawEntry> switches = new LinkedHashMap<String, RawEntry>();
        final Map<String, RawEntry> time = new LinkedHashMap<String, RawEntry>();
        final Map<String, RawEntry> tags = new LinkedHashMap<String, RawEntry>();
        final Map<String, String> notes = new LinkedHashMap<String, String>();

        String name() {
            return identification.get("name");
        }

        String where(int line) {
            return line > 0 ? label + ":" + line : label;
        }
    }

    /** One object or interaction class as it appears in one module. */
    static final class RawClass {
        String name;
        int line;
        RawClass parent;
        String qualifiedName;
        /** Class level properties with non-empty text: sharing, semantics, transportation, order, ... */
        final Map<String, String> props = new LinkedHashMap<String, String>();
        /** Null when no {@code <dimensions>} element is present. */
        List<String> dimensions;
        final List<RawMember> members = new ArrayList<RawMember>();
        final List<RawClass> children = new ArrayList<RawClass>();
        final List<XmlNode> extensions = new ArrayList<XmlNode>();

        /** True if this definition only positions the class in the tree (name and sub-classes only). */
        boolean isScaffolding() {
            return members.isEmpty() && dimensions == null && extensions.isEmpty() && contentProps().isEmpty();
        }

        /** Properties except semantics (documentation). */
        Map<String, String> contentProps() {
            Map<String, String> m = new LinkedHashMap<String, String>(props);
            m.remove("semantics");
            return m;
        }
    }

    /** One attribute or parameter as it appears in one module. */
    static final class RawMember {
        String name;
        int line;
        /** dataType, updateType, updateCondition, ownership, sharing, transportation, order, semantics, valueRequired, ... */
        final Map<String, String> props = new LinkedHashMap<String, String>();
        /** Null when no {@code <dimensions>} element is present. */
        List<String> dimensions;
        final List<XmlNode> extensions = new ArrayList<XmlNode>();
    }

    /** Generic named table entry (dimension, transportation, update rate, sync point, switch, tag, time). */
    static final class RawEntry {
        String name;
        int line;
        final Map<String, String> props = new LinkedHashMap<String, String>();
        /** XML attributes of the element itself (used by switches). */
        final Map<String, String> xmlAttributes = new LinkedHashMap<String, String>();
    }

    static final class RawEnumerator {
        String name;
        int line;
        final List<String> values = new ArrayList<String>();
    }

    static final class RawField {
        String name;
        String dataType;
        String semantics;
        int line;
    }

    static final class RawAlternative {
        String enumeratorSpec;
        String name;
        String dataType;
        String semantics;
        int line;
    }

    /** One datatype definition as it appears in one module. */
    static final class RawDataType {
        DataTypeCategory category;
        String tableElement;
        String entryElement;
        String name;
        int line;
        String semantics;
        String encoding;
        // basic
        String size;
        String interpretation;
        String endian;
        // simple / enumerated
        String representation;
        String units;
        String resolution;
        String accuracy;
        final List<RawEnumerator> enumerators = new ArrayList<RawEnumerator>();
        // array
        String elementType;
        String cardinality;
        // fixed record
        final List<RawField> fields = new ArrayList<RawField>();
        // variant record
        String discriminant;
        String discriminantType;
        final List<RawAlternative> alternatives = new ArrayList<RawAlternative>();
        // other
        final Map<String, String> otherProps = new LinkedHashMap<String, String>();
    }

    // ------------------------------------------------------------------ parsing

    private static final String[] OBJECT_CLASS_PROPS = {"sharing", "semantics"};
    private static final String[] INTERACTION_CLASS_PROPS = {"sharing", "transportation", "order", "semantics"};

    private ModuleParser() {
    }

    /**
     * Parses a loaded document.
     *
     * @param loaded   the XML tree
     * @param index    position of the module in the merge order
     * @param expected HLA version the caller asked for
     */
    static RawModule parse(XmlLoader.Loaded loaded, int index, HlaVersion expected) throws IemException {
        XmlNode root = loaded.root;
        RawModule m = new RawModule();
        m.index = index;
        m.label = loaded.label;
        m.location = loaded.location;

        if (!"objectModel".equals(root.getName())) {
            throw new IemException(loaded.label + ":" + root.getLine() + ": root element is <" + root.getName()
                    + ">, expected <objectModel>; this is not an HLA FOM/SOM module");
        }
        m.version = detectVersion(root, loaded.label, expected);
        List<String> problems = new ArrayList<String>();

        parseIdentification(root.child("modelIdentification"), m);

        XmlNode objects = root.child("objects");
        if (objects != null) {
            m.objectRoot = parseRootClass(objects, "objectClass", "HLAobjectRoot", m, problems);
        }
        XmlNode interactions = root.child("interactions");
        if (interactions != null) {
            m.interactionRoot = parseRootClass(interactions, "interactionClass", "HLAinteractionRoot", m, problems);
        }
        XmlNode dataTypes = root.child("dataTypes");
        if (dataTypes != null) {
            parseDataTypes(dataTypes, m, problems);
        }
        parseEntries(root.child("dimensions"), "dimension", "name", m.dimensions, m, problems);
        parseEntries(root.child("transportations"), "transportation", "name", m.transportations, m, problems);
        parseEntries(root.child("updateRates"), "updateRate", "name", m.updateRates, m, problems);
        parseEntries(root.child("synchronizations"), "synchronizationPoint", "label", m.synchronizationPoints, m,
                problems);
        parseKeyedEntries(root.child("switches"), m.switches);
        parseKeyedEntries(root.child("time"), m.time);
        parseKeyedEntries(root.child("tags"), m.tags);
        XmlNode notes = root.child("notes");
        if (notes != null) {
            for (XmlNode n : notes.children("note")) {
                String label = n.childTextOrNull("label");
                if (label != null) {
                    m.notes.put(label, n.childText("semantics") == null ? "" : n.childText("semantics"));
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new IemException("Invalid FOM module " + loaded.label, problems);
        }
        return m;
    }

    private static HlaVersion detectVersion(XmlNode root, String label, HlaVersion expected) throws IemException {
        String ns = root.getNamespace();
        if (ns.isEmpty()) {
            // IEEE 1516-2000 DIF used attributes (DTDversion, name="..") instead of child elements.
            if (root.getAttribute("DTDversion") != null || looksLike2000(root)) {
                throw new IemException(label + ": this is an IEEE 1516-2000 (DTD based) FOM; only IEEE 1516-2010"
                        + " (HLA Evolved) and IEEE 1516-2025 (HLA 4) DIF documents are supported");
            }
            return null; // no namespace: accepted as the expected version, warned about by the caller
        }
        HlaVersion v = HlaVersion.fromNamespace(ns);
        if (v == null) {
            throw new IemException(label + ": unsupported OMT namespace '" + ns + "'; expected '"
                    + expected.getXmlNamespace() + "'");
        }
        if (v != expected) {
            throw new IemException(label + ": module is " + v.getStandard() + " (" + friendly(v)
                    + ") but the FOM list is being loaded as " + expected.getStandard() + " (" + friendly(expected)
                    + "); mixing HLA Evolved and HLA 4 modules is not supported");
        }
        return v;
    }

    static String friendly(HlaVersion v) {
        return v == HlaVersion.HLA_4 ? "HLA 4" : "HLA Evolved";
    }

    private static boolean looksLike2000(XmlNode root) {
        XmlNode objects = root.child("objects");
        if (objects != null) {
            XmlNode oc = objects.child("objectClass");
            if (oc != null && oc.getAttribute("name") != null) {
                return true;
            }
        }
        XmlNode interactions = root.child("interactions");
        if (interactions != null) {
            XmlNode ic = interactions.child("interactionClass");
            return ic != null && ic.getAttribute("name") != null;
        }
        return false;
    }

    private static void parseIdentification(XmlNode id, RawModule m) {
        if (id == null) {
            return;
        }
        for (XmlNode c : id.getChildren()) {
            if ("reference".equals(c.getName())) {
                String type = c.childTextOrNull("type");
                String ident = c.childTextOrNull("identification");
                if (type != null && ident != null && "dependency".equalsIgnoreCase(type)) {
                    m.dependencies.add(ident.replaceAll("\\s+", " "));
                }
            } else if (!c.hasChildren() && !m.identification.containsKey(c.getName())) {
                m.identification.put(c.getName(), c.getText());
            }
        }
    }

    // ------------------------------------------------------------------ classes

    private static RawClass parseRootClass(XmlNode container, String classElement, String rootName, RawModule m,
            List<String> problems) {
        List<XmlNode> tops = container.children(classElement);
        RawClass root = null;
        for (XmlNode top : tops) {
            RawClass c = parseClass(top, classElement, null, m, problems);
            if (c == null) {
                continue;
            }
            if (!rootName.equals(c.name)) {
                problems.add(m.where(top.getLine()) + ": top level " + classElement + " is '" + c.name
                        + "'; the only allowed root is " + rootName);
                continue;
            }
            if (root != null) {
                problems.add(m.where(top.getLine()) + ": " + rootName + " is defined more than once in the module");
                continue;
            }
            root = c;
        }
        for (XmlNode other : container.getChildren()) {
            if (!classElement.equals(other.getName())) {
                problems.add(m.where(other.getLine()) + ": unexpected element <" + other.getName() + "> in <"
                        + container.getName() + ">");
            }
        }
        return root;
    }

    private static RawClass parseClass(XmlNode node, String classElement, RawClass parent, RawModule m,
            List<String> problems) {
        RawClass c = new RawClass();
        c.line = node.getLine();
        c.parent = parent;
        c.name = node.childTextOrNull("name");
        if (c.name == null) {
            problems.add(m.where(node.getLine()) + ": " + classElement + " without a name");
            return null;
        }
        String nameProblem = checkName(c.name);
        if (nameProblem != null) {
            problems.add(m.where(node.getLine()) + ": " + classElement + " name '" + c.name + "' " + nameProblem);
            return null;
        }
        c.qualifiedName = parent == null ? c.name : parent.qualifiedName + "." + c.name;
        boolean isObject = "objectClass".equals(classElement);
        String memberElement = isObject ? "attribute" : "parameter";
        String[] propNames = isObject ? OBJECT_CLASS_PROPS : INTERACTION_CLASS_PROPS;

        for (XmlNode ch : node.getChildren()) {
            String n = ch.getName();
            if ("name".equals(n)) {
                continue;
            }
            if (n.equals(classElement)) {
                RawClass sub = parseClass(ch, classElement, c, m, problems);
                if (sub != null) {
                    for (RawClass existing : c.children) {
                        if (existing.name.equals(sub.name)) {
                            problems.add(m.where(ch.getLine()) + ": class '" + sub.qualifiedName
                                    + "' is defined twice in the same module");
                        }
                    }
                    c.children.add(sub);
                }
            } else if (n.equals(memberElement)) {
                RawMember mem = parseMember(ch, memberElement, c, m, problems);
                if (mem != null) {
                    for (RawMember existing : c.members) {
                        if (existing.name.equals(mem.name)) {
                            problems.add(m.where(ch.getLine()) + ": " + memberElement + " '" + mem.name
                                    + "' is declared twice in class '" + c.qualifiedName + "'");
                        }
                    }
                    c.members.add(mem);
                }
            } else if ("dimensions".equals(n)) {
                c.dimensions = dimensionList(ch);
            } else if (contains(propNames, n) && !ch.hasChildren()) {
                String t = ch.getText();
                if (!t.isEmpty()) {
                    c.props.put(n, t);
                }
            } else {
                c.extensions.add(ch);
            }
        }
        return c;
    }

    private static RawMember parseMember(XmlNode node, String memberElement, RawClass owner, RawModule m,
            List<String> problems) {
        RawMember mem = new RawMember();
        mem.line = node.getLine();
        mem.name = node.childTextOrNull("name");
        if (mem.name == null) {
            problems.add(m.where(node.getLine()) + ": " + memberElement + " without a name in class '"
                    + owner.qualifiedName + "'");
            return null;
        }
        String nameProblem = checkName(mem.name);
        if (nameProblem != null) {
            problems.add(m.where(node.getLine()) + ": " + memberElement + " name '" + mem.name + "' " + nameProblem);
            return null;
        }
        for (XmlNode ch : node.getChildren()) {
            String n = ch.getName();
            if ("name".equals(n)) {
                continue;
            }
            if ("dimensions".equals(n)) {
                mem.dimensions = dimensionList(ch);
            } else if (!ch.hasChildren()) {
                String t = ch.getText();
                if (!t.isEmpty()) {
                    mem.props.put(n, t);
                }
            } else {
                mem.extensions.add(ch);
            }
        }
        return mem;
    }

    private static List<String> dimensionList(XmlNode dims) {
        List<String> out = new ArrayList<String>();
        for (XmlNode d : dims.children("dimension")) {
            String t = d.getText();
            if (!t.isEmpty() && !"NA".equals(t)) {
                out.add(t);
            }
        }
        return out;
    }

    /** Returns a problem description, or null if the name is acceptable. */
    static String checkName(String name) {
        if (name.indexOf('.') >= 0) {
            return "contains '.', which is the hierarchy separator";
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isWhitespace(name.charAt(i))) {
                return "contains whitespace";
            }
        }
        return null;
    }

    private static boolean contains(String[] arr, String s) {
        for (String a : arr) {
            if (a.equals(s)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ datatypes

    private static void parseDataTypes(XmlNode dataTypes, RawModule m, List<String> problems) {
        for (XmlNode table : dataTypes.getChildren()) {
            DataTypeCategory cat = DataTypeCategory.fromTableElement(table.getName());
            for (XmlNode entry : table.getChildren()) {
                if (cat != DataTypeCategory.OTHER && !cat.getEntryElement().equals(entry.getName())) {
                    problems.add(m.where(entry.getLine()) + ": unexpected <" + entry.getName() + "> in <"
                            + table.getName() + ">");
                    continue;
                }
                RawDataType dt = new RawDataType();
                dt.category = cat;
                dt.tableElement = table.getName();
                dt.entryElement = entry.getName();
                dt.line = entry.getLine();
                dt.name = entry.childTextOrNull("name");
                if (dt.name == null) {
                    problems.add(m.where(entry.getLine()) + ": <" + entry.getName() + "> without a name");
                    continue;
                }
                String nameProblem = checkName(dt.name);
                if (nameProblem != null) {
                    problems.add(m.where(entry.getLine()) + ": datatype name '" + dt.name + "' " + nameProblem);
                    continue;
                }
                if ("NA".equals(dt.name)) {
                    problems.add(m.where(entry.getLine()) + ": 'NA' is reserved and cannot name a datatype");
                    continue;
                }
                dt.semantics = entry.childText("semantics");
                dt.encoding = entry.childTextOrNull("encoding");
                switch (cat) {
                    case BASIC:
                        dt.size = entry.childTextOrNull("size");
                        dt.interpretation = entry.childText("interpretation");
                        dt.endian = entry.childTextOrNull("endian");
                        break;
                    case SIMPLE:
                        dt.representation = entry.childTextOrNull("representation");
                        dt.units = entry.childText("units");
                        dt.resolution = entry.childText("resolution");
                        dt.accuracy = entry.childText("accuracy");
                        break;
                    case ENUMERATED:
                        dt.representation = entry.childTextOrNull("representation");
                        for (XmlNode en : entry.children("enumerator")) {
                            RawEnumerator re = new RawEnumerator();
                            re.line = en.getLine();
                            re.name = en.childTextOrNull("name");
                            if (re.name == null) {
                                problems.add(m.where(en.getLine()) + ": enumerator without a name in '" + dt.name + "'");
                                continue;
                            }
                            for (XmlNode v : en.children("value")) {
                                if (!v.getText().isEmpty()) {
                                    re.values.add(v.getText());
                                }
                            }
                            if (re.values.isEmpty()) {
                                problems.add(m.where(en.getLine()) + ": enumerator '" + re.name + "' of '" + dt.name
                                        + "' has no value");
                                continue;
                            }
                            dt.enumerators.add(re);
                        }
                        break;
                    case ARRAY:
                        dt.elementType = entry.childTextOrNull("dataType");
                        dt.cardinality = entry.childTextOrNull("cardinality");
                        break;
                    case FIXED_RECORD:
                        for (XmlNode f : entry.children("field")) {
                            RawField rf = new RawField();
                            rf.line = f.getLine();
                            rf.name = f.childTextOrNull("name");
                            rf.dataType = f.childTextOrNull("dataType");
                            rf.semantics = f.childText("semantics");
                            if (rf.name == null) {
                                problems.add(m.where(f.getLine()) + ": field without a name in '" + dt.name + "'");
                                continue;
                            }
                            dt.fields.add(rf);
                        }
                        break;
                    case VARIANT_RECORD:
                        dt.discriminant = entry.childTextOrNull("discriminant");
                        dt.discriminantType = entry.childTextOrNull("dataType");
                        for (XmlNode a : entry.children("alternative")) {
                            RawAlternative ra = new RawAlternative();
                            ra.line = a.getLine();
                            ra.enumeratorSpec = a.childTextOrNull("enumerator");
                            ra.name = a.childTextOrNull("name");
                            ra.dataType = a.childTextOrNull("dataType");
                            ra.semantics = a.childText("semantics");
                            if (ra.enumeratorSpec == null) {
                                problems.add(m.where(a.getLine()) + ": alternative without enumerator in '" + dt.name
                                        + "'");
                                continue;
                            }
                            dt.alternatives.add(ra);
                        }
                        break;
                    default:
                        for (XmlNode c : entry.getChildren()) {
                            if (!c.hasChildren() && !"name".equals(c.getName())) {
                                dt.otherProps.put(c.getName(), c.getText());
                            }
                        }
                        break;
                }
                m.dataTypes.add(dt);
            }
        }
    }

    // ------------------------------------------------------------------ misc tables

    private static void parseEntries(XmlNode table, String entryElement, String keyElement, List<RawEntry> out,
            RawModule m, List<String> problems) {
        if (table == null) {
            return;
        }
        for (XmlNode e : table.children(entryElement)) {
            RawEntry re = new RawEntry();
            re.line = e.getLine();
            re.name = e.childTextOrNull(keyElement);
            if (re.name == null) {
                problems.add(m.where(e.getLine()) + ": <" + entryElement + "> without " + keyElement);
                continue;
            }
            for (XmlNode c : e.getChildren()) {
                if (!c.hasChildren() && !keyElement.equals(c.getName())) {
                    re.props.put(c.getName(), c.getText());
                }
            }
            re.xmlAttributes.putAll(e.getAttributes());
            out.add(re);
        }
    }

    /** Tables whose entries are keyed by element name (switches, time, tags). */
    private static void parseKeyedEntries(XmlNode table, Map<String, RawEntry> out) {
        if (table == null) {
            return;
        }
        for (XmlNode e : table.getChildren()) {
            RawEntry re = new RawEntry();
            re.name = e.getName();
            re.line = e.getLine();
            for (XmlNode c : e.getChildren()) {
                if (!c.hasChildren()) {
                    re.props.put(c.getName(), c.getText());
                }
            }
            for (Map.Entry<String, String> a : e.getAttributes().entrySet()) {
                // drop xmlns / notes noise
                if (!"notes".equals(a.getKey()) && !"noteReferences".equals(a.getKey())) {
                    re.xmlAttributes.put(a.getKey(), a.getValue());
                }
            }
            out.put(re.name, re);
        }
    }
}
