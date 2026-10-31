package com.simweaver.j2j;

import com.simweaver.common.config.SimwProperties;
import com.simweaver.j2j.config.J2jConfig;
import com.simweaver.j2j.link.Link;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The TLS-on-443 last resort (design §3.3), on high ports: a primary address that does not answer,
 * the TLS fallback (with the server's key store), the same through an HTTP CONNECT proxy, and the
 * move back to the primary once it answers. Needs the JDK's keytool (skipped without it).
 *
 * <pre>java -cp out/classes:out/test com.simweaver.j2j.J2jFallbackSelfTest</pre>
 */
public final class J2jFallbackSelfTest {

    private static int checks;
    private static int failures;

    private J2jFallbackSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        J2jSelfTest.captureEvents();
        File keytool = new File(System.getProperty("java.home"), "bin/keytool" + (File.separatorChar == '\\' ? ".exe" : ""));
        if (!keytool.isFile()) {
            System.out.println("skipped: no keytool");
            System.out.println("0 checks, 0 failures");
            return;
        }
        Path dir = java.nio.file.Files.createTempDirectory("j2jtls");
        File ks = dir.resolve("server.p12").toFile();
        Process p = new ProcessBuilder(keytool.getPath(), "-genkeypair", "-alias", "s", "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=localhost", "-validity", "2", "-storetype", "PKCS12", "-keystore", ks.getPath(),
                "-storepass", "secret", "-keypass", "secret").redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        if (p.waitFor() != 0) {
            throw new IOException("keytool failed");
        }

        int pa = J2jSelfTest.freePort(), fa = J2jSelfTest.freePort(), gate = J2jSelfTest.freePort(), pb = J2jSelfTest.freePort();
        // b knows a by the gate (closed at first) and by a's TLS fallback
        String members = "member.a.addresses=127.0.0.1:" + gate + "\nmember.a.fallback=127.0.0.1:" + fa
                + "\nmember.b.addresses=\n";
        Path d = J2jSelfTest.files("tls-net", members, J2jSelfTest.key("k1", J2jSelfTest.KEY1));
        J2jNode a = J2jNode.start(cfg(d, "a", pa, fa, ks.getPath(), null), "SimWeaver test");
        J2jNode b = J2jNode.start(cfg(d, "b", pb, 0, ks.getPath(), null), "SimWeaver test");
        Gate g = new Gate(gate, pa);
        try {
            check("a listens on the TLS fallback port", a.links().fallbackPort() == fa && a.links().fallbackError() == null);
            check("primary closed: b reaches a over TLS", J2jSelfTest.await(() -> b.links().link("a") != null, 15000)
                    && "tls443".equals(b.links().link("a").kind()) && "tls443".equals(a.links().link("b").kind()));
            check("over TLS: the J2J handshake inside still authenticates", b.links().link("a").keyId().equals("k1"));
            check("fallback event", J2jSelfTest.await(() -> J2jSelfTest.events("j2j.channel.fallback") >= 1, 3000));
            Link tls = b.links().link("a");
            g.open();
            check("primary open: b moves back to TCP", J2jSelfTest.await(() -> b.links().link("a") != null && "tcp".equals(b.links().link("a").kind())
                    && J2jSelfTest.events("j2j.channel.primary") >= 1, 25000) && b.links().link("a") != tls);
        } finally {
            g.close();
            b.stop();
        }

        // through an HTTP CONNECT proxy
        int pc = J2jSelfTest.freePort();
        Proxy proxy = new Proxy();
        Path d2 = J2jSelfTest.files("tls-net", "member.a.addresses=127.0.0.1:" + gate + "\nmember.a.fallback=127.0.0.1:" + fa
                + "\nmember.b.addresses=\n", J2jSelfTest.key("k1", J2jSelfTest.KEY1));
        J2jNode c = J2jNode.start(cfg(d2, "b", pc, 0, ks.getPath(), "127.0.0.1:" + proxy.port()), "SimWeaver test");
        try {
            check("through the proxy: linked over TLS", J2jSelfTest.await(() -> c.links().link("a") != null, 15000)
                    && "tls443".equals(c.links().link("a").kind()));
            check("the proxy saw CONNECT to a's fallback", proxy.requests.stream().anyMatch(r -> r.startsWith("CONNECT 127.0.0.1:" + fa + " HTTP/1.1")));
        } finally {
            c.stop();
            a.stop();
            proxy.close();
        }

        // a wrong key store: J2J runs, the listener is reported missing
        Path d3 = J2jSelfTest.files("tls-net", "member.a.addresses=127.0.0.1:1\nmember.b.addresses=\n", J2jSelfTest.key("k1", J2jSelfTest.KEY1));
        J2jNode x = J2jNode.start(cfg(d3, "a", J2jSelfTest.freePort(), J2jSelfTest.freePort(), dir.resolve("missing.p12").toString(), null), "t");
        try {
            check("no key store: J2J runs, the fallback listener is reported", x.isRunning() && x.links().fallbackPort() == -1
                    && x.links().fallbackError() != null && J2jSelfTest.events("j2j.fallback.failed") >= 1);
        } finally {
            x.stop();
        }
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    static J2jConfig cfg(Path d, String self, int port, int fallbackPort, String keystore, String proxy) {
        SimwProperties s = new SimwProperties().set("j2j.node.id", self).set("j2j.port", String.valueOf(port))
                .set("j2j.deadseconds", "3").set("j2j.fallback.port", String.valueOf(fallbackPort))
                .set("j2j.fallback.keystore", keystore).set("j2j.fallback.keystorepwd", "secret")
                .set("j2j.fallback.retryseconds", "1").set("j2j.udp", "false");
        if (proxy != null) {
            s.set("j2j.fallback.proxy", proxy);
        }
        return J2jConfig.load(s, d.resolve("network.settings"), d.resolve("network.key"));
    }

    /** A port that refuses connections until opened, then forwards to {@code target}. */
    static final class Gate {
        final int port;
        final int target;
        volatile ServerSocket ss;

        Gate(int port, int target) {
            this.port = port;
            this.target = target;
        }

        void open() throws IOException {
            ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            ss = s;
            Thread t = new Thread(() -> {
                while (!s.isClosed()) {
                    try {
                        Socket in = s.accept();
                        Socket out = new Socket(InetAddress.getLoopbackAddress(), target);
                        pump(in, out);
                        pump(out, in);
                    } catch (IOException e) {
                        return;
                    }
                }
            });
            t.setDaemon(true);
            t.start();
        }

        void close() throws IOException {
            if (ss != null) {
                ss.close();
            }
        }
    }

    static void pump(Socket from, Socket to) {
        Thread t = new Thread(() -> {
            byte[] b = new byte[16384];
            try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
                int n;
                while ((n = in.read(b)) > 0) {
                    out.write(b, 0, n);
                    out.flush();
                }
            } catch (IOException ignored) {
                // closed
            } finally {
                try {
                    to.close();
                } catch (IOException ignored) {
                    // closed
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    /** A minimal HTTP CONNECT proxy. */
    static final class Proxy {
        final ServerSocket ss;
        final List<String> requests = new CopyOnWriteArrayList<String>();

        Proxy() throws IOException {
            ss = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
            Thread t = new Thread(() -> {
                while (!ss.isClosed()) {
                    try {
                        Socket c = ss.accept();
                        InputStream in = c.getInputStream();
                        StringBuilder head = new StringBuilder();
                        int last4 = 0;
                        while (last4 != 0x0D0A0D0A) {
                            int ch = in.read();
                            if (ch < 0) {
                                break;
                            }
                            head.append((char) ch);
                            last4 = (last4 << 8) | ch;
                        }
                        String line = head.toString().split("\r\n")[0];
                        requests.add(line);
                        String[] hp = line.split(" ")[1].split(":");
                        Socket out = new Socket(hp[0], Integer.parseInt(hp[1]));
                        c.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        c.getOutputStream().flush();
                        pump(c, out);
                        pump(out, c);
                    } catch (IOException e) {
                        return;
                    }
                }
            });
            t.setDaemon(true);
            t.start();
        }

        int port() {
            return ss.getLocalPort();
        }

        void close() throws IOException {
            ss.close();
        }
    }

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }
}
