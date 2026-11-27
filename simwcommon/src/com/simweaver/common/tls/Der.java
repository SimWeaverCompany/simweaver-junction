package com.simweaver.common.tls;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 0.14.5: the few DER (X.690) encodings a self-signed X.509 certificate needs. Every method returns one
 * complete TLV.
 */
final class Der {

    static final int INTEGER = 0x02;
    static final int BIT_STRING = 0x03;
    static final int OCTET_STRING = 0x04;
    static final int NULL = 0x05;
    static final int OID = 0x06;
    static final int UTF8_STRING = 0x0c;
    static final int UTC_TIME = 0x17;
    static final int GENERALIZED_TIME = 0x18;
    static final int SEQUENCE = 0x30;
    static final int SET = 0x31;
    static final int BOOLEAN = 0x01;

    private Der() {
    }

    static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(value.length + 6);
        o.write(tag);
        int n = value.length;
        if (n < 0x80) {
            o.write(n);
        } else if (n < 0x100) {
            o.write(0x81);
            o.write(n);
        } else if (n < 0x10000) {
            o.write(0x82);
            o.write(n >> 8);
            o.write(n);
        } else {
            o.write(0x83);
            o.write(n >> 16);
            o.write(n >> 8);
            o.write(n);
        }
        o.write(value, 0, n);
        return o.toByteArray();
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            o.write(p, 0, p.length);
        }
        return o.toByteArray();
    }

    static byte[] sequence(byte[]... parts) {
        return tlv(SEQUENCE, concat(parts));
    }

    static byte[] set(byte[]... parts) {
        return tlv(SET, concat(parts));
    }

    static byte[] integer(BigInteger v) {
        return tlv(INTEGER, v.toByteArray());
    }

    static byte[] integer(long v) {
        return integer(BigInteger.valueOf(v));
    }

    static byte[] bool(boolean v) {
        return tlv(BOOLEAN, new byte[] {(byte) (v ? 0xff : 0)});
    }

    static byte[] octets(byte[] v) {
        return tlv(OCTET_STRING, v);
    }

    /** A BIT STRING of whole octets (no unused bits). */
    static byte[] bits(byte[] v) {
        byte[] b = new byte[v.length + 1];
        System.arraycopy(v, 0, b, 1, v.length);
        return tlv(BIT_STRING, b);
    }

    static byte[] utf8(String s) {
        return tlv(UTF8_STRING, s.getBytes(StandardCharsets.UTF_8));
    }

    /** UTCTime before 2050, GeneralizedTime from then (RFC 5280 4.1.2.5). */
    static byte[] time(ZonedDateTime t) {
        ZonedDateTime u = t.withZoneSameInstant(ZoneOffset.UTC).withNano(0);
        if (u.getYear() < 2050) {
            return tlv(UTC_TIME, u.format(DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'")).getBytes(StandardCharsets.US_ASCII));
        }
        return tlv(GENERALIZED_TIME, u.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'")).getBytes(StandardCharsets.US_ASCII));
    }

    /** An OBJECT IDENTIFIER from its dotted form. */
    static byte[] oid(String dotted) {
        String[] p = dotted.split("\\.");
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(Integer.parseInt(p[0]) * 40 + Integer.parseInt(p[1]));
        for (int i = 2; i < p.length; i++) {
            long v = Long.parseLong(p[i]);
            int n = 0;
            byte[] tmp = new byte[10];
            do {
                tmp[n++] = (byte) (v & 0x7f);
                v >>>= 7;
            } while (v != 0);
            for (int j = n - 1; j >= 0; j--) {
                o.write(tmp[j] | (j > 0 ? 0x80 : 0));
            }
        }
        return tlv(OID, o.toByteArray());
    }

    /** A context-specific tag: constructed and explicit ({@code [n] EXPLICIT}), or primitive / implicit. */
    static byte[] context(int n, boolean constructed, byte[] value) {
        return tlv(0x80 | (constructed ? 0x20 : 0) | n, value);
    }
}
