package com.simweaver.kafka.record;

import java.io.IOException;

/**
 * LZ4 frame decompression (the LZ4 frame format, which Kafka uses for record batches of magic 2),
 * for batches other producers compressed with lz4. Independent and linked blocks, uncompressed
 * blocks, optional content size, block and content checksums (skipped: the batch CRC covers the
 * compressed octets). No compression: this client sends gzip or nothing.
 */
public final class Lz4 {

    private Lz4() {
    }

    private static final int MAGIC = 0x184D2204;
    private static final int SKIPPABLE_MASK = 0xFFFFFFF0;
    private static final int SKIPPABLE = 0x184D2A50;

    public static byte[] decompress(byte[] in) throws IOException {
        Snappy.Output out = new Snappy.Output(in.length * 4);
        int p = 0;
        while (p < in.length) {
            int magic = le32(in, p);
            if ((magic & SKIPPABLE_MASK) == SKIPPABLE) {
                int n = le32(in, p + 4);
                p += 8 + n;
                continue;
            }
            if (magic != MAGIC) {
                throw new IOException("lz4: not an LZ4 frame (magic " + Integer.toHexString(magic) + ")");
            }
            p += 4;
            need(in, p, 2);
            int flg = in[p] & 0xFF;
            if ((flg >>> 6) != 1) {
                throw new IOException("lz4: frame version " + (flg >>> 6));
            }
            boolean blockChecksum = (flg & 0x10) != 0;
            boolean contentSize = (flg & 0x08) != 0;
            boolean contentChecksum = (flg & 0x04) != 0;
            boolean dictId = (flg & 0x01) != 0;
            p += 2;                                             // FLG, BD
            if (contentSize) {
                p += 8;
            }
            if (dictId) {
                throw new IOException("lz4: frames with a dictionary are not supported");
            }
            p += 1;                                             // header checksum
            while (true) {
                need(in, p, 4);
                int size = le32(in, p);
                p += 4;
                if (size == 0) {
                    break;                                      // end mark
                }
                boolean stored = (size & 0x80000000) != 0;
                size &= 0x7FFFFFFF;
                need(in, p, size);
                if (stored) {
                    out.write(in, p, size);
                } else {
                    block(in, p, size, out);
                }
                p += size;
                if (blockChecksum) {
                    p += 4;
                }
            }
            if (contentChecksum) {
                p += 4;
            }
        }
        return out.toBytes();
    }

    /** One compressed block; matches may reach into earlier blocks' output (linked blocks). */
    static void block(byte[] in, int off, int len, Snappy.Output out) throws IOException {
        int p = off;
        int end = off + len;
        while (p < end) {
            int token = in[p++] & 0xFF;
            int lit = token >>> 4;
            if (lit == 15) {
                int b;
                do {
                    if (p >= end) {
                        throw new IOException("lz4: truncated literal length");
                    }
                    b = in[p++] & 0xFF;
                    lit += b;
                } while (b == 255);
            }
            if (lit > end - p) {
                throw new IOException("lz4: literal past the block");
            }
            out.write(in, p, lit);
            p += lit;
            if (p >= end) {
                break;                                          // the last sequence has literals only
            }
            if (end - p < 2) {
                throw new IOException("lz4: truncated offset");
            }
            int offset = (in[p] & 0xFF) | ((in[p + 1] & 0xFF) << 8);
            p += 2;
            int match = token & 15;
            if (match == 15) {
                int b;
                do {
                    if (p >= end) {
                        throw new IOException("lz4: truncated match length");
                    }
                    b = in[p++] & 0xFF;
                    match += b;
                } while (b == 255);
            }
            match += 4;
            int from = out.size() - offset;
            if (offset == 0 || from < 0) {
                throw new IOException("lz4: bad match offset " + offset);
            }
            out.ensure(match);
            for (int i = 0; i < match; i++) {                   // may overlap: byte by byte
                out.write(out.get(from + i));
            }
        }
    }

    private static void need(byte[] in, int p, int n) throws IOException {
        if (n < 0 || p < 0 || p + n > in.length) {
            throw new IOException("lz4: truncated frame");
        }
    }

    private static int le32(byte[] b, int p) throws IOException {
        need(b, p, 4);
        return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8) | ((b[p + 2] & 0xFF) << 16) | ((b[p + 3] & 0xFF) << 24);
    }
}
