package com.simweaver.rift.comms;

/**
 * Who sends on a channel (0.12.0): a Talk client, or an RPR / DIS radio transmitter object. Guarded
 * by its {@link CommsTracker}.
 */
final class CommsSource {

    /** Number in the audio frames (unique within the datasource). */
    final int id;
    final String key;               // Talk: sender UUID hex; radio: the transmitter's instance name
    final String kind;              // "client" or "transmitter"
    String label;
    String equipment = "";          // Talk: the radio equipment's name, when known
    long lastActive;                // ms of the last frame / message
    long frames;

    CommsSource(int id, String key, String kind, String label) {
        this.id = id;
        this.key = key;
        this.kind = kind;
        this.label = label;
    }
}
