package com.nightmare.videoplayermod.paper;

import com.nightmare.videoplayermod.paper.command.CinematicCommand;
import com.nightmare.videoplayermod.paper.config.VideoRegistry;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.logging.Level;

public class VideoPlayerPlugin extends JavaPlugin {

    public static final String CHANNEL_PLAY_VIDEO = "videoplayermod:play_video";
    public static final String CHANNEL_STOP_VIDEO = "videoplayermod:stop_video";
    public static final String CHANNEL_SET_VOLUME = "videoplayermod:set_volume";

    private Path configRoot;
    private VideoRegistry videoRegistry;

    @Override
    public void onEnable() {
        getLogger().info("VideoPlayerPlugin initializing...");

        createConfigStructure();

        this.videoRegistry = new VideoRegistry(getLogger(), configRoot);

        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL_PLAY_VIDEO);
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL_STOP_VIDEO);
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL_SET_VOLUME);

        PluginCommand cinematic = Objects.requireNonNull(getCommand("cinematic"), "Command cinematic missing in plugin.yml");
        CinematicCommand executor = new CinematicCommand(this, videoRegistry);
        cinematic.setExecutor(executor);
        cinematic.setTabCompleter(executor);

        getLogger().info("VideoPlayerPlugin initialized!");
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL_PLAY_VIDEO);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL_STOP_VIDEO);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL_SET_VOLUME);
    }

    public VideoRegistry getVideoRegistry() {
        return videoRegistry;
    }

    private void createConfigStructure() {
        try {
            this.configRoot = Path.of("config", "videoplayermod");
            Path videosDir = configRoot.resolve("videos");
            Path videosProp = configRoot.resolve("videos.properties");
            Path urlsProp = configRoot.resolve("urls.properties");

            Files.createDirectories(videosDir);

            if (!Files.exists(videosProp)) {
                Files.writeString(videosProp,
                        "# Video registry: id=path_or_url\n" +
                        "# Examples:\n" +
                        "# intro=https://cdn.example.com/intro.mp4\n" +
                        "# credits=config/videoplayermod/videos/credits.mp4\n"
                );
            }

            if (!Files.exists(urlsProp)) {
                Files.writeString(urlsProp,
                        "# Remote URL registry: id=https://...\n" +
                        "# Example:\n" +
                        "# trailer=https://cdn.example.com/trailer.mp4\n"
                );
            }
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Failed to create config directory", e);
        }
    }
}
