package com.simweaver.junction.endpoint;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Object classes with attributes, and interaction classes, as ids of one model: what an
 * endpoint publishes or subscribes. Built by the router, then read-only.
 */
public final class Interest {

    private final Map<Integer, Set<Integer>> objectClasses = new LinkedHashMap<Integer, Set<Integer>>();
    private final Set<Integer> interactionClasses = new LinkedHashSet<Integer>();

    /** Adds attributes of an object class. */
    public Interest addAttributes(int objectClass, Iterable<Integer> attributes) {
        Set<Integer> s = objectClasses.get(objectClass);
        if (s == null) {
            s = new TreeSet<Integer>();
            objectClasses.put(objectClass, s);
        }
        for (Integer a : attributes) {
            s.add(a);
        }
        return this;
    }

    /** Adds an interaction class. */
    public Interest addInteraction(int interactionClass) {
        interactionClasses.add(interactionClass);
        return this;
    }

    /** @return object class to attribute ids */
    public Map<Integer, Set<Integer>> getObjectClasses() {
        return Collections.unmodifiableMap(objectClasses);
    }

    /** @return interaction class ids */
    public Set<Integer> getInteractionClasses() {
        return Collections.unmodifiableSet(interactionClasses);
    }

    /** @return true when nothing is in it */
    public boolean isEmpty() {
        return objectClasses.isEmpty() && interactionClasses.isEmpty();
    }

    /** @return attribute ids of a class (empty when not in it) */
    public Set<Integer> attributesOf(int objectClass) {
        Set<Integer> s = objectClasses.get(objectClass);
        return s == null ? Collections.<Integer>emptySet() : Collections.unmodifiableSet(s);
    }
}
