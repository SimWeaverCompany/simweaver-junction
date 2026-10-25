package com.simweaver.common.dis;

/**
 * A DIS entity identifier (site, application, entity). Its text form {@code "site-application-entity"}
 * is the instance name of the object (in Rift and Junktion) and the key RPR references resolve by.
 */
public final class DisEntityId {

    /** 0-0-0: "no entity" (DIS ENTITY_ID_UNKNOWN / D_SPEC_TARGET_ID_UNKNOWN). */
    public static final DisEntityId NONE = new DisEntityId(0, 0, 0);

    public final int site;
    public final int application;
    public final int entity;

    public DisEntityId(int site, int application, int entity) {
        this.site = site & 0xFFFF;
        this.application = application & 0xFFFF;
        this.entity = entity & 0xFFFF;
    }

    public static DisEntityId read(DisReader r) {
        return new DisEntityId(r.u16(), r.u16(), r.u16());
    }

    /** True for 0-0-0, which DIS uses for "no entity" (e.g. no target). */
    public boolean isNone() {
        return site == 0 && application == 0 && entity == 0;
    }

    /** Unique 48-bit key, e.g. for maps. */
    public long key() {
        return ((long) site << 32) | ((long) application << 16) | entity;
    }

    /** "site-application-entity", e.g. "205-3-42". */
    public String text() {
        return site + "-" + application + "-" + entity;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof DisEntityId && ((DisEntityId) o).key() == key();
    }

    @Override
    public int hashCode() {
        return Long.hashCode(key());
    }

    @Override
    public String toString() {
        return text();
    }
}
