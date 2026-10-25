package com.simweaver.common.hla.decoders;

import com.simweaver.common.iem.MemberOnClass;

/**
 * One decoded attribute or parameter value. Immutable.
 */
public final class DecodedMember {

    private final MemberOnClass<?, ?> member;
    private final String name;
    private final DecodedValue value;

    /**
     * @param member the attribute / parameter (as seen on its class), or null when unknown
     * @param name   member name, or a label for an unknown member
     * @param value  decoded value
     */
    public DecodedMember(MemberOnClass<?, ?> member, String name, DecodedValue value) {
        this.member = member;
        this.name = name;
        this.value = value;
    }

    /** @return the attribute or parameter, or null when the handle was not in the model */
    public MemberOnClass<?, ?> getMember() {
        return member;
    }

    /** @return member id, or 0 when unknown */
    public int getId() {
        return member == null ? 0 : member.getId();
    }

    /** @return name */
    public String getName() {
        return name;
    }

    /** @return decoded value */
    public DecodedValue getValue() {
        return value;
    }

    @Override
    public String toString() {
        return name + " = " + value.getText();
    }
}
