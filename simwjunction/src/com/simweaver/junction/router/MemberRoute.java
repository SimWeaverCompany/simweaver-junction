package com.simweaver.junction.router;

import com.simweaver.common.hla.codec.HValue;
import com.simweaver.common.hla.codec.TranscodeRules;
import com.simweaver.common.iem.DataType;

import java.util.function.UnaryOperator;

/**
 * How one attribute or parameter goes from a source endpoint to a destination endpoint:
 * passed on as is, with object references renamed, or converted to another datatype. When
 * the router has value rules for a datatype the member holds ({@link ValueMap}), matching values
 * are replaced first, whatever the operation. Immutable; {@link #convert} is thread safe.
 */
public final class MemberRoute {

    /** What happens to the value. */
    public enum Op {
        /** Same encoding, no references: the octets are passed on. */
        PASS,
        /** Same encoding, holds object instance names: decoded, names replaced, encoded. */
        REWRITE_REFS,
        /** Different encodings: decoded with the source model, encoded with the destination model. */
        TRANSCODE
    }

    final int sourceMember;
    final int destinationMember;
    final String sourceName;
    final String destinationName;
    final Op op;
    final DataType sourceType;
    final DataType destinationType;
    final ModelTools source;
    final ModelTools destination;
    final TranscodeRules rules;
    /** The source model's value rules when they touch this member's datatype, else null. */
    final ValueMap values;

    MemberRoute(int sourceMember, int destinationMember, String sourceName, String destinationName, Op op,
                DataType sourceType, DataType destinationType, ModelTools source, ModelTools destination, TranscodeRules rules) {
        this(sourceMember, destinationMember, sourceName, destinationName, op, sourceType, destinationType, source, destination, rules, null);
    }

    MemberRoute(int sourceMember, int destinationMember, String sourceName, String destinationName, Op op,
                DataType sourceType, DataType destinationType, ModelTools source, ModelTools destination, TranscodeRules rules,
                ValueMap values) {
        this.sourceMember = sourceMember;
        this.destinationMember = destinationMember;
        this.sourceName = sourceName;
        this.destinationName = destinationName;
        this.op = op;
        this.sourceType = sourceType;
        this.destinationType = destinationType;
        this.source = source;
        this.destination = destination;
        this.rules = rules;
        this.values = values != null && values.touches(sourceType) ? values : null;
    }

    /** @return the source member id */
    public int getSourceMember() {
        return sourceMember;
    }

    /** @return the destination member id */
    public int getDestinationMember() {
        return destinationMember;
    }

    /** @return the operation */
    public Op getOp() {
        return op;
    }

    /** @return true when value rules may replace (part of) this member's values */
    public boolean hasValueRules() {
        return values != null;
    }

    /**
     * @param value   the value in the source encoding
     * @param rename  object instance name in the source to the name in the destination
     * @param counter receives conversion problems
     * @return the value in the destination encoding, or null when it cannot be converted
     */
    byte[] convert(byte[] value, UnaryOperator<String> rename, RouterStats counter) {
        if (op == Op.PASS && values == null) {
            return value;
        }
        try {
            HValue v = source.codec().decode(sourceType, value);
            HValue r = source.analysis().containsReference(sourceType) ? source.analysis().rewriteReferences(v, rename) : v;
            if (r != v && op != Op.TRANSCODE) {
                counter.referencesRewritten.incrementAndGet();
            }
            HValue x = values == null ? r : values.apply(r, counter);
            if (op != Op.TRANSCODE) {
                if (x == v) {
                    return value;                       // no name changed, no value replaced
                }
                return destination.codec().encode(destinationType, x);
            }
            counter.transcoded.incrementAndGet();
            return destination.codec().encode(destinationType, x, rules, counter);
        } catch (RuntimeException e) {          // HlaCodecException and anything unexpected
            counter.conversionFailure(sourceName + " -> " + destinationName + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Encodes a value given as HValue (from a converter that builds values itself, e.g. DIS).
     *
     * @return the octets, or null when it cannot be encoded
     */
    byte[] encode(HValue v, RouterStats counter) {
        try {
            return destination.codec().encode(destinationType, v, rules, counter);
        } catch (RuntimeException e) {
            counter.conversionFailure(destinationName + ": " + e.getMessage());
            return null;
        }
    }

    @Override
    public String toString() {
        return sourceName + (sourceName.equals(destinationName) ? "" : " -> " + destinationName) + " (" + op + (values != null ? ", value rules" : "") + ")";
    }
}
