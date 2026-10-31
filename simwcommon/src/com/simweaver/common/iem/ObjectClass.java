package com.simweaver.common.iem;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A merged HLA object class.
 * <p>
 * {@link #getDeclaredAttributes()} are the attributes this class declares itself (possibly
 * contributed by several FOM modules). {@link #getAttributes()} is the full set available on the
 * class - inherited ones first, from {@code HLAobjectRoot} down, then the declared ones - each
 * wrapped in a {@link ClassAttribute} that knows whether it is inherited.
 */
public final class ObjectClass extends HlaClass<ObjectClass> {

    List<Attribute> declaredAttributes = Collections.emptyList();
    List<ClassAttribute> attributes = Collections.emptyList();
    Map<String, ClassAttribute> attributesByName = Collections.emptyMap();
    Map<Integer, ClassAttribute> attributesById = Collections.emptyMap();
    List<InteractionClass> declaredDirectedInteractions = Collections.emptyList();
    List<InteractionClass> directedInteractions = Collections.emptyList();

    ObjectClass(String name) {
        super(name);
    }

    @Override
    public ElementKind getKind() {
        return ElementKind.OBJECT_CLASS;
    }

    /** Attributes declared by this class (not inherited), in merge order. */
    public List<Attribute> getDeclaredAttributes() {
        return declaredAttributes;
    }

    /** All attributes available on this class: inherited first (root down), then declared. */
    public List<ClassAttribute> getAttributes() {
        return attributes;
    }

    /** Number of attributes available on this class, inherited included. */
    public int getAttributeCount() {
        return attributes.size();
    }

    /** Available attribute by simple name (declared or inherited), or null. */
    public ClassAttribute getAttribute(String attributeName) {
        return attributeName == null ? null : attributesByName.get(attributeName.trim());
    }

    /** Available attribute by attribute id (declared or inherited), or null. */
    public ClassAttribute getAttribute(int attributeId) {
        return attributesById.get(attributeId);
    }

    /** True if the attribute (declared or inherited) is available on this class. */
    public boolean hasAttribute(int attributeId) {
        return attributesById.containsKey(attributeId);
    }

    /** True if an attribute with this name (declared or inherited) is available on this class. */
    public boolean hasAttribute(String attributeName) {
        return getAttribute(attributeName) != null;
    }

    /**
     * HLA 4 directed interactions declared directly on this class (best effort, see the package
     * notes); empty for HLA Evolved.
     */
    public List<InteractionClass> getDeclaredDirectedInteractions() {
        return declaredDirectedInteractions;
    }

    /** HLA 4 directed interactions of this class and its superclasses (inherited like attributes). */
    public List<InteractionClass> getDirectedInteractions() {
        return directedInteractions;
    }
}
