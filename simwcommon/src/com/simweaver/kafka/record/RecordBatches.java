package com.simweaver.kafka.record;

import com.simweaver.kafka.wire.KReader;
import com.simweaver.kafka.wire.KWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.CRC32C;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Kafka record batches, magic 2 (the only format Kafka 4 has): writing one batch for a Produce
 * request and reading the batches of a Fetch response.
 *
 * <pre>
 * baseOffset int64, batchLength int32, partitionLeaderEpoch int32, magic int8 (2),
 * crc uint32 (CRC-32C of everything after it), attributes int16, lastOffsetDelta int32,
 * baseTimestamp int64, maxTimestamp int64, producerId int64, producerEpoch int16,
 * baseSequence int32, count int32, records (compressed as a whole when attributes say so)
 * record: length varint, attributes int8, timestampDelta varlong, offsetDelta varint,
 *         key varbytes, value varbytes, header count varint, headers (key varbytes, value varbytes)
 * </pre>
 *
 * Reading takes no, gzip, snappy and lz4 compression; zstd batches are skipped and counted.
 * Control batches (transaction markers) are skipped; message sets of magic 0 and 1 are skipped and
 * counted.
 */
public final class RecordBatches {

    private RecordBatches() {
    }

    public static final int NONE = 0;
    public static final int GZIP = 1;
    public static final int SNAPPY = 2;
    public static final int LZ4 = 3;
    public static final int ZSTD = 4;

    /** Batch header length up to and including the record count. */
    static final int HEADER = 61;

    /** @return the compression code for a setting value (none, gzip), or -1 */
    public static int compressionFromSetting(String s) {
        if (s == null) {
            return NONE;
        }
        switch (s.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "":
            case "none":
                return NONE;
            case "gzip":
                return GZIP;
            default:
                return -1;
        }
    }

    /** @return the name of a compression code */
    public static String compressionName(int c) {
        switch (c) {
            case NONE: return "none";
            case GZIP: return "gzip";
            case SNAPPY: return "snappy";
            case LZ4: return "lz4";
            case ZSTD: return "zstd";
            default: return "compression " + c;
        }
    }

    // ------------------------------------------------------------------ writing

    /**
     * Writes one batch (base offset 0: the broker assigns offsets; no producer id: not idempotent).
     *
     * @param records     at least one record
     * @param compression {@link #NONE} or {@link #GZIP}
     * @return the batch
     */
    public static byte[] encode(List<KafkaRecord> records, int compression) {
        if (records.isEmpty()) {
            throw new IllegalArgumentException("a batch needs records");
        }
        long base = records.get(0).timestamp;
        long max = base;
        for (KafkaRecord r : records) {
            base = Math.min(base, r.timestamp);
            max = Math.max(max, r.timestamp);
        }
        KWriter body = new KWriter(64 + records.size() * 64);
        for (int i = 0; i < records.size(); i++) {
            writeRecord(body, records.get(i), base, i);
        }
        byte[] recs = body.toBytes();
        if (compression == GZIP) {
            recs = gzip(recs);
        } else if (compression != NONE) {
            throw new IllegalArgumentException("cannot write " + compressionName(compression));
        }
        KWriter w = new KWriter(HEADER + recs.length);
        w.int64(0);                                         // base offset
        int lengthAt = w.position();
        w.int32(0);                                         // batch length, patched
        w.int32(-1);                                        // partition leader epoch
        w.int8(2);                                          // magic
        int crcAt = w.position();
        w.int32(0);                                         // crc, patched
        w.int16(compression & 7);                           // attributes: compression, create time, not transactional
        w.int32(records.size() - 1);                        // last offset delta
        w.int64(base);
        w.int64(max);
        w.int64(-1);                                        // producer id
        w.int16(-1);                                        // producer epoch
        w.int32(-1);                                        // base sequence
        w.int32(records.size());
        w.raw(recs);
        byte[] out = w.toBytes();
        w.patchInt32(lengthAt, out.length - 12);
        CRC32C crc = new CRC32C();
        crc.update(w.buffer(), crcAt + 4, out.length - crcAt - 4);
        w.patchInt32(crcAt, (int) crc.getValue());
        return w.toBytes();
    }

    private static void writeRecord(KWriter out, KafkaRecord r, long baseTimestamp, int offsetDelta) {
        KWriter w = new KWriter(r.estimatedSize());
        w.int8(0);                                          // attributes
        w.varlong(r.timestamp - baseTimestamp);
        w.varint(offsetDelta);
        w.varbytes(r.key);
        w.varbytes(r.value);
        w.varint(r.headers.size());
        for (KafkaRecord.Header h : r.headers) {
            w.varbytes(h.key.getBytes(StandardCharsets.UTF_8));
            w.varbytes(h.value);
        }
        out.varint(w.position());
        out.raw(w.buffer(), 0, w.position());
    }

    static byte[] gzip(byte[] b) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(b.length / 2 + 64);
            try (GZIPOutputStream z = new GZIPOutputStream(bo)) {
                z.write(b);
            }
            return bo.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ reading

    /** What reading a partition's batches found besides the records. */
    public static final class ReadResult {
        /** the offset after the last record or batch read (next fetch offset), or -1 when nothing complete was read */
        public long nextOffset = -1;
        public int records;
        public int batches;
        /** batches left out: zstd or unknown compression */
        public int unsupportedCompression;
        /** message sets of magic 0 / 1 left out */
        public int oldFormat;
        /** batches whose CRC did not match */
        public int corrupt;
        /** the first problem, for a log line */
        public String problem;
    }

    /**
     * Reads the batches of one partition of a Fetch response. A batch cut off at the end (the
     * broker sends whole batches up to its size limit, and may cut the last) ends the reading.
     *
     * @param data      the partition's RecordBatches octets (may be null)
     * @param topic     for the records
     * @param partition for the records
     * @param from      records before this offset are left out (a batch may start before the fetch offset)
     * @param sink      receives each record
     */
    public static ReadResult read(byte[] data, String topic, int partition, long from, Consumer<KafkaRecord> sink) {
        ReadResult res = new ReadResult();
        if (data == null) {
            return res;
        }
        int p = 0;
        while (data.length - p >= 17) {
            KReader h = new KReader(data, p, data.length - p);
            long baseOffset = h.int64();
            int length = h.int32();
            if (length < 0 || length > data.length - p - 12) {
                break;                                      // partial batch at the end
            }
            int batchEnd = p + 12 + length;
            h.int32();                                      // partition leader epoch / old: crc
            int magic = h.int8();
            if (magic < 2) {
                res.oldFormat++;
                if (res.problem == null) {
                    res.problem = "message format v" + magic + " (Kafka before 0.11) is not read";
                }
                res.nextOffset = baseOffset + 1;            // an old message set entry is one (wrapper) message
                p = batchEnd;
                continue;
            }
            if (length < HEADER - 12) {
                res.corrupt++;
                break;
            }
            int crc = h.int32();
            CRC32C c = new CRC32C();
            c.update(data, p + 21, batchEnd - (p + 21));
            int attributes = h.int16();
            int lastOffsetDelta = h.int32();
            long baseTimestamp = h.int64();
            h.int64();                                      // max timestamp
            h.int64();                                      // producer id
            h.int16();                                      // producer epoch
            h.int32();                                      // base sequence
            int count = h.int32();
            res.batches++;
            long batchNext = baseOffset + lastOffsetDelta + 1;
            if ((int) c.getValue() != crc) {
                res.corrupt++;
                if (res.problem == null) {
                    res.problem = "a record batch at offset " + baseOffset + " failed its CRC";
                }
                res.nextOffset = batchNext;
                p = batchEnd;
                continue;
            }
            boolean control = (attributes & 0x20) != 0;
            int compression = attributes & 7;
            if (control || count <= 0) {
                res.nextOffset = batchNext;
                p = batchEnd;
                continue;
            }
            byte[] recs;
            int recOff;
            int recLen;
            int start = p + HEADER;
            try {
                if (compression == NONE) {
                    recs = data;
                    recOff = start;
                    recLen = batchEnd - start;
                } else {
                    byte[] packed = java.util.Arrays.copyOfRange(data, start, batchEnd);
                    recs = decompress(compression, packed);
                    recOff = 0;
                    recLen = recs.length;
                }
            } catch (UnsupportedOperationException | IOException e) {
                if (compression == ZSTD || e instanceof UnsupportedOperationException) {
                    res.unsupportedCompression++;
                } else {
                    res.corrupt++;
                }
                if (res.problem == null) {
                    res.problem = e.getMessage();
                }
                res.nextOffset = batchNext;
                p = batchEnd;
                continue;
            }
            KReader r = new KReader(recs, recOff, recLen);
            try {
                for (int i = 0; i < count; i++) {
                    int len = r.varint();
                    int recEnd = r.position() + len;
                    r.int8();                               // attributes (unused)
                    long ts = baseTimestamp + r.varlong();
                    long offset = baseOffset + r.varint();
                    byte[] key = r.varbytes();
                    byte[] value = r.varbytes();
                    int nh = r.varint();
                    List<KafkaRecord.Header> headers = new ArrayList<KafkaRecord.Header>(Math.max(0, Math.min(nh, 64)));
                    for (int k = 0; k < nh; k++) {
                        byte[] hk = r.varbytes();
                        byte[] hv = r.varbytes();
                        headers.add(new KafkaRecord.Header(hk == null ? "" : new String(hk, StandardCharsets.UTF_8), hv));
                    }
                    if (r.position() != recEnd) {
                        r.skip(recEnd - r.position());     // newer fields we do not know
                    }
                    if (offset >= from) {
                        res.records++;
                        sink.accept(new KafkaRecord(topic, partition, offset, ts, key, value, headers));
                    }
                }
            } catch (KReader.KafkaProtocolException e) {
                res.corrupt++;
                if (res.problem == null) {
                    res.problem = "a record batch at offset " + baseOffset + " is malformed: " + e.getMessage();
                }
            }
            res.nextOffset = batchNext;
            p = batchEnd;
        }
        return res;
    }

    /** @throws UnsupportedOperationException for zstd and unknown codes */
    static byte[] decompress(int compression, byte[] packed) throws IOException {
        switch (compression) {
            case GZIP:
                try (GZIPInputStream z = new GZIPInputStream(new ByteArrayInputStream(packed))) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream(packed.length * 4);
                    byte[] buf = new byte[8192];
                    int n;
                    long total = 0;
                    while ((n = z.read(buf)) > 0) {
                        total += n;
                        if (total > Snappy.MAX_OUTPUT) {
                            throw new IOException("gzip: decompressed batch too large");
                        }
                        bo.write(buf, 0, n);
                    }
                    return bo.toByteArray();
                }
            case SNAPPY:
                return Snappy.decompress(packed);
            case LZ4:
                return Lz4.decompress(packed);
            case ZSTD:
                throw new UnsupportedOperationException("zstd compressed batches are not read (use none, gzip, snappy or lz4 on topics Junction reads)");
            default:
                throw new UnsupportedOperationException("unknown compression " + compression);
        }
    }
}
