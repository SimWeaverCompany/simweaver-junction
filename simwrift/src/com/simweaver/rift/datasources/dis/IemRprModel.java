package com.simweaver.rift.datasources.dis;

import com.simweaver.common.iem.ClassAttribute;
import com.simweaver.common.iem.ClassParameter;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.common.iem.ObjectClass;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link RprModel} on an {@link InformationExchangeModel}. Lookups are cached; a name the model
 * does not have (null, or an exception from the model) is cached as {@link #NO_ID}.
 */
public final class IemRprModel implements RprModel {

    private final InformationExchangeModel iem;
    private final ConcurrentHashMap<String, Integer> cache = new ConcurrentHashMap<>();

    public IemRprModel(InformationExchangeModel iem) {
        if (iem == null) throw new IllegalArgumentException("iem must not be null");
        this.iem = iem;
    }

    @Override
    public int objectClassId(String qualifiedName) {
        return cached("o " + qualifiedName, () -> {
            ObjectClass c = iem.getObjectClass(qualifiedName);
            return c == null ? NO_ID : c.getId();
        });
    }

    @Override
    public int attributeId(String objectClassQualifiedName, String attributeName) {
        return cached("a " + objectClassQualifiedName + " " + attributeName, () -> {
            ObjectClass c = iem.getObjectClass(objectClassQualifiedName);
            ClassAttribute a = c == null ? null : c.getAttribute(attributeName);
            return a == null ? NO_ID : a.getId();
        });
    }

    @Override
    public int interactionClassId(String qualifiedName) {
        return cached("i " + qualifiedName, () -> {
            Object c = iem.getInteractionClass(qualifiedName);
            return c == null ? NO_ID : iem.getInteractionClass(qualifiedName).getId();
        });
    }

    @Override
    public String interactionClassNamed(String simpleName) {
        for (com.simweaver.common.iem.InteractionClass c : iem.getInteractionClasses()) {
            String q = c.getQualifiedName();
            if (q.substring(q.lastIndexOf('.') + 1).equalsIgnoreCase(simpleName)) return q;
        }
        return null;
    }

    @Override
    public int parameterId(String interactionClassQualifiedName, String parameterName) {
        return cached("p " + interactionClassQualifiedName + " " + parameterName, () -> {
            ClassParameter p = iem.getClassParameter(interactionClassQualifiedName, parameterName);
            return p == null ? NO_ID : p.getId();
        });
    }

    private int cached(String key, Callable<Integer> lookup) {
        Integer v = cache.get(key);
        if (v != null) return v;
        int id;
        try {
            Integer r = lookup.call();
            id = r == null ? NO_ID : r;
        } catch (Exception e) {                 // unknown name, or a model that throws for it
            id = NO_ID;
        }
        cache.put(key, id);
        return id;
    }
}
