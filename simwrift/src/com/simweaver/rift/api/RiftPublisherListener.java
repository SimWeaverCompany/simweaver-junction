package com.simweaver.rift.api;

import com.simweaver.rift.db.RiftDatabaseListener;
import com.simweaver.rift.db.RiftInstance;
import com.simweaver.rift.db.RiftInteraction;

import java.util.Set;

/**
 * Connects a {@link com.simweaver.rift.db.RiftDatabase} to a {@link RiftEventPublisher}: every
 * database change becomes the matching SSE patch or event. Runs on the database worker thread,
 * which also drives the publisher's once-per-second {@link RiftEventPublisher#tick()}.
 *
 * <pre>
 *   RiftEventPublisher pub = new RiftEventPublisher(hub, dsid, db.resolver(), RiftListProfiles.rprDefaults());
 *   db.addListener(new RiftPublisherListener(pub));
 * </pre>
 */
public final class RiftPublisherListener implements RiftDatabaseListener {

    private final RiftEventPublisher publisher;

    public RiftPublisherListener(RiftEventPublisher publisher) {
        if (publisher == null) throw new IllegalArgumentException("publisher must not be null");
        this.publisher = publisher;
    }

    public RiftEventPublisher publisher() {
        return publisher;
    }

    @Override
    public void instanceDiscovered(RiftInstance instance) {
        publisher.objectDiscovered(instance);
    }

    @Override
    public void instanceUpdated(RiftInstance instance, Set<String> attributeNames) {
        publisher.attributesUpdated(instance, attributeNames);
    }

    @Override
    public void instanceRemoved(RiftInstance instance, long seq) {
        publisher.objectRemoved(instance, seq);
    }

    @Override
    public void interactionReceived(RiftInteraction interaction) {
        publisher.interactionReceived(interaction);
    }

    @Override
    public void tick(long nowMillis) {
        publisher.tick();
    }
}
