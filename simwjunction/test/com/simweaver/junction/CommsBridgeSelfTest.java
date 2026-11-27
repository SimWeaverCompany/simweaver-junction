package com.simweaver.junction;

import com.simweaver.common.config.SimwProperties;
import com.simweaver.common.dis.DisEntityId;
import com.simweaver.common.dis.DisPduHeader;
import com.simweaver.common.dis.DisReader;
import com.simweaver.common.dis.DisWriter;
import com.simweaver.common.hla.codec.HValue;
import com.simweaver.common.hla.codec.HlaCodec;
import com.simweaver.common.hla.session.ConnectSettings;
import com.simweaver.common.hla.session.RtiLibrary;
import com.simweaver.common.hla.session.RtiSession;
import com.simweaver.common.hla.session.RtiSessionListener;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.common.json.Json;
import com.simweaver.junction.config.ConfigLoader;
import com.simweaver.junction.config.GatewayConfig;

import java.io.File;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end test of the Talk router: two Talk federations on the test RTI (channel UUIDs
 * differ, names match), and a DIS 7 radio endpoint on the loopback interface for the radio
 * bridge (channel "Net1" = 251 MHz).
 *
 * <pre>java -cp out/classes:out/test com.simweaver.junction.CommsBridgeSelfTest &lt;fom root&gt; &lt;fake rti folder&gt;</pre>
 */
public final class CommsBridgeSelfTest {

    private static final String VOICE = "HLAobjectRoot.PitchTalk.Channel.AudioChannel.VoiceChannel";
    private static final String CHAT = "HLAobjectRoot.PitchTalk.Channel.ChatChannel";
    private static final String CLIENT = "HLAobjectRoot.PitchTalk.Client";
    private static final String MSG_AUDIO = "HLAinteractionRoot.PitchTalk.Message.Audio";
    private static final String MSG_TEXT = "HLAinteractionRoot.PitchTalk.Message.Text";
    private static final long NET1_HZ = 251_000_000L;
    private static int checks;
    private static int failures;

    private CommsBridgeSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        File fomRoot = new File(args.length > 0 ? args[0] : "simwrift/rtdir/fom").getAbsoluteFile();
        File rtiDir = new File(args.length > 1 ? args[1] : "fakerti/build").getAbsoluteFile();
        // 0.9.21: no HLA Comms FOM is part of the source; the tester gives one (TestCommsFom), else the test skips
        List<String> talkFoms = com.simweaver.common.TestCommsFom.find(fomRoot);
        if (talkFoms.isEmpty()) {
            System.out.println(com.simweaver.common.TestCommsFom.skipped("CommsBridgeSelfTest"));
            System.out.println("0 checks, 0 failures (skipped: no HLA Comms FOM)");
            System.exit(0);
        }
        String talkFom = String.join(";", talkFoms);
        String rpr2 = new File(fomRoot, "ieee1516-2010/RPR_FOM_v2.0_Link16_1516-2010.xml").getPath();
        File jar3 = new File(rtiDir, "fakerti-1516e.jar");
        RtiLibrary lib = RtiLibrary.isolated(Collections.singletonList(jar3));
        InformationExchangeModel tm = InformationExchangeModel.fromHlaEvolved(talkFoms);
        HlaCodec tc = new HlaCodec(tm);

        int gatewayPort = freePort();
        DatagramSocket sim = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        sim.setSoTimeout(200);

        // the Talk federations exist before the gateway joins: channels with the same names, other UUIDs
        Fed a = new Fed(lib, "TalkA", "TalkClientA", talkFom);
        Fed b = new Fed(lib, "TalkB", "TalkClientB", talkFom);
        byte[] ua = uuid(0xA1);
        byte[] ca = uuid(0xCA);
        byte[] cb = uuid(0xCB);
        byte[] opsA = uuid(0x0A);
        byte[] opsB = uuid(0x0B);
        byte[] intelA = uuid(0x1A);
        long clientA = a.publishObject(CLIENT, "ClientA", tc, tm, map("UUID", HValue.ofBytes(ua), "Id", HValue.ofText("client-a"),
                "Status", HValue.ofInt(1)));
        a.publishObject(VOICE, "Net1-A", tc, tm, map("UUID", HValue.ofBytes(ca), "Name", HValue.ofText("Net1"),
                "Description", HValue.ofText("battalion net")));
        a.publishObject(CHAT, "Ops-A", tc, tm, map("UUID", HValue.ofBytes(opsA), "Name", HValue.ofText("Ops"),
                "Description", HValue.ofText("")));
        a.publishObject(CHAT, "Intel-A", tc, tm, map("UUID", HValue.ofBytes(intelA), "Name", HValue.ofText("Intel"),
                "Description", HValue.ofText("")));
        b.publishObject(CHAT, "Operations-B", tc, tm, map("UUID", HValue.ofBytes(opsB), "Name", HValue.ofText("Operations"),
                "Description", HValue.ofText("")));
        b.publishObject(VOICE, "Net1-B", tc, tm, map("UUID", HValue.ofBytes(cb), "Name", HValue.ofText("net1"),
                "Description", HValue.ofText("")));
        for (Fed f : new Fed[] {a, b}) {
            f.subscribeMessages(tm);
            f.subscribeObject(CLIENT, "UUID", "Id");
            for (String ic : new String[] {MSG_AUDIO, MSG_TEXT}) {
                f.s.publishInteractionClass(f.s.interactionClass(ic));
            }
        }

        File dir = Files.createTempDirectory("junction-talk-test").toFile();
        File junctions = new File(dir, "etc/junctions");
        junctions.mkdirs();
        SimwProperties p = new SimwProperties();
        p.set("junction.name", "Talk A - Talk B - radio");
        p.set("junction.endpoints", "a;b;radio");
        for (String[] e : new String[][] {{"a", "TalkA"}, {"b", "TalkB"}}) {
            p.set("junction.endpoint." + e[0] + ".standard", "comms");
            p.set("junction.endpoint." + e[0] + ".hla.foms", talkFom);
            p.set("junction.endpoint." + e[0] + ".hla.federation", e[1]);
            p.set("junction.endpoint." + e[0] + ".hla.rtijar", jar3.getPath());
        }
        p.set("junction.endpoint.radio.standard", "dis7");
        p.set("junction.endpoint.radio.hla.foms", rpr2);
        p.set("junction.endpoint.radio.dis.interface", "127.0.0.1");
        p.set("junction.endpoint.radio.dis.port", String.valueOf(gatewayPort));
        p.set("junction.endpoint.radio.dis.sendaddress", "127.0.0.1:" + sim.getLocalPort());
        p.set("junction.router.kind", "comms");
        p.set("junction.comms.radio.endpoint", "radio");
        p.set("junction.comms.channelmap.b", "Ops>Operations");
        p.set("junction.comms.radio.map.Net1", String.valueOf(NET1_HZ));
        p.set("junction.comms.audio.samplerate", "16000");
        p.set("junction.comms.audio.rprsamplerate", "8000");
        GatewayConfig gc = new ConfigLoader(junctions, dir).gateway(new File(junctions, "talk.junction.settings"), p);
        check("config valid " + gc.getErrors() + " " + gc.getWarnings(), gc.isValid());

        Gateway gw = new Gateway(gc, Collections.<String, String>emptyMap(), dir, null);
        gw.start();
        check("gateway running: " + gw.getState() + " " + gw.getMessage(), gw.getState() == Gateway.State.RUNNING);

        // ---- clients are mirrored with their UUIDs
        String[] d = b.takeDiscovered("ClientA", 4000);
        check("B discovers the mirrored ClientA", d != null);
        if (d != null) {
            b.s.requestAttributeUpdate(Long.parseLong(d[1]), new int[] {b.s.attribute(b.s.objectClass(CLIENT), "UUID")}, null);
            byte[] u = b.waitValue(Long.parseLong(d[1]), b.s.attribute(b.s.objectClass(CLIENT), "UUID"), 3000);
            check("mirrored client keeps its UUID", u != null && Arrays.equals(u, ua));
        }
        String[] gwClient = a.takeDiscovered("Junction-", 4000);
        check("A discovers the gateway's radio client", gwClient != null);

        // ---- audio in Net1 from A arrives in B on B's Net1 (UUID rewritten), and on DIS
        short[] tone = new short[320];
        for (int i = 0; i < tone.length; i++) {
            tone[i] = (short) (8000 * Math.sin(i * 2 * Math.PI / 32));
        }
        byte[] pcmLe = le(tone);
        a.sendAudio(tm, tc, ua, ca, pcmLe, 2);
        Map<String, byte[]> got = b.takeInteraction(MSG_AUDIO, 4000);
        check("B gets the audio message", got != null);
        if (got != null) {
            check("ChannelUUID is B's Net1", Arrays.equals(got.get("ChannelUUID"), cb));
            check("SenderUUID is A's client", Arrays.equals(got.get("SenderUUID"), ua));
            HValue content = tc.decode(tm.getClassParameter(MSG_AUDIO, "AudioContent").getDataType(), got.get("AudioContent"));
            check("audio unchanged", Arrays.equals(content.field("Audio").asBytes(), pcmLe));
        }
        byte[] tx = pduFrom(sim, DisPduHeader.TRANSMITTER, 4000);
        check("DIS gets a transmitter for A's sender", tx != null);
        if (tx != null) {
            DisReader r = reader(tx);
            DisEntityId host = DisEntityId.read(r);
            r.skip(2 + 8);
            int state = r.u8();
            r.seek(DisPduHeader.SIZE + 60);
            check("transmitter host 900-99-1, on air, 251 MHz", host.equals(new DisEntityId(900, 99, 1)) && state == 2 && r.i64() == NET1_HZ);
        }
        byte[] sig = pduFrom(sim, DisPduHeader.SIGNAL, 4000);
        check("DIS gets the audio", sig != null);
        if (sig != null) {
            DisReader r = reader(sig);
            r.skip(8);
            int scheme = r.u16();
            r.skip(2);
            long rate = r.u32();
            int bits = r.u16();
            int samples = r.u16();
            check("PCM16 big endian, resampled to 8 kHz: 160 samples (got " + scheme + " " + rate + " " + samples + ")",
                    scheme == 4 && rate == 8000 && samples == 160 && bits == 160 * 16);
            byte[] data = r.bytes(320);
            check("sample 8 is the tone's sample 16, big endian", ((data[16] << 8) | (data[17] & 0xFF)) == tone[16]);
        }
        check("transmitter goes to 'on, not transmitting' after the hang time", waitTransmitterState(sim, 1, 3000));

        // ---- chat: Intel (not in B) is dropped, Ops is B's Operations (talk.channelmap.b), the lobby passes
        a.sendText(tm, tc, ua, intelA, "hello intel");
        a.sendText(tm, tc, ua, opsA, "hello ops");
        a.sendText(tm, tc, ua, new byte[16], "hello lobby");
        Map<String, byte[]> text = b.takeInteraction(MSG_TEXT, 3000);
        check("B gets Ops as its Operations", text != null && Arrays.equals(text.get("ChannelUUID"), opsB));
        text = b.takeInteraction(MSG_TEXT, 3000);
        check("B gets the lobby text", text != null && Arrays.equals(text.get("ChannelUUID"), new byte[16]));
        check("and not the Intel text", b.takeInteraction(MSG_TEXT, 500) == null);
        b.sendText(tm, tc, uuid(0xB1), opsB, "back from operations");
        text = a.takeInteraction(MSG_TEXT, 3000);
        check("A gets B's Operations as its Ops", text != null && Arrays.equals(text.get("ChannelUUID"), opsA));

        // ---- DIS radio on 251 MHz into Net1 of both Talk federations
        InetSocketAddress gwAddr = new InetSocketAddress(InetAddress.getLoopbackAddress(), gatewayPort);
        DisWriter w = new DisWriter(104).header(7, 1, DisPduHeader.TRANSMITTER, 4, 0, 0);
        w.entityId(new DisEntityId(1, 2, 3)).u16(1).u8(7).u8(1).u16(225).u8(1).u8(0).u16(0).u8(2).u8(0).u16(0);
        w.zeros(24).zeros(12).u16(0).u16(0).u64(NET1_HZ).f32(25000).f32(40).zeros(8).u16(0).u16(0).u8(0).zeros(3);
        byte[] txPdu = w.finish();
        sim.send(new DatagramPacket(txPdu, txPdu.length, gwAddr));
        Thread.sleep(300);
        short[] dis = new short[80];
        for (int i = 0; i < dis.length; i++) {
            dis[i] = (short) (i * 100);
        }
        w = new DisWriter(200).header(7, 1, DisPduHeader.SIGNAL, 4, 0, 0);
        w.entityId(new DisEntityId(1, 2, 3)).u16(1).u16(4).u16(0).u32(8000).u16(80 * 16).u16(80).bytes(be(dis));
        byte[] sigPdu = w.finish();
        sim.send(new DatagramPacket(sigPdu, sigPdu.length, gwAddr));
        for (Fed f : new Fed[] {a, b}) {
            Map<String, byte[]> m = f.takeInteraction(MSG_AUDIO, 4000);
            check(f.name + " gets the DIS audio", m != null);
            if (m != null) {
                check(f.name + ": in its Net1", Arrays.equals(m.get("ChannelUUID"), f == a ? ca : cb));
                check(f.name + ": not from A's client", !Arrays.equals(m.get("SenderUUID"), ua));
                HValue content = tc.decode(tm.getClassParameter(MSG_AUDIO, "AudioContent").getDataType(), m.get("AudioContent"));
                byte[] au = content.field("Audio").asBytes();
                check(f.name + ": PCM16 at 16 kHz, little endian (160 samples)", content.field("Codec").asLong() == 2 && au.length == 320
                        && (short) ((au[3] << 8) | (au[2] & 0xFF)) == 50);
            }
        }

        Thread.sleep(1200);                                   // the report is refreshed once a second
        String talkReport = Json.toJson(gw.getRouter().objects().toJson());
        System.out.println("objects: " + talkReport);
        check("report counts the dropped Ops message", talkReport.contains("\"droppednocounterpart\":1"));
        gw.stop();
        check("gateway stopped", gw.getState() == Gateway.State.STOPPED);
        a.s.close();
        b.s.close();
        sim.close();
        messagesMode(lib, tm, tc, talkFom, rpr2, jar3);
        messagesByName(lib, tm, tc, talkFom, rpr2, jar3);
        createMapped(lib, tm, tc, talkFom, jar3, false);
        createMapped(lib, tm, tc, talkFom, jar3, true);
        lobbyOnly(lib, tm, tc, talkFom, jar3);
        dynamicTalk(lib, tm, tc, talkFom, jar3);
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    /**
     * talk.mode=messages, talk.trackchannels=false: only Message interactions, channel UUIDs by
     * talk.channeluuidmap, radio by UUID key.
     */
    private static void messagesMode(RtiLibrary lib, InformationExchangeModel tm, HlaCodec tc, String talkFom, String rpr2, File jar3)
            throws Exception {
        String radioCls = "HLAinteractionRoot.PitchTalk.Message.Audio.Radio";
        String imageCls = "HLAinteractionRoot.PitchTalk.Message.Image";
        int gatewayPort = freePort();
        DatagramSocket sim = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        sim.setSoTimeout(200);
        Fed a = new Fed(lib, "TalkM1", "M1", talkFom);
        Fed b = new Fed(lib, "TalkM2", "M2", talkFom);
        byte[] ua = uuid(0x51);
        byte[] ca = uuid(0x5A);
        byte[] cb = uuid(0x5B);
        byte[] other = uuid(0x77);
        a.publishObject(CLIENT, "ClientM1", tc, tm, map("UUID", HValue.ofBytes(ua), "Id", HValue.ofText("m1")));
        a.publishObject(VOICE, "Net-M1", tc, tm, map("UUID", HValue.ofBytes(ca), "Name", HValue.ofText("Net1")));
        for (Fed f : new Fed[] {a, b}) {
            f.subscribeMessages(tm);
            f.s.subscribeInteractionClass(f.s.interactionClass(radioCls));
            f.s.subscribeInteractionClass(f.s.interactionClass(imageCls));
            f.subscribeObject(CLIENT, "UUID", "Id");
            for (String ic : new String[] {MSG_AUDIO, MSG_TEXT, imageCls}) {
                f.s.publishInteractionClass(f.s.interactionClass(ic));
            }
        }
        File dir = Files.createTempDirectory("junction-talkm-test").toFile();
        File junctions = new File(dir, "etc/junctions");
        junctions.mkdirs();
        SimwProperties p = new SimwProperties();
        p.set("junction.name", "Talk messages only");
        p.set("junction.endpoints", "m1;m2;radio");
        for (String[] e : new String[][] {{"m1", "TalkM1"}, {"m2", "TalkM2"}}) {
            p.set("junction.endpoint." + e[0] + ".standard", "talk");
            p.set("junction.endpoint." + e[0] + ".hla.foms", talkFom);
            p.set("junction.endpoint." + e[0] + ".hla.federation", e[1]);
            p.set("junction.endpoint." + e[0] + ".hla.rtijar", jar3.getPath());
        }
        p.set("junction.endpoint.radio.standard", "dis7");
        p.set("junction.endpoint.radio.hla.foms", rpr2);
        p.set("junction.endpoint.radio.dis.interface", "127.0.0.1");
        p.set("junction.endpoint.radio.dis.port", String.valueOf(gatewayPort));
        p.set("junction.endpoint.radio.dis.sendaddress", "127.0.0.1:" + sim.getLocalPort());
        // 0.14.1: the old setting names (talk, junction.talk.*) on purpose: they must keep working
        p.set("junction.router.kind", "talk");
        p.set("junction.talk.mode", "messages");
        p.set("junction.talk.trackchannels", "false");
        p.set("junction.talk.channeluuidmap.m2", hexOf(ca) + ">" + hexOf(cb));
        p.set("junction.talk.radio.endpoint", "radio");
        p.set("junction.talk.radio.map." + hexOf(ca), String.valueOf(NET1_HZ));
        GatewayConfig gc = new ConfigLoader(junctions, dir).gateway(new File(junctions, "talkm.junction.settings"), p);
        check("messages mode (old talk.* settings): config valid " + gc.getErrors() + gc.getWarnings(), gc.isValid() && gc.getWarnings().isEmpty());
        Gateway gw = new Gateway(gc, Collections.<String, String>emptyMap(), dir, null);
        gw.start();
        check("messages mode: running", gw.getState() == Gateway.State.RUNNING);
        check("messages mode: no client mirrored into M2", b.takeDiscovered("ClientM1", 1500) == null);
        check("messages mode: no gateway client object in M1", a.takeDiscovered("Junction-", 500) == null);
        String js = Json.toJson(gw);
        check("messages mode: the gateway discovered nothing in M1 (" + js.substring(js.indexOf("\"discovered\""), js.indexOf("\"discovered\"") + 16) + ")",
                js.contains("\"in\":{\"messages\":0,\"discovered\":0"));

        a.sendAudio(tm, tc, ua, ca, new byte[] {1, 0, 2, 0, 3, 0, 4, 0}, 2);
        Map<String, byte[]> m = b.takeInteraction(MSG_AUDIO, 4000);
        check("messages mode: audio arrives, UUID mapped by talk.channeluuidmap.m2", m != null && Arrays.equals(m.get("ChannelUUID"), cb));
        a.sendText(tm, tc, ua, other, "unmapped");
        m = b.takeInteraction(MSG_TEXT, 3000);
        check("messages mode: text with an unmapped UUID passes unchanged", m != null && Arrays.equals(m.get("ChannelUUID"), other));
        Map<String, HValue> img = new LinkedHashMap<String, HValue>();
        img.put("Image", HValue.ofBytes(new byte[] {9}));
        img.put("Codec", HValue.ofInt(1));
        a.send(tm, tc, imageCls, ua, other, "Image", HValue.ofRecord(img));
        check("messages mode: images are not in the default talk.messages", b.takeInteraction(imageCls, 1000) == null);
        check("messages mode: DIS gets the audio of the mapped UUID", pduFrom(sim, DisPduHeader.SIGNAL, 3000) != null);

        InetSocketAddress gwAddr = new InetSocketAddress(InetAddress.getLoopbackAddress(), gatewayPort);
        DisWriter w = new DisWriter(104).header(7, 1, DisPduHeader.TRANSMITTER, 4, 0, 0);
        w.entityId(new DisEntityId(1, 2, 3)).u16(1).u8(7).u8(1).u16(225).u8(1).u8(0).u16(0).u8(2).u8(0).u16(0);
        w.zeros(24).zeros(12).u16(0).u16(0).u64(NET1_HZ).f32(25000).f32(40).zeros(8).u16(0).u16(0).u8(0).zeros(3);
        byte[] txPdu = w.finish();
        sim.send(new DatagramPacket(txPdu, txPdu.length, gwAddr));
        Thread.sleep(300);
        w = new DisWriter(80).header(7, 1, DisPduHeader.SIGNAL, 4, 0, 0);
        w.entityId(new DisEntityId(1, 2, 3)).u16(1).u16(4).u16(0).u32(16000).u16(64).u16(4).bytes(new byte[8]);
        byte[] sigPdu = w.finish();
        sim.send(new DatagramPacket(sigPdu, sigPdu.length, gwAddr));
        m = a.takeInteraction(radioCls, 3000);
        check("messages mode: DIS audio reaches M1 as a radio message on the map's UUID", m != null && Arrays.equals(m.get("ChannelUUID"), ca));
        m = b.takeInteraction(radioCls, 3000);
        check("messages mode: and M2 on its mapped UUID", m != null && Arrays.equals(m.get("ChannelUUID"), cb));
        Thread.sleep(1200);
        check("messages mode: report says so", Json.toJson(gw.getRouter().objects().toJson()).contains("\"mode\":\"messages\""));
        gw.stop();
        a.s.close();
        b.s.close();
        sim.close();
    }

    /**
     * talk.mode=messages with the channels tracked (the default): channel X in one federation is
     * channel Y in the other by talk.channelmap; nothing mirrored or registered; radio by name.
     */
    /**
     * talk.createmappedchannels (the default): channels that talk.channelmap maps to a name the
     * other federation lacks are registered there; an unmapped channel is not; once the federation
     * has its own channel of that name, the gateway's one is deleted. In full or messages mode.
     */
    private static void createMapped(RtiLibrary lib, InformationExchangeModel tm, HlaCodec tc, String talkFom, File jar3, boolean messages)
            throws Exception {
        String w = messages ? "create (messages mode): " : "create: ";
        String suffix = messages ? "M" : "F";
        Fed a = new Fed(lib, "TalkC1" + suffix, "C1", talkFom);
        Fed b = new Fed(lib, "TalkC2" + suffix, "C2", talkFom);
        byte[] ua = uuid(0x71);
        byte[] alpha = uuid(0x7A);
        byte[] chat = uuid(0x7C);
        byte[] misc = uuid(0x7D);
        a.publishObject(VOICE, "Alpha-C1", tc, tm, map("UUID", HValue.ofBytes(alpha), "Name", HValue.ofText("Alpha")));
        a.publishObject(CHAT, "Chat1-C1", tc, tm, map("UUID", HValue.ofBytes(chat), "Name", HValue.ofText("Chat1")));
        a.publishObject(CHAT, "Misc-C1", tc, tm, map("UUID", HValue.ofBytes(misc), "Name", HValue.ofText("Misc")));
        for (Fed f : new Fed[] {a, b}) {
            f.subscribeMessages(tm);
            f.subscribeObject(VOICE, "UUID", "Name");
            f.subscribeObject(CHAT, "UUID", "Name");
            for (String ic : new String[] {MSG_AUDIO, MSG_TEXT}) {
                f.s.publishInteractionClass(f.s.interactionClass(ic));
            }
        }
        File dir = Files.createTempDirectory("junction-talkc-test").toFile();
        File junctions = new File(dir, "etc/junctions");
        junctions.mkdirs();
        SimwProperties p = new SimwProperties();
        p.set("junction.name", "Talk mapped channels");
        p.set("junction.endpoints", "c1;c2");
        for (String[] e : new String[][] {{"c1", "TalkC1" + suffix}, {"c2", "TalkC2" + suffix}}) {
            p.set("junction.endpoint." + e[0] + ".standard", "comms");
            p.set("junction.endpoint." + e[0] + ".hla.foms", talkFom);
            p.set("junction.endpoint." + e[0] + ".hla.federation", e[1]);
            p.set("junction.endpoint." + e[0] + ".hla.rtijar", jar3.getPath());
        }
        p.set("junction.router.kind", "comms");
        if (messages) {
            p.set("junction.comms.mode", "messages");
        }
        p.set("junction.comms.channelmap.c2", "Alpha>Bravo; Chat1>Chat2");
        p.set("junction.comms.createwaitms", "1000");
        GatewayConfig gc = new ConfigLoader(junctions, dir).gateway(new File(junctions, "talkc.junction.settings"), p);
        check(w + "config valid " + gc.getErrors() + gc.getWarnings(), gc.isValid() && gc.getWarnings().isEmpty()
                && gc.getRouter().isTalkCreateMappedChannels());
        Gateway gw = new Gateway(gc, Collections.<String, String>emptyMap(), dir, null);
        gw.start();
        check(w + "running", gw.getState() == Gateway.State.RUNNING);

        Map<String, String[]> made = new LinkedHashMap<String, String[]>();
        for (int i = 0; i < 2; i++) {
            String[] d = b.takeDiscovered("Junction-", 5000);
            if (d != null) made.put(d[3], d);
        }
        int uuidAttr = b.s.attribute(b.s.objectClass(VOICE), "UUID");
        int nameAttr = b.s.attribute(b.s.objectClass(VOICE), "Name");
        String[] bravo = null, chat2 = null;
        for (String[] d : made.values()) {
            byte[] n = b.waitValue(Long.parseLong(d[1]), nameAttr, 2000);
            String name = n == null ? null : tc.decode(tm.getClassAttribute(VOICE, "Name").getDataType(), n).asTrimmedText();
            if ("Bravo".equals(name)) bravo = d;
            if ("Chat2".equals(name)) chat2 = d;
        }
        check(w + "C2 gets a voice channel Bravo for C1's mapped Alpha " + made.keySet(),
                bravo != null && bravo[2].equals(String.valueOf(b.s.objectClass(VOICE))));
        check(w + "and a chat channel Chat2 for Chat1", chat2 != null && chat2[2].equals(String.valueOf(b.s.objectClass(CHAT))));
        check(w + "no channel for the unmapped Misc, none back into C1", b.takeDiscovered("Junction-", 1500) == null
                && a.takeDiscovered("Junction-", 300) == null);
        if (bravo != null) {
            byte[] bu = b.waitValue(Long.parseLong(bravo[1]), uuidAttr, 2000);
            a.sendText(tm, tc, ua, alpha, "on alpha");
            Map<String, byte[]> m = b.takeInteraction(MSG_TEXT, 3000);
            check(w + "C1's Alpha arrives in C2's new Bravo", m != null && bu != null && Arrays.equals(m.get("ChannelUUID"), Arrays.copyOf(bu, 16)));
            // C2 gets a Bravo of its own: the gateway's one goes, messages use C2's
            byte[] own = uuid(0x7B);
            b.publishObject(VOICE, "Bravo-C2", tc, tm, map("UUID", HValue.ofBytes(own), "Name", HValue.ofText("Bravo")));
            String[] r = b.take("removed", 4000);
            check(w + "C2 has its own Bravo now: the gateway deletes its one", r != null && r[1].equals(bravo[1]));
            a.sendText(tm, tc, ua, alpha, "on alpha again");
            m = b.takeInteraction(MSG_TEXT, 3000);
            check(w + "and Alpha now arrives in C2's own Bravo", m != null && Arrays.equals(m.get("ChannelUUID"), own));
        }
        String report = Json.toJson(gw.getRouter().objects().toJson());
        check(w + "report counts the created channels", report.contains("\"channelscreated\":2") && report.contains("\"createmappedchannels\":true"));
        gw.stop();
        a.s.close();
        b.s.close();
    }

    private static void messagesByName(RtiLibrary lib, InformationExchangeModel tm, HlaCodec tc, String talkFom, String rpr2,
            File jar3) throws Exception {
        String radioCls = "HLAinteractionRoot.PitchTalk.Message.Audio.Radio";
        int gatewayPort = freePort();
        DatagramSocket sim = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        sim.setSoTimeout(200);
        Fed a = new Fed(lib, "TalkN1", "N1", talkFom);
        Fed b = new Fed(lib, "TalkN2", "N2", talkFom);
        byte[] ua = uuid(0x61);
        byte[] alpha = uuid(0x6A);
        byte[] bravo = uuid(0x6B);
        byte[] opsA = uuid(0x6C);
        byte[] opsB = uuid(0x6D);
        byte[] intel = uuid(0x6E);
        byte[] unseen = uuid(0x6F);
        a.publishObject(CLIENT, "ClientN1", tc, tm, map("UUID", HValue.ofBytes(ua), "Id", HValue.ofText("n1")));
        a.publishObject(VOICE, "Alpha-N1", tc, tm, map("UUID", HValue.ofBytes(alpha), "Name", HValue.ofText("Alpha")));
        a.publishObject(CHAT, "Ops-N1", tc, tm, map("UUID", HValue.ofBytes(opsA), "Name", HValue.ofText("Ops")));
        a.publishObject(CHAT, "Intel-N1", tc, tm, map("UUID", HValue.ofBytes(intel), "Name", HValue.ofText("Intel")));
        b.publishObject(VOICE, "Bravo-N2", tc, tm, map("UUID", HValue.ofBytes(bravo), "Name", HValue.ofText("Bravo")));
        b.publishObject(CHAT, "Ops-N2", tc, tm, map("UUID", HValue.ofBytes(opsB), "Name", HValue.ofText("ops")));
        for (Fed f : new Fed[] {a, b}) {
            f.subscribeMessages(tm);
            f.s.subscribeInteractionClass(f.s.interactionClass(radioCls));
            f.subscribeObject(CLIENT, "UUID", "Id");
            f.subscribeObject(VOICE, "UUID", "Name");
            for (String ic : new String[] {MSG_AUDIO, MSG_TEXT}) {
                f.s.publishInteractionClass(f.s.interactionClass(ic));
            }
        }
        File dir = Files.createTempDirectory("junction-talkn-test").toFile();
        File junctions = new File(dir, "etc/junctions");
        junctions.mkdirs();
        SimwProperties p = new SimwProperties();
        p.set("junction.name", "Talk messages by name");
        p.set("junction.endpoints", "n1;n2;radio");
        for (String[] e : new String[][] {{"n1", "TalkN1"}, {"n2", "TalkN2"}}) {
            p.set("junction.endpoint." + e[0] + ".standard", "talk");
            p.set("junction.endpoint." + e[0] + ".hla.foms", talkFom);
            p.set("junction.endpoint." + e[0] + ".hla.federation", e[1]);
            p.set("junction.endpoint." + e[0] + ".hla.rtijar", jar3.getPath());
        }
        p.set("junction.endpoint.radio.standard", "dis7");
        p.set("junction.endpoint.radio.hla.foms", rpr2);
        p.set("junction.endpoint.radio.dis.interface", "127.0.0.1");
        p.set("junction.endpoint.radio.dis.port", String.valueOf(gatewayPort));
        p.set("junction.endpoint.radio.dis.sendaddress", "127.0.0.1:" + sim.getLocalPort());
        // 0.14.1: the old setting names on purpose (see messagesMode)
        p.set("junction.router.kind", "talk");
        p.set("junction.talk.mode", "messages");
        p.set("junction.talk.channelmap.n2", "Alpha>Bravo");
        p.set("junction.talk.radio.endpoint", "radio");
        p.set("junction.talk.radio.map.Alpha", String.valueOf(NET1_HZ));
        GatewayConfig gc = new ConfigLoader(junctions, dir).gateway(new File(junctions, "talkn.junction.settings"), p);
        check("by name: config valid " + gc.getErrors() + gc.getWarnings(), gc.isValid() && gc.getWarnings().isEmpty());
        Gateway gw = new Gateway(gc, Collections.<String, String>emptyMap(), dir, null);
        gw.start();
        check("by name: running", gw.getState() == Gateway.State.RUNNING);
        check("by name: no client mirrored into N2", b.takeDiscovered("ClientN1", 1500) == null);
        check("by name: no channel mirrored into N2", b.takeDiscovered("Alpha-N1", 300) == null);
        check("by name: no gateway object in N1", a.takeDiscovered("Junction-", 300) == null);

        a.sendAudio(tm, tc, ua, alpha, new byte[] {1, 0, 2, 0, 3, 0, 4, 0}, 2);
        Map<String, byte[]> m = b.takeInteraction(MSG_AUDIO, 4000);
        check("by name: N1's Alpha arrives in N2 as Bravo", m != null && Arrays.equals(m.get("ChannelUUID"), bravo));
        check("by name: sender UUID unchanged", m != null && Arrays.equals(m.get("SenderUUID"), ua));
        check("by name: DIS gets Alpha's audio", pduFrom(sim, DisPduHeader.SIGNAL, 3000) != null);
        a.sendText(tm, tc, ua, opsA, "ops by name");
        m = b.takeInteraction(MSG_TEXT, 3000);
        check("by name: Ops is matched to N2's ops (same name, other UUID)", m != null && Arrays.equals(m.get("ChannelUUID"), opsB));
        a.sendText(tm, tc, ua, intel, "intel");
        a.sendText(tm, tc, ua, unseen, "unseen");
        m = b.takeInteraction(MSG_TEXT, 3000);
        check("by name: Intel (not in N2) dropped, a channel never seen passes unchanged",
                m != null && Arrays.equals(m.get("ChannelUUID"), unseen));
        check("by name: nothing else", b.takeInteraction(MSG_TEXT, 500) == null);
        b.sendText(tm, tc, uuid(0x62), bravo, "back from bravo");
        m = a.takeInteraction(MSG_TEXT, 3000);
        check("by name: N2's Bravo arrives in N1 as Alpha", m != null && Arrays.equals(m.get("ChannelUUID"), alpha));

        InetSocketAddress gwAddr = new InetSocketAddress(InetAddress.getLoopbackAddress(), gatewayPort);
        DisWriter w = new DisWriter(104).header(7, 1, DisPduHeader.TRANSMITTER, 4, 0, 0);
        w.entityId(new DisEntityId(1, 2, 4)).u16(1).u8(7).u8(1).u16(225).u8(1).u8(0).u16(0).u8(2).u8(0).u16(0);
        w.zeros(24).zeros(12).u16(0).u16(0).u64(NET1_HZ).f32(25000).f32(40).zeros(8).u16(0).u16(0).u8(0).zeros(3);
        byte[] txPdu = w.finish();
        sim.send(new DatagramPacket(txPdu, txPdu.length, gwAddr));
        Thread.sleep(300);
        w = new DisWriter(80).header(7, 1, DisPduHeader.SIGNAL, 4, 0, 0);
        w.entityId(new DisEntityId(1, 2, 4)).u16(1).u16(4).u16(0).u32(16000).u16(64).u16(4).bytes(new byte[8]);
        byte[] sigPdu = w.finish();
        sim.send(new DatagramPacket(sigPdu, sigPdu.length, gwAddr));
        m = a.takeInteraction(MSG_AUDIO, 3000);
        check("by name: DIS audio reaches N1 in Alpha", m != null && Arrays.equals(m.get("ChannelUUID"), alpha));
        m = b.takeInteraction(MSG_AUDIO, 3000);
        check("by name: and N2 in Bravo (talk.channelmap.n2)", m != null && Arrays.equals(m.get("ChannelUUID"), bravo));
        Thread.sleep(1200);
        String report = Json.toJson(gw.getRouter().objects().toJson());
        check("by name: report lists the tracked channels", report.contains("\"trackchannels\":true") && report.contains("\"name\":\"Bravo\""));
        check("by name: report counts Intel and the unseen channel",
                report.contains("\"droppednocounterpart\":1") && report.contains("\"passedunknownchannel\":1"));
        gw.stop();
        a.s.close();
        b.s.close();
        sim.close();
    }

    /**
     * talk.mode=messages with talk.channels=none (0.9.16): the lobby only. Audio, text and images
     * in the lobby pass; a channel both federations have, and a channel the gateway never saw,
     * are dropped; nothing is mirrored or registered.
     */
    private static void lobbyOnly(RtiLibrary lib, InformationExchangeModel tm, HlaCodec tc, String talkFom, File jar3) throws Exception {
        String imageCls = "HLAinteractionRoot.PitchTalk.Message.Image";
        Fed a = new Fed(lib, "TalkL1", "L1", talkFom);
        Fed b = new Fed(lib, "TalkL2", "L2", talkFom);
        byte[] ua = uuid(0x71);
        byte[] alphaA = uuid(0x7A);
        byte[] alphaB = uuid(0x7B);
        byte[] unseen = uuid(0x7F);
        a.publishObject(CLIENT, "ClientL1", tc, tm, map("UUID", HValue.ofBytes(ua), "Id", HValue.ofText("l1")));
        a.publishObject(VOICE, "Alpha-L1", tc, tm, map("UUID", HValue.ofBytes(alphaA), "Name", HValue.ofText("Alpha")));
        b.publishObject(VOICE, "Alpha-L2", tc, tm, map("UUID", HValue.ofBytes(alphaB), "Name", HValue.ofText("Alpha")));
        for (Fed f : new Fed[] {a, b}) {
            f.subscribeMessages(tm);
            f.s.subscribeInteractionClass(f.s.interactionClass(imageCls));
            f.subscribeObject(CLIENT, "UUID", "Id");
            f.subscribeObject(VOICE, "UUID", "Name");
            for (String ic : new String[] {MSG_AUDIO, MSG_TEXT, imageCls}) {
                f.s.publishInteractionClass(f.s.interactionClass(ic));
            }
        }
        File dir = Files.createTempDirectory("junction-talkl-test").toFile();
        File junctions = new File(dir, "etc/junctions");
        junctions.mkdirs();
        SimwProperties p = new SimwProperties();
        p.set("junction.name", "Talk lobby only");
        p.set("junction.endpoints", "l1;l2");
        for (String[] e : new String[][] {{"l1", "TalkL1"}, {"l2", "TalkL2"}}) {
            p.set("junction.endpoint." + e[0] + ".standard", "comms");
            p.set("junction.endpoint." + e[0] + ".hla.foms", talkFom);
            p.set("junction.endpoint." + e[0] + ".hla.federation", e[1]);
            p.set("junction.endpoint." + e[0] + ".hla.rtijar", jar3.getPath());
        }
        p.set("junction.router.kind", "comms");
        p.set("junction.comms.mode", "messages");
        p.set("junction.comms.messages", "audio;text;image");
        p.set("junction.comms.channels", "none");
        GatewayConfig gc = new ConfigLoader(junctions, dir).gateway(new File(junctions, "talkl.junction.settings"), p);
        check("lobby only: config valid " + gc.getErrors() + gc.getWarnings(), gc.isValid() && gc.getWarnings().isEmpty()
                && gc.getRouter().isTalkLobbyOnly());
        Gateway gw = new Gateway(gc, Collections.<String, String>emptyMap(), dir, null);
        gw.start();
        check("lobby only: running", gw.getState() == Gateway.State.RUNNING);
        Thread.sleep(500);                                   // the channels are seen
        a.sendAudio(tm, tc, ua, new byte[16], new byte[] {1, 0, 2, 0}, 2);
        Map<String, byte[]> m = b.takeInteraction(MSG_AUDIO, 3000);
        check("lobby only: lobby audio passes", m != null && Arrays.equals(m.get("ChannelUUID"), new byte[16]));
        a.sendText(tm, tc, ua, new byte[16], "lobby text");
        m = b.takeInteraction(MSG_TEXT, 3000);
        check("lobby only: lobby text passes", m != null && Arrays.equals(m.get("ChannelUUID"), new byte[16]));
        Map<String, HValue> img = new LinkedHashMap<String, HValue>();
        img.put("Image", HValue.ofBytes(new byte[] {9}));
        img.put("Codec", HValue.ofInt(1));
        a.send(tm, tc, imageCls, ua, new byte[16], "Image", HValue.ofRecord(img));
        m = b.takeInteraction(imageCls, 3000);
        check("lobby only: lobby image passes", m != null && Arrays.equals(m.get("ChannelUUID"), new byte[16]));
        a.sendAudio(tm, tc, ua, alphaA, new byte[] {1, 0, 2, 0}, 2);
        check("lobby only: Alpha (in both federations) is not bridged", b.takeInteraction(MSG_AUDIO, 1000) == null);
        a.sendText(tm, tc, ua, unseen, "never seen");
        check("lobby only: a channel never seen is not passed either", b.takeInteraction(MSG_TEXT, 1000) == null);
        b.sendText(tm, tc, uuid(0x72), new byte[16], "lobby back");
        m = a.takeInteraction(MSG_TEXT, 3000);
        check("lobby only: and the lobby works back", m != null && Arrays.equals(m.get("ChannelUUID"), new byte[16]));
        check("lobby only: no client mirrored", b.takeDiscovered("ClientL1", 300) == null);
        check("lobby only: no channel registered", b.takeDiscovered("Junction-", 300) == null && a.takeDiscovered("Junction-", 300) == null);
        String report = Json.toJson(gw.getRouter().objects().toJson());
        check("lobby only: the two dropped messages are counted as filtered", report.contains("\"droppedfiltered\":2"));
        gw.stop();
        a.s.close();
        b.s.close();

        SimwProperties q = new SimwProperties();
        q.set("router.kind", "comms");
        q.set("comms.channels", "none;Alpha");
        q.set("comms.channelmap", "A>B");
        com.simweaver.junction.config.RouterConfig rc = new com.simweaver.junction.config.RouterConfig("x", q, "x", dir);
        check("lobby only: 'none' with other entries, and a channel map, are warned about " + rc.getWarnings(),
                rc.isTalkLobbyOnly() && rc.getWarnings().size() == 2);
    }

    private static String hexOf(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xFF));
        }
        return sb.toString();
    }

    private static boolean waitTransmitterState(DatagramSocket sim, int state, long millis) throws Exception {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            byte[] tx = pduFrom(sim, DisPduHeader.TRANSMITTER, end - System.currentTimeMillis());
            if (tx != null && (tx[DisPduHeader.SIZE + 16] & 0xFF) == state) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ helpers

    static Map<String, HValue> map(Object... kv) {
        Map<String, HValue> m = new LinkedHashMap<String, HValue>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (HValue) kv[i + 1]);
        }
        return m;
    }

    /**
     * 0.9.21: Talk endpoints without FOM files take the HLA Comms FOM from the federation; with
     * endpoint.hla.createfoms they create a federation that is not running; without, the error says
     * what to do.
     */
    private static void dynamicTalk(RtiLibrary lib, InformationExchangeModel tm, HlaCodec tc, String talkFom, File jar3) throws Exception {
        File dir = Files.createTempDirectory("junction-talkd-test").toFile();
        File junctions = new File(dir, "etc/junctions");
        junctions.mkdirs();
        // the settings
        SimwProperties p = new SimwProperties();
        p.set("junction.name", "Talk dynamic");
        p.set("junction.endpoints", "d1;d2");
        p.set("junction.endpoint.d1.standard", "comms");
        p.set("junction.endpoint.d1.hla.federation", "TalkD1");
        p.set("junction.endpoint.d1.hla.rtijar", jar3.getPath());
        p.set("junction.endpoint.d2.standard", "comms");
        p.set("junction.endpoint.d2.hla.federation", "TalkD2");
        p.set("junction.endpoint.d2.hla.createfoms", talkFom);
        p.set("junction.endpoint.d2.hla.rtijar", jar3.getPath());
        p.set("junction.router.kind", "comms");
        p.set("junction.comms.mode", "messages");
        p.set("junction.comms.messages", "text");
        GatewayConfig gc = new ConfigLoader(junctions, dir).gateway(new File(junctions, "talkd.junction.settings"), p);
        check("dynamic Talk: config valid " + gc.getErrors(), gc.isValid());
        com.simweaver.junction.config.EndpointConfig d1 = gc.getEndpoints().get("d1");
        com.simweaver.junction.config.EndpointConfig d2 = gc.getEndpoints().get("d2");
        check("dynamic Talk: no FOM files = from the federation, does not create it", d1.isDynamicFom() && !d1.isCreateFederation()
                && d1.getFomPaths().isEmpty());
        check("dynamic Talk: createfoms = creates a missing federation", d2.isDynamicFom() && d2.isCreateFederation()
                && d2.getCreateFomPaths().size() == talkFom.split(";").length
                && com.simweaver.common.json.Json.toJson(d2).contains("\"createfoms\""));
        SimwProperties bad = new SimwProperties();
        bad.set("junction.name", "Talk createfoms without dynamic");
        bad.set("junction.endpoints", "x;y");
        bad.set("junction.endpoint.x.standard", "comms");
        bad.set("junction.endpoint.x.hla.foms", talkFom);
        bad.set("junction.endpoint.x.hla.createfoms", talkFom);
        bad.set("junction.endpoint.y.standard", "comms");
        bad.set("junction.router.kind", "comms");
        GatewayConfig bc = new ConfigLoader(junctions, dir).gateway(new File(junctions, "bad.junction.settings"), bad);
        check("dynamic Talk: createfoms with FOM files is an error", !bc.isValid()
                && String.valueOf(bc.getErrors()).contains("createfoms"));

        // a running Talk federation (TalkD1) and one the gateway creates (TalkD2)
        Fed a = new Fed(lib, "TalkD1", "D1", talkFom);
        a.subscribeMessages(tm);
        a.s.publishInteractionClass(a.s.interactionClass(MSG_TEXT));
        a.publishObject(VOICE, "Alpha-D1", tc, tm, map("UUID", HValue.ofBytes(uuid(0x5A)), "Name", HValue.ofText("Alpha")));
        // the Start dialog's channel list reads the HLA Comms FOM from the federation too
        String probe = com.simweaver.common.json.Json.toJson(TargetProbe.probe(d1, dir, "TalkD1"));
        check("dynamic Talk: Discover lists the channels, FOM from the federation: " + probe, probe.contains("\"kind\":\"channel\"")
                && probe.contains("\"Alpha\"") && !probe.contains("\"error\""));
        String none = com.simweaver.common.json.Json.toJson(TargetProbe.probe(d1, dir, "TalkNoSuch"));
        check("dynamic Talk: Discover on a federation that is not running says so: " + none, none.contains("not running"));
        Gateway gw = new Gateway(gc, Collections.<String, String>emptyMap(), dir, null);
        gw.start();
        check("dynamic Talk: running (FOM from TalkD1, TalkD2 created) " + gw.getMessage(), gw.getState() == Gateway.State.RUNNING);
        Fed b = new Fed(lib, "TalkD2", "D2", talkFom);
        b.subscribeMessages(tm);
        b.s.publishInteractionClass(b.s.interactionClass(MSG_TEXT));
        Thread.sleep(300);
        a.sendText(tm, tc, uuid(0x41), new byte[16], "from D1");
        Map<String, byte[]> m = b.takeInteraction(MSG_TEXT, 3000);
        check("dynamic Talk: a text passes from the running federation to the created one", m != null);
        gw.stop();
        a.s.close();
        b.s.close();

        // a Talk federation that is not running, no createfoms: the error says what to do
        SimwProperties q = new SimwProperties();
        q.set("junction.name", "Talk missing");
        q.set("junction.endpoints", "m1;m2");
        for (String e : new String[] {"m1", "m2"}) {
            q.set("junction.endpoint." + e + ".standard", "comms");
            q.set("junction.endpoint." + e + ".hla.federation", "TalkNotRunning-" + e);
            q.set("junction.endpoint." + e + ".hla.rtijar", jar3.getPath());
        }
        q.set("junction.router.kind", "comms");
        GatewayConfig mc = new ConfigLoader(junctions, dir).gateway(new File(junctions, "missing.junction.settings"), q);
        Gateway mg = new Gateway(mc, Collections.<String, String>emptyMap(), dir, null);
        try {
            mg.start();
        } catch (Exception expected) {
            // reported in the state below
        }
        String msg = String.valueOf(mg.getMessage());
        check("dynamic Talk: a missing federation fails, naming createfoms: " + msg, mg.getState() != Gateway.State.RUNNING
                && msg.contains("endpoint.hla.createfoms") && msg.contains("HLA Comms"));
        mg.stop();
    }

    static byte[] uuid(int fill) {
        byte[] b = new byte[16];
        Arrays.fill(b, (byte) fill);
        b[15] = 1;
        return b;
    }

    private static byte[] le(short[] s) {
        byte[] b = new byte[s.length * 2];
        for (int i = 0; i < s.length; i++) {
            b[2 * i] = (byte) s[i];
            b[2 * i + 1] = (byte) (s[i] >> 8);
        }
        return b;
    }

    private static byte[] be(short[] s) {
        byte[] b = new byte[s.length * 2];
        for (int i = 0; i < s.length; i++) {
            b[2 * i] = (byte) (s[i] >> 8);
            b[2 * i + 1] = (byte) s[i];
        }
        return b;
    }

    private static byte[] pduFrom(DatagramSocket s, int type, long millis) throws Exception {
        long end = System.currentTimeMillis() + millis;
        byte[] buf = new byte[4096];
        while (System.currentTimeMillis() < end) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                s.receive(p);
            } catch (SocketTimeoutException e) {
                continue;
            }
            if (p.getLength() >= 12 && (buf[2] & 0xFF) == type) {
                return Arrays.copyOf(buf, p.getLength());
            }
        }
        return null;
    }

    private static DisReader reader(byte[] pdu) {
        DisReader r = new DisReader(pdu, 0, pdu.length);
        r.seek(DisPduHeader.SIZE);
        return r;
    }

    private static int freePort() throws Exception {
        try (DatagramSocket s = new DatagramSocket(0)) {
            return s.getLocalPort();
        }
    }

    // ------------------------------------------------------------------ a Talk test federate

    static final class Fed implements RtiSessionListener {
        final RtiSession s;
        final String name;
        final BlockingQueue<String[]> q = new LinkedBlockingQueue<String[]>();
        final BlockingQueue<Object[]> interactions = new LinkedBlockingQueue<Object[]>();
        final Map<Integer, byte[]> values = new ConcurrentHashMap<Integer, byte[]>();
        final Map<Long, Object[]> published = new ConcurrentHashMap<Long, Object[]>();
        final Map<String, byte[]> instanceValues = new ConcurrentHashMap<String, byte[]>();

        Fed(RtiLibrary lib, String federation, String name, String fom) throws Exception {
            this.name = name;
            s = lib.createSession(RtiSession.Version.HLA_1516E);
            s.connect(ConnectSettings.defaults(), this);
            s.join(federation, "Tester", name, Arrays.asList(fom.split(";")), true);
        }

        long publishObject(String cls, String instance, HlaCodec c, InformationExchangeModel m, Map<String, HValue> attrs) throws Exception {
            int oc = s.objectClass(cls);
            int[] h = new int[attrs.size()];
            byte[][] v = new byte[attrs.size()][];
            int i = 0;
            for (Map.Entry<String, HValue> e : attrs.entrySet()) {
                h[i] = s.attribute(oc, e.getKey());
                v[i] = c.encode(m.getClassAttribute(cls, e.getKey()).getDataType(), e.getValue());
                i++;
            }
            s.publishObjectClass(oc, h);
            s.reserveName(instance);
            String[] r = take("name", 3000);
            if (r == null || !"true".equals(r[2])) {
                throw new IllegalStateException("cannot reserve " + instance);
            }
            long o = s.registerObject(oc, instance);
            s.updateAttributes(o, h, v, null);
            published.put(o, new Object[] {h, v});
            return o;
        }

        void subscribeObject(String cls, String... attrs) throws Exception {
            int oc = s.objectClass(cls);
            int[] h = new int[attrs.length];
            for (int i = 0; i < h.length; i++) {
                h[i] = s.attribute(oc, attrs[i]);
            }
            s.subscribeObjectClass(oc, h);
        }

        void subscribeMessages(InformationExchangeModel m) throws Exception {
            for (String ic : new String[] {MSG_AUDIO, MSG_TEXT}) {
                s.subscribeInteractionClass(s.interactionClass(ic));
            }
        }

        void sendAudio(InformationExchangeModel m, HlaCodec c, byte[] sender, byte[] channel, byte[] audio, int codec) throws Exception {
            Map<String, HValue> content = new LinkedHashMap<String, HValue>();
            content.put("Audio", HValue.ofBytes(audio));
            content.put("Codec", HValue.ofInt(codec));
            send(m, c, MSG_AUDIO, sender, channel, "AudioContent", HValue.ofRecord(content));
        }

        void sendText(InformationExchangeModel m, HlaCodec c, byte[] sender, byte[] channel, String text) throws Exception {
            Map<String, HValue> content = new LinkedHashMap<String, HValue>();
            content.put("Text", HValue.ofText(text));
            send(m, c, MSG_TEXT, sender, channel, "Text", HValue.ofRecord(content));
        }

        private void send(InformationExchangeModel m, HlaCodec c, String cls, byte[] sender, byte[] channel, String param, HValue v)
                throws Exception {
            int ic = s.interactionClass(cls);
            s.sendInteraction(ic, new int[] {s.parameter(ic, "SenderUUID"), s.parameter(ic, "ChannelUUID"), s.parameter(ic, param)},
                    new byte[][] {sender, channel, c.encode(m.getClassParameter(cls, param).getDataType(), v)}, null);
        }

        String[] take(String kind, long millis) throws InterruptedException {
            long end = System.currentTimeMillis() + millis;
            List<String[]> skipped = new ArrayList<String[]>();
            try {
                while (System.currentTimeMillis() < end) {
                    String[] e = q.poll(Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                    if (e == null) {
                        return null;
                    }
                    if (e[0].equals(kind)) {
                        return e;
                    }
                    skipped.add(e);
                }
                return null;
            } finally {
                q.addAll(skipped);
            }
        }

        String[] takeDiscovered(String namePrefix, long millis) throws InterruptedException {
            long end = System.currentTimeMillis() + millis;
            List<String[]> skipped = new ArrayList<String[]>();
            try {
                while (System.currentTimeMillis() < end) {
                    String[] e = q.poll(Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                    if (e == null) {
                        return null;
                    }
                    if (e[0].equals("discovered") && e[3].startsWith(namePrefix)) {
                        return e;
                    }
                    skipped.add(e);
                }
                return null;
            } finally {
                q.addAll(skipped);
            }
        }

        /** The next interaction of the class, parameters by name. */
        Map<String, byte[]> takeInteraction(String cls, long millis) throws Exception {
            int want = s.interactionClass(cls);
            long end = System.currentTimeMillis() + millis;
            while (System.currentTimeMillis() < end) {
                Object[] e = interactions.poll(Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                if (e == null) {
                    return null;
                }
                if ((Integer) e[0] != want) {
                    continue;
                }
                int[] ps = (int[]) e[1];
                byte[][] vs = (byte[][]) e[2];
                Map<String, byte[]> m = new LinkedHashMap<String, byte[]>();
                for (String n : new String[] {"SenderUUID", "ChannelUUID", "AudioContent", "Text", "Frequency"}) {
                    int h;
                    try {
                        h = s.parameter(want, n);
                    } catch (Exception x) {
                        continue;
                    }
                    for (int i = 0; i < ps.length; i++) {
                        if (ps[i] == h) {
                            m.put(n, vs[i]);
                        }
                    }
                }
                return m;
            }
            return null;
        }

        byte[] waitValue(long instance, int attr, long millis) {
            long end = System.currentTimeMillis() + millis;
            while (System.currentTimeMillis() < end) {
                byte[] v = instanceValues.get(instance + "/" + attr);
                if (v != null) {
                    return v;
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    return null;
                }
            }
            return null;
        }

        @Override
        public void discovered(long instance, int objectClass, String n, String producer) {
            q.add(new String[] {"discovered", String.valueOf(instance), String.valueOf(objectClass), n});
        }

        @Override
        public void reflected(long instance, int[] attributes, byte[][] vals, byte[] tag, String producer) {
            for (int i = 0; i < attributes.length; i++) {
                values.put(attributes[i], vals[i]);
                instanceValues.put(instance + "/" + attributes[i], vals[i]);
            }
        }

        @Override
        public void removed(long instance, byte[] tag, String producer) {
            q.add(new String[] {"removed", String.valueOf(instance)});
        }

        @Override
        public void received(int interactionClass, int[] parameters, byte[][] vals, byte[] tag, String producer) {
            interactions.add(new Object[] {interactionClass, parameters, vals});
        }

        @Override
        public void provideRequested(long instance, int[] attributes, byte[] tag) {
            final Object[] pv = published.get(instance);
            if (pv != null) {
                new Thread(() -> {
                    try {
                        s.updateAttributes(instance, (int[]) pv[0], (byte[][]) pv[1], null);
                    } catch (Exception e) {
                        System.out.println("provide failed: " + e);
                    }
                }).start();
            }
        }

        @Override
        public void nameReserved(String n, boolean success) {
            q.add(new String[] {"name", n, String.valueOf(success)});
        }

        @Override
        public void connectionLost(String reason) {
        }
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what);
        }
    }
}
