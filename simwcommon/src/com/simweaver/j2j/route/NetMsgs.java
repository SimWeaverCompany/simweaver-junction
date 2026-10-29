package com.simweaver.j2j.route;

import com.simweaver.j2j.wire.ByteIn;
import com.simweaver.j2j.wire.ByteOut;
import com.simweaver.j2j.wire.WireException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The network messages (types 0x0001-0x00FF) of protocol version 1: link state, end-to-end
 * acknowledgements, stream resets and echo. Layouts in doc/j2j-wire-protocol.md §7. Every list
 * entry is length prefixed, so entries can grow without breaking older readers.
 */
public final class NetMsgs {

    public static final int LSA = 0x0001;
    public static final int ACK = 0x0002;
    public static final int STREAM_RESET = 0x0003;
    public static final int ECHO_REQUEST = 0x0004;
    public static final int ECHO_REPLY = 0x0005;

    /** Stream of the network's own admin traffic between two servers. */
    public static final int STREAM_ADMIN = 1;

    private NetMsgs() {
    }

    static void str8(ByteOut o, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length > 255) {
            throw new IllegalArgumentException("string longer than 255 octets");
        }
        o.u8(b.length).bytes(b);
    }

    static String str8(ByteIn in) throws WireException {
        return new String(in.bytes(in.u8()), StandardCharsets.UTF_8);
    }

    // ---- LSA ------------------------------------------------------------------------------

    /** A link-state advertisement: who an origin is linked to, and at what cost. */
    public static final class Lsa {
        public final long sequence;
        public final long boot;
        public final int lifetimeSeconds;
        public final String origin;
        /** neighbour id -> {cost, rtt in microseconds} */
        public final Map<String, long[]> neighbours;
        long receivedAt;

        public Lsa(long sequence, long boot, int lifetimeSeconds, String origin, Map<String, long[]> neighbours) {
            this.sequence = sequence;
            this.boot = boot;
            this.lifetimeSeconds = lifetimeSeconds;
            this.origin = origin;
            this.neighbours = Collections.unmodifiableMap(new LinkedHashMap<String, long[]>(neighbours));
        }

        public byte[] encode() {
            ByteOut o = new ByteOut(64 + neighbours.size() * 24);
            o.u64(sequence).u64(boot).u16(lifetimeSeconds);
            str8(o, origin);
            o.u16(neighbours.size());
            for (Map.Entry<String, long[]> e : neighbours.entrySet()) {
                ByteOut en = new ByteOut(24);
                str8(en, e.getKey());
                en.u32(e.getValue()[0]).u32(e.getValue()[1]);
                byte[] eb = en.toBytes();
                o.u16(eb.length).bytes(eb);
            }
            return o.toBytes();
        }

        public static Lsa decode(byte[] b) throws WireException {
            ByteIn in = new ByteIn(b);
            long seq = in.u64();
            long boot = in.u64();
            int life = in.u16();
            String origin = str8(in);
            int n = in.u16();
            Map<String, long[]> nb = new LinkedHashMap<String, long[]>();
            for (int i = 0; i < n; i++) {
                ByteIn en = new ByteIn(in.bytes(in.u16()));
                String id = str8(en);
                long cost = en.u32();
                long rtt = en.u32();
                nb.put(id, new long[] {cost, rtt});            // octets after these are a newer version's: skipped
            }
            return new Lsa(seq, boot, life, origin, nb);
        }
    }

    // ---- ACK ------------------------------------------------------------------------------

    /** u16 n | n × (u16 entry length | u32 stream | u64 cumulative sequence) */
    public static byte[] ack(Map<Long, Long> streamToCumulative) {
        ByteOut o = new ByteOut(2 + streamToCumulative.size() * 14);
        o.u16(streamToCumulative.size());
        for (Map.Entry<Long, Long> e : streamToCumulative.entrySet()) {
            o.u16(12).u32(e.getKey()).u64(e.getValue());
        }
        return o.toBytes();
    }

    public static List<long[]> ackEntries(byte[] b) throws WireException {
        ByteIn in = new ByteIn(b);
        int n = in.u16();
        List<long[]> r = new ArrayList<long[]>(n);
        for (int i = 0; i < n; i++) {
            ByteIn e = new ByteIn(in.bytes(in.u16()));
            r.add(new long[] {e.u32(), e.u64()});
        }
        return r;
    }

    // ---- STREAM_RESET -----------------------------------------------------------------------

    /** u32 stream | u64 the sequence that could not be placed */
    public static byte[] streamReset(long stream, long sequence) {
        return new ByteOut(12).u32(stream).u64(sequence).toBytes();
    }

    // ---- ECHO -------------------------------------------------------------------------------

    /** request: u32 id | u64 send time; reply: the same | u64 reply time */
    public static byte[] echoRequest(long id, long sentAt) {
        return new ByteOut(12).u32(id).u64(sentAt).toBytes();
    }

    public static byte[] echoReply(byte[] request, long now) {
        return new ByteOut(request.length + 8).bytes(request, 0, 12).u64(now).toBytes();
    }

    // ---- sequence numbers ---------------------------------------------------------------------

    /** sequence = epoch (24 bits, not 0) << 40 | counter (40 bits, from 1) */
    public static long sequence(int epoch, long counter) {
        return ((long) (epoch & 0xFFFFFF) << 40) | (counter & 0xFFFFFFFFFFL);
    }

    public static int epoch(long sequence) {
        return (int) (sequence >>> 40) & 0xFFFFFF;
    }

    public static long counter(long sequence) {
        return sequence & 0xFFFFFFFFFFL;
    }
}
