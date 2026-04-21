package dev.justfeli.towed.mixin.client;

import dev.justfeli.towed.client.ClientTowRopeManager;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.network.client.ClientSableInterpolationState;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientSubLevelContainer.class)
public abstract class ClientSubLevelContainerTowRopeMixin {
    @Shadow
    @Final
    private ClientSableInterpolationState interpolation;

    @Shadow
    public abstract ClientLevel getLevel();

    @Inject(method = "tick", at = @At("TAIL"))
    private void towed$tickTowRopeInterpolation(final CallbackInfo ci) {
        final ClientTowRopeManager manager = ClientTowRopeManager.getOrCreate(this.getLevel());
        if (manager != null) {
            manager.tickInterpolation(this.getLevel(), this.interpolation.getTickPointer());
        }
    }
}
