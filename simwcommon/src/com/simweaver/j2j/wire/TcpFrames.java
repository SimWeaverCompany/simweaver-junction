package com.simweaver.j2j.wire;

import com.simweaver.j2j.crypto.FrameCipher;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.GeneralSecurityException;

/**
 * Frames on a TCP channel (protocol version 1).
 *
 * <pre>
 * plain frame (handshake):   u32 length | u8 type | body                 length = 1 + body
 * encrypted frame:           u32 length | u8 type | u8 flags | u64 counter | ciphertext | tag(16)
 *                            length = 1 + 1 + 8 + ciphertext + 16
 *                            AAD = the first 14 octets (length, type, flags, counter)
 * </pre>
 */
public final class TcpFrames {

    private TcpFrames() {
    }

    /** A frame as read: its type and body (plain), or type, flags, counter and plaintext. */
    public static final class Frame {
        public final int type;
        public final int flags;
        public final long counter;
        public final byte[] body;

        Frame(int type, int flags, long counter, byte[] body) {
            this.type = type;
            this.flags = flags;
            this.counter = counter;
            this.body = body;
        }
    }

    public static void writePlain(OutputStream out, int type, byte[] body) throws IOException {
        ByteOut o = new ByteOut(5 + body.length);
        o.u32(1 + body.length).u8(type).bytes(body);
        out.write(o.toBytes());
        out.flush();
    }

    /** @throws WireException when the length is out of range */
    public static Frame readPlain(DataInputStream in) throws IOException, WireException {
        long len = in.readInt() & 0xFFFFFFFFL;
        if (len < 1 || len > J2jWire.MAX_FRAME) {
            throw new WireException("frame length " + len + " out of range");
        }
        int type = in.readUnsignedByte();
        byte[] body = new byte[(int) len - 1];
        in.readFully(body);
        return new Frame(type, 0, 0, body);
    }

    /** The 14 octets that start an encrypted frame (also its AAD). */
    public static byte[] header(int type, int flags, long counter, int plainLength) {
        ByteOut o = new ByteOut(14);
        o.u32(1 + 1 + 8 + plainLength + FrameCipher.TAG_BYTES).u8(type).u8(flags).u64(counter);
        return o.toBytes();
    }

    /** @return the whole encrypted frame, ready to write */
    public static byte[] seal(FrameCipher c, int type, int flags, long counter, byte[] plain) throws GeneralSecurityException {
        byte[] h = header(type, flags, counter, plain.length);
        byte[] ct = c.seal(counter, h, plain);
        byte[] f = new byte[h.length + ct.length];
        System.arraycopy(h, 0, f, 0, h.length);
        System.arraycopy(ct, 0, f, h.length, ct.length);
        return f;
    }

    /**
     * Reads and decrypts one encrypted frame.
     *
     * @throws GeneralSecurityException when it does not authenticate (altered, wrong key, wrong counter)
     */
    public static Frame readSealed(DataInputStream in, FrameCipher c) throws IOException, WireException, GeneralSecurityException {
        long len = in.readInt() & 0xFFFFFFFFL;
        if (len < 1 + 1 + 8 + FrameCipher.TAG_BYTES || len > J2jWire.MAX_FRAME) {
            throw new WireException("frame length " + len + " out of range");
        }
        byte[] rest = new byte[(int) len];
        in.readFully(rest);
        int type = rest[0] & 0xFF;
        int flags = rest[1] & 0xFF;
        long counter = 0;
        for (int i = 2; i < 10; i++) {
            counter = (counter << 8) | (rest[i] & 0xFF);
        }
        byte[] aad = new byte[14];
        aad[0] = (byte) (len >>> 24);
        aad[1] = (byte) (len >>> 16);
        aad[2] = (byte) (len >>> 8);
        aad[3] = (byte) len;
        System.arraycopy(rest, 0, aad, 4, 10);
        byte[] plain = c.open(counter, aad, rest, 10, rest.length - 10);
        return new Frame(type, flags, counter, plain);
    }
}
