package dev.justfeli.towed.network.packet;

import dev.justfeli.towed.CreateTowed;
import dev.justfeli.towed.client.ClientTowRopeManager;
import foundry.veil.api.network.handler.ClientPacketContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

public record ClientboundTowRopeRemovePacket(UUID ropeId) implements CustomPacketPayload {
    public static final Type<ClientboundTowRopeRemovePacket> TYPE = new Type<>(CreateTowed.path("tow_rope_remove"));
    public static final StreamCodec<ByteBuf, ClientboundTowRopeRemovePacket> CODEC =
            UUIDUtil.STREAM_CODEC.map(ClientboundTowRopeRemovePacket::new, ClientboundTowRopeRemovePacket::ropeId);

    public void handle(final ClientPacketContext context) {
        final LocalPlayer player = context.player();
        if (player == null) {
            return;
        }

        final ClientTowRopeManager manager = ClientTowRopeManager.getOrCreate(player.level());
        if (manager != null) {
            manager.remove(this.ropeId);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
