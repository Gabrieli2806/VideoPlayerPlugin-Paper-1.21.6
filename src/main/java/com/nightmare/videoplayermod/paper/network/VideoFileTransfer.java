package com.nightmare.videoplayermod.paper.network;

import com.nightmare.videoplayermod.paper.VideoPlayerPlugin;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Transfers a video file to a player via Minecraft plugin messages (chunked).
 * Uses the existing MC connection — no extra ports needed.
 * <p>
 * Protocol:
 *   1. Send CHANNEL_TRANSFER_START: writeUtf(videoId) + writeUtf(fileName) + writeLong(fileSize) + writeInt(chunkSize) + writeInt(totalChunks)
 *   2. Send CHANNEL_TRANSFER_CHUNK × N: writeInt(chunkIndex) + writeBytes(data)
 *   3. Send CHANNEL_TRANSFER_END: writeUtf(videoId)
 * <p>
 * Chunk size is 30,000 bytes to stay well under Minecraft's 1MB plugin message limit
 * and to avoid overwhelming the network buffer.
 */
public final class VideoFileTransfer {

    /** 30KB per chunk — safe for all server implementations and proxies */
    private static final int CHUNK_SIZE = 30_000;

    /** Chunks to send per server tick (50ms). ~600KB/tick = ~12MB/s theoretical throughput */
    private static final int CHUNKS_PER_TICK = 20;

    private VideoFileTransfer() {}

    /**
     * Starts an async chunked file transfer to the given player.
     * First sends the play command so the client knows what's coming,
     * then streams the file in chunks.
     *
     * @param plugin    The plugin instance
     * @param player    Target player
     * @param videoId   Video ID
     * @param filePath  Path to the video file on the server
     * @param logger    Logger
     */
    public static void transferToPlayer(Plugin plugin, Player player, String videoId, Path filePath, Logger logger) {
        try {
            long fileSize = Files.size(filePath);
            String fileName = filePath.getFileName().toString();
            int totalChunks = (int) Math.ceil((double) fileSize / CHUNK_SIZE);

            logger.info("Starting file transfer to " + player.getName() + ": " + fileName
                    + " (" + (fileSize / 1024 / 1024) + " MB, " + totalChunks + " chunks)");

            // 1) Send play command with transfer:// source so client knows to wait for chunks
            byte[] playPayload = new PayloadWriter()
                    .writeUtf(videoId)
                    .writeUtf("transfer://" + fileName)
                    .toByteArray();
            DirectPayloadSender.send(plugin, player, VideoPlayerPlugin.CHANNEL_PLAY_VIDEO, playPayload, logger);

            // 2) Send transfer start metadata
            byte[] startPayload = new PayloadWriter()
                    .writeUtf(videoId)
                    .writeUtf(fileName)
                    .writeLong(fileSize)
                    .writeInt(CHUNK_SIZE)
                    .writeInt(totalChunks)
                    .toByteArray();
            DirectPayloadSender.send(plugin, player, VideoPlayerPlugin.CHANNEL_TRANSFER_START, startPayload, logger);

            // 3) Read file and send chunks using BukkitRunnable to pace delivery
            byte[] fileData = Files.readAllBytes(filePath);

            new BukkitRunnable() {
                int chunkIndex = 0;

                @Override
                public void run() {
                    if (!player.isOnline()) {
                        logger.info("Player " + player.getName() + " disconnected — aborting transfer");
                        cancel();
                        return;
                    }

                    for (int i = 0; i < CHUNKS_PER_TICK && chunkIndex < totalChunks; i++, chunkIndex++) {
                        int offset = chunkIndex * CHUNK_SIZE;
                        int length = Math.min(CHUNK_SIZE, fileData.length - offset);

                        byte[] chunkPayload = new PayloadWriter()
                                .writeInt(chunkIndex)
                                .writeInt(length)
                                .writeBytes(fileData, offset, length)
                                .toByteArray();

                        DirectPayloadSender.send(plugin, player, VideoPlayerPlugin.CHANNEL_TRANSFER_CHUNK, chunkPayload, logger);
                    }

                    if (chunkIndex >= totalChunks) {
                        // 4) Send transfer end
                        byte[] endPayload = new PayloadWriter()
                                .writeUtf(videoId)
                                .toByteArray();
                        DirectPayloadSender.send(plugin, player, VideoPlayerPlugin.CHANNEL_TRANSFER_END, endPayload, logger);

                        logger.info("File transfer complete to " + player.getName() + ": " + fileName);
                        cancel();
                    }
                }
            }.runTaskTimer(plugin, 1L, 1L); // Run every tick (50ms)

        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to start file transfer for " + videoId, e);
        }
    }
}
