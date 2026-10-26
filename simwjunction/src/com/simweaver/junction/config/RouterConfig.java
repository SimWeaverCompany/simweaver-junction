package com.simweaver.junction.config;

import com.simweaver.common.config.SimwProperties;
import com.simweaver.common.json.Json;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The router of a gateway, from a router kit ({@code etc/junctions/routers/*.router.settings})
 * or inline. Keys start with {@code router.} (Talk keys with {@code talk.}). Immutable.
 */
public final class RouterConfig implements Json.Writable {

    /** The router kinds. */
    public enum Kind {
        /** Moves (part of) an information model; RPR v2/v3 and DIS conversion. */
        IEM,
        /** Moves Talk channel traffic with channel ids remapped. */
        TALK,
        /** Passes DIS PDUs between DIS 6, DIS 7 and C-DIS endpoints as they are (0.9.18). */
        PDU
    }

    /** How the instance names of bridged objects are chosen in the other federations. */
    public enum NamePolicy {
        /** The same name; when taken, {@code name@endpoint}, then {@code name@endpoint#2}, ... */
        SAME_OR_SUFFIX,
        /** Always {@code <prefix><name>} (then numbered when taken). */
        PREFIX,
        /** The same name or nothing: the object is not bridged where the name is taken. */
        SAME_ONLY,
        /** Let the RTI choose (no reservation). */
        RTI
    }

    private final String id;
    private final String source;
    private final Kind kind;
    private final ClassFilter filter;
    private final boolean promote;
    private final NamePolicy namePolicy;
    private final String namePrefix;
    /** router.names.template[.endpoint]: scope "" = every destination; "" value = none (cancels the general one) */
    private final Map<String, String> nameTemplates;
    /** router.names.maxlength[.endpoint]: scope "" = every destination; 0 = no limit */
    private final Map<String, Integer> nameMaxLengths;
    private final int queueCapacity;
    private final String rprMapping;
    private final boolean deleteOnStop;
    private final boolean provideForward;
    private final int provideForwardIntervalMillis;
    private final List<String> warnings = new ArrayList<String>();
    private final List<String> errors = new ArrayList<String>();
    private final List<ValueRule> valueRules;
    // talk
    private final String talkMatch;
    private final List<String> talkChannels;
    private final boolean talkLobbyOnly;
    private final boolean talkMirrorClients;
    private final boolean talkCreateMissingChannels;
    private final boolean talkCreateMappedChannels;
    private final int talkCreateWaitMillis;
    private final Map<String, Long> talkRadioMap;
    private final String talkRadioEndpoint;
    private final int talkSampleRate;
    private final int talkHangTimeMillis;
    private final String talkRadioEntityId;
    private final Map<String, Map<String, String>> talkChannelMap;
    private final boolean talkMessagesOnly;
    private final List<String> talkMessageKinds;
    private final boolean talkTrackChannels;
    private final Map<String, Map<String, String>> talkChannelUuidMap;
    private final boolean talkPcmLittleEndian;
    private final String talkRprEncoding;
    private final int talkRprSampleRate;
    private final String raw;
    // pdu relay
    private final List<Integer> pduTypes;

    /**
     * @param id      kit id, or the gateway id for an inline router
     * @param p       properties with {@code router.*} / {@code talk.*} keys
     * @param source  file, for messages
     * @param baseDir folder for relative paths (the junctions folder)
     */
    public RouterConfig(String id, SimwProperties p, String source, File baseDir) {
        this.id = id;
        this.source = source;
        String k = p.getString("router.kind", "iem").trim().toLowerCase(Locale.ROOT);
        if (k.equals("talk") || k.equals("pitchtalk")) {
            kind = Kind.TALK;
        } else if (k.equals("pdu") || k.equals("pdu-relay")) {
            kind = Kind.PDU;
        } else {
            if (!k.equals("iem")) {
                warnings.add("router " + id + ": unknown router.kind '" + k + "' (iem, talk, pdu); using iem");
            }
            kind = Kind.IEM;
        }
        List<Integer> types = new ArrayList<Integer>();
        for (String t : p.getStringArray("router.pdu.types")) {
            String x = t.trim();
            if (x.isEmpty()) {
                continue;
            }
            try {
                int v = Integer.parseInt(x);
                if (v < 1 || v > 255) {
                    throw new NumberFormatException();
                }
                if (!types.contains(v)) {
                    types.add(v);
                }
            } catch (NumberFormatException e) {
                errors.add("router " + id + ": router.pdu.types: '" + x + "' is not a PDU type (1-255)");
            }
        }
        this.pduTypes = Collections.unmodifiableList(types);
        this.filter = ClassFilter.fromProperties(p, "router.");
        this.promote = p.getBoolean("router.promote", true);
        String pol = p.getString("router.names.policy", "same-or-suffix").trim();
        String polLower = pol.toLowerCase(Locale.ROOT);
        String prefix = "";
        NamePolicy np;
        if (polLower.startsWith("prefix:")) {
            np = NamePolicy.PREFIX;
            prefix = pol.substring("prefix:".length());
        } else if (polLower.equals("same-only")) {
            np = NamePolicy.SAME_ONLY;
        } else if (polLower.equals("rti")) {
            np = NamePolicy.RTI;
        } else {
            if (!polLower.equals("same-or-suffix")) {
                warnings.add("router " + id + ": unknown router.names.policy '" + pol + "'; using same-or-suffix");
            }
            np = NamePolicy.SAME_OR_SUFFIX;
        }
        this.namePolicy = np;
        this.namePrefix = prefix;
        Map<String, String> templates = new LinkedHashMap<String, String>();
        for (String key : p.getKeysWithPrefix("router.names.template")) {
            String scope = key.equals("router.names.template") ? "" : key.startsWith("router.names.template.") ? key.substring(22) : null;
            if (scope == null) {
                continue;
            }
            String t = p.getString(key, "").trim();
            for (String problem : com.simweaver.junction.router.CopyNames.check(t)) {
                errors.add("router " + id + ": " + key + ": " + problem);
            }
            if (!t.isEmpty() && !com.simweaver.junction.router.CopyNames.distinguishes(t)) {
                warnings.add("router " + id + ": " + key + " has neither {name} nor a GUID token: every copy gets the same name (then #2 .. #4)");
            }
            templates.put(scope, t);
        }
        this.nameTemplates = Collections.unmodifiableMap(templates);
        Map<String, Integer> lengths = new LinkedHashMap<String, Integer>();
        for (String key : p.getKeysWithPrefix("router.names.maxlength")) {
            String scope = key.equals("router.names.maxlength") ? "" : key.startsWith("router.names.maxlength.") ? key.substring(23) : null;
            if (scope == null) {
                continue;
            }
            String v = p.getString(key, "").trim();
            try {
                int n = v.isEmpty() ? 0 : Integer.parseInt(v);
                if (n < 0) {
                    throw new NumberFormatException();
                }
                lengths.put(scope, n);
            } catch (NumberFormatException e) {
                errors.add("router " + id + ": " + key + ": '" + v + "' is not a length (a whole number; 0 = no limit)");
            }
        }
        this.nameMaxLengths = Collections.unmodifiableMap(lengths);
        if (namePolicy == NamePolicy.RTI && !templates.isEmpty()) {
            warnings.add("router " + id + ": router.names.template is used instead of router.names.policy=rti where it applies");
        }
        this.queueCapacity = Math.max(1000, p.getInt("router.queue.capacity", 50000));
        String mapping = p.getString("router.rpr.mapping", "mappings/rpr2-rpr3.mapping");
        File mf = new File(mapping);
        if (!mf.isAbsolute() && baseDir != null) {
            mf = new File(baseDir, mapping);
        }
        this.rprMapping = mf.getPath();
        this.deleteOnStop = p.getBoolean("router.deleteobjectsonstop", true);
        // 0.9.19: a provide request the cache cannot answer is asked of the object's origin
        this.provideForward = p.getBoolean("router.provide.forward", true);
        int fi = p.getInt("router.provide.forwardintervalms", 1000);
        if (fi < 0 || fi > 3_600_000) {
            errors.add("router " + id + ": router.provide.forwardintervalms must be 0 - 3600000 (ms)");
            fi = 1000;
        }
        this.provideForwardIntervalMillis = fi;
        List<String> vkeys = new ArrayList<String>(Arrays.asList(p.getKeysWithPrefix(ValueRule.PREFIX)));
        Collections.sort(vkeys);
        Map<String, String> vvals = new LinkedHashMap<String, String>();
        for (String key : vkeys) {
            vvals.put(key, p.getString(key, ""));
        }
        List<String> verr = new ArrayList<String>();
        this.valueRules = Collections.unmodifiableList(ValueRule.parseAll(vkeys, vvals, verr));
        for (String e : verr) {
            errors.add("router " + id + ": " + e);
        }

        this.talkMatch = p.getString("talk.match", "name").trim().toLowerCase(Locale.ROOT);
        List<String> ch = new ArrayList<String>();
        for (String c : p.getStringArray("talk.channels")) {
            if (!c.trim().isEmpty()) {
                ch.add(c.trim());
            }
        }
        this.talkChannels = Collections.unmodifiableList(ch.isEmpty() ? Collections.singletonList("*") : ch);
        // 0.9.16: talk.channels=none - the lobby only: every other channel's messages are dropped, seen or not
        boolean none = false;
        for (String c : ch) {
            none |= c.equalsIgnoreCase("none");
        }
        if (none && ch.size() > 1) {
            warnings.add("router " + id + ": talk.channels: 'none' (the lobby only) stands alone; the other entries are ignored");
        }
        this.talkLobbyOnly = none;
        this.talkMirrorClients = p.getBoolean("talk.mirrorclients", true);
        this.talkCreateMissingChannels = p.getBoolean("talk.createmissingchannels", false);
        this.talkCreateMappedChannels = p.getBoolean("talk.createmappedchannels", true);
        this.talkCreateWaitMillis = Math.max(0, p.getInt("talk.createwaitms", 3000));
        Map<String, Long> radio = new LinkedHashMap<String, Long>();
        for (String key : p.getKeysWithPrefix("talk.radio.map.")) {
            String channel = key.substring("talk.radio.map.".length());
            try {
                radio.put(channel, Long.parseLong(p.getString(key).trim()));
            } catch (NumberFormatException e) {
                warnings.add("router " + id + ": " + key + " must be a frequency in Hz");
            }
        }
        this.talkRadioMap = Collections.unmodifiableMap(radio);
        this.talkRadioEndpoint = p.getString("talk.radio.endpoint", "");
        this.talkSampleRate = p.getInt("talk.audio.samplerate", 16000);
        this.talkHangTimeMillis = p.getInt("talk.radio.hangtimems", 500);
        Map<String, Map<String, String>> cmap = new LinkedHashMap<String, Map<String, String>>();
        for (String key : p.getKeysWithPrefix("talk.channelmap")) {
            String scope = key.equals("talk.channelmap") ? "" : key.startsWith("talk.channelmap.") ? key.substring(16) : null;
            if (scope == null) {
                continue;
            }
            Map<String, String> pairs = new LinkedHashMap<String, String>();
            for (String pair : p.getString(key, "").split(";")) {
                if (pair.trim().isEmpty()) {
                    continue;
                }
                int gt = pair.indexOf('>');
                if (gt <= 0 || gt == pair.length() - 1) {
                    warnings.add("router " + id + ": " + key + ": '" + pair.trim() + "' is not <name>><name at the receiver>");
                    continue;
                }
                pairs.put(pair.substring(0, gt).trim(), pair.substring(gt + 1).trim());
            }
            cmap.put(scope, Collections.unmodifiableMap(pairs));
        }
        this.talkChannelMap = Collections.unmodifiableMap(cmap);
        String mode = p.getString("talk.mode", "full").trim().toLowerCase(Locale.ROOT);
        if (!mode.equals("full") && !mode.equals("messages")) {
            warnings.add("router " + id + ": talk.mode must be full or messages; using full");
            mode = "full";
        }
        this.talkMessagesOnly = mode.equals("messages");
        this.talkTrackChannels = p.getBoolean("talk.trackchannels", true);
        List<String> kinds = new ArrayList<String>();
        for (String kind : p.getStringArray("talk.messages")) {
            String x = kind.trim().toLowerCase(Locale.ROOT);
            if (x.isEmpty()) {
                continue;
            }
            if (Arrays.asList("audio", "radio", "text", "image").contains(x)) {
                kinds.add(x);
            } else {
                warnings.add("router " + id + ": talk.messages: '" + kind.trim() + "' is not audio, radio, text or image");
            }
        }
        if (kinds.isEmpty()) {
            kinds = talkMessagesOnly ? Arrays.asList("audio", "radio", "text") : Arrays.asList("audio", "radio", "text", "image");
        }
        this.talkMessageKinds = Collections.unmodifiableList(kinds);
        Map<String, Map<String, String>> umap = new LinkedHashMap<String, Map<String, String>>();
        for (String key : p.getKeysWithPrefix("talk.channeluuidmap")) {
            String scope = key.equals("talk.channeluuidmap") ? "" : key.startsWith("talk.channeluuidmap.") ? key.substring(20) : null;
            if (scope == null) {
                continue;
            }
            Map<String, String> pairs = new LinkedHashMap<String, String>();
            for (String pair : p.getString(key, "").split(";")) {
                if (pair.trim().isEmpty()) {
                    continue;
                }
                int gt = pair.indexOf('>');
                String a = gt < 0 ? null : uuidHex(pair.substring(0, gt));
                String b = gt < 0 ? null : uuidHex(pair.substring(gt + 1));
                if (a == null || b == null) {
                    warnings.add("router " + id + ": " + key + ": '" + pair.trim() + "' is not <uuid>><uuid at the receiver>");
                    continue;
                }
                pairs.put(a, b);
            }
            umap.put(scope, Collections.unmodifiableMap(pairs));
        }
        this.talkChannelUuidMap = Collections.unmodifiableMap(umap);
        if (talkLobbyOnly && (!talkChannelMap.isEmpty() || !talkRadioMap.isEmpty() || !umap.isEmpty())) {
            warnings.add("router " + id + ": talk.channels=none passes the lobby only: talk.channelmap, talk.channeluuidmap and"
                    + " talk.radio.map are not used");
        }
        this.talkRadioEntityId = p.getString("talk.radio.entityid", "900-99-1").trim();
        if (!talkRadioEntityId.matches("\\d{1,5}-\\d{1,5}-\\d{1,5}")) {
            warnings.add("router " + id + ": talk.radio.entityid must be site-application-entity, e.g. 900-99-1");
        }
        String order = p.getString("talk.audio.pcmbyteorder", "little").trim().toLowerCase(Locale.ROOT);
        this.talkPcmLittleEndian = !order.startsWith("big");
        String enc = p.getString("talk.audio.rprencoding", "pcm16be").trim().toLowerCase(Locale.ROOT);
        if (!enc.equals("pcm16be") && !enc.equals("pcm16le") && !enc.equals("mulaw")) {
            warnings.add("router " + id + ": talk.audio.rprencoding must be pcm16be, pcm16le or mulaw; using pcm16be");
            enc = "pcm16be";
        }
        this.talkRprEncoding = enc;
        this.talkRprSampleRate = p.getInt("talk.audio.rprsamplerate", 0);
        StringBuilder sb = new StringBuilder();
        for (String key : p.getKeysSorted()) {
            if (key.startsWith("router.") || key.startsWith("talk.")) {
                sb.append(key).append('=').append(p.getString(key)).append('\n');
            }
        }
        this.raw = sb.toString();
    }

    public String getId() {
        return id;
    }

    public String getSource() {
        return source;
    }

    public Kind getKind() {
        return kind;
    }

    /** @return {@code router.pdu.types}: the PDU types a PDU relay passes; empty = all */
    public List<Integer> getPduTypes() {
        return pduTypes;
    }

    /** @return the router's class filter */
    public ClassFilter getFilter() {
        return filter;
    }

    /** @return bridge instances of a blocked class as their nearest allowed superclass */
    public boolean isPromote() {
        return promote;
    }

    public NamePolicy getNamePolicy() {
        return namePolicy;
    }

    public String getNamePrefix() {
        return namePrefix;
    }

    /**
     * @param destination the endpoint the copy is registered in
     * @return the name template for copies in that endpoint ({@code router.names.template.<endpoint>},
     *         else {@code router.names.template}), or null: the name policy applies
     */
    public String getNameTemplate(String destination) {
        String t = nameTemplates.containsKey(destination) ? nameTemplates.get(destination) : nameTemplates.get("");
        return t == null || t.isEmpty() ? null : t;
    }

    /** @return the longest name of a copy in that endpoint, 0 = no limit */
    public int getNameMaxLength(String destination) {
        Integer n = nameMaxLengths.containsKey(destination) ? nameMaxLengths.get(destination) : nameMaxLengths.get("");
        return n == null ? 0 : n;
    }

    /** @return the endpoint ids that have a template or length of their own */
    public java.util.Set<String> getNameScopes() {
        java.util.Set<String> s = new java.util.TreeSet<String>(nameTemplates.keySet());
        s.addAll(nameMaxLengths.keySet());
        s.remove("");
        return s;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    /** @return the RPR v2/v3 mapping file */
    public String getRprMapping() {
        return rprMapping;
    }

    /** @return delete the bridged objects in the destinations when the gateway stops */
    public boolean isDeleteOnStop() {
        return deleteOnStop;
    }

    /**
     * @return {@code router.provide.forward}: a provide request for attributes the gateway has no
     *         value of yet is passed on to the object's origin as a request
     */
    public boolean isProvideForward() {
        return provideForward;
    }

    /** @return {@code router.provide.forwardintervalms}: the same attribute of an object is asked for at most this often */
    public int getProvideForwardIntervalMillis() {
        return provideForwardIntervalMillis;
    }

    /** @return settings that were not understood (defaults used instead) */
    public List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }

    /** @return what prevents starting a gateway with this router (value rules that cannot be read) */
    public List<String> getErrors() {
        return Collections.unmodifiableList(errors);
    }

    /** @return the value rules ({@code router.valuemap.*}), in key order */
    public List<ValueRule> getValueRules() {
        return valueRules;
    }

    /** @return "name" or "description": how Talk channels are matched */
    public String getTalkMatch() {
        return talkMatch;
    }

    /** @return channel names to bridge, "*" = all */
    public List<String> getTalkChannels() {
        return talkChannels;
    }

    /** @return true for {@code talk.channels=none}: only the lobby's messages pass, no channel is bridged or created */
    public boolean isTalkLobbyOnly() {
        return talkLobbyOnly;
    }

    public boolean isTalkMirrorClients() {
        return talkMirrorClients;
    }

    public boolean isTalkCreateMissingChannels() {
        return talkCreateMissingChannels;
    }

    /**
     * @return {@code talk.createmappedchannels} (default true): a channel that {@code talk.channelmap}
     *         maps to a name the receiving federation lacks is registered there under that name
     */
    public boolean isTalkCreateMappedChannels() {
        return talkCreateMappedChannels;
    }

    /**
     * @return {@code talk.createwaitms} (default 3000): how long the gateway watches a Talk federation
     *         for its own channels, after joining, before it creates one there
     */
    public int getTalkCreateWaitMillis() {
        return talkCreateWaitMillis;
    }

    /** @return voice channel name to RPR frequency (Hz) */
    public Map<String, Long> getTalkRadioMap() {
        return talkRadioMap;
    }

    /** @return the endpoint that gets the RPR radio traffic of the Talk channels, "" = none */
    public String getTalkRadioEndpoint() {
        return talkRadioEndpoint;
    }

    public int getTalkSampleRate() {
        return talkSampleRate;
    }

    public int getTalkHangTimeMillis() {
        return talkHangTimeMillis;
    }

    /**
     * {@code talk.channelmap=A>B; C>D}: channel A on one side is channel B on the other (both
     * ways); {@code talk.channelmap.<endpoint>=A>B}: when sending into that endpoint, channel A
     * is its channel B (and B is A on the way out of it).
     *
     * @return scope ("" = every endpoint, else an endpoint id) to name pairs
     */
    public Map<String, Map<String, String>> getTalkChannelMap() {
        return talkChannelMap;
    }

    /**
     * @return {@code talk.mode=messages}: the Talk endpoints only exchange Message interactions;
     *         no channels, clients, users, equipment or configuration objects are subscribed,
     *         mirrored or registered, and channel UUIDs pass unchanged unless
     *         {@code talk.channeluuidmap} maps them
     */
    public boolean isTalkMessagesOnly() {
        return talkMessagesOnly;
    }

    /**
     * @return {@code talk.trackchannels} (messages mode, default true): the channel objects of each
     *         Talk endpoint are watched read-only (UUID, name, description, frequency) so channels
     *         are matched by name and {@code talk.channelmap} applies; nothing is mirrored or
     *         created. False: no channel objects at all, UUIDs pass unchanged or by
     *         {@code talk.channeluuidmap}
     */
    public boolean isTalkTrackChannels() {
        return talkTrackChannels;
    }

    /** @return {@code talk.messages}: the message kinds bridged (audio, radio, text, image) */
    public List<String> getTalkMessageKinds() {
        return talkMessageKinds;
    }

    /**
     * {@code talk.channeluuidmap=<uuid>><uuid>; ...} (both ways) and
     * {@code talk.channeluuidmap.<endpoint>=...} (into that endpoint): channel UUIDs mapped
     * without channel tables (messages mode).
     *
     * @return scope ("" = every endpoint) to pairs of 32-digit lower-case hex UUIDs
     */
    public Map<String, Map<String, String>> getTalkChannelUuidMap() {
        return talkChannelUuidMap;
    }

    /**
     * @param text a UUID as 32 hex digits, or in the 8-4-4-4-12 form
     * @return 32 lower-case hex digits, or null when it is not a UUID
     */
    public static String uuidHex(String text) {
        if (text == null) {
            return null;
        }
        String h = text.trim().replace("-", "").toLowerCase(Locale.ROOT);
        return h.matches("[0-9a-f]{32}") ? h : null;
    }

    /** @return {@code talk.radio.entityid}: the DIS entity id that hosts the gateway's radios ("900-99-1") */
    public String getTalkRadioEntityId() {
        return talkRadioEntityId;
    }

    /** @return {@code talk.audio.pcmbyteorder}: true (default) when Talk's PCM16 is little endian */
    public boolean isTalkPcmLittleEndian() {
        return talkPcmLittleEndian;
    }

    /** @return {@code talk.audio.rprencoding}: pcm16be (default), pcm16le or mulaw, for Talk audio sent as RPR */
    public String getTalkRprEncoding() {
        return talkRprEncoding;
    }

    /** @return {@code talk.audio.rprsamplerate}: the sample rate of Talk audio sent as RPR (0 = Talk's) */
    public int getTalkRprSampleRate() {
        return talkRprSampleRate;
    }

    /** @return the router settings as text, for the UI */
    public String getRaw() {
        return raw;
    }

    @Override
    public Object toJson() {
        Json.JsonObject o = Json.object();
        o.put("id", id);
        o.put("source", source);
        o.put("kind", kind.name().toLowerCase(Locale.ROOT));
        o.put("filter", filter);
        o.put("filtertext", filter.toString());
        o.put("promote", promote);
        o.put("namepolicy", namePolicy.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        if (!nameTemplates.isEmpty()) {
            o.put("nametemplates", new LinkedHashMap<String, Object>(nameTemplates));
        }
        if (!nameMaxLengths.isEmpty()) {
            o.put("namemaxlengths", new LinkedHashMap<String, Object>(nameMaxLengths));
        }
        o.put("valuemap", valueRules);
        if (kind != Kind.PDU) {
            o.put("provideforward", provideForward);
            o.put("provideforwardintervalms", provideForwardIntervalMillis);
        }
        if (kind == Kind.PDU) {
            o.put("pdutypes", pduTypes);
        }
        if (kind == Kind.TALK) {
            o.put("talkmatch", talkMatch);
            o.put("talkchannels", talkChannels);
            o.put("talklobbyonly", talkLobbyOnly);
            o.put("talkmirrorclients", talkMirrorClients);
            o.put("talkradiomap", new LinkedHashMap<String, Object>(talkRadioMap));
            o.put("talkradioendpoint", talkRadioEndpoint);
            o.put("talkradioentityid", talkRadioEntityId);
            o.put("talkchannelmap", new LinkedHashMap<String, Object>(talkChannelMap));
            o.put("talkmode", talkMessagesOnly ? "messages" : "full");
            o.put("talkmessages", talkMessageKinds);
            o.put("talktrackchannels", talkTrackChannels);
            o.put("talkchanneluuidmap", new LinkedHashMap<String, Object>(talkChannelUuidMap));
            o.put("talkcreatemissingchannels", talkCreateMissingChannels);
            o.put("talkcreatemappedchannels", talkCreateMappedChannels);
            o.put("talksamplerate", talkSampleRate);
        }
        o.put("settings", raw);
        o.put("warnings", warnings);
        o.put("errors", errors);
        return o;
    }
}
