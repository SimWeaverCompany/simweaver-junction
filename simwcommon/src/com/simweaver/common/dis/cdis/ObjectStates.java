package com.simweaver.common.dis.cdis;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Per-object state for partial updates (SISO-STD-023-2024 9 s): what the encoder last sent and
 * what the decoder last received, for each stateful object (entity, emitter, designator, radio,
 * IFF system) of one PDU type. Not thread safe; the codec synchronises.
 */
final class ObjectStates<S extends ObjectStates.Timed> {

    /** State with the time it was last touched. */
    abstract static class Timed {
        long touched;
    }

    /** Encoder side: the groups of the last update and when the last full update went out. */
    static final class Encoded extends Timed {
        BitSlice[] groups;
        int usedFlags;
        long lastFull = Long.MIN_VALUE;
        /** Opaque per-PDU state (the EE PDU's emitter systems). */
        Object extra;
    }

    /** Decoder side: the groups as last received. */
    static final class Decoded extends Timed {
        BitSlice[] groups;
        /** Opaque per-PDU state (the EE PDU's emitter systems, the IFF system type). */
        Object extra;
    }

    interface Factory<S> {
        S create();
    }

    private final Map<Long, S> map = new HashMap<Long, S>();
    private final Factory<S> factory;

    ObjectStates(Factory<S> factory) {
        this.factory = factory;
    }

    /** @return the state of the object, or null */
    S get(long key) {
        return map.get(key);
    }

    /** @return the state of the object, created when new */
    S getOrCreate(long key, long now) {
        S s = map.get(key);
        if (s == null) {
            s = factory.create();
            map.put(key, s);
        }
        s.touched = now;
        return s;
    }

    void remove(long key) {
        map.remove(key);
    }

    int size() {
        return map.size();
    }

    void clear() {
        map.clear();
    }

    /** Drops objects not touched since {@code limit}. @return how many */
    int expire(long limit) {
        int n = 0;
        for (Iterator<S> it = map.values().iterator(); it.hasNext();) {
            if (it.next().touched < limit) {
                it.remove();
                n++;
            }
        }
        return n;
    }

    static ObjectStates<Encoded> encoded() {
        return new ObjectStates<Encoded>(Encoded::new);
    }

    static ObjectStates<Decoded> decoded() {
        return new ObjectStates<Decoded>(Decoded::new);
    }

    /** Key of an entity id at the reader's position (48 bits); the position moves past it. */
    static long entityKey(int site, int application, int entity) {
        return ((long) site << 32) | ((long) application << 16) | entity;
    }
}
