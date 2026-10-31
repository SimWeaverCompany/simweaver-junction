package com.simweaver.common.hla.decoders;

import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.common.iem.MemberOnClass;

/**
 * What a {@link ValueFormatter} may know about the value it formats.
 */
public interface FormatContext {

    /** @return the model */
    InformationExchangeModel getModel();

    /** @return decoder options */
    DecoderOptions getOptions();

    /**
     * @return the attribute or parameter being decoded (as seen on its class), or null when a
     *         datatype is decoded on its own
     */
    MemberOnClass<?, ?> getMember();

    /** @return 0 for the member value itself, 1 for its fields/elements, and so on */
    int getDepth();

    /** @return time the data was received (epoch ms UTC), or {@link Long#MIN_VALUE} when unknown */
    long getReceivedMillis();

    /** @return current time from the options' clock */
    long getNowMillis();
}
