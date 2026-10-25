package com.simweaver.common.hla.codec;

import com.simweaver.common.iem.DataType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * One value of an HLA datatype, independent of its encoding: what {@link HlaCodec} decodes to
 * and encodes from. Unlike the display tree of the decoders ({@code DecodedValue}) it keeps
 * exactly what is needed to write the value again, in the same or in another FOM.
 *
 * <ul>
 *   <li>{@link Kind#INT}: integers and enumerators (64-bit; unsigned 64-bit values keep their bits).
 *       An enumerated value also carries its enumerator name.</li>
 *   <li>{@link Kind#FLOAT}: binary32 / binary64.</li>
 *   <li>{@link Kind#TEXT}: arrays of characters (HLAASCIIchar, HLAunicodeChar, RTIobjectId ...),
 *       one Java char per element.</li>
 *   <li>{@link Kind#BYTES}: arrays of octets, and basic representations wider than 64 bits.</li>
 *   <li>{@link Kind#ARRAY}: other arrays, element by element.</li>
 *   <li>{@link Kind#RECORD}: fixed records, field name to value, in FOM order.</li>
 *   <li>{@link Kind#VARIANT}: discriminant value (+ enumerator name) and the alternative.</li>
 *   <li>{@link Kind#PADDING}: RPR padding arrays; the encoder computes the padding again.</li>
 * </ul>
 *
 * Every value remembers the datatype it was decoded as ({@link #getType()}, null when built by
 * hand). Immutable and thread safe; the byte arrays are not copied, callers must not change them.
 */
public final class HValue {

    /** What a value holds. */
    public enum Kind { INT, FLOAT, TEXT, BYTES, ARRAY, RECORD, VARIANT, PADDING }

    /** Variant layout read for an extendable variant (kept so that re-encoding writes the same layout). */
    public enum Layout { DEFAULT, PLAIN, LENGTH_PREFIXED }

    private static final byte[] NO_BYTES = new byte[0];
    private static final HValue PAD = new HValue(Kind.PADDING, null, 0, 0, null, null, null, null, null, Layout.DEFAULT);

    private final Kind kind;
    private final DataType type;
    private final long longValue;
    private final double doubleValue;
    private final String text;          // TEXT; INT: enumerator name; VARIANT: discriminant enumerator name
    private final byte[] bytes;         // BYTES
    private final List<String> names;   // RECORD: field names; VARIANT: [alternative name]
    private final List<HValue> values;  // RECORD fields, ARRAY elements, VARIANT [alternative value] (may be empty)
    private final String extra;         // VARIANT: alternative name
    private final Layout layout;

    private HValue(Kind kind, DataType type, long longValue, double doubleValue, String text, byte[] bytes,
                   List<String> names, List<HValue> values, String extra, Layout layout) {
        this.kind = kind;
        this.type = type;
        this.longValue = longValue;
        this.doubleValue = doubleValue;
        this.text = text;
        this.bytes = bytes;
        this.names = names;
        this.values = values;
        this.extra = extra;
        this.layout = layout;
    }

    // ------------------------------------------------------------------ factories

    /** @return an integer value */
    public static HValue ofInt(long v) {
        return new HValue(Kind.INT, null, v, v, null, null, null, null, null, Layout.DEFAULT);
    }

    /**
     * @param v          the enumerator value
     * @param enumerator its name, or null
     * @return an enumerated value
     */
    public static HValue ofEnum(long v, String enumerator) {
        return new HValue(Kind.INT, null, v, v, enumerator, null, null, null, null, Layout.DEFAULT);
    }

    /** @return a floating point value */
    public static HValue ofFloat(double v) {
        return new HValue(Kind.FLOAT, null, (long) v, v, null, null, null, null, null, Layout.DEFAULT);
    }

    /** @return a character array value (not null) */
    public static HValue ofText(String s) {
        return new HValue(Kind.TEXT, null, 0, 0, s == null ? "" : s, null, null, null, null, Layout.DEFAULT);
    }

    /** @return an octet array value; the array is not copied */
    public static HValue ofBytes(byte[] b) {
        return new HValue(Kind.BYTES, null, 0, 0, null, b == null ? NO_BYTES : b, null, null, null, Layout.DEFAULT);
    }

    /** @return an array value */
    public static HValue ofArray(List<HValue> elements) {
        return new HValue(Kind.ARRAY, null, 0, 0, null, null, null,
                Collections.unmodifiableList(new ArrayList<HValue>(elements)), null, Layout.DEFAULT);
    }

    /** @return a record value; iteration order of the map is the field order */
    public static HValue ofRecord(Map<String, HValue> fields) {
        List<String> n = new ArrayList<String>(fields.size());
        List<HValue> v = new ArrayList<HValue>(fields.size());
        for (Map.Entry<String, HValue> e : fields.entrySet()) {
            n.add(e.getKey());
            v.add(e.getValue());
        }
        return new HValue(Kind.RECORD, null, 0, 0, null, null, Collections.unmodifiableList(n),
                Collections.unmodifiableList(v), null, Layout.DEFAULT);
    }

    /**
     * @param discriminant     discriminant value
     * @param enumerator       discriminant enumerator name, or null
     * @param alternativeName  alternative (field) name, or null
     * @param alternativeValue the alternative's value, or null for an empty alternative
     * @return a variant value
     */
    public static HValue ofVariant(long discriminant, String enumerator, String alternativeName, HValue alternativeValue) {
        List<HValue> v = alternativeValue == null ? Collections.<HValue>emptyList() : Collections.singletonList(alternativeValue);
        return new HValue(Kind.VARIANT, null, discriminant, discriminant, enumerator, null, null, v, alternativeName, Layout.DEFAULT);
    }

    /** @return the padding placeholder */
    public static HValue padding() {
        return PAD;
    }

    /** @return this value marked as decoded from {@code t} */
    public HValue withType(DataType t) {
        if (t == type) {
            return this;
        }
        return new HValue(kind, t, longValue, doubleValue, text, bytes, names, values, extra, layout);
    }

    /** @return this variant with the layout it was read with */
    public HValue withLayout(Layout l) {
        return new HValue(kind, type, longValue, doubleValue, text, bytes, names, values, extra, l);
    }

    // ------------------------------------------------------------------ accessors

    /** @return the kind */
    public Kind getKind() {
        return kind;
    }

    /** @return the datatype this value was decoded as, or null */
    public DataType getType() {
        return type;
    }

    /** @return the datatype name, or null */
    public String getTypeName() {
        return type == null ? null : type.getName();
    }

    /** @return INT/VARIANT discriminant value; FLOAT truncated; 0 otherwise */
    public long asLong() {
        return kind == Kind.FLOAT ? (long) doubleValue : longValue;
    }

    /** @return FLOAT value; INT converted; 0 otherwise */
    public double asDouble() {
        return kind == Kind.FLOAT ? doubleValue : (double) longValue;
    }

    /** @return true for INT and FLOAT */
    public boolean isNumeric() {
        return kind == Kind.INT || kind == Kind.FLOAT;
    }

    /** @return TEXT as is (NULs kept), BYTES as ISO-8859-1, numbers as decimal, else "" */
    public String asText() {
        switch (kind) {
            case TEXT:
                return text;
            case BYTES:
                return new String(bytes, StandardCharsets.ISO_8859_1);
            case INT:
                return Long.toString(longValue);
            case FLOAT:
                return Double.toString(doubleValue);
            default:
                return "";
        }
    }

    /** @return TEXT without trailing NUL characters (the usual way to read a name) */
    public String asTrimmedText() {
        String s = asText();
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == 0) {
            end--;
        }
        return end == s.length() ? s : s.substring(0, end);
    }

    /** @return BYTES (not a copy); TEXT as ISO-8859-1; else empty */
    public byte[] asBytes() {
        if (kind == Kind.BYTES) {
            return bytes;
        }
        if (kind == Kind.TEXT) {
            return text.getBytes(StandardCharsets.ISO_8859_1);
        }
        return NO_BYTES;
    }

    /** @return INT: the enumerator name, VARIANT: the discriminant enumerator name; else null */
    public String getEnumerator() {
        return kind == Kind.INT || kind == Kind.VARIANT ? text : null;
    }

    /** @return VARIANT: the alternative name, or null */
    public String getAlternativeName() {
        return kind == Kind.VARIANT ? extra : null;
    }

    /** @return VARIANT: the alternative value, or null for an empty alternative */
    public HValue getAlternative() {
        return kind == Kind.VARIANT && !values.isEmpty() ? values.get(0) : null;
    }

    /** @return VARIANT: the layout it was decoded with */
    public Layout getLayout() {
        return layout;
    }

    /** @return ARRAY: elements; RECORD: field values; VARIANT: [alternative] or []; else [] */
    public List<HValue> getValues() {
        return values == null ? Collections.<HValue>emptyList() : values;
    }

    /** @return RECORD: field names; else [] */
    public List<String> getFieldNames() {
        return kind == Kind.RECORD ? names : Collections.<String>emptyList();
    }

    /** @return elements (ARRAY), fields (RECORD), characters (TEXT) or octets (BYTES) */
    public int size() {
        switch (kind) {
            case ARRAY:
            case RECORD:
                return values.size();
            case TEXT:
                return text.length();
            case BYTES:
                return bytes.length;
            default:
                return 0;
        }
    }

    /** @return ARRAY element or RECORD field by index */
    public HValue get(int i) {
        return values.get(i);
    }

    /** @return RECORD field by name, or null */
    public HValue field(String fieldName) {
        if (kind != Kind.RECORD || fieldName == null) {
            return null;
        }
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equals(fieldName)) {
                return values.get(i);
            }
        }
        return null;
    }

    /**
     * Follows a dotted path of record fields and variant alternatives, e.g.
     * {@code "FederateIdentifier.SiteID"}; a variant is passed through to its alternative.
     *
     * @return the value, or null
     */
    public HValue path(String dotted) {
        HValue v = this;
        for (String p : dotted.split("\\.")) {
            while (v != null && v.kind == Kind.VARIANT) {
                if (p.equals(v.extra)) {
                    break;
                }
                v = v.getAlternative();
            }
            if (v == null) {
                return null;
            }
            if (v.kind == Kind.VARIANT) {
                v = v.getAlternative();
            } else {
                v = v.field(p);
            }
        }
        return v;
    }

    // ------------------------------------------------------------------ transformation

    /** @return a copy of this record with one field replaced (or added at the end) */
    public HValue withField(String fieldName, HValue v) {
        if (kind != Kind.RECORD) {
            throw new IllegalStateException("not a record: " + kind);
        }
        Map<String, HValue> m = new LinkedHashMap<String, HValue>();
        for (int i = 0; i < names.size(); i++) {
            m.put(names.get(i), values.get(i));
        }
        m.put(fieldName, v);
        return ofRecord(m).withType(type);
    }

    /**
     * Rebuilds the tree bottom-up: {@code f} is applied to every node after its children; it
     * returns the node itself or a replacement. Unchanged subtrees are shared.
     *
     * @param f the transformation
     * @return the transformed tree
     */
    public HValue transform(Function<HValue, HValue> f) {
        HValue self = this;
        if (values != null && !values.isEmpty()) {
            List<HValue> nv = null;
            for (int i = 0; i < values.size(); i++) {
                HValue c = values.get(i);
                HValue t = c.transform(f);
                if (t != c) {
                    if (nv == null) {
                        nv = new ArrayList<HValue>(values);
                    }
                    nv.set(i, t);
                }
            }
            if (nv != null) {
                self = new HValue(kind, type, longValue, doubleValue, text, bytes, names,
                        Collections.unmodifiableList(nv), extra, layout);
            }
        }
        HValue r = f.apply(self);
        return r == null ? self : r;
    }

    // ------------------------------------------------------------------ object methods

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HValue)) {
            return false;
        }
        HValue v = (HValue) o;
        if (kind != v.kind) {
            return false;
        }
        switch (kind) {
            case INT:
                return longValue == v.longValue;
            case FLOAT:
                return Double.doubleToLongBits(doubleValue) == Double.doubleToLongBits(v.doubleValue);
            case TEXT:
                return text.equals(v.text);
            case BYTES:
                return Arrays.equals(bytes, v.bytes);
            case RECORD:
                return names.equals(v.names) && values.equals(v.values);
            case ARRAY:
                return values.equals(v.values);
            case VARIANT:
                return longValue == v.longValue && values.equals(v.values);
            default:
                return true;
        }
    }

    @Override
    public int hashCode() {
        switch (kind) {
            case INT:
            case VARIANT:
                return Long.hashCode(longValue) * 31 + kind.hashCode();
            case FLOAT:
                return Double.hashCode(doubleValue);
            case TEXT:
                return text.hashCode();
            case BYTES:
                return Arrays.hashCode(bytes);
            case RECORD:
            case ARRAY:
                return values.hashCode();
            default:
                return 0;
        }
    }

    /** Compact text, e.g. {@code {SiteID=1, ApplicationID=2}} or {@code "Tank-1"}. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        append(sb, 0);
        return sb.toString();
    }

    private void append(StringBuilder sb, int depth) {
        if (sb.length() > 4000) {
            sb.append('…');
            return;
        }
        switch (kind) {
            case INT:
                sb.append(longValue);
                if (text != null) {
                    sb.append('(').append(text).append(')');
                }
                break;
            case FLOAT:
                sb.append(doubleValue);
                break;
            case TEXT:
                sb.append('"').append(asTrimmedText()).append('"');
                break;
            case BYTES:
                sb.append("0x");
                for (int i = 0; i < Math.min(bytes.length, 32); i++) {
                    sb.append(Character.forDigit((bytes[i] >> 4) & 15, 16)).append(Character.forDigit(bytes[i] & 15, 16));
                }
                if (bytes.length > 32) {
                    sb.append("…(").append(bytes.length).append(')');
                }
                break;
            case ARRAY:
                sb.append('[');
                for (int i = 0; i < values.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    values.get(i).append(sb, depth + 1);
                }
                sb.append(']');
                break;
            case RECORD:
                sb.append('{');
                for (int i = 0; i < values.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(names.get(i)).append('=');
                    values.get(i).append(sb, depth + 1);
                }
                sb.append('}');
                break;
            case VARIANT:
                sb.append('<').append(text != null ? text : Long.toString(longValue));
                if (!values.isEmpty()) {
                    sb.append(' ').append(extra == null ? "" : extra + "=");
                    values.get(0).append(sb, depth + 1);
                }
                sb.append('>');
                break;
            default:
                sb.append("pad");
        }
    }
}
