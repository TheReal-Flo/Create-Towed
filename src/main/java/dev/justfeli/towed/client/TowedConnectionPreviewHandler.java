package dev.justfeli.towed.client;

import dev.justfeli.towed.data.PendingTowEndpoint;
import dev.justfeli.towed.index.TowedDataComponents;
import dev.justfeli.towed.tow.TowAnchorPoints;
import dev.ryanhcode.sable.Sable;
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
import org.jetbrains.annotations.Nullable;

public final class TowedConnectionPreviewHandler {
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

            final PendingTowEndpoint pending = heldItem.get(TowedDataComponents.PENDING_TOW_ENDPOINT);
            if (pending == null) {
                continue;
            }

            renderPreview(minecraft.level, pending, minecraft.hitResult);
            return;
        }
    }

    private static void renderPreview(final Level level, final PendingTowEndpoint pending, final @Nullable HitResult hitResult) {
        final Vec3 firstPoint = resolvePendingPoint(level, pending);
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

        final Vec3 globalFirstPoint = firstPoint;
        final Vec3 globalTarget = target;

        if (valid) {
            Outliner.getInstance().chaseAABB("TowedSecondAttachmentPoint", new AABB(target, target))
                    .colored(color)
                    .lineWidth(1 / 3f)
                    .disableLineNormals();

            final int points = (int) Math.floor(globalFirstPoint.distanceTo(globalTarget));
            if (points > 0) {
                final Vec3 backwardsDiff = globalFirstPoint.subtract(globalTarget).normalize();
                for (int i = 0; i < points; i++) {
                    final Vec3 point = globalTarget.add(backwardsDiff.scale(i));
                    Outliner.getInstance().chaseAABB("TowedRopePreviewPoint" + i, new AABB(point, point))
                            .colored(color)
                            .lineWidth(1 / 8f)
                            .disableLineNormals();
                }
            }
        }

        final int particles = 4;
        for (int i = 0; i < particles; i++) {
            final Vec3 point = globalFirstPoint.lerp(globalTarget, level.random.nextFloat());
            level.addParticle(
                    new net.minecraft.core.particles.DustParticleOptions(color.asVectorF(), 1),
                    point.x, point.y, point.z,
                    0, 0, 0
            );
        }
    }

    private static @Nullable Vec3 resolvePendingPoint(final Level level, final PendingTowEndpoint pending) {
        if (pending.isBlock()) {
            return resolveBlockPoint(level, pending.blockPos());
        }
        if (pending.isEntity()) {
            final Entity entity = level.getEntities((Entity) null, new AABB(-3.0E7, -2048.0, -3.0E7, 3.0E7, 4096.0, 3.0E7),
                            candidate -> pending.entityId().equals(candidate.getUUID()))
                    .stream()
                    .filter(Entity::isAlive)
                    .findFirst()
                    .orElse(null);
            if (entity != null && entity.isAlive()) {
                return resolveEntityPoint(entity);
            }
        }
        return null;
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
}
