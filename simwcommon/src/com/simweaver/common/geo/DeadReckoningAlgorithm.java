package com.simweaver.common.geo;

import java.util.Locale;

/**
 * Dead reckoning algorithms of IEEE 1278.1 (DIS) Annex E, with the DIS / SISO-REF-010
 * codes (UID 44) and the RPR-FOM {@code DeadReckoningAlgorithmEnum8} enumerator names.
 * <p>
 * Letter codes: F = fixed orientation, R = rotating (orientation extrapolated with the
 * angular velocity); P = position from velocity only, V = velocity and position from
 * velocity and acceleration; W = linear vectors in world coordinates, B = in body coordinates.
 */
public enum DeadReckoningAlgorithm {

    /** 0: other / unknown; no extrapolation. */
    OTHER(0, "Other", false, false, false, false),
    /** 1: static, never moves. */
    STATIC(1, "Static", false, false, false, false),
    /** 2: fixed orientation, constant velocity, world. */
    FPW(2, "DRM_FPW", true, false, false, false),
    /** 3: rotating, constant velocity, world. */
    RPW(3, "DRM_RPW", true, true, false, false),
    /** 4: rotating, constant acceleration, world. */
    RVW(4, "DRM_RVW", true, true, true, false),
    /** 5: fixed orientation, constant acceleration, world. */
    FVW(5, "DRM_FVW", true, false, true, false),
    /** 6: fixed orientation, constant velocity, body. */
    FPB(6, "DRM_FPB", true, false, false, true),
    /** 7: rotating, constant velocity, body. */
    RPB(7, "DRM_RPB", true, true, false, true),
    /** 8: rotating, constant acceleration, body. */
    RVB(8, "DRM_RVB", true, true, true, true),
    /** 9: fixed orientation, constant acceleration, body. */
    FVB(9, "DRM_FVB", true, false, true, true);

    private static final DeadReckoningAlgorithm[] BY_CODE = values();

    private final int code;
    private final String rprName;
    private final boolean moving;
    private final boolean rotating;
    private final boolean usingAcceleration;
    private final boolean bodyAxis;

    DeadReckoningAlgorithm(int code, String rprName, boolean moving, boolean rotating,
                           boolean usingAcceleration, boolean bodyAxis) {
        this.code = code;
        this.rprName = rprName;
        this.moving = moving;
        this.rotating = rotating;
        this.usingAcceleration = usingAcceleration;
        this.bodyAxis = bodyAxis;
    }

    /** @return DIS / RPR numeric code, 0..9 */
    public int getCode() {
        return code;
    }

    /** @return RPR enumerator name, e.g. {@code "DRM_FPW"}, {@code "Static"} */
    public String getRprName() {
        return rprName;
    }

    /** @return true for algorithms 2..9 (position is extrapolated) */
    public boolean isMoving() {
        return moving;
    }

    /** @return true when the orientation is extrapolated with the angular velocity (R) */
    public boolean isRotating() {
        return rotating;
    }

    /** @return true when the linear acceleration is used (V) */
    public boolean isUsingAcceleration() {
        return usingAcceleration;
    }

    /** @return true for body axis algorithms (B, codes 6..9) */
    public boolean isBodyAxis() {
        return bodyAxis;
    }

    /**
     * @param code numeric code
     * @return the algorithm, or null for a code outside 0..9
     */
    public static DeadReckoningAlgorithm fromCode(int code) {
        return code >= 0 && code < BY_CODE.length ? BY_CODE[code] : null;
    }

    /**
     * Lenient name lookup: accepts {@code DRM_FPW}, {@code FPW}, {@code drm-fpw},
     * {@code Static}, {@code Other}, and the numeric code as text.
     *
     * @param name name
     * @return the algorithm, or null when not recognised
     */
    public static DeadReckoningAlgorithm fromName(String name) {
        if (name == null) {
            return null;
        }
        String n = name.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (n.isEmpty()) {
            return null;
        }
        if (n.chars().allMatch(Character::isDigit) && n.length() <= 3) {
            return fromCode(Integer.parseInt(n));
        }
        if (n.startsWith("DRM_")) {
            n = n.substring(4);
        }
        for (DeadReckoningAlgorithm a : BY_CODE) {
            if (a.name().equals(n)) {
                return a;
            }
        }
        return null;
    }
}
