package com.receptor.video;

import com.receptor.Receptor;
import com.receptor.network.TransferChunkPayload;
import com.receptor.network.TransferEndPayload;
import com.receptor.network.TransferStartPayload;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Receives video file chunks from the server via plugin messages,
 * assembles them into a temp file, then triggers playback.
 *
 * Also caches completed downloads in config/videoplayermod/videos/
 * so subsequent plays of the same video skip the transfer entirely.
 */
public final class VideoTransferReceiver {

    private static final VideoTransferReceiver INSTANCE = new VideoTransferReceiver();

    private volatile String videoId;
    private volatile String fileName;
    private volatile long fileSize;
    private volatile int chunkSize;
    private volatile int totalChunks;
    private volatile RandomAccessFile outputFile;
    private volatile Path tempFilePath;
    private final AtomicInteger receivedChunks = new AtomicInteger(0);

    private VideoTransferReceiver() {}

    public static VideoTransferReceiver getInstance() { return INSTANCE; }

    /**
     * Called when a TRANSFER_START payload is received.
     * Initializes the temp file and prepares to receive chunks.
     */
    public synchronized void onTransferStart(TransferStartPayload payload) {
        // Clean up any previous in-progress transfer
        cleanup();

        this.videoId = payload.videoId();
        this.fileName = payload.fileName();
        this.fileSize = payload.fileSize();
        this.chunkSize = payload.chunkSize();
        this.totalChunks = payload.totalChunks();
        this.receivedChunks.set(0);

        Receptor.LOGGER.info("[TRANSFER] Start: id='{}' file='{}' size={} chunks={}",
                videoId, fileName, fileSize, totalChunks);

        try {
            Path tempDir = Files.createTempDirectory("receptor-transfer-");
            tempFilePath = tempDir.resolve(fileName);
            outputFile = new RandomAccessFile(tempFilePath.toFile(), "rw");
            outputFile.setLength(fileSize); // pre-allocate

            // Update the playback manager's status text for the HUD
            VideoPlaybackManager.getInstance().setTransferStatus(
                    "Receiving: 0% (0/" + totalChunks + " chunks)");
        } catch (IOException e) {
            Receptor.LOGGER.error("[TRANSFER] Failed to create temp file: {}", e.getMessage());
            VideoPlaybackManager.getInstance().setTransferStatus("Error: " + e.getMessage());
            cleanup();
        }
    }

    /**
     * Called when a TRANSFER_CHUNK payload is received.
     * Writes data to the correct position in the temp file.
     */
    public void onTransferChunk(TransferChunkPayload payload) {
        RandomAccessFile raf = this.outputFile;
        if (raf == null) {
            Receptor.LOGGER.warn("[TRANSFER] Received chunk but no transfer in progress");
            return;
        }

        try {
            long offset = (long) payload.chunkIndex() * chunkSize;
            synchronized (raf) {
                raf.seek(offset);
                raf.write(payload.data());
            }

            int received = receivedChunks.incrementAndGet();
            int pct = totalChunks > 0 ? (received * 100 / totalChunks) : 0;

            // Update HUD every 5 chunks or on last chunk to avoid spamming
            if (received % 5 == 0 || received == totalChunks) {
                String sizeMB = String.format("%.1f", (long) received * chunkSize / 1048576.0);
                String totalMB = String.format("%.1f", fileSize / 1048576.0);
                VideoPlaybackManager.getInstance().setTransferStatus(
                        "Receiving: " + pct + "% (" + sizeMB + "/" + totalMB + " MB)");
            }
        } catch (IOException e) {
            Receptor.LOGGER.error("[TRANSFER] Failed to write chunk {}: {}", payload.chunkIndex(), e.getMessage());
        }
    }

    /**
     * Called when a TRANSFER_END payload is received.
     * Closes the file, caches it, and triggers playback.
     */
    public synchronized void onTransferEnd(TransferEndPayload payload) {
        Receptor.LOGGER.info("[TRANSFER] End: id='{}' received {}/{} chunks",
                payload.videoId(), receivedChunks.get(), totalChunks);

        RandomAccessFile raf = this.outputFile;
        if (raf == null) {
            Receptor.LOGGER.warn("[TRANSFER] Received end but no transfer in progress");
            return;
        }

        try {
            raf.close();
        } catch (IOException e) {
            Receptor.LOGGER.warn("[TRANSFER] Error closing temp file: {}", e.getMessage());
        }
        this.outputFile = null;

        if (receivedChunks.get() < totalChunks) {
            Receptor.LOGGER.warn("[TRANSFER] Incomplete: got {}/{} chunks", receivedChunks.get(), totalChunks);
            VideoPlaybackManager.getInstance().setTransferStatus(
                    "Error: Incomplete transfer (" + receivedChunks.get() + "/" + totalChunks + ")");
            return;
        }

        Path filePath = tempFilePath;
        if (filePath == null || !Files.exists(filePath)) {
            Receptor.LOGGER.error("[TRANSFER] Temp file missing after transfer");
            VideoPlaybackManager.getInstance().setTransferStatus("Error: File missing after transfer");
            return;
        }

        // Cache the video in the client's videos folder for future use
        Path cachedPath = cacheVideo(filePath, fileName);

        // Use cached path if available, otherwise use temp path
        Path playPath = cachedPath != null ? cachedPath : filePath;

        Receptor.LOGGER.info("[TRANSFER] Complete! Playing from: {}", playPath);

        // Tell the playback manager the file is ready — it will start decode+play
        VideoPlaybackManager.getInstance().onTransferComplete(videoId, playPath);
    }

    /**
     * Copies the transferred file to config/videoplayermod/videos/ for future re-use.
     */
    private Path cacheVideo(Path sourcePath, String fileName) {
        try {
            Path videosDir = FabricLoader.getInstance().getGameDir()
                    .resolve("config").resolve("videoplayermod").resolve("videos");
            Files.createDirectories(videosDir);
            Path cached = videosDir.resolve(fileName);
            Files.copy(sourcePath, cached, StandardCopyOption.REPLACE_EXISTING);
            Receptor.LOGGER.info("[TRANSFER] Cached video to: {}", cached);
            return cached;
        } catch (IOException e) {
            Receptor.LOGGER.warn("[TRANSFER] Failed to cache video: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Cleans up any in-progress transfer state.
     */
    private void cleanup() {
        RandomAccessFile raf = this.outputFile;
        if (raf != null) {
            try { raf.close(); } catch (IOException ignored) {}
            this.outputFile = null;
        }
        if (tempFilePath != null) {
            try { Files.deleteIfExists(tempFilePath); } catch (IOException ignored) {}
            // Also try to clean up the temp directory
            Path parent = tempFilePath.getParent();
            if (parent != null) {
                try { Files.deleteIfExists(parent); } catch (IOException ignored) {}
            }
            tempFilePath = null;
        }
        videoId = null;
        fileName = null;
        receivedChunks.set(0);
    }
}
