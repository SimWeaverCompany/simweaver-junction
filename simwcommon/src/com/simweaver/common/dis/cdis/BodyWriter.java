package com.simweaver.common.dis.cdis;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the body of one C-DIS PDU as a run of segments: fixed fields (always written) and
 * groups that belong to a field present flag. The PDU's layout is
 * {@code [flags][units][full update flag][segments]}; a flag is set when its groups are written.
 *
 * <p>For partial updates ({@link #partial}) a flag whose groups encode to the same bits as in the
 * last update of the object is left out.
 */
final class BodyWriter {

    private static final class Segment {
        final int group;
        final int flag;
        final int unit;
        final BitWriter bits = new BitWriter(16);
        boolean omitted;

        Segment(int group, int flag, int unit) {
            this.group = group;
            this.flag = flag;
            this.unit = unit;
        }
    }

    private final int flagCount;
    private final boolean[] units;
    private final boolean hasFullFlag;
    private final int groupCount;
    private final List<Segment> segments = new ArrayList<Segment>();
    private boolean full = true;
    private int kept;

    /**
     * @param flagCount   field present flags of the PDU
     * @param unitCount   units flags of the PDU
     * @param hasFullFlag whether the PDU has the full update flag
     * @param groupCount  group ids used are 0..groupCount-1
     */
    BodyWriter(int flagCount, int unitCount, boolean hasFullFlag, int groupCount) {
        this.flagCount = flagCount;
        this.units = new boolean[unitCount];
        this.hasFullFlag = hasFullFlag;
        this.groupCount = groupCount;
    }

    /** @return a writer for fields that are always present */
    BitWriter fixed() {
        Segment s = new Segment(-1, -1, -1);
        segments.add(s);
        return s.bits;
    }

    /**
     * @param group the group id (several groups may share a flag)
     * @param flag  the field present flag, 0 = the first (most significant) flag
     * @return a writer for the group's fields
     */
    BitWriter group(int group, int flag) {
        return group(group, flag, -1);
    }

    /**
     * A group whose values are in the units a units flag gives.
     *
     * @param unit the units flag index, -1 for none
     */
    BitWriter group(int group, int flag, int unit) {
        Segment s = new Segment(group, flag, unit);
        segments.add(s);
        return s.bits;
    }

    /** A flag whose groups a partial update always carries. */
    void keep(int flag) {
        kept |= 1 << (flagCount - 1 - flag);
    }

    void unit(int index, boolean set) {
        units[index] = set;
    }

    boolean isFull() {
        return full;
    }

    /** @return the flags of the groups written so far, bit (flagCount-1-f) for flag f */
    int usedFlags() {
        int m = 0;
        for (Segment s : segments) {
            if (s.flag >= 0) {
                m |= 1 << (flagCount - 1 - s.flag);
            }
        }
        return m;
    }

    /**
     * Makes this a partial update against what was last sent for the object, or a full one when
     * {@code forceFull} or nothing was sent before or a group fell out of use. Updates the state.
     *
     * @param state     the object's encoder state
     * @param forceFull a full update is due
     */
    void partial(ObjectStates.Encoded state, boolean forceFull) {
        int used = usedFlags();
        BitSlice[] now = new BitSlice[groupCount];
        for (Segment s : segments) {
            if (s.group >= 0) {
                BitWriter key = new BitWriter(s.bits.bitLength() / 8 + 2);
                if (s.unit >= 0) {
                    key.flag(units[s.unit]);
                }
                now[s.group] = key.append(s.bits).toSlice();
            }
        }
        boolean fullUpdate = forceFull || state.groups == null || (state.usedFlags & ~used) != 0;
        if (!fullUpdate) {
            int changed = kept;
            for (Segment s : segments) {
                if (s.group >= 0 && !now[s.group].equals(state.groups[s.group])) {
                    changed |= 1 << (flagCount - 1 - s.flag);
                }
            }
            for (Segment s : segments) {
                if (s.flag >= 0 && (changed & (1 << (flagCount - 1 - s.flag))) == 0) {
                    s.omitted = true;
                }
            }
        }
        full = fullUpdate;
        state.groups = now;
        state.usedFlags = used;
    }

    /** Writes flags, units, the full update flag and the segments. */
    void writeTo(BitWriter out) {
        int present = 0;
        for (Segment s : segments) {
            if (s.flag >= 0 && !s.omitted) {
                present |= 1 << (flagCount - 1 - s.flag);
            }
        }
        out.bits(present, flagCount);
        for (boolean u : units) {
            out.flag(u);
        }
        if (hasFullFlag) {
            out.flag(full);
        }
        for (Segment s : segments) {
            if (!s.omitted) {
                out.append(s.bits);
            }
        }
    }
}
