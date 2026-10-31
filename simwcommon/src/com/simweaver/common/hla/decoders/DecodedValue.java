package com.simweaver.common.hla.decoders;

import com.simweaver.common.iem.DataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One node of a decoded value tree. Immutable and thread safe.
 * <p>
 * Every node has a display text ({@link #getText()}), a {@link ValueKind}, the datatype it
 * was decoded as, and the octets it was decoded from ({@link #getRawOffset()},
 * {@link #getRawLength()}). Records, arrays and variants have children. Formatters may
 * replace the text (for example an RPR {@code EntityTypeStruct} becomes {@code 1.3.203.3.3.3.0}),
 * tag a {@link Reference}, add {@linkplain #isDerived() derived} children (latitude computed
 * from ECEF), and attach a typed object ({@link #getAttachment(Class)}).
 * <p>
 * The raw octets are not copied: a node keeps a reference to the buffer it was decoded from.
 * Callers must not modify a buffer after handing it to the decoder (the binding classes copy
 * the RTI's buffers before decoding).
 */
public final class DecodedValue {

    private static final byte[] NO_BYTES = new byte[0];
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final String name;
    private final String dataTypeName;
    private final DataType dataType;
    private final ValueKind kind;
    private final String text;
    private final Object value;
    private final boolean unsigned;
    private final String enumName;
    private final String units;
    private final boolean derived;
    private final Reference reference;
    private final String error;
    private final List<String> warnings;
    private final List<DecodedValue> children;
    private final Object attachment;
    private final byte[] raw;
    private final int rawOffset;
    private final int rawLength;

    private DecodedValue(Builder b) {
        this.name = b.name;
        this.dataTypeName = b.dataTypeName != null ? b.dataTypeName : (b.dataType != null ? b.dataType.getName() : null);
        this.dataType = b.dataType;
        this.kind = b.kind;
        this.text = b.text == null ? "" : b.text;
        this.value = b.value;
        this.unsigned = b.unsigned;
        this.enumName = b.enumName;
        this.units = b.units;
        this.derived = b.derived;
        this.reference = b.reference;
        this.error = b.error;
        this.warnings = b.warnings == null ? Collections.<String>emptyList()
            : Collections.unmodifiableList(new ArrayList<String>(b.warnings));
        this.children = b.children == null ? Collections.<DecodedValue>emptyList()
            : Collections.unmodifiableList(new ArrayList<DecodedValue>(b.children));
        this.attachment = b.attachment;
        this.raw = b.raw == null ? NO_BYTES : b.raw;
        this.rawOffset = b.raw == null ? 0 : b.rawOffset;
        this.rawLength = b.raw == null ? 0 : b.rawLength;
    }

    /**
     * @param kind the kind
     * @return a new builder
     */
    public static Builder builder(ValueKind kind) {
        return new Builder(kind);
    }

    /** @return a builder initialised with all properties of this node */
    public Builder toBuilder() {
        Builder b = new Builder(kind);
        b.name = name;
        b.dataTypeName = dataTypeName;
        b.dataType = dataType;
        b.text = text;
        b.value = value;
        b.unsigned = unsigned;
        b.enumName = enumName;
        b.units = units;
        b.derived = derived;
        b.reference = reference;
        b.error = error;
        b.warnings = warnings.isEmpty() ? null : new ArrayList<String>(warnings);
        b.children = children.isEmpty() ? null : new ArrayList<DecodedValue>(children);
        b.attachment = attachment;
        if (rawLength > 0 || raw != NO_BYTES) {
            b.raw = raw;
            b.rawOffset = rawOffset;
            b.rawLength = rawLength;
        }
        return b;
    }

    /** @return member, field or alternative name, {@code [i]} for array elements; may be null at the top */
    public String getName() {
        return name;
    }

    /** @return FOM datatype name, or null for derived values without a FOM datatype */
    public String getDataTypeName() {
        return dataTypeName;
    }

    /** @return the IEM datatype, or null for derived values */
    public DataType getDataType() {
        return dataType;
    }

    /** @return the kind */
    public ValueKind getKind() {
        return kind;
    }

    /** @return display text, never null (empty for an absent value) */
    public String getText() {
        return text;
    }

    /** @return true for {@link ValueKind#ERROR} or when {@link #getError()} is set */
    public boolean hasError() {
        return error != null;
    }

    /** @return the decoding error of this node, or null */
    public String getError() {
        return error;
    }

    /** @return non fatal remarks (unknown enumerator, missing terminator, trailing octets ...) */
    public List<String> getWarnings() {
        return warnings;
    }

    /** @return true when the value was computed by a formatter rather than read from the wire */
    public boolean isDerived() {
        return derived;
    }

    /** @return units (from the FOM simple datatype, or of a derived value), or null */
    public String getUnits() {
        return units;
    }

    /** @return the reference tag, or null when this value is not a reference */
    public Reference getReference() {
        return reference;
    }

    /** @return children: record fields, array elements, variant discriminant and alternative, derived values */
    public List<DecodedValue> getChildren() {
        return children;
    }

    /**
     * @param childName name of a direct child
     * @return the first child with that name, or null
     */
    public DecodedValue getChild(String childName) {
        for (DecodedValue c : children) {
            if (childName.equals(c.name)) {
                return c;
            }
        }
        return null;
    }

    /**
     * Follows a dotted path of child names, e.g. {@code "WorldLocation.X"}. For a variant the
     * alternative's name must be part of the path; {@link #getAlternative()} skips it.
     *
     * @param path dotted path
     * @return the node, or null
     */
    public DecodedValue find(String path) {
        DecodedValue v = this;
        int start = 0;
        while (v != null && start <= path.length()) {
            int dot = path.indexOf('.', start);
            String part = dot < 0 ? path.substring(start) : path.substring(start, dot);
            v = v.getChild(part);
            if (dot < 0) {
                return v;
            }
            start = dot + 1;
        }
        return v;
    }

    /** @return for a variant: the discriminant node, else null */
    public DecodedValue getDiscriminant() {
        return kind == ValueKind.VARIANT && !children.isEmpty() ? children.get(0) : null;
    }

    /** @return for a variant: the alternative node (null when absent or NA), else null */
    public DecodedValue getAlternative() {
        if (kind != ValueKind.VARIANT || children.size() < 2) {
            return null;
        }
        DecodedValue a = children.get(1);
        return a.derived ? null : a;
    }

    /** @return integer value, or the double truncated; 0 when not numeric */
    public long getLong() {
        if (value instanceof Long) {
            return (Long) value;
        }
        if (value instanceof Double) {
            return (long) ((Double) value).doubleValue();
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? 1L : 0L;
        }
        return 0L;
    }

    /** @return floating value, or the integer converted (unsigned aware); NaN when not numeric */
    public double getDouble() {
        if (value instanceof Double) {
            return (Double) value;
        }
        if (value instanceof Long) {
            long l = (Long) value;
            if (unsigned && l < 0) {
                return (double) (l >>> 1) * 2.0 + (l & 1L);
            }
            return (double) l;
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? 1.0 : 0.0;
        }
        return Double.NaN;
    }

    /**
     * The exact string of a {@link ValueKind#TEXT} node (not escaped, not cut, unlike
     * {@link #getText()}).
     *
     * @return the string, or null when the node holds no string
     */
    public String getString() {
        return value instanceof String ? (String) value : null;
    }

    /** @return true when the node holds a number (integer, float, enum value or boolean) */
    public boolean isNumeric() {
        return value instanceof Long || value instanceof Double || value instanceof Boolean;
    }

    /** @return true when {@link #getLong()} must be read as an unsigned 64-bit value */
    public boolean isUnsigned() {
        return unsigned;
    }

    /** @return boolean value; for numbers non-zero is true */
    public boolean getBoolean() {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return getLong() != 0L;
    }

    /** @return the enumerator name for {@link ValueKind#ENUM} and boolean enums, else null */
    public String getEnumName() {
        return enumName;
    }

    /** @return the enumerated value (same as {@link #getLong()} for enums) */
    public long getEnumValue() {
        return getLong();
    }

    /**
     * @param type expected attachment type
     * @param <T>  type
     * @return the attachment if it has that type, else null
     */
    public <T> T getAttachment(Class<T> type) {
        return type.isInstance(attachment) ? type.cast(attachment) : null;
    }

    /** @return offset of this node's octets in the buffer */
    public int getRawOffset() {
        return rawOffset;
    }

    /** @return number of octets this node was decoded from (0 for derived values) */
    public int getRawLength() {
        return rawLength;
    }

    /** @return a copy of the octets this node was decoded from */
    public byte[] getRawBytes() {
        byte[] out = new byte[rawLength];
        System.arraycopy(raw, rawOffset, out, 0, rawLength);
        return out;
    }

    /**
     * @param index 0-based index within this node's octets
     * @return the octet, 0..255
     */
    public int getRawByte(int index) {
        if (index < 0 || index >= rawLength) {
            throw new IndexOutOfBoundsException("index " + index + ", length " + rawLength);
        }
        return raw[rawOffset + index] & 0xff;
    }

    /**
     * Lower case hex of this node's octets.
     *
     * @param maxOctets cap; negative for no cap
     * @return hex text (without any marker when cut; see {@link #isRawTruncated(int)})
     */
    public String getRawHex(int maxOctets) {
        int n = maxOctets < 0 ? rawLength : Math.min(rawLength, maxOctets);
        char[] out = new char[n * 2];
        for (int i = 0; i < n; i++) {
            int b = raw[rawOffset + i] & 0xff;
            out[2 * i] = HEX[b >>> 4];
            out[2 * i + 1] = HEX[b & 15];
        }
        return new String(out);
    }

    /**
     * @param maxOctets cap
     * @return true when {@link #getRawHex(int)} with this cap leaves octets out
     */
    public boolean isRawTruncated(int maxOctets) {
        return maxOctets >= 0 && rawLength > maxOctets;
    }

    /**
     * Depth first search for the first node (this one included) with the given datatype name.
     *
     * @param typeName datatype name
     * @return the node, or null
     */
    public DecodedValue findByDataType(String typeName) {
        if (typeName.equals(dataTypeName)) {
            return this;
        }
        for (DecodedValue c : children) {
            DecodedValue f = c.findByDataType(typeName);
            if (f != null) {
                return f;
            }
        }
        return null;
    }

    /** @return the display text */
    @Override
    public String toString() {
        return text;
    }

    /**
     * Multi-line tree dump for logs and tests.
     *
     * @return the dump
     */
    public String dump() {
        StringBuilder sb = new StringBuilder();
        dump(sb, 0);
        return sb.toString();
    }

    private void dump(StringBuilder sb, int depth) {
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        sb.append(name == null ? "<value>" : name);
        if (dataTypeName != null) {
            sb.append(" : ").append(dataTypeName);
        }
        sb.append(" = ").append(text);
        if (units != null) {
            sb.append(' ').append(units);
        }
        if (derived) {
            sb.append("  (derived)");
        }
        if (reference != null) {
            sb.append("  ref ").append(reference);
        }
        if (error != null) {
            sb.append("  ERROR ").append(error);
        }
        for (String w : warnings) {
            sb.append("  warning: ").append(w);
        }
        sb.append('\n');
        for (DecodedValue c : children) {
            c.dump(sb, depth + 1);
        }
    }

    /** Builder; not thread safe. */
    public static final class Builder {
        private String name;
        private String dataTypeName;
        private DataType dataType;
        private ValueKind kind;
        private String text;
        private Object value;
        private boolean unsigned;
        private String enumName;
        private String units;
        private boolean derived;
        private Reference reference;
        private String error;
        private List<String> warnings;
        private List<DecodedValue> children;
        private Object attachment;
        private byte[] raw;
        private int rawOffset;
        private int rawLength;

        private Builder(ValueKind kind) {
            this.kind = kind;
        }

        /** @param v kind @return this */
        public Builder kind(ValueKind v) {
            this.kind = v;
            return this;
        }

        /** @param v name @return this */
        public Builder name(String v) {
            this.name = v;
            return this;
        }

        /** @param v IEM datatype (also sets the datatype name) @return this */
        public Builder dataType(DataType v) {
            this.dataType = v;
            if (v != null) {
                this.dataTypeName = v.getName();
            }
            return this;
        }

        /** @param v datatype name (for derived values without an IEM datatype) @return this */
        public Builder dataTypeName(String v) {
            this.dataTypeName = v;
            return this;
        }

        /** @param v display text @return this */
        public Builder text(String v) {
            this.text = v;
            return this;
        }

        /** @param v integer value @return this */
        public Builder longValue(long v) {
            this.value = v;
            return this;
        }

        /** @param v floating value @return this */
        public Builder doubleValue(double v) {
            this.value = v;
            return this;
        }

        /** @param v boolean value @return this */
        public Builder booleanValue(boolean v) {
            this.value = v;
            return this;
        }

        /** @param v exact string of a text value @return this */
        public Builder stringValue(String v) {
            this.value = v;
            return this;
        }

        /** @param v true when the long value is unsigned @return this */
        public Builder unsigned(boolean v) {
            this.unsigned = v;
            return this;
        }

        /** @param v enumerator name @return this */
        public Builder enumName(String v) {
            this.enumName = v;
            return this;
        }

        /** @param v units @return this */
        public Builder units(String v) {
            this.units = v;
            return this;
        }

        /** @param v derived flag @return this */
        public Builder derived(boolean v) {
            this.derived = v;
            return this;
        }

        /** @param v reference tag @return this */
        public Builder reference(Reference v) {
            this.reference = v;
            return this;
        }

        /** @param v error text @return this */
        public Builder error(String v) {
            this.error = v;
            return this;
        }

        /** @param v warning to add @return this */
        public Builder warning(String v) {
            if (warnings == null) {
                warnings = new ArrayList<String>(2);
            }
            warnings.add(v);
            return this;
        }

        /** @param v child to add @return this */
        public Builder child(DecodedValue v) {
            if (children == null) {
                children = new ArrayList<DecodedValue>();
            }
            children.add(v);
            return this;
        }

        /** @param v children replacing the current ones @return this */
        public Builder children(List<DecodedValue> v) {
            this.children = v == null ? null : new ArrayList<DecodedValue>(v);
            return this;
        }

        /** @param v attachment object @return this */
        public Builder attachment(Object v) {
            this.attachment = v;
            return this;
        }

        /**
         * @param buffer the decoded buffer (not copied)
         * @param offset start of this node's octets
         * @param length number of octets
         * @return this
         */
        public Builder raw(byte[] buffer, int offset, int length) {
            this.raw = buffer;
            this.rawOffset = offset;
            this.rawLength = length;
            return this;
        }

        /** @return the node */
        public DecodedValue build() {
            return new DecodedValue(this);
        }
    }
}
