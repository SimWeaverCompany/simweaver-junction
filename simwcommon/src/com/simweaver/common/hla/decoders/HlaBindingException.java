package com.simweaver.common.hla.decoders;

/**
 * Binding to the RTI failed as a whole (not connected, not joined, RTI internal error).
 * Individual names unknown to the federation are not errors; they are listed in the
 * {@link BindingReport}.
 */
public class HlaBindingException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * @param message what failed
     * @param cause   the RTI exception
     */
    public HlaBindingException(String message, Throwable cause) {
        super(message, cause);
    }
}
