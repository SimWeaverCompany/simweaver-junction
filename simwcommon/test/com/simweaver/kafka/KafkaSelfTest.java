package com.simweaver.kafka;

import com.simweaver.common.hla.codec.HValue;
import com.simweaver.common.hla.codec.HValueJson;
import com.simweaver.common.hla.codec.HlaCodec;
import com.simweaver.common.iem.DataType;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.common.json.Json;
import com.simweaver.common.rest.JsonParser;
import com.simweaver.kafka.client.KafkaConsumer;
import com.simweaver.kafka.client.KafkaProducer;
import com.simweaver.kafka.client.KafkaSettings;
import com.simweaver.kafka.client.Partitioner;
import com.simweaver.kafka.client.Scram;
import com.simweaver.kafka.record.KafkaRecord;
import com.simweaver.kafka.record.Lz4;
import com.simweaver.kafka.record.RecordBatches;
import com.simweaver.kafka.record.Snappy;
import com.simweaver.kafka.wire.KReader;
import com.simweaver.kafka.wire.KWriter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * The Kafka client (0.13.0): wire primitives, murmur2 partitions (the Java client's vectors), the
 * SCRAM-SHA-256 example of RFC 7677, record batches (CRC, gzip, cut-off batches), snappy and lz4
 * decoding of vectors written by another client's compressors, HLA values as JSON and back (every
 * datatype of the RPR FOM), and producer / consumer / consumer groups / SASL PLAIN against an
 * in-memory broker ({@link FakeKafkaBroker}), with the newest and the oldest protocol versions.
 *
 * <pre>java -cp out/classes:out/test com.simweaver.kafka.KafkaSelfTest [fom root]</pre>
 */
public final class KafkaSelfTest {

    private static int checks;
    private static int failures;

    private KafkaSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        File fomRoot = new File(args.length > 0 ? args[0] : "simwrift/rtdir/fom");
        wire();
        partitions();
        scram();
        batches();
        compression();
        jsonValues(new File(fomRoot, "ieee1516-2010/RPR_FOM_v2.0_Link16_1516-2010.xml"));
        settings();
        for (boolean oldest : new boolean[] {false, true}) {
            produceConsume(oldest);
        }
        groups();
        sasl();
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ primitives

    private static void wire() {
        KWriter w = new KWriter(4);
        int[] ints = {0, -1, 1, 63, -64, 64, 300, Integer.MAX_VALUE, Integer.MIN_VALUE};
        for (int i : ints) {
            w.varint(i);
        }
        long[] longs = {0, -1, 1L << 40, Long.MAX_VALUE, Long.MIN_VALUE};
        for (long l : longs) {
            w.varlong(l);
        }
        w.string("åäö").string(null).bytes(null).bytes(new byte[] {1, 2}).int16(-2).int64(-5);
        KReader r = new KReader(w.toBytes());
        boolean ok = true;
        for (int i : ints) {
            ok &= r.varint() == i;
        }
        for (long l : longs) {
            ok &= r.varlong() == l;
        }
        ok &= "åäö".equals(r.string()) && r.string() == null && r.bytes() == null && Arrays.equals(r.bytes(), new byte[] {1, 2})
                && r.int16() == -2 && r.int64() == -5 && r.remaining() == 0;
        check("primitives round trip (zig-zag varints, strings, bytes, nulls)", ok);
        check("zig-zag: 0 -> 00, -1 -> 01, 1 -> 02, 300 -> d8 04", Arrays.equals(new KWriter().varint(0).varint(-1).varint(1).varint(300).toBytes(),
                new byte[] {0, 1, 2, (byte) 0xd8, 4}));
        boolean thrown = false;
        try {
            new KReader(new byte[] {0, 5, 'a'}).string();
        } catch (KReader.KafkaProtocolException e) {
            thrown = true;
        }
        check("short input is a protocol exception, not an index error", thrown);
        thrown = false;
        try {
            new KReader(new byte[] {0x7f, 0, 0, 0}).count();
        } catch (KReader.KafkaProtocolException e) {
            thrown = true;
        }
        check("an array count larger than the input is refused", thrown);
    }

    /** The Java client's murmur2 vectors, and partitions another client chose for 1000 partitions. */
    private static void partitions() {
        Object[][] v = {{"21", -973932308}, {"foobar", -790332482}, {"a-little-bit-long-string", -985981536},
            {"a-little-bit-longer-string", -1486304829}, {"lkjh234lh9fiuh90y23oiuhsafujhadof229phr9h19h89h8", -58897971}, {"abc", 479470107}};
        boolean ok = true;
        for (Object[] c : v) {
            ok &= Partitioner.murmur2(((String) c[0]).getBytes(StandardCharsets.UTF_8)) == (Integer) c[1];
        }
        check("murmur2 = the Java client's", ok);
        Object[][] p = {{"21", 340}, {"foobar", 166}, {"abc", 107}, {"1:1:11", 646}, {"a-little-bit-longer-string", 819}};
        ok = true;
        for (Object[] c : p) {
            ok &= Partitioner.partition(((String) c[0]).getBytes(StandardCharsets.UTF_8), 1000) == (Integer) c[1];
        }
        check("keys land where another client puts them (1000 partitions)", ok);
    }

    /** RFC 7677 section 3. */
    private static void scram() throws Exception {
        Scram s = new Scram("SCRAM-SHA-256", "user", "pencil", "rOprNGfwEbeRWgbNEkqO");
        check("SCRAM client-first", "n,,n=user,r=rOprNGfwEbeRWgbNEkqO".equals(new String(s.first(), StandardCharsets.UTF_8)));
        String serverFirst = "r=rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF$k0,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096";
        String last = new String(s.last(serverFirst.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        check("SCRAM-SHA-256 client-final = RFC 7677: " + last,
                "c=biws,r=rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF$k0,p=dHzbZapWIk4jUhN+Ute9ytag9zjfMHgsqmmiz7AndVQ=".equals(last));
        boolean ok = true;
        try {
            s.verify("v=6rriTRBi23WpRR/wtup+mMhUZUn/dB5nLTJRsjl95G4=".getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            ok = false;
        }
        check("SCRAM server signature of RFC 7677 accepted", ok);
        ok = false;
        try {
            s.verify("v=AAAATRBi23WpRR/wtup+mMhUZUn/dB5nLTJRsjl95G4=".getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            ok = true;
        }
        check("a wrong server signature is refused", ok);
        ok = false;
        try {
            new Scram("SCRAM-SHA-256", "user", "pencil", "abc").last("r=other,s=AAAA,i=4096".getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            ok = true;
        }
        check("a server nonce not extending the client's is refused", ok);
    }

    // ------------------------------------------------------------------ record batches

    private static List<KafkaRecord> sample(int n) {
        List<KafkaRecord> l = new ArrayList<KafkaRecord>();
        long t = 1_790_000_000_000L;
        for (int i = 0; i < n; i++) {
            List<KafkaRecord.Header> h = i % 2 == 0 ? Collections.singletonList(new KafkaRecord.Header("h" + i, ("v" + i).getBytes(StandardCharsets.UTF_8)))
                    : Collections.<KafkaRecord.Header>emptyList();
            l.add(new KafkaRecord(i == 1 ? null : ("k" + i).getBytes(StandardCharsets.UTF_8), i == 2 ? null : ("value " + i).getBytes(StandardCharsets.UTF_8), h, t + i * 7L));
        }
        return l;
    }

    private static boolean same(List<KafkaRecord> a, List<KafkaRecord> b, long base) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            KafkaRecord x = a.get(i), y = b.get(i);
            if (!Arrays.equals(x.key, y.key) || !Arrays.equals(x.value, y.value) || x.timestamp != y.timestamp || y.offset != base + i
                    || x.headers.size() != y.headers.size()) {
                return false;
            }
            for (int k = 0; k < x.headers.size(); k++) {
                if (!x.headers.get(k).key.equals(y.headers.get(k).key) || !Arrays.equals(x.headers.get(k).value, y.headers.get(k).value)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void batches() {
        List<KafkaRecord> recs = sample(5);
        for (int c : new int[] {RecordBatches.NONE, RecordBatches.GZIP}) {
            byte[] b = RecordBatches.encode(recs, c);
            new KWriter(8).int64(100);
            System.arraycopy(new KWriter(8).int64(100).toBytes(), 0, b, 0, 8);     // the broker's base offset (not in the CRC)
            List<KafkaRecord> got = new ArrayList<KafkaRecord>();
            RecordBatches.ReadResult res = RecordBatches.read(b, "t", 0, 0, got::add);
            check(RecordBatches.compressionName(c) + ": a batch reads back (keys, null key / value, headers, timestamps, offsets)",
                    same(recs, got, 100) && res.nextOffset == 105 && res.corrupt == 0);
            got.clear();
            RecordBatches.read(b, "t", 0, 103, got::add);
            check(RecordBatches.compressionName(c) + ": records before the fetch offset are left out", got.size() == 2 && got.get(0).offset == 103);
        }
        byte[] b = RecordBatches.encode(recs, RecordBatches.NONE);
        byte[] two = new byte[b.length * 2 - 10];
        System.arraycopy(b, 0, two, 0, b.length);
        System.arraycopy(b, 0, two, b.length, b.length - 10);
        List<KafkaRecord> got = new ArrayList<KafkaRecord>();
        RecordBatches.ReadResult res = RecordBatches.read(two, "t", 0, 0, got::add);
        check("a batch cut off at the end is not read (the next fetch gets it)", got.size() == 5 && res.batches == 1 && res.nextOffset == 5);
        b[b.length - 1] ^= 1;
        got.clear();
        res = RecordBatches.read(b, "t", 0, 0, got::add);
        check("a changed octet fails the CRC: no records, counted, skipped", got.isEmpty() && res.corrupt == 1 && res.nextOffset == 5);
        byte[] z = RecordBatches.encode(recs, RecordBatches.NONE);
        z[22] = (byte) (z[22] | RecordBatches.ZSTD);           // attributes: zstd (the CRC is not right now either: fix it)
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(z, 21, z.length - 21);
        KWriter cw = new KWriter(4).int32((int) crc.getValue());
        System.arraycopy(cw.toBytes(), 0, z, 17, 4);
        got.clear();
        res = RecordBatches.read(z, "t", 0, 0, got::add);
        check("a zstd batch is skipped and counted as unsupported: " + res.problem, got.isEmpty() && res.unsupportedCompression == 1 && res.nextOffset == 5);
        check("compression settings: none, gzip; snappy is read only", RecordBatches.compressionFromSetting("GZIP") == RecordBatches.GZIP
                && RecordBatches.compressionFromSetting("") == RecordBatches.NONE && RecordBatches.compressionFromSetting("snappy") == -1);
    }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    /** Vectors from another Kafka client's compressors (s2 in snappy mode; the LZ4 frame writer). */
    private static void compression() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            text.append("SimWeaver Junction Kafka test. ");
        }
        byte[] payload = new byte[text.length() + 100];
        System.arraycopy(text.toString().getBytes(StandardCharsets.US_ASCII), 0, payload, 0, text.length());
        for (int i = 0; i < 100; i++) {
            payload[text.length() + i] = (byte) i;
        }
        String tail = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f60616263";
        byte[] snappy = hex("dc027853696d576561766572204a756e6374696f6e204b61666b6120746573742e20ee1f00ee1f00ee1f00921f00f063" + tail);
        byte[] lz4 = hex("04224d186470b98a000000ff1053696d576561766572204a756e6374696f6e204b61666b6120746573742e201f00c6f055" + tail + "000000003844a97c");
        check("snappy (raw block) decodes another client's vector", Arrays.equals(Snappy.decompress(snappy), payload));
        KWriter x = new KWriter();
        x.raw(new byte[] {(byte) 0x82, 'S', 'N', 'A', 'P', 'P', 'Y', 0}).int32(1).int32(1).int32(snappy.length).raw(snappy).int32(snappy.length).raw(snappy);
        byte[] twice = new byte[payload.length * 2];
        System.arraycopy(payload, 0, twice, 0, payload.length);
        System.arraycopy(payload, 0, twice, payload.length, payload.length);
        check("snappy in xerial framing (the Java client's), two blocks", Arrays.equals(Snappy.decompress(x.toBytes()), twice));
        check("lz4 frame decodes another client's vector", Arrays.equals(Lz4.decompress(lz4), payload));
        boolean thrown = false;
        try {
            Snappy.decompress(Arrays.copyOf(snappy, 20));
        } catch (java.io.IOException e) {
            thrown = true;
        }
        check("a truncated snappy block is an error", thrown);
        // a batch whose records are snappy compressed (built here: records as written, compressed by the vector's encoder)
        List<KafkaRecord> recs = sample(3);
        byte[] plain = RecordBatches.encode(recs, RecordBatches.NONE);
        byte[] recBytes = Arrays.copyOfRange(plain, 61, plain.length);
        byte[] lit = snappyLiteral(recBytes);
        KWriter bw = new KWriter();
        bw.raw(plain, 0, 61).raw(lit);
        byte[] batch = bw.toBytes();
        batch[22] = (byte) RecordBatches.SNAPPY;
        System.arraycopy(new KWriter(4).int32(batch.length - 12).toBytes(), 0, batch, 8, 4);
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(batch, 21, batch.length - 21);
        System.arraycopy(new KWriter(4).int32((int) crc.getValue()).toBytes(), 0, batch, 17, 4);
        List<KafkaRecord> got = new ArrayList<KafkaRecord>();
        RecordBatches.read(batch, "t", 0, 0, got::add);
        check("a snappy compressed batch reads back", same(recs, got, 0));
    }

    /** A valid snappy block of literals only. */
    private static byte[] snappyLiteral(byte[] b) {
        KWriter w = new KWriter();
        w.uvarint(b.length);
        int p = 0;
        while (p < b.length) {
            int n = Math.min(60, b.length - p);
            w.int8((n - 1) << 2);
            w.raw(b, p, n);
            p += n;
        }
        return w.toBytes();
    }

    // ------------------------------------------------------------------ HLA values as JSON

    private static void jsonValues(File fom) throws Exception {
        InformationExchangeModel m = InformationExchangeModel.fromHlaEvolved(Collections.singletonList(fom.getPath()));
        HlaCodec c = new HlaCodec(m);
        Random rnd = new Random(7);
        int tried = 0, ok = 0;
        List<String> bad = new ArrayList<String>();
        for (DataType t : m.getDataTypes()) {
            for (int k = 0; k < 60; k++) {
                byte[] b;
                if (k == 0) {
                    b = c.encode(t, null);
                } else {
                    b = new byte[rnd.nextInt(k < 30 ? 16 : 120)];
                    rnd.nextBytes(b);
                }
                HValue v;
                try {
                    v = c.decode(t, b);
                    if (!Arrays.equals(c.encode(t, v), b) || v.toString().contains("NaN")) {
                        continue;                             // only canonical encodings (NaN payloads are not kept)
                    }
                } catch (RuntimeException e) {
                    continue;
                }
                tried++;
                String js = Json.toJson(HValueJson.toJson(v));
                List<String> issues = new ArrayList<String>();
                HValue back = HValueJson.fromJson(c, t, JsonParser.parse(js), (p, msg) -> issues.add(p + ": " + msg));
                byte[] again;
                try {
                    again = c.encode(t, back);
                } catch (RuntimeException e) {
                    again = null;
                }
                if (again != null && Arrays.equals(again, b) && issues.isEmpty()) {
                    ok++;
                } else if (bad.size() < 3) {
                    bad.add(t.getName() + " " + js + " " + issues);
                }
            }
        }
        check("every datatype of the RPR FOM: value -> JSON -> value is the same octets (" + ok + " of " + tried + ") " + bad,
                tried > 1000 && ok == tried);
        DataType marking = m.getClassAttribute("HLAobjectRoot.BaseEntity.PhysicalEntity.Platform.Aircraft", "Marking").getDataType();
        Map<String, HValue> mk = new java.util.LinkedHashMap<String, HValue>();
        mk.put("MarkingEncodingType", HValue.ofEnum(1, "ASCII"));
        mk.put("MarkingData", HValue.ofText("GRIPEN 11"));
        String js = Json.toJson(HValueJson.toJson(c.decode(marking, c.encode(marking, HValue.ofRecord(mk)))));
        check("a marking reads as text, an enumeration by name: " + js, js.startsWith("{\"MarkingEncodingType\":\"ASCII\",\"MarkingData\":\"GRIPEN 11"));
        DataType spatial = m.getClassAttribute("HLAobjectRoot.BaseEntity.PhysicalEntity.Platform.Aircraft", "Spatial").getDataType();
        List<String> issues = new ArrayList<String>();
        HValue v = HValueJson.fromJson(c, spatial, JsonParser.parse("{\"DeadReckoningAlgorithm\":\"DRM_FPW\",\"SpatialFPW\":{\"WorldLocation\":{\"X\":3288882.5},"
                + "\"Unknown\":1}}"), (p, msg) -> issues.add(p + ": " + msg));
        HValue loc = c.decode(spatial, c.encode(spatial, v));
        check("a partial variant record: given fields kept, the rest default, unknown names reported " + issues,
                loc.getAlternative() != null && loc.getAlternative().path("WorldLocation.X").asDouble() == 3288882.5
                        && issues.size() == 1 && issues.get(0).contains("Unknown"));
        DataType force = m.getClassAttribute("HLAobjectRoot.BaseEntity.PhysicalEntity.Platform.Aircraft", "ForceIdentifier").getDataType();
        check("an enumerator given as a number or as its name", c.decode(force, c.encode(force, HValueJson.fromJson(c, force, 2L, null))).asLong() == 2
                && c.decode(force, c.encode(force, HValueJson.fromJson(c, force, "Opposing", null))).asLong() == 2);
        DataType f32 = m.getDataType("HLAfloat32BE");
        check("NaN and Infinity as strings", "\"NaN\"".equals(Json.toJson(HValueJson.toJson(c.decode(f32, c.encode(f32, HValue.ofFloat(Double.NaN))))))
                && Double.isInfinite(c.decode(f32, c.encode(f32, HValueJson.fromJson(c, f32, "-Infinity", null))).asDouble()));
    }

    private static void settings() {
        check("security settings", KafkaSettings.Security.fromSetting("sasl-ssl") == KafkaSettings.Security.SASL_SSL
                && KafkaSettings.Security.fromSetting("TLS") == KafkaSettings.Security.SSL && KafkaSettings.Security.fromSetting("x") == null
                && KafkaSettings.Security.fromSetting("") == KafkaSettings.Security.PLAINTEXT);
        check("host:port", Arrays.equals(KafkaSettings.hostPort("[::1]:9092"), new String[] {"::1", "9092"}) && KafkaSettings.hostPort("k:0") == null
                && KafkaSettings.hostPort("k") == null);
        KafkaSettings s = new KafkaSettings();
        s.bootstrap = KafkaSettings.parseServers("a:1, b:2;c");
        s.security = KafkaSettings.Security.SASL_PLAINTEXT;
        s.saslMechanism = "GSSAPI";
        check("problems: a bad server, an unknown mechanism, no user " + s.problems(), s.problems().size() == 3);
    }

    // ------------------------------------------------------------------ against the in-memory broker

    private static KafkaSettings client(FakeKafkaBroker b) {
        KafkaSettings s = new KafkaSettings();
        s.bootstrap = Collections.singletonList(b.bootstrap());
        s.requestTimeoutMs = 10_000;
        return s;
    }

    private static final class Collector implements KafkaConsumer.Listener {
        final List<KafkaRecord> got = new CopyOnWriteArrayList<KafkaRecord>();
        volatile Map<String, List<Integer>> assigned = Collections.emptyMap();

        @Override
        public void records(List<KafkaRecord> records) {
            got.addAll(records);
        }

        @Override
        public void assigned(Map<String, List<Integer>> partitions) {
            assigned = partitions;
        }

        Set<String> values() {
            Set<String> s = ConcurrentHashMap.newKeySet();
            for (KafkaRecord r : got) {
                s.add(new String(r.value, StandardCharsets.UTF_8));
            }
            return s;
        }
    }

    private static void produceConsume(boolean oldest) throws Exception {
        String v = oldest ? "oldest versions" : "newest versions";
        try (FakeKafkaBroker b = new FakeKafkaBroker(3, oldest)) {
            KafkaProducer.Options po = new KafkaProducer.Options();
            po.compression = oldest ? RecordBatches.GZIP : RecordBatches.NONE;
            KafkaProducer p = new KafkaProducer(client(b), po, "test-producer");
            KafkaConsumer.Options co = new KafkaConsumer.Options();
            co.topics = Collections.singletonList("t1");
            co.startEarliest = true;
            Collector col = new Collector();
            for (int i = 0; i < 100; i++) {
                p.send("t1", new KafkaRecord(("key" + (i % 10)).getBytes(StandardCharsets.UTF_8), ("r" + i).getBytes(StandardCharsets.UTF_8), null, System.currentTimeMillis()));
            }
            check(v + ": 100 records flushed", p.flush(10_000) && p.sent.get() == 100 && b.count("t1") == 100);
            KafkaConsumer c = new KafkaConsumer(client(b), co, col, "test-consumer");
            check(v + ": a consumer without a group reads them all from the earliest", await(() -> col.got.size() == 100, 10_000) && col.values().size() == 100);
            boolean keyed = true;
            for (KafkaRecord r : col.got) {
                keyed &= r.partition == Partitioner.partition(r.key, 3);
            }
            check(v + ": each key in its murmur2 partition, in order per partition", keyed && ordered(col.got));
            int before = col.got.size();
            p.send("t1", new KafkaRecord(null, "late".getBytes(StandardCharsets.UTF_8), null, System.currentTimeMillis()));
            check(v + ": a record written later arrives (fetch waits for data)", await(() -> col.got.size() == before + 1, 5_000));
            check(v + ": the versions used are the broker's " + (oldest ? "lowest" : "highest"),
                    oldest ? b.versionsSeen.get(com.simweaver.kafka.wire.Api.FETCH) == 4 && b.versionsSeen.get(com.simweaver.kafka.wire.Api.PRODUCE) == 3
                            : b.versionsSeen.get(com.simweaver.kafka.wire.Api.FETCH) == 11 && b.versionsSeen.get(com.simweaver.kafka.wire.Api.PRODUCE) == 8);
            // a dropped connection: both go on
            b.dropConnections();
            p.send("t1", new KafkaRecord(null, "after".getBytes(StandardCharsets.UTF_8), null, System.currentTimeMillis()));
            check(v + ": after the connections dropped, records go on both ways", p.flush(10_000) && await(() -> col.values().contains("after"), 10_000));
            c.close();
            p.close();
            // acks 0: no response, still delivered
            po.acks = 0;
            KafkaProducer p0 = new KafkaProducer(client(b), po, "test-producer-0");
            p0.send("t2", new KafkaRecord(null, "a0".getBytes(StandardCharsets.UTF_8), null, System.currentTimeMillis()));
            check(v + ": acks 0 delivers without an answer", await(() -> b.count("t2") == 1, 5000) && p0.flush(2000));
            p0.close();
            // a topic that cannot be created
            b.autoCreate(false);
            KafkaProducer.Options pn = new KafkaProducer.Options();
            pn.deliveryTimeoutMs = 1500;
            KafkaProducer px = new KafkaProducer(client(b), pn, "test-producer-x");
            px.send("missing", new KafkaRecord(null, "x".getBytes(StandardCharsets.UTF_8), null, System.currentTimeMillis()));
            check(v + ": a topic that does not exist: the record expires, the error is kept (" + px.lastError() + ")",
                    await(() -> px.expired.get() == 1, 6000) && px.lastError() != null);
            px.close();
        }
    }

    private static boolean ordered(List<KafkaRecord> l) {
        Map<Integer, Long> last = new java.util.HashMap<Integer, Long>();
        for (KafkaRecord r : l) {
            Long prev = last.put(r.partition, r.offset);
            if (prev != null && prev >= r.offset) {
                return false;
            }
        }
        return true;
    }

    private static KafkaConsumer member(FakeKafkaBroker b, String group, Collector col, String name) {
        KafkaConsumer.Options co = new KafkaConsumer.Options();
        co.topics = Collections.singletonList("g1");
        co.group = group;
        co.startEarliest = true;
        co.heartbeatMs = 300;
        co.autoCommitMs = 500;
        return new KafkaConsumer(client(b), co, col, name);
    }

    private static void groups() throws Exception {
        try (FakeKafkaBroker b = new FakeKafkaBroker(4, false)) {
            b.createTopic("g1", 4);
            KafkaProducer p = new KafkaProducer(client(b), new KafkaProducer.Options(), "test-producer");
            for (int i = 0; i < 40; i++) {
                p.send("g1", new KafkaRecord(("k" + i).getBytes(StandardCharsets.UTF_8), ("a" + i).getBytes(StandardCharsets.UTF_8), null, System.currentTimeMillis()));
            }
            p.flush(5000);
            Collector one = new Collector();
            KafkaConsumer c1 = member(b, "grp", one, "member-1");
            check("a group member reads all 40 from the earliest, all 4 partitions assigned " + one.assigned,
                    await(() -> one.got.size() == 40, 10_000) && one.assigned.get("g1").size() == 4);
            check("it commits its positions", await(() -> b.committed("grp", "g1", 0) + b.committed("grp", "g1", 1)
                    + b.committed("grp", "g1", 2) + b.committed("grp", "g1", 3) == 40, 5000));
            c1.close();
            // a restart goes on where the group left off
            for (int i = 0; i < 10; i++) {
                p.send("g1", new KafkaRecord(("k" + i).getBytes(StandardCharsets.UTF_8), ("b" + i).getBytes(StandardCharsets.UTF_8), null, System.currentTimeMillis()));
            }
            p.flush(5000);
            Collector again = new Collector();
            KafkaConsumer c2 = member(b, "grp", again, "member-2");
            check("a new member of the group gets only what came after the commit", await(() -> again.got.size() == 10, 10_000)
                    && !again.values().contains("a0"));
            // a second member: the partitions are shared
            Collector third = new Collector();
            KafkaConsumer c3 = member(b, "grp", third, "member-3");
            check("two members share the partitions (2 + 2) " + again.assigned + " " + third.assigned, await(() -> again.assigned.containsKey("g1")
                    && third.assigned.containsKey("g1") && again.assigned.get("g1").size() == 2 && third.assigned.get("g1").size() == 2, 15_000));
            for (int i = 0; i < 40; i++) {
                p.send("g1", new KafkaRecord(("k" + i).getBytes(StandardCharsets.UTF_8), ("c" + i).getBytes(StandardCharsets.UTF_8), null, System.currentTimeMillis()));
            }
            p.flush(5000);
            check("each record reaches one member only", await(() -> count(again, "c") + count(third, "c") == 40, 10_000)
                    && count(again, "c") > 0 && count(third, "c") > 0);
            // one leaves: the other takes all partitions
            c3.close();
            check("a member leaves: the other has all 4 partitions again", await(() -> again.assigned.get("g1").size() == 4, 15_000));
            c2.close();
            p.close();
        }
    }

    private static int count(Collector c, String prefix) {
        int n = 0;
        for (String s : c.values()) {
            n += s.startsWith(prefix) ? 1 : 0;
        }
        return n;
    }

    private static void sasl() throws Exception {
        try (FakeKafkaBroker b = new FakeKafkaBroker(1, false).plainUser("alice", "s3cret")) {
            KafkaSettings ok = client(b);
            ok.security = KafkaSettings.Security.SASL_PLAINTEXT;
            ok.username = "alice";
            ok.password = "s3cret";
            KafkaProducer p = new KafkaProducer(ok, new KafkaProducer.Options(), "sasl-ok");
            p.send("s", new KafkaRecord(null, "x".getBytes(StandardCharsets.UTF_8), null, System.currentTimeMillis()));
            check("SASL PLAIN with the right password", p.flush(5000) && b.count("s") == 1);
            p.close();
            KafkaSettings bad = client(b);
            bad.security = KafkaSettings.Security.SASL_PLAINTEXT;
            bad.username = "alice";
            bad.password = "wrong";
            KafkaProducer q = new KafkaProducer(bad, new KafkaProducer.Options(), "sasl-bad");
            q.send("s", new KafkaRecord(null, "y".getBytes(StandardCharsets.UTF_8), null, System.currentTimeMillis()));
            check("a wrong password: refused, said so (" + q.lastError() + ")", await(() -> q.lastError() != null
                    && q.lastError().contains("SASL_AUTHENTICATION_FAILED"), 5000) && b.count("s") == 1);
            q.close(0);
            KafkaSettings none = client(b);
            KafkaProducer r = new KafkaProducer(none, new KafkaProducer.Options(), "sasl-none");
            r.send("s", new KafkaRecord(null, "z".getBytes(StandardCharsets.UTF_8), null, System.currentTimeMillis()));
            check("no SASL against a SASL broker: the hint says so (" + r.lastError() + ")", await(() -> r.lastError() != null
                    && r.lastError().contains("SASL"), 5000));
            r.close(0);
        }
    }

    static boolean await(BooleanSupplier c, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (c.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return c.getAsBoolean();
    }

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }
}
