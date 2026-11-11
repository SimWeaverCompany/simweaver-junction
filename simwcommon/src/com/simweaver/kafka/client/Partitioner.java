package com.simweaver.kafka.client;

/**
 * Partition choice for keyed records, the same as the Java client's default partitioner (and
 * other clients in their compatible mode): {@code (murmur2(key) & 0x7fffffff) % partitions}. So a
 * key lands in the same partition whichever client wrote it, and its records stay in order.
 */
public final class Partitioner {

    private Partitioner() {
    }

    /** @return the partition of a key */
    public static int partition(byte[] key, int partitions) {
        return (murmur2(key) & 0x7FFFFFFF) % partitions;
    }

    /** Kafka's murmur2 (seed 0x9747b28c). */
    public static int murmur2(byte[] data) {
        int length = data.length;
        final int m = 0x5bd1e995;
        final int r = 24;
        int h = 0x9747b28c ^ length;
        int length4 = length / 4;
        for (int i = 0; i < length4; i++) {
            int i4 = i * 4;
            int k = (data[i4] & 0xff) + ((data[i4 + 1] & 0xff) << 8) + ((data[i4 + 2] & 0xff) << 16) + ((data[i4 + 3] & 0xff) << 24);
            k *= m;
            k ^= k >>> r;
            k *= m;
            h *= m;
            h ^= k;
        }
        switch (length % 4) {
            case 3:
                h ^= (data[(length & ~3) + 2] & 0xff) << 16;
                // fall through
            case 2:
                h ^= (data[(length & ~3) + 1] & 0xff) << 8;
                // fall through
            case 1:
                h ^= data[length & ~3] & 0xff;
                h *= m;
                break;
            default:
                break;
        }
        h ^= h >>> 13;
        h *= m;
        h ^= h >>> 15;
        return h;
    }
}
