package com.simweaver.j2j;

import com.simweaver.common.logging.Events;
import com.simweaver.j2j.admin.AdminPlane;
import com.simweaver.j2j.config.J2jConfig;
import com.simweaver.j2j.crypto.J2jCrypto;
import com.simweaver.j2j.link.LinkManager;
import com.simweaver.j2j.route.Router;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.logging.Logger;

/**
 * One server's place in the J2J network (design draft 7). The rest of SimWeaver talks to J2J only
 * through this class: the server starts and stops it, the REST API reads its status, and the
 * {@code j2j} endpoint (later phases) uses its sessions.
 *
 * <p>Links to the neighbours ({@link LinkManager}); routing and end-to-end delivery ({@link Router}).
 */
public final class J2jNode {

    private static final Logger LOG = Logger.getLogger("com.simweaver.j2j");

    /** The features this implementation speaks (handshake, design §6.3). */
    public static final Set<String> FEATURES = Collections.unmodifiableSet(new LinkedHashSet<String>());

    private final J2jConfig cfg;
    private final long started = System.currentTimeMillis();
    private final long boot = J2jCrypto.RANDOM.nextLong();
    private final String software;
    private final LinkManager links;
    private final Router router;
    private final AdminPlane admin;

    private J2jNode(J2jConfig cfg, String software) {
        this.cfg = cfg;
        this.software = software;
        this.links = cfg.isEnabled() ? new LinkManager(cfg, boot, software, FEATURES) : null;
        this.router = links == null ? null : new Router(cfg, links, boot);
        this.admin = router == null ? null : new AdminPlane(cfg, router, software, started);
    }

    /**
     * Reads the configuration and, when it is complete, starts J2J.
     *
     * @return the node; check {@link #isRunning()} and {@link J2jConfig#problems()}
     */
    public static J2jNode start(com.simweaver.common.config.SimwProperties server, Path networkFile, Path keyFile, String software) {
        J2jConfig cfg = J2jConfig.load(server, networkFile, keyFile);
        J2jNode n = new J2jNode(cfg, software);
        if (n.links != null) {
            n.router.start();
            n.admin.start();
            n.links.start();
            Events.notice(LOG, "j2j.started", "J2J started: " + cfg.selfId() + " in network " + cfg.networkId() + " ("
                    + cfg.members().size() + " members, port " + cfg.port() + ")", "network", cfg.networkId(), "node", cfg.selfId(),
                    "members", cfg.members().size(), "port", cfg.port());
        } else if (!cfg.problems().isEmpty()) {
            Events.warning(LOG, "j2j.config.invalid", "J2J did not start: " + String.join("; ", cfg.problems()),
                    "problems", cfg.problems().size());
        }
        return n;
    }

    /** For tests: a node from a configuration already read. */
    public static J2jNode start(J2jConfig cfg, String software) {
        J2jNode n = new J2jNode(cfg, software);
        if (n.links != null) {
            n.router.start();
            n.admin.start();
            n.links.start();
        }
        return n;
    }

    public void stop() {
        if (links != null) {
            admin.stop();
            router.stop();
            links.stop();
        }
    }

    /** @return routing and end-to-end delivery, or null when J2J does not run */
    public Router router() {
        return router;
    }

    /** @return the admin plane (member statuses), or null when J2J does not run */
    public AdminPlane admin() {
        return admin;
    }

    /** @return true when J2J runs (configured and without problems) */
    public boolean isRunning() {
        return links != null;
    }

    public J2jConfig config() {
        return cfg;
    }

    /** @return the link layer, or null when J2J does not run */
    public LinkManager links() {
        return links;
    }

    public long boot() {
        return boot;
    }

    public String software() {
        return software;
    }

    public long startedAt() {
        return started;
    }

    /** @return "off", "running" or "error" (design §13.2) */
    public String state() {
        if (links != null) {
            return "running";
        }
        return cfg.problems().isEmpty() ? "off" : "error";
    }
}
