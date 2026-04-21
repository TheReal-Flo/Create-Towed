package dev.justfeli.towed.network.packet;

import dev.justfeli.towed.CreateTowed;
import dev.justfeli.towed.client.ClientTowRopeManager;
import dev.justfeli.towed.tow.TowRopeAttachment;
import dev.ryanhcode.sable.util.SableBufferUtils;
import foundry.veil.api.network.handler.ClientPacketContext;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.List;
import java.util.UUID;

public record ClientboundTowRopeDataPacket(int interpolationTick, UUID ropeId, TowRopeAttachment startAttachment, TowRopeAttachment endAttachment, List<Vector3d> points)
        implements CustomPacketPayload {
    public static final Type<ClientboundTowRopeDataPacket> TYPE = new Type<>(CreateTowed.path("tow_rope_data"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundTowRopeDataPacket> CODEC =
            StreamCodec.of((buf, value) -> value.write(buf), ClientboundTowRopeDataPacket::read);

    private static ClientboundTowRopeDataPacket read(final RegistryFriendlyByteBuf buf) {
        final int interpolationTick = buf.readInt();
        final UUID ropeId = buf.readUUID();
        final TowRopeAttachment startAttachment = TowRopeAttachment.STREAM_CODEC.decode(buf);
        final TowRopeAttachment endAttachment = TowRopeAttachment.STREAM_CODEC.decode(buf);

        final int size = buf.readInt();
        final List<Vector3d> points = new ObjectArrayList<>(size);
        for (int i = 0; i < size; i++) {
            points.add(SableBufferUtils.read(buf, new Vector3d()));
        }

        return new ClientboundTowRopeDataPacket(interpolationTick, ropeId, startAttachment, endAttachment, points);
    }

    private void write(final RegistryFriendlyByteBuf buf) {
        buf.writeInt(this.interpolationTick);
        buf.writeUUID(this.ropeId);
        TowRopeAttachment.STREAM_CODEC.encode(buf, this.startAttachment);
        TowRopeAttachment.STREAM_CODEC.encode(buf, this.endAttachment);
        buf.writeInt(this.points.size());
        for (final Vector3dc point : this.points) {
            SableBufferUtils.write(buf, point);
        }
    }

    public void handle(final ClientPacketContext context) {
        final LocalPlayer player = context.player();
        if (player == null) {
            return;
        }

        final ClientTowRopeManager manager = ClientTowRopeManager.getOrCreate(player.level());
        if (manager != null) {
            manager.receive(this.ropeId, this.startAttachment, this.endAttachment, this.interpolationTick, this.points);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
