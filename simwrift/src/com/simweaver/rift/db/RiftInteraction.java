package com.simweaver.rift.db;

import com.simweaver.common.SimwTimeStamp;
import com.simweaver.rift.api.RiftInteractionView;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * One received interaction, as kept in the database's ring buffer. Built by the worker after the
 * parameters are decoded, then immutable.
 */
public final class RiftInteraction implements RiftInteractionView {

    private final long seq;
    private final Integer iemId;
    private final String classNameQ;
    private final long receivedMillis;
    private final String producingFederate;
    private final List<RiftParameter> parameters;
    private final byte[] userSuppliedTag;
    private final Set<String> refKeys;          // RiftDatabase.refKey(kind, key) of all references
    private final String correlationKey;
    private final String summary;

    RiftInteraction(long seq, Integer iemId, String classNameQ, long receivedMillis, String producingFederate,
                    List<RiftParameter> parameters, byte[] userSuppliedTag, Set<String> refKeys, String correlationKey,
                    String summary) {
        this.seq = seq;
        this.iemId = iemId;
        this.classNameQ = classNameQ;
        this.receivedMillis = receivedMillis;
        this.producingFederate = producingFederate;
        this.parameters = Collections.unmodifiableList(parameters);
        this.userSuppliedTag = userSuppliedTag;
        this.refKeys = Collections.unmodifiableSet(refKeys);
        this.correlationKey = correlationKey;
        this.summary = summary;
    }

    /** IEM id of the interaction class. */
    public Integer getIemId() {
        return iemId;
    }

    public SimwTimeStamp getSimwTimeStamp() {
        return SimwTimeStamp.ofMillis(receivedMillis);
    }

    public long getReceivedMillis() {
        return receivedMillis;
    }

    public String getProducingFederate() {
        return producingFederate;
    }

    public List<RiftParameter> getRiftParameters() {
        return parameters;
    }

    /** Parameter by name, or null. */
    public RiftParameter getParameter(String name) {
        for (RiftParameter p : parameters) if (p.getName().equals(name)) return p;
        return null;
    }

    public byte[] getUserSuppliedTag() {
        return userSuppliedTag;
    }

    /** All references in the parameters, as RiftDatabase reference keys. */
    Set<String> refKeys() {
        return refKeys;
    }

    // ------------------------------------------------------------------ RiftInteractionView

    @Override
    public long seq() {
        return seq;
    }

    @Override
    public String classNameQ() {
        return classNameQ;
    }

    @Override
    public Instant received() {
        return Instant.ofEpochMilli(receivedMillis);
    }

    @Override
    public String producer() {
        return producingFederate;
    }

    /** One-line summary for the log row (e.g. Link 16 J-labels), or null. */
    @Override
    public String summary() {
        return summary;
    }

    @Override
    public String correlationKey() {
        return correlationKey;
    }

    @Override
    public List<RiftParameter> parameters() {
        return parameters;
    }

    @Override
    public String toString() {
        return "i" + seq + " " + classNameQ;
    }
}
