package com.simweaver.common.iem;

import java.util.Collections;
import java.util.List;

/**
 * Declaration of an object class attribute (one row of the OMT attribute table).
 * Use {@link ObjectClass#getAttribute(String)} to see an attribute in the context of a particular
 * class, including whether it is inherited there.
 */
public final class Attribute extends ClassMember {

    ObjectClass declaringClass;
    UpdateType updateType;
    String updateTypeText;
    String updateCondition;
    OwnershipType ownership;
    String ownershipText;
    Sharing sharing;
    String sharingText;
    String transportationName;
    Transportation transportation;
    OrderType order;
    String orderText;
    List<Dimension> dimensions = Collections.emptyList();
    Boolean valueRequired;

    Attribute(String name) {
        super(name);
    }

    @Override
    public ElementKind getKind() {
        return ElementKind.ATTRIBUTE;
    }

    @Override
    public ObjectClass getDeclaringClass() {
        return declaringClass;
    }

    /** Update type, or null when absent/unrecognised (see {@link #getUpdateTypeText()}). */
    public UpdateType getUpdateType() {
        return updateType;
    }

    public String getUpdateTypeText() {
        return updateTypeText;
    }

    /** Free-text update condition, or null. */
    public String getUpdateCondition() {
        return updateCondition;
    }

    /** Ownership (D/A), or null when absent/unrecognised. */
    public OwnershipType getOwnership() {
        return ownership;
    }

    public String getOwnershipText() {
        return ownershipText;
    }

    /** Sharing, or null when absent/unrecognised. */
    public Sharing getSharing() {
        return sharing;
    }

    public String getSharingText() {
        return sharingText;
    }

    /** Transportation, or null when absent. */
    public Transportation getTransportation() {
        return transportation;
    }

    public String getTransportationName() {
        return transportationName;
    }

    /** Order, or null when absent/unrecognised. */
    public OrderType getOrder() {
        return order;
    }

    public String getOrderText() {
        return orderText;
    }

    /** Available dimensions (IEEE 1516-2010 attribute level DDM); empty when none. */
    public List<Dimension> getDimensions() {
        return dimensions;
    }

    /** IEEE 1516-2025 "value required"; null when the FOM does not say. */
    public Boolean getValueRequired() {
        return valueRequired;
    }
}
