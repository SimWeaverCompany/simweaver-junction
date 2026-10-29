package com.simweaver.j2j.admin;

import com.simweaver.j2j.config.J2jConfig;
import com.simweaver.j2j.crypto.J2jCrypto;
import com.simweaver.j2j.route.Router;
import com.simweaver.j2j.wire.ByteIn;
import com.simweaver.j2j.wire.ByteOut;
import com.simweaver.j2j.wire.Envelope;
import com.simweaver.j2j.wire.J2jWire;
import com.simweaver.j2j.wire.WireException;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The admin plane (design §8): every server tells every reachable member its status
 * ({@code NODE_STATUS}, every 10 s and when a member becomes reachable). Read only: nothing here
 * controls another server.
 *
 * <pre>
 * NODE_STATUS (0x0010), class admin, unsequenced; payload = TLVs:
 *   1 NAME (UTF-8)      2 SOFTWARE (UTF-8)    3 STARTED (u64, UTC ms)  4 CPU (u16, per mille; 0xFFFF unknown)
 *   5 HEAP_USED (u64)   6 HEAP_MAX (u64)      7 GATEWAYS (u16)         8 MEMBER_HASH (4 octets)
 *   9 SESSIONS (u16)    10 NOW (u64, UTC ms when sent)
 * </pre>
 */
public final class AdminPlane {

    public static final int NODE_STATUS = 0x0010;

    static final int T_NAME = 1;
    static final int T_SOFTWARE = 2;
    static final int T_STARTED = 3;
    static final int T_CPU = 4;
    static final int T_HEAP_USED = 5;
    static final int T_HEAP_MAX = 6;
    static final int T_GATEWAYS = 7;
    static final int T_MEMBER_HASH = 8;
    static final int T_SESSIONS = 9;
    static final int T_NOW = 10;

    /** What the host tells about this server; any value may be unknown (negative). */
    public interface StatusSource {
        double cpuPercent();

        long heapUsed();

        long heapMax();

        int gateways();

        int sessions();
    }

    /** A member's last status. */
    public static final class NodeStatus {
        public final String name;
        public final String software;
        public final long started;
        public final double cpuPercent;
        public final long heapUsed;
        public final long heapMax;
        public final int gateways;
        public final int sessions;
        public final String memberHash;
        public final long sentAt;
        public final long receivedAt;

        NodeStatus(String name, String software, long started, double cpuPercent, long heapUsed, long heapMax, int gateways,
                   int sessions, String memberHash, long sentAt, long receivedAt) {
            this.name = name;
            this.software = software;
            this.started = started;
            this.cpuPercent = cpuPercent;
            this.heapUsed = heapUsed;
            this.heapMax = heapMax;
            this.gateways = gateways;
            this.sessions = sessions;
            this.memberHash = memberHash;
            this.sentAt = sentAt;
            this.receivedAt = receivedAt;
        }
    }

    private final J2jConfig cfg;
    private final Router router;
    private final String software;
    private final long started;
    private volatile StatusSource source;
    private final Map<String, NodeStatus> statuses = new ConcurrentHashMap<String, NodeStatus>();
    private final ScheduledExecutorService timer;

    public AdminPlane(J2jConfig cfg, Router router, String software, long started) {
        this.cfg = cfg;
        this.router = router;
        this.software = software;
        this.started = started;
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "j2j-admin");
            t.setDaemon(true);
            return t;
        });
        router.register(0x0010, 0x00FF, this::receive);
        router.addReachabilityListener((id, route) -> {
            if (route != null) {
                try {
                    timer.execute(() -> sendTo(id));
                } catch (java.util.concurrent.RejectedExecutionException ignored) {
                    // stopping
                }
            }
        });
    }

    public void setStatusSource(StatusSource s) {
        this.source = s;
    }

    public void start() {
        timer.scheduleWithFixedDelay(this::broadcast, 1, 10, TimeUnit.SECONDS);
    }

    public void stop() {
        timer.shutdownNow();
    }

    private void broadcast() {
        try {
            for (String id : router.routes().keySet()) {
                sendTo(id);
            }
        } catch (RuntimeException ignored) {
            // the next round
        }
    }

    private void sendTo(String id) {
        router.sendUnsequenced(id, J2jWire.CLASS_ADMIN, NODE_STATUS, encodeOwn());
    }

    byte[] encodeOwn() {
        StatusSource s = source;
        double cpu = s == null ? -1 : s.cpuPercent();
        ByteOut o = new ByteOut(160);
        o.tlv(T_NAME, cfg.self().name());
        o.tlv(T_SOFTWARE, software);
        o.tlvU64(T_STARTED, started);
        o.tlv(T_CPU, new ByteOut(2).u16(cpu < 0 ? 0xFFFF : (int) Math.min(1000, Math.round(cpu * 10))).toBytes());
        o.tlvU64(T_HEAP_USED, s == null ? -1 : s.heapUsed());
        o.tlvU64(T_HEAP_MAX, s == null ? -1 : s.heapMax());
        o.tlv(T_GATEWAYS, new ByteOut(2).u16(s == null ? 0 : Math.max(0, Math.min(0xFFFF, s.gateways()))).toBytes());
        o.tlv(T_MEMBER_HASH, J2jCrypto.unhex(cfg.memberHash()));
        o.tlv(T_SESSIONS, new ByteOut(2).u16(s == null ? 0 : Math.max(0, Math.min(0xFFFF, s.sessions()))).toBytes());
        o.tlvU64(T_NOW, System.currentTimeMillis());
        return o.toBytes();
    }

    private void receive(String src, Envelope env) {
        if (env.type != NODE_STATUS) {
            return;
        }
        try {
            Map<Integer, byte[]> t = new ByteIn(env.payload).tlvs(T_NAME, T_SOFTWARE, T_STARTED, T_CPU, T_HEAP_USED, T_HEAP_MAX,
                    T_GATEWAYS, T_MEMBER_HASH, T_SESSIONS, T_NOW);
            byte[] cpu = t.get(T_CPU);
            int c = cpu == null || cpu.length < 2 ? 0xFFFF : ((cpu[0] & 0xFF) << 8) | (cpu[1] & 0xFF);
            statuses.put(src, new NodeStatus(ByteIn.utf8(t.get(T_NAME)), ByteIn.utf8(t.get(T_SOFTWARE)), u64(t.get(T_STARTED)),
                    c == 0xFFFF ? -1 : c / 10.0, u64(t.get(T_HEAP_USED)), u64(t.get(T_HEAP_MAX)), u16(t.get(T_GATEWAYS)),
                    u16(t.get(T_SESSIONS)), t.get(T_MEMBER_HASH) == null ? null : J2jCrypto.hex(t.get(T_MEMBER_HASH)),
                    u64(t.get(T_NOW)), System.currentTimeMillis()));
        } catch (WireException ignored) {
            // malformed: ignored
        }
    }

    private static long u64(byte[] b) {
        try {
            return b == null ? -1 : ByteIn.u64(b);
        } catch (WireException e) {
            return -1;
        }
    }

    private static int u16(byte[] b) {
        return b == null || b.length < 2 ? 0 : ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
    }

    /** @return the last status of each member that sent one */
    public Map<String, NodeStatus> statuses() {
        return Collections.unmodifiableMap(statuses);
    }

    public NodeStatus status(String id) {
        return statuses.get(id);
    }
}
