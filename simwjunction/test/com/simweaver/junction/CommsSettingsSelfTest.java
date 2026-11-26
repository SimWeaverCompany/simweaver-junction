package com.simweaver.junction;

import com.simweaver.common.config.SimwProperties;
import com.simweaver.common.json.Json;
import com.simweaver.junction.config.ConfigLoader;
import com.simweaver.junction.config.EndpointConfig;
import com.simweaver.junction.config.EndpointType;
import com.simweaver.junction.config.GatewayConfig;
import com.simweaver.junction.config.RouterConfig;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/**
 * 0.14.1: the HLA Comms settings ({@code endpoint.standard=comms}, {@code router.kind=comms}, {@code comms.*},
 * {@code junction.comms.*}, {@code endpoint.comms.hlastandard}) and the old names that must keep working
 * ({@code talk}, {@code pitchtalk}, {@code talk.*}, {@code junction.talk.*}, {@code endpoint.talk.hlastandard}):
 * which wins when both are given, the JSON names, the delivered samples (also the owner's ASIM files, which use
 * the old names). No RTI and no HLA Comms FOM needed.
 *
 * <pre>java -cp out/classes:out/test com.simweaver.junction.CommsSettingsSelfTest [junctions root]</pre>
 */
public final class CommsSettingsSelfTest {

    private static int checks;
    private static int failures;

    private CommsSettingsSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        File etc = new File(args.length > 0 ? args[0] : "simwrift/rtdir/etc").getAbsoluteFile();
        types();
        routers();
        gateways();
        samples(new File(etc, "junctions"));
        samples(new File(etc, "junctions.dist"));
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void types() {
        check("endpoint.standard=comms is HLA Comms", EndpointType.fromSetting("comms") == EndpointType.COMMS);
        check("its id and label", "comms".equals(EndpointType.COMMS.setting()) && "HLA Comms".equals(EndpointType.COMMS.label()));
        check("also HLA-Comms, hla_comms", EndpointType.fromSetting("HLA-Comms") == EndpointType.COMMS
                && EndpointType.fromSetting("hla_comms") == EndpointType.COMMS);
        check("the old talk and pitchtalk still work", EndpointType.fromSetting("talk") == EndpointType.COMMS
                && EndpointType.fromSetting("PitchTalk") == EndpointType.COMMS);
        for (EndpointType t : EndpointType.values()) {
            check("no label says talk: " + t.label(), !t.label().toLowerCase(Locale.ROOT).contains("talk"));
        }
    }

    private static void routers() {
        SimwProperties p = new SimwProperties();
        p.set("router.kind", "comms");
        p.set("comms.mode", "messages");
        p.set("comms.messages", "audio;text");
        p.set("comms.channelmap.home", "Net Alpha>Bravo Net");
        p.set("comms.channeluuidmap.home", "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0>00112233-4455-6677-8899-aabbccddeeff");
        p.set("comms.radio.map.Net1", "251000000");
        p.set("comms.radio.endpoint", "radio");
        p.set("comms.audio.samplerate", "8000");
        RouterConfig r = new RouterConfig("r", p, "r", new File("."));
        check("router.kind=comms", r.getKind() == RouterConfig.Kind.COMMS && r.getErrors().isEmpty() && r.getWarnings().isEmpty());
        check("comms.* read", r.isTalkMessagesOnly() && r.getTalkMessageKinds().size() == 2 && r.getTalkSampleRate() == 8000
                && "radio".equals(r.getTalkRadioEndpoint()) && r.getTalkRadioMap().get("Net1") == 251000000L);
        check("scoped comms.channelmap.<endpoint> and comms.channeluuidmap.<endpoint>",
                "Bravo Net".equals(r.getTalkChannelMap().get("home").get("Net Alpha"))
                        && "00112233445566778899aabbccddeeff".equals(r.getTalkChannelUuidMap().get("home").get("0f1e2d3c4b5a69788796a5b4c3d2e1f0")));
        String js = Json.toJson(r.toJson());
        check("JSON: kind comms, comms* fields, no talk anywhere: " + js, js.contains("\"kind\":\"comms\"") && js.contains("\"commsmode\":\"messages\"")
                && js.contains("\"commschannelmap\"") && js.contains("comms.mode=messages") && !js.toLowerCase(Locale.ROOT).contains("talk"));

        SimwProperties o = new SimwProperties();
        o.set("router.kind", "talk");
        o.set("talk.mode", "messages");
        o.set("talk.channelmap.home", "A>B");
        o.set("talk.radio.map.Net1", "36500000");
        o.set("talk.channels", "none");
        RouterConfig ro = new RouterConfig("o", o, "o", new File("."));
        check("the old router.kind=talk and talk.* keys still work", ro.getKind() == RouterConfig.Kind.COMMS && ro.isTalkMessagesOnly()
                && "B".equals(ro.getTalkChannelMap().get("home").get("A")) && ro.getTalkRadioMap().get("Net1") == 36500000L && ro.isTalkLobbyOnly());
        check("warnings name the comms.* keys: " + ro.getWarnings(), ro.getWarnings().size() == 1 && ro.getWarnings().get(0).contains("comms.channels=none")
                && !ro.getWarnings().get(0).contains("talk"));
        check("the JSON shows them as comms.* and router.kind=comms", Json.toJson(ro.toJson()).contains("comms.mode=messages")
                && Json.toJson(ro.toJson()).contains("router.kind=comms") && !Json.toJson(ro.toJson()).toLowerCase(Locale.ROOT).contains("talk"));
        SimwProperties pt = new SimwProperties();
        pt.set("router.kind", "pitchtalk");
        check("router.kind=pitchtalk still works", new RouterConfig("x", pt, "x", new File(".")).getKind() == RouterConfig.Kind.COMMS);

        SimwProperties both = new SimwProperties();
        both.set("router.kind", "comms");
        both.set("talk.mode", "messages");
        both.set("comms.mode", "full");
        both.set("talk.audio.samplerate", "8000");
        RouterConfig rb = new RouterConfig("b", both, "b", new File("."));
        check("comms.<key> wins over talk.<key>; a talk.<key> alone is read", !rb.isTalkMessagesOnly() && rb.getTalkSampleRate() == 8000);
        check("the caller's properties are not changed", both.isSet("talk.mode") && both.isSet("talk.audio.samplerate") && !both.isSet("comms.audio.samplerate"));

        SimwProperties bad = new SimwProperties();
        bad.set("router.kind", "nonsense");
        RouterConfig rn = new RouterConfig("n", bad, "n", new File("."));
        check("an unknown kind lists comms: " + rn.getWarnings(), rn.getWarnings().toString().contains("(iem, comms, pdu)"));
    }

    private static void gateways() throws Exception {
        File dir = Files.createTempDirectory("junction-comms-settings").toFile();
        File junctions = new File(dir, "etc/junctions");
        new File(junctions, "routers").mkdirs();
        Files.write(new File(junctions, "routers/old.router.settings").toPath(),
                "router.kind=talk\ntalk.mode=messages\ntalk.audio.samplerate=8000\n".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(junctions, "routers/new.router.settings").toPath(),
                "router.kind=comms\ncomms.mode=messages\ncomms.audio.samplerate=8000\n".getBytes(StandardCharsets.UTF_8));
        ConfigLoader loader = new ConfigLoader(junctions, dir);

        GatewayConfig g = loader.gateway(new File(junctions, "c.junction.settings"), gateway("comms", "comms", null,
                "junction.comms.mode", "messages", "junction.comms.radio.map.Net1", "251000000", "junction.endpoint.a.comms.hlastandard", "ieee1516-2025"));
        check("a gateway with comms endpoints, router and junction.comms.* keys: valid " + g.getErrors(), g.isValid());
        check("inline junction.comms.* read", g.getRouter().isTalkMessagesOnly() && g.getRouter().getTalkRadioMap().get("Net1") == 251000000L);
        EndpointConfig a = g.getEndpoints().get("a");
        EndpointConfig b = g.getEndpoints().get("b");
        check("endpoint.comms.hlastandard=ieee1516-2025", a.getType() == EndpointType.COMMS && a.isHla4() && !b.isHla4());
        String ej = Json.toJson(a.toJson());
        check("endpoint JSON iscomms, standard comms: " + ej, ej.contains("\"iscomms\":true") && !ej.contains("istalk"));

        GatewayConfig o = loader.gateway(new File(junctions, "o.junction.settings"), gateway("talk", "talk", null,
                "junction.talk.mode", "messages", "junction.endpoint.a.talk.hlastandard", "ieee1516-2025"));
        check("the old names in a gateway file still work " + o.getErrors(), o.isValid() && o.getRouter().getKind() == RouterConfig.Kind.COMMS
                && o.getRouter().isTalkMessagesOnly() && o.getEndpoints().get("a").isHla4());

        GatewayConfig k1 = loader.gateway(new File(junctions, "k1.junction.settings"), gateway("comms", null, "routers/old.router.settings",
                "junction.comms.mode", "full"));
        check("inline junction.comms.mode overrides the kit's talk.mode", k1.isValid() && !k1.getRouter().isTalkMessagesOnly()
                && k1.getRouter().getTalkSampleRate() == 8000);
        GatewayConfig k2 = loader.gateway(new File(junctions, "k2.junction.settings"), gateway("comms", null, "routers/new.router.settings",
                "junction.talk.mode", "full"));
        check("inline junction.talk.mode (old) overrides the kit's comms.mode", k2.isValid() && !k2.getRouter().isTalkMessagesOnly());
        GatewayConfig k3 = loader.gateway(new File(junctions, "k3.junction.settings"), gateway("comms", null, "routers/new.router.settings",
                "junction.talk.mode", "messages", "junction.comms.mode", "full"));
        check("inline: junction.comms.mode wins over junction.talk.mode", k3.isValid() && !k3.getRouter().isTalkMessagesOnly());

        GatewayConfig one = loader.gateway(new File(junctions, "one.junction.settings"), single());
        check("the error names HLA Comms and comms.radio.endpoint: " + one.getErrors(), !one.isValid()
                && one.getErrors().toString().contains("an HLA Comms router needs two HLA Comms endpoints, or one and comms.radio.endpoint"));
    }

    private static SimwProperties gateway(String standard, String kind, String routerKit, String... more) {
        SimwProperties p = new SimwProperties();
        p.set("junction.name", "HLA Comms settings");
        p.set("junction.endpoints", "a;b");
        p.set("junction.endpoint.a.standard", standard);
        p.set("junction.endpoint.a.hla.federation", "CommsA");
        p.set("junction.endpoint.b.standard", standard);
        p.set("junction.endpoint.b.hla.federation", "CommsB");
        if (kind != null) {
            p.set("junction.router.kind", kind);
        }
        if (routerKit != null) {
            p.set("junction.router", routerKit);
        }
        for (int i = 0; i + 1 < more.length; i += 2) {
            p.set(more[i], more[i + 1]);
        }
        return p;
    }

    private static SimwProperties single() {
        SimwProperties p = new SimwProperties();
        p.set("junction.name", "One HLA Comms endpoint");
        p.set("junction.endpoints", "a;d");
        p.set("junction.endpoint.a.standard", "comms");
        p.set("junction.endpoint.a.hla.federation", "CommsA");
        p.set("junction.endpoint.d.standard", "dis7");
        p.set("junction.router.kind", "comms");
        return p;
    }

    /** Every delivered HLA Comms sample loads, the ASIM ones too; 0.14.1: no sample file says the old name any more. */
    private static void samples(File junctions) {
        ConfigLoader.Result r = new ConfigLoader(junctions, junctions.getParentFile().getParentFile()).load();
        int comms = 0;
        for (GatewayConfig g : r.getGateways()) {
            if (g.getRouter().getKind() != RouterConfig.Kind.COMMS) {
                continue;
            }
            comms++;
            check(junctions.getName() + "/" + g.getId() + ": valid " + g.getErrors(), g.isValid());
            for (EndpointConfig e : g.getEndpoints().values()) {
                check(junctions.getName() + "/" + g.getId() + " " + e.getId() + ": an HLA Comms endpoint (or the radio)",
                        e.getType() == EndpointType.COMMS || e.getType().isDis());
            }
        }
        check(junctions.getName() + ": HLA Comms gateways found (" + comms + ")", comms >= (junctions.getName().endsWith(".dist") ? 4 : 1));
        int kits = 0;
        for (RouterConfig k : r.getRouterKits()) {
            if (k.getKind() == RouterConfig.Kind.COMMS) {
                kits++;
                check(junctions.getName() + " router kit " + k.getId() + ": no warnings " + k.getWarnings(),
                        k.getWarnings().isEmpty() && k.getErrors().isEmpty());
            }
        }
        check(junctions.getName() + ": the comms router kits", kits >= (junctions.getName().endsWith(".dist") ? 4 : 3));
        for (EndpointConfig e : r.getEndpointKits()) {
            if (e.getType() == EndpointType.COMMS) {
                check(junctions.getName() + " endpoint kit " + e.getId() + ": hla-comms or an ASIM kit",
                        "hla-comms".equals(e.getId()) || e.getId().matches("ASIM-(INT|EXT)-comms"));
            }
        }
        // the files themselves, comments included: the new names only (the old ones are still read, undocumented)
        java.util.List<String> old = new java.util.ArrayList<>();
        try (java.util.stream.Stream<java.nio.file.Path> files = Files.walk(junctions.toPath())) {
            files.filter(Files::isRegularFile).forEach(f -> {
                try {
                    String t = new String(Files.readAllBytes(f), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                    if (t.contains("talk") || f.getFileName().toString().toLowerCase(Locale.ROOT).contains("talk")) {
                        old.add(junctions.toPath().relativize(f).toString());
                    }
                } catch (java.io.IOException ex) {
                    old.add(f + ": " + ex);
                }
            });
        } catch (java.io.IOException ex) {
            old.add(String.valueOf(ex));
        }
        check(junctions.getName() + ": no file name or text with the old name " + old, old.isEmpty());
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
