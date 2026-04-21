package dev.justfeli.towed.client;

import dev.justfeli.towed.CreateTowed;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

public final class TowedClientEvents {
    private TowedClientEvents() {
    }

    public static void init() {
    }

    @SubscribeEvent
    public static void postClientTick(final ClientTickEvent.Post event) {
        TowedConnectionPreviewHandler.tick();
    }

    @SubscribeEvent
    public static void onRenderLevelStage(final RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            return;
        }

        TowedWorldRopeRenderer.renderAll(
                event.getPoseStack(),
                event.getPartialTick().getGameTimeDeltaPartialTick(false),
                event.getCamera().getPosition()
        );
    }
}
