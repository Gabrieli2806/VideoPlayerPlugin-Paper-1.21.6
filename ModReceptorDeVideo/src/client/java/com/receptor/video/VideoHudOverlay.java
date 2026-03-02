package com.receptor.video;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

public final class VideoHudOverlay {

    private VideoHudOverlay() {
    }

    public static void register() {
        HudRenderCallback.EVENT.register(VideoHudOverlay::render);
    }

    private static void render(GuiGraphics drawContext, DeltaTracker tickCounter) {
        VideoPlaybackManager manager = VideoPlaybackManager.getInstance();

        if (manager.isRunning()) {
            manager.render(drawContext);

            // On-screen debug stats overlay (top-left corner, 2 lines)
            var font = Minecraft.getInstance().font;
            String line1 = manager.getDebugLine1();
            String line2 = manager.getDebugLine2();
            if (!line1.isEmpty()) {
                int w1 = font.width(line1) + 16;
                int w2 = font.width(line2) + 16;
                int bgW = Math.max(w1, w2);
                drawContext.fill(4, 4, bgW, 30, 0xAA000000);
                drawContext.drawString(font, Component.literal(line1), 8, 7, 0x00FF00, true);
                drawContext.drawString(font, Component.literal(line2), 8, 18, 0x00FF00, true);
            }
        }

        // Status text while loading or decoding (centered on screen)
        String status = manager.getStatusText();
        if (!status.isEmpty() && (status.startsWith("Loading") || status.startsWith("Decoding") || status.startsWith("Downloading"))) {
            var font = Minecraft.getInstance().font;
            String display = "[Receptor] " + status;
            int textW = font.width(display);
            int screenW = Minecraft.getInstance().getWindow().getGuiScaledWidth();
            int screenH = Minecraft.getInstance().getWindow().getGuiScaledHeight();
            int x = (screenW - textW) / 2;
            int y = screenH / 2 - 5;
            drawContext.fill(x - 6, y - 4, x + textW + 6, y + 14, 0xCC000000);
            drawContext.drawString(font, Component.literal(display), x, y, 0x55FF55, true);
        }
    }
}
