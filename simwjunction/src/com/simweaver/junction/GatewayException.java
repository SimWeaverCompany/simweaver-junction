package com.simweaver.junction;

/** A gateway could not be started; {@link #getCode()} is the REST error code. */
public class GatewayException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String code;

    /**
     * @param code    REST error code (RTI_NOT_AVAILABLE, CONNECT_FAILED, CONFIG_INVALID, TARGET_CONFLICT ...)
     * @param message what happened
     * @param cause   the cause, or null
     */
    public GatewayException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** @return the error code */
    public String getCode() {
        return code;
    }
}
