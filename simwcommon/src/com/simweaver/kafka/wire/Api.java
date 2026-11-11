package com.simweaver.kafka.wire;

/**
 * The Kafka API keys this client uses and the request versions it can write. All are
 * non-flexible versions that brokers from Kafka 2.1 to 4.x accept (Kafka 4.0 removed the versions
 * older than 2.1's, KIP-896); the version used with a broker is the highest both sides support
 * (from ApiVersions).
 */
public final class Api {

    private Api() {
    }

    public static final int PRODUCE = 0;
    public static final int FETCH = 1;
    public static final int LIST_OFFSETS = 2;
    public static final int METADATA = 3;
    public static final int OFFSET_COMMIT = 8;
    public static final int OFFSET_FETCH = 9;
    public static final int FIND_COORDINATOR = 10;
    public static final int JOIN_GROUP = 11;
    public static final int HEARTBEAT = 12;
    public static final int LEAVE_GROUP = 13;
    public static final int SYNC_GROUP = 14;
    public static final int SASL_HANDSHAKE = 17;
    public static final int API_VERSIONS = 18;
    public static final int SASL_AUTHENTICATE = 36;

    /** @return the lowest version of the API this client writes, or -1 for an API it does not use */
    public static int minVersion(int api) {
        switch (api) {
            case PRODUCE: return 3;
            case FETCH: return 4;
            case LIST_OFFSETS: return 1;
            case METADATA: return 1;
            case OFFSET_COMMIT: return 2;
            case OFFSET_FETCH: return 1;
            case FIND_COORDINATOR: return 1;
            case JOIN_GROUP: return 2;
            case HEARTBEAT: return 1;
            case LEAVE_GROUP: return 1;
            case SYNC_GROUP: return 1;
            case SASL_HANDSHAKE: return 1;
            case API_VERSIONS: return 0;
            case SASL_AUTHENTICATE: return 0;
            default: return -1;
        }
    }

    /** @return the highest version of the API this client writes (the last non-flexible one it knows) */
    public static int maxVersion(int api) {
        switch (api) {
            case PRODUCE: return 8;
            case FETCH: return 11;
            case LIST_OFFSETS: return 5;
            case METADATA: return 7;
            case OFFSET_COMMIT: return 7;
            case OFFSET_FETCH: return 5;
            case FIND_COORDINATOR: return 2;
            case JOIN_GROUP: return 5;
            case HEARTBEAT: return 3;
            case LEAVE_GROUP: return 2;
            case SYNC_GROUP: return 3;
            case SASL_HANDSHAKE: return 1;
            case API_VERSIONS: return 0;
            case SASL_AUTHENTICATE: return 1;
            default: return -1;
        }
    }

    /** @return a name for messages */
    public static String name(int api) {
        switch (api) {
            case PRODUCE: return "Produce";
            case FETCH: return "Fetch";
            case LIST_OFFSETS: return "ListOffsets";
            case METADATA: return "Metadata";
            case OFFSET_COMMIT: return "OffsetCommit";
            case OFFSET_FETCH: return "OffsetFetch";
            case FIND_COORDINATOR: return "FindCoordinator";
            case JOIN_GROUP: return "JoinGroup";
            case HEARTBEAT: return "Heartbeat";
            case LEAVE_GROUP: return "LeaveGroup";
            case SYNC_GROUP: return "SyncGroup";
            case SASL_HANDSHAKE: return "SaslHandshake";
            case API_VERSIONS: return "ApiVersions";
            case SASL_AUTHENTICATE: return "SaslAuthenticate";
            default: return "api " + api;
        }
    }
}
