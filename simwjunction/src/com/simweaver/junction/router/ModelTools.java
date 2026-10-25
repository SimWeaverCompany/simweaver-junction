package com.simweaver.junction.router;

import com.simweaver.common.hla.codec.HlaCodec;
import com.simweaver.common.hla.codec.TypeAnalysis;
import com.simweaver.common.hla.decoders.HlaDataDecoder;
import com.simweaver.common.iem.InformationExchangeModel;

import java.util.Collection;

/** Codec and type analysis of one endpoint's model, built once per gateway start. Thread safe. */
public final class ModelTools {

    private final InformationExchangeModel model;
    private final HlaCodec codec;
    private final TypeAnalysis analysis;

    /**
     * @param model          the model
     * @param referenceTypes datatype names holding object instance names
     */
    public ModelTools(InformationExchangeModel model, Collection<String> referenceTypes) {
        this.model = model;
        this.codec = new HlaCodec(new HlaDataDecoder(model, HlaDataDecoder.defaultOptionsFor(model).clearFormatters().build()));
        this.analysis = new TypeAnalysis(codec, referenceTypes);
    }

    public InformationExchangeModel model() {
        return model;
    }

    public HlaCodec codec() {
        return codec;
    }

    public TypeAnalysis analysis() {
        return analysis;
    }
}
