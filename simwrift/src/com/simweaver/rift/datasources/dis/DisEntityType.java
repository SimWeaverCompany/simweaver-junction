package com.simweaver.rift.datasources.dis;

/** A DIS entity type record (kind, domain, country, category, subcategory, specific, extra). */
public final class DisEntityType {

    public static final int KIND_OTHER = 0;
    public static final int KIND_PLATFORM = 1;
    public static final int KIND_MUNITION = 2;
    public static final int KIND_LIFE_FORM = 3;
    public static final int KIND_ENVIRONMENTAL = 4;
    public static final int KIND_CULTURAL_FEATURE = 5;
    public static final int KIND_SUPPLY = 6;
    public static final int KIND_RADIO = 7;
    public static final int KIND_EXPENDABLE = 8;
    public static final int KIND_SENSOR_EMITTER = 9;

    public static final int DOMAIN_LAND = 1;
    public static final int DOMAIN_AIR = 2;
    public static final int DOMAIN_SURFACE = 3;
    public static final int DOMAIN_SUBSURFACE = 4;
    public static final int DOMAIN_SPACE = 5;

    public final int kind;
    public final int domain;
    public final int country;
    public final int category;
    public final int subcategory;
    public final int specific;
    public final int extra;

    public DisEntityType(int kind, int domain, int country, int category, int subcategory, int specific, int extra) {
        this.kind = kind & 0xFF;
        this.domain = domain & 0xFF;
        this.country = country & 0xFFFF;
        this.category = category & 0xFF;
        this.subcategory = subcategory & 0xFF;
        this.specific = specific & 0xFF;
        this.extra = extra & 0xFF;
    }

    public static DisEntityType read(DisReader r) {
        return new DisEntityType(r.u8(), r.u8(), r.u16(), r.u8(), r.u8(), r.u8(), r.u8());
    }

    @Override
    public String toString() {
        return kind + "." + domain + "." + country + "." + category + "." + subcategory + "." + specific + "." + extra;
    }
}
