package com.simweaver.kafka.wire;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads what {@link KWriter} writes. Every read checks the remaining length and throws
 * {@link KafkaProtocolException} on short or malformed input, never an index exception.
 */
public final class KReader {

    /** Malformed input. */
    public static final class KafkaProtocolException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public KafkaProtocolException(String message) {
            super(message);
        }
    }

    private final byte[] buf;
    private final int end;
    private int pos;

    public KReader(byte[] buf) {
        this(buf, 0, buf.length);
    }

    public KReader(byte[] buf, int off, int len) {
        if (off < 0 || len < 0 || off + len > buf.length) {
            throw new KafkaProtocolException("bad slice");
        }
        this.buf = buf;
        this.pos = off;
        this.end = off + len;
    }

    private void need(int n) {
        if (n < 0 || end - pos < n) {
            throw new KafkaProtocolException("short input: need " + n + " octets, have " + (end - pos));
        }
    }

    public int remaining() {
        return end - pos;
    }

    public int position() {
        return pos;
    }

    public int int8() {
        need(1);
        return buf[pos++];
    }

    public boolean bool() {
        return int8() != 0;
    }

    public int int16() {
        need(2);
        int v = (short) (((buf[pos] & 0xFF) << 8) | (buf[pos + 1] & 0xFF));
        pos += 2;
        return v;
    }

    public int int32() {
        need(4);
        int v = ((buf[pos] & 0xFF) << 24) | ((buf[pos + 1] & 0xFF) << 16) | ((buf[pos + 2] & 0xFF) << 8) | (buf[pos + 3] & 0xFF);
        pos += 4;
        return v;
    }

    public long int64() {
        long hi = int32() & 0xFFFFFFFFL;
        long lo = int32() & 0xFFFFFFFFL;
        return (hi << 32) | lo;
    }

    /** @return a string with an int16 length, or null for -1 */
    public String string() {
        int n = int16();
        if (n == -1) {
            return null;
        }
        if (n < 0) {
            throw new KafkaProtocolException("negative string length " + n);
        }
        need(n);
        String s = new String(buf, pos, n, StandardCharsets.UTF_8);
        pos += n;
        return s;
    }

    /** @return octets with an int32 length, or null for -1 */
    public byte[] bytes() {
        int n = int32();
        if (n == -1) {
            return null;
        }
        return raw(n);
    }

    /** @return an array count (int32): -1 = null; bounded by what is left, so garbage cannot allocate */
    public int count() {
        int n = int32();
        if (n < -1 || n > remaining()) {
            throw new KafkaProtocolException("bad array count " + n);
        }
        return n;
    }

    public List<Integer> int32s() {
        int n = count();
        List<Integer> l = new ArrayList<Integer>(Math.max(0, n));
        for (int i = 0; i < n; i++) {
            l.add(int32());
        }
        return l;
    }

    public List<String> strings() {
        int n = count();
        List<String> l = new ArrayList<String>(Math.max(0, n));
        for (int i = 0; i < n; i++) {
            l.add(string());
        }
        return l;
    }

    public byte[] raw(int n) {
        need(n);
        byte[] b = new byte[n];
        System.arraycopy(buf, pos, b, 0, n);
        pos += n;
        return b;
    }

    public void skip(int n) {
        need(n);
        pos += n;
    }

    /** Unsigned varint (at most 5 octets). */
    public int uvarint() {
        int v = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = int8() & 0xFF;
            v |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return v;
            }
        }
        throw new KafkaProtocolException("varint too long");
    }

    /** Zig-zag varint. */
    public int varint() {
        int z = uvarint();
        return (z >>> 1) ^ -(z & 1);
    }

    /** Zig-zag varlong (at most 10 octets). */
    public long varlong() {
        long v = 0;
        for (int shift = 0; shift < 70; shift += 7) {
            long b = int8() & 0xFF;
            v |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return (v >>> 1) ^ -(v & 1);
            }
        }
        throw new KafkaProtocolException("varlong too long");
    }

    /** @return octets with a zig-zag varint length, or null for -1 */
    public byte[] varbytes() {
        int n = varint();
        if (n == -1) {
            return null;
        }
        if (n < 0) {
            throw new KafkaProtocolException("negative length " + n);
        }
        return raw(n);
    }
}
