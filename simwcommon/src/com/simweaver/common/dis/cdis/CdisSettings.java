package com.simweaver.common.dis.cdis;

import com.simweaver.common.config.SimwProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How a C-DIS encoder and decoder work (SISO-STD-023-2024 clause 9): the update mode, the full
 * update multiplier and the DIS heartbeats and timeouts agreed in the federation. Immutable.
 */
public final class CdisSettings {

    /** Full updates only (every PDU complete, no state), or partial updates (only what changed). */
    public enum UpdateMode {
        FULL, PARTIAL;

        /** @return the mode of a setting value ({@code full}, {@code partial}), or null */
        public static UpdateMode fromSetting(String s) {
            if (s == null) {
                return null;
            }
            switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "full":
                    return FULL;
                case "partial":
                    return PARTIAL;
                default:
                    return null;
            }
        }
    }

    /** The defaults: full updates, multiplier 2.4, the DIS 7 heartbeats. */
    public static final CdisSettings DEFAULTS = new CdisSettings(UpdateMode.FULL, 2.4, 2.4,
            5_000, 10_000, 5_000, 2_000, 10_000);

    private final UpdateMode updateMode;
    private final double fullUpdateMultiplier;
    private final double timeoutMultiplier;
    private final long heartbeatEntityState;
    private final long heartbeatEmission;
    private final long heartbeatDesignator;
    private final long heartbeatTransmitter;
    private final long heartbeatIff;

    private CdisSettings(UpdateMode updateMode, double fullUpdateMultiplier, double timeoutMultiplier,
                         long es, long ee, long designator, long transmitter, long iff) {
        this.updateMode = updateMode;
        this.fullUpdateMultiplier = fullUpdateMultiplier;
        this.timeoutMultiplier = timeoutMultiplier;
        this.heartbeatEntityState = es;
        this.heartbeatEmission = ee;
        this.heartbeatDesignator = designator;
        this.heartbeatTransmitter = transmitter;
        this.heartbeatIff = iff;
    }

    public UpdateMode getUpdateMode() {
        return updateMode;
    }

    /** @return HBT_CDIS_FULL_UPDATE_MPLIER: full update period = multiplier × heartbeat */
    public double getFullUpdateMultiplier() {
        return fullUpdateMultiplier;
    }

    /** @return the DIS timeout multiplier: an object is dropped after multiplier × heartbeat of silence */
    public double getTimeoutMultiplier() {
        return timeoutMultiplier;
    }

    /** @return the heartbeat in ms of a stateful PDU type, 0 for others */
    public long heartbeat(int pduType) {
        switch (pduType) {
            case CdisCodec.ENTITY_STATE:
                return heartbeatEntityState;
            case CdisCodec.ELECTROMAGNETIC_EMISSION:
                return heartbeatEmission;
            case CdisCodec.DESIGNATOR:
                return heartbeatDesignator;
            case CdisCodec.TRANSMITTER:
                return heartbeatTransmitter;
            case CdisCodec.IFF:
                return heartbeatIff;
            default:
                return 0;
        }
    }

    /** @return CDIS_FULL_UPDATE_PERIOD of a stateful PDU type, ms */
    public long fullUpdatePeriod(int pduType) {
        return (long) (heartbeat(pduType) * fullUpdateMultiplier);
    }

    /** @return the timeout of a stateful PDU type, ms */
    public long timeout(int pduType) {
        return (long) (heartbeat(pduType) * timeoutMultiplier);
    }

    public CdisSettings withUpdateMode(UpdateMode m) {
        return new CdisSettings(m, fullUpdateMultiplier, timeoutMultiplier, heartbeatEntityState, heartbeatEmission,
                heartbeatDesignator, heartbeatTransmitter, heartbeatIff);
    }

    /** @param m the full update multiplier, above 0 */
    public CdisSettings withFullUpdateMultiplier(double m) {
        if (!(m > 0) || Double.isInfinite(m)) {
            throw new IllegalArgumentException("the full update multiplier must be above 0");
        }
        return new CdisSettings(updateMode, m, timeoutMultiplier, heartbeatEntityState, heartbeatEmission,
                heartbeatDesignator, heartbeatTransmitter, heartbeatIff);
    }

    /** @param m the timeout multiplier, above 0 */
    public CdisSettings withTimeoutMultiplier(double m) {
        if (!(m > 0) || Double.isInfinite(m)) {
            throw new IllegalArgumentException("the timeout multiplier must be above 0");
        }
        return new CdisSettings(updateMode, fullUpdateMultiplier, m, heartbeatEntityState, heartbeatEmission,
                heartbeatDesignator, heartbeatTransmitter, heartbeatIff);
    }

    /**
     * @param pduType one of the stateful PDU types
     * @param millis  the heartbeat, above 0
     */
    public CdisSettings withHeartbeat(int pduType, long millis) {
        if (millis <= 0) {
            throw new IllegalArgumentException("a heartbeat must be above 0");
        }
        long es = heartbeatEntityState;
        long ee = heartbeatEmission;
        long des = heartbeatDesignator;
        long tx = heartbeatTransmitter;
        long iff = heartbeatIff;
        switch (pduType) {
            case CdisCodec.ENTITY_STATE:
                es = millis;
                break;
            case CdisCodec.ELECTROMAGNETIC_EMISSION:
                ee = millis;
                break;
            case CdisCodec.DESIGNATOR:
                des = millis;
                break;
            case CdisCodec.TRANSMITTER:
                tx = millis;
                break;
            case CdisCodec.IFF:
                iff = millis;
                break;
            default:
                throw new IllegalArgumentException("PDU type " + pduType + " has no heartbeat");
        }
        return new CdisSettings(updateMode, fullUpdateMultiplier, timeoutMultiplier, es, ee, des, tx, iff);
    }

    /** The heartbeat setting names, by PDU type. */
    private static final Map<String, Integer> HEARTBEAT_KEYS = new LinkedHashMap<String, Integer>();

    static {
        HEARTBEAT_KEYS.put("entitystate", CdisCodec.ENTITY_STATE);
        HEARTBEAT_KEYS.put("emission", CdisCodec.ELECTROMAGNETIC_EMISSION);
        HEARTBEAT_KEYS.put("designator", CdisCodec.DESIGNATOR);
        HEARTBEAT_KEYS.put("transmitter", CdisCodec.TRANSMITTER);
        HEARTBEAT_KEYS.put("iff", CdisCodec.IFF);
    }

    /**
     * Reads the settings under a prefix, e.g. {@code endpoint.cdis.}:
     * <pre>
     * updatemode=full                 full (default) or partial
     * fullupdatemultiplier=2.4        full update period = multiplier x heartbeat (partial mode)
     * timeoutmultiplier=2.4           objects are forgotten after multiplier x heartbeat of silence
     * heartbeat.entitystate=5         seconds; also .emission (10), .designator (5),
     *                                 .transmitter (2), .iff (10)
     * </pre>
     *
     * @param errors receives a message for each value that cannot be used (its default is kept)
     */
    public static CdisSettings fromProperties(SimwProperties p, String prefix, List<String> errors) {
        CdisSettings s = DEFAULTS;
        String mode = p.getString(prefix + "updatemode", "full");
        UpdateMode m = UpdateMode.fromSetting(mode);
        if (m == null) {
            errors.add(prefix + "updatemode: '" + mode + "' is not full or partial");
        } else {
            s = s.withUpdateMode(m);
        }
        double fm = p.getDouble(prefix + "fullupdatemultiplier", s.fullUpdateMultiplier);
        if (fm > 0 && fm <= 1000) {
            s = s.withFullUpdateMultiplier(fm);
        } else {
            errors.add(prefix + "fullupdatemultiplier must be above 0 (and at most 1000)");
        }
        double tm = p.getDouble(prefix + "timeoutmultiplier", s.timeoutMultiplier);
        if (tm > 0 && tm <= 1000) {
            s = s.withTimeoutMultiplier(tm);
        } else {
            errors.add(prefix + "timeoutmultiplier must be above 0 (and at most 1000)");
        }
        for (Map.Entry<String, Integer> e : HEARTBEAT_KEYS.entrySet()) {
            double secs = p.getDouble(prefix + "heartbeat." + e.getKey(), s.heartbeat(e.getValue()) / 1000.0);
            if (secs >= 0.1 && secs <= 86400) {
                s = s.withHeartbeat(e.getValue(), Math.round(secs * 1000));
            } else {
                errors.add(prefix + "heartbeat." + e.getKey() + " must be 0.1 to 86400 seconds");
            }
        }
        return s;
    }

    /** @return the settings for a status report (heartbeats in seconds) */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("updatemode", updateMode.name().toLowerCase(Locale.ROOT));
        m.put("fullupdatemultiplier", fullUpdateMultiplier);
        m.put("timeoutmultiplier", timeoutMultiplier);
        Map<String, Object> hb = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Integer> e : HEARTBEAT_KEYS.entrySet()) {
            hb.put(e.getKey(), heartbeat(e.getValue()) / 1000.0);
        }
        m.put("heartbeats", hb);
        return m;
    }

    @Override
    public String toString() {
        return updateMode.name().toLowerCase(Locale.ROOT) + " updates, full update multiplier " + fullUpdateMultiplier
                + ", timeout multiplier " + timeoutMultiplier + ", heartbeats ES " + heartbeatEntityState
                + " / EE " + heartbeatEmission + " / designator " + heartbeatDesignator + " / transmitter "
                + heartbeatTransmitter + " / IFF " + heartbeatIff + " ms";
    }
}
