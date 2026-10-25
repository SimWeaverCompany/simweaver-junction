package com.simweaver.common.hla;

import com.simweaver.common.hla.implHla3.FederationProbeHla3;
import com.simweaver.common.hla.implHla4.FederationProbeHla4;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Creates the federation probes. Tolerant of a missing RTI library: when the HLA RTI API for a
 * standard is not on the class path, the create method returns null (as for DIS) and logs this
 * once, instead of failing with NoClassDefFoundError.
 *
 * <p>Each RTI-based probe is created in its own nested holder class. The JVM loads (and
 * verifies) a holder only when it is first used, inside the try below, so this factory itself
 * never needs the RTI classes - also not for {@link #createDis6} etc.
 */
public class FederationProbeFactory {

    private static final Logger LOG = Logger.getLogger(FederationProbeFactory.class.getName());
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    /** HLA 1516-2010 probe, or null when the hla.rti1516e library is not on the class path. */
    public static FederationProbe createHla3(String lsd) {
        if (!RtiAvailability.HLA_EVOLVED) return unavailable("IEEE 1516-2010", "hla.rti1516e", null);
        try {
            return Hla3.create(lsd);
        } catch (LinkageError e) {                  // API present, but the RTI library is incomplete
            return unavailable("IEEE 1516-2010", "hla.rti1516e", e);
        }
    }

    /** HLA 4 (1516-2025) probe, or null when the hla.rti1516_2025 library is not on the class path. */
    public static FederationProbe createHla4(String lsd) {
        if (!RtiAvailability.HLA_4) return unavailable("IEEE 1516-2025", "hla.rti1516_2025", null);
        try {
            return Hla4.create(lsd);
        } catch (LinkageError e) {
            return unavailable("IEEE 1516-2025", "hla.rti1516_2025", e);
        }
    }

    public static FederationProbe createDis6(String config) {
        return null;
    }

    public static FederationProbe createDis7(String config) {
        return null;
    }

    /** C-DIS (0.9.18): no probe, as for DIS. */
    public static FederationProbe createCdis(String config) {
        return null;
    }

    public static FederationProbe createMisc(String config) {
        return null;
    }

    private static FederationProbe unavailable(String standard, String api, LinkageError cause) {
        if (WARNED.add(standard)) {
            LOG.warning("No HLA RTI library for " + standard + " (" + api + ") on the class path: "
                    + "no federation probe for those configurations" + (cause == null ? "" : " (" + cause + ")"));
        }
        return null;
    }

    /** Loaded only when createHla3 calls it. */
    private static final class Hla3 {
        static FederationProbe create(String lsd) {
            return new FederationProbeHla3(lsd);
        }
    }

    /** Loaded only when createHla4 calls it. */
    private static final class Hla4 {
        static FederationProbe create(String lsd) {
            return new FederationProbeHla4(lsd);
        }
    }
}
