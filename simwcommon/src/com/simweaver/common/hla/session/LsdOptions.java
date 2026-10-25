package com.simweaver.common.hla.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The local settings designator (1516-2010) / RTI configuration name (1516-2025) setting as a
 * list of choices: {@code endpoint.hla.lsd=default;crc2;crc@10.0.0.5:8989}. The first is the
 * default; the others are offered when a gateway or datasource is started, and any other value
 * can be typed there too.
 *
 * <ul>
 *   <li>Entries are separated by {@code ;} and trimmed. An empty entry offers the RTI's own
 *       default (no designator); an empty setting is that alone.</li>
 *   <li>The same entry twice is listed once.</li>
 *   <li>An LSD that contains {@code ;} cannot be listed, but can be typed at the start.</li>
 * </ul>
 */
public final class LsdOptions {

    /** The longest LSD accepted. */
    public static final int MAX_LENGTH = 512;

    private LsdOptions() {
    }

    /**
     * @param setting the setting's value, null = not set
     * @return the choices, the default first; never empty ({@code [""]} for none)
     */
    public static List<String> parse(String setting) {
        if (setting == null) {
            return Collections.singletonList("");
        }
        Set<String> out = new LinkedHashSet<String>();
        int from = 0;
        while (true) {
            int semi = setting.indexOf(';', from);
            String part = (semi < 0 ? setting.substring(from) : setting.substring(from, semi)).trim();
            out.add(part);
            if (semi < 0) {
                break;
            }
            from = semi + 1;
        }
        return Collections.unmodifiableList(new ArrayList<String>(out));
    }

    /**
     * Checks an LSD given at a start.
     *
     * @return why it cannot be used, or null when it can (null and "" are fine: the RTI default)
     */
    public static String problem(String lsd) {
        if (lsd == null) {
            return null;
        }
        if (lsd.length() > MAX_LENGTH) {
            return "an LSD has at most " + MAX_LENGTH + " characters";
        }
        for (int i = 0; i < lsd.length(); i++) {
            if (Character.isISOControl(lsd.charAt(i))) {
                return "an LSD cannot contain control characters (line breaks, tabs)";
            }
        }
        return null;
    }

    /** @return how an LSD is shown: the value, or {@code (RTI default)} for none */
    public static String label(String lsd) {
        return lsd == null || lsd.isEmpty() ? "(RTI default)" : lsd;
    }
}
