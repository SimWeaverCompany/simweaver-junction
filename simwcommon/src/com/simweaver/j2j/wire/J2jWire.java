package com.simweaver.j2j.wire;

/**
 * Constants of the J2J wire protocol: frame types, handshake TLV tags, refusal and close
 * codes, message classes and flags. The normative description, with byte layouts and test
 * vectors, is {@code doc/j2j-wire-protocol.md}; this class and that document must agree
 * (the self test checks the vectors).
 *
 * <p>Compatibility rules (design §6.3): versions and features are negotiated in the handshake;
 * unknown TLV tags are skipped unless critical ({@link #TLV_CRITICAL}); fields are only appended;
 * unknown frame and message types are ignored unless they carry the must-understand flag.
 */
public final class J2jWire {

    private J2jWire() {
    }

    /** The protocol versions this implementation speaks. */
    public static final int VERSION_MIN = 1;
    public static final int VERSION_MAX = 1;

    /** "J2J": the first three octets of a HELLO body. */
    public static final byte[] MAGIC = {0x4A, 0x32, 0x4A};

    /** Largest frame body (after the length field) accepted. */
    public static final int MAX_FRAME = 1024 * 1024 + 64;

    /** Largest message (envelope + payload) in one DATA frame. */
    public static final int MAX_MESSAGE = 1024 * 1024;

    // ---- frame types: plain (handshake) --------------------------------------------------
    public static final int FRAME_HELLO = 1;
    public static final int FRAME_WELCOME = 2;
    public static final int FRAME_FINISH = 3;
    public static final int FRAME_REFUSE = 4;

    // ---- frame types: encrypted ------------------------------------------------------------
    public static final int FRAME_DATA = 16;
    public static final int FRAME_PING = 17;
    public static final int FRAME_PONG = 18;
    public static final int FRAME_CLOSE = 19;

    /** Encrypted frame flags: the receiver must understand the type, or close the channel. */
    public static final int FRAME_FLAG_MUST_UNDERSTAND = 0x01;

    // ---- handshake TLV tags ----------------------------------------------------------------
    /** Bit 15 of a tag: a receiver that does not know the tag must refuse. */
    public static final int TLV_CRITICAL = 0x8000;
    public static final int TLV_NETWORK = 1;        // UTF-8: the network id
    public static final int TLV_FROM = 2;           // UTF-8: the sender's node id
    public static final int TLV_TO = 3;             // UTF-8: the node id the initiator wants
    public static final int TLV_KEY_ID = 4;         // UTF-8: the id of the network key used
    public static final int TLV_EPHEMERAL = 5;      // 32 octets: X25519 public key (RFC 7748)
    public static final int TLV_BOOT = 7;           // u64: random per process start
    public static final int TLV_FEATURES = 8;       // ASCII: comma separated feature names
    public static final int TLV_SOFTWARE = 9;       // UTF-8: e.g. "SimWeaver 0.10.0"
    public static final int TLV_MEMBER_HASH = 10;   // 4 octets: hash of the member list
    public static final int TLV_MESSAGE = 11;       // UTF-8: a human readable text
    public static final int TLV_VERSIONS = 12;      // 2 octets: min, max protocol version
    public static final int TLV_AUTH = 32;          // 32 octets: authenticator; always the last TLV

    // ---- refusal codes (REFUSE frame) ----------------------------------------------------
    public static final int REFUSE_VERSION = 1;
    public static final int REFUSE_NETWORK = 2;
    public static final int REFUSE_NOT_MEMBER = 3;
    public static final int REFUSE_WRONG_TARGET = 4;
    public static final int REFUSE_KEY_ID = 5;
    public static final int REFUSE_AUTH = 6;
    public static final int REFUSE_BUSY = 7;
    public static final int REFUSE_SELF = 8;
    public static final int REFUSE_PROTOCOL = 9;

    // ---- close codes (CLOSE frame) --------------------------------------------------------
    public static final int CLOSE_SHUTDOWN = 1;
    public static final int CLOSE_DUPLICATE = 2;
    public static final int CLOSE_TIMEOUT = 3;
    public static final int CLOSE_PROTOCOL = 4;
    public static final int CLOSE_REKEY = 5;
    public static final int CLOSE_MEMBER_REMOVED = 6;
    public static final int CLOSE_REPLACED = 7;

    // ---- message classes (envelope) ------------------------------------------------------
    public static final int CLASS_ADMIN = 0;
    public static final int CLASS_RELIABLE = 1;
    public static final int CLASS_BEST_EFFORT = 2;

    // ---- envelope flags -------------------------------------------------------------------
    public static final int MSG_FLAG_MUST_UNDERSTAND = 0x01;
    public static final int MSG_FLAG_SESSION = 0x04;  // destination is a session key, not a node key

    /** @return the name of a refusal code, for logs */
    public static String refuseName(int code) {
        switch (code) {
            case REFUSE_VERSION: return "VERSION";
            case REFUSE_NETWORK: return "NETWORK";
            case REFUSE_NOT_MEMBER: return "NOT_MEMBER";
            case REFUSE_WRONG_TARGET: return "WRONG_TARGET";
            case REFUSE_KEY_ID: return "KEY_ID";
            case REFUSE_AUTH: return "AUTH";
            case REFUSE_BUSY: return "BUSY";
            case REFUSE_SELF: return "SELF";
            case REFUSE_PROTOCOL: return "PROTOCOL";
            default: return "CODE_" + code;
        }
    }

    /** @return the name of a close code, for logs */
    public static String closeName(int code) {
        switch (code) {
            case CLOSE_SHUTDOWN: return "SHUTDOWN";
            case CLOSE_DUPLICATE: return "DUPLICATE";
            case CLOSE_TIMEOUT: return "TIMEOUT";
            case CLOSE_PROTOCOL: return "PROTOCOL";
            case CLOSE_REKEY: return "REKEY";
            case CLOSE_MEMBER_REMOVED: return "MEMBER_REMOVED";
            case CLOSE_REPLACED: return "REPLACED";
            default: return "CODE_" + code;
        }
    }
}
