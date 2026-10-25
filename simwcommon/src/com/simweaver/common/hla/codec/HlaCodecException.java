package com.simweaver.common.hla.codec;

/** Thrown by {@link HlaCodec} when data does not fit its datatype, or a datatype cannot be used. */
public class HlaCodecException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** @param message what is wrong */
    public HlaCodecException(String message) {
        super(message);
    }
}
