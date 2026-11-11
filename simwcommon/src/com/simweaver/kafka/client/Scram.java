package com.simweaver.kafka.client;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The client side of SCRAM-SHA-256 / SCRAM-SHA-512 (RFC 5802, RFC 7677) as Kafka uses it: no
 * channel binding ({@code n,,}), user names with {@code =} and {@code ,} escaped. Passwords are used
 * as UTF-8 without SASLprep (ASCII passwords are the same either way).
 */
public final class Scram {

    private final String hash;          // SHA-256 / SHA-512
    private final String hmac;          // HmacSHA256 / HmacSHA512
    private final String user;
    private final String password;
    private final String clientNonce;
    private String clientFirstBare;
    private String authMessage;
    private byte[] saltedPassword;

    /** @param mechanism SCRAM-SHA-256 or SCRAM-SHA-512 */
    public Scram(String mechanism, String user, String password) {
        this(mechanism, user, password, randomNonce());
    }

    /** With a given client nonce: for the RFC test vectors. */
    public Scram(String mechanism, String user, String password, String nonce) {
        boolean sha512 = mechanism.toUpperCase(java.util.Locale.ROOT).endsWith("512");
        this.hash = sha512 ? "SHA-512" : "SHA-256";
        this.hmac = sha512 ? "HmacSHA512" : "HmacSHA256";
        this.user = user;
        this.password = password == null ? "" : password;
        this.clientNonce = nonce;
    }

    private static String randomNonce() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        return Base64.getEncoder().withoutPadding().encodeToString(b).replace('+', '-').replace('/', '_');
    }

    /** @return the client-first message */
    public byte[] first() {
        clientFirstBare = "n=" + user.replace("=", "=3D").replace(",", "=2C") + ",r=" + clientNonce;
        return ("n,," + clientFirstBare).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * @param serverFirst the server-first message
     * @return the client-final message
     * @throws GeneralSecurityException when the server's message is not acceptable
     */
    public byte[] last(byte[] serverFirst) throws GeneralSecurityException {
        String sf = new String(serverFirst, StandardCharsets.UTF_8);
        Map<String, String> a = attributes(sf);
        String nonce = a.get("r");
        String salt = a.get("s");
        String iter = a.get("i");
        if (a.containsKey("e")) {
            throw new GeneralSecurityException("SCRAM: the server refused: " + a.get("e"));
        }
        if (nonce == null || salt == null || iter == null || !nonce.startsWith(clientNonce) || nonce.length() == clientNonce.length()) {
            throw new GeneralSecurityException("SCRAM: unexpected server-first message");
        }
        int iterations;
        try {
            iterations = Integer.parseInt(iter);
        } catch (NumberFormatException e) {
            throw new GeneralSecurityException("SCRAM: bad iteration count");
        }
        if (iterations < 1 || iterations > 1_000_000) {
            throw new GeneralSecurityException("SCRAM: iteration count out of range: " + iterations);
        }
        byte[] saltBytes = Base64.getDecoder().decode(salt);
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2With" + hmac);
        int bits = hash.equals("SHA-512") ? 512 : 256;
        saltedPassword = f.generateSecret(new PBEKeySpec(password.toCharArray(), saltBytes, iterations, bits)).getEncoded();
        byte[] clientKey = mac(saltedPassword, "Client Key".getBytes(StandardCharsets.UTF_8));
        byte[] storedKey = MessageDigest.getInstance(hash).digest(clientKey);
        String withoutProof = "c=biws,r=" + nonce;
        authMessage = clientFirstBare + "," + sf + "," + withoutProof;
        byte[] signature = mac(storedKey, authMessage.getBytes(StandardCharsets.UTF_8));
        byte[] proof = new byte[clientKey.length];
        for (int i = 0; i < proof.length; i++) {
            proof[i] = (byte) (clientKey[i] ^ signature[i]);
        }
        return (withoutProof + ",p=" + Base64.getEncoder().encodeToString(proof)).getBytes(StandardCharsets.UTF_8);
    }

    /** Checks the server-final message's signature. */
    public void verify(byte[] serverFinal) throws GeneralSecurityException {
        Map<String, String> a = attributes(new String(serverFinal, StandardCharsets.UTF_8));
        if (a.containsKey("e")) {
            throw new GeneralSecurityException("SCRAM: the server refused: " + a.get("e"));
        }
        String v = a.get("v");
        if (v == null) {
            throw new GeneralSecurityException("SCRAM: no server signature");
        }
        byte[] serverKey = mac(saltedPassword, "Server Key".getBytes(StandardCharsets.UTF_8));
        byte[] expected = mac(serverKey, authMessage.getBytes(StandardCharsets.UTF_8));
        if (!MessageDigest.isEqual(expected, Base64.getDecoder().decode(v))) {
            throw new GeneralSecurityException("SCRAM: the server's signature is wrong");
        }
    }

    private byte[] mac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac m = Mac.getInstance(hmac);
        m.init(new SecretKeySpec(key, hmac));
        return m.doFinal(data);
    }

    private static Map<String, String> attributes(String s) {
        Map<String, String> m = new HashMap<String, String>();
        for (String part : s.split(",")) {
            int eq = part.indexOf('=');
            if (eq == 1) {
                m.put(part.substring(0, 1), part.substring(2));
            }
        }
        return m;
    }
}
