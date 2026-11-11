package com.simweaver.kafka;

/**
 * A failure of the Kafka client: a broker error code, a connection or protocol problem, or a
 * setting that cannot work. {@link #code()} is the Kafka error code when a broker returned one
 * ({@link com.simweaver.kafka.wire.KafkaErrors}), otherwise -1.
 */
public class KafkaException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int code;

    public KafkaException(String message) {
        this(-1, message, null);
    }

    public KafkaException(String message, Throwable cause) {
        this(-1, message, cause);
    }

    public KafkaException(int code, String message) {
        this(code, message, null);
    }

    public KafkaException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** @return the broker's error code, or -1 when the failure is not one */
    public int code() {
        return code;
    }
}
