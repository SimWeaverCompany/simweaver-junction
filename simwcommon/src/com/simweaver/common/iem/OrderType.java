package com.simweaver.common.iem;

/**
 * Message delivery order, OMT column "Order".
 */
public enum OrderType {

    RECEIVE("Receive"),
    TIMESTAMP("TimeStamp");

    private final String omtName;

    OrderType(String omtName) {
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
    public static OrderType fromOmtName(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        for (OrderType v : values()) {
            if (v.omtName.equalsIgnoreCase(t)) {
                return v;
            }
        }
        return null;
    }
}
