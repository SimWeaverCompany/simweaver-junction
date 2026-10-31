package com.simweaver.common.iem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.simweaver.common.iem.ModuleParser.RawAlternative;
import com.simweaver.common.iem.ModuleParser.RawClass;
import com.simweaver.common.iem.ModuleParser.RawDataType;
import com.simweaver.common.iem.ModuleParser.RawEntry;
import com.simweaver.common.iem.ModuleParser.RawEnumerator;
import com.simweaver.common.iem.ModuleParser.RawField;
import com.simweaver.common.iem.ModuleParser.RawMember;
import com.simweaver.common.iem.ModuleParser.RawModule;

/**
 * Loads, parses and merges FOM modules and builds the immutable element graph.
 * <p>
 * Phases:
 * <ol>
 * <li>load + parse every module (MIM first); all broken modules are reported together</li>
 * <li>merge modules in order into intermediate nodes, checking equivalence of repeated definitions</li>
 * <li>create elements and allocate ids (deterministic order, contiguous range per kind)</li>
 * <li>resolve every reference (datatypes, transportations, dimensions, variant enumerators)</li>
 * <li>validate the merged result (inherited name clashes, infinitely recursive datatypes, ...)</li>
 * <li>pre-compute inherited member views and lookup tables</li>
 * </ol>
 * Any error makes the build throw one {@link IemException} listing all problems found in that phase.
 */
final class ModelBuilder {

    // ------------------------------------------------------------------ intermediate nodes

    /** Merged class (object or interaction) before element creation. */
    private static final class ClassNode {
        final String name;
        final String qualifiedName;
        final ClassNode parent;
        final Map<String, ClassNode> children = new LinkedHashMap<String, ClassNode>();
        final Map<String, MemberNode> members = new LinkedHashMap<String, MemberNode>();
        final Map<String, String> props = new LinkedHashMap<String, String>();
        final Map<String, FomModule> propSource = new HashMap<String, FomModule>();
        String semantics;
        final Set<FomModule> modules = new LinkedHashSet<FomModule>();
        final Set<FomModule> definingModules = new LinkedHashSet<FomModule>();
        /** Module that first contributed members (for strict merging). */
        FomModule memberModule;
        final Map<String, String> dimensions = new LinkedHashMap<String, String>(); // name -> where
        final Map<String, String> otherProps = new LinkedHashMap<String, String>();
        final Set<String> unknownElements = new LinkedHashSet<String>();
        final Map<String, String> directed = new LinkedHashMap<String, String>(); // name -> where
        HlaClass<?> element;

        ClassNode(String name, ClassNode parent) {
            this.name = name;
            this.parent = parent;
            this.qualifiedName = parent == null ? name : parent.qualifiedName + "." + name;
        }
    }

    private static final class MemberNode {
        final RawMember first;
        final String firstWhere;
        final List<FomModule> modules = new ArrayList<FomModule>();
        String semantics;
        ClassMember element;

        MemberNode(RawMember first, String firstWhere) {
            this.first = first;
            this.firstWhere = firstWhere;
        }
    }

    private static final class EnumNode {
        final String name;
        final long[] values;
        final FomModule module;
        final String where;

        EnumNode(String name, long[] values, FomModule module, String where) {
            this.name = name;
            this.values = values;
            this.module = module;
            this.where = where;
        }
    }

    private static final class AltNode {
        final RawAlternative raw;
        final FomModule module;
        final String where;
        /** Enumerator items, possibly unioned from several modules. */
        final Set<String> specItems = new LinkedHashSet<String>();

        AltNode(RawAlternative raw, FomModule module, String where) {
            this.raw = raw;
            this.module = module;
            this.where = where;
            specItems.addAll(splitSpec(raw.enumeratorSpec));
        }

        String spec() {
            StringBuilder sb = new StringBuilder();
            for (String s : specItems) {
                sb.append(sb.length() == 0 ? "" : ", ").append(s);
            }
            return sb.toString();
        }
    }

    private static final class DtNode {
        final RawDataType first;
        final String firstWhere;
        final List<FomModule> modules = new ArrayList<FomModule>();
        String semantics;
        final Map<String, EnumNode> enumerators = new LinkedHashMap<String, EnumNode>();
        final Map<String, AltNode> alternatives = new LinkedHashMap<String, AltNode>();
        DataType element;

        DtNode(RawDataType first, String firstWhere) {
            this.first = first;
            this.firstWhere = firstWhere;
        }
    }

    private static final class EntryNode {
        final RawEntry first;
        final String firstWhere;
        final List<FomModule> modules = new ArrayList<FomModule>();
        IemElement element;

        EntryNode(RawEntry first, String firstWhere) {
            this.first = first;
            this.firstWhere = firstWhere;
        }
    }

    /** Everything the model needs, handed to the {@link InformationExchangeModel} constructor. */
    static final class Result {
        HlaVersion version;
        List<FomModule> modules;
        IemElement[] elements;          // index = id
        int[] kindStart;                // first id per ElementKind ordinal
        int[] kindCount;
        ObjectClass objectRoot;
        InteractionClass interactionRoot;
        List<DataType> dataTypes;
        List<ObjectClass> objectClasses;
        List<Attribute> attributes;
        List<InteractionClass> interactionClasses;
        List<Parameter> parameters;
        List<Dimension> dimensions;
        List<Transportation> transportations;
        List<UpdateRate> updateRates;
        List<SynchronizationPoint> synchronizationPoints;
        Map<String, DataType> dataTypesByName;
        Map<String, ObjectClass> objectClassesByName;
        Map<String, InteractionClass> interactionClassesByName;
        Map<String, List<ObjectClass>> objectClassesBySimpleName;
        Map<String, List<InteractionClass>> interactionClassesBySimpleName;
        Map<String, Attribute> attributesByName;
        Map<String, Parameter> parametersByName;
        Map<String, Dimension> dimensionsByName;
        Map<String, Transportation> transportationsByName;
        Map<String, UpdateRate> updateRatesByName;
        Map<String, SynchronizationPoint> synchronizationPointsByName;
        Map<String, String> switches;
        Map<String, String> timeDataTypes;
        Map<String, String> tagDataTypes;
        List<String> warnings;
    }

    // ------------------------------------------------------------------ state

    private final HlaVersion version;
    private final IemOptions options;
    private final List<String> errors = new ArrayList<String>();
    private final List<String> warnings = new ArrayList<String>();
    private final List<RawModule> raws = new ArrayList<RawModule>();
    private final List<FomModule> modules = new ArrayList<FomModule>();

    private ClassNode objectRoot;
    private ClassNode interactionRoot;
    private final Map<String, DtNode> dataTypes = new LinkedHashMap<String, DtNode>();
    private final Map<String, EntryNode> dimensions = new LinkedHashMap<String, EntryNode>();
    private final Map<String, EntryNode> transportations = new LinkedHashMap<String, EntryNode>();
    private final Map<String, EntryNode> updateRates = new LinkedHashMap<String, EntryNode>();
    private final Map<String, EntryNode> syncPoints = new LinkedHashMap<String, EntryNode>();
    private final Map<String, String> switches = new LinkedHashMap<String, String>();
    private final Map<String, String> switchWhere = new HashMap<String, String>();
    private final Map<String, String> timeTypes = new LinkedHashMap<String, String>();
    private final Map<String, String> timeWhere = new HashMap<String, String>();
    private final Map<String, String> tagTypes = new LinkedHashMap<String, String>();
    private final Map<String, String> tagWhere = new HashMap<String, String>();

    // elements under construction
    private final List<IemElement> all = new ArrayList<IemElement>();
    private final int[] kindStart = new int[ElementKind.values().length];
    private final int[] kindCount = new int[ElementKind.values().length];
    private final Map<String, DataType> dtByName = new HashMap<String, DataType>();
    private final Map<String, Transportation> trByName = new HashMap<String, Transportation>();
    private final Map<String, Dimension> dimByName = new HashMap<String, Dimension>();
    private final Map<String, InteractionClass> icByName = new HashMap<String, InteractionClass>();

    private ModelBuilder(HlaVersion version, IemOptions options) {
        this.version = version;
        this.options = new IemOptions(options);
    }

    static Result build(HlaVersion version, List<String> foms, IemOptions options) throws IemException {
        if (foms == null) {
            throw new IemException("FOM list is null");
        }
        if (foms.isEmpty()) {
            throw new IemException("FOM list is empty; at least one FOM module is required");
        }
        ModelBuilder b = new ModelBuilder(version, options == null ? new IemOptions() : options);
        return b.run(foms);
    }

    private Result run(List<String> foms) throws IemException {
        loadAll(foms);
        familyChecks();
        for (RawModule rm : raws) {
            merge(rm, modules.get(rm.index));
        }
        failIfErrors("FOM merge failed");
        createElements();
        resolve();
        validate();
        failIfErrors("FOM model is inconsistent");
        computeViews();
        if (options.isWarningsAsErrors() && !warnings.isEmpty()) {
            throw new IemException("FOM merge produced warnings and warningsAsErrors is set", warnings);
        }
        return result();
    }

    private void failIfErrors(String headline) throws IemException {
        if (errors.isEmpty()) {
            return;
        }
        Set<FomFamily> fams = families();
        if (fams.contains(FomFamily.NETN) && fams.contains(FomFamily.RPR_FOM_V3)) {
            errors.add("hint: NETN v4 modules are built on RPR FOM v2 and redefine some types that RPR FOM v3"
                    + " changed; combining NETN v4 with RPR FOM v3 is expected to conflict");
        }
        if (fams.contains(FomFamily.RPR_FOM_V2) && fams.contains(FomFamily.RPR_FOM_V3)) {
            errors.add("hint: RPR FOM v2 and RPR FOM v3 modules are both loaded; they are alternatives, not"
                    + " complements");
        }
        throw new IemException(headline, errors);
    }

    // ------------------------------------------------------------------ phase 1: load

    private void loadAll(List<String> foms) throws IemException {
        List<String> problems = new ArrayList<String>();
        List<Object[]> specs = new ArrayList<Object[]>(); // {spec, isMim, isBuiltIn, userIndex}
        if (options.isIncludeStandardMim()) {
            if (options.getStandardMim() != null) {
                specs.add(new Object[] {options.getStandardMim(), Boolean.TRUE, Boolean.FALSE, -1});
            } else {
                specs.add(new Object[] {StandardMim.xml(version), Boolean.TRUE, Boolean.TRUE, -1});
            }
        }
        for (int i = 0; i < foms.size(); i++) {
            specs.add(new Object[] {foms.get(i), Boolean.FALSE, Boolean.FALSE, i});
        }
        for (Object[] s : specs) {
            String spec = (String) s[0];
            boolean mim = (Boolean) s[1];
            boolean builtIn = (Boolean) s[2];
            int userIndex = (Integer) s[3];
            try {
                XmlLoader.Loaded loaded = XmlLoader.load(spec, userIndex < 0 ? 0 : userIndex);
                if (builtIn) {
                    loaded = new XmlLoader.Loaded(StandardMim.LABEL, null, loaded.root);
                } else if (mim && loaded.location == null) {
                    loaded = new XmlLoader.Loaded("<supplied MIM>", null, loaded.root);
                }
                int index = raws.size();
                RawModule rm = ModuleParser.parse(loaded, index, version);
                rm.standardMim = mim;
                if (rm.version == null) {
                    warnings.add(rm.label + ": no OMT XML namespace; assumed " + version.getStandard() + " ("
                            + ModuleParser.friendly(version) + ")");
                }
                raws.add(rm);
                modules.add(new FomModule(index, rm.label, rm.location, mim, builtIn, rm.version != null,
                        rm.identification, rm.dependencies, rm.notes));
            } catch (IemException e) {
                problems.addAll(e.getProblems());
            }
        }
        if (!problems.isEmpty()) {
            throw new IemException("Loading FOM modules failed", problems);
        }
        // Label inline modules with their identification name for better messages
        for (int i = 0; i < raws.size(); i++) {
            RawModule rm = raws.get(i);
            if (rm.location == null && !rm.standardMim && rm.name() != null && !rm.name().isEmpty()) {
                rm.label = rm.label.substring(0, rm.label.length() - 1) + " " + rm.name() + ">";
                FomModule old = modules.get(i);
                modules.set(i, new FomModule(i, rm.label, null, false, false, old.isNamespaceDeclared(),
                        rm.identification, rm.dependencies, rm.notes));
            }
        }
    }

    // ------------------------------------------------------------------ family awareness

    private Set<FomFamily> families() {
        Set<FomFamily> s = new LinkedHashSet<FomFamily>();
        for (FomModule m : modules) {
            s.add(m.getFamily());
        }
        return s;
    }

    private void familyChecks() {
        Set<FomFamily> fams = families();
        if (fams.contains(FomFamily.RPR_FOM_V2) && fams.contains(FomFamily.RPR_FOM_V3)) {
            warnings.add("both RPR FOM v2 and RPR FOM v3 modules are loaded; they are alternative versions");
        }
        Map<String, Integer> seen = new HashMap<String, Integer>();
        for (FomModule m : modules) {
            if (m.getName() != null) {
                String key = m.getName() + "|" + m.getVersion();
                Integer prev = seen.put(key, m.getIndex());
                if (prev != null) {
                    warnings.add(m.getLabel() + ": module '" + m.getName() + "' version " + m.getVersion()
                            + " is loaded twice (also " + modules.get(prev).getLabel() + ")");
                }
            }
        }
        for (FomModule m : modules) {
            for (String dep : m.getDependencies()) {
                if (!dependencySatisfied(dep)) {
                    warnings.add(m.getLabel() + ": declared dependency '" + dep
                            + "' does not match any loaded module");
                }
            }
        }
    }

    private boolean dependencySatisfied(String dep) {
        String d = normalise(dep);
        if (d.isEmpty()) {
            return true;
        }
        if (d.equals("mim") || d.contains("standardmim") || d.contains("management object model")) {
            for (FomModule m : modules) {
                if (m.isStandardMim()) {
                    return true;
                }
            }
            return false;
        }
        for (FomModule m : modules) {
            String n = normalise(m.getName());
            if (n.isEmpty()) {
                continue;
            }
            if (n.equals(d) || n.contains(d) || d.contains(n)) {
                return true;
            }
            boolean rpr = m.getFamily() == FomFamily.RPR_FOM_V2 || m.getFamily() == FomFamily.RPR_FOM_V3;
            if (rpr && d.startsWith("rpr-")) {
                String part = d.substring(4);
                String longPart = RPR_ABBREVIATIONS.get(part);
                if (n.contains("reference " + part + " ") || n.endsWith("reference " + part)
                        || (longPart != null && n.contains("reference " + longPart))
                        || n.contains("reference fom")) {
                    return true; // modular match, or the monolithic RPR FOM which contains every part
                }
            }
        }
        return false;
    }

    /** RPR module short names used in NETN dependency references. */
    private static final Map<String, String> RPR_ABBREVIATIONS = new HashMap<String, String>();

    static {
        RPR_ABBREVIATIONS.put("se", "synthetic environment");
        RPR_ABBREVIATIONS.put("ua", "underwater acoustics");
        RPR_ABBREVIATIONS.put("der", "distributed emission regeneration");
        RPR_ABBREVIATIONS.put("siman", "simulation management");
        RPR_ABBREVIATIONS.put("io", "information operations");
        RPR_ABBREVIATIONS.put("enum", "enumerations");
    }

    private static String normalise(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        t = t.replaceAll("[_ ]v?\\d+(\\.\\d+)*$", ""); // drop trailing version like _v2.0
        return t;
    }

    // ------------------------------------------------------------------ phase 2: merge

    private void merge(RawModule rm, FomModule mod) {
        if (rm.objectRoot != null) {
            if (objectRoot == null) {
                objectRoot = new ClassNode(rm.objectRoot.name, null);
            }
            mergeClass(objectRoot, rm.objectRoot, mod, rm, true);
        }
        if (rm.interactionRoot != null) {
            if (interactionRoot == null) {
                interactionRoot = new ClassNode(rm.interactionRoot.name, null);
            }
            mergeClass(interactionRoot, rm.interactionRoot, mod, rm, false);
        }
        for (RawDataType d : rm.dataTypes) {
            mergeDataType(d, mod, rm);
        }
        for (RawEntry e : rm.dimensions) {
            mergeEntry(dimensions, e, mod, rm, "dimension", new String[] {"dataType", "upperBound", "normalization",
                "value"}, true);
        }
        for (RawEntry e : rm.transportations) {
            mergeEntry(transportations, e, mod, rm, "transportation", new String[] {"reliable"}, true);
        }
        for (RawEntry e : rm.updateRates) {
            mergeEntry(updateRates, e, mod, rm, "update rate", new String[] {"rate"}, true);
        }
        for (RawEntry e : rm.synchronizationPoints) {
            mergeEntry(syncPoints, e, mod, rm, "synchronization point", new String[] {"dataType", "capability"},
                    false);
        }
        for (RawEntry e : rm.switches.values()) {
            String value = e.xmlAttributes.isEmpty() ? (e.props.isEmpty() ? "" : e.props.values().iterator().next())
                    : e.xmlAttributes.values().iterator().next();
            firstWins(switches, switchWhere, e.name, value, rm.where(e.line), "switch");
        }
        for (RawEntry e : rm.time.values()) {
            String dt = e.props.get("dataType");
            if (dt != null && !dt.isEmpty()) {
                firstWins(timeTypes, timeWhere, e.name, dt, rm.where(e.line), "time " + e.name + " datatype");
            }
        }
        for (RawEntry e : rm.tags.values()) {
            String dt = e.props.get("dataType");
            if (dt != null && !dt.isEmpty()) {
                firstWins(tagTypes, tagWhere, e.name, dt, rm.where(e.line), "tag " + e.name + " datatype");
            }
        }
    }

    private void firstWins(Map<String, String> target, Map<String, String> where, String key, String value,
            String here, String what) {
        String old = target.get(key);
        if (old == null) {
            target.put(key, value);
            where.put(key, here);
        } else if (!old.equalsIgnoreCase(value)) {
            warnings.add(here + ": " + what + " '" + value + "' differs from '" + old + "' (" + where.get(key)
                    + "); keeping the first");
        }
    }

    private void mergeClass(ClassNode node, RawClass raw, FomModule mod, RawModule rm, boolean isObject) {
        String kind = isObject ? "object class" : "interaction class";
        String memberKind = isObject ? "attribute" : "parameter";
        String here = rm.where(raw.line);
        node.modules.add(mod);
        if (!raw.isScaffolding()) {
            node.definingModules.add(mod);
        }
        // class level properties
        for (Map.Entry<String, String> p : raw.props.entrySet()) {
            String k = p.getKey();
            String v = p.getValue();
            if ("semantics".equals(k)) {
                if (node.semantics == null) {
                    node.semantics = v;
                }
                continue;
            }
            String old = node.props.get(k);
            if (old == null) {
                node.props.put(k, v);
                node.propSource.put(k, mod);
            } else if (!old.equalsIgnoreCase(v)) {
                // The MIM is authoritative for the root classes; FOM modules (e.g. NETN-BASE) often restate
                // root properties differently, which has no effect, so that is not reported.
                boolean mimRoot = node.parent == null && node.propSource.get(k).isStandardMim();
                if (!mimRoot) {
                    warnings.add(here + ": " + kind + " '" + node.qualifiedName + "' " + k + " '" + v
                            + "' differs from '" + old + "' in " + node.propSource.get(k).getLabel() + "; keeping '"
                            + old + "'");
                }
            }
        }
        // dimensions
        if (raw.dimensions != null) {
            if (!options.isExtendedMerging() && !node.dimensions.isEmpty()
                    && !new HashSet<String>(raw.dimensions).equals(node.dimensions.keySet())) {
                errors.add(here + ": " + kind + " '" + node.qualifiedName
                        + "' declares different dimensions than an earlier module (extended merging disabled)");
            }
            for (String d : raw.dimensions) {
                if (!node.dimensions.containsKey(d)) {
                    node.dimensions.put(d, here);
                }
            }
        }
        // unknown elements
        for (XmlNode ext : raw.extensions) {
            if (!ext.hasChildren()) {
                if (!node.otherProps.containsKey(ext.getName())) {
                    node.otherProps.put(ext.getName(), ext.getText());
                }
            } else if (isObject && ext.getName().toLowerCase(Locale.ROOT).contains("directed")) {
                collectDirected(ext, node, rm);
            } else {
                node.unknownElements.add(ext.getName());
            }
        }
        // members
        if (!raw.members.isEmpty()) {
            if (node.memberModule == null) {
                node.memberModule = mod;
            } else if (node.memberModule != mod && !options.isExtendedMerging()) {
                Set<String> a = new TreeSet<String>(node.members.keySet());
                Set<String> b = new TreeSet<String>();
                for (RawMember m : raw.members) {
                    b.add(m.name);
                }
                if (!a.equals(b)) {
                    errors.add(here + ": " + kind + " '" + node.qualifiedName + "' declares " + memberKind + "s " + b
                            + " but " + node.memberModule.getLabel() + " declares " + a
                            + "; class extension across modules is disabled (IemOptions.setExtendedMerging)");
                }
            }
        }
        for (RawMember m : raw.members) {
            String mWhere = rm.where(m.line);
            MemberNode existing = node.members.get(m.name);
            if (existing == null) {
                MemberNode mn = new MemberNode(m, mWhere);
                mn.modules.add(mod);
                mn.semantics = m.props.get("semantics");
                node.members.put(m.name, mn);
            } else {
                String diff = memberDifference(existing.first, m, isObject);
                if (diff != null) {
                    errors.add(mWhere + ": " + memberKind + " '" + node.qualifiedName + "." + m.name
                            + "' conflicts with its definition at " + existing.firstWhere + ": " + diff);
                } else {
                    String c1 = existing.first.props.get("updateCondition");
                    String c2 = m.props.get("updateCondition");
                    if (c1 != null && c2 != null && !c1.equals(c2)) {
                        warnings.add(mWhere + ": attribute '" + node.qualifiedName + "." + m.name
                                + "' update condition differs from " + existing.firstWhere + "; keeping the first");
                    }
                    if (!existing.modules.contains(mod)) {
                        existing.modules.add(mod);
                    }
                    if (existing.semantics == null) {
                        existing.semantics = m.props.get("semantics");
                    }
                }
            }
        }
        // subclasses
        for (RawClass child : raw.children) {
            ClassNode cn = node.children.get(child.name);
            if (cn == null) {
                cn = new ClassNode(child.name, node);
                node.children.put(child.name, cn);
            }
            mergeClass(cn, child, mod, rm, isObject);
        }
    }

    private void collectDirected(XmlNode ext, ClassNode node, RawModule rm) {
        if (!ext.hasChildren()) {
            String t = ext.getText();
            if (!t.isEmpty() && !node.directed.containsKey(t)) {
                node.directed.put(t, rm.where(ext.getLine()));
            }
            return;
        }
        for (XmlNode c : ext.getChildren()) {
            if ("semantics".equals(c.getName())) {
                continue;
            }
            collectDirected(c, node, rm);
        }
    }

    private static final String[] ATTRIBUTE_KEYS = {"dataType", "updateType", "ownership", "sharing",
        "transportation", "order"};
    private static final String[] PARAMETER_KEYS = {"dataType"};

    /** Null when equivalent, else a short description of the first difference. */
    private static String memberDifference(RawMember a, RawMember b, boolean isObject) {
        for (String k : isObject ? ATTRIBUTE_KEYS : PARAMETER_KEYS) {
            String va = a.props.get(k);
            String vb = b.props.get(k);
            boolean caseSensitive = "dataType".equals(k) || "transportation".equals(k);
            if (!same(va, vb, caseSensitive)) {
                return k + " '" + vb + "' vs '" + va + "'";
            }
        }
        if (isObject) {
            String va = a.props.get("valueRequired");
            String vb = b.props.get("valueRequired");
            if (va != null && vb != null && !va.equalsIgnoreCase(vb)) {
                return "valueRequired '" + vb + "' vs '" + va + "'";
            }
            Set<String> da = a.dimensions == null ? Collections.<String>emptySet() : new HashSet<String>(a.dimensions);
            Set<String> db = b.dimensions == null ? Collections.<String>emptySet() : new HashSet<String>(b.dimensions);
            if (!da.equals(db)) {
                return "dimensions " + db + " vs " + da;
            }
        }
        return null;
    }

    private static boolean same(String a, String b, boolean caseSensitive) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return caseSensitive ? a.equals(b) : a.equalsIgnoreCase(b);
    }

    // ---------------------------------------------------------------- datatype merge

    private void mergeDataType(RawDataType d, FomModule mod, RawModule rm) {
        String here = rm.where(d.line);
        DtNode node = dataTypes.get(d.name);
        if (node == null) {
            node = new DtNode(d, here);
            node.modules.add(mod);
            node.semantics = emptyToNull(d.semantics);
            if (d.category == DataTypeCategory.ENUMERATED) {
                addEnumerators(node, d, mod, rm, true);
            } else if (d.category == DataTypeCategory.VARIANT_RECORD) {
                addAlternatives(node, d, mod, rm, true);
            }
            dataTypes.put(d.name, node);
            return;
        }
        RawDataType f = node.first;
        String prefix = here + ": datatype '" + d.name + "' conflicts with its definition at " + node.firstWhere + ": ";
        // A datatype of the built-in standard MIM restated differently (RTIs put their own copy of the MIM in the
        // FDD, e.g. HLAtoken with cardinality 0, HLAunsignedInteger8 Little endian): a warning, the standard
        // definition is kept. Between the user's own modules a conflict stays an error.
        int errorsBefore = errors.size();
        mergeDataTypeChecks(d, f, node, mod, rm, here, prefix);
        if (node.firstWhere.startsWith(StandardMim.LABEL) && errors.size() > errorsBefore) {
            List<String> moved = new ArrayList<String>(errors.subList(errorsBefore, errors.size()));
            errors.subList(errorsBefore, errors.size()).clear();
            for (String e : moved) {
                warnings.add(e + "; the standard MIM definition is kept");
            }
        }
        if (!node.modules.contains(mod)) {
            node.modules.add(mod);
        }
        if (node.semantics == null) {
            node.semantics = emptyToNull(d.semantics);
        }
    }

    private void mergeDataTypeChecks(RawDataType d, RawDataType f, DtNode node, FomModule mod, RawModule rm,
                                     String here, String prefix) {
        if (f.category != d.category) {
            errors.add(prefix + "defined as " + d.category + " here and as " + f.category + " there");
            return;
        }
        switch (d.category) {
            case BASIC:
                // the byte order of a single octet means nothing: 8-bit types match whatever their endian says
                boolean oneOctet = "8".equals(trimmed(f.size)) && "8".equals(trimmed(d.size));
                if (!same(f.size, d.size, true) || (!oneOctet && !same(f.endian, d.endian, false))) {
                    errors.add(prefix + "size/endian " + d.size + "/" + d.endian + " vs " + f.size + "/" + f.endian);
                }
                break;
            case SIMPLE:
                if (!same(f.representation, d.representation, true)) {
                    errors.add(prefix + "representation '" + d.representation + "' vs '" + f.representation + "'");
                } else if (!same(f.units, d.units, false)) {
                    warnings.add(here + ": simple datatype '" + d.name + "' units '" + d.units + "' differ from '"
                            + f.units + "' at " + node.firstWhere + "; keeping the first");
                }
                break;
            case ARRAY:
                if (!same(f.elementType, d.elementType, true)) {
                    errors.add(prefix + "element type '" + d.elementType + "' vs '" + f.elementType + "'");
                } else if (!sameCardinality(f.cardinality, d.cardinality)) {
                    errors.add(prefix + "cardinality '" + d.cardinality + "' vs '" + f.cardinality + "'");
                } else if (!same(f.encoding, d.encoding, true)) {
                    errors.add(prefix + "encoding '" + d.encoding + "' vs '" + f.encoding + "'");
                }
                break;
            case FIXED_RECORD: {
                String diff = fieldDifference(f.fields, d.fields);
                if (diff == null && !same(f.encoding, d.encoding, true)) {
                    diff = "encoding '" + d.encoding + "' vs '" + f.encoding + "'";
                }
                if (diff != null) {
                    errors.add(prefix + diff);
                }
                break;
            }
            case ENUMERATED:
                if (!same(f.representation, d.representation, true)) {
                    errors.add(prefix + "representation '" + d.representation + "' vs '" + f.representation + "'");
                    break;
                }
                addEnumerators(node, d, mod, rm, false);
                break;
            case VARIANT_RECORD:
                if (!same(f.discriminant, d.discriminant, true)) {
                    errors.add(prefix + "discriminant '" + d.discriminant + "' vs '" + f.discriminant + "'");
                } else if (!same(f.discriminantType, d.discriminantType, true)) {
                    errors.add(prefix + "discriminant type '" + d.discriminantType + "' vs '" + f.discriminantType
                            + "'");
                } else if (!same(f.encoding, d.encoding, true)) {
                    errors.add(prefix + "encoding '" + d.encoding + "' vs '" + f.encoding + "'");
                } else {
                    addAlternatives(node, d, mod, rm, false);
                }
                break;
            default:
                if (!f.otherProps.equals(d.otherProps) && !withoutSemantics(f.otherProps).equals(
                        withoutSemantics(d.otherProps))) {
                    errors.add(prefix + "definitions differ");
                }
                break;
        }
    }

    private static String trimmed(String s) {
        return s == null ? null : s.trim();
    }

    private static Map<String, String> withoutSemantics(Map<String, String> m) {
        Map<String, String> c = new HashMap<String, String>(m);
        c.remove("semantics");
        return c;
    }

    private static boolean sameCardinality(String a, String b) {
        if (same(a, b, false)) {
            return true;
        }
        try {
            return ArrayCardinality.parse(a).canonical().equals(ArrayCardinality.parse(b).canonical());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String fieldDifference(List<RawField> a, List<RawField> b) {
        if (a.size() != b.size()) {
            return b.size() + " fields vs " + a.size();
        }
        for (int i = 0; i < a.size(); i++) {
            RawField fa = a.get(i);
            RawField fb = b.get(i);
            if (!fa.name.equals(fb.name)) {
                return "field #" + (i + 1) + " is '" + fb.name + "' vs '" + fa.name + "'";
            }
            if (!same(fa.dataType, fb.dataType, true)) {
                return "field '" + fa.name + "' type '" + fb.dataType + "' vs '" + fa.dataType + "'";
            }
        }
        return null;
    }

    private void addEnumerators(DtNode node, RawDataType d, FomModule mod, RawModule rm, boolean first) {
        String here = rm.where(d.line);
        if (!first && !options.isExtendedMerging()) {
            Set<String> a = new TreeSet<String>(node.enumerators.keySet());
            Set<String> b = new TreeSet<String>();
            for (RawEnumerator e : d.enumerators) {
                b.add(e.name);
            }
            if (!a.equals(b)) {
                errors.add(here + ": enumerated datatype '" + d.name + "' has enumerators " + b + " but "
                        + node.firstWhere + " has " + a
                        + "; enumeration extension across modules is disabled (IemOptions.setExtendedMerging)");
                return;
            }
        }
        for (RawEnumerator e : d.enumerators) {
            String eWhere = rm.where(e.line);
            long[] values = new long[e.values.size()];
            boolean ok = true;
            for (int i = 0; i < values.length; i++) {
                Long v = parseLong(e.values.get(i));
                if (v == null) {
                    errors.add(eWhere + ": enumerator '" + d.name + "." + e.name + "' value '" + e.values.get(i)
                            + "' is not an integer");
                    ok = false;
                    break;
                }
                values[i] = v;
            }
            if (!ok) {
                continue;
            }
            EnumNode existing = node.enumerators.get(e.name);
            if (existing == null) {
                node.enumerators.put(e.name, new EnumNode(e.name, values, mod, eWhere));
            } else if (!sameValues(existing.values, values)) {
                errors.add(eWhere + ": enumerator '" + d.name + "." + e.name + "' has value(s) "
                        + e.values + " but " + existing.where + " gives " + valuesText(existing.values));
            }
        }
    }

    private static boolean sameValues(long[] a, long[] b) {
        Set<Long> sa = new HashSet<Long>();
        Set<Long> sb = new HashSet<Long>();
        for (long v : a) {
            sa.add(v);
        }
        for (long v : b) {
            sb.add(v);
        }
        return sa.equals(sb);
    }

    private static String valuesText(long[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            sb.append(i > 0 ? ", " : "").append(v[i]);
        }
        return sb.append(']').toString();
    }

    private void addAlternatives(DtNode node, RawDataType d, FomModule mod, RawModule rm, boolean first) {
        String here = rm.where(d.line);
        if (!first && !options.isExtendedMerging()) {
            Set<String> a = new TreeSet<String>(node.alternatives.keySet());
            Set<String> b = new TreeSet<String>();
            for (RawAlternative alt : d.alternatives) {
                b.add(altKey(alt));
            }
            if (!a.equals(b)) {
                errors.add(here + ": variant record '" + d.name + "' has alternatives " + b + " but "
                        + node.firstWhere + " has " + a
                        + "; variant extension across modules is disabled (IemOptions.setExtendedMerging)");
                return;
            }
        }
        for (RawAlternative alt : d.alternatives) {
            String aWhere = rm.where(alt.line);
            String key = altKey(alt);
            AltNode existing = node.alternatives.get(key);
            if (existing == null) {
                node.alternatives.put(key, new AltNode(alt, mod, aWhere));
            } else if (!same(normNa(existing.raw.dataType), normNa(alt.dataType), true)) {
                errors.add(aWhere + ": variant record '" + d.name + "' alternative '" + key + "' has type '"
                        + alt.dataType + "' but " + existing.where + " has '" + existing.raw.dataType + "'");
            } else if (!specSet(existing.spec()).equals(specSet(alt.enumeratorSpec))) {
                if (options.isExtendedMerging()) {
                    // extended merging: the same alternative may be selected by more enumerators
                    existing.specItems.addAll(splitSpec(alt.enumeratorSpec));
                } else {
                    errors.add(aWhere + ": variant record '" + d.name + "' alternative '" + key + "' selects '"
                            + alt.enumeratorSpec + "' but " + existing.where + " selects '" + existing.spec() + "'");
                }
            }
        }
    }

    private static String altKey(RawAlternative alt) {
        return alt.name != null && !"NA".equals(alt.name) ? alt.name : "[" + alt.enumeratorSpec + "]";
    }

    private static Set<String> specSet(String spec) {
        Set<String> s = new TreeSet<String>();
        for (String p : splitSpec(spec)) {
            s.add(p.replace(" ", ""));
        }
        return s;
    }

    /** Splits a variant enumerator spec on commas outside brackets. */
    static List<String> splitSpec(String spec) {
        List<String> out = new ArrayList<String>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < spec.length(); i++) {
            char c = spec.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            } else if (c == ',' && depth == 0) {
                addTrimmed(out, spec.substring(start, i));
                start = i + 1;
            }
        }
        addTrimmed(out, spec.substring(start));
        return out;
    }

    private static void addTrimmed(List<String> out, String s) {
        String t = s.trim();
        if (!t.isEmpty()) {
            out.add(t);
        }
    }

    private static String normNa(String s) {
        return s == null || "NA".equals(s) ? null : s;
    }

    // ---------------------------------------------------------------- simple tables

    private void mergeEntry(Map<String, EntryNode> map, RawEntry e, FomModule mod, RawModule rm, String what,
            String[] keys, boolean errorOnConflict) {
        String here = rm.where(e.line);
        EntryNode node = map.get(e.name);
        if (node == null) {
            node = new EntryNode(e, here);
            node.modules.add(mod);
            map.put(e.name, node);
            return;
        }
        for (String k : keys) {
            String a = node.first.props.get(k);
            String b = e.props.get(k);
            boolean equal = same(emptyToNull(a), emptyToNull(b), false);
            if (!equal && "rate".equals(k)) {
                Double da = parseDouble(a);
                Double db = parseDouble(b);
                equal = da != null && db != null && da.doubleValue() == db.doubleValue();
            }
            if (!equal) {
                String msg = here + ": " + what + " '" + e.name + "' " + k + " '" + b + "' differs from '" + a
                        + "' at " + node.firstWhere;
                if (errorOnConflict) {
                    errors.add(msg);
                } else {
                    warnings.add(msg + "; keeping the first");
                }
                return;
            }
        }
        if (!node.modules.contains(mod)) {
            node.modules.add(mod);
        }
    }

    // ------------------------------------------------------------------ phase 3: elements and ids

    private void beginKind(ElementKind k) {
        kindStart[k.ordinal()] = all.size() + 1;
    }

    private void register(IemElement e, ElementKind k) {
        all.add(e);
        e.id = all.size();
        e.index = kindCount[k.ordinal()]++;
    }

    private List<DataType> dtList = new ArrayList<DataType>();
    private List<ObjectClass> ocList = new ArrayList<ObjectClass>();
    private List<Attribute> attrList = new ArrayList<Attribute>();
    private List<InteractionClass> icList = new ArrayList<InteractionClass>();
    private List<Parameter> parList = new ArrayList<Parameter>();
    private List<Dimension> dimList = new ArrayList<Dimension>();
    private List<Transportation> trList = new ArrayList<Transportation>();
    private List<UpdateRate> urList = new ArrayList<UpdateRate>();
    private List<SynchronizationPoint> spList = new ArrayList<SynchronizationPoint>();
    private final List<ClassNode> ocNodes = new ArrayList<ClassNode>();
    private final List<ClassNode> icNodes = new ArrayList<ClassNode>();

    private void createElements() {
        // --- datatypes
        beginKind(ElementKind.DATA_TYPE);
        for (DtNode n : dataTypes.values()) {
            DataType dt = createDataType(n);
            n.element = dt;
            register(dt, ElementKind.DATA_TYPE);
            dtList.add(dt);
            dtByName.put(dt.getName(), dt);
        }
        // --- object classes
        if (objectRoot == null) {
            objectRoot = new ClassNode("HLAobjectRoot", null);
        }
        if (interactionRoot == null) {
            interactionRoot = new ClassNode("HLAinteractionRoot", null);
        }
        preorder(objectRoot, ocNodes);
        preorder(interactionRoot, icNodes);
        beginKind(ElementKind.OBJECT_CLASS);
        for (ClassNode n : ocNodes) {
            ObjectClass oc = new ObjectClass(n.name);
            fillClass(oc, n);
            n.element = oc;
            register(oc, ElementKind.OBJECT_CLASS);
            ocList.add(oc);
        }
        beginKind(ElementKind.ATTRIBUTE);
        for (ClassNode n : ocNodes) {
            ObjectClass oc = (ObjectClass) n.element;
            List<Attribute> declared = new ArrayList<Attribute>();
            for (Map.Entry<String, MemberNode> me : n.members.entrySet()) {
                Attribute a = new Attribute(me.getKey());
                a.declaringClass = oc;
                fillMember(a, me.getValue(), oc);
                RawMember r = me.getValue().first;
                a.updateTypeText = r.props.get("updateType");
                a.updateType = UpdateType.fromOmtName(a.updateTypeText);
                a.updateCondition = r.props.get("updateCondition");
                a.ownershipText = r.props.get("ownership");
                a.ownership = OwnershipType.fromOmtName(a.ownershipText);
                a.sharingText = r.props.get("sharing");
                a.sharing = Sharing.fromOmtName(a.sharingText);
                a.transportationName = r.props.get("transportation");
                a.orderText = r.props.get("order");
                a.order = OrderType.fromOmtName(a.orderText);
                String vr = r.props.get("valueRequired");
                a.valueRequired = vr == null ? null : Boolean.valueOf(parseBool(vr));
                checkEnum(a.updateTypeText, a.updateType, "updateType", a, me.getValue());
                checkEnum(a.ownershipText, a.ownership, "ownership", a, me.getValue());
                checkEnum(a.sharingText, a.sharing, "sharing", a, me.getValue());
                checkEnum(a.orderText, a.order, "order", a, me.getValue());
                me.getValue().element = a;
                register(a, ElementKind.ATTRIBUTE);
                attrList.add(a);
                declared.add(a);
            }
            oc.declaredAttributes = Collections.unmodifiableList(declared);
        }
        // --- interaction classes
        beginKind(ElementKind.INTERACTION_CLASS);
        for (ClassNode n : icNodes) {
            InteractionClass ic = new InteractionClass(n.name);
            fillClass(ic, n);
            ic.transportationName = n.props.get("transportation");
            ic.orderText = n.props.get("order");
            ic.order = OrderType.fromOmtName(ic.orderText);
            if (ic.orderText != null && ic.order == null) {
                warnings.add("interaction class '" + n.qualifiedName + "': unknown order '" + ic.orderText + "'");
            }
            n.element = ic;
            register(ic, ElementKind.INTERACTION_CLASS);
            icList.add(ic);
            icByName.put(n.qualifiedName, ic);
        }
        beginKind(ElementKind.PARAMETER);
        for (ClassNode n : icNodes) {
            InteractionClass ic = (InteractionClass) n.element;
            List<Parameter> declared = new ArrayList<Parameter>();
            for (Map.Entry<String, MemberNode> me : n.members.entrySet()) {
                Parameter p = new Parameter(me.getKey());
                p.declaringClass = ic;
                fillMember(p, me.getValue(), ic);
                me.getValue().element = p;
                register(p, ElementKind.PARAMETER);
                parList.add(p);
                declared.add(p);
            }
            ic.declaredParameters = Collections.unmodifiableList(declared);
        }
        // --- dimensions
        beginKind(ElementKind.DIMENSION);
        for (EntryNode n : dimensions.values()) {
            Dimension d = new Dimension(n.first.name);
            d.dataTypeName = emptyToNull(n.first.props.get("dataType"));
            d.upperBoundText = emptyToNull(n.first.props.get("upperBound"));
            if (d.upperBoundText != null) {
                Long ub = parseLong(d.upperBoundText);
                d.upperBound = ub == null ? -1 : ub;
            }
            d.normalization = emptyToNull(n.first.props.get("normalization"));
            d.value = emptyToNull(n.first.props.get("value"));
            d.modules = Collections.unmodifiableList(new ArrayList<FomModule>(n.modules));
            n.element = d;
            register(d, ElementKind.DIMENSION);
            dimList.add(d);
            dimByName.put(d.getName(), d);
        }
        // --- transportations (predefined first)
        beginKind(ElementKind.TRANSPORTATION);
        addTransportation(Transportation.HLA_RELIABLE, Boolean.TRUE, "Provide reliable delivery of data", true);
        addTransportation(Transportation.HLA_BEST_EFFORT, Boolean.FALSE, "Make an effort to deliver data", true);
        for (EntryNode n : transportations.values()) {
            String rel = n.first.props.get("reliable");
            Boolean reliable = rel == null || rel.isEmpty() ? null : Boolean.valueOf(parseBool(rel));
            Transportation predefined = trByName.get(n.first.name);
            if (predefined != null) {
                if (reliable != null && !reliable.equals(predefined.reliable)) {
                    errors.add(n.firstWhere + ": predefined transportation '" + n.first.name
                            + "' cannot be redefined with reliable=" + rel);
                }
                continue;
            }
            addTransportation(n.first.name, reliable, emptyToNull(n.first.props.get("semantics")), false);
        }
        // --- update rates
        beginKind(ElementKind.UPDATE_RATE);
        for (EntryNode n : updateRates.values()) {
            UpdateRate u = new UpdateRate(n.first.name);
            u.rateText = emptyToNull(n.first.props.get("rate"));
            Double r = parseDouble(u.rateText);
            u.rate = r == null ? Double.NaN : r;
            u.semantics = emptyToNull(n.first.props.get("semantics"));
            n.element = u;
            register(u, ElementKind.UPDATE_RATE);
            urList.add(u);
        }
        // --- synchronization points
        beginKind(ElementKind.SYNCHRONIZATION_POINT);
        for (EntryNode n : syncPoints.values()) {
            SynchronizationPoint s = new SynchronizationPoint(n.first.name);
            s.dataTypeName = emptyToNull(n.first.props.get("dataType"));
            s.capability = emptyToNull(n.first.props.get("capability"));
            s.semantics = emptyToNull(n.first.props.get("semantics"));
            n.element = s;
            register(s, ElementKind.SYNCHRONIZATION_POINT);
            spList.add(s);
        }
    }

    private void addTransportation(String name, Boolean reliable, String semantics, boolean predefined) {
        Transportation t = new Transportation(name);
        t.reliable = reliable;
        t.semantics = semantics;
        t.predefined = predefined;
        register(t, ElementKind.TRANSPORTATION);
        trList.add(t);
        trByName.put(name, t);
    }

    private void checkEnum(String text, Object parsed, String what, ClassMember m, MemberNode n) {
        if (text != null && parsed == null) {
            warnings.add(n.firstWhere + ": attribute '" + m.qualifiedName + "' has unknown " + what + " '" + text
                    + "'");
        }
    }

    private static void preorder(ClassNode n, List<ClassNode> out) {
        out.add(n);
        for (ClassNode c : n.children.values()) {
            preorder(c, out);
        }
    }

    private void fillClass(HlaClass<?> c, ClassNode n) {
        c.qualifiedName = n.qualifiedName;
        int depth = 0;
        for (ClassNode p = n.parent; p != null; p = p.parent) {
            depth++;
        }
        c.depth = depth;
        c.sharingText = n.props.get("sharing");
        c.sharing = Sharing.fromOmtName(c.sharingText);
        if (c.sharingText != null && c.sharing == null) {
            warnings.add("class '" + n.qualifiedName + "': unknown sharing '" + c.sharingText + "'");
        }
        c.semantics = emptyToNull(n.semantics);
        c.modules = Collections.unmodifiableList(new ArrayList<FomModule>(n.modules));
        c.definingModules = Collections.unmodifiableList(new ArrayList<FomModule>(n.definingModules));
        c.otherProperties = n.otherProps.isEmpty() ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(n.otherProps));
        c.unknownElements = n.unknownElements.isEmpty() ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(n.unknownElements));
    }

    private void fillMember(ClassMember m, MemberNode n, HlaClass<?> owner) {
        m.qualifiedName = owner.getQualifiedName() + "." + m.getName();
        m.dataTypeName = emptyToNull(n.first.props.get("dataType"));
        m.semantics = emptyToNull(n.semantics);
        m.modules = Collections.unmodifiableList(new ArrayList<FomModule>(n.modules));
        Map<String, String> other = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> e : n.first.props.entrySet()) {
            if (!KNOWN_MEMBER_PROPS.contains(e.getKey())) {
                other.put(e.getKey(), e.getValue());
            }
        }
        m.otherProperties = other.isEmpty() ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(other);
    }

    private static final Set<String> KNOWN_MEMBER_PROPS = new HashSet<String>(java.util.Arrays.asList("dataType",
            "updateType", "updateCondition", "ownership", "sharing", "transportation", "order", "semantics",
            "valueRequired"));

    private DataType createDataType(DtNode n) {
        RawDataType r = n.first;
        DataType dt;
        switch (r.category) {
            case BASIC: {
                BasicDataType b = new BasicDataType(r.name);
                Long size = parseLong(r.size);
                if (size == null || size <= 0 || size > Integer.MAX_VALUE) {
                    errors.add(n.firstWhere + ": basic datatype '" + r.name + "' has invalid size '" + r.size + "'");
                } else {
                    b.sizeBits = size.intValue();
                }
                b.endianness = Endianness.fromOmtName(r.endian);
                if (b.endianness == null) {
                    errors.add(n.firstWhere + ": basic datatype '" + r.name + "' has invalid endian '" + r.endian
                            + "' (expected Big or Little)");
                }
                b.interpretation = emptyToNull(r.interpretation);
                dt = b;
                break;
            }
            case SIMPLE: {
                SimpleDataType s = new SimpleDataType(r.name);
                s.representationName = r.representation;
                s.units = emptyToNull(r.units);
                s.resolution = emptyToNull(r.resolution);
                s.accuracy = emptyToNull(r.accuracy);
                if (r.representation == null) {
                    errors.add(n.firstWhere + ": simple datatype '" + r.name + "' has no representation");
                }
                dt = s;
                break;
            }
            case ENUMERATED: {
                EnumeratedDataType e = new EnumeratedDataType(r.name);
                e.representationName = r.representation;
                if (r.representation == null) {
                    errors.add(n.firstWhere + ": enumerated datatype '" + r.name + "' has no representation");
                }
                List<Enumerator> list = new ArrayList<Enumerator>();
                Map<String, Enumerator> byName = new HashMap<String, Enumerator>();
                Map<Long, Enumerator> byValue = new HashMap<Long, Enumerator>();
                for (EnumNode en : n.enumerators.values()) {
                    Enumerator x = new Enumerator(e, en.name, en.values, list.size(), en.module);
                    list.add(x);
                    byName.put(en.name, x);
                    for (long v : en.values) {
                        Enumerator prev = byValue.get(v);
                        if (prev == null) {
                            byValue.put(v, x);
                        } else {
                            warnings.add(en.where + ": enumerators '" + prev.getName() + "' and '" + en.name + "' of '"
                                    + r.name + "' share value " + v + "; decoding maps it to '" + prev.getName()
                                    + "'");
                        }
                    }
                }
                if (list.isEmpty()) {
                    warnings.add(n.firstWhere + ": enumerated datatype '" + r.name + "' has no enumerators");
                }
                e.enumerators = Collections.unmodifiableList(list);
                e.byName = byName;
                e.byValue = byValue;
                dt = e;
                break;
            }
            case ARRAY: {
                ArrayDataType a = new ArrayDataType(r.name);
                a.elementTypeName = r.elementType;
                if (r.elementType == null) {
                    errors.add(n.firstWhere + ": array datatype '" + r.name + "' has no element datatype");
                }
                try {
                    a.cardinality = ArrayCardinality.parse(r.cardinality);
                } catch (IllegalArgumentException ex) {
                    errors.add(n.firstWhere + ": array datatype '" + r.name + "' has invalid cardinality '"
                            + r.cardinality + "': " + ex.getMessage());
                }
                if (r.encoding == null) {
                    warnings.add(n.firstWhere + ": array datatype '" + r.name + "' has no encoding");
                }
                dt = a;
                break;
            }
            case FIXED_RECORD: {
                FixedRecordDataType f = new FixedRecordDataType(r.name);
                List<RecordField> fields = new ArrayList<RecordField>();
                Map<String, RecordField> byName = new HashMap<String, RecordField>();
                for (RawField rf : r.fields) {
                    RecordField x = new RecordField(f, rf.name, fields.size(), rf.dataType, emptyToNull(rf.semantics));
                    if (byName.containsKey(rf.name)) {
                        errors.add(n.firstWhere + ": fixed record '" + r.name + "' has duplicate field '" + rf.name
                                + "'");
                    }
                    if (rf.dataType == null) {
                        errors.add(n.firstWhere + ": field '" + r.name + "." + rf.name + "' has no datatype");
                    }
                    fields.add(x);
                    byName.put(rf.name, x);
                }
                f.fields = Collections.unmodifiableList(fields);
                f.fieldsByName = byName;
                dt = f;
                break;
            }
            case VARIANT_RECORD: {
                VariantRecordDataType v = new VariantRecordDataType(r.name);
                v.discriminantName = r.discriminant;
                v.discriminantTypeName = r.discriminantType;
                if (r.discriminantType == null) {
                    errors.add(n.firstWhere + ": variant record '" + r.name + "' has no discriminant datatype");
                }
                List<VariantAlternative> alts = new ArrayList<VariantAlternative>();
                for (AltNode an : n.alternatives.values()) {
                    RawAlternative ra = an.raw;
                    alts.add(new VariantAlternative(v, normNa(ra.name), an.spec(), ra.dataType,
                            emptyToNull(ra.semantics), an.module));
                }
                v.alternatives = Collections.unmodifiableList(alts);
                dt = v;
                break;
            }
            default: {
                OtherDataType o = new OtherDataType(r.name);
                o.tableElement = r.tableElement;
                o.entryElement = r.entryElement;
                o.properties = Collections.unmodifiableMap(new LinkedHashMap<String, String>(r.otherProps));
                warnings.add(n.firstWhere + ": datatype '" + r.name + "' comes from unsupported table <"
                        + r.tableElement + ">; kept as an opaque OtherDataType");
                dt = o;
                break;
            }
        }
        dt.semantics = n.semantics;
        dt.encoding = r.encoding;
        dt.modules = Collections.unmodifiableList(new ArrayList<FomModule>(n.modules));
        dt.predefined = n.modules.get(0).isStandardMim();
        return dt;
    }

    // ------------------------------------------------------------------ phase 4: resolve

    private void refProblem(String msg) {
        if (options.isStrictReferences()) {
            errors.add(msg);
        } else {
            warnings.add(msg);
        }
    }

    /** Resolves a datatype name; "NA"/null resolve to null silently. */
    private DataType dt(String name, String context) {
        if (name == null || "NA".equals(name)) {
            return null;
        }
        DataType d = dtByName.get(name);
        if (d == null) {
            refProblem(context + ": unknown datatype '" + name + "'");
        }
        return d;
    }

    private void resolve() {
        for (DtNode n : dataTypes.values()) {
            DataType d = n.element;
            String ctx = n.firstWhere + ": datatype '" + d.getName() + "'";
            switch (d.getCategory()) {
                case SIMPLE: {
                    SimpleDataType s = (SimpleDataType) d;
                    DataType rep = dt(s.representationName, ctx);
                    if (rep instanceof BasicDataType) {
                        s.representation = (BasicDataType) rep;
                    } else if (rep != null) {
                        refProblem(ctx + ": representation '" + s.representationName + "' is a " + rep.getCategory()
                                + " datatype; a basic data representation is required");
                    }
                    break;
                }
                case ENUMERATED: {
                    EnumeratedDataType e = (EnumeratedDataType) d;
                    DataType rep = dt(e.representationName, ctx);
                    if (rep instanceof BasicDataType) {
                        e.representation = (BasicDataType) rep;
                    } else if (rep != null) {
                        refProblem(ctx + ": representation '" + e.representationName + "' is a " + rep.getCategory()
                                + " datatype; a basic data representation is required");
                    }
                    break;
                }
                case ARRAY: {
                    ArrayDataType a = (ArrayDataType) d;
                    a.elementType = dt(a.elementTypeName, ctx + " element");
                    break;
                }
                case FIXED_RECORD:
                    for (RecordField f : ((FixedRecordDataType) d).getFields()) {
                        f.dataType = dt(f.dataTypeName, ctx + " field '" + f.getName() + "'");
                    }
                    break;
                case VARIANT_RECORD:
                    resolveVariant((VariantRecordDataType) d, n, ctx);
                    break;
                default:
                    break;
            }
        }
        for (ClassNode n : ocNodes) {
            ObjectClass oc = (ObjectClass) n.element;
            oc.declaredDimensions = dims(n.dimensions, "object class '" + n.qualifiedName + "'");
            for (Attribute a : oc.declaredAttributes) {
                MemberNode mn = n.members.get(a.getName());
                String ctx = mn.firstWhere + ": attribute '" + a.qualifiedName + "'";
                a.dataType = dt(a.dataTypeName, ctx);
                if (a.dataTypeName == null) {
                    warnings.add(ctx + " has no datatype");
                }
                a.transportation = transportation(a.transportationName, ctx);
                if (mn.first.dimensions != null) {
                    Map<String, String> m = new LinkedHashMap<String, String>();
                    for (String d : mn.first.dimensions) {
                        m.put(d, mn.firstWhere);
                    }
                    a.dimensions = dims(m, ctx);
                }
            }
            List<InteractionClass> directed = new ArrayList<InteractionClass>();
            for (Map.Entry<String, String> e : n.directed.entrySet()) {
                InteractionClass ic = findInteraction(e.getKey());
                if (ic == null) {
                    warnings.add(e.getValue() + ": object class '" + n.qualifiedName
                            + "' references directed interaction '" + e.getKey() + "' which is not an interaction class");
                } else if (!directed.contains(ic)) {
                    directed.add(ic);
                }
            }
            oc.declaredDirectedInteractions = Collections.unmodifiableList(directed);
        }
        for (ClassNode n : icNodes) {
            InteractionClass ic = (InteractionClass) n.element;
            String ctx = "interaction class '" + n.qualifiedName + "'";
            ic.declaredDimensions = dims(n.dimensions, ctx);
            ic.transportation = transportation(ic.transportationName, ctx);
            for (Parameter p : ic.declaredParameters) {
                MemberNode mn = n.members.get(p.getName());
                String pctx = mn.firstWhere + ": parameter '" + p.qualifiedName + "'";
                p.dataType = dt(p.dataTypeName, pctx);
                if (p.dataTypeName == null) {
                    warnings.add(pctx + " has no datatype");
                }
            }
        }
        for (EntryNode n : dimensions.values()) {
            Dimension d = (Dimension) n.element;
            d.dataType = dt(d.dataTypeName, n.firstWhere + ": dimension '" + d.getName() + "'");
        }
        for (EntryNode n : syncPoints.values()) {
            SynchronizationPoint s = (SynchronizationPoint) n.element;
            s.dataType = dt(s.dataTypeName, n.firstWhere + ": synchronization point '" + s.getName() + "'");
        }
        for (Map.Entry<String, String> e : timeTypes.entrySet()) {
            dt(e.getValue(), timeWhere.get(e.getKey()) + ": time " + e.getKey());
        }
        for (Map.Entry<String, String> e : tagTypes.entrySet()) {
            dt(e.getValue(), tagWhere.get(e.getKey()) + ": tag " + e.getKey());
        }
    }

    private InteractionClass findInteraction(String name) {
        InteractionClass ic = icByName.get(name);
        if (ic == null && !name.startsWith("HLAinteractionRoot")) {
            ic = icByName.get("HLAinteractionRoot." + name);
        }
        return ic;
    }

    private Transportation transportation(String name, String ctx) {
        if (name == null || "NA".equals(name)) {
            return null;
        }
        Transportation t = trByName.get(name);
        if (t == null) {
            refProblem(ctx + ": unknown transportation '" + name + "'");
        }
        return t;
    }

    private List<Dimension> dims(Map<String, String> names, String ctx) {
        if (names.isEmpty()) {
            return Collections.emptyList();
        }
        List<Dimension> out = new ArrayList<Dimension>();
        for (Map.Entry<String, String> e : names.entrySet()) {
            Dimension d = dimByName.get(e.getKey());
            if (d == null) {
                refProblem(e.getValue() + ": " + ctx + " references unknown dimension '" + e.getKey() + "'");
            } else {
                out.add(d);
            }
        }
        return Collections.unmodifiableList(out);
    }

    private void resolveVariant(VariantRecordDataType v, DtNode n, String ctx) {
        DataType disc = dt(v.discriminantTypeName, ctx + " discriminant");
        if (disc != null && !(disc instanceof EnumeratedDataType)) {
            refProblem(ctx + ": discriminant type '" + v.discriminantTypeName + "' is a " + disc.getCategory()
                    + " datatype; an enumerated datatype is required");
            disc = null;
        }
        EnumeratedDataType e = (EnumeratedDataType) disc;
        v.discriminantType = e;
        Map<Long, VariantAlternative> byValue = new HashMap<Long, VariantAlternative>();
        Map<String, VariantAlternative> byName = new HashMap<String, VariantAlternative>();
        Map<String, String> claimedBy = new HashMap<String, String>();
        int i = 0;
        for (AltNode an : n.alternatives.values()) {
            VariantAlternative alt = v.alternatives.get(i++);
            String actx = an.where + ": variant record '" + v.getName() + "' alternative '"
                    + (alt.name == null ? alt.enumeratorSpec : alt.name) + "'";
            alt.dataType = dt(alt.dataTypeName, actx);
            List<Enumerator> sel = new ArrayList<Enumerator>();
            for (String item : splitSpec(alt.enumeratorSpec)) {
                if ("HLAother".equals(item)) {
                    if (v.otherAlternative != null && v.otherAlternative != alt) {
                        errors.add(actx + ": more than one alternative uses HLAother");
                    }
                    alt.other = true;
                    v.otherAlternative = alt;
                    continue;
                }
                if (e == null) {
                    continue; // discriminant unresolved, already reported
                }
                if (item.startsWith("[") && item.endsWith("]")) {
                    String inner = item.substring(1, item.length() - 1);
                    int dd = inner.indexOf("..");
                    Enumerator lo = dd < 0 ? null : e.getEnumerator(inner.substring(0, dd).trim());
                    Enumerator hi = dd < 0 ? null : e.getEnumerator(inner.substring(dd + 2).trim());
                    if (lo == null || hi == null) {
                        refProblem(actx + ": range '" + item + "' does not name two enumerators of '" + e.getName()
                                + "'");
                        continue;
                    }
                    long a = Math.min(lo.getValue(), hi.getValue());
                    long b = Math.max(lo.getValue(), hi.getValue());
                    for (Enumerator x : e.getEnumerators()) {
                        if (x.getValue() >= a && x.getValue() <= b && !sel.contains(x)) {
                            sel.add(x);
                        }
                    }
                } else {
                    Enumerator x = e.getEnumerator(item);
                    if (x == null) {
                        refProblem(actx + ": '" + item + "' is not an enumerator of '" + e.getName() + "'");
                    } else if (!sel.contains(x)) {
                        sel.add(x);
                    }
                }
            }
            for (Enumerator x : sel) {
                String prev = claimedBy.put(x.getName(), actx);
                if (prev != null) {
                    errors.add(actx + ": enumerator '" + x.getName() + "' already selects another alternative ("
                            + prev + ")");
                    continue;
                }
                byName.put(x.getName(), alt);
                for (long val : x.rawValues()) {
                    byValue.put(val, alt);
                }
            }
            alt.enumerators = Collections.unmodifiableList(sel);
        }
        v.byValue = byValue;
        v.byEnumeratorName = byName;
    }

    // ------------------------------------------------------------------ phase 5: validate

    private void validate() {
        validateInheritedNames(objectRoot, new HashMap<String, String>(), "attribute");
        validateInheritedNames(interactionRoot, new HashMap<String, String>(), "parameter");
        validateRecursion();
        // reserved HLA prefix outside the MIM
        for (DtNode n : dataTypes.values()) {
            if (reserved(n.first.name) && !n.modules.get(0).isStandardMim()) {
                warnings.add(n.firstWhere + ": datatype '" + n.first.name + "' uses the reserved 'HLA' prefix but is"
                        + " not defined by the standard MIM");
            }
        }
        warnReservedClasses(ocNodes);
        warnReservedClasses(icNodes);
    }

    private void warnReservedClasses(List<ClassNode> nodes) {
        for (ClassNode n : nodes) {
            if (n.parent != null && reserved(n.name) && !allMim(n.modules)) {
                warnings.add("class '" + n.qualifiedName + "' uses the reserved 'HLA' prefix outside the standard MIM");
            }
            for (Map.Entry<String, MemberNode> m : n.members.entrySet()) {
                if (reserved(m.getKey()) && !allMim(m.getValue().modules)) {
                    warnings.add(m.getValue().firstWhere + ": member '" + n.qualifiedName + "." + m.getKey()
                            + "' uses the reserved 'HLA' prefix outside the standard MIM");
                }
            }
        }
    }

    private static boolean allMim(java.util.Collection<FomModule> mods) {
        for (FomModule m : mods) {
            if (!m.isStandardMim()) {
                return false;
            }
        }
        return true;
    }

    private static boolean reserved(String name) {
        return name.length() >= 3 && name.regionMatches(true, 0, "hla", 0, 3);
    }

    private void validateInheritedNames(ClassNode n, Map<String, String> inherited, String what) {
        for (Map.Entry<String, MemberNode> m : n.members.entrySet()) {
            String from = inherited.get(m.getKey());
            if (from != null) {
                errors.add(m.getValue().firstWhere + ": " + what + " '" + m.getKey() + "' declared in '"
                        + n.qualifiedName + "' is already inherited from '" + from + "'");
            }
        }
        if (n.children.isEmpty()) {
            return;
        }
        Map<String, String> next = new HashMap<String, String>(inherited);
        for (String m : n.members.keySet()) {
            if (!next.containsKey(m)) {
                next.put(m, n.qualifiedName);
            }
        }
        for (ClassNode c : n.children.values()) {
            validateInheritedNames(c, next, what);
        }
    }

    /**
     * Detects datatypes whose encoding would be infinitely large: a cycle through fixed record fields,
     * fixed-cardinality arrays or simple/enumerated representations. Dynamic arrays and variant records
     * legitimately break recursion.
     */
    private void validateRecursion() {
        Map<DataType, Integer> state = new HashMap<DataType, Integer>(); // 1 = visiting, 2 = done
        for (DataType d : dtList) {
            List<DataType> path = new ArrayList<DataType>();
            visit(d, state, path);
        }
    }

    private void visit(DataType d, Map<DataType, Integer> state, List<DataType> path) {
        Integer s = state.get(d);
        if (s != null) {
            if (s == 1) {
                StringBuilder sb = new StringBuilder();
                for (int i = path.indexOf(d); i < path.size(); i++) {
                    sb.append(path.get(i).getName()).append(" -> ");
                }
                sb.append(d.getName());
                errors.add(d.getModule().getLabel() + ": datatype '" + d.getName()
                        + "' is infinitely recursive (" + sb + ")");
            }
            return;
        }
        state.put(d, 1);
        path.add(d);
        for (DataType next : sizeEdges(d)) {
            visit(next, state, path);
        }
        path.remove(path.size() - 1);
        state.put(d, 2);
    }

    private static List<DataType> sizeEdges(DataType d) {
        List<DataType> out = new ArrayList<DataType>();
        if (d instanceof FixedRecordDataType) {
            for (RecordField f : ((FixedRecordDataType) d).getFields()) {
                if (f.dataType != null) {
                    out.add(f.dataType);
                }
            }
        } else if (d instanceof ArrayDataType) {
            ArrayDataType a = (ArrayDataType) d;
            if (a.elementType != null && a.cardinality != null && !a.cardinality.isDynamic()
                    && a.cardinality.getFixedElementCount() > 0) {
                out.add(a.elementType);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ phase 6: views

    private void computeViews() {
        for (ClassNode n : ocNodes) {
            ObjectClass oc = (ObjectClass) n.element;
            ObjectClass parent = n.parent == null ? null : (ObjectClass) n.parent.element;
            oc.parent = parent;
            List<ObjectClass> kids = new ArrayList<ObjectClass>();
            for (ClassNode c : n.children.values()) {
                kids.add((ObjectClass) c.element);
            }
            oc.children = Collections.unmodifiableList(kids);
            oc.childIds = ids(kids);
            List<ClassAttribute> views = new ArrayList<ClassAttribute>();
            if (parent != null) {
                for (ClassAttribute pa : parent.attributes) {
                    views.add(new ClassAttribute(oc, pa.getAttribute()));
                }
            }
            for (Attribute a : oc.declaredAttributes) {
                views.add(new ClassAttribute(oc, a));
            }
            Map<String, ClassAttribute> byName = new HashMap<String, ClassAttribute>(views.size() * 2);
            Map<Integer, ClassAttribute> byId = new HashMap<Integer, ClassAttribute>(views.size() * 2);
            for (ClassAttribute v : views) {
                byName.put(v.getName(), v);
                byId.put(v.getId(), v);
            }
            oc.attributes = Collections.unmodifiableList(views);
            oc.attributesByName = byName;
            oc.attributesById = byId;
            oc.dimensions = inheritDims(parent == null ? null : parent.dimensions, oc.declaredDimensions);
            List<InteractionClass> di = new ArrayList<InteractionClass>();
            if (parent != null) {
                di.addAll(parent.directedInteractions);
            }
            for (InteractionClass ic : oc.declaredDirectedInteractions) {
                if (!di.contains(ic)) {
                    di.add(ic);
                }
            }
            oc.directedInteractions = Collections.unmodifiableList(di);
        }
        for (ClassNode n : icNodes) {
            InteractionClass ic = (InteractionClass) n.element;
            InteractionClass parent = n.parent == null ? null : (InteractionClass) n.parent.element;
            ic.parent = parent;
            List<InteractionClass> kids = new ArrayList<InteractionClass>();
            for (ClassNode c : n.children.values()) {
                kids.add((InteractionClass) c.element);
            }
            ic.children = Collections.unmodifiableList(kids);
            ic.childIds = ids(kids);
            List<ClassParameter> views = new ArrayList<ClassParameter>();
            if (parent != null) {
                for (ClassParameter pp : parent.parameters) {
                    views.add(new ClassParameter(ic, pp.getParameter()));
                }
            }
            for (Parameter p : ic.declaredParameters) {
                views.add(new ClassParameter(ic, p));
            }
            Map<String, ClassParameter> byName = new HashMap<String, ClassParameter>(views.size() * 2);
            Map<Integer, ClassParameter> byId = new HashMap<Integer, ClassParameter>(views.size() * 2);
            for (ClassParameter v : views) {
                byName.put(v.getName(), v);
                byId.put(v.getId(), v);
            }
            ic.parameters = Collections.unmodifiableList(views);
            ic.parametersByName = byName;
            ic.parametersById = byId;
            ic.dimensions = inheritDims(parent == null ? null : parent.dimensions, ic.declaredDimensions);
        }
    }

    private static List<Dimension> inheritDims(List<Dimension> parent, List<Dimension> own) {
        if ((parent == null || parent.isEmpty()) && own.isEmpty()) {
            return Collections.emptyList();
        }
        List<Dimension> out = new ArrayList<Dimension>();
        if (parent != null) {
            out.addAll(parent);
        }
        for (Dimension d : own) {
            if (!out.contains(d)) {
                out.add(d);
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static int[] ids(List<? extends IemElement> list) {
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i).getId();
        }
        return out;
    }

    // ------------------------------------------------------------------ result

    private Result result() {
        Result r = new Result();
        r.version = version;
        r.modules = Collections.unmodifiableList(new ArrayList<FomModule>(modules));
        r.elements = new IemElement[all.size() + 1];
        for (IemElement e : all) {
            r.elements[e.getId()] = e;
        }
        r.kindStart = kindStart.clone();
        r.kindCount = kindCount.clone();
        r.objectRoot = (ObjectClass) objectRoot.element;
        r.interactionRoot = (InteractionClass) interactionRoot.element;
        r.dataTypes = Collections.unmodifiableList(dtList);
        r.objectClasses = Collections.unmodifiableList(ocList);
        r.attributes = Collections.unmodifiableList(attrList);
        r.interactionClasses = Collections.unmodifiableList(icList);
        r.parameters = Collections.unmodifiableList(parList);
        r.dimensions = Collections.unmodifiableList(dimList);
        r.transportations = Collections.unmodifiableList(trList);
        r.updateRates = Collections.unmodifiableList(urList);
        r.synchronizationPoints = Collections.unmodifiableList(spList);

        r.dataTypesByName = new HashMap<String, DataType>(dtByName);
        r.objectClassesByName = new HashMap<String, ObjectClass>(ocList.size() * 4);
        r.objectClassesBySimpleName = new HashMap<String, List<ObjectClass>>();
        for (ObjectClass oc : ocList) {
            r.objectClassesByName.put(oc.getQualifiedName(), oc);
            if (!oc.isRoot()) {
                r.objectClassesByName.put(oc.getQualifiedNameWithoutRoot(), oc);
            }
            addMulti(r.objectClassesBySimpleName, oc.getName(), oc);
        }
        r.interactionClassesByName = new HashMap<String, InteractionClass>(icList.size() * 4);
        r.interactionClassesBySimpleName = new HashMap<String, List<InteractionClass>>();
        for (InteractionClass ic : icList) {
            r.interactionClassesByName.put(ic.getQualifiedName(), ic);
            if (!ic.isRoot()) {
                r.interactionClassesByName.put(ic.getQualifiedNameWithoutRoot(), ic);
            }
            addMulti(r.interactionClassesBySimpleName, ic.getName(), ic);
        }
        freezeMulti(r.objectClassesBySimpleName);
        freezeMulti(r.interactionClassesBySimpleName);
        r.attributesByName = new HashMap<String, Attribute>(attrList.size() * 2);
        for (Attribute a : attrList) {
            r.attributesByName.put(a.getQualifiedName(), a);
        }
        r.parametersByName = new HashMap<String, Parameter>(parList.size() * 2);
        for (Parameter p : parList) {
            r.parametersByName.put(p.getQualifiedName(), p);
        }
        r.dimensionsByName = new HashMap<String, Dimension>(dimByName);
        r.transportationsByName = new HashMap<String, Transportation>(trByName);
        r.updateRatesByName = new HashMap<String, UpdateRate>();
        for (UpdateRate u : urList) {
            r.updateRatesByName.put(u.getName(), u);
        }
        r.synchronizationPointsByName = new HashMap<String, SynchronizationPoint>();
        for (SynchronizationPoint s : spList) {
            r.synchronizationPointsByName.put(s.getName(), s);
        }
        r.switches = Collections.unmodifiableMap(new LinkedHashMap<String, String>(switches));
        r.timeDataTypes = Collections.unmodifiableMap(new LinkedHashMap<String, String>(timeTypes));
        r.tagDataTypes = Collections.unmodifiableMap(new LinkedHashMap<String, String>(tagTypes));
        r.warnings = Collections.unmodifiableList(new ArrayList<String>(warnings));
        return r;
    }

    private static <T> void addMulti(Map<String, List<T>> map, String key, T value) {
        List<T> l = map.get(key);
        if (l == null) {
            l = new ArrayList<T>(1);
            map.put(key, l);
        }
        l.add(value);
    }

    private static <T> void freezeMulti(Map<String, List<T>> map) {
        for (Map.Entry<String, List<T>> e : map.entrySet()) {
            e.setValue(Collections.unmodifiableList(e.getValue()));
        }
    }

    // ------------------------------------------------------------------ helpers

    static String emptyToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    /** Decimal (optionally signed) or 0x-hex; values up to 2^64-1 are accepted as unsigned. */
    static Long parseLong(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim().replace("_", "");
        if (t.isEmpty()) {
            return null;
        }
        try {
            boolean neg = t.startsWith("-");
            String body = neg || t.startsWith("+") ? t.substring(1) : t;
            if (body.startsWith("0x") || body.startsWith("0X")) {
                long v = Long.parseUnsignedLong(body.substring(2), 16);
                return neg ? -v : v;
            }
            if (!neg) {
                try {
                    return Long.parseLong(body);
                } catch (NumberFormatException e) {
                    return Long.parseUnsignedLong(body);
                }
            }
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Double parseDouble(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Double.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static boolean parseBool(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        return t.equals("true") || t.equals("yes") || t.equals("1") || t.equals("y");
    }
}
