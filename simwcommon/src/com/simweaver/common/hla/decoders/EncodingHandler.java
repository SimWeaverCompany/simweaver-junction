package com.simweaver.common.hla.decoders;

import com.simweaver.common.iem.DataType;

/**
 * Decodes one array, fixed record or variant record encoding (the {@code <encoding>} of an
 * OMT array/record table entry), e.g. {@code HLAvariableArray} or {@code RPRlengthlessArray}.
 * <p>
 * The standard IEEE 1516.2 encodings and the RPR/NETN ones are built in. A federation
 * specific encoding is added with
 * {@link DecoderOptions.Builder#encodingHandler(String, EncodingHandler)}, which also replaces
 * a built-in one. Implementations must be stateless and thread safe.
 * <p>
 * Alignment follows IEEE 1516.2: every element starts on its octet boundary, counted from the
 * start of the top-level value; {@link DecodeContext#decode(DataType, String)} aligns nested
 * values itself, a handler aligns only what it reads directly (counts, lengths).
 */
public interface EncodingHandler {

    /**
     * Octet boundary of a datatype with this encoding. Called repeatedly while boundaries are
     * resolved to a fixed point (recursive datatypes), so it must only combine the current
     * values from {@code layouts}.
     *
     * @param type    the datatype
     * @param layouts boundaries and sizes of other datatypes
     * @return the boundary, a power of two, at least 1
     */
    int octetBoundary(DataType type, TypeLayouts layouts);

    /**
     * Encoded size when it is the same for every value, otherwise -1.
     *
     * @param type    the datatype
     * @param layouts sizes of other datatypes
     * @return octets, or -1
     */
    int fixedSize(DataType type, TypeLayouts layouts);

    /**
     * A lower bound of the encoded size (used to reject impossible counts early).
     *
     * @param type    the datatype
     * @param layouts sizes of other datatypes
     * @return octets, at least 0
     */
    int minSize(DataType type, TypeLayouts layouts);

    /**
     * Decodes one value at the current position. The handler aligns to the datatype's octet
     * boundary itself.
     *
     * @param type the datatype
     * @param name node name (member, field or element name)
     * @param ctx  decoding context
     * @return the decoded node, raw range set
     * @throws HlaDecodeException when the data cannot be decoded
     */
    DecodedValue decode(DataType type, String name, DecodeContext ctx);
}
