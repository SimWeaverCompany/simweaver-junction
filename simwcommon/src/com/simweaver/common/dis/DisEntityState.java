package com.simweaver.common.dis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The fields of an Entity State PDU (DIS 6 and 7, type 1). An Entity State Update PDU (DIS 7,
 * type 67) is applied on top of the last full state with {@link #withUpdate}.
 */
public final class DisEntityState {

    /** Size of the fixed part of the Entity State PDU, header included. */
    public static final int BASE_SIZE = 144;
    /** Size of the fixed part of the Entity State Update PDU, header included. */
    public static final int UPDATE_BASE_SIZE = 72;

    public final DisEntityId id;
    public final int forceId;
    public final DisEntityType type;
    public final DisEntityType alternateType;
    public final float[] velocity;          // m/s, world coordinates
    public final double[] location;         // m, geocentric (ECEF)
    public final float[] orientation;       // psi, theta, phi in rad
    public final int appearance;
    public final int drAlgorithm;
    public final float[] drAcceleration;    // m/s^2
    public final float[] drAngularVelocity; // rad/s
    public final int markingCharset;
    public final byte[] marking;            // 11 octets
    public final int capabilities;
    public final List<DisArticulation> articulations;

    private DisEntityState(DisEntityId id, int forceId, DisEntityType type, DisEntityType alternateType, float[] velocity,
                           double[] location, float[] orientation, int appearance, int drAlgorithm, float[] drAcceleration,
                           float[] drAngularVelocity, int markingCharset, byte[] marking, int capabilities,
                           List<DisArticulation> articulations) {
        this.id = id;
        this.forceId = forceId;
        this.type = type;
        this.alternateType = alternateType;
        this.velocity = velocity;
        this.location = location;
        this.orientation = orientation;
        this.appearance = appearance;
        this.drAlgorithm = drAlgorithm;
        this.drAcceleration = drAcceleration;
        this.drAngularVelocity = drAngularVelocity;
        this.markingCharset = markingCharset;
        this.marking = marking;
        this.capabilities = capabilities;
        this.articulations = articulations;
    }

    /** Reads an Entity State PDU; the reader is positioned right after the 12-octet header. */
    public static DisEntityState read(DisReader r) {
        DisEntityId id = DisEntityId.read(r);
        int force = r.u8();
        int n = r.u8();
        DisEntityType type = DisEntityType.read(r);
        DisEntityType alt = DisEntityType.read(r);
        float[] vel = {r.f32(), r.f32(), r.f32()};
        double[] loc = {r.f64(), r.f64(), r.f64()};
        float[] ori = {r.f32(), r.f32(), r.f32()};
        int appearance = r.i32();
        int alg = r.u8();
        r.skip(15);                                            // other DR parameters
        float[] acc = {r.f32(), r.f32(), r.f32()};
        float[] ang = {r.f32(), r.f32(), r.f32()};
        int charset = r.u8();
        byte[] marking = r.bytes(11);
        int capabilities = r.i32();
        List<DisArticulation> arts = readArticulations(r, n);
        return new DisEntityState(id, force, type, alt, vel, loc, ori, appearance, alg, acc, ang, charset, marking,
                capabilities, arts);
    }

    /** The entity id of an Entity State Update PDU; the reader is positioned right after the header. */
    public static DisEntityId readUpdateId(DisReader r) {
        return DisEntityId.read(r);
    }

    /**
     * This state with an Entity State Update PDU applied (velocity, location, orientation,
     * appearance and variable parameters); the reader is positioned right after the header.
     */
    public DisEntityState withUpdate(DisReader r) {
        DisEntityId uid = DisEntityId.read(r);
        if (!uid.equals(id)) throw new IllegalArgumentException("update for " + uid + " applied to " + id);
        r.skip(1);                                             // padding
        int n = r.u8();
        float[] vel = {r.f32(), r.f32(), r.f32()};
        double[] loc = {r.f64(), r.f64(), r.f64()};
        float[] ori = {r.f32(), r.f32(), r.f32()};
        int app = r.i32();
        List<DisArticulation> arts = readArticulations(r, n);
        return new DisEntityState(id, forceId, type, alternateType, vel, loc, ori, app, drAlgorithm, drAcceleration,
                drAngularVelocity, markingCharset, marking, capabilities, arts);
    }

    private static List<DisArticulation> readArticulations(DisReader r, int n) {
        if (n == 0) return Collections.emptyList();
        if (r.remaining() < n * DisArticulation.SIZE) {
            throw new DisFormatException(n + " articulation records announced, room for " + r.remaining() / DisArticulation.SIZE);
        }
        List<DisArticulation> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(DisArticulation.read(r));
        return Collections.unmodifiableList(out);
    }

    /** Appearance bits {@code from..from+width-1}. */
    public int appearanceBits(int from, int width) {
        return (appearance >>> from) & ((1 << width) - 1);
    }

    /** Appearance "Frozen status" (bit 21). */
    public boolean isFrozen() {
        return appearanceBits(21, 1) != 0;
    }

    /** Appearance "State" (bit 23): the entity is deactivated, i.e. leaves the exercise. */
    public boolean isDeactivated() {
        return appearanceBits(23, 1) != 0;
    }
}
