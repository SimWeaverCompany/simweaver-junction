package com.simweaver.common.iem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal immutable-after-load XML element tree with line numbers.
 * Package private; only used while parsing FOM modules.
 */
final class XmlNode {

    private final String namespace;
    private final String localName;
    private final int line;
    private final Map<String, String> attributes = new LinkedHashMap<String, String>();
    private final List<XmlNode> children = new ArrayList<XmlNode>();
    private final StringBuilder text = new StringBuilder();

    XmlNode(String namespace, String localName, int line) {
        this.namespace = namespace == null ? "" : namespace;
        this.localName = localName;
        this.line = line;
    }

    String getNamespace() {
        return namespace;
    }

    String getName() {
        return localName;
    }

    int getLine() {
        return line;
    }

    void addAttribute(String name, String value) {
        attributes.put(name, value);
    }

    void addChild(XmlNode child) {
        children.add(child);
    }

    void appendText(String s) {
        text.append(s);
    }

    Map<String, String> getAttributes() {
        return Collections.unmodifiableMap(attributes);
    }

    String getAttribute(String name) {
        return attributes.get(name);
    }

    List<XmlNode> getChildren() {
        return Collections.unmodifiableList(children);
    }

    boolean hasChildren() {
        return !children.isEmpty();
    }

    /** Text content trimmed; never null. */
    String getText() {
        return text.toString().trim();
    }

    /** First direct child with the given local name, or null. */
    XmlNode child(String name) {
        for (XmlNode c : children) {
            if (c.localName.equals(name)) {
                return c;
            }
        }
        return null;
    }

    /** All direct children with the given local name. */
    List<XmlNode> children(String name) {
        List<XmlNode> out = new ArrayList<XmlNode>();
        for (XmlNode c : children) {
            if (c.localName.equals(name)) {
                out.add(c);
            }
        }
        return out;
    }

    /** Trimmed text of the first child with the given name, or null if absent. */
    String childText(String name) {
        XmlNode c = child(name);
        return c == null ? null : c.getText();
    }

    /**
     * Trimmed text of the first child with the given name; null when absent or empty.
     */
    String childTextOrNull(String name) {
        String s = childText(name);
        return (s == null || s.isEmpty()) ? null : s;
    }

    @Override
    public String toString() {
        return "<" + localName + "> line " + line;
    }
}
