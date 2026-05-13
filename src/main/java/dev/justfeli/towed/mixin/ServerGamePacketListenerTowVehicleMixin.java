package dev.justfeli.towed.mixin;

import dev.justfeli.towed.tow.TowRopeSavedData;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerTowVehicleMixin {
    @Shadow
    public ServerPlayer player;

    @Shadow
    private double vehicleLastGoodX;

    @Shadow
    private double vehicleLastGoodY;

    @Shadow
    private double vehicleLastGoodZ;

    @Shadow
    private boolean clientVehicleIsFloating;

    @Shadow
    protected abstract void resyncPlayerWithVehicle(Entity vehicle);

    @Shadow
    protected abstract boolean noBlocksAround(Entity entity);

    @Inject(
            method = "handleMoveVehicle",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"
            ),
            cancellable = true,
            locals = LocalCapture.CAPTURE_FAILHARD
    )
    private void towed$clampMountedTowMovement(final ServerboundMoveVehiclePacket packet,
                                               final CallbackInfo ci,
                                               final Entity entity,
                                               final ServerLevel serverLevel,
                                               final double xBeforeMove,
                                               final double yBeforeMove,
                                               final double zBeforeMove,
                                               final double targetX,
                                               final double targetY,
                                               final double targetZ,
                                               final float targetYRot,
                                               final float targetXRot,
                                               final double requestedDeltaX,
                                               final double requestedDeltaY,
                                               final double requestedDeltaZ,
                                               final double previousSpeedSqr,
                                               final double requestedDistanceSqr,
                                               final boolean startedWithoutCollision,
                                               final boolean wasOnGround) {
        if (!TowRopeSavedData.get(serverLevel).hasEntityAttachment(entity.getUUID())) {
            return;
        }

        final TowRopeSavedData towData = TowRopeSavedData.get(serverLevel);
        final Vec3 requestedMovement = new Vec3(targetX - xBeforeMove, targetY - yBeforeMove, targetZ - zBeforeMove);
        final Vec3 constrainedMovement = towData.previewConstrainedEntityMovement(entity, requestedMovement);
        final double constrainedTargetX = xBeforeMove + constrainedMovement.x;
        final double constrainedTargetY = yBeforeMove + constrainedMovement.y;
        final double constrainedTargetZ = zBeforeMove + constrainedMovement.z;

        double moveDeltaX = constrainedTargetX - this.vehicleLastGoodX;
        double moveDeltaY = constrainedTargetY - this.vehicleLastGoodY - 1.0E-6;
        double moveDeltaZ = constrainedTargetZ - this.vehicleLastGoodZ;

        entity.move(MoverType.PLAYER, new Vec3(moveDeltaX, moveDeltaY, moveDeltaZ));

        final double movedDeltaY = moveDeltaY;
        moveDeltaX = constrainedTargetX - entity.getX();
        moveDeltaY = constrainedTargetY - entity.getY();
        if (moveDeltaY > -0.5 && moveDeltaY < 0.5) {
            moveDeltaY = 0.0;
        }

        moveDeltaZ = constrainedTargetZ - entity.getZ();
        final double residualDistanceSqr = moveDeltaX * moveDeltaX + moveDeltaY * moveDeltaY + moveDeltaZ * moveDeltaZ;
        final boolean movedWrongly = residualDistanceSqr > 0.0625;

        entity.absMoveTo(constrainedTargetX, constrainedTargetY, constrainedTargetZ, targetYRot, targetXRot);
        this.resyncPlayerWithVehicle(entity);
        final boolean endedWithoutCollision = serverLevel.noCollision(entity, entity.getBoundingBox().deflate(0.0625));
        if (startedWithoutCollision && (movedWrongly || !endedWithoutCollision)) {
            entity.absMoveTo(xBeforeMove, yBeforeMove, zBeforeMove, targetYRot, targetXRot);
            this.resyncPlayerWithVehicle(entity);
            this.player.connection.send(new ClientboundMoveVehiclePacket(entity));
            ci.cancel();
            return;
        }

        this.player.serverLevel().getChunkSource().move(this.player);
        final Vec3 actualMovement = new Vec3(entity.getX() - xBeforeMove, entity.getY() - yBeforeMove, entity.getZ() - zBeforeMove);
        this.player.setKnownMovement(actualMovement);
        this.player.checkMovementStatistics(actualMovement.x, actualMovement.y, actualMovement.z);
        this.player.checkRidingStatistics(actualMovement.x, actualMovement.y, actualMovement.z);
        this.clientVehicleIsFloating = movedDeltaY >= -0.03125
                && !wasOnGround
                && !this.player.server.isFlightAllowed()
                && !entity.isNoGravity()
                && this.noBlocksAround(entity);
        this.vehicleLastGoodX = entity.getX();
        this.vehicleLastGoodY = entity.getY();
        this.vehicleLastGoodZ = entity.getZ();
        ci.cancel();
    }
}
