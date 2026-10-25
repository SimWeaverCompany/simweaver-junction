package com.simweaver.common.iem;

/**
 * Byte order of a basic data representation.
 */
public enum Endianness {
    BIG("Big"),
    LITTLE("Little");

    private final String omtName;

    Endianness(String omtName) {
        this.omtName = omtName;
    }

    public String getOmtName() {
        return omtName;
    }

    /** Parses "Big"/"Little" (case-insensitive); null when unknown. */
    public static Endianness fromOmtName(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        for (Endianness e : values()) {
            if (e.omtName.equalsIgnoreCase(t)) {
                return e;
            }
        }
        return null;
    }
}
