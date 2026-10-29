package com.simweaver.j2j.link;

import com.simweaver.j2j.wire.J2jWire;

/** A handshake that did not complete: refused by us, refused by the peer, or broken. */
public final class HandshakeException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int code;
    private final boolean byPeer;
    private final String claimedId;

    /**
     * @param code      a J2jWire.REFUSE_* code
     * @param byPeer    true when the peer refused us
     * @param claimedId the id the peer claimed (FROM), or null
     */
    public HandshakeException(int code, String detail, boolean byPeer, String claimedId) {
        super(detail);
        this.code = code;
        this.byPeer = byPeer;
        this.claimedId = claimedId;
    }

    public int code() {
        return code;
    }

    public String codeName() {
        return J2jWire.refuseName(code);
    }

    public boolean byPeer() {
        return byPeer;
    }

    public String claimedId() {
        return claimedId;
    }
}
