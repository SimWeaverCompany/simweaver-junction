package com.simweaver.kafka.record;

import java.io.IOException;
import java.util.Arrays;

/**
 * Snappy decompression, for record batches other producers compressed with snappy: the raw block
 * format, and the xerial framing the Java client writes ({@code 0x82 SNAPPY 0x00}, two int32
 * versions, then blocks of int32 length + raw snappy). No compression: this client sends gzip or
 * nothing.
 */
public final class Snappy {

    private Snappy() {
    }

    private static final byte[] XERIAL = {(byte) 0x82, 'S', 'N', 'A', 'P', 'P', 'Y', 0};

    /** Largest output accepted (a batch cannot be larger than Kafka allows anyway). */
    static final int MAX_OUTPUT = 256 * 1024 * 1024;

    /** Decompresses either form. */
    public static byte[] decompress(byte[] in) throws IOException {
        if (in.length >= 16 && Arrays.equals(Arrays.copyOf(in, 8), XERIAL)) {
            return xerial(in);
        }
        return raw(in, 0, in.length);
    }

    private static byte[] xerial(byte[] in) throws IOException {
        Output out = new Output(in.length * 4);
        int p = 16;                                           // magic, version, compatible version
        while (p < in.length) {
            if (in.length - p < 4) {
                throw new IOException("snappy: truncated xerial block length");
            }
            int n = ((in[p] & 0xFF) << 24) | ((in[p + 1] & 0xFF) << 16) | ((in[p + 2] & 0xFF) << 8) | (in[p + 3] & 0xFF);
            p += 4;
            if (n < 0 || n > in.length - p) {
                throw new IOException("snappy: bad xerial block length " + n);
            }
            byte[] b = raw(in, p, n);
            out.write(b, 0, b.length);
            p += n;
        }
        return out.toBytes();
    }

    /** Decompresses one raw snappy block. */
    public static byte[] raw(byte[] in, int off, int len) throws IOException {
        int end = off + len;
        int p = off;
        // preamble: uncompressed length, uvarint
        long n = 0;
        int shift = 0;
        while (true) {
            if (p >= end || shift > 28) {
                throw new IOException("snappy: bad length preamble");
            }
            int b = in[p++] & 0xFF;
            n |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        if (n > MAX_OUTPUT) {
            throw new IOException("snappy: output too large: " + n);
        }
        byte[] out = new byte[(int) n];
        int o = 0;
        while (p < end) {
            int tag = in[p++] & 0xFF;
            int kind = tag & 3;
            if (kind == 0) {                                   // literal
                int l = tag >>> 2;
                if (l >= 60) {
                    int extra = l - 59;
                    if (end - p < extra) {
                        throw new IOException("snappy: truncated literal length");
                    }
                    l = 0;
                    for (int i = 0; i < extra; i++) {
                        l |= (in[p++] & 0xFF) << (8 * i);
                    }
                }
                l += 1;
                if (l <= 0 || l > end - p || l > out.length - o) {
                    throw new IOException("snappy: bad literal");
                }
                System.arraycopy(in, p, out, o, l);
                p += l;
                o += l;
                continue;
            }
            int length;
            int offset;
            if (kind == 1) {
                if (p >= end) {
                    throw new IOException("snappy: truncated copy");
                }
                length = 4 + ((tag >>> 2) & 7);
                offset = ((tag >>> 5) << 8) | (in[p++] & 0xFF);
            } else if (kind == 2) {
                if (end - p < 2) {
                    throw new IOException("snappy: truncated copy");
                }
                length = (tag >>> 2) + 1;
                offset = (in[p] & 0xFF) | ((in[p + 1] & 0xFF) << 8);
                p += 2;
            } else {
                if (end - p < 4) {
                    throw new IOException("snappy: truncated copy");
                }
                length = (tag >>> 2) + 1;
                offset = (in[p] & 0xFF) | ((in[p + 1] & 0xFF) << 8) | ((in[p + 2] & 0xFF) << 16) | ((in[p + 3] & 0xFF) << 24);
                p += 4;
            }
            if (offset <= 0 || offset > o || length > out.length - o) {
                throw new IOException("snappy: bad copy (offset " + offset + ", length " + length + ")");
            }
            for (int i = 0; i < length; i++) {                // may overlap: byte by byte
                out[o] = out[o - offset];
                o++;
            }
        }
        if (o != out.length) {
            throw new IOException("snappy: " + o + " octets decoded, " + out.length + " announced");
        }
        return out;
    }

    /** A growing byte buffer. */
    static final class Output {
        private byte[] b;
        private int n;

        Output(int capacity) {
            b = new byte[Math.max(64, capacity)];
        }

        void write(byte[] src, int off, int len) throws IOException {
            ensure(len);
            System.arraycopy(src, off, b, n, len);
            n += len;
        }

        void write(int v) throws IOException {
            ensure(1);
            b[n++] = (byte) v;
        }

        void ensure(int more) throws IOException {
            if ((long) n + more > MAX_OUTPUT) {
                throw new IOException("decompressed batch too large");
            }
            if (n + more > b.length) {
                b = Arrays.copyOf(b, Math.max(b.length * 2, n + more));
            }
        }

        byte get(int i) {
            return b[i];
        }

        int size() {
            return n;
        }

        byte[] toBytes() {
            return Arrays.copyOf(b, n);
        }
    }
}
