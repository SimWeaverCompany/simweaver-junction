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

    public static void main(String[] args){

        // 0.9.22: before anything logs (the log stays open while the server stops)
        LogSetup.prepare();

        // Read basic config
        SimwProperties cfg = new SimwProperties();
        try {
            cfg = new SimwProperties().load("etc/server.root.settings");
        } catch (IOException e) {
            e.printStackTrace();
        }
        new SimwArgs().withConfigKeys("config").parseInto(cfg, args);

        // Logging
        String mainLogDir = cfg.getString("logdir","logs");
        try {
            ConsoleTee.install(Paths.get(mainLogDir), "simwjunction", 1024 * 1024, 5, false);
        } catch (IOException e) {
            e.printStackTrace();
        }
        // 0.9.22: one line per log record (INFO to the .log, problems to the .err.log) and syslog
        List<String> syslogLines = LogSetup.install(cfg, Version.getVersion());
        final Logger log = Logger.getLogger(Server.class.getName());

        //
        int webPort = cfg.getInt("webserver.port",8843);
        String webRoot = cfg.getString("webserver.root","webroot");
        String keyStorePath = cfg.getString("webserver.keystore.path","keystore.jks");
        String keyStorePass = cfg.getString("webserver.keystore.pwd","password");

        //
        System.out.println(Version.PRODUCT_NAME + " v " + Version.getVersion());
        System.out.println();
        System.out.println("Released under the BSD 3-Clause License.");
        System.out.println();
        System.out.println("Log directory: "+mainLogDir);
        System.out.println("Web port:      "+webPort);
        for (String line : syslogLines) {
            System.out.println("Syslog:        " + line);
        }
        System.out.println();

        System.out.println("Starting services...");
        System.out.println();

        // 0.9.20: the Infra view (DIS exercises and HLA federations Rift and Junction have contact with)
        com.simweaver.common.infra.InfraRegistry.global().configure(cfg);

        XrSseHub eventHub = new XrSseHub(1024,250);
        DatasourceManager datasourceManager = new DatasourceManager(eventHub);

        // NEW: SimWeaver Junction (gateways), hosted by Rift: its API under /api/junktion, its
        // page webroot/junktion.html, its endpoints as Rift datasource configurations
        File runtimeDir = Paths.get("").toAbsolutePath().normalize().toFile();

        // NEW: the map view (background rasters as tiles under /api/maps; map.enabled=false turns it off)
        final MapService maps = new MapService(cfg, runtimeDir);
        for (String line : maps.describe()) {
            System.out.println("Map:           " + line);
        }

        JunctionManager junktion = null;
        // junction.enabled / junction.dir; the 0.9.0 spelling junktion.* is still read
        boolean junctionOn = cfg.getBoolean("junction.enabled", cfg.getBoolean("junktion.enabled", true));
        if (junctionOn) {
            File junctionsDir = new File(cfg.getString("junction.dir", cfg.getString("junktion.dir", "etc/junctions")));
            junktion = new JunctionManager(runtimeDir, junctionsDir, eventHub, new RiftJunktionHost(datasourceManager,
                    cfg.getBoolean("junction.datasourceconfigs", true)));
            System.out.println("Junction:      " + junctionsDir.getPath() + " (" + junktion.configs().getGateways().size()
                    + " gateway configurations)");
        }
        final JunctionManager junctionManager = junktion;

        // 0.10.0: J2J, the network of Junction servers; its code is com.simweaver.j2j, its settings etc/j2j/ and j2j.*
        final com.simweaver.j2j.J2jService j2j = new com.simweaver.j2j.J2jService(cfg, Paths.get("etc/j2j/network.settings"),
                Paths.get("etc/j2j/network.key"), "SimWeaver " + Version.getVersion());
        com.simweaver.j2j.J2jService.setGlobal(j2j);
        j2j.setStatusSource(new J2jStatus(junctionManager));
        com.simweaver.j2j.J2jNode j2jNode = j2j.start();
        System.out.println("J2J:           " + (j2jNode.isRunning() ? j2jNode.config().selfId() + " in network " + j2jNode.config().networkId()
                + ", " + j2jNode.config().members().size() + " members, port " + j2jNode.config().port()
                : j2jNode.config().problems().isEmpty() ? "off (no members in etc/j2j/network.settings)"
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
            XrServer.run(webPort,apiRequestEntryPoint,webRoot,"/","/api",keyStorePath,keyStorePass,eventHub,extra);
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
