package com.simweaver.common.iem;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Deque;

import javax.xml.stream.Location;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Turns one entry of the caller's FOM list (a file path, a {@code file:} URI, or inline XML text)
 * into an {@link XmlNode} tree. Uses the JDK StAX parser with DTD processing and external entities
 * disabled (no XXE), and records line numbers for diagnostics.
 */
final class XmlLoader {

    /** Result of loading one list entry. */
    static final class Loaded {
        final String label;      // short label for messages, e.g. "NETN-BASE.xml" or "<inline #3>"
        final String location;   // absolute path, or null for inline XML
        final XmlNode root;

        Loaded(String label, String location, XmlNode root) {
            this.label = label;
            this.location = location;
            this.root = root;
        }
    }

    private XmlLoader() {
    }

    /** True when the string is inline XML rather than a path. */
    static boolean looksLikeXml(String spec) {
        int i = 0;
        int n = spec.length();
        if (n > 0 && spec.charAt(0) == '\uFEFF') {
            i = 1;
        }
        while (i < n && Character.isWhitespace(spec.charAt(i))) {
            i++;
        }
        return i < n && spec.charAt(i) == '<';
    }

    /**
     * Loads one entry.
     *
     * @param spec  path, file URI or inline XML
     * @param index position in the caller's list (0-based), used in labels and messages
     */
    static Loaded load(String spec, int index) throws IemException {
        if (spec == null) {
            throw new IemException("FOM entry #" + index + " is null");
        }
        if (spec.trim().isEmpty()) {
            throw new IemException("FOM entry #" + index + " is an empty string");
        }
        if (looksLikeXml(spec)) {
            // drop a BOM and leading whitespace: an XML declaration must be the very first thing
            int start = spec.charAt(0) == '\uFEFF' ? 1 : 0;
            while (start < spec.length() && Character.isWhitespace(spec.charAt(start))) {
                start++;
            }
            String xml = spec.substring(start);
            String label = "<inline #" + index + ">";
            return new Loaded(label, null, parse(new StringReader(xml), null, label));
        }
        Path path = toPath(spec.trim(), index);
        if (!Files.exists(path)) {
            throw new IemException("FOM entry #" + index + ": file not found: " + path.toAbsolutePath());
        }
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IemException("FOM entry #" + index + ": not a readable file: " + path.toAbsolutePath());
        }
        String label = path.getFileName() == null ? path.toString() : path.getFileName().toString();
        InputStream in = null;
        try {
            in = new BufferedInputStream(Files.newInputStream(path));
            return new Loaded(label, path.toAbsolutePath().toString(), parse(null, in, label));
        } catch (IOException e) {
            throw new IemException("FOM entry #" + index + ": cannot read " + path.toAbsolutePath() + ": "
                    + e.getMessage(), e);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignore) {
                    // nothing useful to do
                }
            }
        }
    }

    private static Path toPath(String spec, int index) throws IemException {
        try {
            if (spec.regionMatches(true, 0, "file:", 0, 5)) {
                return Paths.get(URI.create(spec));
            }
            return Paths.get(spec);
        } catch (InvalidPathException e) {
            throw new IemException("FOM entry #" + index + " is neither XML nor a valid path: " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new IemException("FOM entry #" + index + " is not a valid file URI: " + e.getMessage(), e);
        }
    }

    private static XMLInputFactory newFactory() {
        XMLInputFactory f = XMLInputFactory.newInstance();
        setIfSupported(f, XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        setIfSupported(f, XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        setIfSupported(f, XMLInputFactory.IS_NAMESPACE_AWARE, Boolean.TRUE);
        setIfSupported(f, XMLInputFactory.IS_COALESCING, Boolean.TRUE);
        return f;
    }

    private static void setIfSupported(XMLInputFactory f, String prop, Object value) {
        try {
            if (f.isPropertySupported(prop)) {
                f.setProperty(prop, value);
            }
        } catch (IllegalArgumentException ignore) {
            // property not supported by this StAX implementation
        }
    }

    private static XmlNode parse(Reader reader, InputStream in, String label) throws IemException {
        XMLStreamReader r = null;
        try {
            XMLInputFactory f = newFactory();
            r = reader != null ? f.createXMLStreamReader(reader) : f.createXMLStreamReader(in);
            Deque<XmlNode> stack = new ArrayDeque<XmlNode>();
            XmlNode root = null;
            while (r.hasNext()) {
                int ev = r.next();
                switch (ev) {
                    case XMLStreamConstants.START_ELEMENT: {
                        XmlNode node = new XmlNode(r.getNamespaceURI(), r.getLocalName(), lineOf(r));
                        for (int i = 0; i < r.getAttributeCount(); i++) {
                            node.addAttribute(r.getAttributeLocalName(i), r.getAttributeValue(i));
                        }
                        if (stack.isEmpty()) {
                            root = node;
                        } else {
                            stack.peek().addChild(node);
                        }
                        stack.push(node);
                        break;
                    }
                    case XMLStreamConstants.END_ELEMENT:
                        stack.pop();
                        break;
                    case XMLStreamConstants.CHARACTERS:
                    case XMLStreamConstants.CDATA:
                    case XMLStreamConstants.SPACE:
                        if (!stack.isEmpty()) {
                            stack.peek().appendText(r.getText());
                        }
                        break;
                    case XMLStreamConstants.ENTITY_REFERENCE:
                        throw new IemException(label + ":" + lineOf(r)
                                + ": entity references are not supported in FOM files ('" + r.getLocalName() + "')");
                    default:
                        break;
                }
            }
            if (root == null) {
                throw new IemException(label + ": document has no root element");
            }
            return root;
        } catch (XMLStreamException e) {
            Location loc = e.getLocation();
            String where = loc != null && loc.getLineNumber() > 0 ? label + ":" + loc.getLineNumber() : label;
            String msg = e.getMessage();
            // StAX messages often start with "ParseError at [row,col]:[..]\nMessage: ..." -- keep the tail
            if (msg != null && msg.contains("Message:")) {
                msg = msg.substring(msg.indexOf("Message:") + 8).trim();
            }
            throw new IemException(where + ": malformed XML: " + msg, e);
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (XMLStreamException ignore) {
                    // ignore
                }
            }
        }
    }

    private static int lineOf(XMLStreamReader r) {
        Location loc = r.getLocation();
        return loc == null ? -1 : loc.getLineNumber();
    }
}
