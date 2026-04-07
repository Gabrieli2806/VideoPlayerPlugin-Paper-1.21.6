package com.receptor.network;

import com.receptor.VideoChannels;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;

public record TransferStartPayload(String videoId, String fileName, long fileSize, int chunkSize, int totalChunks)
        implements CustomPacketPayload {

    public static final Type<TransferStartPayload> TYPE = new Type<>(ResourceLocation.parse(VideoChannels.TRANSFER_START));

    public static final StreamCodec<FriendlyByteBuf, TransferStartPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                writeUtf(buf, payload.videoId);
                writeUtf(buf, payload.fileName);
                buf.writeLong(payload.fileSize);
                buf.writeInt(payload.chunkSize);
                buf.writeInt(payload.totalChunks);
            },
            buf -> new TransferStartPayload(readUtf(buf), readUtf(buf), buf.readLong(), buf.readInt(), buf.readInt())
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
        byte[] bytes = new byte[byteLength];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
