package com.simweaver.common.hla;

/**
 * Which HLA RTI Java APIs are on the class path. Checked once per JVM, without loading or
 * initialising anything of the RTI, so it is safe to call when no RTI library is present.
 */
public final class RtiAvailability {

    /** IEEE 1516-2010 (HLA Evolved) Java API, package {@code hla.rti1516e}. */
    public static final boolean HLA_EVOLVED = present("hla.rti1516e.RTIambassador");
    /** IEEE 1516-2025 (HLA 4) Java API, package {@code hla.rti1516_2025}. */
    public static final boolean HLA_4 = present("hla.rti1516_2025.RTIambassador");

    private RtiAvailability() {
    }

    /**
     * True for an HLA standard ("IEEE1516-2010", "IEEE1516_2025", ...): a datasource of it needs an RTI.
     * DIS standards ("DIS6", "DIS7") need none.
     */
    public static boolean needsRti(Object standard) {
        return norm(standard).contains("1516");
    }

    /** True when the RTI API for this HLA standard is on the class path (false for non-HLA standards). */
    public static boolean isAvailable(Object standard) {
        String s = norm(standard);
        if (!s.contains("1516")) return false;
        return s.contains("2025") ? HLA_4 : HLA_EVOLVED;
    }

    /** Upper case, '_' and blanks as '-': "IEEE1516_2010" and "ieee 1516-2010" both become "IEEE1516-2010". */
    private static String norm(Object standard) {
        return standard == null ? "" : String.valueOf(standard).trim().toUpperCase(java.util.Locale.ROOT).replace('_', '-').replace(" ", "");
    }

    private static boolean present(String className) {
        try {
            Class.forName(className, false, RtiAvailability.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
