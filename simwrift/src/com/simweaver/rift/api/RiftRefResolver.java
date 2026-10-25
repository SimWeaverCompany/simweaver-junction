package com.simweaver.rift.api;

/**
 * Resolves reference keys to objects (contract 3.4 / 4.1). Backed by the database's indexes:
 * instance name -> object for "objectname", identity attribute (s-a-e) -> object for "entityid".
 * Called on the database worker thread while serialising.
 */
@FunctionalInterface
public interface RiftRefResolver {

    /** @return the active object for this key, or null when it is not (yet) known */
    RiftObjectView resolve(String kind, String key);

    /** A resolver that knows nothing (all references stay unresolved). */
    RiftRefResolver NONE = (kind, key) -> null;
}
