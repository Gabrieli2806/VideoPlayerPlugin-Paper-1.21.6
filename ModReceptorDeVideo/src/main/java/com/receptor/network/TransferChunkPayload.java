package com.receptor.network;

import com.receptor.VideoChannels;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record TransferChunkPayload(int chunkIndex, byte[] data)
        implements CustomPacketPayload {

    public static final Type<TransferChunkPayload> TYPE = new Type<>(ResourceLocation.parse(VideoChannels.TRANSFER_CHUNK));

    public static final StreamCodec<FriendlyByteBuf, TransferChunkPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeInt(payload.chunkIndex);
                buf.writeInt(payload.data.length);
                buf.writeBytes(payload.data);
            },
            buf -> {
                int chunkIndex = buf.readInt();
                int length = buf.readInt();
                byte[] data = new byte[length];
                buf.readBytes(data);
                return new TransferChunkPayload(chunkIndex, data);
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
