package dev.justfeli.towed.mixin;

import dev.justfeli.towed.tow.TowRopeSavedData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.goal.FollowOwnerGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(FollowOwnerGoal.class)
public abstract class FollowOwnerGoalMixin {
    @Redirect(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/TamableAnimal;tryToTeleportToOwner()V"
            )
    )
    private void towed$skipOwnerTeleportWhileRoped(final TamableAnimal tamable) {
        if (tamable.level() instanceof final ServerLevel serverLevel
                && TowRopeSavedData.get(serverLevel).hasEntityAttachment(tamable.getUUID())) {
            return;
        }

        tamable.tryToTeleportToOwner();
    }
}
