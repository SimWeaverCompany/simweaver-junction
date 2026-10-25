package com.simweaver.rift.db;

import com.simweaver.common.hla.decoders.DecodedMember;
import com.simweaver.common.hla.decoders.DecodedMembers;
import com.simweaver.common.hla.decoders.HlaDataDecoder;
import com.simweaver.common.hla.decoders.RawValues;
import com.simweaver.rift.api.DecodedValueNode;
import com.simweaver.rift.api.ValueNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How the database turns received octets into value nodes. {@link #of(HlaDataDecoder)} is the
 * normal implementation; tests and DIS datasources can supply their own.
 *
 * <p>Implementations must be thread safe: the database calls them from its decode threads,
 * several updates at a time.
 */
public interface RiftValueDecoder {

    /**
     * Decodes one attribute update.
     *
     * @return decoded value by attribute name; attributes missing from the map are stored without
     *         a value
     */
    Map<String, ValueNode> decodeAttributes(RawValues raw, List<RiftAttribute> attributes);

    /**
     * Decodes a captured update for which the datasource passed no RiftAttributes: one member per
     * captured value, in capture order, named from the model. Default: not supported (empty).
     */
    default List<Member> decodeCaptured(RawValues raw) {
        return Collections.emptyList();
    }

    /** One decoded member of a captured update (see {@link #decodeCaptured}). */
    final class Member {
        /** IEM attribute id, or null when the handle was not in the model. */
        public final Integer iemId;
        public final String name;
        public final byte[] raw;
        public final ValueNode value;

        public Member(Integer iemId, String name, byte[] raw, ValueNode value) {
            this.iemId = iemId;
            this.name = name;
            this.raw = raw;
            this.value = value;
        }
    }

    /** Decodes one parameter of an interaction of class {@code interactionClassId} (IEM id). */
    ValueNode decodeParameter(Integer interactionClassId, RiftParameter parameter, long receivedMillis);

    /** The HLA decoder: one {@code decode(raw)} per update, one {@code decodeParameter} per parameter. */
    static RiftValueDecoder of(HlaDataDecoder decoder) {
        if (decoder == null) throw new IllegalArgumentException("decoder must not be null");
        return new RiftValueDecoder() {
            @Override
            public Map<String, ValueNode> decodeAttributes(RawValues raw, List<RiftAttribute> attributes) {
                Map<String, ValueNode> out = new HashMap<>();
                if (raw == null) return out;
                DecodedMembers members = decoder.decode(raw);
                if (members == null) return out;
                for (RiftAttribute a : attributes) {
                    DecodedMember m = members.get(a.getName());
                    if (m != null) out.put(a.getName(), DecodedValueNode.of(m.getValue()));
                }
                return out;
            }

            @Override
            public List<Member> decodeCaptured(RawValues raw) {
                List<Member> out = new ArrayList<>();
                if (raw == null) return out;
                List<DecodedMember> members = decoder.decode(raw).getMembers();     // capture order, one per value
                for (int i = 0; i < members.size() && i < raw.size(); i++) {
                    DecodedMember m = members.get(i);
                    out.add(new Member(m.getId() == 0 ? null : m.getId(), m.getName(), raw.getValue(i), DecodedValueNode.of(m.getValue())));
                }
                return out;
            }

            @Override
            public ValueNode decodeParameter(Integer interactionClassId, RiftParameter p, long receivedMillis) {
                if (interactionClassId == null || p.getIemId() == null) {
                    throw new IllegalArgumentException("parameter " + p.getName() + ": no IEM id (class " + interactionClassId + ")");
                }
                return DecodedValueNode.of(decoder.decodeParameter(interactionClassId, p.getIemId(), p.getRawValue(), receivedMillis));
            }
        };
    }
}
