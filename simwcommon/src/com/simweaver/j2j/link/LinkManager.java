package com.simweaver.j2j.link;

import com.simweaver.common.logging.Events;
import com.simweaver.common.rest.RequestContext;
import com.simweaver.j2j.config.J2jConfig;
import com.simweaver.j2j.config.J2jMember;
import com.simweaver.j2j.crypto.J2jCrypto;
import com.simweaver.j2j.wire.Envelope;
import com.simweaver.j2j.wire.J2jWire;
import com.simweaver.j2j.wire.TcpFrames;
import com.simweaver.j2j.wire.WireException;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The links of this server to its neighbours (design §3): listens on {@code j2j.port}, dials every
 * dialable member with backoff and jitter, runs the handshakes, keeps one link per peer (the one
 * dialled by the smaller id when both dial; a restarted peer's new link replaces the old), and
 * reports links up and down and refused handshakes (rate limited).
 */
public final class LinkManager implements Link.Owner {

    private static final Logger LOG = Logger.getLogger("com.simweaver.j2j");
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int HANDSHAKE_TIMEOUT_MS = 5000;
    private static final int MAX_PENDING_HANDSHAKES = 16;
    private static final long REFUSED_WINDOW_MS = 60_000;

    /** What upper layers (routing, sessions) see. */
    public interface Listener {
        void linkUp(Link link);

        void linkDown(Link link, String reason);

        void message(Link link, Envelope env);
    }

    /** The per-member dialling state. */
    static final class Peer {
        final J2jMember member;
        volatile Link link;
        volatile boolean dialing;
        volatile boolean rekeying;
        volatile long peerRekeyedBoot;           // != 0: the peer closed its link to re-key; its new one is due
        volatile long lastPrimaryTry;
        volatile long nextAttempt;
        long backoffMs = 1000;
        int failures;
        volatile boolean everUp;
        volatile String lastError;
        volatile long lastErrorAt;
        volatile String refused;                 // last refusal, by either side, until a link comes up
        volatile boolean refusedByPeer;
        volatile long lastSeen;
        volatile String peerSoftware;
        volatile String peerMemberHash;
        final Map<String, String[]> attempts = new LinkedHashMap<String, String[]>();   // address -> {kind, at, result, ok}

        Peer(J2jMember m) {
            this.member = m;
        }
    }

    private final J2jConfig cfg;
    private volatile Handshake.Local local;
    private final long boot;
    private final String software;
    private final Set<String> features;
    private volatile java.net.DatagramSocket udpSocket;
    private final Map<Long, Link> udpById = new ConcurrentHashMap<Long, Link>();
    private final Map<String, Peer> peers = new LinkedHashMap<String, Peer>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    private final Map<String, long[]> refusedLog = new ConcurrentHashMap<String, long[]>();   // key -> {windowStart, suppressed}
    private final Semaphore pending = new Semaphore(MAX_PENDING_HANDSHAKES);
    private final ScheduledExecutorService timer;
    private final ExecutorService pool;
    private final long reliableMaxBytes;
    private final int bestEffortMax;
    private final long bestEffortMaxAgeMs;
    private volatile ServerSocket server;
    private volatile ServerSocket fallbackServer;
    private volatile String fallbackError;
    private volatile boolean stopped;
    private volatile String listenError;
    private volatile java.util.function.Predicate<String> linkFilter = id -> true;   // tests: which peers may link

    /**
     * @param software e.g. "SimWeaver 0.10.0"
     * @param features the optional features this server speaks
     */
    public LinkManager(J2jConfig cfg, long boot, String software, Set<String> features) {
        this.cfg = cfg;
        this.boot = boot;
        this.software = software;
        this.features = features;
        this.local = new Handshake.Local(cfg, boot, software, features);
        for (J2jMember m : cfg.members().values()) {
            if (!m.id().equals(cfg.selfId())) {
                peers.put(m.id(), new Peer(m));
            }
        }
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "j2j-timer"));
        this.pool = Executors.newCachedThreadPool(r -> daemon(r, "j2j-connect"));
        this.reliableMaxBytes = 16L * 1024 * 1024;
        this.bestEffortMax = 20000;
        this.bestEffortMaxAgeMs = 1000;
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    /** Starts listening and dialling. A port that cannot be bound is reported, and dialling still runs. */
    public void start() {
        if (cfg.udpEnabled()) {
            try {
                InetAddress bindAddr = cfg.bind().isEmpty() ? null : InetAddress.getByName(cfg.bind());
                java.net.DatagramSocket ds = new java.net.DatagramSocket(null);
                ds.setReuseAddress(true);
                ds.bind(new InetSocketAddress(bindAddr, cfg.port()));
                udpSocket = ds;
                Set<String> f = new java.util.LinkedHashSet<String>(features);
                f.add("udp");
                local = new Handshake.Local(cfg, boot, software, f);
                Thread t = new Thread(this::udpLoop, "j2j-udp");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                Events.warning(LOG, "j2j.udp.failed", "J2J: no UDP on port " + cfg.port() + " (" + e.getMessage()
                        + "); best effort goes over TCP", "port", cfg.port(), "by", "server");
            }
        }
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            InetAddress bindAddr = cfg.bind().isEmpty() ? null : InetAddress.getByName(cfg.bind());
            ss.bind(new InetSocketAddress(bindAddr, cfg.port()), 50);
            server = ss;
            Thread t = new Thread(() -> acceptLoop(ss), "j2j-accept");
            t.setDaemon(true);
            t.start();
        } catch (IOException e) {
            listenError = "cannot listen on TCP port " + cfg.port() + ": " + e.getMessage();
            Events.log(LOG, Level.SEVERE, "j2j.listen.failed", "J2J " + listenError, "port", cfg.port(), "by", "server");
        }
        if (cfg.fallbackPort() > 0) {
            try {
                ServerSocket fs = FallbackTls.listen(cfg.bind(), cfg.fallbackPort(), cfg.fallbackKeystore(), cfg.fallbackKeystorePassword());
                fallbackServer = fs;
                Thread t = new Thread(() -> acceptLoop(fs), "j2j-accept-tls");
                t.setDaemon(true);
                t.start();
            } catch (IOException | java.security.GeneralSecurityException e) {
                fallbackError = "no TLS fallback listener on port " + cfg.fallbackPort() + ": " + e.getMessage();
                Events.warning(LOG, "j2j.fallback.failed", "J2J: " + fallbackError + " (other servers cannot use it as their last resort; "
                        + "set j2j.fallback.port=0 to turn it off)", "port", cfg.fallbackPort(), "by", "server");
            }
        }
        timer.scheduleWithFixedDelay(this::tick, 50, 250, TimeUnit.MILLISECONDS);
    }

    /** @return why the TLS fallback listener does not run, or null */
    public String fallbackError() {
        return fallbackError;
    }

    /** @return the TLS fallback port listened on, or -1 */
    public int fallbackPort() {
        ServerSocket s = fallbackServer;
        return s == null ? -1 : s.getLocalPort();
    }

    /** Closes every link (CLOSE SHUTDOWN) and stops. */
    public void stop() {
        stopped = true;
        timer.shutdownNow();
        for (ServerSocket ss : new ServerSocket[] {server, fallbackServer}) {
            try {
                if (ss != null) {
                    ss.close();
                }
            } catch (IOException ignored) {
                // stopping
            }
        }
        for (Peer p : peersSnapshot()) {
            Link l = p.link;
            if (l != null) {
                l.close(J2jWire.CLOSE_SHUTDOWN, "server stopping");
            }
        }
        if (udpSocket != null) {
            udpSocket.close();
        }
        pool.shutdownNow();
    }

    public String listenError() {
        return listenError;
    }

    /** @return the port actually listened on, or -1 */
    public int localPort() {
        ServerSocket s = server;
        return s == null ? -1 : s.getLocalPort();
    }

    // ---- dialling ---------------------------------------------------------------------------

    private void tick() {
        long now = System.currentTimeMillis();
        for (Peer p : peersSnapshot()) {
            Link l = p.link;
            UdpPath u = l == null ? null : l.udpPath();
            if (u != null && l.isOpen() && u.remote != null && now - u.lastPing >= 1000) {
                u.lastPing = now;
                u.pingsSent++;
                u.send(udpSocket, J2jWire.FRAME_PING, new com.simweaver.j2j.wire.ByteOut(12).u64(now).u32(u.pingsSent).toBytes());
            }
            if (l != null && l.isOpen() && l.dialedBySelf() && "tls443".equals(l.kind()) && !p.rekeying && !p.dialing && !stopped
                    && !p.member.addresses().isEmpty() && now - p.lastPrimaryTry > cfg.primaryRetryMillis()) {
                p.lastPrimaryTry = now;
                p.rekeying = true;
                try {
                    pool.execute(() -> backToPrimary(p));
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    p.rekeying = false;
                }
            }
            if (l != null && l.isOpen() && l.dialedBySelf() && !p.rekeying && !p.dialing && !stopped
                    && now - l.upSince() > cfg.rekeyMillis()) {
                p.rekeying = true;                            // fresh keys: a new channel replaces this one (CLOSE REKEY)
                try {
                    pool.execute(() -> rekey(p));
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    p.rekeying = false;
                }
            }
            if (stopped || p.link != null || p.dialing || !p.member.dialable() || now < p.nextAttempt) {
                continue;
            }
            p.dialing = true;
            try {
                pool.execute(() -> dial(p));
            } catch (java.util.concurrent.RejectedExecutionException e) {
                p.dialing = false;
            }
        }
    }

    private void rekey(Peer p) {
        try {
            Link cur = p.link;
            if (cur != null && "tls443".equals(cur.kind()) && p.member.fallback() != null) {
                J2jMember.Address fb = p.member.fallback();
                Socket s = null;
                try {
                    s = FallbackTls.connect(fb.host, fb.port, cfg.fallbackProxy());
                    s.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
                    DataInputStream in = input(s);
                    install(s, in, initiate(s, in, p.member.id()), true);
                } catch (HandshakeException | IOException e) {
                    close(s);
                }
                return;
            }
            for (J2jMember.Address a : p.member.addresses()) {
                if (stopped || p.link == null) {
                    return;
                }
                Socket s = new Socket();
                try {
                    s.connect(new InetSocketAddress(a.host, a.port), CONNECT_TIMEOUT_MS);
                    s.setTcpNoDelay(true);
                    s.setKeepAlive(true);
                    s.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
                    DataInputStream in = input(s);
                    install(s, in, initiate(s, in, p.member.id()), true);
                    return;
                } catch (HandshakeException | IOException e) {
                    close(s);
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "J2J rekey of " + p.member.id() + " failed", e);
        } finally {
            p.rekeying = false;
        }
    }

    private void dial(Peer p) {
        try {
            for (J2jMember.Address a : p.member.addresses()) {
                if (stopped || p.link != null) {
                    return;
                }
                if (!linkFilter.test(p.member.id())) {
                    attempt(p, a.toString(), "tcp", "blocked (test)", false);
                    continue;
                }
                Socket s = new Socket();
                String key = a.toString();
                try {
                    s.connect(new InetSocketAddress(a.host, a.port), CONNECT_TIMEOUT_MS);
                    s.setTcpNoDelay(true);
                    s.setKeepAlive(true);
                    s.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
                    DataInputStream in = input(s);
                    Handshake.Result r = initiate(s, in, p.member.id());
                    attempt(p, key, "tcp", "ok", true);
                    install(s, in, r, true);
                    return;
                } catch (HandshakeException e) {
                    close(s);
                    attempt(p, key, "tcp", (e.byPeer() ? "refused by it: " : "refused: ") + e.getMessage(), false);
                    refused(p, e, s, key);
                    break;                                   // the same answer on its other addresses
                } catch (IOException e) {
                    close(s);
                    String why = e instanceof java.net.SocketTimeoutException ? "timeout" : e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    attempt(p, key, "tcp", why, false);
                    p.lastError = key + ": " + why;
                    p.lastErrorAt = System.currentTimeMillis();
                }
            }
            J2jMember.Address fb = p.member.fallback();
            if (fb != null && !stopped && p.link == null && p.refused == null && linkFilter.test(p.member.id())) {
                String key = fb.toString();
                Socket s = null;
                try {
                    s = FallbackTls.connect(fb.host, fb.port, cfg.fallbackProxy());
                    s.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
                    DataInputStream in = input(s);
                    Handshake.Result r = initiate(s, in, p.member.id());
                    attempt(p, key, "tls443", "ok" + (cfg.fallbackProxy().isEmpty() ? "" : " via proxy " + cfg.fallbackProxy()), true);
                    install(s, in, r, true);
                    p.lastPrimaryTry = System.currentTimeMillis();
                    Events.notice(LOG, "j2j.channel.fallback", "J2J: " + p.member.id() + " reached on the last resort, TLS " + key
                            + (cfg.fallbackProxy().isEmpty() ? "" : " through " + cfg.fallbackProxy()), "member", p.member.id(),
                            "address", key, "proxy", cfg.fallbackProxy().isEmpty() ? null : cfg.fallbackProxy(), "by", "j2j");
                    return;
                } catch (HandshakeException e) {
                    close(s);
                    attempt(p, key, "tls443", (e.byPeer() ? "refused by it: " : "refused: ") + e.getMessage(), false);
                    refused(p, e, s, key);
                } catch (IOException e) {
                    close(s);
                    String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    attempt(p, key, "tls443", why, false);
                    p.lastError = key + ": " + why;
                    p.lastErrorAt = System.currentTimeMillis();
                }
            }
            failed(p);
        } finally {
            p.dialing = false;
        }
    }

    /** On the last resort: try the primary addresses again; a working one replaces the TLS link (same dialler, newest wins). */
    private void backToPrimary(Peer p) {
        try {
            for (J2jMember.Address a : p.member.addresses()) {
                Link l = p.link;
                if (stopped || l == null || !"tls443".equals(l.kind())) {
                    return;
                }
                Socket s = new Socket();
                try {
                    s.connect(new InetSocketAddress(a.host, a.port), CONNECT_TIMEOUT_MS);
                    s.setTcpNoDelay(true);
                    s.setKeepAlive(true);
                    s.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
                    DataInputStream in = input(s);
                    install(s, in, initiate(s, in, p.member.id()), true);
                    attempt(p, a.toString(), "tcp", "ok", true);
                    Events.notice(LOG, "j2j.channel.primary", "J2J: " + p.member.id() + " reached on " + a + " again; the TLS last resort is closed",
                            "member", p.member.id(), "address", a.toString(), "by", "j2j");
                    return;
                } catch (HandshakeException | IOException e) {
                    close(s);
                }
            }
        } finally {
            p.rekeying = false;
        }
    }

    /**
     * The handshake's round trip on this thread (ms), for the new link's first RTT: a link's cost is
     * known at once, before its first PONG (a link without one would look expensive to the router).
     */
    private static final ThreadLocal<Double> HANDSHAKE_RTT = new ThreadLocal<Double>();

    /** @param in the socket's input, kept for the link: its buffer may already hold the first encrypted frames */
    private Handshake.Result initiate(Socket s, DataInputStream in, String peerId) throws IOException, HandshakeException {
        OutputStream out = s.getOutputStream();
        Handshake.Initiator hs = new Handshake.Initiator(local, peerId, null);
        byte[] hello = hs.hello();
        long t0 = System.nanoTime();
        TcpFrames.writePlain(out, J2jWire.FRAME_HELLO, hello);
        TcpFrames.Frame f = read(in);
        HANDSHAKE_RTT.set((System.nanoTime() - t0) / 1e6);
        if (f.type == J2jWire.FRAME_REFUSE) {
            throw Handshake.refused(f.body, peerId);
        }
        if (f.type != J2jWire.FRAME_WELCOME) {
            throw new HandshakeException(J2jWire.REFUSE_PROTOCOL, "expected WELCOME, got frame type " + f.type, false, peerId);
        }
        byte[] finish;
        try {
            finish = hs.welcome(f.body);
        } catch (HandshakeException e) {
            TcpFrames.writePlain(out, J2jWire.FRAME_REFUSE, Handshake.refuse(e.code(), null));
            throw e;
        }
        TcpFrames.writePlain(out, J2jWire.FRAME_FINISH, finish);
        return hs.result();
    }

    private static TcpFrames.Frame read(DataInputStream in) throws IOException, HandshakeException {
        try {
            return TcpFrames.readPlain(in);
        } catch (WireException e) {
            throw new HandshakeException(J2jWire.REFUSE_PROTOCOL, e.getMessage(), false, null);
        }
    }

    private void failed(Peer p) {
        p.failures++;
        long cap = p.everUp || p.failures < 10 ? 30_000 : 300_000;
        long delay = Math.min(cap, p.backoffMs);
        p.backoffMs = Math.min(cap, p.backoffMs * 2);
        double jitter = 0.8 + 0.4 * J2jCrypto.RANDOM.nextDouble();
        p.nextAttempt = System.currentTimeMillis() + Math.round(delay * jitter);
    }

    private void attempt(Peer p, String address, String kind, String result, boolean ok) {
        synchronized (p.attempts) {
            p.attempts.put(address, new String[] {kind, String.valueOf(System.currentTimeMillis()), result, String.valueOf(ok)});
        }
    }

    // ---- accepting --------------------------------------------------------------------------

    private void acceptLoop(ServerSocket listener) {
        while (!stopped) {
            Socket s;
            try {
                s = listener.accept();
            } catch (IOException e) {
                if (stopped) {
                    return;
                }
                continue;
            }
            if (!pending.tryAcquire()) {
                try {
                    TcpFrames.writePlain(s.getOutputStream(), J2jWire.FRAME_REFUSE, Handshake.refuse(J2jWire.REFUSE_BUSY, "too many handshakes"));
                } catch (IOException ignored) {
                    // refusing anyway
                }
                close(s);
                continue;
            }
            try {
                pool.execute(() -> {
                    try {
                        respond(s);
                    } finally {
                        pending.release();
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                pending.release();
                close(s);
            }
        }
    }

    private void respond(Socket s) {
        String from = s.getInetAddress().getHostAddress() + ":" + s.getPort();
        Handshake.Responder hs = new Handshake.Responder(local, null);
        try {
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            s.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            DataInputStream in = input(s);
            OutputStream out = s.getOutputStream();
            TcpFrames.Frame f = read(in);
            if (f.type != J2jWire.FRAME_HELLO) {
                throw new HandshakeException(J2jWire.REFUSE_PROTOCOL, "expected HELLO, got frame type " + f.type, false, null);
            }
            byte[] welcome;
            try {
                welcome = hs.hello(f.body);
            } catch (HandshakeException e) {
                TcpFrames.writePlain(out, J2jWire.FRAME_REFUSE, Handshake.refuse(e.code(),
                        e.code() == J2jWire.REFUSE_AUTH || e.code() == J2jWire.REFUSE_PROTOCOL ? null : e.getMessage()));
                throw e;
            }
            long t0 = System.nanoTime();
            TcpFrames.writePlain(out, J2jWire.FRAME_WELCOME, welcome);
            f = read(in);
            HANDSHAKE_RTT.set((System.nanoTime() - t0) / 1e6);
            if (f.type == J2jWire.FRAME_REFUSE) {
                throw Handshake.refused(f.body, hs.claimedId());
            }
            if (f.type != J2jWire.FRAME_FINISH) {
                throw new HandshakeException(J2jWire.REFUSE_PROTOCOL, "expected FINISH, got frame type " + f.type, false, hs.claimedId());
            }
            Handshake.Result r;
            try {
                r = hs.finish(f.body);
            } catch (HandshakeException e) {
                TcpFrames.writePlain(out, J2jWire.FRAME_REFUSE, Handshake.refuse(e.code(), null));
                throw e;
            }
            Peer p = peers.get(r.peerId);
            if (p == null || !linkFilter.test(r.peerId)) {
                close(s);
                return;
            }
            install(s, in, r, false);
        } catch (HandshakeException e) {
            close(s);
            Peer p = e.claimedId() == null ? null : peers.get(e.claimedId());
            if (p != null) {
                refused(p, e, s, from);
            } else {
                logRefused(e, from, e.claimedId());
            }
        } catch (IOException e) {
            close(s);                                       // a port scan or a dropped connection: not logged
        }
    }

    // ---- links ------------------------------------------------------------------------------

    private static DataInputStream input(Socket s) throws IOException {
        return new DataInputStream(new BufferedInputStream(s.getInputStream(), 65536));
    }

    private void install(Socket s, DataInputStream in, Handshake.Result r, boolean dialedBySelf) throws IOException {
        Peer p = peers.get(r.peerId);
        Link link = Link.over(this, s, in, r, dialedBySelf, cfg.deadSeconds(), reliableMaxBytes, bestEffortMax, bestEffortMaxAgeMs);
        Double hsRtt = HANDSHAKE_RTT.get();
        HANDSHAKE_RTT.remove();
        if (hsRtt != null) {
            link.seedRtt(hsRtt);
        }
        Link loser = null;
        int loserCode = 0;
        boolean installed;
        boolean swap = false;                                  // replaces a duplicate of the same peer run: no new "up"
        Link current = null;
        synchronized (p) {
            Link old = p.link;
            String dialerNew = dialedBySelf ? cfg.selfId() : r.peerId;
            if (old == null || !old.isOpen()) {
                p.link = link;
                installed = true;
                swap = p.peerRekeyedBoot != 0 && p.peerRekeyedBoot == r.peerBoot;   // the peer's re-keyed link: not news
            } else {
                String dialerOld = old.dialedBySelf() ? cfg.selfId() : r.peerId;
                if (old.peerBoot() != r.peerBoot) {
                    loserCode = J2jWire.CLOSE_REPLACED;       // the peer restarted: its new link wins
                } else if (dialerNew.equals(dialerOld)) {
                    loserCode = J2jWire.CLOSE_REKEY;          // the same dialler again: the newest wins (rekey, or a half-dead old one)
                } else if (dialerNew.compareTo(dialerOld) < 0) {
                    loserCode = J2jWire.CLOSE_DUPLICATE;      // both dialled: the smaller id's link wins
                } else {
                    loserCode = J2jWire.CLOSE_DUPLICATE;
                    loser = link;
                }
                if (loser == null) {
                    p.link = link;
                    loser = old;
                    current = old;
                    swap = loserCode != J2jWire.CLOSE_REPLACED;
                    installed = true;
                } else {
                    installed = false;
                }
            }
            if (installed) {
                p.peerRekeyedBoot = 0;
                p.everUp = true;
                p.failures = 0;
                p.backoffMs = 1000;
                p.refused = null;
                p.lastError = null;
                p.peerSoftware = r.peerSoftware;
                p.peerMemberHash = r.peerMemberHash;
                p.lastSeen = System.currentTimeMillis();
            }
        }
        if (installed) {
            if (current != null) {
                current.handOver(link);                       // what waits on the old link goes on the new one
                UdpPath ou = current.udpPath();
                if (ou != null) {
                    udpById.remove(ou.recvId, current);
                }
            }
            if (udpSocket != null && r.features.contains("udp")) {
                InetSocketAddress remote = dialedBySelf && "tcp".equals(kindOf(s))
                        ? new InetSocketAddress(s.getInetAddress(), s.getPort()) : null;   // the accepting side learns it
                UdpPath up = new UdpPath(r, remote);
                link.attachUdp(up, udpSocket);
                udpById.put(up.recvId, link);
            }
            link.kind(kindOf(s));
            link.start(r.peerId);
        }
        if (loser != null) {
            loser.close(loserCode, loserCode == J2jWire.CLOSE_DUPLICATE ? "both sides dialled; keeping the link dialled by the smaller id"
                    : loserCode == J2jWire.CLOSE_REKEY ? "replaced by a link with fresh keys" : "replaced by a new link");
        }
        if (installed && !swap) {
            RequestContext.runAs("j2j", () -> Events.notice(LOG, "j2j.link.up", "J2J link to " + r.peerId + " up ("
                    + (dialedBySelf ? "dialled " : "accepted from ") + link.address() + ("tls443".equals(kindOf(s)) ? ", TLS last resort" : "") + ")", "member", r.peerId,
                    "address", link.address(), "direction", dialedBySelf ? "out" : "in", "channel", kindOf(s), "software", r.peerSoftware));
            if (r.peerMemberHash != null && !r.peerMemberHash.equals(cfg.memberHash())) {
                Events.warning(LOG, "j2j.members.mismatch", "J2J member " + r.peerId + " has another member list ("
                        + r.peerMemberHash + ", ours " + cfg.memberHash() + ")", "member", r.peerId, "theirs", r.peerMemberHash,
                        "ours", cfg.memberHash(), "by", "j2j");
            }
        }
        if (installed) {
            for (Listener l : listeners) {
                l.linkUp(link);                                // also on a swap: listeners key by peer id
            }
        }
    }

    @Override
    public void onMessage(Link link, Envelope env) {
        Peer p = peers.get(link.peerId());
        if (p != null) {
            p.lastSeen = System.currentTimeMillis();
        }
        for (Listener l : listeners) {
            l.message(link, env);
        }
    }

    private static String kindOf(Socket s) {
        return s instanceof javax.net.ssl.SSLSocket ? "tls443" : "tcp";
    }

    private void udpLoop() {
        byte[] buf = new byte[UdpPath.MAX_DATAGRAM + 64];
        java.net.DatagramSocket ds = udpSocket;
        while (!stopped && ds != null && !ds.isClosed()) {
            java.net.DatagramPacket pk = new java.net.DatagramPacket(buf, buf.length);
            try {
                ds.receive(pk);
            } catch (IOException e) {
                continue;
            }
            int len = pk.getLength();
            if (len < UdpPath.HEADER + 16) {
                continue;
            }
            long id = ((long) (buf[0] & 0xFF) << 24) | ((buf[1] & 0xFF) << 16) | ((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF);
            Link link = udpById.get(id);
            UdpPath u = link == null ? null : link.udpPath();
            if (u == null) {
                continue;
            }
            Object[] r = u.open(buf, len, (InetSocketAddress) pk.getSocketAddress());
            if (r == null) {
                continue;
            }
            int type = (Integer) r[0];
            byte[] plain = (byte[]) r[1];
            try {
                if (type == J2jWire.FRAME_DATA) {
                    link.udpData(Envelope.decode(plain));
                } else if (type == J2jWire.FRAME_PING && plain.length >= 12) {
                    u.send(ds, J2jWire.FRAME_PONG, new com.simweaver.j2j.wire.ByteOut(20).bytes(plain, 0, 12)
                            .u64(System.currentTimeMillis()).toBytes());
                } else if (type == J2jWire.FRAME_PONG && plain.length >= 12) {
                    u.pong(new com.simweaver.j2j.wire.ByteIn(plain).u64());
                }
            } catch (WireException | RuntimeException ignored) {
                // a malformed datagram: dropped
            }
        }
    }

    @Override
    public void onClosed(Link link, int code, String reason, boolean byPeer) {
        UdpPath lu = link.udpPath();
        if (lu != null) {
            udpById.remove(lu.recvId, link);
        }
        Peer p = peers.get(link.peerId());
        if (p == null) {
            return;
        }
        boolean wasCurrent;
        synchronized (p) {
            wasCurrent = p.link == link;
            if (wasCurrent) {
                p.link = null;
                long up = System.currentTimeMillis() - link.upSince();
                p.nextAttempt = System.currentTimeMillis() + (up > 10_000 ? Math.round(200 + 600 * J2jCrypto.RANDOM.nextDouble()) : 1000);
                if (up > 10_000) {
                    p.backoffMs = 1000;
                }
            }
        }
        if (!wasCurrent) {
            return;                                           // a duplicate or a replaced link: quiet
        }
        String text = (byPeer ? "closed by " + link.peerId() + ": " : "") + reason;
        if (byPeer && code == J2jWire.CLOSE_REKEY && !stopped) {
            // the peer re-keyed: its new link may be installed here a moment after this close
            // (its handshake runs on another thread); only if it does not come is the link down
            p.peerRekeyedBoot = link.peerBoot();
            try {
                timer.schedule(() -> {
                    if (p.link == null && !stopped) {
                        p.peerRekeyedBoot = 0;
                        down(p, link, code, text, byPeer);
                    }
                }, REKEY_GRACE_MS, TimeUnit.MILLISECONDS);
                return;
            } catch (java.util.concurrent.RejectedExecutionException stopping) {
                // stopping: at once
            }
        }
        down(p, link, code, text, byPeer);
    }

    private static final long REKEY_GRACE_MS = 3000;

    private void down(Peer p, Link link, int code, String text, boolean byPeer) {
        p.lastError = text;
        p.lastErrorAt = System.currentTimeMillis();
        if (code == J2jWire.CLOSE_DUPLICATE) {
            // both sides dialled and the peer kept the other link, which arrives a moment later: not news
            LOG.fine("J2J link to " + link.peerId() + " replaced by its duplicate");
        } else if (!stopped) {
            Events.warning(LOG, "j2j.link.down", "J2J link to " + link.peerId() + " down: " + text, "member", link.peerId(),
                    "reason", J2jWire.closeName(code), "uptimeseconds", (System.currentTimeMillis() - link.upSince()) / 1000, "by", byPeer ? link.peerId() : "j2j");
        }
        for (Listener l : listeners) {
            l.linkDown(link, text);
        }
    }

    // ---- refusals ---------------------------------------------------------------------------

    private void refused(Peer p, HandshakeException e, Socket s, String address) {
        p.refused = (e.byPeer() ? "refused by " + p.member.id() + ": " : "") + e.getMessage();
        p.refusedByPeer = e.byPeer();
        p.lastError = p.refused;
        p.lastErrorAt = System.currentTimeMillis();
        logRefused(e, address, p.member.id());
    }

    /** One event per (address, reason, side) and minute; repeats are counted into the next. */
    private void logRefused(HandshakeException e, String address, String claimed) {
        String host = address.contains(":") ? address.substring(0, address.lastIndexOf(':')) : address;
        String key = host + "|" + e.code() + "|" + e.byPeer();
        long now = System.currentTimeMillis();
        long[] w = refusedLog.computeIfAbsent(key, k -> new long[] {0, 0});
        synchronized (w) {
            if (now - w[0] < REFUSED_WINDOW_MS) {
                w[1]++;
                return;
            }
            long suppressed = w[1];
            w[0] = now;
            w[1] = 0;
            String who = (claimed == null ? "?" : claimed) + " (" + address + ")";
            String text = e.byPeer() ? "J2J: " + who + " refused our handshake: " + e.getMessage()
                    : "J2J: refused " + who + ": " + e.getMessage();
            Events.warning(LOG, "j2j.handshake.refused", text + (suppressed > 0 ? " (" + (suppressed + 1) + " attempts since the last report)" : ""),
                    "from", claimed, "address", address, "reason", e.codeName(), "side", e.byPeer() ? "theirs" : "ours",
                    "count", suppressed + 1, "by", "j2j");
        }
    }

    // ---- status -------------------------------------------------------------------------------

    private List<Peer> peersSnapshot() {
        return new ArrayList<Peer>(peers.values());
    }

    /** @return the current link to a neighbour, or null */
    public Link link(String peerId) {
        Peer p = peers.get(peerId);
        Link l = p == null ? null : p.link;
        return l != null && l.isOpen() ? l : null;
    }

    /** @return every open link */
    public List<Link> links() {
        List<Link> r = new ArrayList<Link>();
        for (Peer p : peersSnapshot()) {
            Link l = p.link;
            if (l != null && l.isOpen()) {
                r.add(l);
            }
        }
        return r;
    }

    /** A snapshot of one peer's link state, for the status API. */
    public static final class PeerView {
        public final String id;
        public final boolean dialable;
        public final boolean dialing;
        public final long retryInMs;
        public final String refused;
        public final boolean refusedByPeer;
        public final String lastError;
        public final long lastErrorAt;
        public final long lastSeen;
        public final String peerSoftware;
        public final String peerMemberHash;
        public final Link link;
        public final List<String[]> attempts;

        PeerView(Peer p) {
            this.id = p.member.id();
            this.dialable = p.member.dialable();
            this.dialing = p.dialing;
            this.retryInMs = Math.max(0, p.nextAttempt - System.currentTimeMillis());
            this.refused = p.refused;
            this.refusedByPeer = p.refusedByPeer;
            this.lastError = p.lastError;
            this.lastErrorAt = p.lastErrorAt;
            this.lastSeen = p.lastSeen;
            this.peerSoftware = p.peerSoftware;
            this.peerMemberHash = p.peerMemberHash;
            Link l = p.link;
            this.link = l != null && l.isOpen() ? l : null;
            List<String[]> a = new ArrayList<String[]>();
            synchronized (p.attempts) {
                for (Map.Entry<String, String[]> e : p.attempts.entrySet()) {
                    String[] v = e.getValue();
                    a.add(new String[] {e.getKey(), v[0], v[1], v[2], v[3]});
                }
            }
            this.attempts = Collections.unmodifiableList(a);
        }
    }

    public List<PeerView> peerViews() {
        List<PeerView> r = new ArrayList<PeerView>();
        for (Peer p : peersSnapshot()) {
            r.add(new PeerView(p));
        }
        return r;
    }

    public PeerView peerView(String id) {
        Peer p = peers.get(id);
        return p == null ? null : new PeerView(p);
    }

    private static void close(Socket s) {
        if (s == null) {
            return;
        }
        try {
            s.close();
        } catch (IOException ignored) {
            // closing
        }
    }

    /** For tests: which peers may link with this server (dialled and accepted). Existing links to others are closed. */
    public void setLinkFilter(java.util.function.Predicate<String> allowed) {
        this.linkFilter = allowed;
        for (Peer p : peersSnapshot()) {
            Link l = p.link;
            if (l != null && !allowed.test(p.member.id())) {
                l.close(J2jWire.CLOSE_SHUTDOWN, "blocked (test)");
            }
        }
    }

    /** For tests: makes the next attempt to every peer happen now. */
    public void dialNow() {
        for (Peer p : peersSnapshot()) {
            p.nextAttempt = 0;
        }
    }
}
