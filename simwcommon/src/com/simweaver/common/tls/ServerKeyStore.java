package com.simweaver.common.tls;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 0.14.5: the server's HTTPS key store. When the configured file does not exist, a new key (EC P-256) and a
 * self-signed certificate (5 years) are made and stored as PKCS12, readable by the owner only. Every site gets
 * its own key; before 0.14.5 every installation shipped the same {@code keystore.jks}.
 *
 * <p>The certificate names {@code localhost}, the computer's name, its addresses and the extra names of
 * {@code webserver.tls.san}, so browsers that are told to trust it once accept it under any of them.</p>
 */
public final class ServerKeyStore {

    /** The key's alias in a made store. */
    public static final String ALIAS = "server";

    /** How long a made certificate is valid. */
    public static final int VALID_YEARS = 5;

    private static final Pattern DNS_NAME = Pattern.compile("(?i)^(\\*\\.)?[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*\\.?$");
    private static final Pattern IPV4 = Pattern.compile("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$");

    private ServerKeyStore() {
    }

    /** The default key store (0.14.5): {@code <etc>/tls/server.p12}, made on first start. */
    public static Path defaultPath() {
        return com.simweaver.common.config.SimwPaths.etc("tls", "server.p12");
    }

    /** What {@link #ensure} found or made. */
    public static final class Result {
        public final Path file;
        public final boolean made;
        public final String fingerprint;
        public final List<String> names;

        Result(Path file, boolean made, String fingerprint, List<String> names) {
            this.file = file;
            this.made = made;
            this.fingerprint = fingerprint;
            this.names = names;
        }
    }

    /**
     * Makes the key store when the file does not exist; reads its first certificate's fingerprint either way.
     *
     * @param file      the key store file
     * @param password  its password (also the key's)
     * @param extraSan  {@code webserver.tls.san}: {@code ;} or {@code ,} separated names and addresses (may be empty)
     * @throws IOException when it cannot be made or read
     */
    public static Result ensure(Path file, char[] password, String extraSan) throws IOException {
        if (Files.exists(file)) {
            return new Result(file, false, fingerprintOf(file, password), Collections.emptyList());
        }
        List<String> names = names(extraSan);
        byte[] p12;
        String fp;
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
            KeyPair kp = g.generateKeyPair();
            String cn = "SimWeaver Junction " + hostName();
            X509Certificate cert = certificate(kp, cn, names);
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            ks.setKeyEntry(ALIAS, kp.getPrivate(), password, new Certificate[] {cert});
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            ks.store(bo, password);
            p12 = bo.toByteArray();
            fp = fingerprint(cert);
        } catch (GeneralSecurityException e) {
            throw new IOException("the HTTPS key could not be made: " + e.getMessage(), e);
        }
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".new-" + ProcessHandle.current().pid());
        try (OutputStream o = Files.newOutputStream(tmp)) {
            o.write(p12);
        }
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
        }
        try {
            try {
                com.simweaver.common.config.SiteFolders.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                com.simweaver.common.config.SiteFolders.move(tmp, file);
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        return new Result(file, true, fp, names);
    }

    /** Loads a key store file: PKCS12 or JKS, whatever its name says. */
    public static KeyStore load(Path file, char[] password) throws IOException, GeneralSecurityException {
        byte[] b = Files.readAllBytes(file);
        GeneralSecurityException first = null;
        for (String type : new String[] {"PKCS12", "JKS"}) {
            try (InputStream in = new ByteArrayInputStream(b)) {
                KeyStore ks = KeyStore.getInstance(type);
                ks.load(in, password);
                return ks;
            } catch (GeneralSecurityException e) {
                if (first == null) {
                    first = e;
                }
            } catch (IOException e) {
                if (e.getCause() instanceof java.security.UnrecoverableKeyException) {
                    throw e;                                    // a wrong password, not a wrong type
                }
                if (first == null) {
                    first = new java.security.KeyStoreException(e.getMessage(), e);
                }
            }
        }
        throw first;
    }

    /** @return the SHA-256 fingerprint of the store's first certificate, or "?" when it cannot be read */
    public static String fingerprintOf(Path file, char[] password) {
        try {
            KeyStore ks = load(file, password);
            for (Enumeration<String> e = ks.aliases(); e.hasMoreElements(); ) {
                Certificate c = ks.getCertificate(e.nextElement());
                if (c != null) {
                    return fingerprint(c);
                }
            }
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            return "?";
        }
        return "?";
    }

    /** SHA-256 of the certificate, upper-case hex pairs separated by colons. */
    public static String fingerprint(Certificate c) throws GeneralSecurityException {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(c.getEncoded());
        StringBuilder sb = new StringBuilder(d.length * 3);
        for (byte b : d) {
            if (sb.length() > 0) {
                sb.append(':');
            }
            sb.append(String.format("%02X", b & 0xff));
        }
        return sb.toString();
    }

    /**
     * The names a made certificate carries: {@code localhost}, the computer's name, 127.0.0.1, ::1, the
     * addresses of the interfaces that are up, then {@code webserver.tls.san}.
     */
    static List<String> names(String extraSan) {
        Set<String> s = new LinkedHashSet<>();
        s.add("localhost");
        String host = hostName();
        if (!host.isEmpty() && !host.equalsIgnoreCase("localhost") && validName(host)) {
            s.add(host.toLowerCase(Locale.ROOT));
            int dot = host.indexOf('.');
            if (dot > 0) {
                s.add(host.substring(0, dot).toLowerCase(Locale.ROOT));
            }
        }
        s.add("127.0.0.1");
        s.add("::1");
        try {
            for (Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces(); e != null && e.hasMoreElements(); ) {
                NetworkInterface ni = e.nextElement();
                try {
                    if (!ni.isUp()) {
                        continue;
                    }
                } catch (SocketException ignored) {
                    continue;
                }
                for (Enumeration<InetAddress> a = ni.getInetAddresses(); a.hasMoreElements(); ) {
                    InetAddress ia = a.nextElement();
                    if (ia.isLoopbackAddress() || ia.isLinkLocalAddress() || ia.isMulticastAddress()) {
                        continue;
                    }
                    s.add(ia instanceof Inet6Address ? ia.getHostAddress().replaceAll("%.*$", "") : ia.getHostAddress());
                }
            }
        } catch (SocketException ignored) {
            // the names we have are enough
        }
        if (extraSan != null) {
            for (String n : extraSan.split("[;,]")) {
                String t = n.trim();
                if (!t.isEmpty()) {
                    s.add(t.toLowerCase(Locale.ROOT));
                }
            }
        }
        return new ArrayList<>(s);
    }

    /** @return the problem with one {@code webserver.tls.san} entry, or null when it is a usable name or address */
    public static String sanProblem(String entry) {
        String t = entry.trim();
        if (t.isEmpty()) {
            return null;
        }
        if (isIp(t) || validName(t)) {
            return null;
        }
        return "'" + t + "' is neither a host name nor an IP address";
    }

    static boolean validName(String n) {
        return n.length() <= 253 && DNS_NAME.matcher(n).matches();
    }

    static boolean isIp(String n) {
        return IPV4.matcher(n).matches() || (n.indexOf(':') >= 0 && n.matches("[0-9A-Fa-f:.]+"));
    }

    static String hostName() {
        try {
            String h = InetAddress.getLocalHost().getHostName();
            return h == null ? "" : h.trim();
        } catch (UnknownHostException | RuntimeException e) {
            String env = System.getenv("COMPUTERNAME");
            if (env == null) {
                env = System.getenv("HOSTNAME");
            }
            return env == null ? "" : env.trim();
        }
    }

    // ---- the certificate ----

    private static final String OID_ECDSA_SHA256 = "1.2.840.10045.4.3.2";
    private static final String OID_CN = "2.5.4.3";
    private static final String OID_O = "2.5.4.10";

    static X509Certificate certificate(KeyPair kp, String cn, List<String> names) throws GeneralSecurityException, IOException {
        byte[] sigAlg = Der.sequence(Der.oid(OID_ECDSA_SHA256));
        byte[] name = Der.sequence(
                Der.set(Der.sequence(Der.oid(OID_O), Der.utf8("SimWeaver"))),
                Der.set(Der.sequence(Der.oid(OID_CN), Der.utf8(cn.length() > 64 ? cn.substring(0, 64) : cn))));
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC).minusMinutes(10);   // a little slack for clock skew
        byte[] validity = Der.sequence(Der.time(now), Der.time(now.plusYears(VALID_YEARS)));
        byte[] serial = new byte[16];
        new SecureRandom().nextBytes(serial);
        serial[0] &= 0x7f;
        serial[0] |= 0x01;
        byte[] spki = kp.getPublic().getEncoded();                                 // SubjectPublicKeyInfo, DER

        List<byte[]> san = new ArrayList<>();
        for (String n : names) {
            if (isIp(n)) {
                byte[] a = InetAddress.getByName(n).getAddress();                  // literals only: no lookup
                san.add(Der.context(7, false, a));
            } else if (validName(n)) {
                san.add(Der.context(2, false, n.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
            }
        }
        byte[] keyId = java.util.Arrays.copyOf(MessageDigest.getInstance("SHA-1").digest(spki), 20);
        byte[] extensions = Der.sequence(
                extension("2.5.29.19", true, Der.sequence()),                                  // basicConstraints: not a CA
                extension("2.5.29.15", true, Der.tlv(Der.BIT_STRING, new byte[] {7, (byte) 0x80})), // keyUsage: digitalSignature
                extension("2.5.29.37", false, Der.sequence(Der.oid("1.3.6.1.5.5.7.3.1"))),     // extKeyUsage: serverAuth
                extension("2.5.29.14", false, Der.octets(keyId)),                              // subjectKeyIdentifier
                extension("2.5.29.17", false, Der.sequence(san.toArray(new byte[0][]))));      // subjectAltName
        byte[] tbs = Der.sequence(
                Der.context(0, true, Der.integer(2)),
                Der.integer(new BigInteger(serial)),
                sigAlg,
                name,
                validity,
                name,
                spki,
                Der.context(3, true, extensions));
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(kp.getPrivate());
        sig.update(tbs);
        byte[] der = Der.sequence(tbs, sigAlg, Der.bits(sig.sign()));
        X509Certificate c = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
        c.verify(kp.getPublic());
        return c;
    }

    private static byte[] extension(String oid, boolean critical, byte[] value) {
        return critical ? Der.sequence(Der.oid(oid), Der.bool(true), Der.octets(value))
                : Der.sequence(Der.oid(oid), Der.octets(value));
    }
}
