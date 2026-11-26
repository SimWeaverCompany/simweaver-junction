package com.simweaver.common;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where the self tests find an HLA Comms FOM (0.9.21; 0.14.1 renamed). No HLA Comms FOM is part of the
 * source: the tests that need one use the tester's own copy and skip their HLA Comms checks without it.
 *
 * <ol>
 *   <li>{@code -Dcomms.foms=<file>;<file>} (or the environment variable {@code SIMWEAVER_COMMS_FOMS}; the
 *       old names {@code talk.foms} / {@code SIMWEAVER_TALK_FOMS} still work),</li>
 *   <li>else the vendor's two module files, if both are in {@code <fom root>/ieee1516-2010}.</li>
 * </ol>
 */
public final class TestCommsFom {

    private TestCommsFom() {
    }

    /** @return the HLA Comms FOM modules, or an empty list when there are none */
    public static List<String> find(File fomRoot) {
        String setting = null;
        for (String v : new String[] {System.getProperty("comms.foms"), System.getenv("SIMWEAVER_COMMS_FOMS"),
                System.getProperty("talk.foms"), System.getenv("SIMWEAVER_TALK_FOMS")}) {
            if (setting == null && v != null && !v.trim().isEmpty()) {
                setting = v;
            }
        }
        List<String> out = new ArrayList<String>();
        if (setting != null && !setting.trim().isEmpty()) {
            for (String s : setting.split("[;" + File.pathSeparator + "]")) {
                if (!s.trim().isEmpty() && new File(s.trim()).isFile()) {
                    out.add(new File(s.trim()).getAbsolutePath());
                }
            }
            return out;
        }
        File dir = new File(fomRoot, "ieee1516-2010");
        File a = new File(dir, "pitchtalk.xml");
        File b = new File(dir, "pitchtalkeffects.xml");
        if (a.isFile() && b.isFile()) {
            out.add(a.getAbsolutePath());
            out.add(b.getAbsolutePath());
            return out;
        }
        return Collections.emptyList();
    }

    /** The line a test prints when it skips its HLA Comms checks. */
    public static String skipped(String what) {
        return "SKIPPED " + what + ": no HLA Comms FOM (give your own with -Dcomms.foms=<file>;<file>)";
    }
}
