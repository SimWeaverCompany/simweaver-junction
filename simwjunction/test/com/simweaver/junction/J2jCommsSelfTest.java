package com.simweaver.junction;

import com.simweaver.common.config.SimwProperties;
import com.simweaver.common.hla.codec.HValue;
import com.simweaver.common.hla.codec.HlaCodec;
import com.simweaver.common.hla.session.RtiLibrary;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.j2j.J2jNode;
import com.simweaver.j2j.session.J2jSessions;
import com.simweaver.junction.config.ConfigLoader;
import com.simweaver.junction.config.GatewayConfig;
import com.simweaver.junction.endpoint.j2j.J2jEndpoint;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Talk over J2J (0.10.0): two Talk federations on two J2J servers, each bridged by a Talk gateway
 * whose other side is a J2J object session with the Talk model. Clients are mirrored, a chat
 * message on Ops arrives on the other federation's own Ops channel (another UUID), the lobby passes.
 * Needs the tester's HLA Comms FOM (as CommsBridgeSelfTest); skips without.
 *
 * <pre>java -Dcomms.foms=a.xml;b.xml -cp out/classes:out/test com.simweaver.junction.J2jCommsSelfTest [fom root] [fake rti folder]</pre>
 */
public final class J2jCommsSelfTest {

    private static final String CHAT = "HLAobjectRoot.PitchTalk.Channel.ChatChannel";
    private static final String CLIENT = "HLAobjectRoot.PitchTalk.Client";
    private static final String MSG_TEXT = "HLAinteractionRoot.PitchTalk.Message.Text";
    private static int checks;
    private static int failures;

    private J2jCommsSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        File fomRoot = new File(args.length > 0 ? args[0] : "simwrift/rtdir/fom").getAbsoluteFile();
        File rtiDir = new File(args.length > 1 ? args[1] : "fakerti/build").getAbsoluteFile();
        List<String> talkFoms = com.simweaver.common.TestCommsFom.find(fomRoot);
        if (talkFoms.isEmpty()) {
            System.out.println(com.simweaver.common.TestCommsFom.skipped("J2jCommsSelfTest"));
            System.out.println("0 checks, 0 failures (skipped: no HLA Comms FOM)");
            System.exit(0);
        }
        String talkFom = String.join(";", talkFoms);
        File jar3 = new File(rtiDir, "fakerti-1516e.jar");
        RtiLibrary lib = RtiLibrary.isolated(Collections.singletonList(jar3));
        InformationExchangeModel tm = InformationExchangeModel.fromHlaEvolved(talkFoms);
        HlaCodec tc = new HlaCodec(tm);

        CommsBridgeSelfTest.Fed a = new CommsBridgeSelfTest.Fed(lib, "TalkSiteA", "TalkClientA", talkFom);
        CommsBridgeSelfTest.Fed b = new CommsBridgeSelfTest.Fed(lib, "TalkSiteB", "TalkClientB", talkFom);
        byte[] ua = CommsBridgeSelfTest.uuid(0xA1);
        byte[] opsA = CommsBridgeSelfTest.uuid(0x0A);
        byte[] opsB = CommsBridgeSelfTest.uuid(0x0B);
        a.publishObject(CLIENT, "ClientA", tc, tm, CommsBridgeSelfTest.map("UUID", HValue.ofBytes(ua), "Id", HValue.ofText("client-a"),
                "Status", HValue.ofInt(1)));
        a.publishObject(CHAT, "Ops-A", tc, tm, CommsBridgeSelfTest.map("UUID", HValue.ofBytes(opsA), "Name", HValue.ofText("Ops"),
                "Description", HValue.ofText("")));
        b.publishObject(CHAT, "Ops-B", tc, tm, CommsBridgeSelfTest.map("UUID", HValue.ofBytes(opsB), "Name", HValue.ofText("Ops"),
                "Description", HValue.ofText("")));
        for (CommsBridgeSelfTest.Fed f : new CommsBridgeSelfTest.Fed[] {a, b}) {
            f.subscribeMessages(tm);
            f.subscribeObject(CLIENT, "UUID", "Id");
            f.s.publishInteractionClass(f.s.interactionClass(MSG_TEXT));
        }

        File dir = Files.createTempDirectory("junction-j2j-talk").toFile();
        File junctions = new File(dir, "etc/junctions");
        junctions.mkdirs();
        ConfigLoader loader = new ConfigLoader(junctions, dir);
        GatewayConfig ga = loader.gateway(new File(junctions, "ga.junction.settings"), gateway("TalkSiteA", talkFom, jar3));
        GatewayConfig gb = loader.gateway(new File(junctions, "gb.junction.settings"), gateway("TalkSiteB", talkFom, jar3));
        check("Talk gateways with a J2J side: valid " + ga.getErrors(), ga.isValid() && gb.isValid());

        int pa = J2jObjectSelfTest.freePort(), pb = J2jObjectSelfTest.freePort();
        Path d = Files.createTempDirectory("j2jtalk");
        Files.write(d.resolve("network.settings"), ("network.id=talk-net\nmember.a.addresses=127.0.0.1:" + pa
                + "\nmember.b.addresses=127.0.0.1:" + pb + "\n").getBytes(StandardCharsets.UTF_8));
        Files.write(d.resolve("network.key"), ("k1=" + Base64.getEncoder().encodeToString(new byte[32]) + "\n").getBytes(StandardCharsets.UTF_8));
        J2jNode na = J2jNode.start(J2jObjectSelfTest.cfg(d, "a", pa), "SimWeaver test");
        J2jNode nb = J2jNode.start(J2jObjectSelfTest.cfg(d, "b", pb), "SimWeaver test");
        J2jSessions ha = new J2jSessions(3000), hb = new J2jSessions(3000);
        ha.attach(na);
        hb.attach(nb);
        J2jEndpoint.setHubs(g -> g.startsWith("ga") ? ha : hb);
        Gateway gwA = new Gateway(ga, Collections.<String, String>emptyMap(), dir, null);
        Gateway gwB = new Gateway(gb, Collections.<String, String>emptyMap(), dir, null);
        try {
            check("the servers are linked", J2jObjectSelfTest.await(() -> na.links().link("b") != null, 10000));
            gwA.start();
            gwB.start();
            check("both Talk gateways run: " + gwA.getMessage() + " " + gwB.getMessage(),
                    gwA.getState() == Gateway.State.RUNNING && gwB.getState() == Gateway.State.RUNNING);
            boolean client = b.takeDiscovered("ClientA", 8000) != null;
            if (!client) {
                System.out.println("GA: " + com.simweaver.common.json.Json.toJson(gwA));
                System.out.println("GB: " + com.simweaver.common.json.Json.toJson(gwB));
            }
            check("B discovers the mirrored ClientA", client);

            Thread.sleep(2500);                               // the channels settle in the session
            // the first message on a channel the session lacks creates it there (talk.createmissingchannels), as in a federation
            a.sendText(tm, tc, ua, opsA, "first");
            Thread.sleep(1500);
            b.takeInteraction(MSG_TEXT, 500);
            a.sendText(tm, tc, ua, opsA, "hello ops");
            Map<String, byte[]> text = b.takeInteraction(MSG_TEXT, 6000);
            check("B gets A's Ops text on its own Ops channel", text != null && Arrays.equals(text.get("ChannelUUID"), opsB)
                    && Arrays.equals(text.get("SenderUUID"), ua));
            if (text == null) {
                System.out.println("A: " + com.simweaver.common.json.Json.toJson(gwA.getRouter().objects().toJson()));
                System.out.println("B: " + com.simweaver.common.json.Json.toJson(gwB.getRouter().objects().toJson()));
            }
            a.sendText(tm, tc, ua, new byte[16], "hello lobby");
            text = b.takeInteraction(MSG_TEXT, 6000);
            check("B gets the lobby text", text != null && Arrays.equals(text.get("ChannelUUID"), new byte[16]));
            b.sendText(tm, tc, CommsBridgeSelfTest.uuid(0xB1), opsB, "back");
            text = a.takeInteraction(MSG_TEXT, 6000);
            if (text == null) {
                System.out.println("A: " + com.simweaver.common.json.Json.toJson(gwA.getRouter().objects().toJson()));
                System.out.println("B: " + com.simweaver.common.json.Json.toJson(gwB.getRouter().objects().toJson()));
            }
            check("A gets B's Ops text on its own Ops", text != null && Arrays.equals(text.get("ChannelUUID"), opsA));
        } finally {
            gwA.stop();
            gwB.stop();
            a.s.close();
            b.s.close();
            ha.close();
            hb.close();
            na.stop();
            nb.stop();
        }
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    static SimwProperties gateway(String federation, String talkFom, File jar) {
        SimwProperties p = new SimwProperties();
        p.set("junction.endpoints", "talk;j2j");
        p.set("junction.endpoint.talk.standard", "comms");
        p.set("junction.endpoint.talk.hla.foms", talkFom);
        p.set("junction.endpoint.talk.hla.federation", federation);
        p.set("junction.endpoint.talk.hla.rtijar", jar.getPath());
        p.set("junction.endpoint.j2j.standard", "j2j");
        p.set("junction.endpoint.j2j.j2j.session", "talk-net");
        p.set("junction.endpoint.j2j.hla.foms", talkFom);
        p.set("junction.router.kind", "comms");
        p.set("junction.comms.createmissingchannels", "true");
        p.set("junction.comms.createwaitms", "300");
        return p;
    }

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }
}
