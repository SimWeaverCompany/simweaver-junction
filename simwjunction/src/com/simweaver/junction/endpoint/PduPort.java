package com.simweaver.junction.endpoint;

/**
 * An endpoint that passes DIS PDUs as they are, for the PDU relay router
 * ({@code router.kind=pdu}): DIS 6, DIS 7 and C-DIS endpoints. A C-DIS endpoint decodes what it
 * hears to DIS 7 and encodes what it sends.
 */
public interface PduPort {

    /** Takes the PDUs a port hears. Called on the port's receiver thread. */
    interface PduSink {
        /**
         * @param from the port that heard it
         * @param pdu  one DIS PDU (version 6 or 7), the receiver's copy
         */
        void pdu(PduPort from, byte[] pdu);
    }

    /**
     * Hands every PDU heard (of the endpoint's exercise, in versions it takes) to the sink instead
     * of the RPR model. Call after {@link Endpoint#open} and before PDUs are expected; the
     * endpoint then declares nothing to a router.
     */
    void relayTo(PduSink sink);

    /**
     * Sends a PDU as it is, but with this endpoint's protocol version and exercise; a C-DIS
     * endpoint encodes it (types C-DIS does not have are not sent).
     *
     * @param pdu a DIS PDU (version 6 or 7); not changed
     */
    void sendPdu(byte[] pdu);

    /** @return the endpoint id */
    String id();
}
