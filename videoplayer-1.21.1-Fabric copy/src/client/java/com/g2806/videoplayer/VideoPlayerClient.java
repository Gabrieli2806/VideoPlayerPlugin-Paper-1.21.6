package com.g2806.videoplayer;

import com.g2806.videoplayer.block.ModBlocks;
import com.g2806.videoplayer.block.VideoDisplayBlockRenderer;
import com.g2806.videoplayer.network.PlayVideoPayload;
import com.g2806.videoplayer.network.SetVolumePayload;
import com.g2806.videoplayer.network.StopVideoPayload;
import com.g2806.videoplayer.video.VideoHudOverlay;
import com.g2806.videoplayer.video.VideoPlaybackManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;

public class VideoPlayerClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Register HUD overlay for full-screen video playback
        VideoHudOverlay.register();

        // Register block entity renderer for video display block
        BlockEntityRendererRegistry.register(ModBlocks.VIDEO_DISPLAY_ENTITY_TYPE, VideoDisplayBlockRenderer::new);

        // Register client-side payload handlers
        ClientPlayNetworking.registerGlobalReceiver(PlayVideoPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> VideoPlaybackManager.getInstance().play(payload.id(), payload.source()));
        });

        ClientPlayNetworking.registerGlobalReceiver(StopVideoPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> VideoPlaybackManager.getInstance().stop());
        });

        ClientPlayNetworking.registerGlobalReceiver(SetVolumePayload.TYPE, (payload, context) -> {
            context.client().execute(() -> VideoPlaybackManager.getInstance().setVolume(payload.volume()));
        });

        VideoPlayer.LOGGER.info("VideoPlayer client initialized: listening on {} {} {}",
                VideoChannels.PLAY_VIDEO,
                VideoChannels.STOP_VIDEO,
                VideoChannels.SET_VOLUME);
    }
}
