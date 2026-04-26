package dev.justfeli.towed.tow;

import dev.ryanhcode.sable.api.sublevel.SubLevelTrackingPlugin;
import net.minecraft.server.level.ServerLevel;

import java.util.UUID;

public final class TowRopeTrackingSystem implements SubLevelTrackingPlugin {
    private final ServerLevel level;

    public TowRopeTrackingSystem(final ServerLevel level) {
        this.level = level;
    }

    @Override
    public Iterable<UUID> neededPlayers() {
        return TowRopeSavedData.get(this.level).neededTrackingPlayers();
    }

    @Override
    public void sendTrackingData(final int interpolationTick) {
        TowRopeSavedData.get(this.level).sendTrackingData(interpolationTick);
    }
}
