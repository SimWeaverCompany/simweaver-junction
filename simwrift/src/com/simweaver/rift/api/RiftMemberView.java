package com.simweaver.rift.api;

import java.time.Instant;

/** One attribute of an object, or one parameter of an interaction, as stored by the backend. */
public interface RiftMemberView {

    /** Attribute / parameter name, e.g. "Spatial". */
    String name();

    /** FOM datatype name, e.g. "SpatialVariantStruct". */
    String datatype();

    /** When this value was received (attributes); may be null for parameters. */
    Instant lastUpdated();

    /** The decoded value. */
    ValueNode value();

    /** Hex of the received octets, already capped (e.g. 256 bytes); may be null. */
    String rawHex();

    /** Full size of the received value in octets. */
    int rawSize();

    /** True if rawHex() was cut. */
    default boolean rawTruncated() { return false; }
}
