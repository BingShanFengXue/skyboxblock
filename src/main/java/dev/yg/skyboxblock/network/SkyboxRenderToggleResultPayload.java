package dev.yg.skyboxblock.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Acknowledges or rejects one per-player visibility request. */
public record SkyboxRenderToggleResultPayload(long requestId, Status status) implements CustomPacketPayload {
    public static final Type<SkyboxRenderToggleResultPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("skyboxblock", "render_toggle_result_v1"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SkyboxRenderToggleResultPayload> CODEC = new StreamCodec<>() {
        @Override public SkyboxRenderToggleResultPayload decode(RegistryFriendlyByteBuf buffer) {
            long requestId = buffer.readVarLong();
            int ordinal = buffer.readUnsignedByte();
            Status[] values = Status.values();
            if (requestId <= 0 || ordinal >= values.length) throw new IllegalArgumentException("Invalid toggle result payload");
            return new SkyboxRenderToggleResultPayload(requestId, values[ordinal]);
        }
        @Override public void encode(RegistryFriendlyByteBuf buffer, SkyboxRenderToggleResultPayload payload) {
            buffer.writeVarLong(payload.requestId());
            buffer.writeByte(payload.status().ordinal());
        }
    };
    public SkyboxRenderToggleResultPayload {
        if (requestId <= 0 || status == null) throw new IllegalArgumentException("Invalid toggle result");
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public enum Status {
        APPLIED,
        STALE_STATE,
        DUPLICATE,
        STALE_REQUEST,
        RATE_LIMITED,
        INVALID_REQUEST,
        STORAGE_FAILURE,
        STALE_CONNECTION
    }
}
