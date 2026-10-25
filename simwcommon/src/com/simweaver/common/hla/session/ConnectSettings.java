package com.simweaver.common.hla.session;

/**
 * How to connect to an RTI. Version neutral: an IEEE 1516-2010 session uses
 * {@link #getLocalSettingsDesignator()}; an IEEE 1516-2025 session builds an
 * {@code RtiConfiguration} from the designator (as configuration name), the RTI address and
 * the additional settings - the Federate Protocol clients take their server address and
 * transport from there.
 *
 * <p>Immutable.
 */
public final class ConnectSettings {

    private final String localSettingsDesignator;
    private final String rtiAddress;
    private final String additionalSettings;
    private final String factoryName;

    private ConnectSettings(String lsd, String rtiAddress, String additionalSettings, String factoryName) {
        this.localSettingsDesignator = blankToNull(lsd);
        this.rtiAddress = blankToNull(rtiAddress);
        this.additionalSettings = blankToNull(additionalSettings);
        this.factoryName = blankToNull(factoryName);
    }

    /** @return settings that use the RTI's defaults */
    public static ConnectSettings defaults() {
        return new ConnectSettings(null, null, null, null);
    }

    /**
     * @param lsd 1516-2010 local settings designator / 1516-2025 configuration name; null or "" = RTI default
     * @return settings with only the designator
     */
    public static ConnectSettings of(String lsd) {
        return new ConnectSettings(lsd, null, null, null);
    }

    /** @return a copy with the 1516-2025 RTI address (e.g. a Federate Protocol server "host:15164") */
    public ConnectSettings withRtiAddress(String address) {
        return new ConnectSettings(localSettingsDesignator, address, additionalSettings, factoryName);
    }

    /** @return a copy with 1516-2025 additional settings (vendor specific text) */
    public ConnectSettings withAdditionalSettings(String settings) {
        return new ConnectSettings(localSettingsDesignator, rtiAddress, settings, factoryName);
    }

    /** @return a copy that picks the {@code RtiFactory} with this {@code rtiName()} (null = the first one found) */
    public ConnectSettings withFactoryName(String name) {
        return new ConnectSettings(localSettingsDesignator, rtiAddress, additionalSettings, name);
    }

    /** @return the local settings designator / configuration name, or null */
    public String getLocalSettingsDesignator() {
        return localSettingsDesignator;
    }

    /** @return the RTI address (1516-2025), or null */
    public String getRtiAddress() {
        return rtiAddress;
    }

    /** @return the additional settings (1516-2025), or null */
    public String getAdditionalSettings() {
        return additionalSettings;
    }

    /** @return the RtiFactory name, or null */
    public String getFactoryName() {
        return factoryName;
    }

    private static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(localSettingsDesignator == null ? "(default settings)" : localSettingsDesignator);
        if (rtiAddress != null) {
            sb.append(" @").append(rtiAddress);
        }
        if (factoryName != null) {
            sb.append(" [").append(factoryName).append(']');
        }
        return sb.toString();
    }
}
