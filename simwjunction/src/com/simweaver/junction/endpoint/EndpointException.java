package com.simweaver.junction.endpoint;

/** An endpoint could not be opened or declared. {@link #getCode()} is a REST error code. */
public class EndpointException extends Exception {

    private static final long serialVersionUID = 1L;

    /** The RTI library for the endpoint is missing. */
    public static final String RTI_NOT_AVAILABLE = "RTI_NOT_AVAILABLE";
    /** The FOM could not be loaded. */
    public static final String FOM_INVALID = "FOM_INVALID";
    /** Connecting, joining or opening the socket failed. */
    public static final String CONNECT_FAILED = "CONNECT_FAILED";
    /** Bad settings or target. */
    public static final String CONFIG_INVALID = "CONFIG_INVALID";

    private final String code;

    /**
     * @param code    one of the constants
     * @param message what happened
     * @param cause   the cause, or null
     */
    public EndpointException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** @return the error code */
    public String getCode() {
        return code;
    }
}
