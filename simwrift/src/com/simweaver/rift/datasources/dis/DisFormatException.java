package com.simweaver.rift.datasources.dis;

/** A PDU is shorter than its fields, or a field holds a value it cannot hold. */
public final class DisFormatException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DisFormatException(String message) {
        super(message, null, false, false);
    }
}
