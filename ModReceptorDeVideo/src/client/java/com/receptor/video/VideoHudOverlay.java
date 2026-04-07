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
        }

        // Status text (loading, decoding, downloading, errors — anything except active playback)
        String status = manager.getStatusText();
        if (!status.isEmpty() && !status.startsWith("Playing")) {
            var font = Minecraft.getInstance().font;
            int screenW = Minecraft.getInstance().getWindow().getGuiScaledWidth();
            int screenH = Minecraft.getInstance().getWindow().getGuiScaledHeight();

            boolean isError = status.startsWith("Error");
            int textColor = isError ? 0xFFFF5555 : 0xFF55FF55;

            // Extract progress percentage if present (e.g., "Downloading: 45%" or "Decoding: 78%")
            int progressPct = -1;
            if (!isError) {
                int pctIdx = status.indexOf('%');
                if (pctIdx > 0) {
                    // Find the number before %
                    int numStart = pctIdx - 1;
                    while (numStart > 0 && Character.isDigit(status.charAt(numStart - 1))) numStart--;
                    try {
                        progressPct = Integer.parseInt(status.substring(numStart, pctIdx));
                    } catch (NumberFormatException ignored) {}
                }
            }

            // Draw centered status panel
            int barWidth = 200;
            int panelWidth = Math.max(barWidth + 20, font.width(status) + 24);
            int panelX = (screenW - panelWidth) / 2;
            int panelY = screenH / 2 - 20;

            // Background panel
            drawContext.fill(panelX, panelY, panelX + panelWidth, panelY + (progressPct >= 0 ? 38 : 22), 0xCC000000);

            // Status text
            String display = "[Receptor] " + status;
            int textW = font.width(display);
            int textX = (screenW - textW) / 2;
            drawContext.drawString(font, Component.literal(display), textX, panelY + 4, textColor, true);

            // Progress bar (only if we have a percentage)
            if (progressPct >= 0) {
                int barX = (screenW - barWidth) / 2;
                int barY = panelY + 20;
                int barHeight = 10;

                // Bar background (dark gray)
                drawContext.fill(barX, barY, barX + barWidth, barY + barHeight, 0xFF333333);

                // Bar fill (green gradient)
                int fillWidth = (int) (barWidth * (progressPct / 100.0));
                if (fillWidth > 0) {
                    int barColor = isError ? 0xFFFF5555 : 0xFF44CC44;
                    drawContext.fill(barX, barY, barX + fillWidth, barY + barHeight, barColor);
                }

                // Bar border
                drawContext.fill(barX, barY, barX + barWidth, barY + 1, 0xFF666666);           // top
                drawContext.fill(barX, barY + barHeight - 1, barX + barWidth, barY + barHeight, 0xFF666666); // bottom
                drawContext.fill(barX, barY, barX + 1, barY + barHeight, 0xFF666666);           // left
                drawContext.fill(barX + barWidth - 1, barY, barX + barWidth, barY + barHeight, 0xFF666666); // right

                // Percentage text on bar
                String pctText = progressPct + "%";
                int pctW = font.width(pctText);
                drawContext.drawString(font, Component.literal(pctText),
                        barX + (barWidth - pctW) / 2, barY + 1, 0xFFFFFFFF, true);
            }
        }
    }
}
