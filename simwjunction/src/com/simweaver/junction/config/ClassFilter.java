package com.simweaver.junction.config;

import com.simweaver.common.config.SimwProperties;
import com.simweaver.common.json.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Which object and interaction classes may pass, by qualified class name.
 *
 * <h2>Patterns</h2>
 * <ul>
 *   <li>The roots {@code HLAobjectRoot.} / {@code HLAinteractionRoot.} may be left out.</li>
 *   <li>{@code A.B} is exactly that class.</li>
 *   <li>{@code A.B.*} is that class and all its subclasses, at any depth.</li>
 *   <li>{@code *} on its own: every class.</li>
 *   <li>{@code *} inside a segment is a glob within that segment ({@code Platform.*Vehicle}).</li>
 *   <li>{@code **} is any number of segments ({@code **.Aircraft}: every class named Aircraft).</li>
 * </ul>
 *
 * <h2>Decision</h2>
 * The most specific matching rule wins (literal segments, then literal characters, an exact
 * pattern beating a subtree pattern); on a tie, block wins. Without a matching rule a class is
 * blocked when an allow list is given, otherwise allowed. The HLA roots and the MOM
 * ({@code HLAmanager...}) never pass.
 *
 * <p>Immutable, thread safe.
 */
public final class ClassFilter implements Json.Writable {

    /** Lets everything pass. */
    public static final ClassFilter ALL = new ClassFilter(Collections.<String>emptyList(), Collections.<String>emptyList(),
            Collections.<String>emptyList(), Collections.<String>emptyList());

    private final List<Rule> objectAllow;
    private final List<Rule> objectBlock;
    private final List<Rule> interactionAllow;
    private final List<Rule> interactionBlock;
    private final List<String> texts = new ArrayList<String>(4);

    /**
     * @param objectAllow      object class allow patterns
     * @param objectBlock      object class block patterns
     * @param interactionAllow interaction class allow patterns
     * @param interactionBlock interaction class block patterns
     */
    public ClassFilter(List<String> objectAllow, List<String> objectBlock, List<String> interactionAllow,
                       List<String> interactionBlock) {
        this.objectAllow = rules(objectAllow);
        this.objectBlock = rules(objectBlock);
        this.interactionAllow = rules(interactionAllow);
        this.interactionBlock = rules(interactionBlock);
        texts.add(String.join(";", objectAllow));
        texts.add(String.join(";", objectBlock));
        texts.add(String.join(";", interactionAllow));
        texts.add(String.join(";", interactionBlock));
    }

    /**
     * Reads {@code <prefix>objects.allow}, {@code objects.block}, {@code interactions.allow},
     * {@code interactions.block} (';'-separated).
     *
     * @param p      properties
     * @param prefix e.g. {@code "router."}
     * @return the filter ({@link #ALL} when none of the keys is set)
     */
    public static ClassFilter fromProperties(SimwProperties p, String prefix) {
        String[] oa = p.getStringArray(prefix + "objects.allow");
        String[] ob = p.getStringArray(prefix + "objects.block");
        String[] ia = p.getStringArray(prefix + "interactions.allow");
        String[] ib = p.getStringArray(prefix + "interactions.block");
        if (oa.length + ob.length + ia.length + ib.length == 0) {
            return ALL;
        }
        return new ClassFilter(list(oa), list(ob), list(ia), list(ib));
    }

    private static List<String> list(String[] a) {
        List<String> l = new ArrayList<String>();
        for (String s : a) {
            if (!s.trim().isEmpty()) {
                l.add(s.trim());
            }
        }
        return l;
    }

    /** @return true when everything passes */
    public boolean isAll() {
        return objectAllow.isEmpty() && objectBlock.isEmpty() && interactionAllow.isEmpty() && interactionBlock.isEmpty();
    }

    /**
     * @param qualifiedName e.g. {@code HLAobjectRoot.BaseEntity.PhysicalEntity.Platform.Aircraft}
     * @return true when the object class passes
     */
    public boolean allowsObjectClass(String qualifiedName) {
        return decide(strip(qualifiedName, "HLAobjectRoot"), objectAllow, objectBlock);
    }

    /**
     * @param qualifiedName e.g. {@code HLAinteractionRoot.WeaponFire}
     * @return true when the interaction class passes
     */
    public boolean allowsInteractionClass(String qualifiedName) {
        return decide(strip(qualifiedName, "HLAinteractionRoot"), interactionAllow, interactionBlock);
    }

    private static String strip(String q, String root) {
        String s = q == null ? "" : q.trim();
        if (s.equals(root)) {
            return "";
        }
        if (s.startsWith(root + ".")) {
            return s.substring(root.length() + 1);
        }
        return s;
    }

    private static boolean decide(String name, List<Rule> allow, List<Rule> block) {
        if (name.isEmpty() || name.startsWith("HLAmanager") || name.startsWith("HLAobjectRoot") || name.startsWith("HLAinteractionRoot")) {
            return false;                                  // the roots and the MOM never pass
        }
        if (allow.isEmpty() && block.isEmpty()) {
            return true;
        }
        int bestAllow = best(name, allow);
        int bestBlock = best(name, block);
        if (bestAllow < 0 && bestBlock < 0) {
            return allow.isEmpty();
        }
        return bestAllow > bestBlock;                       // tie (or no allow match): block
    }

    private static int best(String name, List<Rule> rules) {
        int b = -1;
        for (Rule r : rules) {
            if (r.matches(name)) {
                b = Math.max(b, r.score);
            }
        }
        return b;
    }

    private static List<Rule> rules(List<String> patterns) {
        List<Rule> l = new ArrayList<Rule>();
        for (String p : patterns) {
            if (p != null && !p.trim().isEmpty()) {
                l.add(new Rule(p.trim()));
            }
        }
        return Collections.unmodifiableList(l);
    }

    /** One pattern, compiled. */
    private static final class Rule {
        final Pattern regex;
        final int score;

        Rule(String text) {
            String t = strip(strip(text, "HLAobjectRoot"), "HLAinteractionRoot");
            boolean subtree = false;
            if (t.equals("*") || t.isEmpty()) {
                regex = Pattern.compile(".+");
                score = 0;
                return;
            }
            if (t.endsWith(".*")) {
                subtree = true;
                t = t.substring(0, t.length() - 2);
            }
            String[] segs = t.split("\\.");
            StringBuilder re = new StringBuilder();
            int literalSegs = 0;
            int literalChars = 0;
            for (int i = 0; i < segs.length; i++) {
                String s = segs[i];
                boolean last = i == segs.length - 1;
                if (s.equals("**")) {
                    // non-last: any number of leading segments (each with its dot); last: one or more segments
                    re.append(last ? "[^.]+(?:\\.[^.]+)*" : "(?:[^.]+\\.)*");
                    continue;
                }
                if (!s.contains("*")) {
                    literalSegs++;
                }
                for (char c : s.toCharArray()) {
                    if (c == '*') {
                        re.append("[^.]*");
                    } else {
                        re.append(Pattern.quote(String.valueOf(c)));
                        literalChars++;
                    }
                }
                if (!last) {
                    re.append("\\.");
                }
            }
            String body = re.toString();
            regex = Pattern.compile(subtree ? body + "(?:\\..+)?" : body);
            score = literalSegs * 1000 + literalChars + (subtree ? 0 : 500);
        }

        boolean matches(String name) {
            return regex.matcher(name).matches();
        }
    }

    @Override
    public Object toJson() {
        Json.JsonObject o = Json.object();
        o.put("objectsallow", texts.get(0));
        o.put("objectsblock", texts.get(1));
        o.put("interactionsallow", texts.get(2));
        o.put("interactionsblock", texts.get(3));
        return o;
    }

    @Override
    public String toString() {
        if (isAll()) {
            return "all";
        }
        StringBuilder sb = new StringBuilder();
        if (!texts.get(0).isEmpty()) {
            sb.append("objects allow ").append(texts.get(0)).append(' ');
        }
        if (!texts.get(1).isEmpty()) {
            sb.append("objects block ").append(texts.get(1)).append(' ');
        }
        if (!texts.get(2).isEmpty()) {
            sb.append("interactions allow ").append(texts.get(2)).append(' ');
        }
        if (!texts.get(3).isEmpty()) {
            sb.append("interactions block ").append(texts.get(3));
        }
        return sb.toString().trim();
    }
}
