package dev.justfeli.towed.index;

import dev.justfeli.towed.CreateTowed;
import dev.justfeli.towed.data.PendingTowEndpoint;
import foundry.veil.platform.registry.RegistrationProvider;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;

import java.util.function.UnaryOperator;

public final class TowedDataComponents {
    private static final RegistrationProvider<DataComponentType<?>> REGISTRY =
            RegistrationProvider.get(Registries.DATA_COMPONENT_TYPE, CreateTowed.MODID);

    public static final DataComponentType<PendingTowEndpoint> PENDING_TOW_ENDPOINT = register(
            "pending_tow_endpoint",
            builder -> builder.persistent(PendingTowEndpoint.CODEC).networkSynchronized(PendingTowEndpoint.STREAM_CODEC)
    );

    private TowedDataComponents() {
    }

    private static <T> DataComponentType<T> register(final String name, final UnaryOperator<DataComponentType.Builder<T>> builder) {
        final DataComponentType<T> type = builder.apply(DataComponentType.builder()).build();
        REGISTRY.register(name, () -> type);
        return type;
    }

    public static void register() {
    }
}
