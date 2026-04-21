package dev.justfeli.towed.client;

import dev.justfeli.towed.tow.TowRopeAttachment;
import dev.ryanhcode.sable.Sable;
import dev.simulated_team.simulated.content.blocks.rope.strand.client.ClientRopePoint;
import dev.simulated_team.simulated.content.blocks.rope.strand.client.ClientRopeStrand;
import dev.simulated_team.simulated.service.SimConfigService;
import dev.justfeli.towed.tow.TowAnchorPoints;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.UUID;

public final class TowedClientRopeStrand extends ClientRopeStrand {
    private @Nullable TowRopeAttachment startAttachment;
    private @Nullable TowRopeAttachment endAttachment;

    public TowedClientRopeStrand(final UUID uuid) {
        super(uuid);
    }

    public void setAttachments(final TowRopeAttachment startAttachment, final TowRopeAttachment endAttachment) {
        this.startAttachment = startAttachment;
        this.endAttachment = endAttachment;
    }

    public void tickTowInterpolation(final Level level, final double interpolationTick) {
        this.tickInterpolation(interpolationTick);
        this.anchorEndpoints(level);
    }

    public boolean startsAtHandle(final BlockPos handlePos) {
        return this.startAttachment != null && this.startAttachment.matchesBlock(handlePos);
    }

    public boolean shouldDiscard(final Level level) {
        return this.shouldDiscard(level, this.startAttachment) || this.shouldDiscard(level, this.endAttachment);
    }

    private void anchorEndpoints(final Level level) {
        if (this.getPoints().isEmpty()) {
            return;
        }

        final Vector3d startPointPosition = this.resolveAttachmentPoint(level, this.startAttachment);
        if (startPointPosition != null) {
            final ClientRopePoint startPoint = this.getPoints().getFirst();
            startPoint.position().set(startPointPosition);
            startPoint.previousPosition().set(startPointPosition);
        }

        final Vector3d endPointPosition = this.resolveAttachmentPoint(level, this.endAttachment);
        if (endPointPosition != null) {
            final ClientRopePoint endPoint = this.getPoints().getLast();
            endPoint.position().set(endPointPosition);
            endPoint.previousPosition().set(endPointPosition);
        }
    }

    private boolean shouldDiscard(final Level level, final @Nullable TowRopeAttachment attachment) {
        return attachment != null
                && attachment.isBlock()
                && level.isLoaded(attachment.blockPos())
                && !TowAnchorPoints.isTowBlockAttachment(level, attachment.blockPos());
    }

    private @Nullable Vector3d resolveAttachmentPoint(final Level level, final @Nullable TowRopeAttachment attachment) {
        if (attachment == null) {
            return null;
        }
        if (attachment.isBlock()) {
            return this.resolveBlockPoint(level, attachment.blockPos());
        }
        if (attachment.isEntity()) {
            return this.resolveEntityPoint(level, attachment.entityId());
        }
        return null;
    }

    private @Nullable Vector3d resolveBlockPoint(final Level level, final BlockPos blockPos) {
        final net.minecraft.world.phys.Vec3 localPoint = TowAnchorPoints.resolveVisualBlockTowPoint(level.getBlockEntity(blockPos));
        if (localPoint == null) {
            return null;
        }
        final net.minecraft.world.phys.Vec3 projected = Sable.HELPER.projectOutOfSubLevel(level, localPoint);
        return new Vector3d(projected.x, projected.y, projected.z);
    }

    private @Nullable Vector3d resolveEntityPoint(final Level level, final UUID entityId) {
        final BlockPos searchOrigin = this.resolveSearchOrigin();
        if (searchOrigin == null) {
            return null;
        }

        final double maxRange = SimConfigService.INSTANCE.server().blocks.maxRopeRange.get();
        final AABB searchBox = new AABB(searchOrigin).inflate(maxRange + 4.0);
        final Entity entity = level.getEntities((Entity) null, searchBox, candidate -> entityId.equals(candidate.getUUID()))
                .stream()
                .findFirst()
                .orElse(null);
        if (entity == null || !entity.isAlive()) {
            return null;
        }

        final net.minecraft.world.phys.Vec3 projected = Sable.HELPER.projectOutOfSubLevel(level, TowAnchorPoints.resolveEntityTowPoint(entity));
        return new Vector3d(projected.x, projected.y, projected.z);
    }

    private @Nullable BlockPos resolveSearchOrigin() {
        if (this.startAttachment != null && this.startAttachment.isBlock()) {
            return this.startAttachment.blockPos();
        }
        if (this.endAttachment != null && this.endAttachment.isBlock()) {
            return this.endAttachment.blockPos();
        }
        return null;
    }
}
