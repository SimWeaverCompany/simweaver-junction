package com.simweaver.junction.endpoint;

/**
 * What an {@link Endpoint} hands to the router. Called on the endpoint's own threads (RTI
 * callback threads, the DIS receiver); implementations queue and return quickly. Ids are of
 * the endpoint's model; values in its encoding; arrays are handed over.
 */
public interface EndpointListener {

    /** An object appeared on this side. */
    void discovered(Endpoint from, long key, int objectClass, String name, String producer);

    /** Attribute values of a discovered object. */
    void reflected(Endpoint from, long key, int[] attributes, byte[][] values, byte[] tag, String producer);

    /** A discovered object disappeared. */
    void removed(Endpoint from, long key, byte[] tag);

    /** An interaction arrived. */
    void received(Endpoint from, int interactionClass, int[] parameters, byte[][] values, byte[] tag, String producer);

    /** Someone on this side asks for attribute values of an object the router created here. */
    void provideRequested(Endpoint from, long key, int[] attributes);

    /** An object asked for with {@link Endpoint#createObject} exists now, with this name. */
    void registered(Endpoint from, long gwId, long key, String name);

    /**
     * An object created with {@link Endpoint#createObject} is known by another name from now on
     * (DIS: its entity id became known only with its first attribute values). References to it
     * that the router hands to or receives from this endpoint use the new name.
     *
     * @param key     the endpoint's key of the object
     * @param newName the name from now on
     */
    default void renamed(Endpoint from, long key, String newName) {
    }

    /** An object asked for with {@link Endpoint#createObject} could not be created. */
    void registrationFailed(Endpoint from, long gwId, String reason);

    /** The endpoint's state changed (connection lost, reconnected ...). */
    void stateChanged(Endpoint from, EndpointState state, String message);
}
