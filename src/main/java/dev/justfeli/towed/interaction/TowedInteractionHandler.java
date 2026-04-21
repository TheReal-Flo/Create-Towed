package dev.justfeli.towed.interaction;

import dev.justfeli.towed.data.PendingTowEndpoint;
import dev.justfeli.towed.index.TowedDataComponents;
import dev.justfeli.towed.tow.TowAnchorPoints;
import dev.justfeli.towed.tow.TowRopeSavedData;
import dev.simulated_team.simulated.index.SimItems;
import dev.simulated_team.simulated.index.SimTags;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

public final class TowedInteractionHandler {
    private TowedInteractionHandler() {
    }

    public static void handleRightClickBlock(final PlayerInteractEvent.RightClickBlock event) {
        final Level level = event.getLevel();
        final BlockPos pos = event.getPos();
        final Player player = event.getEntity();
        final InteractionHand hand = event.getHand();
        final ItemStack stack = player.getItemInHand(hand);

        if (!TowAnchorPoints.isTowBlockAttachment(level, pos)) {
            return;
        }

        if (stack.is(SimTags.Items.DESTROYS_ROPE)) {
            final boolean removed = !level.isClientSide && TowRopeSavedData.get((net.minecraft.server.level.ServerLevel) level).removeByBlock(pos, true);
            if (level.isClientSide || removed) {
                consume(event, InteractionResult.sidedSuccess(level.isClientSide));
            }
            return;
        }

        if (!stack.is(SimItems.ROPE_COUPLING.get())) {
            return;
        }

        if (player.isShiftKeyDown()) {
            clearPending(stack);
            consume(event, InteractionResult.sidedSuccess(level.isClientSide));
            return;
        }

        if (!level.isClientSide) {
            final PendingTowEndpoint pending = stack.get(TowedDataComponents.PENDING_TOW_ENDPOINT);
            if (pending != null && pending.isEntity()) {
                final boolean created = TowRopeSavedData.get((net.minecraft.server.level.ServerLevel) level).tryCreate(pos, pending.entityId());
                clearPending(stack);
                if (created) {
                    finalizeCreation(player, stack, pos);
                }
            } else {
                stack.set(TowedDataComponents.PENDING_TOW_ENDPOINT, PendingTowEndpoint.block(pos));
            }
        } else if (!stack.has(TowedDataComponents.PENDING_TOW_ENDPOINT)) {
            stack.set(TowedDataComponents.PENDING_TOW_ENDPOINT, PendingTowEndpoint.block(pos));
        }

        consume(event, InteractionResult.sidedSuccess(level.isClientSide));
    }

    public static void handleEntityInteract(final PlayerInteractEvent.EntityInteract event) {
        handleEntityInteract(event.getEntity(), event.getLevel(), event.getHand(), event.getTarget(), result -> consume(event, result));
    }

    public static void handleEntityInteract(final PlayerInteractEvent.EntityInteractSpecific event) {
        handleEntityInteract(event.getEntity(), event.getLevel(), event.getHand(), event.getTarget(), result -> consume(event, result));
    }

    private static void handleEntityInteract(final Player player,
                                             final Level level,
                                             final InteractionHand hand,
                                             final Entity target,
                                             final java.util.function.Consumer<InteractionResult> consumeAction) {
        final ItemStack stack = player.getItemInHand(hand);
        if (!stack.is(SimItems.ROPE_COUPLING.get()) || !(target instanceof Mob mob) || !mob.canBeLeashed()) {
            return;
        }

        if (player.isShiftKeyDown()) {
            clearPending(stack);
            consumeAction.accept(InteractionResult.sidedSuccess(level.isClientSide));
            return;
        }

        if (!level.isClientSide) {
            final PendingTowEndpoint pending = stack.get(TowedDataComponents.PENDING_TOW_ENDPOINT);
            if (pending != null && pending.isBlock()) {
                final boolean created = TowRopeSavedData.get((net.minecraft.server.level.ServerLevel) level).tryCreate(pending.blockPos(), mob.getUUID());
                clearPending(stack);
                if (created) {
                    finalizeCreation(player, stack, mob.blockPosition());
                }
            } else {
                stack.set(TowedDataComponents.PENDING_TOW_ENDPOINT, PendingTowEndpoint.entity(mob.getUUID()));
            }
        } else if (!stack.has(TowedDataComponents.PENDING_TOW_ENDPOINT)) {
            stack.set(TowedDataComponents.PENDING_TOW_ENDPOINT, PendingTowEndpoint.entity(mob.getUUID()));
        }

        consumeAction.accept(InteractionResult.sidedSuccess(level.isClientSide));
    }

    public static void handleRightClickItem(final PlayerInteractEvent.RightClickItem event) {
        final Player player = event.getEntity();
        final ItemStack stack = player.getItemInHand(event.getHand());

        if (!stack.is(SimItems.ROPE_COUPLING.get()) || !player.isShiftKeyDown() || !stack.has(TowedDataComponents.PENDING_TOW_ENDPOINT)) {
            return;
        }

        clearPending(stack);

        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
        event.setCanceled(true);
    }

    private static void clearPending(final ItemStack stack) {
        stack.remove(TowedDataComponents.PENDING_TOW_ENDPOINT);
    }

    private static void finalizeCreation(final Player player, final ItemStack stack, final BlockPos soundPos) {
        if (!player.isCreative()) {
            stack.shrink(1);
        }
        player.level().playSound(null, soundPos, SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 0.5F, 1.0F);
    }

    private static void consume(final PlayerInteractEvent.RightClickBlock event, final InteractionResult result) {
        event.setCancellationResult(result);
        event.setCanceled(true);
    }

    private static void consume(final PlayerInteractEvent.EntityInteract event, final InteractionResult result) {
        event.setCancellationResult(result);
        event.setCanceled(true);
    }

    private static void consume(final PlayerInteractEvent.EntityInteractSpecific event, final InteractionResult result) {
        event.setCancellationResult(result);
        event.setCanceled(true);
    }
}
