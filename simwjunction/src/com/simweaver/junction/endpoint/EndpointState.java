package com.simweaver.junction.endpoint;

/** Life of an endpoint. */
public enum EndpointState {
    /** Created, not opened. */
    NEW,
    /** Connecting and joining (HLA) / opening the socket (DIS). */
    CONNECTING,
    /** Working. */
    JOINED,
    /** The connection was lost. */
    LOST,
    /** Trying to connect again after a loss. */
    RECONNECTING,
    /** Opening failed. */
    FAILED,
    /** Closed. */
    CLOSED;

    /** @return true when traffic can flow */
    public boolean isUp() {
        return this == JOINED;
    }
}
