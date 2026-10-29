package com.simweaver.j2j.link;

import com.simweaver.j2j.crypto.FrameCipher;
import com.simweaver.j2j.wire.ByteIn;
import com.simweaver.j2j.wire.ByteOut;
import com.simweaver.j2j.wire.Envelope;
import com.simweaver.j2j.wire.J2jWire;
import com.simweaver.j2j.wire.TcpFrames;
import com.simweaver.j2j.wire.WireException;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * An authenticated, encrypted TCP channel to one neighbour, after the handshake: a reader thread,
 * a writer thread with the link's {@link SendQueue}, a PING every second, RTT and clock offset,
 * and death after {@code deadSeconds} of silence.
 */
public final class Link {

    /** What a link reports to its owner (J2jNode). Called on the link's own threads: be quick. */
    public interface Owner {
        void onMessage(Link link, Envelope env);

        void onClosed(Link link, int code, String reason, boolean byPeer);
    }

    private static final long PING_MS = 1000;

    private final Owner owner;
    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private final Handshake.Result hs;
    private final boolean dialedBySelf;
    private final String address;
    private final int deadSeconds;
    private final FrameCipher sendCipher;
    private final FrameCipher recvCipher;
    private final SendQueue queue;
    private final ReentrantLock writeLock = new ReentrantLock();
    private volatile UdpPath udp;
    private volatile java.net.DatagramSocket udpSocket;
    private volatile String kind = "tcp";
    private final AtomicBoolean closed = new AtomicBoolean();
    private final long upSince = System.currentTimeMillis();
    private long sendCounter;
    private long recvCounter;
    private volatile long lastReceived = System.currentTimeMillis();
    private volatile double srtt = -1;
    private volatile long clockOffset;
    private volatile int closeCode;
    private volatile String closeReason;
    private volatile boolean closeByPeer;
    private volatile long pongDue;
    private volatile long pongTime;
    private volatile long pongId;
    private final double[] rttHistory = new double[60];
    private int rttIndex;
    private int rttCount;
    private long pingId;
    /** The local send time (System.nanoTime) of the last PINGs by id mod 8: the RTT finer than the wire's ms */
    private final long[] pingNanos = new long[8];
    private final long[] pingIds = new long[8];

    // counters
    final AtomicLong framesOut = new AtomicLong();
    final AtomicLong framesIn = new AtomicLong();
    final AtomicLong bytesOut = new AtomicLong();
    final AtomicLong bytesIn = new AtomicLong();
    final AtomicLong[] msgsOut = {new AtomicLong(), new AtomicLong(), new AtomicLong()};
    final AtomicLong[] msgsIn = {new AtomicLong(), new AtomicLong(), new AtomicLong()};
    final AtomicLong unknownIn = new AtomicLong();
    final AtomicLong udpOut = new AtomicLong();
    final AtomicLong udpIn = new AtomicLong();

    // ---- UDP (feature "udp"), driven by LinkManager -----------------------------------------

    void attachUdp(UdpPath path, java.net.DatagramSocket socket) {
        this.udpSocket = socket;
        this.udp = path;
    }

    UdpPath udpPath() {
        return udp;
    }

    void udpData(Envelope env) {
        if (closed.get()) {
            return;
        }
        udpIn.incrementAndGet();
        msgsIn[Math.min(2, env.cls)].incrementAndGet();
        lastReceived = System.currentTimeMillis();
        owner.onMessage(this, env);
    }

    void kind(String k) {
        this.kind = k;
    }

    /** @return "tcp" or "tls443" */
    public String kind() {
        return kind;
    }

    /** @return "up", "down" (negotiated, no answer) or "none" (not used) */
    public String udpState() {
        UdpPath u = udp;
        return u == null ? "none" : u.isUp(deadSeconds * 1000L) ? "up" : "down";
    }

    /** @return the UDP path's smoothed RTT in ms, or -1 */
    public double udpRttMs() {
        UdpPath u = udp;
        return u == null ? -1 : u.srtt;
    }

    /** @return the UDP loss ratio over about 30 s (0..1), or -1 without UDP */
    public double udpLoss() {
        UdpPath u = udp;
        return u == null ? -1 : u.loss();
    }

    /** @return the peer's UDP address, or null */
    public String udpAddress() {
        UdpPath u = udp;
        java.net.InetSocketAddress a = u == null ? null : u.remote;
        return a == null ? null : a.getAddress().getHostAddress() + ":" + a.getPort();
    }

    Link(Owner owner, Socket socket, DataInputStream in, OutputStream out, Handshake.Result hs, boolean dialedBySelf,
         String address, int deadSeconds, long reliableMaxBytes, int bestEffortMax, long bestEffortMaxAgeMs) {
        this.owner = owner;
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.hs = hs;
        this.dialedBySelf = dialedBySelf;
        this.address = address;
        this.deadSeconds = deadSeconds;
        this.sendCipher = new FrameCipher(hs.sendKey);
        this.recvCipher = new FrameCipher(hs.recvKey);
        this.queue = new SendQueue(reliableMaxBytes, bestEffortMax, bestEffortMaxAgeMs);
    }

    /**
     * Wraps a socket whose handshake completed.
     *
     * @param in the stream the handshake read from: its buffer may already hold the first frames
     */
    static Link over(Owner owner, Socket s, DataInputStream in, Handshake.Result hs, boolean dialedBySelf, int deadSeconds,
                     long reliableMaxBytes, int bestEffortMax, long bestEffortMaxAgeMs) throws IOException {
        OutputStream out = new BufferedOutputStream(s.getOutputStream(), 65536);
        String addr = s.getInetAddress().getHostAddress() + ":" + s.getPort();
        return new Link(owner, s, in, out, hs, dialedBySelf, addr, deadSeconds, reliableMaxBytes, bestEffortMax, bestEffortMaxAgeMs);
    }

    void start(String name) {
        try {
            socket.setSoTimeout(deadSeconds * 1000);
        } catch (IOException ignored) {
            // the reader still ends on a broken socket
        }
        Thread r = new Thread(this::readLoop, "j2j-read-" + name);
        Thread w = new Thread(this::writeLoop, "j2j-write-" + name);
        r.setDaemon(true);
        w.setDaemon(true);
        r.start();
        w.start();
    }

    /**
     * Queues a message.
     *
     * @param supersede best effort: replaces a queued message with this key (may be null)
     * @return false when its class's queue is full or the link is closed
     */
    public boolean send(Envelope env, String supersede) {
        UdpPath u = udp;
        if (env.cls == J2jWire.CLASS_BEST_EFFORT && u != null && u.isUp(deadSeconds * 1000L)) {
            byte[] b = env.encode();
            if (u.send(udpSocket, J2jWire.FRAME_DATA, b)) {
                msgsOut[2].incrementAndGet();
                udpOut.incrementAndGet();
                return true;
            }
        }
        boolean ok = queue.offer(env, supersede);
        if (ok) {
            msgsOut[Math.min(2, env.cls)].incrementAndGet();
        }
        return ok;
    }

    /** Moves what still waits in this link's queue to its replacement (rekey, duplicate, restart). */
    void handOver(Link replacement) {
        queue.drainTo(replacement.queue);
    }

    /** Closes the link: a CLOSE frame if possible, then the socket. Idempotent. */
    public void close(int code, String reason) {
        closeInternal(code, reason, false, true);
    }

    private void closeInternal(int code, String reason, boolean byPeer, boolean sendClose) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        closeCode = code;
        closeReason = reason;
        closeByPeer = byPeer;
        queue.close();
        if (sendClose) {
            try {
                ByteOut b = new ByteOut(32).u16(code);
                if (reason != null) {
                    b.tlv(J2jWire.TLV_MESSAGE, reason.length() > 200 ? reason.substring(0, 200) : reason);
                }
                if (writeLock.tryLock(300, TimeUnit.MILLISECONDS)) {       // never wait on a stuck writer
                    try {
                        out.write(TcpFrames.seal(sendCipher, J2jWire.FRAME_CLOSE, 0, sendCounter++, b.toBytes()));
                        out.flush();
                    } finally {
                        writeLock.unlock();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException | GeneralSecurityException ignored) {
                // closing anyway
            }
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // closed
        }
        owner.onClosed(this, code, reason, byPeer);
    }

    private void writeLoop() {
        long nextPing = 0;
        try {
            while (!closed.get()) {
                long now = System.currentTimeMillis();
                if (now >= nextPing) {
                    long id = ++pingId & 0xFFFFFFFFL;
                    synchronized (pingNanos) {
                        pingIds[(int) (id & 7)] = id;
                        pingNanos[(int) (id & 7)] = System.nanoTime();
                    }
                    writeFrame(J2jWire.FRAME_PING, new ByteOut(12).u64(now).u32(id).toBytes());
                    nextPing = now + PING_MS;
                }
                if (pongDue != 0) {
                    long t = pongTime, id = pongId;
                    pongDue = 0;
                    writeFrame(J2jWire.FRAME_PONG, new ByteOut(20).u64(t).u32(id).u64(System.currentTimeMillis()).toBytes());
                }
                SendQueue.Item it = queue.poll(Math.max(1, Math.min(nextPing - System.currentTimeMillis(), 200)));
                if (it != null) {
                    writeFrame(J2jWire.FRAME_DATA, it.encoded);
                }
            }
        } catch (IOException | GeneralSecurityException e) {
            closeInternal(J2jWire.CLOSE_PROTOCOL, "send failed: " + e.getMessage(), false, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void writeFrame(int type, byte[] plain) throws IOException, GeneralSecurityException {
        writeLock.lock();
        try {
            if (closed.get()) {
                return;
            }
            byte[] f = TcpFrames.seal(sendCipher, type, 0, sendCounter++, plain);
            out.write(f);
            out.flush();
            framesOut.incrementAndGet();
            bytesOut.addAndGet(f.length);
        } finally {
            writeLock.unlock();
        }
    }

    private void readLoop() {
        try {
            while (!closed.get()) {
                TcpFrames.Frame f;
                try {
                    f = TcpFrames.readSealed(in, recvCipher);
                } catch (SocketTimeoutException e) {
                    closeInternal(J2jWire.CLOSE_TIMEOUT, "no frame for " + deadSeconds + " s", false, true);
                    return;
                }
                if (f.counter != recvCounter) {
                    closeInternal(J2jWire.CLOSE_PROTOCOL, "frame counter " + f.counter + ", expected " + recvCounter, false, true);
                    return;
                }
                recvCounter++;
                lastReceived = System.currentTimeMillis();
                framesIn.incrementAndGet();
                bytesIn.addAndGet(f.body.length + 30);
                switch (f.type) {
                    case J2jWire.FRAME_DATA:
                        Envelope env = Envelope.decode(f.body);
                        msgsIn[Math.min(2, env.cls)].incrementAndGet();
                        owner.onMessage(this, env);
                        break;
                    case J2jWire.FRAME_PING: {
                        ByteIn b = new ByteIn(f.body);
                        pongTime = b.u64();
                        pongId = b.u32();
                        pongDue = 1;
                        queue.wake();
                        break;
                    }
                    case J2jWire.FRAME_PONG: {
                        ByteIn b = new ByteIn(f.body);
                        long sent = b.u64();
                        long id = b.u32();
                        long peerNow = b.u64();
                        long now = System.currentTimeMillis();
                        double rtt = Math.max(0, now - sent);
                        synchronized (pingNanos) {
                            if (pingIds[(int) (id & 7)] == id && pingNanos[(int) (id & 7)] != 0) {
                                rtt = (System.nanoTime() - pingNanos[(int) (id & 7)]) / 1e6;     // the same PING, measured locally
                                pingNanos[(int) (id & 7)] = 0;
                            }
                        }
                        srtt = srtt < 0 ? rtt : srtt * 0.875 + rtt * 0.125;
                        clockOffset = peerNow - (sent + Math.round(rtt / 2));
                        synchronized (rttHistory) {
                            rttHistory[rttIndex] = rtt;
                            rttIndex = (rttIndex + 1) % rttHistory.length;
                            rttCount = Math.min(rttCount + 1, rttHistory.length);
                        }
                        break;
                    }
                    case J2jWire.FRAME_CLOSE: {
                        ByteIn b = new ByteIn(f.body);
                        int code = b.u16();
                        String msg = ByteIn.utf8(b.tlvs(J2jWire.TLV_MESSAGE).get(J2jWire.TLV_MESSAGE));
                        closeInternal(code, J2jWire.closeName(code) + (msg == null ? "" : ": " + msg), true, false);
                        return;
                    }
                    default:
                        if ((f.flags & J2jWire.FRAME_FLAG_MUST_UNDERSTAND) != 0) {
                            closeInternal(J2jWire.CLOSE_PROTOCOL, "unknown frame type " + f.type + " marked must-understand", false, true);
                            return;
                        }
                        unknownIn.incrementAndGet();          // a newer peer: skipped (design §6.3)
                }
            }
        } catch (GeneralSecurityException e) {
            closeInternal(J2jWire.CLOSE_PROTOCOL, "a frame did not authenticate", false, true);
        } catch (WireException e) {
            closeInternal(J2jWire.CLOSE_PROTOCOL, "malformed frame: " + e.getMessage(), false, true);
        } catch (IOException e) {
            closeInternal(J2jWire.CLOSE_TIMEOUT, "connection lost: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), false, false);
        } catch (RuntimeException e) {
            closeInternal(J2jWire.CLOSE_PROTOCOL, "internal error: " + e, false, true);
        }
    }

    // ---- state ------------------------------------------------------------------------------

    public String peerId() {
        return hs.peerId;
    }

    public long peerBoot() {
        return hs.peerBoot;
    }

    public String peerSoftware() {
        return hs.peerSoftware;
    }

    public String peerMemberHash() {
        return hs.peerMemberHash;
    }

    public Set<String> features() {
        return hs.features;
    }

    public int version() {
        return hs.version;
    }

    public String keyId() {
        return hs.keyId;
    }

    public boolean dialedBySelf() {
        return dialedBySelf;
    }

    /** @return the peer's address as seen on the socket */
    public String address() {
        return address;
    }

    public boolean isOpen() {
        return !closed.get();
    }

    public long upSince() {
        return upSince;
    }

    public long lastReceived() {
        return lastReceived;
    }

    /** @return smoothed RTT in ms, or -1 before the first PONG */
    /** The first RTT estimate, from the handshake; the PINGs refine it (not kept in the history). */
    void seedRtt(double ms) {
        if (srtt < 0 && ms >= 0) {
            srtt = ms;
        }
    }

    public double rttMs() {
        return srtt;
    }

    public long clockOffsetMs() {
        return clockOffset;
    }

    /** @return the last RTT samples, oldest first (up to 60, one per second) */
    public double[] rttHistory() {
        synchronized (rttHistory) {
            double[] r = new double[rttCount];
            for (int i = 0; i < rttCount; i++) {
                r[i] = rttHistory[(rttIndex - rttCount + i + rttHistory.length) % rttHistory.length];
            }
            return r;
        }
    }

    public int closeCode() {
        return closeCode;
    }

    public String closeReason() {
        return closeReason;
    }

    public boolean closedByPeer() {
        return closeByPeer;
    }

    /** @return {admin, reliable, best effort} queued */
    public int[] queued() {
        return queue.sizes();
    }

    public long[] counters() {
        synchronized (queue) {
            return new long[] {framesOut.get(), framesIn.get(), bytesOut.get(), bytesIn.get(),
                msgsOut[0].get(), msgsOut[1].get(), msgsOut[2].get(), msgsIn[0].get(), msgsIn[1].get(), msgsIn[2].get(),
                queue.dropped, queue.superseded, queue.expired, unknownIn.get(), udpOut.get(), udpIn.get()};
        }
    }
}
