package com.receptor;

import com.receptor.network.PlayVideoPayload;
import com.receptor.network.SetVolumePayload;
import com.receptor.network.StopVideoPayload;
import com.receptor.network.TransferStartPayload;
import com.receptor.network.TransferChunkPayload;
import com.receptor.network.TransferEndPayload;
import com.receptor.video.VideoHudOverlay;
import com.receptor.video.VideoPlaybackManager;
import com.receptor.video.VideoTransferReceiver;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public class ReceptorClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		VideoHudOverlay.register();

		// Register payload types
		PayloadTypeRegistry.playS2C().register(PlayVideoPayload.TYPE, PlayVideoPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(StopVideoPayload.TYPE, StopVideoPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(SetVolumePayload.TYPE, SetVolumePayload.CODEC);
		PayloadTypeRegistry.playS2C().register(TransferStartPayload.TYPE, TransferStartPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(TransferChunkPayload.TYPE, TransferChunkPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(TransferEndPayload.TYPE, TransferEndPayload.CODEC);

		// Play/Stop/Volume handlers
		ClientPlayNetworking.registerGlobalReceiver(PlayVideoPayload.TYPE, (payload, context) -> {
			Receptor.LOGGER.info("[NET] Received PLAY command: id='{}' source='{}'", payload.id(), payload.source());
			context.client().execute(() -> VideoPlaybackManager.getInstance().play(payload.id(), payload.source()));
		});

		ClientPlayNetworking.registerGlobalReceiver(StopVideoPayload.TYPE, (payload, context) -> {
			Receptor.LOGGER.info("[NET] Received STOP command");
			context.client().execute(() -> VideoPlaybackManager.getInstance().stop());
		});

		ClientPlayNetworking.registerGlobalReceiver(SetVolumePayload.TYPE, (payload, context) -> {
			Receptor.LOGGER.info("[NET] Received VOLUME command: {}", payload.volume());
			context.client().execute(() -> VideoPlaybackManager.getInstance().setVolume(payload.volume()));
		});

		// Chunked file transfer handlers
		ClientPlayNetworking.registerGlobalReceiver(TransferStartPayload.TYPE, (payload, context) -> {
			Receptor.LOGGER.info("[NET] Received TRANSFER_START: id='{}' file='{}' size={} chunks={}",
					payload.videoId(), payload.fileName(), payload.fileSize(), payload.totalChunks());
			context.client().execute(() -> VideoTransferReceiver.getInstance().onTransferStart(payload));
		});

		ClientPlayNetworking.registerGlobalReceiver(TransferChunkPayload.TYPE, (payload, context) -> {
			// Don't log every chunk — too spammy. The receiver handles progress updates.
			VideoTransferReceiver.getInstance().onTransferChunk(payload);
		});

		ClientPlayNetworking.registerGlobalReceiver(TransferEndPayload.TYPE, (payload, context) -> {
			Receptor.LOGGER.info("[NET] Received TRANSFER_END: id='{}'", payload.videoId());
			context.client().execute(() -> VideoTransferReceiver.getInstance().onTransferEnd(payload));
		});

		Receptor.LOGGER.info("Receptor client initialized: listening on {} {} {} + transfer channels",
				VideoChannels.PLAY_VIDEO,
				VideoChannels.STOP_VIDEO,
				VideoChannels.SET_VOLUME);
	}
}