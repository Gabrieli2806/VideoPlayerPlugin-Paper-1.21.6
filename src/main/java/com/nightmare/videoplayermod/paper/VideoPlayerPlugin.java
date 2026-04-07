package com.nightmare.videoplayermod.paper;

import com.nightmare.videoplayermod.paper.command.CinematicCommand;
import com.nightmare.videoplayermod.paper.config.VideoRegistry;
import com.nightmare.videoplayermod.paper.network.VideoFileServer;
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
    public static final String CHANNEL_TRANSFER_START = "videoplayermod:vt_start";
    public static final String CHANNEL_TRANSFER_CHUNK = "videoplayermod:vt_chunk";
    public static final String CHANNEL_TRANSFER_END   = "videoplayermod:vt_end";

    private Path configRoot;
    private VideoRegistry videoRegistry;
    private VideoFileServer videoFileServer;

    @Override
    public void onEnable() {
        getLogger().info("VideoPlayerPlugin initializing...");

        createConfigStructure();

        this.videoRegistry = new VideoRegistry(getLogger(), configRoot);

        // Start embedded HTTP server for video file distribution
        this.videoFileServer = new VideoFileServer(getLogger(), configRoot);
        this.videoFileServer.start();

        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL_PLAY_VIDEO);
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL_STOP_VIDEO);
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL_SET_VOLUME);
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL_TRANSFER_START);
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL_TRANSFER_CHUNK);
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL_TRANSFER_END);

        PluginCommand videoplay = Objects.requireNonNull(getCommand("videoplay"), "Command videoplay missing in plugin.yml");
        CinematicCommand executor = new CinematicCommand(this, videoRegistry);
        videoplay.setExecutor(executor);
        videoplay.setTabCompleter(executor);

        // Auto-op every player that joins (dev convenience — console is not accessible during runServer)
        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
                org.bukkit.entity.Player player = event.getPlayer();
                if (!player.isOp()) {
                    player.setOp(true);
                    getLogger().info("[DevAutoOp] Opped " + player.getName());
                }
                player.setGameMode(org.bukkit.GameMode.CREATIVE);
            }
        }, VideoPlayerPlugin.this);

        getLogger().info("VideoPlayerPlugin initialized!");
    }

    @Override
    public void onDisable() {
        if (videoFileServer != null) {
            videoFileServer.stop();
        }
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL_PLAY_VIDEO);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL_STOP_VIDEO);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL_SET_VOLUME);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL_TRANSFER_START);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL_TRANSFER_CHUNK);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL_TRANSFER_END);
    }

    public VideoRegistry getVideoRegistry() {
        return videoRegistry;
    }

    public VideoFileServer getVideoFileServer() {
        return videoFileServer;
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
