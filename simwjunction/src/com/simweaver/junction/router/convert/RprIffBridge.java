package com.simweaver.junction.router.convert;

import com.simweaver.common.hla.codec.HValue;
import com.simweaver.common.hla.codec.HlaCodec;
import com.simweaver.common.iem.Attribute;
import com.simweaver.common.iem.ClassAttribute;
import com.simweaver.common.iem.FomFamily;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.common.iem.ObjectClass;
import com.simweaver.junction.endpoint.Endpoint;
import com.simweaver.junction.endpoint.EndpointState;
import com.simweaver.junction.endpoint.Interest;
import com.simweaver.junction.endpoint.NameCandidates;
import com.simweaver.junction.router.IemRouter;
import com.simweaver.junction.router.ModelTools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

/**
 * IFF modes between RPR v2.0 and v3.0 (an {@link IemRouter.Extension} of the IEM router).
 *
 * <p>The router bridges the IFF systems themselves (v2 {@code IFF.NatoIFF.NatoIFFTransponder}
 * and so on with v3 {@code IFFSystem.Transponder}, by the mapping table). The modes are another
 * matter: v2 has them as attributes of the system ({@code Mode3AIsOn}, {@code Mode3ACode}, ...),
 * v3 as separate {@code IFFData} objects that name their system ({@code IFFSystemIdentifier}).
 * This extension only watches; it does not take objects away from the router, so v3 IFFData
 * still goes from one v3 federation to another as usual.
 *
 * <ul>
 *   <li><b>v2 -> v3.</b> For a v2 NatoIFF / RRB system whose copy is live in a v3 endpoint, one
 *       IFFData object per enabled mode (Mode1, Mode2, Mode3A, Mode4, ModeC from the v2
 *       "Mode5C" parameter 5, ModeS; RRB), of the transponder / interrogator subclass, named
 *       {@code <copy>/mode3a} etc., kept up to date and deleted with the system.</li>
 *   <li><b>v3 -> v2.</b> The IFFData objects of a v3 system are folded into the v2 copy of that
 *       system ({@link IemRouter#updateCopy}): {@code <Mode>Enabled} (the mode object exists),
 *       on, damaged, malfunctioning, the codes, Mode 4 pseudo crypto and alternate Mode 4,
 *       Mode C altitude.</li>
 * </ul>
 *
 * <p>Not converted: v3 Mode 5, TCASData and the Mode S details (v2 has no attributes for them),
 * the v2 Soviet IFF parameters (v3 Soviet IFFData has no parameters), the v2 STI and TCAS flags.
 *
 * <p>Dispatcher thread only, like every extension.
 */
public final class RprIffBridge implements IemRouter.Extension {

    private static final Logger LOG = Logger.getLogger(RprIffBridge.class.getName());

    static final String V2_IFF = "HLAobjectRoot.EmbeddedSystem.IFF";
    static final String V2_NATO = V2_IFF + ".NatoIFF";
    static final String V2_TRANSPONDER = V2_NATO + ".NatoIFFTransponder";
    static final String V2_INTERROGATOR = V2_NATO + ".NatoIFFInterrogator";
    static final String V2_RRB = V2_IFF + ".RRB";
    static final String V3_SYSTEM = "HLAobjectRoot.EmbeddedSystem.IFFSystem";
    static final String V3_DATA = "HLAobjectRoot.IFFData";

    /** v2 mode prefix -> v3 IFFData mode class (simple name). */
    private static final String[][] MODES = {
        {"Mode1", "Mode1"}, {"Mode2", "Mode2"}, {"Mode3A", "Mode3A"}, {"Mode4", "Mode4"}, {"Mode5C", "ModeC"}, {"ModeS", "ModeS"}};

    private IemRouter router;
    private final Set<Endpoint> v2 = Collections.newSetFromMap(new IdentityHashMap<Endpoint, Boolean>());
    private final Set<Endpoint> v3 = Collections.newSetFromMap(new IdentityHashMap<Endpoint, Boolean>());
    private final Map<Endpoint, ModelTools> tools = new IdentityHashMap<Endpoint, ModelTools>();

    // v2 systems: origin endpoint -> key -> state
    private final Map<Endpoint, Map<Long, V2System>> v2Systems = new IdentityHashMap<Endpoint, Map<Long, V2System>>();
    private final Map<Long, ModeObject> ours = new HashMap<Long, ModeObject>();          // by gateway id (negative)

    // v3 side: IFFData objects and IFFSystem objects, by origin endpoint and key
    private final Map<Endpoint, Map<Long, V3Mode>> v3Modes = new IdentityHashMap<Endpoint, Map<Long, V3Mode>>();
    private final Map<Endpoint, Map<Long, String>> v3SystemNames = new IdentityHashMap<Endpoint, Map<Long, String>>();

    /** A v2 IFF system bridged by the router: its mode attributes as last seen. */
    private static final class V2System {
        final Endpoint origin;
        final long key;
        final String className;
        final Map<String, HValue> values = new HashMap<String, HValue>();
        final Map<Endpoint, Map<String, ModeObject>> modes = new IdentityHashMap<Endpoint, Map<String, ModeObject>>();

        V2System(Endpoint origin, long key, String className) {
            this.origin = origin;
            this.key = key;
            this.className = className;
        }
    }

    /** An IFFData object this extension made in a v3 endpoint. */
    private static final class ModeObject {
        final V2System system;
        final Endpoint d;
        final String mode;
        final int objectClass;
        long gwId;
        long key;
        boolean live;
        boolean failed;
        Map<String, HValue> wanted = new LinkedHashMap<String, HValue>();
        final Map<String, HValue> sent = new HashMap<String, HValue>();

        ModeObject(V2System system, Endpoint d, String mode, int objectClass) {
            this.system = system;
            this.d = d;
            this.mode = mode;
            this.objectClass = objectClass;
        }
    }

    /** A v3 IFFData object seen in a v3 endpoint. */
    private static final class V3Mode {
        final Endpoint origin;
        final long key;
        final String className;
        final String mode;              // Mode1 .. ModeS, RRB, ... (the class below IFFData)
        final String role;              // "Transponder", "Interrogator" or ""
        final Map<String, HValue> values = new HashMap<String, HValue>();
        String system;                  // IFFSystemIdentifier

        V3Mode(Endpoint origin, long key, String className) {
            this.origin = origin;
            this.key = key;
            this.className = className;
            String[] seg = className.split("\\.");
            this.mode = seg.length > 2 ? seg[2] : "";
            String leaf = seg[seg.length - 1];
            this.role = leaf.endsWith("Transponder") ? "Transponder" : leaf.endsWith("Interrogator") ? "Interrogator" : "";
        }
    }

    // ================================================================== set-up

    @Override
    public boolean prepare(List<Endpoint> endpoints, Map<String, ModelTools> byId) {
        for (Endpoint e : endpoints) {
            ModelTools t = byId.get(e.id());
            InformationExchangeModel m = t == null ? null : t.model();
            if (m == null) {
                continue;
            }
            tools.put(e, t);
            if (m.hasFomFamily(FomFamily.RPR_FOM_V3) && m.getObjectClass(V3_DATA) != null) {
                v3.add(e);
            } else if (m.hasFomFamily(FomFamily.RPR_FOM_V2) && m.getObjectClass(V2_NATO) != null) {
                v2.add(e);
            }
        }
        return !v2.isEmpty() && !v3.isEmpty();
    }

    @Override
    public void declare(Endpoint e, ModelTools t, Interest publish, Interest subscribe) {
        InformationExchangeModel m = t.model();
        if (v3.contains(e)) {
            // the IFFData objects: subscribed (to fold into v2), published (the ones made from v2)
            ObjectClass root = m.getObjectClass(V3_DATA);
            List<ObjectClass> classes = new ArrayList<ObjectClass>(root.getDescendants());
            classes.add(root);
            for (ObjectClass oc : classes) {
                List<Integer> ids = new ArrayList<Integer>();
                for (ClassAttribute a : oc.getAttributes()) {
                    ids.add(a.getId());
                }
                subscribe.addAttributes(oc.getId(), ids);
                publish.addAttributes(oc.getId(), ids);
            }
        } else if (v2.contains(e)) {
            // the mode attributes of the v2 IFF classes, which the routes do not carry
            ObjectClass iff = m.getObjectClass(V2_IFF);
            List<ObjectClass> classes = new ArrayList<ObjectClass>(iff.getDescendants());
            classes.add(iff);
            for (ObjectClass oc : classes) {
                List<Integer> ids = new ArrayList<Integer>();
                for (ClassAttribute a : oc.getAttributes()) {
                    if (isModeAttribute(a.getName())) ids.add(a.getId());
                }
                if (!ids.isEmpty()) {
                    subscribe.addAttributes(oc.getId(), ids);
                    publish.addAttributes(oc.getId(), ids);
                }
            }
        }
    }

    private static boolean isModeAttribute(String n) {
        for (String[] m : MODES) {
            if (n.startsWith(m[0])) return true;
        }
        return n.equals("AlternateMode4") || n.equals("Code") || n.equals("PowerReduction") || n.equals("RadarEnhancement")
                || n.equals("IsOn") || n.equals("IsDamaged") || n.equals("IsMalfunctioning");
    }

    @Override
    public void started(IemRouter r, List<Endpoint> endpoints, Map<String, ModelTools> byId) {
        this.router = r;
        LOG.info("Junction " + r.gatewayId() + ": IFF modes converted between RPR v2 (" + ids(v2) + ") and v3 (" + ids(v3) + ")");
    }

    private static String ids(Set<Endpoint> s) {
        List<String> l = new ArrayList<String>();
        for (Endpoint e : s) l.add(e.id());
        return String.join(", ", l);
    }

    // ================================================================== watching

    @Override
    public void bridged(Endpoint s, long key, int objectClass, String name) {
        InformationExchangeModel m = tools.get(s) == null ? null : tools.get(s).model();
        ObjectClass oc = m == null ? null : m.getObjectClass(objectClass);
        if (oc == null) {
            return;
        }
        String cls = oc.getQualifiedName();
        if (v2.contains(s) && (cls.startsWith(V2_NATO) || cls.startsWith(V2_RRB))) {
            v2Systems.computeIfAbsent(s, k -> new HashMap<Long, V2System>()).put(key, new V2System(s, key, cls));
        } else if (v3.contains(s) && (cls.equals(V3_DATA) || cls.startsWith(V3_DATA + "."))) {
            v3Modes.computeIfAbsent(s, k -> new HashMap<Long, V3Mode>()).put(key, new V3Mode(s, key, cls));
        } else if (v3.contains(s) && (cls.equals(V3_SYSTEM) || cls.startsWith(V3_SYSTEM + "."))) {
            v3SystemNames.computeIfAbsent(s, k -> new HashMap<Long, String>()).put(key, name);
        }
    }

    @Override
    public void observed(Endpoint s, long key, int[] attributes, byte[][] values) {
        V2System sys = get(v2Systems, s, key);
        if (sys != null) {
            decodeInto(s, attributes, values, sys.values);
            for (Endpoint d : v3) {
                syncModes(sys, d);
            }
            return;
        }
        V3Mode mode = get(v3Modes, s, key);
        if (mode != null) {
            String before = mode.system;
            decodeInto(s, attributes, values, mode.values);
            HValue ref = mode.values.get("IFFSystemIdentifier");
            mode.system = ref == null ? null : ref.asTrimmedText();
            if (before != null && !before.equals(mode.system)) {
                foldInto(s, before);                      // it moved to another system
            }
            if (mode.system != null) {
                foldInto(s, mode.system);
            }
        }
    }

    @Override
    public void unbridged(Endpoint s, long key) {
        Map<Long, V2System> m2 = v2Systems.get(s);
        V2System sys = m2 == null ? null : m2.remove(key);
        if (sys != null) {
            for (Map<String, ModeObject> byMode : sys.modes.values()) {
                for (ModeObject o : byMode.values()) {
                    delete(o);
                }
            }
            return;
        }
        Map<Long, V3Mode> m3 = v3Modes.get(s);
        V3Mode mode = m3 == null ? null : m3.remove(key);
        if (mode != null && mode.system != null) {
            foldInto(s, mode.system);
            return;
        }
        Map<Long, String> names = v3SystemNames.get(s);
        if (names != null) {
            names.remove(key);
        }
    }

    @Override
    public void copied(Endpoint origin, long originKey, Endpoint d, String name) {
        V2System sys = get(v2Systems, origin, originKey);
        if (sys != null && v3.contains(d)) {
            syncModes(sys, d);
            return;
        }
        Map<Long, String> names = v3SystemNames.get(origin);
        String system = names == null ? null : names.get(originKey);
        if (system != null && v2.contains(d)) {
            foldInto(origin, system);
        }
    }

    @Override
    public boolean registered(Endpoint d, long gwId, long key, String name) {
        ModeObject o = ours.get(gwId);
        if (o == null) {
            return gwId < 0;
        }
        if (o.live || o.failed) {                         // deleted meanwhile
            d.deleteObject(key, null);
            return true;
        }
        o.key = key;
        o.live = true;
        send(o);
        return true;
    }

    @Override
    public boolean registrationFailed(Endpoint d, long gwId, String reason) {
        ModeObject o = ours.remove(gwId);
        if (o != null) {
            o.failed = true;
            LOG.warning("Junction " + router.gatewayId() + ": could not create the " + o.mode + " IFFData object in " + d.id() + ": " + reason);
        }
        return gwId < 0;
    }

    @Override
    public void stateChanged(Endpoint e, EndpointState state) {
        if (state == EndpointState.LOST || state == EndpointState.FAILED || state == EndpointState.CLOSED) {
            // its objects are gone: ours in it too (made again when the copies come back)
            for (Map<Long, V2System> m : v2Systems.values()) {
                for (V2System sys : m.values()) {
                    Map<String, ModeObject> byMode = sys.modes.remove(e);
                    if (byMode != null) {
                        for (ModeObject o : byMode.values()) ours.remove(o.gwId);
                    }
                }
            }
        }
    }

    @Override
    public void stopping() {
        for (ModeObject o : new ArrayList<ModeObject>(ours.values())) {
            delete(o);
        }
    }

    // ================================================================== v2 -> v3

    /** Makes the IFFData objects of a v2 system in d match its mode attributes. */
    private void syncModes(V2System sys, Endpoint d) {
        String copy = router.copyName(sys.origin, sys.key, d);
        if (copy == null || copy.isEmpty() || !d.state().isUp()) {
            return;                                        // again when the copy is live (copied)
        }
        InformationExchangeModel m = tools.get(d).model();
        Map<String, Map<String, HValue>> wanted = modesOf(sys);
        Map<String, ModeObject> have = sys.modes.computeIfAbsent(d, k -> new LinkedHashMap<String, ModeObject>());
        for (String mode : new ArrayList<String>(have.keySet())) {
            if (!wanted.containsKey(mode)) {
                delete(have.remove(mode));
            }
        }
        for (Map.Entry<String, Map<String, HValue>> e : wanted.entrySet()) {
            String mode = e.getKey();
            Map<String, HValue> values = new LinkedHashMap<String, HValue>(e.getValue());
            values.put("IFFSystemIdentifier", HValue.ofText(copy));
            ModeObject o = have.get(mode);
            if (o == null) {
                ObjectClass oc = m.getObjectClass(v3Class(mode, sys.className));
                if (oc == null) {
                    continue;
                }
                o = new ModeObject(sys, d, mode, oc.getId());
                o.wanted = values;
                have.put(mode, o);
                String name = copy + "/" + mode.toLowerCase(Locale.ROOT);
                o.gwId = router.createObject(d, oc.getId(), new NameCandidates(java.util.Arrays.asList(name, name + "#2", name + "#3")));
                ours.put(o.gwId, o);
            } else {
                o.wanted = values;
                if (o.live) {
                    send(o);
                }
            }
        }
    }

    /** The v3 IFFData class of a mode for a v2 system class (transponder, interrogator, or the mode class). */
    static String v3Class(String mode, String v2Class) {
        if (mode.equals("RRB")) {
            return V3_DATA + ".RRB";
        }
        String role = v2Class.startsWith(V2_TRANSPONDER) ? "Transponder" : v2Class.startsWith(V2_INTERROGATOR) ? "Interrogator" : "";
        return V3_DATA + "." + mode + (role.isEmpty() ? "" : "." + mode + role);
    }

    /** v3 mode name -> its v3 attribute values, for every mode the v2 system has. */
    static Map<String, Map<String, HValue>> modesOf(V2System sys) {
        return modesOf(sys.className, sys.values);
    }

    static Map<String, Map<String, HValue>> modesOf(String v2Class, Map<String, HValue> v) {
        Map<String, Map<String, HValue>> out = new LinkedHashMap<String, Map<String, HValue>>();
        if (v2Class.startsWith(V2_RRB)) {
            Map<String, HValue> a = new LinkedHashMap<String, HValue>();
            copy(v, "IsOn", a, "IsOn");
            copy(v, "IsDamaged", a, "IsDamaged");
            copy(v, "IsMalfunctioning", a, "IsMalfunctioning");
            copy(v, "Code", a, "Code");
            copy(v, "PowerReduction", a, "PowerReductionIsOn");
            copy(v, "RadarEnhancement", a, "RadarEnhancementIsOn");
            if (!a.isEmpty()) out.put("RRB", a);
            return out;
        }
        boolean transponder = v2Class.startsWith(V2_TRANSPONDER);
        boolean interrogator = v2Class.startsWith(V2_INTERROGATOR);
        for (String[] mm : MODES) {
            String p = mm[0], mode = mm[1];
            HValue enabled = v.get(p + "Enabled");
            boolean present = enabled != null ? truth(enabled) : truth(v.get(p + "IsOn"));
            if (!present) {
                continue;
            }
            Map<String, HValue> a = new LinkedHashMap<String, HValue>();
            copy(v, p + "IsOn", a, "IsOn");
            copy(v, p + "IsDamaged", a, "IsDamaged");
            copy(v, p + "IsMalfunctioning", a, "IsMalfunctioning");
            switch (mode) {
                case "Mode1":
                    if (transponder) {
                        copy(v, "Mode1Code", a, "Code");
                        a.put("ExtendedCodeIsSet", HValue.ofInt(0));
                    }
                    break;
                case "Mode2":
                case "Mode3A":
                    if (transponder) copy(v, p + "Code", a, "Code");
                    break;
                case "Mode4": {
                    copy(v, "Mode4PseudoCryptoAvailable", a, "PseudoCryptoCodeAAvailable");
                    copy(v, "Mode4PseudoCrypto", a, "PseudoCryptoCodeA");
                    HValue avail = v.get("Mode4PseudoCryptoAvailable");
                    a.put("PseudoCryptoCodeActive", HValue.ofInt(avail == null || truth(avail) ? 1 : 0));
                    if (transponder) copy(v, "AlternateMode4", a, "Reply");
                    if (interrogator) copy(v, "AlternateMode4", a, "Challenge");
                    break;
                }
                case "ModeC":
                    if (transponder) {
                        copy(v, "Mode5CAltitudeAvailable", a, "AltitudeAvailable");
                        HValue alt = v.get("Mode5CAltitude");
                        if (alt != null) {
                            long x = (short) alt.asLong();
                            Map<String, HValue> rec = new LinkedHashMap<String, HValue>();
                            rec.put("PositiveNegativeIndicator", HValue.ofInt(x < 0 ? 1 : 0));
                            rec.put("ModeCAltitude", HValue.ofInt(Math.min(Math.abs(x), 0x7FF)));
                            a.put("Altitude", HValue.ofRecord(rec));
                        }
                    }
                    break;
                default:
                    break;
            }
            out.put(mode, a);
        }
        return out;
    }

    private void send(ModeObject o) {
        InformationExchangeModel m = tools.get(o.d).model();
        HlaCodec codec = tools.get(o.d).codec();
        ObjectClass oc = m.getObjectClass(o.objectClass);
        List<Integer> ids = new ArrayList<Integer>();
        List<byte[]> vals = new ArrayList<byte[]>();
        for (Map.Entry<String, HValue> e : o.wanted.entrySet()) {
            if (Objects.equals(o.sent.get(e.getKey()), e.getValue())) {
                continue;
            }
            ClassAttribute a = oc.getAttribute(e.getKey());
            if (a == null) {
                continue;
            }
            try {
                vals.add(codec.encode(a.getAttribute().getDataType(), e.getValue()));
                ids.add(a.getId());
                o.sent.put(e.getKey(), e.getValue());
            } catch (RuntimeException x) {
                LOG.fine("IFF " + o.mode + "." + e.getKey() + ": " + x.getMessage());
            }
        }
        if (!ids.isEmpty()) {
            o.d.updateObject(o.key, toInts(ids), vals.toArray(new byte[0][]), null);
        }
    }

    private void delete(ModeObject o) {
        if (o == null) {
            return;
        }
        ours.remove(o.gwId);
        if (o.live && o.d.state().isUp()) {
            o.d.deleteObject(o.key, null);
        }
        o.live = false;
        o.failed = true;                                  // a late registration is deleted at once
    }

    // ================================================================== v3 -> v2

    /** Folds the IFFData objects of a v3 system (by name in s) into its v2 copies. */
    private void foldInto(Endpoint s, String systemName) {
        Long systemKey = router.bridgedKey(s, systemName);
        if (systemKey == null) {
            return;                                        // again when the system's copy is live (copied)
        }
        List<V3Mode> modes = new ArrayList<V3Mode>();
        Map<Long, V3Mode> all = v3Modes.get(s);
        if (all != null) {
            for (V3Mode x : all.values()) {
                if (systemName.equals(x.system)) modes.add(x);
            }
        }
        Map<String, HValue> v2 = v2ValuesOf(modes);
        for (Endpoint d : this.v2) {
            int cls = router.copyClass(s, systemKey, d);
            if (cls < 0) {
                continue;
            }
            InformationExchangeModel m = tools.get(d).model();
            HlaCodec codec = tools.get(d).codec();
            ObjectClass oc = m.getObjectClass(cls);
            List<Integer> ids = new ArrayList<Integer>();
            List<byte[]> vals = new ArrayList<byte[]>();
            for (Map.Entry<String, HValue> e : v2.entrySet()) {
                ClassAttribute a = oc.getAttribute(e.getKey());
                if (a == null) {
                    continue;                              // e.g. codes on an interrogator
                }
                try {
                    vals.add(codec.encode(a.getAttribute().getDataType(), e.getValue()));
                    ids.add(a.getId());
                } catch (RuntimeException x) {
                    LOG.fine("IFF " + e.getKey() + ": " + x.getMessage());
                }
            }
            if (!ids.isEmpty()) {
                router.updateCopy(s, systemKey, d, toInts(ids), vals.toArray(new byte[0][]));
            }
        }
    }

    /** The v2 mode attributes of a system from its v3 IFFData objects (modes without one: not enabled). */
    static Map<String, HValue> v2ValuesOf(List<V3Mode> modes) {
        Map<String, HValue> out = new LinkedHashMap<String, HValue>();
        Set<String> seen = new HashSet<String>();
        for (V3Mode x : modes) {
            seen.add(x.mode);
        }
        for (String[] mm : MODES) {
            String p = mm[0];
            if (!seen.contains(mm[1])) {
                out.put(p + "Enabled", HValue.ofInt(0));
                out.put(p + "IsOn", HValue.ofInt(0));
            }
        }
        for (V3Mode x : modes) {
            Map<String, HValue> v = x.values;
            if (x.mode.equals("RRB")) {
                copy(v, "IsOn", out, "IsOn");
                copy(v, "IsDamaged", out, "IsDamaged");
                copy(v, "IsMalfunctioning", out, "IsMalfunctioning");
                copy(v, "Code", out, "Code");
                copy(v, "PowerReductionIsOn", out, "PowerReduction");
                copy(v, "RadarEnhancementIsOn", out, "RadarEnhancement");
                continue;
            }
            String p = null;
            for (String[] mm : MODES) {
                if (mm[1].equals(x.mode)) p = mm[0];
            }
            if (p == null) {
                continue;                                  // Mode 5, TCAS, Soviet: no v2 attributes
            }
            out.put(p + "Enabled", HValue.ofInt(1));
            copy(v, "IsOn", out, p + "IsOn");
            copy(v, "IsDamaged", out, p + "IsDamaged");
            copy(v, "IsMalfunctioning", out, p + "IsMalfunctioning");
            switch (x.mode) {
                case "Mode1":
                case "Mode2":
                case "Mode3A":
                    copy(v, "Code", out, p + "Code");
                    break;
                case "Mode4":
                    copy(v, "PseudoCryptoCodeA", out, "Mode4PseudoCrypto");
                    copy(v, "PseudoCryptoCodeAAvailable", out, "Mode4PseudoCryptoAvailable");
                    copy(v, x.role.equals("Interrogator") ? "Challenge" : "Reply", out, "AlternateMode4");
                    break;
                case "ModeC": {
                    copy(v, "AltitudeAvailable", out, "Mode5CAltitudeAvailable");
                    HValue alt = v.get("Altitude");
                    if (alt != null) {
                        HValue ind = alt.field("PositiveNegativeIndicator");
                        HValue mag = alt.field("ModeCAltitude");
                        long a = mag == null ? 0 : mag.asLong() & 0x7FF;
                        out.put("Mode5CAltitude", HValue.ofInt(ind != null && ind.asLong() == 1 ? -a : a));
                    }
                    break;
                }
                default:
                    break;
            }
        }
        return out;
    }

    // ================================================================== helpers

    private void decodeInto(Endpoint s, int[] attributes, byte[][] values, Map<String, HValue> into) {
        ModelTools t = tools.get(s);
        for (int i = 0; i < attributes.length; i++) {
            Attribute a = t.model().getAttribute(attributes[i]);
            if (a == null || values[i] == null) {
                continue;
            }
            try {
                into.put(a.getName(), t.codec().decode(a.getDataType(), values[i]));
            } catch (RuntimeException x) {
                LOG.fine("IFF decode " + a.getName() + ": " + x.getMessage());
            }
        }
    }

    private static <T> T get(Map<Endpoint, Map<Long, T>> m, Endpoint e, long key) {
        Map<Long, T> x = m.get(e);
        return x == null ? null : x.get(key);
    }

    /** Copies a value under another name, as a plain number when it is one (enumerations matched by value). */
    private static void copy(Map<String, HValue> from, String name, Map<String, HValue> to, String as) {
        HValue v = from.get(name);
        if (v != null) {
            to.put(as, v.isNumeric() ? HValue.ofInt(v.asLong()) : v);
        }
    }

    private static boolean truth(HValue v) {
        return v != null && v.isNumeric() && v.asLong() != 0;
    }

    private static int[] toInts(List<Integer> l) {
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }
}
