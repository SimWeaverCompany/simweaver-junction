package com.simweaver.common.dis;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps DIS PDUs to RPR FOM v2.0 objects and interactions, encoded as an HLA federate of the
 * RPR FOM would send them. Follows the DIS to RPR mapping of the RPR GRIM (SISO-STD-001-2015)
 * where the two meet; see the README for the tables and the parts left out.
 *
 * <p>DIS entities are identified by their entity id: its text {@code "site-application-entity"}
 * is the object's instance name and goes into every RTIobjectId that refers to it, so that
 * references resolve like HLA instance names. Radios get {@code "<host>/radio-<n>"} and
 * {@code "<host>/receiver-<n>"}.
 *
 * <p>Stateless apart from the protocol version; thread safe.
 */
public final class DisToRpr {

    // object classes
    public static final String BASE_ENTITY = "HLAobjectRoot.BaseEntity";
    public static final String PHYSICAL_ENTITY = BASE_ENTITY + ".PhysicalEntity";
    public static final String PLATFORM = PHYSICAL_ENTITY + ".Platform";
    public static final String RADIO_TRANSMITTER = "HLAobjectRoot.EmbeddedSystem.RadioTransmitter";
    public static final String RADIO_RECEIVER = "HLAobjectRoot.EmbeddedSystem.RadioReceiver";
    public static final String DESIGNATOR = "HLAobjectRoot.EmbeddedSystem.Designator";
    public static final String IFF = "HLAobjectRoot.EmbeddedSystem.IFF";
    public static final String NATO_IFF = IFF + ".NatoIFF";
    public static final String NATO_IFF_TRANSPONDER = NATO_IFF + ".NatoIFFTransponder";
    public static final String NATO_IFF_INTERROGATOR = NATO_IFF + ".NatoIFFInterrogator";
    public static final String SOVIET_IFF = IFF + ".SovietIFF";
    public static final String RRB = IFF + ".RRB";
    public static final String EMITTER_SYSTEM = "HLAobjectRoot.EmbeddedSystem.EmitterSystem";
    public static final String RADAR_BEAM = "HLAobjectRoot.EmitterBeam.RadarBeam";
    public static final String JAMMER_BEAM = "HLAobjectRoot.EmitterBeam.JammerBeam";

    /** DIS beam function "Jamming": such a beam is a JammerBeam, every other one a RadarBeam (GRIM). */
    public static final int BEAM_FUNCTION_JAMMING = 12;

    // interaction classes
    public static final String WEAPON_FIRE = "HLAinteractionRoot.WeaponFire";
    public static final String MUNITION_DETONATION = "HLAinteractionRoot.MunitionDetonation";
    public static final String COLLISION = "HLAinteractionRoot.Collision";
    public static final String ENCODED_AUDIO_SIGNAL = "HLAinteractionRoot.RadioSignal.EncodedAudioRadioSignal";
    public static final String RAW_BINARY_SIGNAL = "HLAinteractionRoot.RadioSignal.RawBinaryRadioSignal";
    public static final String APPLICATION_SPECIFIC_SIGNAL = "HLAinteractionRoot.RadioSignal.ApplicationSpecificRadioSignal";
    public static final String DATABASE_INDEX_SIGNAL = "HLAinteractionRoot.RadioSignal.DatabaseIndexRadioSignal";
    public static final String START_RESUME = "HLAinteractionRoot.StartResume";
    public static final String STOP_FREEZE = "HLAinteractionRoot.StopFreeze";
    public static final String ACKNOWLEDGE = "HLAinteractionRoot.Acknowledge";

    // Link 16 (SISO-STD-002-2021): the Link-16 module's subclasses of RawBinaryRadioSignal
    public static final String LINK16_SIGNAL = RAW_BINARY_SIGNAL + ".TDLBinaryRadioSignal.Link16RadioSignal";
    /** The Link16RadioSignal subclass of each Message Type Identifier 0-7 (SISO-STD-002 Table B-1). */
    public static final String[] LINK16_CLASSES = {
        LINK16_SIGNAL + ".JTIDSMessageRadioSignal", LINK16_SIGNAL + ".RTTABRadioSignal",
        LINK16_SIGNAL + ".RTTReplyRadioSignal", LINK16_SIGNAL + ".JTIDSVoiceCVSDRadioSignal",
        LINK16_SIGNAL + ".JTIDSVoiceLPC10RadioSignal", LINK16_SIGNAL + ".JTIDSVoiceLPC12RadioSignal",
        LINK16_SIGNAL + ".JTIDSLETRadioSignal", LINK16_SIGNAL + ".VMFRadioSignal"};
    /** TDL types of Link 16: Standardized Format (JTIDS/MIDS/TADIL J), and its non-NATO surrogate. */
    public static final int TDL_LINK16 = 100;
    public static final int TDL_LINK16_SURROGATE = 113;
    /** Octets of the Link 16 Simulation Network Header at the start of the Signal PDU data. */
    public static final int LINK16_NETWORK_HEADER_OCTETS = 20;
    /** Octets of the JTIDS / LET header word (48 bits) and of a J-word (80 bits). */
    public static final int LINK16_HEADER_WORD_OCTETS = 6;
    public static final int LINK16_WORD_OCTETS = 10;
    /** Parameters that only some versions of the Link-16 module have: no warning when the FOM lacks them. */
    public static final java.util.Set<String> OPTIONAL_PARAMETERS = java.util.Collections.unmodifiableSet(
            new java.util.HashSet<>(Arrays.asList("SISOSTD002Version", "Link16Version", "DataLength")));

    /**
     * An interaction to log: its class and its parameters in the RPR encoding, in FOM-like order,
     * and what to send instead when the FOM lacks the class (Link 16 without the Link-16 module:
     * a RawBinaryRadioSignal with the data as it is).
     */
    public static final class Interaction {
        public final String className;
        public final Map<String, byte[]> parameters;
        /** Used when the FOM has no {@link #className}; null = none. */
        public final Interaction fallback;

        Interaction(String className, Map<String, byte[]> parameters) {
            this(className, parameters, null);
        }

        Interaction(String className, Map<String, byte[]> parameters, Interaction fallback) {
            this.className = className;
            this.parameters = parameters;
            this.fallback = fallback;
        }

        /** The simple class name: "JTIDSMessageRadioSignal". */
        public String simpleName() {
            return className.substring(className.lastIndexOf('.') + 1);
        }
    }

    /** @return the Link 16 Message Type Identifier of a Link16RadioSignal subclass (by its simple name), else -1 */
    public static int link16MessageType(String classNameQ) {
        if (classNameQ == null) return -1;
        String simple = classNameQ.substring(classNameQ.lastIndexOf('.') + 1);
        for (int i = 0; i < LINK16_CLASSES.length; i++) {
            String c = LINK16_CLASSES[i];
            if (c.substring(c.lastIndexOf('.') + 1).equalsIgnoreCase(simple)) return i;
        }
        if (simple.equalsIgnoreCase("RTTReplySignal")) return 2;           // the name in the 2021 parameter table
        return -1;
    }

    /** A radio transmitter or receiver: the object it maps to and its attributes. */
    public static final class Radio {
        public final String className;
        public final String instanceName;
        public final DisEntityId host;
        public final int radioId;
        public final Map<String, byte[]> attributes;

        Radio(String className, String instanceName, DisEntityId host, int radioId, Map<String, byte[]> attributes) {
            this.className = className;
            this.instanceName = instanceName;
            this.host = host;
            this.radioId = radioId;
            this.attributes = attributes;
        }
    }

    private final int protocolVersion;

    /** @param protocolVersion 6 or 7: the DIS version whose rules apply (e.g. the DIS 7 PDU status bits) */
    public DisToRpr(int protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    // ================================================================== entities

    /**
     * The RPR class of an entity, from its kind, domain and category. The caller falls back to
     * the nearest superclass the FOM has.
     */
    public static String entityClass(DisEntityType t) {
        switch (t.kind) {
            case DisEntityType.KIND_PLATFORM:
                switch (t.domain) {
                    case DisEntityType.DOMAIN_LAND: return PLATFORM + ".GroundVehicle";
                    case DisEntityType.DOMAIN_AIR: return PLATFORM + ".Aircraft";
                    case DisEntityType.DOMAIN_SURFACE: return PLATFORM + ".SurfaceVessel";
                    case DisEntityType.DOMAIN_SUBSURFACE: return PLATFORM + ".SubmersibleVessel";
                    case DisEntityType.DOMAIN_SPACE: return PLATFORM + ".Spacecraft";
                    default: return PLATFORM;
                }
            case DisEntityType.KIND_MUNITION: return PHYSICAL_ENTITY + ".Munition";
            case DisEntityType.KIND_LIFE_FORM:                          // categories 200 and up: non-human
                return PHYSICAL_ENTITY + ".Lifeform." + (t.category >= 200 ? "NonHuman" : "Human");
            case DisEntityType.KIND_ENVIRONMENTAL: return BASE_ENTITY + ".EnvironmentalEntity";
            case DisEntityType.KIND_CULTURAL_FEATURE: return PHYSICAL_ENTITY + ".CulturalFeature";
            case DisEntityType.KIND_SUPPLY: return PHYSICAL_ENTITY + ".Supplies";
            case DisEntityType.KIND_RADIO: return PHYSICAL_ENTITY + ".Radio";
            case DisEntityType.KIND_EXPENDABLE: return PHYSICAL_ENTITY + ".Expendables";
            case DisEntityType.KIND_SENSOR_EMITTER: return PHYSICAL_ENTITY + ".Sensor";
            default: return PHYSICAL_ENTITY;
        }
    }

    /**
     * All attributes an entity state maps to. The caller keeps those its class has, and sends
     * only the ones that changed.
     */
    public Map<String, byte[]> entityAttributes(DisEntityState s) {
        Map<String, byte[]> a = new LinkedHashMap<>();
        a.put("EntityIdentifier", RprCodec.entityIdentifier(s.id));
        a.put("EntityType", RprCodec.entityType(s.type));
        a.put("Marking", RprCodec.marking(s.markingCharset, s.marking));
        a.put("ForceIdentifier", RprCodec.enum8(s.forceId));
        a.put("Spatial", RprCodec.spatial(s.drAlgorithm, s.location, s.isFrozen(), s.orientation, s.velocity,
                s.drAcceleration, s.drAngularVelocity));
        a.put("AlternateEntityType", RprCodec.entityType(s.alternateType));
        DisAppearance.put(s, a);
        // the array is sent empty only to clear earlier records (see the caller's change check)
        a.put("ArticulatedParametersArray", RprCodec.articulatedParameters(s.articulations));
        return a;
    }

    // ================================================================== warfare

    /** Fire PDU (type 2) -> WeaponFire. The reader is positioned after the header. */
    public Interaction fire(DisPduHeader h, DisReader r) {
        DisEntityId firing = DisEntityId.read(r);
        DisEntityId target = DisEntityId.read(r);
        DisEntityId munition = DisEntityId.read(r);
        r.skip(4);                                                   // event id: site, application
        int eventNumber = r.u16();
        long fireMissionIndex = r.u32();
        double[] location = {r.f64(), r.f64(), r.f64()};
        DisEntityType munitionType = DisEntityType.read(r);
        int warhead = r.u16();
        int fuse = r.u16();
        int quantity = r.u16();
        int rate = r.u16();
        float[] velocity = {r.f32(), r.f32(), r.f32()};
        float range = r.f32();
        boolean expendable = protocolVersion >= 7 && h.fireTypeExpendable();  // expendable descriptor: type + padding

        Map<String, byte[]> p = new LinkedHashMap<>();
        p.put("EventIdentifier", RprCodec.eventIdentifier(eventNumber, issuer(firing)));
        p.put("FireControlSolutionRange", RprCodec.f32(range));
        p.put("FireMissionIndex", RprCodec.u32(fireMissionIndex));
        p.put("FiringLocation", RprCodec.worldLocation(location));
        putRef(p, "FiringObjectIdentifier", firing);
        if (!expendable) p.put("FuseType", RprCodec.u16(fuse));
        p.put("InitialVelocityVector", RprCodec.float3(velocity));
        putRef(p, "MunitionObjectIdentifier", munition);
        p.put("MunitionType", RprCodec.entityType(munitionType));
        if (!expendable) {
            p.put("QuantityFired", RprCodec.u16(quantity));
            p.put("RateOfFire", RprCodec.u16(rate));
        }
        putRef(p, "TargetObjectIdentifier", target);
        if (!expendable) p.put("WarheadType", RprCodec.u16(warhead));
        return new Interaction(WEAPON_FIRE, p);
    }

    /** Detonation PDU (type 3) -> MunitionDetonation. The reader is positioned after the header. */
    public Interaction detonation(DisPduHeader h, DisReader r) {
        DisEntityId firing = DisEntityId.read(r);
        DisEntityId target = DisEntityId.read(r);
        DisEntityId munition = DisEntityId.read(r);
        r.skip(4);
        int eventNumber = r.u16();
        float[] velocity = {r.f32(), r.f32(), r.f32()};
        double[] location = {r.f64(), r.f64(), r.f64()};
        DisEntityType munitionType = DisEntityType.read(r);
        int warhead = r.u16();
        int fuse = r.u16();
        int quantity = r.u16();
        int rate = r.u16();
        float[] relative = {r.f32(), r.f32(), r.f32()};
        int result = r.u8();
        int n = r.u8();
        r.skip(2);
        java.util.List<DisArticulation> arts = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) arts.add(DisArticulation.read(r));
        // DIS 7: the descriptor is a munition descriptor only when the detonation type indicator is 0
        boolean munitionDescriptor = protocolVersion < 7 || h.detonationTypeIndicator() == 0;

        Map<String, byte[]> p = new LinkedHashMap<>();
        if (RprCodec.articulatedParameterCount(arts) > 0) p.put("ArticulatedPartData", RprCodec.articulatedParameters(arts));
        p.put("DetonationLocation", RprCodec.worldLocation(location));
        p.put("DetonationResultCode", RprCodec.enum8(result));
        p.put("EventIdentifier", RprCodec.eventIdentifier(eventNumber, issuer(firing)));
        putRef(p, "FiringObjectIdentifier", firing);
        p.put("FinalVelocityVector", RprCodec.float3(velocity));
        if (munitionDescriptor) p.put("FuseType", RprCodec.u16(fuse));
        putRef(p, "MunitionObjectIdentifier", munition);
        p.put("MunitionType", RprCodec.entityType(munitionType));
        if (munitionDescriptor) {
            p.put("QuantityFired", RprCodec.u16(quantity));
            p.put("RateOfFire", RprCodec.u16(rate));
        }
        p.put("RelativeDetonationLocation", RprCodec.float3(relative));
        putRef(p, "TargetObjectIdentifier", target);
        if (munitionDescriptor) p.put("WarheadType", RprCodec.u16(warhead));
        return new Interaction(MUNITION_DETONATION, p);
    }

    /** Collision PDU (type 4) -> Collision. The reader is positioned after the header. */
    public Interaction collision(DisPduHeader h, DisReader r) {
        DisEntityId issuing = DisEntityId.read(r);
        DisEntityId colliding = DisEntityId.read(r);
        r.skip(4);
        int eventNumber = r.u16();
        int type = r.u8();
        r.skip(1);
        float[] velocity = {r.f32(), r.f32(), r.f32()};
        float mass = r.f32();
        float[] location = {r.f32(), r.f32(), r.f32()};

        Map<String, byte[]> p = new LinkedHashMap<>();
        putRef(p, "CollidingObjectIdentifier", colliding);
        p.put("IssuingObjectMass", RprCodec.f32(mass));
        p.put("IssuingObjectVelocityVector", RprCodec.float3(velocity));
        p.put("CollisionType", RprCodec.enum8(type));
        p.put("CollisionLocation", RprCodec.float3(location));
        p.put("EventIdentifier", RprCodec.eventIdentifier(eventNumber, issuer(issuing)));
        putRef(p, "IssuingObjectIdentifier", issuing);
        return new Interaction(COLLISION, p);
    }

    // ================================================================== designator and IFF

    /** Instance name of an entity's designator: {@code "<host>/designator"} (DIS has one per entity). */
    public static String designatorName(DisEntityId host) {
        return host.text() + "/designator";
    }

    /**
     * Instance name of an IFF system: {@code "<host>/iff-<system type>"}, and
     * {@code "-<system designator>"} when that (DIS 7) is not 0.
     */
    public static String iffName(DisEntityId host, int systemType, int systemDesignator) {
        return host.text() + "/iff-" + systemType + (systemDesignator != 0 ? "-" + systemDesignator : "");
    }

    /**
     * The RPR v2 class of an IFF system type (SISO-REF-010): Mark X/XII, Mark XIIA, Mode 5 and
     * Mode S transponders and interrogators are NatoIFF, Soviet ones SovietIFF, the RRB
     * transponder RRB; combined interrogator/transponders and TCAS are NatoIFF; others IFF.
     */
    public static String iffClass(int systemType) {
        switch (systemType) {
            case 1: case 9: case 10: case 11:
                return NATO_IFF_TRANSPONDER;
            case 2: case 6: case 7: case 8:
                return NATO_IFF_INTERROGATOR;
            case 3:
                return SOVIET_IFF + ".SovietIFFTransponder";
            case 4:
                return SOVIET_IFF + ".SovietIFFInterrogator";
            case 5:
                return RRB;
            case 12: case 13: case 14:
                return NATO_IFF;
            default:
                return IFF;
        }
    }

    /** The IFF system type a class stands for when the object has no SystemType (0: unknown). */
    public static int iffSystemType(String className) {
        if (className.startsWith(NATO_IFF_TRANSPONDER)) return 1;
        if (className.startsWith(NATO_IFF_INTERROGATOR)) return 2;
        if (className.startsWith(SOVIET_IFF + ".SovietIFFTransponder")) return 3;
        if (className.startsWith(SOVIET_IFF + ".SovietIFFInterrogator")) return 4;
        if (className.startsWith(RRB)) return 5;
        return 0;
    }

    /** The NatoIFF mode names of parameters 1-6, as in the RPR v2 attribute names. */
    public static final String[] NATO_MODES = {"Mode1", "Mode2", "Mode3A", "Mode4", "Mode5C", "ModeS"};

    /** Bits of an IFF parameter (DIS "Parameter 1-6" records, SISO-REF-010). */
    public static final int IFF_CODE_MASK = 0x0FFF;
    public static final int IFF_ALTERNATE_MODE4 = 1 << 12;
    public static final int IFF_ON = 1 << 13;
    public static final int IFF_DAMAGED = 1 << 14;
    public static final int IFF_MALFUNCTION = 1 << 15;
    /** RRB: bits 0-4 code, bit 9 power reduction, bit 10 radar enhancement. */
    public static final int RRB_CODE_MASK = 0x1F;
    public static final int RRB_POWER_REDUCTION = 1 << 9;
    public static final int RRB_RADAR_ENHANCEMENT = 1 << 10;
    /** Mode C altitude (parameter 5): bit 0 negative, bits 1-11 hundreds of feet; all ones: not available. */
    public static final int MODE_C_NOT_AVAILABLE = 0x0FFF;

    /**
     * Designator PDU (type 24) -> EmbeddedSystem.Designator ({@code "<host>/designator"}). The
     * reader is positioned after the header.
     */
    public Embedded designator(DisPduHeader h, DisReader r) {
        DisEntityId host = DisEntityId.read(r);
        int codeName = r.u16();
        DisEntityId designated = DisEntityId.read(r);
        int code = r.u16();
        float power = r.f32();
        float wavelength = r.f32();
        float[] relative = {r.f32(), r.f32(), r.f32()};
        double[] spot = {r.f64(), r.f64(), r.f64()};
        int algorithm = r.u8();
        r.skip(3);
        float[] acceleration = {r.f32(), r.f32(), r.f32()};

        Map<String, byte[]> a = new LinkedHashMap<>();
        putHost(a, host);
        a.put("CodeName", RprCodec.u16(codeName));
        putRef(a, "DesignatedObjectIdentifier", designated);
        a.put("DesignatorCode", RprCodec.u16(code));
        a.put("DesignatorEmissionWavelength", RprCodec.f32(wavelength));
        a.put("DesignatorOutputPower", RprCodec.f32(power));
        a.put("DesignatorSpotLocation", RprCodec.worldLocation(spot));
        a.put("DeadReckoningAlgorithm", RprCodec.enum8(algorithm));
        a.put("RelativeSpotLocation", RprCodec.float3(relative));
        a.put("SpotLinearAccelerationVector", RprCodec.float3(acceleration));
        return new Embedded(DESIGNATOR, designatorName(host), a);
    }

    /**
     * IFF PDU (type 28; DIS 6: IFF/ATC/NAVAIDS) -> an IFF object of the class of its system
     * type ({@link #iffClass}), named {@link #iffName}. Layer 1 gives the system, its status
     * and the six parameters (modes); layer 2, when present, the beam and the fundamental
     * parameter data. Layers 3 and up (DIS 7 Mode 5 / Mode S) have no RPR v2 attributes and are
     * skipped. The reader is positioned after the header.
     */
    public Embedded iff(DisPduHeader h, DisReader r) {
        DisEntityId host = DisEntityId.read(r);
        r.skip(4);                                              // event id: site and application
        int eventNumber = r.u16();
        float[] location = {r.f32(), r.f32(), r.f32()};
        int systemType = r.u16();
        int systemName = r.u16();
        int systemMode = r.u8();
        r.skip(1);                                              // change indicator / change-options
        int systemDesignator = 0;
        if (protocolVersion >= 7) {
            systemDesignator = r.u8();
            r.skip(1);                                          // system specific data
        } else {
            r.skip(2);
        }
        int status = r.u8();
        int field1 = r.u8();                                    // alternate parameter 4
        int layers = r.u8();
        int field2 = r.u8();                                    // modifier
        int[] p = new int[6];
        for (int i = 0; i < 6; i++) p[i] = r.u16();

        String cls = iffClass(systemType);
        Map<String, byte[]> a = new LinkedHashMap<>();
        putHost(a, host);
        a.put("RelativePosition", RprCodec.float3(location));
        a.put("EventIdentifier", RprCodec.eventIdentifier(eventNumber, issuer(host)));
        a.put("SystemType", RprCodec.u16(systemType));
        a.put("SystemName", RprCodec.u16(systemName));
        a.put("SystemMode", RprCodec.enum8(systemMode));
        a.put("SystemIsOn", RprCodec.bool((status & 1) != 0));
        a.put("SystemIsOperational", RprCodec.bool((status & 0x80) == 0));

        // layer 2 (and the others, skipped): layer number, specific information, length
        boolean layer2 = false;
        while (r.remaining() >= 4) {
            int start = r.position();
            int number = r.u8();
            r.skip(1);
            int length = r.u16();
            if (length < 4 || start + length > r.length()) break;
            if (number == 2 && length >= 28) {
                layer2 = true;
                a.put("BeamAzimuthCenter", RprCodec.f32(r.f32()));
                a.put("BeamAzimuthSweep", RprCodec.f32(r.f32()));
                a.put("BeamElevationCenter", RprCodec.f32(r.f32()));
                a.put("BeamElevationSweep", RprCodec.f32(r.f32()));
                a.put("BeamSweepSync", RprCodec.f32(r.f32()));
                a.put("SecondaryOperationalDataParameter1", RprCodec.enum8(r.u8()));
                a.put("SecondaryOperationalDataParameter2", RprCodec.enum8(r.u8()));
                int sets = r.u16();
                int n = Math.max(0, Math.min(sets, (start + length - r.position()) / 24));
                a.put("FundamentalParameterData", RprCodec.octets(r.bytes(24 * n)));   // same 24-octet layout
            }
            r.seek(start + length);
        }
        a.put("Layer2DataAvailable", RprCodec.bool(layer2));

        if (cls.startsWith(NATO_IFF)) {
            a.put("AlternateMode4", RprCodec.enum8(field1));
            for (int i = 0; i < 6; i++) {
                String m = NATO_MODES[i];
                a.put(m + "Enabled", RprCodec.bool((status & (1 << (i + 1))) != 0));
                a.put(m + "IsOn", RprCodec.bool((p[i] & IFF_ON) != 0));
                a.put(m + "IsDamaged", RprCodec.bool((p[i] & IFF_DAMAGED) != 0));
                a.put(m + "IsMalfunctioning", RprCodec.bool((p[i] & IFF_MALFUNCTION) != 0));
            }
            a.put("ModeSIsTcasI", RprCodec.bool((p[5] & 1) != 0));
            a.put("Mode4PseudoCrypto", RprCodec.u16(p[3] & IFF_CODE_MASK));
            a.put("Mode4PseudoCryptoAvailable", RprCodec.bool((p[3] & IFF_ALTERNATE_MODE4) == 0));
            if (cls.startsWith(NATO_IFF_TRANSPONDER)) {
                a.put("EmergencyOn", RprCodec.bool((field2 & 0x2) != 0));
                a.put("IdentSquawkFlashOn", RprCodec.bool((field2 & 0x4) != 0));
                a.put("StiOn", RprCodec.bool((field2 & 0x8) != 0));
                a.put("Mode1Code", RprCodec.u16(p[0] & IFF_CODE_MASK));
                a.put("Mode2Code", RprCodec.u16(p[1] & IFF_CODE_MASK));
                a.put("Mode3ACode", RprCodec.u16(p[2] & IFF_CODE_MASK));
                int c = p[4] & IFF_CODE_MASK;
                boolean available = c != MODE_C_NOT_AVAILABLE;
                int altitude = available ? ((c & 1) != 0 ? -(c >>> 1) : (c >>> 1)) : 0;
                a.put("Mode5CAltitude", RprCodec.u16(altitude & 0xFFFF));
                a.put("Mode5CAltitudeAvailable", RprCodec.bool(available));
            }
        } else if (cls.startsWith(SOVIET_IFF)) {
            for (int i = 0; i < 6; i++) {
                String m = "Parameter" + (i + 1);
                a.put(m + "Enabled", RprCodec.bool((status & (1 << (i + 1))) != 0));
                a.put(m + "IsOn", RprCodec.bool((p[i] & IFF_ON) != 0));
                a.put(m + "IsDamaged", RprCodec.bool((p[i] & IFF_DAMAGED) != 0));
                a.put(m + "IsMalfunctioning", RprCodec.bool((p[i] & IFF_MALFUNCTION) != 0));
            }
        } else if (cls.startsWith(RRB)) {
            a.put("Code", RprCodec.enum8(p[0] & RRB_CODE_MASK));
            a.put("PowerReduction", RprCodec.bool((p[0] & RRB_POWER_REDUCTION) != 0));
            a.put("RadarEnhancement", RprCodec.bool((p[0] & RRB_RADAR_ENHANCEMENT) != 0));
            a.put("IsOn", RprCodec.bool((p[0] & IFF_ON) != 0));
            a.put("IsDamaged", RprCodec.bool((p[0] & IFF_DAMAGED) != 0));
            a.put("IsMalfunctioning", RprCodec.bool((p[0] & IFF_MALFUNCTION) != 0));
        }
        return new Embedded(cls, iffName(host, systemType, systemDesignator), a);
    }

    // ================================================================== emitters

    /** An object of an Electromagnetic Emission PDU (emitter system or beam) and its attributes. */
    public static final class Embedded {
        public final String className;
        public final String instanceName;
        public final Map<String, byte[]> attributes;

        Embedded(String className, String instanceName, Map<String, byte[]> attributes) {
            this.className = className;
            this.instanceName = instanceName;
            this.attributes = attributes;
        }
    }

    /** What one Electromagnetic Emission PDU says about one entity's emitters. */
    public static final class Emission {
        public final DisEntityId host;
        /** True for a heartbeat (state update indicator 0): every active system of the entity is listed. */
        public final boolean complete;
        public final java.util.List<Embedded> systems = new java.util.ArrayList<>();
        /** Beams by system instance name: every active beam of the listed systems (none: the system is off). */
        public final Map<String, java.util.List<Embedded>> beams = new LinkedHashMap<>();

        Emission(DisEntityId host, boolean complete) {
            this.host = host;
            this.complete = complete;
        }
    }

    /** Instance name of an emitter system: {@code "<host>/emitter-<emitter id number>"}. */
    public static String emitterName(DisEntityId host, int emitterId) {
        return host.text() + "/emitter-" + emitterId;
    }

    /** Instance name of a beam: {@code "<host>/emitter-<n>/beam-<beam id number>"}. */
    public static String beamName(String emitterName, int beamId) {
        return emitterName + "/beam-" + beamId;
    }

    /**
     * Electromagnetic Emission PDU (type 23) -> EmitterSystem objects and their RadarBeam /
     * JammerBeam objects (RPR GRIM). DIS 6 and 7 share the layout; the DIS 7 beam status
     * "deactivated" leaves a beam out. Records are walked by their length fields, so unknown
     * extensions are skipped. The reader is positioned after the header.
     */
    public Emission emission(DisPduHeader h, DisReader r) {
        DisEntityId host = DisEntityId.read(r);
        r.skip(4);                                              // event id: site and application
        int eventNumber = r.u16();
        int stateUpdate = r.u8();
        int systems = r.u8();
        r.skip(2);
        Emission out = new Emission(host, stateUpdate == 0);
        for (int si = 0; si < systems && r.remaining() >= 20; si++) {
            int sysStart = r.position();
            int sysLen = r.u8() * 4;
            int beams = r.u8();
            r.skip(2);
            int emitterName = r.u16();
            int function = r.u8();
            int emitterId = r.u8();
            float[] location = {r.f32(), r.f32(), r.f32()};
            String sysName = emitterName(host, emitterId);

            Map<String, byte[]> a = new LinkedHashMap<>();
            putHost(a, host);
            a.put("RelativePosition", RprCodec.float3(location));
            a.put("EmitterFunctionCode", RprCodec.enum8(function));
            a.put("EmitterType", RprCodec.u16(emitterName));
            a.put("EmitterIndex", RprCodec.enum8(emitterId));
            a.put("EventIdentifier", RprCodec.eventIdentifier(eventNumber, issuer(host)));
            out.systems.add(new Embedded(EMITTER_SYSTEM, sysName, a));

            java.util.List<Embedded> list = new java.util.ArrayList<>();
            for (int bi = 0; bi < beams && r.remaining() >= 52; bi++) {
                int beamStart = r.position();
                int beamLen = r.u8() * 4;
                int beamId = r.u8();
                int parameterIndex = r.u16();
                float frequency = r.f32(), frequencyRange = r.f32(), erp = r.f32(), prf = r.f32(), pulseWidth = r.f32();
                float azCenter = r.f32(), azSweep = r.f32(), elCenter = r.f32(), elSweep = r.f32(), sweepSync = r.f32();
                int beamFunction = r.u8();
                int targets = r.u8();
                int highDensity = r.u8();
                int status = r.u8();                            // DIS 7: bit 0 = deactivated; DIS 6: padding
                long jamming = r.u32();
                java.util.List<String> tracked = new java.util.ArrayList<>();
                for (int ti = 0; ti < targets && r.remaining() >= 8; ti++) {
                    DisEntityId t = DisEntityId.read(r);
                    r.skip(2);                                  // emitter and beam number of the target
                    if (!t.isNone()) tracked.add(t.text());
                }
                if (beamStart + beamLen > r.position()) r.seek(Math.min(beamStart + beamLen, r.length()));   // longer record: skip the rest
                if (protocolVersion >= 7 && (status & 1) != 0) continue;

                boolean jammer = beamFunction == BEAM_FUNCTION_JAMMING;
                Map<String, byte[]> b = new LinkedHashMap<>();
                b.put("BeamAzimuthCenter", RprCodec.f32(azCenter));
                b.put("BeamAzimuthSweep", RprCodec.f32(azSweep));
                b.put("BeamElevationCenter", RprCodec.f32(elCenter));
                b.put("BeamElevationSweep", RprCodec.f32(elSweep));
                b.put("BeamFunctionCode", RprCodec.enum8(beamFunction));
                b.put("BeamIdentifier", RprCodec.enum8(beamId));
                b.put("BeamParameterIndex", RprCodec.u16(parameterIndex));
                b.put("EffectiveRadiatedPower", RprCodec.f32(erp));
                b.put("EmissionFrequency", RprCodec.f32(frequency));
                b.put("EmitterSystemIdentifier", RprCodec.objectId(sysName));
                b.put("EventIdentifier", RprCodec.eventIdentifier(eventNumber, issuer(host)));
                b.put("FrequencyRange", RprCodec.f32(frequencyRange));
                b.put("PulseRepetitionFrequency", RprCodec.f32(prf));
                b.put("PulseWidth", RprCodec.f32(pulseWidth));
                b.put("SweepSynch", RprCodec.f32(sweepSync));
                if (jammer) {
                    b.put("JammingModeSequence", RprCodec.u32(jamming));
                    b.put("JammedObjectIdentifiers", RprCodec.objectIdArray(tracked));
                    b.put("HighDensityJam", RprCodec.enum8(highDensity != 0 ? 1 : 0));
                } else {
                    b.put("HighDensityTrack", RprCodec.enum8(highDensity != 0 ? 1 : 0));
                    b.put("TrackObjectIdentifiers", RprCodec.objectIdArray(tracked));
                }
                list.add(new Embedded(jammer ? JAMMER_BEAM : RADAR_BEAM, beamName(sysName, beamId), b));
            }
            out.beams.put(sysName, list);
            if (sysStart + sysLen > r.position()) r.seek(Math.min(sysStart + sysLen, r.length()));
        }
        return out;
    }

    // ================================================================== radio

    /**
     * NEW (Junktion): the RPR StreamTag of a DIS radio, the same in its RadioTransmitter object
     * and in its EncodedAudioRadioSignal interactions, so that audio can be related to the
     * transmitter (DIS has no stream tag): site, application, entity and radio id, 16 bits each.
     */
    public static long streamTag(DisEntityId host, int radioId) {
        return ((long) host.site << 48) | ((long) host.application << 32) | ((long) host.entity << 16) | (radioId & 0xFFFF);
    }

    /** NEW (Junktion): the host and radio id packed in a {@link #streamTag}; index 3 is the radio id. */
    public static int[] unpackStreamTag(long tag) {
        return new int[] {(int) (tag >>> 48) & 0xFFFF, (int) (tag >>> 32) & 0xFFFF, (int) (tag >>> 16) & 0xFFFF, (int) tag & 0xFFFF};
    }

    /** Instance name of a radio transmitter: {@code "<host>/radio-<id>"}. */
    public static String transmitterName(DisEntityId host, int radioId) {
        return host.text() + "/radio-" + radioId;
    }

    /** Instance name of a radio receiver: {@code "<host>/receiver-<id>"}. */
    public static String receiverName(DisEntityId host, int radioId) {
        return host.text() + "/receiver-" + radioId;
    }

    /** Transmitter PDU (type 25) -> RadioTransmitter. The reader is positioned after the header. */
    public Radio transmitter(DisPduHeader h, DisReader r) {
        DisEntityId host = DisEntityId.read(r);
        int radioId = r.u16();
        int kind = r.u8(), domain = r.u8(), country = r.u16(), category = r.u8(), nomVersion = r.u8(), nomenclature = r.u16();
        int transmitState = r.u8();
        int inputSource = r.u8();
        r.skip(2);                               // DIS 6: padding; DIS 7: number of variable transmitter parameters
        double[] antenna = {r.f64(), r.f64(), r.f64()};
        float[] relativeAntenna = {r.f32(), r.f32(), r.f32()};
        r.skip(4);                               // antenna pattern type and length (pattern not mapped)
        long frequency = r.i64();
        float bandwidth = r.f32();
        float power = r.f32();
        int spreadSpectrum = r.u16();
        int major = r.u16();
        int detail = r.u16();
        int system = r.u16();
        int cryptoSystem = r.u16();
        int cryptoKey = r.u16();

        Map<String, byte[]> a = new LinkedHashMap<>();
        putHost(a, host);
        a.put("RelativePosition", RprCodec.float3(relativeAntenna));
        a.put("CryptoSystem", RprCodec.u16(cryptoSystem));
        if (protocolVersion >= 7) {
            a.put("EncryptionKeyIdentifier", RprCodec.u16(cryptoKey & 0x7FFF));
            a.put("CryptographicMode", RprCodec.u32((cryptoKey >>> 15) & 1));    // 0 baseband, 1 diphase
        } else {
            a.put("EncryptionKeyIdentifier", RprCodec.u16(cryptoKey));
        }
        a.put("Frequency", RprCodec.u64(frequency));
        a.put("FrequencyBandwidth", RprCodec.f32(bandwidth));
        a.put("RadioIndex", RprCodec.u16(radioId));
        a.put("RadioInputSource", RprCodec.enum8(inputSource));
        a.put("RadioSystemType", RprCodec.radioType(kind, domain, country, category, nomVersion, nomenclature));
        a.put("RFModulationSystemType", RprCodec.u16(system));
        a.put("RFModulationType", RprCodec.rfModulationType(major, detail));
        a.put("StreamTag", RprCodec.u64(streamTag(host, radioId)));                 // NEW (Junktion)
        if (spreadSpectrum == 0) a.put("SpreadSpectrum", RprCodec.spreadSpectrumNone());
        a.put("TimeHopInUse", RprCodec.bool((spreadSpectrum & 0x4) != 0));
        a.put("TransmittedPower", RprCodec.f32(power));
        a.put("TransmitterOperationalStatus", RprCodec.enum8(transmitState));
        a.put("WorldLocation", RprCodec.worldLocation(antenna));
        return new Radio(RADIO_TRANSMITTER, transmitterName(host, radioId), host, radioId, a);
    }

    /** Receiver PDU (type 27) -> RadioReceiver. The reader is positioned after the header. */
    public Radio receiver(DisPduHeader h, DisReader r) {
        DisEntityId host = DisEntityId.read(r);
        int radioId = r.u16();
        int state = r.u16();
        r.skip(2);
        float power = r.f32();
        DisEntityId txHost = DisEntityId.read(r);
        int txRadio = r.u16();

        Map<String, byte[]> a = new LinkedHashMap<>();
        putHost(a, host);
        a.put("RadioIndex", RprCodec.u16(radioId));
        a.put("ReceivedPower", RprCodec.f32(power));
        if (!txHost.isNone()) a.put("ReceivedTransmitterIdentifier", RprCodec.objectId(transmitterName(txHost, txRadio)));
        a.put("ReceiverOperationalStatus", RprCodec.u16(state));
        return new Radio(RADIO_RECEIVER, receiverName(host, radioId), host, radioId, a);
    }

    /**
     * Signal PDU (type 26) -> one of the RadioSignal subclasses, by the encoding class (bits
     * 14-15 of the encoding scheme): encoded audio, raw binary, application specific, database
     * index. The reader is positioned after the header.
     */
    public Interaction signal(DisPduHeader h, DisReader r) {
        DisEntityId host = DisEntityId.read(r);
        int radioId = r.u16();
        int scheme = r.u16();
        int tdlType = r.u16();
        long sampleRate = r.u32();
        int dataLengthBits = r.u16();
        int samples = r.u16();
        int octets = (dataLengthBits + 7) / 8;
        byte[] data = r.bytes(Math.min(octets, r.remaining()));
        int encodingClass = (scheme >>> 14) & 0x3;
        int encodingType = scheme & 0x3FFF;
        String hostRadio = transmitterName(host, radioId);

        Map<String, byte[]> p = new LinkedHashMap<>();
        switch (encodingClass) {
            case 0:                                                  // encoded audio
                if (data.length == 0) return null;
                // NEW (Junktion): the stream tag of the transmitter (was 0)
                p.put("AudioData", RprCodec.audioData(streamTag(host, radioId), encodingType, sampleRate, dataLengthBits, samples, data));
                return new Interaction(ENCODED_AUDIO_SIGNAL, p);
            case 1:                                                  // raw binary (incl. tactical data links)
            case 2: {                                                // application specific
                p.put("HostRadioIndex", RprCodec.objectId(hostRadio));
                p.put("DataRate", RprCodec.u32(sampleRate));
                p.put("SignalDataLength", RprCodec.u16(dataLengthBits));
                if (data.length > 0) p.put("SignalData", RprCodec.octets(data));
                p.put("TacticalDataLinkType", RprCodec.u16(tdlType));
                p.put("TDLMessageCount", RprCodec.u16(encodingType));     // encoding type: the number of TDL messages
                if (encodingClass == 1) {
                    Interaction raw = new Interaction(RAW_BINARY_SIGNAL, p);
                    Interaction l16 = (tdlType == TDL_LINK16 || tdlType == TDL_LINK16_SURROGATE)
                            ? link16(hostRadio, encodingType, tdlType, sampleRate, dataLengthBits, data, raw) : null;
                    return l16 != null ? l16 : raw;
                }
                // the user protocol id is the first 32 bits of the data
                if (data.length >= 4) p.put("UserProtocolID", Arrays.copyOf(data, 4));
                return new Interaction(APPLICATION_SPECIFIC_SIGNAL, p);
            }
            default: {                                               // database index
                p.put("HostRadioIndex", RprCodec.objectId(hostRadio));
                if (data.length >= 12) {
                    DisReader d = new DisReader(data, 0, data.length);
                    p.put("DatabaseIndex", RprCodec.u32(d.u32()));
                    p.put("StartOffset", RprCodec.u32(d.u32()));
                    p.put("Duration", RprCodec.u32(d.u32()));
                }
                p.put("TacticalDataLinkType", RprCodec.u16(tdlType));
                p.put("TDLMessageCount", RprCodec.u16(encodingType));
                return new Interaction(DATABASE_INDEX_SIGNAL, p);
            }
        }
    }

    /**
     * Link 16 Signal PDU data (SISO-STD-002-2021 Annex B) -> the Link16RadioSignal subclass of its
     * Message Type Identifier: the 20-octet Link 16 Simulation Network Header split into the
     * Link16RadioSignal parameters, the message data copied octet for octet into the subclass's
     * parameters (JTIDSHeader / LETHeader + TADILJMessage, RTTAB, RTTReply, JTIDSHeader + voice
     * Data). The octets are copied, not reinterpreted, so both the 2006 (byte swapped) and the 2021
     * (bit stream) packing pass, with SISOSTD002Version telling which.
     *
     * @param fallback the RawBinaryRadioSignal, for a FOM without the Link-16 module
     * @return null when the data is too short or the message type unknown (then raw binary)
     */
    private Interaction link16(String hostRadio, int words, int tdlType, long dataRate, int dataLengthBits, byte[] data,
                               Interaction fallback) {
        int bits = Math.min(dataLengthBits, data.length * 8);
        if (bits < (LINK16_NETWORK_HEADER_OCTETS + LINK16_HEADER_WORD_OCTETS) * 8) return null;
        DisReader h = new DisReader(data, 0, data.length);
        int npg = h.u16();
        int net = h.u8();
        int tsec = h.u8();
        int msec = h.u8();
        int mti = h.u8();
        int version = h.u8();
        int l16Version = h.u8();
        long timeSlot = h.u32();
        long seconds = h.u32();
        long fraction = h.u32();
        if (mti < 0 || mti >= LINK16_CLASSES.length) return null;

        Map<String, byte[]> p = new LinkedHashMap<>();
        p.put("HostRadioIndex", RprCodec.objectId(hostRadio));
        p.put("DataRate", RprCodec.u32(dataRate));
        p.put("TacticalDataLinkType", RprCodec.u16(tdlType));
        p.put("TDLMessageCount", RprCodec.u16(words));
        p.put("NPGNumber", RprCodec.u16(npg));
        p.put("NetNumber", RprCodec.enum8(net));
        p.put("TSEC_CVLL", RprCodec.enum8(tsec));
        p.put("MSEC_CVLL", RprCodec.enum8(msec));
        p.put("SISOSTD002Version", RprCodec.enum8(version));
        p.put("Link16Version", RprCodec.enum8(l16Version));
        p.put("TimeSlotID", RprCodec.u32(timeSlot));
        p.put("PerceivedTransmitTime", concat(RprCodec.u32(seconds), RprCodec.u32(fraction)));

        int at = LINK16_NETWORK_HEADER_OCTETS;
        byte[] headerWord = Arrays.copyOfRange(data, at, at + LINK16_HEADER_WORD_OCTETS);
        at += LINK16_HEADER_WORD_OCTETS;
        int restBits = bits - at * 8;
        switch (mti) {
            case 1:
                p.put("RTTAB", headerWord);
                break;
            case 2:
                p.put("RTTReply", headerWord);
                break;
            case 3:
            case 4:
            case 5: {                                               // JTIDS voice: header word + voice data
                p.put("JTIDSHeader", headerWord);
                int octets = Math.min((restBits + 7) / 8, data.length - at);
                p.put("Data", Arrays.copyOfRange(data, at, at + octets));
                p.put("DataLength", RprCodec.u16(restBits));
                break;
            }
            default: {                                              // 0, 6, 7: header word + J-words
                p.put(mti == 6 ? "LETHeader" : "JTIDSHeader", headerWord);
                int n = Math.min(restBits / (LINK16_WORD_OCTETS * 8), (data.length - at) / LINK16_WORD_OCTETS);
                p.put("TADILJMessage", Arrays.copyOfRange(data, at, at + n * LINK16_WORD_OCTETS));
                break;
            }
        }
        return new Interaction(LINK16_CLASSES[mti], p, fallback);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] c = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }

    // ================================================================== simulation management

    /** Start/Resume PDU (type 13) -> StartResume. */
    public Interaction startResume(DisPduHeader h, DisReader r) {
        Map<String, byte[]> p = new LinkedHashMap<>();
        putEntities(p, r);
        p.put("RealWorldTime", RprCodec.clockTime(r.i32(), r.u32()));
        p.put("SimulationTime", RprCodec.clockTime(r.i32(), r.u32()));
        p.put("RequestIdentifier", RprCodec.u32(r.u32()));
        return new Interaction(START_RESUME, p);
    }

    /** Stop/Freeze PDU (type 14) -> StopFreeze. */
    public Interaction stopFreeze(DisPduHeader h, DisReader r) {
        Map<String, byte[]> p = new LinkedHashMap<>();
        putEntities(p, r);
        p.put("RealWorldTime", RprCodec.clockTime(r.i32(), r.u32()));
        p.put("Reason", RprCodec.enum8(r.u8()));
        int behaviour = r.u8();
        r.skip(2);
        p.put("RunInternalSimulationClock", RprCodec.bool((behaviour & 0x1) != 0));
        p.put("UpdateAttributes", RprCodec.bool((behaviour & 0x2) != 0));      // "transmit updates"
        p.put("ReflectValues", RprCodec.bool((behaviour & 0x4) != 0));         // "process updates"
        p.put("RequestIdentifier", RprCodec.u32(r.u32()));
        return new Interaction(STOP_FREEZE, p);
    }

    /** Acknowledge PDU (type 15) -> Acknowledge. */
    public Interaction acknowledge(DisPduHeader h, DisReader r) {
        Map<String, byte[]> p = new LinkedHashMap<>();
        putEntities(p, r);
        p.put("AcknowledgeFlag", RprCodec.u16(r.u16()));
        p.put("ResponseFlag", RprCodec.u16(r.u16()));
        p.put("RequestIdentifier", RprCodec.u32(r.u32()));
        return new Interaction(ACKNOWLEDGE, p);
    }

    // ================================================================== helpers

    /** Issuing object of an EventIdentifier: the firing / issuing entity ("" when it is 0-0-0). */
    private static String issuer(DisEntityId id) {
        return id.isNone() ? "" : id.text();
    }

    /** An RTIobjectId reference to an entity; left out for 0-0-0 ("no entity"). */
    private static void putRef(Map<String, byte[]> p, String name, DisEntityId id) {
        if (!id.isNone()) p.put(name, RprCodec.objectId(id.text()));
    }

    /** EmbeddedSystem attributes of a radio: the host's EntityIdentifier and instance name. */
    private static void putHost(Map<String, byte[]> a, DisEntityId host) {
        a.put("EntityIdentifier", RprCodec.entityIdentifier(host));
        a.put("HostObjectIdentifier", RprCodec.objectId(host.text()));
    }

    /** OriginatingEntity and ReceivingEntity (EntityIdentifierStruct) of a simulation management PDU. */
    private static void putEntities(Map<String, byte[]> p, DisReader r) {
        p.put("OriginatingEntity", RprCodec.entityIdentifier(DisEntityId.read(r)));
        p.put("ReceivingEntity", RprCodec.entityIdentifier(DisEntityId.read(r)));
    }
}
