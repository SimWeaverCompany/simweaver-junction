package com.simweaver.junction.config;

import java.util.Locale;

/**
 * The kinds of endpoint a gateway can have. HLA kinds share one implementation (over
 * {@code common.hla.session}); DIS kinds present themselves to the router as RPR FOM v2.0.
 */
public enum EndpointType {

    /** IEEE 1516-2010 (HLA Evolved). Setting: {@code ieee1516-2010}. */
    HLA3("ieee1516-2010", "HLA Evolved (IEEE 1516-2010)", true, false),
    /** IEEE 1516-2025 (HLA 4). Setting: {@code ieee1516-2025}. */
    HLA4("ieee1516-2025", "HLA 4 (IEEE 1516-2025)", true, true),
    /** IEEE 1516-2025 over the Federate Protocol (the vendor's FedPro client). Setting: {@code ieee1516-2025-fedpro}. */
    HLA4_FEDPRO("ieee1516-2025-fedpro", "HLA 4 Federate Protocol", true, true),
    /** DIS 6 (IEEE 1278.1a-1998). Setting: {@code dis6}. */
    DIS6("dis6", "DIS 6 (IEEE 1278.1a-1998)", false, false),
    /** DIS 7 (IEEE 1278.1-2012). Setting: {@code dis7}. */
    DIS7("dis7", "DIS 7 (IEEE 1278.1-2012)", false, false),
    /**
     * Compressed DIS (SISO-STD-023-2024) on the network, a DIS 7 endpoint inside: PDUs are
     * encoded to C-DIS when sent and decoded to DIS 7 when heard. Setting: {@code cdis}.
     */
    CDIS("cdis", "C-DIS (SISO-STD-023-2024)", false, false),
    /** A Talk federation (HLA, by default 1516-2010). Setting: {@code talk}. */
    TALK("talk", "Talk (HLA)", true, false),
    /**
     * A session of the J2J network (0.10.0): gateways on other SimWeaver servers sharing DIS PDUs
     * as they are, for the PDU relay ({@code router.kind=pdu}). Setting: {@code j2j}.
     */
    J2J("j2j", "J2J session", false, false);

    private final String setting;
    private final String label;
    private final boolean hla;
    private final boolean hla4;

    EndpointType(String setting, String label, boolean hla, boolean hla4) {
        this.setting = setting;
        this.label = label;
        this.hla = hla;
        this.hla4 = hla4;
    }

    /** @return the value of {@code endpoint.standard} */
    public String setting() {
        return setting;
    }

    /** @return human readable name */
    public String label() {
        return label;
    }

    /** @return true for the kinds that need an HLA RTI */
    public boolean isHla() {
        return hla;
    }

    /** @return true for DIS (C-DIS included: a DIS 7 endpoint with C-DIS on the network) */
    public boolean isDis() {
        return this == DIS6 || this == DIS7 || this == CDIS;
    }

    /** @return true for a J2J session endpoint */
    public boolean isJ2j() {
        return this == J2J;
    }

    /** @return true for the kinds a PDU relay takes: DIS 6, DIS 7, C-DIS and J2J sessions */
    public boolean isPduPort() {
        return isDis() || this == J2J;
    }

    /** @return true for C-DIS */
    public boolean isCdis() {
        return this == CDIS;
    }

    /** @return true when the HLA API is IEEE 1516-2025 (TALK: see EndpointConfig#isHla4) */
    public boolean isHla4() {
        return hla4;
    }

    /**
     * @param s a setting, case and '_' / '-' insensitive; aliases: hla3, hlaevolved, hla4, fedpro, hla4-fedpro, c-dis
     * @return the type, or null
     */
    public static EndpointType fromSetting(String s) {
        if (s == null) {
            return null;
        }
        String n = s.trim().toLowerCase(Locale.ROOT).replace('_', '-').replace(" ", "");
        switch (n) {
            case "ieee1516-2010":
            case "hla3":
            case "hlaevolved":
            case "1516e":
                return HLA3;
            case "ieee1516-2025":
            case "hla4":
                return HLA4;
            case "ieee1516-2025-fedpro":
            case "hla4-fedpro":
            case "fedpro":
                return HLA4_FEDPRO;
            case "dis6":
                return DIS6;
            case "dis7":
                return DIS7;
            case "cdis":
            case "c-dis":
                return CDIS;
            case "talk":
            case "pitchtalk":
                return TALK;
            case "j2j":
                return J2J;
            default:
                return null;
        }
    }
}
