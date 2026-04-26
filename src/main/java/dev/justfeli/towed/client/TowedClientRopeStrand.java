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
    private static final long ENTITY_LOOKUP_RETRY_TICKS = 10L;

    private @Nullable TowRopeAttachment startAttachment;
    private @Nullable TowRopeAttachment endAttachment;
    private @Nullable Entity cachedStartEntity;
    private @Nullable Entity cachedEndEntity;
    private long nextStartEntityLookupTick;
    private long nextEndEntityLookupTick;

    public TowedClientRopeStrand(final UUID uuid) {
        super(uuid);
        this.nextStartEntityLookupTick = 0L;
        this.nextEndEntityLookupTick = 0L;
    }

    public void setAttachments(final TowRopeAttachment startAttachment, final TowRopeAttachment endAttachment) {
        if (!startAttachment.equals(this.startAttachment)) {
            this.cachedStartEntity = null;
            this.nextStartEntityLookupTick = 0L;
        }
        if (!endAttachment.equals(this.endAttachment)) {
            this.cachedEndEntity = null;
            this.nextEndEntityLookupTick = 0L;
        }
        this.startAttachment = startAttachment;
        this.endAttachment = endAttachment;
    }

    public void tickTowInterpolation(final Level level, final double interpolationTick) {
        this.tickInterpolation(interpolationTick);
        this.anchorEndpoints(level);
    }

    public boolean shouldDiscard(final Level level) {
        return this.shouldDiscard(level, this.startAttachment) || this.shouldDiscard(level, this.endAttachment);
    }

    private void anchorEndpoints(final Level level) {
        if (this.getPoints().isEmpty()) {
            return;
        }

        final Vector3d startPointPosition = this.resolveAttachmentPoint(level, this.startAttachment, true);
        if (startPointPosition != null) {
            this.applyEndpointCorrection(startPointPosition, true, this.startAttachment != null && this.startAttachment.isEntity());
        }

        final Vector3d endPointPosition = this.resolveAttachmentPoint(level, this.endAttachment, false);
        if (endPointPosition != null) {
            this.applyEndpointCorrection(endPointPosition, false, this.endAttachment != null && this.endAttachment.isEntity());
        }
    }

    private void applyEndpointCorrection(final Vector3d targetPosition, final boolean startSide, final boolean propagateAcrossRope) {
        final ClientRopePoint endpoint = startSide ? this.getPoints().getFirst() : this.getPoints().getLast();
        if (!propagateAcrossRope || this.getPoints().size() < 2) {
            endpoint.position().set(targetPosition);
            endpoint.previousPosition().set(targetPosition);
            return;
        }

        final Vector3d delta = new Vector3d(targetPosition).sub(endpoint.position());
        if (delta.lengthSquared() <= 1.0E-8) {
            endpoint.position().set(targetPosition);
            endpoint.previousPosition().set(targetPosition);
            return;
        }

        final double maxIndex = this.getPoints().size() - 1.0;
        for (int i = 0; i < this.getPoints().size(); i++) {
            final double weight = startSide ? (maxIndex - i) / maxIndex : i / maxIndex;
            if (weight <= 0.0) {
                continue;
            }

            final ClientRopePoint point = this.getPoints().get(i);
            point.position().fma(weight, delta);
            point.previousPosition().fma(weight, delta);
        }

        endpoint.position().set(targetPosition);
        endpoint.previousPosition().set(targetPosition);
    }

    private boolean shouldDiscard(final Level level, final @Nullable TowRopeAttachment attachment) {
        return attachment != null
                && attachment.isBlock()
                && level.isLoaded(attachment.blockPos())
                && !TowAnchorPoints.isTowBlockAttachment(level, attachment.blockPos());
    }

    private @Nullable Vector3d resolveAttachmentPoint(final Level level,
                                                      final @Nullable TowRopeAttachment attachment,
                                                      final boolean startSide) {
        if (attachment == null) {
            return null;
        }
        if (attachment.isBlock()) {
            return this.resolveBlockPoint(level, attachment.blockPos());
        }
        if (attachment.isEntity()) {
            return this.resolveEntityPoint(level, attachment.entityId(), startSide);
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

    private @Nullable Vector3d resolveEntityPoint(final Level level, final UUID entityId, final boolean startSide) {
        final Entity cachedEntity = startSide ? this.cachedStartEntity : this.cachedEndEntity;
        if (this.isUsableCachedEntity(level, cachedEntity, entityId)) {
            return this.projectEntityPoint(level, cachedEntity);
        }

        final long gameTime = level.getGameTime();
        final long nextLookupTick = startSide ? this.nextStartEntityLookupTick : this.nextEndEntityLookupTick;
        if (gameTime < nextLookupTick) {
            return null;
        }

        final BlockPos searchOrigin = this.resolveSearchOrigin(startSide);
        if (searchOrigin == null) {
            return null;
        }

        final double maxRange = SimConfigService.INSTANCE.server().blocks.maxRopeRange.get();
        final AABB searchBox = new AABB(searchOrigin).inflate(maxRange + 4.0);
        @Nullable Entity entity = null;
        for (final Entity candidate : level.getEntities((Entity) null, searchBox, candidateEntity -> entityId.equals(candidateEntity.getUUID()))) {
            entity = candidate;
            break;
        }

        if (!this.isUsableCachedEntity(level, entity, entityId)) {
            this.cacheEntity(startSide, null, gameTime + ENTITY_LOOKUP_RETRY_TICKS);
            return null;
        }

        this.cacheEntity(startSide, entity, gameTime + ENTITY_LOOKUP_RETRY_TICKS);
        return this.projectEntityPoint(level, entity);
    }

    private boolean isUsableCachedEntity(final Level level, final @Nullable Entity entity, final UUID entityId) {
        return entity != null
                && entity.isAlive()
                && entity.level() == level
                && entityId.equals(entity.getUUID());
    }

    private void cacheEntity(final boolean startSide, final @Nullable Entity entity, final long nextLookupTick) {
        if (startSide) {
            this.cachedStartEntity = entity;
            this.nextStartEntityLookupTick = nextLookupTick;
            return;
        }

        this.cachedEndEntity = entity;
        this.nextEndEntityLookupTick = nextLookupTick;
    }

    private Vector3d projectEntityPoint(final Level level, final Entity entity) {
        final net.minecraft.world.phys.Vec3 projected = Sable.HELPER.projectOutOfSubLevel(level, TowAnchorPoints.resolveEntityTowPoint(entity));
        return new Vector3d(projected.x, projected.y, projected.z);
    }

    private @Nullable BlockPos resolveSearchOrigin(final boolean startSide) {
        if (!this.getPoints().isEmpty()) {
            final Vector3d point = startSide ? this.getPoints().getFirst().position() : this.getPoints().getLast().position();
            return BlockPos.containing(point.x, point.y, point.z);
        }

        if (this.startAttachment != null && this.startAttachment.isBlock()) {
            return this.startAttachment.blockPos();
        }
        if (this.endAttachment != null && this.endAttachment.isBlock()) {
            return this.endAttachment.blockPos();
        }
        return null;
    }
}
