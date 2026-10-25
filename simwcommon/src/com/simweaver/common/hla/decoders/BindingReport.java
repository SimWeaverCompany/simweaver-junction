package com.simweaver.common.hla.decoders;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result of binding an {@code InformationExchangeModel} to the handles of a joined federation:
 * what was bound and which IEM names the federation's FDD does not know (usually a sign that
 * the IEM was built from different FOM modules than the federation, e.g. RPR v2 against an
 * RPR v3 federation). Immutable.
 */
public final class BindingReport {

    private final int objectClasses;
    private final int attributes;
    private final int interactionClasses;
    private final int parameters;
    private final List<String> missing;
    private final long elapsedMillis;

    /**
     * @param objectClasses      object classes bound
     * @param attributes         class attributes bound (counted per class, inherited included)
     * @param interactionClasses interaction classes bound
     * @param parameters         class parameters bound (per class)
     * @param missing            qualified names the RTI did not know, with the reason
     * @param elapsedMillis      time taken
     */
    public BindingReport(int objectClasses, int attributes, int interactionClasses, int parameters,
                         List<String> missing, long elapsedMillis) {
        this.objectClasses = objectClasses;
        this.attributes = attributes;
        this.interactionClasses = interactionClasses;
        this.parameters = parameters;
        this.missing = Collections.unmodifiableList(new ArrayList<String>(missing));
        this.elapsedMillis = elapsedMillis;
    }

    /** @return object classes bound */
    public int getObjectClassCount() {
        return objectClasses;
    }

    /** @return class attributes bound */
    public int getAttributeCount() {
        return attributes;
    }

    /** @return interaction classes bound */
    public int getInteractionClassCount() {
        return interactionClasses;
    }

    /** @return class parameters bound */
    public int getParameterCount() {
        return parameters;
    }

    /** @return IEM names not known to the RTI, e.g. {@code "HLAobjectRoot.Foo: NameNotFound"} */
    public List<String> getMissing() {
        return missing;
    }

    /** @return true when every IEM class and member was found in the federation */
    public boolean isComplete() {
        return missing.isEmpty();
    }

    /** @return time taken to bind */
    public long getElapsedMillis() {
        return elapsedMillis;
    }

    @Override
    public String toString() {
        return "bound " + objectClasses + " object classes / " + attributes + " attributes, "
            + interactionClasses + " interaction classes / " + parameters + " parameters in "
            + elapsedMillis + " ms" + (missing.isEmpty() ? "" : ", " + missing.size() + " missing");
    }
}
