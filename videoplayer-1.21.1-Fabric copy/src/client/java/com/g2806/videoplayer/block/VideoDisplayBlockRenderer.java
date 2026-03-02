package com.g2806.videoplayer.block;

import com.g2806.videoplayer.video.VideoPlaybackManager;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

/**
 * Renders the currently playing video on the front face of a VideoDisplayBlock.
 * The video is letterboxed to preserve aspect ratio. The face is fully lit
 * to simulate a glowing screen.
 */
public class VideoDisplayBlockRenderer implements BlockEntityRenderer<VideoDisplayBlockEntity> {

    public VideoDisplayBlockRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(VideoDisplayBlockEntity entity, float tickDelta, PoseStack poseStack,
                       MultiBufferSource bufferSource, int light, int overlay) {
        VideoPlaybackManager manager = VideoPlaybackManager.getInstance();
        if (!manager.isRunning()) return;

        ResourceLocation texId = manager.getTextureId();
        if (texId == null) return;

        int videoW = manager.getVideoWidth();
        int videoH = manager.getVideoHeight();
        if (videoW <= 0 || videoH <= 0) return;

        // Determine block orientation
        Direction facing = entity.getBlockState().getValue(VideoDisplayBlock.FACING);

        poseStack.pushPose();

        // Rotate so "front face" aligns with block's FACING direction
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

        // Letterboxing: fit video into the 1x1 block face
        float videoAspect = (float) videoW / videoH;
        float renderW, renderH;
        float offsetX = 0, offsetY = 0;

        if (videoAspect > 1.0f) {
            // Wider than tall → letterbox top/bottom
            renderW = 1.0f;
            renderH = 1.0f / videoAspect;
            offsetY = (1.0f - renderH) / 2;
        } else {
            // Taller than wide → pillarbox left/right
            renderH = 1.0f;
            renderW = videoAspect;
            offsetX = (1.0f - renderW) / 2;
        }

        // Add margin
        float margin = 0.0625f; // 1 pixel on a 16px texture
        float x0 = offsetX + margin;
        float x1 = offsetX + renderW - margin;
        float y0 = offsetY + margin;
        float y1 = offsetY + renderH - margin;

        // Slightly in front of the block face to avoid z-fighting
        float z = -0.001f;

        // Full brightness for the screen
        int fullBright = 15728880;

        RenderType renderType = RenderType.entityCutoutNoCull(texId);
        VertexConsumer consumer = bufferSource.getBuffer(renderType);

        var pose = poseStack.last();
        var matrix = pose.pose();

        // Draw quad on front face (facing -Z in local space)
        // Using counter-clockwise winding when viewed from the front
        // Bottom-left
        consumer.addVertex(matrix, x0, y0, z)
                .setColor(255, 255, 255, 255)
                .setUv(0.0f, 1.0f)
                .setOverlay(overlay)
                .setLight(fullBright)
                .setNormal(pose, 0, 0, -1);

        // Bottom-right
        consumer.addVertex(matrix, x1, y0, z)
                .setColor(255, 255, 255, 255)
                .setUv(1.0f, 1.0f)
                .setOverlay(overlay)
                .setLight(fullBright)
                .setNormal(pose, 0, 0, -1);

        // Top-right
        consumer.addVertex(matrix, x1, y1, z)
                .setColor(255, 255, 255, 255)
                .setUv(1.0f, 0.0f)
                .setOverlay(overlay)
                .setLight(fullBright)
                .setNormal(pose, 0, 0, -1);

        // Top-left
        consumer.addVertex(matrix, x0, y1, z)
                .setColor(255, 255, 255, 255)
                .setUv(0.0f, 0.0f)
                .setOverlay(overlay)
                .setLight(fullBright)
                .setNormal(pose, 0, 0, -1);

        poseStack.popPose();
    }
}
