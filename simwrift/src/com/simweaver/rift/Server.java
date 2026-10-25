package com.simweaver.rift;


import com.simweaver.common.config.SimwArgs;
import com.simweaver.common.config.SimwProperties;
import com.simweaver.common.logging.ConsoleTee;
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

public class Server {

    public static void main(String[] args){



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
            ConsoleTee.install(Paths.get(mainLogDir), "simwjunction", 1024 * 1024, 5);
        } catch (IOException e) {
            e.printStackTrace();
        }

        //
        int webPort = cfg.getInt("webserver.port",8843);
        String webRoot = cfg.getString("webserver.root","webroot");
        String keyStorePath = cfg.getString("webserver.keystore.path","keystore.jks");
        String keyStorePass = cfg.getString("webserver.keystore.pwd","password");

        //
        System.out.println(Version.PRODUCT_NAME + " v " + Version.getVersion());
        System.out.println();
        System.out.println(Version.LICENSE_TAG);
        System.out.println();
        System.out.println("Log directory: "+mainLogDir);
        System.out.println("Web port:      "+webPort);
        System.out.println();

        System.out.println("Starting services...");
        System.out.println();

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

        try {
            ApiRequestEntryPoint apiRequestEntryPoint = new ApiRequestEntryPoint(datasourceManager);
            apiRequestEntryPoint.setMapEnabled(maps.isEnabled());
            if (junctionManager != null) {
                JunktionApi junctionApi = new JunktionApi(junctionManager);
                apiRequestEntryPoint.mount("junction", junctionApi);
                apiRequestEntryPoint.mount("junktion", junctionApi);          // the 0.9.0 path, still answered
            }
            java.util.Map<String, com.sun.net.httpserver.HttpHandler> extra = new java.util.LinkedHashMap<>();
            extra.put("/api/maps", new MapHttpHandler(maps, "/api/maps"));        // NEW: binary tiles
            XrServer.run(webPort,apiRequestEntryPoint,webRoot,"/","/api",keyStorePath,keyStorePass,eventHub,extra);
            maps.prewarm(3);                                                       // the world view, in the background
        } catch (Exception e) {
            System.out.println("There was an issue starting the web server:");
            System.out.println(e.getMessage());
        }

        System.out.println("Listening to HTTPS on TCP port " + webPort);
        System.out.println("Files URL: https://SERVER-IP:" + webPort);
        System.out.println("API URL:   https://SERVER-IP:" + webPort + "/api");
        System.out.println();
        System.out.println("We are good to go!");

        if (junctionManager != null) {
            junctionManager.autostart();                 // NEW: gateways with junction.autostart=true
        }


        Runtime.getRuntime().addShutdownHook(new Thread()
        {
            public void run()
            {
                System.out.println("Shutdown Hook is running to clean up...");
                // NEW: stop the gateways (their bridged objects are deleted, the federates resign)
                if (junctionManager != null) {
                    junctionManager.shutdown();
                }
                maps.shutdown();
            }
        });
    }
}
