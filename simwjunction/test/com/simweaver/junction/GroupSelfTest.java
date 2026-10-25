package com.simweaver.junction;

import com.simweaver.junction.config.ConfigLoader;
import com.simweaver.junction.config.GatewayConfig;
import com.simweaver.junction.config.GroupVariables;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gateway groups and group variables: substitution in gateway files and kits, defaults,
 * unresolved variables, and starting / stopping a group on the test RTI.
 *
 * <pre>java -cp out/classes:out/test com.simweaver.junction.GroupSelfTest &lt;fom root&gt; &lt;fake rti folder&gt;</pre>
 */
public final class GroupSelfTest {

    private static int checks;
    private static int failures;

    private GroupSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        File fomRoot = new File(args.length > 0 ? args[0] : "simwrift/rtdir/etc/fom").getAbsoluteFile();
        File rtiDir = new File(args.length > 1 ? args[1] : "fakerti/build").getAbsoluteFile();
        String rpr2 = new File(fomRoot, "ieee1516-2010/RPR_FOM_v2.0_Link16_1516-2010.xml").getPath();
        String jar = new File(rtiDir, "fakerti-1516e.jar").getPath();

        Map<String, String> v = new LinkedHashMap<String, String>();
        v.put("GROUPVAR1", "Fed");
        check("token replaced as a whole word", "Fed-x GROUPVAR10 Fed".equals(GroupVariables.apply("GROUPVAR1-x GROUPVAR10 GROUPVAR1", v)));
        check("token inside a name", "radio_Fed".equals(GroupVariables.apply("radio_GROUPVAR1", v)));
        {
            com.simweaver.common.config.SimwProperties kp = new com.simweaver.common.config.SimwProperties();
            kp.set("talk.radio.map.radio_GROUPVAR1", "251000000");
            kp.set("talk.channelmap", "Net1>radio_GROUPVAR1");
            kp.set("junction.groupvar.GROUPVAR1", "Default");
            com.simweaver.common.config.SimwProperties out = GroupVariables.apply(kp, v);
            check("setting names are substituted", "251000000".equals(out.getString("talk.radio.map.radio_Fed", null))
                    && out.getString("talk.radio.map.radio_GROUPVAR1", null) == null);
            check("values too", "Net1>radio_Fed".equals(out.getString("talk.channelmap", null)));
            check("the definitions keep their names", "Default".equals(out.getString("junction.groupvar.GROUPVAR1", null)));
            java.util.Set<String> used = new java.util.TreeSet<String>();
            com.simweaver.common.config.SimwProperties only = new com.simweaver.common.config.SimwProperties();
            only.set("junction.groupvar.GROUPVAR7", "x");
            only.set("talk.radio.map.GROUPVAR3", "1");
            GroupVariables.collect(only, used);
            check("used: names count, definitions do not " + used, used.toString().equals("[GROUPVAR3]"));
        }

        File dir = Files.createTempDirectory("junction-group-test").toFile();
        File j = new File(dir, "etc/junctions");
        new File(j, "endpoints").mkdirs();
        new File(j, "routers").mkdirs();
        write(new File(j, "endpoints/side.endpoint.settings"), "endpoint.standard=ieee1516-2010\n"
                + "endpoint.hla.foms=" + rpr2 + "\nendpoint.hla.rtijar=" + jar + "\nendpoint.hla.federation=GROUPVAR1-side\n");
        write(new File(j, "routers/grp.router.settings"), "router.kind=iem\nrouter.group=Exercise\n");
        write(new File(j, "one.junction.settings"), "junction.name=One GROUPVAR1\njunction.endpoints=a;b\n"
                + "junction.endpoint.a=endpoints/side.endpoint.settings\n"
                + "junction.endpoint.b=endpoints/side.endpoint.settings\n"
                + "junction.endpoint.b.hla.federation=GROUPVAR2\n"
                + "junction.router=routers/grp.router.settings\n"
                + "junction.groupvar.GROUPVAR2=OneB\n");
        write(new File(j, "two.junction.settings"), "junction.name=Two\njunction.group=Exercise\njunction.endpoints=a;b\n"
                + "junction.endpoint.a=endpoints/side.endpoint.settings\n"
                + "junction.endpoint.a.hla.federation=GROUPVAR1-two-a\n"
                + "junction.endpoint.b=endpoints/side.endpoint.settings\n"
                + "junction.endpoint.b.hla.federation=GROUPVAR1-two-b\n"
                + "junction.router.kind=iem\n");
        write(new File(j, "other.junction.settings"), "junction.name=Other\njunction.group=Elsewhere\njunction.endpoints=a;b\n"
                + "junction.endpoint.a=endpoints/side.endpoint.settings\njunction.endpoint.a.hla.federation=X\n"
                + "junction.endpoint.b=endpoints/side.endpoint.settings\njunction.endpoint.b.hla.federation=Y\n");

        ConfigLoader loader = new ConfigLoader(j, dir);
        GatewayConfig one = loader.load().gateway("one");
        check("group from the router kit", "Exercise".equals(one.getGroup()));
        check("variables found in the file and the kit", one.getVariableDefaults().keySet().toString().equals("[GROUPVAR1, GROUPVAR2]"));
        check("GROUPVAR1 unresolved, GROUPVAR2 has a default", one.getUnresolved().equals(Collections.singletonList("GROUPVAR1")));
        check("default applied", "OneB".equals(one.getEndpoints().get("b").getFederation()));
        check("valid (waiting for a value only)", one.isValid());
        GatewayConfig withVars = loader.gateway("one", v);
        check("value replaces the token in the kit", "Fed-side".equals(withVars.getEndpoints().get("a").getFederation()));
        check("and in the name", "One Fed".equals(withVars.getName()));
        check("nothing unresolved", withVars.getUnresolved().isEmpty());

        JunctionManager m = new JunctionManager(dir, j, null, Inspector.NONE);
        try {
            m.start("one", new LinkedHashMap<String, String>());
            check("start without the value fails", false);
        } catch (GatewayException e) {
            check("start without the value: VARIABLES_REQUIRED", "VARIABLES_REQUIRED".equals(e.getCode()));
        }
        Map<String, String> vars = new LinkedHashMap<String, String>();
        vars.put("GROUPVAR1", "FederationX");
        List<JunctionManager.GroupStart> r = m.startGroup("Exercise", vars);
        check("group start: two gateways", r.size() == 2);
        for (JunctionManager.GroupStart s : r) {
            check(s.configId + " started " + (s.error == null ? "" : s.error.getMessage()), s.error == null && s.gateway != null
                    && s.gateway.getState() == Gateway.State.RUNNING);
        }
        Map<String, String> targets = null;
        for (Gateway g : m.gateways()) {
            if (g.getConfig().getId().equals("two")) {
                targets = g.effectiveTargets();
            }
        }
        check("targets from the value: " + targets, targets != null && "FederationX-two-a".equals(targets.get("a"))
                && "FederationX-two-b".equals(targets.get("b")));
        check("the other group is not started", m.gateways().size() == 2);
        check("unknown group", m.startGroup("Nope", vars).isEmpty());
        instances(m);
        m.shutdown();
        descriptions(rpr2, jar);
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** Several instances of a group and of a single gateway: names, refusals, listings, stop per instance. */
    private static void instances(JunctionManager m) throws Exception {
        check("the first start is instance 1", m.gateways().stream().allMatch(g -> "1".equals(g.getInstance()))
                && m.gateways().stream().anyMatch(g -> "two".equals(g.runtimeName())));
        Map<String, String> y = new LinkedHashMap<String, String>();
        y.put("GROUPVAR1", "FederationY");
        List<JunctionManager.GroupStart> r2 = m.startGroup("Exercise", y, null);
        check("a second group start without a name is instance 2", r2.size() == 2 && r2.stream().allMatch(x -> x.error == null
                && "2".equals(x.instance) && "2".equals(x.gateway.getInstance())));
        check("its gateways run under their own names (two-2)", r2.stream().anyMatch(x -> "two-2".equals(x.gateway.runtimeName())));
        check("4 gateways run", m.gateways().size() == 4);
        try {
            m.startGroup("Exercise", y, "2");
            check("starting a running instance fails", false);
        } catch (GatewayException e) {
            check("starting a running instance: INSTANCE_RUNNING, nothing started", "INSTANCE_RUNNING".equals(e.getCode()) && m.gateways().size() == 4);
        }
        try {
            m.startGroup("Exercise", y, "a/b");
            check("a bad instance name fails", false);
        } catch (GatewayException e) {
            check("a bad instance name: INSTANCE_INVALID", "INSTANCE_INVALID".equals(e.getCode()));
        }
        Map<String, String> z = new LinkedHashMap<String, String>();
        z.put("GROUPVAR1", "FederationZ");
        List<JunctionManager.GroupStart> ra = m.startGroup("Exercise", z, " Övning A ");
        check("a named instance (trimmed, any letters)", ra.stream().allMatch(x -> x.error == null && "Övning A".equals(x.instance))
                && ra.stream().anyMatch(x -> "two-_vning_A".equals(x.gateway.runtimeName())));

        String js = com.simweaver.common.json.Json.toJson(m.jsonGroups());
        check("groups listing: instances 1, 2 and Övning A, complete, with their values; next is 3",
                js.contains("\"instance\":\"1\"") && js.contains("\"instance\":\"2\"") && js.contains("\"instance\":\"Övning A\"")
                && js.contains("\"GROUPVAR1\":\"FederationY\"") && js.contains("\"GROUPVAR1\":\"FederationZ\"")
                && js.contains("\"complete\":true") && !js.contains("\"complete\":false") && js.contains("\"nextinstance\":\"3\"")
                && js.indexOf("\"instance\":\"1\"") < js.indexOf("\"instance\":\"2\"")
                && js.indexOf("\"instance\":\"2\"") < js.indexOf("\"instance\":\"Övning A\""));

        // a single gateway: a second start of the same configuration is instance 2
        Gateway o1 = m.start("other", new LinkedHashMap<String, String>(), z, null);
        Gateway o2 = m.start("other", new LinkedHashMap<String, String>(), z, null);
        check("single gateway started twice: instances 1 and 2", "1".equals(o1.getInstance()) && "2".equals(o2.getInstance()));
        String cj = com.simweaver.common.json.Json.toJson(m.jsonConfigs());
        check("configs listing: other runs as 1 and 2, next 3", cj.contains("\"instances\":[\"1\",\"2\"]") && cj.contains("\"nextinstance\":\"3\""));
        try {
            m.start("two", new LinkedHashMap<String, String>(), z, "Övning A");
            check("a single start of a running instance fails", false);
        } catch (GatewayException e) {
            check("a single start of a running instance: INSTANCE_RUNNING", "INSTANCE_RUNNING".equals(e.getCode()));
        }

        // stop one instance of the group; its number is free again
        check("stop instance 2 of the group: 2 gateways", m.stopGroup("Exercise", "2") == 2 && m.gateways().size() == 6);
        check("instance 2 no longer runs, 1 does", !m.groupInstanceRuns("Exercise", "2") && m.groupInstanceRuns("Exercise", "1"));
        check("next group instance is 2 again", "2".equals(m.nextInstanceOfGroup("Exercise")));

        // a gateway of an instance stops; it can be started again as that instance alone
        Gateway twoA = m.gateways().stream().filter(g -> "two".equals(g.getConfig().getId()) && "Övning A".equals(g.getInstance())).findFirst().orElse(null);
        check("found two of Övning A", twoA != null && m.stop(twoA.getId()));
        check("Övning A is incomplete now", com.simweaver.common.json.Json.toJson(m.jsonGroups()).contains("\"complete\":false"));
        Gateway back = m.start("two", new LinkedHashMap<String, String>(), z, "Övning A");
        check("two started again as Övning A completes the instance", "Övning A".equals(back.getInstance())
                && !com.simweaver.common.json.Json.toJson(m.jsonGroups()).contains("\"complete\":false"));

        check("group stop without an instance stops every instance (4)", m.stopGroup("Exercise") == 4);
        check("the other configuration's instances stop on their own", m.stop(o1.getId()) && m.stop(o2.getId()) && m.gateways().isEmpty());
    }

    /** Variable descriptions (0.9.16): labels only; the variables work as before. */
    private static void descriptions(String rpr2, String jar) throws Exception {
        File dir = Files.createTempDirectory("junction-desc-test").toFile();
        File j = new File(dir, "etc/junctions");
        new File(j, "endpoints").mkdirs();
        write(new File(j, "endpoints/side.endpoint.settings"), "endpoint.standard=ieee1516-2010\n"
                + "endpoint.hla.foms=" + rpr2 + "\nendpoint.hla.rtijar=" + jar + "\n");
        // alpha describes GROUPVAR1 and GROUPVAR3 (only bravo uses GROUPVAR3); the text may name variables
        write(new File(j, "alpha.junction.settings"), "junction.name=Alpha\njunction.group=Trip\njunction.endpoints=a;b\n"
                + "junction.endpoint.a=endpoints/side.endpoint.settings\njunction.endpoint.a.hla.federation=GROUPVAR1-a\n"
                + "junction.endpoint.b=endpoints/side.endpoint.settings\njunction.endpoint.b.hla.federation=GROUPVAR2-b\n"
                + "junction.groupvar.GROUPVAR2=Kiruna\n"
                + "junction.groupvar.GROUPVAR1.desc=Shoe size\n"
                + "junction.groupvar.groupvar3.DESC=Not GROUPVAR4, the colour\n"
                + "junction.router.kind=iem\n");
        write(new File(j, "bravo.junction.settings"), "junction.name=Bravo\njunction.group=Trip\njunction.endpoints=a;b\n"
                + "junction.endpoint.a=endpoints/side.endpoint.settings\njunction.endpoint.a.hla.federation=GROUPVAR3-a\n"
                + "junction.endpoint.b=endpoints/side.endpoint.settings\njunction.endpoint.b.hla.federation=GROUPVAR2-c\n"
                + "junction.groupvar.GROUPVAR2=Kiruna\njunction.groupvar.GROUPVAR2.desc=Home city\n"
                + "junction.groupvar.GROUPVAR1.desc=Another text\n"
                + "junction.router.kind=iem\n");
        write(new File(j, "lone.junction.settings"), "junction.name=Lone\njunction.endpoints=a;b\n"
                + "junction.endpoint.a=endpoints/side.endpoint.settings\njunction.endpoint.a.hla.federation=L1\n"
                + "junction.endpoint.b=endpoints/side.endpoint.settings\njunction.endpoint.b.hla.federation=L2\n"
                + "junction.groupvar.GROUPVAR7.desc=Nobody uses me\njunction.groupvar.HOME.desc=x\n"
                + "junction.router.kind=iem\n");
        com.simweaver.junction.config.ConfigLoader.Result r = new ConfigLoader(j, dir).load();
        GatewayConfig alpha = r.gateway("alpha");
        check("descriptions read (any case of the name and of .desc) " + alpha.getVariableDescriptions(),
                "Shoe size".equals(alpha.getVariableDescriptions().get("GROUPVAR1"))
                && "Not GROUPVAR4, the colour".equals(alpha.getVariableDescriptions().get("GROUPVAR3")));
        check("a description is not a variable, nor does it use one " + alpha.getVariableDefaults().keySet(),
                alpha.getVariableDefaults().keySet().toString().equals("[GROUPVAR1, GROUPVAR2]"));
        check("no warning for the description keys in a group " + alpha.getWarnings(), alpha.getWarnings().isEmpty());
        String aj = com.simweaver.common.json.Json.toJson(alpha);
        check("the configuration lists them with its variables", aj.contains("\"name\":\"GROUPVAR1\",\"description\":\"Shoe size\"")
                && aj.contains("\"default\":\"Kiruna\""));
        check("the target as written, for Discover: from the gateway file " + alpha.getEndpoints().get("a").getTargetTemplate(),
                "GROUPVAR1-a".equals(alpha.getEndpoints().get("a").getTargetTemplate()) && aj.contains("\"targettemplate\":\"GROUPVAR1-a\""));
        GatewayConfig lone = r.gateway("lone");
        check("no template for a target without variables", lone.getEndpoints().get("a").getTargetTemplate() == null);
        check("outside a group: an unused description and a bad name are warned about " + lone.getWarnings(),
                lone.getWarnings().stream().anyMatch(w -> w.contains("GROUPVAR7.desc describes a variable the settings do not use"))
                && lone.getWarnings().stream().anyMatch(w -> w.contains("junction.groupvar.HOME.desc")));
        check("and change nothing else", lone.isValid() && lone.getUnresolved().isEmpty());

        JunctionManager m = new JunctionManager(dir, j, null, Inspector.NONE);
        String groups = com.simweaver.common.json.Json.toJson(m.jsonGroups());
        check("the group: descriptions from any of its gateways, the first wins " + groups,
                groups.contains("\"name\":\"GROUPVAR1\",\"description\":\"Shoe size\"")
                && groups.contains("\"name\":\"GROUPVAR2\",\"default\":\"Kiruna\",\"description\":\"Home city\"")
                && groups.contains("\"name\":\"GROUPVAR3\",\"description\":\"Not GROUPVAR4, the colour\""));
        Map<String, String> v = new LinkedHashMap<String, String>();
        v.put("GROUPVAR1", "42");
        v.put("GROUPVAR3", "Blue");
        List<JunctionManager.GroupStart> st = m.startGroup("Trip", v);
        check("the group starts with the values as before " + st.stream().filter(x -> x.error != null).map(x -> x.error.getMessage()).collect(java.util.stream.Collectors.toList()),
                st.size() == 2 && st.stream().allMatch(x -> x.error == null));
        Gateway ga = st.stream().filter(x -> x.configId.equals("alpha")).findFirst().get().gateway;
        check("targets substituted as before " + ga.effectiveTargets(), "42-a".equals(ga.effectiveTargets().get("a"))
                && "Kiruna-b".equals(ga.effectiveTargets().get("b")));
        String gj = com.simweaver.common.json.Json.toJson(ga);
        check("a running gateway carries the descriptions of its variables " + gj.replaceAll(".*(\"vardescriptions\":\\{[^}]*\\}).*", "$1"),
                gj.contains("\"vardescriptions\":{\"GROUPVAR1\":\"Shoe size\"}"));
        m.shutdown();
    }

    private static void write(File f, String text) throws Exception {
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what);
        }
    }
}
