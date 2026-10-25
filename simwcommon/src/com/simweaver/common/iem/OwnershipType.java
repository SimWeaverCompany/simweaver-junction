package com.simweaver.common.iem;

/**
 * Attribute ownership transfer capability, OMT attribute table column "D/A".
 */
public enum OwnershipType {

    DIVEST("Divest"),
    ACQUIRE("Acquire"),
    DIVEST_ACQUIRE("DivestAcquire"),
    NO_TRANSFER("NoTransfer");

    private final String omtName;

    OwnershipType(String omtName) {
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
    public static OwnershipType fromOmtName(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        for (OwnershipType v : values()) {
            if (v.omtName.equalsIgnoreCase(t)) {
                return v;
            }
        }
        return null;
    }
}
