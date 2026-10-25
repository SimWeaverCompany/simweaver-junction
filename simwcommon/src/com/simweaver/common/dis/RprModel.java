package com.simweaver.common.dis;

/**
 * The FOM as the DIS datasources need it: IEM ids of classes and members, looked up by name.
 * Every lookup returns {@link #NO_ID} for a name the FOM does not have, so the mapping can skip
 * what an older or a reduced FOM lacks.
 *
 * <p>Implementations must be thread safe.
 */
public interface RprModel {

    int NO_ID = 0;

    /** @param qualifiedName e.g. "HLAobjectRoot.BaseEntity.PhysicalEntity.Platform.Aircraft" */
    int objectClassId(String qualifiedName);

    /** Id of an attribute as seen on the class (inherited ones included). */
    int attributeId(String objectClassQualifiedName, String attributeName);

    /** @param qualifiedName e.g. "HLAinteractionRoot.WeaponFire" */
    int interactionClassId(String qualifiedName);

    int parameterId(String interactionClassQualifiedName, String parameterName);

    /**
     * The class itself when the FOM has it, else its nearest superclass in the FOM (by the
     * dotted name), else null.
     */
    default String nearestObjectClass(String qualifiedName) {
        String c = qualifiedName;
        while (c != null) {
            if (objectClassId(c) != NO_ID) return c;
            int dot = c.lastIndexOf('.');
            c = dot < 0 ? null : c.substring(0, dot);
        }
        return null;
    }
}
