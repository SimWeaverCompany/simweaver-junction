package com.simweaver.rift.api;

/**
 * XrSseHub topic names of the datasource page.
 *
 * Must match the front end (js/datasource.js, function topics()) and section 7.2 of the
 * datasource page contract. Topics are matched exactly by the hub; dsid is part of every
 * topic because one hub serves all datasources and instance ids ("o1042") are only unique
 * within one datasource.
 */
public final class RiftTopics {

    /** datasource.state, datasource.removed, interaction.stats (publishJson). */
    public static String ds(String dsid) {
        return "ds:" + dsid;
    }

    /** { "&lt;iid&gt;": listView | null } (publishPatch). */
    public static String objects(String dsid) {
        return "ds:" + dsid + ":objects";
    }

    /** { "&lt;classnameq&gt;": count } (publishPatch). */
    public static String classes(String dsid) {
        return "ds:" + dsid + ":classes";
    }

    /** One "interaction" event per interaction (publish). */
    public static String interactions(String dsid) {
        return "ds:" + dsid + ":interactions";
    }

    /** { "&lt;attribute&gt;": attribute, "@rev", "@lastupdated", ... } (publishPatch). */
    public static String object(String dsid, String externalId) {
        return "obj:" + dsid + ":" + externalId;
    }

    private RiftTopics() {
    }
}
