package com.simweaver.common.siso;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * SISO-REF-010 entity type names (0.11.1): the delivered file, partial matches, ranges, the
 * subcategory cross-reference, the generic tables, the cache, and a broken file.
 *
 * <pre>java -cp out/classes:out/test com.simweaver.common.siso.SisoEnumerationsSelfTest [SISO-REF-010.xml]</pre>
 */
public final class SisoEnumerationsSelfTest {

    private static int checks;
    private static int failures;

    private SisoEnumerationsSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path file = Paths.get(args.length > 0 ? args[0] : "simwrift/rtdir/etc/enumerations/SISO-REF-010.xml");
        long t0 = System.nanoTime();
        SisoEnumerations e = SisoEnumerations.load(file);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println(e.title() + " " + e.date() + ": " + e.entityTypeCount() + " entity type entries, read in " + ms + " ms");
        check("the file: title, date, entries", e.title().startsWith("SISO-REF-010") && e.date().matches("\\d{4}-\\d\\d-\\d\\d")
                && e.entityTypeCount() > 10000);
        check("tables: kind 1 Platform, Sweden (SWE)", "Platform".equals(e.enumName(7, 1)) && "Sweden (SWE)".equals(e.enumName(29, 205)));

        // a full match, down to the specific
        SisoEnumerations.EntityTypeInfo i = e.describe(1, 1, 205, 2, 1, 5, 0);
        show("1.1.205.2.1.5.0", i);
        check("1.1.205.2.1.5.0: CV9040A, Armored Fighting Vehicle, SWE, complete", "CV9040A".equals(i.text())
                && i.context() != null && i.context().contains("Armored") && "SWE".equals(i.countryCode())
                && "Sweden".equals(i.countryName()) && "specific".equals(i.matched()) && i.complete() && i.path().size() == 6);
        // the subcategory, the specific unknown
        i = e.describe(1, 1, 205, 2, 1, 200, 0);
        show("1.1.205.2.1.200.0", i);
        check("an unknown specific: the subcategory, not complete", "subcategory".equals(i.matched()) && !i.complete()
                && i.text().contains("CV90"));
        // only the category
        i = e.describe(1, 1, 205, 2, 0, 0, 0);
        show("1.1.205.2.0.0.0", i);
        check("only a category: it, with Land platform", "category".equals(i.matched()) && i.complete()
                && "Land platform".equals(i.context()));
        // a country with nothing in the CET: the generic category table
        i = e.describe(1, 1, 0, 2, 0, 0, 0);
        show("1.1.0.2.0.0.0", i);
        check("country 0: the generic land category, no country", "category".equals(i.matched()) && i.countryCode() == null
                && i.countryName() == null
                && i.text().length() > 0 && "Land platform".equals(i.context()));
        // an unknown category of a known country
        i = e.describe(1, 2, 205, 250, 0, 0, 0);
        show("1.2.205.250.0.0.0", i);
        check("an unknown category: Air platform, the country, not complete", "Air platform".equals(i.text()) && "SWE".equals(i.countryCode())
                && !i.complete() && "country".equals(i.matched()));
        // a munition
        i = e.describe(2, 2, 225, 1, 0, 0, 0);
        show("2.2.225.1.0.0.0", i);
        check("a munition: Anti-Armor munition, USA", i.text().length() > 0 && "USA".equals(i.countryCode())
                && ("Anti-Armor munition".equals(i.context()) || "Anti-Armor munition".equals(i.text())));
        // a life form: subcategory_xref or plain
        i = e.describe(3, 1, 225, 1, 0, 0, 0);
        show("3.1.225.1.0.0.0", i);
        check("a life form is named", i.text().length() > 0 && "USA".equals(i.countryCode()));
        // nothing known
        i = e.describe(250, 0, 0, 0, 0, 0, 0);
        check("an unknown kind: empty text, matched none", i.text().isEmpty() && "none".equals(i.matched()) && !i.complete());
        check("kind 0 (Other, e.g. an unused alternate type): nothing", e.describe(0, 0, 0, 0, 0, 0, 0).text().isEmpty()
                && e.describe(0, 1, 205, 0, 0, 0, 0).text().isEmpty());
        i = e.describe(1, 1, 9999, 0, 0, 0, 0);
        check("an unknown country: no code, not complete", i.countryCode() == null && !i.complete() && "Land platform".equals(i.text()));
        check("cached: the same object", e.describe(1, 1, 205, 2, 1, 5, 0) == e.describe(1, 1, 205, 2, 1, 5, 0));

        // a range and an xref, from a small file of our own
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE ebv [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<ebv xmlns=\"http://www.sisostds.org/schemas/SISO-REF-010/2.7\" title=\"T\" date=\"2026-01-01\">"
                + "<enum uid=\"7\" name=\"Entity Kind\" size=\"8\"><enumrow value=\"3\" description=\"Life form\" uuid=\"u\"/></enum>"
                + "<enum uid=\"8\" name=\"Platform Domain\" size=\"8\"><enumrow value=\"1\" description=\"Land\" uuid=\"u\"/></enum>"
                + "<enum uid=\"21\" name=\"Sizes\" size=\"8\"><enumrow value=\"4\" description=\"Large\" uuid=\"u\"/></enum>"
                + "<enum uid=\"29\" name=\"Country\" size=\"16\"><enumrow value=\"1\" description=\"Ruritania (RUR)\" uuid=\"u\"/></enum>"
                + "<cet uid=\"30\" name=\"Entity Types\"><entity kind=\"3\" domain=\"1\" country=\"1\" uid=\"1\" uuid=\"u\">"
                + "<category value=\"1\" description=\"Soldier\" uid=\"2\" uuid=\"u\"><cr value=\"1\"/>"
                + "<subcategory_range value_min=\"10\" value_max=\"20\" description=\"Rifles\" uid=\"3\" uuid=\"u\">"
                + "<specific value=\"1\" description=\"Rifle A\" uid=\"4\" uuid=\"u\"/></subcategory_range></category>"
                + "<category value=\"2\" description=\"Animal\" uid=\"5\" uuid=\"u\"><subcategory_xref xref=\"21\" description=\"Size\" uid=\"6\" uuid=\"u\"/></category>"
                + "</entity></cet></ebv>";
        SisoEnumerations s;
        try {
            s = SisoEnumerations.read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            i = s.describe(3, 1, 1, 1, 15, 1, 0);
            check("a subcategory range, and its specific: " + i.text(), "Rifle A".equals(i.text()) && "Soldier".equals(i.context())
                    && "RUR".equals(i.countryCode()) && i.complete());
            i = s.describe(3, 1, 1, 2, 4, 0, 0);
            check("a subcategory_xref: the other table's row: " + i.text(), "Large".equals(i.text()) && "Animal".equals(i.context()));
            check("an extra is shown with its specific", true);
        } catch (IOException x) {
            check("a file with a DTD is read without it: " + x.getMessage(), false);
        }
        try {
            SisoEnumerations.read(new ByteArrayInputStream("<ebv/>".getBytes(StandardCharsets.UTF_8)));
            check("a file without the tables is refused", false);
        } catch (IOException x) {
            check("a file without the tables is refused: " + x.getMessage(), x.getMessage().contains("CET"));
        }
        try {
            SisoEnumerations.read(new ByteArrayInputStream("<ebv><enum".getBytes(StandardCharsets.UTF_8)));
            check("broken XML is refused", false);
        } catch (IOException x) {
            check("broken XML is refused", true);
        }
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void show(String type, SisoEnumerations.EntityTypeInfo i) {
        StringBuilder p = new StringBuilder();
        for (String[] l : i.path()) {
            p.append(p.length() == 0 ? "" : " > ").append(l[2]);
        }
        System.out.println("  " + type + "  [" + i.countryCode() + "] " + i.text() + (i.context() == null ? "" : "  · " + i.context())
                + "   (" + i.matched() + (i.complete() ? "" : ", partly") + ")   " + p);
    }

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }
}
