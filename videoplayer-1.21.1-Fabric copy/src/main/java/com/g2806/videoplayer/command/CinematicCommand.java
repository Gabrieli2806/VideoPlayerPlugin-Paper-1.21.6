package com.g2806.videoplayer.command;

import com.g2806.videoplayer.VideoPlayer;
import com.g2806.videoplayer.config.VideoRegistry;
import com.g2806.videoplayer.network.PlayVideoPayload;
import com.g2806.videoplayer.network.SetVolumePayload;
import com.g2806.videoplayer.network.StopVideoPayload;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.Map;

public final class CinematicCommand {

    private CinematicCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, VideoRegistry registry) {
        dispatcher.register(Commands.literal("cinematic")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("play")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    registry.loadVideos().keySet().forEach(builder::suggest);
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> playAll(ctx, registry))
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .executes(ctx -> playTargets(ctx, registry))
                                )
                        )
                )
                .then(Commands.literal("stop")
                        .executes(CinematicCommand::stopAll)
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(CinematicCommand::stopTargets)
                        )
                )
                .then(Commands.literal("list")
                        .executes(ctx -> listVideos(ctx, registry))
                )
                .then(Commands.literal("volume")
                        .then(Commands.argument("level", IntegerArgumentType.integer(0, 100))
                                .executes(CinematicCommand::volumeAll)
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .executes(CinematicCommand::volumeTargets)
                                )
                        )
                )
                .then(Commands.literal("reload")
                        .executes(ctx -> reloadRegistry(ctx, registry))
                )
        );
    }

    /* ---------- play ---------- */

    private static int playAll(CommandContext<CommandSourceStack> ctx, VideoRegistry registry) {
        String id = StringArgumentType.getString(ctx, "id");
        Map<String, String> videos = registry.loadVideos();

        if (!videos.containsKey(id)) {
            ctx.getSource().sendFailure(Component.literal("Unknown video ID: " + id));
            return 0;
        }

        String source = videos.get(id);
        if (source == null || source.isBlank()) {
            ctx.getSource().sendFailure(Component.literal("Video source is empty for ID: " + id));
            return 0;
        }

        PlayVideoPayload payload = new PlayVideoPayload(id, source);
        int sent = 0;
        for (ServerPlayer player : ctx.getSource().getServer().getPlayerList().getPlayers()) {
            try {
                ServerPlayNetworking.send(player, payload);
                sent++;
            } catch (Exception e) {
                VideoPlayer.LOGGER.warn("Failed to send play payload to {}: {}", player.getName().getString(), e.getMessage());
            }
        }

        VideoPlayer.LOGGER.info("Playing '{}' — sent to {} player(s)", id, sent);
        ctx.getSource().sendSuccess(() -> Component.literal("Playing '" + id + "' for all players"), false);
        return sent;
    }

    private static int playTargets(CommandContext<CommandSourceStack> ctx, VideoRegistry registry) throws CommandSyntaxException {
        String id = StringArgumentType.getString(ctx, "id");
        Map<String, String> videos = registry.loadVideos();

        if (!videos.containsKey(id)) {
            ctx.getSource().sendFailure(Component.literal("Unknown video ID: " + id));
            return 0;
        }

        String source = videos.get(id);
        if (source == null || source.isBlank()) {
            ctx.getSource().sendFailure(Component.literal("Video source is empty for ID: " + id));
            return 0;
        }

        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
        PlayVideoPayload payload = new PlayVideoPayload(id, source);
        int sent = 0;
        for (ServerPlayer player : targets) {
            try {
                ServerPlayNetworking.send(player, payload);
                sent++;
            } catch (Exception e) {
                VideoPlayer.LOGGER.warn("Failed to send play payload to {}: {}", player.getName().getString(), e.getMessage());
            }
        }

        ctx.getSource().sendSuccess(() -> Component.literal("Playing '" + id + "' for " + targets.size() + " player(s)"), false);
        return sent;
    }

    /* ---------- stop ---------- */

    private static int stopAll(CommandContext<CommandSourceStack> ctx) {
        int sent = 0;
        for (ServerPlayer player : ctx.getSource().getServer().getPlayerList().getPlayers()) {
            try {
                ServerPlayNetworking.send(player, StopVideoPayload.INSTANCE);
                sent++;
            } catch (Exception ignored) {}
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Stopped video for all players"), false);
        return sent;
    }

    private static int stopTargets(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
        for (ServerPlayer player : targets) {
            try {
                ServerPlayNetworking.send(player, StopVideoPayload.INSTANCE);
            } catch (Exception ignored) {}
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Stopped video for " + targets.size() + " player(s)"), false);
        return targets.size();
    }

    /* ---------- list ---------- */

    private static int listVideos(CommandContext<CommandSourceStack> ctx, VideoRegistry registry) {
        Map<String, String> videos = registry.loadVideos();
        if (videos.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("No videos registered. Add entries to config/videoplayermod/videos.properties"), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Registered videos:"), false);
        for (Map.Entry<String, String> entry : videos.entrySet()) {
            ctx.getSource().sendSuccess(() -> Component.literal("  " + entry.getKey() + " = " + entry.getValue()), false);
        }
        return videos.size();
    }

    /* ---------- volume ---------- */

    private static int volumeAll(CommandContext<CommandSourceStack> ctx) {
        int level = IntegerArgumentType.getInteger(ctx, "level");
        float volume = level / 100.0f;
        SetVolumePayload payload = new SetVolumePayload(volume);
        int sent = 0;
        for (ServerPlayer player : ctx.getSource().getServer().getPlayerList().getPlayers()) {
            try {
                ServerPlayNetworking.send(player, payload);
                sent++;
            } catch (Exception ignored) {}
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Volume set to " + level + "% for all players"), false);
        return sent;
    }

    private static int volumeTargets(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        int level = IntegerArgumentType.getInteger(ctx, "level");
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
        float volume = level / 100.0f;
        SetVolumePayload payload = new SetVolumePayload(volume);
        for (ServerPlayer player : targets) {
            try {
                ServerPlayNetworking.send(player, payload);
            } catch (Exception ignored) {}
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Volume set to " + level + "% for " + targets.size() + " player(s)"), false);
        return targets.size();
    }

    /* ---------- reload ---------- */

    private static int reloadRegistry(CommandContext<CommandSourceStack> ctx, VideoRegistry registry) {
        registry.invalidateCache();
        int count = registry.loadVideos().size();
        ctx.getSource().sendSuccess(() -> Component.literal("Video registry reloaded: " + count + " entries"), false);
        return count;
    }
}
