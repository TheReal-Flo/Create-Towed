package dev.justfeli.towed.tow;

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

public record TowRopeAttachment(Type type, @Nullable BlockPos blockPos, @Nullable UUID entityId) {
    public static final Codec<TowRopeAttachment> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Type.CODEC.fieldOf("type").forGetter(TowRopeAttachment::type),
            BlockPos.CODEC.optionalFieldOf("block_pos").forGetter(attachment -> Optional.ofNullable(attachment.blockPos)),
            UUIDUtil.CODEC.optionalFieldOf("entity_id").forGetter(attachment -> Optional.ofNullable(attachment.entityId))
    ).apply(instance, (type, blockPos, entityId) -> new TowRopeAttachment(type, blockPos.orElse(null), entityId.orElse(null))));

    public static final StreamCodec<RegistryFriendlyByteBuf, TowRopeAttachment> STREAM_CODEC = StreamCodec.composite(
            Type.STREAM_CODEC, TowRopeAttachment::type,
            ByteBufCodecs.optional(BlockPos.STREAM_CODEC), attachment -> Optional.ofNullable(attachment.blockPos),
            ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), attachment -> Optional.ofNullable(attachment.entityId),
            (type, blockPos, entityId) -> new TowRopeAttachment(type, blockPos.orElse(null), entityId.orElse(null))
    );

    public static TowRopeAttachment block(final BlockPos blockPos) {
        return new TowRopeAttachment(Type.BLOCK, blockPos.immutable(), null);
    }

    public static TowRopeAttachment handleBlock(final BlockPos blockPos) {
        return block(blockPos);
    }

    public static TowRopeAttachment entity(final UUID entityId) {
        return new TowRopeAttachment(Type.ENTITY, null, entityId);
    }

    public boolean isBlock() {
        return this.type == Type.BLOCK && this.blockPos != null;
    }

    public boolean isHandleBlock() {
        return this.isBlock();
    }

    public boolean isEntity() {
        return this.type == Type.ENTITY && this.entityId != null;
    }

    public boolean matchesBlock(final BlockPos blockPos) {
        return this.isBlock() && this.blockPos.equals(blockPos);
    }

    public boolean matchesHandle(final BlockPos handlePos) {
        return this.matchesBlock(handlePos);
    }

    public boolean matchesEntity(final UUID entityId) {
        return this.isEntity() && this.entityId.equals(entityId);
    }

    public enum Type {
        BLOCK("block"),
        ENTITY("entity");

        public static final Codec<Type> CODEC = Codec.STRING.xmap(Type::byName, Type::serializedName);
        public static final StreamCodec<RegistryFriendlyByteBuf, Type> STREAM_CODEC =
                StreamCodec.of((buf, type) -> buf.writeUtf(type.serializedName), buf -> Type.byName(buf.readUtf()));

        private final String serializedName;

        Type(final String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return this.serializedName;
        }

        private static Type byName(final String name) {
            if ("handle_block".equals(name)) {
                return BLOCK;
            }
            for (final Type value : values()) {
                if (value.serializedName.equals(name)) {
                    return value;
                }
            }
            throw new IllegalArgumentException("Unknown tow rope attachment type: " + name);
        }
    }
}
