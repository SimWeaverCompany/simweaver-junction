package com.simweaver.j2j.link;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * The last-resort channel (design §3.3): the same J2J frames inside TLS on port 443, so firewalls
 * and web proxies see HTTPS; optionally through an HTTP proxy with {@code CONNECT}. TLS is only
 * the outer layer: the J2J handshake inside authenticates and encrypts as on any channel, so the
 * client does not check the server's certificate, and the server uses the web server's key store.
 */
final class FallbackTls {

    private static final int TIMEOUT_MS = 5000;

    private FallbackTls() {
    }

    /** A TLS listener with the given key store (JKS or PKCS12). */
    static ServerSocket listen(String bind, int port, String keystore, String password) throws IOException, GeneralSecurityException {
        KeyStore ks = com.simweaver.common.tls.ServerKeyStore.load(java.nio.file.Paths.get(keystore), password.toCharArray());
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, password.toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        SSLServerSocket ss = (SSLServerSocket) ctx.getServerSocketFactory().createServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(bind.isEmpty() ? null : InetAddress.getByName(bind), port), 50);
        return ss;
    }

    /**
     * Opens a TLS connection to host:port, through {@code proxy} ("host:port", or empty for none).
     *
     * @return the TLS socket, handshake done
     */
    static SSLSocket connect(String host, int port, String proxy) throws IOException {
        Socket raw = new Socket();
        try {
            if (proxy == null || proxy.isEmpty()) {
                raw.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            } else {
                int colon = proxy.lastIndexOf(':');
                String ph = colon > 0 ? proxy.substring(0, colon) : proxy;
                int pp = colon > 0 ? Integer.parseInt(proxy.substring(colon + 1).trim()) : 8080;
                raw.connect(new InetSocketAddress(ph, pp), TIMEOUT_MS);
                raw.setSoTimeout(TIMEOUT_MS);
                connectThrough(raw, host, port);
            }
            raw.setTcpNoDelay(true);
            SSLSocket s = (SSLSocket) trustAll().getSocketFactory().createSocket(raw, host, port, true);
            s.setSoTimeout(TIMEOUT_MS);
            s.startHandshake();
            return s;
        } catch (IOException | RuntimeException e) {
            try {
                raw.close();
            } catch (IOException ignored) {
                // failed anyway
            }
            throw e instanceof IOException ? (IOException) e : new IOException(e.getMessage(), e);
        }
    }

    /** HTTP CONNECT; the proxy must answer 2xx. */
    private static void connectThrough(Socket raw, String host, int port) throws IOException {
        String target = (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
        OutputStream out = raw.getOutputStream();
        out.write(("CONNECT " + target + " HTTP/1.1\r\nHost: " + target + "\r\nUser-Agent: SimWeaver-J2J\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        InputStream in = raw.getInputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int last4 = 0;
        while (last4 != 0x0D0A0D0A) {                         // read up to the blank line, byte by byte (no read-ahead)
            int c = in.read();
            if (c < 0) {
                throw new IOException("the proxy closed the connection");
            }
            head.write(c);
            if (head.size() > 8192) {
                throw new IOException("the proxy's answer is too long");
            }
            last4 = (last4 << 8) | c;
        }
        String status = head.toString("ISO-8859-1").split("\r\n", 2)[0];
        String[] parts = status.split(" ");
        if (parts.length < 2 || !parts[1].startsWith("2")) {
            throw new IOException("the proxy refused CONNECT: " + status);
        }
    }

    private static volatile SSLContext trustAll;

    private static SSLContext trustAll() throws IOException {
        if (trustAll == null) {
            try {
                SSLContext c = SSLContext.getInstance("TLS");
                c.init(null, new TrustManager[] {new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        // not used
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        // the J2J handshake inside authenticates the peer (network key); TLS only wraps
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }}, null);
                trustAll = c;
            } catch (GeneralSecurityException e) {
                throw new IOException("TLS unavailable: " + e.getMessage(), e);
            }
        }
        return trustAll;
    }
}
