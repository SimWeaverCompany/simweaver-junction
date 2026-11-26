package com.simweaver.rift.comms;

import com.simweaver.common.audio.Pcm;
import com.simweaver.common.hla.codec.HValue;
import com.simweaver.common.hla.codec.HlaCodec;
import com.simweaver.common.iem.Attribute;
import com.simweaver.common.iem.DataType;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.common.iem.Parameter;
import com.simweaver.common.rest.XrSseHub;
import com.simweaver.rift.api.RiftJson;
import com.simweaver.rift.db.RiftAttribute;
import com.simweaver.rift.db.RiftDatabase;
import com.simweaver.rift.db.RiftDatabaseListener;
import com.simweaver.rift.db.RiftInstance;
import com.simweaver.rift.db.RiftInteraction;
import com.simweaver.rift.db.RiftParameter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * The Comms view of one datasource (0.12.0): finds the communication channels in what the
 * datasource receives and keeps, per channel, its activity, its senders, a chat transcript, and
 * hands audio to the listeners.
 *
 * <ul>
 *   <li><b>Talk</b> (FOM class names {@code HLAobjectRoot.PitchTalk.*}): the channel objects
 *       (voice, radio, chat), the lobby, the {@code Message.Audio} / {@code Audio.Radio} /
 *       {@code Text} / {@code Image} interactions; senders named from the Client / User objects.
 *       PCM16 audio is played (sample rate and byte order from the datasource settings), Opus is
 *       not.</li>
 *   <li><b>RPR / DIS radio</b> (DIS arrives as RPR): one channel per frequency of the
 *       {@code RadioTransmitter} objects, which are listed with their host objects;
 *       {@code EncodedAudioRadioSignal} audio is matched to its transmitter by {@code StreamTag}
 *       (16-bit PCM either byte order, 8-bit PCM and mu-law are played); the other
 *       {@code RadioSignal} classes (raw binary, Link 16, database index, application specific)
 *       count as activity, matched by {@code HostRadioIndex}.</li>
 * </ul>
 *
 * <p>Fed by the database worker thread (a {@link RiftDatabaseListener}); read by REST threads.
 * All state is guarded by this object's lock; the listener calls are short. Audio frames go to
 * each listening stream's bounded queue, never blocking the worker.
 */
public final class CommsTracker implements RiftDatabaseListener {

    private static final Logger LOG = Logger.getLogger(CommsTracker.class.getName());

    static final String TALK_OBJECTS = "HLAobjectRoot.PitchTalk.";
    static final String TALK_CHANNEL = "HLAobjectRoot.PitchTalk.Channel";
    static final String TALK_CLIENT = "HLAobjectRoot.PitchTalk.Client";
    static final String TALK_USER = "HLAobjectRoot.PitchTalk.User";
    static final String TALK_EQUIPMENT = "HLAobjectRoot.PitchTalk.Equipment";
    static final String TALK_MESSAGE = "HLAinteractionRoot.PitchTalk.Message";
    static final String RADIO_SIGNAL = "HLAinteractionRoot.RadioSignal";
    static final String ENCODED_AUDIO = "EncodedAudioRadioSignal";
    static final String LOBBY = "t-lobby";

    /** RPR / DIS audio encodings (SISO-REF-010 "Encoding Type"; 100 = PCM16 little endian, a common extension). */
    static final int ENC_MULAW = 1, ENC_PCM16BE = 4, ENC_PCM8 = 5, ENC_OPUS = 11, ENC_PCM16LE = 100;
    /** Opus always decodes at 48 kHz (RFC 6716); the rate in the frame header. */
    static final int OPUS_RATE = 48000;
    static final int TALK_OPUS = 1, TALK_PCM16 = 2;
    /** A sender counts as talking this long after its last frame. */
    static final long TALKING_MS = 1500;

    /** HLA Comms audio settings ({@code datasource.comms.*}). */
    public static final class Settings {
        final int talkSampleRate;
        final boolean talkLittleEndian;

        public Settings(int talkSampleRate, boolean talkLittleEndian) {
            this.talkSampleRate = talkSampleRate > 0 ? talkSampleRate : 16000;
            this.talkLittleEndian = talkLittleEndian;
        }

        public static Settings defaults() {
            return new Settings(16000, true);
        }
    }

    private final RiftDatabase db;
    private final String dsid;
    private final XrSseHub hub;
    private final Settings settings;
    private final String topic;

    private InformationExchangeModel codecModel;
    private HlaCodec codec;

    private final Map<String, CommsChannel> channels = new LinkedHashMap<String, CommsChannel>();
    private final Map<String, String> talkClientNames = new HashMap<String, String>();   // UUID hex -> name
    private final Map<String, String> talkUserNames = new HashMap<String, String>();
    private final Map<String, String> talkEquipment = new HashMap<String, String>();
    private final Map<Integer, String> talkObjectUuid = new HashMap<Integer, String>();   // db id -> UUID hex
    private final Map<String, Integer> talkClientObject = new HashMap<String, Integer>();  // client UUID hex -> db id
    private final Map<Integer, Tx> transmitters = new LinkedHashMap<Integer, Tx>();     // db id -> transmitter
    private final Map<String, CommsSource> talkSenders = new HashMap<String, CommsSource>();
    private final List<CommsAudioStream> streams = new CopyOnWriteArrayList<CommsAudioStream>();
    private int nextSourceId = 1;
    private long rev = 1;                       // changes when channels, senders or names change
    private long chatSeq;
    private long lastTickSecond;
    private final AtomicLong audioFrames = new AtomicLong();
    private final AtomicLong playedFrames = new AtomicLong();
    private final AtomicLong unplayableFrames = new AtomicLong();
    private final AtomicLong decodeErrors = new AtomicLong();

    /** An RPR radio transmitter object. */
    private static final class Tx {
        final int dbId;
        final CommsSource source;
        long frequency;
        long streamTag;
        boolean hasTag;
        int status = -1;                // TransmitterOperationalStatus: 0 off, 1 on, 2 transmitting
        String host = "";               // HostObjectIdentifier (an instance name)
        String hostEntity = "";         // EntityIdentifier key "s-a-e" (DIS: the host entity)
        int radioIndex = -1;

        Tx(int dbId, CommsSource source) {
            this.dbId = dbId;
            this.source = source;
        }
    }

    /**
     * @param db       the datasource's database
     * @param dsid     the datasource id (SSE topic {@code comms:<dsid>})
     * @param hub      the SSE hub, or null (tests)
     * @param settings Talk audio settings
     */
    public CommsTracker(RiftDatabase db, String dsid, XrSseHub hub, Settings settings) {
        this.db = db;
        this.dsid = dsid;
        this.hub = hub;
        this.settings = settings == null ? Settings.defaults() : settings;
        this.topic = "comms:" + dsid;
    }

    public String topic() {
        return topic;
    }

    // ================================================================== objects

    @Override
    public void instanceDiscovered(RiftInstance instance) {
        String c = instance.getClassName();
        if (isTransmitter(c)) {
            synchronized (this) {
                CommsSource s = new CommsSource(nextSourceId++, instance.getInstanceName(), "transmitter", label(instance));
                transmitters.put(instance.getDbId(), new Tx(instance.getDbId(), s));
            }
        }
    }

    @Override
    public void instanceUpdated(RiftInstance instance, Set<String> names) {
        String c = instance.getClassName();
        if (c == null) {
            return;
        }
        try {
            if (isTransmitter(c)) {
                transmitterUpdated(instance, names);
            } else if (c.startsWith(TALK_OBJECTS)) {
                talkObjectUpdated(instance, c);
            }
        } catch (RuntimeException e) {
            decodeErrors.incrementAndGet();
        }
    }

    @Override
    public void instanceRemoved(RiftInstance instance, long seq) {
        synchronized (this) {
            if (transmitters.remove(instance.getDbId()) != null) {
                rev++;
                return;
            }
            String uuid = talkObjectUuid.remove(instance.getDbId());
            if (uuid != null) talkClientObject.remove(uuid, instance.getDbId());
            if (uuid != null && instance.getClassName() != null && instance.getClassName().startsWith(TALK_CHANNEL)) {
                CommsChannel ch = channels.get("t-" + uuid);
                if (ch != null) {
                    ch.present = false;
                    rev++;
                }
            }
        }
    }

    static boolean isTransmitter(String className) {
        return className != null && (className.endsWith(".RadioTransmitter") || className.contains(".RadioTransmitter."));
    }

    private void transmitterUpdated(RiftInstance instance, Set<String> names) {
        Map<String, HValue> v = decodeAttributes(instance, names, "Frequency", "StreamTag", "TransmitterOperationalStatus",
                "HostObjectIdentifier", "EntityIdentifier", "RadioIndex", "Marking");
        synchronized (this) {
            Tx tx = transmitters.get(instance.getDbId());
            if (tx == null) {
                tx = new Tx(instance.getDbId(), new CommsSource(nextSourceId++, instance.getInstanceName(), "transmitter", label(instance)));
                transmitters.put(instance.getDbId(), tx);
            }
            String l = label(instance);
            if (!l.equals(tx.source.label)) {
                tx.source.label = l;
                rev++;
            }
            if (v.containsKey("Frequency")) {
                long f = v.get("Frequency").asLong();
                if (f != tx.frequency) {
                    tx.frequency = f;
                    if (f > 0) radioChannel(f);
                    rev++;
                }
            }
            if (v.containsKey("StreamTag")) {
                tx.streamTag = v.get("StreamTag").asLong();
                tx.hasTag = true;
            }
            if (v.containsKey("TransmitterOperationalStatus")) {
                tx.status = (int) v.get("TransmitterOperationalStatus").asLong();
            }
            if (v.containsKey("HostObjectIdentifier")) {
                String h = v.get("HostObjectIdentifier").asTrimmedText();
                if (!h.equals(tx.host)) {
                    tx.host = h;
                    rev++;
                }
            }
            if (v.containsKey("EntityIdentifier")) {
                tx.hostEntity = entityKey(v.get("EntityIdentifier"));
            }
            if (v.containsKey("RadioIndex")) {
                tx.radioIndex = (int) v.get("RadioIndex").asLong();
            }
        }
    }

    private void talkObjectUpdated(RiftInstance instance, String c) {
        Map<String, HValue> v = decodeAttributes(instance, null, "UUID", "Name", "Description", "ChannelFrequency", "Id",
                "MasterConfiguredName", "LoginAccountName", "Hostname");
        HValue u = v.get("UUID");
        if (u == null) {
            return;
        }
        String uuid = hex(u.asBytes());
        synchronized (this) {
            talkObjectUuid.put(instance.getDbId(), uuid);
            if (c.startsWith(TALK_CHANNEL)) {
                CommsChannel ch = talkChannel(uuid, kindOfTalkClass(c));
                ch.present = true;
                ch.kind = kindOfTalkClass(c);
                String n = text(v.get("Name"));
                if (!n.isEmpty()) ch.name = n;
                if (v.containsKey("Description")) ch.description = text(v.get("Description"));
                HValue f = v.get("ChannelFrequency");
                if (f != null && f.field("Frequency") != null && (f.field("Defined") == null || f.field("Defined").asLong() != 0)) {
                    ch.frequency = f.field("Frequency").asLong();
                }
                rev++;
            } else if (c.startsWith(TALK_CLIENT)) {
                talkClientObject.put(uuid, instance.getDbId());
                String n = firstNonEmpty(text(v.get("MasterConfiguredName")), text(v.get("Id")),
                        joinAt(text(v.get("LoginAccountName")), text(v.get("Hostname"))));
                if (!n.isEmpty()) {
                    talkClientNames.put(uuid, n);
                    renameSender(uuid);
                }
            } else if (c.startsWith(TALK_USER)) {
                String n = text(v.get("Name"));
                if (!n.isEmpty()) {
                    talkUserNames.put(uuid, n);
                    renameSender(uuid);
                }
            } else if (c.startsWith(TALK_EQUIPMENT)) {
                String n = text(v.get("Name"));
                if (!n.isEmpty()) talkEquipment.put(uuid, n);
            }
        }
    }

    private void renameSender(String uuid) {
        CommsSource s = talkSenders.get(uuid);
        if (s != null) {
            s.label = senderName(uuid);
            rev++;
        }
    }

    static String kindOfTalkClass(String c) {
        if (c.contains(".RadioChannel")) return "radio";
        if (c.contains(".VoiceChannel")) return "voice";
        if (c.contains(".ChatChannel")) return "chat";
        if (c.contains(".AudioChannel")) return "voice";
        return "chat";
    }

    // ================================================================== interactions

    @Override
    public void interactionReceived(RiftInteraction ix) {
        String c = ix.classNameQ();
        if (c == null) {
            return;
        }
        try {
            if (c.startsWith(TALK_MESSAGE)) {
                talkMessage(ix, c);
            } else if (c.startsWith(RADIO_SIGNAL)) {
                radioSignal(ix, c);
            }
        } catch (RuntimeException e) {
            decodeErrors.incrementAndGet();
        }
    }

    private void talkMessage(RiftInteraction ix, String c) {
        Map<String, HValue> p = decodeParameters(ix, "SenderUUID", "ChannelUUID", "AudioContent", "Frequency", "Text", "Image",
                "Description", "EquipmentUUID");
        String channelUuid = p.containsKey("ChannelUUID") ? hex(p.get("ChannelUUID").asBytes()) : "";
        String sender = p.containsKey("SenderUUID") ? hex(p.get("SenderUUID").asBytes()) : "";
        long now = ix.getReceivedMillis() > 0 ? ix.getReceivedMillis() : System.currentTimeMillis();
        boolean audio = c.startsWith(TALK_MESSAGE + ".Audio");
        boolean radio = c.startsWith(TALK_MESSAGE + ".Audio.Radio");
        boolean text = c.startsWith(TALK_MESSAGE + ".Text");
        boolean image = c.startsWith(TALK_MESSAGE + ".Image");
        byte[] pcm = null;
        byte[] opus = null;
        int rate = settings.talkSampleRate;
        CommsChannel ch;
        CommsSource src;
        synchronized (this) {
            ch = isZero(channelUuid) ? lobby() : talkChannel(channelUuid, radio ? "radio" : audio ? "voice" : "chat");
            src = talkSender(ch, sender);
            if (p.containsKey("EquipmentUUID")) {
                String eq = talkEquipment.get(hex(p.get("EquipmentUUID").asBytes()));
                if (eq != null) src.equipment = eq;
            }
            src.lastActive = now;
            src.frames++;
            int size = 0;
            if (audio) {
                HValue content = p.get("AudioContent");
                byte[] data = content == null || content.field("Audio") == null ? new byte[0] : content.field("Audio").asBytes();
                int codecValue = content == null || content.field("Codec") == null ? 0 : (int) content.field("Codec").asLong();
                size = data.length;
                ch.audio = true;
                audioFrames.incrementAndGet();
                if (radio && p.containsKey("Frequency") && ch.frequency == 0) {
                    ch.frequency = p.get("Frequency").asLong();
                }
                if (codecValue == TALK_PCM16) {
                    ch.playable = true;
                    ch.encoding = "PCM16";
                    ch.codec = "pcm";
                    pcm = settings.talkLittleEndian ? evenLength(data) : Pcm.octets(Pcm.samples(data, false), true);
                } else if (codecValue == TALK_OPUS) {
                    ch.playable = true;                      // 0.12.1: passed through, the browser decodes it
                    ch.encoding = "Opus";
                    ch.codec = "opus";
                    opus = data;
                } else {
                    String n = "codec " + codecValue;
                    ch.encoding = n;
                    ch.unplayable = n + " is not played";
                    unplayableFrames.incrementAndGet();
                }
            } else if (text || image) {
                String t;
                if (text) {
                    HValue tc = p.get("Text");
                    t = tc == null ? "" : tc.getKind() == HValue.Kind.RECORD && tc.field("Text") != null ? text(tc.field("Text")) : text(tc);
                } else {
                    String d = p.containsKey("Description") ? textOf(p.get("Description")) : "";
                    t = d.isEmpty() ? "[image]" : "[image] " + d;
                }
                size = t.length();
                CommsChannel.ChatLine line = new CommsChannel.ChatLine(++chatSeq, now, src.label, sender, t, text ? "text" : "image");
                ch.addChat(line);
                publishChat(ch, line);
            }
            ch.hit(now, size);
        }
        if (pcm != null && pcm.length > 0) {
            fanOut(CommsAudioStream.ENC_PCM16LE, ch.id, src.id, rate, now, pcm);
        } else if (opus != null && opus.length > 0) {
            fanOut(CommsAudioStream.ENC_OPUS, ch.id, src.id, OPUS_RATE, now, opus);
        }
    }

    private void radioSignal(RiftInteraction ix, String c) {
        long now = ix.getReceivedMillis() > 0 ? ix.getReceivedMillis() : System.currentTimeMillis();
        String simple = c.substring(c.lastIndexOf('.') + 1);
        if (c.contains("." + ENCODED_AUDIO)) {
            Map<String, HValue> p = decodeParameters(ix, "AudioData");
            HValue a = p.get("AudioData");
            if (a == null) {
                return;
            }
            long tag = a.field("StreamTag") == null ? 0 : a.field("StreamTag").asLong();
            int enc = a.field("EncodingType") == null ? 0 : (int) (a.field("EncodingType").asLong() & 0x3FFF);
            String encName = a.field("EncodingType") == null ? null : a.field("EncodingType").getEnumerator();
            int rate = a.field("SampleRate") == null ? 0 : (int) a.field("SampleRate").asLong();
            byte[] data = a.field("Data") == null ? new byte[0] : a.field("Data").asBytes();
            int bits = a.field("DataLength") == null ? 0 : (int) a.field("DataLength").asLong();
            if (bits > 0 && bits / 8 < data.length) {
                data = java.util.Arrays.copyOf(data, bits / 8);
            }
            byte[] pcm = null;
            boolean opus = encName != null ? encName.toLowerCase(Locale.ROOT).contains("opus") : enc == ENC_OPUS;
            CommsChannel ch;
            CommsSource src;
            synchronized (this) {
                Tx tx = byStreamTag(tag);
                ch = tx != null && tx.frequency > 0 ? radioChannel(tx.frequency) : unknownChannel("x-" + Long.toHexString(tag),
                        "Stream " + Long.toHexString(tag));
                src = tx != null ? tx.source : unknownSource(ch, "tag:" + Long.toHexString(tag), "stream " + Long.toHexString(tag));
                src.lastActive = now;
                src.frames++;
                ch.audio = true;
                audioFrames.incrementAndGet();
                switch (enc) {
                    case ENC_PCM16BE:
                        pcm = Pcm.octets(Pcm.samples(data, false), true);
                        break;
                    case ENC_PCM16LE:
                        pcm = evenLength(data);
                        break;
                    case ENC_MULAW:
                        pcm = Pcm.octets(Pcm.muLawDecode(data), true);
                        break;
                    case ENC_PCM8: {
                        short[] s = new short[data.length];
                        for (int i = 0; i < data.length; i++) s[i] = (short) (data[i] << 8);
                        pcm = Pcm.octets(s, true);
                        break;
                    }
                    default:
                        break;
                }
                String n = known(enc) || encName == null || encName.isEmpty() ? encodingName(enc) : pretty(encName);
                ch.encoding = opus ? "Opus" : n;
                if (opus && data.length > 0) {
                    ch.playable = true;                      // 0.12.1: the browser decodes it
                    ch.codec = "opus";
                } else if (pcm != null && rate > 0) {
                    ch.playable = true;
                    ch.codec = "pcm";
                } else {
                    pcm = null;
                    ch.unplayable = n + (rate > 0 ? " is not played" : " without a sample rate");
                    unplayableFrames.incrementAndGet();
                }
                ch.hit(now, data.length);
            }
            if (opus && data.length > 0) {
                fanOut(CommsAudioStream.ENC_OPUS, ch.id, src.id, OPUS_RATE, now, data);
            } else if (pcm != null && pcm.length > 0) {
                fanOut(CommsAudioStream.ENC_PCM16LE, ch.id, src.id, rate, now, pcm);
            }
            return;
        }
        // a signal without audio: activity on its transmitter's frequency
        Map<String, HValue> p = decodeParameters(ix, "HostRadioIndex");
        String host = p.containsKey("HostRadioIndex") ? p.get("HostRadioIndex").asTrimmedText() : "";
        int size = 0;
        for (RiftParameter rp : ix.getRiftParameters()) {
            if (rp.getRawValue() != null) size += rp.getRawValue().length;
        }
        synchronized (this) {
            Tx tx = byName(host);
            CommsChannel ch = tx != null && tx.frequency > 0 ? radioChannel(tx.frequency)
                    : unknownChannel("x-" + safeId(host.isEmpty() ? "unknown" : host), host.isEmpty() ? "Unknown transmitter" : "Transmitter " + host);
            CommsSource src = tx != null ? tx.source : unknownSource(ch, "name:" + host, host.isEmpty() ? "unknown" : host);
            src.lastActive = now;
            src.frames++;
            String kind = signalKind(c, simple);
            Long n = ch.signals.get(kind);
            ch.signals.put(kind, n == null ? 1 : n + 1);
            ch.hit(now, size);
        }
    }

    static String signalKind(String qualified, String simple) {
        if (qualified.contains(".Link16RadioSignal")) {
            return simple.equals("Link16RadioSignal") ? "Link 16" : "Link 16 " + simple.replace("RadioSignal", "");
        }
        if (qualified.contains(".TDLBinaryRadioSignal")) return "TDL binary";
        if (simple.equals("RawBinaryRadioSignal")) return "Raw binary";
        if (simple.equals("DatabaseIndexRadioSignal")) return "Database index";
        if (simple.equals("ApplicationSpecificRadioSignal")) return "Application specific";
        return simple.replace("RadioSignal", "");
    }

    static boolean known(int enc) {
        return (enc >= 1 && enc <= 6) || (enc >= 8 && enc <= 11) || enc == ENC_PCM16LE;
    }

    /** A FOM enumerator name for display: "Encoding_8-bit_mu-law" -> "8-bit mu-law". */
    static String pretty(String enumerator) {
        String s = enumerator.replace('_', ' ').trim();
        return s.startsWith("Encoding ") ? s.substring(9) : s;
    }

    static String encodingName(int enc) {
        switch (enc) {
            case ENC_MULAW: return "8-bit mu-law";
            case 2: return "CVSD";
            case 3: return "ADPCM";
            case ENC_PCM16BE: return "16-bit linear PCM";
            case ENC_PCM8: return "8-bit linear PCM";
            case 6: return "VQ";
            case 8: return "GSM full rate";
            case 9: return "GSM half rate";
            case 10: return "Speex narrow band";
            case 11: return "Opus";
            case ENC_PCM16LE: return "16-bit linear PCM (little endian)";
            default: return "encoding " + enc;
        }
    }

    // ================================================================== channels (locked)

    private CommsChannel radioChannel(long frequency) {
        String id = "f-" + frequency;
        CommsChannel ch = channels.get(id);
        if (ch == null) {
            ch = new CommsChannel(id, "radio", "radio", frequencyText(frequency));
            ch.frequency = frequency;
            ch.present = true;
            channels.put(id, ch);
            rev++;
        }
        return ch;
    }

    private CommsChannel unknownChannel(String id, String name) {
        CommsChannel ch = channels.get(id);
        if (ch == null) {
            ch = new CommsChannel(id, "radio", "radio", name);
            ch.present = true;
            channels.put(id, ch);
            rev++;
        }
        return ch;
    }

    private CommsChannel talkChannel(String uuid, String kind) {
        String id = "t-" + uuid;
        CommsChannel ch = channels.get(id);
        if (ch == null) {
            ch = new CommsChannel(id, "hlacomms", kind, "Channel " + uuid.substring(0, Math.min(8, uuid.length())));
            channels.put(id, ch);
            rev++;
        }
        return ch;
    }

    private CommsChannel lobby() {
        CommsChannel ch = channels.get(LOBBY);
        if (ch == null) {
            ch = new CommsChannel(LOBBY, "hlacomms", "lobby", "Lobby");
            ch.present = true;
            channels.put(LOBBY, ch);
            rev++;
        }
        return ch;
    }

    private CommsSource talkSender(CommsChannel ch, String uuid) {
        CommsSource s = talkSenders.get(uuid);
        if (s == null) {
            s = new CommsSource(nextSourceId++, uuid, "client", senderName(uuid));
            talkSenders.put(uuid, s);
        }
        if (!ch.senders.containsKey(uuid)) {
            ch.senders.put(uuid, s);
            rev++;
        }
        return s;
    }

    private CommsSource unknownSource(CommsChannel ch, String key, String label) {
        CommsSource s = ch.senders.get(key);
        if (s == null) {
            s = new CommsSource(nextSourceId++, key, "transmitter", label);
            ch.senders.put(key, s);
            rev++;
        }
        return s;
    }

    private String senderName(String uuid) {
        String n = talkClientNames.get(uuid);
        if (n == null) n = talkUserNames.get(uuid);
        return n != null ? n : isZero(uuid) || uuid.isEmpty() ? "unknown" : uuid.substring(0, Math.min(8, uuid.length()));
    }

    private Tx byStreamTag(long tag) {
        for (Tx t : transmitters.values()) {
            if (t.hasTag && t.streamTag == tag) return t;
        }
        return null;
    }

    private Tx byName(String name) {
        if (name == null || name.isEmpty()) return null;
        for (Tx t : transmitters.values()) {
            if (name.equals(t.source.key)) return t;
        }
        return null;
    }

    // ================================================================== audio

    /**
     * Opens a listener for some channels of this datasource; close it with {@link #close}.
     *
     * @param channelIds the channel ids, in the order their numbers have in the frames
     * @param client     who listens (status only)
     */
    public CommsAudioStream open(List<String> channelIds, String client) {
        CommsAudioStream s = new CommsAudioStream(channelIds, client);
        streams.add(s);
        return s;
    }

    /** @return the open audio streams */
    public int listenerCount() {
        return streams.size();
    }

    public void close(CommsAudioStream s) {
        s.close();
        streams.remove(s);
    }

    private void fanOut(int encoding, String channelId, int source, int rate, long time, byte[] payload) {
        boolean any = false;
        for (CommsAudioStream s : streams) {
            int i = s.channelIndex(channelId);
            if (i >= 0) {
                s.audio(encoding, i, source, rate, time, payload);
                any = true;
            }
        }
        if (any) playedFrames.incrementAndGet();
    }

    /** Closes every listener (the datasource is going away). */
    public void closeAll() {
        for (CommsAudioStream s : streams) {
            s.close();
        }
        streams.clear();
    }

    // ================================================================== live updates

    @Override
    public void tick(long nowMillis) {
        long second = nowMillis / 1000 - 1;          // the last whole second
        if (second <= lastTickSecond) {
            return;
        }
        lastTickSecond = second;
        if (hub == null || !hub.hasSubscribers(topic)) {
            return;
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        Map<String, Object> per = new LinkedHashMap<String, Object>();
        synchronized (this) {
            m.put("rev", rev);
            m.put("t", second);
            for (CommsChannel ch : channels.values()) {
                ch.advance(second + 1);
                long n = ch.countAt(second);
                List<Integer> talking = talking(ch, nowMillis);
                if (n == 0 && talking.isEmpty()) {
                    continue;
                }
                Map<String, Object> c = new LinkedHashMap<String, Object>();
                c.put("n", n);
                if (!talking.isEmpty()) c.put("speaking", talking);
                c.put("lastactivity", iso(ch.lastActivity));
                per.put(ch.id, c);
            }
        }
        m.put("channels", per);
        hub.publishJson(topic, "comms.tick", m);
    }

    private void publishChat(CommsChannel ch, CommsChannel.ChatLine line) {
        if (hub == null || !hub.hasSubscribers(topic)) {
            return;
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("channel", ch.id);
        m.put("line", chatJson(line));
        hub.publishJson(topic, "comms.chat", m);
    }

    private List<Integer> talking(CommsChannel ch, long now) {
        List<Integer> l = new ArrayList<Integer>();
        for (CommsSource s : sourcesOf(ch)) {
            if (s.lastActive > 0 && now - s.lastActive < TALKING_MS) l.add(s.id);
        }
        return l;
    }

    /** Locked: the senders of a channel (radio frequencies: the transmitters on it now, plus unknown ones). */
    private List<CommsSource> sourcesOf(CommsChannel ch) {
        List<CommsSource> l = new ArrayList<CommsSource>();
        if ("radio".equals(ch.family) && ch.frequency > 0) {
            for (Tx t : transmitters.values()) {
                if (t.frequency == ch.frequency) l.add(t.source);
            }
        }
        l.addAll(ch.senders.values());
        return l;
    }

    // ================================================================== JSON (REST threads)

    /** {@code GET …/comms}: every channel with its senders and activity history. */
    public Map<String, Object> snapshot() {
        long now = System.currentTimeMillis();
        long last = now / 1000 - 1;
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        List<Object> list = new ArrayList<Object>();
        synchronized (this) {
            m.put("rev", rev);
            m.put("t", last);
            m.put("seconds", CommsChannel.SECONDS);
            for (CommsChannel ch : channels.values()) {
                ch.advance(last + 1);
                list.add(channelJson(ch, last, now));
            }
            Map<String, Object> st = new LinkedHashMap<String, Object>();
            st.put("audioframes", audioFrames.get());
            st.put("playedframes", playedFrames.get());
            st.put("unplayableframes", unplayableFrames.get());
            st.put("decodeerrors", decodeErrors.get());
            st.put("transmitters", transmitters.size());
            List<Object> ls = new ArrayList<Object>();
            for (CommsAudioStream s : streams) ls.add(s.status());
            st.put("listeners", ls);
            m.put("stats", st);
        }
        Map<String, Object> talk = new LinkedHashMap<String, Object>();
        talk.put("samplerate", settings.talkSampleRate);
        talk.put("pcmbyteorder", settings.talkLittleEndian ? "little" : "big");
        m.put("hlacomms", talk);
        m.put("channels", list);
        return m;
    }

    private Map<String, Object> channelJson(CommsChannel ch, long last, long now) {
        Map<String, Object> c = new LinkedHashMap<String, Object>();
        c.put("id", ch.id);
        c.put("family", ch.family);
        c.put("kind", ch.kind);
        c.put("name", ch.name);
        if (!ch.description.isEmpty()) c.put("description", ch.description);
        if (ch.frequency > 0) c.put("frequency", ch.frequency);
        c.put("present", ch.present);
        c.put("audio", ch.audio);
        c.put("playable", ch.playable);
        if (!ch.encoding.isEmpty()) c.put("encoding", ch.encoding);
        if (!ch.codec.isEmpty()) c.put("codec", ch.codec);
        if (!ch.unplayable.isEmpty() && !ch.playable) c.put("unplayable", ch.unplayable);
        if (!ch.signals.isEmpty()) c.put("signals", new LinkedHashMap<String, Object>(ch.signals));
        c.put("chat", ch.isChat());
        c.put("chatcount", ch.chatCount);
        c.put("frames", ch.frames);
        c.put("bytes", ch.bytes);
        if (ch.lastActivity > 0) c.put("lastactivity", iso(ch.lastActivity));
        c.put("activity", ch.history(last));
        List<Object> src = new ArrayList<Object>();
        for (CommsSource s : sourcesOf(ch)) {
            src.add(sourceJson(s, now));
        }
        c.put("sources", src);
        return c;
    }

    private Map<String, Object> sourceJson(CommsSource s, long now) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", s.id);
        m.put("kind", s.kind);
        m.put("label", s.label);
        if (!s.equipment.isEmpty()) m.put("equipment", s.equipment);
        m.put("frames", s.frames);
        if (s.lastActive > 0) m.put("lastactive", iso(s.lastActive));
        m.put("speaking", s.lastActive > 0 && now - s.lastActive < TALKING_MS);
        if ("transmitter".equals(s.kind)) {
            for (Tx t : transmitters.values()) {
                if (t.source == s) {
                    RiftInstance o = db.getInstance(t.dbId);
                    if (o != null) m.put("object", RiftJson.target(o));
                    if (t.status >= 0) m.put("status", t.status);
                    if (t.radioIndex >= 0) m.put("radioindex", t.radioIndex);
                    RiftInstance h = t.host.isEmpty() ? null : db.findByInstanceName(t.host);
                    if (h == null && !t.hostEntity.isEmpty()) h = db.findByEntityId(t.hostEntity);
                    if (h != null) {
                        m.put("host", RiftJson.target(h));
                    } else if (!t.host.isEmpty()) {
                        m.put("hostname", t.host);
                    }
                    break;
                }
            }
        } else if ("client".equals(s.kind)) {
            m.put("uuid", s.key);
            Integer dbId = talkClientObject.get(s.key);
            RiftInstance o = dbId == null ? null : db.getInstance(dbId);
            if (o != null) m.put("object", RiftJson.target(o));
        }
        return m;
    }

    /** {@code GET …/comms/chat?channel=}: the transcript, oldest first; null for an unknown channel. */
    public Map<String, Object> chat(String channelId, int limit) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        synchronized (this) {
            CommsChannel ch = channels.get(channelId);
            if (ch == null) {
                return null;
            }
            List<Object> lines = new ArrayList<Object>();
            for (CommsChannel.ChatLine l : ch.chatLines(limit)) {
                lines.add(chatJson(l));
            }
            m.put("channel", ch.id);
            m.put("name", ch.name);
            m.put("total", ch.chatCount);
            m.put("lines", lines);
        }
        return m;
    }

    private static Map<String, Object> chatJson(CommsChannel.ChatLine l) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("seq", l.seq);
        m.put("time", iso(l.time));
        m.put("sender", l.sender);
        if (!l.senderId.isEmpty()) m.put("senderid", l.senderId);
        m.put("kind", l.kind);
        m.put("text", l.text);
        return m;
    }

    /** @return true when a channel with this id exists */
    public synchronized boolean hasChannel(String id) {
        return channels.containsKey(id);
    }

    /** @return the channel ids, for tests */
    public synchronized List<String> channelIds() {
        return new ArrayList<String>(channels.keySet());
    }

    // ================================================================== decoding

    private HlaCodec codec() {
        InformationExchangeModel m = db.getInformationExchangeModel();
        if (m == null) {
            return null;
        }
        if (m != codecModel) {
            codec = new HlaCodec(m);
            codecModel = m;
        }
        return codec;
    }

    /** Decodes some attributes of an instance (only those of {@code changed}, when given). */
    private Map<String, HValue> decodeAttributes(RiftInstance instance, Set<String> changed, String... names) {
        Map<String, HValue> out = new HashMap<String, HValue>();
        HlaCodec c = codec();
        if (c == null) {
            return out;
        }
        InformationExchangeModel m = codecModel;
        for (String n : names) {
            if (changed != null && !changed.contains(n)) continue;
            RiftAttribute a = instance.getAttribute(n);
            if (a == null || a.getRawValue() == null || a.getIemId() == null) continue;
            Attribute decl = m.getAttribute(a.getIemId());
            DataType dt = decl == null ? null : decl.getDataType();
            if (dt == null) continue;
            try {
                out.put(n, c.decode(dt, a.getRawValue()));
            } catch (RuntimeException e) {
                decodeErrors.incrementAndGet();
            }
        }
        return out;
    }

    private Map<String, HValue> decodeParameters(RiftInteraction ix, String... names) {
        Map<String, HValue> out = new HashMap<String, HValue>();
        HlaCodec c = codec();
        if (c == null) {
            return out;
        }
        InformationExchangeModel m = codecModel;
        for (String n : names) {
            RiftParameter p = ix.getParameter(n);
            if (p == null || p.getRawValue() == null || p.getIemId() == null) continue;
            Parameter decl = m.getParameter(p.getIemId());
            DataType dt = decl == null ? null : decl.getDataType();
            if (dt == null) continue;
            try {
                out.put(n, c.decode(dt, p.getRawValue()));
            } catch (RuntimeException e) {
                decodeErrors.incrementAndGet();
            }
        }
        return out;
    }

    // ================================================================== helpers

    private String label(RiftInstance o) {
        String l = RiftJson.labelOf(o);
        return l == null ? "" : l;
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) {
            s.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        }
        return s.toString();
    }

    static boolean isZero(String hex) {
        for (int i = 0; i < hex.length(); i++) {
            if (hex.charAt(i) != '0') return false;
        }
        return true;
    }

    private static String text(HValue v) {
        return v == null ? "" : v.asTrimmedText().trim();
    }

    /** A TextContent record or a plain string. */
    private static String textOf(HValue v) {
        if (v == null) return "";
        if (v.getKind() == HValue.Kind.RECORD && v.field("Text") != null) return text(v.field("Text"));
        return text(v);
    }

    private static String firstNonEmpty(String... s) {
        for (String x : s) {
            if (x != null && !x.isEmpty()) return x;
        }
        return "";
    }

    private static String joinAt(String user, String host) {
        if (user.isEmpty()) return host;
        return host.isEmpty() ? user : user + "@" + host;
    }

    /** The integer leaves of an EntityIdentifierStruct joined with '-' (the database's entity key). */
    static String entityKey(HValue v) {
        List<String> parts = new ArrayList<String>();
        leaves(v, parts);
        return String.join("-", parts);
    }

    private static void leaves(HValue v, List<String> out) {
        if (v == null) return;
        switch (v.getKind()) {
            case INT:
                out.add(Long.toString(v.asLong()));
                break;
            case RECORD:
                for (String f : v.getFieldNames()) leaves(v.field(f), out);
                break;
            case ARRAY:
                for (HValue x : v.getValues()) leaves(x, out);
                break;
            default:
                break;
        }
    }

    static String frequencyText(long hz) {
        if (hz >= 1_000_000_000L) return trim(hz / 1e9) + " GHz";
        if (hz >= 1_000_000L) return trim(hz / 1e6) + " MHz";
        if (hz >= 1_000L) return trim(hz / 1e3) + " kHz";
        return hz + " Hz";
    }

    private static String trim(double d) {
        String s = String.format(Locale.ROOT, "%.4f", d);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    static String safeId(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < 48; i++) {
            char c = s.charAt(i);
            b.append(Character.isLetterOrDigit(c) && c < 128 || c == '-' || c == '_' ? c : '_');
        }
        return b.toString();
    }

    private static byte[] evenLength(byte[] b) {
        return (b.length & 1) == 0 ? b : java.util.Arrays.copyOf(b, b.length - 1);
    }

    private static String iso(long ms) {
        return Instant.ofEpochMilli(ms).toString();
    }

    @Override
    public String toString() {
        return "CommsTracker[" + dsid + ", " + channelIds().size() + " channels]";
    }

    static List<String> list(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.trim().isEmpty()) return Collections.emptyList();
        List<String> l = new ArrayList<String>();
        for (String s : commaSeparated.split(",")) {
            if (!s.trim().isEmpty()) l.add(s.trim());
        }
        return l;
    }
}
