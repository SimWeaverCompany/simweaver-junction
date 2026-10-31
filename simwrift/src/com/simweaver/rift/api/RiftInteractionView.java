package com.simweaver.rift.api;

import java.time.Instant;
import java.util.List;

/** One received interaction, as stored in the backend's ring buffer (contract 8). */
public interface RiftInteractionView {

    /** seq of this interaction; the external id is "i" + seq. */
    long seq();

    /** Qualified class name, e.g. "HLAinteractionRoot.WeaponFire". */
    String classNameQ();

    /** Wall-clock receive time. */
    Instant received();

    /** "RO" or "TSO" as sent; null if unknown. */
    default String sentOrder() { return null; }

    /** Logical time as text, null when the interaction had none. */
    default String logicalTime() { return null; }

    default String producer() { return null; }

    /** Optional one-line summary for the log row. */
    default String summary() { return null; }

    /** Optional correlation key (RPR: EventIdentifier), with an optional label. */
    default String correlationKey() { return null; }

    default String correlationLabel() { return null; }

    List<? extends RiftMemberView> parameters();
}
