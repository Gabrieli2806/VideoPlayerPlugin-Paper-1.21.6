package com.g2806.videoplayer.network;

import com.g2806.videoplayer.VideoChannels;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record StopVideoPayload() implements CustomPacketPayload {

    public static final StopVideoPayload INSTANCE = new StopVideoPayload();

    public static final Type<StopVideoPayload> TYPE =
            new Type<>(ResourceLocation.parse(VideoChannels.STOP_VIDEO));

    public static final StreamCodec<FriendlyByteBuf, StopVideoPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {},
            buf -> INSTANCE
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
