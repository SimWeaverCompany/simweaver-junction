package com.simweaver.junction.router.talk;

import com.simweaver.common.hla.codec.HValue;
import com.simweaver.common.hla.codec.HlaCodec;
import com.simweaver.common.iem.Attribute;
import com.simweaver.common.iem.ClassAttribute;
import com.simweaver.common.iem.ClassParameter;
import com.simweaver.common.iem.EnumeratedDataType;
import com.simweaver.common.iem.Enumerator;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.common.iem.InteractionClass;
import com.simweaver.common.iem.ObjectClass;
import com.simweaver.common.iem.Parameter;
import com.simweaver.common.json.Json;
import com.simweaver.junction.config.EndpointType;
import com.simweaver.junction.config.RouterConfig;
import com.simweaver.junction.endpoint.Endpoint;
import com.simweaver.junction.endpoint.EndpointState;
import com.simweaver.junction.endpoint.Interest;
import com.simweaver.junction.endpoint.NameCandidates;
import com.simweaver.junction.router.IemRouter;
import com.simweaver.junction.router.ModelTools;

import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * The Pitch Talk part of the Talk router (design §9), as an {@link IemRouter.Extension}: the
 * {@code IemRouter} mirrors the Talk clients, users and equipment and routes the
 * {@code PitchTalk.Message} interactions; this class
 *
 * <ul>
 *   <li>keeps a channel table per Talk endpoint (UUID, name, description, class) from the
 *       discovered {@code PitchTalk.Channel} objects, which are not mirrored;</li>
 *   <li>rewrites {@code ChannelUUID} of every routed message to the channel of the same name
 *       (or description, {@code talk.match}) in the destination; the lobby (all zeros) stays the
 *       lobby; a message for a channel without a counterpart is dropped and counted, or, with
 *       {@code talk.createmissingchannels}, the channel is created there (the messages before it
 *       exists are dropped); radio channels without a counterpart keep their UUID, for the
 *       clients to tune by frequency;</li>
 *   <li>registers, with {@code talk.createmappedchannels} (the default), every channel that
 *       {@code talk.channelmap} maps to a name a receiving federation lacks, under that name, as
 *       soon as the source channel is known and the receiver has been watched for its own
 *       channels for {@code talk.createwaitms}; such a channel is deleted again when the
 *       federation gets a channel of that name of its own;</li>
 *   <li>bridges audio of the channels in {@code talk.radio.map.<channel>=<Hz>} to and from the
 *       RPR endpoint {@code talk.radio.endpoint}: every Talk sender on a mapped channel becomes
 *       a RadioTransmitter (host {@code talk.radio.entityid}) and its audio
 *       EncodedAudioRadioSignals; RPR audio on a mapped frequency is sent into the channel
 *       from a Client object of the gateway. PCM16 and G.711 mu-law are converted (byte order,
 *       sample rate); Opus is passed through when the RPR FOM has an Opus encoding.</li>
 * </ul>
 *
 * With {@code talk.mode=messages} nothing is mirrored and the radio bridge's "client" is a bare
 * sender UUID. With {@code talk.trackchannels} (the default) the channel objects are still
 * watched, so the channel table exists and ChannelUUIDs are rewritten by name as above; the only
 * objects registered are the mapped channels a federation lacks ({@code talk.createmappedchannels}).
 * A channel not seen (yet) keeps its UUID.
 * {@code talk.channeluuidmap} pairs are applied first in either case.
 *
 * All methods run on the router's dispatcher thread.
 */
final class TalkBridge implements IemRouter.Extension {

    private static final Logger LOG = Logger.getLogger(TalkBridge.class.getName());

    static final String CHANNEL = "HLAobjectRoot.PitchTalk.Channel";
    static final String AUDIO_CHANNEL = CHANNEL + ".AudioChannel";
    static final String RADIO_CHANNEL = AUDIO_CHANNEL + ".RadioChannel";
    static final String VOICE_CHANNEL = AUDIO_CHANNEL + ".VoiceChannel";
    static final String CHAT_CHANNEL = CHANNEL + ".ChatChannel";
    static final String CLIENT = "HLAobjectRoot.PitchTalk.Client";
    static final String MESSAGE = "HLAinteractionRoot.PitchTalk.Message";
    static final String AUDIO = MESSAGE + ".Audio";
    static final String RADIO = AUDIO + ".Radio";
    static final String TRANSMITTER = "HLAobjectRoot.EmbeddedSystem.RadioTransmitter";
    static final String SIGNAL = "HLAinteractionRoot.RadioSignal.EncodedAudioRadioSignal";

    /** Most specific first: an RTI discovers with the classes subscribed so far. */
    private static final String[] CHANNEL_CLASSES = {RADIO_CHANNEL, VOICE_CHANNEL, CHAT_CHANNEL, AUDIO_CHANNEL, CHANNEL};
    private static final String[] CLIENT_ATTRIBUTES = {"UUID", "Id", "Hostname", "LoginAccountName", "MasterConfiguredName",
        "Status", "CanListPeerClients"};
    private static final String[] TX_ATTRIBUTES = {"EntityIdentifier", "HostObjectIdentifier", "RadioIndex", "Frequency",
        "FrequencyBandwidth", "StreamTag", "TransmitterOperationalStatus", "RadioSystemType", "RadioInputSource"};
    private static final byte[] LOBBY = new byte[16];
    private static final int AUDIO_CODEC_OPUS = 1;
    private static final int AUDIO_CODEC_PCM16 = 2;
    private static final int RPR_PCM16_BE = 4;
    private static final int RPR_PCM16_LE = 100;
    private static final int RPR_MULAW = 1;
    private static final int TX_ON_NOT_TRANSMITTING = 1;
    private static final int TX_TRANSMITTING = 2;
    private static final int MAX_BUFFERED_FRAMES = 100;

    private final RouterConfig cfg;
    private final String gatewayId;
    private final SecureRandom random = new SecureRandom();
    private final List<Pattern> channelFilter = new ArrayList<Pattern>();
    private final boolean lobbyOnly;
    private final Map<String, Long> radioMap = new LinkedHashMap<String, Long>();     // lower-case channel name -> Hz

    private final boolean messagesOnly;
    /** Messages mode with talk.trackchannels: channel objects watched read-only for name matching. */
    private final boolean tracking;
    /** talk.createmappedchannels with a talk.channelmap (and channel tables). */
    private final boolean createMapped;
    private long lastMappedCheck;
    private IemRouter router;
    private final Map<Endpoint, TalkSide> talk = new IdentityHashMap<Endpoint, TalkSide>();
    private RadioSide radio;

    // counters (read by the page from any thread)
    final AtomicLong messagesRouted = new AtomicLong();
    final AtomicLong droppedNoCounterpart = new AtomicLong();
    final AtomicLong droppedFiltered = new AtomicLong();
    final AtomicLong droppedUnknownChannel = new AtomicLong();
    final AtomicLong passedUnknownChannel = new AtomicLong();
    final AtomicLong channelsCreated = new AtomicLong();
    final AtomicLong framesToRpr = new AtomicLong();
    final AtomicLong framesToTalk = new AtomicLong();
    final AtomicLong framesDropped = new AtomicLong();
    private final Map<String, Boolean> warned = new HashMap<String, Boolean>();

    TalkBridge(RouterConfig cfg, String gatewayId) {
        this.cfg = cfg;
        this.gatewayId = gatewayId;
        for (String c : cfg.getTalkChannels()) {
            channelFilter.add(Pattern.compile(glob(c), Pattern.CASE_INSENSITIVE));
        }
        this.lobbyOnly = cfg.isTalkLobbyOnly();
        for (Map.Entry<String, Long> e : lobbyOnly ? java.util.Collections.<String, Long>emptyMap().entrySet()
                : cfg.getTalkRadioMap().entrySet()) {                  // talk.channels=none: no radio channel either
            String uuid = RouterConfig.uuidHex(e.getKey());          // a channel UUID instead of a name
            radioMap.put(uuid != null ? uuid : e.getKey().trim().toLowerCase(Locale.ROOT), e.getValue());
        }
        this.messagesOnly = cfg.isTalkMessagesOnly();
        this.tracking = messagesOnly && cfg.isTalkTrackChannels();
        boolean anyMap = false;
        for (Map<String, String> pairs : cfg.getTalkChannelMap().values()) {
            anyMap |= !pairs.isEmpty();
        }
        this.createMapped = cfg.isTalkCreateMappedChannels() && anyMap && (!messagesOnly || tracking);
    }

    private static String glob(String g) {
        StringBuilder sb = new StringBuilder();
        for (char ch : g.toCharArray()) {
            if (ch == '*') {
                sb.append(".*");
            } else if (ch == '?') {
                sb.append('.');
            } else {
                sb.append(Pattern.quote(String.valueOf(ch)));
            }
        }
        return sb.toString();
    }

    private boolean radioBridge() {
        return !radioMap.isEmpty() && !cfg.getTalkRadioEndpoint().isEmpty();
    }

    // ================================================================== model helpers

    /** One endpoint's model, as names. */
    private static class Side {
        final Endpoint e;
        final InformationExchangeModel m;
        final HlaCodec codec;

        Side(Endpoint e, ModelTools t) {
            this.e = e;
            this.m = t.model();
            this.codec = t.codec();
        }

        int objectClass(String n) {
            ObjectClass c = m.getObjectClass(n);
            return c == null ? 0 : c.getId();
        }

        int interactionClass(String n) {
            InteractionClass c = m.getInteractionClass(n);
            return c == null ? 0 : c.getId();
        }

        List<Integer> attributes(String cls, String... names) {
            List<Integer> l = new ArrayList<Integer>();
            ObjectClass c = m.getObjectClass(cls);
            if (c != null) {
                for (String n : names) {
                    ClassAttribute a = c.getAttribute(n);
                    if (a != null) {
                        l.add(a.getId());
                    }
                }
            }
            return l;
        }

        String objectClassName(int id) {
            ObjectClass c = m.getObjectClass(id);
            return c == null ? "" : c.getQualifiedName();
        }

        String interactionClassName(int id) {
            InteractionClass c = m.getInteractionClass(id);
            return c == null ? "" : c.getQualifiedName();
        }

        /** Encodes the named values the class has; returns {ids, values}. */
        Object[] encodeAttributes(String cls, Map<String, HValue> values) {
            ObjectClass c = m.getObjectClass(cls);
            List<Integer> ids = new ArrayList<Integer>();
            List<byte[]> vals = new ArrayList<byte[]>();
            for (Map.Entry<String, HValue> e : values.entrySet()) {
                ClassAttribute a = c == null ? null : c.getAttribute(e.getKey());
                if (a != null && a.getDataType() != null) {
                    ids.add(a.getId());
                    vals.add(codec.encode(a.getDataType(), e.getValue()));
                }
            }
            return new Object[] {toInts(ids), vals.toArray(new byte[0][])};
        }

        Object[] encodeParameters(String cls, Map<String, Object> values) {
            InteractionClass c = m.getInteractionClass(cls);
            List<Integer> ids = new ArrayList<Integer>();
            List<byte[]> vals = new ArrayList<byte[]>();
            for (Map.Entry<String, Object> e : values.entrySet()) {
                ClassParameter p = c == null ? null : c.getParameter(e.getKey());
                if (p != null && p.getDataType() != null) {
                    ids.add(p.getId());
                    Object v = e.getValue();
                    vals.add(v instanceof byte[] ? (byte[]) v : codec.encode(p.getDataType(), (HValue) v));
                }
            }
            return new Object[] {toInts(ids), vals.toArray(new byte[0][])};
        }

        /** Raw values by member name. */
        Map<String, byte[]> byName(int[] ids, byte[][] values, boolean attributes) {
            Map<String, byte[]> out = new HashMap<String, byte[]>();
            for (int i = 0; i < ids.length; i++) {
                String n;
                if (attributes) {
                    Attribute a = m.getAttribute(ids[i]);
                    n = a == null ? null : a.getName();
                } else {
                    Parameter p = m.getParameter(ids[i]);
                    n = p == null ? null : p.getName();
                }
                if (n != null && values[i] != null) {
                    out.put(n, values[i]);
                }
            }
            return out;
        }

        HValue decodeAttribute(String cls, String name, byte[] v) {
            ClassAttribute a = m.getClassAttribute(cls, name);
            return a == null || v == null ? null : codec.decode(a.getDataType(), v);
        }

        HValue decodeParameter(String cls, String name, byte[] v) {
            ClassParameter p = m.getClassParameter(cls, name);
            return p == null || v == null ? null : codec.decode(p.getDataType(), v);
        }
    }

    private static int[] toInts(List<Integer> l) {
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = l.get(i);
        }
        return a;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(16, b.length); i++) {
            sb.append(String.format("%02x", b[i] & 0xFF));
        }
        return sb.toString();
    }

    private static boolean is(String cls, String base) {
        return cls.equals(base) || cls.startsWith(base + ".");
    }

    // ================================================================== Talk side

    /** A Talk channel, discovered or created by the gateway. */
    private static final class Channel {
        long key;
        String cls;
        byte[] uuid;
        String name = "";
        String description = "";
        long frequency;          // voice channel frequency, 0 = none
        boolean ours;
        long gwId;
        boolean live;
    }

    /** The gateway's own Talk Client object in one Talk endpoint. */
    private static final class OwnClient {
        long gwId;
        long key;
        boolean live;
        final byte[] uuid = new byte[16];
    }

    private final class TalkSide extends Side {
        final Map<Long, Channel> byKey = new HashMap<Long, Channel>();
        final Map<String, Channel> byUuid = new HashMap<String, Channel>();
        final Map<String, Channel> created = new HashMap<String, Channel>();      // match key -> ours
        OwnClient client;
        /** When the endpoint (re)joined; channels are created there only after talk.createwaitms. */
        long joinedAt;

        TalkSide(Endpoint e, ModelTools t) {
            super(e, t);
        }

        void clear() {
            byKey.clear();
            byUuid.clear();
            created.clear();
            client = null;
        }

        /** A channel with this match key (prefers the same class), or null. */
        Channel find(String key, String cls) {
            Channel any = null;
            for (Channel c : byKey.values()) {
                if (key.equals(matchKey(c))) {
                    if (c.cls.equals(cls)) {
                        return c;
                    }
                    any = any == null ? c : any;
                }
            }
            Channel ours = created.get(key);
            if (any == null && ours != null && ours.live) {
                any = ours;
            }
            return any;
        }

        /** A channel by name (radio map), or null. */
        Channel byName(String name) {
            for (Channel c : byKey.values()) {
                if (c.name.equalsIgnoreCase(name)) {
                    return c;
                }
            }
            return null;
        }
    }

    private String matchKey(Channel c) {
        String k = "description".equals(cfg.getTalkMatch()) ? c.description : c.name;
        return k == null ? "" : k.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The match key a channel of {@code s} has in {@code d}, after {@code talk.channelmap}: the
     * source endpoint's own pairs are undone first (its local name back to the common one), then
     * the destination's pairs, else the global pairs (either way round), are applied.
     *
     * @param key the channel's match text in {@code s} (any case)
     * @return the match text to look for in {@code d}, in the case configured
     */
    private String translate(Endpoint s, Endpoint d, String key) {
        Map<String, Map<String, String>> m = cfg.getTalkChannelMap();
        if (m.isEmpty() || key == null) {
            return key;
        }
        String n = key.trim();
        Map<String, String> sm = m.get(s.id());
        if (sm != null) {
            for (Map.Entry<String, String> e : sm.entrySet()) {
                if (e.getValue().equalsIgnoreCase(n)) {
                    n = e.getKey();
                    break;
                }
            }
        }
        Map<String, String> dm = m.get(d.id());
        if (dm != null) {
            for (Map.Entry<String, String> e : dm.entrySet()) {
                if (e.getKey().equalsIgnoreCase(n)) {
                    return e.getValue();
                }
            }
        }
        Map<String, String> g = m.get("");
        if (g != null) {
            for (Map.Entry<String, String> e : g.entrySet()) {
                if (e.getKey().equalsIgnoreCase(n)) {
                    return e.getValue();
                }
            }
            for (Map.Entry<String, String> e : g.entrySet()) {
                if (e.getValue().equalsIgnoreCase(n)) {
                    return e.getKey();
                }
            }
        }
        return n;
    }

    /** The name of a channel of {@code e} as the radio map knows it (its talk.channelmap.<e> pairs undone). */
    private String commonName(Endpoint e, String local) {
        Map<String, String> sm = cfg.getTalkChannelMap().get(e.id());
        if (sm != null && local != null) {
            for (Map.Entry<String, String> x : sm.entrySet()) {
                if (x.getValue().equalsIgnoreCase(local.trim())) {
                    return x.getKey();
                }
            }
        }
        return local;
    }

    /** The name a radio map channel has in {@code e} (its talk.channelmap.<e> pairs applied). */
    private String localName(Endpoint e, String common) {
        Map<String, String> sm = cfg.getTalkChannelMap().get(e.id());
        if (sm != null) {
            for (Map.Entry<String, String> x : sm.entrySet()) {
                if (x.getKey().equalsIgnoreCase(common.trim())) {
                    return x.getValue();
                }
            }
        }
        return common;
    }

    private static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private boolean allowed(Channel c) {
        if (lobbyOnly) {
            return false;                                    // talk.channels=none: no channel at all
        }
        for (Pattern p : channelFilter) {
            if (p.matcher(c.name).matches()) {
                return true;
            }
        }
        return false;
    }

    // ================================================================== radio side

    /** A RadioTransmitter heard on the RPR endpoint. */
    private static final class HeardTx {
        long frequency;
        long streamTag;
        boolean hasTag;
    }

    /** A RadioTransmitter the gateway sends for one Talk sender. */
    private static final class OwnTx {
        long gwId;
        long key;
        boolean live;
        boolean failed;
        int radioIndex;
        long frequency;
        long streamTag;
        int status;
        long lastAudio;
        final Deque<Map<String, HValue>> buffered = new ArrayDeque<Map<String, HValue>>();
    }

    private final class RadioSide extends Side {
        final Map<Long, HeardTx> heard = new HashMap<Long, HeardTx>();
        final Map<String, OwnTx> own = new HashMap<String, OwnTx>();           // talk endpoint|sender|Hz
        final Map<Long, OwnTx> ownByGwId = new HashMap<Long, OwnTx>();
        final int opusEncoding;
        int nextRadioIndex;

        RadioSide(Endpoint e, ModelTools t) {
            super(e, t);
            int opus = -1;
            ClassParameter p = m.getClassParameter(SIGNAL, "AudioData");
            if (p != null && p.getDataType() instanceof com.simweaver.common.iem.FixedRecordDataType) {
                for (com.simweaver.common.iem.RecordField f : ((com.simweaver.common.iem.FixedRecordDataType) p.getDataType()).getFields()) {
                    if ("EncodingType".equals(f.getName()) && f.getDataType() instanceof EnumeratedDataType) {
                        for (Enumerator en : ((EnumeratedDataType) f.getDataType()).getEnumerators()) {
                            if (en.getName().toLowerCase(Locale.ROOT).contains("opus")) {
                                opus = (int) en.getValue();
                            }
                        }
                    }
                }
            }
            this.opusEncoding = opus;
        }

        void clear() {
            heard.clear();
            own.clear();
            ownByGwId.clear();
        }

        HeardTx byStreamTag(long tag) {
            for (HeardTx t : heard.values()) {
                if (t.hasTag && t.streamTag == tag) {
                    return t;
                }
            }
            return null;
        }
    }

    // ================================================================== Extension: setup

    @Override
    public void declare(Endpoint e, ModelTools tools, Interest publish, Interest subscribe) {
        if (e.type() == EndpointType.TALK) {
            Side s = new Side(e, tools);
            if (messagesOnly) {
                // talk.mode=messages: the channel objects at most, read-only (talk.trackchannels),
                // and the radio bridge's messages; nothing published but those messages
                if (tracking) {
                    for (String cls : CHANNEL_CLASSES) {
                        int id = s.objectClass(cls);
                        if (id != 0) {
                            List<Integer> attrs = s.attributes(cls, "UUID", "Name", "Description", "ChannelFrequency");
                            subscribe.addAttributes(id, attrs);
                            if (createMapped) {
                                publish.addAttributes(id, attrs);              // only to register mapped channels
                            }
                        }
                    }
                }
                if (radioBridge()) {
                    for (String ic : new String[] {AUDIO, RADIO}) {
                        int id = s.interactionClass(ic);
                        if (id != 0) {
                            publish.addInteraction(id);
                            subscribe.addInteraction(id);
                        }
                    }
                }
                return;
            }
            for (String cls : CHANNEL_CLASSES) {
                int id = s.objectClass(cls);
                if (id == 0) {
                    continue;
                }
                List<Integer> attrs = s.attributes(cls, "UUID", "Name", "Description", "ChannelFrequency");
                subscribe.addAttributes(id, attrs);
                if (cfg.isTalkCreateMissingChannels() || createMapped) {
                    publish.addAttributes(id, attrs);
                }
            }
            if (radioBridge()) {
                int client = s.objectClass(CLIENT);
                if (client != 0) {
                    publish.addAttributes(client, s.attributes(CLIENT, CLIENT_ATTRIBUTES));
                }
                for (String ic : new String[] {AUDIO, RADIO}) {
                    int id = s.interactionClass(ic);
                    if (id != 0) {
                        publish.addInteraction(id);
                        subscribe.addInteraction(id);
                    }
                }
            }
        } else if (radioBridge() && e.id().equals(cfg.getTalkRadioEndpoint())) {
            Side s = new Side(e, tools);
            int tx = s.objectClass(TRANSMITTER);
            int sig = s.interactionClass(SIGNAL);
            if (tx == 0 || sig == 0) {
                LOG.warning("Junction " + gatewayId + ": radio endpoint " + e.id() + " has no " + TRANSMITTER + " / " + SIGNAL
                        + " (an RPR FOM is needed): no radio bridge");
                return;
            }
            publish.addAttributes(tx, s.attributes(TRANSMITTER, TX_ATTRIBUTES));
            publish.addAttributes(tx, s.attributes(TRANSMITTER, "WorldLocation"));
            subscribe.addAttributes(tx, s.attributes(TRANSMITTER, "Frequency", "StreamTag", "TransmitterOperationalStatus"));
            publish.addInteraction(sig);
            subscribe.addInteraction(sig);
        }
    }

    @Override
    public void started(IemRouter r, List<Endpoint> endpoints, Map<String, ModelTools> tools) {
        this.router = r;
        long now = System.currentTimeMillis();
        for (Endpoint e : endpoints) {
            if (e.type() == EndpointType.TALK) {
                TalkSide ts = new TalkSide(e, tools.get(e.id()));
                ts.joinedAt = now;
                talk.put(e, ts);
            } else if (radioBridge() && e.id().equals(cfg.getTalkRadioEndpoint())) {
                RadioSide rs = new RadioSide(e, tools.get(e.id()));
                if (rs.objectClass(TRANSMITTER) != 0 && rs.interactionClass(SIGNAL) != 0) {
                    radio = rs;
                }
            }
        }
        if (radioBridge() && radio == null) {
            warnOnce("noradio", "talk.radio.endpoint '" + cfg.getTalkRadioEndpoint() + "' is not an RPR endpoint of this gateway: no radio bridge");
        }
        if (radio != null) {
            for (TalkSide s : talk.values()) {
                createClient(s);
            }
        }
    }

    private void createClient(TalkSide s) {
        if (messagesOnly) {
            // no Client object in messages mode: a sender UUID of the gateway only
            OwnClient c = new OwnClient();
            random.nextBytes(c.uuid);
            c.live = true;
            c.gwId = 0;
            s.client = c;
            return;
        }
        int cls = s.objectClass(CLIENT);
        if (cls == 0) {
            return;
        }
        OwnClient c = new OwnClient();
        random.nextBytes(c.uuid);
        s.client = c;
        String base = "Junction-" + gatewayId + "-radio";
        c.gwId = router.createObject(s.e, cls, new NameCandidates(Arrays.asList(base, base + "#2", base + "#3")));
    }

    private void sendClient(TalkSide s) {
        OwnClient c = s.client;
        if (c == null || !c.live) {
            return;
        }
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "junction";
        }
        Map<String, HValue> v = new LinkedHashMap<String, HValue>();
        v.put("UUID", HValue.ofBytes(c.uuid));
        v.put("Id", HValue.ofText("Junction " + gatewayId + " radio"));
        v.put("Hostname", HValue.ofText(host));
        v.put("LoginAccountName", HValue.ofText("junction"));
        v.put("MasterConfiguredName", HValue.ofText("Junction " + gatewayId));
        v.put("Status", HValue.ofInt(1));                       // Online
        v.put("CanListPeerClients", HValue.ofInt(0));
        Object[] enc = s.encodeAttributes(CLIENT, v);
        s.e.updateObject(c.key, (int[]) enc[0], (byte[][]) enc[1], null);
    }

    // ================================================================== Extension: objects

    @Override
    public boolean discovered(Endpoint s, long key, int objectClass, String name) {
        TalkSide t = talk.get(s);
        if (t != null) {
            String cls = t.objectClassName(objectClass);
            if (is(cls, CHANNEL)) {
                Channel c = new Channel();
                c.key = key;
                c.cls = cls;
                t.byKey.put(key, c);
                return true;
            }
            return false;
        }
        if (radio != null && s == radio.e && is(radio.objectClassName(objectClass), TRANSMITTER)) {
            radio.heard.put(key, new HeardTx());
            return true;
        }
        return false;
    }

    @Override
    public boolean reflected(Endpoint s, long key, int[] attributes, byte[][] values) {
        TalkSide t = talk.get(s);
        if (t != null) {
            Channel c = t.byKey.get(key);
            if (c == null) {
                return false;
            }
            Map<String, byte[]> v = t.byName(attributes, values, true);
            if (v.containsKey("UUID")) {
                if (c.uuid != null) {
                    t.byUuid.remove(hex(c.uuid));
                }
                c.uuid = Arrays.copyOf(v.get("UUID"), 16);
                t.byUuid.put(hex(c.uuid), c);
            }
            if (v.containsKey("Name")) {
                c.name = t.decodeAttribute(c.cls, "Name", v.get("Name")).asTrimmedText();
            }
            if (v.containsKey("Description")) {
                c.description = t.decodeAttribute(c.cls, "Description", v.get("Description")).asTrimmedText();
            }
            if (v.containsKey("ChannelFrequency")) {
                HValue f = t.decodeAttribute(c.cls, "ChannelFrequency", v.get("ChannelFrequency"));
                c.frequency = f != null && f.field("Defined") != null && f.field("Defined").asLong() != 0
                        ? f.field("Frequency").asLong() : 0;
            }
            return true;
        }
        if (radio != null && s == radio.e) {
            HeardTx h = radio.heard.get(key);
            if (h == null) {
                return false;
            }
            Map<String, byte[]> v = radio.byName(attributes, values, true);
            if (v.containsKey("Frequency")) {
                h.frequency = radio.decodeAttribute(TRANSMITTER, "Frequency", v.get("Frequency")).asLong();
            }
            if (v.containsKey("StreamTag")) {
                h.streamTag = radio.decodeAttribute(TRANSMITTER, "StreamTag", v.get("StreamTag")).asLong();
                h.hasTag = true;
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean removed(Endpoint s, long key) {
        TalkSide t = talk.get(s);
        if (t != null) {
            Channel c = t.byKey.remove(key);
            if (c != null && c.uuid != null) {
                t.byUuid.remove(hex(c.uuid));
            }
            return c != null;
        }
        return radio != null && s == radio.e && radio.heard.remove(key) != null;
    }

    @Override
    public boolean registered(Endpoint d, long gwId, long key, String name) {
        if (gwId >= 0) {
            return false;
        }
        TalkSide t = talk.get(d);
        if (t != null) {
            if (t.client != null && t.client.gwId == gwId) {
                t.client.key = key;
                t.client.live = true;
                sendClient(t);
                return true;
            }
            for (Channel c : t.created.values()) {
                if (c.gwId == gwId) {
                    c.key = key;
                    c.live = true;
                    t.byUuid.put(hex(c.uuid), c);
                    sendChannel(t, c);
                    return true;
                }
            }
        }
        if (radio != null && d == radio.e) {
            OwnTx tx = radio.ownByGwId.get(gwId);
            if (tx != null) {
                tx.key = key;
                tx.live = true;
                sendTransmitter(tx, TX_TRANSMITTING);
                while (!tx.buffered.isEmpty()) {
                    sendSignal(tx, tx.buffered.poll());
                }
                return true;
            }
        }
        d.deleteObject(key, null);                       // nothing of ours any more
        return true;
    }

    @Override
    public boolean registrationFailed(Endpoint d, long gwId, String reason) {
        if (gwId >= 0) {
            return false;
        }
        warnOnce("regfail " + d.id(), "the gateway could not create an object in " + d.id() + ": " + reason);
        if (radio != null && d == radio.e) {
            OwnTx tx = radio.ownByGwId.get(gwId);
            if (tx != null) {
                tx.failed = true;
                tx.buffered.clear();
            }
        }
        return true;
    }

    @Override
    public boolean provide(Endpoint d, long key, int[] attributes) {
        TalkSide t = talk.get(d);
        if (t != null) {
            if (t.client != null && t.client.live && t.client.key == key) {
                sendClient(t);
                return true;
            }
            for (Channel c : t.created.values()) {
                if (c.live && c.key == key) {
                    sendChannel(t, c);
                    return true;
                }
            }
            return false;
        }
        if (radio != null && d == radio.e) {
            for (OwnTx tx : radio.own.values()) {
                if (tx.live && tx.key == key) {
                    sendTransmitter(tx, tx.status);
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void stateChanged(Endpoint e, EndpointState state) {
        TalkSide t = talk.get(e);
        boolean down = state == EndpointState.LOST || state == EndpointState.FAILED || state == EndpointState.CLOSED;
        if (t != null) {
            if (down) {
                t.clear();
            } else if (state == EndpointState.JOINED) {
                t.joinedAt = System.currentTimeMillis();
                if (radio != null && t.client == null) {
                    createClient(t);
                }
            }
        } else if (radio != null && e == radio.e && down) {
            radio.clear();
        }
    }

    // ================================================================== Extension: Talk <-> Talk messages

    @Override
    public byte[][] rewrite(Endpoint s, Endpoint d, int destinationClass, int[] parameters, byte[][] values) {
        TalkSide from = talk.get(s);
        TalkSide to = talk.get(d);
        if (from == null || to == null) {
            return values;
        }
        String cls = to.interactionClassName(destinationClass);
        if (!is(cls, MESSAGE)) {
            return values;
        }
        if (messagesOnly) {
            return rewriteMessage(from, to, cls, parameters, values);
        }
        ClassParameter cp = to.m.getClassParameter(cls, "ChannelUUID");
        int at = -1;
        for (int i = 0; cp != null && i < parameters.length; i++) {
            if (parameters[i] == cp.getId()) {
                at = i;
            }
        }
        if (at < 0 || values[at] == null || Arrays.equals(Arrays.copyOf(values[at], 16), LOBBY)) {
            messagesRouted.incrementAndGet();
            return values;                                   // the lobby (or no channel given)
        }
        if (lobbyOnly) {
            droppedFiltered.incrementAndGet();               // talk.channels=none: the lobby only
            return null;
        }
        Channel src = from.byUuid.get(hex(values[at]));
        if (src == null) {
            droppedUnknownChannel.incrementAndGet();
            return null;
        }
        if (!allowed(src)) {
            droppedFiltered.incrementAndGet();
            return null;
        }
        String own = matchText(src);
        String target = translate(s, d, own);
        String key = lower(target);
        Channel dst = key.isEmpty() ? null : to.find(key, src.cls);
        if (dst == null || dst.uuid == null) {
            if (!key.isEmpty() && settled(to, System.currentTimeMillis())
                    && (cfg.isTalkCreateMissingChannels() || createMapped && mapped(own, target))) {
                createChannel(to, src, key, target);
            }
            if (is(src.cls, RADIO_CHANNEL) && !cfg.isTalkCreateMissingChannels()) {
                messagesRouted.incrementAndGet();
                return values;                               // radios tune by frequency
            }
            droppedNoCounterpart.incrementAndGet();
            return null;
        }
        values[at] = dst.uuid.clone();
        messagesRouted.incrementAndGet();
        return values;
    }

    /**
     * Messages mode: the ChannelUUID of a message for {@code to}. In order: a
     * {@code talk.channeluuidmap} pair; with {@code talk.trackchannels} the channel's name (after
     * {@code talk.channelmap}) looked up among the channels {@code to} has (no counterpart: dropped,
     * radio channels pass, they tune by frequency); else, and for channels not seen (yet), the
     * UUID unchanged.
     */
    private byte[][] rewriteMessage(TalkSide from, TalkSide to, String cls, int[] parameters, byte[][] values) {
        ClassParameter cp = to.m.getClassParameter(cls, "ChannelUUID");
        int at = -1;
        for (int i = 0; cp != null && i < parameters.length; i++) {
            if (parameters[i] == cp.getId()) {
                at = i;
            }
        }
        if (at < 0 || values[at] == null || Arrays.equals(Arrays.copyOf(values[at], 16), LOBBY)) {
            messagesRouted.incrementAndGet();
            return values;
        }
        if (lobbyOnly) {
            droppedFiltered.incrementAndGet();               // talk.channels=none: the lobby only, seen channel or not
            return null;
        }
        String uuid = hex(values[at]);
        String mapped = translateUuid(from.e, to.e, uuid);
        if (!mapped.equals(uuid)) {
            values[at] = unhex(mapped);
            messagesRouted.incrementAndGet();
            return values;
        }
        Channel src = tracking ? from.byUuid.get(uuid) : null;
        if (src == null) {
            if (tracking) {
                passedUnknownChannel.incrementAndGet();
            }
            messagesRouted.incrementAndGet();
            return values;                                   // unchanged
        }
        if (!allowed(src)) {
            droppedFiltered.incrementAndGet();
            return null;
        }
        String own = matchText(src);
        String target = translate(from.e, to.e, own);
        String key = lower(target);
        Channel dst = key.isEmpty() ? null : to.find(key, src.cls);
        if (dst == null || dst.uuid == null) {
            if (createMapped && !key.isEmpty() && mapped(own, target) && settled(to, System.currentTimeMillis())) {
                createChannel(to, src, key, target);
            }
            if (is(src.cls, RADIO_CHANNEL)) {
                messagesRouted.incrementAndGet();
                return values;                               // radios tune by frequency
            }
            droppedNoCounterpart.incrementAndGet();
            return null;
        }
        values[at] = dst.uuid.clone();
        messagesRouted.incrementAndGet();
        return values;
    }

    /** As {@link #translate}, for 32-digit hex channel UUIDs and talk.channeluuidmap. */
    private String translateUuid(Endpoint s, Endpoint d, String uuid) {
        Map<String, Map<String, String>> m = cfg.getTalkChannelUuidMap();
        String n = uuid;
        Map<String, String> sm = m.get(s.id());
        if (sm != null) {
            for (Map.Entry<String, String> e : sm.entrySet()) {
                if (e.getValue().equals(n)) {
                    n = e.getKey();
                    break;
                }
            }
        }
        Map<String, String> dm = m.get(d.id());
        if (dm != null && dm.containsKey(n)) {
            return dm.get(n);
        }
        Map<String, String> g = m.get("");
        if (g != null) {
            if (g.containsKey(n)) {
                return g.get(n);
            }
            for (Map.Entry<String, String> e : g.entrySet()) {
                if (e.getValue().equals(n)) {
                    return e.getKey();
                }
            }
        }
        return n;
    }

    /**
     * Messages mode: the radio map frequency of a channel UUID of endpoint {@code e}: a map key
     * that is this UUID, or that this endpoint's talk.channeluuidmap.<e> or the global
     * talk.channeluuidmap pairs with it.
     */
    private Long frequencyOfUuid(Endpoint e, String uuid) {
        Long f = radioMap.get(uuid);
        if (f != null) {
            return f;
        }
        Map<String, String> sm = cfg.getTalkChannelUuidMap().get(e.id());
        Map<String, String> g = cfg.getTalkChannelUuidMap().get("");
        for (Map.Entry<String, Long> r : radioMap.entrySet()) {
            String k = r.getKey();
            if (sm != null && uuid.equals(sm.get(k)) || g != null && (uuid.equals(g.get(k)) || k.equals(g.get(uuid)))) {
                return r.getValue();
            }
        }
        return null;
    }

    private static byte[] unhex(String h) {
        byte[] b = new byte[16];
        for (int i = 0; i < 16; i++) {
            b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    /** The channel's text that channels are matched by (name or description). */
    private String matchText(Channel c) {
        return "description".equals(cfg.getTalkMatch()) ? c.description : c.name;
    }

    /** True when talk.channelmap gave the channel another name in the receiver. */
    private static boolean mapped(String own, String target) {
        return own != null && target != null && !lower(own).equals(lower(target));
    }

    /** True when the endpoint is joined and has been watched long enough to know its own channels. */
    private boolean settled(TalkSide t, long now) {
        return t.e.state() == EndpointState.JOINED && now - t.joinedAt >= cfg.getTalkCreateWaitMillis();
    }

    /**
     * talk.createmappedchannels: registers the mapped channels a federation lacks, and deletes a
     * channel the gateway registered once the federation has one of that name of its own.
     */
    private void mappedChannels(long now) {
        for (TalkSide to : talk.values()) {
            if (!settled(to, now)) {
                continue;
            }
            for (TalkSide from : talk.values()) {
                if (from == to) {
                    continue;
                }
                for (Channel c : new ArrayList<Channel>(from.byKey.values())) {
                    String own = matchText(c);
                    if (c.uuid == null || own == null || own.isEmpty() || !allowed(c)) {
                        continue;
                    }
                    String target = translate(from.e, to.e, own);
                    String key = lower(target);
                    if (!key.isEmpty() && mapped(own, target) && to.find(key, c.cls) == null && !to.created.containsKey(key)) {
                        LOG.info("Junction " + gatewayId + ": channel '" + c.name + "' of " + from.e.id() + " is mapped to '" + target
                                + "', which " + to.e.id() + " does not have: registering it there");
                        createChannel(to, c, key, target);
                    }
                }
            }
            for (java.util.Iterator<Map.Entry<String, Channel>> it = to.created.entrySet().iterator(); it.hasNext();) {
                Map.Entry<String, Channel> e = it.next();
                Channel ours = e.getValue();
                if (!ours.live) {
                    continue;
                }
                for (Channel theirs : to.byKey.values()) {
                    if (e.getKey().equals(matchKey(theirs)) && theirs.uuid != null) {
                        LOG.info("Junction " + gatewayId + ": " + to.e.id() + " has a channel '" + theirs.name
                                + "' of its own now: deleting the one the gateway registered");
                        to.e.deleteObject(ours.key, null);
                        if (ours.uuid != null) {
                            to.byUuid.remove(hex(ours.uuid));
                        }
                        it.remove();
                        break;
                    }
                }
            }
        }
    }

    private void createChannel(TalkSide t, Channel src, String key, String target) {
        if (t.created.containsKey(key)) {
            return;
        }
        int cls = t.objectClass(src.cls);
        if (cls == 0) {
            return;
        }
        Channel c = new Channel();
        c.cls = src.cls;
        c.uuid = new byte[16];
        random.nextBytes(c.uuid);
        boolean byDescription = "description".equals(cfg.getTalkMatch());
        c.name = byDescription ? src.name : target;
        c.description = byDescription ? target : src.description;
        c.frequency = src.frequency;
        c.ours = true;
        t.created.put(key, c);
        String base = "Junction-" + gatewayId + "-" + c.name.replaceAll("[^A-Za-z0-9_.-]", "_");
        c.gwId = router.createObject(t.e, cls, new NameCandidates(Arrays.asList(base, base + "#2", base + "#3")));
        channelsCreated.incrementAndGet();
    }

    private void sendChannel(TalkSide t, Channel c) {
        Map<String, HValue> v = new LinkedHashMap<String, HValue>();
        v.put("UUID", HValue.ofBytes(c.uuid));
        v.put("Name", HValue.ofText(c.name));
        v.put("Description", HValue.ofText(c.description));
        if (is(c.cls, VOICE_CHANNEL)) {
            Map<String, HValue> f = new LinkedHashMap<String, HValue>();
            f.put("Frequency", HValue.ofInt(c.frequency));
            f.put("Defined", HValue.ofInt(c.frequency != 0 ? 1 : 0));
            v.put("ChannelFrequency", HValue.ofRecord(f));
        }
        Object[] enc = t.encodeAttributes(c.cls, v);
        t.e.updateObject(c.key, (int[]) enc[0], (byte[][]) enc[1], null);
    }

    // ================================================================== Extension: radio bridge

    @Override
    public void received(Endpoint s, int interactionClass, int[] parameters, byte[][] values) {
        if (radio == null) {
            return;
        }
        TalkSide t = talk.get(s);
        if (t != null) {
            String cls = t.interactionClassName(interactionClass);
            if (is(cls, AUDIO)) {
                talkAudio(t, cls, t.byName(parameters, values, false));
            }
        } else if (s == radio.e && is(radio.interactionClassName(interactionClass), SIGNAL)) {
            rprAudio(radio.byName(parameters, values, false));
        }
    }

    private Long mappedFrequency(String channelName) {
        return channelName == null ? null : radioMap.get(channelName.trim().toLowerCase(Locale.ROOT));
    }

    /** Talk audio on a mapped channel: sent as RPR radio audio. */
    private void talkAudio(TalkSide t, String cls, Map<String, byte[]> p) {
        byte[] ch = p.get("ChannelUUID");
        byte[] sender = p.get("SenderUUID");
        if (ch == null || sender == null) {
            return;
        }
        if (t.client != null && Arrays.equals(Arrays.copyOf(sender, 16), t.client.uuid)) {
            return;                                           // our own
        }
        Channel c = t.byUuid.get(hex(ch));
        Long freq = c == null ? null : mappedFrequency(commonName(t.e, c.name));
        if (freq == null) {
            freq = frequencyOfUuid(t.e, hex(ch));
        }
        if (freq == null && is(cls, RADIO) && p.containsKey("Frequency")) {
            long f = t.decodeParameter(cls, "Frequency", p.get("Frequency")).asLong();
            if (radioMap.containsValue(f)) {
                freq = f;
            }
        }
        if (freq == null) {
            return;
        }
        HValue content = t.decodeParameter(cls, "AudioContent", p.get("AudioContent"));
        if (content == null || content.field("Audio") == null) {
            return;
        }
        byte[] audio = content.field("Audio").asBytes();
        int codec = (int) content.field("Codec").asLong();
        Map<String, HValue> signal = toRpr(audio, codec);
        if (signal == null) {
            framesDropped.incrementAndGet();
            return;
        }
        String key = t.e.id() + "|" + hex(sender) + "|" + freq;
        OwnTx tx = radio.own.get(key);
        if (tx == null) {
            tx = new OwnTx();
            tx.radioIndex = ++radio.nextRadioIndex;
            tx.frequency = freq;
            tx.streamTag = 0x4A4B000000000000L | ((long) (gatewayId.hashCode() & 0xFFFF) << 16) | (tx.radioIndex & 0xFFFF);
            radio.own.put(key, tx);
            String base = "Junction-" + gatewayId + "-talk-" + tx.radioIndex;
            tx.gwId = router.createObject(radio.e, radio.objectClass(TRANSMITTER), new NameCandidates(Arrays.asList(base, base + "#2")));
            radio.ownByGwId.put(tx.gwId, tx);
        }
        tx.lastAudio = System.currentTimeMillis();
        if (tx.failed) {
            framesDropped.incrementAndGet();
            return;
        }
        if (!tx.live) {
            if (tx.buffered.size() < MAX_BUFFERED_FRAMES) {
                tx.buffered.add(signal);
            } else {
                framesDropped.incrementAndGet();
            }
            return;
        }
        if (tx.status != TX_TRANSMITTING) {
            sendTransmitter(tx, TX_TRANSMITTING);
        }
        sendSignal(tx, signal);
    }

    /** AudioData fields for Talk audio, or null when the codec cannot be sent. */
    private Map<String, HValue> toRpr(byte[] audio, int codec) {
        Map<String, HValue> a = new LinkedHashMap<String, HValue>();
        if (codec == AUDIO_CODEC_PCM16) {
            int rate = cfg.getTalkRprSampleRate() > 0 ? cfg.getTalkRprSampleRate() : cfg.getTalkSampleRate();
            short[] s = Pcm.resample(Pcm.samples(audio, cfg.isTalkPcmLittleEndian()), cfg.getTalkSampleRate(), rate);
            byte[] data;
            int enc;
            switch (cfg.getTalkRprEncoding()) {
                case "pcm16le":
                    enc = RPR_PCM16_LE;
                    data = Pcm.octets(s, true);
                    break;
                case "mulaw":
                    enc = RPR_MULAW;
                    data = Pcm.muLawEncode(s);
                    break;
                default:
                    enc = RPR_PCM16_BE;
                    data = Pcm.octets(s, false);
            }
            a.put("EncodingType", HValue.ofInt(enc));
            a.put("SampleRate", HValue.ofInt(rate));
            a.put("DataLength", HValue.ofInt(Math.min(65535, data.length * 8)));
            a.put("SampleCount", HValue.ofInt(s.length));
            a.put("Data", HValue.ofBytes(data));
            return a;
        }
        if (codec == AUDIO_CODEC_OPUS) {
            if (radio.opusEncoding < 0) {
                warnOnce("opus", "Talk sends Opus, the RPR FOM of " + radio.e.id() + " has no Opus encoding: those frames are dropped");
                return null;
            }
            a.put("EncodingType", HValue.ofInt(radio.opusEncoding));
            a.put("SampleRate", HValue.ofInt(48000));
            a.put("DataLength", HValue.ofInt(Math.min(65535, audio.length * 8)));
            a.put("SampleCount", HValue.ofInt(Pcm.opusSamples(audio)));
            a.put("Data", HValue.ofBytes(audio));
            return a;
        }
        warnOnce("codec " + codec, "Talk audio codec " + codec + " is not supported: those frames are dropped");
        return null;
    }

    private void sendTransmitter(OwnTx tx, int status) {
        tx.status = status;
        String[] id = cfg.getTalkRadioEntityId().split("-");
        Map<String, HValue> fed = new LinkedHashMap<String, HValue>();
        fed.put("SiteID", HValue.ofInt(parse(id, 0, 900)));
        fed.put("ApplicationID", HValue.ofInt(parse(id, 1, 99)));
        Map<String, HValue> eid = new LinkedHashMap<String, HValue>();
        eid.put("FederateIdentifier", HValue.ofRecord(fed));
        eid.put("EntityNumber", HValue.ofInt(parse(id, 2, 1)));
        Map<String, HValue> type = new LinkedHashMap<String, HValue>();
        type.put("EntityKind", HValue.ofInt(7));                // radio
        type.put("Domain", HValue.ofInt(1));
        Map<String, HValue> v = new LinkedHashMap<String, HValue>();
        v.put("EntityIdentifier", HValue.ofRecord(eid));
        v.put("HostObjectIdentifier", HValue.ofText(""));
        v.put("RadioIndex", HValue.ofInt(tx.radioIndex));
        v.put("Frequency", HValue.ofInt(tx.frequency));
        v.put("FrequencyBandwidth", HValue.ofFloat(25000));
        v.put("StreamTag", HValue.ofInt(tx.streamTag));
        v.put("TransmitterOperationalStatus", HValue.ofInt(status));
        v.put("RadioSystemType", HValue.ofRecord(type));
        v.put("RadioInputSource", HValue.ofInt(0));
        Object[] enc = radio.encodeAttributes(TRANSMITTER, v);
        radio.e.updateObject(tx.key, (int[]) enc[0], (byte[][]) enc[1], null);
    }

    private static int parse(String[] a, int i, int dflt) {
        try {
            return Integer.parseInt(a[i]);
        } catch (RuntimeException e) {
            return dflt;
        }
    }

    private void sendSignal(OwnTx tx, Map<String, HValue> audio) {
        Map<String, HValue> a = new LinkedHashMap<String, HValue>();
        a.put("StreamTag", HValue.ofInt(tx.streamTag));
        a.putAll(audio);
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("AudioData", HValue.ofRecord(a));
        Object[] enc = radio.encodeParameters(SIGNAL, p);
        radio.e.sendInteraction(radio.interactionClass(SIGNAL), (int[]) enc[0], (byte[][]) enc[1], null);
        framesToRpr.incrementAndGet();
    }

    /** RPR audio on a mapped frequency: sent into the mapped Talk channels. */
    private void rprAudio(Map<String, byte[]> p) {
        HValue a = radio.decodeParameter(SIGNAL, "AudioData", p.get("AudioData"));
        if (a == null) {
            return;
        }
        HeardTx tx = radio.byStreamTag(a.field("StreamTag") == null ? 0 : a.field("StreamTag").asLong());
        if (tx == null || tx.frequency == 0) {
            framesDropped.incrementAndGet();
            return;
        }
        if (lobbyOnly) {
            return;                                          // talk.channels=none: no channel, so no radio into Talk
        }
        List<String> channels = new ArrayList<String>();
        for (Map.Entry<String, Long> e : cfg.getTalkRadioMap().entrySet()) {
            if (e.getValue() == tx.frequency) {
                channels.add(e.getKey());
            }
        }
        if (channels.isEmpty()) {
            return;
        }
        int enc = (int) a.field("EncodingType").asLong();
        byte[] data = a.field("Data") == null ? new byte[0] : a.field("Data").asBytes();
        int bits = (int) a.field("DataLength").asLong();
        if (bits > 0 && bits / 8 < data.length) {
            data = Arrays.copyOf(data, bits / 8);
        }
        int rate = (int) a.field("SampleRate").asLong();
        byte[] talkAudio;
        int codec;
        if (enc == RPR_PCM16_BE || enc == RPR_PCM16_LE || enc == RPR_MULAW) {
            short[] s = enc == RPR_MULAW ? Pcm.muLawDecode(data) : Pcm.samples(data, enc == RPR_PCM16_LE);
            s = Pcm.resample(s, rate, cfg.getTalkSampleRate());
            talkAudio = Pcm.octets(s, cfg.isTalkPcmLittleEndian());
            codec = AUDIO_CODEC_PCM16;
        } else if (enc == radio.opusEncoding) {
            talkAudio = data;
            codec = AUDIO_CODEC_OPUS;
        } else {
            warnOnce("rprenc " + enc, "RPR audio encoding " + enc + " is not converted for Talk: those frames are dropped");
            framesDropped.incrementAndGet();
            return;
        }
        Map<String, HValue> content = new LinkedHashMap<String, HValue>();
        content.put("Audio", HValue.ofBytes(talkAudio));
        content.put("Codec", HValue.ofInt(codec));
        for (TalkSide t : talk.values()) {
            if (t.client == null || !t.client.live) {
                continue;
            }
            for (String key : channels) {
                String uuid = RouterConfig.uuidHex(key);
                if (uuid != null) {
                    Map<String, String> scoped = cfg.getTalkChannelUuidMap().get(t.e.id());
                    if (scoped != null && scoped.containsKey(uuid)) {
                        uuid = scoped.get(uuid);
                    }
                    Channel c = t.byUuid.get(uuid);
                    if (c != null && is(c.cls, CHAT_CHANNEL)) {
                        continue;
                    }
                    sendTalkAudio(t, unhex(uuid), c == null || is(c.cls, RADIO_CHANNEL), content, tx.frequency);
                    continue;
                }
                if (messagesOnly && !tracking) {
                    warnOnce("namekey " + key, "talk.trackchannels=false: talk.radio.map." + key
                            + " names a channel; RPR audio needs a channel UUID as key to reach Talk");
                    continue;
                }
                Channel c = t.byName(localName(t.e, key));
                if (c == null || c.uuid == null || is(c.cls, CHAT_CHANNEL)) {
                    continue;
                }
                sendTalkAudio(t, c.uuid, is(c.cls, RADIO_CHANNEL), content, tx.frequency);
            }
        }
    }

    /** One Message.Audio (or Message.Audio.Radio, with the frequency) from the gateway's sender. */
    private void sendTalkAudio(TalkSide t, byte[] channel, boolean isRadio, Map<String, HValue> content, long frequency) {
        String cls = isRadio ? RADIO : AUDIO;
        Map<String, Object> pv = new LinkedHashMap<String, Object>();
        pv.put("SenderUUID", t.client.uuid.clone());
        pv.put("ChannelUUID", channel.clone());
        pv.put("AudioContent", HValue.ofRecord(content));
        if (isRadio) {
            pv.put("Frequency", HValue.ofInt(frequency));
            pv.put("RadioModuleIndex", HValue.ofInt(0));
        }
        Object[] e = t.encodeParameters(cls, pv);
        t.e.sendInteraction(t.interactionClass(cls), (int[]) e[0], (byte[][]) e[1], null);
        framesToTalk.incrementAndGet();
    }

    // ================================================================== Extension: time

    @Override
    public void tick(long now) {
        if (createMapped && now - lastMappedCheck >= 1000) {
            lastMappedCheck = now;
            mappedChannels(now);
        }
        if (now - lastSnapshot >= 1000) {
            lastSnapshot = now;
            snapshot = buildReport();
        }
        if (radio == null) {
            return;
        }
        for (OwnTx tx : radio.own.values()) {
            if (tx.live && tx.status == TX_TRANSMITTING && now - tx.lastAudio > cfg.getTalkHangTimeMillis()) {
                sendTransmitter(tx, TX_ON_NOT_TRANSMITTING);
            }
        }
    }

    // ================================================================== reports

    private volatile Object snapshot = Json.object();
    private long lastSnapshot;

    /** Channel tables and counters, for the page (refreshed once a second on the dispatcher). */
    Json.Writable report() {
        return () -> snapshot;
    }

    private Object buildReport() {
        {
            Json.JsonObject o = Json.object();
            Json.JsonArray eps = Json.array();
            for (TalkSide t : talk.values()) {
                Json.JsonArray chs = Json.array();
                List<Channel> list = new ArrayList<Channel>(t.byKey.values());
                list.addAll(t.created.values());
                Collections.sort(list, (x, y) -> x.name.compareToIgnoreCase(y.name));
                for (Channel c : list) {
                    Json.JsonArray matched = Json.array();
                    String own = "description".equals(cfg.getTalkMatch()) ? c.description : c.name;
                    for (TalkSide other : talk.values()) {
                        if (other == t) {
                            continue;
                        }
                        String target = translate(t.e, other.e, own);
                        String key = lower(target);
                        if (!key.isEmpty() && other.find(key, c.cls) != null) {
                            matched.add(key.equals(lower(own)) ? other.e.id() : other.e.id() + " as " + target);
                        }
                    }
                    Long f = mappedFrequency(commonName(t.e, c.name));
                    chs.add(Json.object().put("name", c.name).put("class", c.cls.substring(c.cls.lastIndexOf('.') + 1))
                            .put("ours", c.ours).put("bridged", allowed(c)).put("matchedin", matched)
                            .putIfNotNull("radiohz", f));
                }
                eps.add(Json.object().put("endpoint", t.e.id()).put("channels", chs)
                        .put("radioclient", t.client != null && t.client.live));
            }
            o.put("endpoints", eps);
            o.put("mode", messagesOnly ? "messages" : "full");
            o.put("trackchannels", !messagesOnly || tracking);
            o.put("passedunknownchannel", passedUnknownChannel.get());
            o.put("messagesrouted", messagesRouted.get());
            o.put("droppednocounterpart", droppedNoCounterpart.get());
            o.put("droppedfiltered", droppedFiltered.get());
            o.put("droppedunknownchannel", droppedUnknownChannel.get());
            o.put("channelscreated", channelsCreated.get());
            o.put("createmappedchannels", createMapped);
            if (radio != null) {
                o.put("radio", Json.object().put("endpoint", radio.e.id()).put("transmitters", radio.own.size())
                        .put("heard", radio.heard.size()).put("framestorpr", framesToRpr.get())
                        .put("framestotalk", framesToTalk.get()).put("framesdropped", framesDropped.get())
                        .put("opus", radio.opusEncoding >= 0));
            }
            return o;
        }
    }

    private void warnOnce(String key, String message) {
        if (warned.put(key, Boolean.TRUE) == null) {
            LOG.warning("Junction " + gatewayId + ": " + message);
        }
    }

    /** @return the channel classes, for the filter of the inner router */
    static Set<String> channelClasses() {
        return new java.util.HashSet<String>(Arrays.asList(CHANNEL_CLASSES));
    }
}
