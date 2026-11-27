package com.simweaver.junction;

import com.simweaver.common.config.SimwProperties;
import com.simweaver.common.dis.DisEntityId;
import com.simweaver.common.dis.DisEntityState;
import com.simweaver.common.dis.DisEntityType;
import com.simweaver.common.dis.DisPduHeader;
import com.simweaver.common.dis.DisReader;
import com.simweaver.common.dis.DisToRpr;
import com.simweaver.common.dis.DisWriter;
import com.simweaver.common.hla.codec.HValue;
import com.simweaver.common.hla.codec.HlaCodec;
import com.simweaver.common.hla.session.ConnectSettings;
import com.simweaver.common.hla.session.RtiLibrary;
import com.simweaver.common.hla.session.RtiSession;
import com.simweaver.common.hla.session.RtiSessionListener;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.junction.config.ConfigLoader;
import com.simweaver.junction.config.EndpointConfig;
import com.simweaver.junction.config.GatewayConfig;
import com.simweaver.junction.endpoint.Endpoint;
import com.simweaver.junction.endpoint.EndpointListener;
import com.simweaver.junction.endpoint.EndpointState;
import com.simweaver.junction.endpoint.Interest;
import com.simweaver.junction.endpoint.NameCandidates;
import com.simweaver.junction.endpoint.dis.DisEndpoint;

import java.io.File;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * End-to-end test of a gateway between an IEEE 1516-2010 federation (RPR v2, test RTI) and a
 * DIS 7 network on the loopback interface: the gateway listens on one UDP port and sends to a
 * "DIS simulator" socket of the test on another ({@code endpoint.dis.sendaddress}).
 *
 * <pre>java -cp out/classes:out/test com.simweaver.junction.DisBridgeSelfTest &lt;fom root&gt; &lt;fake rti folder&gt;</pre>
 */
public final class DisBridgeSelfTest {

    private static final String AIRCRAFT = "HLAobjectRoot.BaseEntity.PhysicalEntity.Platform.Aircraft";
    private static final String GROUND = "HLAobjectRoot.BaseEntity.PhysicalEntity.Platform.GroundVehicle";
    private static final String TX = "HLAobjectRoot.EmbeddedSystem.RadioTransmitter";
    private static final String FIRE = "HLAinteractionRoot.WeaponFire";
    private static final String AUDIO = "HLAinteractionRoot.RadioSignal.EncodedAudioRadioSignal";
    private static final String EMITTER = "HLAobjectRoot.EmbeddedSystem.EmitterSystem";
    private static final String RADAR = "HLAobjectRoot.EmitterBeam.RadarBeam";
    private static final String JAMMER = "HLAobjectRoot.EmitterBeam.JammerBeam";
    private static final String TRANSPONDER = "HLAobjectRoot.EmbeddedSystem.IFF.NatoIFF.NatoIFFTransponder";
    private static final String DESIGNATOR = "HLAobjectRoot.EmbeddedSystem.Designator";
    private static int checks;
    private static int failures;

    private DisBridgeSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        File fomRoot = new File(args.length > 0 ? args[0] : "simwrift/rtdir/fom").getAbsoluteFile();
        File rtiDir = new File(args.length > 1 ? args[1] : "fakerti/build").getAbsoluteFile();
        bridge(fomRoot, rtiDir);
        emitters(fomRoot, rtiDir);
        iffAndDesignator(fomRoot, rtiDir);
        echo(fomRoot);
        emittersBeforeDeclare(fomRoot);
        takenName(fomRoot, rtiDir);
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ HLA <-> DIS

    private static void bridge(File fomRoot, File rtiDir) throws Exception {
        String rpr2 = new File(fomRoot, "ieee1516-2010/RPR_FOM_v2.0_Link16_1516-2010.xml").getPath();
        File jar3 = new File(rtiDir, "fakerti-1516e.jar");
        int gatewayPort = freePort();
        DatagramSocket sim = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        sim.setSoTimeout(200);

        File dir = Files.createTempDirectory("junction-dis-test").toFile();
        File junctions = new File(dir, "etc/junctions");
        junctions.mkdirs();
        SimwProperties p = new SimwProperties();
        p.set("junction.name", "HLA - DIS");
        p.set("junction.endpoints", "hla;dis");
        p.set("junction.endpoint.hla.standard", "ieee1516-2010");
        p.set("junction.endpoint.hla.hla.foms", rpr2);
        p.set("junction.endpoint.hla.hla.federation", "Blue");
        p.set("junction.endpoint.hla.hla.rtijar", jar3.getPath());
        p.set("junction.endpoint.dis.standard", "dis7");
        p.set("junction.endpoint.dis.hla.foms", rpr2);
        p.set("junction.endpoint.dis.dis.interface", "127.0.0.1");
        p.set("junction.endpoint.dis.dis.port", String.valueOf(gatewayPort));
        p.set("junction.endpoint.dis.dis.exid", "3");
        p.set("junction.endpoint.dis.dis.sendaddress", "127.0.0.1:" + sim.getLocalPort());
        p.set("junction.endpoint.dis.dis.heartbeatsecs", "1");
        p.set("junction.endpoint.dis.dis.entitytimeoutsecs", "2");
        GatewayConfig gc = new ConfigLoader(junctions, dir).gateway(new File(junctions, "dis.junction.settings"), p);
        check("config valid " + gc.getErrors(), gc.isValid());

        RtiLibrary lib = RtiLibrary.isolated(Collections.singletonList(jar3));
        InformationExchangeModel m = InformationExchangeModel.fromHlaEvolved(Collections.singletonList(rpr2));
        HlaCodec codec = new HlaCodec(m);

        Gateway gw = new Gateway(gc, Collections.<String, String>emptyMap(), dir, null);
        gw.start();
        check("gateway running: " + gw.getState(), gw.getState() == Gateway.State.RUNNING);

        // ---- HLA -> DIS: an aircraft with an EntityIdentifier keeps it in DIS
        Fed blue = new Fed(lib, "Blue", "BlueSim", rpr2);
        int ac = blue.s.objectClass(AIRCRAFT);
        String[] names = {"EntityIdentifier", "EntityType", "Spatial", "Marking", "ForceIdentifier"};
        int[] h = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            h[i] = blue.s.attribute(ac, names[i]);
        }
        blue.s.publishObjectClass(ac, h);
        blue.reserve("Viper-1");
        long viper = blue.s.registerObject(ac, "Viper-1");
        byte[][] vals = {
            encode(codec, m, AIRCRAFT, "EntityIdentifier", entityId(5, 6, 7)),
            encode(codec, m, AIRCRAFT, "EntityType", entityType(1, 2, 225, 1, 3, 0, 0)),
            encode(codec, m, AIRCRAFT, "Spatial", spatial(codec, m, 3100000.5, 1000000.25, 5400000.125)),
            encode(codec, m, AIRCRAFT, "Marking", marking("VIPER1")),
            encode(codec, m, AIRCRAFT, "ForceIdentifier", HValue.ofInt(1))};
        blue.s.updateAttributes(viper, h, vals, null);

        DisEntityState es = entityStateFrom(sim, s -> s.id.equals(new DisEntityId(5, 6, 7)), 4000);
        check("DIS gets the aircraft as 5-6-7", es != null);
        if (es != null) {
            check("location", es.location[0] == 3100000.5 && es.location[1] == 1000000.25 && es.location[2] == 5400000.125);
            check("type", es.type.kind == 1 && es.type.domain == 2 && es.type.country == 225 && es.type.subcategory == 3);
            check("marking", new String(es.marking, 0, 6, "US-ASCII").equals("VIPER1"));
            check("force", es.forceId == 1);
            check("not deactivated", !es.isDeactivated());
        }
        check("heartbeat without updates", entityStateFrom(sim, s -> s.id.equals(new DisEntityId(5, 6, 7)), 2500) != null);

        // an aircraft without an EntityIdentifier gets one of the endpoint (900-1-n)
        blue.reserve("Viper-2");
        long viper2 = blue.s.registerObject(ac, "Viper-2");
        blue.s.updateAttributes(viper2, new int[] {h[1], h[2]}, new byte[][] {vals[1], vals[2]}, null);
        DisEntityState es2 = entityStateFrom(sim, s -> s.id.site == 900 && s.id.application == 1, 4000);
        check("an aircraft without EntityIdentifier gets 900-1-n", es2 != null);

        // a radio transmitter on Viper-1, and audio on it
        int tx = blue.s.objectClass(TX);
        String[] tn = {"HostObjectIdentifier", "RadioIndex", "Frequency", "StreamTag", "TransmitterOperationalStatus"};
        int[] th = new int[tn.length];
        for (int i = 0; i < tn.length; i++) {
            th[i] = blue.s.attribute(tx, tn[i]);
        }
        blue.s.publishObjectClass(tx, th);
        blue.reserve("Viper-1-radio");
        long radio = blue.s.registerObject(tx, "Viper-1-radio");
        blue.s.updateAttributes(radio, th, new byte[][] {
            encode(codec, m, TX, "HostObjectIdentifier", HValue.ofText("Viper-1")),
            encode(codec, m, TX, "RadioIndex", HValue.ofInt(2)),
            encode(codec, m, TX, "Frequency", HValue.ofInt(251_000_000L)),
            encode(codec, m, TX, "StreamTag", HValue.ofInt(777)),
            encode(codec, m, TX, "TransmitterOperationalStatus", HValue.ofInt(2))}, null);
        byte[] tpdu = pduFrom(sim, DisPduHeader.TRANSMITTER, 4000);
        check("DIS gets the transmitter", tpdu != null);
        if (tpdu != null) {
            DisReader r = reader(tpdu);
            DisEntityId host = DisEntityId.read(r);
            int rid = r.u16();
            r.seek(DisPduHeader.SIZE + 60);
            long freq = r.i64();
            check("transmitter on host 5-6-7, radio 2, 251 MHz", host.equals(new DisEntityId(5, 6, 7)) && rid == 2 && freq == 251_000_000L);
        }
        int audio = blue.s.interactionClass(AUDIO);
        blue.s.publishInteractionClass(audio);
        int pAudio = blue.s.parameter(audio, "AudioData");
        byte[] pcm = {10, 20, 30, 40, 50, 60, 70, 80};
        Map<String, HValue> ad = new LinkedHashMap<String, HValue>();
        ad.put("StreamTag", HValue.ofInt(777));
        ad.put("EncodingType", HValue.ofInt(4));
        ad.put("SampleRate", HValue.ofInt(8000));
        ad.put("DataLength", HValue.ofInt(64));
        ad.put("SampleCount", HValue.ofInt(4));
        ad.put("Data", HValue.ofBytes(pcm));
        blue.s.sendInteraction(audio, new int[] {pAudio}, new byte[][] {
            codec.encode(m.getClassParameter(AUDIO, "AudioData").getDataType(), HValue.ofRecord(ad))}, null);
        byte[] spdu = pduFrom(sim, DisPduHeader.SIGNAL, 3000);
        check("DIS gets the audio as a Signal PDU", spdu != null);
        if (spdu != null) {
            DisReader r = reader(spdu);
            DisEntityId host = DisEntityId.read(r);
            int rid = r.u16();
            int scheme = r.u16();
            r.skip(2);
            long rate = r.u32();
            int bits = r.u16();
            r.skip(2);
            check("signal of 5-6-7 radio 2 (found by StreamTag), PCM16 at 8 kHz, 64 bits",
                    host.equals(new DisEntityId(5, 6, 7)) && rid == 2 && scheme == 4 && rate == 8000 && bits == 64);
            check("audio data", java.util.Arrays.equals(r.bytes(8), pcm));
        }

        // ---- Link 16 (SISO-STD-002-2021): HLA JTIDSMessageRadioSignal <-> DIS Signal PDU, TDL type 100
        String jtids = DisToRpr.LINK16_CLASSES[0];
        int jc = blue.s.interactionClass(jtids);
        blue.s.publishInteractionClass(jc);
        blue.s.subscribeInteractionClass(jc);
        byte[] hw = {0x11, 0x0F, 0x22, 0x33, 0x00, 0x00};
        byte[] w1 = {0x08, 0x09, 1, 2, 3, 4, 5, 6, 7, 0};                         // J2.2-ish initial word, then one more
        byte[] w2 = {0x02, 9, 8, 7, 6, 5, 4, 3, 2, 0};
        Map<String, HValue> head = new LinkedHashMap<String, HValue>();
        head.put("Data", HValue.ofBytes(hw));
        Map<String, HValue> msg = new LinkedHashMap<String, HValue>();
        msg.put("Data", HValue.ofArray(java.util.Arrays.asList(HValue.ofBytes(w1), HValue.ofBytes(w2))));
        String[] jn = {"HostRadioIndex", "NPGNumber", "NetNumber", "TimeSlotID", "JTIDSHeader", "TADILJMessage"};
        HValue[] jv = {HValue.ofText("Viper-1-radio"), HValue.ofInt(7), HValue.ofInt(3), HValue.ofInt(0x01001234L),
            HValue.ofRecord(head), HValue.ofRecord(msg)};
        int[] jh = new int[jn.length];
        byte[][] jb = new byte[jn.length][];
        for (int i = 0; i < jn.length; i++) {
            jh[i] = blue.s.parameter(jc, jn[i]);
            jb[i] = codec.encode(m.getClassParameter(jtids, jn[i]).getDataType(), jv[i]);
        }
        blue.s.sendInteraction(jc, jh, jb, null);
        byte[] lpdu = pduFrom(sim, DisPduHeader.SIGNAL, 3000);
        check("DIS gets the Link 16 message as a Signal PDU", lpdu != null);
        if (lpdu != null) {
            DisReader r = reader(lpdu);
            DisEntityId host = DisEntityId.read(r);
            int rid = r.u16();
            int scheme = r.u16();
            int tdl = r.u16();
            r.skip(4);
            int bits = r.u16();
            r.skip(2);
            byte[] d = r.bytes(Math.min(46, r.remaining()));
            check("Link 16: radio 5-6-7/2, raw binary with 2 words, TDL 100, 368 bits", host.equals(new DisEntityId(5, 6, 7)) && rid == 2
                    && scheme == ((1 << 14) | 2) && tdl == 100 && bits == 160 + 48 + 160);
            check("Link 16: network header NPG 7, net 3, CVLLs 255, type 0, time slot; transmit time no statement",
                    d.length == 46 && d[0] == 0 && d[1] == 7 && d[2] == 3 && (d[3] & 0xFF) == 255 && (d[4] & 0xFF) == 255 && d[5] == 0
                    && d[8] == 1 && d[10] == 0x12 && d[11] == 0x34 && (d[12] & 0xFF) == 255 && (d[19] & 0xFF) == 255);
            check("Link 16: header word and J-words copied", d.length == 46 && java.util.Arrays.equals(java.util.Arrays.copyOfRange(d, 20, 26), hw)
                    && java.util.Arrays.equals(java.util.Arrays.copyOfRange(d, 26, 36), w1) && java.util.Arrays.equals(java.util.Arrays.copyOfRange(d, 36, 46), w2));
        }
        // and DIS -> HLA: a Link 16 Signal PDU of the DIS radio 1-2-3/1 arrives as a JTIDSMessageRadioSignal
        DisWriter lw = new DisWriter(80).header(7, 3, DisPduHeader.SIGNAL, 4, 0, 0);
        lw.entityId(new DisEntityId(1, 2, 3)).u16(1).u16((1 << 14) | 1).u16(100).u32(0).u16(160 + 48 + 80).u16(0);
        lw.u16(6).u8(1).u8(255).u8(255).u8(0).u8(1).u8(0).u32(0xFFFFFFFFL).u32(0xFFFFFFFFL).u32(0xFFFFFFFFL);
        lw.bytes(hw).bytes(w2).zeros(2);
        byte[] lin = lw.finish();
        sim.send(new DatagramPacket(lin, lin.length, new InetSocketAddress(InetAddress.getLoopbackAddress(), gatewayPort)));
        String[] lr = null;
        for (int i = 0; i < 5 && lr == null; i++) {
            String[] e = blue.take("received", 3000);
            if (e == null) break;
            if (e[1].equals(String.valueOf(jc))) lr = e;
        }
        check("HLA receives the DIS Link 16 message as JTIDSMessageRadioSignal", lr != null);
        if (lr != null) {
            Map<Integer, byte[]> pv = blue.lastInteraction;
            byte[] tj = pv.get(blue.s.parameter(jc, "TADILJMessage"));
            byte[] npg = pv.get(blue.s.parameter(jc, "NPGNumber"));
            check("with NPG 6, its header word and its J-word", npg != null && npg[1] == 6 && tj != null && java.util.Arrays.equals(tj, w2)
                    && java.util.Arrays.equals(pv.get(blue.s.parameter(jc, "JTIDSHeader")), hw));
            byte[] host = pv.get(blue.s.parameter(jc, "HostRadioIndex"));
            check("HostRadioIndex names the DIS radio 1-2-3/radio-1", host != null
                    && codec.decode(m.getClassParameter(jtids, "HostRadioIndex").getDataType(), host).asTrimmedText().equals("1-2-3/radio-1"));
        }

        // ---- DIS -> HLA: a tank on the DIS network
        int gv = blue.s.objectClass(GROUND);
        int gSpatial = blue.s.attribute(gv, "Spatial");
        int gMarking = blue.s.attribute(gv, "Marking");
        blue.s.subscribeObjectClass(gv, new int[] {gSpatial, gMarking});
        int fire = blue.s.interactionClass(FIRE);
        blue.s.subscribeInteractionClass(fire);
        blue.s.publishInteractionClass(fire);
        InetSocketAddress gwAddr = new InetSocketAddress(InetAddress.getLoopbackAddress(), gatewayPort);
        byte[] tank = tankPdu(3, 0);
        sim.send(new DatagramPacket(tank, tank.length, gwAddr));
        String[] d = blue.take("discovered", 4000);
        check("HLA discovers the DIS tank as 1-2-3", d != null && "1-2-3".equals(d[3]) && d[2].equals(String.valueOf(gv)));
        check("and gets its marking", blue.waitValue(gMarking, 3000) != null);
        byte[] other = tankPdu(4, 0);                                  // exercise 4: not ours
        sim.send(new DatagramPacket(other, other.length, gwAddr));

        // a Fire PDU from DIS at the aircraft: the target is the HLA object's name
        DisWriter fw = new DisWriter(96).header(7, 3, 2, 2, 0, 0);
        fw.entityId(new DisEntityId(1, 2, 3)).entityId(new DisEntityId(5, 6, 7)).entityId(new DisEntityId(0, 0, 0));
        fw.u16(1).u16(2).u16(9).u32(0).f64(1).f64(2).f64(3);
        fw.entityType(new DisEntityType(2, 9, 225, 2, 1, 0, 0)).u16(1000).u16(1000).u16(1).u16(0);
        fw.f32(1).f32(2).f32(3).f32(1000);
        byte[] firePdu = fw.finish();
        sim.send(new DatagramPacket(firePdu, firePdu.length, gwAddr));
        String[] rx = blue.take("received", 4000);
        check("HLA receives WeaponFire", rx != null);
        if (rx != null) {
            Map<Integer, byte[]> pv = blue.lastInteraction;
            String target = codec.decode(m.getClassParameter(FIRE, "TargetObjectIdentifier").getDataType(),
                    pv.get(blue.s.parameter(fire, "TargetObjectIdentifier"))).asTrimmedText();
            String firing = codec.decode(m.getClassParameter(FIRE, "FiringObjectIdentifier").getDataType(),
                    pv.get(blue.s.parameter(fire, "FiringObjectIdentifier"))).asTrimmedText();
            check("target 5-6-7 is Viper-1 in HLA (got " + target + ")", "Viper-1".equals(target));
            check("firing entity is the DIS tank 1-2-3 (got " + firing + ")", "1-2-3".equals(firing));
        }

        // HLA fires at the DIS tank: the Fire PDU names 1-2-3 as the target, 5-6-7 as the shooter
        int pFiring = blue.s.parameter(fire, "FiringObjectIdentifier");
        int pTarget = blue.s.parameter(fire, "TargetObjectIdentifier");
        blue.s.sendInteraction(fire, new int[] {pFiring, pTarget}, new byte[][] {
            codec.encode(m.getClassParameter(FIRE, "FiringObjectIdentifier").getDataType(), HValue.ofText("Viper-1")),
            codec.encode(m.getClassParameter(FIRE, "TargetObjectIdentifier").getDataType(), HValue.ofText("1-2-3"))}, null);
        byte[] fpdu = pduFrom(sim, DisPduHeader.FIRE, 3000);
        check("DIS gets the Fire PDU", fpdu != null);
        if (fpdu != null) {
            DisReader r = reader(fpdu);
            DisEntityId f = DisEntityId.read(r);
            DisEntityId t = DisEntityId.read(r);
            check("shooter 5-6-7, target 1-2-3", f.equals(new DisEntityId(5, 6, 7)) && t.equals(new DisEntityId(1, 2, 3)));
            check("exercise 3", (fpdu[1] & 0xFF) == 3);
        }
        check("the gateway's own copy of the tank is not sent back to DIS",
                entityStateFrom(sim, s -> s.id.equals(new DisEntityId(1, 2, 3)), 1500) == null);

        // the tank goes quiet: it times out
        check("the tank times out in HLA", blue.take("removed", 5000) != null);

        // removing the aircraft sends it deactivated
        blue.s.deleteObject(viper, null);
        DisEntityState gone = entityStateFrom(sim, s -> s.id.equals(new DisEntityId(5, 6, 7)) && s.isDeactivated(), 3000);
        check("removed aircraft sent deactivated", gone != null);
        byte[] off = pduFrom(sim, DisPduHeader.TRANSMITTER, 2500);
        check("transmitter still heard (its object remains)", off != null);

        System.out.println("gateway: " + com.simweaver.common.json.Json.toJson(gw));
        gw.stop();
        check("gateway stopped", gw.getState() == Gateway.State.STOPPED);
        blue.s.close();
        sim.close();
    }

    // ------------------------------------------------------------------ emitters both ways

    /**
     * EmitterSystem, RadarBeam and JammerBeam objects to Electromagnetic Emission PDUs and back:
     * names, references, values, beams switched off, systems removed, heartbeats.
     */
    private static void emitters(File fomRoot, File rtiDir) throws Exception {
        String rpr2 = new File(fomRoot, "ieee1516-2010/RPR_FOM_v2.0_Link16_1516-2010.xml").getPath();
        File jar3 = new File(rtiDir, "fakerti-1516e.jar");
        int gatewayPort = freePort();
        DatagramSocket sim = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        sim.setSoTimeout(200);
        File dir = Files.createTempDirectory("junction-ee-test").toFile();
        File junctions = new File(dir, "etc/junctions");
        junctions.mkdirs();
        SimwProperties p = new SimwProperties();
        p.set("junction.name", "HLA - DIS emitters");
        p.set("junction.endpoints", "hla;dis");
        p.set("junction.endpoint.hla.standard", "ieee1516-2010");
        p.set("junction.endpoint.hla.hla.foms", rpr2);
        p.set("junction.endpoint.hla.hla.federation", "Green");
        p.set("junction.endpoint.hla.hla.rtijar", jar3.getPath());
        p.set("junction.endpoint.dis.standard", "dis7");
        p.set("junction.endpoint.dis.hla.foms", rpr2);
        p.set("junction.endpoint.dis.dis.interface", "127.0.0.1");
        p.set("junction.endpoint.dis.dis.port", String.valueOf(gatewayPort));
        p.set("junction.endpoint.dis.dis.exid", "3");
        p.set("junction.endpoint.dis.dis.sendaddress", "127.0.0.1:" + sim.getLocalPort());
        p.set("junction.endpoint.dis.dis.heartbeatsecs", "1");
        p.set("junction.endpoint.dis.dis.entitytimeoutsecs", "5");
        GatewayConfig gc = new ConfigLoader(junctions, dir).gateway(new File(junctions, "ee.junction.settings"), p);
        RtiLibrary lib = RtiLibrary.isolated(Collections.singletonList(jar3));
        InformationExchangeModel m = InformationExchangeModel.fromHlaEvolved(Collections.singletonList(rpr2));
        HlaCodec codec = new HlaCodec(m);
        Gateway gw = new Gateway(gc, Collections.<String, String>emptyMap(), dir, null);
        gw.start();
        check("emitter gateway running: " + gw.getState(), gw.getState() == Gateway.State.RUNNING);
        Fed green = new Fed(lib, "Green", "GreenSim", rpr2);

        // ---- HLA -> DIS: an aircraft with a radar system, a search beam and a jamming beam
        int ac = green.s.objectClass(AIRCRAFT);
        int[] ah = {green.s.attribute(ac, "EntityIdentifier"), green.s.attribute(ac, "EntityType"), green.s.attribute(ac, "Spatial")};
        green.s.publishObjectClass(ac, ah);
        green.reserve("Hawk");
        long hawk = green.s.registerObject(ac, "Hawk");
        green.s.updateAttributes(hawk, ah, new byte[][] {
            encode(codec, m, AIRCRAFT, "EntityIdentifier", entityId(5, 6, 8)),
            encode(codec, m, AIRCRAFT, "EntityType", entityType(1, 2, 225, 1, 3, 0, 0)),
            encode(codec, m, AIRCRAFT, "Spatial", spatial(codec, m, 3100000, 1000000, 5400000))}, null);
        check("DIS gets the aircraft 5-6-8", entityStateFrom(sim, x -> x.id.equals(new DisEntityId(5, 6, 8)), 4000) != null);

        int es = green.s.objectClass(EMITTER);
        String[] en = {"HostObjectIdentifier", "EmitterType", "EmitterFunctionCode", "EmitterIndex", "RelativePosition"};
        int[] eh = handles(green, es, en);
        green.s.publishObjectClass(es, eh);
        int rb = green.s.objectClass(RADAR);
        String[] rn = {"EmitterSystemIdentifier", "BeamIdentifier", "BeamFunctionCode", "BeamAzimuthCenter", "BeamAzimuthSweep",
            "EmissionFrequency", "EffectiveRadiatedPower", "TrackObjectIdentifiers"};
        int[] rh = handles(green, rb, rn);
        green.s.publishObjectClass(rb, rh);
        int jb = green.s.objectClass(JAMMER);
        String[] jn = {"EmitterSystemIdentifier", "BeamIdentifier", "BeamFunctionCode", "BeamAzimuthCenter", "JammingModeSequence"};
        int[] jh = handles(green, jb, jn);
        green.s.publishObjectClass(jb, jh);

        green.reserve("Hawk-radar");
        long sysObj = green.s.registerObject(es, "Hawk-radar");
        Map<String, HValue> rel = new LinkedHashMap<String, HValue>();
        rel.put("BodyXDistance", HValue.ofFloat(1));
        rel.put("BodyYDistance", HValue.ofFloat(0));
        rel.put("BodyZDistance", HValue.ofFloat(-2));
        green.s.updateAttributes(sysObj, eh, new byte[][] {
            encode(codec, m, EMITTER, "HostObjectIdentifier", HValue.ofText("Hawk")),
            encode(codec, m, EMITTER, "EmitterType", HValue.ofInt(9200)),
            encode(codec, m, EMITTER, "EmitterFunctionCode", HValue.ofInt(2)),
            encode(codec, m, EMITTER, "EmitterIndex", HValue.ofInt(1)),
            encode(codec, m, EMITTER, "RelativePosition", HValue.ofRecord(rel))}, null);
        green.reserve("Hawk-search");
        long search = green.s.registerObject(rb, "Hawk-search");
        green.s.updateAttributes(search, rh, new byte[][] {
            encode(codec, m, RADAR, "EmitterSystemIdentifier", HValue.ofText("Hawk-radar")),
            encode(codec, m, RADAR, "BeamIdentifier", HValue.ofInt(1)),
            encode(codec, m, RADAR, "BeamFunctionCode", HValue.ofInt(1)),
            encode(codec, m, RADAR, "BeamAzimuthCenter", HValue.ofFloat(0.5)),
            encode(codec, m, RADAR, "BeamAzimuthSweep", HValue.ofFloat(0.25)),
            encode(codec, m, RADAR, "EmissionFrequency", HValue.ofFloat(3.1e9)),
            encode(codec, m, RADAR, "EffectiveRadiatedPower", HValue.ofFloat(70)),
            encode(codec, m, RADAR, "TrackObjectIdentifiers", HValue.ofArray(Collections.singletonList(HValue.ofText("1-2-9"))))}, null);
        green.reserve("Hawk-jam");
        long jam = green.s.registerObject(jb, "Hawk-jam");
        green.s.updateAttributes(jam, jh, new byte[][] {
            encode(codec, m, JAMMER, "EmitterSystemIdentifier", HValue.ofText("Hawk-radar")),
            encode(codec, m, JAMMER, "BeamIdentifier", HValue.ofInt(2)),
            encode(codec, m, JAMMER, "BeamFunctionCode", HValue.ofInt(12)),
            encode(codec, m, JAMMER, "BeamAzimuthCenter", HValue.ofFloat(-0.5)),
            encode(codec, m, JAMMER, "JammingModeSequence", HValue.ofInt(0x01020304L))}, null);

        DisToRpr.Emission e = emissionFrom(sim, x -> x.systems.size() == 1 && beams(x, "5-6-8/emitter-1") == 2, 4000);
        check("DIS gets an Emission PDU of 5-6-8 with one system and two beams", e != null);
        if (e != null) {
            check("host 5-6-8", e.host.equals(new DisEntityId(5, 6, 8)));
            Map<String, byte[]> sa = e.systems.get(0).attributes;
            check("system: emitter 9200, function 2, index 1", u16(sa.get("EmitterType")) == 9200 && sa.get("EmitterFunctionCode")[0] == 2
                    && sa.get("EmitterIndex")[0] == 1);
            check("system: relative position", f32(sa.get("RelativePosition"), 0) == 1f && f32(sa.get("RelativePosition"), 8) == -2f);
            DisToRpr.Embedded b1 = e.beams.get("5-6-8/emitter-1").get(0), b2 = e.beams.get("5-6-8/emitter-1").get(1);
            check("beam 1 a RadarBeam, azimuth 0.5 ± 0.25 rad, 3.1 GHz, 70 dBm", b1.className.equals(DisToRpr.RADAR_BEAM)
                    && f32(b1.attributes.get("BeamAzimuthCenter"), 0) == 0.5f && f32(b1.attributes.get("BeamAzimuthSweep"), 0) == 0.25f
                    && f32(b1.attributes.get("EmissionFrequency"), 0) == 3.1e9f && f32(b1.attributes.get("EffectiveRadiatedPower"), 0) == 70f);
            byte[] tracks = b1.attributes.get("TrackObjectIdentifiers");
            check("beam 1 tracks 1-2-9", tracks != null && java.nio.ByteBuffer.wrap(tracks).getInt() == 1
                    && new String(tracks, 4, 5, "US-ASCII").equals("1-2-9"));
            check("beam 2 a JammerBeam with jamming technique 0x01020304", b2.className.equals(DisToRpr.JAMMER_BEAM)
                    && java.nio.ByteBuffer.wrap(b2.attributes.get("JammingModeSequence")).getInt() == 0x01020304);
        }
        check("Emission heartbeat (state update indicator 0)", emissionFrom(sim, x -> x.complete && x.systems.size() == 1, 2500) != null);

        green.s.deleteObject(jam, null);
        check("jammer removed: the next PDU has the search beam only",
                emissionFrom(sim, x -> x.systems.size() == 1 && beams(x, "5-6-8/emitter-1") == 1, 2500) != null);
        green.s.deleteObject(sysObj, null);
        check("system removed: an Emission PDU with no systems", emissionFrom(sim, x -> x.systems.isEmpty() && x.complete, 2500) != null);
        green.s.deleteObject(search, null);
        green.s.deleteObject(hawk, null);

        // ---- DIS -> HLA: a DIS tank with emitter 4, a search beam and a jamming beam
        String[] esn = {"HostObjectIdentifier", "EmitterType", "EmitterIndex"};
        int[] esh = handles(green, es, esn);
        green.s.subscribeObjectClass(es, esh);
        int[] rbh = handles(green, rb, new String[] {"EmitterSystemIdentifier", "BeamFunctionCode", "BeamAzimuthCenter"});
        green.s.subscribeObjectClass(rb, rbh);
        int[] jbh = handles(green, jb, new String[] {"EmitterSystemIdentifier", "JammingModeSequence"});
        green.s.subscribeObjectClass(jb, jbh);
        sleep(300);
        InetSocketAddress gwAddr = new InetSocketAddress(InetAddress.getLoopbackAddress(), gatewayPort);
        byte[] tank = tankPdu(3, 0);
        sim.send(new DatagramPacket(tank, tank.length, gwAddr));
        byte[] ee = eePdu(true, new int[][] {{1, 1}, {2, 12}});
        sim.send(new DatagramPacket(ee, ee.length, gwAddr));
        Map<String, String[]> found = new LinkedHashMap<String, String[]>();
        for (int i = 0; i < 3; i++) {
            String[] d = green.take("discovered", 4000);
            if (d != null) found.put(d[3], d);
        }
        String[] dSys = found.get("1-2-3/emitter-4"), dRadar = found.get("1-2-3/emitter-4/beam-1"), dJam = found.get("1-2-3/emitter-4/beam-2");
        check("HLA discovers EmitterSystem 1-2-3/emitter-4 " + found.keySet(), dSys != null && dSys[2].equals(String.valueOf(es)));
        check("and RadarBeam .../beam-1", dRadar != null && dRadar[2].equals(String.valueOf(rb)));
        check("and JammerBeam .../beam-2", dJam != null && dJam[2].equals(String.valueOf(jb)));
        if (dSys != null && dRadar != null && dJam != null) {
            long iSys = Long.parseLong(dSys[1]), iRadar = Long.parseLong(dRadar[1]), iJam = Long.parseLong(dJam[1]);
            byte[] host = green.waitValue(iSys, esh[0], 3000), type = green.waitValue(iSys, esh[1], 3000);
            check("system: hosted by 1-2-3, emitter 9300", host != null && "1-2-3".equals(text(codec, m, EMITTER, "HostObjectIdentifier", host))
                    && type != null && codec.decode(m.getClassAttribute(EMITTER, "EmitterType").getDataType(), type).asLong() == 9300);
            byte[] ref = green.waitValue(iRadar, rbh[0], 3000), az = green.waitValue(iRadar, rbh[2], 3000);
            check("radar beam: on 1-2-3/emitter-4, azimuth 0.75 rad", ref != null
                    && "1-2-3/emitter-4".equals(text(codec, m, RADAR, "EmitterSystemIdentifier", ref)) && az != null
                    && Math.abs(codec.decode(m.getClassAttribute(RADAR, "BeamAzimuthCenter").getDataType(), az).asDouble() - 0.75) < 1e-6);
            byte[] jms = green.waitValue(iJam, jbh[1], 3000);
            check("jammer beam: jamming 0x0A0B0C0D", jms != null
                    && codec.decode(m.getClassAttribute(JAMMER, "JammingModeSequence").getDataType(), jms).asLong() == 0x0A0B0C0DL);
            byte[] ee2 = eePdu(false, new int[][] {{1, 1}});                    // the jamming beam switched off
            sim.send(new DatagramPacket(ee2, ee2.length, gwAddr));
            String[] r1 = green.take("removed", 3000);
            check("jamming beam switched off: removed in HLA", r1 != null && r1[1].equals(dJam[1]));
            byte[] ee3 = eePdu(true, null);                                       // heartbeat with no systems
            sim.send(new DatagramPacket(ee3, ee3.length, gwAddr));
            String[] r2 = green.take("removed", 3000), r3 = green.take("removed", 3000);
            java.util.Set<String> rem = new java.util.HashSet<String>();
            if (r2 != null) rem.add(r2[1]);
            if (r3 != null) rem.add(r3[1]);
            check("heartbeat without systems: system and radar beam removed", rem.contains(dSys[1]) && rem.contains(dRadar[1]));
            check("the gateway does not echo the DIS emitters back to DIS", emissionFrom(sim, x -> x.host.equals(new DisEntityId(1, 2, 3)), 1500) == null);
        }
        gw.stop();
        green.s.close();
        sim.close();
    }

    // ------------------------------------------------------------------ IFF and designator both ways

    private static void iffAndDesignator(File fomRoot, File rtiDir) throws Exception {
        String rpr2 = new File(fomRoot, "ieee1516-2010/RPR_FOM_v2.0_Link16_1516-2010.xml").getPath();
        File jar3 = new File(rtiDir, "fakerti-1516e.jar");
        int gatewayPort = freePort();
        DatagramSocket sim = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        sim.setSoTimeout(200);
        File dir = Files.createTempDirectory("junction-iff-test").toFile();
        File junctions = new File(dir, "etc/junctions");
        junctions.mkdirs();
        SimwProperties p = new SimwProperties();
        p.set("junction.name", "HLA - DIS IFF");
        p.set("junction.endpoints", "hla;dis");
        p.set("junction.endpoint.hla.standard", "ieee1516-2010");
        p.set("junction.endpoint.hla.hla.foms", rpr2);
        p.set("junction.endpoint.hla.hla.federation", "Yellow");
        p.set("junction.endpoint.hla.hla.rtijar", jar3.getPath());
        p.set("junction.endpoint.dis.standard", "dis7");
        p.set("junction.endpoint.dis.hla.foms", rpr2);
        p.set("junction.endpoint.dis.dis.interface", "127.0.0.1");
        p.set("junction.endpoint.dis.dis.port", String.valueOf(gatewayPort));
        p.set("junction.endpoint.dis.dis.exid", "3");
        p.set("junction.endpoint.dis.dis.sendaddress", "127.0.0.1:" + sim.getLocalPort());
        p.set("junction.endpoint.dis.dis.heartbeatsecs", "1");
        GatewayConfig gc = new ConfigLoader(junctions, dir).gateway(new File(junctions, "iff.junction.settings"), p);
        RtiLibrary lib = RtiLibrary.isolated(Collections.singletonList(jar3));
        InformationExchangeModel m = InformationExchangeModel.fromHlaEvolved(Collections.singletonList(rpr2));
        HlaCodec codec = new HlaCodec(m);
        Gateway gw = new Gateway(gc, Collections.<String, String>emptyMap(), dir, null);
        gw.start();
        check("IFF gateway running: " + gw.getState(), gw.getState() == Gateway.State.RUNNING);
        Fed yellow = new Fed(lib, "Yellow", "YellowSim", rpr2);

        // ---- HLA -> DIS: an aircraft 5-6-9 with a Mark XII transponder and a designator
        int ac = yellow.s.objectClass(AIRCRAFT);
        int[] ah = handles(yellow, ac, new String[] {"EntityIdentifier", "EntityType", "Spatial"});
        yellow.s.publishObjectClass(ac, ah);
        yellow.reserve("Eagle");
        long eagle = yellow.s.registerObject(ac, "Eagle");
        yellow.s.updateAttributes(eagle, ah, new byte[][] {
            encode(codec, m, AIRCRAFT, "EntityIdentifier", entityId(5, 6, 9)),
            encode(codec, m, AIRCRAFT, "EntityType", entityType(1, 2, 225, 1, 3, 0, 0)),
            encode(codec, m, AIRCRAFT, "Spatial", spatial(codec, m, 3100000, 1000000, 5400000))}, null);
        check("DIS gets the aircraft 5-6-9", entityStateFrom(sim, x -> x.id.equals(new DisEntityId(5, 6, 9)), 4000) != null);

        int tc = yellow.s.objectClass(TRANSPONDER);
        String[] tn = {"HostObjectIdentifier", "SystemType", "SystemName", "SystemMode", "SystemIsOn", "SystemIsOperational",
            "Mode3AEnabled", "Mode3AIsOn", "Mode3ACode", "Mode5CEnabled", "Mode5CIsOn", "Mode5CAltitude", "Mode5CAltitudeAvailable",
            "EmergencyOn"};
        int[] th = handles(yellow, tc, tn);
        yellow.s.publishObjectClass(tc, th);
        yellow.reserve("Eagle-iff");
        long xpdr = yellow.s.registerObject(tc, "Eagle-iff");
        yellow.s.updateAttributes(xpdr, th, new byte[][] {
            encode(codec, m, TRANSPONDER, "HostObjectIdentifier", HValue.ofText("Eagle")),
            encode(codec, m, TRANSPONDER, "SystemType", HValue.ofInt(1)),
            encode(codec, m, TRANSPONDER, "SystemName", HValue.ofInt(7)),
            encode(codec, m, TRANSPONDER, "SystemMode", HValue.ofInt(3)),
            encode(codec, m, TRANSPONDER, "SystemIsOn", HValue.ofInt(1)),
            encode(codec, m, TRANSPONDER, "SystemIsOperational", HValue.ofInt(1)),
            encode(codec, m, TRANSPONDER, "Mode3AEnabled", HValue.ofInt(1)),
            encode(codec, m, TRANSPONDER, "Mode3AIsOn", HValue.ofInt(1)),
            encode(codec, m, TRANSPONDER, "Mode3ACode", HValue.ofInt(01234)),
            encode(codec, m, TRANSPONDER, "Mode5CEnabled", HValue.ofInt(1)),
            encode(codec, m, TRANSPONDER, "Mode5CIsOn", HValue.ofInt(1)),
            encode(codec, m, TRANSPONDER, "Mode5CAltitude", HValue.ofInt(250)),
            encode(codec, m, TRANSPONDER, "Mode5CAltitudeAvailable", HValue.ofInt(1)),
            encode(codec, m, TRANSPONDER, "EmergencyOn", HValue.ofInt(0))}, null);
        byte[] ipdu = pduFrom(sim, DisPduHeader.IFF, 4000);
        check("DIS gets the IFF PDU", ipdu != null);
        if (ipdu != null) {
            DisToRpr.Embedded e = new DisToRpr(7).iff(DisPduHeader.read(new DisReader(ipdu, 0, ipdu.length)), reader(ipdu));
            check("IFF of 5-6-9, Mark X/XII transponder: " + e.instanceName, e.instanceName.equals("5-6-9/iff-1")
                    && e.className.equals(DisToRpr.NATO_IFF_TRANSPONDER));
            DisReader r = reader(ipdu);
            r.seek(DisPduHeader.SIZE + 32);                               // entity, event, location, system id, designator
            int status = r.u8();
            r.skip(3);
            int[] pp = new int[6];
            for (int i = 0; i < 6; i++) pp[i] = r.u16();
            check("system on, Mode 3/A and C capable", (status & 1) == 1 && (status & (1 << 3)) != 0 && (status & (1 << 5)) != 0);
            check("Mode 3/A 1234 on", (pp[2] & 0xFFF) == 01234 && (pp[2] & (1 << 13)) != 0);
            check("Mode C 25 000 ft on", (pp[4] & 0xFFF) == (250 << 1) && (pp[4] & (1 << 13)) != 0);
        }
        check("IFF heartbeat", pduFrom(sim, DisPduHeader.IFF, 2500) != null);

        int dc = yellow.s.objectClass(DESIGNATOR);
        int[] dh = handles(yellow, dc, new String[] {"HostObjectIdentifier", "DesignatedObjectIdentifier", "DesignatorCode",
            "DesignatorOutputPower", "DesignatorEmissionWavelength", "DesignatorSpotLocation"});
        yellow.s.publishObjectClass(dc, dh);
        yellow.reserve("Eagle-laser");
        long laser = yellow.s.registerObject(dc, "Eagle-laser");
        Map<String, HValue> spot = new LinkedHashMap<String, HValue>();
        spot.put("X", HValue.ofFloat(3900100));
        spot.put("Y", HValue.ofFloat(1000200));
        spot.put("Z", HValue.ofFloat(4900300));
        yellow.s.updateAttributes(laser, dh, new byte[][] {
            encode(codec, m, DESIGNATOR, "HostObjectIdentifier", HValue.ofText("Eagle")),
            encode(codec, m, DESIGNATOR, "DesignatedObjectIdentifier", HValue.ofText("1-2-3")),
            encode(codec, m, DESIGNATOR, "DesignatorCode", HValue.ofInt(1688)),
            encode(codec, m, DESIGNATOR, "DesignatorOutputPower", HValue.ofFloat(2.5)),
            encode(codec, m, DESIGNATOR, "DesignatorEmissionWavelength", HValue.ofFloat(1.064)),
            encode(codec, m, DESIGNATOR, "DesignatorSpotLocation", HValue.ofRecord(spot))}, null);
        byte[] dpdu = pduFrom(sim, DisPduHeader.DESIGNATOR, 4000);
        check("DIS gets the Designator PDU", dpdu != null);
        if (dpdu != null) {
            DisReader r = reader(dpdu);
            DisEntityId by = DisEntityId.read(r);
            r.skip(2);
            DisEntityId at = DisEntityId.read(r);
            int code = r.u16();
            float power = r.f32();
            r.skip(16);
            double x = r.f64();
            check("designator of 5-6-9 at 1-2-3, code 1688, 2.5 W, spot", by.equals(new DisEntityId(5, 6, 9)) && at.equals(new DisEntityId(1, 2, 3))
                    && code == 1688 && power == 2.5f && x == 3900100);
        }
        yellow.s.deleteObject(laser, null);
        check("deleted designator sent with power 0",
                pduWhere(sim, DisPduHeader.DESIGNATOR, x -> java.nio.ByteBuffer.wrap(x, 28, 4).getFloat() == 0f, 2500) != null);
        yellow.s.deleteObject(xpdr, null);
        yellow.s.deleteObject(eagle, null);

        // ---- DIS -> HLA: tank 1-2-3 with a Mode S transponder (type 11), and its designator
        int[] tsub = handles(yellow, tc, new String[] {"HostObjectIdentifier", "Mode3ACode", "Mode5CAltitude", "IdentSquawkFlashOn"});
        yellow.s.subscribeObjectClass(tc, tsub);
        int[] dsub = handles(yellow, dc, new String[] {"HostObjectIdentifier", "DesignatedObjectIdentifier", "DesignatorCode"});
        yellow.s.subscribeObjectClass(dc, dsub);
        sleep(300);
        InetSocketAddress gwAddr = new InetSocketAddress(InetAddress.getLoopbackAddress(), gatewayPort);
        DisWriter w = new DisWriter(60).header(7, 3, 28, 6, 0, 0);
        w.entityId(new DisEntityId(1, 2, 3)).u16(1).u16(2).u16(5).f32(0).f32(0).f32(0);
        w.u16(11).u16(0).u8(3).u8(0).u8(0).u8(0);
        w.u8(0x7F).u8(0).u8(0x2).u8(0x4);                                  // on, all capable; ident
        w.u16(0x2000).u16(0x2000).u16(0x2000 | 07777).u16(0).u16(0x2000 | (120 << 1)).u16(0x2000);
        byte[] iff = w.finish();
        sim.send(new DatagramPacket(iff, iff.length, gwAddr));
        DisWriter dw = new DisWriter(88).header(7, 3, 24, 6, 0, 0);
        dw.entityId(new DisEntityId(1, 2, 3)).u16(1100).entityId(new DisEntityId(5, 6, 9)).u16(1511).f32(3f).f32(1.064f);
        dw.f32(0).f32(0).f32(0).f64(3900000).f64(1000000).f64(4900000).u8(1).zeros(3).f32(0).f32(0).f32(0);
        byte[] des = dw.finish();
        sim.send(new DatagramPacket(des, des.length, gwAddr));
        Map<String, String[]> found = new LinkedHashMap<String, String[]>();
        for (int i = 0; i < 2; i++) {
            String[] d = yellow.take("discovered", 4000);
            if (d != null) found.put(d[3], d);
        }
        String[] dIff = found.get("1-2-3/iff-11"), dDes = found.get("1-2-3/designator");
        check("HLA discovers the Mode S transponder 1-2-3/iff-11 " + found.keySet(), dIff != null && dIff[2].equals(String.valueOf(tc)));
        check("and the designator 1-2-3/designator", dDes != null && dDes[2].equals(String.valueOf(dc)));
        if (dIff != null) {
            long inst = Long.parseLong(dIff[1]);
            byte[] code = yellow.waitValue(inst, tsub[1], 3000), alt = yellow.waitValue(inst, tsub[2], 3000), ident = yellow.waitValue(inst, tsub[3], 3000);
            check("Mode 3/A 7777, Mode C 12 000 ft, ident on", code != null && alt != null && ident != null
                    && codec.decode(m.getClassAttribute(TRANSPONDER, "Mode3ACode").getDataType(), code).asLong() == 07777
                    && codec.decode(m.getClassAttribute(TRANSPONDER, "Mode5CAltitude").getDataType(), alt).asLong() == 120
                    && codec.decode(m.getClassAttribute(TRANSPONDER, "IdentSquawkFlashOn").getDataType(), ident).asLong() == 1);
        }
        if (dDes != null) {
            long inst = Long.parseLong(dDes[1]);
            byte[] at = yellow.waitValue(inst, dsub[1], 3000), code = yellow.waitValue(inst, dsub[2], 3000);
            check("designator: designates 5-6-9, code 1511", at != null && code != null
                    && "5-6-9".equals(text(codec, m, DESIGNATOR, "DesignatedObjectIdentifier", at))
                    && codec.decode(m.getClassAttribute(DESIGNATOR, "DesignatorCode").getDataType(), code).asLong() == 1511);
        }
        check("the gateway does not echo the DIS IFF back",
                pduWhere(sim, DisPduHeader.IFF, x -> DisEntityId.read(reader(x)).equals(new DisEntityId(1, 2, 3)), 1500) == null);
        gw.stop();
        yellow.s.close();
        sim.close();
    }

    /** A DIS 7 Electromagnetic Emission PDU of tank 1-2-3: emitter 4 (name 9300) with beams {id, function}; null: no systems. */
    private static byte[] eePdu(boolean heartbeat, int[][] beams) {
        int nb = beams == null ? 0 : beams.length;
        int sysLen = 20 + 52 * nb;
        DisWriter w = new DisWriter(28 + (beams == null ? 0 : sysLen)).header(7, 3, 23, 6, 0, 0);
        w.entityId(new DisEntityId(1, 2, 3)).u16(1).u16(2).u16(5);
        w.u8(heartbeat ? 0 : 1).u8(beams == null ? 0 : 1).u16(0);
        if (beams != null) {
            w.u8(sysLen / 4).u8(nb).u16(0).u16(9300).u8(1).u8(4).f32(0).f32(0).f32(-3);
            for (int[] b : beams) {
                w.u8(13).u8(b[0]).u16(0).f32(9.4e9f).f32(0).f32(65).f32(1000).f32(1);
                w.f32(0.75f).f32(0.1f).f32(0).f32(0.05f).f32(0);
                w.u8(b[1]).u8(0).u8(0).u8(0).u32(b[1] == 12 ? 0x0A0B0C0DL : 0);
            }
        }
        return w.finish();
    }

    private static DisToRpr.Emission emissionFrom(DatagramSocket s, Predicate<DisToRpr.Emission> want, long millis) throws Exception {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            byte[] pdu = pduFrom(s, DisPduHeader.ELECTROMAGNETIC_EMISSION, end - System.currentTimeMillis());
            if (pdu == null) {
                return null;
            }
            DisReader r = new DisReader(pdu, 0, pdu.length);
            DisPduHeader h = DisPduHeader.read(r);
            r.seek(DisPduHeader.SIZE);
            DisToRpr.Emission e = new DisToRpr(7).emission(h, r);
            if (want.test(e)) {
                return e;
            }
        }
        return null;
    }

    private static byte[] pduWhere(DatagramSocket s, int type, Predicate<byte[]> want, long millis) throws Exception {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            byte[] pdu = pduFrom(s, type, end - System.currentTimeMillis());
            if (pdu == null) {
                return null;
            }
            if (want.test(pdu)) {
                return pdu;
            }
        }
        return null;
    }

    private static int beams(DisToRpr.Emission e, String system) {
        List<DisToRpr.Embedded> l = e.beams.get(system);
        return l == null ? -1 : l.size();
    }

    private static int[] handles(Fed f, int cls, String[] names) throws Exception {
        int[] h = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            h[i] = f.s.attribute(cls, names[i]);
        }
        return h;
    }

    private static String text(HlaCodec c, InformationExchangeModel m, String cls, String attr, byte[] v) {
        return c.decode(m.getClassAttribute(cls, attr).getDataType(), v).asTrimmedText();
    }

    private static int u16(byte[] b) {
        return ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
    }

    private static float f32(byte[] b, int at) {
        return java.nio.ByteBuffer.wrap(b, at, 4).getFloat();
    }

    // ------------------------------------------------------------------ own echo

    /** An endpoint that sends to its own port must not hear itself. */
    private static void echo(File fomRoot) throws Exception {
        int port = freePort();
        SimwProperties p = new SimwProperties();
        p.set("endpoint.standard", "dis7");
        p.set("endpoint.hla.foms", new File(fomRoot, "ieee1516-2010/RPR_FOM_v2.0_Link16_1516-2010.xml").getPath());
        p.set("endpoint.dis.interface", "127.0.0.1");
        p.set("endpoint.dis.port", String.valueOf(port));
        p.set("endpoint.dis.sendaddress", "127.0.0.1");
        EndpointConfig cfg = new EndpointConfig("loop", p, "test", new File("."));
        DisEndpoint e = new DisEndpoint(cfg, "1", "echo");
        final List<String> heard = Collections.synchronizedList(new ArrayList<String>());
        final BlockingQueue<Long> registered = new LinkedBlockingQueue<Long>();
        e.open(new EndpointListener() {
            public void discovered(Endpoint from, long key, int objectClass, String name, String producer) {
                heard.add(name);
            }
            public void reflected(Endpoint from, long key, int[] a, byte[][] v, byte[] tag, String producer) {
            }
            public void removed(Endpoint from, long key, byte[] tag) {
            }
            public void received(Endpoint from, int ic, int[] a, byte[][] v, byte[] tag, String producer) {
                heard.add("interaction");
            }
            public void provideRequested(Endpoint from, long key, int[] attributes) {
            }
            public void registered(Endpoint from, long gwId, long key, String name) {
                registered.add(key);
            }
            public void registrationFailed(Endpoint from, long gwId, String reason) {
            }
            public void stateChanged(Endpoint from, EndpointState state, String message) {
            }
        });
        InformationExchangeModel m = e.model();
        HlaCodec codec = new HlaCodec(m);
        int ac = m.getObjectClass(AIRCRAFT).getId();
        Interest sub = new Interest();
        List<Integer> attrs = new ArrayList<Integer>();
        attrs.add(m.getClassAttribute(AIRCRAFT, "Spatial").getId());
        sub.addAttributes(ac, attrs);
        e.declare(new Interest(), sub);
        e.createObject(1, ac, NameCandidates.ANY);
        Long key = registered.poll(2, TimeUnit.SECONDS);
        check("loop endpoint registers", key != null);
        if (key != null) {
            e.updateObject(key, new int[] {m.getClassAttribute(AIRCRAFT, "EntityType").getId(), m.getClassAttribute(AIRCRAFT, "Spatial").getId()},
                    new byte[][] {encode(codec, m, AIRCRAFT, "EntityType", entityType(1, 2, 225, 1, 3, 0, 0)),
                        encode(codec, m, AIRCRAFT, "Spatial", spatial(codec, m, 1, 2, 3))}, null);
        }
        sleep(700);
        Map<String, Object> diag = e.diagnostics();
        check("own PDUs recognised as echoes " + diag, ((Number) diag.get("echoes")).longValue() > 0);
        check("and not discovered", heard.isEmpty());
        e.close(true);
    }

    // ------------------------------------------------------------------ emitters heard before declare

    /**
     * 0.12.2: an emitter already sending when the gateway starts (heard while the other endpoints are
     * still joining, so before the router declares) is bridged once the router has declared; before,
     * its systems and beams stayed without a class for as long as it kept sending.
     */
    private static void emittersBeforeDeclare(File fomRoot) throws Exception {
        int port = freePort();
        try (DatagramSocket sim = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            SimwProperties p = new SimwProperties();
            p.set("endpoint.standard", "dis7");
            p.set("endpoint.hla.foms", new File(fomRoot, "ieee1516-2010/RPR_FOM_v2.0_Link16_1516-2010.xml").getPath());
            p.set("endpoint.dis.interface", "127.0.0.1");
            p.set("endpoint.dis.port", String.valueOf(port));
            p.set("endpoint.dis.sendaddress", "127.0.0.1:" + sim.getLocalPort());
            EndpointConfig cfg = new EndpointConfig("early", p, "test", new File("."));
            DisEndpoint e = new DisEndpoint(cfg, "3", "early-emitters");     // target: exercise 3 (the PDUs')
            final List<Integer> classes = Collections.synchronizedList(new ArrayList<Integer>());
            e.open(new EndpointListener() {
                public void discovered(Endpoint from, long key, int objectClass, String name, String producer) {
                    classes.add(objectClass);
                }
                public void reflected(Endpoint from, long key, int[] a, byte[][] v, byte[] tag, String producer) {
                }
                public void removed(Endpoint from, long key, byte[] tag) {
                }
                public void received(Endpoint from, int ic, int[] a, byte[][] v, byte[] tag, String producer) {
                }
                public void provideRequested(Endpoint from, long key, int[] attributes) {
                }
                public void registered(Endpoint from, long gwId, long key, String name) {
                }
                public void registrationFailed(Endpoint from, long gwId, String reason) {
                }
                public void stateChanged(Endpoint from, EndpointState state, String message) {
                }
            });
            byte[] ee = eePdu(true, new int[][] {{1, 1}});
            for (int i = 0; i < 5; i++) {
                sim.send(new DatagramPacket(ee, ee.length, InetAddress.getLoopbackAddress(), port));
                sleep(40);
            }
            sleep(200);
            check("emitter heard before declare: not yet discovered", classes.isEmpty());

            InformationExchangeModel m = e.model();
            int es = m.getObjectClass(EMITTER).getId();
            int rb = m.getObjectClass(RADAR).getId();
            Interest sub = new Interest();
            sub.addAttributes(es, Collections.singletonList(m.getClassAttribute(EMITTER, "EmitterType").getId()));
            sub.addAttributes(rb, Collections.singletonList(m.getClassAttribute(RADAR, "BeamAzimuthCenter").getId()));
            e.declare(new Interest(), sub);
            long until = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < until && !(classes.contains(es) && classes.contains(rb))) {
                sim.send(new DatagramPacket(ee, ee.length, InetAddress.getLoopbackAddress(), port));
                sleep(100);
            }
            check("emitter heard before declare: its system is bridged after the declare", classes.contains(es));
            check("emitter heard before declare: its beam is bridged after the declare", classes.contains(rb));
            e.close(true);
        }
    }

    // ------------------------------------------------------------------ a DIS name taken in HLA

    /**
     * A DIS entity whose name ("1-2-3") an HLA federate already holds: its copy gets the next
     * name ("1-2-3@dis"), and references follow it both ways, while "1-2-3" in HLA keeps meaning
     * the HLA object (which DIS knows by another entity id).
     */
    private static void takenName(File fomRoot, File rtiDir) throws Exception {
        String rpr2 = new File(fomRoot, "ieee1516-2010/RPR_FOM_v2.0_Link16_1516-2010.xml").getPath();
        File jar3 = new File(rtiDir, "fakerti-1516e.jar");
        int gatewayPort = freePort();
        DatagramSocket sim = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        sim.setSoTimeout(200);
        File dir = Files.createTempDirectory("junction-dis-taken").toFile();
        File junctions = new File(dir, "etc/junctions");
        junctions.mkdirs();
        SimwProperties p = new SimwProperties();
        p.set("junction.name", "Taken names");
        p.set("junction.endpoints", "hla;dis");
        p.set("junction.endpoint.hla.standard", "ieee1516-2010");
        p.set("junction.endpoint.hla.hla.foms", rpr2);
        p.set("junction.endpoint.hla.hla.federation", "Taken");
        p.set("junction.endpoint.hla.hla.rtijar", jar3.getPath());
        p.set("junction.endpoint.dis.standard", "dis7");
        p.set("junction.endpoint.dis.hla.foms", rpr2);
        p.set("junction.endpoint.dis.dis.interface", "127.0.0.1");
        p.set("junction.endpoint.dis.dis.port", String.valueOf(gatewayPort));
        p.set("junction.endpoint.dis.dis.exid", "5");
        p.set("junction.endpoint.dis.dis.sendaddress", "127.0.0.1:" + sim.getLocalPort());
        p.set("junction.endpoint.dis.dis.heartbeatsecs", "1");
        GatewayConfig gc = new ConfigLoader(junctions, dir).gateway(new File(junctions, "taken.junction.settings"), p);
        check("taken: config valid " + gc.getErrors(), gc.isValid());
        RtiLibrary lib = RtiLibrary.isolated(Collections.singletonList(jar3));
        InformationExchangeModel m = InformationExchangeModel.fromHlaEvolved(Collections.singletonList(rpr2));
        HlaCodec codec = new HlaCodec(m);

        // an HLA federate already holds the name "1-2-3": an aircraft without EntityIdentifier
        Fed red = new Fed(lib, "Taken", "RedSim", rpr2);
        int ac = red.s.objectClass(AIRCRAFT);
        int[] ah = {red.s.attribute(ac, "EntityType"), red.s.attribute(ac, "Spatial")};
        red.s.publishObjectClass(ac, ah);
        red.reserve("1-2-3");
        long squatter = red.s.registerObject(ac, "1-2-3");
        byte[][] squatterValues = {
            encode(codec, m, AIRCRAFT, "EntityType", entityType(1, 2, 225, 1, 3, 0, 0)),
            encode(codec, m, AIRCRAFT, "Spatial", spatial(codec, m, 3100000, 1000000, 5400000))};
        int gv = red.s.objectClass(GROUND);
        red.s.subscribeObjectClass(gv, new int[] {red.s.attribute(gv, "Spatial"), red.s.attribute(gv, "Marking")});
        int fire = red.s.interactionClass(FIRE);
        red.s.subscribeInteractionClass(fire);
        red.s.publishInteractionClass(fire);

        Gateway gw = new Gateway(gc, Collections.<String, String>emptyMap(), dir, null);
        gw.start();
        check("taken: gateway running: " + gw.getState(), gw.getState() == Gateway.State.RUNNING);
        sleep(300);                                                    // the gateway has discovered the aircraft
        red.s.updateAttributes(squatter, ah, squatterValues, null);
        DisEntityState sq = entityStateFrom(sim, e -> e.id.site == 900 && e.id.application == 1, 4000);
        check("taken: the HLA aircraft \"1-2-3\" reaches DIS under an id of the gateway (900-1-n)", sq != null);

        // DIS entities 1-2-3 (its name is taken in HLA) and 1-2-4
        InetSocketAddress gwAddr = new InetSocketAddress(InetAddress.getLoopbackAddress(), gatewayPort);
        byte[] t3 = entityPdu(5, new DisEntityId(1, 2, 3), "T3");
        byte[] t4 = entityPdu(5, new DisEntityId(1, 2, 4), "T4");
        sim.send(new DatagramPacket(t3, t3.length, gwAddr));
        sim.send(new DatagramPacket(t4, t4.length, gwAddr));
        java.util.Set<String> found = new java.util.HashSet<String>();
        for (int i = 0; i < 2; i++) {
            String[] d = red.take("discovered", 4000);
            if (d != null) {
                found.add(d[3]);
            }
        }
        check("taken: HLA discovers DIS 1-2-3 as \"1-2-3@dis\" and 1-2-4 as \"1-2-4\" " + found,
                found.contains("1-2-3@dis") && found.contains("1-2-4"));

        // DIS 1-2-3 fires at 1-2-4: HLA gets the copies' names, not the squatter's
        sleep(300);
        DisWriter fw = new DisWriter(96).header(7, 5, 2, 2, 0, 0);
        fw.entityId(new DisEntityId(1, 2, 3)).entityId(new DisEntityId(1, 2, 4)).entityId(new DisEntityId(0, 0, 0));
        fw.u16(1).u16(2).u16(9).u32(0).f64(1).f64(2).f64(3);
        fw.entityType(new DisEntityType(2, 9, 225, 2, 1, 0, 0)).u16(1000).u16(1000).u16(1).u16(0);
        fw.f32(1).f32(2).f32(3).f32(1000);
        byte[] firePdu = fw.finish();
        sim.send(new DatagramPacket(firePdu, firePdu.length, gwAddr));
        String[] rx = red.take("received", 4000);
        check("taken: HLA receives the WeaponFire", rx != null);
        if (rx != null) {
            Map<Integer, byte[]> pv = red.lastInteraction;
            String firing = codec.decode(m.getClassParameter(FIRE, "FiringObjectIdentifier").getDataType(),
                    pv.get(red.s.parameter(fire, "FiringObjectIdentifier"))).asTrimmedText();
            String target = codec.decode(m.getClassParameter(FIRE, "TargetObjectIdentifier").getDataType(),
                    pv.get(red.s.parameter(fire, "TargetObjectIdentifier"))).asTrimmedText();
            check("taken: FiringObjectIdentifier is the copy \"1-2-3@dis\", not the HLA aircraft (got " + firing + ")", "1-2-3@dis".equals(firing));
            check("taken: TargetObjectIdentifier is \"1-2-4\" (got " + target + ")", "1-2-4".equals(target));
        }

        // and back: HLA's "1-2-3" (the aircraft) fires at "1-2-3@dis" (the DIS tank)
        int pFiring = red.s.parameter(fire, "FiringObjectIdentifier");
        int pTarget = red.s.parameter(fire, "TargetObjectIdentifier");
        red.s.sendInteraction(fire, new int[] {pFiring, pTarget}, new byte[][] {
            codec.encode(m.getClassParameter(FIRE, "FiringObjectIdentifier").getDataType(), HValue.ofText("1-2-3")),
            codec.encode(m.getClassParameter(FIRE, "TargetObjectIdentifier").getDataType(), HValue.ofText("1-2-3@dis"))}, null);
        byte[] fpdu = pduFrom(sim, DisPduHeader.FIRE, 3000);
        check("taken: DIS gets the Fire PDU", fpdu != null);
        if (fpdu != null && sq != null) {
            DisReader r = reader(fpdu);
            DisEntityId f = DisEntityId.read(r);
            DisEntityId t = DisEntityId.read(r);
            check("taken: shooter is the aircraft's DIS id " + sq.id + " (got " + f + "), target 1-2-3 (got " + t + ")",
                    f.equals(sq.id) && t.equals(new DisEntityId(1, 2, 3)));
        }
        gw.stop();
        red.s.close();
        sim.close();
    }

    // ------------------------------------------------------------------ PDUs

    private static byte[] tankPdu(int exercise, int appearance) {
        DisWriter w = new DisWriter(144).header(7, exercise, 1, 1, 0, 0);
        w.entityId(new DisEntityId(1, 2, 3)).u8(2).u8(0);
        w.entityType(new DisEntityType(1, 1, 225, 1, 1, 3, 0)).entityType(new DisEntityType(1, 1, 225, 1, 1, 3, 0));
        w.f32(0).f32(0).f32(0).f64(3900000).f64(1000000).f64(4900000).f32(0).f32(0).f32(0);
        w.i32(appearance).u8(2).zeros(15).zeros(24);
        w.u8(1).text("TANK", 11).i32(0);
        return w.finish();
    }

    /** An Entity State PDU of a ground vehicle with this id and marking. */
    private static byte[] entityPdu(int exercise, DisEntityId id, String marking) {
        DisWriter w = new DisWriter(144).header(7, exercise, 1, 1, 0, 0);
        w.entityId(id).u8(2).u8(0);
        w.entityType(new DisEntityType(1, 1, 225, 1, 1, 3, 0)).entityType(new DisEntityType(1, 1, 225, 1, 1, 3, 0));
        w.f32(0).f32(0).f32(0).f64(3900000).f64(1000000).f64(4900000).f32(0).f32(0).f32(0);
        w.i32(0).u8(2).zeros(15).zeros(24);
        w.u8(1).text(marking, 11).i32(0);
        return w.finish();
    }

    private static DisEntityState entityStateFrom(DatagramSocket s, Predicate<DisEntityState> want, long millis) throws Exception {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            byte[] pdu = pduFrom(s, DisPduHeader.ENTITY_STATE, end - System.currentTimeMillis());
            if (pdu == null) {
                return null;
            }
            DisEntityState es = DisEntityState.read(reader(pdu));
            if (want.test(es)) {
                return es;
            }
        }
        return null;
    }

    private static byte[] pduFrom(DatagramSocket s, int type, long millis) throws Exception {
        long end = System.currentTimeMillis() + millis;
        byte[] buf = new byte[2048];
        while (System.currentTimeMillis() < end) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                s.receive(p);
            } catch (SocketTimeoutException e) {
                continue;
            }
            if (p.getLength() >= 12 && (buf[2] & 0xFF) == type) {
                return java.util.Arrays.copyOf(buf, p.getLength());
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

    // ------------------------------------------------------------------ values

    private static byte[] encode(HlaCodec c, InformationExchangeModel m, String cls, String attr, HValue v) {
        return c.encode(m.getClassAttribute(cls, attr).getDataType(), v);
    }

    private static HValue entityId(int site, int app, int entity) {
        Map<String, HValue> f = new LinkedHashMap<String, HValue>();
        f.put("SiteID", HValue.ofInt(site));
        f.put("ApplicationID", HValue.ofInt(app));
        Map<String, HValue> e = new LinkedHashMap<String, HValue>();
        e.put("FederateIdentifier", HValue.ofRecord(f));
        e.put("EntityNumber", HValue.ofInt(entity));
        return HValue.ofRecord(e);
    }

    private static HValue entityType(int kind, int domain, int country, int category, int sub, int specific, int extra) {
        Map<String, HValue> t = new LinkedHashMap<String, HValue>();
        t.put("EntityKind", HValue.ofInt(kind));
        t.put("Domain", HValue.ofInt(domain));
        t.put("CountryCode", HValue.ofInt(country));
        t.put("Category", HValue.ofInt(category));
        t.put("Subcategory", HValue.ofInt(sub));
        t.put("Specific", HValue.ofInt(specific));
        t.put("Extra", HValue.ofInt(extra));
        return HValue.ofRecord(t);
    }

    private static HValue spatial(HlaCodec codec, InformationExchangeModel m, double x, double y, double z) {
        HValue def = codec.defaultValue(m.getDataType("SpatialVariantStruct"));
        Map<String, HValue> loc = new LinkedHashMap<String, HValue>();
        loc.put("X", HValue.ofFloat(x));
        loc.put("Y", HValue.ofFloat(y));
        loc.put("Z", HValue.ofFloat(z));
        HValue fp = codec.defaultValue(m.getDataType("SpatialFPStruct")).withField("WorldLocation", HValue.ofRecord(loc));
        return HValue.ofVariant(2, "DRM_FPW", "SpatialFPW", fp).withType(def.getType());
    }

    private static HValue marking(String text) {
        Map<String, HValue> mk = new LinkedHashMap<String, HValue>();
        mk.put("MarkingEncodingType", HValue.ofEnum(1, "ASCII"));
        mk.put("MarkingData", HValue.ofText(text));
        return HValue.ofRecord(mk);
    }

    // ------------------------------------------------------------------ a test federate

    private static final class Fed implements RtiSessionListener {
        final RtiSession s;
        final BlockingQueue<String[]> q = new LinkedBlockingQueue<String[]>();
        final Map<Integer, byte[]> values = new ConcurrentHashMap<Integer, byte[]>();
        final Map<Long, Map<Integer, byte[]>> byInstance = new ConcurrentHashMap<Long, Map<Integer, byte[]>>();
        volatile Map<Integer, byte[]> lastInteraction;

        Fed(RtiLibrary lib, String federation, String name, String fom) throws Exception {
            s = lib.createSession(RtiSession.Version.HLA_1516E);
            s.connect(ConnectSettings.defaults(), this);
            s.join(federation, "Tester", name, Collections.singletonList(fom), true);
        }

        void reserve(String name) throws Exception {
            s.reserveName(name);
            String[] r = take("name", 3000);
            if (r == null || !r[2].equals("true")) {
                throw new IllegalStateException("could not reserve " + name);
            }
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

        byte[] waitValue(int attr, long millis) {
            long end = System.currentTimeMillis() + millis;
            while (System.currentTimeMillis() < end) {
                byte[] v = values.get(attr);
                if (v != null) {
                    return v;
                }
                sleep(20);
            }
            return null;
        }

        byte[] waitValue(long instance, int attr, long millis) {
            long end = System.currentTimeMillis() + millis;
            while (System.currentTimeMillis() < end) {
                Map<Integer, byte[]> mine = byInstance.get(instance);
                byte[] v = mine == null ? null : mine.get(attr);
                if (v != null) {
                    return v;
                }
                sleep(20);
            }
            return null;
        }

        @Override
        public void discovered(long instance, int objectClass, String name, String producer) {
            q.add(new String[] {"discovered", String.valueOf(instance), String.valueOf(objectClass), name});
        }

        @Override
        public void reflected(long instance, int[] attributes, byte[][] vals, byte[] tag, String producer) {
            Map<Integer, byte[]> mine = byInstance.computeIfAbsent(instance, k -> new ConcurrentHashMap<Integer, byte[]>());
            for (int i = 0; i < attributes.length; i++) {
                values.put(attributes[i], vals[i]);
                mine.put(attributes[i], vals[i]);
            }
        }

        @Override
        public void removed(long instance, byte[] tag, String producer) {
            q.add(new String[] {"removed", String.valueOf(instance)});
        }

        @Override
        public void received(int interactionClass, int[] parameters, byte[][] vals, byte[] tag, String producer) {
            Map<Integer, byte[]> m = new LinkedHashMap<Integer, byte[]>();
            for (int i = 0; i < parameters.length; i++) {
                m.put(parameters[i], vals[i]);
            }
            lastInteraction = m;
            q.add(new String[] {"received", String.valueOf(interactionClass)});
        }

        @Override
        public void provideRequested(long instance, int[] attributes, byte[] tag) {
        }

        @Override
        public void nameReserved(String name, boolean success) {
            q.add(new String[] {"name", name, String.valueOf(success)});
        }

        @Override
        public void connectionLost(String reason) {
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
