package dev.justfeli.towed.platform;

import dev.justfeli.towed.event.TowedCommonEvents;
import dev.ryanhcode.sable.platform.SableEventPlatform;

public final class TowedPlatform {
    private static boolean initialized;

    private TowedPlatform() {
    }

    public static void init() {
        if (initialized) {
            return;
        }

        initialized = true;
        SableEventPlatform.INSTANCE.onPhysicsTick(TowedCommonEvents::onPhysicsTick);
    }
}
