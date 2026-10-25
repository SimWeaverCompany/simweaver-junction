package com.simweaver.common.hla.decoders.rpr;

import com.simweaver.common.hla.decoders.DecodedValue;
import com.simweaver.common.hla.decoders.ValueKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Link 16 (TADIL J) identification for the RPR-FOM v2 Link-16 BOM ({@code Link16RadioSignal}
 * and its subclasses, SISO-STD-002 transport): the packed JTIDS header, and the J-words of a
 * {@code TADILJMessage} split into J-series messages with their labels.
 * <p>
 * <b>Scope.</b> Only the publicly described structure is decoded: the header fields given in the
 * FOM, and the word framing of the 70 data bits of each 75-bit word (word format in bits 0-1;
 * on an initial word label bits 2-6, sublabel bits 7-9 and message length indicator bits
 * 10-12; on a continuation word its label in bits 2-6). The information fields inside the
 * messages are defined in MIL-STD-6016 and are <b>not</b> decoded; the data bits of each word
 * are shown as hex.
 * <p>
 * <b>Bit packing.</b> Each 75-bit word travels in 10 octets. Which octet bit holds word bit 0 is
 * an option ({@link BitOrder}); {@link BitOrder#AUTO} tries the packings in turn and takes the
 * first for which the words form complete messages (every message starts with an initial word,
 * its length indicator matches the words that follow, nothing is left over), preferring zero
 * padding bits 75-79. The header uses the same packing; under AUTO the one whose padding bits
 * 35-47 are zero.
 * <p>
 * Stateless and thread safe; Java 8, pure ASCII, no RTI.
 */
public final class Link16 {

    /** Where word bit 0 sits in the octets. */
    public enum BitOrder {
        /** Try LSB_FIRST, WORD_BIG_ENDIAN and MSB_FIRST, keep the first consistent one. */
        AUTO,
        /** Bit 0 is the least significant bit of the first octet (little-endian word). */
        LSB_FIRST,
        /** The octets form one big-endian integer: bit 0 is the least significant bit of the last octet. */
        WORD_BIG_ENDIAN,
        /** Bit 0 is the most significant bit of the first octet. */
        MSB_FIRST
    }

    /** Octets per J-word in the FOM ({@code TDLJWordData}). */
    public static final int WORD_OCTETS = 10;
    /** Octets of the JTIDS header ({@code TDLHeaderData}). */
    public static final int HEADER_OCTETS = 6;

    /** Word format codes (bits 0-1 of a word). */
    public static final int FORMAT_INITIAL = 0;
    public static final int FORMAT_CONTINUATION = 1;
    public static final int FORMAT_EXTENSION = 2;

    private static final BitOrder[] TRY = {BitOrder.LSB_FIRST, BitOrder.WORD_BIG_ENDIAN, BitOrder.MSB_FIRST};

    private static final Map<String, String> MESSAGE_NAMES = new HashMap<String, String>();
    private static final Map<Integer, String> NPG_NAMES = new HashMap<Integer, String>();

    static {
        String[][] m = {
            {"J0.0", "Initial Entry"}, {"J0.1", "Test"}, {"J0.2", "Network Time Update"},
            {"J0.3", "Time Slot Assignment"}, {"J0.4", "Radio Relay Control"}, {"J0.5", "Repromulgation Relay"},
            {"J0.6", "Communication Control"}, {"J0.7", "Time Slot Reallocation"},
            {"J1.0", "Connectivity Interrogation"}, {"J1.1", "Connectivity Status"}, {"J1.2", "Route Establishment"},
            {"J1.3", "Acknowledgement"}, {"J1.4", "Communicant Status"}, {"J1.5", "Net Control Initialization"},
            {"J1.6", "Needline Participation Group Assignment"},
            {"J2.0", "Indirect Interface Unit PPLI"}, {"J2.2", "Air PPLI"}, {"J2.3", "Surface PPLI"},
            {"J2.4", "Subsurface PPLI"}, {"J2.5", "Land Point PPLI"}, {"J2.6", "Land Track PPLI"},
            {"J3.0", "Reference Point"}, {"J3.1", "Emergency Point"}, {"J3.2", "Air Track"},
            {"J3.3", "Surface Track"}, {"J3.4", "Subsurface Track"}, {"J3.5", "Land Track/Point"},
            {"J3.6", "Space Track"}, {"J3.7", "Electronic Warfare Product Information"},
            {"J5.4", "Acoustic Bearing and Range"},
            {"J6.0", "Amplification"},
            {"J7.0", "Track Management"}, {"J7.1", "Data Update Request"}, {"J7.2", "Correlation"},
            {"J7.3", "Pointer"}, {"J7.4", "Track Identifier"}, {"J7.5", "IFF/SIF Management"},
            {"J7.6", "Filter Management"}, {"J7.7", "Association"},
            {"J8.0", "Unit Designator"}, {"J8.1", "Mission Correlator Change"},
            {"J9.0", "Command"}, {"J9.1", "Engagement Coordination"}, {"J9.2", "ECCM Coordination"},
            {"J10.2", "Engagement Status"}, {"J10.3", "Handover"}, {"J10.5", "Controlling Unit Report"},
            {"J10.6", "Pairing"},
            {"J12.0", "Mission Assignment"}, {"J12.1", "Vector"}, {"J12.2", "Precision Aircraft Direction"},
            {"J12.3", "Flight Path"}, {"J12.4", "Controlling Unit Change"}, {"J12.5", "Target/Track Correlation"},
            {"J12.6", "Target Sorting"}, {"J12.7", "Target Bearing"},
            {"J13.0", "Airfield Status"}, {"J13.2", "Air Platform and System Status"},
            {"J13.3", "Surface Platform and System Status"}, {"J13.4", "Subsurface Platform and System Status"},
            {"J13.5", "Land Platform and System Status"},
            {"J14.0", "Parametric Information"}, {"J14.2", "Electronic Control/Coordination"},
            {"J15.0", "Threat Warning"},
            {"J16.0", "Image Transfer"},
            {"J17.0", "Weather Over Target"},
            {"J31.0", "Over-the-Air Rekeying Management"}, {"J31.1", "Over-the-Air Rekeying"},
            {"J31.7", "No Statement"},
            // from the Ersatz MIL-STD-6016 schema (public domain, reconstructed from open sources)
            {"J11.0", "From the Weapon"}, {"J11.1", "To the Weapon"}, {"J11.2", "Weapon Coordination"},
            {"J28.0", "U.S. National 1 (Army)"}, {"J28.1", "U.S. National 2 (Navy)"}, {"J28.2", "U.S. National 3 (Air Force)"},
            {"J28.3", "U.S. National 4 (Marine Corps)"}, {"J28.4", "French National 1"}, {"J28.5", "French National 2"},
            {"J28.6", "U.S. National 5 (NSA)"}, {"J28.7", "UK National"},
        };
        for (String[] e : m) {
            MESSAGE_NAMES.put(e[0], e[1]);
        }
        Object[][] n = {
            {1, "Initial Entry"}, {2, "RTT-A"}, {3, "RTT-B"}, {4, "Network Management"},
            {5, "PPLI and Status Pool 1"}, {6, "PPLI and Status Pool 2"}, {7, "Surveillance"},
            {8, "Mission Management/Weapons Coordination"}, {9, "Control"}, {10, "Electronic Warfare"},
            {11, "Image Transfer"}, {12, "Voice Group A"}, {13, "Voice Group B"}, {14, "Indirect PPLI"},
            {18, "Network Enabled Weapons"}, {19, "Fighter-to-Fighter A"}, {20, "Fighter-to-Fighter B"},
            {21, "Engagement Coordination"}, {27, "Joint PPLI"}, {28, "Distributed Network Management"},
            {29, "Residual Message"},
        };
        for (Object[] e : n) {
            NPG_NAMES.put((Integer) e[0], (String) e[1]);
        }
    }

    /** The category of each J-series label 0-31 (Ersatz MIL-STD-6016 schema). */
    private static final String[] CATEGORIES = {
        "Network Management", "Network Management", "Precise Participant Location and Identification", "Surveillance",
        null, "Anti-Submarine Warfare", "Intelligence", "Information Management", "Information Management",
        "Weapons Coordination and Management", "Weapons Coordination and Management", "Weapons Coordination and Management",
        "Control", "Platform and System Status", "Electronic Warfare", "Threat Warning", "Imagery", "Weather",
        null, null, null, null, null, null, null, null, null, null, "National Use", "National Use", "National Use", "Miscellaneous"};

    private Link16() {
    }

    /** @return the category of a J-series label ("Surveillance" for J3), or null when not known */
    public static String category(int label) {
        return label >= 0 && label < CATEGORIES.length ? CATEGORIES[label] : null;
    }

    // ------------------------------------------------------------------ names

    /**
     * @param label    J-series label 0-31
     * @param sublabel sublabel 0-7
     * @return the message name, e.g. "Air Track" for J3.2, or null when not in the table
     */
    public static String messageName(int label, int sublabel) {
        return MESSAGE_NAMES.get(jLabel(label, sublabel));
    }

    /** @return "J3.2" */
    public static String jLabel(int label, int sublabel) {
        return "J" + label + "." + sublabel;
    }

    /** @return the network participation group name, or null */
    public static String npgName(int npg) {
        return NPG_NAMES.get(npg);
    }

    /**
     * @param id a Time Slot ID (SISO-STD-002 Table 8): bits 0-16 the slot 0-98303, 24-31 the epoch
     * @return "epoch 1, slot A-4661", or "no statement" for all ones
     */
    public static String timeSlotText(long id) {
        long v = id & 0xFFFFFFFFL;
        if (v == 0xFFFFFFFFL) {
            return "no statement";
        }
        int slot = (int) (v & 0x1FFFF);
        int epoch = (int) (v >>> 24);
        String s = slot <= 98303 ? (char) ('A' + slot / 32768) + "-" + (slot % 32768 + 1) : "slot " + slot;
        return "epoch " + (epoch == 255 ? "no statement" : String.valueOf(epoch)) + ", slot " + s;
    }

    /** @return an NTP time (seconds since 1900, 2^-32 fractions) as UTC, or "no statement" for all ones */
    public static String ntpText(long seconds, long fraction) {
        long s = seconds & 0xFFFFFFFFL;
        long f = fraction & 0xFFFFFFFFL;
        if (s == 0xFFFFFFFFL && f == 0xFFFFFFFFL) {
            return "no statement";
        }
        long millis = (s - 2208988800L) * 1000L + (f * 1000L >>> 32);
        return java.time.Instant.ofEpochMilli(millis).toString();
    }

    /** @return a source track number as the usual 5-digit octal, e.g. "00417" */
    public static String trackNumber(int stn) {
        return String.format(Locale.ROOT, "%05o", stn & 0x7fff);
    }

    // ------------------------------------------------------------------ bits

    /** One bit of a word or header, by its Link 16 bit number. */
    static int bit(byte[] b, int i, BitOrder order) {
        int octet;
        int shift;
        switch (order) {
            case WORD_BIG_ENDIAN:
                octet = b.length - 1 - (i >>> 3);
                shift = i & 7;
                break;
            case MSB_FIRST:
                octet = i >>> 3;
                shift = 7 - (i & 7);
                break;
            default:                                          // LSB_FIRST
                octet = i >>> 3;
                shift = i & 7;
                break;
        }
        return (b[octet] >>> shift) & 1;
    }

    /** Field of {@code length} bits starting at bit {@code first}; the lower bit number is less significant. */
    static long field(byte[] b, int first, int length, BitOrder order) {
        long v = 0;
        for (int k = 0; k < length; k++) {
            v |= ((long) bit(b, first + k, order)) << k;
        }
        return v;
    }

    /** The 70 data bits of a word as 18 hex digits (bit 69 first). */
    static String dataHex(byte[] w, BitOrder order) {
        long lo = field(w, 0, 64, order);
        long hi = field(w, 64, 6, order);
        return String.format(Locale.ROOT, "%02x%016x", hi, lo);
    }

    // ------------------------------------------------------------------ JTIDS header

    /** The fields of a JTIDSHeaderStruct (bits per the RPR Link-16 BOM). */
    public static final class Header {
        public final int timeSlotType;
        public final boolean relay;
        public final int sourceTrackNumber;
        public final int sduSerialNumber;
        public final boolean paddingZero;
        public final BitOrder order;

        Header(byte[] b, BitOrder order) {
            this.order = order;
            this.timeSlotType = (int) field(b, 0, 3, order);
            this.relay = field(b, 3, 1, order) != 0;
            this.sourceTrackNumber = (int) field(b, 4, 15, order);
            this.sduSerialNumber = (int) field(b, 19, 16, order);
            this.paddingZero = field(b, 35, 13, order) == 0;
        }
    }

    /**
     * @param octets the 6 header octets
     * @param order  bit packing; AUTO picks the first packing with zero padding (LSB_FIRST if none)
     * @return the header, or null when there are fewer than 6 octets
     */
    public static Header parseHeader(byte[] octets, BitOrder order) {
        if (octets == null || octets.length < HEADER_OCTETS) {
            return null;
        }
        if (order != BitOrder.AUTO) {
            return new Header(octets, order);
        }
        for (BitOrder o : TRY) {
            Header h = new Header(octets, o);
            if (h.paddingZero) {
                return h;
            }
        }
        return new Header(octets, BitOrder.LSB_FIRST);
    }

    /** The fields of a LETHeaderStruct (SISO-STD-002-2021 Table 15). */
    public static final class LetHeader {
        public final int letIdSymbol;
        public final boolean relay;
        public final int packingType;
        public final int sourceTrackNumber;
        public final int sduSerialNumber;
        public final boolean paddingZero;
        public final BitOrder order;

        LetHeader(byte[] b, BitOrder order) {
            this.order = order;
            this.letIdSymbol = (int) field(b, 0, 4, order);
            this.relay = field(b, 4, 1, order) != 0;
            this.packingType = (int) field(b, 5, 4, order);
            this.sourceTrackNumber = (int) field(b, 9, 15, order);
            this.sduSerialNumber = (int) field(b, 24, 16, order);
            this.paddingZero = field(b, 40, 8, order) == 0;
        }
    }

    /** As {@link #parseHeader}, for the LET header (padding bits 40-47). */
    public static LetHeader parseLetHeader(byte[] octets, BitOrder order) {
        if (octets == null || octets.length < HEADER_OCTETS) {
            return null;
        }
        if (order != BitOrder.AUTO) {
            return new LetHeader(octets, order);
        }
        for (BitOrder o : TRY) {
            LetHeader h = new LetHeader(octets, o);
            if (h.paddingZero) {
                return h;
            }
        }
        return new LetHeader(octets, BitOrder.LSB_FIRST);
    }

    // ------------------------------------------------------------------ J-words and messages

    /** One J-word: its format and, per format, label fields. */
    public static final class Word {
        public final int format;
        /** Initial word: label 0-31; continuation word: continuation label; else -1. */
        public final int label;
        /** Initial word: sublabel 0-7; else -1. */
        public final int sublabel;
        /** Initial word: message length indicator (words that follow); else -1. */
        public final int lengthIndicator;
        public final boolean paddingZero;
        /** The 70 data bits as hex. */
        public final String dataHex;

        Word(byte[] w, BitOrder order) {
            this.format = (int) field(w, 0, 2, order);
            this.label = format == FORMAT_INITIAL || format == FORMAT_CONTINUATION ? (int) field(w, 2, 5, order) : -1;
            this.sublabel = format == FORMAT_INITIAL ? (int) field(w, 7, 3, order) : -1;
            this.lengthIndicator = format == FORMAT_INITIAL ? (int) field(w, 10, 3, order) : -1;
            this.paddingZero = field(w, 75, 5, order) == 0;
            this.dataHex = dataHex(w, order);
        }

        /** @return "initial", "extension", "continuation" or "format 3" */
        public String formatName() {
            switch (format) {
                case FORMAT_INITIAL: return "initial";
                case FORMAT_EXTENSION: return "extension";
                case FORMAT_CONTINUATION: return "continuation";
                default: return "format " + format;
            }
        }
    }

    /** One J-series message: its initial word and the words that follow it. */
    public static final class Message {
        public final List<Word> words;

        Message(List<Word> words) {
            this.words = Collections.unmodifiableList(words);
        }

        public int label() {
            return words.get(0).label;
        }

        public int sublabel() {
            return words.get(0).sublabel;
        }

        /** @return "J3.2" */
        public String jLabel() {
            return Link16.jLabel(label(), sublabel());
        }

        /** @return "Air Track", or null */
        public String name() {
            return messageName(label(), sublabel());
        }

        /** @return "Surveillance", or null */
        public String category() {
            return Link16.category(label());
        }

        /** @return "J3.2 Air Track (3 words)" */
        public String text() {
            String n = name();
            return jLabel() + (n != null ? " " + n : "") + " (" + words.size() + (words.size() == 1 ? " word)" : " words)");
        }
    }

    /** The messages of a TADILJMessage, and how they were read. */
    public static final class Messages {
        public final List<Message> messages;
        /** False when the words did not form complete messages under any packing. */
        public final boolean consistent;
        public final BitOrder order;
        /** Words left over or not starting a message (when not consistent). */
        public final List<Word> stray;

        Messages(List<Message> messages, List<Word> stray, boolean consistent, BitOrder order) {
            this.messages = Collections.unmodifiableList(messages);
            this.stray = Collections.unmodifiableList(stray);
            this.consistent = consistent;
            this.order = order;
        }

        /** @return "J2.2 Air PPLI (5 words) + J3.2 Air Track (2 words)" */
        public String text() {
            if (messages.isEmpty()) {
                return stray.isEmpty() ? "no J-words" : stray.size() + " unframed J-words";
            }
            StringBuilder sb = new StringBuilder();
            for (Message m : messages) {
                if (sb.length() > 0) {
                    sb.append(" + ");
                }
                sb.append(m.text());
            }
            if (!stray.isEmpty()) {
                sb.append(" + ").append(stray.size()).append(" unframed");
            }
            return sb.toString();
        }
    }

    /**
     * Splits J-words into messages.
     *
     * @param words the 10-octet words in order
     * @param order bit packing (AUTO: first consistent packing, preferring zero padding)
     * @return the messages
     */
    public static Messages parseMessages(List<byte[]> words, BitOrder order) {
        if (order != BitOrder.AUTO) {
            return frame(words, order);
        }
        Messages firstConsistent = null;
        for (BitOrder o : TRY) {
            Messages m = frame(words, o);
            if (m.consistent) {
                if (allPaddingZero(m)) {
                    return m;
                }
                if (firstConsistent == null) {
                    firstConsistent = m;
                }
            }
        }
        return firstConsistent != null ? firstConsistent : frame(words, BitOrder.LSB_FIRST);
    }

    private static boolean allPaddingZero(Messages m) {
        for (Message msg : m.messages) {
            for (Word w : msg.words) {
                if (!w.paddingZero) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Reads words under one packing; consistent = every word belongs to a complete message. */
    static Messages frame(List<byte[]> octets, BitOrder order) {
        List<Word> words = new ArrayList<Word>(octets.size());
        for (byte[] w : octets) {
            words.add(new Word(w, order));
        }
        List<Message> out = new ArrayList<Message>();
        List<Word> stray = new ArrayList<Word>();
        boolean consistent = !words.isEmpty();
        int i = 0;
        while (i < words.size()) {
            Word first = words.get(i);
            if (first.format != FORMAT_INITIAL) {
                consistent = false;
                stray.add(first);
                i++;
                continue;
            }
            int n = first.lengthIndicator;
            List<Word> msg = new ArrayList<Word>(n + 1);
            msg.add(first);
            int j = i + 1;
            while (j < words.size() && j <= i + n) {
                Word w = words.get(j);
                if (w.format != FORMAT_EXTENSION && w.format != FORMAT_CONTINUATION) {
                    break;
                }
                msg.add(w);
                j++;
            }
            if (msg.size() != n + 1) {
                consistent = false;                    // length indicator does not match
            }
            out.add(new Message(msg));
            i = j;
        }
        return new Messages(out, stray, consistent, order);
    }

    // ------------------------------------------------------------------ value trees

    /**
     * The header value with text and derived fields.
     *
     * @param value  the decoded JTIDSHeaderStruct
     * @param octets its 6 octets
     */
    public static DecodedValue headerValue(DecodedValue value, byte[] octets, BitOrder order) {
        Header h = parseHeader(octets, order);
        if (h == null) {
            return value;
        }
        String stn = trackNumber(h.sourceTrackNumber);
        DecodedValue.Builder b = value.toBuilder()
            .text("STN " + stn + ", TS type " + h.timeSlotType + ", SDU " + h.sduSerialNumber + (h.relay ? ", relay" : ""))
            .child(integer("TimeSlotType", h.timeSlotType))
            .child(DecodedValue.builder(ValueKind.BOOLEAN).name("RelayTransmission").booleanValue(h.relay)
                .text(h.relay ? "true" : "false").derived(true).build())
            .child(DecodedValue.builder(ValueKind.TEXT).name("SourceTrackNumber").stringValue(stn)
                .text(stn).derived(true).build())
            .child(integer("SecureDataUnitSerialNumber", h.sduSerialNumber));
        if (!h.paddingZero) {
            b.warning("header padding bits 35-47 are not zero (bit packing " + h.order + ")");
        } else if (order == BitOrder.AUTO && h.order != BitOrder.LSB_FIRST) {
            b.warning("header read with bit packing " + h.order);
        }
        return b.build();
    }

    /**
     * The TADILJMessage value with the message summary as text and one derived record per
     * message (label, name, words).
     */
    public static DecodedValue messagesValue(DecodedValue value, List<byte[]> words, BitOrder order) {
        Messages ms = parseMessages(words, order);
        DecodedValue.Builder b = value.toBuilder().text(ms.text());
        int k = 0;
        for (Message m : ms.messages) {
            k++;
            DecodedValue.Builder mb = DecodedValue.builder(ValueKind.RECORD).name(m.jLabel()).derived(true)
                .text(m.text())
                .child(DecodedValue.builder(ValueKind.TEXT).name("Label").stringValue(m.jLabel()).text(m.jLabel()).derived(true).build());
            if (m.name() != null) {
                mb.child(DecodedValue.builder(ValueKind.TEXT).name("Name").stringValue(m.name()).text(m.name()).derived(true).build());
            }
            if (m.category() != null) {
                mb.child(DecodedValue.builder(ValueKind.TEXT).name("Category").stringValue(m.category()).text(m.category()).derived(true).build());
            }
            mb.child(integer("Words", m.words.size()));
            int wi = 0;
            for (Word w : m.words) {
                mb.child(wordValue("Word " + wi++, w));
            }
            b.child(mb.build());
        }
        int si = 0;
        for (Word w : ms.stray) {
            b.child(wordValue("Unframed " + si++, w));
        }
        if (!ms.consistent) {
            b.warning(words.isEmpty() ? "no J-words"
                : "the J-words do not form complete messages with any known bit packing (shown as " + ms.order + ")");
        } else if (order == BitOrder.AUTO && ms.order != BitOrder.LSB_FIRST) {
            b.warning("J-words read with bit packing " + ms.order);
        }
        return b.build();
    }

    /** The LETHeaderStruct value with text and derived fields. */
    public static DecodedValue letHeaderValue(DecodedValue value, byte[] octets, BitOrder order) {
        LetHeader h = parseLetHeader(octets, order);
        if (h == null) {
            return value;
        }
        String stn = trackNumber(h.sourceTrackNumber);
        DecodedValue.Builder b = value.toBuilder()
            .text("STN " + stn + ", LET id " + h.letIdSymbol + ", packing " + h.packingType + ", SDU " + h.sduSerialNumber
                + (h.relay ? ", relay" : ""))
            .child(integer("LETIdSymbol", h.letIdSymbol))
            .child(DecodedValue.builder(ValueKind.BOOLEAN).name("RelayTransmission").booleanValue(h.relay)
                .text(h.relay ? "true" : "false").derived(true).build())
            .child(integer("LETMessagePackingType", h.packingType))
            .child(DecodedValue.builder(ValueKind.TEXT).name("SourceTrackNumber").stringValue(stn).text(stn).derived(true).build())
            .child(integer("SecureDataUnitSerialNumber", h.sduSerialNumber));
        if (!h.paddingZero) {
            b.warning("LET header padding bits 40-47 are not zero (bit packing " + h.order + ")");
        }
        return b.build();
    }

    /**
     * The TADILJMessage of a VMFRadioSignal: VMF words are not J-series messages (word format
     * in bits 0-1, then 68 bits of VMF data), so they are listed without labels.
     */
    public static DecodedValue vmfValue(DecodedValue value, List<byte[]> words, BitOrder order) {
        BitOrder o = order == BitOrder.AUTO ? BitOrder.LSB_FIRST : order;
        DecodedValue.Builder b = value.toBuilder().text("VMF (" + words.size() + (words.size() == 1 ? " word)" : " words)"));
        int i = 0;
        for (byte[] w : words) {
            Word x = new Word(w, o);
            b.child(DecodedValue.builder(ValueKind.TEXT).name("Word " + i++).stringValue(x.dataHex)
                .text("format " + x.format + ", data 0x" + x.dataHex).derived(true).build());
        }
        return b.build();
    }

    private static DecodedValue wordValue(String name, Word w) {
        StringBuilder t = new StringBuilder(w.formatName());
        if (w.format == FORMAT_INITIAL) {
            t.append(" ").append(jLabel(w.label, w.sublabel)).append(", length ").append(w.lengthIndicator);
        } else if (w.format == FORMAT_CONTINUATION) {
            t.append(" C").append(w.label);
        }
        t.append(", data 0x").append(w.dataHex);
        return DecodedValue.builder(ValueKind.TEXT).name(name).stringValue(w.dataHex).text(t.toString()).derived(true).build();
    }

    private static DecodedValue integer(String name, long v) {
        return DecodedValue.builder(ValueKind.INTEGER).name(name).longValue(v).text(Long.toString(v)).derived(true).build();
    }
}
