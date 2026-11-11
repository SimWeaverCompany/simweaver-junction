package com.simweaver.junction;

import com.simweaver.common.config.SimwProperties;
import com.simweaver.common.hla.codec.HValue;
import com.simweaver.common.hla.codec.HlaCodec;
import com.simweaver.common.hla.session.RtiLibrary;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.common.json.Json;
import com.simweaver.common.rest.JsonParser;
import com.simweaver.junction.config.ConfigLoader;
import com.simweaver.junction.config.GatewayConfig;
import com.simweaver.kafka.FakeKafkaBroker;
import com.simweaver.kafka.client.KafkaConsumer;
import com.simweaver.kafka.client.KafkaProducer;
import com.simweaver.kafka.client.KafkaSettings;
import com.simweaver.kafka.record.KafkaRecord;

import java.io.File;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Kafka endpoints (0.13.0) against an in-memory broker: the settings; a PDU relay DIS 7 -> topic
 * -> DIS 6 and back, keyed by entity, without an endpoint's own records coming back; HLA objects and
 * interactions as JSON records (Blue -> topic -> Green), every record standing on its own
 * ({@code values=full}), a record written by another producer (no root in the class name, one
 * attribute, an enumerator by name), removal, the remove records of a gateway that stops; a cluster
 * that cannot be reached.
 *
 * <pre>java -cp out/classes:out/test com.simweaver.junction.KafkaBridgeSelfTest [fom root] [fake rti folder]</pre>
 */
public final class KafkaBridgeSelfTest {

    private static int checks;
    private static int failures;
    static final String AIRCRAFT = "HLAobjectRoot.BaseEntity.PhysicalEntity.Platform.Aircraft";
    static final String FIRE = "HLAinteractionRoot.WeaponFire";

    private KafkaBridgeSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        File fomRoot = new File(args.length > 0 ? args[0] : "simwrift/rtdir/etc/fom").getAbsoluteFile();
        File rtiDir = new File(args.length > 1 ? args[1] : "fakerti/build").getAbsoluteFile();
        String rpr2 = new File(fomRoot, "ieee1516-2010/RPR_FOM_v2.0_Link16_1516-2010.xml").getPath();
        File jar3 = new File(rtiDir, "fakerti-1516e.jar");
        File dir = Files.createTempDirectory("junction-kafka").toFile();
        File junctions = new File(dir, "etc/junctions");
        junctions.mkdirs();
        ConfigLoader loader = new ConfigLoader(junctions, dir);

        try (FakeKafkaBroker broker = new FakeKafkaBroker(2, false)) {
            settings(loader, junctions, rpr2, broker);
            pdus(loader, junctions, dir, rpr2, broker);
            objects(loader, junctions, dir, rpr2, jar3, broker);
        }
        unreachable(loader, junctions, dir, rpr2);
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void settings(ConfigLoader loader, File junctions, String rpr2, FakeKafkaBroker broker) throws Exception {
        SimwProperties bad = new SimwProperties();
        bad.set("junction.endpoints", "dis;k");
        J2jPduSelfTest.disEndpoint(bad, "dis", "dis7", J2jPduSelfTest.freePort(), 1, 1, rpr2);
        bad.set("junction.endpoint.k.standard", "kafka");
        bad.set("junction.endpoint.k.kafka.topic", "no spaces");
        bad.set("junction.endpoint.k.kafka.acks", "2");
        bad.set("junction.endpoint.k.kafka.direction", "sideways");
        bad.set("junction.endpoint.k.kafka.security", "sasl_ssl");
        bad.set("junction.router.kind", "pdu");
        GatewayConfig g = loader.gateway(new File(junctions, "bad.junction.settings"), bad);
        String errs = String.join(" | ", g.getErrors());
        check("bad Kafka settings are errors: " + errs, !g.isValid() && errs.contains("endpoint.kafka.bootstrap is empty")
                && errs.contains("not a Kafka topic name") && errs.contains("endpoint.kafka.acks") && errs.contains("endpoint.kafka.direction")
                && errs.contains("SASL needs endpoint.kafka.sasl.username"));

        SimwProperties ok = pduGateway(broker, "dis7", J2jPduSelfTest.freePort(), 1, 1, rpr2, null);
        ok.set("junction.endpoint.kafka.kafka.security", "sasl_plaintext");
        ok.set("junction.endpoint.kafka.kafka.sasl.username", "alice");
        ok.set("junction.endpoint.kafka.kafka.sasl.password", "very-secret");
        GatewayConfig gok = loader.gateway(new File(junctions, "ok.junction.settings"), ok);
        String json = Json.toJson(gok.getEndpoints().get("kafka"));
        check("a PDU relay with a Kafka endpoint: valid, default topic simweaver.dis, no password in the JSON " + gok.getErrors(),
                gok.isValid() && json.contains("\"defaulttarget\":\"simweaver.dis\"") && json.contains("\"kafkakind\":\"pdu\"")
                        && json.contains("\"saslusername\":\"alice\"") && !json.contains("very-secret"));

        SimwProperties obj = objectGateway(broker, "Blue", new File("x.jar"), rpr2, null);
        GatewayConfig gobj = loader.gateway(new File(junctions, "obj.junction.settings"), obj);
        String oj = Json.toJson(gobj.getEndpoints().get("kafka"));
        check("in an IEM gateway: objects, default topic simweaver.objects, the RPR FOM", oj.contains("\"kafkakind\":\"objects\"")
                && oj.contains("\"defaulttarget\":\"simweaver.objects\"") && oj.contains("RPR_FOM_v2.0"));

        SimwProperties talk = new SimwProperties();
        talk.set("junction.endpoints", "t;kafka");
        talk.set("junction.endpoint.t.standard", "talk");
        talk.set("junction.endpoint.t.hla.federation", "TalkFed");
        talk.set("junction.endpoint.kafka.standard", "kafka");
        talk.set("junction.endpoint.kafka.kafka.bootstrap", broker.bootstrap());
        talk.set("junction.router.kind", "talk");
        GatewayConfig gt = loader.gateway(new File(junctions, "talk.junction.settings"), talk);
        check("a Kafka endpoint in a Talk gateway is refused", !gt.isValid() && String.join(" ", gt.getErrors()).contains("cannot be in a Talk gateway"));
    }

    static SimwProperties pduGateway(FakeKafkaBroker broker, String standard, int port, int sendTo, int exercise, String fom, String topic) {
        SimwProperties p = new SimwProperties();
        p.set("junction.endpoints", "dis;kafka");
        J2jPduSelfTest.disEndpoint(p, "dis", standard, port, sendTo, exercise, fom);
        p.set("junction.endpoint.kafka.standard", "kafka");
        p.set("junction.endpoint.kafka.kafka.bootstrap", broker.bootstrap());
        if (topic != null) {
            p.set("junction.endpoint.kafka.kafka.topic", topic);
        }
        p.set("junction.router.kind", "pdu");
        return p;
    }

    static SimwProperties objectGateway(FakeKafkaBroker broker, String federation, File jar, String fom, String topic) {
        SimwProperties p = new SimwProperties();
        p.set("junction.endpoints", "hla;kafka");
        p.set("junction.endpoint.hla.standard", "ieee1516-2010");
        p.set("junction.endpoint.hla.hla.foms", fom);
        p.set("junction.endpoint.hla.hla.federation", federation);
        p.set("junction.endpoint.hla.hla.rtijar", jar.getPath());
        p.set("junction.endpoint.kafka.standard", "kafka");
        p.set("junction.endpoint.kafka.kafka.bootstrap", broker.bootstrap());
        p.set("junction.endpoint.kafka.hla.foms", fom);
        if (topic != null) {
            p.set("junction.endpoint.kafka.kafka.topic", topic);
        }
        return p;
    }

    private static boolean consuming(Gateway g) {
        String s = Json.toJson(g);
        return s.contains("\"state\":\"consuming\"");
    }

    // ------------------------------------------------------------------ DIS PDUs

    private static void pdus(ConfigLoader loader, File junctions, File dir, String rpr2, FakeKafkaBroker broker) throws Exception {
        int disA = J2jPduSelfTest.freePort(), disB = J2jPduSelfTest.freePort();
        DatagramSocket simA = J2jPduSelfTest.sim(), simB = J2jPduSelfTest.sim();
        GatewayConfig ga = loader.gateway(new File(junctions, "ka.junction.settings"), pduGateway(broker, "dis7", disA, simA.getLocalPort(), 3, rpr2, "t.dis"));
        GatewayConfig gb = loader.gateway(new File(junctions, "kb.junction.settings"), pduGateway(broker, "dis6", disB, simB.getLocalPort(), 4, rpr2, "t.dis"));
        Gateway gwA = new Gateway(ga, Collections.<String, String>emptyMap(), dir, null);
        Gateway gwB = new Gateway(gb, Collections.<String, String>emptyMap(), dir, null);
        DatagramSocket out = new DatagramSocket();
        InetAddress lo = InetAddress.getLoopbackAddress();
        try {
            gwA.start();
            gwB.start();
            check("both PDU gateways run, consuming", gwA.getState() == Gateway.State.RUNNING && gwB.getState() == Gateway.State.RUNNING
                    && J2jPduSelfTest.await(() -> consuming(gwA) && consuming(gwB), 10_000));
            byte[] es = J2jPduSelfTest.entityState(7, 3, 1);
            out.send(new DatagramPacket(es, es.length, lo, disA));
            byte[] esB = J2jPduSelfTest.pdu(simB, 1, 6000);
            check("Entity State DIS 7 ex 3 -> topic -> DIS 6 ex 4, the body the same", esB != null && esB[0] == 6 && esB[1] == 4
                    && Arrays.equals(Arrays.copyOfRange(esB, 11, esB.length), Arrays.copyOfRange(es, 11, es.length)));
            check("the gateway's own record does not come back to its DIS side", J2jPduSelfTest.pdu(simA, 1, 800) == null);
            byte[] es6 = J2jPduSelfTest.entityState(6, 4, 2);
            out.send(new DatagramPacket(es6, es6.length, lo, disB));
            byte[] esA = J2jPduSelfTest.pdu(simA, 1, 6000);
            check("and back: DIS 6 ex 4 -> topic -> DIS 7 ex 3", esA != null && esA[0] == 7 && esA[1] == 3 && esA[17] == 2);
            // the records: keyed by entity, the PDU as it is
            List<KafkaRecord> recs = read(broker, "t.dis", 2, 5000);
            KafkaRecord first = recs.isEmpty() ? null : recs.get(0);
            check("the record: key 1:2:1, the PDU as it is, content-type and source headers", first != null && "1:2:1".equals(str(first.key))
                    && Arrays.equals(first.value, es) && "application/x-dis".equals(str(first.header("content-type")))
                    && first.header("simweaver.source") != null);
            String status = Json.toJson(gwA);
            check("gateway status: the Kafka endpoint's producer and consumer group " + status.length(), status.contains("\"kafka\":{\"kind\":\"pdu\"")
                    && status.contains("\"group\":\"simweaver-junction-ka-kafka\"") && status.contains("\"owndropped\":1"));
        } finally {
            gwA.stop();
            gwB.stop();
            out.close();
            simA.close();
            simB.close();
        }
    }

    private static String str(byte[] b) {
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    /** Reads a topic from the earliest, without a group, until n records or the time is up. */
    static List<KafkaRecord> read(FakeKafkaBroker broker, String topic, int n, long ms) throws Exception {
        KafkaSettings s = new KafkaSettings();
        s.bootstrap = Collections.singletonList(broker.bootstrap());
        KafkaConsumer.Options o = new KafkaConsumer.Options();
        o.topics = Collections.singletonList(topic);
        o.startEarliest = true;
        List<KafkaRecord> got = new CopyOnWriteArrayList<KafkaRecord>();
        KafkaConsumer c = new KafkaConsumer(s, o, got::addAll, "test-reader");
        J2jPduSelfTest.await(() -> got.size() >= n, ms);
        c.close();
        got.sort((a, b) -> Long.compare(a.timestamp, b.timestamp));
        return got;
    }

    // ------------------------------------------------------------------ HLA objects as JSON

    private static void objects(ConfigLoader loader, File junctions, File dir, String rpr2, File jar3, FakeKafkaBroker broker) throws Exception {
        GatewayConfig gc = loader.gateway(new File(junctions, "kc.junction.settings"), objectGateway(broker, "Blue", jar3, rpr2, "t.obj"));
        GatewayConfig gd = loader.gateway(new File(junctions, "kd.junction.settings"), objectGateway(broker, "Green", jar3, rpr2, "t.obj"));
        RtiLibrary lib = RtiLibrary.isolated(Collections.singletonList(jar3));
        InformationExchangeModel m = InformationExchangeModel.fromHlaEvolved(Collections.singletonList(rpr2));
        HlaCodec codec = new HlaCodec(m);
        Gateway gwC = new Gateway(gc, Collections.<String, String>emptyMap(), dir, null);
        Gateway gwD = new Gateway(gd, Collections.<String, String>emptyMap(), dir, null);
        J2jObjectSelfTest.Fed blue = null, green = null;
        KafkaProducer foreign = null;
        try {
            blue = new J2jObjectSelfTest.Fed(lib, "Blue", "BlueSim", rpr2);
            green = new J2jObjectSelfTest.Fed(lib, "Green", "GreenSim", rpr2);
            gwC.start();
            gwD.start();
            check("both object gateways run, consuming", J2jPduSelfTest.await(() -> consuming(gwC) && consuming(gwD), 10_000));
            int acB = blue.s.objectClass(AIRCRAFT);
            int[] hB = {blue.s.attribute(acB, "Marking"), blue.s.attribute(acB, "ForceIdentifier")};
            blue.s.publishObjectClass(acB, hB);
            int fireB = blue.s.interactionClass(FIRE);
            blue.s.publishInteractionClass(fireB);
            int acG = green.s.objectClass(AIRCRAFT);
            int[] hG = {green.s.attribute(acG, "Marking"), green.s.attribute(acG, "ForceIdentifier")};
            green.s.subscribeObjectClass(acG, hG);
            int fireG = green.s.interactionClass(FIRE);
            green.s.subscribeInteractionClass(fireG);

            blue.reserve("Viper-1");
            long viper = blue.s.registerObject(acB, "Viper-1");
            byte[] mk = codec.encode(m.getClassAttribute(AIRCRAFT, "Marking").getDataType(), J2jObjectSelfTest.marking("VIPER1"));
            byte[] force = codec.encode(m.getClassAttribute(AIRCRAFT, "ForceIdentifier").getDataType(), HValue.ofInt(1));
            blue.s.updateAttributes(viper, hB, new byte[][] {mk, force}, null);
            String[] disc = green.take("discovered", 8000);
            check("Green discovers Viper-1 through the topic", disc != null && "Viper-1".equals(disc[3]));
            long gViper = disc == null ? 0 : Long.parseLong(disc[1]);
            check("with the same Marking and ForceIdentifier octets", Arrays.equals(green.waitValue(gViper, hG[0], 5000), mk)
                    && Arrays.equals(green.waitValue(gViper, hG[1], 5000), force));

            int pIdx = blue.s.parameter(fireB, "FireMissionIndex");
            byte[] idx = codec.encode(m.getClassParameter(FIRE, "FireMissionIndex").getDataType(), HValue.ofInt(4711));
            blue.s.sendInteraction(fireB, new int[] {pIdx}, new byte[][] {idx}, null);
            J2jObjectSelfTest.Fed g = green;
            check("Green receives the WeaponFire", green.take("received", 6000) != null && g.lastInteraction != null
                    && g.lastInteraction.values().stream().anyMatch(v -> Arrays.equals(v, idx)));

            byte[] mk2 = codec.encode(m.getClassAttribute(AIRCRAFT, "Marking").getDataType(), J2jObjectSelfTest.marking("VIPER9"));
            blue.s.updateAttributes(viper, new int[] {hB[0]}, new byte[][] {mk2}, null);
            check("an update reaches Green", J2jPduSelfTest.await(() -> Arrays.equals(g.byInstance.getOrDefault(gViper, Collections.emptyMap()).get(hG[0]), mk2), 6000));

            // the records as JSON
            List<KafkaRecord> recs = read(broker, "t.obj", 3, 5000);
            Map<String, Object> last = null;
            Map<String, Object> fire = null;
            for (KafkaRecord r : recs) {
                Map<String, Object> j = JsonParser.parseObject(str(r.value));
                if ("object".equals(j.get("kind"))) {
                    last = j;
                } else {
                    fire = j;
                }
            }
            Object lastAttrs = last == null ? null : last.get("attributes");
            check("the object record: kind, op, class, name; values=full: the Marking update has the ForceIdentifier too " + last,
                    last != null && "update".equals(last.get("op")) && AIRCRAFT.equals(last.get("class")) && "Viper-1".equals(last.get("name"))
                            && lastAttrs instanceof Map && "Friendly".equals(((Map<?, ?>) lastAttrs).get("ForceIdentifier"))
                            && String.valueOf(((Map<?, ?>) ((Map<?, ?>) lastAttrs).get("Marking")).get("MarkingData")).startsWith("VIPER9"));
            check("the interaction record, its parameter by name " + fire, fire != null && FIRE.equals(fire.get("class"))
                    && Long.valueOf(4711).equals(((Map<?, ?>) fire.get("parameters")).get("FireMissionIndex")));

            // a record from another producer: class without the root, one attribute, an enumerator by name
            KafkaSettings s = new KafkaSettings();
            s.bootstrap = Collections.singletonList(broker.bootstrap());
            foreign = new KafkaProducer(s, new KafkaProducer.Options(), "foreign");
            String rec = "{\"v\":1,\"kind\":\"object\",\"op\":\"update\",\"class\":\"BaseEntity.PhysicalEntity.Platform.Aircraft\","
                    + "\"name\":\"Foreign-1\",\"attributes\":{\"ForceIdentifier\":\"Opposing\"}}";
            foreign.send("t.obj", new KafkaRecord("Foreign-1".getBytes(StandardCharsets.UTF_8), rec.getBytes(StandardCharsets.UTF_8), null, System.currentTimeMillis()));
            String[] f = green.take("discovered", 8000);
            check("a record from another producer: Foreign-1 discovered in Green", f != null && "Foreign-1".equals(f[3]));
            long gf = f == null ? 0 : Long.parseLong(f[1]);
            byte[] opposing = green.waitValue(gf, hG[1], 5000);
            check("its ForceIdentifier Opposing (2)", opposing != null
                    && codec.decode(m.getClassAttribute(AIRCRAFT, "ForceIdentifier").getDataType(), opposing).asLong() == 2);
            String rm = "{\"v\":1,\"kind\":\"object\",\"op\":\"remove\",\"name\":\"Foreign-1\"}";
            foreign.send("t.obj", new KafkaRecord("Foreign-1".getBytes(StandardCharsets.UTF_8), rm.getBytes(StandardCharsets.UTF_8), null, System.currentTimeMillis()));
            check("its remove record removes it in Green", green.take("removed", 6000) != null);
            foreign.send("t.obj", new KafkaRecord(null, "not json".getBytes(StandardCharsets.UTF_8), null, System.currentTimeMillis()));

            blue.reserve("Viper-2");
            long viper2 = blue.s.registerObject(acB, "Viper-2");
            blue.s.updateAttributes(viper2, hB, new byte[][] {mk, force}, null);
            String[] d2 = green.take("discovered", 8000);
            check("Green discovers Viper-2", d2 != null && "Viper-2".equals(d2[3]));

            // Blue deletes Viper-1
            blue.s.deleteObject(viper, null);
            String[] r1 = green.take("removed", 6000);
            check("deleted in Blue: removed in Green", r1 != null && gViper == Long.parseLong(r1[1]));
            String status = Json.toJson(gwD);
            check("gateway status: objects, malformed records counted", status.contains("\"kind\":\"objects\"")
                    && J2jPduSelfTest.await(() -> Json.toJson(gwD).contains("\"malformed\":1"), 3000));

            // the writer stops: its remove records remove Viper-2 in Green
            gwC.stop();
            String[] r2 = green.take("removed", 8000);
            check("the writing gateway stops: Viper-2 removed in Green", r2 != null && d2 != null && r2[1].equals(d2[1]));
        } finally {
            if (foreign != null) {
                foreign.close();
            }
            gwC.stop();
            gwD.stop();
            for (J2jObjectSelfTest.Fed f : new J2jObjectSelfTest.Fed[] {blue, green}) {
                if (f != null) {
                    f.s.resign(false);
                    f.s.disconnect();
                }
            }
        }
    }

    /** A gateway whose cluster cannot be reached does not start, and says why. */
    private static void unreachable(ConfigLoader loader, File junctions, File dir, String rpr2) throws Exception {
        int closed;
        try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) {
            closed = ss.getLocalPort();
        }
        SimwProperties p = new SimwProperties();
        p.set("junction.endpoints", "dis;kafka");
        J2jPduSelfTest.disEndpoint(p, "dis", "dis7", J2jPduSelfTest.freePort(), 1, 1, rpr2);
        p.set("junction.endpoint.kafka.standard", "kafka");
        p.set("junction.endpoint.kafka.kafka.bootstrap", "127.0.0.1:" + closed);
        p.set("junction.router.kind", "pdu");
        GatewayConfig gc = loader.gateway(new File(junctions, "down.junction.settings"), p);
        Gateway gw = new Gateway(gc, Collections.<String, String>emptyMap(), dir, null);
        String code = null;
        String msg = null;
        long t0 = System.currentTimeMillis();
        try {
            gw.start();
        } catch (GatewayException e) {
            code = e.getCode();
            msg = e.getMessage();
        } finally {
            gw.stop();
        }
        check("a cluster that cannot be reached: the gateway does not start (" + code + ": " + msg + ")",
                code != null && msg != null && msg.contains("127.0.0.1:" + closed) && System.currentTimeMillis() - t0 < 20_000);
    }

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }
}
