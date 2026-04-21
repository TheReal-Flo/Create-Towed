package dev.justfeli.towed.event;

import dev.justfeli.towed.tow.TowRopeSavedData;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.server.level.ServerLevel;

public final class TowedCommonEvents {
    private TowedCommonEvents() {
    }

    public static void onPhysicsTick(final SubLevelPhysicsSystem physicsSystem, final double timeStep) {
        TowRopeSavedData.get(physicsSystem.getLevel()).physicsTick(physicsSystem, timeStep);
    }

    public static void onServerLevelTickEnd(final ServerLevel level) {
        TowRopeSavedData.get(level).serverTick();
    }
}
