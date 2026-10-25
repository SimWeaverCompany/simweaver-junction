package com.simweaver.common.rest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class XrFileHandler implements HttpHandler {

    /**
     * Content types by file extension. Files.probeContentType depends on the platform
     * (registry on Windows, mime databases on Linux) and returns null or wrong types on
     * many offline/Windows machines - and browsers refuse a stylesheet that is not
     * served as text/css. Known extensions are therefore answered from this table.
     */
    private static final Map<String, String> TYPES = new HashMap<>();
    static {
        TYPES.put("html", "text/html; charset=UTF-8");
        TYPES.put("htm", "text/html; charset=UTF-8");
        TYPES.put("css", "text/css; charset=UTF-8");
        TYPES.put("js", "text/javascript; charset=UTF-8");
        TYPES.put("mjs", "text/javascript; charset=UTF-8");
        TYPES.put("json", "application/json; charset=UTF-8");
        TYPES.put("map", "application/json; charset=UTF-8");
        TYPES.put("txt", "text/plain; charset=UTF-8");
        TYPES.put("md", "text/markdown; charset=UTF-8");
        TYPES.put("xml", "application/xml; charset=UTF-8");
        TYPES.put("csv", "text/csv; charset=UTF-8");
        TYPES.put("svg", "image/svg+xml");
        TYPES.put("png", "image/png");
        TYPES.put("jpg", "image/jpeg");
        TYPES.put("jpeg", "image/jpeg");
        TYPES.put("gif", "image/gif");
        TYPES.put("webp", "image/webp");
        TYPES.put("ico", "image/x-icon");
        TYPES.put("woff", "font/woff");
        TYPES.put("woff2", "font/woff2");
        TYPES.put("ttf", "font/ttf");
        TYPES.put("pdf", "application/pdf");
        TYPES.put("wasm", "application/wasm");
    }

    private final Path baseDir;
    private final String filesRoute;

    public XrFileHandler(String baseDir, String filesRoute) {
        this.baseDir = Paths.get(baseDir).toAbsolutePath().normalize();
        this.filesRoute = normalizeRoute(filesRoute);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            boolean head = "HEAD".equals(method);
            if (!head && !"GET".equals(method)) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                sendResponse(exchange, 405, "405 Method Not Allowed");
                return;
            }

            // Decoded path of the URL (e.g. /index.html), relative to this handler's route.
            String pathStr = exchange.getRequestURI().getPath();
            if (pathStr == null) pathStr = "/";
            if (!"/".equals(filesRoute)) {
                // The JDK matches contexts by plain prefix, so "/web" also receives "/webfoo".
                if (!pathStr.equals(filesRoute) && !pathStr.startsWith(filesRoute + "/")) {
                    sendResponse(exchange, 404, "404 Not Found");
                    return;
                }
                pathStr = pathStr.substring(filesRoute.length());
            }
            while (pathStr.startsWith("/") || pathStr.startsWith("\\")) {
                pathStr = pathStr.substring(1);   // keep it relative: never an absolute or root path
            }

            // Security: no directory traversal (../) and no paths outside baseDir.
            Path file;
            try {
                file = baseDir.resolve(pathStr).normalize();
            } catch (InvalidPathException e) {
                // e.g. "C:" or a NUL character on Windows - previously an unanswered exchange
                sendResponse(exchange, 404, "404 Not Found");
                return;
            }
            if (!file.startsWith(baseDir)) {
                sendResponse(exchange, 403, "403 Forbidden");
                return;
            }

            // A directory serves its index.html
            if (Files.isDirectory(file)) {
                file = file.resolve("index.html");
            }

            if (Files.isRegularFile(file)) {
                exchange.getResponseHeaders().set("Content-Type", contentType(file));
                exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                // Front-end files change with deployments: revalidate instead of using stale copies.
                exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                long size = Files.size(file);
                if (head) {
                    exchange.getResponseHeaders().set("Content-Length", Long.toString(size));
                    exchange.sendResponseHeaders(200, -1);
                    return;
                }
                exchange.sendResponseHeaders(200, size == 0 ? -1 : size);
                if (size > 0) {
                    try (OutputStream os = exchange.getResponseBody()) {
                        Files.copy(file, os);
                    }
                }
            } else {
                sendResponse(exchange, 404, "404 Not Found");
            }
        } finally {
            exchange.close();
        }
    }

    private static String contentType(Path file) throws IOException {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String known = TYPES.get(name.substring(dot + 1).toLowerCase(Locale.ROOT));
            if (known != null) return known;
        }
        String probed = Files.probeContentType(file);
        return probed != null ? probed : "application/octet-stream";
    }

    private static String normalizeRoute(String route) {
        if (route == null || route.isEmpty()) return "/";
        String r = route.startsWith("/") ? route : "/" + route;
        while (r.length() > 1 && r.endsWith("/")) r = r.substring(0, r.length() - 1);
        return r;
    }

    private void sendResponse(HttpExchange exchange, int code, String msg) throws IOException {
        byte[] response = msg.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(code, response.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
    }
}
