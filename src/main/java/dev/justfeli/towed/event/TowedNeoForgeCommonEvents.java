package dev.justfeli.towed.event;

import dev.justfeli.towed.CreateTowed;
import dev.justfeli.towed.interaction.TowedInteractionHandler;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

@EventBusSubscriber(modid = CreateTowed.MODID)
public final class TowedNeoForgeCommonEvents {
    private TowedNeoForgeCommonEvents() {
    }

    @SubscribeEvent
    public static void onLevelTickPost(final LevelTickEvent.Post event) {
        if (event.getLevel() instanceof final ServerLevel serverLevel) {
            TowedCommonEvents.onServerLevelTickEnd(serverLevel);
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(final PlayerInteractEvent.RightClickBlock event) {
        TowedInteractionHandler.handleRightClickBlock(event);
    }

    @SubscribeEvent
    public static void onEntityInteract(final PlayerInteractEvent.EntityInteract event) {
        TowedInteractionHandler.handleEntityInteract(event);
    }

    @SubscribeEvent
    public static void onEntityInteractSpecific(final PlayerInteractEvent.EntityInteractSpecific event) {
        TowedInteractionHandler.handleEntityInteract(event);
    }

    @SubscribeEvent
    public static void onRightClickItem(final PlayerInteractEvent.RightClickItem event) {
        TowedInteractionHandler.handleRightClickItem(event);
    }
}
