package com.simweaver.common.hla.session;

/**
 * An RTI service failed. The message starts with the simple name of the RTI's exception
 * class, e.g. {@code "FederationExecutionDoesNotExist: Blue"}, so it reads well in logs and
 * REST replies without the RTI classes.
 */
public class RtiSessionException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String rtiExceptionName;

    /**
     * @param rtiExceptionName simple name of the RTI exception class, or null
     * @param message          the message
     * @param cause            the original exception, or null
     */
    public RtiSessionException(String rtiExceptionName, String message, Throwable cause) {
        super(rtiExceptionName == null ? message : rtiExceptionName + ": " + message, cause);
        this.rtiExceptionName = rtiExceptionName;
    }

    /** @param message the message */
    public RtiSessionException(String message) {
        this(null, message, null);
    }

    /** Wraps any exception thrown by an RTI. */
    public static RtiSessionException of(Throwable t) {
        if (t instanceof RtiSessionException) {
            return (RtiSessionException) t;
        }
        String msg = t.getMessage() == null ? "" : t.getMessage();
        return new RtiSessionException(t.getClass().getSimpleName(), msg, t);
    }

    /** @return simple name of the RTI exception class (e.g. "FederationExecutionAlreadyExists"), or null */
    public String getRtiExceptionName() {
        return rtiExceptionName;
    }
}
