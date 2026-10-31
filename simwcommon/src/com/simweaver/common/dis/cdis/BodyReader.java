package com.simweaver.common.dis.cdis;

/**
 * Reads the body of one C-DIS PDU laid out as {@code [flags][units][full update flag][fields]}
 * (see {@link BodyWriter}). For a partial update of a stateful object the groups that are not in
 * the PDU are read from the object's state (the bits of its last update), so a PDU decoder reads
 * every group the same way whether it came in this PDU or before.
 */
final class BodyReader {

    /** A group to read: where its bits are and the units its values are in. */
    static final class Group {
        final BitReader in;
        final boolean unit;
        final boolean fromPdu;

        Group(BitReader in, boolean unit, boolean fromPdu) {
            this.in = in;
            this.unit = unit;
            this.fromPdu = fromPdu;
        }
    }

    private final BitReader in;
    private final int flagCount;
    private final int flags;
    private final boolean[] units;
    private final boolean full;
    private final BitSlice[] captured;
    private ObjectStates.Decoded state;
    private int openGroup = -1;
    private int openStart;
    private int openUnit = -1;

    /**
     * Reads flags, units and the full update flag.
     *
     * @param hasFullFlag false for PDUs that are always full updates
     * @param groupCount  group ids used are 0..groupCount-1
     */
    BodyReader(BitReader in, int flagCount, int unitCount, boolean hasFullFlag, int groupCount) {
        this.in = in;
        this.flagCount = flagCount;
        this.flags = (int) in.bits(flagCount);
        this.units = new boolean[unitCount];
        for (int i = 0; i < unitCount; i++) {
            units[i] = in.flag();
        }
        this.full = !hasFullFlag || in.flag();
        this.captured = new BitSlice[groupCount];
    }

    BitReader in() {
        return in;
    }

    boolean isFull() {
        return full;
    }

    boolean unit(int index) {
        return units[index];
    }

    /** @return whether the flag is set in this PDU */
    boolean has(int flag) {
        return (flags & (1 << (flagCount - 1 - flag))) != 0;
    }

    /** The object's state, for the groups a partial update leaves out. */
    void useState(ObjectStates.Decoded s) {
        this.state = s;
    }

    /**
     * Opens a group: in this PDU when its flag is set, else from the state (partial updates),
     * else absent.
     *
     * @param unit the units flag index of the group's values, -1 for none
     * @return the group, or null when absent (its values are zero)
     */
    Group open(int group, int flag, int unit) {
        if (has(flag)) {
            openGroup = group;
            openStart = in.position();
            openUnit = unit;
            return new Group(in, unit >= 0 && units[unit], true);
        }
        if (!full && state != null && state.groups != null && state.groups[group] != null) {
            BitReader r = state.groups[group].reader();
            boolean u = unit >= 0 && r.flag();
            return new Group(r, u, false);
        }
        return null;
    }

    Group open(int group, int flag) {
        return open(group, flag, -1);
    }

    /** Closes the group opened last; one read from the PDU is kept for the state. */
    void close(Group g) {
        if (g == null || !g.fromPdu) {
            return;
        }
        BitWriter key = new BitWriter(16);
        if (openUnit >= 0) {
            key.flag(units[openUnit]);
        }
        key.slice(in.sliceSince(openStart));
        captured[openGroup] = key.toSlice();
        openGroup = -1;
    }

    /**
     * Stores this PDU's groups as the object's state: a full update replaces it, a partial one
     * replaces the groups it carries.
     */
    void commit(ObjectStates.Decoded s) {
        if (full || s.groups == null) {
            s.groups = captured.clone();
        } else {
            for (int i = 0; i < captured.length; i++) {
                if (captured[i] != null) {
                    s.groups[i] = captured[i];
                }
            }
        }
    }
}
