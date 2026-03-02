package com.g2806.videoplayer.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class VideoDisplayBlockEntity extends BlockEntity {

    public VideoDisplayBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlocks.VIDEO_DISPLAY_ENTITY_TYPE, pos, state);
    }
}
