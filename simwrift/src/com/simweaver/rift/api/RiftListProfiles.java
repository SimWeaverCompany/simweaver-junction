package com.simweaver.rift.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which attributes the list view shows per class (contract 3.4). A profile set on a class
 * applies to all its subclasses unless a subclass has its own. Classes without a profile
 * show the first {@link #DEFAULT_COUNT} received attributes in FOM order.
 *
 * Immutable after construction except for the per-class lookup cache; thread safe.
 */
public final class RiftListProfiles {

    public static final int DEFAULT_COUNT = 3;

    private final Map<String, List<String>> byClass;          // qualified class -> attributes
    private final Map<String, List<String>> resolved = new ConcurrentHashMap<>();

    private RiftListProfiles(Map<String, List<String>> byClass) {
        this.byClass = byClass;
    }

    /** No profiles: every class shows its first three received attributes. */
    public static RiftListProfiles none() {
        return new RiftListProfiles(Collections.emptyMap());
    }

    /**
     * Defaults for RPR-FOM v2/v3 (and NETN, which extends RPR classes). The label comes from
     * Marking (see {@link RiftJson#labelOf}), so Marking is not repeated in the list.
     */
    public static RiftListProfiles rprDefaults() {
        return builder()
            .set("HLAobjectRoot.BaseEntity", "EntityIdentifier", "EntityType", "ForceIdentifier")
            .set("HLAobjectRoot.BaseEntity.AggregateEntity", "EntityIdentifier", "ForceIdentifier", "AggregateState")
            .set("HLAobjectRoot.EmbeddedSystem", "HostObjectIdentifier")
            .set("HLAobjectRoot.EmitterBeam", "EmitterSystemIdentifier", "BeamFunctionCode")
            .set("HLAobjectRoot.Minefield", "MinefieldIdentifier", "ForceIdentifier")
            .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A builder starting from the profiles of {@code base} (e.g. {@link #rprDefaults()}). */
    public static Builder builder(RiftListProfiles base) {
        Builder b = new Builder();
        if (base != null) b.map.putAll(base.byClass);
        return b;
    }

    /** Default property prefix for {@link #fromProperties}. */
    public static final String PROPERTY_PREFIX = "rift.listprofile.";

    /**
     * Profiles from properties on top of {@code base}: one entry per class, attribute names comma
     * separated, in display order. An empty value removes the class's profile (it then inherits
     * from its superclass, or shows its first three attributes).
     *
     * <pre>
     * rift.listprofile.HLAobjectRoot.BaseEntity = EntityIdentifier, EntityType, ForceIdentifier
     * rift.listprofile.EmbeddedSystem.RadioTransmitter = HostObjectIdentifier, Frequency, TransmitterOperationalStatus
     * </pre>
     *
     * Class names may leave out "HLAobjectRoot.".
     */
    public static RiftListProfiles fromProperties(Properties props, String prefix, RiftListProfiles base) {
        Builder b = builder(base);
        String p = prefix == null ? PROPERTY_PREFIX : prefix;
        if (props != null) {
            for (String key : props.stringPropertyNames()) {
                if (!key.startsWith(p) || key.length() == p.length()) continue;
                String cls = key.substring(p.length()).trim();
                List<String> attrs = new ArrayList<>();
                for (String a : props.getProperty(key, "").split(",")) {
                    if (!a.trim().isEmpty()) attrs.add(a.trim());
                }
                if (attrs.isEmpty()) b.remove(cls);
                else b.set(cls, attrs.toArray(new String[0]));
            }
        }
        return b.build();
    }

    /** The configured profiles (qualified class -> attributes), for logging or a settings page. */
    public Map<String, List<String>> asMap() {
        return byClass;
    }

    /** Attribute names configured for exactly this class or its nearest configured superclass; null if none. */
    public List<String> configured(String classNameQ) {
        if (classNameQ == null) return null;
        List<String> hit = resolved.get(classNameQ);
        if (hit != null) return hit.isEmpty() ? null : hit;
        String c = classNameQ;
        List<String> found = null;
        while (true) {
            found = byClass.get(c);
            if (found != null) break;
            int dot = c.lastIndexOf('.');
            if (dot < 0) break;
            c = c.substring(0, dot);
        }
        resolved.put(classNameQ, found == null ? Collections.<String>emptyList() : found);
        return found;
    }

    /**
     * The attributes to show for an object: the configured ones it has received, or the first
     * DEFAULT_COUNT received ones when nothing is configured for its class.
     */
    public List<String> select(String classNameQ, Collection<String> receivedInFomOrder) {
        List<String> conf = configured(classNameQ);
        List<String> out = new ArrayList<>();
        if (conf != null) {
            for (String a : conf) if (receivedInFomOrder.contains(a)) out.add(a);
            return out;
        }
        for (String a : receivedInFomOrder) {
            if (out.size() >= DEFAULT_COUNT) break;
            out.add(a);
        }
        return out;
    }

    /** True if a change of these attributes changes the list view of the class. */
    public boolean affectsListView(String classNameQ, Collection<String> changed, Collection<String> receivedInFomOrder) {
        List<String> shown = select(classNameQ, receivedInFomOrder);
        for (String a : changed) if (shown.contains(a)) return true;
        return false;
    }

    public static final class Builder {
        private final Map<String, List<String>> map = new LinkedHashMap<>();

        /** Profile for a class and its subclasses; "HLAobjectRoot." may be left out of the name. */
        public Builder set(String classNameQ, String... attributes) {
            map.put(qualify(classNameQ), Collections.unmodifiableList(new ArrayList<>(Arrays.asList(attributes))));
            return this;
        }

        /** Removes the profile of exactly this class. */
        public Builder remove(String classNameQ) {
            map.remove(qualify(classNameQ));
            return this;
        }

        private static String qualify(String c) {
            String t = c.trim();
            return t.equals("HLAobjectRoot") || t.startsWith("HLAobjectRoot.") ? t : "HLAobjectRoot." + t;
        }

        public RiftListProfiles build() {
            return new RiftListProfiles(Collections.unmodifiableMap(new LinkedHashMap<>(map)));
        }
    }
}
