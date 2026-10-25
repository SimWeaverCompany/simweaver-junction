package com.simweaver.common.config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Dependency-free self-test for {@link SimwProperties}. Run with:
 * <pre>java -cp out com.simweaver.config.SimwPropertiesSelfTest</pre>
 * Exits non-zero if anything fails.
 */
public final class SimwPropertiesSelfTest {

    private static int checks = 0;
    private static int failures = 0;

    public enum Mode { BEST_EFFORT, RELIABLE, TIMESTAMP_ORDER }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("simwprops");

        Path base = write(dir.resolve("base.properties"),
                "# base configuration",
                "! also a comment",
                "",
                "rti.host = localhost",
                "rti.port=8989",
                "federation:SimWeaverFederation",
                "federate.name  ImplicitSeparator",
                "empty.value=",
                "no.separator",
                "trailing.trimmed = value   ",
                "trailing.kept = value\\ ",
                "escaped.key\\ with\\ spaces = ok",
                "windows.path = C\\:\\\\temp\\\\fom",
                "unicode = \\u00e5\\u00e4\\u00f6",
                "utf8 = raksmorgas-\u00e5\u00e4\u00f6",
                "tabbed = a\\tb",
                "continued = one, \\",
                "            two, \\",
                "            three",
                "hosts = alpha;beta ; gamma;",
                "ports = 1;2;3",
                "ratios = 1.5;2,5;3",
                "flags = true;no;on",
                "hex = 0xFF",
                "binary = 0b1010",
                "grouped = 1_000_000",
                "negative = -42",
                "leading.zero = 0755",
                "swedish.double = 3,14",
                "sci = 1.5e3",
                "flag.on = ON",
                "flag.off = disabled",
                "letter = X",
                "mode = best-effort",
                "timeout = 30s",
                "heartbeat = 250ms",
                "buffer = 64k",
                "big.buffer = 8M",
                "rti.password = hunter2",
                "not.a.number = twelve",
                "root = /opt/simweaver",
                "fom.dir = ${root}/fom",
                "fom.file = ${fom.dir}/RPR2.xml",
                "fallback = ${missing.key:-defaulted}",
                "literal = $${not.expanded}",
                "loop.a = ${loop.b}",
                "loop.b = ${loop.a}");

        Path local = write(dir.resolve("local.properties"),
                "# local overrides",
                "rti.port = 9000",
                "rti.crc = crc.simweaver.se",
                "root = /srv/sim");

        // -------------------------------------------------- loading & overlay
        SimwProperties p = new SimwProperties().load(base);
        eq("first load: port", 8989, p.getInt("rti.port"));
        eq("first load: source", base.toAbsolutePath().normalize().toString(), p.getSource("rti.port"));

        p.load(local);
        eq("overlay overrides", 9000, p.getInt("rti.port"));
        eq("overlay adds", "crc.simweaver.se", p.getString("rti.crc"));
        eq("untouched keys survive", "localhost", p.getString("rti.host"));
        eq("source follows override", local.toAbsolutePath().normalize().toString(), p.getSource("rti.port"));
        eq("previous source recorded", base.toAbsolutePath().normalize().toString(), p.getPreviousSource("rti.port"));
        eq("assignment count", 2, p.getAssignmentCount("rti.port"));
        eq("untouched key keeps its source", base.toAbsolutePath().normalize().toString(), p.getSource("rti.host"));
        eq("loaded files", 2, p.getLoadedFiles().length);
        eq("loaded files order", base.toAbsolutePath().normalize().toString(), p.getLoadedFiles()[0]);
        eq("reload is not duplicated in file list", 2, p.copy().load(local).getLoadedFiles().length);

        // -------------------------------------------------- parsing
        eq("colon separator", "SimWeaverFederation", p.getString("federation"));
        eq("whitespace separator", "ImplicitSeparator", p.getString("federate.name"));
        eq("empty value", "", p.getString("empty.value"));
        eq("key with no separator", "", p.getString("no.separator"));
        eq("trailing whitespace trimmed", "value", p.getString("trailing.trimmed"));
        eq("escaped trailing space kept", "value ", p.getString("trailing.kept"));
        eq("escaped spaces in key", "ok", p.getString("escaped.key with spaces"));
        eq("windows path", "C:\\temp\\fom", p.getString("windows.path"));
        eq("unicode escape", "\u00e5\u00e4\u00f6", p.getString("unicode"));
        eq("utf-8 file content", "raksmorgas-\u00e5\u00e4\u00f6", p.getString("utf8"));
        eq("tab escape", "a\tb", p.getString("tabbed"));
        eq("line continuation", "one, two, three", p.getString("continued"));

        // -------------------------------------------------- presence
        yes("isSet on a set key", p.isSet("rti.host"));
        yes("isSet on an empty value", p.isSet("empty.value"));
        no("isSet on an unknown key", p.isSet("nope"));
        no("hasValue on an empty value", p.hasValue("empty.value"));
        yes("hasValue on a real value", p.hasValue("rti.host"));

        // -------------------------------------------------- defaults
        eq("default when missing", "fallback", p.getString("nope", "fallback"));
        eq("default int when missing", 7, p.getInt("nope", 7));
        eq("default long when missing", 7L, p.getLong("nope", 7L));
        eq("default double when missing", 7.5, p.getDouble("nope", 7.5));
        eq("default bool when missing", true, p.getBoolean("nope", true));
        eq("value wins over default", "localhost", p.getString("rti.host", "other"));
        eq("unparsable falls back", 5, p.getInt("not.a.number", 5));
        yes("fallback recorded a warning", p.getWarnings().length > 0);

        // -------------------------------------------------- numbers
        eq("hex", 255, p.getInt("hex"));
        eq("binary", 10, p.getInt("binary"));
        eq("underscores", 1000000, p.getInt("grouped"));
        eq("negative", -42, p.getInt("negative"));
        eq("leading zero is decimal", 755, p.getInt("leading.zero"));
        eq("long", 9000L, p.getLong("rti.port"));
        eq("double", 3.14, p.getDouble("swedish.double"));
        eq("scientific", 1500.0, p.getDouble("sci"));
        eq("float", 3.14f, p.getFloat("swedish.double", 0f));
        eq("short", (short) 255, p.getShort("hex"));
        eq("byte out of range falls back", (byte) 9, p.getByte("hex", (byte) 9));

        // -------------------------------------------------- booleans, chars, enums
        eq("boolean ON", true, p.getBoolean("flag.on"));
        eq("boolean disabled", false, p.getBoolean("flag.off"));
        eq("char", 'X', p.getChar("letter"));
        eq("enum with dash", Mode.BEST_EFFORT, p.getEnum(Mode.class, "mode"));
        eq("enum default", Mode.RELIABLE, p.getEnum(Mode.class, "nope", Mode.RELIABLE));

        // -------------------------------------------------- arrays
        arrayEq("string array", new String[]{"alpha", "beta", "gamma"}, p.getStringArray("hosts"));
        arrayEq("string array default", new String[]{"x"}, p.getStringArray("nope", new String[]{"x"}));
        eq("empty value yields empty array", 0, p.getStringArray("empty.value").length);
        eq("missing yields empty array", 0, p.getStringArray("nope").length);
        yes("int array", Arrays.equals(new int[]{1, 2, 3}, p.getIntArray("ports")));
        yes("long array", Arrays.equals(new long[]{1L, 2L, 3L}, p.getLongArray("ports")));
        yes("double array", Arrays.equals(new double[]{1.5, 2.5, 3.0}, p.getDoubleArray("ratios")));
        yes("boolean array", Arrays.equals(new boolean[]{true, false, true}, p.getBooleanArray("flags")));

        // -------------------------------------------------- durations and sizes
        eq("seconds", 30000L, p.getMillis("timeout", 0));
        eq("millis", 250L, p.getMillis("heartbeat", 0));
        eq("kilobytes", 65536L, p.getByteSize("buffer", 0));
        eq("megabytes", 8L * 1024 * 1024, p.getByteSize("big.buffer", 0));

        // -------------------------------------------------- expansion
        eq("expansion uses the overridden value", "/srv/sim/fom", p.getString("fom.dir"));
        eq("nested expansion", "/srv/sim/fom/RPR2.xml", p.getString("fom.file"));
        eq("inline default", "defaulted", p.getString("fallback"));
        eq("escaped dollar", "${not.expanded}", p.getString("literal"));
        yes("circular reference is contained", p.getString("loop.a").contains("${"));
        System.setProperty("simw.selftest.sys", "from-sys");
        p.set("from.sys", "${sys:simw.selftest.sys}");
        eq("system property reference", "from-sys", p.getString("from.sys"));
        p.setExpandVariables(false);
        eq("expansion can be disabled", "${root}/fom", p.getString("fom.dir"));
        p.setExpandVariables(true);

        // -------------------------------------------------- runtime set
        p.set("runtime.key", "runtime.value");
        eq("set value", "runtime.value", p.getString("runtime.key"));
        eq("set source", SimwProperties.RUNTIME_SOURCE, p.getSource("runtime.key"));
        p.setIfAbsent("runtime.key", "ignored");
        eq("setIfAbsent respects existing", "runtime.value", p.getString("runtime.key"));
        eq("remove returns old value", "runtime.value", p.remove("runtime.key"));
        no("removed key is gone", p.isSet("runtime.key"));

        // -------------------------------------------------- streams and views
        SimwProperties s = new SimwProperties().load(
                new ByteArrayInputStream("a=1\nb=2\n".getBytes(StandardCharsets.UTF_8)), "<memory>");
        eq("stream load", 2, s.size());
        eq("stream source label", "<memory>", s.getSource("a"));
        eq("toProperties", "1", s.toProperties().getProperty("a"));
        eq("toMap", "2", s.toMap().get("b"));
        eq("merge", "1", new SimwProperties().merge(s).getString("a"));
        eq("keys sorted", "a", s.getKeysSorted()[0]);
        eq("prefix filter", 1, p.getKeysWithPrefix("federate.").length);

        // -------------------------------------------------- required semantics
        throwsSimw("required missing key", () -> p.requireString("definitely.missing"));
        throwsSimw("required unparsable int", () -> p.getInt("not.a.number"));
        throwsSimw("required out of range byte", () -> p.getByte("hex"));

        // -------------------------------------------------- strict mode
        SimwProperties strict = p.copy().setStrict(true);
        throwsSimw("strict rejects a bad value even with a default", () -> strict.getInt("not.a.number", 1));
        eq("strict still honours a plain default", 1, strict.getInt("also.missing", 1));

        // -------------------------------------------------- loadIfExists
        SimwProperties opt = new SimwProperties();
        no("loadIfExists on a missing file", opt.loadIfExists(dir.resolve("nope.properties")));
        yes("loadIfExists on a real file", opt.loadIfExists(base));

        // -------------------------------------------------- dump
        String dump = p.dump();
        yes("dump lists the sources", dump.contains(base.getFileName().toString()));
        yes("dump shows values", dump.contains("localhost"));
        yes("dump masks secrets", dump.contains("********") && !dump.contains("hunter2"));
        yes("dump shows raw form of expanded values", dump.contains("<- ${root}/fom"));
        yes("dump can show secrets on request", p.dump(false).contains("hunter2"));
        yes("dump of an empty instance is sane", new SimwProperties().dump().contains("no properties"));

        System.out.println("---- dump() sample -------------------------------------------");
        p.dumpToStdout();
        System.out.println("--------------------------------------------------------------");

        System.out.println();
        System.out.println(checks + " checks, " + failures + " failure(s)");
        if (failures > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Path write(Path file, String... lines) throws IOException {
        Files.write(file, String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static void eq(String what, Object expected, Object actual) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            failures++;
            System.out.println("FAIL " + what + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void arrayEq(String what, Object[] expected, Object[] actual) {
        checks++;
        if (!Arrays.equals(expected, actual)) {
            failures++;
            System.out.println("FAIL " + what + ": expected " + Arrays.toString(expected)
                    + " but was " + Arrays.toString(actual));
        }
    }

    private static void yes(String what, boolean condition) {
        checks++;
        if (!condition) {
            failures++;
            System.out.println("FAIL " + what + ": expected true");
        }
    }

    private static void no(String what, boolean condition) {
        checks++;
        if (condition) {
            failures++;
            System.out.println("FAIL " + what + ": expected false");
        }
    }

    private interface Block {
        void run();
    }

    private static void throwsSimw(String what, Block block) {
        checks++;
        try {
            block.run();
            failures++;
            System.out.println("FAIL " + what + ": expected SimwPropertyException");
        } catch (SimwPropertyException expected) {
            // good
        } catch (RuntimeException other) {
            failures++;
            System.out.println("FAIL " + what + ": expected SimwPropertyException but got " + other);
        }
    }

    private SimwPropertiesSelfTest() {
    }
}
