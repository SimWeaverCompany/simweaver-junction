package com.simweaver.common.logging;

import com.simweaver.common.config.SimwProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

/**
 * The {@code syslog.*} server settings (0.9.22). Mistakes are collected in {@link #problems()};
 * syslog then stays off and the server starts anyway.
 *
 * <pre>
 * syslog.enabled          false
 * syslog.host             (required)                the syslog server: name or address
 * syslog.port             514 (udp, tcp), 6514 (tls)
 * syslog.protocol         udp                       udp (RFC 5426), tcp (RFC 6587), tls (RFC 5425)
 * syslog.format           rfc5424                   rfc5424 or rfc3164 (BSD)
 * syslog.facility         local0                    kern user mail daemon auth syslog lpr news uucp cron
 *                                                   authpriv ftp local0 … local7, or 0-23
 * syslog.level            INFO                      the lowest level sent: SEVERE WARNING NOTICE INFO CONFIG FINE
 * syslog.appname          simweaver                 APP-NAME / TAG
 * syslog.hostname         (this computer's name)    HOSTNAME
 * syslog.tcp.framing      octet (rfc5424), lf (rfc3164)   tcp / tls: octet counting or a newline after each message
 * syslog.maxlength        2048 (udp; 1024 with rfc3164), 8192 (tcp, tls)   longer messages are cut
 * syslog.queuesize        10000                     messages waiting while the server cannot be reached
 * syslog.bom              true                      rfc5424: UTF-8 BOM before the message, as RFC 5424 asks
 * syslog.tls.truststore   (Java's default)          a JKS / PKCS12 file with the syslog server's CA
 * syslog.tls.truststorepassword
 * syslog.tls.verifyhostname  true                   the certificate must name syslog.host
 * </pre>
 */
public final class SyslogSettings {

    public enum Protocol { UDP, TCP, TLS }

    public enum Format { RFC5424, RFC3164 }

    private static final String[] FACILITIES = {
        "kern", "user", "mail", "daemon", "auth", "syslog", "lpr", "news", "uucp", "cron", "authpriv", "ftp",
        "ntp", "security", "console", "solaris-cron", "local0", "local1", "local2", "local3", "local4", "local5",
        "local6", "local7"
    };

    private final boolean enabled;
    private final String host;
    private final int port;
    private final Protocol protocol;
    private final Format format;
    private final int facility;
    private final Level level;
    private final String appName;
    private final String hostName;
    private final boolean octetFraming;
    private final int maxLength;
    private final int queueSize;
    private final boolean bom;
    private final String trustStore;
    private final String trustStorePassword;
    private final boolean verifyHostname;
    private final List<String> problems = new ArrayList<String>();

    public SyslogSettings(SimwProperties p) {
        boolean on = p.getBoolean("syslog.enabled", false);
        this.host = p.getString("syslog.host", "").trim();
        String proto = p.getString("syslog.protocol", "udp").trim().toUpperCase(Locale.ROOT);
        Protocol pr;
        try {
            pr = Protocol.valueOf(proto);
        } catch (IllegalArgumentException e) {
            problems.add("syslog.protocol '" + proto.toLowerCase(Locale.ROOT) + "' is not udp, tcp or tls");
            pr = Protocol.UDP;
        }
        this.protocol = pr;
        String fmt = p.getString("syslog.format", "rfc5424").trim().toUpperCase(Locale.ROOT).replace("-", "").replace(" ", "");
        Format f;
        if (fmt.equals("RFC5424") || fmt.equals("5424")) {
            f = Format.RFC5424;
        } else if (fmt.equals("RFC3164") || fmt.equals("3164") || fmt.equals("BSD")) {
            f = Format.RFC3164;
        } else {
            problems.add("syslog.format '" + fmt.toLowerCase(Locale.ROOT) + "' is not rfc5424 or rfc3164");
            f = Format.RFC5424;
        }
        this.format = f;
        int port = p.getInt("syslog.port", pr == Protocol.TLS ? 6514 : 514);
        if (port < 1 || port > 65535) {
            problems.add("syslog.port " + port + " is not 1-65535");
            port = 514;
        }
        this.port = port;
        this.facility = facility(p.getString("syslog.facility", "local0").trim());
        this.level = level(p.getString("syslog.level", "INFO").trim());
        String app = printable(p.getString("syslog.appname", "simweaver").trim(), 48);
        this.appName = app.isEmpty() ? "simweaver" : app;
        String hn = printable(p.getString("syslog.hostname", "").trim(), 255);
        this.hostName = hn.isEmpty() ? localHostName() : hn;
        String framing = p.getString("syslog.tcp.framing", f == Format.RFC5424 ? "octet" : "lf").trim().toLowerCase(Locale.ROOT);
        if (!framing.equals("octet") && !framing.equals("lf")) {
            problems.add("syslog.tcp.framing '" + framing + "' is not octet or lf");
            framing = f == Format.RFC5424 ? "octet" : "lf";
        }
        this.octetFraming = framing.equals("octet");
        int max = p.getInt("syslog.maxlength", pr != Protocol.UDP ? 8192 : f == Format.RFC3164 ? 1024 : 2048);
        this.maxLength = Math.max(480, Math.min(65000, max));
        this.queueSize = Math.max(100, Math.min(1_000_000, p.getInt("syslog.queuesize", 10000)));
        this.bom = p.getBoolean("syslog.bom", true);
        String ts = p.getString("syslog.tls.truststore", "").trim();
        this.trustStore = ts.isEmpty() ? "" : com.simweaver.common.config.SimwPaths.site(ts).toString();   // 0.14.5: against the site
        this.trustStorePassword = p.getString("syslog.tls.truststorepassword", "");
        this.verifyHostname = p.getBoolean("syslog.tls.verifyhostname", true);
        if (on && host.isEmpty()) {
            problems.add("syslog.enabled=true needs syslog.host");
        }
        this.enabled = on && problems.isEmpty();
    }

    private int facility(String v) {
        String s = v.toLowerCase(Locale.ROOT);
        for (int i = 0; i < FACILITIES.length; i++) {
            if (FACILITIES[i].equals(s)) {
                return i;
            }
        }
        try {
            int n = Integer.parseInt(s);
            if (n >= 0 && n <= 23) {
                return n;
            }
        } catch (NumberFormatException ignored) {
            // below
        }
        problems.add("syslog.facility '" + v + "' is not a facility name (user, daemon, local0 … local7) or 0-23");
        return 16;
    }

    private Level level(String v) {
        if ("NOTICE".equalsIgnoreCase(v)) {
            return Events.NOTICE;
        }
        try {
            return Level.parse(v.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            problems.add("syslog.level '" + v + "' is not SEVERE, WARNING, NOTICE, INFO, CONFIG or FINE");
            return Level.INFO;
        }
    }

    /** Printable US-ASCII without spaces, at most {@code max} characters (RFC 5424 header fields). */
    static String printable(String s, int max) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < max; i++) {
            char c = s.charAt(i);
            if (c > ' ' && c < 0x7F) {
                b.append(c);
            }
        }
        return b.toString();
    }

    static String localHostName() {
        try {
            String n = printable(java.net.InetAddress.getLocalHost().getHostName(), 255);
            if (!n.isEmpty()) {
                return n;
            }
        } catch (Exception ignored) {
            // below
        }
        String env = System.getenv("COMPUTERNAME");
        if (env == null) {
            env = System.getenv("HOSTNAME");
        }
        String n = env == null ? "" : printable(env, 255);
        return n.isEmpty() ? "-" : n;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public Protocol protocol() {
        return protocol;
    }

    public Format format() {
        return format;
    }

    public int facility() {
        return facility;
    }

    public Level level() {
        return level;
    }

    public String appName() {
        return appName;
    }

    public String hostName() {
        return hostName;
    }

    public boolean octetFraming() {
        return octetFraming;
    }

    public int maxLength() {
        return maxLength;
    }

    public int queueSize() {
        return queueSize;
    }

    public boolean bom() {
        return bom;
    }

    public String trustStore() {
        return trustStore;
    }

    public String trustStorePassword() {
        return trustStorePassword;
    }

    public boolean verifyHostname() {
        return verifyHostname;
    }

    /** @return what is wrong with the settings (syslog stays off when any) */
    public List<String> problems() {
        return Collections.unmodifiableList(problems);
    }

    /** @return e.g. "udp 10.0.0.9:514, RFC 5424, facility local0, INFO and above" */
    public String describe() {
        return protocol.name().toLowerCase(Locale.ROOT) + " " + host + ":" + port + ", "
                + (format == Format.RFC5424 ? "RFC 5424" : "RFC 3164") + ", facility "
                + (facility < FACILITIES.length ? FACILITIES[facility] : String.valueOf(facility)) + ", "
                + level.getName() + " and above";
    }
}
