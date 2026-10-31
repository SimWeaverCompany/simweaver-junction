package com.simweaver.common.iem;

/**
 * Attribute update type, OMT attribute table column "Update type".
 */
public enum UpdateType {

    STATIC("Static"),
    PERIODIC("Periodic"),
    CONDITIONAL("Conditional"),
    NA("NA");

    private final String omtName;

    UpdateType(String omtName) {
        this.omtName = omtName;
    }

    /** The spelling used in OMT / FOM XML files. */
    public String getOmtName() {
        return omtName;
    }

    /**
     * Parses the OMT spelling (case-insensitive, surrounding whitespace ignored).
     *
     * @return the constant, or {@code null} when the text is null, empty or unknown
     */
    public static UpdateType fromOmtName(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        for (UpdateType v : values()) {
            if (v.omtName.equalsIgnoreCase(t)) {
                return v;
            }
        }
        return null;
    }
}
