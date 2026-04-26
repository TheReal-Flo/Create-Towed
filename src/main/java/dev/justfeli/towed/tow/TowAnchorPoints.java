package dev.justfeli.towed.tow;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBehavior;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

public final class TowAnchorPoints {
    private TowAnchorPoints() {
    }

    public static boolean isTowBlockAttachment(final Level level, final BlockPos blockPos) {
        return isTowBlockAttachment(level.getBlockEntity(blockPos));
    }

    public static boolean isTowBlockAttachment(final @Nullable BlockEntity blockEntity) {
        return getRopeHolder(blockEntity) != null;
    }

    public static boolean hasSimulatedRopeAttachment(final Level level, final BlockPos blockPos) {
        final RopeStrandHolderBehavior ropeHolder = getRopeHolder(level.getBlockEntity(blockPos));
        return ropeHolder != null && ropeHolder.isAttached();
    }

    public static @Nullable Vector3d resolveBlockTowPoint(final @Nullable BlockEntity blockEntity) {
        final RopeStrandHolderBehavior ropeHolder = getRopeHolder(blockEntity);
        if (ropeHolder != null) {
            final Vec3 attachmentPoint = ropeHolder.getAttachmentPoint();
            return new Vector3d(attachmentPoint.x, attachmentPoint.y, attachmentPoint.z);
        }

        return null;
    }

    public static @Nullable Vec3 resolveVisualBlockTowPoint(final @Nullable BlockEntity blockEntity) {
        final RopeStrandHolderBehavior ropeHolder = getRopeHolder(blockEntity);
        return ropeHolder != null ? ropeHolder.getVisualAttachmentPoint() : null;
    }

    private static @Nullable RopeStrandHolderBehavior getRopeHolder(final @Nullable BlockEntity blockEntity) {
        if (blockEntity instanceof final SmartBlockEntity smartBlockEntity) {
            return smartBlockEntity.getBehaviour(RopeStrandHolderBehavior.TYPE);
        }

        return null;
    }

    public static Vec3 resolveEntityTowPoint(final Entity entity) {
        return entity.getBoundingBox().getCenter();
    }
}
