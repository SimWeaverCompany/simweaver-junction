package com.simweaver.common.infra;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Decoding (and the one encoding needed) of the MOM datatypes of IEEE 1516-2010 / 2025:
 * HLAunicodeString, HLAinteger32BE-based counts, enumerations and HLAboolean, HLAhandle and the
 * lists of them, HLAmoduleDesignatorList, HLAinteractionSubList and encoded logical times. Every
 * method returns null (or an empty list) on octets that do not fit, never throws.
 */
final class MomCodec {

    private MomCodec() {
    }

    /** HLAunicodeString: element count (int32) and UTF-16BE characters. */
    static String unicodeString(byte[] b) {
        if (b == null || b.length < 4) {
            return null;
        }
        int n = int32(b, 0);
        if (n < 0 || 4 + 2L * n > b.length) {
            return null;
        }
        return new String(b, 4, 2 * n, StandardCharsets.UTF_16BE);
    }

    /** HLAinteger32BE (HLAcount, HLAmsec, HLAboolean, the MOM enumerations). */
    static Integer int32(byte[] b) {
        return b == null || b.length < 4 ? null : int32(b, 0);
    }

    static int int32(byte[] b, int at) {
        return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }

    /** HLAinteger64BE, or an HLAinteger32BE value when only four octets came (some RTIs' counts). */
    static Long count(byte[] b) {
        if (b == null) {
            return null;
        }
        if (b.length == 8) {
            long v = 0;
            for (int i = 0; i < 8; i++) {
                v = (v << 8) | (b[i] & 0xFF);
            }
            return v;
        }
        Integer i = int32(b);
        return i == null ? null : (long) i;
    }

    /** HLAboolean (HLAinteger32BE, 1 = true). */
    static Boolean bool(byte[] b) {
        Integer i = int32(b);
        return i == null ? null : i == 1;
    }

    /** HLAhandle: element count and octets; returns the octets (the RTI's own handle encoding). */
    static byte[] handle(byte[] b) {
        if (b == null || b.length < 4) {
            return null;
        }
        int n = int32(b, 0);
        if (n < 0 || 4L + n > b.length) {
            return null;
        }
        byte[] h = new byte[n];
        System.arraycopy(b, 4, h, 0, n);
        return h;
    }

    /** Encodes octets as an HLAhandle. */
    static byte[] encodeHandle(byte[] inner) {
        byte[] b = new byte[4 + inner.length];
        b[0] = (byte) (inner.length >>> 24);
        b[1] = (byte) (inner.length >>> 16);
        b[2] = (byte) (inner.length >>> 8);
        b[3] = (byte) inner.length;
        System.arraycopy(inner, 0, b, 4, inner.length);
        return b;
    }

    /** HLAhandleList: HLAvariableArray of HLAhandle (each padded to four octets). */
    static List<byte[]> handleList(byte[] b) {
        List<byte[]> out = new ArrayList<byte[]>();
        if (b == null || b.length < 4) {
            return out;
        }
        int n = int32(b, 0);
        int at = 4;
        for (int i = 0; i < n; i++) {
            at = align4(at);
            if (at + 4 > b.length) {
                return out;
            }
            int len = int32(b, at);
            if (len < 0 || at + 4L + len > b.length) {
                return out;
            }
            byte[] h = new byte[len];
            System.arraycopy(b, at + 4, h, 0, len);
            out.add(h);
            at += 4 + len;
        }
        return out;
    }

    /** One element of an HLAinteractionSubList. */
    static final class InteractionSub {
        final byte[] handle;
        final boolean active;

        InteractionSub(byte[] handle, boolean active) {
            this.handle = handle;
            this.active = active;
        }
    }

    /**
     * HLAinteractionSubList: HLAvariableArray of the record {HLAinteractionClass: HLAhandle,
     * HLAactive: HLAboolean}. Falls back to a plain HLAhandleList (all active) when the octets
     * are one.
     */
    static List<InteractionSub> interactionSubList(byte[] b) {
        List<InteractionSub> out = new ArrayList<InteractionSub>();
        if (b == null || b.length < 4) {
            return out;
        }
        int n = int32(b, 0);
        int at = 4;
        for (int i = 0; i < n; i++) {
            at = align4(at);
            if (at + 4 > b.length) {
                return fallback(b);
            }
            int len = int32(b, at);
            if (len < 0 || at + 4L + len > b.length) {
                return fallback(b);
            }
            byte[] h = new byte[len];
            System.arraycopy(b, at + 4, h, 0, len);
            at = align4(at + 4 + len);
            if (at + 4 > b.length) {
                return fallback(b);
            }
            out.add(new InteractionSub(h, int32(b, at) == 1));
            at += 4;
        }
        return out;
    }

    private static List<InteractionSub> fallback(byte[] b) {
        List<InteractionSub> out = new ArrayList<InteractionSub>();
        for (byte[] h : handleList(b)) {
            out.add(new InteractionSub(h, true));
        }
        return out;
    }

    /** HLAmoduleDesignatorList: HLAvariableArray of HLAunicodeString. */
    static List<String> stringList(byte[] b) {
        List<String> out = new ArrayList<String>();
        if (b == null || b.length < 4) {
            return out;
        }
        int n = int32(b, 0);
        int at = 4;
        for (int i = 0; i < n && i < 1000; i++) {
            at = align4(at);
            if (at + 4 > b.length) {
                return out;
            }
            int len = int32(b, at);
            if (len < 0 || at + 4L + 2L * len > b.length) {
                return out;
            }
            out.add(new String(b, at + 4, 2 * len, StandardCharsets.UTF_16BE));
            at += 4 + 2 * len;
        }
        return out;
    }

    /**
     * An encoded logical time or interval (HLAlogicalTime / HLAtimeInterval: octets of the time
     * implementation). Eight octets read as HLAinteger64Time when the federation's time
     * implementation says integer, else as HLAfloat64Time.
     */
    static String time(byte[] b, String timeImplementation) {
        byte[] t = handle(b);
        if (t == null) {
            return null;
        }
        if (t.length != 8) {
            return t.length == 0 ? "" : hex(t);
        }
        long bits = 0;
        for (int i = 0; i < 8; i++) {
            bits = (bits << 8) | (t[i] & 0xFF);
        }
        if (timeImplementation != null && timeImplementation.toLowerCase(java.util.Locale.ROOT).contains("integer")) {
            return String.valueOf(bits);
        }
        double d = Double.longBitsToDouble(bits);
        if (d == Double.MAX_VALUE || Double.isInfinite(d)) {
            return "∞";
        }
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
    }

    static String hex(byte[] b) {
        if (b == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    static byte[] unhex(String s) {
        if (s == null || (s.length() & 1) != 0) {
            return null;
        }
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            int hi = Character.digit(s.charAt(2 * i), 16);
            int lo = Character.digit(s.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) {
                return null;
            }
            b[i] = (byte) ((hi << 4) | lo);
        }
        return b;
    }

    private static int align4(int at) {
        return (at + 3) & ~3;
    }
}
