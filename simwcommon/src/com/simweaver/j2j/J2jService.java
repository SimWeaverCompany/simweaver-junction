package com.simweaver.j2j;

import com.simweaver.common.config.SimwProperties;
import com.simweaver.common.logging.Events;
import com.simweaver.j2j.admin.AdminPlane;
import com.simweaver.j2j.config.J2jConfig;
import com.simweaver.j2j.session.J2jSessions;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * J2J as the server hosts it: owns the running {@link J2jNode}, reloads the member list, keeps the
 * latest J2J log events for the page, and tells listeners (the REST API's live updates) about them.
 * The server's only J2J objects are this service and its REST API.
 */
public final class J2jService {

    private static final Logger LOG = Logger.getLogger("com.simweaver.j2j");
    private static final int EVENT_RING = 500;

    private final SimwProperties server;
    private final Path networkFile;
    private final Path keyFile;
    private final String software;
    private volatile J2jNode node;
    private volatile AdminPlane.StatusSource statusSource;
    private final Deque<LogRecord> events = new ArrayDeque<LogRecord>();
    private final List<Consumer<LogRecord>> eventListeners = new CopyOnWriteArrayList<Consumer<LogRecord>>();
    private final Handler capture;
    private final J2jSessions sessions;
    private static volatile J2jService global;

    /** @return the server's J2J service, or null (set by the server at start-up) */
    public static J2jService global() {
        return global;
    }

    public static void setGlobal(J2jService s) {
        global = s;
    }

    /** @return "off", "running" or "error" for the top bar */
    public String state() {
        J2jNode n = node;
        return n == null ? "off" : n.state();
    }

    public J2jService(SimwProperties server, Path networkFile, Path keyFile, String software) {
        this.server = server;
        this.networkFile = networkFile;
        this.keyFile = keyFile;
        this.software = software;
        this.sessions = new J2jSessions(Math.max(1, server.getInt("j2j.session.lostseconds", 10)) * 1000L);
        this.capture = new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (Events.tagOf(r) == null) {
                    return;
                }
                synchronized (events) {
                    events.addFirst(r);
                    while (events.size() > EVENT_RING) {
                        events.removeLast();
                    }
                }
                for (Consumer<LogRecord> l : eventListeners) {
                    try {
                        l.accept(r);
                    } catch (RuntimeException ignored) {
                        // a listener's trouble is not ours
                    }
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        LOG.addHandler(capture);
    }

    /** Reads the configuration and starts J2J when it is complete. */
    public synchronized J2jNode start() {
        node = J2jNode.start(server, networkFile, keyFile, software);
        if (node.admin() != null && statusSource != null) {
            node.admin().setStatusSource(statusSource);
        }
        sessions.attach(node);
        return node;
    }

    /** @return the PDU sessions of this server's gateways (they outlive reloads) */
    public J2jSessions sessions() {
        return sessions;
    }

    public synchronized void stop() {
        sessions.close();
        if (node != null) {
            node.stop();
        }
        LOG.removeHandler(capture);
    }

    public void setStatusSource(AdminPlane.StatusSource s) {
        this.statusSource = s;
        J2jNode n = node;
        if (n != null && n.admin() != null) {
            n.admin().setStatusSource(s);
        }
    }

    public J2jNode node() {
        return node;
    }

    /** The result of a reload. */
    public static final class Reload {
        public final boolean applied;
        public final List<String> problems;
        public final List<String> added;
        public final List<String> removed;
        public final List<String> changed;
        public final String state;

        Reload(boolean applied, List<String> problems, List<String> added, List<String> removed, List<String> changed, String state) {
            this.applied = applied;
            this.problems = problems;
            this.added = added;
            this.removed = removed;
            this.changed = changed;
            this.state = state;
        }
    }

    /**
     * Reads {@code network.settings} and the key again. A file with problems is not applied while
     * J2J runs (the running list stays); otherwise J2J restarts with it when anything changed (its
     * links are set up again within seconds). Off ↔ on works without a server restart.
     */
    public synchronized Reload reload() {
        J2jConfig next = J2jConfig.load(server, networkFile, keyFile);
        J2jNode cur = node;
        J2jConfig prev = cur == null ? null : cur.config();
        List<String> added = new ArrayList<String>();
        List<String> removed = new ArrayList<String>();
        List<String> changed = new ArrayList<String>();
        if (prev != null) {
            for (String id : next.members().keySet()) {
                if (!prev.members().containsKey(id)) {
                    added.add(id);
                } else if (!same(prev, next, id)) {
                    changed.add(id);
                }
            }
            for (String id : prev.members().keySet()) {
                if (!next.members().containsKey(id)) {
                    removed.add(id);
                }
            }
        }
        if (!next.problems().isEmpty() && cur != null && cur.isRunning()) {
            return new Reload(false, next.problems(), added, removed, changed, cur.state());
        }
        boolean keysChanged = prev == null || !keyIds(prev).equals(keyIds(next));
        boolean anything = prev == null || !added.isEmpty() || !removed.isEmpty() || !changed.isEmpty() || keysChanged
                || !prev.networkId().equals(next.networkId()) || !prev.selfId().equals(next.selfId()) || prev.isEnabled() != next.isEnabled();
        if (anything) {
            sessions.detach();
            if (cur != null) {
                cur.stop();
            }
            node = J2jNode.start(server, networkFile, keyFile, software);
            if (node.admin() != null && statusSource != null) {
                node.admin().setStatusSource(statusSource);
            }
            sessions.attach(node);
            Events.notice(LOG, "j2j.members.reloaded", "J2J member list read again: " + (node.isRunning() ? "running" : node.state())
                    + (added.isEmpty() ? "" : ", added " + added) + (removed.isEmpty() ? "" : ", removed " + removed)
                    + (changed.isEmpty() ? "" : ", changed " + changed), "state", node.state());
        }
        return new Reload(true, next.problems(), added, removed, changed, node.state());
    }

    private static boolean same(J2jConfig a, J2jConfig b, String id) {
        return a.member(id).addresses().equals(b.member(id).addresses()) && a.member(id).name().equals(b.member(id).name())
                && String.valueOf(a.member(id).fallback()).equals(String.valueOf(b.member(id).fallback()))
                && String.valueOf(a.member(id).site()).equals(String.valueOf(b.member(id).site()))
                && a.member(id).connect() == b.member(id).connect();
    }

    private static List<String> keyIds(J2jConfig c) {
        List<String> l = new ArrayList<String>();
        for (J2jConfig.Key k : c.keys()) {
            l.add(k.id + ":" + k.fingerprint());
        }
        return l;
    }

    /** @return the latest J2J events, newest first */
    public List<LogRecord> events(int limit) {
        synchronized (events) {
            List<LogRecord> l = new ArrayList<LogRecord>(Math.min(limit, events.size()));
            for (LogRecord r : events) {
                if (l.size() >= limit) {
                    break;
                }
                l.add(r);
            }
            return Collections.unmodifiableList(l);
        }
    }

    public void addEventListener(Consumer<LogRecord> l) {
        eventListeners.add(l);
    }

    /** @return member id -> last status (empty when J2J does not run) */
    public Map<String, AdminPlane.NodeStatus> statuses() {
        J2jNode n = node;
        return n == null || n.admin() == null ? Collections.<String, AdminPlane.NodeStatus>emptyMap() : n.admin().statuses();
    }
}
