package com.simweaver.common.iem;

/**
 * Encoding names for array, fixed-record and variant-record datatypes that this package knows about.
 * <p>
 * The OMT lets a FOM name any encoding; the standard ones start with {@code HLA}, and the RPR FOM
 * (v2 and v3) and NETN define several of their own. A decoder can switch on this enum and fall back
 * to {@link #CUSTOM} (inspect {@link DataType#getEncoding()}) for anything else.
 */
public enum KnownEncoding {

    // --- IEEE 1516.2 standard encodings ---
    /** Array with a fixed number of elements, no length prefix. */
    HLA_FIXED_ARRAY("HLAfixedArray", true),
    /** Array preceded by a 32-bit element count (HLAinteger32BE). */
    HLA_VARIABLE_ARRAY("HLAvariableArray", true),
    /** Fields encoded in order, each aligned to its octet boundary. */
    HLA_FIXED_RECORD("HLAfixedRecord", true),
    /** Discriminant followed by the selected alternative. */
    HLA_VARIANT_RECORD("HLAvariantRecord", true),
    /** IEEE 1516-2025: variant record where each alternative carries its length, so new alternatives can be skipped. */
    HLA_EXTENDABLE_VARIANT_RECORD("HLAextendableVariantRecord", true),

    // --- de facto / FOM specific encodings seen in RPR and NETN ---
    /** Dynamic array without length prefix; length implied by the enclosing data (RPR v2 Link-16, NETN). */
    HLA_LENGTHLESS_VAR_ARRAY("HLAlengthlessVarArray", false),
    /** RPR: dynamic array without length prefix; consumes the rest of the enclosing buffer. */
    RPR_LENGTHLESS_ARRAY("RPRlengthlessArray", false),
    /** RPR: array terminated by a null element (typically a C string). */
    RPR_NULL_TERMINATED_ARRAY("RPRnullTerminatedArray", false),
    /** RPR: variable array padded to a 32-bit boundary. */
    RPR_PADDING_TO_32_ARRAY("RPRpaddingTo32Array", false),
    /** RPR: variable array padded to a 64-bit boundary. */
    RPR_PADDING_TO_64_ARRAY("RPRpaddingTo64Array", false),
    /** RPR: extended variant record. */
    RPR_EXTENDED_VARIANT_RECORD("RPRextendedVariantRecord", false),

    /** Any other (or absent) encoding name. */
    CUSTOM(null, false);

    private final String omtName;
    private final boolean standard;

    KnownEncoding(String omtName, boolean standard) {
        this.omtName = omtName;
        this.standard = standard;
    }

    /** Spelling used in FOM files; null for {@link #CUSTOM}. */
    public String getOmtName() {
        return omtName;
    }

    /** True for encodings defined by IEEE 1516.2. */
    public boolean isStandard() {
        return standard;
    }

    /** Maps an encoding name (exact, then case-insensitive match) to a constant; {@link #CUSTOM} otherwise. */
    public static KnownEncoding of(String encoding) {
        if (encoding == null) {
            return CUSTOM;
        }
        String t = encoding.trim();
        for (KnownEncoding k : values()) {
            if (k.omtName != null && k.omtName.equals(t)) {
                return k;
            }
        }
        for (KnownEncoding k : values()) {
            if (k.omtName != null && k.omtName.equalsIgnoreCase(t)) {
                return k;
            }
        }
        return CUSTOM;
    }
}
