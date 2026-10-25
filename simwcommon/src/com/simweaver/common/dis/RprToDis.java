package com.simweaver.common.dis;

import com.simweaver.common.hla.codec.HValue;

import java.util.List;
import java.util.function.Function;

/**
 * NEW (Junktion): the reverse of {@link DisToRpr}: RPR FOM v2.0 objects and interactions to DIS
 * PDUs. Values come as decoded {@link HValue}s (see {@code HlaCodec}), looked up by attribute or
 * parameter name; a missing value is written as 0 (or the DIS "none"), so a partly known object
 * still gives a valid PDU.
 *
 * <p>Object references (RTIobjectId) are resolved to DIS entity ids by a {@link Names} the
 * caller provides: the names of the objects it sends (and has seen) as DIS entities. Names in
 * the {@code "site-application-entity"} form resolve to themselves.
 *
 * <p>The PDUs follow IEEE 1278.1 (DIS 6: 1278.1a-1998, DIS 7: 1278.1-2012) with the sizes of the
 * fixed parts: Entity State 144 + 16 per articulation record, Fire 96, Detonation 104 + 16n,
 * Collision 60, Transmitter 104 (no modulation parameters, no antenna pattern), Signal 32 +
 * data (padded to 32 bits), Receiver 36, Start/Resume 44, Stop/Freeze 40, Acknowledge 32,
 * Electromagnetic Emission 28 + per system 20 + per beam 52 + 8 per track/jam target,
 * Designator 88, IFF 60 (layer 1) + 28 + 24 per fundamental parameter set (layer 2).
 *
 * <p>Thread safe (no state apart from the settings).
 */
public final class RprToDis {

    /** Resolves object instance names to DIS entity ids. */
    public interface Names {
        /** @return the entity id the named object has in DIS, or null when unknown */
        DisEntityId entity(String objectName);
    }

    // protocol families
    private static final int FAMILY_ENTITY_INFORMATION = 1;
    private static final int FAMILY_WARFARE = 2;
    private static final int FAMILY_RADIO = 4;
    private static final int FAMILY_SIMULATION_MANAGEMENT = 5;
    private static final int FAMILY_EMISSION = 6;

    /** DIS limits a PDU to 8192 octets: track/jam targets beyond that are left out. */
    private static final int MAX_PDU = 8192;

    private final int version;
    private final int exercise;
    private final int siteId;
    private final int applicationId;

    /**
     * @param version       6 or 7
     * @param exercise      exercise id written into every PDU, 1-255
     * @param siteId        this application's site (event ids without a known issuer)
     * @param applicationId this application's application number
     */
    public RprToDis(int version, int exercise, int siteId, int applicationId) {
        this.version = version >= 7 ? 7 : 6;
        this.exercise = exercise < 1 || exercise > 255 ? 1 : exercise;
        this.siteId = siteId;
        this.applicationId = applicationId;
    }

    public int version() {
        return version;
    }

    public int exercise() {
        return exercise;
    }

    private DisWriter header(int type, int family, long now, int capacity) {
        return new DisWriter(capacity).header(version, exercise, type, family, DisWriter.timestamp(now, false), 0);
    }

    // ================================================================== entities

    /**
     * Whether an entity has what an Entity State PDU needs: its EntityType and its Spatial.
     *
     * @param v attribute values by name
     */
    public static boolean canSendEntity(Function<String, HValue> v) {
        return v.apply("EntityType") != null && v.apply("Spatial") != null;
    }

    /**
     * Entity State PDU (type 1) of a BaseEntity (or subclass) object.
     *
     * @param id          the entity id to send it as
     * @param v           attribute values by name
     * @param deactivated sets appearance bit 23: the entity leaves the exercise
     * @param now         time of the PDU (epoch milliseconds)
     */
    public byte[] entityState(DisEntityId id, Function<String, HValue> v, boolean deactivated, long now) {
        DisEntityType type = entityType(v.apply("EntityType"));
        HValue spatial = v.apply("Spatial");
        HValue alt = spatial == null ? null : spatial.getAlternative();
        int algorithm = spatial == null ? 1 : (int) spatial.asLong();
        boolean frozen = alt != null && truth(alt.field("IsFrozen"));
        HValue arts = v.apply("ArticulatedParametersArray");
        int n = countArticulations(arts);

        int[] bits = DisAppearance.toBits(type, name -> {
            HValue x = v.apply(name);
            return x == null || !x.isNumeric() ? null : x.asLong();
        }, frozen);
        int appearance = bits[0] | (deactivated ? 1 << 23 : 0);

        DisWriter w = header(DisPduHeader.ENTITY_STATE, FAMILY_ENTITY_INFORMATION, now, DisEntityState.BASE_SIZE + 16 * n);
        w.entityId(id);
        w.u8(intOf(v.apply("ForceIdentifier")));
        w.u8(n);
        w.entityType(type);
        HValue altType = v.apply("AlternateEntityType");
        w.entityType(altType == null ? type : entityType(altType));
        w.f32x3(float3(alt == null ? null : alt.field("VelocityVector")));
        w.f64x3(double3(alt == null ? null : alt.field("WorldLocation")));
        w.f32x3(float3(alt == null ? null : alt.field("Orientation")));
        w.i32(appearance);
        w.u8(algorithm < 0 || algorithm > 255 ? 0 : algorithm);
        w.zeros(15);                                              // other DR parameters
        w.f32x3(float3(alt == null ? null : alt.field("AccelerationVector")));
        w.f32x3(float3(alt == null ? null : alt.field("AngularVelocity")));
        HValue marking = v.apply("Marking");
        w.u8(marking == null ? 1 : intOf(marking.field("MarkingEncodingType")));
        w.bytes(java.util.Arrays.copyOf(marking == null ? new byte[0] : textBytes(marking.field("MarkingData")), 11));
        w.i32(bits[1]);
        writeArticulations(w, arts);
        return w.finish();
    }

    // ================================================================== warfare

    /** Fire PDU (type 2) from a WeaponFire interaction. */
    public byte[] fire(Function<String, HValue> p, Names names, long now) {
        DisEntityId firing = ref(p.apply("FiringObjectIdentifier"), names);
        DisWriter w = header(DisPduHeader.FIRE, FAMILY_WARFARE, now, 96);
        w.entityId(firing);
        w.entityId(ref(p.apply("TargetObjectIdentifier"), names));
        w.entityId(ref(p.apply("MunitionObjectIdentifier"), names));
        eventId(w, p.apply("EventIdentifier"), firing, names);
        w.u32(longOf(p.apply("FireMissionIndex")));
        w.f64x3(double3(p.apply("FiringLocation")));
        burst(w, p);
        w.f32x3(float3(p.apply("InitialVelocityVector")));
        w.f32((float) doubleOf(p.apply("FireControlSolutionRange")));
        return w.finish();
    }

    /** Detonation PDU (type 3) from a MunitionDetonation interaction. */
    public byte[] detonation(Function<String, HValue> p, Names names, long now) {
        DisEntityId firing = ref(p.apply("FiringObjectIdentifier"), names);
        HValue arts = p.apply("ArticulatedPartData");
        int n = countArticulations(arts);
        DisWriter w = header(DisPduHeader.DETONATION, FAMILY_WARFARE, now, 104 + 16 * n);
        w.entityId(firing);
        w.entityId(ref(p.apply("TargetObjectIdentifier"), names));
        w.entityId(ref(p.apply("MunitionObjectIdentifier"), names));
        eventId(w, p.apply("EventIdentifier"), firing, names);
        w.f32x3(float3(p.apply("FinalVelocityVector")));
        w.f64x3(double3(p.apply("DetonationLocation")));
        burst(w, p);
        w.f32x3(float3(p.apply("RelativeDetonationLocation")));
        w.u8(intOf(p.apply("DetonationResultCode")));
        w.u8(n);
        w.u16(0);
        writeArticulations(w, arts);
        return w.finish();
    }

    /** Collision PDU (type 4) from a Collision interaction. */
    public byte[] collision(Function<String, HValue> p, Names names, long now) {
        DisEntityId issuing = ref(p.apply("IssuingObjectIdentifier"), names);
        DisWriter w = header(DisPduHeader.COLLISION, FAMILY_ENTITY_INFORMATION, now, 60);
        w.entityId(issuing);
        w.entityId(ref(p.apply("CollidingObjectIdentifier"), names));
        eventId(w, p.apply("EventIdentifier"), issuing, names);
        w.u8(intOf(p.apply("CollisionType")));
        w.u8(0);
        w.f32x3(float3(p.apply("IssuingObjectVelocityVector")));
        w.f32((float) doubleOf(p.apply("IssuingObjectMass")));
        w.f32x3(float3(p.apply("CollisionLocation")));
        return w.finish();
    }

    /** Munition descriptor: munition type, warhead, fuse, quantity, rate (16 octets). */
    private static void burst(DisWriter w, Function<String, HValue> p) {
        w.entityType(entityType(p.apply("MunitionType")));
        w.u16(intOf(p.apply("WarheadType")));
        w.u16(intOf(p.apply("FuseType")));
        w.u16(intOf(p.apply("QuantityFired")));
        w.u16(intOf(p.apply("RateOfFire")));
    }

    /** Event id: site and application of the issuer (else this application's), and the event count. */
    private void eventId(DisWriter w, HValue eventIdentifier, DisEntityId fallback, Names names) {
        DisEntityId issuer = eventIdentifier == null ? null : names.entity(eventIdentifier.field("IssuingObjectIdentifier") == null
                ? "" : eventIdentifier.field("IssuingObjectIdentifier").asTrimmedText());
        if (issuer == null || issuer.isNone()) {
            issuer = fallback.isNone() ? new DisEntityId(siteId, applicationId, 0) : fallback;
        }
        w.u16(issuer.site).u16(issuer.application);
        w.u16(eventIdentifier == null ? 0 : intOf(eventIdentifier.field("EventCount")));
    }

    // ================================================================== emitters

    /** One emitter system of an entity, with its beams, for {@link #emission}. */
    public static final class EmitterOut {
        public final int emitterId;
        public final Function<String, HValue> values;
        public final List<BeamOut> beams;

        /**
         * @param emitterId the emitter id number on the entity, 1-255
         * @param values    the EmitterSystem's attribute values by name
         * @param beams     its active beams (none: the system is on but radiates nothing)
         */
        public EmitterOut(int emitterId, Function<String, HValue> values, List<BeamOut> beams) {
            this.emitterId = emitterId;
            this.values = values;
            this.beams = beams;
        }
    }

    /** One beam of an emitter system, for {@link #emission}. */
    public static final class BeamOut {
        public final int beamId;
        public final Function<String, HValue> values;
        public final boolean jammer;

        /**
         * @param beamId the beam id number in its system, 1-255
         * @param values the RadarBeam / JammerBeam attribute values by name
         * @param jammer true for a JammerBeam (its targets are JammedObjectIdentifiers)
         */
        public BeamOut(int beamId, Function<String, HValue> values, boolean jammer) {
            this.beamId = beamId;
            this.values = values;
            this.jammer = jammer;
        }
    }

    /**
     * Electromagnetic Emission PDU (type 23) of an entity's EmitterSystem objects and their
     * RadarBeam / JammerBeam objects: the reverse of {@code DisToRpr.emission} (RPR GRIM).
     * Every system and beam of the entity is listed each time, so receivers that treat a
     * changed-data update like a heartbeat see the same state.
     *
     * @param host        the emitting entity
     * @param systems     all its emitter systems (none: it emits nothing any more)
     * @param heartbeat   true: state update indicator 0 (heartbeat), false: 1 (changed data)
     * @param eventNumber the event number of the event id
     */
    public byte[] emission(DisEntityId host, List<EmitterOut> systems, boolean heartbeat, int eventNumber, Names names, long now) {
        int n = Math.min(systems.size(), 255);
        // the targets of each beam, resolved first: they decide the record lengths
        List<List<List<DisEntityId>>> targets = new java.util.ArrayList<>();
        int size = 28;
        for (int si = 0; si < n; si++) {
            EmitterOut sys = systems.get(si);
            List<List<DisEntityId>> perBeam = new java.util.ArrayList<>();
            size += 20;
            for (int bi = 0; bi < Math.min(sys.beams.size(), 255); bi++) {
                BeamOut b = sys.beams.get(bi);
                List<DisEntityId> t = targetIds(b.values.apply(b.jammer ? "JammedObjectIdentifiers" : "TrackObjectIdentifiers"), names);
                perBeam.add(t);
                size += 52 + 8 * t.size();
            }
            targets.add(perBeam);
        }
        while (size > MAX_PDU && trimLongest(targets)) {
            size -= 8;
        }
        DisWriter w = header(DisPduHeader.ELECTROMAGNETIC_EMISSION, FAMILY_EMISSION, now, size);
        w.entityId(host);
        w.u16(host.site).u16(host.application).u16(eventNumber & 0xFFFF);
        w.u8(heartbeat ? 0 : 1).u8(n).u16(0);
        for (int si = 0; si < n; si++) {
            EmitterOut sys = systems.get(si);
            List<List<DisEntityId>> perBeam = targets.get(si);
            int sysLen = 20;
            for (List<DisEntityId> t : perBeam) sysLen += 52 + 8 * t.size();
            w.u8(Math.min(sysLen / 4, 255)).u8(perBeam.size()).u16(0);
            w.u16(intOf(sys.values.apply("EmitterType")));
            w.u8(intOf(sys.values.apply("EmitterFunctionCode")));
            w.u8(sys.emitterId & 0xFF);
            w.f32x3(float3(sys.values.apply("RelativePosition")));
            for (int bi = 0; bi < perBeam.size(); bi++) {
                BeamOut b = sys.beams.get(bi);
                Function<String, HValue> v = b.values;
                List<DisEntityId> t = perBeam.get(bi);
                w.u8((52 + 8 * t.size()) / 4).u8(b.beamId & 0xFF).u16(intOf(v.apply("BeamParameterIndex")));
                w.f32((float) doubleOf(v.apply("EmissionFrequency")));
                w.f32((float) doubleOf(v.apply("FrequencyRange")));
                w.f32((float) doubleOf(v.apply("EffectiveRadiatedPower")));
                w.f32((float) doubleOf(v.apply("PulseRepetitionFrequency")));
                w.f32((float) doubleOf(v.apply("PulseWidth")));
                w.f32((float) doubleOf(v.apply("BeamAzimuthCenter")));
                w.f32((float) doubleOf(v.apply("BeamAzimuthSweep")));
                w.f32((float) doubleOf(v.apply("BeamElevationCenter")));
                w.f32((float) doubleOf(v.apply("BeamElevationSweep")));
                w.f32((float) doubleOf(v.apply("SweepSynch")));
                int function = intOf(v.apply("BeamFunctionCode"));
                if (b.jammer && function == 0) function = DisToRpr.BEAM_FUNCTION_JAMMING;   // "Other" on a jammer
                w.u8(function);
                w.u8(t.size());
                w.u8(truth(v.apply(b.jammer ? "HighDensityJam" : "HighDensityTrack")) ? 1 : 0);
                w.u8(0);                                      // DIS 7 beam status: active; DIS 6 padding
                w.u32(b.jammer ? jamming(v.apply("JammingModeSequence")) : 0);
                for (DisEntityId id : t) {
                    w.entityId(id).u8(0).u8(0);               // the target's emitter and beam: not known in RPR
                }
            }
        }
        return w.finish();
    }

    /** JammingModeSequence: RPR v2 32 bits; a v3 JammingTechniqueStruct (kind, category, subcategory, specific) packed. */
    private static long jamming(HValue v) {
        if (v == null) {
            return 0;
        }
        if (v.isNumeric()) {
            return v.asLong() & 0xFFFFFFFFL;
        }
        return ((long) (intOf(v.field("Kind")) & 0xFF) << 24) | ((intOf(v.field("Category")) & 0xFF) << 16)
                | ((intOf(v.field("Subcategory")) & 0xFF) << 8) | (intOf(v.field("Specific")) & 0xFF);
    }

    /** RTIobjectIdArray -> the DIS ids of the objects it names that are known (at most 255). */
    private static List<DisEntityId> targetIds(HValue array, Names names) {
        List<DisEntityId> out = new java.util.ArrayList<>();
        if (array == null || array.getKind() != HValue.Kind.ARRAY) {
            return out;
        }
        for (HValue e : array.getValues()) {
            DisEntityId id = ref(e, names);
            if (!id.isNone() && out.size() < 255) out.add(id);
        }
        return out;
    }

    /** Drops one target of the beam with the most; false when there are none left. */
    private static boolean trimLongest(List<List<List<DisEntityId>>> targets) {
        List<DisEntityId> longest = null;
        for (List<List<DisEntityId>> s : targets) {
            for (List<DisEntityId> t : s) {
                if (longest == null || t.size() > longest.size()) longest = t;
            }
        }
        if (longest == null || longest.isEmpty()) return false;
        longest.remove(longest.size() - 1);
        return true;
    }

    // ================================================================== designator and IFF

    /**
     * Designator PDU (type 24, 88 octets) of an EmbeddedSystem.Designator object.
     *
     * @param host the designating entity
     * @param off  sends the designator with output power 0 (deleted: it no longer designates)
     */
    public byte[] designator(DisEntityId host, Function<String, HValue> v, Names names, boolean off, long now) {
        DisWriter w = header(DisPduHeader.DESIGNATOR, FAMILY_EMISSION, now, 88);
        w.entityId(host);
        w.u16(intOf(v.apply("CodeName")));
        w.entityId(off ? DisEntityId.NONE : ref(v.apply("DesignatedObjectIdentifier"), names));
        w.u16(intOf(v.apply("DesignatorCode")));
        w.f32(off ? 0f : (float) doubleOf(v.apply("DesignatorOutputPower")));
        w.f32((float) doubleOf(v.apply("DesignatorEmissionWavelength")));
        w.f32x3(float3(v.apply("RelativeSpotLocation")));
        w.f64x3(double3(v.apply("DesignatorSpotLocation")));
        w.u8(intOf(v.apply("DeadReckoningAlgorithm"))).zeros(3);
        w.f32x3(float3(v.apply("SpotLinearAccelerationVector")));
        return w.finish();
    }

    /**
     * IFF PDU (type 28) of an RPR v2 IFF object: layer 1 (system, status, the six parameters)
     * and, with {@code Layer2DataAvailable}, layer 2 (beam, secondary operational data,
     * fundamental parameter data). The reverse of {@code DisToRpr.iff}.
     *
     * @param className        the object's class (NatoIFF / SovietIFF / RRB decide the parameters)
     * @param systemDesignator the DIS 7 system designator (0 for the first system of its type)
     * @param eventNumber      the event number of the event id
     * @param off              sends the system as off (deleted)
     */
    public byte[] iff(DisEntityId host, String className, int systemDesignator, Function<String, HValue> v, int eventNumber,
                      boolean off, long now) {
        HValue st = v.apply("SystemType");
        int type = st != null ? intOf(st) : DisToRpr.iffSystemType(className);
        int status = (!off && flag(v, "SystemIsOn", true) ? 1 : 0) | (flag(v, "SystemIsOperational", true) ? 0 : 0x80);
        int field1 = 0, field2 = 0;
        int[] p = new int[6];
        if (className.startsWith(DisToRpr.NATO_IFF)) {
            field1 = intOf(v.apply("AlternateMode4"));
            for (int i = 0; i < 6; i++) {
                String m = DisToRpr.NATO_MODES[i];
                if (flag(v, m + "Enabled", false)) status |= 1 << (i + 1);
                p[i] = modeFlags(v, m + "IsOn", m + "IsDamaged", m + "IsMalfunctioning");
            }
            if (flag(v, "ModeSIsTcasI", false)) p[5] |= 1;
            p[3] |= intOf(v.apply("Mode4PseudoCrypto")) & DisToRpr.IFF_CODE_MASK;
            if (!flag(v, "Mode4PseudoCryptoAvailable", true)) p[3] |= DisToRpr.IFF_ALTERNATE_MODE4;
            if (className.startsWith(DisToRpr.NATO_IFF_TRANSPONDER)) {
                field2 = (flag(v, "EmergencyOn", false) ? 0x2 : 0) | (flag(v, "IdentSquawkFlashOn", false) ? 0x4 : 0)
                        | (flag(v, "StiOn", false) ? 0x8 : 0);
                p[0] |= intOf(v.apply("Mode1Code")) & DisToRpr.IFF_CODE_MASK;
                p[1] |= intOf(v.apply("Mode2Code")) & DisToRpr.IFF_CODE_MASK;
                p[2] |= intOf(v.apply("Mode3ACode")) & DisToRpr.IFF_CODE_MASK;
                HValue alt = v.apply("Mode5CAltitude");
                if (alt == null || !flag(v, "Mode5CAltitudeAvailable", true)) {
                    p[4] |= DisToRpr.MODE_C_NOT_AVAILABLE;
                } else {
                    int a = (short) alt.asLong();
                    int mag = Math.min(Math.abs(a), 0x7FF);
                    if (a < 0 && mag == 0x7FF) mag = 0x7FE;           // all twelve bits set reads "not available"
                    p[4] |= (mag << 1) | (a < 0 ? 1 : 0);
                }
            }
        } else if (className.startsWith(DisToRpr.SOVIET_IFF)) {
            for (int i = 0; i < 6; i++) {
                String m = "Parameter" + (i + 1);
                if (flag(v, m + "Enabled", false)) status |= 1 << (i + 1);
                p[i] = modeFlags(v, m + "IsOn", m + "IsDamaged", m + "IsMalfunctioning");
            }
        } else if (className.startsWith(DisToRpr.RRB)) {
            p[0] = (intOf(v.apply("Code")) & DisToRpr.RRB_CODE_MASK)
                    | (flag(v, "PowerReduction", false) ? DisToRpr.RRB_POWER_REDUCTION : 0)
                    | (flag(v, "RadarEnhancement", false) ? DisToRpr.RRB_RADAR_ENHANCEMENT : 0)
                    | modeFlags(v, "IsOn", "IsDamaged", "IsMalfunctioning");
        }
        boolean layer2 = flag(v, "Layer2DataAvailable", false);
        HValue fp = v.apply("FundamentalParameterData");
        int sets = layer2 && fp != null && fp.getKind() == HValue.Kind.ARRAY ? Math.min(fp.size(), 255) : 0;
        int layer2Size = 28 + 24 * sets;
        DisWriter w = header(DisPduHeader.IFF, FAMILY_EMISSION, now, 60 + (layer2 ? layer2Size : 0));
        w.entityId(host).u16(host.site).u16(host.application).u16(eventNumber & 0xFFFF);
        w.f32x3(float3(v.apply("RelativePosition")));
        w.u16(type).u16(intOf(v.apply("SystemName"))).u8(intOf(v.apply("SystemMode"))).u8(0);
        if (version >= 7) {
            w.u8(systemDesignator & 0xFF).u8(0);
        } else {
            w.zeros(2);
        }
        w.u8(status).u8(field1).u8(0x2 | (layer2 ? 0x4 : 0)).u8(field2);
        for (int x : p) w.u16(x & 0xFFFF);
        if (layer2) {
            w.u8(2).u8(0).u16(layer2Size);
            w.f32((float) doubleOf(v.apply("BeamAzimuthCenter")));
            w.f32((float) doubleOf(v.apply("BeamAzimuthSweep")));
            w.f32((float) doubleOf(v.apply("BeamElevationCenter")));
            w.f32((float) doubleOf(v.apply("BeamElevationSweep")));
            w.f32((float) doubleOf(v.apply("BeamSweepSync")));
            w.u8(intOf(v.apply("SecondaryOperationalDataParameter1")));
            w.u8(intOf(v.apply("SecondaryOperationalDataParameter2")));
            w.u16(sets);
            for (int i = 0; i < sets; i++) {
                HValue rec = fp.get(i);
                w.f32((float) doubleOf(rec.field("ERP")));
                w.f32((float) doubleOf(rec.field("Frequency")));
                w.f32((float) doubleOf(rec.field("PgRF")));
                w.f32((float) doubleOf(rec.field("PulseWidth")));
                w.u32(longOf(rec.field("BurstLength")) & 0xFFFFFFFFL);
                w.u8(intOf(rec.field("ApplicableModes")));
                w.bytes(java.util.Arrays.copyOf(bytesOf(rec.field("Padding")), 3));   // system specific data
            }
        }
        return w.finish();
    }

    private static int modeFlags(Function<String, HValue> v, String on, String damaged, String malfunction) {
        return (flag(v, on, false) ? DisToRpr.IFF_ON : 0) | (flag(v, damaged, false) ? DisToRpr.IFF_DAMAGED : 0)
                | (flag(v, malfunction, false) ? DisToRpr.IFF_MALFUNCTION : 0);
    }

    /** A boolean attribute, or the default when it is missing. */
    private static boolean flag(Function<String, HValue> v, String name, boolean missing) {
        HValue x = v.apply(name);
        return x == null ? missing : truth(x);
    }

    // ================================================================== radio

    /**
     * Transmitter PDU (type 25) of a RadioTransmitter object.
     *
     * @param host    the host entity
     * @param radioId the radio number on the host
     * @param v       attribute values by name
     * @param off     sends the transmitter as off (state 0), e.g. when the object is deleted
     */
    public byte[] transmitter(DisEntityId host, int radioId, Function<String, HValue> v, boolean off, long now) {
        DisWriter w = header(DisPduHeader.TRANSMITTER, FAMILY_RADIO, now, 104);
        w.entityId(host).u16(radioId);
        HValue rt = v.apply("RadioSystemType");
        w.u8(rt == null ? DisEntityType.KIND_RADIO : intOf(rt.field("EntityKind")));
        w.u8(rt == null ? 0 : intOf(rt.field("Domain")));
        w.u16(rt == null ? 0 : intOf(rt.field("CountryCode")));
        w.u8(rt == null ? 0 : intOf(rt.field("Category")));
        w.u8(rt == null ? 0 : intOf(rt.field("NomenclatureVersion")));
        w.u16(rt == null ? 0 : intOf(rt.field("Nomenclature")));
        w.u8(off ? 0 : intOf(v.apply("TransmitterOperationalStatus")));
        w.u8(intOf(v.apply("RadioInputSource")));
        w.u16(0);                                     // DIS 6 padding, DIS 7 number of variable transmitter parameters
        w.f64x3(double3(v.apply("WorldLocation")));
        w.f32x3(float3(v.apply("RelativePosition")));
        w.u16(0).u16(0);                              // antenna pattern type (omnidirectional), length
        w.u64(longOf(v.apply("Frequency")));
        w.f32((float) doubleOf(v.apply("FrequencyBandwidth")));
        w.f32((float) doubleOf(v.apply("TransmittedPower")));
        // modulation type: spread spectrum, major, detail, system
        int spread = 0;
        HValue ss = v.apply("SpreadSpectrum");
        String ssAlt = ss == null ? null : ss.getAlternativeName();
        if (ssAlt != null && ssAlt.startsWith("SINCGARS")) {
            spread |= 0x1;                            // frequency hopping
        } else if (ssAlt != null && ssAlt.startsWith("JTIDS")) {
            spread |= 0x3;                            // frequency hopping, pseudo noise
        }
        if (truth(v.apply("TimeHopInUse"))) {
            spread |= 0x4;
        }
        w.u16(spread);
        HValue mod = v.apply("RFModulationType");
        w.u16(mod == null ? 0 : (int) mod.asLong());
        w.u16(mod == null || mod.getAlternative() == null ? 0 : (int) mod.getAlternative().asLong());
        w.u16(intOf(v.apply("RFModulationSystemType")));
        w.u16(intOf(v.apply("CryptoSystem")));
        int key = intOf(v.apply("EncryptionKeyIdentifier"));
        if (version >= 7) {
            key = (key & 0x7FFF) | ((intOf(v.apply("CryptographicMode")) & 1) << 15);
        }
        w.u16(key);
        w.u8(0).zeros(3);                             // modulation parameter length, padding
        return w.finish();
    }

    /**
     * Receiver PDU (type 27) of a RadioReceiver object.
     *
     * @param transmitter    the transmitter received from (host and radio), or null
     * @param transmitterRadio its radio number
     */
    public byte[] receiver(DisEntityId host, int radioId, Function<String, HValue> v, DisEntityId transmitter,
                           int transmitterRadio, boolean off, long now) {
        DisWriter w = header(DisPduHeader.RECEIVER, FAMILY_RADIO, now, 36);
        w.entityId(host).u16(radioId);
        w.u16(off ? 0 : intOf(v.apply("ReceiverOperationalStatus")));
        w.u16(0);
        w.f32((float) doubleOf(v.apply("ReceivedPower")));
        w.entityId(transmitter == null ? DisEntityId.NONE : transmitter);
        w.u16(transmitter == null ? 0 : transmitterRadio);
        return w.finish();
    }

    /** Signal PDU (type 26) from an EncodedAudioRadioSignal (encoding class 0). */
    public byte[] encodedAudio(DisEntityId host, int radioId, HValue audio, long now) {
        byte[] data = audio == null ? new byte[0] : bytesOf(audio.field("Data"));
        int bits = audio == null ? 0 : intOf(audio.field("DataLength"));
        if (bits == 0 || bits > data.length * 8) {
            bits = data.length * 8;
        }
        int scheme = (audio == null ? 0 : intOf(audio.field("EncodingType"))) & 0x3FFF;
        return signal(host, radioId, scheme, 0, audio == null ? 0 : longOf(audio.field("SampleRate")),
                bits, audio == null ? 0 : intOf(audio.field("SampleCount")), data, now);
    }

    /**
     * Signal PDU from a RawBinaryRadioSignal (class 1, its subclasses included: the signal data
     * is sent as it is) or an ApplicationSpecificRadioSignal (class 2: the data starts with the
     * user protocol id, as in the PDU).
     */
    public byte[] binarySignal(DisEntityId host, int radioId, boolean applicationSpecific, Function<String, HValue> p, long now) {
        byte[] data = bytesOf(p.apply("SignalData"));
        int bits = intOf(p.apply("SignalDataLength"));
        if (bits == 0 || bits > data.length * 8) {
            bits = data.length * 8;
        }
        int scheme = ((applicationSpecific ? 2 : 1) << 14) | (intOf(p.apply("TDLMessageCount")) & 0x3FFF);
        return signal(host, radioId, scheme, intOf(p.apply("TacticalDataLinkType")), longOf(p.apply("DataRate")),
                bits, 0, data, now);
    }

    /**
     * Signal PDU from a Link16RadioSignal subclass (SISO-STD-002-2021 section 4.2.2, Annex B):
     * raw binary, TDL type 100 (or the interaction's 113), the 20-octet Link 16 Simulation Network
     * Header built from the Link16RadioSignal parameters (missing ones as "no statement": CVLLs
     * 255, time slot and transmit time all ones; SISOSTD002Version 0 when the FOM has none), then
     * the message data copied octet for octet from the subclass's parameters.
     *
     * @param messageType the Message Type Identifier 0-7 ({@link DisToRpr#link16MessageType})
     */
    public byte[] link16Signal(DisEntityId host, int radioId, int messageType, Function<String, HValue> p, long now) {
        byte[] headerWord;
        byte[] body;
        int bodyBits;
        int words;
        switch (messageType) {
            case 1:
            case 2:
                headerWord = fit(flatOctets(p.apply(messageType == 1 ? "RTTAB" : "RTTReply")), DisToRpr.LINK16_HEADER_WORD_OCTETS);
                body = new byte[0];
                bodyBits = 0;
                words = 1;
                break;
            case 3:
            case 4:
            case 5: {
                headerWord = fit(flatOctets(p.apply("JTIDSHeader")), DisToRpr.LINK16_HEADER_WORD_OCTETS);
                body = flatOctets(p.apply("Data"));
                int stated = intOf(p.apply("DataLength"));
                bodyBits = stated > 0 && stated <= body.length * 8 ? stated : body.length * 8;
                words = 1;
                break;
            }
            default: {
                headerWord = fit(flatOctets(p.apply(messageType == 6 ? "LETHeader" : "JTIDSHeader")), DisToRpr.LINK16_HEADER_WORD_OCTETS);
                byte[] w = flatOctets(p.apply("TADILJMessage"));
                words = w.length / DisToRpr.LINK16_WORD_OCTETS;
                body = java.util.Arrays.copyOf(w, words * DisToRpr.LINK16_WORD_OCTETS);
                bodyBits = body.length * 8;
                break;
            }
        }
        DisWriter d = new DisWriter(DisToRpr.LINK16_NETWORK_HEADER_OCTETS + headerWord.length + body.length);
        d.u16(intOf(p.apply("NPGNumber")));
        d.u8(intOf(p.apply("NetNumber")));
        d.u8(orDefault(p.apply("TSEC_CVLL"), 255));
        d.u8(orDefault(p.apply("MSEC_CVLL"), 255));
        d.u8(messageType);
        d.u8(intOf(p.apply("SISOSTD002Version")));
        d.u8(intOf(p.apply("Link16Version")));
        HValue slot = p.apply("TimeSlotID");
        d.u32(slot == null ? 0xFFFFFFFFL : slot.asLong() & 0xFFFFFFFFL);
        HValue t = p.apply("PerceivedTransmitTime");
        d.u32(t == null || t.field("Seconds") == null ? 0xFFFFFFFFL : t.field("Seconds").asLong() & 0xFFFFFFFFL);
        d.u32(t == null || t.field("FractionalSeconds") == null ? 0xFFFFFFFFL : t.field("FractionalSeconds").asLong() & 0xFFFFFFFFL);
        d.bytes(headerWord).bytes(body);
        byte[] data = d.toBytes();
        int tdl = intOf(p.apply("TacticalDataLinkType"));
        if (tdl != DisToRpr.TDL_LINK16_SURROGATE) {
            tdl = DisToRpr.TDL_LINK16;
        }
        int bits = (DisToRpr.LINK16_NETWORK_HEADER_OCTETS + DisToRpr.LINK16_HEADER_WORD_OCTETS) * 8 + bodyBits;
        return signal(host, radioId, (1 << 14) | (words & 0x3FFF), tdl, longOf(p.apply("DataRate")), bits, 0, data, now);
    }

    private static int orDefault(HValue v, int def) {
        return v == null || !v.isNumeric() ? def : (int) v.asLong();
    }

    /** {@code n} octets: cut, or padded with zeros. */
    private static byte[] fit(byte[] b, int n) {
        return b.length == n ? b : java.util.Arrays.copyOf(b, n);
    }

    /** The octets of a value made of octets: bytes, arrays of numbers, records of them (in field order). */
    static byte[] flatOctets(HValue v) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        flatOctets(v, out);
        return out.toByteArray();
    }

    private static void flatOctets(HValue v, java.io.ByteArrayOutputStream out) {
        if (v == null) {
            return;
        }
        switch (v.getKind()) {
            case BYTES: {
                byte[] b = v.asBytes();
                out.write(b, 0, b.length);
                return;
            }
            case INT:
                out.write((int) v.asLong());
                return;
            case ARRAY:
            case RECORD:
                for (int i = 0; i < v.size(); i++) {
                    flatOctets(v.get(i), out);
                }
                return;
            default:
                return;
        }
    }

    /** Signal PDU from a DatabaseIndexRadioSignal (class 3): index, start offset, duration. */
    public byte[] databaseIndexSignal(DisEntityId host, int radioId, Function<String, HValue> p, long now) {
        DisWriter d = new DisWriter(12);
        d.u32(longOf(p.apply("DatabaseIndex"))).u32(longOf(p.apply("StartOffset"))).u32(longOf(p.apply("Duration")));
        byte[] data = d.toBytes();                    // not finish(): that writes a PDU length into octets 8-9
        return signal(host, radioId, (3 << 14) | (intOf(p.apply("TDLMessageCount")) & 0x3FFF), intOf(p.apply("TacticalDataLinkType")),
                0, 96, 0, data, now);
    }

    private byte[] signal(DisEntityId host, int radioId, int scheme, int tdlType, long sampleRate, int bits, int samples,
                          byte[] data, long now) {
        int octets = Math.min(data.length, (bits + 7) / 8);
        int padded = (octets + 3) & ~3;
        DisWriter w = header(DisPduHeader.SIGNAL, FAMILY_RADIO, now, 32 + padded);
        w.entityId(host).u16(radioId);
        w.u16(scheme).u16(tdlType).u32(sampleRate).u16(bits).u16(samples);
        w.bytes(octets == data.length ? data : java.util.Arrays.copyOf(data, octets));
        w.zeros(padded - octets);
        return w.finish();
    }

    // ================================================================== simulation management

    /** Start/Resume PDU (type 13). */
    public byte[] startResume(Function<String, HValue> p, long now) {
        DisWriter w = header(DisPduHeader.START_RESUME, FAMILY_SIMULATION_MANAGEMENT, now, 44);
        w.entityId(entityId(p.apply("OriginatingEntity"))).entityId(entityId(p.apply("ReceivingEntity")));
        clock(w, p.apply("RealWorldTime"));
        clock(w, p.apply("SimulationTime"));
        w.u32(longOf(p.apply("RequestIdentifier")));
        return w.finish();
    }

    /** Stop/Freeze PDU (type 14). */
    public byte[] stopFreeze(Function<String, HValue> p, long now) {
        DisWriter w = header(DisPduHeader.STOP_FREEZE, FAMILY_SIMULATION_MANAGEMENT, now, 40);
        w.entityId(entityId(p.apply("OriginatingEntity"))).entityId(entityId(p.apply("ReceivingEntity")));
        clock(w, p.apply("RealWorldTime"));
        w.u8(intOf(p.apply("Reason")));
        int behaviour = (truth(p.apply("RunInternalSimulationClock")) ? 0x1 : 0) | (truth(p.apply("UpdateAttributes")) ? 0x2 : 0)
                | (truth(p.apply("ReflectValues")) ? 0x4 : 0);
        w.u8(behaviour).u16(0);
        w.u32(longOf(p.apply("RequestIdentifier")));
        return w.finish();
    }

    /** Acknowledge PDU (type 15). */
    public byte[] acknowledge(Function<String, HValue> p, long now) {
        DisWriter w = header(DisPduHeader.ACKNOWLEDGE, FAMILY_SIMULATION_MANAGEMENT, now, 32);
        w.entityId(entityId(p.apply("OriginatingEntity"))).entityId(entityId(p.apply("ReceivingEntity")));
        w.u16(intOf(p.apply("AcknowledgeFlag"))).u16(intOf(p.apply("ResponseFlag")));
        w.u32(longOf(p.apply("RequestIdentifier")));
        return w.finish();
    }

    private static void clock(DisWriter w, HValue c) {
        w.i32(c == null ? 0 : intOf(c.field("Hours")));
        w.u32(c == null ? 0 : longOf(c.field("TimePastTheHour")));
    }

    // ================================================================== value helpers

    /** An EntityIdentifierStruct, or 0-0-0. */
    public static DisEntityId entityId(HValue v) {
        if (v == null) {
            return DisEntityId.NONE;
        }
        HValue f = v.field("FederateIdentifier");
        return new DisEntityId(f == null ? 0 : intOf(f.field("SiteID")), f == null ? 0 : intOf(f.field("ApplicationID")),
                intOf(v.field("EntityNumber")));
    }

    /** An EntityTypeStruct, or all zeros. */
    public static DisEntityType entityType(HValue v) {
        if (v == null) {
            return new DisEntityType(0, 0, 0, 0, 0, 0, 0);
        }
        return new DisEntityType(intOf(v.field("EntityKind")), intOf(v.field("Domain")), intOf(v.field("CountryCode")),
                intOf(v.field("Category")), intOf(v.field("Subcategory")), intOf(v.field("Specific")), intOf(v.field("Extra")));
    }

    /**
     * A {@code "site-application-entity"} name, or null for any other text.
     *
     * @param name e.g. "1-2-3"
     */
    public static DisEntityId parseEntityName(String name) {
        if (name == null) {
            return null;
        }
        String[] p = name.split("-");
        if (p.length != 3) {
            return null;
        }
        try {
            int s = Integer.parseInt(p[0]);
            int a = Integer.parseInt(p[1]);
            int e = Integer.parseInt(p[2]);
            if (s < 0 || s > 65535 || a < 0 || a > 65535 || e < 0 || e > 65535) {
                return null;
            }
            return new DisEntityId(s, a, e);
        } catch (NumberFormatException x) {
            return null;
        }
    }

    /**
     * A {@code "<host>/radio-<n>"} or {@code "<host>/receiver-<n>"} name: {site, application,
     * entity, radio}, or null.
     */
    public static int[] parseRadioName(String name) {
        if (name == null) {
            return null;
        }
        int slash = name.indexOf('/');
        if (slash < 0) {
            return null;
        }
        DisEntityId host = parseEntityName(name.substring(0, slash));
        String rest = name.substring(slash + 1);
        int dash = rest.lastIndexOf('-');
        if (host == null || dash < 0 || !(rest.startsWith("radio-") || rest.startsWith("receiver-"))) {
            return null;
        }
        try {
            int r = Integer.parseInt(rest.substring(dash + 1));
            return r < 0 || r > 65535 ? null : new int[] {host.site, host.application, host.entity, r};
        } catch (NumberFormatException x) {
            return null;
        }
    }

    private static DisEntityId ref(HValue objectId, Names names) {
        if (objectId == null) {
            return DisEntityId.NONE;
        }
        String n = objectId.asTrimmedText();
        if (n.isEmpty()) {
            return DisEntityId.NONE;
        }
        DisEntityId id = names.entity(n);
        if (id == null) {
            id = parseEntityName(n);
        }
        return id == null ? DisEntityId.NONE : id;
    }

    private static int countArticulations(HValue arts) {
        if (arts == null || arts.getKind() != HValue.Kind.ARRAY) {
            return 0;
        }
        int n = 0;
        for (HValue a : arts.getValues()) {
            if (articulationAlternative(a) != null) {
                n++;
            }
        }
        return Math.min(n, 255);
    }

    private static HValue articulationAlternative(HValue record) {
        HValue pv = record == null ? null : record.field("ParameterValue");
        return pv == null ? null : pv.getAlternative();
    }

    /** ArticulatedParameterStruct records as 16-octet variable parameter records (at most 255). */
    private static void writeArticulations(DisWriter w, HValue arts) {
        if (arts == null || arts.getKind() != HValue.Kind.ARRAY) {
            return;
        }
        int written = 0;
        List<HValue> l = arts.getValues();
        for (HValue a : l) {
            HValue alt = articulationAlternative(a);
            if (alt == null || written == 255) {
                continue;
            }
            int designator = (int) a.field("ParameterValue").asLong();
            w.u8(designator);
            w.u8(intOf(a.field("ArticulatedParameterChange")));
            w.u16(intOf(a.field("PartAttachedTo")));
            if (designator == DisArticulation.ARTICULATED_PART) {
                w.u32(longOf(alt.field("Class")) + longOf(alt.field("TypeMetric")));
                w.f32((float) doubleOf(alt.field("Value"))).zeros(4);
            } else {
                w.u32(longOf(alt.field("Station")));
                w.entityType(entityType(alt.field("StoreType")));
            }
            written++;
        }
    }

    private static float[] float3(HValue v) {
        if (v == null || v.size() < 3) {
            return new float[3];
        }
        return new float[] {(float) v.get(0).asDouble(), (float) v.get(1).asDouble(), (float) v.get(2).asDouble()};
    }

    private static double[] double3(HValue v) {
        if (v == null || v.size() < 3) {
            return new double[3];
        }
        return new double[] {v.get(0).asDouble(), v.get(1).asDouble(), v.get(2).asDouble()};
    }

    private static byte[] textBytes(HValue v) {
        if (v == null) {
            return new byte[0];
        }
        if (v.getKind() == HValue.Kind.ARRAY) {                      // an array of characters decoded element-wise
            byte[] b = new byte[v.size()];
            for (int i = 0; i < b.length; i++) {
                b[i] = (byte) v.get(i).asLong();
            }
            return b;
        }
        return v.asBytes();
    }

    private static byte[] bytesOf(HValue v) {
        return textBytes(v);
    }

    private static boolean truth(HValue v) {
        return v != null && v.isNumeric() && v.asLong() != 0;
    }

    private static int intOf(HValue v) {
        return v == null ? 0 : (int) v.asLong();
    }

    private static long longOf(HValue v) {
        return v == null ? 0 : v.asLong();
    }

    private static double doubleOf(HValue v) {
        return v == null ? 0 : v.asDouble();
    }
}
