package com.simweaver.common.config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a {@code main(String[] args)} array into a {@link SimwProperties}.
 *
 * <p>The expected command line is plain {@code key=value} tokens:</p>
 *
 * <pre>
 * java -jar simweaver.jar rti.host=localhost rti.port=9000 federation=SimWeaverFederation
 * </pre>
 *
 * <pre>{@code
 * public static void main(String[] args) {
 *     SimwProperties cfg = SimwArgs.toProperties(args);
 *     ...
 * }
 * }</pre>
 *
 * <p>The usual real-world shape is a defaults file with the command line layered on top, which is
 * exactly the overlay semantics {@link SimwProperties} already has:</p>
 *
 * <pre>{@code
 * SimwProperties cfg = new SimwProperties().load("conf/defaults.properties");
 * new SimwArgs().withConfigKeys("config").parseInto(cfg, args);
 * }</pre>
 *
 * <p>With {@code config} registered as a config key, {@code java -jar simweaver.jar
 * config=site.properties rti.port=9000} loads {@code site.properties} <em>first</em> and then
 * applies every command line value on top, so an explicit argument always wins over a file the
 * same command line named. The same is true for {@code @file} tokens.</p>
 *
 * <h2>What a token can look like</h2>
 * <table border="1">
 *   <caption>Token forms</caption>
 *   <tr><th>Token</th><th>Effect</th></tr>
 *   <tr><td>{@code key=value}</td><td>sets {@code key}</td></tr>
 *   <tr><td>{@code key=}</td><td>sets {@code key} to the empty string ({@code isSet} is true)</td></tr>
 *   <tr><td>{@code key="a b"}</td><td>surrounding quotes are removed if the shell left them</td></tr>
 *   <tr><td>{@code --key=value}, {@code -key=value}, {@code -Dkey=value}</td><td>same as {@code key=value}</td></tr>
 *   <tr><td>{@code --verbose}</td><td>flag: sets {@code verbose=true}</td></tr>
 *   <tr><td>{@code --no-verbose}</td><td>flag: sets {@code verbose=false}</td></tr>
 *   <tr><td>{@code @conf/site.properties}</td><td>loads that properties file</td></tr>
 *   <tr><td>{@code plain-token}</td><td>positional, stored as {@code arg.0}, {@code arg.1}, … plus {@code arg.count}</td></tr>
 *   <tr><td>{@code --}</td><td>everything after it is positional, verbatim</td></tr>
 * </table>
 *
 * <p>Every value keeps its provenance: {@link SimwProperties#getSource(String)} reports
 * {@code <args>} and {@link SimwProperties#getSourceLine(String)} the 1-based index of the
 * argument that supplied it, so a {@link SimwProperties#dump()} shows at a glance which settings
 * came from the command line and which from a file.</p>
 *
 * <p>Instances are immutable-by-convention configuration holders and can be reused across calls;
 * the {@code with…} methods mutate and return {@code this}, so configure once at start-up.</p>
 *
 * <p>Java 11, no third-party dependencies.</p>
 */
public final class SimwArgs {

    /** Source label reported for values that came from the command line. */
    public static final String DEFAULT_SOURCE = "<args>";

    /** Key prefix under which positional (non {@code key=value}) arguments are stored. */
    public static final String DEFAULT_POSITIONAL_PREFIX = "arg.";

    /** Key holding the number of positional arguments, appended to the positional prefix. */
    public static final String COUNT_SUFFIX = "count";

    private final Map<String, String> aliases = new LinkedHashMap<>();
    private final Set<String> configKeys = new LinkedHashSet<>();

    private String source = DEFAULT_SOURCE;
    private String positionalPrefix = DEFAULT_POSITIONAL_PREFIX;
    private boolean flagsAllowed = true;
    private boolean stripDashes = true;
    private boolean atFilesAllowed = true;
    private boolean strict = false;

    // ------------------------------------------------------------------
    // Static shortcuts
    // ------------------------------------------------------------------

    /** Parses {@code key=value} arguments with default settings into a fresh instance. */
    public static SimwProperties toProperties(String[] args) {
        return new SimwArgs().parseInto(new SimwProperties(), args);
    }

    /**
     * Parses arguments on top of the given properties file, so the command line overrides
     * the file. Equivalent to a {@code load} followed by {@link #parseInto}.
     */
    public static SimwProperties toProperties(String[] args, String defaultsFile) throws IOException {
        SimwProperties props = new SimwProperties();
        props.loadIfExists(defaultsFile);
        return new SimwArgs().parseInto(props, args);
    }

    /** The positional arguments stored by a parse that used the default prefix. */
    public static String[] positionals(SimwProperties props) {
        return positionals(props, DEFAULT_POSITIONAL_PREFIX);
    }

    /** The positional arguments stored by a parse that used the given prefix. */
    public static String[] positionals(SimwProperties props, String prefix) {
        if (props == null || prefix == null) {
            return new String[0];
        }
        int count = props.getInt(prefix + COUNT_SUFFIX, 0);
        String[] out = new String[Math.max(0, count)];
        for (int i = 0; i < out.length; i++) {
            out[i] = props.getString(prefix + i, "");
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    /** Overrides the source label reported by {@link SimwProperties#getSource(String)}. */
    public SimwArgs withSourceLabel(String label) {
        this.source = (label == null || label.isEmpty()) ? DEFAULT_SOURCE : label;
        return this;
    }

    /**
     * Registers keys whose <em>value</em> names a properties file to load before the command line
     * is applied — for example {@code withConfigKeys("config")} makes
     * {@code config=conf/site.properties} load that file.
     */
    public SimwArgs withConfigKeys(String... keys) {
        if (keys != null) {
            for (String key : keys) {
                if (key != null && !key.trim().isEmpty()) {
                    configKeys.add(key.trim());
                }
            }
        }
        return this;
    }

    /** Maps a short or legacy argument name onto the real property key. */
    public SimwArgs withAlias(String argumentName, String propertyKey) {
        if (argumentName == null || argumentName.trim().isEmpty()) {
            throw new IllegalArgumentException("argumentName is null or empty");
        }
        if (propertyKey == null || propertyKey.trim().isEmpty()) {
            throw new IllegalArgumentException("propertyKey is null or empty");
        }
        aliases.put(argumentName.trim(), propertyKey.trim());
        return this;
    }

    /** Bulk form of {@link #withAlias(String, String)}. */
    public SimwArgs withAliases(Map<String, String> map) {
        if (map != null) {
            for (Map.Entry<String, String> e : map.entrySet()) {
                withAlias(e.getKey(), e.getValue());
            }
        }
        return this;
    }

    /**
     * Key prefix for positional arguments; {@code null} discards them instead of storing them.
     * Default {@value #DEFAULT_POSITIONAL_PREFIX}.
     */
    public SimwArgs withPositionalPrefix(String prefix) {
        this.positionalPrefix = prefix;
        return this;
    }

    /** Whether {@code --flag} / {@code --no-flag} tokens become {@code true} / {@code false}. Default true. */
    public SimwArgs withFlags(boolean allowed) {
        this.flagsAllowed = allowed;
        return this;
    }

    /** Whether leading {@code -}, {@code --} and {@code -D} are stripped from keys. Default true. */
    public SimwArgs withDashStripping(boolean strip) {
        this.stripDashes = strip;
        return this;
    }

    /** Whether {@code @file} tokens load a properties file. Default true. */
    public SimwArgs withAtFiles(boolean allowed) {
        this.atFilesAllowed = allowed;
        return this;
    }

    /**
     * In strict mode any token that is not a recognised {@code key=value}, flag or {@code @file}
     * raises {@link SimwPropertyException} instead of being kept as a positional argument.
     * Default false.
     */
    public SimwArgs withStrict(boolean strict) {
        this.strict = strict;
        return this;
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    /** Parses the arguments into a new {@link SimwProperties}. */
    public SimwProperties parse(String... args) {
        return parseInto(new SimwProperties(), args);
    }

    /**
     * Overlays the arguments onto an existing {@link SimwProperties} and returns it, so the
     * command line takes precedence over everything loaded before.
     *
     * <p>Any properties file named on the command line (a config key or an {@code @file} token)
     * is loaded first, in the order the tokens appeared; every {@code key=value} argument is then
     * applied on top.</p>
     *
     * @throws SimwPropertyException if a file named on the command line cannot be read, or if a
     *                               token is unrecognised while strict mode is on
     */
    public SimwProperties parseInto(SimwProperties target, String... args) {
        if (target == null) {
            throw new IllegalArgumentException("target is null");
        }
        if (args == null || args.length == 0) {
            return target;
        }

        List<String[]> assignments = new ArrayList<>(args.length);   // {key, value, index}
        List<String[]> fileTokens = new ArrayList<>();               // {path, index}
        List<String> positionals = new ArrayList<>();
        boolean literalsOnly = false;

        for (int i = 0; i < args.length; i++) {
            String raw = args[i];
            int position = i + 1;
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            if (literalsOnly) {
                positionals.add(raw);
                continue;
            }
            String token = raw.trim();

            if ("--".equals(token)) {
                literalsOnly = true;
                continue;
            }
            if (atFilesAllowed && token.length() > 1 && token.charAt(0) == '@') {
                fileTokens.add(new String[] {token.substring(1), String.valueOf(position)});
                continue;
            }

            String body = stripDashes ? stripLeadingDashes(token) : token;
            int eq = body.indexOf('=');

            if (eq < 0) {
                if (flagsAllowed && token.charAt(0) == '-' && !body.isEmpty()) {
                    boolean negated = body.startsWith("no-") || body.startsWith("no.");
                    String key = alias(negated ? body.substring(3) : body);
                    if (key.isEmpty()) {
                        reject(target, raw, position, "empty flag name");
                        continue;
                    }
                    assignments.add(new String[] {key, negated ? "false" : "true", String.valueOf(position)});
                } else if (strict) {
                    reject(target, raw, position, "expected key=value");
                } else {
                    positionals.add(raw);
                }
                continue;
            }

            String key = alias(body.substring(0, eq).trim());
            if (key.isEmpty()) {
                reject(target, raw, position, "empty key");
                continue;
            }
            String value = unquote(body.substring(eq + 1));
            assignments.add(new String[] {key, value, String.valueOf(position)});
            if (configKeys.contains(key)) {
                fileTokens.add(new String[] {value, String.valueOf(position)});
            }
        }

        // 1. Files named on the command line, in order.
        for (String[] file : fileTokens) {
            if (file[0].isEmpty()) {
                continue;
            }
            try {
                target.load(file[0]);
            } catch (IOException e) {
                throw new SimwPropertyException(null, "Cannot read the properties file named by "
                        + "argument " + file[1] + " ('" + file[0] + "'): " + e.getMessage(), e);
            }
        }

        // 2. Command line values, which therefore win.
        for (String[] a : assignments) {
            target.set(a[0], a[1], source, Integer.parseInt(a[2]));
        }

        // 3. Positionals.
        if (positionalPrefix != null && !positionals.isEmpty()) {
            for (int i = 0; i < positionals.size(); i++) {
                target.set(positionalPrefix + i, positionals.get(i), source, 0);
            }
            target.set(positionalPrefix + COUNT_SUFFIX, String.valueOf(positionals.size()), source, 0);
        }

        return target;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private String alias(String key) {
        String mapped = aliases.get(key);
        return (mapped == null) ? key : mapped;
    }

    /** Strict: fail fast. Otherwise: record it as a warning on the target and carry on. */
    private void reject(SimwProperties target, String token, int position, String why) {
        String message = "Cannot interpret argument " + position + " ('" + token + "'): " + why;
        if (strict) {
            throw new SimwPropertyException(null, message);
        }
        target.addWarning(message);
    }

    /** Removes a leading {@code -D}, or any run of leading dashes. */
    private static String stripLeadingDashes(String token) {
        if (token.startsWith("-D") && token.length() > 2) {
            return token.substring(2);
        }
        int i = 0;
        while (i < token.length() && token.charAt(i) == '-') {
            i++;
        }
        return token.substring(i);
    }

    /** Removes one layer of matching surrounding quotes, if the shell left them in place. */
    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' || first == '\'') && first == last) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    @Override
    public String toString() {
        return "SimwArgs[source=" + source + ", configKeys=" + configKeys
                + ", aliases=" + aliases.keySet()
                + ", flags=" + flagsAllowed + ", atFiles=" + atFilesAllowed
                + ", positionalPrefix=" + positionalPrefix
                + ", strict=" + strict + "]";
    }

    /** Unmodifiable view of the registered config keys, for diagnostics. */
    public String[] getConfigKeys() {
        return configKeys.toArray(new String[0]);
    }

    /** Unmodifiable view of the registered aliases, for diagnostics. */
    public String[] getAliasNames() {
        return aliases.keySet().toArray(new String[0]);
    }
}
