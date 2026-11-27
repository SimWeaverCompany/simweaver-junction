package com.simweaver.junction;

import com.simweaver.common.hla.session.LsdOptions;
import com.simweaver.common.json.Json;
import com.simweaver.junction.config.EndpointConfig;
import com.simweaver.junction.config.GatewayConfig;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * LSDs as a list of choices (0.9.15): {@code endpoint.hla.lsd=a;b;c}, the first the default, any
 * other value given at the start. The test RTI refuses the LSD {@code fakerti:refuse}, which shows
 * which LSD an endpoint really connects with.
 *
 * <pre>java -cp out/classes:out/test:&lt;HLA API jars&gt; com.simweaver.junction.LsdSelfTest &lt;fom root&gt; &lt;fake rti folder&gt;</pre>
 */
public final class LsdSelfTest {

    private static int checks;
    private static int failures;

    private LsdSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        File fomRoot = new File(args.length > 0 ? args[0] : "simwrift/rtdir/fom").getAbsoluteFile();
        File rtiDir = new File(args.length > 1 ? args[1] : "fakerti/build").getAbsoluteFile();
        options();
        gateways(fomRoot, rtiDir);
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ the setting

    private static void options() {
        check("not set: the RTI default only", LsdOptions.parse(null).equals(Collections.singletonList("")));
        check("empty: the RTI default only", LsdOptions.parse("").equals(Collections.singletonList("")));
        check("one value", LsdOptions.parse(" crc@host:8989 ").equals(Collections.singletonList("crc@host:8989")));
        check("a list, trimmed, the first is the default",
                LsdOptions.parse("default ; crc2;crc@10.0.0.5:8989").equals(Arrays.asList("default", "crc2", "crc@10.0.0.5:8989")));
        check("the same value twice is listed once", LsdOptions.parse("a;b;a").equals(Arrays.asList("a", "b")));
        check("an empty entry offers the RTI default", LsdOptions.parse("a;;b").equals(Arrays.asList("a", "", "b"))
                && LsdOptions.parse(";a").equals(Arrays.asList("", "a")));
        check("usable LSDs", LsdOptions.problem(null) == null && LsdOptions.problem("") == null
                && LsdOptions.problem("crc@host:8989") == null && LsdOptions.problem("a;b") == null);
        char[] big = new char[LsdOptions.MAX_LENGTH + 1];
        Arrays.fill(big, 'x');
        check("too long", LsdOptions.problem(new String(big)) != null);
        check("control characters", LsdOptions.problem("a\nb") != null && LsdOptions.problem("a\tb") != null);
        check("label", "(RTI default)".equals(LsdOptions.label("")) && "x".equals(LsdOptions.label("x")));
    }

    // ------------------------------------------------------------------ gateways on the test RTI

    private static void gateways(File fomRoot, File rtiDir) throws Exception {
        String rpr2 = new File(fomRoot, "ieee1516-2010/RPR_FOM_v2.0_Link16_1516-2010.xml").getPath();
        String jar = new File(rtiDir, "fakerti-1516e.jar").getPath();
        File dir = Files.createTempDirectory("junction-lsd-test").toFile();
        File j = new File(dir, "etc/junctions");
        new File(j, "endpoints").mkdirs();
        write(new File(j, "endpoints/side.endpoint.settings"), "endpoint.standard=ieee1516-2010\n"
                + "endpoint.hla.foms=" + rpr2 + "\nendpoint.hla.rtijar=" + jar + "\n"
                + "endpoint.hla.lsd=default;crc2;fakerti:refuse\n");
        write(new File(j, "pair.junction.settings"), "junction.name=Pair\njunction.group=G\njunction.endpoints=a;b\n"
                + "junction.endpoint.a=endpoints/side.endpoint.settings\njunction.endpoint.a.hla.federation=LsdA\n"
                + "junction.endpoint.b=endpoints/side.endpoint.settings\njunction.endpoint.b.hla.federation=LsdB\n"
                + "junction.router.kind=iem\n");
        write(new File(j, "vars.junction.settings"), "junction.name=Vars\njunction.group=G\njunction.endpoints=a;b\n"
                + "junction.endpoint.a=endpoints/side.endpoint.settings\njunction.endpoint.a.hla.federation=LsdC\n"
                + "junction.endpoint.a.hla.lsd=GROUPVAR1;other\n"
                + "junction.endpoint.b=endpoints/side.endpoint.settings\njunction.endpoint.b.hla.federation=LsdD\n"
                + "junction.groupvar.GROUPVAR1=fromvar\njunction.router.kind=iem\n");

        RecordingInspector insp = new RecordingInspector();
        JunctionManager m = new JunctionManager(dir, j, null, insp);
        com.simweaver.junction.config.ConfigLoader.Result loaded = new com.simweaver.junction.config.ConfigLoader(j, dir).load();
        GatewayConfig pair = loaded.gateway("pair");
        EndpointConfig a = pair.getEndpoints().get("a");
        check("the default is the first choice", "default".equals(a.getLsd()));
        check("all choices", a.getLsdOptions().equals(Arrays.asList("default", "crc2", "fakerti:refuse")));
        String cj = Json.toJson(pair);
        check("the configuration lists them", cj.contains("\"lsd\":\"default\"") && cj.contains("\"lsds\":[\"default\",\"crc2\",\"fakerti:refuse\"]"));
        EndpointConfig va = loaded.gateway("vars").getEndpoints().get("a");
        check("group variables in the choices: " + va.getLsdOptions(), va.getLsdOptions().equals(Arrays.asList("fromvar", "other")));

        // default
        Gateway g1 = m.start("pair", new LinkedHashMap<String, String>(), new LinkedHashMap<String, String>(), "1");
        String j1 = Json.toJson(g1);
        check("without a choice: the default", j1.contains("\"lsd\":\"default\"") && !j1.contains("\"lsdoverridden\":true"));
        check("effective LSDs", g1.effectiveLsds().equals(map("a", "default", "b", "default")));

        // a refused LSD for one endpoint: the start fails, so the endpoint really used it
        String why = null;
        try {
            m.start("pair", new LinkedHashMap<String, String>(), new LinkedHashMap<String, String>(), "2", map("a", "fakerti:refuse"));
        } catch (GatewayException e) {
            why = e.getMessage();
        }
        check("the chosen LSD is the one connected with: " + why, why != null && why.contains("refuses the LSD fakerti:refuse"));
        check("and the failed instance is not left running", m.gateways().size() == 1);

        // another choice, and a typed value that is not among them
        Gateway g2 = m.start("pair", new LinkedHashMap<String, String>(), new LinkedHashMap<String, String>(), "2", map("a", "crc2", "b", "my-own"));
        String j2 = Json.toJson(g2);
        check("another configured choice", g2.getState() == Gateway.State.RUNNING && j2.contains("\"lsd\":\"crc2\""));
        check("a typed LSD", j2.contains("\"lsd\":\"my-own\""));
        check("both shown as chosen", j2.split("\"lsdoverridden\":true", -1).length == 3);
        Gateway g3 = m.start("pair", new LinkedHashMap<String, String>(), new LinkedHashMap<String, String>(), "3", map("a", ""));
        check("an empty LSD: the RTI default", "".equals(g3.effectiveLsds().get("a")) && "default".equals(g3.effectiveLsds().get("b")));
        Gateway g4 = m.start("pair", new LinkedHashMap<String, String>(), map("GROUPVAR3", "viaVar"), "4", map("a", "crc-GROUPVAR3"));
        check("group variables in a chosen LSD are replaced", "crc-viaVar".equals(g4.effectiveLsds().get("a")));

        // mistakes
        check("an unknown endpoint", code(m, map("x", "a")).equals("LSD_INVALID"));
        check("a line break", code(m, map("a", "a\nb")).equals("LSD_INVALID"));

        // Inspect uses the LSD the endpoint connects with
        m.inspect(g2.getId(), "a");
        check("Inspect gets the endpoint's LSD: " + insp.last, insp.last != null && "crc2".equals(insp.last.lsd));
        m.inspect(g1.getId(), "b");
        check("and the default for an endpoint without a choice", insp.last != null && "default".equals(insp.last.lsd));
        for (Gateway g : m.gateways()) {
            m.stop(g.getId());
        }

        // a group: one gateway with a refused LSD, the other starts
        Map<String, Map<String, String>> gl = new LinkedHashMap<String, Map<String, String>>();
        gl.put("vars", map("b", "fakerti:refuse"));
        List<JunctionManager.GroupStart> r = m.startGroup("G", new LinkedHashMap<String, String>(), "7", gl);
        String pairErr = null, varsErr = null;
        for (JunctionManager.GroupStart s : r) {
            if (s.configId.equals("pair")) {
                pairErr = s.error == null ? "" : s.error.getMessage();
            } else {
                varsErr = s.error == null ? "" : s.error.getMessage();
            }
        }
        check("group: LSDs per gateway and endpoint (" + pairErr + " / " + varsErr + ")",
                "".equals(pairErr) && varsErr != null && varsErr.contains("refuses"));
        String code = null;
        try {
            gl.clear();
            gl.put("nope", map("a", "x"));
            m.startGroup("G", new LinkedHashMap<String, String>(), "8", gl);
        } catch (GatewayException e) {
            code = e.getCode();
        }
        check("group: a gateway that is not in the group", "LSD_INVALID".equals(code));
        m.shutdown();
    }

    private static String code(JunctionManager m, Map<String, String> lsds) {
        try {
            Gateway g = m.start("pair", new LinkedHashMap<String, String>(), new LinkedHashMap<String, String>(), "9", lsds);
            m.stop(g.getId());
            return "started";
        } catch (GatewayException e) {
            return e.getCode();
        }
    }

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static final class RecordingInspector implements Inspector {
        volatile EndpointRef last;
        private int n;

        @Override
        public synchronized String inspect(EndpointRef endpoint) {
            last = endpoint;
            return "ds-" + (++n);
        }

        @Override
        public boolean exists(String datasourceId) {
            return false;
        }

        @Override
        public void remove(String datasourceId) {
        }

        @Override
        public String pageOf(String datasourceId) {
            return "datasource.html?id=" + datasourceId;
        }

        @Override
        public void advertise(Supplier<List<EndpointRef>> endpoints) {
        }
    }

    private static void write(File f, String text) throws Exception {
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
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
