package com.simweaver.rift.datasources.dis;

import java.util.List;

/**
 * HLA encodings of the RPR FOM v2.0 datatypes (SISO-STD-001.1-2015) that the DIS mapping uses.
 * Field order, sizes and padding follow the FOM's datatype tables and the HLA encoding rules
 * (fixed records: each field on its octet boundary; variant records: the alternative on its
 * boundary after the discriminant; RPR lengthless and null-terminated arrays: no count).
 *
 * <p>The comments give each record's octet boundary and size. Stateless.
 */
public final class RprCodec {

    private RprCodec() {
    }

    // ---------------------------------------------------------------- basics

    /** HLAoctet-based enumeration or Octet. */
    public static byte[] enum8(int v) {
        return new byte[] {(byte) v};
    }

    /** RPRunsignedInteger16BE-based enumeration, UnsignedInteger16. */
    public static byte[] u16(int v) {
        return new HlaWriter(2).u16(v).toByteArray();
    }

    /** RPRunsignedInteger32BE-based enumeration, UnsignedInteger32. */
    public static byte[] u32(long v) {
        return new HlaWriter(4).u32(v).toByteArray();
    }

    /** RPRunsignedInteger64BE (UnsignedInteger64, FrequencyHertzUnsignedInteger64). */
    public static byte[] u64(long v) {
        return new HlaWriter(8).u64(v).toByteArray();
    }

    /** HLAfloat32BE-based simple types. */
    public static byte[] f32(float v) {
        return new HlaWriter(4).f32(v).toByteArray();
    }

    /** RPRboolean (enumeration on HLAoctet: False 0, True 1). */
    public static byte[] bool(boolean v) {
        return enum8(v ? 1 : 0);
    }

    /** RTIobjectId: RPRnullTerminatedArray of HLAASCIIchar. */
    public static byte[] objectId(String name) {
        return new HlaWriter(name.length() + 1).nullTerminatedAscii(name).toByteArray();
    }

    /** RTIobjectIdArray: HLAvariableArray (count, then the names) of RTIobjectId. */
    public static byte[] objectIdArray(java.util.List<String> names) {
        int size = 4;
        for (String n : names) size += n.length() + 1;
        HlaWriter w = new HlaWriter(size).i32(names.size());
        for (String n : names) w.nullTerminatedAscii(n);
        return w.toByteArray();
    }

    /** RPRlengthlessArray of Octet (e.g. SignalDataLengthlessArray1Plus): the octets as they are. */
    public static byte[] octets(byte[] b) {
        return b.clone();
    }

    // ---------------------------------------------------------------- records

    /** EntityTypeStruct (boundary 2, 8 octets). */
    public static byte[] entityType(DisEntityType t) {
        HlaWriter w = new HlaWriter(8);
        writeEntityType(w, t);
        return w.toByteArray();
    }

    static void writeEntityType(HlaWriter w, DisEntityType t) {
        w.align(2).octet(t.kind).octet(t.domain).u16(t.country)
                .octet(t.category).octet(t.subcategory).octet(t.specific).octet(t.extra);
    }

    /** EntityIdentifierStruct {FederateIdentifierStruct {SiteID, ApplicationID}, EntityNumber} (boundary 2, 6 octets). */
    public static byte[] entityIdentifier(DisEntityId id) {
        return new HlaWriter(6).u16(id.site).u16(id.application).u16(id.entity).toByteArray();
    }

    /** RadioTypeStruct {EntityKind, Domain, CountryCode, Category, NomenclatureVersion, Nomenclature} (boundary 2, 8 octets). */
    public static byte[] radioType(int kind, int domain, int country, int category, int nomenclatureVersion, int nomenclature) {
        return new HlaWriter(8).octet(kind).octet(domain).u16(country).octet(category).octet(nomenclatureVersion)
                .u16(nomenclature).toByteArray();
    }

    /** WorldLocationStruct {X, Y, Z: MeterFloat64} (boundary 8, 24 octets). */
    public static byte[] worldLocation(double[] xyz) {
        HlaWriter w = new HlaWriter(24);
        writeWorldLocation(w, xyz);
        return w.toByteArray();
    }

    static void writeWorldLocation(HlaWriter w, double[] xyz) {
        w.align(8).f64(xyz[0]).f64(xyz[1]).f64(xyz[2]);
    }

    /**
     * Any record of three HLAfloat32BE fields (boundary 4, 12 octets): VelocityVectorStruct,
     * AccelerationVectorStruct, AngularVelocityVectorStruct, OrientationStruct,
     * RelativePositionStruct, EntityCoordinateVectorStruct.
     */
    public static byte[] float3(float[] v) {
        HlaWriter w = new HlaWriter(12);
        writeFloat3(w, v);
        return w.toByteArray();
    }

    static void writeFloat3(HlaWriter w, float[] v) {
        w.align(4).f32(v[0]).f32(v[1]).f32(v[2]);
    }

    /** MarkingStruct {MarkingEncodingType: MarkingEncodingEnum8, MarkingData: MarkingArray11} (boundary 1, 12 octets). */
    public static byte[] marking(int charset, byte[] eleven) {
        HlaWriter w = new HlaWriter(12).octet(charset);
        byte[] m = new byte[11];
        System.arraycopy(eleven, 0, m, 0, Math.min(11, eleven.length));
        return w.bytes(m).toByteArray();
    }

    /** EventIdentifierStruct {EventCount: UnsignedInteger16, IssuingObjectIdentifier: RTIobjectId} (boundary 2). */
    public static byte[] eventIdentifier(int eventCount, String issuingObject) {
        return new HlaWriter(16).u16(eventCount).nullTerminatedAscii(issuingObject).toByteArray();
    }

    /** ClockTimeStruct {Hours: HLAinteger32BE, TimePastTheHour: RPRunsignedInteger32BE} (boundary 4, 8 octets). */
    public static byte[] clockTime(int hours, long timePastTheHour) {
        return new HlaWriter(8).i32(hours).u32(timePastTheHour).toByteArray();
    }

    // ---------------------------------------------------------------- Spatial

    /**
     * SpatialVariantStruct (HLAvariantRecord, boundary 8): the DeadReckoningAlgorithmEnum8
     * discriminant, padding to 8, then the alternative record of the algorithm:
     *
     * <pre>
     * Static            SpatialStaticStruct  WorldLocation, IsFrozen, Orientation                                 40 octets
     * FPW, FPB          SpatialFPStruct      ... , VelocityVector                                                 52
     * RPW, RPB          SpatialRPStruct      ... , VelocityVector, AngularVelocity                                64
     * RVW, RVB          SpatialRVStruct      ... , VelocityVector, AccelerationVector, AngularVelocity            76
     * FVW, FVB          SpatialFVStruct      ... , VelocityVector, AccelerationVector                             64
     * </pre>
     *
     * The FOM has no alternative for algorithm 0 (Other) or values above 9; those are sent as
     * DRM_FPW, so that position and velocity are kept.
     */
    public static byte[] spatial(int algorithm, double[] location, boolean frozen, float[] orientation,
                                 float[] velocity, float[] acceleration, float[] angularVelocity) {
        int alg = algorithm >= 1 && algorithm <= 9 ? algorithm : 2;
        HlaWriter w = new HlaWriter(88);
        w.octet(alg).align(8);
        writeWorldLocation(w, location);
        w.octet(frozen ? 1 : 0);                                   // IsFrozen: RPRboolean
        writeFloat3(w, orientation);
        if (alg == 1) return w.toByteArray();                      // Static
        writeFloat3(w, velocity);
        switch (alg) {
            case 2: case 6:                                        // FPW, FPB
                break;
            case 3: case 7:                                        // RPW, RPB
                writeFloat3(w, angularVelocity);
                break;
            case 4: case 8:                                        // RVW, RVB
                writeFloat3(w, acceleration);
                writeFloat3(w, angularVelocity);
                break;
            default:                                               // 5, 9: FVW, FVB
                writeFloat3(w, acceleration);
                break;
        }
        return w.toByteArray();
    }

    // ---------------------------------------------------------------- articulated parameters

    /**
     * ArticulatedParameterStructLengthlessArray: one ArticulatedParameterStruct per record
     * (boundary 4, 20 octets each, so no padding between them):
     *
     * <pre>
     * ArticulatedParameterChange: Octet       @0
     * PartAttachedTo: UnsignedInteger16       @2
     * ParameterValue: ParameterValueVariantStruct
     *   ArticulatedParameterType: ParameterTypeEnum32 @4
     *   ArticulatedPart: {Class, TypeMetric: enum32, Value: Float32}   @8
     *   AttachedPart:    {Station: StationEnum32, StoreType: EntityTypeStruct} @8
     * </pre>
     *
     * Records whose designator has no alternative in the FOM (DIS 7 separation, entity type,
     * entity association) are left out.
     */
    public static byte[] articulatedParameters(List<DisArticulation> records) {
        HlaWriter w = new HlaWriter(20 * Math.max(1, records.size()));
        for (DisArticulation a : records) {
            if (a.designator != DisArticulation.ARTICULATED_PART && a.designator != DisArticulation.ATTACHED_PART) continue;
            w.align(4);
            w.octet(a.change).align(2).u16(a.attachedTo);
            w.align(4).u32(a.designator);
            if (a.designator == DisArticulation.ARTICULATED_PART) {
                long metric = a.parameterType & 0x1F;                  // low 5 bits: type metric
                long cls = a.parameterType - metric;                   // the rest: part class (multiple of 32)
                w.u32(cls).u32(metric).bytes(a.value, 0, 4);           // Value: the float32 in the first 4 octets
            } else {
                w.u32(a.parameterType);                                // station
                DisReader v = new DisReader(a.value, 0, 8);
                writeEntityType(w, DisEntityType.read(v));
            }
        }
        return w.toByteArray();
    }

    /** Number of records {@link #articulatedParameters} writes. */
    public static int articulatedParameterCount(List<DisArticulation> records) {
        int n = 0;
        for (DisArticulation a : records) {
            if (a.designator == DisArticulation.ARTICULATED_PART || a.designator == DisArticulation.ATTACHED_PART) n++;
        }
        return n;
    }

    // ---------------------------------------------------------------- radio

    /**
     * RFModulationTypeVariantStruct: MajorRFModulationTypeEnum16, then the detail as the
     * enumeration of that major type (boundary 2, 4 octets). Majors without an alternative in
     * the FOM (0 Other, 7 and up) give the discriminant only (2 octets).
     */
    public static byte[] rfModulationType(int major, int detail) {
        HlaWriter w = new HlaWriter(4).u16(major);
        if (major >= 1 && major <= 6) w.u16(detail);
        return w.toByteArray();
    }

    /** SpreadSpectrumVariantStruct with SpreadSpectrumType None (discriminant only, 2 octets). */
    public static byte[] spreadSpectrumNone() {
        return u16(0);
    }

    /**
     * AudioDataTypeStruct (boundary 8): StreamTag u64 @0, EncodingType enum32 @8, SampleRate u32 @12,
     * DataLength u16 @16, SampleCount u32 @20, Data: lengthless octets @24.
     */
    public static byte[] audioData(long streamTag, long encodingType, long sampleRate, int dataLengthBits, long sampleCount, byte[] data) {
        return new HlaWriter(24 + data.length).u64(streamTag).u32(encodingType).u32(sampleRate)
                .u16(dataLengthBits).align(4).u32(sampleCount).bytes(data).toByteArray();
    }
}
