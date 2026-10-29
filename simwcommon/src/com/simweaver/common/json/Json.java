package com.simweaver.common.json;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Small, dependency-free JSON builder and serializer (Java 11).
 *
 * <pre>{@code
 * String json = Json.object()
 *         .put("name", "Alpha-1")
 *         .put("speed", 12.5)
 *         .put("active", true)
 *         .put("tags", List.of("air", "blue"))
 *         .putObject("pos", p -> p.put("lat", 59.33).put("lon", 18.07))
 *         .put("platform", myPlatform)          // implements Json.Writable
 *         .toString();
 * }</pre>
 *
 * Supported values: {@code null}, Boolean, Number, CharSequence, Character, Enum (name),
 * Optional, Map (keys via String.valueOf), Iterable, arrays (incl. primitive arrays; char[]
 * becomes a string), {@link JsonObject}, {@link JsonArray}, {@link Raw}, {@link Writable},
 * any type with a registered adapter, and your own classes opted in to field reflection via
 * {@link Reflect @Json.Reflect} or {@link #registerReflective}. Anything else is written as its
 * quoted toString().
 *
 * <pre>{@code
 * @Json.Reflect
 * public class EntityState {
 *     String id;
 *     double[] position;
 *     List<Track> tracks;              // Track is also @Json.Reflect, or Writable
 *     @Json.Name("type") EntityKind kind;
 *     @Json.Ignore RTIambassador rti;  // never serialized
 * }
 * String json = Json.toJson(state);
 * }</pre>
 *
 * Reflection only ever applies to opted-in classes, never to JDK classes. Static, transient,
 * synthetic and {@link Ignore @Json.Ignore} fields are skipped. Superclass fields come first,
 * then each class's fields in declaration order (as reported by the JVM).
 *
 * <p>NaN and infinite floating point values have no JSON representation and are written as
 * {@code null}. Values are serialized when toString() is called, not when they are added.
 * Builders are not thread-safe.
 */
public final class Json {

    private Json() {
    }

    // ------------------------------------------------------------------ extension points

    /** Implemented by your own types that know how to represent themselves as JSON. */
    @FunctionalInterface
    public interface Writable {
        /**
         * @return any value this class can serialize, typically a {@link JsonObject},
         *         but a String, Number, List etc. is also fine
         */
        Object toJson();
    }

    private static final Map<Class<?>, Function<Object, ?>> ADAPTERS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Optional<Function<Object, ?>>> RESOLVED = new ConcurrentHashMap<>();

    /**
     * Registers a conversion for a type you do not own (or want to override, e.g. byte[]).
     * The adapter returns any serializable value. The most specific registered type wins.
     * Intended to be called at startup.
     */
    public static <T> void registerAdapter(Class<T> type, Function<? super T, ?> adapter) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(adapter, "adapter");
        ADAPTERS.put(type, o -> adapter.apply(type.cast(o)));
        RESOLVED.clear();
    }

    private static Function<Object, ?> adapterFor(Class<?> c) {
        if (ADAPTERS.isEmpty()) {
            return null;
        }
        return RESOLVED.computeIfAbsent(c, k -> {
            Function<Object, ?> exact = ADAPTERS.get(k);
            if (exact != null) {
                return Optional.of(exact);
            }
            Class<?> best = null;
            for (Class<?> t : ADAPTERS.keySet()) {
                if (t.isAssignableFrom(k) && (best == null || best.isAssignableFrom(t))) {
                    best = t;
                }
            }
            return best == null ? Optional.empty() : Optional.of(ADAPTERS.get(best));
        }).orElse(null);
    }

    // ------------------------------------------------------------------ field reflection

    /** Serialize instances of this class (and its subclasses) from their fields. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @Inherited
    public @interface Reflect {
        /** Leave out fields whose value is null instead of writing {@code "x":null}. */
        boolean skipNulls() default false;
    }

    /** Exclude this field from reflective serialization. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface Ignore {
    }

    /** Use this JSON member name instead of the field name. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface Name {
        String value();
    }

    private static final Set<Class<?>> REFLECTIVE = ConcurrentHashMap.newKeySet();
    private static final Map<Class<?>, Optional<ReflectInfo>> REFLECT_CACHE = new ConcurrentHashMap<>();

    /**
     * Opts in classes you cannot annotate (e.g. generated code), including their subclasses.
     * Intended to be called at startup.
     */
    public static void registerReflective(Class<?>... types) {
        for (Class<?> t : types) {
            if (isJdk(t)) {
                throw new IllegalArgumentException("JDK classes cannot be reflected: " + t.getName());
            }
            REFLECTIVE.add(t);
        }
        REFLECT_CACHE.clear();
    }

    private static final class Prop {
        final String name;
        final Field field;

        Prop(String name, Field field) {
            this.name = name;
            this.field = field;
        }
    }

    private static final class ReflectInfo {
        final List<Prop> props;
        final boolean skipNulls;

        ReflectInfo(List<Prop> props, boolean skipNulls) {
            this.props = props;
            this.skipNulls = skipNulls;
        }
    }

    private static ReflectInfo reflectInfo(Class<?> c) {
        return REFLECT_CACHE.computeIfAbsent(c, k -> Optional.ofNullable(buildReflectInfo(k))).orElse(null);
    }

    private static ReflectInfo buildReflectInfo(Class<?> c) {
        if (c.isArray() || Enum.class.isAssignableFrom(c) || isJdk(c)) {
            return null;
        }
        Reflect annotation = c.getAnnotation(Reflect.class);
        if (annotation == null && REFLECTIVE.stream().noneMatch(t -> t.isAssignableFrom(c))) {
            return null;
        }
        Deque<Class<?>> chain = new ArrayDeque<>(); // superclass first
        for (Class<?> k = c; k != null && !isJdk(k); k = k.getSuperclass()) {
            chain.push(k);
        }
        Map<String, Prop> props = new LinkedHashMap<>();
        for (Class<?> k : chain) {
            for (Field f : k.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isTransient(mod) || f.isSynthetic()
                        || f.isAnnotationPresent(Ignore.class)) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                } catch (RuntimeException e) {
                    throw new IllegalStateException("Cannot access " + k.getName() + "." + f.getName()
                            + " (if you use modules, open the package to this class's module)", e);
                }
                Name n = f.getAnnotation(Name.class);
                String name = n != null ? n.value() : f.getName();
                props.put(name, new Prop(name, f)); // a subclass field shadows a superclass one
            }
        }
        return new ReflectInfo(List.copyOf(props.values()), annotation != null && annotation.skipNulls());
    }

    private static boolean isJdk(Class<?> c) {
        String n = c.getName();
        return c.isPrimitive() || n.startsWith("java.") || n.startsWith("javax.")
                || n.startsWith("jdk.") || n.startsWith("sun.") || n.startsWith("com.sun.");
    }

    // ------------------------------------------------------------------ factories / helpers

    public static JsonObject object() {
        return new JsonObject();
    }

    public static JsonArray array(Object... values) {
        JsonArray a = new JsonArray();
        for (Object v : values) {
            a.add(v);
        }
        return a;
    }

    /** Wraps an already serialized, trusted JSON fragment. It is inserted verbatim, unvalidated. */
    public static Raw raw(String json) {
        return new Raw(json);
    }

    /** Serializes any supported value to compact JSON. */
    public static String toJson(Object value) {
        return new Writer(null).write(value).toString();
    }

    /** Serializes any supported value to indented JSON. */
    public static String toPrettyJson(Object value) {
        return new Writer("  ").write(value).toString();
    }

    /** Returns s as a quoted and escaped JSON string literal, or {@code null} for null. */
    public static String quote(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        appendQuoted(sb, s);
        return sb.toString();
    }

    // ------------------------------------------------------------------ builders

    /** Ordered JSON object builder. Putting an existing key replaces its value. */
    public static final class JsonObject {
        private final Map<String, Object> members = new LinkedHashMap<>();

        private JsonObject() {
        }

        public JsonObject put(String key, Object value) {
            members.put(Objects.requireNonNull(key, "key"), value);
            return this;
        }

        public JsonObject putIfNotNull(String key, Object value) {
            return value == null ? this : put(key, value);
        }

        public JsonObject putObject(String key, Consumer<JsonObject> init) {
            JsonObject o = new JsonObject();
            init.accept(o);
            return put(key, o);
        }

        public JsonObject putArray(String key, Consumer<JsonArray> init) {
            JsonArray a = new JsonArray();
            init.accept(a);
            return put(key, a);
        }

        /** Puts an already serialized, trusted JSON fragment. */
        public JsonObject putRaw(String key, String json) {
            return put(key, raw(json));
        }

        public JsonObject remove(String key) {
            members.remove(key);
            return this;
        }

        /** @return the value put for {@code key}, or null (0.10.0) */
        public Object get(String key) {
            return members.get(key);
        }

        public boolean has(String key) {
            return members.containsKey(key);
        }

        public int size() {
            return members.size();
        }

        public boolean isEmpty() {
            return members.isEmpty();
        }

        @Override
        public String toString() {
            return toJson(this);
        }

        public String toPrettyString() {
            return toPrettyJson(this);
        }
    }

    /** JSON array builder. */
    public static final class JsonArray {
        private final List<Object> items = new ArrayList<>();

        private JsonArray() {
        }

        /** Adds one value. A collection passed here becomes a nested array. */
        public JsonArray add(Object value) {
            items.add(value);
            return this;
        }

        /** Adds each element of values to this array. */
        public JsonArray addAll(Iterable<?> values) {
            for (Object v : values) {
                items.add(v);
            }
            return this;
        }

        public JsonArray addObject(Consumer<JsonObject> init) {
            JsonObject o = new JsonObject();
            init.accept(o);
            return add(o);
        }

        public JsonArray addArray(Consumer<JsonArray> init) {
            JsonArray a = new JsonArray();
            init.accept(a);
            return add(a);
        }

        public JsonArray addRaw(String json) {
            return add(raw(json));
        }

        public int size() {
            return items.size();
        }

        public boolean isEmpty() {
            return items.isEmpty();
        }

        @Override
        public String toString() {
            return toJson(this);
        }

        public String toPrettyString() {
            return toPrettyJson(this);
        }
    }

    /** Pre-serialized JSON, written verbatim. */
    public static final class Raw {
        private final String json;

        private Raw(String json) {
            this.json = Objects.requireNonNull(json, "json");
        }

        @Override
        public String toString() {
            return json;
        }
    }

    // ------------------------------------------------------------------ serialization

    private static final class Writer {
        private final StringBuilder sb = new StringBuilder(128);
        private final String indent; // null = compact output
        private final Set<Object> active = Collections.newSetFromMap(new IdentityHashMap<>());
        private int depth;

        Writer(String indent) {
            this.indent = indent;
        }

        Writer write(Object v) {
            value(v);
            return this;
        }

        @Override
        public String toString() {
            return sb.toString();
        }

        private void value(Object v) {
            if (v == null) {
                sb.append("null");
            } else if (v instanceof JsonObject) {
                object(((JsonObject) v).members);
            } else if (v instanceof JsonArray) {
                iterable(((JsonArray) v).items);
            } else if (v instanceof Raw) {
                sb.append(((Raw) v).json);
            } else if (v instanceof Writable) {
                enter(v);
                value(((Writable) v).toJson());
                exit(v);
            } else {
                Function<Object, ?> adapter = adapterFor(v.getClass());
                ReflectInfo info;
                if (adapter != null) {
                    enter(v);
                    value(adapter.apply(v));
                    exit(v);
                } else if ((info = reflectInfo(v.getClass())) != null) {
                    reflect(v, info);
                } else {
                    builtIn(v);
                }
            }
        }

        private void reflect(Object v, ReflectInfo info) {
            enter(v);
            Map<String, Object> members = new LinkedHashMap<>();
            for (Prop p : info.props) {
                Object fieldValue;
                try {
                    fieldValue = p.field.get(v);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("Cannot read field " + p.field, e);
                }
                if (fieldValue != null || !info.skipNulls) {
                    members.put(p.name, fieldValue);
                }
            }
            object(members);
            exit(v);
        }

        private void builtIn(Object v) {
            if (v instanceof CharSequence || v instanceof Character || v instanceof Path) {
                // Path is Iterable<Path>; treat it as a string to avoid endless recursion.
                appendQuoted(sb, v.toString());
            } else if (v instanceof Boolean) {
                sb.append(v.toString());
            } else if (v instanceof Number) {
                number((Number) v);
            } else if (v instanceof Enum) {
                appendQuoted(sb, ((Enum<?>) v).name());
            } else if (v instanceof Optional) {
                value(((Optional<?>) v).orElse(null));
            } else if (v instanceof Map) {
                object((Map<?, ?>) v);
            } else if (v instanceof Iterable) {
                iterable((Iterable<?>) v);
            } else if (v instanceof char[]) {
                appendQuoted(sb, new String((char[]) v));
            } else if (v.getClass().isArray()) {
                array(v);
            } else {
                appendQuoted(sb, v.toString());
            }
        }

        private void number(Number n) {
            if (n instanceof Double || n instanceof Float) {
                double d = n.doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    sb.append("null");
                } else {
                    sb.append(n.toString()); // Float.toString keeps 0.1f as "0.1"
                }
            } else if (n instanceof Integer || n instanceof Long || n instanceof Short
                    || n instanceof Byte || n instanceof BigInteger || n instanceof BigDecimal
                    || n instanceof AtomicInteger || n instanceof AtomicLong) {
                sb.append(n.toString());
            } else {
                number(n.doubleValue()); // unknown Number subclass: go via double
            }
        }

        private void object(Map<?, ?> m) {
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            enter(m);
            sb.append('{');
            depth++;
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                newline();
                appendQuoted(sb, String.valueOf(e.getKey()));
                sb.append(indent == null ? ":" : ": ");
                value(e.getValue());
            }
            depth--;
            newline();
            sb.append('}');
            exit(m);
        }

        private void iterable(Iterable<?> it) {
            Iterator<?> i = it.iterator();
            if (!i.hasNext()) {
                sb.append("[]");
                return;
            }
            enter(it);
            sb.append('[');
            depth++;
            boolean first = true;
            while (i.hasNext()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                newline();
                value(i.next());
            }
            depth--;
            newline();
            sb.append(']');
            exit(it);
        }

        private void array(Object arr) {
            int n = Array.getLength(arr);
            if (n == 0) {
                sb.append("[]");
                return;
            }
            enter(arr);
            sb.append('[');
            depth++;
            for (int i = 0; i < n; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                newline();
                value(Array.get(arr, i));
            }
            depth--;
            newline();
            sb.append(']');
            exit(arr);
        }

        private void newline() {
            if (indent != null) {
                sb.append('\n');
                for (int i = 0; i < depth; i++) {
                    sb.append(indent);
                }
            }
        }

        private void enter(Object o) {
            if (!active.add(o)) {
                throw new IllegalStateException(
                        "Cycle detected while serializing " + o.getClass().getName());
            }
        }

        private void exit(Object o) {
            active.remove(o);
        }
    }

    private static void appendQuoted(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0, n = s.length(); i < n; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (Character.isHighSurrogate(c) && i + 1 < n
                            && Character.isLowSurrogate(s.charAt(i + 1))) {
                        sb.append(c).append(s.charAt(++i)); // valid surrogate pair
                    } else if (c < 0x20 || c == 0x2028 || c == 0x2029 || Character.isSurrogate(c)) {
                        // control chars, JS line separators, lone surrogates
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
