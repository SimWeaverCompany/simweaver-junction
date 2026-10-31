package com.simweaver.junction.endpoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The instance names to try, in order, when an endpoint creates an object for the router.
 * Finite: when all are taken the registration fails. Empty means "let the RTI choose".
 * Immutable.
 */
public final class NameCandidates {

    /** Let the RTI (or the endpoint) choose. */
    public static final NameCandidates ANY = new NameCandidates(Collections.<String>emptyList());

    private final List<String> names;

    /** @param names the names, in order */
    public NameCandidates(List<String> names) {
        this.names = Collections.unmodifiableList(new ArrayList<String>(names));
    }

    /** @return the names (empty = any) */
    public List<String> getNames() {
        return names;
    }

    /** @return true when the endpoint may choose */
    public boolean isAny() {
        return names.isEmpty();
    }

    @Override
    public String toString() {
        return names.isEmpty() ? "(any)" : names.toString();
    }
}
