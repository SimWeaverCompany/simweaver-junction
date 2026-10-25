package com.simweaver.junction.endpoint;

import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.junction.config.EndpointConfig;
import com.simweaver.junction.config.EndpointType;

/**
 * One side of a gateway. What is native to the endpoint (RTI handles, PDUs, sockets) stays
 * behind this interface; the router sees classes and members as ids of the endpoint's
 * {@link #model()} and values in that model's encoding.
 *
 * <p>Objects are identified by a {@code long} key chosen by the endpoint: for objects it
 * discovered (and reports with {@link EndpointListener#discovered}) and for objects the router
 * asked it to create ({@link #createObject}, reported with {@link EndpointListener#registered}).
 *
 * <p>The router calls the sending methods from its dispatcher thread. They never throw:
 * failures are counted in {@link #stats()} and logged once per kind.
 */
public interface Endpoint {

    /** @return the endpoint id within its gateway, e.g. "blue" */
    String id();

    /** @return the configuration */
    EndpointConfig config();

    /** @return the kind */
    EndpointType type();

    /** @return the information model; valid after {@link #open} */
    InformationExchangeModel model();

    /** @return the target: federation name, "exercise 3", ... */
    String target();

    /** @return the current state */
    EndpointState state();

    /** @return what goes with the state (an error message), or null */
    String stateMessage();

    /**
     * Loads the model and connects (HLA: connect + join; DIS: opens the socket). Blocks until
     * done or failed.
     *
     * @param listener receives everything the endpoint sees
     * @throws EndpointException when it cannot be opened
     */
    void open(EndpointListener listener) throws EndpointException;

    /**
     * Declares what will be sent (publish) and what the router wants (subscribe). Called once
     * after {@link #open}; the endpoint repeats it itself after a reconnect.
     *
     * @throws EndpointException when the declarations fail
     */
    void declare(Interest publish, Interest subscribe) throws EndpointException;

    /**
     * Creates an object for the router. The result comes later as
     * {@link EndpointListener#registered} or {@link EndpointListener#registrationFailed}.
     *
     * @param gwId        the router's id of the object (handed back in the callbacks)
     * @param objectClass class id of this endpoint's model
     * @param names       the names to try, in order (empty: let the endpoint / RTI choose)
     */
    void createObject(long gwId, int objectClass, NameCandidates names);

    /** Updates attributes of an object created with {@link #createObject}. */
    void updateObject(long key, int[] attributes, byte[][] values, byte[] tag);

    /** Deletes an object created with {@link #createObject}. */
    void deleteObject(long key, byte[] tag);

    /** Sends an interaction. */
    void sendInteraction(int interactionClass, int[] parameters, byte[][] values, byte[] tag);

    /** Asks the owner of a discovered object to update attributes (HLA); ignored where meaningless. */
    void requestUpdate(long key, int[] attributes);

    /** Closes: deletes created objects (when asked to), resigns, disconnects. Safe to call twice. */
    void close(boolean deleteObjects);

    /** @return traffic counters */
    EndpointStats stats();
}
