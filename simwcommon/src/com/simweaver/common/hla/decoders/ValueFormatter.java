package com.simweaver.common.hla.decoders;

import com.simweaver.common.iem.DataType;

import java.util.Collection;

/**
 * Turns a structurally decoded value into a more useful one: a display text such as
 * {@code 205-3-42}, derived children such as latitude/longitude, a {@link Reference} tag, or
 * an attachment. The RPR-FOM formatters live in {@code com.simweaver.common.hla.decoders.rpr}.
 * <p>
 * Formatters are bound per datatype when the decoder is built: for each datatype whose name is
 * in {@link #getDataTypeNames()}, {@link #accepts(DataType)} is asked once. A formatter
 * therefore applies wherever the datatype occurs (any attribute, parameter, record field or
 * array element), including in classes that inherit from or extend the FOM that defined it.
 * <p>
 * Formatters run bottom-up (children before parents) and in the order they were added. A
 * formatter that throws is ignored for that value (a warning is added). Implementations must
 * be stateless and thread safe.
 */
public interface ValueFormatter {

    /** @return datatype names this formatter may handle */
    Collection<String> getDataTypeNames();

    /**
     * Shape check, called once per matching datatype when the decoder is built; return false
     * when a FOM defines a datatype with this name but a different structure.
     *
     * @param type the datatype
     * @return true to handle it
     */
    boolean accepts(DataType type);

    /**
     * @param value structurally decoded value (possibly already changed by earlier formatters)
     * @param ctx   context
     * @return the value to use; may be {@code value} itself
     */
    DecodedValue format(DecodedValue value, FormatContext ctx);
}
