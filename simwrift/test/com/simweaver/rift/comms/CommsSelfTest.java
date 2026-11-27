package com.simweaver.rift.comms;

import com.simweaver.common.audio.Pcm;
import com.simweaver.common.config.SimwProperties;
import com.simweaver.common.dis.DisEntityType;
import com.simweaver.common.dis.DisWriter;
import com.simweaver.common.hla.codec.HValue;
import com.simweaver.common.hla.codec.HlaCodec;
import com.simweaver.common.hla.decoders.HlaDataDecoder;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.common.iem.InteractionClass;
import com.simweaver.common.iem.ObjectClass;
import com.simweaver.common.json.Json;
import com.simweaver.rift.DatasourceConfig;
import com.simweaver.rift.api.DataSource;
import com.simweaver.rift.api.DatasourceFactory;
import com.simweaver.rift.db.RiftAttribute;
import com.simweaver.rift.db.RiftDatabase;
import com.simweaver.rift.db.RiftParameter;
import com.sun.net.httpserver.HttpServer;

import java.io.DataInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The Comms view (0.12.0): a DIS 7 datasource over UDP (transmitters by frequency with their host,
 * PCM16 / mu-law audio converted and streamed over HTTP, a raw binary signal, an unplayable
 * encoding), the audio endpoint's errors, and Talk channels / chat / PCM16 audio fed straight into a
 * database (needs the tester's HLA Comms FOM: -Dcomms.foms=a;b, else that part is skipped).
 *
 * <pre>java -Dcomms.foms=… -cp out/classes:out/test com.simweaver.rift.comms.CommsSelfTest [fom root]</pre>
 */
public final class CommsSelfTest {

    private static int checks;
    private static int failures;
    private static final long F1 = 251_000_000L, F2 = 300_000_000L, F3 = 40_000_000L;

    private CommsSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        File fomRoot = new File(args.length > 0 ? args[0] : "simwrift/rtdir/fom").getAbsoluteFile();
        units();
        dis(fomRoot);
        talk(fomRoot);
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void units() {
        check("frequency text", CommsTracker.frequencyText(251_000_000L).equals("251 MHz") && CommsTracker.frequencyText(30_025_000L).equals("30.025 MHz"));
        CommsChannel ch = new CommsChannel("f-1", "radio", "radio", "x");
        ch.hit(10_000_500, 4);
        ch.hit(10_000_900, 4);
        ch.hit(10_003_100, 4);
        long[] h = ch.history(10_003);
        check("activity: per second, gaps cleared", h[CommsChannel.SECONDS - 1] == 1 && h[CommsChannel.SECONDS - 4] == 2
                && h[CommsChannel.SECONDS - 2] == 0 && ch.frames == 3);
        ch.advance(10_003 + 500);
        check("activity: old seconds fall out", ch.history(10_003 + 500)[CommsChannel.SECONDS - 1] == 0 && ch.countAt(10_000) == 0);
        CommsAudioStream s = new CommsAudioStream(Arrays.asList("a", "b", "a"), "test");
        check("stream: channel numbers by first position", s.channelIndex("a") == 0 && s.channelIndex("b") == 1 && s.channelIndex("c") == -1);
        for (int i = 0; i < CommsAudioStream.QUEUE + 10; i++) {
            s.audio(1, 7, 8000, i, new byte[] {(byte) i, 0});
        }
        byte[] f = s.poll();
        ByteBuffer b = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN);
        check("stream: a full queue drops the oldest (" + b.getInt(16) + ")", b.getInt(16) == 10 && ((Number) s.status().get("dropped")).longValue() == 10);
    }

    // ================================================================== DIS

    private static void dis(File fomRoot) throws Exception {
        String rpr2 = new File(fomRoot, "ieee1516-2010/RPR_FOM_v2.0_Link16_1516-2010.xml").getPath();
        int port;
        try (DatagramSocket probe = new DatagramSocket(0)) {
            port = probe.getLocalPort();
        }
        SimwProperties p = new SimwProperties();
        p.set("datasource.name", "Comms DIS test");
        p.set("datasource.standard", "dis7");
        p.set("datasource.hla.foms", rpr2);
        p.set("datasource.dis.interface", "127.0.0.1");
        p.set("datasource.dis.port", String.valueOf(port));
        p.set("datasource.dis.exid", "1");
        DataSource ds = DatasourceFactory.createDatasource(new DatasourceConfig(1, new File("comms-dis.datasource.settings"), p), null, null, null);
        CommsTracker t = new CommsTracker(ds.getDatabase(), ds.getId().toString(), null, CommsTracker.Settings.defaults());
        ds.getDatabase().addListener(t);
        check("DIS datasource listening", ds.connectJoinSub().isSuccess());
        DatagramSocket out = new DatagramSocket();
        send(out, port, entity(1, 2, 3, "VIPER 11"));
        send(out, port, transmitter(1, 2, 3, 1, F1, 2));
        send(out, port, transmitter(1, 2, 3, 2, F2, 1));
        send(out, port, transmitter(1, 2, 4, 1, F3, 2));
        waitFor(() -> t.hasChannel("f-" + F1) && t.hasChannel("f-" + F2) && t.hasChannel("f-" + F3), 3000);
        check("a channel per transmitter frequency " + t.channelIds(), t.hasChannel("f-" + F1) && t.hasChannel("f-" + F2) && t.hasChannel("f-" + F3));

        // listen over HTTP to F1 and F3
        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        UUID id = ds.getId();
        http.createContext("/api/audio", new CommsAudioHandler(u -> u.equals(id) ? t : null, "/api/audio"));
        http.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        http.start();
        String base = "http://127.0.0.1:" + http.getAddress().getPort() + "/api/audio/stream";
        check("no channels: 400", status(base + "?ds=" + id) == 400);
        check("a bad channel id: 400", status(base + "?ds=" + id + "&channels=a%2Fb") == 400);
        check("an unknown datasource: 404", status(base + "?ds=" + UUID.randomUUID() + "&channels=f-1") == 404);
        check("not a datasource id: 400", status(base + "?ds=x&channels=f-1") == 400);
        HttpURLConnection c = (HttpURLConnection) new URL(base + "?ds=" + id + "&channels=f-" + F1 + ",f-" + F3).openConnection();
        c.setReadTimeout(5000);
        DataInputStream in = new DataInputStream(c.getInputStream());
        check("stream: 200, octet-stream", c.getResponseCode() == 200 && "application/octet-stream".equals(c.getContentType()));
        Frame k = Frame.read(in);
        check("first a keep-alive", k.type == CommsAudioStream.TYPE_KEEPALIVE && k.payload.length == 0);
        waitFor(() -> t.listenerCount() == 1, 2000);

        short[] samples = new short[160];
        for (int i = 0; i < samples.length; i++) samples[i] = (short) (i * 100 - 8000);
        send(out, port, signal(1, 2, 3, 1, 4, 8000, Pcm.octets(samples, false)));                // PCM16 big endian
        byte[] mu = new byte[80];
        for (int i = 0; i < mu.length; i++) mu[i] = (byte) (0x80 + i);
        send(out, port, signal(1, 2, 3, 1, 1, 8000, mu));                                         // mu-law
        send(out, port, signal(1, 2, 3, 2, (1 << 14) | 1, 9600, new byte[] {1, 2, 3, 4, 5, 6, 7, 8}));   // raw binary on F2
        send(out, port, signal(1, 2, 4, 1, 2, 16000, new byte[64]));                               // CVSD on F3: not played
        Frame a = Frame.read(in);
        while (a.type == CommsAudioStream.TYPE_KEEPALIVE) a = Frame.read(in);
        check("audio frame: channel 0, rate 8000, PCM16 LE", a.type == 1 && a.encoding == 1 && a.channel == 0 && a.rate == 8000
                && Arrays.equals(Pcm.samples(a.payload, true), samples));
        Frame b = Frame.read(in);
        while (b.type == CommsAudioStream.TYPE_KEEPALIVE) b = Frame.read(in);
        check("mu-law converted", b.channel == 0 && Arrays.equals(Pcm.samples(b.payload, true), Pcm.muLawDecode(mu)) && b.source == a.source
                && b.seq > a.seq);
        in.close();
        c.disconnect();
        waitFor(() -> t.listenerCount() == 0, 8000);
        check("the listener goes when the page does", t.listenerCount() == 0);
        http.stop(0);

        String j = Json.toJson(t.snapshot());
        Map<String, String> chans = split(j);
        String c1 = chans.get("f-" + F1), c2 = chans.get("f-" + F2), c3 = chans.get("f-" + F3);
        check("F1: radio, 251 MHz, audio playable, 2 frames", c1 != null && c1.contains("\"name\":\"251 MHz\"") && c1.contains("\"playable\":true")
                && c1.contains("\"frames\":2") && c1.contains("\"family\":\"radio\""));
        check("F1 lists the transmitter 1-2-3/radio-1 with its host VIPER 11", c1 != null && c1.contains("\"label\":\"1-2-3/radio-1\"")
                && c1.contains("\"host\":{") && c1.contains("\"label\":\"VIPER 11\"") && c1.contains("\"status\":2"));
        check("F2: a raw binary signal, no audio", c2 != null && c2.contains("\"Raw binary\":1") && c2.contains("\"audio\":false"));
        check("F3: CVSD heard, not playable", c3 != null && c3.contains("\"audio\":true") && c3.contains("\"playable\":false")
                && c3.contains("not played"));
        check("activity history of 120 seconds", c1 != null && c1.contains("\"activity\":[") && j.contains("\"seconds\":120"));
        check("no chat on a radio channel", t.chat("f-" + F1, 10) != null && t.chat("nope", 10) == null);
        out.close();
        ds.disconnect();
    }

    // ================================================================== Talk

    private static void talk(File fomRoot) throws Exception {
        List<String> foms = com.simweaver.common.TestCommsFom.find(fomRoot);
        if (foms.isEmpty()) {
            System.out.println(com.simweaver.common.TestCommsFom.skipped("Comms Talk checks"));
            return;
        }
        InformationExchangeModel m = InformationExchangeModel.fromHlaEvolved(foms);
        HlaCodec codec = new HlaCodec(m);
        RiftDatabase db = new RiftDatabase("talk");
        db.setInformationExchangeModel(m);
        db.setDecoder(HlaDataDecoder.create(m));
        db.setInteractionClassNames(i -> m.getInteractionClass(i).getQualifiedName());
        CommsTracker t = new CommsTracker(db, "talk-ds", null, new CommsTracker.Settings(16000, true));
        db.addListener(t);

        byte[] chat = uuid(), voice = uuid(), client = uuid();
        object(db, m, codec, "HLAobjectRoot.PitchTalk.Channel.ChatChannel", "ch-ops", "UUID", HValue.ofBytes(chat), "Name", HValue.ofText("Ops"));
        object(db, m, codec, "HLAobjectRoot.PitchTalk.Channel.AudioChannel.VoiceChannel", "ch-net1", "UUID", HValue.ofBytes(voice),
                "Name", HValue.ofText("Net1"));
        object(db, m, codec, "HLAobjectRoot.PitchTalk.Client", "client-1", "UUID", HValue.ofBytes(client),
                "MasterConfiguredName", HValue.ofText("Bravo Ops"));
        Map<String, HValue> tc = new LinkedHashMap<String, HValue>();
        tc.put("Text", HValue.ofText("Contact north, 2 km"));
        interaction(db, m, codec, "HLAinteractionRoot.PitchTalk.Message.Text", "SenderUUID", HValue.ofBytes(client), "ChannelUUID",
                HValue.ofBytes(chat), "Text", HValue.ofRecord(tc));
        short[] s = new short[320];
        for (int i = 0; i < s.length; i++) s[i] = (short) (i * 50);
        Map<String, HValue> ac = new LinkedHashMap<String, HValue>();
        ac.put("Audio", HValue.ofBytes(Pcm.octets(s, true)));
        ac.put("Codec", HValue.ofInt(2));
        CommsAudioStream listen = t.open(Arrays.asList("t-" + CommsTracker.hex(voice), CommsTracker.LOBBY), "test");
        interaction(db, m, codec, "HLAinteractionRoot.PitchTalk.Message.Audio", "SenderUUID", HValue.ofBytes(client), "ChannelUUID",
                HValue.ofBytes(voice), "AudioContent", HValue.ofRecord(ac));
        Map<String, HValue> op = new LinkedHashMap<String, HValue>();
        byte[] packet = new byte[40];
        packet[0] = 0x78;                                          // an Opus TOC byte (CELT FB 20 ms, mono)
        for (int i = 1; i < packet.length; i++) packet[i] = (byte) (i * 7);
        op.put("Audio", HValue.ofBytes(packet));
        op.put("Codec", HValue.ofInt(1));
        interaction(db, m, codec, "HLAinteractionRoot.PitchTalk.Message.Audio", "SenderUUID", HValue.ofBytes(client), "ChannelUUID",
                HValue.ofBytes(new byte[16]), "AudioContent", HValue.ofRecord(op));
        db.awaitDrained(3, TimeUnit.SECONDS);

        Map<String, String> chans = split(Json.toJson(t.snapshot()));
        String ops = chans.get("t-" + CommsTracker.hex(chat)), net = chans.get("t-" + CommsTracker.hex(voice)), lobby = chans.get(CommsTracker.LOBBY);
        check("Talk chat channel Ops", ops != null && ops.contains("\"name\":\"Ops\"") && ops.contains("\"kind\":\"chat\"") && ops.contains("\"chatcount\":1"));
        Map<String, Object> tr = t.chat("t-" + CommsTracker.hex(chat), 50);
        String trj = Json.toJson(tr);
        check("transcript: Bravo Ops says 'Contact north, 2 km'", trj.contains("\"sender\":\"Bravo Ops\"") && trj.contains("Contact north, 2 km"));
        check("voice channel Net1: PCM16 playable, sender listed", net != null && net.contains("\"kind\":\"voice\"") && net.contains("\"playable\":true")
                && net.contains("\"label\":\"Bravo Ops\""));
        byte[] f = listen.poll();
        ByteBuffer b = f == null ? null : ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN);
        check("Talk audio streamed at 16 kHz", b != null && b.get(1) == 1 && b.getInt(12) == 16000 && b.getInt(20) == 640);
        check("lobby Opus: playable in the browser (codec opus)", lobby != null && lobby.contains("\"kind\":\"lobby\"")
                && lobby.contains("\"playable\":true") && lobby.contains("\"codec\":\"opus\"") && lobby.contains("\"encoding\":\"Opus\""));
        check("voice PCM16: codec pcm", net != null && net.contains("\"codec\":\"pcm\""));
        byte[] o = listen.poll();
        ByteBuffer ob = o == null ? null : ByteBuffer.wrap(o).order(ByteOrder.LITTLE_ENDIAN);
        check("the Opus packet goes out as it came: encoding 2, channel 1, 48 kHz", ob != null && ob.get(2) == CommsAudioStream.ENC_OPUS
                && ob.getShort(4) == 1 && ob.getInt(12) == 48000 && ob.getInt(20) == packet.length
                && Arrays.equals(Arrays.copyOfRange(o, CommsAudioStream.HEADER, o.length), packet));
        db.close();
    }

    private static void object(RiftDatabase db, InformationExchangeModel m, HlaCodec c, String cls, String name, Object... kv) {
        ObjectClass oc = m.getObjectClass(cls);
        Integer id = db.discoverInstance(oc.getId(), cls, name, "TalkFed");
        ArrayList<RiftAttribute> attrs = new ArrayList<RiftAttribute>();
        for (int i = 0; i < kv.length; i += 2) {
            com.simweaver.common.iem.ClassAttribute a = oc.getAttribute((String) kv[i]);
            attrs.add(new RiftAttribute(a.getId(), a.getName(), c.encode(a.getDataType(), (HValue) kv[i + 1]), null, null, null));
        }
        db.reflectObjectUpdate(id, attrs, null);
    }

    private static void interaction(RiftDatabase db, InformationExchangeModel m, HlaCodec c, String cls, Object... kv) {
        InteractionClass ic = m.getInteractionClass(cls);
        ArrayList<RiftParameter> ps = new ArrayList<RiftParameter>();
        for (int i = 0; i < kv.length; i += 2) {
            com.simweaver.common.iem.ClassParameter p = ic.getParameter((String) kv[i]);
            ps.add(new RiftParameter(p.getId(), p.getName(), c.encode(p.getDataType(), (HValue) kv[i + 1])));
        }
        db.reflectInteractionEvent(ic.getId(), ps, "TalkFed", null);
    }

    private static byte[] uuid() {
        UUID u = UUID.randomUUID();
        return ByteBuffer.allocate(16).putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits()).array();
    }

    // ================================================================== DIS PDUs

    private static byte[] entity(int site, int app, int number, String marking) {
        DisWriter w = new DisWriter(144).header(7, 1, 1, 1, 0, 0);
        w.entityId(site, app, number).u8(1).u8(0).entityType(new DisEntityType(1, 2, 225, 1, 1, 3, 0)).zeros(8);
        w.f32(0).f32(0).f32(0).f64(3_100_000).f64(1_000_000).f64(5_400_000).f32(0).f32(0).f32(0);
        w.u32(0).u8(1).zeros(15).zeros(24).u8(1).text(marking, 11).u32(0);
        return w.finish();
    }

    private static byte[] transmitter(int site, int app, int number, int radio, long freq, int state) {
        DisWriter w = new DisWriter(120).header(7, 1, 25, 4, 0, 0);
        w.entityId(site, app, number).u16(radio);
        w.u8(7).u8(1).u16(225).u8(1).u8(1).u16(1);              // radio type
        w.u8(state).u8(0).u16(0);
        w.f64(3_100_000).f64(1_000_000).f64(5_400_000).f32(0).f32(0).f32(0);
        w.u16(0).u16(0);                                        // antenna pattern
        w.u64(freq).f32(25000).f32(40);
        w.u16(0).u16(1).u16(2).u16(1).u16(0).u16(0);            // modulation, crypto
        w.u8(0).zeros(3);
        return w.finish();
    }

    private static byte[] signal(int site, int app, int number, int radio, int scheme, int rate, byte[] data) {
        DisWriter w = new DisWriter(64 + data.length).header(7, 1, 26, 4, 0, 0);
        w.entityId(site, app, number).u16(radio).u16(scheme).u16(0).u32(rate).u16(data.length * 8)
                .u16((scheme >>> 14) == 0 && scheme == 4 ? data.length / 2 : data.length).bytes(data);
        int pad = (4 - data.length % 4) % 4;
        w.zeros(pad);
        return w.finish();
    }

    private static void send(DatagramSocket s, int port, byte[] pdu) throws Exception {
        s.send(new DatagramPacket(pdu, pdu.length, InetAddress.getLoopbackAddress(), port));
        Thread.sleep(30);
    }

    // ================================================================== helpers

    private static final class Frame {
        int type, encoding, channel, source, rate;
        long seq;
        byte[] payload;

        static Frame read(DataInputStream in) throws Exception {
            byte[] h = new byte[CommsAudioStream.HEADER];
            in.readFully(h);
            ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
            Frame f = new Frame();
            if (b.get(0) != 1) throw new IllegalStateException("version " + b.get(0));
            f.type = b.get(1);
            f.encoding = b.get(2);
            f.channel = b.getShort(4) & 0xFFFF;
            f.source = b.getInt(8);
            f.rate = b.getInt(12);
            f.seq = b.getInt(16) & 0xFFFFFFFFL;
            f.payload = new byte[b.getInt(20)];
            in.readFully(f.payload);
            return f;
        }
    }

    private static int status(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        int s = c.getResponseCode();
        InputStream e = c.getErrorStream();
        if (e != null) e.close();
        c.disconnect();
        return s;
    }

    /** The channels of a snapshot as JSON text, by id (good enough for contains checks). */
    private static Map<String, String> split(String json) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        int i = json.indexOf("\"channels\":[");
        if (i < 0) return m;
        String rest = json.substring(i);
        String[] parts = rest.split("\\{\"id\":\"");
        for (int k = 1; k < parts.length; k++) {
            String id = parts[k].substring(0, parts[k].indexOf('"'));
            m.put(id, parts[k]);
        }
        return m;
    }

    interface Cond {
        boolean ok();
    }

    private static void waitFor(Cond c, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!c.ok() && System.currentTimeMillis() < end) Thread.sleep(20);
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (ok) {
            System.out.println("  ok   " + what);
        } else {
            failures++;
            System.out.println("  FAIL " + what);
        }
    }
}
