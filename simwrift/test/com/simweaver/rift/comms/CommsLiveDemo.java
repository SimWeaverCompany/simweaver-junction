package com.simweaver.rift.comms;

import com.simweaver.common.audio.Pcm;
import com.simweaver.common.dis.DisEntityType;
import com.simweaver.common.dis.DisWriter;
import com.simweaver.common.hla.codec.HValue;
import com.simweaver.common.hla.codec.HlaCodec;
import com.simweaver.common.hla.session.ConnectSettings;
import com.simweaver.common.hla.session.RtiLibrary;
import com.simweaver.common.hla.session.RtiSession;
import com.simweaver.common.hla.session.RtiSessionListener;
import com.simweaver.common.iem.InformationExchangeModel;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A live Comms demo for trying the page in a browser (test only): runs the whole server in this
 * JVM (run it in the runtime folder, with fakerti-1516e.jar on the class path), creates a Talk
 * federation on the test RTI with chat and voice traffic, sends DIS 6 radio traffic (a tone on
 * 251 MHz, binary data on 969 MHz) to UDP port 3000, and creates the datasources HLA3-RPR2-Comms and
 * DIS6 through the REST API. Then prints the Comms page URLs.
 *
 * <pre>cd simwrift/rtdir; java -Dcomms.foms=a;b -cp ../../out/classes:../../out/test:fakerti-1516e.jar com.simweaver.rift.comms.CommsLiveDemo</pre>
 */
public final class CommsLiveDemo {

    private CommsLiveDemo() {
    }

    public static void main(String[] args) throws Exception {
        Thread server = new Thread(() -> {
            try {
                com.simweaver.rift.Server.main(new String[0]);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "demo-server");
        server.setDaemon(true);
        server.start();
        Thread.sleep(6000);

        String[] foms = System.getProperty("comms.foms", System.getProperty("talk.foms", "")).split(";");
        InformationExchangeModel m = InformationExchangeModel.fromHlaEvolved(Arrays.asList(foms));
        HlaCodec c = new HlaCodec(m);
        RtiSession s = RtiLibrary.classPath().createSession(RtiSession.Version.HLA_1516E);
        Quiet listener = new Quiet();
        s.connect(ConnectSettings.defaults(), listener);
        listener.session = s;
        s.join("Comms", "CommsSim", "CommsSim", Arrays.asList(foms), true);
        byte[] alpha = uuid(), bravo = uuid(), ops = uuid(), net1 = uuid(), guard = uuid();
        object(s, m, c, "HLAobjectRoot.PitchTalk.Client", "UUID", HValue.ofBytes(alpha), "MasterConfiguredName", HValue.ofText("Alpha Lead"));
        object(s, m, c, "HLAobjectRoot.PitchTalk.Client", "UUID", HValue.ofBytes(bravo), "MasterConfiguredName", HValue.ofText("Bravo Ops"));
        object(s, m, c, "HLAobjectRoot.PitchTalk.Channel.ChatChannel", "UUID", HValue.ofBytes(ops), "Name", HValue.ofText("Ops"),
                "Description", HValue.ofText("Operations chat"));
        object(s, m, c, "HLAobjectRoot.PitchTalk.Channel.AudioChannel.VoiceChannel", "UUID", HValue.ofBytes(net1), "Name", HValue.ofText("Net1"));
        object(s, m, c, "HLAobjectRoot.PitchTalk.Channel.AudioChannel.RadioChannel", "UUID", HValue.ofBytes(guard), "Name", HValue.ofText("Guard"));
        // 0.12.1: Opus packets for the lobby (-Dopus.packets=<file of 2-octet-length-prefixed packets>)
        java.util.List<byte[]> opus = new java.util.ArrayList<byte[]>();
        String opusFile = System.getProperty("opus.packets", "");
        if (!opusFile.isEmpty()) {
            ByteBuffer ob = ByteBuffer.wrap(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(opusFile)));
            while (ob.remaining() > 2) {
                byte[] pk = new byte[ob.getShort() & 0xFFFF];
                ob.get(pk);
                opus.add(pk);
            }
        }
        int text = s.interactionClass("HLAinteractionRoot.PitchTalk.Message.Text");
        int audio = s.interactionClass("HLAinteractionRoot.PitchTalk.Message.Audio");
        s.publishInteractionClass(text);
        s.publishInteractionClass(audio);

        // the datasources
        String configs = get("/api/datasourceconfigs");
        create(idOf(configs, "HLA3-RPR2-Comms"), "Comms");
        create(idOf(configs, "DIS6"), "1");
        System.out.println("DEMO datasources: " + get("/api/datasources"));

        DatagramSocket udp = new DatagramSocket();
        String[] lines = {"Contact north, 2 km", "Roger, moving to checkpoint Bravo", "Hold at phase line Red", "Wilco",
            "Request fire mission grid 3456 7890", "Copy, standing by"};
        long start = System.currentTimeMillis();
        int n = 0;
        while (true) {
            long t = System.currentTimeMillis() - start;
            // DIS: entity + transmitters every 2 s, tone on 251 MHz 3 s of every 6 s, data on 969 MHz twice a second
            if (n % 100 == 0) {
                send(udp, entity(1, 2, 3, "VIPER 11"));
                send(udp, transmitter(1, 2, 3, 1, 251_000_000L, (t / 3000) % 2 == 0 ? 2 : 1));
                send(udp, transmitter(1, 2, 3, 2, 969_000_000L, 2));
                send(udp, transmitter(1, 2, 7, 1, 251_000_000L, 1));
                send(udp, entity(1, 2, 7, "HAWK 2"));
            }
            if ((t / 3000) % 2 == 0) {
                send(udp, signal(1, 2, 3, 1, 4, 8000, Pcm.octets(tone(n, 160, 8000, 440), false)));
            }
            if (n % 25 == 0) {
                send(udp, signal(1, 2, 3, 2, (1 << 14) | 1, 31600, new byte[] {9, 8, 7, 6, 5, 4, 3, 2, 1, 0, 1, 2}));
            }
            // Talk: a chat line every 4 s, voice on Net1 2 s of every 5 s
            if (n % 200 == 0) {
                Map<String, HValue> tc = new LinkedHashMap<String, HValue>();
                tc.put("Text", HValue.ofText(lines[(n / 200) % lines.length]));
                interaction(s, m, c, text, "HLAinteractionRoot.PitchTalk.Message.Text", "SenderUUID", HValue.ofBytes((n / 200) % 2 == 0 ? alpha : bravo),
                        "ChannelUUID", HValue.ofBytes(ops), "Text", HValue.ofRecord(tc));
            }
            if ((t / 1000) % 5 < 2) {
                Map<String, HValue> ac = new LinkedHashMap<String, HValue>();
                ac.put("Audio", HValue.ofBytes(Pcm.octets(tone(n, 320, 16000, 660), true)));
                ac.put("Codec", HValue.ofInt(2));
                interaction(s, m, c, audio, "HLAinteractionRoot.PitchTalk.Message.Audio", "SenderUUID", HValue.ofBytes(bravo),
                        "ChannelUUID", HValue.ofBytes(net1), "AudioContent", HValue.ofRecord(ac));
            }
            if (!opus.isEmpty() && (t / 1000) % 6 >= 3) {           // Opus in the lobby 3 s of every 6 s
                Map<String, HValue> oc = new LinkedHashMap<String, HValue>();
                oc.put("Audio", HValue.ofBytes(opus.get(n % opus.size())));
                oc.put("Codec", HValue.ofInt(1));
                interaction(s, m, c, audio, "HLAinteractionRoot.PitchTalk.Message.Audio", "SenderUUID", HValue.ofBytes(alpha),
                        "ChannelUUID", HValue.ofBytes(new byte[16]), "AudioContent", HValue.ofRecord(oc));
            }
            n++;
            long next = start + n * 20L;
            long sleep = next - System.currentTimeMillis();
            if (sleep > 0) Thread.sleep(sleep);
        }
    }

    static short[] tone(int frame, int samples, int rate, double hz) {
        short[] s = new short[samples];
        for (int i = 0; i < samples; i++) {
            double t = ((long) frame * samples + i) / (double) rate;
            s[i] = (short) (Math.sin(2 * Math.PI * hz * t) * 6000);
        }
        return s;
    }

    private static void object(RtiSession s, InformationExchangeModel m, HlaCodec c, String cls, Object... kv) throws Exception {
        int oc = s.objectClass(cls);
        int[] h = new int[kv.length / 2];
        byte[][] v = new byte[h.length][];
        for (int i = 0; i < h.length; i++) {
            h[i] = s.attribute(oc, (String) kv[2 * i]);
            v[i] = c.encode(m.getClassAttribute(cls, (String) kv[2 * i]).getAttribute().getDataType(), (HValue) kv[2 * i + 1]);
        }
        s.publishObjectClass(oc, h);
        long o = s.registerObject(oc, null);
        s.updateAttributes(o, h, v, null);
        VALUES.put(o, new Object[] {h, v});
    }

    private static void interaction(RtiSession s, InformationExchangeModel m, HlaCodec c, int ic, String cls, Object... kv) throws Exception {
        int[] h = new int[kv.length / 2];
        byte[][] v = new byte[h.length][];
        for (int i = 0; i < h.length; i++) {
            h[i] = s.parameter(ic, (String) kv[2 * i]);
            v[i] = c.encode(m.getInteractionClass(cls).getParameter((String) kv[2 * i]).getDataType(), (HValue) kv[2 * i + 1]);
        }
        s.sendInteraction(ic, h, v, null);
    }

    private static byte[] uuid() {
        UUID u = UUID.randomUUID();
        return ByteBuffer.allocate(16).putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits()).array();
    }

    private static byte[] entity(int site, int app, int number, String marking) {
        DisWriter w = new DisWriter(144).header(6, 1, 1, 1, 0, 0);
        w.entityId(site, app, number).u8(1).u8(0).entityType(new DisEntityType(1, 2, 225, 1, 1, 3, 0)).zeros(8);
        w.f32(0).f32(0).f32(0).f64(3_100_000).f64(1_000_000).f64(5_400_000).f32(0).f32(0).f32(0);
        w.u32(0).u8(1).zeros(15).zeros(24).u8(1).text(marking, 11).u32(0);
        return w.finish();
    }

    private static byte[] transmitter(int site, int app, int number, int radio, long freq, int state) {
        DisWriter w = new DisWriter(120).header(6, 1, 25, 4, 0, 0);
        w.entityId(site, app, number).u16(radio).u8(7).u8(1).u16(225).u8(1).u8(1).u16(1);
        w.u8(state).u8(0).u16(0).f64(3_100_000).f64(1_000_000).f64(5_400_000).f32(0).f32(0).f32(0).u16(0).u16(0);
        w.u64(freq).f32(25000).f32(40).u16(0).u16(1).u16(2).u16(1).u16(0).u16(0).u8(0).zeros(3);
        return w.finish();
    }

    private static byte[] signal(int site, int app, int number, int radio, int scheme, int rate, byte[] data) {
        DisWriter w = new DisWriter(64 + data.length).header(6, 1, 26, 4, 0, 0);
        w.entityId(site, app, number).u16(radio).u16(scheme).u16(0).u32(rate).u16(data.length * 8).u16(scheme == 4 ? data.length / 2 : data.length).bytes(data);
        w.zeros((4 - data.length % 4) % 4);
        return w.finish();
    }

    private static void send(DatagramSocket s, byte[] pdu) throws Exception {
        s.send(new DatagramPacket(pdu, pdu.length, InetAddress.getLoopbackAddress(), 3000));
    }

    // ------------------------------------------------------------------ REST (test only: any certificate)

    private static javax.net.ssl.SSLSocketFactory trustAll() throws Exception {
        javax.net.ssl.TrustManager[] tm = {new javax.net.ssl.X509TrustManager() {
            public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) { }
            public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) { }
            public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
        }};
        javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
        ctx.init(null, tm, new java.security.SecureRandom());
        return ctx.getSocketFactory();
    }

    private static javax.net.ssl.HttpsURLConnection open(String path) throws Exception {
        javax.net.ssl.HttpsURLConnection c = (javax.net.ssl.HttpsURLConnection) new URL("https://127.0.0.1:8843" + path).openConnection();
        c.setSSLSocketFactory(trustAll());
        c.setHostnameVerifier((h, x) -> true);
        return c;
    }

    private static String get(String path) throws Exception {
        javax.net.ssl.HttpsURLConnection c = open(path);
        try (InputStream in = c.getInputStream()) {
            return read(in);
        }
    }

    private static void create(String configId, String federation) throws Exception {
        javax.net.ssl.HttpsURLConnection c = open("/api/datasources");
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream o = c.getOutputStream()) {
            o.write(("{\"configid\":\"" + configId + "\",\"federation\":\"" + federation + "\"}").getBytes(StandardCharsets.UTF_8));
        }
        InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream();
        System.out.println("DEMO create " + configId + ": " + c.getResponseCode() + " " + read(in));
    }

    private static String idOf(String configs, String name) {
        Matcher mm = Pattern.compile("\\{[^{}]*?\"id\":(-?\\d+|\"[^\"]+\")[^{}]*?\"name\":\"" + Pattern.quote(name) + "\"").matcher(configs);
        if (mm.find()) return mm.group(1).replace("\"", "");
        mm = Pattern.compile("\"name\":\"" + Pattern.quote(name) + "\"[^{}]*?\"id\":(-?\\d+|\"[^\"]+\")").matcher(configs);
        return mm.find() ? mm.group(1).replace("\"", "") : "?";
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while (in != null && (r = in.read(buf)) > 0) b.write(buf, 0, r);
        return b.toString("UTF-8");
    }

    /** What each registered object sent, to answer provide requests (Rift asks when it discovers one). */
    private static final Map<Long, Object[]> VALUES = new java.util.concurrent.ConcurrentHashMap<Long, Object[]>();

    private static final class Quiet implements RtiSessionListener {
        volatile RtiSession session;
        public void discovered(long instance, int objectClass, String name, String producer) { }
        public void reflected(long instance, int[] attributes, byte[][] values, byte[] tag, String producer) { }
        public void removed(long instance, byte[] tag, String producer) { }
        public void received(int interactionClass, int[] parameters, byte[][] values, byte[] tag, String producer) { }
        public void provideRequested(long instance, int[] attributes, byte[] tag) {
            Object[] hv = VALUES.get(instance);
            if (hv == null || session == null) return;
            new Thread(() -> {                                  // not from the callback thread
                try {
                    session.updateAttributes(instance, (int[]) hv[0], (byte[][]) hv[1], null);
                } catch (Exception e) {
                    System.out.println("DEMO provide failed: " + e);
                }
            }).start();
        }
        public void nameReserved(String name, boolean success) { }
        public void connectionLost(String reason) { }
    }
}
