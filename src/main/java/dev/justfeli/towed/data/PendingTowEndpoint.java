package dev.justfeli.towed.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

public record PendingTowEndpoint(@Nullable BlockPos blockPos, @Nullable UUID entityId) {
    public static final Codec<PendingTowEndpoint> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockPos.CODEC.optionalFieldOf("block_pos").forGetter(endpoint -> Optional.ofNullable(endpoint.blockPos)),
            UUIDUtil.CODEC.optionalFieldOf("entity_id").forGetter(endpoint -> Optional.ofNullable(endpoint.entityId))
    ).apply(instance, (blockPos, entityId) -> new PendingTowEndpoint(blockPos.orElse(null), entityId.orElse(null))));

    public static final StreamCodec<RegistryFriendlyByteBuf, PendingTowEndpoint> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.optional(BlockPos.STREAM_CODEC), endpoint -> Optional.ofNullable(endpoint.blockPos),
            ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), endpoint -> Optional.ofNullable(endpoint.entityId),
            (blockPos, entityId) -> new PendingTowEndpoint(blockPos.orElse(null), entityId.orElse(null))
    );

    public static PendingTowEndpoint block(final BlockPos blockPos) {
        return new PendingTowEndpoint(blockPos.immutable(), null);
    }

    public static PendingTowEndpoint entity(final UUID entityId) {
        return new PendingTowEndpoint(null, entityId);
    }

    public boolean isBlock() {
        return this.blockPos != null;
    }

    public boolean isEntity() {
        return this.entityId != null;
    }
}
