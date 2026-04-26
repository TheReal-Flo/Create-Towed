package dev.justfeli.towed.client;

import dev.justfeli.towed.data.PendingTowEndpoint;
import dev.justfeli.towed.index.TowedDataComponents;
import dev.justfeli.towed.tow.TowAnchorPoints;
import dev.ryanhcode.sable.Sable;
import dev.simulated_team.simulated.index.SimDataComponents;
import dev.simulated_team.simulated.index.SimItems;
import dev.simulated_team.simulated.service.SimConfigService;
import dev.simulated_team.simulated.util.SimColors;
import net.createmod.catnip.outliner.Outliner;
import net.createmod.catnip.theme.Color;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

public final class TowedConnectionPreviewHandler {
    private static final long ENTITY_LOOKUP_RETRY_TICKS = 10L;
    private static final long PARTICLE_SPAWN_INTERVAL_TICKS = 4L;
    private static final int MAX_PREVIEW_POINTS = 12;

    private static @Nullable java.util.UUID cachedPendingEntityId;
    private static @Nullable Entity cachedPendingEntity;
    private static long nextPendingEntityLookupTick;

    private TowedConnectionPreviewHandler() {
    }

    public static void tick() {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || minecraft.player == null || minecraft.level == null) {
            return;
        }

        for (final InteractionHand hand : InteractionHand.values()) {
            final ItemStack heldItem = minecraft.player.getItemInHand(hand);
            if (!SimItems.ROPE_COUPLING.isIn(heldItem)) {
                continue;
            }

            final PendingTowEndpoint pending = resolvePendingEndpoint(heldItem);
            if (pending == null) {
                continue;
            }

            renderPreview(minecraft.level, minecraft.player.position(), pending, minecraft.hitResult);
            return;
        }
    }

    private static @Nullable PendingTowEndpoint resolvePendingEndpoint(final ItemStack stack) {
        final PendingTowEndpoint pending = stack.get(TowedDataComponents.PENDING_TOW_ENDPOINT);
        if (pending != null) {
            return pending;
        }

        final BlockPos ropeFirstConnection = stack.get(SimDataComponents.ROPE_FIRST_CONNECTION);
        return ropeFirstConnection != null ? PendingTowEndpoint.block(ropeFirstConnection) : null;
    }

    private static void renderPreview(final Level level,
                                      final Vec3 searchCenter,
                                      final PendingTowEndpoint pending,
                                      final @Nullable HitResult hitResult) {
        final Vec3 firstPoint = resolvePendingPoint(level, searchCenter, pending);
        if (firstPoint == null) {
            return;
        }

        final double maxRange = SimConfigService.INSTANCE.server().blocks.maxRopeRange.get();
        boolean valid = false;
        Vec3 target = hitResult != null ? hitResult.getLocation() : firstPoint;

        if (pending.isBlock() && hitResult instanceof EntityHitResult entityHitResult) {
            final Entity entity = entityHitResult.getEntity();
            if (entity instanceof Mob mob && mob.canBeLeashed() && !mob.blockPosition().equals(pending.blockPos())) {
                final Vec3 entityPoint = resolveEntityPoint(mob);
                if (entityPoint != null) {
                    valid = Sable.HELPER.distanceSquaredWithSubLevels(level, firstPoint, entityPoint) < maxRange * maxRange;
                    target = entityPoint;
                }
            }
        } else if (pending.isEntity() && hitResult instanceof BlockHitResult blockHitResult) {
            final BlockPos blockPos = blockHitResult.getBlockPos();
            final Vec3 blockPoint = resolveBlockPoint(level, blockPos);
            if (blockPoint != null) {
                valid = Sable.HELPER.distanceSquaredWithSubLevels(level, firstPoint, blockPoint) < maxRange * maxRange;
                target = blockPoint;
            }
        }

        renderLine(level, firstPoint, target, valid);
    }

    private static void renderLine(final Level level, final Vec3 firstPoint, final Vec3 target, final boolean valid) {
        final Color color = new Color(valid ? SimColors.SUCCESS_LIME : SimColors.NUH_UH_RED);
        Outliner.getInstance().chaseAABB("TowedFirstAttachmentPoint", new AABB(firstPoint, firstPoint))
                .colored(color)
                .lineWidth(1 / 3f)
                .disableLineNormals();

        if (valid) {
            Outliner.getInstance().chaseAABB("TowedSecondAttachmentPoint", new AABB(target, target))
                    .colored(color)
                    .lineWidth(1 / 3f)
                    .disableLineNormals();

            final int points = Mth.clamp(Mth.ceil((float) (firstPoint.distanceTo(target) * 0.5)), 1, MAX_PREVIEW_POINTS);
            for (int i = 0; i < points; i++) {
                final double lerpDelta = points == 1 ? 0.5 : i / (double) (points - 1);
                final Vec3 point = firstPoint.lerp(target, lerpDelta);
                Outliner.getInstance().chaseAABB("TowedRopePreviewPoint" + i, new AABB(point, point))
                        .colored(color)
                        .lineWidth(1 / 8f)
                        .disableLineNormals();
            }
        }

        if (level.getGameTime() % PARTICLE_SPAWN_INTERVAL_TICKS == 0L) {
            final int particles = 2;
            for (int i = 0; i < particles; i++) {
                final Vec3 point = firstPoint.lerp(target, level.random.nextFloat());
                level.addParticle(
                        new net.minecraft.core.particles.DustParticleOptions(color.asVectorF(), 1),
                        point.x, point.y, point.z,
                        0, 0, 0
                );
            }
        }
    }

    private static @Nullable Vec3 resolvePendingPoint(final Level level, final Vec3 searchCenter, final PendingTowEndpoint pending) {
        if (pending.isBlock()) {
            return resolveBlockPoint(level, pending.blockPos());
        }
        if (pending.isEntity()) {
            final Entity entity = resolveNearbyEntity(level, searchCenter, pending.entityId());
            if (entity != null && entity.isAlive()) {
                return resolveEntityPoint(entity);
            }
        }
        return null;
    }

    private static @Nullable Entity resolveNearbyEntity(final Level level, final Vec3 searchCenter, final java.util.UUID entityId) {
        if (isUsableCachedEntity(level, entityId)) {
            return cachedPendingEntity;
        }

        final long gameTime = level.getGameTime();
        if (gameTime < nextPendingEntityLookupTick && entityId.equals(cachedPendingEntityId)) {
            return null;
        }

        final double maxRange = SimConfigService.INSTANCE.server().blocks.maxRopeRange.get();
        final AABB searchBox = new AABB(searchCenter, searchCenter).inflate(maxRange + 8.0);
        @Nullable Entity resolvedEntity = null;
        for (final Entity candidate : level.getEntities((Entity) null, searchBox, entity -> entityId.equals(entity.getUUID()))) {
            if (candidate.isAlive()) {
                resolvedEntity = candidate;
                break;
            }
        }

        cachedPendingEntityId = entityId;
        cachedPendingEntity = resolvedEntity;
        nextPendingEntityLookupTick = gameTime + ENTITY_LOOKUP_RETRY_TICKS;
        return resolvedEntity;
    }

    private static @Nullable Vec3 resolveBlockPoint(final Level level, final @Nullable BlockPos blockPos) {
        if (blockPos == null || !TowAnchorPoints.isTowBlockAttachment(level, blockPos)) {
            return null;
        }

        final Vec3 localPoint = TowAnchorPoints.resolveVisualBlockTowPoint(level.getBlockEntity(blockPos));
        if (localPoint == null) {
            return null;
        }

        return Sable.HELPER.projectOutOfSubLevel(level, localPoint);
    }

    private static @Nullable Vec3 resolveEntityPoint(final Entity entity) {
        if (!entity.isAlive()) {
            return null;
        }

        return Sable.HELPER.projectOutOfSubLevel(entity.level(), TowAnchorPoints.resolveEntityTowPoint(entity));
    }

    private static boolean isUsableCachedEntity(final Level level, final java.util.UUID entityId) {
        return cachedPendingEntity != null
                && cachedPendingEntity.isAlive()
                && cachedPendingEntity.level() == level
                && entityId.equals(cachedPendingEntityId)
                && entityId.equals(cachedPendingEntity.getUUID());
    }
}
