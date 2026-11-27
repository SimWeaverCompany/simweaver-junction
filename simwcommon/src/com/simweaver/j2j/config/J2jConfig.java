package com.simweaver.j2j.config;

import com.simweaver.common.config.SimwProperties;
import com.simweaver.j2j.crypto.J2jCrypto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The J2J configuration (design §2, §4.1, §10.1), read from three places:
 * <ul>
 * <li>{@code etc/j2j/network.settings}: the network and every member, the same file on every server;</li>
 * <li>{@code etc/j2j/network.key}: the network key(s), {@code <key id>=<base64 of 32 octets>} per line,
 *     optionally {@code preferred=<key id>} (else the first key);</li>
 * <li>{@code etc/server.root.settings}: {@code j2j.node.id}, {@code j2j.port} (8844), {@code j2j.bind},
 *     {@code j2j.fallback.port} (443; 0 = none), {@code j2j.fallback.proxy}, {@code j2j.deadseconds} (5),
 *     {@code j2j.rekeyminutes} (60, at most 60), {@code j2j.udp} (true).</li>
 * </ul>
 * No members, or no member file: J2J is off ({@link #isEnabled()} false, no problems). A file that
 * cannot be used gives {@link #problems()}; J2J then does not start. Immutable.
 */
public final class J2jConfig {

    public static final int DEFAULT_PORT = 8844;
    public static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_.-]{1,16}");

    /** One network key. */
    public static final class Key {
        public final String id;
        private final byte[] secret;

        Key(String id, byte[] secret) {
            this.id = id;
            this.secret = secret;
        }

        /** @return a copy of the 32-octet key */
        public byte[] secret() {
            return secret.clone();
        }

        /** @return 8 hex: the first 4 octets of SHA-256 of the key, safe to show */
        public String fingerprint() {
            return J2jCrypto.fingerprint(secret, 4);
        }
    }

    private final String networkId;
    private final String networkName;
    private final String selfId;
    private final Map<String, J2jMember> members;
    private final List<Key> keys;
    private final int port;
    private final String bind;
    private final int fallbackPort;
    private final String fallbackProxy;
    private final int deadSeconds;
    private final long rekeyMillis;
    private final boolean udp;
    private final String keystore;
    private final long primaryRetryMillis;
    private final String keystorePassword;
    private final String memberHash;
    private final List<String> problems;
    private final boolean enabled;

    private J2jConfig(Builder b) {
        this.networkId = b.networkId;
        this.networkName = b.networkName;
        this.selfId = b.selfId;
        this.members = Collections.unmodifiableMap(b.members);
        this.keys = Collections.unmodifiableList(b.keys);
        this.port = b.port;
        this.bind = b.bind;
        this.fallbackPort = b.fallbackPort;
        this.fallbackProxy = b.fallbackProxy;
        this.deadSeconds = b.deadSeconds;
        this.rekeyMillis = b.rekeyMillis;
        this.udp = b.udp;
        this.keystore = b.keystore;
        this.primaryRetryMillis = b.primaryRetryMillis;
        this.keystorePassword = b.keystorePassword;
        this.problems = Collections.unmodifiableList(b.problems);
        this.memberHash = hashOf(b.members);
        this.enabled = !b.members.isEmpty() && b.problems.isEmpty();
    }

    /**
     * Reads the configuration.
     *
     * @param server     the server settings ({@code j2j.*})
     * @param networkFile {@code etc/j2j/network.settings} (may be missing: J2J off)
     * @param keyFile    {@code etc/j2j/network.key}
     */
    public static J2jConfig load(SimwProperties server, Path networkFile, Path keyFile) {
        Builder b = new Builder();
        b.selfId = server.getString("j2j.node.id", "").trim();
        b.port = port(server, "j2j.port", DEFAULT_PORT, b.problems);
        b.bind = server.getString("j2j.bind", "").trim();
        b.fallbackPort = server.getInt("j2j.fallback.port", 443);
        if (b.fallbackPort < 0 || b.fallbackPort > 65535) {
            b.problems.add("j2j.fallback.port " + b.fallbackPort + " is not 0-65535");
            b.fallbackPort = 0;
        }
        b.fallbackProxy = server.getString("j2j.fallback.proxy", "").trim();
        b.deadSeconds = Math.max(2, Math.min(120, server.getInt("j2j.deadseconds", 5)));
        b.udp = server.getBoolean("j2j.udp", true);
        b.primaryRetryMillis = Math.max(1, Math.min(3600, server.getInt("j2j.fallback.retryseconds", 60))) * 1000L;
        // 0.14.5: the web server's key store (made per site), relative paths against the site root
        b.keystore = com.simweaver.common.config.SimwPaths.site(server.getString("j2j.fallback.keystore",
                server.getString("webserver.keystore.path", com.simweaver.common.tls.ServerKeyStore.defaultPath().toString())).trim()).toString();
        b.keystorePassword = server.getString("j2j.fallback.keystorepwd", server.getString("webserver.keystore.pwd", "password"));
        b.rekeyMillis = (long) (Math.max(0.05, Math.min(60.0, server.getDouble("j2j.rekeyminutes", 60))) * 60_000);

        if (networkFile == null || !Files.isRegularFile(networkFile)) {
            return new J2jConfig(b);                 // off, quietly
        }
        SimwProperties net = new SimwProperties();
        try {
            net.setExpandVariables(false).load(networkFile);
        } catch (IOException e) {
            b.problems.add("cannot read " + networkFile + ": " + e.getMessage());
            return new J2jConfig(b);
        }
        b.networkId = net.getString("network.id", "").trim();
        b.networkName = net.getString("network.name", "").trim();
        Map<String, Map<String, String>> raw = new LinkedHashMap<String, Map<String, String>>();
        final Map<String, Integer> firstLine = new LinkedHashMap<String, Integer>();
        for (String k : net.getKeys()) {
            if (!k.startsWith("member.")) {
                continue;
            }
            int dot = k.indexOf('.', 7);
            if (dot < 0) {
                b.problems.add(networkFile.getFileName() + ": '" + k + "' is not member.<id>.<setting>");
                continue;
            }
            String id = k.substring(7, dot);
            String what = k.substring(dot + 1).toLowerCase(Locale.ROOT);
            raw.computeIfAbsent(id, x -> new LinkedHashMap<String, String>()).put(what, net.getString(k, ""));
            int line = net.getSourceLine(k);
            firstLine.merge(id, line < 0 ? Integer.MAX_VALUE : line, Math::min);
        }
        if (raw.isEmpty()) {
            return new J2jConfig(b);                 // no members: off
        }
        if (b.networkId.isEmpty()) {
            b.problems.add(networkFile.getFileName() + ": network.id is missing");
        }
        List<String> order = new ArrayList<String>(raw.keySet());
        Collections.sort(order, (x, y) -> {
            int c = Integer.compare(firstLine.get(x), firstLine.get(y));
            return c != 0 ? c : x.compareTo(y);
        });
        for (String id : order) {
            Map<String, String> m = raw.get(id);
            if (!ID.matcher(id).matches()) {
                b.problems.add("member id '" + id + "' is not 1-32 of a-z, 0-9 and '-' (starting with a letter or digit)");
                continue;
            }
            for (String what : m.keySet()) {
                if (!what.equals("name") && !what.equals("addresses") && !what.equals("fallback") && !what.equals("site") && !what.equals("connect")) {
                    b.problems.add("member." + id + "." + what + " is not a member setting (name, addresses, fallback, site, connect)");
                }
            }
            List<J2jMember.Address> addrs = new ArrayList<J2jMember.Address>();
            for (String a : m.getOrDefault("addresses", "").split("[;,\\s]+")) {
                if (!a.trim().isEmpty()) {
                    J2jMember.Address ad = address(a.trim(), DEFAULT_PORT, "member." + id + ".addresses", b.problems);
                    if (ad != null) {
                        addrs.add(ad);
                    }
                }
            }
            String fb = m.getOrDefault("fallback", "").trim();
            J2jMember.Address fallback = fb.isEmpty() ? null : address(fb, 443, "member." + id + ".fallback", b.problems);
            String connect = m.getOrDefault("connect", "true").trim().toLowerCase(Locale.ROOT);
            if (!connect.equals("true") && !connect.equals("false")) {
                b.problems.add("member." + id + ".connect '" + connect + "' is not true or false");
            }
            String name = m.getOrDefault("name", "").trim();
            String site = m.getOrDefault("site", "").trim();
            b.members.put(id, new J2jMember(id, name.isEmpty() ? id : name, site.isEmpty() ? null : site, addrs, fallback, !connect.equals("false")));
        }
        // node keys must differ (64-bit hashes of the ids)
        Map<Long, String> seen = new LinkedHashMap<Long, String>();
        for (J2jMember mm : b.members.values()) {
            String other = seen.put(mm.key(), mm.id());
            if (other != null) {
                b.problems.add("members " + other + " and " + mm.id() + " have the same node key; rename one");
            }
        }
        if (b.selfId.isEmpty()) {
            b.problems.add("j2j.node.id is not set in the server settings (this server's id in the member list)");
        } else if (!b.members.containsKey(b.selfId)) {
            b.problems.add("j2j.node.id '" + b.selfId + "' is not in " + networkFile.getFileName());
        }
        readKeys(keyFile, b);
        return new J2jConfig(b);
    }

    private static void readKeys(Path keyFile, Builder b) {
        if (keyFile == null || !Files.isRegularFile(keyFile)) {
            b.problems.add("the network key file " + (keyFile == null ? "etc/j2j/network.key" : keyFile) + " is missing");
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(keyFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            b.problems.add("cannot read " + keyFile + ": " + e.getMessage());
            return;
        }
        String preferred = null;
        int n = 0;
        for (String raw : lines) {
            n++;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                b.problems.add(keyFile.getFileName() + " line " + n + ": not <key id>=<key>");
                continue;
            }
            String id = line.substring(0, eq).trim();
            String val = line.substring(eq + 1).trim();
            if (id.equals("preferred")) {
                preferred = val;
                continue;
            }
            if (!KEY_ID.matcher(id).matches()) {
                b.problems.add(keyFile.getFileName() + " line " + n + ": key id '" + id + "' is not 1-16 letters, digits, '_', '.', '-'");
                continue;
            }
            byte[] secret;
            try {
                secret = Base64.getDecoder().decode(val);
            } catch (IllegalArgumentException e) {
                secret = null;
            }
            if (secret == null || secret.length != 32) {
                b.problems.add(keyFile.getFileName() + " line " + n + ": key " + id + " is not 32 octets in base64");
                continue;
            }
            for (Key k : b.keys) {
                if (k.id.equals(id)) {
                    b.problems.add(keyFile.getFileName() + ": key id " + id + " appears twice");
                }
            }
            b.keys.add(new Key(id, secret));
        }
        if (b.keys.isEmpty()) {
            b.problems.add(keyFile.getFileName() + " holds no key");
        } else if (preferred != null) {
            Key p = null;
            for (Key k : b.keys) {
                if (k.id.equals(preferred)) {
                    p = k;
                }
            }
            if (p == null) {
                b.problems.add(keyFile.getFileName() + ": preferred=" + preferred + " is not a key in the file");
            } else {
                b.keys.remove(p);
                b.keys.add(0, p);
            }
        }
    }

    private static int port(SimwProperties p, String key, int def, List<String> problems) {
        int v = p.getInt(key, def);
        if (v < 1 || v > 65535) {
            problems.add(key + " " + v + " is not 1-65535");
            return def;
        }
        return v;
    }

    /** host, host:port, [v6]:port */
    static J2jMember.Address address(String s, int defPort, String where, List<String> problems) {
        String host = s;
        int port = defPort;
        if (s.startsWith("[")) {
            int close = s.indexOf(']');
            if (close < 0) {
                problems.add(where + ": '" + s + "' is not an address");
                return null;
            }
            host = s.substring(1, close);
            if (close + 1 < s.length()) {
                if (s.charAt(close + 1) != ':') {
                    problems.add(where + ": '" + s + "' is not an address");
                    return null;
                }
                port = parsePort(s.substring(close + 2));
            }
        } else if (s.indexOf(':') >= 0 && s.indexOf(':') == s.lastIndexOf(':')) {
            host = s.substring(0, s.indexOf(':'));
            port = parsePort(s.substring(s.indexOf(':') + 1));
        }
        if (host.isEmpty() || port < 1) {
            problems.add(where + ": '" + s + "' is not host or host:port");
            return null;
        }
        return new J2jMember.Address(host, port);
    }

    private static int parsePort(String p) {
        try {
            int v = Integer.parseInt(p.trim());
            return v >= 1 && v <= 65535 ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The 64-bit node key of an id: the first 8 octets of SHA-256 of its UTF-8. */
    public static long nodeKey(String id) {
        byte[] h = J2jCrypto.sha256(id.getBytes(StandardCharsets.UTF_8));
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (h[i] & 0xFF);
        }
        return v;
    }

    private static String hashOf(Map<String, J2jMember> members) {
        StringBuilder b = new StringBuilder();
        new TreeMap<String, J2jMember>(members).values().forEach(m -> b.append(m.canonical()).append('\n'));
        return J2jCrypto.fingerprint(b.toString().getBytes(StandardCharsets.UTF_8), 4);
    }

    // ---- accessors ------------------------------------------------------------------------

    /** @return true when J2J should run: members listed and no problems */
    public boolean isEnabled() {
        return enabled;
    }

    /** @return true when the member file lists members (J2J configured, maybe with problems) */
    public boolean isConfigured() {
        return !members.isEmpty() || !problems.isEmpty();
    }

    public List<String> problems() {
        return problems;
    }

    public String networkId() {
        return networkId;
    }

    public String networkName() {
        return networkName.isEmpty() ? networkId : networkName;
    }

    public String selfId() {
        return selfId;
    }

    public J2jMember self() {
        return members.get(selfId);
    }

    /** @return every member, in file order, this server included */
    public Map<String, J2jMember> members() {
        return members;
    }

    public J2jMember member(String id) {
        return members.get(id);
    }

    /** @return the member with this node key, or null */
    public J2jMember memberByKey(long key) {
        for (J2jMember m : members.values()) {
            if (m.key() == key) {
                return m;
            }
        }
        return null;
    }

    /** @return the keys, the preferred one first */
    public List<Key> keys() {
        return keys;
    }

    public Key key(String id) {
        for (Key k : keys) {
            if (k.id.equals(id)) {
                return k;
            }
        }
        return null;
    }

    public Key preferredKey() {
        return keys.isEmpty() ? null : keys.get(0);
    }

    public int port() {
        return port;
    }

    /** @return the address to listen on, or "" for all */
    public String bind() {
        return bind;
    }

    public int fallbackPort() {
        return fallbackPort;
    }

    public String fallbackProxy() {
        return fallbackProxy;
    }

    public int deadSeconds() {
        return deadSeconds;
    }

    /** @return the key store of the TLS fallback listener (the web server's unless {@code j2j.fallback.keystore}) */
    public String fallbackKeystore() {
        return keystore;
    }

    public String fallbackKeystorePassword() {
        return keystorePassword;
    }

    /** @return how often a link on the TLS last resort tries the primary addresses again ({@code j2j.fallback.retryseconds}, 60) */
    public long primaryRetryMillis() {
        return primaryRetryMillis;
    }

    /** @return false when {@code j2j.udp=false}: best effort over TCP only */
    public boolean udpEnabled() {
        return udp;
    }

    /** @return how long a link may run before it is re-keyed (at most an hour; {@code j2j.rekeyminutes}) */
    public long rekeyMillis() {
        return rekeyMillis;
    }

    /** @return 8 hex: the hash of the member list, advertised so servers can compare */
    public String memberHash() {
        return memberHash;
    }

    private static final class Builder {
        String networkId = "";
        String networkName = "";
        String selfId = "";
        final Map<String, J2jMember> members = new LinkedHashMap<String, J2jMember>();
        final List<Key> keys = new ArrayList<Key>();
        int port = DEFAULT_PORT;
        String bind = "";
        int fallbackPort = 443;
        String fallbackProxy = "";
        int deadSeconds = 5;
        long rekeyMillis = 3_600_000;
        boolean udp = true;
        String keystore = "keystore.jks";
        long primaryRetryMillis = 60_000;
        String keystorePassword = "password";
        final List<String> problems = new ArrayList<String>();
    }
}
