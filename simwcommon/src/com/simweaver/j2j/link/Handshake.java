package com.simweaver.j2j.link;

import com.simweaver.j2j.config.J2jConfig;
import com.simweaver.j2j.crypto.J2jCrypto;
import com.simweaver.j2j.wire.ByteIn;
import com.simweaver.j2j.wire.ByteOut;
import com.simweaver.j2j.wire.J2jWire;
import com.simweaver.j2j.wire.WireException;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The J2J handshake (protocol version 1), as message bodies without I/O, so the test vectors can
 * run it with fixed keys. Pattern: Noise NNpsk0-like, mutual authentication by the network key,
 * forward secrecy by ephemeral X25519.
 *
 * <pre>
 * I → R  HELLO    "J2J" | u8 vmin | u8 vmax | TLVs: NETWORK FROM TO KEY_ID EPHEMERAL BOOT FEATURES SOFTWARE MEMBER_HASH
 * R → I  WELCOME  u8 version | TLVs: FROM KEY_ID EPHEMERAL BOOT FEATURES SOFTWARE MEMBER_HASH, then AUTH (last)
 * I → R  FINISH   TLVs: AUTH
 *
 * th     = SHA-256("J2J v1 handshake" | HELLO body | WELCOME body without its AUTH TLV)
 * prk    = HKDF-Extract(salt = network key, ikm = X25519(ephemeral I, ephemeral R))
 * AUTH R = HKDF-Expand(prk, "j2j v1 auth responder" | th, 32)
 * AUTH I = HKDF-Expand(prk, "j2j v1 auth initiator" | th, 32)
 * key I→R = HKDF-Expand(prk, "j2j v1 key i2r" | th, 32);  key R→I = HKDF-Expand(prk, "j2j v1 key r2i" | th, 32)
 * </pre>
 *
 * Either side may answer with REFUSE: u16 code | TLVs MESSAGE, VERSIONS.
 */
public final class Handshake {

    static final int[] HELLO_TAGS = {J2jWire.TLV_NETWORK, J2jWire.TLV_FROM, J2jWire.TLV_TO, J2jWire.TLV_KEY_ID,
        J2jWire.TLV_EPHEMERAL, J2jWire.TLV_BOOT, J2jWire.TLV_FEATURES, J2jWire.TLV_SOFTWARE, J2jWire.TLV_MEMBER_HASH};
    static final int[] WELCOME_TAGS = {J2jWire.TLV_FROM, J2jWire.TLV_KEY_ID, J2jWire.TLV_EPHEMERAL, J2jWire.TLV_BOOT,
        J2jWire.TLV_FEATURES, J2jWire.TLV_SOFTWARE, J2jWire.TLV_MEMBER_HASH, J2jWire.TLV_AUTH};

    private static final byte[] LABEL_TH = "J2J v1 handshake".getBytes(StandardCharsets.US_ASCII);

    private Handshake() {
    }

    /** What a completed handshake agreed on. */
    public static final class Result {
        public final String peerId;
        public final long peerBoot;
        public final String peerSoftware;
        public final String peerMemberHash;
        public final Set<String> features;
        public final int version;
        public final String keyId;
        final byte[] sendKey;
        final byte[] recvKey;
        /** feature "udp": the datagram keys and the connection ids that open each direction's datagrams */
        final byte[] udpSendKey;
        final byte[] udpRecvKey;
        final long udpSendId;
        final long udpRecvId;

        Result(String peerId, long peerBoot, String peerSoftware, String peerMemberHash, Set<String> features, int version,
               String keyId, byte[] prk, byte[] th, boolean initiator) {
            this.peerId = peerId;
            this.peerBoot = peerBoot;
            this.peerSoftware = peerSoftware;
            this.peerMemberHash = peerMemberHash;
            this.features = Collections.unmodifiableSet(features);
            this.version = version;
            this.keyId = keyId;
            byte[] i2r = J2jCrypto.hkdfExpand(prk, label("j2j v1 key i2r", th), 32);
            byte[] r2i = J2jCrypto.hkdfExpand(prk, label("j2j v1 key r2i", th), 32);
            byte[] ui2r = J2jCrypto.hkdfExpand(prk, label("j2j v1 udp key i2r", th), 32);
            byte[] ur2i = J2jCrypto.hkdfExpand(prk, label("j2j v1 udp key r2i", th), 32);
            long idi2r = u32(J2jCrypto.hkdfExpand(prk, label("j2j v1 udp id i2r", th), 4));
            long idr2i = u32(J2jCrypto.hkdfExpand(prk, label("j2j v1 udp id r2i", th), 4));
            this.sendKey = initiator ? i2r : r2i;
            this.recvKey = initiator ? r2i : i2r;
            this.udpSendKey = initiator ? ui2r : ur2i;
            this.udpRecvKey = initiator ? ur2i : ui2r;
            this.udpSendId = initiator ? idi2r : idr2i;
            this.udpRecvId = initiator ? idr2i : idi2r;
        }

        private static long u32(byte[] b) {
            return ((long) (b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
        }

        /** For the test vectors. */
        public byte[] udpSendKey() {
            return udpSendKey.clone();
        }

        public long udpSendId() {
            return udpSendId;
        }

        public long udpRecvId() {
            return udpRecvId;
        }

        /** For the test vectors. */
        public byte[] sendKey() {
            return sendKey.clone();
        }

        public byte[] recvKey() {
            return recvKey.clone();
        }
    }

    /** What this side offers: its id, boot, software and features. */
    public static final class Local {
        final J2jConfig cfg;
        final long boot;
        final String software;
        final Set<String> features;

        public Local(J2jConfig cfg, long boot, String software, Set<String> features) {
            this.cfg = cfg;
            this.boot = boot;
            this.software = software;
            this.features = features;
        }
    }

    // ---- initiator ------------------------------------------------------------------------

    /** The dialling side. */
    public static final class Initiator {
        private final Local local;
        private final String expectedPeer;
        private final J2jCrypto.Ephemeral eph;
        private final J2jConfig.Key key;
        private byte[] hello;
        private Result result;

        public Initiator(Local local, String expectedPeer, J2jCrypto.Ephemeral eph) {
            this.local = local;
            this.expectedPeer = expectedPeer;
            this.eph = eph == null ? J2jCrypto.newEphemeral() : eph;
            this.key = local.cfg.preferredKey();
        }

        public byte[] hello() {
            ByteOut o = new ByteOut(200);
            o.bytes(J2jWire.MAGIC).u8(J2jWire.VERSION_MIN).u8(J2jWire.VERSION_MAX);
            o.tlv(J2jWire.TLV_NETWORK, local.cfg.networkId());
            o.tlv(J2jWire.TLV_FROM, local.cfg.selfId());
            o.tlv(J2jWire.TLV_TO, expectedPeer);
            o.tlv(J2jWire.TLV_KEY_ID, key.id);
            o.tlv(J2jWire.TLV_EPHEMERAL, eph.publicKey);
            o.tlvU64(J2jWire.TLV_BOOT, local.boot);
            o.tlv(J2jWire.TLV_FEATURES, String.join(",", local.features));
            o.tlv(J2jWire.TLV_SOFTWARE, local.software);
            o.tlv(J2jWire.TLV_MEMBER_HASH, J2jCrypto.unhex(local.cfg.memberHash()));
            hello = o.toBytes();
            return hello;
        }

        /** @return the FINISH body; {@link #result()} is then set */
        public byte[] welcome(byte[] body) throws HandshakeException {
            try {
                ByteIn in = new ByteIn(body);
                int version = in.u8();
                if (version < J2jWire.VERSION_MIN || version > J2jWire.VERSION_MAX) {
                    throw new HandshakeException(J2jWire.REFUSE_VERSION, "the peer chose protocol version " + version, false, null);
                }
                int authAt = authOffset(body, 1);
                Map<Integer, byte[]> t = new ByteIn(body, 1, body.length - 1).tlvs(WELCOME_TAGS);
                String from = ByteIn.utf8(t.get(J2jWire.TLV_FROM));
                if (!expectedPeer.equals(from)) {
                    throw new HandshakeException(J2jWire.REFUSE_WRONG_TARGET, "answered as '" + from + "', not " + expectedPeer, false, from);
                }
                if (!key.id.equals(ByteIn.utf8(t.get(J2jWire.TLV_KEY_ID)))) {
                    throw new HandshakeException(J2jWire.REFUSE_KEY_ID, "the peer used another key id", false, from);
                }
                byte[] eR = t.get(J2jWire.TLV_EPHEMERAL);
                byte[] auth = t.get(J2jWire.TLV_AUTH);
                if (eR == null || auth == null || authAt < 0) {
                    throw new HandshakeException(J2jWire.REFUSE_PROTOCOL, "WELCOME lacks EPHEMERAL or AUTH", false, from);
                }
                byte[] th = J2jCrypto.sha256(LABEL_TH, hello, Arrays.copyOf(body, authAt));
                byte[] prk = J2jCrypto.hkdfExtract(key.secret(), J2jCrypto.agree(eph.privateKey, eR));
                if (!J2jCrypto.equal(auth, J2jCrypto.hkdfExpand(prk, label("j2j v1 auth responder", th), 32))) {
                    throw new HandshakeException(J2jWire.REFUSE_AUTH, "the peer does not hold the network key " + key.id, false, from);
                }
                Set<String> features = intersect(local.features, ByteIn.utf8(t.get(J2jWire.TLV_FEATURES)));
                result = new Result(from, ByteIn.u64(t.get(J2jWire.TLV_BOOT)), ByteIn.utf8(t.get(J2jWire.TLV_SOFTWARE)),
                        hexOrNull(t.get(J2jWire.TLV_MEMBER_HASH)), features, version, key.id, prk, th, true);
                return new ByteOut(40).tlv(J2jWire.TLV_AUTH, J2jCrypto.hkdfExpand(prk, label("j2j v1 auth initiator", th), 32)).toBytes();
            } catch (WireException e) {
                throw new HandshakeException(J2jWire.REFUSE_PROTOCOL, "bad WELCOME: " + e.getMessage(), false, null);
            } catch (GeneralSecurityException e) {
                throw new HandshakeException(J2jWire.REFUSE_PROTOCOL, "bad WELCOME: " + e.getMessage(), false, null);
            }
        }

        public Result result() {
            return result;
        }
    }

    // ---- responder ------------------------------------------------------------------------

    /** The accepting side. */
    public static final class Responder {
        private final Local local;
        private final J2jCrypto.Ephemeral eph;
        private byte[] authI;
        private Result pending;
        private String claimed;

        public Responder(Local local, J2jCrypto.Ephemeral eph) {
            this.local = local;
            this.eph = eph == null ? J2jCrypto.newEphemeral() : eph;
        }

        /** @return the id the initiator claimed (after {@link #hello}), or null */
        public String claimedId() {
            return claimed;
        }

        /** @return the WELCOME body */
        public byte[] hello(byte[] body) throws HandshakeException {
            try {
                ByteIn in = new ByteIn(body);
                byte[] magic = in.bytes(3);
                if (!Arrays.equals(magic, J2jWire.MAGIC)) {
                    throw new HandshakeException(J2jWire.REFUSE_PROTOCOL, "not a J2J HELLO", false, null);
                }
                int vmin = in.u8();
                int vmax = in.u8();
                int version = Math.min(vmax, J2jWire.VERSION_MAX);
                Map<Integer, byte[]> t = in.tlvs(HELLO_TAGS);
                claimed = ByteIn.utf8(t.get(J2jWire.TLV_FROM));
                if (version < Math.max(vmin, J2jWire.VERSION_MIN)) {
                    throw new HandshakeException(J2jWire.REFUSE_VERSION, "it speaks protocol versions " + vmin + "-" + vmax
                            + ", this server " + J2jWire.VERSION_MIN + "-" + J2jWire.VERSION_MAX, false, claimed);
                }
                J2jConfig cfg = local.cfg;
                String net = ByteIn.utf8(t.get(J2jWire.TLV_NETWORK));
                if (!cfg.networkId().equals(net)) {
                    throw new HandshakeException(J2jWire.REFUSE_NETWORK, "network '" + net + "', not " + cfg.networkId(), false, claimed);
                }
                if (claimed == null || cfg.member(claimed) == null) {
                    throw new HandshakeException(J2jWire.REFUSE_NOT_MEMBER, "not in the member list", false, claimed);
                }
                if (claimed.equals(cfg.selfId())) {
                    throw new HandshakeException(J2jWire.REFUSE_SELF, "it claims this server's own id", false, claimed);
                }
                String to = ByteIn.utf8(t.get(J2jWire.TLV_TO));
                if (to != null && !to.equals(cfg.selfId())) {
                    throw new HandshakeException(J2jWire.REFUSE_WRONG_TARGET, "it wanted '" + to + "', this is " + cfg.selfId(), false, claimed);
                }
                String keyId = ByteIn.utf8(t.get(J2jWire.TLV_KEY_ID));
                J2jConfig.Key key = keyId == null ? null : cfg.key(keyId);
                if (key == null) {
                    throw new HandshakeException(J2jWire.REFUSE_KEY_ID, "unknown key id " + keyId, false, claimed);
                }
                byte[] eI = t.get(J2jWire.TLV_EPHEMERAL);
                if (eI == null || eI.length != 32) {
                    throw new HandshakeException(J2jWire.REFUSE_PROTOCOL, "HELLO lacks EPHEMERAL", false, claimed);
                }
                Set<String> features = intersect(local.features, ByteIn.utf8(t.get(J2jWire.TLV_FEATURES)));
                ByteOut o = new ByteOut(200);
                o.u8(version);
                o.tlv(J2jWire.TLV_FROM, cfg.selfId());
                o.tlv(J2jWire.TLV_KEY_ID, key.id);
                o.tlv(J2jWire.TLV_EPHEMERAL, eph.publicKey);
                o.tlvU64(J2jWire.TLV_BOOT, local.boot);
                o.tlv(J2jWire.TLV_FEATURES, String.join(",", features));
                o.tlv(J2jWire.TLV_SOFTWARE, local.software);
                o.tlv(J2jWire.TLV_MEMBER_HASH, J2jCrypto.unhex(cfg.memberHash()));
                byte[] noAuth = o.toBytes();
                byte[] th = J2jCrypto.sha256(LABEL_TH, body, noAuth);
                byte[] prk = J2jCrypto.hkdfExtract(key.secret(), J2jCrypto.agree(eph.privateKey, eI));
                o.tlv(J2jWire.TLV_AUTH, J2jCrypto.hkdfExpand(prk, label("j2j v1 auth responder", th), 32));
                authI = J2jCrypto.hkdfExpand(prk, label("j2j v1 auth initiator", th), 32);
                pending = new Result(claimed, ByteIn.u64(t.get(J2jWire.TLV_BOOT)), ByteIn.utf8(t.get(J2jWire.TLV_SOFTWARE)),
                        hexOrNull(t.get(J2jWire.TLV_MEMBER_HASH)), features, version, key.id, prk, th, false);
                return o.toBytes();
            } catch (WireException e) {
                throw new HandshakeException(J2jWire.REFUSE_PROTOCOL, "bad HELLO: " + e.getMessage(), false, claimed);
            } catch (GeneralSecurityException e) {
                throw new HandshakeException(J2jWire.REFUSE_PROTOCOL, "bad HELLO: " + e.getMessage(), false, claimed);
            }
        }

        /** @return the agreed result */
        public Result finish(byte[] body) throws HandshakeException {
            try {
                Map<Integer, byte[]> t = new ByteIn(body).tlvs(J2jWire.TLV_AUTH);
                if (pending == null || !J2jCrypto.equal(t.get(J2jWire.TLV_AUTH), authI)) {
                    throw new HandshakeException(J2jWire.REFUSE_AUTH, "it does not hold the network key", false, claimed);
                }
                return pending;
            } catch (WireException e) {
                throw new HandshakeException(J2jWire.REFUSE_PROTOCOL, "bad FINISH: " + e.getMessage(), false, claimed);
            }
        }
    }

    // ---- REFUSE ----------------------------------------------------------------------------

    public static byte[] refuse(int code, String message) {
        ByteOut o = new ByteOut(64).u16(code);
        if (message != null && !message.isEmpty()) {
            o.tlv(J2jWire.TLV_MESSAGE, message.length() > 200 ? message.substring(0, 200) : message);
        }
        if (code == J2jWire.REFUSE_VERSION) {
            o.tlv(J2jWire.TLV_VERSIONS, new byte[] {(byte) J2jWire.VERSION_MIN, (byte) J2jWire.VERSION_MAX});
        }
        return o.toBytes();
    }

    /** @return the exception a received REFUSE stands for */
    public static HandshakeException refused(byte[] body, String peer) {
        try {
            ByteIn in = new ByteIn(body);
            int code = in.u16();
            Map<Integer, byte[]> t = in.tlvs(J2jWire.TLV_MESSAGE, J2jWire.TLV_VERSIONS);
            String msg = ByteIn.utf8(t.get(J2jWire.TLV_MESSAGE));
            byte[] v = t.get(J2jWire.TLV_VERSIONS);
            String text = J2jWire.refuseName(code) + (msg == null ? "" : ": " + msg)
                    + (v != null && v.length == 2 ? " (it speaks versions " + v[0] + "-" + v[1] + ")" : "");
            return new HandshakeException(code, text, true, peer);
        } catch (WireException e) {
            return new HandshakeException(J2jWire.REFUSE_PROTOCOL, "bad REFUSE", true, peer);
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static byte[] label(String l, byte[] th) {
        byte[] a = l.getBytes(StandardCharsets.US_ASCII);
        byte[] r = Arrays.copyOf(a, a.length + th.length);
        System.arraycopy(th, 0, r, a.length, th.length);
        return r;
    }

    /** Offset of the AUTH TLV in a WELCOME body (TLVs from {@code from}); it must be the last; -1 if absent. */
    static int authOffset(byte[] body, int from) throws WireException {
        ByteIn in = new ByteIn(body, from, body.length - from);
        int at = -1;
        while (in.remaining() > 0) {
            int pos = in.position();
            int tag = in.u16() & ~J2jWire.TLV_CRITICAL;
            int len = in.u16();
            in.bytes(len);
            if (tag == J2jWire.TLV_AUTH) {
                at = pos;
                if (in.remaining() > 0) {
                    throw new WireException("AUTH is not the last TLV");
                }
            }
        }
        return at;
    }

    private static Set<String> intersect(Set<String> mine, String theirs) {
        Set<String> r = new LinkedHashSet<String>();
        if (theirs == null) {
            return r;
        }
        for (String f : theirs.split(",")) {
            if (mine.contains(f.trim())) {
                r.add(f.trim());
            }
        }
        return r;
    }

    private static String hexOrNull(byte[] b) {
        return b == null ? null : J2jCrypto.hex(b);
    }
}
