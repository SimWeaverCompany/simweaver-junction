package com.simweaver.common.infra;

import com.simweaver.common.json.Json;

/**
 * Who has the contact: a Rift datasource or a Junction gateway's endpoint. The {@link #key()}
 * stays the same when the same datasource configuration or gateway is started again, so a
 * breakout tab finds its items after a restart.
 */
public final class InfraSource {

    /** The application that holds the connection. */
    public enum Kind {
        RIFT("Rift"), JUNCTION("Junction");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final Kind kind;
    private final String key;
    private final String label;
    private final String link;

    private InfraSource(Kind kind, String key, String label, String link) {
        this.kind = kind;
        this.key = key;
        this.label = label;
        this.link = link;
    }

    /**
     * A Rift datasource.
     *
     * @param stable what identifies it across restarts (configuration name and federation or exercise)
     * @param name   the running datasource's name (shown)
     * @param id     the running datasource's id (for the link to its page), may be null
     */
    public static InfraSource rift(String stable, String name, String id) {
        String n = name == null ? "datasource" : name;
        return new InfraSource(Kind.RIFT, "rift:" + (stable == null ? n : stable), "Rift datasource " + n,
                id == null ? "index.html" : "datasource.html?id=" + id);
    }

    /** A Rift datasource identified by its name. */
    public static InfraSource rift(String name, String id) {
        return rift(name, name, id);
    }

    /**
     * A Junction gateway endpoint.
     *
     * @param gateway  the gateway's runtime name (configuration id, plus instance)
     * @param endpoint the endpoint id
     */
    public static InfraSource junction(String gateway, String endpoint) {
        return new InfraSource(Kind.JUNCTION, "junction:" + gateway + "/" + endpoint,
                "Junction " + gateway + " / " + endpoint, "junction.html");
    }

    public Kind kind() {
        return kind;
    }

    /** @return stable identity, e.g. {@code rift:dis7 - exercise 1} or {@code junction:hla3-dis7/dis} */
    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    /** @return a page of this server that shows the source (relative URL) */
    public String link() {
        return link;
    }

    Json.JsonObject toJson() {
        return Json.object().put("kind", kind.label().toLowerCase(java.util.Locale.ROOT)).put("key", key)
                .put("label", label).put("link", link);
    }

    @Override
    public String toString() {
        return label;
    }
}
