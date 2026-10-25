package com.simweaver.common.iem;

/**
 * Sharing (publish/subscribe capability) of a class or attribute, OMT table column "Sharing".
 */
public enum Sharing {

    PUBLISH_SUBSCRIBE("PublishSubscribe"),
    PUBLISH("Publish"),
    SUBSCRIBE("Subscribe"),
    NEITHER("Neither");

    private final String omtName;

    Sharing(String omtName) {
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
    public static Sharing fromOmtName(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        for (Sharing v : values()) {
            if (v.omtName.equalsIgnoreCase(t)) {
                return v;
            }
        }
        return null;
    }
}
