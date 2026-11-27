package dev.yg.skyboxblock.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client request for changing only the sender's render_skybox override. */
public record SkyboxRenderTogglePayload(long requestId, boolean expectedVisible, boolean desiredVisible)
        implements CustomPacketPayload {
    public static final Type<SkyboxRenderTogglePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("skyboxblock", "render_toggle_v1"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SkyboxRenderTogglePayload> CODEC = new StreamCodec<>() {
        @Override public SkyboxRenderTogglePayload decode(RegistryFriendlyByteBuf buffer) {
            return new SkyboxRenderTogglePayload(buffer.readVarLong(), buffer.readBoolean(), buffer.readBoolean());
        }
        @Override public void encode(RegistryFriendlyByteBuf buffer, SkyboxRenderTogglePayload payload) {
            buffer.writeVarLong(payload.requestId());
            buffer.writeBoolean(payload.expectedVisible());
            buffer.writeBoolean(payload.desiredVisible());
        }
    };

    public SkyboxRenderTogglePayload {
        if (requestId <= 0) throw new IllegalArgumentException("requestId must be positive");
        if (expectedVisible == desiredVisible) throw new IllegalArgumentException("toggle request must change visibility");
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
