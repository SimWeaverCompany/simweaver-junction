package com.simweaver.junction.config;

import com.simweaver.common.config.SimwProperties;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Group execution variables: the tokens {@code GROUPVAR1}, {@code GROUPVAR2}, ... in any setting
 * of a gateway, its endpoint kits and its router kit, replaced by values given when the gateway
 * (or its group) is started, e.g. {@code endpoint.hla.federation=GROUPVAR1},
 * {@code talk.channelmap=Net1>radio_GROUPVAR2}.
 *
 * <ul>
 *   <li>A token is replaced wherever it stands; the text around it stays
 *       ({@code radio_GROUPVAR1} -> {@code radio_Bravo}).</li>
 *   <li>The whole number counts: {@code GROUPVAR10} is variable 10, not {@code GROUPVAR1} and a 0.</li>
 *   <li>Values and setting names are replaced: a name holds a channel in
 *       {@code talk.radio.map.radio_GROUPVAR1=251000000}. The definitions
 *       {@code junction.groupvar.GROUPVARn} are left as they are.</li>
 * </ul>
 */
public final class GroupVariables {

    /** A variable token. */
    public static final Pattern TOKEN = Pattern.compile("GROUPVAR\\d+");

    private GroupVariables() {
    }

    /** @return true for a valid variable name, e.g. "GROUPVAR1" */
    public static boolean isName(String s) {
        return s != null && TOKEN.matcher(s).matches();
    }

    /** The prefix of the default definitions, whose names are not substituted. */
    static final String DEFINITION_PREFIX = "junction.groupvar.";

    /** The suffix of a variable's description: {@code junction.groupvar.GROUPVAR1.desc=Home city}. */
    public static final String DESCRIPTION_SUFFIX = ".desc";

    /** @return true for a description key, {@code junction.groupvar.<name>.desc} (any case of "desc") */
    public static boolean isDescriptionKey(String key) {
        return key.startsWith(DEFINITION_PREFIX) && key.length() > DEFINITION_PREFIX.length() + DESCRIPTION_SUFFIX.length()
                && key.regionMatches(true, key.length() - DESCRIPTION_SUFFIX.length(), DESCRIPTION_SUFFIX, 0, DESCRIPTION_SUFFIX.length());
    }

    /**
     * Adds the variables used in the settings of {@code p} (names and values) to {@code into}.
     * Descriptions are text for people: a variable named in one is not used by it.
     */
    public static void collect(SimwProperties p, Set<String> into) {
        for (String k : p.getKeys()) {
            if (isDescriptionKey(k)) {
                continue;
            }
            if (!k.startsWith(DEFINITION_PREFIX)) {
                find(k, into);
            }
            find(p.getString(k), into);
        }
    }

    private static void find(String text, Set<String> into) {
        if (text == null) {
            return;
        }
        Matcher m = TOKEN.matcher(text);
        while (m.find()) {
            into.add(m.group());
        }
    }

    /** @return the text with the known variables replaced (unknown ones stay as they are) */
    public static String apply(String text, Map<String, String> values) {
        if (text == null || values.isEmpty() || text.indexOf("GROUPVAR") < 0) {
            return text;
        }
        Matcher m = TOKEN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String v = values.get(m.group());
            m.appendReplacement(sb, Matcher.quoteReplacement(v != null ? v : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * @return a copy of {@code p} with the variables replaced in every value and every setting
     *         name, except the names of the {@code junction.groupvar.*} definitions (sources kept)
     */
    public static SimwProperties apply(SimwProperties p, Map<String, String> values) {
        if (values.isEmpty()) {
            return p;
        }
        SimwProperties out = new SimwProperties();
        for (String k : p.getKeys()) {
            String src = p.getSource(k);
            String key = k.startsWith(DEFINITION_PREFIX) ? k : apply(k, values);
            String v = isDescriptionKey(k) ? p.getString(k) : apply(p.getString(k), values);   // descriptions stay as written
            if (src != null) {
                out.set(key, v, src, p.getSourceLine(k));
            } else {
                out.set(key, v);
            }
        }
        return out;
    }

    /** @return the names sorted by number (GROUPVAR2 before GROUPVAR10) */
    public static Set<String> sorted(Collection<String> names) {
        Set<String> s = new TreeSet<String>((a, b) -> {
            int c = Integer.compare(a.length(), b.length());
            return c != 0 ? c : a.compareTo(b);
        });
        s.addAll(names);
        return s;
    }
}
