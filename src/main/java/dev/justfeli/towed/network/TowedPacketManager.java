package dev.justfeli.towed.network;

import dev.justfeli.towed.CreateTowed;
import dev.justfeli.towed.network.packet.ClientboundTowRopeDataPacket;
import dev.justfeli.towed.network.packet.ClientboundTowRopeRemovePacket;
import foundry.veil.api.network.VeilPacketManager;

public final class TowedPacketManager {
    public static final VeilPacketManager INSTANCE = VeilPacketManager.create(CreateTowed.MODID, "0.1");

    private TowedPacketManager() {
    }

    public static void init() {
        INSTANCE.registerClientbound(ClientboundTowRopeDataPacket.TYPE, ClientboundTowRopeDataPacket.CODEC, ClientboundTowRopeDataPacket::handle);
        INSTANCE.registerClientbound(ClientboundTowRopeRemovePacket.TYPE, ClientboundTowRopeRemovePacket.CODEC, ClientboundTowRopeRemovePacket::handle);
    }
}
