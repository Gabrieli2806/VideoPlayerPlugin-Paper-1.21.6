package com.g2806.videoplayer.network;

import com.g2806.videoplayer.VideoChannels;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;

/**
 * @param displayMode Either "hud" (full-screen overlay) or "block" (VideoDisplayBlock face).
 */
public record PlayVideoPayload(String id, String source, String displayMode) implements CustomPacketPayload {

    public static final String MODE_HUD   = "hud";
    public static final String MODE_BLOCK = "block";

    public static final Type<PlayVideoPayload> TYPE =
            new Type<>(ResourceLocation.parse(VideoChannels.PLAY_VIDEO));

    public static final StreamCodec<FriendlyByteBuf, PlayVideoPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                writeUtf(buf, payload.id);
                writeUtf(buf, payload.source);
                writeUtf(buf, payload.displayMode);
            },
            buf -> new PlayVideoPayload(readUtf(buf), readUtf(buf), readUtf(buf))
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void writeUtf(FriendlyByteBuf buf, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        buf.writeVarInt(bytes.length);
        buf.writeBytes(bytes);
    }

    private static String readUtf(FriendlyByteBuf buf) {
        int byteLength = buf.readVarInt();
        if (byteLength < 0 || byteLength > 32767 * 4) {
            throw new IllegalArgumentException("Invalid UTF byte length: " + byteLength);
        }
        byte[] bytes = new byte[byteLength];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
