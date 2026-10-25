package com.simweaver.common.config;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * A layered configuration store for {@code .properties} style files.
 *
 * <h2>Layering</h2>
 * Every call to one of the {@code load} methods overlays the current state: keys already
 * present are overwritten with the newer value, keys that are new are added, and keys that
 * the newer file does not mention keep their previous value. Load order therefore expresses
 * precedence, lowest first:
 *
 * <pre>{@code
 * SimwProperties cfg = new SimwProperties()
 *         .load("conf/defaults.properties")
 *         .load("conf/site.properties")
 *         .load("conf/local.properties");
 * }</pre>
 *
 * <h2>Provenance</h2>
 * Each key remembers the file (and line) that last supplied its value, which is what makes a
 * layered configuration debuggable: {@link #getSource(String)}, {@link #getSourceLine(String)},
 * {@link #getPreviousSource(String)} and {@link #getLoadedFiles()}. {@link #dump()} prints the
 * whole picture at once.
 *
 * <h2>Reading values</h2>
 * Typed accessors come in two flavours:
 * <ul>
 *   <li><b>with a default</b> — {@code getInt("rti.port", 8989)} never throws: a missing or
 *       unparsable value yields the default (and records a {@linkplain #getWarnings() warning}),
 *       unless {@linkplain #setStrict(boolean) strict mode} is on.</li>
 *   <li><b>without a default</b> — {@code getInt("rti.port")} treats the property as required
 *       and throws {@link SimwPropertyException} when it is missing or unparsable. The two
 *       exceptions are {@link #getString(String)}, which returns {@code null} for an absent key,
 *       and {@link #getStringArray(String)}, which returns an empty array.</li>
 * </ul>
 *
 * <h2>File format</h2>
 * The parser follows {@link java.util.Properties} semantics — {@code #} and {@code !} comments,
 * {@code =}, {@code :} or whitespace as the key/value separator, backslash line continuations,
 * and the usual {@code \t \n \r \f \\ &#92;uXXXX} escapes — with three deliberate improvements:
 * files are read as UTF-8 by default, insertion order is preserved, and unescaped trailing
 * whitespace on a value is trimmed.
 *
 * <h2>Extras</h2>
 * <ul>
 *   <li>{@link #getStringArray(String)} splits on {@code ;} — {@code hosts=a;b;c}.</li>
 *   <li>{@code ${...}} references are expanded on read against other properties, then system
 *       properties, then environment variables; {@code ${key:-fallback}} supplies an inline
 *       default, {@code ${env:PATH}} / {@code ${sys:user.home}} address a source explicitly,
 *       and {@code $${...}} is a literal. Disable with {@link #setExpandVariables(boolean)}.</li>
 *   <li>{@link #getMillis(String, long)} understands {@code 30s}, {@code 5m}, {@code 250ms};
 *       {@link #getByteSize(String, long)} understands {@code 64k}, {@code 8M}, {@code 1G}.</li>
 *   <li>Values whose key looks like a credential are masked in {@link #dump()}.</li>
 * </ul>
 *
 * <p>Instances are thread safe; reads and loads are serialised on an internal lock.</p>
 *
 * <p>Java 11, no third-party dependencies.</p>
 */
public final class SimwProperties {

    /** Separator used by {@link #getStringArray(String)} unless changed. */
    public static final char DEFAULT_ARRAY_SEPARATOR = ';';

    /** Source label used for values set programmatically via {@link #set(String, String)}. */
    public static final String RUNTIME_SOURCE = "<runtime>";

    private static final int MAX_EXPANSION_DEPTH = 32;

    /** One stored property, with the provenance that came with it. */
    private static final class Entry {
        final String value;
        final String source;
        final int line;
        final String previousSource;
        final int assignments;

        Entry(String value, String source, int line, Entry previous) {
            this.value = value;
            this.source = source;
            this.line = line;
            this.previousSource = (previous == null) ? null : previous.source;
            this.assignments = (previous == null) ? 1 : previous.assignments + 1;
        }
    }

    private final Object lock = new Object();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Set<String> files = new LinkedHashSet<>();
    private final List<String> warnings = new ArrayList<>();
    private final List<String> secretMarkers = new ArrayList<>(Arrays.asList(
            "password", "passwd", "secret", "token", "credential", "apikey", "api.key", "privatekey"));

    private char arraySeparator = DEFAULT_ARRAY_SEPARATOR;
    private boolean strict = false;
    private boolean expandVariables = true;
    private Charset charset = StandardCharsets.UTF_8;

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    public SimwProperties() {
        // empty
    }

    /**
     * Convenience factory: creates an instance and overlays the given files in order,
     * lowest precedence first.
     */
    public static SimwProperties fromFiles(String... paths) throws IOException {
        SimwProperties p = new SimwProperties();
        if (paths != null) {
            for (String path : paths) {
                p.load(path);
            }
        }
        return p;
    }

    /**
     * Like {@link #fromFiles(String...)} but silently skips files that do not exist,
     * which suits the common "defaults + optional local override" layout.
     */
    public static SimwProperties fromExistingFiles(String... paths) throws IOException {
        SimwProperties p = new SimwProperties();
        if (paths != null) {
            for (String path : paths) {
                p.loadIfExists(path);
            }
        }
        return p;
    }

    // ------------------------------------------------------------------
    // Configuration of this instance
    // ------------------------------------------------------------------

    /** Charset used to read subsequent files. Default UTF-8. */
    public SimwProperties setCharset(Charset cs) {
        synchronized (lock) {
            this.charset = (cs == null) ? StandardCharsets.UTF_8 : cs;
        }
        return this;
    }

    public Charset getCharset() {
        synchronized (lock) {
            return charset;
        }
    }

    /**
     * In strict mode a value that cannot be interpreted as the requested type throws
     * {@link SimwPropertyException} instead of falling back to the supplied default.
     * Default {@code false}.
     */
    public SimwProperties setStrict(boolean strict) {
        synchronized (lock) {
            this.strict = strict;
        }
        return this;
    }

    public boolean isStrict() {
        synchronized (lock) {
            return strict;
        }
    }

    /** Enables/disables {@code ${...}} expansion on read. Default {@code true}. */
    public SimwProperties setExpandVariables(boolean expand) {
        synchronized (lock) {
            this.expandVariables = expand;
        }
        return this;
    }

    public boolean isExpandVariables() {
        synchronized (lock) {
            return expandVariables;
        }
    }

    /** Separator used by the {@code get*Array} methods. Default {@code ';'}. */
    public SimwProperties setArraySeparator(char separator) {
        synchronized (lock) {
            this.arraySeparator = separator;
        }
        return this;
    }

    public char getArraySeparator() {
        synchronized (lock) {
            return arraySeparator;
        }
    }

    /**
     * Adds a lower-case fragment that marks a key as secret, so its value is masked in
     * {@link #dump()}. Matching is a case-insensitive substring test on the key.
     */
    public SimwProperties addSecretMarker(String fragment) {
        if (fragment != null && !fragment.trim().isEmpty()) {
            synchronized (lock) {
                secretMarkers.add(fragment.trim().toLowerCase(Locale.ROOT));
            }
        }
        return this;
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    /** Loads (and overlays) a properties file. */
    public SimwProperties load(String path) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("path is null");
        }
        return load(Paths.get(path));
    }

    /** Loads (and overlays) a properties file. */
    public SimwProperties load(File file) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("file is null");
        }
        return load(file.toPath());
    }

    /** Loads (and overlays) a properties file. */
    public SimwProperties load(Path file) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("file is null");
        }
        String source = describe(file);
        try (Reader reader = Files.newBufferedReader(file, charsetSnapshot())) {
            return read(reader, source, true);
        }
    }

    /**
     * Loads the file if it exists and is readable; otherwise does nothing.
     *
     * @return {@code true} if the file was read
     */
    public boolean loadIfExists(String path) throws IOException {
        return path != null && loadIfExists(Paths.get(path));
    }

    /**
     * Loads the file if it exists and is readable; otherwise does nothing.
     *
     * @return {@code true} if the file was read
     */
    public boolean loadIfExists(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            return false;
        }
        try {
            load(file);
            return true;
        } catch (NoSuchFileException e) {
            return false;
        }
    }

    /**
     * Overlays properties read from a stream. The stream is <em>not</em> closed.
     *
     * @param sourceName label reported by {@link #getSource(String)} and {@link #dump()}
     */
    public SimwProperties load(InputStream in, String sourceName) throws IOException {
        if (in == null) {
            throw new IllegalArgumentException("stream is null");
        }
        String label = (sourceName == null || sourceName.isEmpty()) ? "<stream>" : sourceName;
        return read(new InputStreamReader(in, charsetSnapshot()), label, true);
    }

    /** Overlays a properties file found on the classpath. */
    public SimwProperties loadResource(String resourcePath) throws IOException {
        if (resourcePath == null) {
            throw new IllegalArgumentException("resourcePath is null");
        }
        String name = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        InputStream in = (cl == null) ? null : cl.getResourceAsStream(name);
        if (in == null) {
            in = SimwProperties.class.getClassLoader().getResourceAsStream(name);
        }
        if (in == null) {
            throw new IOException("Classpath resource not found: " + resourcePath);
        }
        try {
            return load(in, "classpath:" + name);
        } finally {
            in.close();
        }
    }

    /** Overlays the contents of a {@link Properties} object. */
    public SimwProperties load(Properties props, String sourceName) {
        if (props == null) {
            throw new IllegalArgumentException("props is null");
        }
        String label = (sourceName == null || sourceName.isEmpty()) ? "<properties>" : sourceName;
        synchronized (lock) {
            files.add(label);
            for (String name : props.stringPropertyNames()) {
                put(name, props.getProperty(name), label, 0);
            }
        }
        return this;
    }

    /** Overlays every entry of {@code other}, preserving {@code other}'s source labels. */
    public SimwProperties merge(SimwProperties other) {
        if (other == null) {
            return this;
        }
        Map<String, Entry> copy;
        List<String> otherFiles;
        synchronized (other.lock) {
            copy = new LinkedHashMap<>(other.entries);
            otherFiles = new ArrayList<>(other.files);
        }
        synchronized (lock) {
            files.addAll(otherFiles);
            for (Map.Entry<String, Entry> e : copy.entrySet()) {
                put(e.getKey(), e.getValue().value, e.getValue().source, e.getValue().line);
            }
        }
        return this;
    }

    // ------------------------------------------------------------------
    // Mutation
    // ------------------------------------------------------------------

    /** Sets (or overrides) a value programmatically. Source is reported as {@value #RUNTIME_SOURCE}. */
    public SimwProperties set(String key, String value) {
        return set(key, value, RUNTIME_SOURCE, 0);
    }

    /**
     * Sets a value and labels where it came from, for configuration sources that are not files —
     * command line arguments, environment, a registry, a management interface.
     *
     * @param source label reported by {@link #getSource(String)}; not added to
     *               {@link #getLoadedFiles()}, which stays a list of loaded files
     */
    public SimwProperties set(String key, String value, String source) {
        return set(key, value, source, 0);
    }

    /**
     * Sets a value with a source label and a position within that source (a line number, an
     * argument index, a record number). {@code 0} means "no meaningful position".
     */
    public SimwProperties set(String key, String value, String source, int sourceLine) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("key is null or empty");
        }
        String label = (source == null || source.isEmpty()) ? RUNTIME_SOURCE : source;
        synchronized (lock) {
            put(key, value == null ? "" : value, label, Math.max(0, sourceLine));
        }
        return this;
    }

    /** Sets the value only if the key is not already set. */
    public SimwProperties setIfAbsent(String key, String value) {
        synchronized (lock) {
            if (!entries.containsKey(key)) {
                set(key, value);
            }
        }
        return this;
    }

    /** Removes a key. @return the previous raw value, or {@code null} */
    public String remove(String key) {
        synchronized (lock) {
            Entry e = entries.remove(key);
            return (e == null) ? null : e.value;
        }
    }

    /** Removes every property, loaded file and warning. */
    public SimwProperties clear() {
        synchronized (lock) {
            entries.clear();
            files.clear();
            warnings.clear();
        }
        return this;
    }

    // ------------------------------------------------------------------
    // Presence and provenance
    // ------------------------------------------------------------------

    /** {@code true} if the key has a value, including an explicitly empty one. */
    public boolean isSet(String key) {
        synchronized (lock) {
            return key != null && entries.containsKey(key);
        }
    }

    /** {@code true} if the key is set and its (expanded) value is not blank. */
    public boolean hasValue(String key) {
        String v = resolve(key);
        return v != null && !v.trim().isEmpty();
    }

    /** Number of properties currently held. */
    public int size() {
        synchronized (lock) {
            return entries.size();
        }
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    /** All keys, in the order they were first seen. */
    public String[] getKeys() {
        synchronized (lock) {
            return entries.keySet().toArray(new String[0]);
        }
    }

    /** All keys, sorted alphabetically. */
    public String[] getKeysSorted() {
        String[] keys = getKeys();
        Arrays.sort(keys);
        return keys;
    }

    /** Keys starting with the given prefix, in insertion order. */
    public String[] getKeysWithPrefix(String prefix) {
        String p = (prefix == null) ? "" : prefix;
        List<String> out = new ArrayList<>();
        synchronized (lock) {
            for (String k : entries.keySet()) {
                if (k.startsWith(p)) {
                    out.add(k);
                }
            }
        }
        return out.toArray(new String[0]);
    }

    /**
     * The file (or other source label) the current value of {@code key} came from.
     *
     * @return the source, or {@code null} if the key is not set
     */
    public String getSource(String key) {
        synchronized (lock) {
            Entry e = entries.get(key);
            return (e == null) ? null : e.source;
        }
    }

    /**
     * The 1-based line number within {@link #getSource(String)} that supplied the current
     * value, or {@code 0} when the value did not come from a parsed file.
     */
    public int getSourceLine(String key) {
        synchronized (lock) {
            Entry e = entries.get(key);
            return (e == null) ? 0 : e.line;
        }
    }

    /**
     * The source that held the value before the current one overrode it, or {@code null}
     * if this key has only ever been set once.
     */
    public String getPreviousSource(String key) {
        synchronized (lock) {
            Entry e = entries.get(key);
            return (e == null) ? null : e.previousSource;
        }
    }

    /** How many times this key has been assigned across all loads. */
    public int getAssignmentCount(String key) {
        synchronized (lock) {
            Entry e = entries.get(key);
            return (e == null) ? 0 : e.assignments;
        }
    }

    /** Every source loaded into this instance, in load order, without duplicates. */
    public String[] getLoadedFiles() {
        synchronized (lock) {
            return files.toArray(new String[0]);
        }
    }

    /** Non-fatal problems recorded while loading or converting values. */
    public String[] getWarnings() {
        synchronized (lock) {
            return warnings.toArray(new String[0]);
        }
    }

    /**
     * Records a diagnostic of your own, so helpers that layer on top of this class — an argument
     * parser, an environment reader — surface their complaints in the same place as the ones this
     * class records. Duplicates are ignored.
     */
    public SimwProperties addWarning(String message) {
        if (message != null && !message.isEmpty()) {
            warn(message);
        }
        return this;
    }

    public SimwProperties clearWarnings() {
        synchronized (lock) {
            warnings.clear();
        }
        return this;
    }

    // ------------------------------------------------------------------
    // Typed access
    // ------------------------------------------------------------------

    /** @return the expanded value, or {@code null} if the key is not set */
    public String getString(String key) {
        return resolve(key);
    }

    /** @return the expanded value, or {@code def} if the key is not set */
    public String getString(String key, String def) {
        String v = resolve(key);
        return (v == null) ? def : v;
    }

    /** @throws SimwPropertyException if the key is not set */
    public String requireString(String key) {
        String v = resolve(key);
        if (v == null) {
            throw missing(key);
        }
        return v;
    }

    public boolean getBoolean(String key) {
        return required(key, "boolean", parseBoolean(requireString(key)));
    }

    public boolean getBoolean(String key, boolean def) {
        String v = resolve(key);
        if (v == null) {
            return def;
        }
        Boolean b = parseBoolean(v);
        return (b == null) ? fallback(key, v, "boolean", def) : b;
    }

    public byte getByte(String key) {
        long v = getLongRequired(key, "byte");
        return (byte) rangeChecked(key, v, Byte.MIN_VALUE, Byte.MAX_VALUE, "byte");
    }

    public byte getByte(String key, byte def) {
        return (byte) getBounded(key, def, Byte.MIN_VALUE, Byte.MAX_VALUE, "byte");
    }

    public short getShort(String key) {
        long v = getLongRequired(key, "short");
        return (short) rangeChecked(key, v, Short.MIN_VALUE, Short.MAX_VALUE, "short");
    }

    public short getShort(String key, short def) {
        return (short) getBounded(key, def, Short.MIN_VALUE, Short.MAX_VALUE, "short");
    }

    public int getInt(String key) {
        long v = getLongRequired(key, "int");
        return (int) rangeChecked(key, v, Integer.MIN_VALUE, Integer.MAX_VALUE, "int");
    }

    public int getInt(String key, int def) {
        return (int) getBounded(key, def, Integer.MIN_VALUE, Integer.MAX_VALUE, "int");
    }

    public long getLong(String key) {
        return getLongRequired(key, "long");
    }

    public long getLong(String key, long def) {
        String v = resolve(key);
        if (v == null) {
            return def;
        }
        Long l = parseLong(v);
        return (l == null) ? fallback(key, v, "long", def) : l;
    }

    public float getFloat(String key) {
        return (float) getDouble(key);
    }

    public float getFloat(String key, float def) {
        String v = resolve(key);
        if (v == null) {
            return def;
        }
        Double d = parseDouble(v);
        return (d == null) ? fallback(key, v, "float", def) : d.floatValue();
    }

    public double getDouble(String key) {
        String v = requireString(key);
        Double d = parseDouble(v);
        if (d == null) {
            throw invalid(key, v, "double");
        }
        return d;
    }

    public double getDouble(String key, double def) {
        String v = resolve(key);
        if (v == null) {
            return def;
        }
        Double d = parseDouble(v);
        return (d == null) ? fallback(key, v, "double", def) : d;
    }

    public char getChar(String key) {
        String v = requireString(key);
        Character c = parseChar(v);
        if (c == null) {
            throw invalid(key, v, "char");
        }
        return c;
    }

    public char getChar(String key, char def) {
        String v = resolve(key);
        if (v == null) {
            return def;
        }
        Character c = parseChar(v);
        return (c == null) ? fallback(key, v, "char", def) : c;
    }

    /**
     * Reads an enum constant. Matching is case-insensitive and treats {@code '-'},
     * {@code ' '} and {@code '.'} as {@code '_'}, so {@code best-effort} finds
     * {@code BEST_EFFORT}.
     */
    public <T extends Enum<T>> T getEnum(Class<T> type, String key, T def) {
        if (type == null) {
            throw new IllegalArgumentException("type is null");
        }
        String v = resolve(key);
        if (v == null) {
            return def;
        }
        T found = matchEnum(type, v);
        return (found == null) ? fallback(key, v, type.getSimpleName(), def) : found;
    }

    /** Required enum variant. @throws SimwPropertyException if missing or unmatched */
    public <T extends Enum<T>> T getEnum(Class<T> type, String key) {
        String v = requireString(key);
        T found = matchEnum(type, v);
        if (found == null) {
            throw invalid(key, v, type.getSimpleName() + " (expected one of "
                    + Arrays.toString(type.getEnumConstants()) + ")");
        }
        return found;
    }

    /**
     * Reads a duration in milliseconds. Accepts a bare number (milliseconds) or a value with
     * a unit suffix: {@code ms}, {@code s}, {@code m}, {@code h}, {@code d} — for example
     * {@code 30s}, {@code 250ms}, {@code 2h}.
     */
    public long getMillis(String key, long def) {
        String v = resolve(key);
        if (v == null) {
            return def;
        }
        Long l = parseMillis(v);
        return (l == null) ? fallback(key, v, "duration", def) : l;
    }

    /**
     * Reads a byte size. Accepts a bare number (bytes) or a value with a binary unit suffix:
     * {@code k}/{@code kb}, {@code m}/{@code mb}, {@code g}/{@code gb}, {@code t}/{@code tb} —
     * for example {@code 64k}, {@code 8M}.
     */
    public long getByteSize(String key, long def) {
        String v = resolve(key);
        if (v == null) {
            return def;
        }
        Long l = parseByteSize(v);
        return (l == null) ? fallback(key, v, "byte size", def) : l;
    }

    // ------------------------------------------------------------------
    // Array access
    // ------------------------------------------------------------------

    /**
     * Splits the value on the array separator ({@code ;} by default).
     * {@code hosts=alpha;beta;gamma} yields {@code ["alpha","beta","gamma"]}.
     * Elements are trimmed and empty elements dropped, so trailing separators are harmless.
     *
     * @return the elements, or an empty array if the key is not set
     */
    public String[] getStringArray(String key) {
        return getStringArray(key, new String[0]);
    }

    /** @return the elements, or {@code def} if the key is not set */
    public String[] getStringArray(String key, String[] def) {
        String v = resolve(key);
        if (v == null) {
            return def;
        }
        char sep = getArraySeparator();
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= v.length(); i++) {
            if (i == v.length() || v.charAt(i) == sep) {
                String part = v.substring(start, i).trim();
                if (!part.isEmpty()) {
                    out.add(part);
                }
                start = i + 1;
            }
        }
        return out.toArray(new String[0]);
    }

    /** {@link #getStringArray(String)} with each element converted to {@code int}. */
    public int[] getIntArray(String key, int[] def) {
        String[] parts = getStringArray(key, null);
        if (parts == null) {
            return def;
        }
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            Long l = parseLong(parts[i]);
            if (l == null || l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
                return fallback(key, parts[i], "int[] element " + i, def);
            }
            out[i] = l.intValue();
        }
        return out;
    }

    public int[] getIntArray(String key) {
        return getIntArray(key, new int[0]);
    }

    /** {@link #getStringArray(String)} with each element converted to {@code long}. */
    public long[] getLongArray(String key, long[] def) {
        String[] parts = getStringArray(key, null);
        if (parts == null) {
            return def;
        }
        long[] out = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            Long l = parseLong(parts[i]);
            if (l == null) {
                return fallback(key, parts[i], "long[] element " + i, def);
            }
            out[i] = l;
        }
        return out;
    }

    public long[] getLongArray(String key) {
        return getLongArray(key, new long[0]);
    }

    /** {@link #getStringArray(String)} with each element converted to {@code double}. */
    public double[] getDoubleArray(String key, double[] def) {
        String[] parts = getStringArray(key, null);
        if (parts == null) {
            return def;
        }
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            Double d = parseDouble(parts[i]);
            if (d == null) {
                return fallback(key, parts[i], "double[] element " + i, def);
            }
            out[i] = d;
        }
        return out;
    }

    public double[] getDoubleArray(String key) {
        return getDoubleArray(key, new double[0]);
    }

    /** {@link #getStringArray(String)} with each element converted to {@code boolean}. */
    public boolean[] getBooleanArray(String key, boolean[] def) {
        String[] parts = getStringArray(key, null);
        if (parts == null) {
            return def;
        }
        boolean[] out = new boolean[parts.length];
        for (int i = 0; i < parts.length; i++) {
            Boolean b = parseBoolean(parts[i]);
            if (b == null) {
                return fallback(key, parts[i], "boolean[] element " + i, def);
            }
            out[i] = b;
        }
        return out;
    }

    public boolean[] getBooleanArray(String key) {
        return getBooleanArray(key, new boolean[0]);
    }

    // ------------------------------------------------------------------
    // Views
    // ------------------------------------------------------------------

    /** A snapshot of key to expanded value. Insertion ordered, detached from this instance. */
    public Map<String, String> toMap() {
        Map<String, String> out = new LinkedHashMap<>();
        synchronized (lock) {
            for (String key : entries.keySet()) {
                out.put(key, resolve(key));
            }
        }
        return out;
    }

    /** A {@link Properties} snapshot holding the expanded values. */
    public Properties toProperties() {
        Properties p = new Properties();
        for (Map.Entry<String, String> e : toMap().entrySet()) {
            if (e.getValue() != null) {
                p.setProperty(e.getKey(), e.getValue());
            }
        }
        return p;
    }

    /** A detached copy of this instance, including provenance. */
    public SimwProperties copy() {
        SimwProperties c = new SimwProperties();
        synchronized (lock) {
            c.arraySeparator = arraySeparator;
            c.strict = strict;
            c.expandVariables = expandVariables;
            c.charset = charset;
            c.secretMarkers.clear();
            c.secretMarkers.addAll(secretMarkers);
            c.files.addAll(files);
            c.entries.putAll(entries);
        }
        return c;
    }

    // ------------------------------------------------------------------
    // Dumping
    // ------------------------------------------------------------------

    /** Multi-line, human-readable rendering of every property with its origin. Secrets masked. */
    public String dump() {
        return dump(true);
    }

    /**
     * Multi-line, human-readable rendering of every property with its origin.
     *
     * @param maskSecrets replace values of credential-looking keys with {@code ********}
     */
    public String dump(boolean maskSecrets) {
        StringBuilder sb = new StringBuilder(512);
        synchronized (lock) {
            List<String> fileList = new ArrayList<>(files);
            sb.append("# SimwProperties: ").append(entries.size()).append(" propert")
              .append(entries.size() == 1 ? "y" : "ies")
              .append(" from ").append(fileList.size()).append(" source")
              .append(fileList.size() == 1 ? "" : "s").append(System.lineSeparator());
            for (int i = 0; i < fileList.size(); i++) {
                sb.append("#   [").append(i + 1).append("] ").append(fileList.get(i))
                  .append(System.lineSeparator());
            }
            if (entries.isEmpty()) {
                sb.append("#   (no properties)").append(System.lineSeparator());
                return sb.toString();
            }

            List<String[]> rows = new ArrayList<>(entries.size());
            int keyWidth = 0;
            int valueWidth = 0;
            for (Map.Entry<String, Entry> me : entries.entrySet()) {
                String key = me.getKey();
                Entry e = me.getValue();
                String shown;
                if (maskSecrets && isSecret(key)) {
                    shown = "********";
                } else {
                    String expanded = expandVariables ? expand(e.value, new LinkedHashSet<>(), 0) : e.value;
                    shown = escapeForDump(expanded);
                    if (!expanded.equals(e.value)) {
                        shown = shown + "   <- " + escapeForDump(e.value);
                    }
                }

                StringBuilder origin = new StringBuilder();
                origin.append(label(fileList, e.source));
                if (e.line > 0) {
                    origin.append(':').append(e.line);
                }
                if (e.assignments > 1 && e.previousSource != null) {
                    origin.append(" (overrides ").append(label(fileList, e.previousSource)).append(')');
                }

                rows.add(new String[] {key, shown, origin.toString()});
                keyWidth = Math.max(keyWidth, key.length());
                valueWidth = Math.max(valueWidth, shown.length());
            }
            keyWidth = Math.min(keyWidth, 48);
            valueWidth = Math.min(valueWidth, 60);

            for (String[] row : rows) {
                sb.append(pad(row[0], keyWidth)).append(" = ").append(pad(row[1], valueWidth))
                  .append("  # ").append(row[2]).append(System.lineSeparator());
            }

            if (!warnings.isEmpty()) {
                sb.append("# ").append(warnings.size()).append(" warning")
                  .append(warnings.size() == 1 ? "" : "s").append(':').append(System.lineSeparator());
                for (String w : warnings) {
                    sb.append("#   ").append(w).append(System.lineSeparator());
                }
            }
        }
        return sb.toString();
    }

    /** Writes {@link #dump()} to {@link System#out}. */
    public void dumpToStdout() {
        dumpTo(System.out);
    }

    /** Writes {@link #dump()} to the given stream. */
    public void dumpTo(PrintStream out) {
        if (out == null) {
            throw new IllegalArgumentException("stream is null");
        }
        out.print(dump());
        out.flush();
    }

    @Override
    public String toString() {
        synchronized (lock) {
            return "SimwProperties[" + entries.size() + " properties from " + files.size() + " source(s)]";
        }
    }

    // ------------------------------------------------------------------
    // Internals: reading and parsing
    // ------------------------------------------------------------------

    private Charset charsetSnapshot() {
        synchronized (lock) {
            return charset;
        }
    }

    private static String describe(Path file) {
        try {
            return file.toAbsolutePath().normalize().toString();
        } catch (RuntimeException e) {
            return file.toString();
        }
    }

    private SimwProperties read(Reader reader, String source, boolean recordSource) throws IOException {
        BufferedReader br = (reader instanceof BufferedReader)
                ? (BufferedReader) reader
                : new BufferedReader(reader);

        synchronized (lock) {
            if (recordSource) {
                files.add(source);
            }
            StringBuilder logical = new StringBuilder();
            boolean continuing = false;
            int lineNo = 0;
            int startLine = 0;
            String line;
            while ((line = br.readLine()) != null) {
                lineNo++;
                String s = stripLeadingWhitespace(line);
                if (!continuing) {
                    if (s.isEmpty() || s.charAt(0) == '#' || s.charAt(0) == '!') {
                        continue;
                    }
                    startLine = lineNo;
                }
                if (endsWithOddBackslash(s)) {
                    logical.append(s, 0, s.length() - 1);
                    continuing = true;
                    continue;
                }
                logical.append(s);
                continuing = false;
                acceptLogicalLine(logical.toString(), source, startLine);
                logical.setLength(0);
            }
            if (logical.length() > 0) {
                acceptLogicalLine(logical.toString(), source, startLine);
            }
        }
        return this;
    }

    /** Caller holds {@link #lock}. */
    private void acceptLogicalLine(String logical, String source, int lineNo) {
        int len = logical.length();
        int i = 0;
        while (i < len) {
            char c = logical.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == ' ' || c == '\t' || c == '\f' || c == '=' || c == ':') {
                break;
            }
            i++;
        }
        int keyEnd = Math.min(i, len);
        String rawKey = logical.substring(0, keyEnd);

        int j = keyEnd;
        while (j < len && isSpace(logical.charAt(j))) {
            j++;
        }
        if (j < len && (logical.charAt(j) == '=' || logical.charAt(j) == ':')) {
            j++;
            while (j < len && isSpace(logical.charAt(j))) {
                j++;
            }
        }
        String rawValue = logical.substring(j);

        String key = unescape(rawKey, false);
        if (key.isEmpty()) {
            warn("Ignored line without a key at " + source + ":" + lineNo);
            return;
        }
        put(key, unescape(rawValue, true), source, lineNo);
    }

    /** Caller holds {@link #lock}. */
    private void put(String key, String value, String source, int line) {
        entries.put(key, new Entry(value, source, line, entries.get(key)));
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\f';
    }

    private static String stripLeadingWhitespace(String s) {
        int i = 0;
        while (i < s.length() && isSpace(s.charAt(i))) {
            i++;
        }
        return (i == 0) ? s : s.substring(i);
    }

    private static boolean endsWithOddBackslash(String s) {
        int count = 0;
        for (int i = s.length() - 1; i >= 0 && s.charAt(i) == '\\'; i--) {
            count++;
        }
        return (count & 1) == 1;
    }

    /**
     * Applies {@code .properties} escape rules. When {@code trimTrailing} is set, trailing
     * whitespace that was not itself escaped is removed.
     */
    private static String unescape(String s, boolean trimTrailing) {
        StringBuilder sb = new StringBuilder(s.length());
        int keep = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean escaped = false;
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                escaped = true;
                switch (n) {
                    case 't': c = '\t'; break;
                    case 'n': c = '\n'; break;
                    case 'r': c = '\r'; break;
                    case 'f': c = '\f'; break;
                    case 'u':
                        if (i + 5 <= s.length()) {
                            try {
                                c = (char) Integer.parseInt(s.substring(i + 1, i + 5), 16);
                                i += 4;
                            } catch (NumberFormatException ignored) {
                                c = 'u';
                            }
                        } else {
                            c = 'u';
                        }
                        break;
                    default:
                        c = n;
                        break;
                }
            }
            sb.append(c);
            if (!trimTrailing || escaped || !isWhitespace(c)) {
                keep = sb.length();
            }
        }
        if (trimTrailing) {
            sb.setLength(keep);
        }
        return sb.toString();
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\f' || c == '\n' || c == '\r';
    }

    // ------------------------------------------------------------------
    // Internals: value resolution
    // ------------------------------------------------------------------

    private String resolve(String key) {
        if (key == null) {
            return null;
        }
        synchronized (lock) {
            Entry e = entries.get(key);
            if (e == null) {
                return null;
            }
            if (!expandVariables) {
                return e.value;
            }
            Set<String> visiting = new LinkedHashSet<>();
            visiting.add(key);
            return expand(e.value, visiting, 0);
        }
    }

    /** Caller holds {@link #lock}. */
    private String expand(String value, Set<String> visiting, int depth) {
        if (value == null || value.indexOf('$') < 0) {
            return value;
        }
        if (depth > MAX_EXPANSION_DEPTH) {
            warn("Variable expansion too deep, stopped at: " + value);
            return value;
        }
        int len = value.length();
        StringBuilder out = new StringBuilder(len + 16);
        int i = 0;
        while (i < len) {
            char c = value.charAt(i);
            if (c == '$' && i + 2 < len && value.charAt(i + 1) == '$' && value.charAt(i + 2) == '{') {
                out.append("${");
                i += 3;
                continue;
            }
            if (c == '$' && i + 1 < len && value.charAt(i + 1) == '{') {
                int nesting = 1;
                int j = i + 2;
                while (j < len) {
                    char d = value.charAt(j);
                    if (d == '{') {
                        nesting++;
                    } else if (d == '}') {
                        nesting--;
                        if (nesting == 0) {
                            break;
                        }
                    }
                    j++;
                }
                if (j >= len) {
                    out.append(value, i, len);
                    break;
                }
                out.append(resolveReference(value.substring(i + 2, j), visiting, depth));
                i = j + 1;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** Caller holds {@link #lock}. */
    private String resolveReference(String expression, Set<String> visiting, int depth) {
        String name = expression;
        String inlineDefault = null;
        int d = expression.indexOf(":-");
        if (d >= 0) {
            name = expression.substring(0, d);
            inlineDefault = expression.substring(d + 2);
        }
        name = name.trim();

        String value;
        if (name.startsWith("env:")) {
            value = System.getenv(name.substring(4));
        } else if (name.startsWith("sys:")) {
            value = System.getProperty(name.substring(4));
        } else {
            Entry e = entries.get(name);
            if (e != null) {
                if (!visiting.add(name)) {
                    warn("Circular property reference involving '" + name + "'");
                    return "${" + expression + "}";
                }
                try {
                    value = expand(e.value, visiting, depth + 1);
                } finally {
                    visiting.remove(name);
                }
            } else {
                value = System.getProperty(name);
                if (value == null) {
                    value = System.getenv(name);
                }
            }
        }
        if (value == null) {
            if (inlineDefault != null) {
                return expand(inlineDefault, visiting, depth + 1);
            }
            warn("Unresolved reference '${" + expression + "}'");
            return "${" + expression + "}";
        }
        return value;
    }

    // ------------------------------------------------------------------
    // Internals: conversions
    // ------------------------------------------------------------------

    static Boolean parseBoolean(String raw) {
        String v = raw.trim().toLowerCase(Locale.ROOT);
        switch (v) {
            case "true": case "t": case "yes": case "y": case "on":
            case "1": case "enabled": case "enable":
                return Boolean.TRUE;
            case "false": case "f": case "no": case "n": case "off":
            case "0": case "disabled": case "disable":
                return Boolean.FALSE;
            default:
                return null;
        }
    }

    /**
     * Accepts an optional sign, {@code _} digit separators, a trailing {@code L},
     * and {@code 0x}/{@code #} hex, {@code 0b} binary and {@code 0o} octal prefixes.
     * A leading zero alone does <em>not</em> mean octal — {@code 0755} is seven hundred
     * fifty five, because configuration files are read by people.
     */
    static Long parseLong(String raw) {
        String v = raw.trim().replace("_", "");
        if (v.isEmpty()) {
            return null;
        }
        boolean negative = false;
        if (v.charAt(0) == '+' || v.charAt(0) == '-') {
            negative = v.charAt(0) == '-';
            v = v.substring(1);
        }
        int radix = 10;
        if (v.length() > 2 && (v.startsWith("0x") || v.startsWith("0X"))) {
            radix = 16;
            v = v.substring(2);
        } else if (v.length() > 1 && v.charAt(0) == '#') {
            radix = 16;
            v = v.substring(1);
        } else if (v.length() > 2 && (v.startsWith("0b") || v.startsWith("0B"))) {
            radix = 2;
            v = v.substring(2);
        } else if (v.length() > 2 && (v.startsWith("0o") || v.startsWith("0O"))) {
            radix = 8;
            v = v.substring(2);
        }
        if (v.length() > 1 && (v.endsWith("L") || v.endsWith("l")) && radix != 16) {
            v = v.substring(0, v.length() - 1);
        }
        if (v.isEmpty()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(v, radix);
            return negative ? -parsed : parsed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Accepts everything {@link Double#parseDouble} does, plus {@code _} separators, a
     * trailing {@code f}/{@code d}, and a decimal comma ({@code 3,14}) as long as the value
     * contains no dot — convenient in locales that write numbers that way.
     */
    static Double parseDouble(String raw) {
        String v = raw.trim().replace("_", "");
        if (v.isEmpty()) {
            return null;
        }
        if (v.indexOf('.') < 0 && v.indexOf(',') >= 0 && v.indexOf(',') == v.lastIndexOf(',')) {
            v = v.replace(',', '.');
        }
        String lower = v.toLowerCase(Locale.ROOT);
        String bare = lower.startsWith("+") || lower.startsWith("-") ? lower.substring(1) : lower;
        boolean neg = lower.startsWith("-");
        if (bare.equals("inf") || bare.equals("infinity")) {
            return neg ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        if (bare.equals("nan")) {
            return Double.NaN;
        }
        // Reject the hex-float and suffix forms Double.parseDouble would silently accept
        // only to surprise the reader; keep plain decimal and scientific notation.
        if (bare.indexOf('x') >= 0) {
            return null;
        }
        if (v.length() > 1) {
            char last = v.charAt(v.length() - 1);
            if (last == 'f' || last == 'F' || last == 'd' || last == 'D') {
                v = v.substring(0, v.length() - 1);
            }
        }
        try {
            return Double.valueOf(Double.parseDouble(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Character parseChar(String raw) {
        if (raw.length() == 1) {
            return raw.charAt(0);
        }
        String v = raw.trim();
        return (v.length() == 1) ? Character.valueOf(v.charAt(0)) : null;
    }

    static Long parseMillis(String raw) {
        String v = raw.trim().toLowerCase(Locale.ROOT).replace("_", "");
        if (v.isEmpty()) {
            return null;
        }
        long factor = 1L;
        if (v.endsWith("ms")) {
            v = v.substring(0, v.length() - 2);
        } else if (v.endsWith("millis")) {
            v = v.substring(0, v.length() - 6);
        } else if (v.endsWith("s")) {
            factor = 1000L;
            v = v.substring(0, v.length() - 1);
        } else if (v.endsWith("m")) {
            factor = 60_000L;
            v = v.substring(0, v.length() - 1);
        } else if (v.endsWith("h")) {
            factor = 3_600_000L;
            v = v.substring(0, v.length() - 1);
        } else if (v.endsWith("d")) {
            factor = 86_400_000L;
            v = v.substring(0, v.length() - 1);
        }
        Double amount = parseDouble(v);
        if (amount == null || amount.isNaN() || amount.isInfinite()) {
            return null;
        }
        return Math.round(amount * factor);
    }

    static Long parseByteSize(String raw) {
        String v = raw.trim().toLowerCase(Locale.ROOT).replace("_", "");
        if (v.isEmpty()) {
            return null;
        }
        if (v.endsWith("b") && v.length() > 1 && !Character.isDigit(v.charAt(v.length() - 2))) {
            v = v.substring(0, v.length() - 1); // "kb" -> "k"
        }
        long factor = 1L;
        if (v.endsWith("k")) {
            factor = 1024L;
        } else if (v.endsWith("m")) {
            factor = 1024L * 1024L;
        } else if (v.endsWith("g")) {
            factor = 1024L * 1024L * 1024L;
        } else if (v.endsWith("t")) {
            factor = 1024L * 1024L * 1024L * 1024L;
        } else if (v.endsWith("b")) {
            v = v.substring(0, v.length() - 1);
        }
        if (factor != 1L) {
            v = v.substring(0, v.length() - 1);
        }
        Double amount = parseDouble(v);
        if (amount == null || amount.isNaN() || amount.isInfinite()) {
            return null;
        }
        return Math.round(amount * factor);
    }

    private static <T extends Enum<T>> T matchEnum(Class<T> type, String raw) {
        String wanted = normaliseEnum(raw);
        for (T constant : type.getEnumConstants()) {
            if (normaliseEnum(constant.name()).equals(wanted)) {
                return constant;
            }
        }
        return null;
    }

    private static String normaliseEnum(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '-' || c == ' ' || c == '.') {
                c = '_';
            }
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Internals: error handling
    // ------------------------------------------------------------------

    private long getLongRequired(String key, String type) {
        String v = requireString(key);
        Long l = parseLong(v);
        if (l == null) {
            throw invalid(key, v, type);
        }
        return l;
    }

    private long getBounded(String key, long def, long min, long max, String type) {
        String v = resolve(key);
        if (v == null) {
            return def;
        }
        Long l = parseLong(v);
        if (l == null) {
            return fallback(key, v, type, def);
        }
        if (l < min || l > max) {
            return fallback(key, v, type + " (out of range " + min + ".." + max + ")", def);
        }
        return l;
    }

    private long rangeChecked(String key, long value, long min, long max, String type) {
        if (value < min || value > max) {
            throw invalid(key, String.valueOf(value), type + " (out of range " + min + ".." + max + ")");
        }
        return value;
    }

    private <T> T required(String key, String type, T value) {
        if (value == null) {
            throw invalid(key, getString(key), type);
        }
        return value;
    }

    /** Records the problem and returns {@code def} — or throws, in strict mode. */
    private <T> T fallback(String key, String value, String type, T def) {
        String message = "Property '" + key + "' = '" + value + "' is not a valid " + type
                + at(key) + "; using default '" + describeDefault(def) + "'";
        if (isStrict()) {
            throw new SimwPropertyException(key, message);
        }
        warn(message);
        return def;
    }

    private static String describeDefault(Object def) {
        if (def == null) {
            return "null";
        }
        if (def instanceof int[]) {
            return Arrays.toString((int[]) def);
        }
        if (def instanceof long[]) {
            return Arrays.toString((long[]) def);
        }
        if (def instanceof double[]) {
            return Arrays.toString((double[]) def);
        }
        if (def instanceof boolean[]) {
            return Arrays.toString((boolean[]) def);
        }
        if (def instanceof Object[]) {
            return Arrays.toString((Object[]) def);
        }
        return String.valueOf(def);
    }

    private SimwPropertyException invalid(String key, String value, String type) {
        return new SimwPropertyException(key,
                "Property '" + key + "' = '" + value + "' is not a valid " + type + at(key));
    }

    private SimwPropertyException missing(String key) {
        String loaded;
        synchronized (lock) {
            loaded = files.isEmpty() ? "" : " (loaded: " + String.join(", ", files) + ")";
        }
        return new SimwPropertyException(key, "Required property '" + key + "' is not set" + loaded);
    }

    private String at(String key) {
        String source = getSource(key);
        if (source == null) {
            return "";
        }
        int line = getSourceLine(key);
        return " (" + source + (line > 0 ? ":" + line : "") + ")";
    }

    private void warn(String message) {
        synchronized (lock) {
            if (warnings.size() < 500 && !warnings.contains(message)) {
                warnings.add(message);
            }
        }
    }

    // ------------------------------------------------------------------
    // Internals: dump helpers
    // ------------------------------------------------------------------

    private boolean isSecret(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        for (String marker : secretMarkers) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** Renders a source as its bracketed index in the file list, or verbatim if unknown. */
    private static String label(List<String> fileList, String source) {
        int idx = fileList.indexOf(source);
        return (idx >= 0) ? ("[" + (idx + 1) + "]") : source;
    }

    private static String pad(String s, int width) {
        if (s.length() >= width) {
            return s;
        }
        StringBuilder sb = new StringBuilder(width);
        sb.append(s);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static String escapeForDump(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c); break;
            }
        }
        return sb.toString();
    }

    /** Unmodifiable view used by tests. */
    static List<String> secretMarkersOf(SimwProperties p) {
        synchronized (p.lock) {
            return Collections.unmodifiableList(new ArrayList<>(p.secretMarkers));
        }
    }
}
