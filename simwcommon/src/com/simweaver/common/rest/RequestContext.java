package com.simweaver.common.rest;

import java.util.function.Supplier;

/**
 * Who asked for what the current thread is doing (0.9.22), for the audit part of the log: the
 * REST request handler sets the client's address while it runs an API call, and work started by
 * the server itself (autostart) names itself with {@link #runAs}. Everything an API call does
 * on its own thread (creating a datasource, starting a gateway) can then log who asked.
 */
public final class RequestContext {

    private static final ThreadLocal<String> ACTOR = new ThreadLocal<String>();

    private RequestContext() {
    }

    /**
     * Sets the actor for the current thread.
     *
     * @param remoteAddress the client's address (IP)
     * @param forwardedFor  the request's X-Forwarded-For header as the client sent it, or null
     */
    static void enter(String remoteAddress, String forwardedFor) {
        enter(remoteAddress, forwardedFor, null);
    }

    /**
     * Sets the actor for the current thread.
     *
     * @param remoteAddress the client's address (IP)
     * @param forwardedFor  the request's X-Forwarded-For header as the client sent it, or null
     * @param credential    who API authentication let in (0.11.0), e.g. {@code token ops-dashboard}, or null
     */
    static void enter(String remoteAddress, String forwardedFor, String credential) {
        String a = remoteAddress == null || remoteAddress.isEmpty() ? "unknown client" : remoteAddress;
        if (forwardedFor != null && !forwardedFor.trim().isEmpty()) {
            a = a + " (forwarded for " + clean(forwardedFor) + ")";
        }
        if (credential != null && !credential.isEmpty()) {
            a = a + " (" + clean(credential) + ")";
        }
        ACTOR.set(a);
    }

    static void leave() {
        ACTOR.remove();
    }

    /** 0.14.0: {@link #enter(String, String, String)} for handlers outside this package (MCP). */
    public static void enterRequest(String remoteAddress, String forwardedFor, String credential) {
        enter(remoteAddress, forwardedFor, credential);
    }

    /** 0.14.0: ends {@link #enterRequest}. */
    public static void leaveRequest() {
        leave();
    }

    /**
     * @return who the current work is for: a client address such as {@code 10.0.0.5}, a name given
     *         with {@link #runAs} (e.g. {@code autostart}), or {@code server} when neither
     */
    public static String actor() {
        String a = ACTOR.get();
        return a == null ? "server" : a;
    }

    /** Runs work in the name of {@code actor} (e.g. "autostart") and returns its result. */
    public static <T> T runAs(String actor, Supplier<T> work) {
        String before = ACTOR.get();
        ACTOR.set(actor);
        try {
            return work.get();
        } finally {
            if (before == null) {
                ACTOR.remove();
            } else {
                ACTOR.set(before);
            }
        }
    }

    /** Runs work in the name of {@code actor}. */
    public static void runAs(String actor, Runnable work) {
        runAs(actor, () -> {
            work.run();
            return null;
        });
    }

    /** A header value as a short, printable text (it is the client's claim, not checked). */
    private static String clean(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < 120; i++) {
            char c = s.charAt(i);
            b.append(c >= 0x20 && c < 0x7F ? c : '?');
        }
        return b.toString().trim();
    }
}
