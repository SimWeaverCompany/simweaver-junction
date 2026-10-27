package com.simweaver.junction;

/** A gateway could not be started; {@link #getCode()} is the REST error code. */
public class GatewayException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String code;
    private transient volatile boolean logged;     // 0.9.22: gateway.startfailed was logged for it

    /**
     * @param code    REST error code (RTI_NOT_AVAILABLE, CONNECT_FAILED, CONFIG_INVALID, TARGET_CONFLICT ...)
     * @param message what happened
     * @param cause   the cause, or null
     */
    public GatewayException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    boolean isLogged() {
        return logged;
    }

    GatewayException markLogged() {
        logged = true;
        return this;
    }

    /** @return the error code */
    public String getCode() {
        return code;
    }
}
