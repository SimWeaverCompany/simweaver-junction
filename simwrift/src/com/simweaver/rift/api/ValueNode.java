package com.simweaver.rift.api;

import java.util.Collections;
import java.util.List;

/**
 * Read-only view of one decoded value (contract 3.3), independent of the decoder classes.
 * {@link DecodedValueNode} adapts the decoder's DecodedValue to it; tests can use
 * simple implementations.
 */
public interface ValueNode {

    /** ValueKind name: INTEGER, FLOAT, BOOLEAN, ENUM, TEXT, BYTES, RECORD, ARRAY, VARIANT, EMPTY, PADDING, ERROR. */
    String kind();

    /** One-line display text (never null; "" if none). */
    String text();

    /**
     * Typed scalar for INTEGER (Long), FLOAT (Double), BOOLEAN (Boolean), ENUM (Long, the numeric
     * value), TEXT (String), BYTES (hex String); null for structured kinds.
     */
    Object scalar();

    /** Enumerator name for ENUM, null otherwise or when the value is not in the FOM. */
    default String enumName() { return null; }

    default String units() { return null; }

    /** Datatype name of this node, if known. */
    default String type() { return null; }

    /** RECORD / VARIANT members in FOM order (VARIANT: discriminant first). */
    default List<Named> fields() { return Collections.emptyList(); }

    /** ARRAY elements (as decoded; serialisation caps them). */
    default List<ValueNode> items() { return Collections.emptyList(); }

    /** Total element count of an ARRAY (may exceed items().size()). */
    default int count() { return items().size(); }

    /** Values computed by formatters, e.g. RPR Latitude/Longitude/Heading. */
    default List<Named> derived() { return Collections.emptyList(); }

    /** Reference kind ("objectname", "entityid") when this node is a reference, else null. */
    default String refKind() { return null; }

    /** Referenced keys when refKind() != null. */
    default List<String> refKeys() { return Collections.emptyList(); }

    default String error() { return null; }

    default List<String> warnings() { return Collections.emptyList(); }

    /** True if the decoder cut text/bytes/items. */
    default boolean truncated() { return false; }

    /** A named child node (record field, variant member, derived value). */
    final class Named {
        public final String name;
        public final ValueNode node;

        public Named(String name, ValueNode node) {
            this.name = name;
            this.node = node;
        }
    }
}
