package com.simweaver.common.iem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One enumerator of an {@link EnumeratedDataType}. The OMT allows an enumerator to have more than
 * one value; {@link #getValue()} is the first, which is what an encoder should send.
 */
public final class Enumerator {

    private final EnumeratedDataType type;
    private final String name;
    private final long[] values;
    private final int index;
    private final FomModule module;

    Enumerator(EnumeratedDataType type, String name, long[] values, int index, FomModule module) {
        this.type = type;
        this.name = name;
        this.values = values;
        this.index = index;
        this.module = module;
    }

    public EnumeratedDataType getEnumeratedType() {
        return type;
    }

    public String getName() {
        return name;
    }

    /** The first (primary) value. */
    public long getValue() {
        return values[0];
    }

    /** All values, in FOM order. */
    public List<Long> getValues() {
        List<Long> out = new ArrayList<Long>(values.length);
        for (long v : values) {
            out.add(v);
        }
        return Collections.unmodifiableList(out);
    }

    /** True if {@code v} is one of this enumerator's values. */
    public boolean hasValue(long v) {
        for (long x : values) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }

    /** 0-based position within the enumerated type. */
    public int getIndex() {
        return index;
    }

    /** Module that contributed this enumerator (relevant when enumerations are extended by several modules). */
    public FomModule getModule() {
        return module;
    }

    long[] rawValues() {
        return values;
    }

    @Override
    public String toString() {
        return type.getName() + "." + name + "=" + values[0];
    }
}
