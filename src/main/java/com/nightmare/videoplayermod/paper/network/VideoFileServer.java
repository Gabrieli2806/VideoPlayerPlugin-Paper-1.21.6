package com.nightmare.videoplayermod.paper.network;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Embedded HTTP server that serves video files from the server's videos directory
 * so that Fabric clients can download them on demand.
 */
public class VideoFileServer {

    private static final int DEFAULT_PORT = 8190;

    private final Logger logger;
    private final Path videosDir;
    private final Path configFile;

    private HttpServer httpServer;
    private String host;
    private int port;

    public VideoFileServer(Logger logger, Path configRoot) {
        this.logger = logger;
        this.videosDir = configRoot.resolve("videos");
        this.configFile = configRoot.resolve("http-server.properties");
    }

    public void start() {
        loadConfig();

        try {
            httpServer = HttpServer.create(new InetSocketAddress(port), 0);
            httpServer.createContext("/videos/", this::handleRequest);
            httpServer.setExecutor(null); // default single-thread executor
            httpServer.start();
            logger.info("Video HTTP server started on " + host + ":" + port);
            logger.info("Clients will download videos from http://" + host + ":" + port + "/videos/<filename>");
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to start video HTTP server on port " + port, e);
        }
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop(1);
            httpServer = null;
            logger.info("Video HTTP server stopped");
        }
    }

    /**
     * Converts a local file path to an HTTP URL that clients can download.
     * If the source is already a URL, returns it unchanged.
     * If the file is not in the videos directory, returns the source unchanged.
     */
    public String toClientUrl(String source) {
        // Already a URL — pass through
        if (source.startsWith("http://") || source.startsWith("https://")) {
            return source;
        }

        if (httpServer == null) {
            logger.warning("HTTP server not running — cannot convert source to URL: " + source);
            return source;
        }

        // Extract just the filename
        Path sourcePath = Path.of(source);
        String fileName = sourcePath.getFileName().toString();

        // Verify the file exists in our videos directory
        Path resolved = videosDir.resolve(fileName);
        if (!Files.exists(resolved)) {
            logger.warning("Video file not found in videos dir: " + resolved);
            return source;
        }

        // URL-encode the filename (spaces, special chars)
        String encoded = java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8)
                .replace("+", "%20"); // spaces as %20 not +

        return "http://" + host + ":" + port + "/videos/" + encoded;
    }

    public boolean isRunning() {
        return httpServer != null;
    }

    // ---- HTTP handler ----

    private void handleRequest(HttpExchange exchange) {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }

            String uri = exchange.getRequestURI().getPath();
            String rawFileName = uri.substring("/videos/".length());
            String fileName = URLDecoder.decode(rawFileName, StandardCharsets.UTF_8);

            // Security: reject path traversal
            if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
                logger.warning("Blocked path traversal attempt: " + fileName);
                exchange.sendResponseHeaders(403, -1);
                exchange.close();
                return;
            }

            Path file = videosDir.resolve(fileName).normalize();
            if (!file.startsWith(videosDir) || !Files.exists(file) || Files.isDirectory(file)) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }

            long fileSize = Files.size(file);
            String contentType = guessContentType(fileName);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Content-Length", String.valueOf(fileSize));
            exchange.sendResponseHeaders(200, fileSize);

            logger.info("Serving video '" + fileName + "' (" + (fileSize / 1024 / 1024) + " MB) to " +
                    exchange.getRemoteAddress());

            try (OutputStream os = exchange.getResponseBody();
                 InputStream is = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536]; // 64KB chunks
                int read;
                while ((read = is.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Error serving video: " + e.getMessage());
        } finally {
            exchange.close();
        }
    }

    // ---- config ----

    private void loadConfig() {
        this.port = DEFAULT_PORT;
        this.host = detectHost();

        if (Files.exists(configFile)) {
            try (InputStream in = Files.newInputStream(configFile)) {
                Properties props = new Properties();
                props.load(in);
                String cfgHost = props.getProperty("host", "").trim();
                String cfgPort = props.getProperty("port", "").trim();
                if (!cfgHost.isEmpty()) this.host = cfgHost;
                if (!cfgPort.isEmpty()) {
                    try { this.port = Integer.parseInt(cfgPort); } catch (NumberFormatException ignored) {}
                }
            } catch (IOException e) {
                logger.warning("Failed to read http-server.properties: " + e.getMessage());
            }
        } else {
            // Write default config
            try {
                Files.writeString(configFile,
                        "# Video HTTP Server Configuration\n" +
                        "# This server lets clients download video files from the server.\n" +
                        "#\n" +
                        "# host: The IP/hostname that clients will connect to for video downloads.\n" +
                        "#       Leave empty to auto-detect.\n" +
                        "#       For a public server, set this to your server's public IP or domain.\n" +
                        "#       Examples: play.myserver.com, 123.45.67.89\n" +
                        "#\n" +
                        "# port: The HTTP port (must be open in your firewall/router).\n" +
                        "#       Default: 8190\n" +
                        "#\n" +
                        "host=\n" +
                        "port=8190\n"
                );
            } catch (IOException e) {
                logger.warning("Failed to write default http-server.properties: " + e.getMessage());
            }
        }
    }

    private String detectHost() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    private static String guessContentType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".avi")) return "video/x-msvideo";
        if (lower.endsWith(".mov")) return "video/quicktime";
        return "application/octet-stream";
    }
}
