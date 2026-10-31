package com.simweaver.rift.api;

/**
 * One relation of an object (contract 4.2): a reference value in an attribute of a source object
 * pointing at a key. In an outgoing list {@link #other} is the target (null while no object with
 * that key is discovered); in an incoming list it is the referring source object.
 */
public final class RiftRelation {

    /** Attribute holding the reference, e.g. "HostObjectIdentifier". */
    public final String attribute;
    /** Where inside the attribute, e.g. "EntityIdentifiers[3]"; equals attribute at top level. */
    public final String path;
    /** "objectname" or "entityid". */
    public final String kind;
    /** The referenced key (instance name, or "s-a-e"). */
    public final String key;
    /** Target (outgoing) or source (incoming); may be null for an unresolved outgoing reference. */
    public final RiftObjectView other;

    public RiftRelation(String attribute, String path, String kind, String key, RiftObjectView other) {
        this.attribute = attribute;
        this.path = path;
        this.kind = kind;
        this.key = key;
        this.other = other;
    }
}
