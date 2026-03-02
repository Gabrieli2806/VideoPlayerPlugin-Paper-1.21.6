package com.g2806.videoplayer.block;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class ModBlocks {

    public static final Block VIDEO_DISPLAY = Registry.register(
            BuiltInRegistries.BLOCK,
            ResourceLocation.parse("videoplayer:video_display"),
            new VideoDisplayBlock(BlockBehaviour.Properties.of()
                    .strength(1.5f)
                    .sound(SoundType.STONE)
                    .noOcclusion()
                    .lightLevel(state -> 8)
            )
    );

    public static final BlockItem VIDEO_DISPLAY_ITEM = Registry.register(
            BuiltInRegistries.ITEM,
            ResourceLocation.parse("videoplayer:video_display"),
            new BlockItem(VIDEO_DISPLAY, new Item.Properties())
    );

    public static final BlockEntityType<VideoDisplayBlockEntity> VIDEO_DISPLAY_ENTITY_TYPE = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            ResourceLocation.parse("videoplayer:video_display"),
            BlockEntityType.Builder.of(VideoDisplayBlockEntity::new, VIDEO_DISPLAY).build(null)
    );

    /** Call from ModInitializer to force class-load and register everything. */
    public static void init() {
        // static initializers above handle registration
    }

    private ModBlocks() {}
}
