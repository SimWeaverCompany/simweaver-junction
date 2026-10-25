package com.simweaver.common.hla.decoders;

import com.simweaver.common.iem.HlaClass;

import java.util.Collections;
import java.util.List;

/**
 * Decoded values of one attribute update or one interaction. Immutable.
 */
public final class DecodedMembers {

    private final RawValues.Kind kind;
    private final HlaClass<?> hlaClass;
    private final long receivedMillis;
    private final List<DecodedMember> members;

    DecodedMembers(RawValues.Kind kind, HlaClass<?> hlaClass, long receivedMillis, List<DecodedMember> members) {
        this.kind = kind;
        this.hlaClass = hlaClass;
        this.receivedMillis = receivedMillis;
        this.members = Collections.unmodifiableList(members);
    }

    /** @return attributes or parameters */
    public RawValues.Kind getKind() {
        return kind;
    }

    /** @return object class (known class) or interaction class; null when the class id was unknown */
    public HlaClass<?> getHlaClass() {
        return hlaClass;
    }

    /** @return receive time, or {@link HlaDataDecoder#NO_TIME} */
    public long getReceivedMillis() {
        return receivedMillis;
    }

    /** @return the members in capture order */
    public List<DecodedMember> getMembers() {
        return members;
    }

    /**
     * @param name member name
     * @return the member, or null
     */
    public DecodedMember get(String name) {
        for (DecodedMember m : members) {
            if (name.equals(m.getName())) {
                return m;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return (hlaClass == null ? "?" : hlaClass.getQualifiedName()) + members;
    }
}
