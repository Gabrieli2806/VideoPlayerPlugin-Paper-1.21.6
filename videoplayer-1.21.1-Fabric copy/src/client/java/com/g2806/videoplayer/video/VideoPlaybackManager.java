package com.g2806.videoplayer.video;

import com.g2806.videoplayer.VideoPlayer;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Architecture: Pre-decode to file + Queue + Render-Paced
 *
 * Phase 1 (pre-decode): ffmpeg decodes ALL video frames to a temp .raw file on disk.
 * Phase 2 (stream): A reader thread reads frames from the file into a queue.
 * Render:  MC's render thread (~60fps) picks frames from the queue based on elapsed time.
 *
 * Early start: playback begins once 2 seconds of frames are pre-decoded.
 * Audio sync:  audio starts exactly when pre-buffer fills and first frame is shown.
 */
public final class VideoPlaybackManager {

    private static final VideoPlaybackManager INSTANCE = new VideoPlaybackManager();
    private static final double DEFAULT_FPS = 30.0;
    private static final int MAX_OUTPUT_WIDTH = 854;
    private static final int MAX_OUTPUT_HEIGHT = 480;

    private static final int QUEUE_CAPACITY = 60;
    private static final int PRE_BUFFER_FRAMES = 10;

    private final AtomicLong generation = new AtomicLong(0);
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile Thread workerThread;
    private volatile Process ffmpegProcess;
    private volatile String currentVideoId = "";
    private volatile String statusText = "";
    private volatile float volume = 1.0f;

    /* ---------- frame queue ---------- */
    private static final class FrameBuffer {
        int[] pixels;
        int width, height;
    }

    private volatile ArrayBlockingQueue<FrameBuffer> frameQueue;
    private volatile ArrayBlockingQueue<int[]> pixelPool;

    /* ---------- render state (render thread only) ---------- */
    private DynamicTexture texture;
    private ResourceLocation textureId;
    private Path tempDownloadedFile;
    private Path tempRawFile;
    private volatile int videoWidth;
    private volatile int videoHeight;
    private volatile double videoFps;
    private long playbackStartNano;
    private long framesDisplayed;
    private boolean preBuffering;
    private volatile Path videoFilePath;

    /* ---------- debug stats ---------- */
    private volatile int debugQueueSize;
    private volatile double debugStreamFps;
    private volatile double debugAvgReadMs;
    private volatile int debugDropped;
    private volatile int debugOutW, debugOutH;
    private volatile int debugEstTotalFrames;
    private volatile int debugDecodedFrames;
    private volatile boolean debugDecodeComplete;

    private long debugRenderFrames;
    private long debugRenderStartNano;
    private volatile double debugRenderFps;
    private long debugUploadTotalNs;
    private int debugUploadCount;

    /* ---------- audio ---------- */
    private AudioPlayer audioPlayer;

    /* ---------- NativeImage reflection ---------- */
    private static Method getPointerMethod;
    private static Field pixelsField;
    private static boolean reflectionInit = false;

    private VideoPlaybackManager() {}

    public static VideoPlaybackManager getInstance() { return INSTANCE; }

    /* ======================= public API ======================= */

    public synchronized void play(String id, String source) {
        stop();
        currentVideoId = id;
        statusText = "Loading...";
        running.set(true);

        playbackStartNano = 0;
        framesDisplayed = 0;
        preBuffering = true;
        debugRenderFrames = 0;
        debugRenderStartNano = 0;
        debugRenderFps = 0;
        debugUploadTotalNs = 0;
        debugUploadCount = 0;
        debugDropped = 0;
        debugDecodedFrames = 0;
        debugDecodeComplete = false;
        debugEstTotalFrames = 0;
        videoFilePath = null;

        frameQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        pixelPool = new ArrayBlockingQueue<>(QUEUE_CAPACITY + 4);

        long gen = generation.incrementAndGet();
        workerThread = new Thread(() -> workerLoop(id, source, gen), "videoplayer-video-worker");
        workerThread.setDaemon(true);
        workerThread.setPriority(Thread.MAX_PRIORITY);
        workerThread.start();
    }

    public synchronized void stop() {
        running.set(false);
        Process p = ffmpegProcess;
        ffmpegProcess = null;
        if (p != null) p.destroyForcibly();
        Thread t = workerThread;
        workerThread = null;
        if (t != null) {
            t.interrupt();
            try { t.join(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        ArrayBlockingQueue<FrameBuffer> q = frameQueue;
        if (q != null) q.clear();
        frameQueue = null;
        pixelPool = null;

        statusText = "";
        currentVideoId = "";
        videoWidth = 0;
        videoHeight = 0;
        videoFps = 0;
        videoFilePath = null;
        clearTexture();
        deleteTempFiles();
        stopAudio();
    }

    public void setVolume(float v) {
        this.volume = Math.max(0f, Math.min(1f, v));
        AudioPlayer ap = audioPlayer;
        if (ap != null) ap.setVolume(this.volume);
    }

    public boolean isRunning()         { return running.get(); }
    public String  getStatusText()     { return statusText; }
    public float   getVolume()         { return volume; }
    public String  getCurrentVideoId() { return currentVideoId; }
    public ResourceLocation getTextureId() { return textureId; }
    public int getVideoWidth()  { return videoWidth; }
    public int getVideoHeight() { return videoHeight; }

    /* ======================= render (render thread ~60fps) ======================= */

    public void render(GuiGraphics context) {
        debugRenderFrames++;
        long now = System.nanoTime();
        if (debugRenderStartNano == 0) debugRenderStartNano = now;
        long dt = now - debugRenderStartNano;
        if (dt >= 500_000_000L) {
            debugRenderFps = debugRenderFrames / (dt / 1_000_000_000.0);
            debugRenderFrames = 0;
            debugRenderStartNano = now;
        }

        ArrayBlockingQueue<FrameBuffer> q = frameQueue;
        if (q == null) return;

        // Pre-buffering
        if (preBuffering) {
            if (q.size() >= PRE_BUFFER_FRAMES) {
                preBuffering = false;
                playbackStartNano = System.nanoTime();
                framesDisplayed = 0;
                Path vf = videoFilePath;
                if (vf != null) startAudio(vf);
            } else {
                return;
            }
        }

        if (videoFps <= 0) return;
        long elapsed = System.nanoTime() - playbackStartNano;
        long targetFrame = (long) (elapsed / (1_000_000_000.0 / videoFps));

        // Pop frames from queue until we reach targetFrame
        FrameBuffer frameToShow = null;
        int dropped = 0;
        while (framesDisplayed <= targetFrame) {
            FrameBuffer fb = q.poll();
            if (fb == null) break;
            if (frameToShow != null) {
                recyclePixels(frameToShow.pixels);
                dropped++;
            }
            frameToShow = fb;
            framesDisplayed++;
        }
        debugDropped += dropped;

        if (frameToShow != null) {
            long t0 = System.nanoTime();
            uploadPixels(frameToShow.pixels, frameToShow.width, frameToShow.height);
            debugUploadTotalNs += System.nanoTime() - t0;
            debugUploadCount++;
            recyclePixels(frameToShow.pixels);
        }

        // Blit full-screen
        if (textureId != null && texture != null && videoWidth > 0 && videoHeight > 0) {
            Minecraft mc = Minecraft.getInstance();
            int sw = mc.getWindow().getGuiScaledWidth();
            int sh = mc.getWindow().getGuiScaledHeight();
            context.blit(textureId, 0, 0, sw, sh, 0.0f, 0.0f,
                    videoWidth, videoHeight, videoWidth, videoHeight);
        }

        debugQueueSize = q.size();
    }

    /* ======================= debug HUD ======================= */

    public String getDebugLine1() {
        if (!running.get()) return "";
        return String.format("rFPS:%.0f  vFPS:%.0f  sFPS:%.1f  Q:%d  drop:%d",
                debugRenderFps, videoFps, debugStreamFps, debugQueueSize, debugDropped);
    }

    public String getDebugLine2() {
        if (!running.get()) return "";
        double avgUp = debugUploadCount > 0 ? (debugUploadTotalNs / (double) debugUploadCount / 1_000_000.0) : 0;
        String decStatus = debugDecodeComplete ? "done" :
                (debugEstTotalFrames > 0
                        ? (debugDecodedFrames * 100 / debugEstTotalFrames) + "%"
                        : debugDecodedFrames + "f");
        return String.format("%dx%d  read:%.1fms  up:%.2fms  dec:%s",
                debugOutW, debugOutH, debugAvgReadMs, avgUp, decStatus);
    }

    /* ======================= worker thread ======================= */

    private void workerLoop(String id, String source, long myGen) {
        Process ffmpeg = null;
        Path rawFile = null;
        FileChannel readChannel = null;
        try {
            Path file = resolveSource(source);

            String ffmpegCmd = findExecutable("ffmpeg", "ffmpeg.exe");
            if (ffmpegCmd == null) {
                statusText = "Error: ffmpeg not found";
                VideoPlayer.LOGGER.error("ffmpeg not found");
                return;
            }

            // ---- Probe dimensions + FPS ----
            int origW = 0, origH = 0;
            double fps = DEFAULT_FPS;
            double duration = 0;
            String ffprobeCmd = findExecutable("ffprobe", "ffprobe.exe");
            if (ffprobeCmd != null) {
                try {
                    ProcessBuilder pb = new ProcessBuilder(
                            ffprobeCmd, "-v", "error",
                            "-select_streams", "v:0",
                            "-show_entries", "stream=width,height,r_frame_rate",
                            "-of", "csv=p=0",
                            file.toAbsolutePath().toString());
                    pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                    Process probe = pb.start();
                    String out = new String(probe.getInputStream().readAllBytes()).trim();
                    probe.waitFor(5, TimeUnit.SECONDS);
                    String[] parts = out.split(",");
                    if (parts.length >= 3) {
                        String fpsStr = parts[parts.length - 1].trim();
                        origH = Integer.parseInt(parts[parts.length - 2].trim());
                        origW = Integer.parseInt(parts[parts.length - 3].trim());
                        String[] frac = fpsStr.split("/");
                        fps = Double.parseDouble(frac[0]);
                        if (frac.length > 1 && Double.parseDouble(frac[1]) > 0)
                            fps /= Double.parseDouble(frac[1]);
                    }
                } catch (Exception e) {
                    VideoPlayer.LOGGER.warn("ffprobe dim/fps failed: {}", e.getMessage());
                }
                try {
                    ProcessBuilder pb = new ProcessBuilder(
                            ffprobeCmd, "-v", "error",
                            "-show_entries", "format=duration",
                            "-of", "csv=p=0",
                            file.toAbsolutePath().toString());
                    pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                    Process probe = pb.start();
                    String out = new String(probe.getInputStream().readAllBytes()).trim();
                    probe.waitFor(5, TimeUnit.SECONDS);
                    duration = Double.parseDouble(out.trim());
                } catch (Exception e) {
                    VideoPlayer.LOGGER.warn("ffprobe duration failed: {}", e.getMessage());
                }
            }
            if (origW <= 0 || origH <= 0) { origW = 480; origH = 360; }

            // Scale down if needed
            int outW = origW, outH = origH;
            String scaleFilter = null;
            if (origW > MAX_OUTPUT_WIDTH || origH > MAX_OUTPUT_HEIGHT) {
                double s = Math.min((double) MAX_OUTPUT_WIDTH / origW, (double) MAX_OUTPUT_HEIGHT / origH);
                outW = ((int) (origW * s)) & ~1;
                outH = ((int) (origH * s)) & ~1;
                scaleFilter = "scale=" + outW + ":" + outH;
            }

            this.videoFps = fps;
            this.videoWidth = outW;
            this.videoHeight = outH;
            this.debugOutW = outW;
            this.debugOutH = outH;
            this.videoFilePath = file;

            int frameSize = outW * outH * 4;
            int pixelCount = outW * outH;
            int estimatedTotal = duration > 0 ? (int) (duration * fps) : 0;
            debugEstTotalFrames = estimatedTotal;

            VideoPlayer.LOGGER.info("Video '{}': {}x{} -> {}x{} @ {}fps, est {} frames",
                    id, origW, origH, outW, outH, String.format("%.2f", fps), estimatedTotal);

            // ========== PHASE 1: Pre-decode to temp file ==========
            rawFile = Files.createTempFile("videoplayer_raw_", ".bin");
            tempRawFile = rawFile;

            java.util.List<String> args = new java.util.ArrayList<>();
            args.add(ffmpegCmd);
            args.add("-i"); args.add(file.toAbsolutePath().toString());
            if (scaleFilter != null) { args.add("-vf"); args.add(scaleFilter); }
            args.add("-f"); args.add("rawvideo");
            args.add("-pix_fmt"); args.add("rgba");
            args.add("-v"); args.add("quiet");
            args.add("-nostdin");
            args.add("-y");
            args.add(rawFile.toAbsolutePath().toString());

            ProcessBuilder videoPb = new ProcessBuilder(args);
            videoPb.redirectError(ProcessBuilder.Redirect.DISCARD);
            ffmpeg = videoPb.start();
            ffmpegProcess = ffmpeg;

            int earlyStartThreshold = Math.max(PRE_BUFFER_FRAMES + 5, (int) (fps * 2));
            statusText = "Decoding video...";

            while (ffmpeg.isAlive() && running.get() && generation.get() == myGen) {
                long fileSize = Files.size(rawFile);
                int framesReady = (int) (fileSize / frameSize);
                debugDecodedFrames = framesReady;

                if (estimatedTotal > 0) {
                    int pct = Math.min(100, framesReady * 100 / estimatedTotal);
                    statusText = "Decoding: " + pct + "% (" + framesReady + " frames)";
                } else {
                    statusText = "Decoding: " + framesReady + " frames";
                }

                if (framesReady >= earlyStartThreshold) break;
                Thread.sleep(100);
            }

            if (!running.get() || generation.get() != myGen) return;

            statusText = "Playing: " + id;

            // ========== PHASE 2: Stream from file into queue ==========
            ArrayBlockingQueue<int[]> pool = this.pixelPool;
            if (pool != null) {
                for (int i = 0; i < QUEUE_CAPACITY + 2; i++) {
                    pool.offer(new int[pixelCount]);
                }
            }

            readChannel = FileChannel.open(rawFile, StandardOpenOption.READ);
            ByteBuffer readBuf = ByteBuffer.allocateDirect(frameSize);
            readBuf.order(ByteOrder.LITTLE_ENDIAN);
            int framesRead = 0;

            long logStart = System.nanoTime();
            long totalReadNs = 0;
            int logFrames = 0;

            while (running.get() && generation.get() == myGen) {
                long fileSize = Files.size(rawFile);
                int framesAvailable = (int) (fileSize / frameSize);
                debugDecodedFrames = framesAvailable;

                if (framesRead >= framesAvailable) {
                    if (!ffmpeg.isAlive()) {
                        debugDecodeComplete = true;
                        break;
                    }
                    Thread.sleep(5);
                    continue;
                }

                long t0 = System.nanoTime();
                readBuf.clear();
                long filePos = (long) framesRead * frameSize;
                int totalRead = 0;
                while (totalRead < frameSize) {
                    int r = readChannel.read(readBuf, filePos + totalRead);
                    if (r < 0) break;
                    totalRead += r;
                }
                if (totalRead < frameSize) {
                    if (!ffmpeg.isAlive()) break;
                    Thread.sleep(5);
                    continue;
                }
                totalReadNs += System.nanoTime() - t0;

                int[] pixels = (pool != null) ? pool.poll() : null;
                if (pixels == null || pixels.length != pixelCount) {
                    pixels = new int[pixelCount];
                }
                readBuf.flip();
                readBuf.asIntBuffer().get(pixels, 0, pixelCount);

                FrameBuffer fb = new FrameBuffer();
                fb.pixels = pixels;
                fb.width = outW;
                fb.height = outH;

                ArrayBlockingQueue<FrameBuffer> q = this.frameQueue;
                if (q != null) {
                    q.put(fb);
                }

                framesRead++;
                logFrames++;

                long now = System.nanoTime();
                if (now - logStart >= 2_000_000_000L) {
                    double sec = (now - logStart) / 1_000_000_000.0;
                    debugStreamFps = logFrames / sec;
                    debugAvgReadMs = logFrames > 0 ? (totalReadNs / (double) logFrames / 1_000_000.0) : 0;
                    VideoPlayer.LOGGER.info("[STREAM] fps={} avgRead={}ms queue={} decoded={}/{}",
                            String.format("%.1f", debugStreamFps),
                            String.format("%.2f", debugAvgReadMs),
                            q != null ? q.size() : 0,
                            framesAvailable,
                            estimatedTotal > 0 ? estimatedTotal : "?");
                    logStart = now;
                    totalReadNs = 0;
                    logFrames = 0;
                }
            }

            debugDecodeComplete = true;

            ArrayBlockingQueue<FrameBuffer> q = this.frameQueue;
            while (running.get() && generation.get() == myGen && q != null && !q.isEmpty()) {
                Thread.sleep(50);
            }

        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            VideoPlayer.LOGGER.error("Failed to play video '{}': {}", id, ex.getMessage(), ex);
            statusText = "Error: " + id;
        } finally {
            if (readChannel != null) {
                try { readChannel.close(); } catch (IOException ignored) {}
            }
            if (ffmpeg != null) ffmpeg.destroyForcibly();
            if (generation.get() == myGen) {
                ffmpegProcess = null;
                running.set(false);
                deleteTempFiles();
            }
        }
    }

    private void recyclePixels(int[] arr) {
        ArrayBlockingQueue<int[]> pool = this.pixelPool;
        if (pool != null && arr != null) pool.offer(arr);
    }

    /* ======================= helpers ======================= */

    private Path resolveSource(String source) throws IOException, InterruptedException {
        if (source.startsWith("http://") || source.startsWith("https://")) {
            statusText = "Downloading video...";
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(source))
                    .timeout(Duration.ofMinutes(5)).GET().build();
            Path downloaded = Files.createTempFile("videoplayer-video-", ".mp4");
            HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(downloaded));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                Files.deleteIfExists(downloaded);
                throw new IOException("HTTP " + response.statusCode());
            }
            tempDownloadedFile = downloaded;
            return downloaded;
        }
        Path localPath = Path.of(source);
        if (!Files.exists(localPath)) throw new IOException("File not found: " + source);
        return localPath;
    }

    private void uploadPixels(int[] pixels, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        this.videoWidth = w;
        this.videoHeight = h;

        if (texture != null && textureId != null) {
            NativeImage existing = texture.getPixels();
            if (existing != null && existing.getWidth() == w && existing.getHeight() == h) {
                bulkCopy(existing, pixels, w, h);
                texture.upload();
                return;
            }
        }
        NativeImage img = new NativeImage(w, h, false);
        bulkCopy(img, pixels, w, h);
        if (texture != null) { texture.close(); texture = null; }
        if (textureId == null) textureId = ResourceLocation.parse("videoplayer:video_overlay");
        texture = new DynamicTexture(img);
        mc.getTextureManager().register(textureId, texture);
        texture.upload();
    }

    private static void bulkCopy(NativeImage image, int[] pixels, int w, int h) {
        // Fast path: direct memory copy via pointer
        try {
            long ptr = getImagePointer(image);
            if (ptr != 0L) {
                MemoryUtil.memIntBuffer(ptr, w * h).put(0, pixels, 0, w * h);
                return;
            }
        } catch (Exception ignored) {}

        // Slow fallback: per-pixel
        for (int y = 0; y < h; y++) {
            int off = y * w;
            for (int x = 0; x < w; x++) {
                image.setPixelRGBA(x, y, pixels[off + x]);
            }
        }
    }

    /** Get NativeImage pixel pointer via reflection (works across MC versions). */
    private static long getImagePointer(NativeImage image) {
        if (!reflectionInit) {
            reflectionInit = true;
            // Try 1.21.6+ public method
            try {
                getPointerMethod = NativeImage.class.getMethod("getPointer");
            } catch (Exception ignored) {}
            // Try private 'pixels' field (1.21.1)
            if (getPointerMethod == null) {
                try {
                    pixelsField = NativeImage.class.getDeclaredField("pixels");
                    pixelsField.setAccessible(true);
                } catch (Exception ignored) {}
            }
        }
        try {
            if (getPointerMethod != null) {
                return (long) getPointerMethod.invoke(image);
            }
            if (pixelsField != null) {
                return (long) pixelsField.get(image);
            }
        } catch (Exception ignored) {}
        return 0L;
    }

    private static String findExecutable(String... names) {
        for (String cmd : names) {
            try {
                Process p = new ProcessBuilder(cmd, "-version")
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) return cmd;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private void startAudio(Path videoFile) {
        try {
            audioPlayer = new AudioPlayer();
            audioPlayer.start(videoFile, volume);
        } catch (Exception e) {
            VideoPlayer.LOGGER.warn("Could not start audio: {}", e.getMessage());
            audioPlayer = null;
        }
    }

    private void stopAudio() {
        AudioPlayer ap = audioPlayer;
        audioPlayer = null;
        if (ap != null) ap.stop();
    }

    private synchronized void clearTexture() {
        if (texture != null) { texture.close(); texture = null; }
        textureId = null;
    }

    private synchronized void deleteTempFiles() {
        if (tempDownloadedFile != null) {
            try { Files.deleteIfExists(tempDownloadedFile); } catch (IOException ignored) {}
            tempDownloadedFile = null;
        }
        if (tempRawFile != null) {
            try { Files.deleteIfExists(tempRawFile); } catch (IOException ignored) {}
            tempRawFile = null;
        }
    }
}
