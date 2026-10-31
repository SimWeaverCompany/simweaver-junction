package com.simweaver.common.iem;

/**
 * A class member (attribute or parameter) seen from one particular class: either declared by that
 * class or inherited from a superclass.
 * <p>
 * Terminology used by the accessors:
 * <ul>
 * <li><b>class</b> - the class the member is viewed on ({@link #getClassId()})</li>
 * <li><b>declaring class</b> - the class whose definition contains the member; equals the class
 *     unless {@link #isInherited()}</li>
 * <li><b>parent class</b> - the superclass of the class the member is viewed on</li>
 * <li><b>child classes</b> - the direct subclasses of the class the member is viewed on, all of
 *     which inherit the member</li>
 * </ul>
 * The id of the view is the id of the member declaration ({@link #getId()}), which is the same on
 * every class that has the member, just like an HLA handle.
 *
 * @param <C> class type
 * @param <M> member declaration type
 */
public abstract class MemberOnClass<C extends HlaClass<C>, M extends ClassMember> {

    private final C hlaClass;
    private final M member;

    MemberOnClass(C hlaClass, M member) {
        this.hlaClass = hlaClass;
        this.member = member;
    }

    /** The member declaration. */
    public final M getDeclaration() {
        return member;
    }

    /** Member id (same as the declaration id; identical on every class that has the member). */
    public final int getId() {
        return member.getId();
    }

    /** Simple member name. */
    public final String getName() {
        return member.getName();
    }

    /** True if the member is declared in a superclass and inherited by this class. */
    public final boolean isInherited() {
        return member.getDeclaringClass() != hlaClass;
    }

    /** Canonical qualified name: declaring class + '.' + name. Unique in the model. */
    public final String getQualifiedName() {
        return member.getQualifiedName();
    }

    /** Qualified name relative to the viewed class: viewed class + '.' + name. */
    public final String getQualifiedNameOnClass() {
        return hlaClass.getQualifiedName() + "." + member.getName();
    }

    /** The class this member is viewed on. */
    public final C getHlaClass() {
        return hlaClass;
    }

    /** Id of the class this member is viewed on. */
    public final int getClassId() {
        return hlaClass.getId();
    }

    /** Id of the class declaring the member. */
    public final int getDeclaringClassId() {
        return member.getDeclaringClass().getId();
    }

    /** Superclass id of the class the member is viewed on, {@link IemElement#NO_ID} at the root. */
    public final int getParentClassId() {
        return hlaClass.getParentClassId();
    }

    /** Ids of the direct subclasses of the class the member is viewed on (fresh copy). */
    public final int[] getChildClassIds() {
        return hlaClass.getChildClassIds();
    }

    /** Datatype name as written in the FOM ({@code "NA"} or null when there is none). */
    public final String getDataTypeName() {
        return member.getDataTypeName();
    }

    /** Resolved datatype, or null. */
    public final DataType getDataType() {
        return member.getDataType();
    }

    /** Resolved datatype id, or {@link IemElement#NO_ID}. */
    public final int getDataTypeId() {
        return member.getDataTypeId();
    }

    @Override
    public String toString() {
        return getQualifiedNameOnClass() + " [id=" + getId() + ", type=" + getDataTypeName()
                + (isInherited() ? ", inherited from " + member.getDeclaringClass().getQualifiedName() : "") + "]";
    }
}
