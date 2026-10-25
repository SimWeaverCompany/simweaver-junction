package com.simweaver.common.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Dependency-free self-test for {@link SimwArgs}. Run with:
 * <pre>java -cp out com.simweaver.config.SimwArgsSelfTest</pre>
 * Exits non-zero if anything fails.
 */
public final class SimwArgsSelfTest {

    private static int checks = 0;
    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("simwargs");
        Path site = write(dir.resolve("site.properties"),
                "rti.host = site-host",
                "rti.port = 1234",
                "federation = FromFile");
        Path extra = write(dir.resolve("extra.properties"),
                "extra.key = from-extra");

        // -------------------------------------------------- the plain case
        SimwProperties p = SimwArgs.toProperties(new String[] {
                "arg1=nisse", "arg2=value2", "arg3=blabla"});
        eq("simple key=value", "nisse", p.getString("arg1"));
        eq("second arg", "value2", p.getString("arg2"));
        eq("third arg", "blabla", p.getString("arg3"));
        eq("count", 3, p.size());
        eq("source label", SimwArgs.DEFAULT_SOURCE, p.getSource("arg1"));
        eq("argument index kept as the source position", 2, p.getSourceLine("arg2"));
        eq("no files were loaded", 0, p.getLoadedFiles().length);

        // -------------------------------------------------- typed access still applies
        SimwProperties t = SimwArgs.toProperties(new String[] {
                "rti.port=9000", "verbose=yes", "scale=2.5", "hosts=a;b;c", "timeout=30s"});
        eq("int from the command line", 9000, t.getInt("rti.port"));
        eq("boolean from the command line", true, t.getBoolean("verbose"));
        eq("double from the command line", 2.5, t.getDouble("scale"));
        arrayEq("array from the command line", new String[] {"a", "b", "c"}, t.getStringArray("hosts"));
        eq("duration from the command line", 30000L, t.getMillis("timeout", 0));

        // -------------------------------------------------- token shapes
        SimwProperties s = SimwArgs.toProperties(new String[] {
                "plain=1", "--dashed=2", "-single=3", "-Djvm.style=4",
                "quoted=\"a b\"", "empty=", "with.equals=a=b", "--verbose", "--no-color"});
        eq("plain", "1", s.getString("plain"));
        eq("double dash", "2", s.getString("dashed"));
        eq("single dash", "3", s.getString("single"));
        eq("-D style", "4", s.getString("jvm.style"));
        eq("quotes removed", "a b", s.getString("quoted"));
        eq("empty value", "", s.getString("empty"));
        yes("empty value counts as set", s.isSet("empty"));
        eq("only the first = splits", "a=b", s.getString("with.equals"));
        eq("flag", true, s.getBoolean("verbose"));
        eq("negated flag", false, s.getBoolean("color"));

        // -------------------------------------------------- overlay onto a defaults file
        SimwProperties layered = new SimwProperties().load(site);
        new SimwArgs().parseInto(layered, "rti.port=9999", "new.key=added");
        eq("command line overrides the file", 9999, layered.getInt("rti.port"));
        eq("file value survives", "site-host", layered.getString("rti.host"));
        eq("command line adds", "added", layered.getString("new.key"));
        eq("overridden key reports the command line", SimwArgs.DEFAULT_SOURCE, layered.getSource("rti.port"));
        eq("untouched key still reports the file",
                site.toAbsolutePath().normalize().toString(), layered.getSource("rti.host"));
        eq("loaded files lists only real files", 1, layered.getLoadedFiles().length);

        // -------------------------------------------------- config key names a file
        SimwProperties c = new SimwArgs().withConfigKeys("config").parse(
                "config=" + site, "rti.port=7777");
        eq("file named by a config key is loaded", "site-host", c.getString("rti.host"));
        eq("file value used where not overridden", "FromFile", c.getString("federation"));
        eq("command line still wins over the file it named", 7777, c.getInt("rti.port"));
        eq("the config key itself is a property", site.toString(), c.getString("config"));
        eq("the named file shows up as loaded", 1, c.getLoadedFiles().length);

        // order of the tokens must not matter: value first, file second
        SimwProperties c2 = new SimwArgs().withConfigKeys("config").parse(
                "rti.port=7777", "config=" + site);
        eq("command line wins regardless of token order", 7777, c2.getInt("rti.port"));

        // -------------------------------------------------- @file
        SimwProperties at = new SimwArgs().parse("@" + extra, "own=1");
        eq("@file loaded", "from-extra", at.getString("extra.key"));
        eq("@file is a real loaded file", 1, at.getLoadedFiles().length);
        eq("normal args still apply", "1", at.getString("own"));

        SimwProperties noAt = new SimwArgs().withAtFiles(false).parse("@" + extra);
        no("@file can be turned off", noAt.isSet("extra.key"));

        // -------------------------------------------------- aliases
        SimwProperties a = new SimwArgs()
                .withAlias("p", "rti.port")
                .withAlias("h", "rti.host")
                .parse("-p=8989", "h=alpha");
        eq("alias with dash", 8989, a.getInt("rti.port"));
        eq("alias without dash", "alpha", a.getString("rti.host"));

        // -------------------------------------------------- positionals
        SimwProperties pos = new SimwArgs().parse("run", "key=1", "second-file.xml");
        arrayEq("positionals collected", new String[] {"run", "second-file.xml"}, SimwArgs.positionals(pos));
        eq("positional count", 2, pos.getInt("arg.count"));
        eq("positional by key", "run", pos.getString("arg.0"));
        eq("key=value still parsed alongside", "1", pos.getString("key"));

        SimwProperties term = new SimwArgs().parse("a=1", "--", "--not=a-flag", "b=2");
        eq("after -- nothing is parsed", 1, term.getInt("a"));
        no("after -- b is not a property", term.isSet("b"));
        arrayEq("after -- everything is positional",
                new String[] {"--not=a-flag", "b=2"}, SimwArgs.positionals(term));

        SimwProperties dropped = new SimwArgs().withPositionalPrefix(null).parse("run", "a=1");
        eq("positionals can be discarded", 1, dropped.size());

        // -------------------------------------------------- empty and odd input
        eq("null array", 0, SimwArgs.toProperties(null).size());
        eq("empty array", 0, SimwArgs.toProperties(new String[0]).size());
        eq("null and blank tokens ignored", 1,
                new SimwArgs().parse(null, "  ", "a=1").size());
        SimwProperties odd = new SimwArgs().parse("=novalue", "a=1");
        eq("empty key is skipped", 1, odd.size());
        yes("empty key is reported", odd.getWarnings().length == 1);

        // -------------------------------------------------- strict mode
        throwsSimw("strict rejects a bare token",
                () -> new SimwArgs().withStrict(true).parse("notakeyvalue"));
        throwsSimw("missing config file is fatal",
                () -> new SimwArgs().withConfigKeys("config").parse("config=" + dir.resolve("nope.properties")));

        // -------------------------------------------------- dump shows where things came from
        String dump = c.dump();
        yes("dump marks command line values", dump.contains("<args>"));
        yes("dump lists the loaded file", dump.contains("site.properties"));

        System.out.println("---- dump() of a file + command line ------------------------");
        c.dumpToStdout();
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

    private SimwArgsSelfTest() {
    }
}
