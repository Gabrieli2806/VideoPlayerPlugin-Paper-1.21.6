package com.nightmare.videoplayermod.paper.network;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class PayloadWriter {

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    public PayloadWriter writeUtf(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(bytes.length);
        output.writeBytes(bytes);
        return this;
    }

    public PayloadWriter writeFloat(float value) {
        int bits = Float.floatToIntBits(value);
        output.write((bits >>> 24) & 0xFF);
        output.write((bits >>> 16) & 0xFF);
        output.write((bits >>> 8) & 0xFF);
        output.write(bits & 0xFF);
        return this;
    }

    public byte[] toByteArray() {
        return output.toByteArray();
    }

    private void writeVarInt(int value) {
        int current = value;
        while ((current & 0xFFFFFF80) != 0) {
            output.write((current & 0x7F) | 0x80);
            current >>>= 7;
        }
        output.write(current & 0x7F);
    }
}
