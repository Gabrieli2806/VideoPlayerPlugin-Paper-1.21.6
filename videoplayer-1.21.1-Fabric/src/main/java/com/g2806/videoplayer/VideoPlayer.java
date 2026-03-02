package com.g2806.videoplayer;

import com.g2806.videoplayer.block.ModBlocks;
import com.g2806.videoplayer.command.CinematicCommand;
import com.g2806.videoplayer.config.VideoRegistry;
import com.g2806.videoplayer.network.PlayVideoPayload;
import com.g2806.videoplayer.network.SetVolumePayload;
import com.g2806.videoplayer.network.StopVideoPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.item.CreativeModeTabs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class VideoPlayer implements ModInitializer {
	public static final String MOD_ID = "videoplayer";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static VideoRegistry videoRegistry;

	@Override
	public void onInitialize() {
		LOGGER.info("VideoPlayer mod initializing...");

		// Config & video registry
		Path configRoot = FabricLoader.getInstance().getConfigDir().resolve("videoplayermod");
		videoRegistry = new VideoRegistry(LOGGER, configRoot);

		// Register blocks & items
		ModBlocks.init();

		// Add block item to creative tab
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(entries ->
				entries.accept(ModBlocks.VIDEO_DISPLAY_ITEM)
		);

		// Register S2C payload types (both server and client need to know)
		PayloadTypeRegistry.playS2C().register(PlayVideoPayload.TYPE, PlayVideoPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(StopVideoPayload.TYPE, StopVideoPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(SetVolumePayload.TYPE, SetVolumePayload.CODEC);

		// Register commands
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				CinematicCommand.register(dispatcher, videoRegistry)
		);

		LOGGER.info("VideoPlayer mod initialized!");
	}

	public static VideoRegistry getVideoRegistry() {
		return videoRegistry;
	}
}