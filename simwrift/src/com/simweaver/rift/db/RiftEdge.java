package com.simweaver.rift.db;

/**
 * One reference found in an attribute: source object, where in the attribute, and the referenced
 * key. Immutable. Kept per source attribute and in the database's incoming index under
 * {@link #indexKey}, so it resolves by itself as soon as an object with that key appears.
 */
final class RiftEdge {

    final RiftInstance source;
    final String attribute;
    final String path;
    final String kind;
    final String key;
    final String indexKey;

    RiftEdge(RiftInstance source, String attribute, String path, String kind, String key) {
        this.source = source;
        this.attribute = attribute;
        this.path = path;
        this.kind = kind;
        this.key = key;
        this.indexKey = RiftDatabase.refKey(kind, key);
    }

    @Override
    public String toString() {
        return source.externalId() + "." + path + " -> " + kind + ":" + key;
    }
}
