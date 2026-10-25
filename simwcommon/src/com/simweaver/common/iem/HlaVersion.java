package com.simweaver.common.iem;

/**
 * The HLA specification version a set of FOM modules is written for.
 * An {@link InformationExchangeModel} is always built from modules of a single version.
 */
public enum HlaVersion {

    /** IEEE 1516-2010, "HLA Evolved". */
    HLA_EVOLVED("IEEE 1516-2010", "http://standards.ieee.org/IEEE1516-2010"),

    /** IEEE 1516-2025, "HLA 4". */
    HLA_4("IEEE 1516-2025", "http://standards.ieee.org/IEEE1516-2025");

    private final String standard;
    private final String xmlNamespace;

    HlaVersion(String standard, String xmlNamespace) {
        this.standard = standard;
        this.xmlNamespace = xmlNamespace;
    }

    /** E.g. {@code "IEEE 1516-2010"}. */
    public String getStandard() {
        return standard;
    }

    /** XML namespace of the OMT Data Interchange Format (DIF) for this version. */
    public String getXmlNamespace() {
        return xmlNamespace;
    }

    /** The version whose DIF namespace equals {@code ns}, or {@code null}. */
    public static HlaVersion fromNamespace(String ns) {
        if (ns == null) {
            return null;
        }
        for (HlaVersion v : values()) {
            if (v.xmlNamespace.equals(ns.trim())) {
                return v;
            }
        }
        return null;
    }
}
