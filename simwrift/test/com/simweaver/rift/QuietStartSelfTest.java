package com.simweaver.rift;

import com.simweaver.common.config.SimwProperties;
import com.simweaver.common.hla.FederationProbe;
import com.simweaver.common.hla.FederationProbeFactory;
import com.simweaver.common.hla.ProbeStatus;
import com.simweaver.common.hla.implHla3.FederationProbeHla3;
import com.simweaver.common.logging.ConsoleTee;
import com.simweaver.rift.map.MapService;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * 0.11.5: a quieter console. Map layers go to the log file only ({@link ConsoleTee#logOnly}); a
 * federation probe that cannot reach its RTI says so once ({@link ProbeStatus}); probes are shared
 * per standard and LSD and connect on first use, not when a configuration is read.
 *
 * <pre>java -cp out/classes:out/test:fakerti/build/fakerti-1516e.jar com.simweaver.rift.QuietStartSelfTest</pre>
 */
public final class QuietStartSelfTest {

    private static int checks;
    private static int failures;
    private static final List<LogRecord> RECORDS = new ArrayList<LogRecord>();

    private QuietStartSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Logger probeLog = Logger.getLogger(FederationProbe.class.getName());
        probeLog.setLevel(Level.ALL);
        probeLog.addHandler(new Handler() {
            @Override
            public void publish(LogRecord r) {
                synchronized (RECORDS) {
                    RECORDS.add(r);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });

        logOnly();
        status();
        sharing();
        probe();
        map();

        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** ConsoleTee.logOnly: in the log file, not on the console. */
    private static void logOnly() throws Exception {
        PrintStream real = System.out;
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        Path dir = Files.createTempDirectory("quiet");
        System.setOut(new PrintStream(console, true, "UTF-8"));
        try {
            ConsoleTee.install(dir, "q", 1 << 20, 2, false);
            check("tee installed", ConsoleTee.isInstalled());
            System.out.println("on both");
            ConsoleTee.logOnly("only in the file");
            System.out.println("on both again");
            ConsoleTee.close();
        } finally {
            System.setOut(real);
        }
        String c = new String(console.toByteArray(), StandardCharsets.UTF_8);
        String f = new String(Files.readAllBytes(dir.resolve("q.log")), StandardCharsets.UTF_8);
        check("console: the printed lines, not the log-only one", c.contains("on both") && c.contains("on both again")
                && !c.contains("only in the file"));
        check("log file: all three, in order", f.indexOf("on both") < f.indexOf("only in the file")
                && f.indexOf("only in the file") < f.indexOf("on both again"));
        check("tee closed", !ConsoleTee.isInstalled());
    }

    /** ProbeStatus: one warning, repeats only on a new message, one INFO when connected. */
    private static void status() throws Exception {
        clear();
        PrintStream real = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, "UTF-8"));
        ProbeStatus s = new ProbeStatus("IEEE 1516-2010", "LSD", "crc-a");
        try {
            check("first attempt allowed", s.mayTry());
            s.failed(new RuntimeException("no CRC"));
            check("after a failure: no new attempt at once", !s.mayTry());
            s.failed(new RuntimeException("no CRC"));
            s.failed(new RuntimeException("no CRC"));
            s.failed(new IllegalStateException("other"));
        } finally {
            System.setOut(real);
        }
        String o = new String(out.toByteArray(), StandardCharsets.UTF_8);
        check("4 failures: one warning", count(Level.WARNING) == 1 && warning().contains("LSD 'crc-a'")
                && warning().contains("RuntimeException: no CRC"));
        check("same message again: nothing; a new message: one log-file line", o.split("still cannot connect", -1).length == 2
                && o.contains("IllegalStateException: other") && o.contains("4 attempts"));
        check("failing, 4 in a row", s.isFailing() && s.failures() == 4);
        s.connected();
        check("connected: one INFO with the count", count(Level.INFO) == 1 && last(Level.INFO).contains("after 4 failed attempts"));
        check("connected: not failing", !s.isFailing() && s.failures() == 0 && s.mayTry());
        check("the default LSD named as such", new ProbeStatus("IEEE 1516-2025", "RTI configuration", "").toString()
                .equals("Federation probe (IEEE 1516-2025, the RTI's default configuration)"));
    }

    /** One probe per standard and LSD; a configuration does not connect when it is read. */
    private static void sharing() {
        clear();
        FederationProbe a = FederationProbeFactory.createHla3("share-test");
        check("probe made (RTI API on the class path)", a != null);
        check("same LSD, same probe (also with spaces)", a == FederationProbeFactory.createHla3(" share-test "));
        check("another LSD, another probe", a != FederationProbeFactory.createHla3("share-other"));
        check("HLA 4 shared too", FederationProbeFactory.createHla4("x") == FederationProbeFactory.createHla4("x"));

        System.setProperty("fakerti.refuse", "true");
        try {
            SimwProperties p = new SimwProperties();
            p.set("datasource.name", "quiet");
            p.set("datasource.standard", "ieee1516-2010");
            p.set("datasource.hla.lsd", "config-test;config-other");
            p.set("datasource.hla.foms", "RPR_FOM_v2.0_Link16_1516-2010.xml");
            DatasourceConfig c1 = new DatasourceConfig(1, new File("quiet-1"), p);
            DatasourceConfig c2 = new DatasourceConfig(2, new File("quiet-2"), p);
            check("two configurations with the same LSD share the probe", c1.getFederationProbe() == c2.getFederationProbe());
            check("another LSD of the choices: the shared probe for it",
                    c1.getFederationProbe("config-other") == FederationProbeFactory.createHla3("config-other"));
            check("reading configurations connects nothing (no warning, not failing)", count(Level.WARNING) == 0
                    && !((FederationProbeHla3) c1.getFederationProbe()).status().isFailing());
        } finally {
            System.clearProperty("fakerti.refuse");
        }
    }

    /** A real probe on the test RTI: refused, not retried at once, then connected. */
    private static void probe() throws Exception {
        clear();
        FederationProbeHla3 p = (FederationProbeHla3) FederationProbeFactory.createHla3("probe-test");
        System.setProperty("fakerti.refuse", "true");
        try {
            p.rescan();
            check("refused: one warning, failing", count(Level.WARNING) == 1 && p.status().isFailing()
                    && warning().contains("refuses every connect"));
            long t0 = System.nanoTime();
            p.rescan();
            p.rescan();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            check("asked again at once: not tried (" + ms + " ms), still one warning", ms < 300
                    && p.status().failures() == 1 && count(Level.WARNING) == 1);
            check("no federations", p.getFederations().isEmpty());
        } finally {
            System.clearProperty("fakerti.refuse");
        }
        Thread.sleep(ProbeStatus.RETRY_MS + 200);
        p.rescan();
        check("the RTI answers: connected, one INFO", !p.status().isFailing() && count(Level.INFO) == 1
                && last(Level.INFO).contains("after 1 failed attempt"));
        check("listing works after it", p.getFederations() != null);
    }

    /** The console line of the map, and its details for the log file. */
    private static void map() throws Exception {
        SimwProperties off = new SimwProperties();
        off.set("map.enabled", "false");
        MapService m = new MapService(off, Files.createTempDirectory("quietmap").toFile());
        check("map off: one line, no details", "off (map.enabled=false)".equals(m.summary()) && m.details().isEmpty()
                && m.describe().size() == 1);
        SimwProperties none = new SimwProperties();
        none.set("map.dir", "no/such/folder");
        none.set("map.overlays.dir", "no/such/overlays");
        MapService n = new MapService(none, Files.createTempDirectory("quietmap").toFile());
        check("no maps: summary, the problem separately (" + n.summary() + ")", n.summary().startsWith("no background map")
                && !n.problems().isEmpty() && n.details().isEmpty() && n.describe().size() == n.problems().size());
        n.shutdown();
        m.shutdown();
    }

    // ------------------------------------------------------------------ helpers

    private static void clear() {
        synchronized (RECORDS) {
            RECORDS.clear();
        }
    }

    private static int count(Level level) {
        int n = 0;
        synchronized (RECORDS) {
            for (LogRecord r : RECORDS) {
                if (r.getLevel().equals(level)) {
                    n++;
                }
            }
        }
        return n;
    }

    private static String last(Level level) {
        String m = "";
        synchronized (RECORDS) {
            for (LogRecord r : RECORDS) {
                if (r.getLevel().equals(level)) {
                    m = r.getMessage();
                }
            }
        }
        return m;
    }

    private static String warning() {
        return last(Level.WARNING);
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
    }
}
