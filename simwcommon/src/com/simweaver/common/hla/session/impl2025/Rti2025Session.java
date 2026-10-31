package com.simweaver.common.hla.session.impl2025;

import com.simweaver.common.hla.session.ConnectSettings;
import com.simweaver.common.hla.session.RtiSession;
import com.simweaver.common.hla.session.RtiSessionException;
import com.simweaver.common.hla.session.RtiSessionListener;
import hla.rti1516_2025.AttributeHandle;
import hla.rti1516_2025.AttributeHandleSet;
import hla.rti1516_2025.AttributeHandleValueMap;
import hla.rti1516_2025.CallbackModel;
import hla.rti1516_2025.FederateHandle;
import hla.rti1516_2025.FederationExecutionInformation;
import hla.rti1516_2025.FederationExecutionInformationSet;
import hla.rti1516_2025.InteractionClassHandle;
import hla.rti1516_2025.time.LogicalTime;
import hla.rti1516_2025.RegionHandleSet;
import hla.rti1516_2025.RtiConfiguration;
import hla.rti1516_2025.MessageRetractionHandle;
import hla.rti1516_2025.NullFederateAmbassador;
import hla.rti1516_2025.ObjectClassHandle;
import hla.rti1516_2025.ObjectInstanceHandle;
import hla.rti1516_2025.OrderType;
import hla.rti1516_2025.ParameterHandle;
import hla.rti1516_2025.ParameterHandleValueMap;
import hla.rti1516_2025.RTIambassador;
import hla.rti1516_2025.ResignAction;
import hla.rti1516_2025.RtiFactory;
import hla.rti1516_2025.RtiFactoryFactory;
import hla.rti1516_2025.TransportationTypeHandle;
import hla.rti1516_2025.exceptions.FederateInternalError;
import hla.rti1516_2025.exceptions.FederationExecutionAlreadyExists;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link RtiSession} on the IEEE 1516-2025 (HLA 4) Java API ({@code hla.rti1516_2025}); also the API of the Federate Protocol clients.
 *
 * <p>Created only by {@code RtiSessions} through reflection, so that nothing outside this
 * package links against the RTI API. When the RTI comes from an isolated {@code RtiLibrary},
 * this class is defined again inside that library's class loader and links against that
 * vendor's API classes. It therefore uses nothing of SimWeaver except the session API.
 */
public final class Rti2025Session implements RtiSession {

    private final String libraryDescription;

    private volatile RTIambassador rti;
    private volatile RtiSessionListener listener;
    private volatile boolean connected;
    private volatile boolean joined;
    private volatile String federation;

    // handles <-> session ints
    private final Handles<ObjectClassHandle> objectClasses = new Handles<ObjectClassHandle>();
    private final Handles<AttributeHandle> attributes = new Handles<AttributeHandle>();
    private final Handles<InteractionClassHandle> interactionClasses = new Handles<InteractionClassHandle>();
    private final Handles<ParameterHandle> parameters = new Handles<ParameterHandle>();
    private final Map<ObjectInstanceHandle, Long> instanceIds = new ConcurrentHashMap<ObjectInstanceHandle, Long>();
    private final Map<Long, ObjectInstanceHandle> instances = new ConcurrentHashMap<Long, ObjectInstanceHandle>();
    private final Map<Long, String> instanceNames = new ConcurrentHashMap<Long, String>();
    private final AtomicLong nextInstance = new AtomicLong();
    private final Map<FederateHandle, String> federateNames = new ConcurrentHashMap<FederateHandle, String>();
    private final AtomicReference<CountDownLatch> federationReport = new AtomicReference<CountDownLatch>();
    private volatile List<String> reportedFederations = Collections.emptyList();

    /** @param libraryDescription where the RTI classes come from (for messages) */
    public Rti2025Session(String libraryDescription) {
        this.libraryDescription = libraryDescription;
    }

    @Override
    public Version version() {
        return Version.HLA_1516_2025;
    }

    @Override
    public String libraryDescription() {
        return libraryDescription;
    }

    // ================================================================== lifecycle

    @Override
    public synchronized void connect(ConnectSettings settings, RtiSessionListener l) throws RtiSessionException {
        if (connected) {
            throw new RtiSessionException("already connected");
        }
        this.listener = l;
        Thread th = Thread.currentThread();
        ClassLoader saved = th.getContextClassLoader();
        // the RTI factory is found with ServiceLoader (context class loader), and the RTI's own
        // threads, created while connecting, inherit it: use the loader that defined this class
        th.setContextClassLoader(Rti2025Session.class.getClassLoader());
        try {
            RtiFactory factory = settings.getFactoryName() == null ? RtiFactoryFactory.getRtiFactory()
                    : RtiFactoryFactory.getRtiFactory(settings.getFactoryName());
            RTIambassador amb = factory.getRtiAmbassador();
            Ambassador fa = new Ambassador();
            if (settings.getLocalSettingsDesignator() == null && settings.getRtiAddress() == null
                    && settings.getAdditionalSettings() == null) {
                amb.connect(fa, CallbackModel.HLA_IMMEDIATE);
            } else {
                RtiConfiguration c = RtiConfiguration.createConfiguration();
                if (settings.getLocalSettingsDesignator() != null) {
                    c = c.withConfigurationName(settings.getLocalSettingsDesignator());
                }
                if (settings.getRtiAddress() != null) {
                    c = c.withRtiAddress(settings.getRtiAddress());
                }
                if (settings.getAdditionalSettings() != null) {
                    c = c.withAdditionalSettings(settings.getAdditionalSettings());
                }
                amb.connect(fa, CallbackModel.HLA_IMMEDIATE, c);
            }
            this.rti = amb;
            this.connected = true;
        } catch (Exception | LinkageError e) {
            throw RtiSessionException.of(e);
        } finally {
            th.setContextClassLoader(saved);
        }
    }

    @Override
    public List<String> listFederations(long timeoutMillis) throws RtiSessionException {
        RTIambassador amb = requireConnected();
        CountDownLatch latch = new CountDownLatch(1);
        federationReport.set(latch);
        try {
            amb.listFederationExecutions();
            if (!latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                return Collections.emptyList();
            }
            return reportedFederations;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        } catch (Exception e) {
            throw RtiSessionException.of(e);
        } finally {
            federationReport.set(null);
        }
    }

    @Override
    public synchronized String join(String federationName, String federateType, String federateName,
                                    List<String> fomModules, boolean createIfMissing) throws RtiSessionException {
        RTIambassador amb = requireConnected();
        String[] urls = fomModules == null ? new String[0] : fomModules.toArray(new String[0]);
        Thread th = Thread.currentThread();
        ClassLoader saved = th.getContextClassLoader();
        th.setContextClassLoader(Rti2025Session.class.getClassLoader());
        try {
            if (createIfMissing) {
                try {
                    amb.createFederationExecution(federationName, urls);
                } catch (FederationExecutionAlreadyExists ignored) {
                    // joined below
                }
            }
            FederateHandle me;
            if (urls.length == 0) {                       // no modules: the federation's FDD (dynamic FOM)
                me = federateName == null || federateName.isEmpty() ? amb.joinFederationExecution(federateType, federationName)
                        : amb.joinFederationExecution(federateName, federateType, federationName);
            } else if (federateName == null || federateName.isEmpty()) {
                me = amb.joinFederationExecution(federateType, federationName, urls);
            } else {
                me = amb.joinFederationExecution(federateName, federateType, federationName, urls);
            }
            this.federation = federationName;
            this.joined = true;
            String name = federateName;
            try {
                name = amb.getFederateName(me);
            } catch (Exception ignored) {
                // keep the requested name
            }
            return name == null ? "" : name;
        } catch (Exception e) {
            throw RtiSessionException.of(e);
        } finally {
            th.setContextClassLoader(saved);
        }
    }

    @Override
    public synchronized void resign(boolean destroyFederation) {
        RTIambassador amb = rti;
        if (amb == null || !joined) {
            return;
        }
        joined = false;
        try {
            amb.resignFederationExecution(ResignAction.DELETE_OBJECTS_THEN_DIVEST);
        } catch (Exception e) {
            try {
                amb.resignFederationExecution(ResignAction.NO_ACTION);
            } catch (Exception ignored) {
                // nothing more to do
            }
        }
        if (destroyFederation && federation != null) {
            try {
                amb.destroyFederationExecution(federation);
            } catch (Exception ignored) {
                // other federates still joined, or already gone
            }
        }
    }

    @Override
    public synchronized void disconnect() {
        RTIambassador amb = rti;
        if (amb == null || !connected) {
            return;
        }
        connected = false;
        try {
            amb.disconnect();
        } catch (Exception ignored) {
            // the connection may already be gone
        }
    }

    @Override
    public void close() {
        resign(false);
        disconnect();
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public boolean isJoined() {
        return joined;
    }

    // ================================================================== handles

    @Override
    public int objectClass(String qualifiedName) throws RtiSessionException {
        try {
            return objectClasses.id(requireConnected().getObjectClassHandle(qualifiedName));
        } catch (Exception e) {
            throw RtiSessionException.of(e);
        }
    }

    @Override
    public int attribute(int objectClass, String attributeName) throws RtiSessionException {
        try {
            return attributes.id(requireConnected().getAttributeHandle(objectClasses.handle(objectClass), attributeName));
        } catch (Exception e) {
            throw RtiSessionException.of(e);
        }
    }

    @Override
    public int interactionClass(String qualifiedName) throws RtiSessionException {
        try {
            return interactionClasses.id(requireConnected().getInteractionClassHandle(qualifiedName));
        } catch (Exception e) {
            throw RtiSessionException.of(e);
        }
    }

    @Override
    public int parameter(int interactionClass, String parameterName) throws RtiSessionException {
        try {
            return parameters.id(requireConnected().getParameterHandle(interactionClasses.handle(interactionClass), parameterName));
        } catch (Exception e) {
            throw RtiSessionException.of(e);
        }
    }

    @Override
    public void publishObjectClass(int objectClass, int[] attrs) throws RtiSessionException {
        try {
            RTIambassador amb = requireConnected();
            amb.publishObjectClassAttributes(objectClasses.handle(objectClass), set(amb, attrs));
        } catch (Exception e) {
            throw RtiSessionException.of(e);
        }
    }

    @Override
    public void subscribeObjectClass(int objectClass, int[] attrs) throws RtiSessionException {
        try {
            RTIambassador amb = requireConnected();
            amb.subscribeObjectClassAttributes(objectClasses.handle(objectClass), set(amb, attrs));
        } catch (Exception e) {
            throw RtiSessionException.of(e);
        }
    }

    @Override
    public void publishInteractionClass(int interactionClass) throws RtiSessionException {
        try {
            requireConnected().publishInteractionClass(interactionClasses.handle(interactionClass));
        } catch (Exception e) {
            throw RtiSessionException.of(e);
        }
    }

    @Override
    public void subscribeInteractionClass(int interactionClass) throws RtiSessionException {
        try {
            requireConnected().subscribeInteractionClass(interactionClasses.handle(interactionClass));
        } catch (Exception e) {
            throw RtiSessionException.of(e);
        }
    }

    // ================================================================== objects and interactions

    @Override
    public void reserveName(String name) throws RtiSessionException {
        try {
            requireConnected().reserveObjectInstanceName(name);
        } catch (Exception e) {
            throw RtiSessionException.of(e);
        }
    }

    @Override
    public void releaseName(String name) {
        try {
            RTIambassador amb = rti;
            if (amb != null) {
                amb.releaseObjectInstanceName(name);
            }
        } catch (Exception ignored) {
            // not reserved, or the connection is gone
        }
    }

    @Override
    public long registerObject(int objectClass, String name) throws RtiSessionException {
        try {
            RTIambassador amb = requireConnected();
            ObjectInstanceHandle h = name == null ? amb.registerObjectInstance(objectClasses.handle(objectClass))
                    : amb.registerObjectInstance(objectClasses.handle(objectClass), name);
            long id = instanceId(h);
            String n = name;
            if (n == null) {
                try {
                    n = amb.getObjectInstanceName(h);
                } catch (Exception ignored) {
                    n = null;
                }
            }
            if (n != null) {
                instanceNames.put(id, n);
            }
            return id;
        } catch (Exception e) {
            throw RtiSessionException.of(e);
        }
    }

    @Override
    public String objectName(long instance) {
        return instanceNames.get(instance);
    }

    @Override
    public void updateAttributes(long instance, int[] attrs, byte[][] values, byte[] tag) throws RtiSessionException {
        try {
            RTIambassador amb = requireConnected();
            AttributeHandleValueMap m = amb.getAttributeHandleValueMapFactory().create(attrs.length);
            for (int i = 0; i < attrs.length; i++) {
                m.put(attributes.handle(attrs[i]), values[i]);
            }
            amb.updateAttributeValues(instanceHandle(instance), m, tag == null ? new byte[0] : tag);
        } catch (Exception e) {
            throw RtiSessionException.of(e);
        }
    }

    @Override
    public void sendInteraction(int interactionClass, int[] params, byte[][] values, byte[] tag) throws RtiSessionException {
        try {
            RTIambassador amb = requireConnected();
            ParameterHandleValueMap m = amb.getParameterHandleValueMapFactory().create(params.length);
            for (int i = 0; i < params.length; i++) {
                m.put(parameters.handle(params[i]), values[i]);
            }
            amb.sendInteraction(interactionClasses.handle(interactionClass), m, tag == null ? new byte[0] : tag);
        } catch (Exception e) {
            throw RtiSessionException.of(e);
        }
    }

    @Override
    public void deleteObject(long instance, byte[] tag) throws RtiSessionException {
        try {
            requireConnected().deleteObjectInstance(instanceHandle(instance), tag == null ? new byte[0] : tag);
            forget(instance);
        } catch (Exception e) {
            throw RtiSessionException.of(e);
        }
    }

    @Override
    public void requestAttributeUpdate(long instance, int[] attrs, byte[] tag) throws RtiSessionException {
        try {
            RTIambassador amb = requireConnected();
            amb.requestAttributeValueUpdate(instanceHandle(instance), set(amb, attrs), tag == null ? new byte[0] : tag);
        } catch (Exception e) {
            throw RtiSessionException.of(e);
        }
    }

    @Override
    public void requestClassAttributeUpdate(int objectClass, int[] attrs, byte[] tag) throws RtiSessionException {
        try {
            RTIambassador amb = requireConnected();
            amb.requestAttributeValueUpdate(objectClasses.handle(objectClass), set(amb, attrs), tag == null ? new byte[0] : tag);
        } catch (Exception e) {
            throw RtiSessionException.of(e);
        }
    }

    // ================================================================== names from encoded handles (0.9.20)

    @Override
    public String objectClassName(byte[] encodedHandle) {
        try {
            RTIambassador amb = requireConnected();
            return amb.getObjectClassName(amb.getObjectClassHandleFactory().decode(encodedHandle, 0));
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    @Override
    public String interactionClassName(byte[] encodedHandle) {
        try {
            RTIambassador amb = requireConnected();
            return amb.getInteractionClassName(amb.getInteractionClassHandleFactory().decode(encodedHandle, 0));
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    @Override
    public String attributeName(byte[] encodedClass, byte[] encodedAttribute) {
        try {
            RTIambassador amb = requireConnected();
            return amb.getAttributeName(amb.getObjectClassHandleFactory().decode(encodedClass, 0),
                    amb.getAttributeHandleFactory().decode(encodedAttribute, 0));
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    // ================================================================== helpers

    private RTIambassador requireConnected() throws RtiSessionException {
        RTIambassador amb = rti;
        if (amb == null || !connected) {
            throw new RtiSessionException("NotConnected", "the session is not connected", null);
        }
        return amb;
    }

    private AttributeHandleSet set(RTIambassador amb, int[] attrs) throws Exception {
        AttributeHandleSet s = amb.getAttributeHandleSetFactory().create();
        for (int a : attrs) {
            s.add(attributes.handle(a));
        }
        return s;
    }

    private long instanceId(ObjectInstanceHandle h) {
        Long id = instanceIds.get(h);
        if (id != null) {
            return id;
        }
        long n = nextInstance.incrementAndGet();
        Long prev = instanceIds.putIfAbsent(h, n);
        if (prev != null) {
            return prev;
        }
        instances.put(n, h);
        return n;
    }

    private ObjectInstanceHandle instanceHandle(long id) throws RtiSessionException {
        ObjectInstanceHandle h = instances.get(id);
        if (h == null) {
            throw new RtiSessionException("ObjectInstanceNotKnown", "no instance " + id, null);
        }
        return h;
    }

    private void forget(long id) {
        ObjectInstanceHandle h = instances.remove(id);
        if (h != null) {
            instanceIds.remove(h);
        }
        instanceNames.remove(id);
    }

    private String federateName(FederateHandle h) {
        if (h == null) {
            return "";
        }
        String n = federateNames.get(h);
        if (n == null) {
            try {
                RTIambassador amb = rti;
                n = amb == null ? "" : amb.getFederateName(h);
            } catch (Exception e) {
                n = "";
            }
            federateNames.put(h, n);
        }
        return n;
    }

    private int[] attributeIds(AttributeHandleValueMap m, byte[][] values) {
        int[] ids = new int[m.size()];
        int i = 0;
        for (Map.Entry<AttributeHandle, byte[]> e : m.entrySet()) {
            ids[i] = attributes.id(e.getKey());
            values[i] = e.getValue();
            i++;
        }
        return ids;
    }

    private int[] attributeIds(AttributeHandleSet s) {
        int[] ids = new int[s.size()];
        int i = 0;
        for (AttributeHandle h : s) {
            ids[i++] = attributes.id(h);
        }
        return ids;
    }

    /** RTI handle objects to dense session ints (1, 2, ...) and back. */
    private static final class Handles<H> {
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

        synchronized H handle(int id) throws RtiSessionException {
            if (id <= 0 || id > byId.size()) {
                throw new RtiSessionException("InvalidHandle", "unknown handle " + id, null);
            }
            return byId.get(id - 1);
        }
    }

    // ================================================================== callbacks

    /** The federate ambassador: converts and hands over, never throws into the RTI. */
    private final class Ambassador extends NullFederateAmbassador {

        private void failed(String what, Throwable t) {
            System.err.println("RtiSession (1516-2025): listener failed in " + what + ": " + t);
        }

        @Override
        public void connectionLost(String faultDescription) throws FederateInternalError {
            connected = false;
            joined = false;
            try {
                listener.connectionLost(faultDescription);
            } catch (RuntimeException e) {
                failed("connectionLost", e);
            }
        }

        @Override
        public void reportFederationExecutions(FederationExecutionInformationSet set) throws FederateInternalError {
            List<String> names = new ArrayList<String>();
            for (FederationExecutionInformation i : set) {
                names.add(i.federationExecutionName);
            }
            Collections.sort(names);
            reportedFederations = Collections.unmodifiableList(names);
            CountDownLatch l = federationReport.get();
            if (l != null) {
                l.countDown();
            }
        }

        @Override
        public void objectInstanceNameReservationSucceeded(String name) throws FederateInternalError {
            try {
                listener.nameReserved(name, true);
            } catch (RuntimeException e) {
                failed("nameReserved", e);
            }
        }

        @Override
        public void objectInstanceNameReservationFailed(String name) throws FederateInternalError {
            try {
                listener.nameReserved(name, false);
            } catch (RuntimeException e) {
                failed("nameReserved", e);
            }
        }

        @Override
        public void discoverObjectInstance(ObjectInstanceHandle theObject, ObjectClassHandle theObjectClass, String objectName,
                                           FederateHandle producingFederate) throws FederateInternalError {
            try {
                long id = instanceId(theObject);
                if (objectName != null) {
                    instanceNames.put(id, objectName);
                }
                listener.discovered(id, objectClasses.id(theObjectClass), objectName, federateName(producingFederate));
            } catch (RuntimeException e) {
                failed("discovered", e);
            }
        }

        @Override
        public void reflectAttributeValues(ObjectInstanceHandle theObject, AttributeHandleValueMap theAttributes,
                                           byte[] userSuppliedTag, TransportationTypeHandle transportationType,
                                           FederateHandle producingFederate, RegionHandleSet optionalSentRegions)
                throws FederateInternalError {
            try {
                Long id = instanceIds.get(theObject);
                if (id == null) {
                    return;
                }
                byte[][] values = new byte[theAttributes.size()][];
                int[] ids = attributeIds(theAttributes, values);
                listener.reflected(id, ids, values, userSuppliedTag, federateName(producingFederate));
            } catch (RuntimeException e) {
                failed("reflected", e);
            }
        }

        @Override
        public void reflectAttributeValues(ObjectInstanceHandle theObject, AttributeHandleValueMap theAttributes,
                                           byte[] userSuppliedTag, TransportationTypeHandle transportationType,
                                           FederateHandle producingFederate, RegionHandleSet optionalSentRegions,
                                           LogicalTime<?, ?> time, OrderType sentOrderType, OrderType receivedOrderType,
                                           MessageRetractionHandle optionalRetraction) throws FederateInternalError {
            reflectAttributeValues(theObject, theAttributes, userSuppliedTag, transportationType, producingFederate, optionalSentRegions);
        }

        @Override
        public void receiveInteraction(InteractionClassHandle interactionClass, ParameterHandleValueMap theParameters,
                                       byte[] userSuppliedTag, TransportationTypeHandle transportationType,
                                       FederateHandle producingFederate, RegionHandleSet optionalSentRegions)
                throws FederateInternalError {
            try {
                int[] ids = new int[theParameters.size()];
                byte[][] values = new byte[ids.length][];
                int i = 0;
                for (Map.Entry<ParameterHandle, byte[]> e : theParameters.entrySet()) {
                    ids[i] = parameters.id(e.getKey());
                    values[i] = e.getValue();
                    i++;
                }
                listener.received(interactionClasses.id(interactionClass), ids, values, userSuppliedTag, federateName(producingFederate));
            } catch (RuntimeException e) {
                failed("received", e);
            }
        }

        @Override
        public void receiveInteraction(InteractionClassHandle interactionClass, ParameterHandleValueMap theParameters,
                                       byte[] userSuppliedTag, TransportationTypeHandle transportationType,
                                       FederateHandle producingFederate, RegionHandleSet optionalSentRegions,
                                       LogicalTime<?, ?> time, OrderType sentOrderType, OrderType receivedOrderType,
                                       MessageRetractionHandle optionalRetraction) throws FederateInternalError {
            receiveInteraction(interactionClass, theParameters, userSuppliedTag, transportationType, producingFederate, optionalSentRegions);
        }

        @Override
        public void removeObjectInstance(ObjectInstanceHandle theObject, byte[] userSuppliedTag, FederateHandle producingFederate)
                throws FederateInternalError {
            try {
                Long id = instanceIds.get(theObject);
                if (id == null) {
                    return;
                }
                listener.removed(id, userSuppliedTag, federateName(producingFederate));
                forget(id);
            } catch (RuntimeException e) {
                failed("removed", e);
            }
        }

        @Override
        public void removeObjectInstance(ObjectInstanceHandle theObject, byte[] userSuppliedTag, FederateHandle producingFederate,
                                         LogicalTime<?, ?> time, OrderType sentOrderType, OrderType receivedOrderType,
                                         MessageRetractionHandle optionalRetraction) throws FederateInternalError {
            removeObjectInstance(theObject, userSuppliedTag, producingFederate);
        }

        @Override
        public void provideAttributeValueUpdate(ObjectInstanceHandle theObject, AttributeHandleSet theAttributes,
                                                byte[] userSuppliedTag) throws FederateInternalError {
            try {
                Long id = instanceIds.get(theObject);
                if (id != null) {
                    listener.provideRequested(id, attributeIds(theAttributes), userSuppliedTag);
                }
            } catch (RuntimeException e) {
                failed("provideRequested", e);
            }
        }
    }
}
