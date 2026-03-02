package com.g2806.videoplayer.network;

import net.minecraft.network.FriendlyByteBuf;

import java.nio.charset.StandardCharsets;

public final class PayloadReader {

    private PayloadReader() {}

    public static String readUtf(FriendlyByteBuf buf) {
        int byteLength = buf.readVarInt();
        if (byteLength < 0 || byteLength > 32767 * 4) {
            throw new IllegalArgumentException("Invalid UTF byte length: " + byteLength);
        }
        byte[] bytes = new byte[byteLength];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static float readFloat(FriendlyByteBuf buf) {
        return buf.readFloat();
    }
}
