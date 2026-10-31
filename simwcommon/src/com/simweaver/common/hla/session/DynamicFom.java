package com.simweaver.common.hla.session;

import com.simweaver.common.iem.HlaVersion;
import com.simweaver.common.iem.IemException;
import com.simweaver.common.iem.InformationExchangeModel;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * A FOM taken from the federation instead of from files: join without FOM modules, then read
 * the federation's current FDD from the MOM ({@code HLAobjectRoot.HLAmanager.HLAfederation},
 * attribute {@code HLAcurrentFDD}, an {@code HLAunicodeString} with the merged FDD as OMT XML)
 * and build the model from it.
 *
 * <p>{@link Reader} does that over an {@link RtiSession}; its owner forwards the session's
 * discover / reflect / remove callbacks to it first. The static helpers decode the value and
 * build the model (also used by Rift's datasources, which talk to the RTI API directly).
 */
public final class DynamicFom {

    /** The MOM class that carries the FDD. */
    public static final String FEDERATION_CLASS = "HLAobjectRoot.HLAmanager.HLAfederation";
    /** The attribute with the current FDD. */
    public static final String FDD_ATTRIBUTE = "HLAcurrentFDD";
    /** The setting value that asks for it: {@code endpoint.hla.foms=dynamic}, {@code datasource.hla.foms=dynamic}. */
    public static final String SETTING = "dynamic";

    private DynamicFom() {
    }

    /** @return true for the setting value {@code dynamic} (any case, alone) */
    public static boolean isDynamic(String[] foms) {
        return foms != null && foms.length == 1 && foms[0] != null && SETTING.equalsIgnoreCase(foms[0].trim());
    }

    /**
     * Decodes an HLAunicodeString: a 32-bit big-endian count of UTF-16 code units, then the code
     * units big-endian.
     *
     * @throws IllegalArgumentException when the value is shorter than its count says
     */
    public static String decodeUnicodeString(byte[] v) {
        if (v == null || v.length < 4) {
            throw new IllegalArgumentException("not an HLAunicodeString (" + (v == null ? 0 : v.length) + " octets)");
        }
        long n = ((v[0] & 0xFFL) << 24) | ((v[1] & 0xFFL) << 16) | ((v[2] & 0xFFL) << 8) | (v[3] & 0xFFL);
        if (n < 0 || 4 + 2 * n > v.length) {
            throw new IllegalArgumentException("HLAunicodeString of " + n + " characters in " + v.length + " octets");
        }
        return new String(v, 4, (int) (2 * n), StandardCharsets.UTF_16BE);
    }

    /** Encodes an HLAunicodeString (for tests and test RTIs). */
    public static byte[] encodeUnicodeString(String s) {
        byte[] t = s.getBytes(StandardCharsets.UTF_16BE);
        int n = t.length / 2;
        byte[] out = new byte[4 + t.length];
        out[0] = (byte) (n >>> 24);
        out[1] = (byte) (n >>> 16);
        out[2] = (byte) (n >>> 8);
        out[3] = (byte) n;
        System.arraycopy(t, 0, out, 4, t.length);
        return out;
    }

    /** @return a short fingerprint of an FDD (to notice a change, and to show) */
    public static String fingerprint(String fdd) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(fdd.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", d[i] & 0xFF));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(fdd.hashCode());
        }
    }

    private static final java.util.regex.Pattern OBJECT_MODEL_TAG = java.util.regex.Pattern.compile(
            "<(?:[A-Za-z_][\\w.-]*:)?objectModel\\b[^>]*>");
    private static final java.util.regex.Pattern NAMESPACE_ATTRIBUTE = java.util.regex.Pattern.compile(
            "\\sxmlns(?::[A-Za-z_][\\w.-]*)?\\s*=\\s*[\"']([^\"']*)[\"']");

    /**
     * The OMT format the FDD is written in, from the namespace of its {@code objectModel}
     * element. An RTI may report it in another format than the API the federate joined with:
     * an HLA 4 RTI (e.g. pRTI 6) reports a 1516-2025 FDD to 1516-2010 federates too.
     *
     * @return the format, or null when the FDD names no known OMT namespace
     */
    public static HlaVersion format(String fdd) {
        if (fdd == null) {
            return null;
        }
        java.util.regex.Matcher tag = OBJECT_MODEL_TAG.matcher(fdd);
        if (!tag.find()) {
            return null;
        }
        java.util.regex.Matcher ns = NAMESPACE_ATTRIBUTE.matcher(tag.group());
        while (ns.find()) {
            HlaVersion v = HlaVersion.fromNamespace(ns.group(1));
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /**
     * @param hla4 the federate's API is IEEE 1516-2025
     * @return {@code ", written in the IEEE 1516-2025 format"} (or 2010) when the FDD's format is
     *         not the API's, else ""
     */
    public static String formatNote(String fdd, boolean hla4) {
        HlaVersion f = format(fdd);
        return f == null || (f == HlaVersion.HLA_4) == hla4 ? "" : ", written in the " + f.getStandard() + " format";
    }

    /**
     * Builds the model from an FDD, read in the format it is written in (see {@link #format}).
     * Only the reading depends on it: class, attribute and datatype names and encodings are the
     * same for both APIs. When the FDD restates the MIM classes ({@code HLAmanager}) differently
     * from the standard MIM the model builder uses, it is built again without them.
     *
     * @param hla4 the endpoint's API is IEEE 1516-2025 (else 1516-2010): the format used when
     *             the FDD names no OMT namespace
     * @throws IemException when the FDD cannot be read as a FOM
     */
    public static InformationExchangeModel toModel(String fdd, boolean hla4) throws IemException {
        if (fdd == null || fdd.trim().isEmpty()) {
            throw new IemException("the federation's FDD is empty");
        }
        HlaVersion f = format(fdd);
        if (f != null) {
            hla4 = f == HlaVersion.HLA_4;
        }
        try {
            return build(fdd, hla4);
        } catch (IemException first) {
            String stripped;
            try {
                stripped = withoutMim(fdd);
            } catch (Exception e) {
                throw first;
            }
            if (stripped.equals(fdd)) {
                throw first;
            }
            try {
                return build(stripped, hla4);
            } catch (IemException second) {
                throw first;
            }
        }
    }

    private static InformationExchangeModel build(String xml, boolean hla4) throws IemException {
        List<String> one = Collections.singletonList(xml);
        return hla4 ? InformationExchangeModel.fromHla4(one) : InformationExchangeModel.fromHlaEvolved(one);
    }

    /** The FDD without the HLAmanager object and interaction class trees. */
    static String withoutMim(String fdd) throws Exception {
        javax.xml.parsers.DocumentBuilderFactory f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        org.w3c.dom.Document d = f.newDocumentBuilder().parse(new ByteArrayInputStream(fdd.getBytes(StandardCharsets.UTF_8)));
        boolean changed = false;
        for (String tag : new String[] {"objectClass", "interactionClass"}) {
            org.w3c.dom.NodeList l = d.getElementsByTagName(tag);
            List<org.w3c.dom.Element> drop = new ArrayList<org.w3c.dom.Element>();
            for (int i = 0; i < l.getLength(); i++) {
                org.w3c.dom.Element e = (org.w3c.dom.Element) l.item(i);
                if ("HLAmanager".equals(nameOf(e))) {
                    drop.add(e);
                }
            }
            for (org.w3c.dom.Element e : drop) {
                e.getParentNode().removeChild(e);
                changed = true;
            }
        }
        if (!changed) {
            return fdd;
        }
        javax.xml.transform.Transformer t = javax.xml.transform.TransformerFactory.newInstance().newTransformer();
        StringWriter w = new StringWriter();
        t.transform(new javax.xml.transform.dom.DOMSource(d), new javax.xml.transform.stream.StreamResult(w));
        return w.toString();
    }

    private static String nameOf(org.w3c.dom.Element e) {
        for (org.w3c.dom.Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof org.w3c.dom.Element && "name".equals(((org.w3c.dom.Element) c).getTagName())) {
                return c.getTextContent().trim();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ over an RtiSession

    /**
     * Reads the FDD over a joined session. Call {@link #start()} after the join, forward the
     * session's callbacks to {@link #discovered}, {@link #reflected} and {@link #removed} (they
     * answer true for the MOM object, which the owner then leaves alone), and {@link #await} the
     * first value. Every value that differs from the one before, the first included, goes to
     * {@link #onValue} (a federate that joins with more modules changes it).
     * The RTI is only called from {@code start()} and {@code await()}, never from a callback.
     */
    public static final class Reader {
        private final RtiSession session;
        private final String className;
        private final String attributeName;
        private volatile int cls;
        private volatile int attr;
        private volatile long instance = -1;
        private volatile String fdd;
        private final CountDownLatch first = new CountDownLatch(1);
        private volatile Consumer<String> valueListener;

        /** Reads {@link #FDD_ATTRIBUTE} of {@link #FEDERATION_CLASS}. */
        public Reader(RtiSession session) {
            this(session, FEDERATION_CLASS, FDD_ATTRIBUTE);
        }

        /** Reads another class / attribute (an RTI that names them differently). */
        public Reader(RtiSession session, String className, String attributeName) {
            this.session = session;
            this.className = className == null || className.trim().isEmpty() ? FEDERATION_CLASS : className.trim();
            this.attributeName = attributeName == null || attributeName.trim().isEmpty() ? FDD_ATTRIBUTE : attributeName.trim();
        }

        /**
         * Subscribes the MOM class and asks for the value.
         *
         * @throws RtiSessionException when the RTI does not know the class or attribute
         */
        public void start() throws RtiSessionException {
            cls = session.objectClass(className);
            attr = session.attribute(cls, attributeName);
            session.subscribeObjectClass(cls, new int[] {attr});
            session.requestClassAttributeUpdate(cls, new int[] {attr}, null);
        }

        /**
         * Waits for the first value, asking again every two seconds.
         *
         * @return the FDD text
         * @throws RtiSessionException when no value came within the time
         */
        public String await(long timeoutMillis) throws RtiSessionException {
            long end = System.currentTimeMillis() + timeoutMillis;
            try {
                while (true) {
                    long left = end - System.currentTimeMillis();
                    if (left <= 0) {
                        break;
                    }
                    if (first.await(Math.min(left, 2000), TimeUnit.MILLISECONDS)) {
                        return fdd;
                    }
                    session.requestClassAttributeUpdate(cls, new int[] {attr}, null);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new RtiSessionException("FddNotProvided", "the RTI gave no " + attributeName + " of " + className
                    + " within " + (timeoutMillis / 1000) + " s (" + (instance < 0 ? "the federation object was not discovered"
                    : "the federation object was discovered, but its value did not come") + ")", null);
        }

        /** @return the session's MOM federation class (0 before {@link #start}) */
        public int objectClass() {
            return cls;
        }

        /** @return the session's FDD attribute (0 before {@link #start}) */
        public int attribute() {
            return attr;
        }

        /** @param l receives every value that differs from the one before, the first included */
        public void onValue(Consumer<String> l) {
            this.valueListener = l;
        }

        /** @return the last value, or null before the first */
        public String fdd() {
            return fdd;
        }

        /** @return true when the callback was about the MOM object (the owner ignores it then) */
        public boolean discovered(long inst, int objectClass) {
            if (cls == 0 || objectClass != cls) {
                return false;
            }
            instance = inst;
            return true;
        }

        /** @return true when the callback was about the MOM object */
        public boolean reflected(long inst, int[] attributes, byte[][] values) {
            if (inst != instance) {
                return false;
            }
            for (int i = 0; i < attributes.length; i++) {
                if (attributes[i] == attr && values[i] != null) {
                    String text;
                    try {
                        text = decodeUnicodeString(values[i]);
                    } catch (IllegalArgumentException e) {
                        return true;
                    }
                    String before = fdd;
                    fdd = text;
                    first.countDown();
                    Consumer<String> l = valueListener;
                    if (l != null && !text.equals(before)) {
                        l.accept(text);
                    }
                }
            }
            return true;
        }

        /** @return true when the callback was about the MOM object */
        public boolean removed(long inst) {
            if (inst != instance) {
                return false;
            }
            instance = -1;
            return true;
        }
    }
}
