package com.simweaver.common.logging;

import com.simweaver.common.config.SimwProperties;
import com.simweaver.common.rest.ApiCallHandler;
import com.simweaver.common.rest.RequestContext;
import com.simweaver.common.rest.XrRequestHandler;
import com.simweaver.common.rest.XrResultPair;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;

/**
 * Logging and syslog (0.9.22) without a network beyond the loopback: settings, the RFC 5424 and
 * RFC 3164 formats (PRI, header, BOM, line breaks, cutting), events and their tags, the console
 * handler's routing, the actor of a REST call (through a real XrRequestHandler), and the three
 * transports against local receivers: UDP, TCP (octet counting and LF), TLS (with and without
 * host name checks), reconnecting, a full queue and the dropped-messages notice. TLS needs
 * {@code keytool} from the JDK running the test (skipped without it).
 *
 * <pre>java -cp out/classes:out/test com.simweaver.common.logging.LoggingSelfTest</pre>
 */
public final class LoggingSelfTest {

    private static final String BOM = "\uFEFF";
    private static final String PID = String.valueOf(ProcessHandle.current().pid());
    private static int checks;
    private static int failures;

    private LoggingSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        settingsCases();
        formats();
        events();
        console();
        requestContext();
        udp();
        tcp();
        reconnectAndDrops();
        tls();
        logSetup();
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ settings

    private static SyslogSettings settings(String... kv) {
        SimwProperties p = new SimwProperties();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            p.set(kv[i], kv[i + 1]);
        }
        return new SyslogSettings(p);
    }

    private static void settingsCases() {
        SyslogSettings d = settings();
        check("off by default", !d.isEnabled() && d.problems().isEmpty());
        check("defaults: udp 514, RFC 5424, local0, INFO", d.protocol() == SyslogSettings.Protocol.UDP && d.port() == 514
                && d.format() == SyslogSettings.Format.RFC5424 && d.facility() == 16 && d.level() == Level.INFO);
        check("defaults: app, queue, bom, udp length 2048", "simweaver".equals(d.appName()) && d.queueSize() == 10000
                && d.bom() && d.maxLength() == 2048 && d.octetFraming() && d.verifyHostname());
        check("host name taken from the computer", !d.hostName().isEmpty() && !d.hostName().contains(" "));
        SyslogSettings on = settings("syslog.enabled", "true", "syslog.host", "logs.example");
        check("enabled with a host", on.isEnabled() && "logs.example".equals(on.host()));
        check("describe", on.describe().equals("udp logs.example:514, RFC 5424, facility local0, INFO and above"));
        SyslogSettings tls = settings("syslog.enabled", "true", "syslog.host", "h", "syslog.protocol", "TLS");
        check("tls: port 6514, length 8192", tls.port() == 6514 && tls.maxLength() == 8192 && tls.isEnabled());
        SyslogSettings bsd = settings("syslog.enabled", "true", "syslog.host", "h", "syslog.format", "rfc3164");
        check("rfc3164 over udp: 1024, lf framing", bsd.format() == SyslogSettings.Format.RFC3164 && bsd.maxLength() == 1024
                && !bsd.octetFraming());
        check("format spellings", settings("syslog.format", "RFC-3164").format() == SyslogSettings.Format.RFC3164
                && settings("syslog.format", "bsd").format() == SyslogSettings.Format.RFC3164
                && settings("syslog.format", "5424").format() == SyslogSettings.Format.RFC5424);
        check("facility names and numbers", settings("syslog.facility", "daemon").facility() == 3
                && settings("syslog.facility", "LOCAL7").facility() == 23 && settings("syslog.facility", "1").facility() == 1);
        check("NOTICE and FINE levels", settings("syslog.level", "notice").level() == Events.NOTICE
                && settings("syslog.level", "fine").level() == Level.FINE);
        SyslogSettings bad = settings("syslog.enabled", "true", "syslog.host", "h", "syslog.protocol", "sctp",
                "syslog.facility", "local9", "syslog.level", "LOUD", "syslog.format", "json", "syslog.tcp.framing", "x",
                "syslog.port", "70000");
        check("six mistakes found, syslog off", bad.problems().size() == 6 && !bad.isEnabled());
        check("enabled without a host", settings("syslog.enabled", "true").problems().equals(
                Collections.singletonList("syslog.enabled=true needs syslog.host")));
        check("explicit framing and length", !settings("syslog.tcp.framing", "lf").octetFraming()
                && settings("syslog.maxlength", "100").maxLength() == 480 && settings("syslog.maxlength", "4000").maxLength() == 4000);
        check("app name without spaces", "my-app".equals(settings("syslog.appname", "my -app").appName()));
    }

    // ------------------------------------------------------------------ formats

    private static String text(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    private static void formats() {
        SyslogSettings s = settings("syslog.enabled", "true", "syslog.host", "h", "syslog.hostname", "rift-1");
        SyslogFormat f = new SyslogFormat(s, "0.9.22", ZoneOffset.UTC);
        Instant t = Instant.parse("2026-10-06T13:03:11.250Z");
        Map<String, String> fields = new java.util.LinkedHashMap<String, String>();
        fields.put("id", "42");
        fields.put("by", "10.0.0.5");
        Events.Tag tag = new Events.Tag("datasource.created", fields);
        String m = text(f.format(Events.NOTICE, t, tag, "Datasource Blue created  [" + tag.text() + "]"));
        check("RFC 5424 message", m.equals("<133>1 2026-10-06T13:03:11.250Z rift-1 simweaver " + PID
                + " datasource.created [origin software=\"SimWeaver\" swVersion=\"0.9.22\"] " + BOM
                + "Datasource Blue created  [event=datasource.created id=42 by=10.0.0.5]"));
        check("PRI per level", text(f.format(Level.SEVERE, t, null, "x")).startsWith("<131>1 ")
                && text(f.format(Level.WARNING, t, null, "x")).startsWith("<132>1 ")
                && text(f.format(Level.INFO, t, null, "x")).startsWith("<134>1 ")
                && text(f.format(Level.FINE, t, null, "x")).startsWith("<135>1 "));
        check("no event: MSGID -", text(f.format(Level.INFO, t, null, "x")).contains(" " + PID + " - [origin "));
        String withZone = text(new SyslogFormat(s, "1", ZoneId.of("Europe/Stockholm")).format(Level.INFO, t, null, "x"));
        check("local time with its offset", withZone.contains(" 2026-10-06T15:03:11.250+02:00 "));
        check("line breaks", text(f.format(Level.INFO, t, null, "a\nb\r\nc\n")).endsWith(BOM + "a | b | c"));
        check("empty message: no BOM", text(f.format(Level.INFO, t, null, "")).endsWith("swVersion=\"1\"]")
                || text(f.format(Level.INFO, t, null, "")).endsWith("swVersion=\"0.9.22\"]"));
        SyslogSettings nobom = settings("syslog.enabled", "true", "syslog.host", "h", "syslog.bom", "false", "syslog.hostname", "x");
        check("syslog.bom=false", !text(new SyslogFormat(nobom, "1", ZoneOffset.UTC).format(Level.INFO, t, null, "y")).contains(BOM));

        SyslogSettings b = settings("syslog.enabled", "true", "syslog.host", "h", "syslog.format", "rfc3164",
                "syslog.hostname", "rift-1", "syslog.facility", "user");
        SyslogFormat bf = new SyslogFormat(b, "0.9.22", ZoneOffset.UTC);
        check("RFC 3164 message, day padded", text(bf.format(Level.WARNING, t, tag, "lost")).equals(
                "<12>Oct  6 13:03:11 rift-1 simweaver[" + PID + "]: lost"));
        check("RFC 3164 two-digit day", text(bf.format(Level.INFO, Instant.parse("2026-12-24T01:02:03Z"), null, "x"))
                .startsWith("<14>Dec 24 01:02:03 rift-1 "));

        SyslogSettings shortS = settings("syslog.enabled", "true", "syslog.host", "h", "syslog.maxlength", "480", "syslog.hostname", "x");
        SyslogFormat sf = new SyslogFormat(shortS, "1", ZoneOffset.UTC);
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            longText.append("å");                 // two octets each
        }
        byte[] cut = sf.format(Level.INFO, t, null, longText.toString());
        String cutText = text(cut);
        check("cut to the limit at a character boundary", cut.length <= 480 && cut.length >= 478
                && !cutText.contains("\uFFFD") && cutText.endsWith("å"));
        check("cut helper", SyslogFormat.cut(new byte[] {65, (byte) 0xC3, (byte) 0xA5}, 2) == 1
                && SyslogFormat.cut(new byte[] {65, 66}, 5) == 2);
        check("SD value escapes", SyslogFormat.sdValue("a\"b]c\\").equals("a\\\"b\\]c\\\\"));
        check("3164 tag", SyslogFormat.tagName("sim weaver!").equals("simweaver") && SyslogFormat.tagName("!!").equals("simweaver"));
    }

    // ------------------------------------------------------------------ events

    private static final class Capture extends Handler {
        final List<LogRecord> records = Collections.synchronizedList(new ArrayList<LogRecord>());

        @Override
        public void publish(LogRecord r) {
            records.add(r);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    private static Logger captured(String name, Capture c) {
        Logger l = Logger.getLogger(name);
        l.setUseParentHandlers(false);
        l.addHandler(c);
        l.setLevel(Level.ALL);
        return l;
    }

    private static void events() {
        Capture c = new Capture();
        Logger l = captured("test.events", c);
        Events.notice(l, "gateway.started", "Gateway A started", "gateway", "A", "targets", "hla:Blue Force", "skip", null);
        LogRecord r = c.records.get(0);
        Events.Tag tag = Events.tagOf(r);
        check("NOTICE level 850", r.getLevel() == Events.NOTICE && Events.NOTICE.intValue() == 850
                && Level.parse("NOTICE") != null);
        check("event tag, null left out, by=server", tag != null && tag.text().equals(
                "event=gateway.started gateway=A targets=\"hla:Blue Force\" by=server"));
        check("message kept as text", "Gateway A started".equals(r.getMessage()));
        RequestContext.runAs("autostart", () -> Events.warning(l, "gateway.startfailed", "x {0}", "gateway", "B"));
        check("runAs sets by", Events.tagOf(c.records.get(1)).fields().get("by").equals("autostart")
                && c.records.get(1).getLevel() == Level.WARNING);
        check("runAs restores", "server".equals(RequestContext.actor()));
        Events.notice(l, "x", "y", "by", "rti");
        check("by given wins", Events.tagOf(c.records.get(2)).fields().get("by").equals("rti"));
        check("quote", Events.quote("").equals("\"\"") && Events.quote("a=b").equals("\"a=b\"")
                && Events.quote("say \"hi\"").equals("\"say \\\"hi\\\"\"") && Events.quote("plain").equals("plain"));
        check("console text of an event (no MessageFormat)", ConsoleLineHandler.line(c.records.get(1))
                .equals("WARNING: x {0}  [event=gateway.startfailed gateway=B by=autostart]"));
        l.setLevel(Level.WARNING);
        int before = c.records.size();
        Events.notice(l, "quiet", "not logged");
        check("level respected", c.records.size() == before);
    }

    // ------------------------------------------------------------------ console

    private static void console() {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        ConsoleLineHandler h = new ConsoleLineHandler();
        try {
            System.setOut(new PrintStream(o, true));
            System.setErr(new PrintStream(e, true));
            h.publish(new LogRecord(Level.INFO, "info line"));
            LogRecord p = new LogRecord(Level.INFO, "value {0}");
            p.setParameters(new Object[] {7});
            h.publish(p);
            h.publish(new LogRecord(Events.NOTICE, "notice line"));
            LogRecord w = new LogRecord(Level.WARNING, "warn line");
            w.setThrown(new IOException("disk full"));
            h.publish(w);
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        String os = o.toString();
        String es = e.toString();
        check("INFO and NOTICE to stdout", os.contains("INFO: info line") && os.contains("INFO: value 7")
                && os.contains("NOTICE: notice line") && !os.contains("warn"));
        check("WARNING to stderr with the exception", es.startsWith("WARNING: warn line (java.io.IOException: disk full)")
                && es.contains("at com.simweaver.common.logging.LoggingSelfTest") && !es.contains("info"));
    }

    // ------------------------------------------------------------------ REST actor

    private static void requestContext() throws Exception {
        Capture c = new Capture();
        Logger l = captured("test.rest", c);
        AtomicReference<String> during = new AtomicReference<String>();
        ApiCallHandler api = (method, uri, route, query) -> {
            during.set(RequestContext.actor());
            Events.notice(l, "datasource.created", "created", "id", "1");
            return new XrResultPair("{}", 200);
        };
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api", new XrRequestHandler(api, "/api"));
        server.setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor());
        server.start();
        try {
            int port = server.getAddress().getPort();
            int code = get("http://127.0.0.1:" + port + "/api/x", null);
            check("REST call answered", code == 200);
            check("actor = client address", "127.0.0.1".equals(during.get()));
            check("event by= client address", c.records.size() == 1
                    && "127.0.0.1".equals(Events.tagOf(c.records.get(0)).fields().get("by")));
            get("http://127.0.0.1:" + port + "/api/x", "10.1.2.3, 10.0.0.1\u0007");
            check("X-Forwarded-For noted", "127.0.0.1 (forwarded for 10.1.2.3, 10.0.0.1)".equals(during.get())
                    || "127.0.0.1 (forwarded for 10.1.2.3, 10.0.0.1?)".equals(during.get()));
            // the executor's thread is reused: the actor must not stick to it
            AtomicReference<String> after = new AtomicReference<String>();
            server.getExecutor().execute(() -> after.set(RequestContext.actor()));
            long end = System.currentTimeMillis() + 2000;
            while (after.get() == null && System.currentTimeMillis() < end) {
                Thread.sleep(10);
            }
            check("actor cleared after the call", "server".equals(after.get()));
        } finally {
            server.stop(0);
            ((java.util.concurrent.ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    private static int get(String url, String forwardedFor) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(java.net.Proxy.NO_PROXY);
        if (forwardedFor != null) {
            c.setRequestProperty("X-Forwarded-For", forwardedFor);
        }
        int code = c.getResponseCode();
        try (InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream()) {
            if (in != null) {
                while (in.read() >= 0) {
                    // drain
                }
            }
        }
        return code;
    }

    // ------------------------------------------------------------------ transports

    private static LogRecord event(Level level, String event, String text) {
        LogRecord r = new LogRecord(level, text);
        Map<String, String> f = new java.util.LinkedHashMap<String, String>();
        f.put("by", "test");
        r.setParameters(new Object[] {new Events.Tag(event, f)});
        return r;
    }

    private static void udp() throws Exception {
        try (DatagramSocket rx = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            rx.setSoTimeout(3000);
            SyslogSettings s = settings("syslog.enabled", "true", "syslog.host", "127.0.0.1", "syslog.port",
                    String.valueOf(rx.getLocalPort()), "syslog.hostname", "rift-1");
            SyslogHandler h = new SyslogHandler(s, "0.9.22", ZoneOffset.UTC, null);
            try {
                h.publish(event(Events.NOTICE, "server.started", "started"));
                h.publish(new LogRecord(Level.FINE, "below the level"));
                h.publish(new LogRecord(Level.WARNING, "second"));
                List<String> got = new ArrayList<String>();
                byte[] buf = new byte[4096];
                for (int i = 0; i < 2; i++) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    rx.receive(p);
                    got.add(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
                }
                check("udp: one message per datagram", got.size() == 2 && got.get(0).startsWith("<133>1 ")
                        && got.get(0).contains(" rift-1 simweaver " + PID + " server.started [origin ")
                        && got.get(0).endsWith(BOM + "started  [event=server.started by=test]")
                        && got.get(1).startsWith("<132>1 ") && got.get(1).endsWith(BOM + "second"));
                check("udp: FINE not sent", h.drain(2000) && h.sentCount() == 2);
                Map<String, Object> st = h.status();
                check("status", Boolean.TRUE.equals(st.get("enabled")) && "udp".equals(st.get("protocol"))
                        && "rfc5424".equals(st.get("format")) && Long.valueOf(2).equals(st.get("sent"))
                        && Boolean.TRUE.equals(st.get("connected")) && !st.containsKey("lasterror"));
            } finally {
                h.close();
            }
        }
    }

    /** Reads framed messages from one connection. */
    private static final class StreamReceiver implements Runnable {
        final ServerSocket server;
        final boolean octet;
        final List<String> messages = Collections.synchronizedList(new ArrayList<String>());
        volatile Exception failure;

        StreamReceiver(ServerSocket server, boolean octet) {
            this.server = server;
            this.octet = octet;
            Thread t = new Thread(this, "syslog-test-receiver");
            t.setDaemon(true);
            t.start();
        }

        @Override
        public void run() {
            while (!server.isClosed()) {
                try (Socket s = server.accept()) {
                    DataInputStream in = new DataInputStream(s.getInputStream());
                    while (true) {
                        String m = octet ? readOctet(in) : readLine(in);
                        if (m == null) {
                            break;
                        }
                        messages.add(m);
                    }
                } catch (javax.net.ssl.SSLException e) {
                    // a client the test expects to fail the handshake: wait for the next one
                } catch (IOException e) {
                    if (!server.isClosed()) {
                        failure = e;
                    }
                }
            }
        }

        private static String readOctet(DataInputStream in) throws IOException {
            int len = 0;
            int c;
            boolean any = false;
            while ((c = in.read()) >= '0' && c <= '9') {
                len = len * 10 + (c - '0');
                any = true;
            }
            if (c < 0 && !any) {
                return null;
            }
            if (c != ' ') {
                throw new IOException("framing: '" + (char) c + "' after the length");
            }
            byte[] b = new byte[len];
            in.readFully(b);
            return new String(b, StandardCharsets.UTF_8);
        }

        private static String readLine(InputStream in) throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            int c;
            while ((c = in.read()) >= 0 && c != '\n') {
                b.write(c);
            }
            if (c < 0 && b.size() == 0) {
                return null;
            }
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }

        boolean await(int n, long millis) throws InterruptedException {
            long end = System.currentTimeMillis() + millis;
            while (messages.size() < n && System.currentTimeMillis() < end) {
                Thread.sleep(20);
            }
            return messages.size() >= n;
        }
    }

    private static void tcp() throws Exception {
        try (ServerSocket ss = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            StreamReceiver rx = new StreamReceiver(ss, true);
            SyslogHandler h = new SyslogHandler(settings("syslog.enabled", "true", "syslog.host", "127.0.0.1",
                    "syslog.port", String.valueOf(ss.getLocalPort()), "syslog.protocol", "tcp", "syslog.hostname", "r"),
                    "0.9.22", ZoneOffset.UTC, null);
            try {
                h.publish(event(Events.NOTICE, "gateway.started", "Gateway åäö started"));
                h.publish(new LogRecord(Level.INFO, "two\nlines"));
                check("tcp octet counting: both arrive", rx.await(2, 3000) && rx.failure == null);
                check("tcp octet counting: content (UTF-8 length)", rx.messages.size() == 2
                        && rx.messages.get(0).endsWith(BOM + "Gateway åäö started  [event=gateway.started by=test]")
                        && rx.messages.get(1).endsWith(BOM + "two | lines"));
            } finally {
                h.close();
            }
        }
        try (ServerSocket ss = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            StreamReceiver rx = new StreamReceiver(ss, false);
            SyslogHandler h = new SyslogHandler(settings("syslog.enabled", "true", "syslog.host", "127.0.0.1",
                    "syslog.port", String.valueOf(ss.getLocalPort()), "syslog.protocol", "tcp", "syslog.format", "rfc3164",
                    "syslog.hostname", "r"), "0.9.22", ZoneOffset.UTC, null);
            try {
                h.publish(new LogRecord(Level.SEVERE, "broken\nthing"));
                h.publish(new LogRecord(Level.INFO, "fine"));
                check("tcp rfc3164 with LF framing", rx.await(2, 3000) && rx.messages.get(0).matches("<131>[A-Z][a-z]{2} [ 0-9]\\d \\d\\d:\\d\\d:\\d\\d r simweaver\\[" + PID + "\\]: broken \\| thing")
                        && rx.messages.get(1).endsWith("]: fine"));
            } finally {
                h.close();
            }
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    private static void reconnectAndDrops() throws Exception {
        int port = freePort();
        PrintStream err = System.err;
        PrintStream out = System.out;
        ByteArrayOutputStream notes = new ByteArrayOutputStream();
        SyslogHandler h;
        try {
            System.setErr(new PrintStream(notes, true));
            System.setOut(new PrintStream(notes, true));
            h = new SyslogHandler(settings("syslog.enabled", "true", "syslog.host", "127.0.0.1", "syslog.port",
                    String.valueOf(port), "syslog.protocol", "tcp", "syslog.queuesize", "100"), "0.9.22", ZoneOffset.UTC, null);
            for (int i = 0; i < 150; i++) {
                h.publish(new LogRecord(Level.INFO, "m" + i));
            }
            Thread.sleep(300);
            check("no server: queued, 49-50 dropped", h.droppedCount() >= 49 && h.droppedCount() <= 50 && !h.isConnected());
            Map<String, Object> st = h.status();
            check("status names the error", st.containsKey("lasterror") && Boolean.FALSE.equals(st.get("connected")));
            try (ServerSocket ss = new ServerSocket()) {
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
                StreamReceiver rx = new StreamReceiver(ss, true);
                long dropped = h.droppedCount();
                int expected = (int) (150 - dropped + 1);  // what was kept, and the notice
                boolean all = rx.await(expected, 8000);    // retry after 1 s, then 2 s
                Thread.sleep(100);
                check("reconnected: the queue and the drop notice arrive", all && rx.messages.size() == expected);
                check("drop notice first, as NOTICE", rx.messages.size() > 0 && rx.messages.get(0).startsWith("<133>1 ")
                        && rx.messages.get(0).endsWith("syslog: " + dropped + " message(s) were dropped while the syslog server could not be reached"));
                check("order kept", rx.messages.size() > 2 && rx.messages.get(1).endsWith(BOM + "m0")
                        && rx.messages.get(rx.messages.size() - 1).endsWith(BOM + "m" + (150 - dropped - 1)));
                check("connected again", h.isConnected() && h.drain(1000));
                h.close();
            }
        } finally {
            System.setErr(err);
            System.setOut(out);
        }
        String n = notes.toString();
        check("trouble reported once on the console, then connected", n.contains("WARNING: syslog: cannot reach 127.0.0.1:" + port)
                && n.indexOf("WARNING: syslog: cannot reach") == n.lastIndexOf("WARNING: syslog: cannot reach")
                && n.contains("INFO: syslog: connected to tcp 127.0.0.1:" + port));
    }

    // ------------------------------------------------------------------ TLS

    private static void tls() throws Exception {
        File keytool = new File(System.getProperty("java.home"), "bin/keytool" + (File.separatorChar == '\\' ? ".exe" : ""));
        if (!keytool.isFile()) {
            System.out.println("TLS: skipped (no keytool in " + keytool.getParent() + ")");
            return;
        }
        Path dir = Files.createTempDirectory("syslogtls");
        File ks = dir.resolve("server.p12").toFile();
        File cert = dir.resolve("server.cer").toFile();
        File ts = dir.resolve("trust.p12").toFile();
        run(keytool.getPath(), "-genkeypair", "-alias", "s", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost", "-validity", "2", "-storetype", "PKCS12", "-keystore", ks.getPath(),
                "-storepass", "secret", "-keypass", "secret");
        run(keytool.getPath(), "-exportcert", "-alias", "s", "-keystore", ks.getPath(), "-storepass", "secret", "-file", cert.getPath());
        run(keytool.getPath(), "-importcert", "-noprompt", "-alias", "ca", "-file", cert.getPath(), "-storetype", "PKCS12",
                "-keystore", ts.getPath(), "-storepass", "trustpw");
        KeyStore k = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(ks)) {
            k.load(in, "secret".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(k, "secret".toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        try (SSLServerSocket ss = (SSLServerSocket) ctx.getServerSocketFactory().createServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            StreamReceiver rx = new StreamReceiver(ss, true);
            int port = ss.getLocalPort();
            SyslogHandler h = new SyslogHandler(settings("syslog.enabled", "true", "syslog.host", "localhost", "syslog.port",
                    String.valueOf(port), "syslog.protocol", "tls", "syslog.tls.truststore", ts.getPath(),
                    "syslog.tls.truststorepassword", "trustpw"), "0.9.22", ZoneOffset.UTC, null);
            try {
                h.publish(event(Events.NOTICE, "datasource.removed", "over TLS"));
                check("tls: delivered with the host name checked", rx.await(1, 5000)
                        && rx.messages.get(0).endsWith(BOM + "over TLS  [event=datasource.removed by=test]"));
            } finally {
                h.close();
            }

            PrintStream err = System.err;
            ByteArrayOutputStream notes = new ByteArrayOutputStream();
            System.setErr(new PrintStream(notes, true));
            try {
                SyslogHandler wrongName = new SyslogHandler(settings("syslog.enabled", "true", "syslog.host", "127.0.0.1",
                        "syslog.port", String.valueOf(port), "syslog.protocol", "tls", "syslog.tls.truststore", ts.getPath(),
                        "syslog.tls.truststorepassword", "trustpw"), "1", ZoneOffset.UTC, null);
                wrongName.publish(new LogRecord(Level.INFO, "must not arrive"));
                Thread.sleep(1200);
                check("tls: a certificate for another name is refused", !wrongName.isConnected()
                        && wrongName.status().containsKey("lasterror") && rx.messages.size() == 1);
                wrongName.close();
                SyslogHandler noCheck = new SyslogHandler(settings("syslog.enabled", "true", "syslog.host", "127.0.0.1",
                        "syslog.port", String.valueOf(port), "syslog.protocol", "tls", "syslog.tls.truststore", ts.getPath(),
                        "syslog.tls.truststorepassword", "trustpw", "syslog.tls.verifyhostname", "false"), "1", ZoneOffset.UTC, null);
                noCheck.publish(new LogRecord(Level.INFO, "name not checked"));
                check("tls: verifyhostname=false accepts it", rx.await(2, 5000) && rx.messages.get(1).endsWith("name not checked"));
                noCheck.close();
                SyslogHandler untrusted = new SyslogHandler(settings("syslog.enabled", "true", "syslog.host", "localhost",
                        "syslog.port", String.valueOf(port), "syslog.protocol", "tls"), "1", ZoneOffset.UTC, null);
                untrusted.publish(new LogRecord(Level.INFO, "must not arrive either"));
                Thread.sleep(1200);
                check("tls: Java's CAs do not trust a self-signed server", !untrusted.isConnected() && rx.messages.size() == 2);
                untrusted.close();
            } finally {
                System.setErr(err);
            }
            check("tls trouble on the console", notes.toString().contains("WARNING: syslog: cannot reach"));
        } finally {
            for (File f : new File[] {ks, cert, ts}) {
                f.delete();
            }
            dir.toFile().delete();
        }
    }

    private static void run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        if (p.waitFor() != 0) {
            throw new IOException(String.join(" ", cmd) + ": " + new String(out, StandardCharsets.UTF_8));
        }
    }

    // ------------------------------------------------------------------ LogSetup

    private static void logSetup() throws Exception {
        Logger root = Logger.getLogger("");
        boolean hadConsole = false;
        for (Handler h : root.getHandlers()) {
            hadConsole |= h instanceof ConsoleHandler;
        }
        List<String> off = LogSetup.install(new SimwProperties(), "0.9.22");
        boolean console = false;
        boolean jdk = false;
        for (Handler h : root.getHandlers()) {
            console |= h instanceof ConsoleLineHandler;
            jdk |= h instanceof ConsoleHandler;
        }
        check("console handler replaced" + (hadConsole ? "" : " (none before)"), console && !jdk);
        check("syslog off line and status", off.equals(Collections.singletonList("off (syslog.enabled=false)"))
                && Boolean.FALSE.equals(LogSetup.syslogStatus().get("enabled")));
        List<String> bad = LogSetup.install(new SimwProperties().set("syslog.enabled", "true"), "1");
        check("settings mistakes in the banner", bad.size() == 1 && bad.get(0).startsWith("WARNING: syslog.enabled=true needs syslog.host"));

        try (DatagramSocket rx = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            rx.setSoTimeout(3000);
            List<String> on = LogSetup.install(new SimwProperties().set("syslog.enabled", "true").set("syslog.host", "127.0.0.1")
                    .set("syslog.port", String.valueOf(rx.getLocalPort())).set("syslog.level", "FINE"), "0.9.22");
            int consoles = 0;
            for (Handler h : root.getHandlers()) {
                consoles += h instanceof ConsoleLineHandler ? 1 : 0;
            }
            check("installed twice: one console handler", consoles == 1);
            check("syslog line", on.size() == 1 && on.get(0).startsWith("udp 127.0.0.1:" + rx.getLocalPort() + ", RFC 5424"));
            check("root lowered to FINE for syslog", root.getLevel() == Level.FINE);
            PrintStream out = System.out;
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            System.setOut(new PrintStream(o, true));
            try {
                Logger.getLogger("test.setup").fine("fine for syslog only");
            } finally {
                System.setOut(out);
            }
            DatagramPacket p = new DatagramPacket(new byte[2048], 2048);
            String got = null;
            try {
                rx.receive(p);
                got = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
            } catch (SocketTimeoutException e) {
                // below
            }
            check("FINE reaches syslog, not the console", got != null && got.startsWith("<135>1 ")
                    && got.endsWith("fine for syslog only") && !o.toString().contains("fine for syslog only"));
            check("status from LogSetup", Boolean.TRUE.equals(LogSetup.syslogStatus().get("enabled")));
            LogSetup.shutdown();
            check("shutdown: syslog gone", Boolean.FALSE.equals(LogSetup.syslogStatus().get("enabled")) && LogSetup.syslog() == null);
            root.setLevel(Level.INFO);
        }
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }
}
