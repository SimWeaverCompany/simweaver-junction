package com.simweaver.common.config;

/**
 * Thrown when a property is missing where one is required, or when a property value
 * cannot be interpreted as the requested type while {@link SimwProperties#setStrict(boolean)
 * strict mode} is enabled.
 *
 * <p>Unchecked on purpose: a malformed configuration file is a start-up defect, not a
 * condition application code is expected to recover from.</p>
 */
public class SimwPropertyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String key;

    public SimwPropertyException(String message) {
        this(null, message, null);
    }

    public SimwPropertyException(String key, String message) {
        this(key, message, null);
    }

    public SimwPropertyException(String key, String message, Throwable cause) {
        super(message, cause);
        this.key = key;
    }

    /** The property key this exception concerns, or {@code null} if not key specific. */
    public String getKey() {
        return key;
    }
}
