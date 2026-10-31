package com.simweaver.j2j;

import com.simweaver.common.config.SimwProperties;
import com.simweaver.j2j.config.J2jConfig;
import com.simweaver.j2j.crypto.FrameCipher;
import com.simweaver.j2j.crypto.J2jCrypto;
import com.simweaver.j2j.crypto.ReplayWindow;
import com.simweaver.j2j.link.Handshake;
import com.simweaver.j2j.link.HandshakeException;
import com.simweaver.j2j.link.Link;
import com.simweaver.j2j.link.LinkManager;
import com.simweaver.j2j.wire.ByteIn;
import com.simweaver.j2j.wire.ByteOut;
import com.simweaver.j2j.wire.Envelope;
import com.simweaver.j2j.wire.J2jWire;
import com.simweaver.j2j.wire.TcpFrames;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * J2J phase 1 without a network beyond the loopback: configuration, crypto (RFC test vectors),
 * frames, the handshake (success, every refusal, versions, unknown TLVs), the wire test vectors
 * of doc/j2j-wire-protocol.md, and live servers in one JVM: links, tie-break, messages, RTT,
 * best-effort supersede, a restart, a wrong key, a stranger, refusal rate limiting.
 *
 * <pre>java -cp out/classes:out/test com.simweaver.j2j.J2jSelfTest</pre>
 */
public final class J2jSelfTest {

    private static int checks;
    private static int failures;
    private static final List<LogRecord> EVENTS = new CopyOnWriteArrayList<LogRecord>();

    private J2jSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        captureEvents();
        config();
        crypto();
        frames();
        handshake();
        vectors();
        live();
        udp();
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ helpers

    private static boolean capturing;
    /** Held: the log manager keeps loggers only weakly, and a collected logger loses its handler. */
    private static final Logger J2J_LOG = Logger.getLogger("com.simweaver.j2j");

    /** Collects the J2J log records (for {@link #events}); once per JVM. */
    static synchronized void captureEvents() {
        if (capturing) {
            return;
        }
        capturing = true;
        J2J_LOG.addHandler(new Handler() {
            @Override
            public void publish(LogRecord r) {
                EVENTS.add(r);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    static final byte[] KEY1 = J2jCrypto.sha256("test key 1".getBytes(StandardCharsets.UTF_8));
    static final byte[] KEY2 = J2jCrypto.sha256("test key 2".getBytes(StandardCharsets.UTF_8));

    /** Writes network.settings and network.key into a new folder; returns the folder. */
    static Path files(String network, String members, String keys) throws Exception {
        Path d = Files.createTempDirectory("j2jtest");
        Files.write(d.resolve("network.settings"), (network == null ? "" : "network.id=" + network + "\n" + members).getBytes(StandardCharsets.UTF_8));
        if (keys != null) {
            Files.write(d.resolve("network.key"), keys.getBytes(StandardCharsets.UTF_8));
        }
        return d;
    }

    static String key(String id, byte[] k) {
        return id + "=" + Base64.getEncoder().encodeToString(k) + "\n";
    }

    static J2jConfig cfg(Path d, String self, int port) {
        return cfg(d, self, port, false);
    }

    static J2jConfig cfg(Path d, String self, int port, boolean udp) {
        SimwProperties s = new SimwProperties().set("j2j.node.id", self).set("j2j.port", String.valueOf(port))
                .set("j2j.deadseconds", "3").set("j2j.udp", String.valueOf(udp));
        return J2jConfig.load(s, d.resolve("network.settings"), d.resolve("network.key"));
    }

    static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    static boolean await(BooleanSupplier c, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (c.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return c.getAsBoolean();
    }

    static long events(String event) {
        return EVENTS.stream().filter(r -> {
            com.simweaver.common.logging.Events.Tag t = com.simweaver.common.logging.Events.tagOf(r);
            return t != null && t.event().equals(event);
        }).count();
    }

    static List<String> eventTexts(String event) {
        List<String> l = new ArrayList<String>();
        for (LogRecord r : EVENTS) {
            com.simweaver.common.logging.Events.Tag t = com.simweaver.common.logging.Events.tagOf(r);
            if (t != null && t.event().equals(event)) {
                l.add(r.getMessage() + "  [" + t.text() + "]");
            }
        }
        return l;
    }

    // ------------------------------------------------------------------ configuration

    private static void config() throws Exception {
        Path none = Files.createTempDirectory("j2jnone");
        J2jConfig off = J2jConfig.load(new SimwProperties(), none.resolve("network.settings"), none.resolve("network.key"));
        check("no file: off, no problems", !off.isEnabled() && !off.isConfigured() && off.problems().isEmpty());
        Path empty = files("net", "", null);
        J2jConfig off2 = J2jConfig.load(new SimwProperties().set("j2j.node.id", "a"), empty.resolve("network.settings"), empty.resolve("network.key"));
        check("no members: off, no problems", !off2.isEnabled() && off2.problems().isEmpty());

        Path d = files("exercise-net", "network.name=Exercise North\n"
                + "member.sto-1.name=Stockholm lab\nmember.sto-1.addresses=10.10.0.5:8844; 192.0.2.10\n"
                + "member.sto-1.fallback=gw.sto.example.org\nmember.sto-1.site=Central\n"
                + "# a comment\nmember.got-2.addresses=[fd00::7]:9000, 10.20.0.7\n"
                + "member.mlm-3.name=Malmö\nmember.mlm-3.addresses=\n"
                + "member.kir-5.addresses=10.50.0.2\nmember.kir-5.connect=false\n", key("k1", KEY1) + key("k2", KEY2) + "preferred=k2\n");
        J2jConfig c = cfg(d, "sto-1", 8844);
        check("valid: enabled " + c.problems(), c.isEnabled() && c.problems().isEmpty());
        check("network and members in file order", "exercise-net".equals(c.networkId()) && "Exercise North".equals(c.networkName())
                && String.join(",", c.members().keySet()).equals("sto-1,got-2,mlm-3,kir-5"));
        check("addresses, default port, fallback 443, site", c.member("sto-1").addresses().toString().equals("[10.10.0.5:8844, 192.0.2.10:8844]")
                && c.member("sto-1").fallback().toString().equals("gw.sto.example.org:443") && "Central".equals(c.member("sto-1").site()));
        check("IPv6 address and comma list", c.member("got-2").addresses().toString().equals("[[fd00::7]:9000, 10.20.0.7:8844]"));
        check("name defaults to the id; UTF-8 name", "got-2".equals(c.member("got-2").name()) && "Malmö".equals(c.member("mlm-3").name()));
        check("no addresses: not dialable; connect=false: not dialable", !c.member("mlm-3").dialable() && !c.member("kir-5").dialable()
                && c.member("got-2").dialable());
        check("preferred key first", c.preferredKey().id.equals("k2") && c.keys().size() == 2 && c.key("k1") != null);
        check("key fingerprint is 8 hex", c.preferredKey().fingerprint().matches("[0-9a-f]{8}"));
        check("member hash 8 hex, same for every server", c.memberHash().matches("[0-9a-f]{8}") && c.memberHash().equals(cfg(d, "got-2", 1).memberHash()));
        check("node key = SHA-256 prefix", c.member("sto-1").key() == J2jConfig.nodeKey("sto-1") && J2jConfig.nodeKey("a") != J2jConfig.nodeKey("b"));

        J2jConfig notListed = cfg(d, "upp-9", 8844);
        check("self not in the list: problem", !notListed.isEnabled() && notListed.problems().toString().contains("'upp-9' is not in"));
        J2jConfig noSelf = J2jConfig.load(new SimwProperties(), d.resolve("network.settings"), d.resolve("network.key"));
        check("no j2j.node.id: problem", noSelf.problems().toString().contains("j2j.node.id is not set"));
        Path bad = files("n", "member.Bad_Id.addresses=x\nmember.ok.addresses=h:99999\nmember.ok.colour=red\nmember.ok.connect=maybe\n",
                "k1=notbase64!!\nk2=" + Base64.getEncoder().encodeToString(new byte[16]) + "\npreferred=k9\n");
        J2jConfig b = cfg(bad, "ok", 8844);
        String ps = b.problems().toString();
        check("mistakes found: id, port, unknown setting, connect, keys, preferred " + b.problems().size(),
                ps.contains("'Bad_Id'") && ps.contains("h:99999") && ps.contains("colour") && ps.contains("maybe")
                && ps.contains("key k1 is not 32 octets") && ps.contains("key k2 is not 32 octets") && !b.isEnabled());
        Path nokey = files("n", "member.a.addresses=h\n", null);
        check("missing key file: problem", cfg(nokey, "a", 8844).problems().toString().contains("network key file"));
        Path nonet = Files.createTempDirectory("j2jnonet");
        Files.write(nonet.resolve("network.settings"), "member.a.addresses=h\n".getBytes(StandardCharsets.UTF_8));
        Files.write(nonet.resolve("network.key"), key("k1", KEY1).getBytes(StandardCharsets.UTF_8));
        check("missing network.id: problem", cfg(nonet, "a", 8844).problems().toString().contains("network.id is missing"));
    }

    // ------------------------------------------------------------------ crypto

    private static void crypto() throws Exception {
        // RFC 5869 test case 1
        byte[] ikm = new byte[22];
        Arrays.fill(ikm, (byte) 0x0b);
        byte[] salt = J2jCrypto.unhex("000102030405060708090a0b0c");
        byte[] info = J2jCrypto.unhex("f0f1f2f3f4f5f6f7f8f9");
        byte[] prk = J2jCrypto.hkdfExtract(salt, ikm);
        check("HKDF-Extract (RFC 5869 A.1)", J2jCrypto.hex(prk).equals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5"));
        check("HKDF-Expand (RFC 5869 A.1)", J2jCrypto.hex(J2jCrypto.hkdfExpand(prk, info, 42)).equals(
                "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"));
        // RFC 7748 6.1
        J2jCrypto.Ephemeral alice = J2jCrypto.ephemeral(J2jCrypto.unhex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"));
        J2jCrypto.Ephemeral bob = J2jCrypto.ephemeral(J2jCrypto.unhex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"));
        check("X25519 public keys (RFC 7748 6.1)", J2jCrypto.hex(alice.publicKey).equals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
                && J2jCrypto.hex(bob.publicKey).equals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"));
        String shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742";
        check("X25519 shared secret both ways", J2jCrypto.hex(J2jCrypto.agree(alice.privateKey, bob.publicKey)).equals(shared)
                && J2jCrypto.hex(J2jCrypto.agree(bob.privateKey, alice.publicKey)).equals(shared));
        boolean lowOrder;
        try {
            J2jCrypto.agree(alice.privateKey, new byte[32]);
            lowOrder = false;
        } catch (java.security.GeneralSecurityException e) {
            lowOrder = true;
        }
        check("X25519 low-order (all zero) key refused", lowOrder);

        FrameCipher send = new FrameCipher(KEY1);
        FrameCipher recv = new FrameCipher(KEY1);
        byte[] aad = {1, 2, 3};
        byte[] ct = send.seal(7, aad, "hello".getBytes(StandardCharsets.UTF_8));
        check("AES-GCM round trip, 16-octet tag", ct.length == 5 + 16 && new String(recv.open(7, aad, ct, 0, ct.length), StandardCharsets.UTF_8).equals("hello"));
        check("AES-GCM: altered ciphertext refused", fails(() -> {
            byte[] x = ct.clone();
            x[0] ^= 1;
            recv.open(7, aad, x, 0, x.length);
        }));
        check("AES-GCM: other counter refused", fails(() -> recv.open(8, aad, ct, 0, ct.length)));
        check("AES-GCM: other AAD refused", fails(() -> recv.open(7, new byte[] {1, 2, 4}, ct, 0, ct.length)));
        check("AES-GCM: other key refused", fails(() -> new FrameCipher(KEY2).open(7, aad, ct, 0, ct.length)));

        ReplayWindow w = new ReplayWindow();
        check("replay window: new, repeat, older within, too old", w.accept(5) && !w.accept(5) && w.accept(3) && !w.accept(3)
                && w.accept(2000) && !w.accept(5) && w.accept(1999) && w.accept(977) && !w.accept(976));
    }

    interface Throwing {
        void run() throws Exception;
    }

    static boolean fails(Throwing t) {
        try {
            t.run();
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    // ------------------------------------------------------------------ frames

    private static void frames() throws Exception {
        Envelope e = new Envelope(J2jWire.CLASS_RELIABLE, J2jWire.MSG_FLAG_MUST_UNDERSTAND, 16, 1, 2, 3, 4, 0x0101,
                new ByteOut().tlv(5, "x").toBytes(), new byte[] {9, 8, 7});
        byte[] enc = e.encode();
        Envelope d = Envelope.decode(enc);
        check("envelope: 36 fixed octets, round trip", Envelope.FIXED == 36 && enc.length == 36 + 5 + 3 && d.cls == 1 && d.flags == 1
                && d.ttl == 16 && d.source == 1 && d.destination == 2 && d.stream == 3 && d.sequence == 4 && d.type == 0x0101
                && d.ext.length == 5 && Arrays.equals(d.payload, new byte[] {9, 8, 7}) && d.mustUnderstand());
        check("envelope: truncated refused", fails(() -> Envelope.decode(Arrays.copyOf(enc, 20))));

        FrameCipher s = new FrameCipher(KEY1);
        byte[] f = TcpFrames.seal(s, J2jWire.FRAME_DATA, 0, 0, enc);
        TcpFrames.Frame r = TcpFrames.readSealed(new DataInputStream(new ByteArrayInputStream(f)), new FrameCipher(KEY1));
        check("sealed frame: length field covers the rest", f.length == 4 + 10 + enc.length + 16
                && new ByteIn(f).u32() == f.length - 4 && r.type == J2jWire.FRAME_DATA && Arrays.equals(r.body, enc));
        byte[] g = f.clone();
        g[4] = J2jWire.FRAME_PING;                       // the type is authenticated
        check("sealed frame: altered header refused", fails(() -> TcpFrames.readSealed(new DataInputStream(new ByteArrayInputStream(g)), new FrameCipher(KEY1))));
        byte[] huge = new ByteOut().u32(J2jWire.MAX_FRAME + 1L).u8(1).toBytes();
        check("frame longer than the limit refused", fails(() -> TcpFrames.readPlain(new DataInputStream(new ByteArrayInputStream(huge)))));

        // TLVs: unknown non-critical skipped, unknown critical refused
        byte[] tl = new ByteOut().tlv(1, "a").tlv(99, "b").tlv(2, "c").toBytes();
        check("TLV: unknown tag skipped", new ByteIn(tl).tlvs(1, 2).size() == 2);
        byte[] crit = new ByteOut().tlv(1, "a").tlv(99 | J2jWire.TLV_CRITICAL, "b").toBytes();
        check("TLV: unknown critical tag refused", fails(() -> new ByteIn(crit).tlvs(1, 2)));
    }

    // ------------------------------------------------------------------ handshake

    static final String MEMBERS = "member.a.addresses=127.0.0.1:1\nmember.b.addresses=127.0.0.1:2\n";

    private static Handshake.Local local(J2jConfig c) {
        return new Handshake.Local(c, 42, "SimWeaver test", new LinkedHashSet<String>(Arrays.asList("udp", "x-future")));
    }

    private static void handshake() throws Exception {
        Path d = files("net", MEMBERS, key("k1", KEY1));
        J2jConfig a = cfg(d, "a", 1);
        J2jConfig b = cfg(d, "b", 2);
        Handshake.Initiator i = new Handshake.Initiator(local(a), "b", null);
        Handshake.Responder r = new Handshake.Responder(new Handshake.Local(b, 43, "SimWeaver test", Collections.singleton("udp")), null);
        byte[] fin = i.welcome(r.hello(i.hello()));
        Handshake.Result rr = r.finish(fin);
        Handshake.Result ir = i.result();
        check("handshake: keys cross over", Arrays.equals(ir.sendKey(), rr.recvKey()) && Arrays.equals(ir.recvKey(), rr.sendKey())
                && !Arrays.equals(ir.sendKey(), ir.recvKey()));
        check("handshake: ids, boots, software, version", "b".equals(ir.peerId) && "a".equals(rr.peerId) && ir.peerBoot == 43 && rr.peerBoot == 42
                && "SimWeaver test".equals(ir.peerSoftware) && ir.version == 1 && rr.version == 1 && "k1".equals(ir.keyId));
        check("handshake: features are the common ones", ir.features.equals(Collections.singleton("udp")) && rr.features.equals(Collections.singleton("udp")));
        check("handshake: member hashes exchanged", a.memberHash().equals(ir.peerMemberHash));
        Handshake.Initiator i2 = new Handshake.Initiator(local(a), "b", null);
        Handshake.Responder r2 = new Handshake.Responder(local(b), null);
        i2.welcome(r2.hello(i2.hello()));
        check("handshake: fresh keys every time (forward secrecy)", !Arrays.equals(i2.result().sendKey(), ir.sendKey()));

        // wrong key (same id)
        Path d2 = files("net", MEMBERS, key("k1", KEY2));
        HandshakeException e = refusal(cfg(d, "a", 1), "b", cfg(d2, "b", 2), false);
        check("wrong key: initiator sees AUTH (the responder's proof fails)", e != null && e.code() == J2jWire.REFUSE_AUTH && !e.byPeer());
        HandshakeException e2 = refusal(cfg(d, "a", 1), "b", cfg(d2, "b", 2), true);
        check("wrong key: a forged FINISH is refused AUTH by the responder", e2 != null && e2.code() == J2jWire.REFUSE_AUTH);
        Path d3 = files("net", MEMBERS, key("k9", KEY1));
        check("unknown key id: KEY_ID", code(refusal(cfg(d3, "a", 1), "b", b, false)) == J2jWire.REFUSE_KEY_ID);
        Path d4 = files("other-net", MEMBERS, key("k1", KEY1));
        check("other network: NETWORK", code(refusal(cfg(d4, "a", 1), "b", b, false)) == J2jWire.REFUSE_NETWORK);
        Path d5 = files("net", MEMBERS + "member.z.addresses=h\n", key("k1", KEY1));
        check("not in the member list: NOT_MEMBER", code(refusal(cfg(d5, "z", 1), "b", b, false)) == J2jWire.REFUSE_NOT_MEMBER);
        check("wrong target: WRONG_TARGET", code(refusal(a, "c", b, false)) == J2jWire.REFUSE_WRONG_TARGET);
        check("own id: SELF", code(refusal(b, "b", b, false)) == J2jWire.REFUSE_SELF);

        // versions: a future peer speaking 2-3 only
        ByteOut h = new ByteOut().bytes(J2jWire.MAGIC).u8(2).u8(3).tlv(J2jWire.TLV_FROM, "a");
        HandshakeException ve = null;
        try {
            new Handshake.Responder(local(b), null).hello(h.toBytes());
        } catch (HandshakeException x) {
            ve = x;
        }
        check("versions 2-3 only: VERSION", code(ve) == J2jWire.REFUSE_VERSION);
        HandshakeException vr = Handshake.refused(Handshake.refuse(J2jWire.REFUSE_VERSION, "x"), "b");
        check("VERSION refusal names our range", vr.getMessage().contains("versions 1-1") && vr.byPeer());
        // a future peer speaking 1-5 with a new non-critical field: accepted at version 1
        Handshake.Initiator fi = new Handshake.Initiator(local(a), "b", null);
        byte[] hello = fi.hello();
        hello[4] = 5;                                     // vmax 5
        byte[] withNew = new ByteOut().bytes(hello).tlv(200, "something new").toBytes();
        Handshake.Responder fr = new Handshake.Responder(local(b), null);
        byte[] welcome = fr.hello(withNew);
        check("vmax 5 and an unknown TLV: answered with version 1", welcome[0] == 1);
        byte[] critical = new ByteOut().bytes(fi.hello()).tlv(201 | J2jWire.TLV_CRITICAL, "must know").toBytes();
        HandshakeException ce = null;
        try {
            new Handshake.Responder(local(b), null).hello(critical);
        } catch (HandshakeException x) {
            ce = x;
        }
        check("unknown critical TLV: PROTOCOL", code(ce) == J2jWire.REFUSE_PROTOCOL);
        check("not J2J: PROTOCOL", code(catching(() -> new Handshake.Responder(local(b), null).hello("GET / HTTP/1.1".getBytes(StandardCharsets.US_ASCII))))
                == J2jWire.REFUSE_PROTOCOL);
    }

    interface HsCall {
        void run() throws HandshakeException;
    }

    static HandshakeException catching(HsCall c) {
        try {
            c.run();
            return null;
        } catch (HandshakeException e) {
            return e;
        }
    }

    static int code(HandshakeException e) {
        return e == null ? -1 : e.code();
    }

    /** Runs a handshake in memory; returns the first refusal (null when it succeeds). */
    static HandshakeException refusal(J2jConfig ini, String target, J2jConfig res, boolean forgeFinish) {
        try {
            Handshake.Initiator i = new Handshake.Initiator(local(ini), target, null);
            Handshake.Responder r = new Handshake.Responder(local(res), null);
            byte[] w = r.hello(i.hello());
            if (forgeFinish) {
                r.finish(new ByteOut().tlv(J2jWire.TLV_AUTH, new byte[32]).toBytes());
                return null;
            }
            r.finish(i.welcome(w));
            return null;
        } catch (HandshakeException e) {
            return e;
        }
    }

    // ------------------------------------------------------------------ wire vectors

    /** The test vectors of doc/j2j-wire-protocol.md, appendix A. */
    private static void vectors() throws Exception {
        // fixed inputs: see the document
        Path d = files("example-net", "member.sto-1.addresses=10.10.0.5\nmember.got-2.addresses=10.20.0.7\n", key("k1", J2jCrypto.unhex(
                "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")));
        J2jConfig a = cfg(d, "sto-1", 8844);
        J2jConfig b = cfg(d, "got-2", 8844);
        J2jCrypto.Ephemeral ea = J2jCrypto.ephemeral(J2jCrypto.unhex("a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf"));
        J2jCrypto.Ephemeral eb = J2jCrypto.ephemeral(J2jCrypto.unhex("c0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedf"));
        Handshake.Initiator i = new Handshake.Initiator(new Handshake.Local(a, 1, "SimWeaver 0.10.0", Collections.<String>emptySet()), "got-2", ea);
        Handshake.Responder r = new Handshake.Responder(new Handshake.Local(b, 2, "SimWeaver 0.10.0", Collections.<String>emptySet()), eb);
        byte[] hello = i.hello();
        byte[] welcome = r.hello(hello);
        byte[] finish = i.welcome(welcome);
        r.finish(finish);
        FrameCipher c = new FrameCipher(i.result().sendKey());
        byte[] data = TcpFrames.seal(c, J2jWire.FRAME_DATA, 0, 0, new Envelope(J2jWire.CLASS_ADMIN, 0, 1, a.member("sto-1").key(),
                a.member("got-2").key(), 0, 1, 0x0001, new byte[0], "hi".getBytes(StandardCharsets.UTF_8)).encode());
        String[][] v = {
            {"member hash", a.memberHash()},
            {"ephemeral I", J2jCrypto.hex(ea.publicKey)},
            {"ephemeral R", J2jCrypto.hex(eb.publicKey)},
            {"HELLO body", J2jCrypto.hex(hello)},
            {"WELCOME body", J2jCrypto.hex(welcome)},
            {"FINISH body", J2jCrypto.hex(finish)},
            {"key I->R", J2jCrypto.hex(i.result().sendKey())},
            {"key R->I", J2jCrypto.hex(i.result().recvKey())},
            {"first DATA frame I->R", J2jCrypto.hex(data)},
            {"udp key I->R", J2jCrypto.hex(i.result().udpSendKey())},
            {"udp id I->R", Long.toHexString(i.result().udpSendId())},
            {"udp id R->I", Long.toHexString(i.result().udpRecvId())}
        };
        if (System.getProperty("j2j.printvectors") != null) {
            for (String[] x : v) {
                System.out.println(x[0] + ": " + x[1]);
            }
        }
        for (String[] x : v) {
            String expected = Vectors.get(x[0]);
            check("wire vector: " + x[0], expected != null && expected.equals(x[1]));
        }
    }

    // ------------------------------------------------------------------ live servers

    static final class Inbox implements LinkManager.Listener {
        final List<String> got = new CopyOnWriteArrayList<String>();
        final List<String> ups = new CopyOnWriteArrayList<String>();
        final List<String> downs = new CopyOnWriteArrayList<String>();

        @Override
        public void linkUp(Link link) {
            ups.add(link.peerId());
        }

        @Override
        public void linkDown(Link link, String reason) {
            downs.add(link.peerId() + ": " + reason);
        }

        @Override
        public void message(Link link, Envelope env) {
            if (env.type < 0x0100) {
                return;                                   // the network's own (link state, acks)
            }
            got.add(link.peerId() + ":" + env.cls + ":" + new String(env.payload, StandardCharsets.UTF_8));
        }
    }

    private static void live() throws Exception {
        int pa = freePort(), pb = freePort(), pc = freePort();
        String members = "member.a.addresses=127.0.0.1:" + pa + "\nmember.b.addresses=127.0.0.1:" + pb
                + "\nmember.c.addresses=127.0.0.1:" + pc + "\n";
        Path d = files("live-net", members, key("k1", KEY1));
        J2jNode a = J2jNode.start(cfg(d, "a", pa), "SimWeaver test");
        J2jNode b = J2jNode.start(cfg(d, "b", pb), "SimWeaver test");
        Inbox ia = new Inbox(), ib = new Inbox();
        a.links().addListener(ia);
        b.links().addListener(ib);
        J2jNode c = J2jNode.start(cfg(d, "c", pc), "SimWeaver test");
        try {
            check("three servers: every pair linked", await(() -> a.links().links().size() == 2 && b.links().links().size() == 2
                    && c.links().links().size() == 2, 8000));
            Thread.sleep(600);
            check("one link per pair after both dialled (tie-break)", a.links().links().size() == 2 && b.links().links().size() == 2
                    && a.links().link("b").peerBoot() == b.boot() && a.links().link("b").dialedBySelf()
                    && !b.links().link("a").dialedBySelf() && !c.links().link("a").dialedBySelf());
            check("link up events " + eventTexts("j2j.link.down"), events("j2j.link.up") >= 3 && events("j2j.link.down") == 0);
            Link ab = a.links().link("b");
            for (int n = 0; n < 50; n++) {
                ab.send(new Envelope(J2jWire.CLASS_RELIABLE, 0, 1, 1, 2, 0, n, 0x0101, null, ("r" + n).getBytes(StandardCharsets.UTF_8)), null);
            }
            check("50 reliable messages arrive in order", await(() -> ib.got.size() >= 50, 3000)
                    && ib.got.get(0).equals("a:1:r0") && ib.got.get(49).equals("a:1:r49"));
            check("RTT and clock offset measured", await(() -> ab.rttMs() >= 0, 3000) && ab.rttMs() < 200 && Math.abs(ab.clockOffsetMs()) < 200
                    && ab.rttHistory().length >= 1);
            // best effort superseded while the writer is held back by a big reliable backlog
            ib.got.clear();
            byte[] big = new byte[200_000];
            for (int n = 0; n < 20; n++) {
                ab.send(new Envelope(J2jWire.CLASS_RELIABLE, 0, 1, 1, 2, 0, n, 0x0101, null, big), null);
            }
            for (int n = 0; n < 200; n++) {
                ab.send(new Envelope(J2jWire.CLASS_BEST_EFFORT, 0, 1, 1, 2, 0, 0, 0x0102, null, ("s" + n).getBytes(StandardCharsets.UTF_8)), "obj-1");
            }
            check("best effort: superseded, the newest arrives", await(() -> ib.got.stream().anyMatch(x -> x.equals("a:2:s199")), 5000)
                    && ib.got.stream().filter(x -> x.startsWith("a:2:")).count() < 200 && ab.counters()[11] > 0);
            check("big reliable messages all arrive", await(() -> ib.got.stream().filter(x -> x.startsWith("a:1:")).count() == 20, 8000));

            // restart b: a sees the link go down and come back (new boot replaces)
            long oldBoot = b.boot();
            b.stop();
            boolean gone = await(() -> a.links().link("b") == null && ia.downs.size() >= 1, 5000);
            check("b stops: a sees link down (" + gone + ", " + ia.downs + ")", gone && ia.downs.size() >= 1);
            J2jNode b2 = J2jNode.start(cfg(d, "b", pb), "SimWeaver test");
            try {
                a.links().dialNow();
                check("b back: link up again, new boot", await(() -> a.links().link("b") != null && a.links().link("b").peerBoot() == b2.boot()
                        && b2.boot() != oldBoot, 8000));
            } finally {
                b2.stop();
            }

            // a server with the wrong key: refused both ways, events, state in the peer view
            int pw = freePort();
            Path dw = files("live-net", members.replace("127.0.0.1:" + pc, "127.0.0.1:" + pw), key("k1", KEY2));
            c.stop();
            await(() -> a.links().link("c") == null, 5000);
            long before = events("j2j.handshake.refused");
            J2jNode wrong = J2jNode.start(cfg(dw, "c", pw), "SimWeaver test");
            try {
                check("wrong key: refused, never linked", await(() -> events("j2j.handshake.refused") > before, 6000)
                        && wrong.links().links().isEmpty());
                check("wrong key: the dialler's view says refused", await(() -> wrong.links().peerView("a").refused != null, 6000)
                        && wrong.links().peerView("a").refused.contains("AUTH") || wrong.links().peerView("a").refused.contains("network key"));
            } finally {
                wrong.stop();
            }

            // a stranger (not in a's list) dials a five times: one event, counted
            Path ds = files("live-net", "member.a.addresses=127.0.0.1:" + pa + "\nmember.x.addresses=127.0.0.1:1\n", key("k1", KEY1));
            long beforeS = eventTexts("j2j.handshake.refused").stream().filter(t -> t.contains("from=x")).count();
            for (int n = 0; n < 5; n++) {
                try (java.net.Socket s = new java.net.Socket(InetAddress.getLoopbackAddress(), pa)) {
                    s.setSoTimeout(3000);
                    Handshake.Initiator hi = new Handshake.Initiator(local(cfg(ds, "x", 1)), "a", null);
                    TcpFrames.writePlain(s.getOutputStream(), J2jWire.FRAME_HELLO, hi.hello());
                    TcpFrames.Frame f = TcpFrames.readPlain(new DataInputStream(s.getInputStream()));
                    if (n == 0) {
                        check("a stranger gets REFUSE NOT_MEMBER", f.type == J2jWire.FRAME_REFUSE
                                && Handshake.refused(f.body, "a").code() == J2jWire.REFUSE_NOT_MEMBER);
                    }
                }
            }
            Thread.sleep(300);
            List<String> st = eventTexts("j2j.handshake.refused");
            check("stranger: one event, rate limited", st.stream().filter(t -> t.contains("from=x")).count() - beforeS == 1
                    && st.stream().anyMatch(t -> t.contains("not in the member list")));
            check("peer views: c down with an error", a.links().peerView("c").link == null && a.links().peerView("c").lastError != null);
        } finally {
            a.stop();
            b.stop();
            c.stop();
        }
        check("stop: CLOSE SHUTDOWN, links closed", await(() -> a.links().links().isEmpty(), 3000));
    }

    private static void udp() throws Exception {
        int pa = freePort(), pb = freePort();
        Path d = files("udp-net", "member.a.addresses=127.0.0.1:" + pa + "\nmember.b.addresses=127.0.0.1:" + pb + "\n", key("k1", KEY1));
        J2jNode a = J2jNode.start(cfg(d, "a", pa, true), "SimWeaver test");
        J2jNode b = J2jNode.start(cfg(d, "b", pb, true), "SimWeaver test");
        Inbox ib = new Inbox();
        b.links().addListener(ib);
        try {
            check("udp: link up with the feature", await(() -> a.links().link("b") != null && a.links().link("b").features().contains("udp"), 8000));
            check("udp: both directions up (pings answered)", await(() -> "up".equals(a.links().link("b").udpState())
                    && "up".equals(b.links().link("a").udpState()), 8000));
            check("udp: the accepting side learned the address", b.links().link("a").udpAddress() != null
                    && a.links().link("b").udpRttMs() >= 0);
            Link ab = a.links().link("b");
            for (int n = 0; n < 20; n++) {
                ab.send(new Envelope(J2jWire.CLASS_BEST_EFFORT, 0, 1, 1, 2, 0, 0, 0x0102, null, ("u" + n).getBytes(StandardCharsets.UTF_8)), null);
            }
            check("udp: best effort goes by datagram and arrives", await(() -> ib.got.stream().filter(x -> x.startsWith("a:2:u")).count() == 20, 3000)
                    && ab.counters()[14] >= 20);
            ab.send(new Envelope(J2jWire.CLASS_BEST_EFFORT, 0, 1, 1, 2, 0, 0, 0x0102, null, new byte[3000]), null);
            check("udp: a best-effort message too big for a datagram goes by TCP", await(() -> ib.got.size() == 21, 3000));
            ab.send(new Envelope(J2jWire.CLASS_RELIABLE, 0, 1, 1, 2, 0, 1, 0x0101, null, "r".getBytes(StandardCharsets.UTF_8)), null);
            check("udp: reliable stays on TCP", await(() -> ib.got.contains("a:1:r"), 3000) && ab.counters()[14] == 20);
            check("udp: loss figure 0", ab.udpLoss() >= 0 && ab.udpLoss() < 0.5);
        } finally {
            a.stop();
            b.stop();
        }
    }

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    /** Expected vector values (filled from the document's appendix A). */
    static final class Vectors {
        static final java.util.Map<String, String> V = new java.util.HashMap<String, String>();

        static {
            // checked against an independent implementation (Python cryptography), see the document
            V.put("member hash", "31374ef5");
            V.put("ephemeral I", "605a725d2a4adfeeb1a29e17edd621c1b7593ee8cdbc44ac6c4ab6e2f805d23c");
            V.put("ephemeral R", "dc2cca31e8e43bbd91dff7e475cca3347eb478107d5bd765aba4ae4a30c35d44");
            V.put("HELLO body", "4a324a01010001000b6578616d706c652d6e65740002000573746f2d3100030005676f742d32000400026b3100050020605a725d2a4adfeeb1a29e17edd621c1b7593ee8cdbc44ac6c4ab6e2f805d23c000700080000000000000001000800000009001053696d57656176657220302e31302e30000a000431374ef5");
            V.put("WELCOME body", "0100020005676f742d32000400026b3100050020dc2cca31e8e43bbd91dff7e475cca3347eb478107d5bd765aba4ae4a30c35d44000700080000000000000002000800000009001053696d57656176657220302e31302e30000a000431374ef50020002053e23b196e8e53f68aad9f7ee424e1dc763c49f1ceebd4b5ffe7eb31a01df652");
            V.put("FINISH body", "002000203341ccc9ee2391a6dfb2bfcd2ecdcabc4ee852f4a1c24afc6920f17edbf7a530");
            V.put("key I->R", "2480e7893608536d8009165ba83009d8b16bab784cbe3cf64e9bb9a581fd203e");
            V.put("key R->I", "760466c2b1935dec96642aad13b25237dff2b14bf718b489ad22f968a8ff239b");
            V.put("first DATA frame I->R", "00000040100000000000000000005a279a77c54f5f3b2f3f1dc35afc775955a72735fd4e8fcc6e46d55bd1be8809bd2a159aca9bea3d8a390f2b084bfd2b871b419c3374");
            V.put("udp key I->R", "2a2781a7d2034b5f54f6565747685ade50f934281b0308fda83e886852d11cf8");
            V.put("udp id I->R", "7575db19");
            V.put("udp id R->I", "c58a12fd");
        }

        static String get(String k) {
            return V.get(k);
        }
    }
}
