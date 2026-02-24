package com.nightmare.videoplayermod.paper.command;

import com.nightmare.videoplayermod.paper.VideoPlayerPlugin;
import com.nightmare.videoplayermod.paper.config.VideoRegistry;
import com.nightmare.videoplayermod.paper.network.PayloadWriter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class CinematicCommand implements CommandExecutor, TabCompleter {

    private static final List<String> ROOT_SUBCOMMANDS = List.of("play", "stop", "list", "volume");
    private static final List<String> TARGET_SUGGESTIONS = List.of("@a", "@p", "@s");

    private final VideoPlayerPlugin plugin;
    private final VideoRegistry videoRegistry;

    public CinematicCommand(VideoPlayerPlugin plugin, VideoRegistry videoRegistry) {
        this.plugin = plugin;
        this.videoRegistry = videoRegistry;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.RED + "Usage: /cinematic <play|stop|list|volume>");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "play" -> handlePlay(sender, args);
            case "stop" -> handleStop(sender, args);
            case "list" -> handleList(sender);
            case "volume" -> handleVolume(sender, args);
            default -> {
                sender.sendMessage(ChatColor.RED + "Unknown subcommand. Use: /cinematic <play|stop|list|volume>");
                yield true;
            }
        };
    }

    private boolean handlePlay(CommandSender sender, String[] args) {
        if (!sender.hasPermission("videoplayermod.play")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission.");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /cinematic play <id> [targets]");
            return true;
        }

        String id = args[1];
        Map<String, String> videos = videoRegistry.loadVideos();

        plugin.getLogger().info("[DEBUG][Command] /cinematic play '" + id + "' requested by " + sender.getName() + ". Loaded IDs: " + videos.size());

        if (!videos.containsKey(id)) {
            plugin.getLogger().warning("[DEBUG][Command] Unknown video ID '" + id + "'. Available IDs: " + videos.keySet());
            sender.sendMessage(ChatColor.RED + "Unknown video ID: " + id);
            return true;
        }

        String source = videos.get(id);
        if (source == null || source.isBlank()) {
            plugin.getLogger().warning("[DEBUG][Command] Video ID '" + id + "' resolved to empty source");
            sender.sendMessage(ChatColor.RED + "Video source is empty for ID: " + id);
            return true;
        }

        String targetInput = args.length >= 3 ? args[2] : "@a";
        Collection<Player> targets = resolveTargets(sender, targetInput);

        plugin.getLogger().info("[DEBUG][Command] Broadcasting PlayVideoPayload id='" + id + "' to " + targets.size() + " player(s) (" + targetInput + ")");

        if (targets.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No target players matched.");
            return true;
        }

        byte[] payload = new PayloadWriter()
                .writeUtf(id)
                .writeUtf(source)
                .toByteArray();

        for (Player player : targets) {
            plugin.getLogger().info("[DEBUG][Command] Sending play payload to player '" + player.getName() + "'");
            player.sendPluginMessage(plugin, VideoPlayerPlugin.CHANNEL_PLAY_VIDEO, payload);
        }

        if ("@a".equals(targetInput)) {
            sender.sendMessage(ChatColor.GREEN + "Playing '" + id + "' for all players");
        } else {
            sender.sendMessage(ChatColor.GREEN + "Playing '" + id + "' for " + targets.size() + " player(s)");
        }

        return true;
    }

    private boolean handleStop(CommandSender sender, String[] args) {
        if (!sender.hasPermission("videoplayermod.stop")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission.");
            return true;
        }

        String targetInput = args.length >= 2 ? args[1] : "@a";
        Collection<Player> targets = resolveTargets(sender, targetInput);

        plugin.getLogger().info("[DEBUG][Command] Broadcasting StopVideoPayload to " + targets.size() + " player(s) (" + targetInput + ")");

        if (targets.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No target players matched.");
            return true;
        }

        byte[] payload = new byte[0];
        for (Player player : targets) {
            player.sendPluginMessage(plugin, VideoPlayerPlugin.CHANNEL_STOP_VIDEO, payload);
        }

        if ("@a".equals(targetInput)) {
            sender.sendMessage(ChatColor.GREEN + "Stopped video for all players");
        } else {
            sender.sendMessage(ChatColor.GREEN + "Stopped video for " + targets.size() + " player(s)");
        }

        return true;
    }

    private boolean handleList(CommandSender sender) {
        if (!sender.hasPermission("videoplayermod.list")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission.");
            return true;
        }

        Map<String, String> videos = videoRegistry.loadVideos();
        if (videos.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No videos registered. Add .mp4 files to config/videoplayermod/videos/ or add URLs to urls.properties");
            return true;
        }

        sender.sendMessage(ChatColor.GOLD + "Registered videos:");
        for (Map.Entry<String, String> entry : videos.entrySet()) {
            sender.sendMessage(ChatColor.GRAY + "  " + entry.getKey() + " = " + entry.getValue());
        }
        return true;
    }

    private boolean handleVolume(CommandSender sender, String[] args) {
        if (!sender.hasPermission("videoplayermod.volume")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission.");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /cinematic volume <0-100>");
            return true;
        }

        int level;
        try {
            level = Integer.parseInt(args[1]);
        } catch (NumberFormatException ex) {
            sender.sendMessage(ChatColor.RED + "Volume must be a number between 0 and 100.");
            return true;
        }

        if (level < 0 || level > 100) {
            sender.sendMessage(ChatColor.RED + "Volume must be between 0 and 100.");
            return true;
        }

        float volume = level / 100.0f;
        byte[] payload = new PayloadWriter().writeFloat(volume).toByteArray();

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendPluginMessage(plugin, VideoPlayerPlugin.CHANNEL_SET_VOLUME, payload);
        }

        sender.sendMessage(ChatColor.GREEN + "Video volume set to " + level + "%");
        return true;
    }

    private Collection<Player> resolveTargets(CommandSender sender, String targetInput) {
        String normalized = targetInput.toLowerCase(Locale.ROOT);

        return switch (normalized) {
            case "@a" -> new ArrayList<>(Bukkit.getOnlinePlayers());
            case "@s" -> {
                if (sender instanceof Player player) {
                    yield List.of(player);
                }
                yield Collections.emptyList();
            }
            case "@p" -> resolveNearestPlayer(sender);
            default -> {
                Player player = Bukkit.getPlayerExact(targetInput);
                if (player == null) {
                    yield Collections.emptyList();
                }
                yield List.of(player);
            }
        };
    }

    private Collection<Player> resolveNearestPlayer(CommandSender sender) {
        if (sender instanceof Player senderPlayer) {
            Location origin = senderPlayer.getLocation();
            return Bukkit.getOnlinePlayers().stream()
                    .min(Comparator.comparingDouble(player -> player.getLocation().distanceSquared(origin)))
                    .map(List::of)
                    .orElseGet(Collections::emptyList);
        }

        return Bukkit.getOnlinePlayers().stream().findFirst().<Collection<Player>>map(List::of).orElseGet(Collections::emptyList);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(ROOT_SUBCOMMANDS, args[0]);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if ("play".equals(sub)) {
            if (args.length == 2) {
                return filter(new ArrayList<>(videoRegistry.loadVideos().keySet()), args[1]);
            }
            if (args.length == 3) {
                return filter(buildTargetSuggestions(), args[2]);
            }
        }

        if ("stop".equals(sub) && args.length == 2) {
            return filter(buildTargetSuggestions(), args[1]);
        }

        if ("volume".equals(sub) && args.length == 2) {
            return filter(List.of("0", "25", "50", "75", "100"), args[1]);
        }

        return Collections.emptyList();
    }

    private List<String> buildTargetSuggestions() {
        Set<String> suggestions = new LinkedHashSet<>(TARGET_SUGGESTIONS);
        for (Player online : Bukkit.getOnlinePlayers()) {
            suggestions.add(online.getName());
        }
        return new ArrayList<>(suggestions);
    }

    private List<String> filter(List<String> values, String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lower)) {
                matches.add(value);
            }
        }
        return matches;
    }
}
