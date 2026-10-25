package com.simweaver.common.dis.cdis;

/** A C-DIS PDU is shorter than its fields, or a field holds a value it cannot hold. */
public final class CdisFormatException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CdisFormatException(String message) {
        super(message, null, false, false);
    }
}
