package com.simweaver.j2j.crypto;

import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM for one direction of a channel. The 96-bit nonce is 4 zero octets followed by the
 * 64-bit frame counter (big endian); each direction has its own key, so nonces never repeat
 * under one key as long as the counter does not wrap (a channel is re-keyed long before).
 * Not thread safe: one instance per direction, used by one thread.
 */
public final class FrameCipher {

    public static final int TAG_BYTES = 16;

    private final SecretKeySpec key;
    private final Cipher cipher;
    private final byte[] nonce = new byte[12];

    public FrameCipher(byte[] key256) {
        if (key256.length != 32) {
            throw new IllegalArgumentException("AES-256 needs a 32-octet key");
        }
        this.key = new SecretKeySpec(key256, "AES");
        try {
            this.cipher = Cipher.getInstance("AES/GCM/NoPadding");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES/GCM missing", e);
        }
    }

    private void nonce(long counter) {
        for (int i = 0; i < 8; i++) {
            nonce[4 + i] = (byte) (counter >>> (56 - 8 * i));
        }
    }

    /** @return ciphertext followed by the 16-octet tag */
    public byte[] seal(long counter, byte[] aad, byte[] plain) throws GeneralSecurityException {
        nonce(counter);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(plain);
    }

    /** @throws GeneralSecurityException (AEADBadTagException) when the frame was altered or the key is wrong */
    public byte[] open(long counter, byte[] aad, byte[] sealed, int off, int len) throws GeneralSecurityException {
        nonce(counter);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(sealed, off, len);
    }
}
