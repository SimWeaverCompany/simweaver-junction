package com.simweaver.rift.api;

import com.simweaver.common.geo.LocalKinematics;
import com.simweaver.common.iem.HlaClass;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.rift.db.RiftDatabase;
import com.simweaver.rift.db.RiftInstance;
import com.simweaver.rift.db.RiftInteraction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 0.14.0: the object queries the datasource page and the map use, for other packages (the MCP
 * tools): the same class and text matching, force, and positions (own, or the carrier's).
 */
public final class RiftQueries {

    private RiftQueries() {
    }

    /**
     * The active objects of these classes (qualified names, empty = all) that contain this text
     * (null = any), matched as the datasource page matches them.
     */
    public static List<RiftInstance> match(RiftDatabase db, RiftJson json, List<String> classes, boolean subclasses, String text) {
        String lower = text == null || text.trim().isEmpty() ? null : text.trim().toLowerCase(Locale.ROOT);
        return db.getInstances(o -> !o.isRemoved()
                && RiftDatasourcePageApi.classMatch(o.getClassName(), classes, subclasses)
                && (lower == null || RiftDatasourcePageApi.textMatch(o, lower, json)));
    }

    /** "friendly", "opposing", "neutral", "other", or null (no ForceIdentifier). */
    public static String force(RiftInstance o) {
        return RiftMapObjects.force(text(o, "ForceIdentifier"));
    }

    /** The display text of an attribute, or null. */
    public static String text(RiftInstance o, String attribute) {
        RiftMemberView a = o.attributes().get(attribute);
        if (a == null || a.value() == null) {
            return null;
        }
        String t = a.value().text();
        return t == null || t.trim().isEmpty() ? null : t.trim();
    }

    /** An attribute's entity type in words ({"text", "context", "country", "countryname" …}), or null. */
    public static Map<String, Object> entityType(RiftInstance o, String attribute) {
        RiftMemberView a = o.attributes().get(attribute);
        return a == null || a.value() == null ? null : RiftJson.entityType(a.value(), false);
    }

    /** The entity type in words of any value node, or null. */
    public static Map<String, Object> entityType(ValueNode v) {
        return v == null ? null : RiftJson.entityType(v, false);
    }

    /**
     * The object classes a name means: a qualified name (with or without {@code HLAobjectRoot}),
     * a simple name (case-insensitive), or a dotted tail ({@code Platform.Aircraft}). From the
     * datasource's model, else from the classes of the objects it has.
     *
     * @return qualified names (with the root), empty when none
     */
    public static List<String> objectClasses(RiftDatabase db, String name) {
        Set<String> all = new LinkedHashSet<>();
        InformationExchangeModel iem = db.getInformationExchangeModel();
        if (iem != null) {
            for (HlaClass<?> c : iem.getObjectClasses()) {
                all.add(c.getQualifiedName());
            }
        }
        all.addAll(db.getClassCounts().keySet());
        return resolve(all, name, "HLAobjectRoot");
    }

    /** The interaction classes a name means, as {@link #objectClasses}; also from interactions received. */
    public static List<String> interactionClasses(RiftDatabase db, String name) {
        Set<String> all = new LinkedHashSet<>();
        InformationExchangeModel iem = db.getInformationExchangeModel();
        if (iem != null) {
            for (HlaClass<?> c : iem.getInteractionClasses()) {
                all.add(c.getQualifiedName());
            }
        }
        for (RiftInteraction ix : db.getInteractions(0, 5000, null)) {
            if (ix.classNameQ() != null) {
                all.add(ix.classNameQ());
            }
        }
        return resolve(all, name, "HLAinteractionRoot");
    }

    private static List<String> resolve(Set<String> all, String name, String root) {
        List<String> out = new ArrayList<>();
        if (name == null || name.trim().isEmpty()) {
            return out;
        }
        String n = name.trim();
        String q = n.equals(root) || n.startsWith(root + ".") ? n : root + "." + n;
        for (String c : all) {
            if (c.equals(q)) {
                out.add(c);
                return out;
            }
        }
        String lq = q.toLowerCase(Locale.ROOT);
        for (String c : all) {
            if (c.toLowerCase(Locale.ROOT).equals(lq)) {
                out.add(c);
            }
        }
        if (!out.isEmpty()) {
            return out;
        }
        String tail = "." + n.toLowerCase(Locale.ROOT);
        for (String c : all) {
            if (c.toLowerCase(Locale.ROOT).endsWith(tail)) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * Positions as the map shows them, as of one moment: an object's own (dead reckoned), else the
     * position of the top-most positioned object carrying it (HostObjectIdentifier, IsPartOf,
     * EmitterSystemIdentifier). Not thread safe: one per request.
     */
    public static final class Positions {
        private final RiftDatabase db;
        private final long now;
        private final Map<RiftInstance, LocalKinematics> pos = new HashMap<>();
        private final Map<RiftInstance, RiftInstance> hostOf = new HashMap<>();
        private final Map<RiftInstance, String> via = new HashMap<>();

        public Positions(RiftDatabase db, long nowMillis) {
            this.db = db;
            this.now = nowMillis;
        }

        public long now() {
            return now;
        }

        /** The object's own position, dead reckoned to now, or null. */
        public LocalKinematics own(RiftInstance o) {
            return RiftMapObjects.position(o, pos, now);
        }

        /** The top-most positioned object carrying this one, or null. */
        public RiftInstance carrier(RiftInstance o) {
            return RiftMapObjects.topHost(db, o, pos, hostOf, via, now);
        }

        /** The object directly carrying this one, or null. */
        public RiftInstance host(RiftInstance o) {
            return RiftMapObjects.host(db, o, hostOf, via);
        }

        /** How this object is carried by its host: "hosted", "partof", "beam", or null. */
        public String relation(RiftInstance o) {
            return host(o) == null ? null : via.get(o);
        }

        /** The object's own position, else its carrier's, or null. */
        public LocalKinematics of(RiftInstance o) {
            LocalKinematics k = own(o);
            if (k != null) {
                return k;
            }
            RiftInstance c = carrier(o);
            return c == null ? null : own(c);
        }

        /** When the object's Spatial was received (epoch ms), 0 when never. */
        public long received(RiftInstance o) {
            return o.spatialReceivedMillis();
        }
    }
}
