package com.simweaver.rift.db;

import java.util.Set;

/**
 * Changes of a {@link RiftDatabase}. All methods run on the database worker thread, in the order
 * of the RTI callbacks, after the change is visible to readers. Keep them quick: the next event
 * waits. An exception thrown here is logged and does not stop the database.
 */
public interface RiftDatabaseListener {

    /** A new instance was discovered (no attributes yet). */
    default void instanceDiscovered(RiftInstance instance) { }

    /**
     * Attributes of an instance were updated.
     *
     * @param attributeNames the attributes of this update (already in {@code instance.getAttributes()})
     */
    default void instanceUpdated(RiftInstance instance, Set<String> attributeNames) { }

    /**
     * An instance was removed from the federation. It stays readable with {@code isRemoved()}
     * until the removed-instance retention purges it.
     *
     * @param seq the seq of the removal (= the instance's rev)
     */
    default void instanceRemoved(RiftInstance instance, long seq) { }

    /** An interaction was received (and is in the ring buffer). */
    default void interactionReceived(RiftInteraction interaction) { }

    /** Once per second on the worker thread, e.g. for RiftEventPublisher.tick(). */
    default void tick(long nowMillis) { }
}
