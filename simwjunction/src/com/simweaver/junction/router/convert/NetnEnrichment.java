package com.simweaver.junction.router.convert;

import com.simweaver.common.hla.codec.HValue;
import com.simweaver.common.iem.ClassAttribute;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.common.iem.ObjectClass;
import com.simweaver.junction.config.ClassFilter;
import com.simweaver.junction.config.RouterConfig;
import com.simweaver.junction.endpoint.Endpoint;
import com.simweaver.junction.endpoint.Interest;
import com.simweaver.junction.router.IemRouter;
import com.simweaver.junction.router.ModelTools;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * NETN properties for objects from an RPR (non-NETN) endpoint (0.12.0, an
 * {@link IemRouter.Extension}; {@code router.netn.*}).
 *
 * <p>When an object is bridged from an endpoint whose model lacks a NETN attribute into one whose
 * model has it, its copy there gets:
 * <ul>
 *   <li><b>{@code UniqueId}</b> (NETN-BASE, on {@code HLAobjectRoot}): a UUID, {@code stable} (name
 *       based: the router's object GUID, from the gateway, the origin endpoint and the object's
 *       name, the same as the {@code {guid}} copy-name token; the same after a reconnect or a
 *       restart) or {@code random} (new for every bridged object).</li>
 *   <li><b>{@code Callsign}</b> (NETN-ENTITY, on {@code BaseEntity}): the text of the RPR
 *       {@code Marking} (or {@code AggregateMarking}), when it is not empty, and again whenever it
 *       changes.</li>
 * </ul>
 * Objects that come from a NETN endpoint carry their own values and are left alone, as are
 * classes outside {@code router.netn.classes} and copies whose class lacks the attribute.
 *
 * <p>The values are extras of the copy ({@link IemRouter#updateCopy}): sent with the copy's first
 * update, again after a re-registration, and on provide requests. The attributes are published
 * for the classes the router publishes in the NETN endpoint.
 *
 * <p>Dispatcher thread only, like every extension.
 */
public final class NetnEnrichment implements IemRouter.Extension {

    private static final Logger LOG = Logger.getLogger(NetnEnrichment.class.getName());

    static final String UNIQUE_ID = "UniqueId";
    static final String CALLSIGN = "Callsign";
    static final String BASE_ENTITY = "HLAobjectRoot.BaseEntity";
    private static final String[] MARKINGS = {"Marking", "AggregateMarking"};

    private final RouterConfig.Netn cfg;
    private final ClassFilter classes;
    private IemRouter router;
    private final Map<Endpoint, ModelTools> tools = new IdentityHashMap<Endpoint, ModelTools>();
    /** Endpoints whose model has the attribute (targets) or lacks it (sources). */
    private final Set<Endpoint> uidTargets = Collections.newSetFromMap(new IdentityHashMap<Endpoint, Boolean>());
    private final Set<Endpoint> callsignTargets = Collections.newSetFromMap(new IdentityHashMap<Endpoint, Boolean>());
    private final List<Endpoint> endpoints = new ArrayList<Endpoint>();

    /** Bridged objects that get something: origin endpoint -> key -> state. */
    private final Map<Endpoint, Map<Long, Tracked>> tracked = new IdentityHashMap<Endpoint, Map<Long, Tracked>>();

    final AtomicInteger objects = new AtomicInteger();
    final AtomicInteger uniqueIds = new AtomicInteger();
    final AtomicInteger callsigns = new AtomicInteger();

    /** An object from a non-NETN endpoint. */
    private static final class Tracked {
        final int originClass;
        final UUID uuid;
        String callsign;

        Tracked(int originClass, UUID uuid) {
            this.originClass = originClass;
            this.uuid = uuid;
        }
    }

    /** @param cfg the router's {@code router.netn.*} settings */
    public NetnEnrichment(RouterConfig.Netn cfg) {
        this.cfg = cfg;
        this.classes = new ClassFilter(cfg.getClasses(), Collections.<String>emptyList(),
                Collections.<String>emptyList(), Collections.<String>emptyList());
    }

    // ================================================================== set-up

    @Override
    public boolean prepare(List<Endpoint> eps, Map<String, ModelTools> byId) {
        if (!cfg.isEnabled()) {
            return false;
        }
        boolean uidSource = false, callsignSource = false;
        for (Endpoint e : eps) {
            ModelTools t = byId.get(e.id());
            InformationExchangeModel m = t == null ? null : t.model();
            if (m == null) {
                continue;
            }
            tools.put(e, t);
            endpoints.add(e);
            if (hasUniqueId(m)) uidTargets.add(e); else uidSource = true;
            if (hasCallsign(m)) callsignTargets.add(e); else callsignSource = true;
        }
        boolean uid = cfg.isUniqueId() && uidSource && !uidTargets.isEmpty();
        boolean cs = cfg.isCallsign() && callsignSource && !callsignTargets.isEmpty();
        if (!cfg.isUniqueId()) uidTargets.clear();
        if (!cfg.isCallsign()) callsignTargets.clear();
        if (!uid && !cs) {
            LOG.info("NETN enrichment is on but has nothing to do: it needs an endpoint whose model lacks "
                    + "HLAobjectRoot.UniqueId / BaseEntity.Callsign and one that has them");
            return false;
        }
        return true;
    }

    static boolean hasUniqueId(InformationExchangeModel m) {
        ObjectClass root = m.getObjectClassRoot();
        return root != null && root.getAttribute(UNIQUE_ID) != null;
    }

    static boolean hasCallsign(InformationExchangeModel m) {
        ObjectClass be = m.getObjectClass(BASE_ENTITY);
        return be != null && be.getAttribute(CALLSIGN) != null;
    }

    @Override
    public void declare(Endpoint e, ModelTools t, Interest publish, Interest subscribe) {
        boolean uid = uidTargets.contains(e), cs = callsignTargets.contains(e);
        if (!uid && !cs) {
            return;
        }
        InformationExchangeModel m = t.model();
        // the classes the router publishes here, plus the attributes the copies get
        for (Integer cls : new ArrayList<Integer>(publish.getObjectClasses().keySet())) {
            ObjectClass oc = m.getObjectClass(cls);
            if (oc == null || !classes.allowsObjectClass(oc.getQualifiedName())) {
                continue;
            }
            List<Integer> ids = new ArrayList<Integer>(2);
            ClassAttribute a = uid ? oc.getAttribute(UNIQUE_ID) : null;
            if (a != null) ids.add(a.getId());
            a = cs ? oc.getAttribute(CALLSIGN) : null;
            if (a != null) ids.add(a.getId());
            if (!ids.isEmpty()) {
                publish.addAttributes(cls, ids);
            }
        }
    }

    @Override
    public void started(IemRouter r, List<Endpoint> eps, Map<String, ModelTools> byId) {
        this.router = r;
        List<String> to = new ArrayList<String>();
        for (Endpoint e : endpoints) {
            if (uidTargets.contains(e) || callsignTargets.contains(e)) {
                to.add(e.id() + (uidTargets.contains(e) ? " UniqueId" : "") + (callsignTargets.contains(e) ? " Callsign" : ""));
            }
        }
        LOG.info("Junction gateway " + r.gatewayId() + ": NETN properties for objects from non-NETN endpoints into "
                + String.join(", ", to) + " (UniqueId " + cfg.getUniqueIdMode() + ", classes " + String.join(";", cfg.getClasses()) + ")");
    }

    // ================================================================== objects

    @Override
    public void bridged(Endpoint s, long key, int objectClass, String name) {
        ModelTools st = tools.get(s);
        if (st == null) {
            return;
        }
        ObjectClass oc = st.model().getObjectClass(objectClass);
        if (oc == null || !classes.allowsObjectClass(oc.getQualifiedName())) {
            return;
        }
        boolean wantsUid = !uidTargets.isEmpty() && !hasUniqueId(st.model());
        boolean wantsCallsign = !callsignTargets.isEmpty() && !hasCallsign(st.model()) && markingOf(oc) != null;
        if (!wantsUid && !wantsCallsign) {
            return;
        }
        UUID uuid = null;
        if (wantsUid) {
            uuid = cfg.getUniqueIdMode() == RouterConfig.Netn.UniqueIdMode.RANDOM ? UUID.randomUUID() : router.objectGuid(s, key);
        }
        Tracked t = new Tracked(objectClass, uuid);
        tracked.computeIfAbsent(s, k -> new HashMap<Long, Tracked>()).put(key, t);
        objects.incrementAndGet();
        if (uuid != null) {
            // kept by the router and sent with the copy's first update, wherever it is registered
            for (Endpoint d : uidTargets) {
                if (d != s) {
                    send(s, key, t, d, true, false);
                }
            }
        }
    }

    @Override
    public void observed(Endpoint s, long key, int[] attributes, byte[][] values) {
        Map<Long, Tracked> mine = tracked.get(s);
        Tracked t = mine == null ? null : mine.get(key);
        if (t == null || callsignTargets.isEmpty()) {
            return;
        }
        ModelTools st = tools.get(s);
        ObjectClass oc = st.model().getObjectClass(t.originClass);
        ClassAttribute marking = oc == null ? null : markingOf(oc);
        if (marking == null || hasCallsign(st.model())) {
            return;
        }
        for (int i = 0; i < attributes.length; i++) {
            if (attributes[i] != marking.getId() || values[i] == null) {
                continue;
            }
            String text;
            try {
                text = markingText(st.codec().decode(marking.getDataType(), values[i]));
            } catch (RuntimeException e) {
                continue;                                        // undecodable: keep the last one
            }
            if (text.isEmpty() || text.equals(t.callsign)) {
                continue;                                        // "if set": an empty marking gives no callsign
            }
            t.callsign = text;
            for (Endpoint d : callsignTargets) {
                if (d != s) {
                    send(s, key, t, d, false, true);
                }
            }
        }
    }

    @Override
    public void unbridged(Endpoint s, long key) {
        Map<Long, Tracked> mine = tracked.get(s);
        if (mine != null && mine.remove(key) != null) {
            objects.decrementAndGet();
        }
    }

    /** Gives the copy of the object in {@code d} its UniqueId and / or Callsign. */
    private void send(Endpoint s, long key, Tracked t, Endpoint d, boolean uid, boolean callsign) {
        int dc = router.routedClass(s, t.originClass, d);
        ModelTools dt = tools.get(d);
        ObjectClass doc = dc < 0 || dt == null ? null : dt.model().getObjectClass(dc);
        if (doc == null || !classes.allowsObjectClass(doc.getQualifiedName())) {
            return;
        }
        List<Integer> ids = new ArrayList<Integer>(2);
        List<byte[]> vals = new ArrayList<byte[]>(2);
        ClassAttribute a = uid && t.uuid != null && uidTargets.contains(d) ? doc.getAttribute(UNIQUE_ID) : null;
        if (a != null) {
            ids.add(a.getId());
            vals.add(dt.codec().encode(a.getDataType(), HValue.ofBytes(uuidBytes(t.uuid))));
            uniqueIds.incrementAndGet();
        }
        a = callsign && t.callsign != null && callsignTargets.contains(d) ? doc.getAttribute(CALLSIGN) : null;
        if (a != null) {
            ids.add(a.getId());
            vals.add(dt.codec().encode(a.getDataType(), HValue.ofText(t.callsign)));
            callsigns.incrementAndGet();
        }
        if (ids.isEmpty()) {
            return;
        }
        int[] ia = new int[ids.size()];
        for (int i = 0; i < ia.length; i++) ia[i] = ids.get(i);
        router.updateCopy(s, key, d, ia, vals.toArray(new byte[0][]));
    }

    // ================================================================== values

    private static ClassAttribute markingOf(ObjectClass oc) {
        for (String n : MARKINGS) {
            ClassAttribute a = oc.getAttribute(n);
            if (a != null) return a;
        }
        return null;
    }

    /**
     * The text of an RPR marking ({@code MarkingStruct} / {@code AggregateMarkingStruct}: an
     * encoding type and the characters, NUL padded), or of a plain text value: printable
     * characters, trimmed.
     */
    static String markingText(HValue v) {
        if (v == null) {
            return "";
        }
        HValue data = v;
        if (v.getKind() == HValue.Kind.RECORD) {
            data = v.field("MarkingData");
            if (data == null) {
                for (String f : v.getFieldNames()) {
                    HValue x = v.field(f);
                    if (x != null && (x.getKind() == HValue.Kind.TEXT || x.getKind() == HValue.Kind.BYTES || x.getKind() == HValue.Kind.ARRAY)) {
                        data = x;
                    }
                }
            }
        }
        if (data == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        if (data.getKind() == HValue.Kind.ARRAY) {
            for (HValue x : data.getValues()) {
                long c = x.asLong();
                if (c == 0) break;
                b.append((char) (c & 0xFF));
            }
        } else {
            String s = data.asText();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == 0) break;
                b.append(c);
            }
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < b.length(); i++) {
            char c = b.charAt(i);
            out.append(c < 0x20 || c == 0x7F ? ' ' : c);
        }
        return out.toString().trim();
    }

    static byte[] uuidBytes(UUID u) {
        return ByteBuffer.allocate(16).putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits()).array();
    }

    // ================================================================== status

    /** For the gateway page: what was added. */
    public Map<String, Object> status() {
        Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
        m.put("objects", objects.get());
        m.put("uniqueids", uniqueIds.get());
        m.put("callsigns", callsigns.get());
        m.put("uniqueid", cfg.isUniqueId() ? cfg.getUniqueIdMode().name().toLowerCase(java.util.Locale.ROOT) : "off");
        m.put("callsign", cfg.isCallsign() ? "marking" : "off");
        List<String> to = new ArrayList<String>();
        for (Endpoint e : endpoints) {
            if (uidTargets.contains(e) || callsignTargets.contains(e)) to.add(e.id());
        }
        m.put("into", to);
        return m;
    }

    @Override
    public void describe(Map<String, Object> into) {
        into.put("netn", status());
    }

    @Override
    public String toString() {
        return "NetnEnrichment" + Objects.toString(status());
    }
}
