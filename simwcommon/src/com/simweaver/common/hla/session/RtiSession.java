package com.simweaver.common.hla.session;

import java.util.List;

/**
 * One federate's connection to an HLA RTI, without any type of the HLA Java API in its
 * signatures: what Junktion's HLA endpoints (and any other SimWeaver code) build on, for both
 * IEEE 1516-2010 and IEEE 1516-2025, with the RTI library on the class path or loaded in
 * isolation ({@link RtiLibrary}).
 *
 * <p>Handles are small session-local numbers: object classes, attributes, interaction
 * classes and parameters are {@code int}s (never 0), object instances {@code long}s
 * (never 0). They mean nothing outside this session.
 *
 * <p>Services may be called from any thread except an RTI callback thread (the session uses
 * the HLA_IMMEDIATE callback model). Callbacks arrive on RTI threads through the
 * {@link RtiSessionListener}; the session never lets a listener's exception reach the RTI.
 *
 * <p>Not supported, by design: time management, ownership management, DDM, save/restore,
 * synchronization points.
 */
public interface RtiSession extends AutoCloseable {

    /** No handle. */
    int NO_HANDLE = 0;

    /** The HLA version of an RTI API. */
    enum Version {
        /** IEEE 1516-2010 (HLA Evolved), {@code hla.rti1516e}. */
        HLA_1516E("IEEE 1516-2010", "hla.rti1516e"),
        /** IEEE 1516-2025 (HLA 4), {@code hla.rti1516_2025}. */
        HLA_1516_2025("IEEE 1516-2025", "hla.rti1516_2025");

        private final String label;
        private final String apiPackage;

        Version(String label, String apiPackage) {
            this.label = label;
            this.apiPackage = apiPackage;
        }

        /** @return e.g. "IEEE 1516-2010" */
        public String label() {
            return label;
        }

        /** @return the Java package of the API, e.g. "hla.rti1516e" */
        public String apiPackage() {
            return apiPackage;
        }
    }

    /** @return the HLA version of this session */
    Version version();

    /** @return what the RTI library is: "class path" or the jar list of an isolated library */
    String libraryDescription();

    // ------------------------------------------------------------------ lifecycle

    /**
     * Connects to the RTI. Callbacks go to {@code listener} from now on.
     *
     * @param settings where and how to connect
     * @param listener receives the callbacks
     * @throws RtiSessionException when the RTI refuses or cannot be reached
     */
    void connect(ConnectSettings settings, RtiSessionListener listener) throws RtiSessionException;

    /**
     * Asks the RTI for the federation executions it knows (connected, not joined).
     *
     * @param timeoutMillis how long to wait for the RTI's report
     * @return the federation names (empty when the RTI did not answer in time)
     * @throws RtiSessionException when the request fails
     */
    List<String> listFederations(long timeoutMillis) throws RtiSessionException;

    /**
     * Joins a federation execution, creating it first when asked to.
     *
     * @param federation      federation execution name
     * @param federateType    federate type
     * @param federateName    federate name, or null to let the RTI choose
     * @param fomModules      FOM module files (paths); also given at create
     * @param createIfMissing create the federation execution when it does not exist
     * @return the federate name the RTI knows this federate by
     * @throws RtiSessionException when creating or joining fails
     */
    String join(String federation, String federateType, String federateName, List<String> fomModules,
                boolean createIfMissing) throws RtiSessionException;

    /**
     * Resigns (deleting this federate's objects) and, when asked to, tries to destroy the
     * federation execution (ignored when other federates are still joined).
     *
     * @param destroyFederation try to destroy the federation execution
     */
    void resign(boolean destroyFederation);

    /** Disconnects from the RTI. Safe to call more than once. */
    void disconnect();

    /** Resigns (without destroying) and disconnects, ignoring errors. */
    @Override
    void close();

    /** @return true between a successful connect and disconnect / connection lost */
    boolean isConnected();

    /** @return true between a successful join and resign / connection lost */
    boolean isJoined();

    // ------------------------------------------------------------------ handles and declarations

    /** @return the object class handle; throws when the FOM has no such class */
    int objectClass(String qualifiedName) throws RtiSessionException;

    /** @return the attribute handle of an attribute available on the class */
    int attribute(int objectClass, String attributeName) throws RtiSessionException;

    /** @return the interaction class handle */
    int interactionClass(String qualifiedName) throws RtiSessionException;

    /** @return the parameter handle of a parameter available on the interaction class */
    int parameter(int interactionClass, String parameterName) throws RtiSessionException;

    /** Publishes the attributes of an object class. */
    void publishObjectClass(int objectClass, int[] attributes) throws RtiSessionException;

    /** Subscribes to the attributes of an object class. */
    void subscribeObjectClass(int objectClass, int[] attributes) throws RtiSessionException;

    /** Publishes an interaction class. */
    void publishInteractionClass(int interactionClass) throws RtiSessionException;

    /** Subscribes to an interaction class. */
    void subscribeInteractionClass(int interactionClass) throws RtiSessionException;

    // ------------------------------------------------------------------ objects and interactions

    /** Asks to reserve an object instance name; the answer comes as {@link RtiSessionListener#nameReserved}. */
    void reserveName(String name) throws RtiSessionException;

    /** Releases a reserved name that will not be used. */
    void releaseName(String name);

    /**
     * Registers an object instance.
     *
     * @param objectClass the class (published)
     * @param name        a reserved name, or null to let the RTI choose
     * @return the instance handle
     */
    long registerObject(int objectClass, String name) throws RtiSessionException;

    /** @return the instance name of an instance of this session (cached), or null */
    String objectName(long instance);

    /** Updates attribute values of a registered instance. */
    void updateAttributes(long instance, int[] attributes, byte[][] values, byte[] tag) throws RtiSessionException;

    /** Sends an interaction. */
    void sendInteraction(int interactionClass, int[] parameters, byte[][] values, byte[] tag) throws RtiSessionException;

    /** Deletes a registered instance. */
    void deleteObject(long instance, byte[] tag) throws RtiSessionException;

    /** Asks the owners of a discovered instance to update the given attributes. */
    void requestAttributeUpdate(long instance, int[] attributes, byte[] tag) throws RtiSessionException;

    /** Asks the owners of all instances of a class to update the given attributes. */
    void requestClassAttributeUpdate(int objectClass, int[] attributes, byte[] tag) throws RtiSessionException;

    // ------------------------------------------------------------------ names from encoded handles (0.9.20, Infra)

    /**
     * @param encodedHandle an object class handle as the RTI encodes it (e.g. in a MOM report)
     * @return the class's qualified name, or null when the RTI cannot decode it
     */
    default String objectClassName(byte[] encodedHandle) {
        return null;
    }

    /** @return an interaction class's qualified name from its encoded handle, or null */
    default String interactionClassName(byte[] encodedHandle) {
        return null;
    }

    /** @return an attribute's name from the encoded class and attribute handles, or null */
    default String attributeName(byte[] encodedClass, byte[] encodedAttribute) {
        return null;
    }
}
