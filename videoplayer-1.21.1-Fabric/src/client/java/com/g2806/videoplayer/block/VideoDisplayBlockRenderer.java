package com.g2806.videoplayer.block;

import com.g2806.videoplayer.video.VideoPlaybackManager;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Renders the currently playing video on the front face of VideoDisplayBlocks.
 * <p>
 * When multiple VideoDisplay blocks sharing the same facing direction are placed
 * side-by-side (like a wall of screens), the renderer automatically detects the
 * full rectangle and maps UV coordinates so the video stretches seamlessly across
 * all of them — similar to WebDisplays.
 * <p>
 * The "screen rectangle" is found by flood-filling along the two axes perpendicular
 * to the facing direction (horizontal = right axis, vertical = up axis).
 */
public class VideoDisplayBlockRenderer implements BlockEntityRenderer<VideoDisplayBlockEntity> {

    /** Full-brightness lightmap value (emissive screen). */
    private static final int FULL_BRIGHT = 15728880;

    public VideoDisplayBlockRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public int getViewDistance() {
        return 128;
    }

    @Override
    public void render(VideoDisplayBlockEntity entity, float tickDelta, PoseStack poseStack,
                       MultiBufferSource bufferSource, int light, int overlay) {
        VideoPlaybackManager manager = VideoPlaybackManager.getInstance();
        if (!manager.isRunning() || !manager.isBlockMode()) return;

        ResourceLocation texId = manager.getTextureId();
        if (texId == null) return;

        int videoW = manager.getVideoWidth();
        int videoH = manager.getVideoHeight();
        if (videoW <= 0 || videoH <= 0) return;

        Level level = entity.getLevel();
        if (level == null) return;

        BlockPos pos = entity.getBlockPos();
        BlockState state = entity.getBlockState();
        Direction facing = state.getValue(VideoDisplayBlock.FACING);

        // Determine the local axes for the screen rectangle
        // "right" is the horizontal axis when looking at the front face
        // "up" is always Direction.UP / Direction.DOWN
        Direction right = facing.getClockWise();  // horizontal expansion axis
        Direction up = Direction.UP;               // vertical expansion axis

        // Find the full connected rectangle of VideoDisplay blocks with the same facing
        // Start by finding the bottom-left corner (min right, min up)
        BlockPos corner = pos;
        while (isSameScreen(level, corner.relative(right.getOpposite()), facing)) {
            corner = corner.relative(right.getOpposite());
        }
        while (isSameScreen(level, corner.relative(up.getOpposite()), facing)) {
            corner = corner.relative(up.getOpposite());
        }
        // corner is now bottom-left

        // Measure width (along right) and height (along up)
        int screenW = 1;
        while (isSameScreen(level, corner.relative(right, screenW), facing)) {
            screenW++;
        }
        int screenH = 1;
        while (isSameScreen(level, corner.relative(up, screenH), facing)) {
            screenH++;
        }

        // Determine this block's position within the rectangle
        int bx = 0; // column index from left
        int by = 0; // row index from bottom
        {
            BlockPos diff = pos.subtract(corner);
            // Project onto right and up axes
            bx = diff.getX() * right.getStepX()
               + diff.getY() * right.getStepY()
               + diff.getZ() * right.getStepZ();
            by = diff.getX() * up.getStepX()
               + diff.getY() * up.getStepY()
               + diff.getZ() * up.getStepZ();
        }

        // Compute UV tile for this block
        float u0 = (float) bx / screenW;
        float u1 = (float) (bx + 1) / screenW;
        // V is inverted: top of image = v0=0, bottom = v1=1
        // by=0 is bottom row, by=screenH-1 is top row
        float v0 = 1.0f - (float) (by + 1) / screenH;
        float v1 = 1.0f - (float) by / screenH;

        // --- Render the quad on the front face ---
        poseStack.pushPose();

        // Rotate to match facing
        poseStack.translate(0.5, 0.5, 0.5);
        float yRot = switch (facing) {
            case NORTH -> 0f;
            case SOUTH -> 180f;
            case WEST -> 90f;
            case EAST -> -90f;
            default -> 0f;
        };
        poseStack.mulPose(Axis.YP.rotationDegrees(yRot));
        poseStack.translate(-0.5, -0.5, -0.5);

        // Slightly in front of the block face to avoid z-fighting
        float z = -0.001f;
        float margin = 0.002f;
        float x0 = margin;
        float x1 = 1.0f - margin;
        float y0 = margin;
        float y1 = 1.0f - margin;

        RenderType renderType = RenderType.entityCutoutNoCull(texId);
        VertexConsumer consumer = bufferSource.getBuffer(renderType);

        var pose = poseStack.last();
        var matrix = pose.pose();

        // Quad: counter-clockwise as viewed from front
        consumer.addVertex(matrix, x0, y0, z)
                .setColor(255, 255, 255, 255)
                .setUv(u0, v1)
                .setOverlay(overlay)
                .setLight(FULL_BRIGHT)
                .setNormal(pose, 0, 0, -1);

        consumer.addVertex(matrix, x1, y0, z)
                .setColor(255, 255, 255, 255)
                .setUv(u1, v1)
                .setOverlay(overlay)
                .setLight(FULL_BRIGHT)
                .setNormal(pose, 0, 0, -1);

        consumer.addVertex(matrix, x1, y1, z)
                .setColor(255, 255, 255, 255)
                .setUv(u1, v0)
                .setOverlay(overlay)
                .setLight(FULL_BRIGHT)
                .setNormal(pose, 0, 0, -1);

        consumer.addVertex(matrix, x0, y1, z)
                .setColor(255, 255, 255, 255)
                .setUv(u0, v0)
                .setOverlay(overlay)
                .setLight(FULL_BRIGHT)
                .setNormal(pose, 0, 0, -1);

        poseStack.popPose();
    }

    /**
     * Returns true if the given position has a VideoDisplayBlock facing the same direction.
     */
    private static boolean isSameScreen(Level level, BlockPos check, Direction facing) {
        BlockState bs = level.getBlockState(check);
        if (!(bs.getBlock() instanceof VideoDisplayBlock)) return false;
        return bs.getValue(VideoDisplayBlock.FACING) == facing;
    }
}
