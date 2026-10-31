package com.simweaver.common;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where the self tests find a Talk FOM (0.9.21). No Talk FOM is part of the source: the tests that
 * need one use the tester's own copy and skip their Talk checks without it.
 *
 * <ol>
 *   <li>{@code -Dtalk.foms=<file>;<file>} (or the environment variable {@code SIMWEAVER_TALK_FOMS}),</li>
 *   <li>else the vendor's two module files, if both are in {@code <fom root>/ieee1516-2010}.</li>
 * </ol>
 */
public final class TestTalkFom {

    private TestTalkFom() {
    }

    /** @return the Talk FOM modules, or an empty list when there are none */
    public static List<String> find(File fomRoot) {
        String setting = System.getProperty("talk.foms");
        if (setting == null || setting.trim().isEmpty()) {
            setting = System.getenv("SIMWEAVER_TALK_FOMS");
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

    /** The line a test prints when it skips its Talk checks. */
    public static String skipped(String what) {
        return "SKIPPED " + what + ": no Talk FOM (give your own with -Dtalk.foms=<file>;<file>)";
    }
}
