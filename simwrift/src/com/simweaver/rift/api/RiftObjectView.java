package com.simweaver.rift.api;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * What the web layer needs to know about one object instance. Implement it on (or adapt)
 * the database's object state; everything else in this package works on this view only.
 */
public interface RiftObjectView {

    /** Backend id, e.g. "o1042" (RiftObject.toExternalId()). */
    String externalId();

    /** HLA object instance name; what RTIobjectId references point to. */
    String instanceName();

    /** Display label (RPR: Marking text); null = use instanceName(). */
    default String label() { return null; }

    /** Qualified class name, e.g. "HLAobjectRoot.BaseEntity.PhysicalEntity.Platform.Aircraft". */
    String classNameQ();

    Instant discovered();

    Instant lastUpdated();

    /** seq of the last change of this object (contract 1). */
    long rev();

    long updateCount();

    /** Producing federate name, or null. */
    default String producer() { return null; }

    /** True once the object has been removed from the federation. */
    default boolean removed() { return false; }

    /** When the object was removed; null while active (or unknown). */
    default Instant removedAt() { return null; }

    /**
     * Attributes computed when read, e.g. the dead-reckoned Spatial ("DRSpatial"): never
     * stored, no raw octets, recomputed for every full view and pushed about once per second to
     * pages showing the object. Empty by default.
     */
    default List<? extends RiftMemberView> computedAttributes(long nowMillis) { return Collections.emptyList(); }

    /** Cheap check whether {@link #computedAttributes} can return anything (no computation). */
    default boolean hasComputedAttributes() { return false; }

    /** Received attributes by name, in FOM order (e.g. a LinkedHashMap). */
    Map<String, ? extends RiftMemberView> attributes();
}
