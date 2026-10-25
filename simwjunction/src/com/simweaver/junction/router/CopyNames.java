package com.simweaver.junction.router;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Names of the copies the router registers, from a template ({@code router.names.template}):
 * the tokens below are replaced, everything else is kept, and the result is cut to a maximum
 * length ({@code router.names.maxlength}) with the numbering of taken names ({@code #2}, ...)
 * inside that length.
 *
 * <pre>
 * {name}     the object's instance name where it comes from
 * {hexguid}  a GUID of the bridged object, 32 lower-case hex digits (the same for every copy of it,
 *            and kept while the gateway runs, so a copy registered again after a reconnect keeps its name)
 * {HEXGUID}  the same in upper case
 * {guid}     the same GUID as 8-4-4-4-12
 * {origin}   the id of the endpoint the object comes from
 * </pre>
 */
public final class CopyNames {

    /** The tokens a template may use. */
    public static final List<String> TOKENS = Collections.unmodifiableList(Arrays.asList("name", "hexguid", "HEXGUID", "guid", "origin"));

    /** How many candidates a template gives: the name, then {@code #2} .. {@code #4}. */
    static final int CANDIDATES = 4;

    private CopyNames() {
    }

    /**
     * @return what is wrong with the template (unknown tokens, a '{' without '}'), empty when it is usable
     */
    public static List<String> check(String template) {
        List<String> problems = new ArrayList<String>();
        if (template == null) {
            return problems;
        }
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '{') {
                int end = template.indexOf('}', i + 1);
                if (end < 0) {
                    problems.add("'{' at " + (i + 1) + " has no '}'");
                    break;
                }
                String token = template.substring(i + 1, end);
                if (!TOKENS.contains(token)) {
                    problems.add("unknown token {" + token + "} (known: {" + String.join("}, {", TOKENS) + "})");
                }
                i = end + 1;
            } else if (c == '}') {
                problems.add("'}' at " + (i + 1) + " has no '{'");
                i++;
            } else {
                i++;
            }
        }
        return problems;
    }

    /** @return true when the template gives every object a name of its own ({name} or a GUID token) */
    public static boolean distinguishes(String template) {
        return template.contains("{name}") || template.contains("{hexguid}") || template.contains("{HEXGUID}") || template.contains("{guid}");
    }

    /** The template with its tokens replaced (a checked template). */
    public static String expand(String template, String name, UUID guid, String origin) {
        String g = guid == null ? "" : guid.toString();
        String hex = g.replace("-", "");
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            int end = c == '{' ? template.indexOf('}', i + 1) : -1;
            if (end > 0) {
                switch (template.substring(i + 1, end)) {
                    case "name": sb.append(name); break;
                    case "hexguid": sb.append(hex.toLowerCase(Locale.ROOT)); break;
                    case "HEXGUID": sb.append(hex.toUpperCase(Locale.ROOT)); break;
                    case "guid": sb.append(g); break;
                    case "origin": sb.append(origin); break;
                    default: sb.append(template, i, end + 1);
                }
                i = end + 1;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * @param max the longest name in characters (code points); 0 or less = no limit
     * @return {@code base + suffix}, with base cut so that the whole is at most {@code max}
     */
    public static String fit(String base, String suffix, int max) {
        String whole = base + suffix;
        if (max <= 0 || whole.codePointCount(0, whole.length()) <= max) {
            return whole;
        }
        int room = max - suffix.codePointCount(0, suffix.length());
        if (room < 1) {
            return whole.substring(0, whole.offsetByCodePoints(0, max));
        }
        return base.substring(0, base.offsetByCodePoints(0, Math.min(room, base.codePointCount(0, base.length())))) + suffix;
    }

    /**
     * Names starting with "HLA" are reserved for the RTI: such a name gets the origin endpoint's
     * id in front ({@code <origin>.HLA...}).
     */
    public static String unreserved(String n, String origin) {
        return n.regionMatches(true, 0, "HLA", 0, 3) ? origin + "." + n : n;
    }

    /**
     * @return the names to try, in order: the expanded template, then numbered ({@code #2} ...),
     *         each within {@code max}; duplicates left out
     */
    public static List<String> fromTemplate(String template, int max, String name, UUID guid, String origin) {
        String t = expand(template, name, guid, origin);
        if (t.trim().isEmpty()) {
            t = name;
        }
        t = unreserved(t, origin);
        List<String> l = new ArrayList<String>();
        for (int k = 1; k <= CANDIDATES; k++) {
            String c = fit(t, k == 1 ? "" : "#" + k, max);
            if (!l.contains(c)) {
                l.add(c);
            }
        }
        return l;
    }

    /** @return the names cut to {@code max} (the numbering kept), duplicates left out */
    public static List<String> limited(List<String> names, int max) {
        if (max <= 0) {
            return names;
        }
        List<String> l = new ArrayList<String>();
        for (String n : names) {
            int hash = n.lastIndexOf('#');
            int at = n.lastIndexOf('@');
            int cut = at > 0 ? at : hash > 0 && n.substring(hash + 1).matches("\\d+") ? hash : -1;   // keep "@origin#n" / "#n" whole
            String c = cut > 0 ? fit(n.substring(0, cut), n.substring(cut), max) : fit(n, "", max);
            if (!l.contains(c)) {
                l.add(c);
            }
        }
        return l;
    }
}
