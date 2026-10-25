package com.simweaver.rift.db;

import com.simweaver.common.SimwTimeStamp;
import com.simweaver.common.hla.decoders.DecodedValue;
import com.simweaver.common.hla.decoders.rpr.RprSpatial;
import com.simweaver.rift.api.DecodedValueNode;
import com.simweaver.rift.api.RiftMember;
import com.simweaver.rift.api.RiftObjectView;
import com.simweaver.rift.api.ValueNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One discovered object instance.
 *
 * <p><b>Threading:</b> only the database worker changes an instance. Every changing field is
 * volatile, and the attributes are an immutable map that is replaced as a whole on each update
 * (copy on write), so REST and SSE threads can read an instance at any time without locks and
 * always see a complete attribute set. An attribute map read once is a consistent snapshot.
 */
public class RiftInstance implements RiftObjectView {

    /** Name of the computed dead-reckoned Spatial attribute. */
    public static final String DR_SPATIAL = "DRSpatial";

    /** Longest dead-reckoning extrapolation (s); older Spatial values stay where they were then. */
    public static final double DR_MAX_SECONDS = 30.0;

    /** Attributes in FOM order: IEM ids follow the FOM (superclass attributes first); unknown ids last. */
    private static final Comparator<RiftAttribute> FOM_ORDER = Comparator.comparing(
            RiftAttribute::getIemId, Comparator.nullsLast(Comparator.naturalOrder()));

    private final String instanceName;
    private final int dbId;
    private final String externalId;
    private final Integer iemClassId;
    private final String className;
    private final String registeringFederate;
    private final long discoveredMillis;

    private volatile long lastUpdateMillis;
    private volatile long rev;
    private volatile long updateCount;
    private volatile boolean removed;
    private volatile long removedMillis;
    private volatile Map<String, RiftAttribute> attributes = Collections.emptyMap();
    private volatile String entityKey;                                   // identity key, e.g. "205-3-42"
    private volatile Map<String, List<RiftEdge>> outgoing = Collections.emptyMap();   // by attribute

    /**
     * @param instanceName        the HLA object instance name (what RTIobjectId references point to)
     * @param dbId                database id (allocated by {@link RiftDatabase#discoverInstance})
     * @param className           qualified class name, e.g. "HLAobjectRoot.BaseEntity.PhysicalEntity.Platform.Aircraft"
     */
    public RiftInstance(String instanceName, int dbId, Integer iemClassId, String className, String registeringFederate) {
        this(instanceName, dbId, iemClassId, className, registeringFederate, System.currentTimeMillis());
    }

    RiftInstance(String instanceName, int dbId, Integer iemClassId, String className, String registeringFederate,
                 long discoveredMillis) {
        this.instanceName = instanceName;
        this.dbId = dbId;
        this.externalId = toExternalId(dbId);
        this.iemClassId = iemClassId;
        this.className = className;
        this.registeringFederate = registeringFederate;
        this.discoveredMillis = discoveredMillis;
        this.lastUpdateMillis = discoveredMillis;
    }

    /** "o1042" for database id 1042 (contract 1: short, URL safe, never reused). */
    public static String toExternalId(int dbId) {
        return "o" + dbId;
    }

    /** Database id from "o1042", or {@link RiftDatabase#NO_ID} if the text is not an external id. */
    public static int parseExternalId(String externalId) {
        if (externalId == null || externalId.length() < 2 || externalId.length() > 11 || externalId.charAt(0) != 'o') {
            return RiftDatabase.NO_ID;
        }
        long v = 0;
        for (int i = 1; i < externalId.length(); i++) {
            char c = externalId.charAt(i);
            if (c < '0' || c > '9') return RiftDatabase.NO_ID;
            v = v * 10 + (c - '0');
        }
        return v > 0 && v <= Integer.MAX_VALUE ? (int) v : RiftDatabase.NO_ID;
    }

    // ------------------------------------------------------------------ worker-side changes

    /** Worker only: puts the decoded attributes of one update and returns the names that changed. */
    void applyAttributes(List<RiftAttribute> update, long seq, long receivedMillis) {
        Map<String, RiftAttribute> cur = attributes;
        Map<String, RiftAttribute> next = new LinkedHashMap<>(cur);
        boolean newName = false;
        for (RiftAttribute a : update) {
            if (next.put(a.getName(), a) == null) newName = true;
        }
        if (newName) {                       // keep FOM order when a first value of an attribute arrives
            List<RiftAttribute> sorted = new ArrayList<>(next.values());
            sorted.sort(FOM_ORDER);
            next = new LinkedHashMap<>();
            for (RiftAttribute a : sorted) next.put(a.getName(), a);
        }
        attributes = Collections.unmodifiableMap(next);
        lastUpdateMillis = receivedMillis;
        updateCount++;
        rev = seq;
    }

    void markRemoved(long seq, long removedMillis) {
        this.removedMillis = removedMillis;
        this.rev = seq;
        this.removed = true;
    }

    void setRev(long seq) {
        this.rev = seq;
    }

    void setEntityKey(String key) {
        this.entityKey = key;
    }

    String entityKey() {
        return entityKey;
    }

    /** NEW (map): the identity key, e.g. "205-3-42" (RPR EntityIdentifier), or null. */
    public String getEntityKey() {
        return entityKey;
    }

    Map<String, List<RiftEdge>> outgoing() {
        return outgoing;
    }

    void setOutgoing(Map<String, List<RiftEdge>> map) {
        this.outgoing = map;
    }

    // ------------------------------------------------------------------ getters

    public int getDbId() {
        return dbId;
    }

    public String getInstanceName() {
        return instanceName;
    }

    public Integer getIemClassId() {
        return iemClassId;
    }

    /** Qualified class name. */
    public String getClassName() {
        return className;
    }

    public String getRegisteringFederate() {
        return registeringFederate;
    }

    public SimwTimeStamp getRegistrationTime() {
        return SimwTimeStamp.ofMillis(discoveredMillis);
    }

    public SimwTimeStamp getLastUpdateTime() {
        return SimwTimeStamp.ofMillis(lastUpdateMillis);
    }

    public boolean isRemoved() {
        return removed;
    }

    /** Epoch millis of the removal, 0 while active. */
    public long getRemovedMillis() {
        return removedMillis;
    }

    /** The latest value of an attribute, or null if never received. */
    public RiftAttribute getAttribute(String name) {
        return attributes.get(name);
    }

    /** Snapshot of all received attributes by name, in FOM order. */
    public Map<String, RiftAttribute> getAttributes() {
        return attributes;
    }

    /**
     * RPR "now" value of the Spatial attribute, computed when read (never stored): the decoder's
     * derived {@code Now} record (latitude ... angular velocity, plus {@code Age}), dead reckoned
     * from the latest Spatial to {@code nowMillis}. Null when there is no decoded Spatial, or for a
     * relative spatial.
     *
     * @param maxSeconds cap for the extrapolation, as for {@link RprSpatial#nowValue}
     */
    public DecodedValue spatialNow(long nowMillis, double maxSeconds) {
        RiftAttribute spatial = attributes.get("Spatial");
        if (spatial == null) return null;
        ValueNode v = spatial.getValue();
        if (!(v instanceof DecodedValueNode)) return null;
        return RprSpatial.nowValue(((DecodedValueNode) v).unwrap(), nowMillis, maxSeconds);
    }

    /**
     * NEW (map): the dead-reckoned state in local terms (latitude, longitude, height, heading,
     * velocities), or null: no decoded Spatial, a relative spatial (a part of a host), or a
     * Spatial that cannot be read.
     */
    public com.simweaver.common.geo.LocalKinematics kinematicsNow(long nowMillis) {
        RiftAttribute spatial = attributes.get("Spatial");
        if (spatial == null) return null;
        ValueNode v = spatial.getValue();
        if (!(v instanceof DecodedValueNode)) return null;
        try {
            return RprSpatial.now(((DecodedValueNode) v).unwrap(), nowMillis, DR_MAX_SECONDS);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** NEW (map): when the Spatial was last received (epoch ms), or 0. */
    public long spatialReceivedMillis() {
        RiftAttribute spatial = attributes.get("Spatial");
        return spatial == null ? 0 : spatial.getReceivedMillis();
    }

    /** True when a decoded RPR Spatial is present (DRSpatial can be computed). */
    @Override
    public boolean hasComputedAttributes() {
        RiftAttribute s = attributes.get("Spatial");
        return s != null && s.getValue() instanceof DecodedValueNode;
    }

    /**
     * {@value #DR_SPATIAL}: the Spatial attribute dead reckoned to {@code nowMillis} (the decoder's
     * {@code Now} record: position, orientation, velocities, Age), computed when asked.
     */
    @Override
    public List<RiftMember> computedAttributes(long nowMillis) {
        if (!hasComputedAttributes()) return Collections.emptyList();
        DecodedValue now;
        try {
            now = spatialNow(nowMillis, DR_MAX_SECONDS);
        } catch (RuntimeException e) {                // a formatter problem must not break the view
            return Collections.emptyList();
        }
        if (now == null) return Collections.emptyList();   // e.g. relative spatial
        return Collections.singletonList(new RiftMember(DR_SPATIAL, "Spatial (dead reckoned)",
                Instant.ofEpochMilli(nowMillis), DecodedValueNode.of(now), null, 0, false));
    }

    // ------------------------------------------------------------------ RiftObjectView

    @Override
    public String externalId() {
        return externalId;
    }

    @Override
    public String instanceName() {
        return instanceName;
    }

    @Override
    public String classNameQ() {
        return className;
    }

    @Override
    public Instant discovered() {
        return Instant.ofEpochMilli(discoveredMillis);
    }

    @Override
    public Instant lastUpdated() {
        return Instant.ofEpochMilli(lastUpdateMillis);
    }

    @Override
    public long rev() {
        return rev;
    }

    @Override
    public long updateCount() {
        return updateCount;
    }

    @Override
    public String producer() {
        return registeringFederate;
    }

    @Override
    public boolean removed() {
        return removed;
    }

    @Override
    public Instant removedAt() {
        long t = removedMillis;
        return removed && t != 0 ? Instant.ofEpochMilli(t) : null;
    }

    @Override
    public Map<String, RiftAttribute> attributes() {
        return attributes;
    }

    @Override
    public String toString() {
        return externalId + " " + instanceName + " (" + className + ")" + (removed ? " removed" : "");
    }
}
