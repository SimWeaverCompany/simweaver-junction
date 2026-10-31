package com.simweaver.j2j.wire;

/**
 * A J2J message: the plaintext of a DATA frame (protocol version 1).
 *
 * <pre>
 * u8  class        0 admin, 1 reliable, 2 best effort
 * u8  flags        bit 0 must understand, bit 2 destination is a session; others reserved (0)
 * u8  ttl          hops left; a router drops the message at 0
 * u8  reserved     0
 * u64 source       node key of the sender
 * u64 destination  node key, or session key with flag bit 2
 * u32 stream       end-to-end stream (0 = none)
 * u64 sequence     number in the stream (reliable and admin; 0 = none)
 * u16 type         message type
 * u16 ext length   octets of extension TLVs that follow
 * ext              TLVs (u16 tag, u16 length, value); unknown non-critical tags are skipped
 * payload          the rest of the frame
 * </pre>
 *
 * Fields are only ever appended to the extension block (design §6.3).
 */
public final class Envelope {

    /** Octets before the extension block. */
    public static final int FIXED = 1 + 1 + 1 + 1 + 8 + 8 + 4 + 8 + 2 + 2;

    public final int cls;
    public final int flags;
    public final int ttl;
    public final long source;
    public final long destination;
    public final long stream;
    public final long sequence;
    public final int type;
    public final byte[] ext;
    public final byte[] payload;

    public Envelope(int cls, int flags, int ttl, long source, long destination, long stream, long sequence,
                    int type, byte[] ext, byte[] payload) {
        this.cls = cls;
        this.flags = flags;
        this.ttl = ttl;
        this.source = source;
        this.destination = destination;
        this.stream = stream;
        this.sequence = sequence;
        this.type = type;
        this.ext = ext == null ? new byte[0] : ext;
        this.payload = payload == null ? new byte[0] : payload;
    }

    public boolean mustUnderstand() {
        return (flags & J2jWire.MSG_FLAG_MUST_UNDERSTAND) != 0;
    }

    /** @return the same message with another ttl (forwarding) */
    public Envelope withTtl(int newTtl) {
        return new Envelope(cls, flags, newTtl, source, destination, stream, sequence, type, ext, payload);
    }

    public byte[] encode() {
        ByteOut o = new ByteOut(FIXED + ext.length + payload.length);
        o.u8(cls).u8(flags).u8(ttl).u8(0).u64(source).u64(destination).u32(stream).u64(sequence)
                .u16(type).u16(ext.length).bytes(ext).bytes(payload);
        return o.toBytes();
    }

    public static Envelope decode(byte[] b) throws WireException {
        ByteIn in = new ByteIn(b);
        int cls = in.u8();
        int flags = in.u8();
        int ttl = in.u8();
        in.u8();
        long src = in.u64();
        long dst = in.u64();
        long stream = in.u32();
        long seq = in.u64();
        int type = in.u16();
        int extLen = in.u16();
        byte[] ext = in.bytes(extLen);
        return new Envelope(cls, flags, ttl, src, dst, stream, seq, type, ext, in.rest());
    }

    public int size() {
        return FIXED + ext.length + payload.length;
    }
}
