package com.receptor.network;

import com.receptor.VideoChannels;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;

public record TransferEndPayload(String videoId) implements CustomPacketPayload {

    public static final Type<TransferEndPayload> TYPE = new Type<>(ResourceLocation.parse(VideoChannels.TRANSFER_END));

    public static final StreamCodec<FriendlyByteBuf, TransferEndPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                byte[] bytes = payload.videoId.getBytes(StandardCharsets.UTF_8);
                buf.writeVarInt(bytes.length);
                buf.writeBytes(bytes);
            },
            buf -> {
                int len = buf.readVarInt();
                byte[] bytes = new byte[len];
                buf.readBytes(bytes);
                return new TransferEndPayload(new String(bytes, StandardCharsets.UTF_8));
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
