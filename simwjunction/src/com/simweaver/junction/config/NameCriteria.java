package com.simweaver.junction.config;

import com.simweaver.common.json.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 0.14.0: which bridged objects get the name template ({@code router.names.template}) in a
 * destination; the others keep their own name under the name policy.
 *
 * <pre>
 * router.names.select.classes[.&lt;endpoint&gt;] = Platform.*;Lifeform     (ClassFilter patterns)
 * router.names.except.classes[.&lt;endpoint&gt;] = Platform.Spacecraft
 * router.names.select.names[.&lt;endpoint&gt;]   = Blue-*;re:^[A-Z]{3}\d+$     (glob * ?, or re: a Java regex)
 * router.names.except.names[.&lt;endpoint&gt;]   = *-test
 * </pre>
 *
 * An object matches when its class (in the model it comes from) passes the class rules and its
 * own instance name passes the name rules. Classes are decided as by {@link ClassFilter}:
 * {@code select} is the allow list, {@code except} the block list, the most specific pattern wins,
 * block on a tie, no select list = every class. Names: no select list = every name; any except
 * pattern that matches excludes. Globs match the whole name and are case-sensitive (HLA instance
 * names are); {@code re:(?i)…} for a case-insensitive regex. Without any rule every object matches.
 *
 * <p>Immutable, thread safe.
 */
public final class NameCriteria implements Json.Writable {

    /** No rules: every object gets the template. */
    public static final NameCriteria ALL = new NameCriteria(Collections.<String>emptyList(), Collections.<String>emptyList(),
            Collections.<String>emptyList(), Collections.<String>emptyList());

    private final List<String> selectClasses;
    private final List<String> exceptClasses;
    private final List<String> selectNames;
    private final List<String> exceptNames;
    private final ClassFilter classes;
    private final List<Pattern> select;
    private final List<Pattern> except;

    /**
     * @throws IllegalArgumentException for a name pattern that is no valid regex (the message says which)
     */
    public NameCriteria(List<String> selectClasses, List<String> exceptClasses, List<String> selectNames, List<String> exceptNames) {
        this.selectClasses = clean(selectClasses);
        this.exceptClasses = clean(exceptClasses);
        this.selectNames = clean(selectNames);
        this.exceptNames = clean(exceptNames);
        this.classes = this.selectClasses.isEmpty() && this.exceptClasses.isEmpty() ? ClassFilter.ALL
                : new ClassFilter(this.selectClasses, this.exceptClasses, Collections.<String>emptyList(), Collections.<String>emptyList());
        this.select = patterns(this.selectNames);
        this.except = patterns(this.exceptNames);
    }

    /** @return true when there are no rules (every object matches) */
    public boolean isAll() {
        return selectClasses.isEmpty() && exceptClasses.isEmpty() && selectNames.isEmpty() && exceptNames.isEmpty();
    }

    /**
     * @param qualifiedClass the object's class in the model it comes from (with or without the root)
     * @param name           its own instance name
     */
    public boolean matches(String qualifiedClass, String name) {
        if (isAll()) {
            return true;
        }
        if (!selectClasses.isEmpty() || !exceptClasses.isEmpty()) {
            if (qualifiedClass == null || !classes.allowsObjectClass(qualifiedClass)) {
                return false;
            }
        }
        String n = name == null ? "" : name;
        if (!select.isEmpty()) {
            boolean any = false;
            for (Pattern p : select) {
                if (p.matcher(n).matches()) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                return false;
            }
        }
        for (Pattern p : except) {
            if (p.matcher(n).matches()) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return the problem with a name pattern, or null when it is fine
     */
    public static String problem(String pattern) {
        try {
            pattern(pattern);
            return null;
        } catch (PatternSyntaxException e) {
            return "'" + pattern + "' is not a valid regular expression: " + e.getDescription();
        }
    }

    /** A glob ({@code *} any run, {@code ?} one character) or {@code re:<regex>}. */
    static Pattern pattern(String p) {
        String t = p.trim();
        if (t.regionMatches(true, 0, "re:", 0, 3)) {
            return Pattern.compile(t.substring(3));
        }
        StringBuilder b = new StringBuilder();
        StringBuilder lit = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '*' || c == '?') {
                if (lit.length() > 0) {
                    b.append(Pattern.quote(lit.toString()));
                    lit.setLength(0);
                }
                b.append(c == '*' ? ".*" : ".");
            } else {
                lit.append(c);
            }
        }
        if (lit.length() > 0) {
            b.append(Pattern.quote(lit.toString()));
        }
        return Pattern.compile(b.toString(), Pattern.DOTALL);
    }

    private static List<Pattern> patterns(List<String> l) {
        List<Pattern> out = new ArrayList<>();
        for (String s : l) {
            String why = problem(s);
            if (why != null) {
                throw new IllegalArgumentException(why);
            }
            out.add(pattern(s));
        }
        return Collections.unmodifiableList(out);
    }

    private static List<String> clean(List<String> l) {
        List<String> out = new ArrayList<>();
        if (l != null) {
            for (String s : l) {
                if (s != null && !s.trim().isEmpty()) {
                    out.add(s.trim());
                }
            }
        }
        return Collections.unmodifiableList(out);
    }

    @Override
    public Object toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (!selectClasses.isEmpty()) {
            m.put("selectclasses", selectClasses);
        }
        if (!exceptClasses.isEmpty()) {
            m.put("exceptclasses", exceptClasses);
        }
        if (!selectNames.isEmpty()) {
            m.put("selectnames", selectNames);
        }
        if (!exceptNames.isEmpty()) {
            m.put("exceptnames", exceptNames);
        }
        return m;
    }

    @Override
    public String toString() {
        List<String> parts = new ArrayList<>();
        if (!selectClasses.isEmpty()) {
            parts.add("classes " + String.join(";", selectClasses));
        }
        if (!exceptClasses.isEmpty()) {
            parts.add("not classes " + String.join(";", exceptClasses));
        }
        if (!selectNames.isEmpty()) {
            parts.add("names " + String.join(";", selectNames));
        }
        if (!exceptNames.isEmpty()) {
            parts.add("not names " + String.join(";", exceptNames));
        }
        return parts.isEmpty() ? "all" : String.join(", ", parts);
    }
}
