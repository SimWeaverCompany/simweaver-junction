package com.simweaver.rift.db;

import com.simweaver.rift.api.ValueNode;

import java.util.List;
import java.util.function.BiFunction;

/**
 * One-line summaries for the interaction log ("summary" of contract 8.1), made from the decoded
 * parameters. {@link #DEFAULT} knows Link 16 (RPR v2 Link-16 BOM / module, from HLA or from DIS
 * Signal PDUs) and leaves other classes without a summary.
 */
public final class RiftInteractionSummaries {

    /** Link 16 summary, else none. */
    public static final BiFunction<String, List<RiftParameter>, String> DEFAULT = RiftInteractionSummaries::link16;

    private static final String SEP = " · ";

    private RiftInteractionSummaries() {
    }

    /**
     * "J3.2 Air Track (3 words) · STN 00417 · NPG 7 Surveillance" for interactions with a
     * decoded {@code TADILJWordStruct} parameter (JTIDSMessageRadioSignal and the like); null
     * otherwise.
     */
    public static String link16(String classNameQ, List<RiftParameter> parameters) {
        ValueNode message = null, header = null, npg = null;
        int voiceOctets = -1;
        for (RiftParameter p : parameters) {
            ValueNode v = p.getValue();
            if (v == null) continue;
            String type = v.type();
            if ("TADILJWordStruct".equals(type)) message = v;
            else if ("JTIDSHeaderStruct".equals(type) || "LETHeaderStruct".equals(type)) header = v;
            else if ("NPGNumber".equals(p.getName())) npg = v;
            else if ("Data".equals(p.getName()) && p.getRawValue() != null) voiceOctets = p.getRawValue().length;
        }
        String what;
        if (message != null && message.text() != null && !message.text().isEmpty()) {
            what = message.text();
        } else {
            what = otherKind(classNameQ, voiceOctets);           // RTT and voice: no J-words
            if (what == null) return null;
        }
        StringBuilder s = new StringBuilder(what);
        if (header != null) {
            for (ValueNode.Named d : header.derived()) {
                if ("SourceTrackNumber".equals(d.name) && d.node != null) s.append(SEP).append("STN ").append(d.node.text());
            }
        }
        if (npg != null && npg.text() != null && !npg.text().isEmpty()) s.append(SEP).append("NPG ").append(npg.text());
        return s.toString();
    }

    /** "RTT A/B", "RTT reply", "JTIDS voice CVSD (29 octets)" for the Link 16 classes without J-words; null for others. */
    private static String otherKind(String classNameQ, int voice) {
        if (classNameQ == null) return null;
        String simple = classNameQ.substring(classNameQ.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
        switch (simple) {
            case "rttabradiosignal": return "RTT A/B";
            case "rttreplyradiosignal":
            case "rttreplysignal": return "RTT reply";
            case "jtidsvoicecvsdradiosignal": return "JTIDS voice CVSD" + octets(voice);
            case "jtidsvoicelpc10radiosignal": return "JTIDS voice LPC10" + octets(voice);
            case "jtidsvoicelpc12radiosignal": return "JTIDS voice LPC12" + octets(voice);
            default: return null;
        }
    }

    private static String octets(int n) {
        return n < 0 ? "" : " (" + n + " octets)";
    }
}
