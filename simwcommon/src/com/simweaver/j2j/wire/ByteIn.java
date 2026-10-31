package com.simweaver.j2j.wire;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** A big-endian reader over a byte array; any read past the end throws {@link WireException}. */
public final class ByteIn {

    private final byte[] b;
    private int pos;
    private final int end;

    public ByteIn(byte[] b) {
        this(b, 0, b.length);
    }

    public ByteIn(byte[] b, int off, int len) {
        this.b = b;
        this.pos = off;
        this.end = off + len;
    }

    private void need(int n) throws WireException {
        if (n < 0 || pos + n > end) {
            throw new WireException("truncated: " + n + " octets needed, " + (end - pos) + " left");
        }
    }

    public int u8() throws WireException {
        need(1);
        return b[pos++] & 0xFF;
    }

    public int u16() throws WireException {
        need(2);
        int v = ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
        pos += 2;
        return v;
    }

    public long u32() throws WireException {
        need(4);
        long v = ((long) (b[pos] & 0xFF) << 24) | ((b[pos + 1] & 0xFF) << 16) | ((b[pos + 2] & 0xFF) << 8) | (b[pos + 3] & 0xFF);
        pos += 4;
        return v;
    }

    public long u64() throws WireException {
        need(8);
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[pos++] & 0xFF);
        }
        return v;
    }

    public byte[] bytes(int n) throws WireException {
        need(n);
        byte[] r = Arrays.copyOfRange(b, pos, pos + n);
        pos += n;
        return r;
    }

    public byte[] rest() {
        byte[] r = Arrays.copyOfRange(b, pos, end);
        pos = end;
        return r;
    }

    public int remaining() {
        return end - pos;
    }

    public int position() {
        return pos;
    }

    /**
     * Reads TLVs to the end. A tag that appears twice keeps its first value.
     *
     * @param known tags this reader knows; an unknown tag with the critical bit throws
     * @return tag (without the critical bit) to value, in order
     */
    public Map<Integer, byte[]> tlvs(int... known) throws WireException {
        Map<Integer, byte[]> m = new LinkedHashMap<Integer, byte[]>();
        while (remaining() > 0) {
            int tag = u16();
            int len = u16();
            byte[] v = bytes(len);
            int t = tag & ~J2jWire.TLV_CRITICAL;
            boolean isKnown = false;
            for (int k : known) {
                isKnown |= k == t;
            }
            if (!isKnown && (tag & J2jWire.TLV_CRITICAL) != 0) {
                throw new WireException("unknown critical TLV " + t);
            }
            if (isKnown && !m.containsKey(t)) {
                m.put(t, v);
            }
        }
        return m;
    }

    public static String utf8(byte[] v) {
        return v == null ? null : new String(v, StandardCharsets.UTF_8);
    }

    public static long u64(byte[] v) throws WireException {
        if (v == null || v.length < 8) {
            throw new WireException("u64 field too short");
        }
        return new ByteIn(v).u64();
    }
}
