package com.simweaver.common.hla.decoders;

import com.simweaver.common.iem.BasicDataType;

import java.util.Locale;
import java.util.Map;

/**
 * How the octets of a basic data representation are read. The OMT basic table states size
 * and endianness only, so the interpretation comes from the name (standard HLA and RPR
 * representations), then from the interpretation text, then from overrides in
 * {@link DecoderOptions.Builder#basicInterpretation(String, BasicInterpretation)}.
 */
public enum BasicInterpretation {
    /** Two's complement signed integer. */
    SIGNED,
    /** Unsigned integer (64-bit values above 2^63 are kept as unsigned longs). */
    UNSIGNED,
    /** IEEE 754 binary32 or binary64. */
    FLOAT,
    /** Opaque octets, shown as hex (for representations wider than 64 bits). */
    OPAQUE;

    /**
     * Resolves the interpretation of a basic representation.
     *
     * @param type      the basic datatype
     * @param overrides overrides by name
     * @return the interpretation
     */
    public static BasicInterpretation of(BasicDataType type, Map<String, BasicInterpretation> overrides) {
        BasicInterpretation o = overrides.get(type.getName());
        int octets = type.getSizeOctets();
        if (o != null) {
            return fit(o, octets);
        }
        if (octets > 8) {
            return OPAQUE;
        }
        String n = type.getName().toLowerCase(Locale.ROOT);
        if (n.startsWith("hlainteger")) {
            return SIGNED;
        }
        if (n.startsWith("hlafloat")) {
            return fit(FLOAT, octets);
        }
        if (n.startsWith("hlaunsigned") || n.startsWith("rprunsigned") || n.equals("hlaoctet")
                || n.startsWith("hlaoctetpair")) {
            return UNSIGNED;
        }
        if (type.isFloatingPointHint()) {
            return fit(FLOAT, octets);
        }
        if (type.isUnsignedHint() || n.contains("octet") || n.contains("byte")) {
            return UNSIGNED;
        }
        return SIGNED;
    }

    private static BasicInterpretation fit(BasicInterpretation i, int octets) {
        if (i == FLOAT && octets != 4 && octets != 8) {
            return octets > 8 ? OPAQUE : UNSIGNED;
        }
        if ((i == SIGNED || i == UNSIGNED) && octets > 8) {
            return OPAQUE;
        }
        return i;
    }
}
