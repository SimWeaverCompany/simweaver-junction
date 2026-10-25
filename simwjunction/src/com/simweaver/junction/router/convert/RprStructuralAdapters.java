package com.simweaver.junction.router.convert;

import com.simweaver.common.hla.codec.HValue;
import com.simweaver.common.hla.codec.TranscodeRules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The RPR v2.0 / v3.0 differences that renames cannot express, as value adapters (see
 * {@link TranscodeRules.Builder#adapter}). Always active, with or without a mapping file.
 *
 * <h2>Articulated parameters</h2>
 * v2 {@code ArticulatedParameterStructLengthlessArray}: records of change indicator, part
 * attached to and a variant on {@code ParameterTypeEnum32} (articulated part: class, metric,
 * value; attached part: station, store type). v3 {@code VariableParameterVariantStructArray}:
 * a variant per record on {@code VariableParameterRecordTypeEnum8}, the common fields inside
 * each alternative, the part class and metric as separate fields. Both describe the DIS variable
 * parameter record, so the conversion is exact for articulated and attached parts; v3 records
 * of the kinds v2 has no alternative for (separation, entity type, entity association) are
 * left out.
 *
 * <h2>Jamming technique</h2>
 * v2 {@code JammerBeam.JammingModeSequence} is 32 bits, v3 a {@code JammingTechniqueStruct} of
 * kind, category, subcategory and specific: the DIS 6 jamming mode sequence and the DIS 7
 * jamming technique record share those four octets.
 *
 * <h2>Radio type</h2>
 * v2 {@code RadioTypeStruct} has the DIS 6 layout (category, nomenclature version, 16-bit
 * nomenclature), v3 the DIS 7 one (category, subcategory, specific, extra): the same eight
 * octets, so the nomenclature version is the subcategory and the nomenclature is specific
 * (high octet) and extra (low octet).
 *
 * <h2>Silent entities</h2>
 * v2 {@code SilentEntityStruct} gives the number of appearance records in a field of its own
 * ({@code NumberOfAppearanceRecords}, the count of a lengthless array); v3 uses a counted array.
 * Down, the count is set from the array.
 */
final class RprStructuralAdapters {

    static final String V2_ARTICULATIONS = "ArticulatedParameterStructLengthlessArray";
    static final String V3_VARIABLE_PARAMETERS = "VariableParameterVariantStructArray";

    private static final int ARTICULATED_PART = 0;
    private static final int ATTACHED_PART = 1;

    private RprStructuralAdapters() {
    }

    /** Adds the adapters of both directions. */
    static void register(TranscodeRules.Builder up, TranscodeRules.Builder down) {
        up.adapter(V2_ARTICULATIONS, V3_VARIABLE_PARAMETERS, RprStructuralAdapters::articulationsUp);
        down.adapter(V3_VARIABLE_PARAMETERS, V2_ARTICULATIONS, RprStructuralAdapters::articulationsDown);
        down.adapter("SilentEntityStruct", "SilentEntityStruct", RprStructuralAdapters::silentEntityDown);
        up.adapter("RadioTypeStruct", "RadioTypeStruct", RprStructuralAdapters::radioTypeUp);
        down.adapter("RadioTypeStruct", "RadioTypeStruct", RprStructuralAdapters::radioTypeDown);
        up.adapter("UnsignedInteger32", "JammingTechniqueStruct", RprStructuralAdapters::jammingUp);
        down.adapter("JammingTechniqueStruct", "UnsignedInteger32", RprStructuralAdapters::jammingDown);
    }

    /** v3 silent entity record to v2: the appearance record count from the array. */
    static HValue silentEntityDown(HValue v) {
        Map<String, HValue> m = new LinkedHashMap<String, HValue>();
        HValue appearance = v.field("EntityAppearance");
        m.put("NumberOfEntitiesOfThisType", HValue.ofInt(number(v.field("NumberOfEntitiesOfThisType"))));
        m.put("NumberOfAppearanceRecords", HValue.ofInt(appearance == null ? 0 : appearance.size()));
        m.put("EntityType", untyped(v.field("EntityType")));
        m.put("EntityAppearance", appearance == null ? HValue.ofArray(new ArrayList<HValue>()) : appearance);
        return HValue.ofRecord(m);
    }

    /** v2 JammingModeSequence (32 bits) to the v3 JammingTechniqueStruct: the same four octets, as in DIS 6 / 7. */
    static HValue jammingUp(HValue v) {
        long n = number(v) & 0xFFFFFFFFL;
        Map<String, HValue> m = new LinkedHashMap<String, HValue>();
        m.put("Kind", HValue.ofInt((n >>> 24) & 0xFF));
        m.put("Category", HValue.ofInt((n >>> 16) & 0xFF));
        m.put("Subcategory", HValue.ofInt((n >>> 8) & 0xFF));
        m.put("Specific", HValue.ofInt(n & 0xFF));
        return HValue.ofRecord(m);
    }

    /** v3 JammingTechniqueStruct to the v2 JammingModeSequence. */
    static HValue jammingDown(HValue v) {
        return HValue.ofInt(((number(v.field("Kind")) & 0xFF) << 24) | ((number(v.field("Category")) & 0xFF) << 16)
                | ((number(v.field("Subcategory")) & 0xFF) << 8) | (number(v.field("Specific")) & 0xFF));
    }

    /** DIS 6 radio type to DIS 7. */
    static HValue radioTypeUp(HValue v) {
        Map<String, HValue> m = new LinkedHashMap<String, HValue>();
        m.put("EntityKind", HValue.ofInt(number(v.field("EntityKind"))));
        m.put("Domain", HValue.ofInt(number(v.field("Domain"))));
        m.put("CountryCode", HValue.ofInt(number(v.field("CountryCode"))));
        m.put("Category", HValue.ofInt(number(v.field("Category"))));
        long nomenclature = number(v.field("Nomenclature"));
        m.put("Subcategory", HValue.ofInt(number(v.field("NomenclatureVersion"))));
        m.put("Specific", HValue.ofInt((nomenclature >>> 8) & 0xFF));
        m.put("Extra", HValue.ofInt(nomenclature & 0xFF));
        return HValue.ofRecord(m);
    }

    /** DIS 7 radio type to DIS 6. */
    static HValue radioTypeDown(HValue v) {
        Map<String, HValue> m = new LinkedHashMap<String, HValue>();
        m.put("EntityKind", HValue.ofInt(number(v.field("EntityKind"))));
        m.put("Domain", HValue.ofInt(number(v.field("Domain"))));
        m.put("CountryCode", HValue.ofInt(number(v.field("CountryCode"))));
        m.put("Category", HValue.ofInt(number(v.field("Category"))));
        m.put("NomenclatureVersion", HValue.ofInt(number(v.field("Subcategory"))));
        m.put("Nomenclature", HValue.ofInt(((number(v.field("Specific")) & 0xFF) << 8) | (number(v.field("Extra")) & 0xFF)));
        return HValue.ofRecord(m);
    }

    /** v2 records to v3 variants. */
    static HValue articulationsUp(HValue array) {
        List<HValue> out = new ArrayList<HValue>();
        for (HValue rec : array.getValues()) {
            HValue pv = rec.field("ParameterValue");
            HValue alt = pv == null ? null : pv.getAlternative();
            if (alt == null) {
                continue;
            }
            int kind = (int) pv.asLong();
            Map<String, HValue> f = new LinkedHashMap<String, HValue>();
            if (kind == ARTICULATED_PART) {
                f.put("ChangeIndicator", HValue.ofInt(number(rec.field("ArticulatedParameterChange"))));
                f.put("PartAttachedTo", HValue.ofInt(number(rec.field("PartAttachedTo"))));
                f.put("ParameterTypeClass", HValue.ofInt(number(alt.field("Class"))));
                f.put("ParameterTypeMetric", HValue.ofInt(number(alt.field("TypeMetric"))));
                f.put("Value", alt.field("Value") == null ? HValue.ofFloat(0) : HValue.ofFloat(alt.field("Value").asDouble()));
                out.add(HValue.ofVariant(ARTICULATED_PART, null, "ArticulatedPart", HValue.ofRecord(f)));
            } else if (kind == ATTACHED_PART) {
                f.put("DetachedIndicator", HValue.ofInt(0));
                f.put("PartAttachedTo", HValue.ofInt(number(rec.field("PartAttachedTo"))));
                f.put("ParameterType", HValue.ofInt(number(alt.field("Station"))));
                f.put("AttachedPartType", untyped(alt.field("StoreType")));
                out.add(HValue.ofVariant(ATTACHED_PART, null, "AttachedPart", HValue.ofRecord(f)));
            }
        }
        return HValue.ofArray(out);
    }

    /** v3 variants to v2 records (articulated and attached parts only). */
    static HValue articulationsDown(HValue array) {
        List<HValue> out = new ArrayList<HValue>();
        for (HValue v : array.getValues()) {
            HValue alt = v.getAlternative();
            if (alt == null) {
                continue;
            }
            int kind = (int) v.asLong();
            Map<String, HValue> inner = new LinkedHashMap<String, HValue>();
            Map<String, HValue> rec = new LinkedHashMap<String, HValue>();
            if (kind == ARTICULATED_PART) {
                inner.put("Class", HValue.ofInt(number(alt.field("ParameterTypeClass"))));
                inner.put("TypeMetric", HValue.ofInt(number(alt.field("ParameterTypeMetric"))));
                inner.put("Value", alt.field("Value") == null ? HValue.ofFloat(0) : HValue.ofFloat(alt.field("Value").asDouble()));
                rec.put("ArticulatedParameterChange", HValue.ofInt(number(alt.field("ChangeIndicator"))));
                rec.put("PartAttachedTo", HValue.ofInt(number(alt.field("PartAttachedTo"))));
                rec.put("ParameterValue", HValue.ofVariant(ARTICULATED_PART, null, "ArticulatedParts", HValue.ofRecord(inner)));
            } else if (kind == ATTACHED_PART) {
                inner.put("Station", HValue.ofInt(number(alt.field("ParameterType"))));
                inner.put("StoreType", untyped(alt.field("AttachedPartType")));
                rec.put("ArticulatedParameterChange", HValue.ofInt(0));
                rec.put("PartAttachedTo", HValue.ofInt(number(alt.field("PartAttachedTo"))));
                rec.put("ParameterValue", HValue.ofVariant(ATTACHED_PART, null, "AttachedParts", HValue.ofRecord(inner)));
            } else {
                continue;
            }
            out.add(HValue.ofRecord(rec));
        }
        return HValue.ofArray(out);
    }

    /** A number from an integer, enumeration or octet value. */
    private static long number(HValue v) {
        if (v == null) {
            return 0;
        }
        if (v.getKind() == HValue.Kind.BYTES) {
            byte[] b = v.asBytes();
            long n = 0;
            for (byte x : b) {
                n = (n << 8) | (x & 0xFF);
            }
            return n;
        }
        return v.asLong();
    }

    /** A record with its fields by name and numbers without their enumerator names (matched by value). */
    private static HValue untyped(HValue v) {
        if (v == null || v.getKind() != HValue.Kind.RECORD) {
            return v;
        }
        Map<String, HValue> m = new LinkedHashMap<String, HValue>();
        List<String> names = v.getFieldNames();
        for (int i = 0; i < names.size(); i++) {
            HValue f = v.get(i);
            m.put(names.get(i), f.isNumeric() || f.getKind() == HValue.Kind.BYTES ? HValue.ofInt(number(f)) : f);
        }
        return HValue.ofRecord(m);
    }
}
