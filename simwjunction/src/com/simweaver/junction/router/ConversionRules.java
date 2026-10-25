package com.simweaver.junction.router;

import com.simweaver.common.hla.codec.TranscodeRules;
import com.simweaver.common.iem.InformationExchangeModel;

/**
 * Renames between two information models (one direction), e.g. RPR FOM v2.0 to v3.0. Without
 * a rule, classes and members keep their names. Implementations are immutable.
 */
public interface ConversionRules {

    /** No renames. */
    ConversionRules NONE = new ConversionRules() {
    };

    /** @return the destination object class for a source class, or null for the same name */
    default String objectClass(String sourceQualifiedName) {
        return null;
    }

    /** @return the destination interaction class for a source class, or null for the same name */
    default String interactionClass(String sourceQualifiedName) {
        return null;
    }

    /**
     * @param sourceClass qualified name of the class the attribute is looked up on (the compiler
     *                    asks for the class and then each superclass)
     * @return the destination attribute name, or null when there is no rule for this class
     */
    default String attribute(String sourceClass, String attribute) {
        return null;
    }

    /** @return the destination parameter name, or null (see {@link #attribute}) */
    default String parameter(String sourceClass, String parameter) {
        return null;
    }

    /** @return field renames and value maps for transcoded values */
    default TranscodeRules transcodeRules() {
        return TranscodeRules.NONE;
    }

    /** @return a short description, for the compatibility report */
    default String describe() {
        return "same names";
    }

    /** Chooses the rules for a pair of models. */
    interface Provider {
        /** No rules for any pair. */
        Provider NONE = (s, d) -> ConversionRules.NONE;

        /**
         * @param source      the source endpoint's model
         * @param destination the destination endpoint's model
         * @return the rules (never null)
         */
        ConversionRules rulesFor(InformationExchangeModel source, InformationExchangeModel destination);
    }
}
