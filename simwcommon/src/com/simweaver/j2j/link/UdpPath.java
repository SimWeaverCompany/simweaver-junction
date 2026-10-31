package com.simweaver.j2j.link;

import com.simweaver.j2j.crypto.FrameCipher;
import com.simweaver.j2j.crypto.ReplayWindow;
import com.simweaver.j2j.wire.ByteIn;
import com.simweaver.j2j.wire.ByteOut;
import com.simweaver.j2j.wire.WireException;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;

/**
 * The datagram side of a link (feature "udp"): best-effort messages and the UDP liveness pings.
 *
 * <pre>
 * datagram:  u32 connection id | u8 type | u8 flags | u64 counter | ciphertext | tag (16)
 *            AAD = the first 14 octets; nonce = 00 00 00 00 | counter; the keys and ids come from the handshake
 * </pre>
 *
 * The receiver accepts a counter once, within a window of 1024 ({@link ReplayWindow}). The
 * accepting side learns the peer's address from its first authenticated datagram, and follows it
 * when it changes (NAT).
 */
final class UdpPath {

    static final int MAX_DATAGRAM = 1400;
    static final int HEADER = 14;

    final long sendId;
    final long recvId;
    private final FrameCipher send;
    private final FrameCipher recv;
    private final ReplayWindow window = new ReplayWindow();
    private long counter;
    volatile InetSocketAddress remote;
    volatile long lastHeard;
    volatile long lastPong;
    volatile double srtt = -1;
    long lastPing;
    long pingsSent;
    long pongsSeen;
    private final long[] lossWindow = new long[2];      // pings, pongs at the start of the current 30 s window
    private long lossWindowStart = System.currentTimeMillis();
    private volatile double loss;

    UdpPath(Handshake.Result r, InetSocketAddress remote) {
        this.sendId = r.udpSendId;
        this.recvId = r.udpRecvId;
        this.send = new FrameCipher(r.udpSendKey);
        this.recv = new FrameCipher(r.udpRecvKey);
        this.remote = remote;
    }

    /** @return false when there is no peer address yet or the datagram would be too big */
    synchronized boolean send(DatagramSocket socket, int type, byte[] plain) {
        InetSocketAddress to = remote;
        if (to == null || socket == null || HEADER + plain.length + FrameCipher.TAG_BYTES > MAX_DATAGRAM) {
            return false;
        }
        try {
            byte[] h = new ByteOut(HEADER).u32(sendId).u8(type).u8(0).u64(counter++).toBytes();
            byte[] ct = send.seal(counter - 1, h, plain);
            byte[] d = new byte[h.length + ct.length];
            System.arraycopy(h, 0, d, 0, h.length);
            System.arraycopy(ct, 0, d, h.length, ct.length);
            socket.send(new DatagramPacket(d, d.length, to));
            return true;
        } catch (GeneralSecurityException | java.io.IOException e) {
            return false;
        }
    }

    /** @return {type, plaintext} of an authentic, new datagram, or null */
    synchronized Object[] open(byte[] d, int len, InetSocketAddress from) {
        try {
            ByteIn in = new ByteIn(d, 0, len);
            in.u32();
            int type = in.u8();
            in.u8();
            long ctr = in.u64();
            byte[] aad = new byte[HEADER];
            System.arraycopy(d, 0, aad, 0, HEADER);
            byte[] plain = recv.open(ctr, aad, d, HEADER, len - HEADER);
            if (!window.accept(ctr)) {
                return null;
            }
            lastHeard = System.currentTimeMillis();
            remote = from;
            return new Object[] {type, plain};
        } catch (WireException | GeneralSecurityException e) {
            return null;
        }
    }

    boolean isUp(long deadMs) {
        return remote != null && System.currentTimeMillis() - lastPong < deadMs;
    }

    void pong(long sentAt) {
        long now = System.currentTimeMillis();
        lastPong = now;
        pongsSeen++;
        double rtt = Math.max(0, now - sentAt);
        srtt = srtt < 0 ? rtt : srtt * 0.875 + rtt * 0.125;
    }

    /** Loss over roughly the last 30 s, from pings without a pong. */
    synchronized double loss() {
        long now = System.currentTimeMillis();
        if (now - lossWindowStart > 30_000) {
            long p = pingsSent - lossWindow[0];
            long q = pongsSeen - lossWindow[1];
            loss = p <= 1 ? 0 : Math.max(0, Math.min(1, 1 - (double) q / (p - 1)));
            lossWindow[0] = pingsSent;
            lossWindow[1] = pongsSeen;
            lossWindowStart = now;
        }
        return loss;
    }
}
