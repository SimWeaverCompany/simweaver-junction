package com.simweaver.rift;


import com.simweaver.common.config.SimwArgs;
import com.simweaver.common.config.SimwProperties;
import com.simweaver.common.logging.ConsoleTee;
import com.simweaver.common.logging.Events;
import com.simweaver.common.logging.LogSetup;
import com.simweaver.common.rest.RequestContext;
import com.simweaver.common.rest.XrServer;
import com.simweaver.common.rest.XrSseHub;
import com.simweaver.junction.JunctionManager;
import com.simweaver.junction.api.JunktionApi;
import com.simweaver.rift.api.ApiRequestEntryPoint;
import com.simweaver.rift.junktion.RiftJunktionHost;
import com.simweaver.rift.map.MapHttpHandler;
import com.simweaver.rift.map.MapService;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;
import java.util.logging.Logger;

public class Server {

    /**
     * 0.11.4: the settings, as before 0.14.5 with the installation as both program and site folder.
     */
    static SimwProperties readSettings(java.nio.file.Path installation, String[] args) {
        return readSettings(installation, installation, args);
    }

    /**
     * The settings. {@code etc/server.root.settings} of the site first; when {@code etcdir} (in it, or on the
     * command line) names another configuration folder, that folder's {@code server.root.settings} on top (if it
     * has one); then the command line. Sets the folders ({@link com.simweaver.common.config.SimwPaths}) for every
     * other default. 0.14.5: the site is {@code root} (the program folder in the simple way).
     */
    static SimwProperties readSettings(java.nio.file.Path program, java.nio.file.Path root, String[] args) {
        SimwProperties fromArgs = new SimwProperties();
        new SimwArgs().withConfigKeys("config").parseInto(fromArgs, args);
        SimwProperties cfg = new SimwProperties();
        java.nio.file.Path siteEtc = root.resolve(com.simweaver.common.config.SimwPaths.DEFAULT_ETC);
        java.nio.file.Path own = siteEtc.resolve("server.root.settings");
        if (java.nio.file.Files.isReadable(own)) {
            try {
                cfg.load(own);
            } catch (IOException e) {
                System.err.println("Settings: " + own + " could not be read: " + e.getMessage());
            }
        }
        String etcdir = fromArgs.getString("etcdir", cfg.getString("etcdir", "")).trim();
        java.nio.file.Path etc = etcdir.isEmpty() ? siteEtc : root.resolve(etcdir);
        if (!etc.normalize().equals(siteEtc.normalize())) {
            java.nio.file.Path moved = etc.resolve("server.root.settings");
            if (java.nio.file.Files.isReadable(moved)) {
                try {
                    cfg.load(moved);
                } catch (IOException e) {
                    System.err.println("Settings: " + moved + " could not be read: " + e.getMessage());
                }
            }
            if (!java.nio.file.Files.isDirectory(etc)) {
                System.err.println("Settings: the configuration folder " + etc.toAbsolutePath() + " (etcdir) does not exist");
            }
        } else if (!java.nio.file.Files.isReadable(own)) {
            System.err.println("Settings: " + own.toAbsolutePath() + " not found: running with the defaults");
        }
        new SimwArgs().withConfigKeys("config").parseInto(cfg, args);
        com.simweaver.common.config.SimwPaths.setFolders(program, root, etc, !etc.normalize().equals(siteEtc.normalize()));
        return cfg;
    }

    /**
     * 0.14.5: makes the configuration folder from the program's {@code etc.dist} when it does not exist yet.
     *
     * @param notes what was made, for the start-up lines
     * @return null when fine, else why the site cannot be used
     */
    static String seed(java.nio.file.Path program, java.nio.file.Path etc, List<String> notes) {
        if (java.nio.file.Files.exists(etc)) {
            return null;
        }
        try {
            int n = com.simweaver.common.config.SiteFolders.seed(program, etc);
            notes.add(n < 0 ? "new: " + etc + " made (the program folder has no etc.dist)"
                    : "new: " + etc + " made from " + program.resolve(com.simweaver.common.config.SiteFolders.TEMPLATES) + " (" + n + " files)");
            return null;
        } catch (IOException | RuntimeException e) {
            String why = e instanceof java.nio.file.AccessDeniedException
                    ? "permission denied: " + ((java.nio.file.AccessDeniedException) e).getFile() : e.getMessage();
            if (etc.toAbsolutePath().normalize().startsWith(program.toAbsolutePath().normalize())) {
                return "the program folder " + program + " is not writable (" + why + "), so its settings folder etc cannot be made. "
                        + "Unpack the zip into a folder you can write, or start with --root=<a folder you can write>.";
            }
            return "the configuration folder " + etc + " cannot be made (" + why + "). Check that the server's account may write there.";
        }
    }

    /** 0.14.5: the HTTPS key store file the settings name (relative paths against the site), the per-site one by default. */
    static java.nio.file.Path keyStoreFile(SimwProperties cfg) {
        return com.simweaver.common.config.SimwPaths.site(cfg.getString("webserver.keystore.path",
                com.simweaver.common.tls.ServerKeyStore.defaultPath().toString()));
    }

    /**
     * 0.14.5: {@code --health}. Asks this server's own {@code /api/serverinfo}: any HTTP answer (also 401 with API
     * authentication on) means it is up. Any certificate, no proxy, 5 s.
     *
     * @return 0 when it answers, else 1
     */
    static int health(SimwProperties cfg, java.io.PrintStream out) {
        int port = cfg.getInt("webserver.port", 8843);
        String url = "https://127.0.0.1:" + port + "/api/serverinfo";
        try {
            javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
            ctx.init(null, new javax.net.ssl.TrustManager[] {new javax.net.ssl.X509TrustManager() {
                @Override
                public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {
                }

                @Override
                public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {
                }

                @Override
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return new java.security.cert.X509Certificate[0];
                }
            }}, new java.security.SecureRandom());
            javax.net.ssl.HttpsURLConnection c = (javax.net.ssl.HttpsURLConnection) new java.net.URL(url).openConnection(java.net.Proxy.NO_PROXY);
            c.setSSLSocketFactory(ctx.getSocketFactory());
            c.setHostnameVerifier((h, sslSession) -> true);
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            int code = c.getResponseCode();
            c.disconnect();
            out.println("health: " + url + " answers " + code);
            return 0;
        } catch (IOException | java.security.GeneralSecurityException | RuntimeException e) {
            out.println("health: " + url + " does not answer (" + e.getMessage() + ")");
            return 1;
        }
    }

    /** A path for a start-up line: relative to the site when inside it. */
    static String shortPath(java.nio.file.Path root, java.nio.file.Path p) {
        java.nio.file.Path a = p.toAbsolutePath().normalize();
        return a.startsWith(root) && !a.equals(root) ? root.relativize(a).toString() : a.toString();
    }

    /**
     * 0.14.5: {@code --init}. Makes the site: etc (already seeded by now), the HTTPS key store and the log folder;
     * says what it made and what was there. The server does not start.
     */
    static int init(java.nio.file.Path program, java.nio.file.Path root, java.nio.file.Path logs, java.nio.file.Path keyStore,
                    String keyStorePass, SimwProperties cfg, List<String> notes) {
        System.out.println(Version.PRODUCT_NAME + " " + Version.getVersion() + ": site " + root);
        System.out.println("Program:       " + program);
        for (String n : notes) {
            System.out.println("Site:          " + n);
        }
        if (notes.isEmpty()) {
            System.out.println("Site:          " + com.simweaver.common.config.SimwPaths.etc() + " was there: not changed");
        }
        int rc = 0;
        try {
            boolean had = java.nio.file.Files.isDirectory(logs);
            java.nio.file.Files.createDirectories(logs);
            System.out.println("Log directory: " + logs + (had ? " (was there)" : " (made)"));
        } catch (IOException e) {
            System.out.println("Log directory: " + logs + " cannot be made: " + e.getMessage());
            rc = 1;
        }
        try {
            com.simweaver.common.tls.ServerKeyStore.Result ks = com.simweaver.common.tls.ServerKeyStore.ensure(keyStore,
                    keyStorePass.toCharArray(), cfg.getString("webserver.tls.san", ""));
            System.out.println("HTTPS:         " + shortPath(root, keyStore) + ", SHA-256 " + ks.fingerprint
                    + (ks.made ? " (made for " + String.join(", ", ks.names) + ")" : " (was there)"));
        } catch (IOException e) {
            System.out.println("HTTPS:         " + keyStore + " cannot be made: " + e.getMessage());
            rc = 1;
        }
        System.out.println(rc == 0 ? "The site is ready. Start the server with the same root." : "The site is not complete.");
        return rc;
    }

    /**
     * 0.14.5: site files that hide delivered ones (etc/fom, etc/enumerations, junctions/mappings): one line on the
     * console, each file in the log file. An upgraded tree has copies of the delivered FOMs in its etc/fom.
     */
    static void printShadowed(java.nio.file.Path root, java.nio.file.Path program) {
        List<com.simweaver.common.config.SiteFolders.Shadow> all = new java.util.ArrayList<>();
        all.addAll(com.simweaver.common.config.SiteFolders.shadowed(com.simweaver.common.config.SimwPaths.etc("fom"), program.resolve("fom")));
        all.addAll(com.simweaver.common.config.SiteFolders.shadowed(com.simweaver.common.config.SimwPaths.etc("enumerations"), program.resolve("enumerations")));
        all.addAll(com.simweaver.common.config.SiteFolders.shadowed(com.simweaver.common.config.SimwPaths.etc("junctions", "mappings"), program.resolve("mappings")));
        if (all.isEmpty()) {
            return;
        }
        long same = all.stream().filter(x -> x.identical).count();
        System.out.println("Site:          " + all.size() + " file" + (all.size() == 1 ? "" : "s") + " in " + shortPath(root, com.simweaver.common.config.SimwPaths.etc())
                + " hide delivered ones" + (same > 0 ? ", " + same + " of them the same as delivered (delete those to get updates)" : "")
                + "; listed in the log file");
        for (com.simweaver.common.config.SiteFolders.Shadow x : all) {
            ConsoleTee.logOnly("Site:          " + shortPath(root, x.site) + (x.identical ? " is the same as the delivered "
                    : " differs from the delivered ") + shortPath(program, x.delivered) + (x.identical ? ": delete it to get updates" : " and is used"));
        }
    }

    /**
     * 0.11.1: reads {@code enumerations.siso.file} (etc/enumerations/SISO-REF-010.xml; empty = none)
     * and prints a start-up line. Without it the entity types are shown as numbers only.
     */
    static void loadEnumerations(SimwProperties cfg) {
        // 0.14.5: the site's etc/enumerations when it has the file, else the program's enumerations folder;
        // a setting's relative path is relative to the site
        String name = cfg.isSet("enumerations.siso.file") ? cfg.getString("enumerations.siso.file", "").trim()
                : com.simweaver.common.config.SimwPaths.findDelivered(com.simweaver.common.config.SimwPaths.etc("enumerations", "SISO-REF-010.xml"),
                        com.simweaver.common.config.SimwPaths.delivered("enumerations", "SISO-REF-010.xml")).toString();
        if (!name.isEmpty()) {
            name = com.simweaver.common.config.SimwPaths.site(name).toString();
        }
        if (name.isEmpty()) {
            com.simweaver.common.siso.SisoEnumerations.setGlobal(null, "off (enumerations.siso.file is empty)");
            System.out.println("Enumerations:  off (enumerations.siso.file is empty)");
            return;
        }
        java.nio.file.Path file = Paths.get(name);
        if (!java.nio.file.Files.exists(file)) {
            com.simweaver.common.siso.SisoEnumerations.setGlobal(null, name + " not found");
            System.out.println("Enumerations:  none (" + name + " not found): entity types are shown as numbers");
            return;
        }
        try {
            long t0 = System.nanoTime();
            com.simweaver.common.siso.SisoEnumerations e = com.simweaver.common.siso.SisoEnumerations.load(file);
            com.simweaver.common.siso.SisoEnumerations.setGlobal(e, null);
            System.out.println("Enumerations:  " + e.title() + " (" + e.date() + "), " + e.entityTypeCount() + " entity types, "
                    + (System.nanoTime() - t0) / 1_000_000 + " ms. Reprinted with permission from SISO Inc.");
        } catch (IOException | RuntimeException ex) {
            com.simweaver.common.siso.SisoEnumerations.setGlobal(null, name + ": " + ex.getMessage());
            System.out.println("Enumerations:  NOT READ, " + name + ": " + ex.getMessage());
            Logger.getLogger(Server.class.getName()).warning("SISO enumerations " + name + " could not be read: " + ex.getMessage());
        }
    }

    public static void main(String[] args){

        // 0.9.22: before anything logs (the log stays open while the server stops)
        LogSetup.prepare();

        // 0.14.5: the program folder (from the jar), the site (root=, JUNCTION_SITE, else the program folder)
        SimwProperties fromArgs = new SimwProperties();
        new SimwArgs().withConfigKeys("config").parseInto(fromArgs, args);
        final java.nio.file.Path program = com.simweaver.common.config.SimwPaths.detectProgram(Server.class);
        final java.nio.file.Path root = com.simweaver.common.config.SimwPaths.chooseRoot(fromArgs.getString("root", ""),
                System.getenv(com.simweaver.common.config.SimwPaths.SITE_VARIABLE), program);
        String rootSource = !fromArgs.getString("root", "").trim().isEmpty() ? "root"
                : root.equals(program) ? "the program folder" : com.simweaver.common.config.SimwPaths.SITE_VARIABLE;
        boolean healthCommand = fromArgs.getBoolean("health", false);
        boolean initCommand = fromArgs.getBoolean("init", false);

        // a new site (or a fresh unzip) gets its etc from the program's etc.dist; an existing etc is never touched
        List<String> siteNotes = new java.util.ArrayList<>();
        if (!healthCommand) {
            String argEtc = fromArgs.getString("etcdir", "").trim();
            String problem = seed(program, argEtc.isEmpty() ? root.resolve("etc") : root.resolve(argEtc), siteNotes);
            if (problem != null) {
                System.err.println("SimWeaver Junction cannot start: " + problem);
                System.exit(2);
            }
        }

        // Read basic config. 0.11.4: the site's etc/server.root.settings, then (when etcdir names another
        // configuration folder, there or on the command line) that folder's server.root.settings on top, then
        // the command line
        SimwProperties cfg = readSettings(program, root, args);
        if (!healthCommand && com.simweaver.common.config.SimwPaths.isMoved()) {
            String problem = seed(program, com.simweaver.common.config.SimwPaths.etc(), siteNotes);
            if (problem != null) {
                System.err.println("SimWeaver Junction cannot start: " + problem);
                System.exit(2);
            }
        }

        // 0.14.5: run.sh --health asks a running server whether it answers, and stops
        if (healthCommand) {
            System.exit(health(cfg, System.out));
        }

        // 0.11.0: run.sh --new-token=<name> makes an API token and stops (the server does not start)
        if (cfg.isSet("new-token")) {
            System.exit(com.simweaver.common.rest.auth.ApiAuth.newTokenCommand(cfg, root, System.out, System.err));
        }

        java.nio.file.Path logPath = com.simweaver.common.config.SimwPaths.site(cfg.getString("logdir", "logs"));
        String keyStorePass = cfg.getString("webserver.keystore.pwd","password");
        java.nio.file.Path keyStoreFile = keyStoreFile(cfg);

        // 0.14.5: run.sh --root=<site> --init makes the site (etc, the key store, the log folder) and stops
        if (initCommand) {
            System.exit(init(program, root, logPath, keyStoreFile, keyStorePass, cfg, siteNotes));
        }

        // 0.14.5: one server per site
        String lockProblem = com.simweaver.common.config.SiteFolders.lock(root);
        if (lockProblem != null) {
            System.err.println("SimWeaver Junction cannot start: " + lockProblem);
            System.exit(2);
        }

        // Logging
        String mainLogDir = logPath.toString();
        try {
            ConsoleTee.install(logPath, "simwjunction", 1024 * 1024, 5, false);
        } catch (IOException e) {
            System.err.println("The log folder " + logPath + " cannot be written (" + e.getMessage() + "): the log goes to the console only."
                    + (com.simweaver.common.config.SimwPaths.isSite() ? "" : " Unpack the zip into a folder you can write, or start with --root=<a folder you can write>."));
        }
        // 0.9.22: one line per log record (INFO to the .log, problems to the .err.log) and syslog
        List<String> syslogLines = LogSetup.install(cfg, Version.getVersion());
        final Logger log = Logger.getLogger(Server.class.getName());

        //
        int webPort = cfg.getInt("webserver.port",8843);
        // 0.14.5: the web pages come from the program folder
        String webRoot = program.resolve(cfg.getString("webserver.root","webroot")).normalize().toString();
        String keyStorePath = keyStoreFile.toString();
        String keyStoreLine;
        try {
            com.simweaver.common.tls.ServerKeyStore.Result ks = com.simweaver.common.tls.ServerKeyStore.ensure(keyStoreFile,
                    keyStorePass.toCharArray(), cfg.getString("webserver.tls.san", ""));
            keyStoreLine = shortPath(root, keyStoreFile) + ", SHA-256 " + ks.fingerprint + (ks.made ? ", made now for " + String.join(", ", ks.names) : "");
        } catch (IOException e) {
            keyStoreLine = shortPath(root, keyStoreFile) + ": " + e.getMessage();
        }

        //
        System.out.println(Version.PRODUCT_NAME + " v " + Version.getVersion());
        System.out.println();
        System.out.println("Released under the BSD 3-Clause License.");
        System.out.println();
        System.out.println("Program:       " + program);
        System.out.println("Site:          " + root + " (" + rootSource + ")");
        for (String note : siteNotes) {
            System.out.println("Site:          " + note);
        }
        printShadowed(root, program);
        System.out.println("Config folder: " + com.simweaver.common.config.SimwPaths.etc()
                + (com.simweaver.common.config.SimwPaths.isMoved() ? " (etcdir)" : ""));
        System.out.println("Log directory: "+mainLogDir);
        System.out.println("HTTPS:         " + keyStoreLine);
        System.out.println("Web port:      "+webPort);
        // 0.11.0: token authentication of /api (off unless api.auth.enabled=true)
        final com.simweaver.common.rest.auth.ApiAuth apiAuth = com.simweaver.common.rest.auth.ApiAuth.fromSettings(cfg, root, "/api");
        System.out.println("API auth:      " + apiAuth.describe());
        for (String line : syslogLines) {
            System.out.println("Syslog:        " + line);
        }
        System.out.println();

        System.out.println("Starting services...");
        System.out.println();

        // 0.11.1: SISO-REF-010 (the DIS / RPR enumerations), to name entity types in the object view
        loadEnumerations(cfg);

        // 0.9.20: the Infra view (DIS exercises and HLA federations Rift and Junction have contact with)
        com.simweaver.common.infra.InfraRegistry.global().configure(cfg);

        XrSseHub eventHub = new XrSseHub(1024,250);
        DatasourceManager datasourceManager = new DatasourceManager(eventHub);

        // NEW: SimWeaver Junction (gateways), hosted by Rift: its API under /api/junktion, its
        // page webroot/junktion.html, its endpoints as Rift datasource configurations
        // 0.14.5: relative paths in settings (RTI jars, FOM folders, trust stores …) are relative to the site
        File runtimeDir = root.toFile();

        // NEW: the map view (background rasters as tiles under /api/maps; map.enabled=false turns it off)
        final MapService maps = new MapService(cfg, runtimeDir);
        // 0.11.5: one line and the problems on the console; every map and overlay in the log file
        System.out.println("Map:           " + maps.summary());
        for (String line : maps.details()) {
            com.simweaver.common.logging.ConsoleTee.logOnly("Map:           " + line);
        }
        for (String line : maps.problems()) {
            System.out.println("Map:           " + line);
        }

        JunctionManager junktion = null;
        // junction.enabled / junction.dir; the 0.9.0 spelling junktion.* is still read
        boolean junctionOn = cfg.getBoolean("junction.enabled", cfg.getBoolean("junktion.enabled", true));
        if (junctionOn) {
            String jdir = cfg.getString("junction.dir", cfg.getString("junktion.dir", com.simweaver.common.config.SimwPaths.etcString("junctions")));
            // 0.11.4: "etc/junctions", the old default written out in many settings files, means the default:
            // junctions in the configuration folder, also when etcdir moves it
            if (com.simweaver.common.config.SimwPaths.isMoved() && Paths.get(jdir).normalize().equals(Paths.get("etc", "junctions"))) {
                jdir = com.simweaver.common.config.SimwPaths.etcString("junctions");
            }
            File junctionsDir = com.simweaver.common.config.SimwPaths.siteFile(jdir);      // 0.14.5: relative to the site
            junktion = new JunctionManager(runtimeDir, junctionsDir, eventHub, new RiftJunktionHost(datasourceManager,
                    cfg.getBoolean("junction.datasourceconfigs", true)));
            java.nio.file.Path jSamples = program.resolve(com.simweaver.common.config.SiteFolders.TEMPLATES).resolve("junctions");
            System.out.println("Junction:      " + junctionsDir.getPath() + (junctionsDir.isDirectory()
                    ? " (" + junktion.configs().getGateways().size() + " gateway configurations)"
                    : " does not exist: no gateway configurations" + (java.nio.file.Files.isDirectory(jSamples)
                            ? " (the delivered samples are in " + jSamples + ": copy that folder there to use them)" : "")));
        }
        final JunctionManager junctionManager = junktion;

        // 0.10.0: J2J, the network of Junction servers; its code is com.simweaver.j2j, its settings etc/j2j/ and j2j.*
        final com.simweaver.j2j.J2jService j2j = new com.simweaver.j2j.J2jService(cfg, com.simweaver.common.config.SimwPaths.etc("j2j", "network.settings"),
                com.simweaver.common.config.SimwPaths.etc("j2j", "network.key"), "SimWeaver " + Version.getVersion());
        com.simweaver.j2j.J2jService.setGlobal(j2j);
        j2j.setStatusSource(new J2jStatus(junctionManager));
        com.simweaver.j2j.J2jNode j2jNode = j2j.start();
        System.out.println("J2J:           " + (j2jNode.isRunning() ? j2jNode.config().selfId() + " in network " + j2jNode.config().networkId()
                + ", " + j2jNode.config().members().size() + " members, port " + j2jNode.config().port()
                : j2jNode.config().problems().isEmpty() ? "off (no members in " + com.simweaver.common.config.SimwPaths.etc("j2j", "network.settings") + ")"
                : "NOT STARTED: " + String.join("; ", j2jNode.config().problems())));
        final com.simweaver.j2j.api.J2jApi j2jApi = new com.simweaver.j2j.api.J2jApi(j2j);

        try {
            ApiRequestEntryPoint apiRequestEntryPoint = new ApiRequestEntryPoint(datasourceManager);
            apiRequestEntryPoint.setMapEnabled(maps.isEnabled());
            if (junctionManager != null) {
                JunktionApi junctionApi = new JunktionApi(junctionManager);
                apiRequestEntryPoint.mount("junction", junctionApi);
                apiRequestEntryPoint.mount("junktion", junctionApi);          // the 0.9.0 path, still answered
            }
            apiRequestEntryPoint.mount("infra", new com.simweaver.common.infra.InfraApi(com.simweaver.common.infra.InfraRegistry.global()));
            // 0.10.0: the J2J network (off unless etc/j2j/network.settings lists members)
            j2jApi.startLive(eventHub);
            apiRequestEntryPoint.mount("j2j", j2jApi);
            java.util.Map<String, com.sun.net.httpserver.HttpHandler> extra = new java.util.LinkedHashMap<>();
            extra.put("/api/maps", new MapHttpHandler(maps, "/api/maps"));        // NEW: binary tiles
            extra.put("/api/auth", apiAuth.handler());                            // 0.11.0: sign in / out, whoami
            extra.put("/api/audio", new com.simweaver.rift.comms.CommsAudioHandler(datasourceManager::getComms, "/api/audio"));  // 0.12.0: Comms audio
            // 0.14.0: MCP, tools for AI assistants (off unless mcp.enabled=true)
            com.simweaver.rift.mcp.RiftMcp mcp = com.simweaver.rift.mcp.RiftMcp.fromSettings(cfg, Version.PRODUCT_NAME,
                    Version.getVersion(), datasourceManager, apiRequestEntryPoint, apiAuth);
            System.out.println("MCP:           " + mcp.summary());
            for (String p : mcp.problems()) {
                System.out.println("MCP:           " + p);
                Events.log(log, java.util.logging.Level.WARNING, "mcp.config", p);
            }
            if (mcp.handler() != null) {
                extra.put("/api/mcp", mcp.handler());
            }
            apiAuth.logStart();
            XrServer.run(webPort,apiRequestEntryPoint,webRoot,"/","/api",keyStorePath,keyStorePass,eventHub,extra,
                    apiAuth.filter());
            maps.prewarm(3);                                                       // the world view, in the background
        } catch (Exception e) {
            System.out.println("There was an issue starting the web server:");
            System.out.println(e.getMessage());
            Events.log(log, java.util.logging.Level.SEVERE, "server.webfailed",
                    "The web server could not start on port " + webPort + ": " + e.getMessage(), "port", webPort);
        }

        System.out.println("Listening to HTTPS on TCP port " + webPort);
        System.out.println("Files URL: https://SERVER-IP:" + webPort);
        System.out.println("API URL:   https://SERVER-IP:" + webPort + "/api");
        System.out.println();
        System.out.println("We are good to go!");
        Events.notice(log, "server.started", Version.PRODUCT_NAME + " " + Version.getVersion() + " started",
                "version", Version.getVersion(), "port", webPort, "pid", ProcessHandle.current().pid());

        if (junctionManager != null) {
            // NEW: gateways with junction.autostart=true; 0.9.22: logged as started by "autostart"
            RequestContext.runAs("autostart", junctionManager::autostart);
        }


        Runtime.getRuntime().addShutdownHook(new Thread("server-shutdown")
        {
            public void run()
            {
                RequestContext.runAs("shutdown", () -> {
                    Events.notice(log, "server.stopping", Version.PRODUCT_NAME + " is stopping");
                    System.out.println("Shutdown Hook is running to clean up...");
                    // NEW: stop the gateways (their bridged objects are deleted, the federates resign)
                    if (junctionManager != null) {
                        junctionManager.shutdown();
                    }
                    maps.shutdown();
                    j2jApi.stopLive();
                    j2j.stop();
                    Events.notice(log, "server.stopped", Version.PRODUCT_NAME + " stopped");
                });
                LogSetup.shutdown();          // 0.9.22: what syslog still has queued is sent (at most 3 s)
                ConsoleTee.close();
            }
        });
    }
}
