package com.simweaver.rift.api;

import java.util.Collections;
import java.util.List;

/** Outgoing and incoming relations of one object (contract 4.2), incoming capped. */
public final class RiftRelations {

    public static final RiftRelations NONE = new RiftRelations(Collections.emptyList(), Collections.emptyList(), 0);

    public final List<RiftRelation> outgoing;
    public final List<RiftRelation> incoming;
    /** Real number of incoming relations (incoming may be cut). */
    public final int incomingTotal;

    public RiftRelations(List<RiftRelation> outgoing, List<RiftRelation> incoming, int incomingTotal) {
        this.outgoing = Collections.unmodifiableList(outgoing);
        this.incoming = Collections.unmodifiableList(incoming);
        this.incomingTotal = incomingTotal;
    }
}
