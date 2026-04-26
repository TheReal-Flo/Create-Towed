package dev.justfeli.towed.mixin;

import dev.justfeli.towed.tow.TowRopeSavedData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Entity.class)
public abstract class EntityTowMovementMixin {
    @ModifyVariable(
            method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
            at = @At("HEAD"),
            argsOnly = true
    )
    private Vec3 towed$constrainMovementByTowRope(final Vec3 movement, final MoverType moverType) {
        final Entity entity = (Entity) (Object) this;
        if (!(entity.level() instanceof final ServerLevel serverLevel) || movement.lengthSqr() <= 1.0E-10) {
            return movement;
        }

        return TowRopeSavedData.get(serverLevel).constrainEntityMovement(entity, movement);
    }
}
