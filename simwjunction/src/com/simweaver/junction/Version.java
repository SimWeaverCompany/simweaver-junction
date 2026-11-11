package com.simweaver.junction;

/** Product name and version of SimWeaver Junction. */
public final class Version {

    /** Product name. */
    public static final String PRODUCT_NAME = "SimWeaver Junction";

    private static final String VERSION = "0.13.0";

    private Version() {
    }

    /** @return the version: the jar manifest's Implementation-Version of this package, else the built-in one */
    public static String getVersion() {
        Package p = Version.class.getPackage();
        String v = p == null ? null : p.getImplementationVersion();
        return v == null || v.isEmpty() ? VERSION : v;
    }
}
