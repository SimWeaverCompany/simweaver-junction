package com.simweaver.rift.datasources;

import com.simweaver.common.SimwTimeStamp;
import com.simweaver.common.hla.decoders.HlaDataDecoder;
import com.simweaver.common.hla.decoders.RawValues;
import com.simweaver.common.hla.decoders.evolved.HlaEvolvedBinding;
import com.simweaver.common.iem.*;
import com.simweaver.common.json.Json;
import com.simweaver.rift.DatasourceConfig;
import com.simweaver.rift.DatasourceManager;
import com.simweaver.rift.api.DataSource;
import com.simweaver.rift.api.DataSourceCallbackSink;
import com.simweaver.rift.api.DatasourceFactory;
import com.simweaver.rift.db.RiftAttribute;
import com.simweaver.rift.db.RiftDatabase;
import com.simweaver.rift.db.RiftParameter;
import hla.rti1516e.*;
import hla.rti1516e.OrderType;
import hla.rti1516e.exceptions.*;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RiftFederateHla3 extends NullFederateAmbassador implements DataSource {

    private UUID id;
    private String name;
    private DatasourceConfig config;
    private String customFederation;
    private String effectiveFederation;
    private InformationExchangeModel iem;
    private RiftDatabase db;
    // NEW (0.9.14) datasource.hla.foms=dynamic: the FOM is the federation's FDD (MOM HLAcurrentFDD)
    private volatile boolean fddReading;
    private volatile ObjectClassHandle fddClass;
    private volatile AttributeHandle fddAttribute;
    private volatile ObjectInstanceHandle fddInstance;
    private volatile String fddInstanceName;
    private String fddText;
    private final Object fddLock = new Object();
    private final DataSourceCallbackSink callbackSink;
    private volatile RiftMomPortHla3 infra;                      // 0.9.20: the Infra view's MOM access and counts     // told about a lost RTI connection

    private RTIambassador rtIambassador;

    private ConcurrentHashMap<InteractionClassHandle,String> mapIhdlName;
    private ConcurrentHashMap<InteractionClassHandle,Integer> mapIhdlIemId;
    private ConcurrentHashMap<String,InteractionClassHandle> mapNameIhdl;
    private ConcurrentHashMap<String,Integer> mapNameIemId;
    private ConcurrentHashMap<Integer,InteractionClassHandle> mapIemIdIhdl;
    private ConcurrentHashMap<Integer,String> mapIemIdName;

    private ConcurrentHashMap<AttributeHandle,String> mapAhdlName;
    private ConcurrentHashMap<AttributeHandle,Integer> mapAhdlIemId;
    private ConcurrentHashMap<String, AttributeHandle> mapAnameAhdl;
    private ConcurrentHashMap<String, Integer> mapAnameIemId;
    private ConcurrentHashMap<Integer, AttributeHandle> mapIemIdAhdl;
    private ConcurrentHashMap<Integer, String> mapIemIdAname;

    private ConcurrentHashMap<ObjectClassHandle,String> mapOhdlName;
    private ConcurrentHashMap<ObjectClassHandle,Integer> mapOhdlIemId;
    private final String lsd;
    private ConcurrentHashMap<Integer, ObjectClassHandle> mapIemIdOhdl;
    private ConcurrentHashMap<Integer,String> mapIemIdOname;
    private ConcurrentHashMap<String, ObjectClassHandle> mapOnameOhdl;
    private ConcurrentHashMap<String,Integer> mapOnameIemId;

    private ConcurrentHashMap<ObjectInstanceHandle,Integer> mapOinstHdlDbId;
    // FIX: known class per instance (from discovery) and federate names, instead of an RTI call per reflect
    private final ConcurrentHashMap<ObjectInstanceHandle, ObjectClassHandle> mapOinstHdlKnownClass = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<FederateHandle, String> mapFedHdlName = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Integer,ObjectInstanceHandle> mapDbIdOinstHdl;

    private HlaDataDecoder decoder;
    private HlaEvolvedBinding binding;

    public RiftFederateHla3(DatasourceConfig config, String customFederation, DataSourceCallbackSink callbackSink) {
        this(config, customFederation, null, callbackSink);
    }

    /** @param lsd 0.9.15: the LSD / RTI configuration name to connect with; null = the configuration's default */
    public RiftFederateHla3(DatasourceConfig config, String customFederation, String lsd, DataSourceCallbackSink callbackSink) {
        this.lsd = lsd == null ? config.getHlalsd() : lsd.trim();
        this.id = UUID.randomUUID();
        this.callbackSink = callbackSink;
        this.config = config;
        this.customFederation = customFederation;
        if (customFederation == null || customFederation.isBlank()){
            effectiveFederation = config.getHlaFederation();
        }
        else{
            effectiveFederation = customFederation;
        }
        this.name = DatasourceFactory.generateDataSourceName(this.config.getName(),this.effectiveFederation);
        this.rtIambassador = null;
        this.db = new RiftDatabase(this.name);              // FIX: named, for thread names and log messages

        mapIhdlName = new ConcurrentHashMap<>();
        mapIhdlIemId = new ConcurrentHashMap<>();
        mapNameIhdl = new ConcurrentHashMap<>();
        mapNameIemId = new ConcurrentHashMap<>();
        mapIemIdIhdl = new ConcurrentHashMap<>();
        mapIemIdName = new ConcurrentHashMap<>();

        mapAhdlName = new ConcurrentHashMap<>();
        mapAhdlIemId = new ConcurrentHashMap<>();
        mapAnameAhdl = new ConcurrentHashMap<>();
        mapAnameIemId = new ConcurrentHashMap<>();
        mapIemIdAhdl = new ConcurrentHashMap<>();
        mapIemIdAname = new ConcurrentHashMap<>();

        mapOnameIemId = new ConcurrentHashMap<>();
        mapOnameOhdl = new ConcurrentHashMap<>();
        mapIemIdOhdl = new ConcurrentHashMap<>();
        mapIemIdOname = new ConcurrentHashMap<>();
        mapOhdlName = new ConcurrentHashMap<>();
        mapOhdlIemId = new ConcurrentHashMap<>();

        mapOinstHdlDbId = new ConcurrentHashMap<>();
        mapDbIdOinstHdl = new ConcurrentHashMap<>();
    }

    @Override
    public UUID getId() {
        return this.id;
    }

    @Override
    public DatasourceManager.OpResult connectJoinSub() {
        FederateHandle joinedAs = null;

        try {
            if (config.isHlaDynamicFom()) {
                // NEW (0.9.14): join the running federation without modules, take its FDD as the FOM
                rtIambassador = RtiFactoryFactory.getRtiFactory().getRtiAmbassador();
                synchronized (com.simweaver.common.hla.session.RtiLibrary.classPath().connectLock()) {        // one connect / join at a time on the RTI
                    rtIambassador.connect(this, CallbackModel.HLA_IMMEDIATE, this.lsd);
                    joinedAs = rtIambassador.joinFederationExecution(config.getHlaFederateType(), effectiveFederation);
                }
                String fdd = readFdd();
                iem = com.simweaver.common.hla.session.DynamicFom.toModel(fdd, false);
                System.out.println("Datasource " + name + ": FOM taken from federation " + effectiveFederation + " (FDD "
                        + com.simweaver.common.hla.session.DynamicFom.fingerprint(fdd) + ", " + iem.getObjectClasses().size()
                        + " object classes" + com.simweaver.common.hla.session.DynamicFom.formatNote(fdd, false)
                        + (iem.getRprFomVersion() > 0 ? ", RPR v" + iem.getRprFomVersion() : "") + ")");
                db.setInformationExchangeModel(iem);
                decoder = HlaDataDecoder.create(iem);
                db.setDecoder(decoder);
            } else {
            iem = InformationExchangeModel.fromHlaEvolved(config.getHlafoms());
            db.setInformationExchangeModel(iem);
            rtIambassador = RtiFactoryFactory.getRtiFactory().getRtiAmbassador();
            decoder = HlaDataDecoder.create(iem);

            db.setDecoder(decoder);
            synchronized (com.simweaver.common.hla.session.RtiLibrary.classPath().connectLock()) {            // one connect / join at a time on the RTI
                rtIambassador.connect(this, CallbackModel.HLA_IMMEDIATE,this.lsd);
                try{
                    rtIambassador.createFederationExecution(effectiveFederation,config.getHlaFomModuleUrls());
                }catch (FederationExecutionAlreadyExists ignored){
                    // joined below
                }
                joinedAs = rtIambassador.joinFederationExecution(config.getHlaFederateType(),effectiveFederation,config.getHlaFomModuleUrls());
            }
            }
            binding = HlaEvolvedBinding.bind(iem,rtIambassador);

            // pubsub and class handles
            ArrayList<String> objectClassNameList = DatasourceFactory.createObjectClassNameList(iem);
            for (String name : objectClassNameList) {
                ObjectClassHandle hdl = rtIambassador.getObjectClassHandle(name);
                ObjectClass objectClass = iem.getObjectClass(name);
                mapOhdlIemId.put(hdl,objectClass.getId());
                mapOhdlName.put(hdl,name);
                mapOnameOhdl.put(name,hdl);
                mapOnameIemId.put(name,objectClass.getId());
                mapIemIdOhdl.put(objectClass.getId(),hdl);
                mapIemIdOname.put(objectClass.getId(),name);

                AttributeHandleSet attributeHandles = rtIambassador.getAttributeHandleSetFactory().create();
                for (ClassAttribute attribute : objectClass.getAttributes()) {
                    AttributeHandle aHdl = rtIambassador.getAttributeHandle(hdl,attribute.getName());
                    mapAhdlIemId.put(aHdl,attribute.getId());
                    mapAhdlName.put(aHdl,attribute.getName());          // FIX: was the class name
                    mapIemIdAhdl.put(attribute.getId(),aHdl);
                    mapIemIdAname.put(attribute.getId(),attribute.getName());   // FIX: was the class name
                    mapAnameAhdl.put(attribute.getName(),aHdl);
                    mapAnameIemId.put(attribute.getName(),attribute.getId());
                    attributeHandles.add(aHdl);
                }
                rtIambassador.subscribeObjectClassAttributes(hdl, attributeHandles);

            }

            ArrayList<String> interactionClassNameList = DatasourceFactory.createInteractionClassNameList(iem);
            for (String name : interactionClassNameList) {
                InteractionClassHandle hdl = rtIambassador.getInteractionClassHandle(name);
                mapIhdlName.put(hdl,name);
                mapIhdlIemId.put(hdl,iem.getInteractionClass(name).getId());
                mapIemIdIhdl.put(iem.getInteractionClass(name).getId(),hdl);
                mapNameIhdl.put(name,hdl);
                mapIemIdName.put(iem.getInteractionClass(name).getId(),name);
                mapNameIemId.put(name,iem.getInteractionClass(name).getId());
                rtIambassador.subscribeInteractionClass(hdl);
            }
            db.setInteractionClassNames(mapIemIdName::get);      // FIX: qualified names in the interaction log
            if (config.isHlaDynamicFom()) {
                handOverFddObject();
            }
            openInfra(joinedAs);




        } catch (Exception e) {
            if (config.isHlaDynamicFom() && rtIambassador != null) {
                fddReading = false;
                try {
                    rtIambassador.resignFederationExecution(ResignAction.CANCEL_THEN_DELETE_THEN_DIVEST);
                } catch (Exception ignored) {
                    // not joined
                }
            }
            return new DatasourceManager.OpResult(false,e.getClass().getName() + ": " + e.getMessage());
        }
        return new DatasourceManager.OpResult(true,"Joined " + this.effectiveFederation);
    }

    @Override
    public DatasourceManager.OpResult disconnect() {
        closeInfra();
        if (rtIambassador != null){
            try {
                rtIambassador.resignFederationExecution(ResignAction.CANCEL_THEN_DELETE_THEN_DIVEST);
            } catch (Exception e) {

            }

            try {
                rtIambassador.destroyFederationExecution(effectiveFederation);
            } catch (Exception e) {

            }

            try {
                rtIambassador.disconnect();
            } catch (Exception e) {
                return new DatasourceManager.OpResult(false,e.getMessage());
            }

            return new DatasourceManager.OpResult(true,"Disconnected");
        }
        return new DatasourceManager.OpResult(true,"Already Disconnected");
    }

    @Override
    public RiftDatabase getDatabase() {
        return db;
    }

    //
    @Override
    public Object toJson() {

        /* sample minimum data for listing

         {"id":227688254,"name":"Sample1"},
            {"id":436942241,"name":"Sample2 - Link16 exercise"}

         */
        Json.JsonObject jsonOut = Json.object().put("id", this.id).put("name", this.name).put("hlalsd", this.lsd);
        return jsonOut;
    }





    /** Dynamic FOM: subscribes the MOM federation object and waits for HLAcurrentFDD. */
    private String readFdd() throws Exception {
        String cls = com.simweaver.common.hla.session.DynamicFom.FEDERATION_CLASS;
        String att = com.simweaver.common.hla.session.DynamicFom.FDD_ATTRIBUTE;
        fddClass = rtIambassador.getObjectClassHandle(cls);
        fddAttribute = rtIambassador.getAttributeHandle(fddClass, att);
        AttributeHandleSet set = rtIambassador.getAttributeHandleSetFactory().create();
        set.add(fddAttribute);
        synchronized (fddLock) {
            fddText = null;
        }
        fddInstance = null;
        fddReading = true;
        rtIambassador.subscribeObjectClassAttributes(fddClass, set);
        long end = System.currentTimeMillis() + config.getHlaFddTimeoutSecs() * 1000L;
        synchronized (fddLock) {
            while (fddText == null) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) {
                    throw new Exception("the RTI gave no " + att + " of " + cls + " within " + config.getHlaFddTimeoutSecs() + " s");
                }
                rtIambassador.requestAttributeValueUpdate(fddClass, set, new byte[0]);
                fddLock.wait(Math.min(left, 2000));
            }
            return fddText;
        }
    }

    /** After the subscriptions: the federation object becomes an ordinary object of the datasource. */
    private void handOverFddObject() {
        fddReading = false;
        ObjectInstanceHandle h = fddInstance;
        if (fddClass != null && !mapOhdlIemId.containsKey(fddClass)) {
            // the model has no MOM classes (as with file FOMs): the datasource does not list it
            try {
                rtIambassador.unsubscribeObjectClass(fddClass);
            } catch (Exception ignored) {
                // nothing to undo
            }
            return;
        }
        if (h != null) {
            try {
                discoverObjectInstance(h, fddClass, fddInstanceName, null);
            } catch (Exception e) {
                System.err.println("Datasource " + name + ": the federation object could not be listed: " + e);
            }
        }
    }

    /** @return true when a callback was about the federation object while the FDD is read */
    private boolean fddDiscovered(ObjectInstanceHandle h, ObjectClassHandle c, String n) {
        if (!fddReading || c == null || !c.equals(fddClass)) {
            return false;
        }
        fddInstance = h;
        fddInstanceName = n;
        return true;
    }

    private boolean fddReflected(ObjectInstanceHandle h, AttributeHandleValueMap values) {
        if (!fddReading || h == null || !h.equals(fddInstance)) {
            return false;
        }
        byte[] v = values.get(fddAttribute);
        if (v != null) {
            try {
                String t = com.simweaver.common.hla.session.DynamicFom.decodeUnicodeString(v);
                synchronized (fddLock) {
                    fddText = t;
                    fddLock.notifyAll();
                }
            } catch (IllegalArgumentException ignored) {
                // not an HLAunicodeString: keep waiting
            }
        }
        return true;
    }

    //// CALLBACKS
    //
    // lost

    @Override
    public void connectionLost(String faultDescription) throws FederateInternalError {
        // Runs on an RTI thread: only hand over to the sink (no RTI calls here, the connection is gone)
        System.err.println("Datasource " + name + ": RTI connection lost: " + faultDescription);
        closeInfra();
        if (callbackSink != null) {
            try {
                callbackSink.connectionLost(id);
            } catch (RuntimeException e) {
                e.printStackTrace();   // never let the sink's failure go back into the RTI
            }
        }
    }

    // DISCOVER

    @Override
    public void discoverObjectInstance(ObjectInstanceHandle theObject, ObjectClassHandle theObjectClass, String objectName) throws FederateInternalError {
        this.discoverObjectInstance(theObject, theObjectClass, objectName, null);
    }

    @Override
    public void discoverObjectInstance(ObjectInstanceHandle theObject, ObjectClassHandle theObjectClass, String objectName, FederateHandle producingFederate) throws FederateInternalError {
        if (fddDiscovered(theObject, theObjectClass, objectName)) {
            return;
        }
        RiftMomPortHla3 mom = infra;
        if (mom != null && mom.discovered(theObject, theObjectClass, objectName) && !mapOhdlIemId.containsKey(theObjectClass)) {
            return;                                   // a MOM object for the Infra view only
        }
        String prodFed = federateName(producingFederate);
        Integer iemClassId = mapOhdlIemId.get(theObjectClass);
        if (iemClassId == null) {
            return;   // a class this datasource does not list
        }

        Integer instanceDbId = db.discoverInstance(iemClassId,mapIemIdOname.get(iemClassId),objectName,prodFed);

        mapDbIdOinstHdl.put(instanceDbId,theObject);
        mapOinstHdlDbId.put(theObject,instanceDbId);
        mapOinstHdlKnownClass.put(theObject,theObjectClass);   // the discovered class is the known class


        try {
            ObjectClass objectClass = iem.getObjectClass(mapOhdlIemId.get(theObjectClass));
            AttributeHandleSet attributeHandles = rtIambassador.getAttributeHandleSetFactory().create();
            for (ClassAttribute attribute : objectClass.getAttributes()) {
                    attributeHandles.add(mapIemIdAhdl.get(attribute.getId()));
            }
            rtIambassador.requestAttributeValueUpdate(theObject,attributeHandles, new byte[0]);

        } catch (Exception e) {
            System.out.printf("Failed to request attributes for object class: %s\n", objectName);
        }



    }

    @Override
    public void removeObjectInstance(ObjectInstanceHandle theObject, byte[] userSuppliedTag, OrderType sentOrdering, SupplementalRemoveInfo removeInfo) throws FederateInternalError {
        if (fddReading && theObject.equals(fddInstance)) {
            fddInstance = null;
            return;
        }
        RiftMomPortHla3 mom = infra;
        if (mom != null && mom.removed(theObject) && !mapOinstHdlDbId.containsKey(theObject)) {
            return;
        }
        Integer dbInstanceId = mapOinstHdlDbId.remove(theObject);     // FIX: both maps (handles may be reused)
        mapOinstHdlKnownClass.remove(theObject);
        if (dbInstanceId != null) {
            db.removeInstance(dbInstanceId);
            mapDbIdOinstHdl.remove(dbInstanceId);
        }
    }

    @Override
    public void removeObjectInstance(ObjectInstanceHandle theObject, byte[] userSuppliedTag, OrderType sentOrdering, LogicalTime theTime, OrderType receivedOrdering, SupplementalRemoveInfo removeInfo) throws FederateInternalError {
        this.removeObjectInstance(theObject, userSuppliedTag, sentOrdering, removeInfo);
    }

    @Override
    public void removeObjectInstance(ObjectInstanceHandle theObject, byte[] userSuppliedTag, OrderType sentOrdering, LogicalTime theTime, OrderType receivedOrdering, MessageRetractionHandle retractionHandle, SupplementalRemoveInfo removeInfo) throws FederateInternalError {
        this.removeObjectInstance(theObject, userSuppliedTag, sentOrdering, theTime, receivedOrdering, removeInfo);
    }


    @Override
    public void reflectAttributeValues(ObjectInstanceHandle theObject, AttributeHandleValueMap theAttributes, byte[] userSuppliedTag, OrderType sentOrdering, TransportationTypeHandle theTransport, SupplementalReflectInfo reflectInfo) throws FederateInternalError {
        if (fddReflected(theObject, theAttributes)) {
            return;
        }
        RiftMomPortHla3 mom = infra;
        if (mom != null && mom.reflected(theObject, theAttributes) && !mapOinstHdlDbId.containsKey(theObject)) {
            return;
        }
        Integer dbInstanceId = mapOinstHdlDbId.get(theObject);
        if (dbInstanceId != null) {
            ObjectClassHandle knownClassHandle = knownClass(theObject);
            String producingFederateName = reflectInfo != null && reflectInfo.hasProducingFederate()
                    ? federateName(reflectInfo.getProducingFederate()) : "";
            RawValues raw = binding.capture(knownClassHandle,theAttributes, System.currentTimeMillis());

            // FIX: names and ids from the binding (known class + attribute handle), in capture order,
            // so each RiftAttribute matches the decoder's member exactly
            ArrayList<RiftAttribute> dbAttributes = new ArrayList<>(raw.size());
            SimwTimeStamp now = new SimwTimeStamp();
            for (int i = 0; i < raw.size(); i++) {
                int attrId = raw.getMemberId(i);
                ClassAttribute ca = attrId == IemElement.NO_ID ? null : iem.getClassAttribute(raw.getClassId(), attrId);
                String attributeName = ca != null ? ca.getName() : raw.getLabel(i);
                dbAttributes.add(new RiftAttribute(attrId == IemElement.NO_ID ? null : attrId, attributeName,
                        raw.getValue(i), producingFederateName, now, userSuppliedTag));
            }
            db.reflectObjectUpdate(dbInstanceId,dbAttributes,raw);
            if (mom != null) {
                mom.observed(producingFederateName, false, octets(theAttributes));
            }
        }
    }

    @Override
    public void reflectAttributeValues(ObjectInstanceHandle theObject, AttributeHandleValueMap theAttributes, byte[] userSuppliedTag, OrderType sentOrdering, TransportationTypeHandle theTransport, LogicalTime theTime, OrderType receivedOrdering, SupplementalReflectInfo reflectInfo) throws FederateInternalError {
        this.reflectAttributeValues(theObject, theAttributes, userSuppliedTag, sentOrdering, theTransport, reflectInfo);
    }

    @Override
    public void reflectAttributeValues(ObjectInstanceHandle theObject, AttributeHandleValueMap theAttributes, byte[] userSuppliedTag, OrderType sentOrdering, TransportationTypeHandle theTransport, LogicalTime theTime, OrderType receivedOrdering, MessageRetractionHandle retractionHandle, SupplementalReflectInfo reflectInfo) throws FederateInternalError {
        this.reflectAttributeValues(theObject, theAttributes, userSuppliedTag, sentOrdering, theTransport, theTime, receivedOrdering, reflectInfo);
    }


    @Override
    public void receiveInteraction(InteractionClassHandle interactionClass, ParameterHandleValueMap theParameters, byte[] userSuppliedTag, OrderType sentOrdering, TransportationTypeHandle theTransport, SupplementalReceiveInfo receiveInfo) throws FederateInternalError {
        String producingFederateName = receiveInfo != null && receiveInfo.hasProducingFederate()
                ? federateName(receiveInfo.getProducingFederate()) : "";

        RiftMomPortHla3 mom = infra;
        if (mom != null && mom.received(interactionClass, theParameters) && !mapIhdlName.containsKey(interactionClass)) {
            return;                                   // a MOM report for the Infra view only
        }
        if (mom != null) {
            mom.observed(producingFederateName, true, octets(theParameters));
        }
        String interactionClassName = mapIhdlName.get(interactionClass);
        Integer interactionId = mapIhdlIemId.get(interactionClass);
        ArrayList<RiftParameter> parameters = new ArrayList<>();
        for (ParameterHandle parameterHandle : theParameters.keySet()) {
            // FIX: one failing parameter no longer drops all the others silently
            try {
                String parameterName = rtIambassador.getParameterName(interactionClass, parameterHandle);
                ClassParameter classParameter = iem.getClassParameter(interactionClassName, parameterName);
                Integer parameterId = classParameter == null ? null : classParameter.getId();
                parameters.add(new RiftParameter(parameterId,parameterName,theParameters.get(parameterHandle)));
            } catch (Exception e) {
                parameters.add(new RiftParameter(null,"parameter " + parameterHandle,theParameters.get(parameterHandle)));
            }
        }
        db.reflectInteractionEvent(interactionId,parameters,producingFederateName,userSuppliedTag);
    }

    @Override
    public void receiveInteraction(InteractionClassHandle interactionClass, ParameterHandleValueMap theParameters, byte[] userSuppliedTag, OrderType sentOrdering, TransportationTypeHandle theTransport, LogicalTime theTime, OrderType receivedOrdering, SupplementalReceiveInfo receiveInfo) throws FederateInternalError {
        this.receiveInteraction(interactionClass, theParameters, userSuppliedTag, sentOrdering, theTransport, receiveInfo);
    }

    @Override
    public void receiveInteraction(InteractionClassHandle interactionClass, ParameterHandleValueMap theParameters, byte[] userSuppliedTag, OrderType sentOrdering, TransportationTypeHandle theTransport, LogicalTime theTime, OrderType receivedOrdering, MessageRetractionHandle retractionHandle, SupplementalReceiveInfo receiveInfo) throws FederateInternalError {
        this.receiveInteraction(interactionClass, theParameters, userSuppliedTag, sentOrdering, theTransport, theTime, receivedOrdering, receiveInfo);
    }

    // HELPERS

    /** Known class of an instance: cached at discovery, asked from the RTI only if missing. */
    private ObjectClassHandle knownClass(ObjectInstanceHandle theObject) {
        ObjectClassHandle c = mapOinstHdlKnownClass.get(theObject);
        if (c == null) {
            try {
                c = rtIambassador.getKnownObjectClassHandle(theObject);
                mapOinstHdlKnownClass.put(theObject, c);
            } catch (Exception e) {
                // null: the binding then reports the values as unknown members (raw octets)
            }
        }
        return c;
    }

    /** Federate name, cached per handle ("" when unknown). */
    private String federateName(FederateHandle federate) {
        if (federate == null) return "";
        String n = mapFedHdlName.get(federate);
        if (n == null) {
            try {
                n = rtIambassador.getFederateName(federate);
                mapFedHdlName.put(federate, n);
            } catch (Exception e) {
                return "";
            }
        }
        return n;
    }

    // ================================================================== Infra (0.9.20)

    /** Registers this datasource's federation with the Infra view (after the subscriptions). */
    private void openInfra(FederateHandle me) {
        com.simweaver.common.infra.InfraRegistry reg = com.simweaver.common.infra.InfraRegistry.global();
        if (!reg.isEnabled()) {
            return;
        }
        String own = "";
        try {
            own = me == null ? "" : rtIambassador.getFederateName(me);
        } catch (Exception ignored) {
            // shown without it
        }
        RiftMomPortHla3 p = new RiftMomPortHla3(rtIambassador, this::ownSubscription);
        p.setTap(reg.openHla(com.simweaver.common.infra.InfraSource.rift(config.getName() + " / " + effectiveFederation, name, id.toString()), false, effectiveFederation, own,
                lsd, p));
        infra = p;
    }

    private void closeInfra() {
        RiftMomPortHla3 p = infra;
        infra = null;
        if (p != null) {
            p.close();
        }
    }

    /** The attributes this datasource subscribes of a class (null when it does not subscribe the class). */
    private AttributeHandleSet ownSubscription(ObjectClassHandle c) {
        Integer iemId = mapOhdlIemId.get(c);
        if (iemId == null || iem == null) {
            return null;
        }
        try {
            AttributeHandleSet s = rtIambassador.getAttributeHandleSetFactory().create();
            for (ClassAttribute a : iem.getObjectClass(iemId).getAttributes()) {
                AttributeHandle h = mapIemIdAhdl.get(a.getId());
                if (h != null) {
                    s.add(h);
                }
            }
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    private static int octets(java.util.Map<?, byte[]> values) {
        int n = 0;
        for (byte[] v : values.values()) {
            n += v == null ? 0 : v.length;
        }
        return n;
    }
}
