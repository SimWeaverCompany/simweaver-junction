package com.simweaver.rift.api;

import com.simweaver.common.hla.decoders.DecodedValue;
import com.simweaver.common.hla.decoders.Reference;
import com.simweaver.common.hla.decoders.ValueKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Adapts the decoder's {@link DecodedValue} tree to {@link ValueNode}. A thin wrapper: it copies
 * nothing and wraps children only when they are asked for.
 *
 * <p>Mapping (DecodedValue is immutable and thread safe, so this is too):
 * <ul>
 *   <li>{@code getChildren()} holds the wire children (record fields; variant discriminant and
 *       alternative; array elements {@code [0]}, {@code [1]} ...) and, flagged
 *       {@code isDerived()}, the values computed by formatters (RPR Latitude, Heading ...). The
 *       first become {@link #fields()} / {@link #items()}, the second {@link #derived()}.</li>
 *   <li>Unsigned 64-bit integers ({@code isUnsigned()} with a negative long) are sent as decimal
 *       strings; other large integers are made safe by {@link RiftJson}.</li>
 *   <li>The decoder cuts long display texts with an ellipsis and rejects arrays above its element
 *       limit with an error node, so {@link #truncated()} stays false here; RiftJson caps
 *       arrays itself.</li>
 * </ul>
 */
public final class DecodedValueNode implements ValueNode {

    /** Max octets of a BYTES value sent as hex (same as the decoder's default rawCap). */
    public static final int BYTES_HEX_CAP = 256;

    private final DecodedValue v;

    private DecodedValueNode(DecodedValue v) {
        this.v = v;
    }

    /** Wraps a decoded value; null stays null (serialised as an EMPTY node). */
    public static ValueNode of(DecodedValue v) {
        return v == null ? null : new DecodedValueNode(v);
    }

    /** The wrapped value. */
    public DecodedValue unwrap() {
        return v;
    }

    @Override
    public String kind() {
        return v.getKind().name();
    }

    @Override
    public String text() {
        return v.getText();                          // never null
    }

    @Override
    public Object scalar() {
        switch (v.getKind()) {
            case INTEGER:
            case ENUM:
                return integer();
            case FLOAT:
                return v.getDouble();
            case BOOLEAN:
                return v.getBoolean();
            case TEXT:
                return v.getString();                // exact string; null for formatter-only text
            case BYTES:
                return v.getRawHex(BYTES_HEX_CAP);   // capped hex
            default:
                return null;
        }
    }

    private Object integer() {
        long l = v.getLong();
        return v.isUnsigned() && l < 0 ? Long.toUnsignedString(l) : (Object) l;
    }

    @Override
    public String enumName() {
        return v.getEnumName();
    }

    @Override
    public String units() {
        return v.getUnits();
    }

    @Override
    public String type() {
        return v.getDataTypeName();
    }

    @Override
    public List<Named> fields() {
        ValueKind k = v.getKind();
        if (k != ValueKind.RECORD && k != ValueKind.VARIANT) return Collections.emptyList();
        return named(false);
    }

    @Override
    public List<ValueNode> items() {
        if (v.getKind() != ValueKind.ARRAY) return Collections.emptyList();
        List<DecodedValue> c = v.getChildren();
        List<ValueNode> out = new ArrayList<>(c.size());
        for (DecodedValue d : c) if (!d.isDerived()) out.add(new DecodedValueNode(d));
        return out;
    }

    @Override
    public int count() {
        if (v.getKind() != ValueKind.ARRAY) return 0;
        int n = 0;
        for (DecodedValue d : v.getChildren()) if (!d.isDerived()) n++;
        return n;
    }

    @Override
    public List<Named> derived() {
        return named(true);
    }

    @Override
    public String refKind() {
        Reference r = v.getReference();
        return r == null || r.isEmpty() ? null : r.getKind();
    }

    @Override
    public List<String> refKeys() {
        Reference r = v.getReference();
        return r == null ? Collections.<String>emptyList() : r.getKeys();
    }

    @Override
    public String error() {
        return v.getError();
    }

    @Override
    public List<String> warnings() {
        return v.getWarnings();                      // never null
    }

    /** Wire children (derived = false) or formatter results (derived = true), with their names. */
    private List<Named> named(boolean derived) {
        List<DecodedValue> c = v.getChildren();
        if (c.isEmpty()) return Collections.emptyList();
        List<Named> out = new ArrayList<>(c.size());
        for (DecodedValue d : c) {
            if (d.isDerived() == derived) out.add(new Named(d.getName(), new DecodedValueNode(d)));
        }
        return out;
    }
}
