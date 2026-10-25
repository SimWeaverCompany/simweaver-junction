package com.simweaver.common.iem;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An HLA-API independent, merged representation of a set of FOM modules - in effect the FDD an RTI
 * would build - with object and interaction class hierarchies, attributes and parameters (declared
 * and inherited), datatypes, dimensions, transportations, update rates, synchronization points,
 * switches and time/tag datatypes.
 *
 * <h2>Building</h2>
 * <pre>
 * List&lt;String&gt; foms = Arrays.asList("foms/RPR-Base_v2.0.xml", "foms/RPR-Physical_v2.0.xml", xmlText);
 * InformationExchangeModel iem = InformationExchangeModel.fromHla4(foms);         // IEEE 1516-2025
 * InformationExchangeModel iem = InformationExchangeModel.fromHlaEvolved(foms);   // IEEE 1516-2010
 * </pre>
 * Each list entry is either a path (or {@code file:} URI) to a FOM module or the XML text of one;
 * paths and XML may be mixed, HLA versions may not. Modules are merged in list order after the
 * standard MIM, using the OMT merge rules including HLA 4 extended merging (see {@link IemOptions}).
 * Any problem throws a checked {@link IemException} listing everything that was wrong.
 *
 * <h2>Ids</h2>
 * Every element (datatype, class, attribute, parameter, dimension, transportation, update rate,
 * synchronization point) gets a model-wide unique id &gt;= 1; see {@link IemElement}. Lookups by id are
 * array indexed (O(1)); lookups by name are hash based.
 *
 * <h2>Names</h2>
 * Class names are the dotted hierarchical names. Lookups accept them with or without the root:
 * {@code "HLAobjectRoot.BaseEntity.PhysicalEntity"} and {@code "BaseEntity.PhysicalEntity"} are the
 * same class. Names are case-sensitive, as in HLA.
 *
 * <h2>Threading</h2>
 * Instances are immutable and thread safe.
 */
public final class InformationExchangeModel {

    private final HlaVersion version;
    private final List<FomModule> modules;
    private final IemElement[] elements;
    private final int[] kindStart;
    private final int[] kindCount;
    private final ObjectClass objectRoot;
    private final InteractionClass interactionRoot;
    private final List<DataType> dataTypes;
    private final List<ObjectClass> objectClasses;
    private final List<Attribute> attributes;
    private final List<InteractionClass> interactionClasses;
    private final List<Parameter> parameters;
    private final List<Dimension> dimensions;
    private final List<Transportation> transportations;
    private final List<UpdateRate> updateRates;
    private final List<SynchronizationPoint> synchronizationPoints;
    private final Map<String, DataType> dataTypesByName;
    private final Map<String, ObjectClass> objectClassesByName;
    private final Map<String, InteractionClass> interactionClassesByName;
    private final Map<String, List<ObjectClass>> objectClassesBySimpleName;
    private final Map<String, List<InteractionClass>> interactionClassesBySimpleName;
    private final Map<String, Attribute> attributesByName;
    private final Map<String, Parameter> parametersByName;
    private final Map<String, Dimension> dimensionsByName;
    private final Map<String, Transportation> transportationsByName;
    private final Map<String, UpdateRate> updateRatesByName;
    private final Map<String, SynchronizationPoint> synchronizationPointsByName;
    private final Map<String, String> switches;
    private final Map<String, String> timeDataTypes;
    private final Map<String, String> tagDataTypes;
    private final List<String> warnings;
    private final Set<FomFamily> families;
    private final Set<FomFamily> familiesFromContent;

    private InformationExchangeModel(ModelBuilder.Result r) {
        this.version = r.version;
        this.modules = r.modules;
        this.elements = r.elements;
        this.kindStart = r.kindStart;
        this.kindCount = r.kindCount;
        this.objectRoot = r.objectRoot;
        this.interactionRoot = r.interactionRoot;
        this.dataTypes = r.dataTypes;
        this.objectClasses = r.objectClasses;
        this.attributes = r.attributes;
        this.interactionClasses = r.interactionClasses;
        this.parameters = r.parameters;
        this.dimensions = r.dimensions;
        this.transportations = r.transportations;
        this.updateRates = r.updateRates;
        this.synchronizationPoints = r.synchronizationPoints;
        this.dataTypesByName = r.dataTypesByName;
        this.objectClassesByName = r.objectClassesByName;
        this.interactionClassesByName = r.interactionClassesByName;
        this.objectClassesBySimpleName = r.objectClassesBySimpleName;
        this.interactionClassesBySimpleName = r.interactionClassesBySimpleName;
        this.attributesByName = r.attributesByName;
        this.parametersByName = r.parametersByName;
        this.dimensionsByName = r.dimensionsByName;
        this.transportationsByName = r.transportationsByName;
        this.updateRatesByName = r.updateRatesByName;
        this.synchronizationPointsByName = r.synchronizationPointsByName;
        this.switches = r.switches;
        this.timeDataTypes = r.timeDataTypes;
        this.tagDataTypes = r.tagDataTypes;
        this.warnings = r.warnings;
        EnumSet<FomFamily> f = EnumSet.noneOf(FomFamily.class);
        for (FomModule m : modules) {
            f.add(m.getFamily());
        }
        EnumSet<FomFamily> byContent = familiesFromContent(f);
        f.addAll(byContent);
        this.families = Collections.unmodifiableSet(f);
        this.familiesFromContent = Collections.unmodifiableSet(byContent);
    }

    // ================================================================== construction

    /**
     * Builds a model from IEEE 1516-2010 (HLA Evolved) FOM modules with default options.
     *
     * @param foms paths / {@code file:} URIs to FOM modules and/or FOM module XML text, in merge order
     * @throws IemException if a module cannot be read or parsed, is not an HLA Evolved module, or the
     *                      modules cannot be merged into a consistent model
     */
    public static InformationExchangeModel fromHlaEvolved(List<String> foms) throws IemException {
        return fromHlaEvolved(foms, new IemOptions());
    }

    /** As {@link #fromHlaEvolved(List)} with explicit options. */
    public static InformationExchangeModel fromHlaEvolved(List<String> foms, IemOptions options) throws IemException {
        return new InformationExchangeModel(ModelBuilder.build(HlaVersion.HLA_EVOLVED, foms, options));
    }

    /**
     * Builds a model from IEEE 1516-2025 (HLA 4) FOM modules with default options.
     *
     * @param foms paths / {@code file:} URIs to FOM modules and/or FOM module XML text, in merge order
     * @throws IemException if a module cannot be read or parsed, is not an HLA 4 module, or the modules
     *                      cannot be merged into a consistent model
     */
    public static InformationExchangeModel fromHla4(List<String> foms) throws IemException {
        return fromHla4(foms, new IemOptions());
    }

    /** As {@link #fromHla4(List)} with explicit options. */
    public static InformationExchangeModel fromHla4(List<String> foms, IemOptions options) throws IemException {
        return new InformationExchangeModel(ModelBuilder.build(HlaVersion.HLA_4, foms, options));
    }

    /** Builds a model for an explicitly given version. */
    public static InformationExchangeModel from(HlaVersion version, List<String> foms, IemOptions options)
            throws IemException {
        if (version == null) {
            throw new IemException("HLA version is null");
        }
        return new InformationExchangeModel(ModelBuilder.build(version, foms, options));
    }

    // ================================================================== general

    /** HLA version of the merged modules. */
    public HlaVersion getHlaVersion() {
        return version;
    }

    /** Merged modules in merge order (standard MIM first when included). */
    public List<FomModule> getModules() {
        return modules;
    }

    /** Non-fatal findings from parsing and merging, each prefixed with its location when known. */
    public List<String> getWarnings() {
        return warnings;
    }

    /** FOM families present among the merged modules. */
    public Set<FomFamily> getFomFamilies() {
        return families;
    }

    /**
     * Families recognised from the classes and datatypes when no module names them, as in a
     * merged FDD taken from a federation (or a vendor FOM that merges RPR into a module of its
     * own name): RPR when {@code BaseEntity.PhysicalEntity} is there, v3 with
     * {@code Fire.WeaponFire}, v2 with {@code WeaponFire} directly under the root; NETN when
     * {@code HLAobjectRoot} has {@code UniqueId} and the datatype {@code HostilityStatusCodeEnum32}
     * is there (NETN-BASE).
     */
    private EnumSet<FomFamily> familiesFromContent(Set<FomFamily> byModule) {
        EnumSet<FomFamily> out = EnumSet.noneOf(FomFamily.class);
        if (!byModule.contains(FomFamily.RPR_FOM_V2) && !byModule.contains(FomFamily.RPR_FOM_V3)
                && objectClassesByName.containsKey("HLAobjectRoot.BaseEntity.PhysicalEntity")) {
            if (interactionClassesByName.containsKey("HLAinteractionRoot.Fire.WeaponFire")) {
                out.add(FomFamily.RPR_FOM_V3);
            } else if (interactionClassesByName.containsKey("HLAinteractionRoot.WeaponFire")) {
                out.add(FomFamily.RPR_FOM_V2);
            }
        }
        if (!byModule.contains(FomFamily.NETN)) {
            ObjectClass root = objectClassesByName.get("HLAobjectRoot");
            if (root != null && root.hasAttribute("UniqueId") && dataTypesByName.containsKey("HostilityStatusCodeEnum32")) {
                out.add(FomFamily.NETN);
            }
        }
        return out;
    }

    /** @return the families recognised from the content only (see {@link #familiesFromContent}) */
    public Set<FomFamily> getFamiliesFromContent() {
        return familiesFromContent;
    }

    /** True if at least one module of the family is merged. */
    public boolean hasFomFamily(FomFamily family) {
        return families.contains(family);
    }

    /** 3 if RPR FOM v3 modules are merged, else 2 if RPR FOM v2 modules are, else 0. */
    public int getRprFomVersion() {
        return families.contains(FomFamily.RPR_FOM_V3) ? 3 : families.contains(FomFamily.RPR_FOM_V2) ? 2 : 0;
    }

    /** True if NETN modules are merged. */
    public boolean hasNetn() {
        return families.contains(FomFamily.NETN);
    }

    // ================================================================== ids

    /** Any element by id, or null for an unknown id. */
    public IemElement getElement(int id) {
        return id > 0 && id < elements.length ? elements[id] : null;
    }

    /** Highest id in use (ids run from 1 to this value without gaps). */
    public int getMaxId() {
        return elements.length - 1;
    }

    /** First id of a kind; meaningless when {@link #getElementCount(ElementKind)} is 0. */
    public int getFirstId(ElementKind kind) {
        return kindStart[kind.ordinal()];
    }

    /** Number of elements of a kind. */
    public int getElementCount(ElementKind kind) {
        return kindCount[kind.ordinal()];
    }

    /** True if the id belongs to an element of the given kind. */
    public boolean isKind(int id, ElementKind kind) {
        int s = kindStart[kind.ordinal()];
        return id >= s && id < s + kindCount[kind.ordinal()];
    }

    private IemElement typed(int id, ElementKind kind) {
        return isKind(id, kind) ? elements[id] : null;
    }

    // ================================================================== object classes

    /** HLAobjectRoot. */
    public ObjectClass getObjectClassRoot() {
        return objectRoot;
    }

    /** All object classes in pre-order (root first, each class before its subclasses), i.e. id order. */
    public List<ObjectClass> getObjectClasses() {
        return objectClasses;
    }

    /** Object class by id, or null. */
    public ObjectClass getObjectClass(int id) {
        return (ObjectClass) typed(id, ElementKind.OBJECT_CLASS);
    }

    /**
     * Object class by qualified name, with or without {@code HLAobjectRoot.}, or null.
     * For an unqualified simple name use {@link #findObjectClassesBySimpleName(String)}.
     */
    public ObjectClass getObjectClass(String qualifiedName) {
        return qualifiedName == null ? null : objectClassesByName.get(qualifiedName.trim());
    }

    /** Object classes whose simple (last) name matches; usually zero or one. */
    public List<ObjectClass> findObjectClassesBySimpleName(String simpleName) {
        List<ObjectClass> l = simpleName == null ? null : objectClassesBySimpleName.get(simpleName.trim());
        return l == null ? Collections.<ObjectClass>emptyList() : l;
    }

    // ================================================================== attributes

    /** All attribute declarations in id order. */
    public List<Attribute> getAttributes() {
        return attributes;
    }

    /** Attribute declaration by id, or null. */
    public Attribute getAttribute(int attributeId) {
        return (Attribute) typed(attributeId, ElementKind.ATTRIBUTE);
    }

    /**
     * Attribute declaration by qualified name. The class part may be the declaring class or any
     * subclass (with or without {@code HLAobjectRoot.}): {@code "BaseEntity.EntityType"} and
     * {@code "BaseEntity.PhysicalEntity.Platform.EntityType"} give the same declaration.
     */
    public Attribute getAttribute(String qualifiedName) {
        if (qualifiedName == null) {
            return null;
        }
        Attribute a = attributesByName.get(qualifiedName.trim());
        if (a != null) {
            return a;
        }
        ClassAttribute ca = getClassAttribute(qualifiedName);
        return ca == null ? null : ca.getAttribute();
    }

    /** An attribute as seen on a class (declared or inherited), or null. */
    public ClassAttribute getClassAttribute(int objectClassId, int attributeId) {
        ObjectClass oc = getObjectClass(objectClassId);
        return oc == null ? null : oc.getAttribute(attributeId);
    }

    /** An attribute as seen on a class (declared or inherited), or null. */
    public ClassAttribute getClassAttribute(String classQualifiedName, String attributeName) {
        ObjectClass oc = getObjectClass(classQualifiedName);
        return oc == null ? null : oc.getAttribute(attributeName);
    }

    /**
     * An attribute as seen on a class, from {@code <class qualified name>.<attribute name>}, e.g.
     * {@code "HLAobjectRoot.BaseEntity.PhysicalEntity.Platform.EntityType"} (inherited view on Platform).
     */
    public ClassAttribute getClassAttribute(String qualifiedName) {
        if (qualifiedName == null) {
            return null;
        }
        String q = qualifiedName.trim();
        int dot = q.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        String cls = q.substring(0, dot);
        ObjectClass oc = getObjectClass(cls);
        return oc == null ? null : oc.getAttribute(q.substring(dot + 1));
    }

    // ================================================================== interaction classes

    /** HLAinteractionRoot. */
    public InteractionClass getInteractionClassRoot() {
        return interactionRoot;
    }

    /** All interaction classes in pre-order, i.e. id order. */
    public List<InteractionClass> getInteractionClasses() {
        return interactionClasses;
    }

    /** Interaction class by id, or null. */
    public InteractionClass getInteractionClass(int id) {
        return (InteractionClass) typed(id, ElementKind.INTERACTION_CLASS);
    }

    /** Interaction class by qualified name, with or without {@code HLAinteractionRoot.}, or null. */
    public InteractionClass getInteractionClass(String qualifiedName) {
        return qualifiedName == null ? null : interactionClassesByName.get(qualifiedName.trim());
    }

    /** Interaction classes whose simple (last) name matches; usually zero or one. */
    public List<InteractionClass> findInteractionClassesBySimpleName(String simpleName) {
        List<InteractionClass> l = simpleName == null ? null : interactionClassesBySimpleName.get(simpleName.trim());
        return l == null ? Collections.<InteractionClass>emptyList() : l;
    }

    // ================================================================== parameters

    /** All parameter declarations in id order. */
    public List<Parameter> getParameters() {
        return parameters;
    }

    /** Parameter declaration by id, or null. */
    public Parameter getParameter(int parameterId) {
        return (Parameter) typed(parameterId, ElementKind.PARAMETER);
    }

    /** Parameter declaration by qualified name (declaring class or any subclass as class part). */
    public Parameter getParameter(String qualifiedName) {
        if (qualifiedName == null) {
            return null;
        }
        Parameter p = parametersByName.get(qualifiedName.trim());
        if (p != null) {
            return p;
        }
        ClassParameter cp = getClassParameter(qualifiedName);
        return cp == null ? null : cp.getParameter();
    }

    /** A parameter as seen on a class (declared or inherited), or null. */
    public ClassParameter getClassParameter(int interactionClassId, int parameterId) {
        InteractionClass ic = getInteractionClass(interactionClassId);
        return ic == null ? null : ic.getParameter(parameterId);
    }

    /** A parameter as seen on a class (declared or inherited), or null. */
    public ClassParameter getClassParameter(String classQualifiedName, String parameterName) {
        InteractionClass ic = getInteractionClass(classQualifiedName);
        return ic == null ? null : ic.getParameter(parameterName);
    }

    /** A parameter as seen on a class, from {@code <class qualified name>.<parameter name>}, or null. */
    public ClassParameter getClassParameter(String qualifiedName) {
        if (qualifiedName == null) {
            return null;
        }
        String q = qualifiedName.trim();
        int dot = q.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        String cls = q.substring(0, dot);
        InteractionClass ic = getInteractionClass(cls);
        return ic == null ? null : ic.getParameter(q.substring(dot + 1));
    }

    // ================================================================== datatypes

    /** All datatypes in id order (MIM types first). */
    public List<DataType> getDataTypes() {
        return dataTypes;
    }

    /** Datatypes of one category, in id order. */
    public List<DataType> getDataTypes(DataTypeCategory category) {
        List<DataType> out = new ArrayList<DataType>();
        for (DataType d : dataTypes) {
            if (d.getCategory() == category) {
                out.add(d);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** Datatype by id, or null. */
    public DataType getDataType(int id) {
        return (DataType) typed(id, ElementKind.DATA_TYPE);
    }

    /** Datatype by name, or null. */
    public DataType getDataType(String name) {
        return name == null ? null : dataTypesByName.get(name.trim());
    }

    /**
     * Datatype by name, cast to the expected class; null when absent or of another category.
     * Example: {@code getDataType("ForceIdentifierEnum8", EnumeratedDataType.class)}.
     */
    public <T extends DataType> T getDataType(String name, Class<T> type) {
        DataType d = getDataType(name);
        return type.isInstance(d) ? type.cast(d) : null;
    }

    // ================================================================== other tables

    public List<Dimension> getDimensions() {
        return dimensions;
    }

    public Dimension getDimension(int id) {
        return (Dimension) typed(id, ElementKind.DIMENSION);
    }

    public Dimension getDimension(String name) {
        return name == null ? null : dimensionsByName.get(name.trim());
    }

    /** All transportation types; HLAreliable and HLAbestEffort first. */
    public List<Transportation> getTransportations() {
        return transportations;
    }

    public Transportation getTransportation(int id) {
        return (Transportation) typed(id, ElementKind.TRANSPORTATION);
    }

    public Transportation getTransportation(String name) {
        return name == null ? null : transportationsByName.get(name.trim());
    }

    public List<UpdateRate> getUpdateRates() {
        return updateRates;
    }

    public UpdateRate getUpdateRate(int id) {
        return (UpdateRate) typed(id, ElementKind.UPDATE_RATE);
    }

    public UpdateRate getUpdateRate(String name) {
        return name == null ? null : updateRatesByName.get(name.trim());
    }

    public List<SynchronizationPoint> getSynchronizationPoints() {
        return synchronizationPoints;
    }

    public SynchronizationPoint getSynchronizationPoint(int id) {
        return (SynchronizationPoint) typed(id, ElementKind.SYNCHRONIZATION_POINT);
    }

    public SynchronizationPoint getSynchronizationPoint(String label) {
        return label == null ? null : synchronizationPointsByName.get(label.trim());
    }

    /**
     * Switch settings from the switches tables, first module wins: switch element name
     * (e.g. {@code autoProvide}, {@code automaticResignAction}) to its value ({@code true}/{@code false}
     * or the resign action). Switches absent from every module are absent here (RTI defaults apply).
     */
    public Map<String, String> getSwitches() {
        return switches;
    }

    /** Datatype name of the time stamp from the time table, or null. */
    public String getTimeStampDataTypeName() {
        return timeDataTypes.get("timeStamp");
    }

    /** Resolved time stamp datatype, or null. */
    public DataType getTimeStampDataType() {
        return getDataType(getTimeStampDataTypeName());
    }

    /** Datatype name of the lookahead from the time table, or null. */
    public String getLookaheadDataTypeName() {
        return timeDataTypes.get("lookahead");
    }

    /** Resolved lookahead datatype, or null. */
    public DataType getLookaheadDataType() {
        return getDataType(getLookaheadDataTypeName());
    }

    /** User supplied tag datatypes: tag element name (e.g. {@code updateReflectTag}) to datatype name. */
    public Map<String, String> getTagDataTypes() {
        return tagDataTypes;
    }

    // ================================================================== diagnostics

    /** Writes an indented class tree with attributes/parameters (inherited ones marked with ^). */
    public void printHierarchy(Appendable out, boolean includeMembers) throws IOException {
        out.append("Object classes\n");
        printObject(out, objectRoot, includeMembers);
        out.append("Interaction classes\n");
        printInteraction(out, interactionRoot, includeMembers);
    }

    /** {@link #printHierarchy(Appendable, boolean)} into a String. */
    public String hierarchyToString(boolean includeMembers) {
        StringBuilder sb = new StringBuilder();
        try {
            printHierarchy(sb, includeMembers);
        } catch (IOException e) {
            throw new IllegalStateException(e); // cannot happen with StringBuilder
        }
        return sb.toString();
    }

    private static void indent(Appendable out, int depth) throws IOException {
        for (int i = 0; i <= depth; i++) {
            out.append("  ");
        }
    }

    private static void printObject(Appendable out, ObjectClass c, boolean members) throws IOException {
        indent(out, c.getDepth());
        out.append(c.getName()).append(" [").append(Integer.toString(c.getId())).append("]")
                .append(c.isScaffoldingOnly() ? " (scaffolding)" : "").append('\n');
        if (members) {
            for (Attribute a : c.getDeclaredAttributes()) {
                indent(out, c.getDepth() + 1);
                out.append(". ").append(a.getName()).append(" : ").append(String.valueOf(a.getDataTypeName()))
                        .append(" [").append(Integer.toString(a.getId())).append("]\n");
            }
        }
        for (ObjectClass s : c.getChildClasses()) {
            printObject(out, s, members);
        }
    }

    private static void printInteraction(Appendable out, InteractionClass c, boolean members) throws IOException {
        indent(out, c.getDepth());
        out.append(c.getName()).append(" [").append(Integer.toString(c.getId())).append("]")
                .append(c.isScaffoldingOnly() ? " (scaffolding)" : "").append('\n');
        if (members) {
            for (Parameter p : c.getDeclaredParameters()) {
                indent(out, c.getDepth() + 1);
                out.append(". ").append(p.getName()).append(" : ").append(String.valueOf(p.getDataTypeName()))
                        .append(" [").append(Integer.toString(p.getId())).append("]\n");
            }
        }
        for (InteractionClass s : c.getChildClasses()) {
            printInteraction(out, s, members);
        }
    }

    @Override
    public String toString() {
        return "InformationExchangeModel[" + ModuleParser.friendly(version) + ", " + modules.size() + " modules, "
                + objectClasses.size() + " object classes, " + attributes.size() + " attributes, "
                + interactionClasses.size() + " interaction classes, " + parameters.size() + " parameters, "
                + dataTypes.size() + " datatypes]";
    }
}
