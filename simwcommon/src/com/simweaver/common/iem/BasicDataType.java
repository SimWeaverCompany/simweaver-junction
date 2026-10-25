package com.simweaver.common.iem;

/**
 * Basic data representation (OMT basicData): the leaves of every encoding, e.g.
 * {@code HLAinteger32BE} or RPR's {@code RPRunsignedInteger16BE}.
 */
public final class BasicDataType extends DataType {

    int sizeBits;
    String interpretation;
    Endianness endianness;

    BasicDataType(String name) {
        super(name);
    }

    @Override
    public DataTypeCategory getCategory() {
        return DataTypeCategory.BASIC;
    }

    /** Size in bits. */
    public int getSizeBits() {
        return sizeBits;
    }

    /** Size in octets, rounded up. */
    public int getSizeOctets() {
        return (sizeBits + 7) / 8;
    }

    /** Byte order. */
    public Endianness getEndianness() {
        return endianness;
    }

    /** Free-text interpretation, e.g. "Integer in the range [-2^31, 2^31 - 1]". */
    public String getInterpretation() {
        return interpretation;
    }

    /**
     * Best-effort hint: true when the name or interpretation says the integer is unsigned
     * (HLA 4 HLAunsignedInteger*, RPRunsignedInteger*, or "[0, 2^n-1]" interpretations).
     */
    public boolean isUnsignedHint() {
        String n = getName().toLowerCase(java.util.Locale.ROOT);
        if (n.contains("unsigned")) {
            return true;
        }
        String i = interpretation == null ? "" : interpretation.replace(" ", "");
        return i.contains("[0,2^") || i.toLowerCase(java.util.Locale.ROOT).contains("unsigned");
    }

    /** Best-effort hint: true when name or interpretation indicates IEEE floating point. */
    public boolean isFloatingPointHint() {
        String n = getName().toLowerCase(java.util.Locale.ROOT);
        String i = interpretation == null ? "" : interpretation.toLowerCase(java.util.Locale.ROOT);
        return n.contains("float") || i.contains("floating") || i.contains("ieee 754") || i.contains("ieee754");
    }
}
