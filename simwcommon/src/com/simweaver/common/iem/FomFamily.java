package com.simweaver.common.iem;

/**
 * Well-known FOM families recognised from a module's identification table.
 * Recognition is by module name (and version); it never changes how a module is merged,
 * it only drives diagnostics and the convenience queries on {@link InformationExchangeModel}.
 */
public enum FomFamily {

    /** The HLA Management Object Model / standard MIM (built-in or supplied). */
    STANDARD_MIM,

    /** SISO-STD-001.1-2015 Real-time Platform Reference FOM v2.0 (modular or monolithic). */
    RPR_FOM_V2,

    /** SISO-STD-001.1-2025 Real-time Platform Reference FOM v3.0. */
    RPR_FOM_V3,

    /** NATO Education and Training Network FOM modules (NETN-*). */
    NETN,

    /** Anything else. */
    OTHER;

    /**
     * Classifies a module from its identification name and version.
     *
     * @param name    modelIdentification/name, may be null
     * @param version modelIdentification/version, may be null
     */
    static FomFamily classify(String name, String version) {
        String n = name == null ? "" : name.trim();
        String lower = n.toLowerCase(java.util.Locale.ROOT);
        String v = version == null ? "" : version.trim().toLowerCase(java.util.Locale.ROOT);
        if (v.startsWith("v")) {
            v = v.substring(1);
        }
        if (lower.startsWith("netn")) {
            return NETN;
        }
        if (lower.contains("siso-std-001.1-2025")) {
            return RPR_FOM_V3;
        }
        if (lower.contains("siso-std-001.1-2015")) {
            return RPR_FOM_V2;
        }
        boolean rpr = lower.contains("real-time platform reference") || lower.startsWith("rpr");
        if (rpr) {
            if (v.startsWith("3")) {
                return RPR_FOM_V3;
            }
            if (v.startsWith("2")) {
                return RPR_FOM_V2;
            }
        }
        if (lower.equals("mim") || lower.contains("hlastandardmim") || lower.contains("management object model")
                || lower.contains("standard mim")) {
            return STANDARD_MIM;
        }
        return OTHER;
    }
}
