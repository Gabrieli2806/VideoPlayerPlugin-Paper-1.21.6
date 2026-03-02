package com.g2806.videoplayer.video;

import com.g2806.videoplayer.VideoPlayer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * Plays the audio track of a video file using ffmpeg for decoding
 * and javax.sound.sampled for playback.
 * Volume is applied by scaling PCM samples directly.
 */
public final class AudioPlayer {

    private volatile Process ffmpegProcess;
    private volatile Thread audioThread;
    private volatile SourceDataLine audioLine;
    private volatile float volume = 1.0f;
    private volatile boolean stopped = false;

    public void start(Path videoFile, float initialVolume) {
        this.volume = initialVolume;
        this.stopped = false;

        audioThread = new Thread(() -> audioLoop(videoFile), "videoplayer-audio-player");
        audioThread.setDaemon(true);
        audioThread.start();
    }

    public void stop() {
        stopped = true;

        Process p = ffmpegProcess;
        if (p != null) {
            p.destroyForcibly();
        }

        SourceDataLine line = audioLine;
        if (line != null) {
            try { line.stop(); } catch (Exception ignored) {}
            try { line.close(); } catch (Exception ignored) {}
        }

        Thread t = audioThread;
        if (t != null) {
            t.interrupt();
        }
    }

    public void setVolume(float v) {
        this.volume = Math.max(0f, Math.min(1f, v));
    }

    private void audioLoop(Path videoFile) {
        try {
            String ffmpegCmd = findFFmpeg();
            if (ffmpegCmd == null) {
                VideoPlayer.LOGGER.warn("ffmpeg not found in PATH — audio playback disabled.");
                return;
            }

            ProcessBuilder pb = new ProcessBuilder(
                    ffmpegCmd,
                    "-i", videoFile.toAbsolutePath().toString(),
                    "-vn",
                    "-f", "s16le",
                    "-acodec", "pcm_s16le",
                    "-ar", "44100",
                    "-ac", "2",
                    "pipe:1"
            );
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);

            ffmpegProcess = pb.start();
            InputStream pcmStream = ffmpegProcess.getInputStream();

            AudioFormat format = new AudioFormat(44100, 16, 2, true, false);
            audioLine = AudioSystem.getSourceDataLine(format);
            audioLine.open(format, 8192);
            audioLine.start();

            VideoPlayer.LOGGER.info("Audio playback started for '{}'", videoFile.getFileName());

            byte[] buffer = new byte[4096];
            int bytesRead;
            while (!stopped && (bytesRead = pcmStream.read(buffer)) != -1) {
                applyVolume(buffer, bytesRead);
                audioLine.write(buffer, 0, bytesRead);
            }
        } catch (Exception e) {
            if (!stopped) {
                VideoPlayer.LOGGER.warn("Audio playback error: {}", e.getMessage());
            }
        } finally {
            cleanup();
        }
    }

    private void applyVolume(byte[] buffer, int length) {
        float vol = this.volume;
        if (vol >= 0.99f) return;

        for (int i = 0; i + 1 < length; i += 2) {
            short sample = (short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8));
            sample = (short) Math.max(Short.MIN_VALUE,
                    Math.min(Short.MAX_VALUE, (int) (sample * vol)));
            buffer[i] = (byte) (sample & 0xFF);
            buffer[i + 1] = (byte) ((sample >> 8) & 0xFF);
        }
    }

    private String findFFmpeg() {
        for (String cmd : new String[]{"ffmpeg", "ffmpeg.exe"}) {
            try {
                Process p = new ProcessBuilder(cmd, "-version")
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
                if (p.waitFor() == 0) return cmd;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private void cleanup() {
        Process p = ffmpegProcess;
        ffmpegProcess = null;
        if (p != null) p.destroyForcibly();

        SourceDataLine line = audioLine;
        audioLine = null;
        if (line != null) {
            try { line.stop(); } catch (Exception ignored) {}
            try { line.close(); } catch (Exception ignored) {}
        }
    }
}
