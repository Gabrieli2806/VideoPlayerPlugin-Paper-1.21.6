package com.g2806.videoplayer.network;

import com.g2806.videoplayer.VideoChannels;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record SetVolumePayload(float volume) implements CustomPacketPayload {

    public static final Type<SetVolumePayload> TYPE =
            new Type<>(ResourceLocation.parse(VideoChannels.SET_VOLUME));

    public static final StreamCodec<FriendlyByteBuf, SetVolumePayload> CODEC = StreamCodec.of(
            (buf, payload) -> buf.writeFloat(payload.volume),
            buf -> new SetVolumePayload(buf.readFloat())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
