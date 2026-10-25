package com.simweaver.common.hla.decoders;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Marks a decoded value as a reference to other objects. The decoder only <em>tags</em>
 * references; resolving them to object instances is the job of whoever keeps the object store.
 * <p>
 * Kinds used by the RPR formatters (see the Rift datasource page contract, 4.1):
 * <ul>
 *   <li>{@link #OBJECT_NAME}: an HLA object instance name ({@code RTIobjectId});</li>
 *   <li>{@link #ENTITY_ID}: an entity identifier, key {@code site-application-entity}.</li>
 * </ul>
 * Other formatters may define their own kinds.
 */
public final class Reference {

    /** HLA object instance name. */
    public static final String OBJECT_NAME = "objectname";
    /** Entity identifier; keys are {@code site-application-entity} with dashes. */
    public static final String ENTITY_ID = "entityid";

    private final String kind;
    private final List<String> keys;

    /**
     * @param kind reference kind (not null)
     * @param keys lookup keys; empty strings are dropped
     */
    public Reference(String kind, List<String> keys) {
        if (kind == null || kind.isEmpty()) {
            throw new IllegalArgumentException("kind must not be empty");
        }
        this.kind = kind;
        List<String> k = new ArrayList<String>(keys.size());
        for (String s : keys) {
            if (s != null && !s.isEmpty()) {
                k.add(s);
            }
        }
        this.keys = Collections.unmodifiableList(k);
    }

    /**
     * @param kind reference kind
     * @param key  the single key
     * @return the reference
     */
    public static Reference of(String kind, String key) {
        return new Reference(kind, Collections.singletonList(key));
    }

    /** @return the kind, e.g. {@link #OBJECT_NAME} */
    public String getKind() {
        return kind;
    }

    /** @return the keys to resolve (one for a single reference, several for arrays); may be empty */
    public List<String> getKeys() {
        return keys;
    }

    /** @return true when no key is present (e.g. an empty name or the null entity id) */
    public boolean isEmpty() {
        return keys.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Reference)) {
            return false;
        }
        Reference r = (Reference) o;
        return kind.equals(r.kind) && keys.equals(r.keys);
    }

    @Override
    public int hashCode() {
        return 31 * kind.hashCode() + keys.hashCode();
    }

    @Override
    public String toString() {
        return kind + keys;
    }
}
