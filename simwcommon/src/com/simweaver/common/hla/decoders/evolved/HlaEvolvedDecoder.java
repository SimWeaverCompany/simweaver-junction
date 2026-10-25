package com.simweaver.common.hla.decoders.evolved;

import com.simweaver.common.hla.decoders.DecodedMembers;
import com.simweaver.common.hla.decoders.HlaBindingException;
import com.simweaver.common.hla.decoders.HlaDataDecoder;
import com.simweaver.common.iem.InformationExchangeModel;

import hla.rti1516e.AttributeHandleValueMap;
import hla.rti1516e.InteractionClassHandle;
import hla.rti1516e.ObjectClassHandle;
import hla.rti1516e.ParameterHandleValueMap;
import hla.rti1516e.RTIambassador;

/**
 * Convenience for IEEE 1516-2010 (HLA Evolved): an {@link HlaDataDecoder} plus the
 * {@link HlaEvolvedBinding} of the current join. Thread safe.
 * <p>
 * For high rates, prefer {@link HlaEvolvedBinding#capture} on the callback thread and
 * {@link HlaDataDecoder#decode(com.simweaver.common.hla.decoders.RawValues)} on a worker;
 * the methods here do both in the calling thread.
 */
public final class HlaEvolvedDecoder {

    private final HlaDataDecoder decoder;
    private final HlaEvolvedBinding binding;

    /**
     * @param decoder the version independent decoder (shared across joins)
     * @param binding the binding of the current join
     */
    public HlaEvolvedDecoder(HlaDataDecoder decoder, HlaEvolvedBinding binding) {
        if (decoder.getModel() != binding.getModel()) {
            throw new IllegalArgumentException("decoder and binding use different models");
        }
        this.decoder = decoder;
        this.binding = binding;
    }

    /**
     * Binds and creates a decoder with {@link HlaDataDecoder#create} defaults.
     *
     * @param model the model
     * @param rti   a joined RTI ambassador
     * @return the decoder
     * @throws HlaBindingException when binding fails
     */
    public static HlaEvolvedDecoder create(InformationExchangeModel model, RTIambassador rti) throws HlaBindingException {
        return new HlaEvolvedDecoder(HlaDataDecoder.create(model), HlaEvolvedBinding.bind(model, rti));
    }

    /** @return the version independent decoder */
    public HlaDataDecoder getDecoder() {
        return decoder;
    }

    /** @return the handle binding */
    public HlaEvolvedBinding getBinding() {
        return binding;
    }

    /**
     * @param knownClass     the object's known class handle
     * @param values         reflected values
     * @param receivedMillis receive time, epoch ms UTC
     * @return the decoded attributes
     */
    public DecodedMembers decode(ObjectClassHandle knownClass, AttributeHandleValueMap values, long receivedMillis) {
        return decoder.decode(binding.capture(knownClass, values, receivedMillis));
    }

    /**
     * @param interactionClass interaction class handle
     * @param values           parameter values
     * @param receivedMillis   receive time, epoch ms UTC
     * @return the decoded parameters
     */
    public DecodedMembers decode(InteractionClassHandle interactionClass, ParameterHandleValueMap values, long receivedMillis) {
        return decoder.decode(binding.capture(interactionClass, values, receivedMillis));
    }
}
