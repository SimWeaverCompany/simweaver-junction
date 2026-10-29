package com.simweaver.j2j.wire;

/** Malformed or unacceptable J2J wire data. */
public class WireException extends Exception {

    private static final long serialVersionUID = 1L;

    public WireException(String message) {
        super(message);
    }
}
