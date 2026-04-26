package dev.justfeli.towed.event;

import dev.justfeli.towed.tow.TowRopeTrackingSystem;
import dev.justfeli.towed.tow.TowRopeSavedData;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.ryanhcode.sable.sublevel.system.SubLevelTrackingSystem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

public final class TowedCommonEvents {
    private TowedCommonEvents() {
    }

    public static void onContainerReady(final Level level, final SubLevelContainer subLevelContainer) {
        if (!(subLevelContainer instanceof final ServerSubLevelContainer serverContainer)) {
            return;
        }

        final SubLevelTrackingSystem trackingSystem = serverContainer.trackingSystem();
        trackingSystem.addTrackingPlugin(new TowRopeTrackingSystem(serverContainer.getLevel()));
    }

    public static void onPhysicsTick(final SubLevelPhysicsSystem physicsSystem, final double timeStep) {
        TowRopeSavedData.get(physicsSystem.getLevel()).physicsTick(physicsSystem, timeStep);
    }

    public static void onServerLevelTickEnd(final ServerLevel level) {
        TowRopeSavedData.get(level).serverTick();
    }
}
