package com.simweaver.common.hla.session;

/**
 * Callbacks of an {@link RtiSession}, on RTI callback threads. Implementations should hand
 * the data over quickly (queue it) and must not call the session's services from here.
 * Arrays are owned by the listener once delivered.
 *
 * <p>{@code producer} is the producing federate's name when the RTI tells it, else "".
 */
public interface RtiSessionListener {

    /** An object instance was discovered, as {@code objectClass} (the known class). */
    void discovered(long instance, int objectClass, String name, String producer);

    /** Attribute values of a discovered instance. */
    void reflected(long instance, int[] attributes, byte[][] values, byte[] tag, String producer);

    /** A discovered instance was removed. */
    void removed(long instance, byte[] tag, String producer);

    /** An interaction was received. */
    void received(int interactionClass, int[] parameters, byte[][] values, byte[] tag, String producer);

    /** The RTI asks this federate to provide attribute values of one of its instances. */
    void provideRequested(long instance, int[] attributes, byte[] tag);

    /** Answer to {@link RtiSession#reserveName}. */
    void nameReserved(String name, boolean success);

    /** The connection to the RTI was lost; the session is unusable afterwards. */
    void connectionLost(String reason);
}
