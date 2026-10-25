package com.simweaver.common.iem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Common part of {@link ObjectClass} and {@link InteractionClass}: the position in the class
 * hierarchy, class level properties, and which modules contributed to the merged class.
 *
 * @param <C> the concrete class type
 */
public abstract class HlaClass<C extends HlaClass<C>> extends IemElement {

    String qualifiedName;
    C parent;
    List<C> children = Collections.emptyList();
    int[] childIds = new int[0];
    int depth;
    Sharing sharing;
    String sharingText;
    String semantics;
    List<Dimension> declaredDimensions = Collections.emptyList();
    List<Dimension> dimensions = Collections.emptyList();
    List<FomModule> modules = Collections.emptyList();
    List<FomModule> definingModules = Collections.emptyList();
    Map<String, String> otherProperties = Collections.emptyMap();
    List<String> unknownElements = Collections.emptyList();

    HlaClass(String name) {
        super(name);
    }

    /**
     * Fully qualified name including the root, e.g. {@code HLAobjectRoot.BaseEntity.PhysicalEntity}.
     */
    public final String getQualifiedName() {
        return qualifiedName;
    }

    /** Qualified name without the leading root, e.g. {@code BaseEntity.PhysicalEntity}; empty for the root. */
    public final String getQualifiedNameWithoutRoot() {
        int dot = qualifiedName.indexOf('.');
        return dot < 0 ? "" : qualifiedName.substring(dot + 1);
    }

    /** Superclass, or null for the root class. */
    public final C getParentClass() {
        return parent;
    }

    /** Id of the superclass, or {@link IemElement#NO_ID} for the root class. */
    public final int getParentClassId() {
        return parent == null ? NO_ID : parent.getId();
    }

    /** Direct subclasses, in merge order. */
    public final List<C> getChildClasses() {
        return children;
    }

    /** Ids of the direct subclasses (a fresh copy each call). */
    public final int[] getChildClassIds() {
        return childIds.clone();
    }

    /** True if the class has no subclasses. */
    public final boolean isLeaf() {
        return children.isEmpty();
    }

    /** True for HLAobjectRoot / HLAinteractionRoot. */
    public final boolean isRoot() {
        return parent == null;
    }

    /** 0 for the root, 1 for its direct subclasses, and so on. */
    public final int getDepth() {
        return depth;
    }

    /** Ancestors from the root down to the direct superclass (empty for the root). */
    public final List<C> getAncestors() {
        List<C> out = new ArrayList<C>(depth);
        C c = parent;
        while (c != null) {
            out.add(c);
            c = c.getParentClass();
        }
        Collections.reverse(out);
        return out;
    }

    /** All subclasses (direct and indirect) in pre-order. */
    public final List<C> getDescendants() {
        List<C> out = new ArrayList<C>();
        collect(children, out);
        return out;
    }

    private void collect(List<C> list, List<C> out) {
        for (C c : list) {
            out.add(c);
            collect(c.getChildClasses(), out);
        }
    }

    /** True if this class is {@code other} or a (direct or indirect) subclass of it. */
    public final boolean isSameOrSubclassOf(HlaClass<?> other) {
        HlaClass<?> c = this;
        while (c != null) {
            if (c == other) {
                return true;
            }
            c = c.getParentClass();
        }
        return false;
    }

    /** True if this class is a strict (direct or indirect) subclass of {@code other}. */
    public final boolean isSubclassOf(HlaClass<?> other) {
        return this != other && isSameOrSubclassOf(other);
    }

    /** Declared sharing, or null when no module declared one. */
    public final Sharing getSharing() {
        return sharing;
    }

    /** Sharing as written in the FOM (useful if it did not parse), or null. */
    public final String getSharingText() {
        return sharingText;
    }

    /** Semantics (description) from the first module that provided one, or null. */
    public final String getSemantics() {
        return semantics;
    }

    /** Dimensions declared directly on this class (2010: interaction classes; 2025: object and interaction classes). */
    public final List<Dimension> getDeclaredDimensions() {
        return declaredDimensions;
    }

    /**
     * Declared dimensions of this class and all its superclasses (HLA 4 inheritance rule), in order
     * root first, without duplicates.
     */
    public final List<Dimension> getDimensions() {
        return dimensions;
    }

    /** All modules that mention this class (including as scaffolding), in merge order. */
    public final List<FomModule> getModules() {
        return modules;
    }

    /**
     * Modules that contribute content to the class (properties, members or dimensions), as opposed
     * to only declaring it as scaffolding to position subclasses. In merge order.
     */
    public final List<FomModule> getDefiningModules() {
        return definingModules;
    }

    /**
     * True when no module gives this class any content; it only exists to position subclasses
     * (a pure scaffolding class).
     */
    public final boolean isScaffoldingOnly() {
        return definingModules.isEmpty();
    }

    /** Unrecognised simple class-level elements (element name to text), e.g. from a newer OMT. */
    public final Map<String, String> getOtherProperties() {
        return otherProperties;
    }

    /** Names of unrecognised structured class-level elements that were ignored. */
    public final List<String> getUnknownElements() {
        return unknownElements;
    }

    static <T> Set<T> linkedSet() {
        return new LinkedHashSet<T>();
    }

    @Override
    public String toString() {
        return getKind() + " " + qualifiedName + " [id=" + getId() + "]";
    }
}
