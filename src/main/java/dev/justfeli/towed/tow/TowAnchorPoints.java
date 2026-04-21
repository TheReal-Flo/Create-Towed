package dev.justfeli.towed.tow;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import dev.simulated_team.simulated.content.blocks.handle.HandleBlockEntity;
import dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBehavior;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

public final class TowAnchorPoints {
    private static final double HANDLE_TOW_HEIGHT = 4.0 / 16.0;

    private TowAnchorPoints() {
    }

    public static Vector3d resolveHandleTowPoint(final HandleBlockEntity handleBlockEntity) {
        return new Vector3d(handleBlockEntity.getGrabCenter()).add(0.0, HANDLE_TOW_HEIGHT, 0.0);
    }

    public static boolean isTowBlockAttachment(final Level level, final BlockPos blockPos) {
        return isTowBlockAttachment(level.getBlockEntity(blockPos));
    }

    public static boolean isTowBlockAttachment(final @Nullable BlockEntity blockEntity) {
        if (blockEntity instanceof HandleBlockEntity) {
            return true;
        }

        if (blockEntity instanceof final SmartBlockEntity smartBlockEntity) {
            return smartBlockEntity.getBehaviour(RopeStrandHolderBehavior.TYPE) != null;
        }

        return false;
    }

    public static @Nullable Vector3d resolveBlockTowPoint(final @Nullable BlockEntity blockEntity) {
        if (blockEntity instanceof final HandleBlockEntity handleBlockEntity) {
            return resolveHandleTowPoint(handleBlockEntity);
        }

        if (blockEntity instanceof final SmartBlockEntity smartBlockEntity) {
            final RopeStrandHolderBehavior ropeHolder = smartBlockEntity.getBehaviour(RopeStrandHolderBehavior.TYPE);
            if (ropeHolder != null) {
                final Vec3 attachmentPoint = ropeHolder.getAttachmentPoint();
                return new Vector3d(attachmentPoint.x, attachmentPoint.y, attachmentPoint.z);
            }
        }

        return null;
    }

    public static @Nullable Vec3 resolveVisualBlockTowPoint(final @Nullable BlockEntity blockEntity) {
        if (blockEntity instanceof final HandleBlockEntity handleBlockEntity) {
            final Vector3d point = resolveHandleTowPoint(handleBlockEntity);
            return new Vec3(point.x, point.y, point.z);
        }

        if (blockEntity instanceof final SmartBlockEntity smartBlockEntity) {
            final RopeStrandHolderBehavior ropeHolder = smartBlockEntity.getBehaviour(RopeStrandHolderBehavior.TYPE);
            if (ropeHolder != null) {
                return ropeHolder.getVisualAttachmentPoint();
            }
        }

        return null;
    }

    public static Vec3 resolveEntityTowPoint(final Entity entity) {
        return entity.getBoundingBox().getCenter();
    }
}
