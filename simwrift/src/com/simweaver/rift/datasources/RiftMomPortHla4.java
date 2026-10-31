package com.simweaver.rift.datasources;

import com.simweaver.common.infra.HlaTap;
import com.simweaver.common.infra.MomPort;
import hla.rti1516_2025.AttributeHandle;
import hla.rti1516_2025.AttributeHandleSet;
import hla.rti1516_2025.AttributeHandleValueMap;
import hla.rti1516_2025.InteractionClassHandle;
import hla.rti1516_2025.ObjectClassHandle;
import hla.rti1516_2025.ObjectInstanceHandle;
import hla.rti1516_2025.ParameterHandle;
import hla.rti1516_2025.ParameterHandleValueMap;
import hla.rti1516_2025.RTIambassador;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * {@link MomPort} for a Rift HLA 1516-2025 datasource (0.9.20, Infra): the RTI handles as ints,
 * instances as longs. The datasource's own subscription of a class is kept in every subscribe
 * (an RTI may replace a class's subscription instead of adding to it).
 */
final class RiftMomPortHla4 implements MomPort {

    private final RTIambassador rti;
    private final Function<ObjectClassHandle, AttributeHandleSet> ownAttributes;
    private final Table<ObjectClassHandle> classes = new Table<ObjectClassHandle>();
    private final Table<AttributeHandle> attributes = new Table<AttributeHandle>();
    private final Table<InteractionClassHandle> interactions = new Table<InteractionClassHandle>();
    private final Table<ParameterHandle> parameters = new Table<ParameterHandle>();
    private final Map<ObjectInstanceHandle, Long> instanceIds = new ConcurrentHashMap<ObjectInstanceHandle, Long>();
    private final Map<Long, ObjectInstanceHandle> instances = new ConcurrentHashMap<Long, ObjectInstanceHandle>();
    private final Map<Integer, AttributeHandleSet> subscribed = new ConcurrentHashMap<Integer, AttributeHandleSet>();
    private long nextInstance;

    RiftMomPortHla4(RTIambassador rti, Function<ObjectClassHandle, AttributeHandleSet> ownAttributes) {
        this.rti = rti;
        this.ownAttributes = ownAttributes;
    }

    private volatile HlaTap tap;

    void setTap(HlaTap t) {
        this.tap = t;
    }

    HlaTap tap() {
        return tap;
    }

    // ------------------------------------------------------------------ the federate's callbacks, to the tap

    /** @return true when the object is a MOM object the Infra view follows */
    boolean discovered(ObjectInstanceHandle object, ObjectClassHandle cls, String name) {
        HlaTap t = tap;
        int c = classId(cls);
        return t != null && c != 0 && t.discovered(instance(object), c, name);
    }

    /** @return true when the values were of a MOM object the Infra view follows */
    boolean reflected(ObjectInstanceHandle object, AttributeHandleValueMap values) {
        HlaTap t = tap;
        long inst = knownInstance(object);
        if (t == null || inst < 0) {
            return false;
        }
        int[] ids = new int[values.size()];
        byte[][] vals = new byte[values.size()][];
        int n = 0;
        for (Map.Entry<AttributeHandle, byte[]> e : values.entrySet()) {
            ids[n] = attributeId(e.getKey());
            vals[n++] = e.getValue();
        }
        return t.reflected(inst, ids, vals);
    }

    boolean removed(ObjectInstanceHandle object) {
        HlaTap t = tap;
        long inst = knownInstance(object);
        return t != null && inst >= 0 && t.removed(inst);
    }

    /** @return true when the interaction was a MOM report the Infra view asked for */
    boolean received(InteractionClassHandle cls, ParameterHandleValueMap values) {
        HlaTap t = tap;
        int ic = interactionId(cls);
        if (t == null || ic == 0) {
            return false;
        }
        int[] ids = new int[values.size()];
        byte[][] vals = new byte[values.size()][];
        int n = 0;
        for (Map.Entry<ParameterHandle, byte[]> e : values.entrySet()) {
            ids[n] = parameterId(e.getKey());
            vals[n++] = e.getValue();
        }
        return t.received(ic, ids, vals);
    }

    /** Counts what the datasource received from a producer. */
    void observed(String producer, boolean interaction, int octets) {
        HlaTap t = tap;
        if (t != null) {
            t.observed(producer, interaction, octets);
        }
    }

    void close() {
        HlaTap t = tap;
        tap = null;
        if (t != null) {
            t.close();
        }
    }

    // ------------------------------------------------------------------ handle ids

    /** @return the class as the tap knows it, or 0 when the tap never asked for it */
    int classId(ObjectClassHandle h) {
        return h == null ? 0 : classes.known(h);
    }

    int interactionId(InteractionClassHandle h) {
        return h == null ? 0 : interactions.known(h);
    }

    synchronized long instance(ObjectInstanceHandle h) {
        Long id = instanceIds.get(h);
        if (id == null) {
            id = ++nextInstance;
            instanceIds.put(h, id);
            instances.put(id, h);
        }
        return id;
    }

    /** @return the instance id, or -1 when never seen */
    long knownInstance(ObjectInstanceHandle h) {
        Long id = instanceIds.get(h);
        return id == null ? -1 : id;
    }

    int attributeId(AttributeHandle h) {
        return attributes.known(h);
    }

    int parameterId(ParameterHandle h) {
        return parameters.known(h);
    }

    // ------------------------------------------------------------------ MomPort

    @Override
    public int objectClass(String qualifiedName) throws Exception {
        return classes.id(rti.getObjectClassHandle(qualifiedName));
    }

    @Override
    public int attribute(int objectClass, String name) throws Exception {
        return attributes.id(rti.getAttributeHandle(classes.handle(objectClass), name));
    }

    @Override
    public int interactionClass(String qualifiedName) throws Exception {
        return interactions.id(rti.getInteractionClassHandle(qualifiedName));
    }

    @Override
    public int parameter(int interactionClass, String name) throws Exception {
        return parameters.id(rti.getParameterHandle(interactions.handle(interactionClass), name));
    }

    @Override
    public void subscribeObjectClass(int objectClass, int[] attrs) throws Exception {
        ObjectClassHandle c = classes.handle(objectClass);
        AttributeHandleSet set = subscribed.computeIfAbsent(objectClass, k -> {
            try {
                return rti.getAttributeHandleSetFactory().create();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        synchronized (set) {
            for (int a : attrs) {
                set.add(attributes.handle(a));
            }
            AttributeHandleSet own = ownAttributes == null ? null : ownAttributes.apply(c);
            if (own != null) {
                set.addAll(own);
            }
            rti.subscribeObjectClassAttributes(c, set);
        }
    }

    @Override
    public void subscribeInteractionClass(int interactionClass) throws Exception {
        rti.subscribeInteractionClass(interactions.handle(interactionClass));
    }

    @Override
    public void publishInteractionClass(int interactionClass) throws Exception {
        rti.publishInteractionClass(interactions.handle(interactionClass));
    }

    @Override
    public void requestClassUpdate(int objectClass, int[] attrs) throws Exception {
        rti.requestAttributeValueUpdate(classes.handle(objectClass), set(attrs), new byte[0]);
    }

    @Override
    public void requestUpdate(long instance, int[] attrs) throws Exception {
        ObjectInstanceHandle h = instances.get(instance);
        if (h != null) {
            rti.requestAttributeValueUpdate(h, set(attrs), new byte[0]);
        }
    }

    @Override
    public void sendInteraction(int interactionClass, int[] params, byte[][] values) throws Exception {
        ParameterHandleValueMap m = rti.getParameterHandleValueMapFactory().create(params.length);
        for (int i = 0; i < params.length; i++) {
            m.put(parameters.handle(params[i]), values[i]);
        }
        rti.sendInteraction(interactions.handle(interactionClass), m, new byte[0]);
    }

    @Override
    public String objectClassName(byte[] encodedHandle) {
        try {
            return rti.getObjectClassName(rti.getObjectClassHandleFactory().decode(encodedHandle, 0));
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String interactionClassName(byte[] encodedHandle) {
        try {
            return rti.getInteractionClassName(rti.getInteractionClassHandleFactory().decode(encodedHandle, 0));
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String attributeName(byte[] encodedClass, byte[] encodedAttribute) {
        try {
            return rti.getAttributeName(rti.getObjectClassHandleFactory().decode(encodedClass, 0),
                    rti.getAttributeHandleFactory().decode(encodedAttribute, 0));
        } catch (Exception e) {
            return null;
        }
    }

    private AttributeHandleSet set(int[] attrs) throws Exception {
        AttributeHandleSet s = rti.getAttributeHandleSetFactory().create();
        for (int a : attrs) {
            s.add(attributes.handle(a));
        }
        return s;
    }

    /** Handles as small ints (1, 2, ...). */
    static final class Table<H> {
        private final Map<H, Integer> ids = new ConcurrentHashMap<H, Integer>();
        private final List<H> byId = new ArrayList<H>();

        synchronized int id(H h) {
            Integer id = ids.get(h);
            if (id == null) {
                byId.add(h);
                id = byId.size();
                ids.put(h, id);
            }
            return id;
        }

        int known(H h) {
            Integer id = ids.get(h);
            return id == null ? 0 : id;
        }

        synchronized H handle(int id) {
            if (id < 1 || id > byId.size()) {
                throw new IllegalArgumentException("no handle " + id);
            }
            return byId.get(id - 1);
        }
    }
}
