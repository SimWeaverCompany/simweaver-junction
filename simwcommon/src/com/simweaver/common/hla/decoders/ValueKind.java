package com.simweaver.common.hla.decoders;

/**
 * What a {@link DecodedValue} holds.
 */
public enum ValueKind {
    /** Integer; see {@link DecodedValue#getLong()} and {@link DecodedValue#isUnsigned()}. */
    INTEGER,
    /** IEEE floating point; see {@link DecodedValue#getDouble()}. */
    FLOAT,
    /** Boolean, including boolean-like enumerations (HLAboolean, RPRboolean). */
    BOOLEAN,
    /** Enumerator; see {@link DecodedValue#getEnumName()} and {@link DecodedValue#getEnumValue()}. */
    ENUM,
    /** Character data (arrays of HLAASCIIchar / HLAunicodeChar, RTIobjectId, formatted text). */
    TEXT,
    /** Opaque octets (HLAopaqueData, octet arrays); the bytes are available raw. */
    BYTES,
    /** Fixed record; children are the fields. */
    RECORD,
    /** Array; children are the elements, named {@code [0]}, {@code [1]}, ... */
    ARRAY,
    /** Variant record; children are the discriminant and, when present, the alternative. */
    VARIANT,
    /** No data (a variant alternative of datatype NA). */
    EMPTY,
    /** Alignment padding that carries no information (RPR padding arrays). */
    PADDING,
    /** Could not be decoded; see {@link DecodedValue#getError()}. */
    ERROR
}
