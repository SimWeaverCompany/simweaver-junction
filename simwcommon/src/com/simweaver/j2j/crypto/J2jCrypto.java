package com.simweaver.j2j.crypto;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.XECPublicKey;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPrivateKeySpec;
import java.security.spec.XECPublicKeySpec;
import java.util.Arrays;

import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The J2J crypto primitives, JDK only (Java 11): SHA-256, HMAC-SHA256, HKDF (RFC 5869) and
 * X25519 (RFC 7748) with 32-octet little-endian public keys.
 */
public final class J2jCrypto {

    public static final SecureRandom RANDOM = new SecureRandom();

    private static final BigInteger BASE_U = BigInteger.valueOf(9);

    private J2jCrypto() {
    }

    public static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) {
                d.update(p);
            }
            return d.digest();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 missing", e);
        }
    }

    public static byte[] hmac(byte[] key, byte[]... parts) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            for (byte[] p : parts) {
                m.update(p);
            }
            return m.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 missing", e);
        }
    }

    /** HKDF-Extract (RFC 5869): PRK = HMAC(salt, ikm). */
    public static byte[] hkdfExtract(byte[] salt, byte[] ikm) {
        return hmac(salt, ikm);
    }

    /** HKDF-Expand (RFC 5869) to {@code length} octets (at most 255 × 32). */
    public static byte[] hkdfExpand(byte[] prk, byte[] info, int length) {
        byte[] out = new byte[length];
        byte[] t = new byte[0];
        int done = 0;
        for (int i = 1; done < length; i++) {
            t = hmac(prk, t, info, new byte[] {(byte) i});
            int n = Math.min(t.length, length - done);
            System.arraycopy(t, 0, out, done, n);
            done += n;
        }
        return out;
    }

    /** Constant-time comparison. */
    public static boolean equal(byte[] a, byte[] b) {
        return a != null && b != null && MessageDigest.isEqual(a, b);
    }

    public static byte[] random(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    // ---- X25519 -----------------------------------------------------------------------------

    /** An X25519 key pair with its 32-octet public key. */
    public static final class Ephemeral {
        public final PrivateKey privateKey;
        public final byte[] publicKey;

        Ephemeral(PrivateKey privateKey, byte[] publicKey) {
            this.privateKey = privateKey;
            this.publicKey = publicKey;
        }
    }

    /** A new random X25519 key pair. */
    public static Ephemeral newEphemeral() {
        return ephemeral(random(32));
    }

    /** The X25519 key pair of a given 32-octet private scalar (test vectors). */
    public static Ephemeral ephemeral(byte[] scalar) {
        try {
            KeyFactory kf = KeyFactory.getInstance("XDH");
            PrivateKey priv = kf.generatePrivate(new XECPrivateKeySpec(NamedParameterSpec.X25519, scalar.clone()));
            // public = scalar × base point (u = 9)
            PublicKey base = kf.generatePublic(new XECPublicKeySpec(NamedParameterSpec.X25519, BASE_U));
            KeyAgreement ka = KeyAgreement.getInstance("XDH");
            ka.init(priv);
            ka.doPhase(base, true);
            return new Ephemeral(priv, ka.generateSecret());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("X25519 missing (Java 11 or later needed)", e);
        }
    }

    /** X25519(private, peer public): the 32-octet shared secret. */
    public static byte[] agree(PrivateKey priv, byte[] peerPublic) throws GeneralSecurityException {
        if (peerPublic == null || peerPublic.length != 32) {
            throw new GeneralSecurityException("X25519 public key must be 32 octets");
        }
        byte[] le = peerPublic.clone();
        le[31] &= 0x7F;                                   // RFC 7748: mask the top bit
        byte[] be = new byte[32];
        for (int i = 0; i < 32; i++) {
            be[i] = le[31 - i];
        }
        PublicKey pub = KeyFactory.getInstance("XDH").generatePublic(
                new XECPublicKeySpec(NamedParameterSpec.X25519, new BigInteger(1, be)));
        KeyAgreement ka = KeyAgreement.getInstance("XDH");
        ka.init(priv);
        ka.doPhase(pub, true);
        byte[] s = ka.generateSecret();
        boolean zero = true;
        for (byte x : s) {
            zero &= x == 0;
        }
        if (zero) {
            throw new GeneralSecurityException("X25519: low-order public key");
        }
        return s;
    }

    /** The 32-octet little-endian encoding of a public key (kept for completeness). */
    static byte[] encode(XECPublicKey k) {
        byte[] be = k.getU().toByteArray();
        byte[] le = new byte[32];
        for (int i = 0; i < 32 && i < be.length; i++) {
            le[i] = be[be.length - 1 - i];
        }
        return le;
    }

    public static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) {
            s.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        }
        return s.toString();
    }

    public static byte[] unhex(String h) {
        String s = h.replaceAll("\\s", "");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    /** The first {@code n} octets of SHA-256 as hex: fingerprints shown in the UI. */
    public static String fingerprint(byte[] data, int n) {
        return hex(Arrays.copyOf(sha256(data), n));
    }
}
