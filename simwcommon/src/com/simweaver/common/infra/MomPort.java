package com.simweaver.common.infra;

/**
 * The few RTI services the Infra view uses on a federate that is already joined (a Rift
 * datasource or a Junction endpoint), with handles as ints the owner maps. {@link HlaTap} calls
 * them only from the Infra thread, never from an RTI callback; the implementation does any
 * locking its owner needs.
 *
 * <p>Methods throw when the RTI refuses or does not know a name; the tap then leaves that part
 * out (an RTI's MOM may lack an attribute of the standard).
 */
public interface MomPort {

    int objectClass(String qualifiedName) throws Exception;

    int attribute(int objectClass, String name) throws Exception;

    int interactionClass(String qualifiedName) throws Exception;

    int parameter(int interactionClass, String name) throws Exception;

    /**
     * Subscribes these attributes <em>in addition to</em> what the owner subscribes of the class
     * (an implementation passes the union to the RTI when the owner subscribes the class too).
     */
    void subscribeObjectClass(int objectClass, int[] attributes) throws Exception;

    void subscribeInteractionClass(int interactionClass) throws Exception;

    void publishInteractionClass(int interactionClass) throws Exception;

    void requestClassUpdate(int objectClass, int[] attributes) throws Exception;

    void requestUpdate(long instance, int[] attributes) throws Exception;

    void sendInteraction(int interactionClass, int[] parameters, byte[][] values) throws Exception;

    /** @return the name of an object class from its encoded handle (as in MOM reports), or null */
    String objectClassName(byte[] encodedHandle);

    /** @return the name of an interaction class from its encoded handle, or null */
    String interactionClassName(byte[] encodedHandle);

    /** @return the name of an attribute from the encoded class and attribute handles, or null */
    String attributeName(byte[] encodedClass, byte[] encodedAttribute);
}
