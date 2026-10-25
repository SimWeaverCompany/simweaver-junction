package com.simweaver.common.hla.decoders.evolved;

import com.simweaver.common.hla.decoders.BindingReport;
import com.simweaver.common.hla.decoders.HlaBindingException;
import com.simweaver.common.hla.decoders.RawValues;
import com.simweaver.common.iem.ClassAttribute;
import com.simweaver.common.iem.ClassParameter;
import com.simweaver.common.iem.IemElement;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.common.iem.InteractionClass;
import com.simweaver.common.iem.ObjectClass;

import hla.rti1516e.AttributeHandle;
import hla.rti1516e.AttributeHandleValueMap;
import hla.rti1516e.InteractionClassHandle;
import hla.rti1516e.ObjectClassHandle;
import hla.rti1516e.ParameterHandle;
import hla.rti1516e.ParameterHandleValueMap;
import hla.rti1516e.RTIambassador;
import hla.rti1516e.exceptions.InvalidInteractionClassHandle;
import hla.rti1516e.exceptions.InvalidObjectClassHandle;
import hla.rti1516e.exceptions.NameNotFound;
import hla.rti1516e.exceptions.RTIexception;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps the handles of one joined federation (IEEE 1516-2010, {@code hla.rti1516e}) to the
 * classes and members of an {@link InformationExchangeModel}.
 * <p>
 * Handles are only valid while joined, so create a binding right after each join (also
 * after a reconnect) and drop it on resign; the {@code HlaDataDecoder} stays the same.
 * Binding is eager: every IEM class and every attribute/parameter available on it is looked
 * up once (a few thousand RTI calls for RPR + NETN), so callbacks never call the RTI.
 * Attributes are keyed by the object's <em>known</em> class handle (from
 * {@code discoverObjectInstance}) plus the attribute handle, so nothing assumes handle
 * values are unique across classes.
 * <p>
 * Immutable and thread safe after {@link #bind}. Names the federation does not know are not
 * errors; they are listed in {@link #getReport()}.
 */
public final class HlaEvolvedBinding {

    private final InformationExchangeModel model;
    private final Map<ObjectClassHandle, ObjectEntry> objectsByHandle;
    private final ObjectEntry[] objectsByIndex;
    private final Map<InteractionClassHandle, InteractionEntry> interactionsByHandle;
    private final InteractionEntry[] interactionsByIndex;
    private final BindingReport report;

    private static final class ObjectEntry {
        final ObjectClass cls;
        final ObjectClassHandle handle;
        final Map<AttributeHandle, ClassAttribute> byHandle = new HashMap<AttributeHandle, ClassAttribute>();
        final Map<Integer, AttributeHandle> byId = new HashMap<Integer, AttributeHandle>();

        ObjectEntry(ObjectClass cls, ObjectClassHandle handle) {
            this.cls = cls;
            this.handle = handle;
        }
    }

    private static final class InteractionEntry {
        final InteractionClass cls;
        final InteractionClassHandle handle;
        final Map<ParameterHandle, ClassParameter> byHandle = new HashMap<ParameterHandle, ClassParameter>();
        final Map<Integer, ParameterHandle> byId = new HashMap<Integer, ParameterHandle>();

        InteractionEntry(InteractionClass cls, InteractionClassHandle handle) {
            this.cls = cls;
            this.handle = handle;
        }
    }

    private HlaEvolvedBinding(InformationExchangeModel model, Map<ObjectClassHandle, ObjectEntry> objects,
                              ObjectEntry[] objectsByIndex, Map<InteractionClassHandle, InteractionEntry> interactions,
                              InteractionEntry[] interactionsByIndex, BindingReport report) {
        this.model = model;
        this.objectsByHandle = objects;
        this.objectsByIndex = objectsByIndex;
        this.interactionsByHandle = interactions;
        this.interactionsByIndex = interactionsByIndex;
        this.report = report;
    }

    /**
     * Looks up the handles of every class and member of the model. Call after joining, not
     * from within a callback.
     *
     * @param model the model the federation's FDD was built from
     * @param rti   a joined RTI ambassador
     * @return the binding
     * @throws HlaBindingException when the RTI cannot answer (not joined, not connected, internal error)
     */
    public static HlaEvolvedBinding bind(InformationExchangeModel model, RTIambassador rti) throws HlaBindingException {
        long t0 = System.nanoTime();
        List<String> missing = new ArrayList<String>();
        Map<ObjectClassHandle, ObjectEntry> objects = new HashMap<ObjectClassHandle, ObjectEntry>();
        ObjectEntry[] objectsByIndex = new ObjectEntry[model.getObjectClasses().size()];
        Map<InteractionClassHandle, InteractionEntry> interactions = new HashMap<InteractionClassHandle, InteractionEntry>();
        InteractionEntry[] interactionsByIndex = new InteractionEntry[model.getInteractionClasses().size()];
        int attributeCount = 0;
        int parameterCount = 0;
        String current = null;
        try {
            for (ObjectClass oc : model.getObjectClasses()) {
                current = oc.getQualifiedName();
                ObjectClassHandle h;
                try {
                    h = rti.getObjectClassHandle(oc.getQualifiedName());
                } catch (NameNotFound e) {
                    missing.add(oc.getQualifiedName() + ": NameNotFound");
                    continue;
                }
                ObjectEntry entry = new ObjectEntry(oc, h);
                for (ClassAttribute a : oc.getAttributes()) {
                    current = oc.getQualifiedName() + "." + a.getName();
                    try {
                        AttributeHandle ah = rti.getAttributeHandle(h, a.getName());
                        entry.byHandle.put(ah, a);
                        entry.byId.put(a.getId(), ah);
                        attributeCount++;
                    } catch (NameNotFound e) {
                        if (!a.isInherited()) {
                            missing.add(a.getQualifiedNameOnClass() + ": NameNotFound");
                        }
                    } catch (InvalidObjectClassHandle e) {
                        missing.add(a.getQualifiedNameOnClass() + ": InvalidObjectClassHandle");
                    }
                }
                objects.put(h, entry);
                objectsByIndex[oc.getIndex()] = entry;
            }
            for (InteractionClass ic : model.getInteractionClasses()) {
                current = ic.getQualifiedName();
                InteractionClassHandle h;
                try {
                    h = rti.getInteractionClassHandle(ic.getQualifiedName());
                } catch (NameNotFound e) {
                    missing.add(ic.getQualifiedName() + ": NameNotFound");
                    continue;
                }
                InteractionEntry entry = new InteractionEntry(ic, h);
                for (ClassParameter p : ic.getParameters()) {
                    current = ic.getQualifiedName() + "." + p.getName();
                    try {
                        ParameterHandle ph = rti.getParameterHandle(h, p.getName());
                        entry.byHandle.put(ph, p);
                        entry.byId.put(p.getId(), ph);
                        parameterCount++;
                    } catch (NameNotFound e) {
                        if (!p.isInherited()) {
                            missing.add(p.getQualifiedNameOnClass() + ": NameNotFound");
                        }
                    } catch (InvalidInteractionClassHandle e) {
                        missing.add(p.getQualifiedNameOnClass() + ": InvalidInteractionClassHandle");
                    }
                }
                interactions.put(h, entry);
                interactionsByIndex[ic.getIndex()] = entry;
            }
        } catch (RTIexception e) {
            throw new HlaBindingException("binding failed at " + current + ": " + e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : " " + e.getMessage()), e);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        BindingReport report = new BindingReport(objects.size(), attributeCount, interactions.size(), parameterCount, missing, ms);
        return new HlaEvolvedBinding(model, objects, objectsByIndex, interactions, interactionsByIndex, report);
    }

    /** @return the model */
    public InformationExchangeModel getModel() {
        return model;
    }

    /** @return what was bound and what the federation did not know */
    public BindingReport getReport() {
        return report;
    }

    // ------------------------------------------------------------------ object classes

    /**
     * @param handle object class handle (e.g. the known class from discovery)
     * @return the IEM class, or null when not bound
     */
    public ObjectClass getObjectClass(ObjectClassHandle handle) {
        ObjectEntry e = objectsByHandle.get(handle);
        return e == null ? null : e.cls;
    }

    /**
     * @param objectClass IEM class
     * @return its handle, or null when the federation does not know it
     */
    public ObjectClassHandle getObjectClassHandle(ObjectClass objectClass) {
        ObjectEntry e = objectsByIndex[objectClass.getIndex()];
        return e == null ? null : e.handle;
    }

    /**
     * @param knownClass the object's known class handle
     * @param handle     attribute handle
     * @return the attribute as seen on that class, or null when not bound
     */
    public ClassAttribute getAttribute(ObjectClassHandle knownClass, AttributeHandle handle) {
        ObjectEntry e = objectsByHandle.get(knownClass);
        return e == null ? null : e.byHandle.get(handle);
    }

    /**
     * @param objectClass IEM class
     * @param attributeId IEM attribute id (declared or inherited)
     * @return the handle, or null
     */
    public AttributeHandle getAttributeHandle(ObjectClass objectClass, int attributeId) {
        ObjectEntry e = objectsByIndex[objectClass.getIndex()];
        return e == null ? null : e.byId.get(attributeId);
    }

    /**
     * All bound attribute handles available on a class, inherited included (e.g. to build an
     * {@code AttributeHandleSet} for subscribing).
     *
     * @param objectClass IEM class
     * @return handles; empty when the class is not bound
     */
    public Collection<AttributeHandle> getAttributeHandles(ObjectClass objectClass) {
        ObjectEntry e = objectsByIndex[objectClass.getIndex()];
        return e == null ? Collections.<AttributeHandle>emptyList()
            : Collections.unmodifiableCollection(e.byId.values());
    }

    // ------------------------------------------------------------------ interaction classes

    /**
     * @param handle interaction class handle
     * @return the IEM class, or null when not bound
     */
    public InteractionClass getInteractionClass(InteractionClassHandle handle) {
        InteractionEntry e = interactionsByHandle.get(handle);
        return e == null ? null : e.cls;
    }

    /**
     * @param interactionClass IEM class
     * @return its handle, or null
     */
    public InteractionClassHandle getInteractionClassHandle(InteractionClass interactionClass) {
        InteractionEntry e = interactionsByIndex[interactionClass.getIndex()];
        return e == null ? null : e.handle;
    }

    /**
     * @param interactionClass interaction class handle
     * @param handle           parameter handle
     * @return the parameter as seen on that class, or null
     */
    public ClassParameter getParameter(InteractionClassHandle interactionClass, ParameterHandle handle) {
        InteractionEntry e = interactionsByHandle.get(interactionClass);
        return e == null ? null : e.byHandle.get(handle);
    }

    /**
     * @param interactionClass IEM class
     * @param parameterId      IEM parameter id
     * @return the handle, or null
     */
    public ParameterHandle getParameterHandle(InteractionClass interactionClass, int parameterId) {
        InteractionEntry e = interactionsByIndex[interactionClass.getIndex()];
        return e == null ? null : e.byId.get(parameterId);
    }

    // ------------------------------------------------------------------ capture

    /**
     * Captures a reflect on the RTI callback thread: handles to ids, octets copied. Cheap;
     * decode later with {@code HlaDataDecoder.decode(RawValues)}.
     *
     * @param knownClass     the object's known class handle
     * @param values         the reflected values
     * @param receivedMillis receive time, epoch ms UTC
     * @return the captured values
     */
    public RawValues capture(ObjectClassHandle knownClass, AttributeHandleValueMap values, long receivedMillis) {
        ObjectEntry e = objectsByHandle.get(knownClass);
        int n = values.size();
        int[] ids = new int[n];
        String[] labels = new String[n];
        byte[][] data = new byte[n][];
        int i = 0;
        for (Map.Entry<AttributeHandle, byte[]> v : values.entrySet()) {
            ClassAttribute a = e == null ? null : e.byHandle.get(v.getKey());
            ids[i] = a == null ? IemElement.NO_ID : a.getId();
            labels[i] = a == null ? "attribute " + v.getKey() : null;
            byte[] b = v.getValue();
            data[i] = b == null ? new byte[0] : b.clone();
            i++;
        }
        return new RawValues(RawValues.Kind.ATTRIBUTES, e == null ? IemElement.NO_ID : e.cls.getId(),
                             receivedMillis, ids, labels, data);
    }

    /**
     * Captures a received interaction on the RTI callback thread.
     *
     * @param interactionClass interaction class handle
     * @param values           the parameter values
     * @param receivedMillis   receive time, epoch ms UTC
     * @return the captured values
     */
    public RawValues capture(InteractionClassHandle interactionClass, ParameterHandleValueMap values, long receivedMillis) {
        InteractionEntry e = interactionsByHandle.get(interactionClass);
        int n = values.size();
        int[] ids = new int[n];
        String[] labels = new String[n];
        byte[][] data = new byte[n][];
        int i = 0;
        for (Map.Entry<ParameterHandle, byte[]> v : values.entrySet()) {
            ClassParameter p = e == null ? null : e.byHandle.get(v.getKey());
            ids[i] = p == null ? IemElement.NO_ID : p.getId();
            labels[i] = p == null ? "parameter " + v.getKey() : null;
            byte[] b = v.getValue();
            data[i] = b == null ? new byte[0] : b.clone();
            i++;
        }
        return new RawValues(RawValues.Kind.PARAMETERS, e == null ? IemElement.NO_ID : e.cls.getId(),
                             receivedMillis, ids, labels, data);
    }

    @Override
    public String toString() {
        return "HlaEvolvedBinding[" + report + "]";
    }
}
