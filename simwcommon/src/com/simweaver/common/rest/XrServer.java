package com.simweaver.common.rest;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import java.io.FileInputStream;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class XrServer {

    public static String getVersion() {
        return "0.0.7";
    }

    public static void run(int port, ApiCallHandler apiCallHandler) throws Exception {
        run(port, apiCallHandler, "./webroot", "/", "/api", "keystore.jks", "password");
    }

    /** Start the server with the default routes and an SSE event hub at /api/events. */
    public static void run(int port, ApiCallHandler apiCallHandler, XrSseHub eventHub) throws Exception {
        run(port, apiCallHandler, "./webroot", "/", "/api", "keystore.jks", "password", eventHub);
    }

    public static void run(int port, ApiCallHandler apiCallHandler, String webFilesDir,
                           String filesRoute, String apiRoute, String keystorePath,
                           String keystorePass) throws Exception {
        start(port, apiCallHandler, webFilesDir, filesRoute, apiRoute,
                keystorePath, keystorePass, null);
    }

    /**
     * Start the server with an optional SSE hub. The SSE endpoint is apiRoute + "/events"
     * (for example, /api/events when apiRoute is /api).
     */
    public static void run(int port, ApiCallHandler apiCallHandler, String webFilesDir,
                           String filesRoute, String apiRoute, String keystorePath,
                           String keystorePass, XrSseHub eventHub) throws Exception {
        start(port, apiCallHandler, webFilesDir, filesRoute, apiRoute,
                keystorePath, keystorePass, eventHub);
    }

    /**
     * As above, with extra handlers on their own paths (e.g. binary responses such as map tiles,
     * which the JSON API handler does not send). The JDK server picks the longest matching path,
     * so "/api/maps" takes precedence over "/api".
     *
     * @param extraContexts path -> handler; may be null
     */
    public static void run(int port, ApiCallHandler apiCallHandler, String webFilesDir,
                           String filesRoute, String apiRoute, String keystorePath,
                           String keystorePass, XrSseHub eventHub,
                           java.util.Map<String, com.sun.net.httpserver.HttpHandler> extraContexts) throws Exception {
        start(port, apiCallHandler, webFilesDir, filesRoute, apiRoute,
                keystorePath, keystorePass, eventHub, extraContexts, null);
    }

    /**
     * As above, with a filter (0.11.0: API authentication) in front of every context under
     * {@code apiRoute}: the API, its SSE events and every extra context whose path starts with it.
     *
     * @param apiFilter may be null
     */
    public static void run(int port, ApiCallHandler apiCallHandler, String webFilesDir,
                           String filesRoute, String apiRoute, String keystorePath,
                           String keystorePass, XrSseHub eventHub,
                           java.util.Map<String, com.sun.net.httpserver.HttpHandler> extraContexts,
                           com.sun.net.httpserver.Filter apiFilter) throws Exception {
        start(port, apiCallHandler, webFilesDir, filesRoute, apiRoute,
                keystorePath, keystorePass, eventHub, extraContexts, apiFilter);
    }

    private static void start(int port, ApiCallHandler apiCallHandler, String webFilesDir,
                              String filesRoute, String apiRoute, String keystorePath,
                              String keystorePass, XrSseHub eventHub) throws Exception {
        start(port, apiCallHandler, webFilesDir, filesRoute, apiRoute, keystorePath, keystorePass, eventHub, null, null);
    }

    private static void start(int port, ApiCallHandler apiCallHandler, String webFilesDir,
                              String filesRoute, String apiRoute, String keystorePath,
                              String keystorePass, XrSseHub eventHub,
                              java.util.Map<String, com.sun.net.httpserver.HttpHandler> extraContexts,
                              com.sun.net.httpserver.Filter apiFilter) throws Exception {
        // 1. Server instance creation
        HttpsServer server = HttpsServer.create(new InetSocketAddress(port), 0);

        // 2. SSL-config
        SSLContext sslContext = createSslContext(keystorePath, keystorePass);
        server.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
            @Override
            public void configure(HttpsParameters params) {
                try {
                    SSLContext context = getSSLContext();
                    SSLEngine engine = context.createSSLEngine();
                    params.setNeedClientAuth(false);
                    params.setCipherSuites(engine.getEnabledCipherSuites());
                    params.setProtocols(engine.getEnabledProtocols());
                    SSLParameters sslparams = context.getDefaultSSLParameters();
                    params.setSSLParameters(sslparams);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        });

        // 3. Define routes. HttpServer selects the longest matching context,
        // so /api/events takes precedence over the general /api context.
        java.util.List<com.sun.net.httpserver.HttpContext> api = new java.util.ArrayList<>();
        api.add(server.createContext(apiRoute, new XrRequestHandler(apiCallHandler, apiRoute)));
        if (eventHub != null) {
            api.add(server.createContext(normalizeRoute(apiRoute) + "/events", eventHub));
        }
        server.createContext(filesRoute, new XrFileHandler(webFilesDir, filesRoute));
        if (extraContexts != null) {
            String prefix = normalizeRoute(apiRoute);
            for (java.util.Map.Entry<String, com.sun.net.httpserver.HttpHandler> e : extraContexts.entrySet()) {
                com.sun.net.httpserver.HttpContext c = server.createContext(e.getKey(), e.getValue());
                if (e.getKey().equals(prefix) || e.getKey().startsWith(prefix + "/")) {
                    api.add(c);
                }
            }
        }
        if (apiFilter != null) {                    // 0.11.0: every context under the API's route
            for (com.sun.net.httpserver.HttpContext c : api) {
                c.getFilters().add(apiFilter);
            }
        }

        // SSE handlers occupy a request thread while a stream is open. A fixed
        // pool of 10 would let 10 streams starve all ordinary REST requests.
        // The JDK HttpServer API is blocking, so use a cached pool here. For a
        // large public deployment, use a bounded/non-blocking server instead.
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);

        System.out.println("HTTPS-server started at " + port);
        server.start();
    }

    private static String normalizeRoute(String route) {
        if (route == null || route.isEmpty()) return "/api";
        String normalized = route.startsWith("/") ? route : "/" + route;
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static SSLContext createSslContext(String keystorePath, String password) throws Exception {
        char[] passChars = password.toCharArray();
        // 0.14.5: PKCS12 (the per-site store the server makes) or JKS, whatever the file is
        KeyStore ks = com.simweaver.common.tls.ServerKeyStore.load(java.nio.file.Paths.get(keystorePath), passChars);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, passChars);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);
        return sslContext;
    }
}
