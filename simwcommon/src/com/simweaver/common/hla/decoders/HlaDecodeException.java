package com.simweaver.common.hla.decoders;

/**
 * Thrown inside the decoder when data cannot be decoded. It carries the part of the value
 * tree decoded so far, so that every enclosing level can add its partial node and the caller
 * finally receives a tree with an {@link ValueKind#ERROR} node where decoding stopped.
 * <p>
 * The public {@code decode} methods of {@link HlaDataDecoder} never throw it; they return the
 * partial tree with {@link DecodedValue#getError()} set. Encoding handlers throw it (see
 * {@link DecodeContext#error(String)}) and must, when they catch it from a nested decode,
 * re-throw it with their own partial node ({@link #withPartial(DecodedValue)}).
 */
public final class HlaDecodeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient DecodedValue partial;

    /**
     * @param message what went wrong (at the innermost level)
     * @param partial partial value, or null
     */
    public HlaDecodeException(String message, DecodedValue partial) {
        super(message, null, false, false);
        this.partial = partial;
    }

    /** @return the partial value of the level that threw (or re-threw) last, or null */
    public DecodedValue getPartial() {
        return partial;
    }

    /**
     * @param newPartial the enclosing level's partial node
     * @return an exception with the same message and the new partial node
     */
    public HlaDecodeException withPartial(DecodedValue newPartial) {
        return new HlaDecodeException(getMessage(), newPartial);
    }
}
