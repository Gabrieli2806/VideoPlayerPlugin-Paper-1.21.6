package com.nightmare.videoplayermod.paper.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;
import java.util.stream.Stream;

public class VideoRegistry {

    private final Logger logger;
    private final Path configRoot;
    private final Path videosProp;
    private final Path urlsProp;
    private final Path videosDir;

    public VideoRegistry(Logger logger, Path configRoot) {
        this.logger = logger;
        this.configRoot = configRoot;
        this.videosProp = configRoot.resolve("videos.properties");
        this.urlsProp = configRoot.resolve("urls.properties");
        this.videosDir = configRoot.resolve("videos");
    }

    public Map<String, String> loadVideos() {
        Map<String, String> merged = new LinkedHashMap<>();

        mergeProperties(merged, videosProp);
        mergeProperties(merged, urlsProp);

        if (Files.exists(videosDir)) {
            try (Stream<Path> files = Files.list(videosDir)) {
                files.filter(path -> path.toString().toLowerCase().endsWith(".mp4"))
                        .sorted()
                        .forEach(path -> {
                            String fileName = path.getFileName().toString();
                            String id = fileName.substring(0, fileName.length() - 4);
                            merged.putIfAbsent(id, path.toAbsolutePath().toString());
                        });
            } catch (IOException ex) {
                logger.warning("Failed to scan videos directory: " + ex.getMessage());
            }
        }

        logger.info("[DEBUG][Command] loadVideos() merged " + merged.size() + " entries from properties + videos folder");
        return merged;
    }

    public Path getConfigRoot() {
        return configRoot;
    }

    private void mergeProperties(Map<String, String> target, Path file) {
        if (!Files.exists(file)) {
            return;
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
            for (String key : properties.stringPropertyNames()) {
                String value = properties.getProperty(key);
                if (value != null && !value.isBlank()) {
                    target.put(key, value);
                }
            }
        } catch (IOException ex) {
            logger.warning("Failed to read " + file + ": " + ex.getMessage());
        }
    }
}
