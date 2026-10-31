package com.simweaver.common.iem;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A merged HLA interaction class.
 * <p>
 * {@link #getDeclaredParameters()} are the parameters this class declares itself (possibly
 * contributed by several FOM modules). {@link #getParameters()} is the full set available on the
 * class - inherited ones first, from {@code HLAinteractionRoot} down, then the declared ones - each
 * wrapped in a {@link ClassParameter} that knows whether it is inherited.
 */
public final class InteractionClass extends HlaClass<InteractionClass> {

    List<Parameter> declaredParameters = Collections.emptyList();
    List<ClassParameter> parameters = Collections.emptyList();
    Map<String, ClassParameter> parametersByName = Collections.emptyMap();
    Map<Integer, ClassParameter> parametersById = Collections.emptyMap();
    String transportationName;
    Transportation transportation;
    OrderType order;
    String orderText;

    InteractionClass(String name) {
        super(name);
    }

    @Override
    public ElementKind getKind() {
        return ElementKind.INTERACTION_CLASS;
    }

    /** Parameters declared by this class (not inherited), in merge order. */
    public List<Parameter> getDeclaredParameters() {
        return declaredParameters;
    }

    /** All parameters available on this class: inherited first (root down), then declared. */
    public List<ClassParameter> getParameters() {
        return parameters;
    }

    /** Number of parameters available on this class, inherited included. */
    public int getParameterCount() {
        return parameters.size();
    }

    /** Available parameter by simple name (declared or inherited), or null. */
    public ClassParameter getParameter(String parameterName) {
        return parameterName == null ? null : parametersByName.get(parameterName.trim());
    }

    /** Available parameter by parameter id (declared or inherited), or null. */
    public ClassParameter getParameter(int parameterId) {
        return parametersById.get(parameterId);
    }

    /** True if the parameter (declared or inherited) is available on this class. */
    public boolean hasParameter(int parameterId) {
        return parametersById.containsKey(parameterId);
    }

    /** True if a parameter with this name (declared or inherited) is available on this class. */
    public boolean hasParameter(String parameterName) {
        return getParameter(parameterName) != null;
    }

    /** Transportation type declared on this class, or null when not declared. */
    public Transportation getTransportation() {
        return transportation;
    }

    /** Transportation name as written in the FOM, or null. */
    public String getTransportationName() {
        return transportationName;
    }

    /**
     * Transportation declared on this class or, when absent (scaffolding), on the nearest superclass
     * that declares one; null if none in the chain does.
     */
    public Transportation getEffectiveTransportation() {
        InteractionClass c = this;
        while (c != null) {
            if (c.transportation != null) {
                return c.transportation;
            }
            c = c.getParentClass();
        }
        return null;
    }

    /** Order type declared on this class, or null when not declared. */
    public OrderType getOrder() {
        return order;
    }

    /** Order as written in the FOM, or null. */
    public String getOrderText() {
        return orderText;
    }

    /**
     * Order declared on this class or the nearest superclass declaring one; {@link OrderType#RECEIVE}
     * if none in the chain does.
     */
    public OrderType getEffectiveOrder() {
        InteractionClass c = this;
        while (c != null) {
            if (c.order != null) {
                return c.order;
            }
            c = c.getParentClass();
        }
        return OrderType.RECEIVE;
    }
}
