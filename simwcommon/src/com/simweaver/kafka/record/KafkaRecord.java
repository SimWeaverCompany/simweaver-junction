package com.simweaver.kafka.record;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * One Kafka record: key and value (either may be null), headers, timestamp; and where it was read
 * from (topic, partition, offset) when it came from a broker. Arrays are not copied.
 */
public final class KafkaRecord {

    /** A record header. */
    public static final class Header {
        public final String key;
        public final byte[] value;

        public Header(String key, byte[] value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public String toString() {
            return key + "=" + (value == null ? "null" : new String(value, StandardCharsets.UTF_8));
        }
    }

    public final String topic;
    public final int partition;
    public final long offset;
    public final long timestamp;
    public final byte[] key;
    public final byte[] value;
    public final List<Header> headers;

    /** A record to send. */
    public KafkaRecord(byte[] key, byte[] value, List<Header> headers, long timestamp) {
        this(null, -1, -1, timestamp, key, value, headers);
    }

    public KafkaRecord(String topic, int partition, long offset, long timestamp, byte[] key, byte[] value, List<Header> headers) {
        this.topic = topic;
        this.partition = partition;
        this.offset = offset;
        this.timestamp = timestamp;
        this.key = key;
        this.value = value;
        this.headers = headers == null ? Collections.<Header>emptyList() : headers;
    }

    /** @return the value of the first header with this key, or null */
    public byte[] header(String k) {
        for (Header h : headers) {
            if (h.key.equals(k)) {
                return h.value;
            }
        }
        return null;
    }

    /** @return the record's size in a batch, roughly (for batch limits) */
    public int estimatedSize() {
        int n = 21 + (key == null ? 0 : key.length) + (value == null ? 0 : value.length);
        for (Header h : headers) {
            n += 10 + h.key.length() + (h.value == null ? 0 : h.value.length);
        }
        return n;
    }

    @Override
    public String toString() {
        return (topic == null ? "" : topic + "/" + partition + "@" + offset + " ")
                + (key == null ? "-" : new String(key, StandardCharsets.UTF_8)) + " " + (value == null ? "null" : value.length + " octets");
    }
}
