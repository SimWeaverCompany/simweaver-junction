package com.simweaver.junction.router;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * How one object or interaction class goes from a source endpoint to a destination endpoint:
 * the destination class and the member routes. Immutable after the compiler built it.
 */
public final class ClassRoute {

    final int sourceClass;
    final int destinationClass;
    final String sourceName;
    final String destinationName;
    final boolean promoted;
    private final Map<Integer, MemberRoute> bySource = new LinkedHashMap<Integer, MemberRoute>();
    private final Map<Integer, MemberRoute> byDestination = new LinkedHashMap<Integer, MemberRoute>();

    ClassRoute(int sourceClass, int destinationClass, String sourceName, String destinationName, boolean promoted) {
        this.sourceClass = sourceClass;
        this.destinationClass = destinationClass;
        this.sourceName = sourceName;
        this.destinationName = destinationName;
        this.promoted = promoted;
    }

    void add(MemberRoute m) {
        bySource.put(m.sourceMember, m);
        byDestination.put(m.destinationMember, m);
    }

    /** @return the destination class id */
    public int getDestinationClass() {
        return destinationClass;
    }

    /** @return the destination class name */
    public String getDestinationName() {
        return destinationName;
    }

    /** @return true when the destination class is a superclass of the source class's counterpart */
    public boolean isPromoted() {
        return promoted;
    }

    /** @return the member routes */
    public Collection<MemberRoute> members() {
        return Collections.unmodifiableCollection(bySource.values());
    }

    /** @return the route of a source member, or null */
    public MemberRoute bySource(int sourceMember) {
        return bySource.get(sourceMember);
    }

    /** @return the route to a destination member, or null */
    public MemberRoute byDestination(int destinationMember) {
        return byDestination.get(destinationMember);
    }

    /** @return true when no member passes */
    public boolean isEmpty() {
        return bySource.isEmpty();
    }

    /**
     * Converts the members that have a route; the others are left out.
     *
     * @return {ids, values} in the destination, or null when nothing is left
     */
    Converted convert(int[] members, byte[][] values, UnaryOperator<String> rename, RouterStats stats) {
        int[] ids = new int[members.length];
        byte[][] out = new byte[members.length][];
        int n = 0;
        for (int i = 0; i < members.length; i++) {
            MemberRoute r = bySource.get(members[i]);
            if (r == null || values[i] == null) {
                continue;
            }
            byte[] v = r.convert(values[i], rename, stats);
            if (v != null) {
                ids[n] = r.destinationMember;
                out[n] = v;
                n++;
            }
        }
        if (n == 0) {
            return null;
        }
        if (n < ids.length) {
            ids = java.util.Arrays.copyOf(ids, n);
            out = java.util.Arrays.copyOf(out, n);
        }
        return new Converted(ids, out);
    }

    /** Converted member ids and values. */
    static final class Converted {
        final int[] ids;
        final byte[][] values;

        Converted(int[] ids, byte[][] values) {
            this.ids = ids;
            this.values = values;
        }
    }

    @Override
    public String toString() {
        return sourceName + " -> " + destinationName + (promoted ? " (promoted)" : "") + " " + bySource.values();
    }
}
