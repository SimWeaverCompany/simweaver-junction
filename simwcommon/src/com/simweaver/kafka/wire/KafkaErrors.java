package com.simweaver.kafka.wire;

import java.util.HashMap;
import java.util.Map;

/** Kafka error codes this client reacts to or names in messages (the protocol's error table). */
public final class KafkaErrors {

    private KafkaErrors() {
    }

    public static final int UNKNOWN_SERVER_ERROR = -1;
    public static final int NONE = 0;
    public static final int OFFSET_OUT_OF_RANGE = 1;
    public static final int CORRUPT_MESSAGE = 2;
    public static final int UNKNOWN_TOPIC_OR_PARTITION = 3;
    public static final int LEADER_NOT_AVAILABLE = 5;
    public static final int NOT_LEADER_OR_FOLLOWER = 6;
    public static final int REQUEST_TIMED_OUT = 7;
    public static final int BROKER_NOT_AVAILABLE = 8;
    public static final int REPLICA_NOT_AVAILABLE = 9;
    public static final int MESSAGE_TOO_LARGE = 10;
    public static final int NETWORK_EXCEPTION = 13;
    public static final int COORDINATOR_LOAD_IN_PROGRESS = 14;
    public static final int COORDINATOR_NOT_AVAILABLE = 15;
    public static final int NOT_COORDINATOR = 16;
    public static final int INVALID_TOPIC_EXCEPTION = 17;
    public static final int RECORD_LIST_TOO_LARGE = 18;
    public static final int NOT_ENOUGH_REPLICAS = 19;
    public static final int NOT_ENOUGH_REPLICAS_AFTER_APPEND = 20;
    public static final int INVALID_REQUIRED_ACKS = 21;
    public static final int ILLEGAL_GENERATION = 22;
    public static final int INCONSISTENT_GROUP_PROTOCOL = 23;
    public static final int INVALID_GROUP_ID = 24;
    public static final int UNKNOWN_MEMBER_ID = 25;
    public static final int INVALID_SESSION_TIMEOUT = 26;
    public static final int REBALANCE_IN_PROGRESS = 27;
    public static final int TOPIC_AUTHORIZATION_FAILED = 29;
    public static final int GROUP_AUTHORIZATION_FAILED = 30;
    public static final int CLUSTER_AUTHORIZATION_FAILED = 31;
    public static final int UNSUPPORTED_SASL_MECHANISM = 33;
    public static final int ILLEGAL_SASL_STATE = 34;
    public static final int UNSUPPORTED_VERSION = 35;
    public static final int INVALID_REQUEST = 42;
    public static final int SASL_AUTHENTICATION_FAILED = 58;
    public static final int KAFKA_STORAGE_ERROR = 56;
    public static final int LISTENER_NOT_FOUND = 72;
    public static final int FENCED_LEADER_EPOCH = 74;
    public static final int UNKNOWN_LEADER_EPOCH = 75;
    public static final int UNSUPPORTED_COMPRESSION_TYPE = 76;
    public static final int MEMBER_ID_REQUIRED = 79;
    public static final int FENCED_INSTANCE_ID = 82;
    public static final int INVALID_RECORD = 87;
    public static final int GROUP_ID_NOT_FOUND = 69;
    public static final int THROTTLING_QUOTA_EXCEEDED = 89;

    private static final Map<Integer, String> NAMES = new HashMap<Integer, String>();

    static {
        name(UNKNOWN_SERVER_ERROR, "UNKNOWN_SERVER_ERROR");
        name(NONE, "NONE");
        name(OFFSET_OUT_OF_RANGE, "OFFSET_OUT_OF_RANGE");
        name(CORRUPT_MESSAGE, "CORRUPT_MESSAGE");
        name(UNKNOWN_TOPIC_OR_PARTITION, "UNKNOWN_TOPIC_OR_PARTITION");
        name(4, "INVALID_FETCH_SIZE");
        name(LEADER_NOT_AVAILABLE, "LEADER_NOT_AVAILABLE");
        name(NOT_LEADER_OR_FOLLOWER, "NOT_LEADER_OR_FOLLOWER");
        name(REQUEST_TIMED_OUT, "REQUEST_TIMED_OUT");
        name(BROKER_NOT_AVAILABLE, "BROKER_NOT_AVAILABLE");
        name(REPLICA_NOT_AVAILABLE, "REPLICA_NOT_AVAILABLE");
        name(MESSAGE_TOO_LARGE, "MESSAGE_TOO_LARGE");
        name(12, "OFFSET_METADATA_TOO_LARGE");
        name(NETWORK_EXCEPTION, "NETWORK_EXCEPTION");
        name(COORDINATOR_LOAD_IN_PROGRESS, "COORDINATOR_LOAD_IN_PROGRESS");
        name(COORDINATOR_NOT_AVAILABLE, "COORDINATOR_NOT_AVAILABLE");
        name(NOT_COORDINATOR, "NOT_COORDINATOR");
        name(INVALID_TOPIC_EXCEPTION, "INVALID_TOPIC_EXCEPTION");
        name(RECORD_LIST_TOO_LARGE, "RECORD_LIST_TOO_LARGE");
        name(NOT_ENOUGH_REPLICAS, "NOT_ENOUGH_REPLICAS");
        name(NOT_ENOUGH_REPLICAS_AFTER_APPEND, "NOT_ENOUGH_REPLICAS_AFTER_APPEND");
        name(INVALID_REQUIRED_ACKS, "INVALID_REQUIRED_ACKS");
        name(ILLEGAL_GENERATION, "ILLEGAL_GENERATION");
        name(INCONSISTENT_GROUP_PROTOCOL, "INCONSISTENT_GROUP_PROTOCOL");
        name(INVALID_GROUP_ID, "INVALID_GROUP_ID");
        name(UNKNOWN_MEMBER_ID, "UNKNOWN_MEMBER_ID");
        name(INVALID_SESSION_TIMEOUT, "INVALID_SESSION_TIMEOUT");
        name(REBALANCE_IN_PROGRESS, "REBALANCE_IN_PROGRESS");
        name(28, "INVALID_COMMIT_OFFSET_SIZE");
        name(TOPIC_AUTHORIZATION_FAILED, "TOPIC_AUTHORIZATION_FAILED");
        name(GROUP_AUTHORIZATION_FAILED, "GROUP_AUTHORIZATION_FAILED");
        name(CLUSTER_AUTHORIZATION_FAILED, "CLUSTER_AUTHORIZATION_FAILED");
        name(32, "INVALID_TIMESTAMP");
        name(UNSUPPORTED_SASL_MECHANISM, "UNSUPPORTED_SASL_MECHANISM");
        name(ILLEGAL_SASL_STATE, "ILLEGAL_SASL_STATE");
        name(UNSUPPORTED_VERSION, "UNSUPPORTED_VERSION");
        name(INVALID_REQUEST, "INVALID_REQUEST");
        name(43, "UNSUPPORTED_FOR_MESSAGE_FORMAT");
        name(44, "POLICY_VIOLATION");
        name(KAFKA_STORAGE_ERROR, "KAFKA_STORAGE_ERROR");
        name(SASL_AUTHENTICATION_FAILED, "SASL_AUTHENTICATION_FAILED");
        name(LISTENER_NOT_FOUND, "LISTENER_NOT_FOUND");
        name(FENCED_LEADER_EPOCH, "FENCED_LEADER_EPOCH");
        name(UNKNOWN_LEADER_EPOCH, "UNKNOWN_LEADER_EPOCH");
        name(UNSUPPORTED_COMPRESSION_TYPE, "UNSUPPORTED_COMPRESSION_TYPE");
        name(MEMBER_ID_REQUIRED, "MEMBER_ID_REQUIRED");
        name(FENCED_INSTANCE_ID, "FENCED_INSTANCE_ID");
        name(INVALID_RECORD, "INVALID_RECORD");
        name(THROTTLING_QUOTA_EXCEEDED, "THROTTLING_QUOTA_EXCEEDED");
        name(68, "NON_EMPTY_GROUP");
        name(GROUP_ID_NOT_FOUND, "GROUP_ID_NOT_FOUND");
        name(37, "INVALID_PARTITIONS");
        name(39, "INVALID_REPLICATION_FACTOR");
        name(45, "OUT_OF_ORDER_SEQUENCE_NUMBER");
        name(54, "INVALID_PRINCIPAL_TYPE");
    }

    private static void name(int code, String n) {
        NAMES.put(code, n);
    }

    /** @return the error's name, or "error <code>" */
    public static String name(int code) {
        String n = NAMES.get(code);
        return n == null ? "error " + code : n;
    }

    /** @return true for errors that go away by refreshing metadata and trying again */
    public static boolean isRetriable(int code) {
        switch (code) {
            case CORRUPT_MESSAGE:
            case UNKNOWN_TOPIC_OR_PARTITION:
            case LEADER_NOT_AVAILABLE:
            case NOT_LEADER_OR_FOLLOWER:
            case REQUEST_TIMED_OUT:
            case REPLICA_NOT_AVAILABLE:
            case NETWORK_EXCEPTION:
            case COORDINATOR_LOAD_IN_PROGRESS:
            case COORDINATOR_NOT_AVAILABLE:
            case NOT_COORDINATOR:
            case NOT_ENOUGH_REPLICAS:
            case NOT_ENOUGH_REPLICAS_AFTER_APPEND:
            case KAFKA_STORAGE_ERROR:
            case FENCED_LEADER_EPOCH:
            case UNKNOWN_LEADER_EPOCH:
            case THROTTLING_QUOTA_EXCEEDED:
                return true;
            default:
                return false;
        }
    }

    /** @return true for errors that mean the partition's leader moved (refresh metadata) */
    public static boolean isLeaderMoved(int code) {
        return code == NOT_LEADER_OR_FOLLOWER || code == LEADER_NOT_AVAILABLE || code == UNKNOWN_TOPIC_OR_PARTITION
                || code == FENCED_LEADER_EPOCH || code == UNKNOWN_LEADER_EPOCH || code == REPLICA_NOT_AVAILABLE
                || code == KAFKA_STORAGE_ERROR;
    }
}
