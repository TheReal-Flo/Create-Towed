package dev.justfeli.towed.tow;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.object.rope.RopeHandle;
import dev.ryanhcode.sable.api.physics.object.rope.RopePhysicsObject;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.blocks.rope.rope_winch.RopeWinchBlockEntity;
import dev.simulated_team.simulated.service.SimConfigService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.Collection;
import java.util.UUID;

public final class ServerTowRope extends RopePhysicsObject {
    private static final double SEGMENT_LENGTH = 1.0;

    private final UUID ropeId;
    private final TowRopeAttachment startAttachment;
    private final TowRopeAttachment endAttachment;
    private double extensionGoal;
    private double appliedFirstSegmentLength;

    public ServerTowRope(final UUID ropeId, final TowRopeAttachment startAttachment, final TowRopeAttachment endAttachment, final Collection<Vector3d> points) {
        super(points, 0.125);
        this.ropeId = ropeId;
        this.startAttachment = startAttachment;
        this.endAttachment = endAttachment;
        this.extensionGoal = this.points.size() > 1 ? this.points.get(0).distance(this.points.get(1)) : SEGMENT_LENGTH;
        this.appliedFirstSegmentLength = this.extensionGoal;
    }

    public UUID ropeId() {
        return this.ropeId;
    }

    public TowRopeAttachment startAttachment() {
        return this.startAttachment;
    }

    public TowRopeAttachment endAttachment() {
        return this.endAttachment;
    }

    public @Nullable BlockPos trackingBlockPos() {
        if (this.startAttachment.isBlock()) {
            return this.startAttachment.blockPos();
        }
        if (this.endAttachment.isBlock()) {
            return this.endAttachment.blockPos();
        }
        return null;
    }

    public boolean matchesBlock(final BlockPos blockPos) {
        return this.startAttachment.matchesBlock(blockPos) || this.endAttachment.matchesBlock(blockPos);
    }

    public boolean matchesHandle(final BlockPos handlePos) {
        return this.matchesBlock(handlePos);
    }

    public boolean matchesEntity(final UUID entityId) {
        return this.startAttachment.matchesEntity(entityId) || this.endAttachment.matchesEntity(entityId);
    }

    public TowRopeState toState() {
        return new TowRopeState(this.ropeId, this.startAttachment, this.endAttachment, this.points.stream().map(Vector3d::new).toList());
    }

    public void serverTick(final ServerLevel level) {
        this.updateWinchExtension(level);
    }

    public boolean refreshAttachments(final ServerLevel level) {
        final AttachmentEndpoint startEndpoint = resolveAttachmentEndpoint(level, this.startAttachment);
        final AttachmentEndpoint endEndpoint = resolveAttachmentEndpoint(level, this.endAttachment);

        if (startEndpoint == null || endEndpoint == null) {
            return false;
        }

        this.setAttachment(RopeHandle.AttachmentPoint.START, startEndpoint.localPoint(), startEndpoint.subLevel());
        this.setAttachment(RopeHandle.AttachmentPoint.END, endEndpoint.localPoint(), endEndpoint.subLevel());
        return true;
    }

    public boolean prePhysicsTick(final ServerLevel level) {
        if (!this.refreshAttachments(level)) {
            return false;
        }

        if (!Mth.equal(this.appliedFirstSegmentLength, this.extensionGoal)) {
            this.setFirstSegmentLength(this.extensionGoal);
            this.appliedFirstSegmentLength = this.extensionGoal;
        }

        return true;
    }

    @Override
    public void onAddition(final dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem physicsSystem) {
        super.onAddition(physicsSystem);
        this.refreshAttachments(physicsSystem.getLevel());
        this.setFirstSegmentLength(this.extensionGoal);
        this.appliedFirstSegmentLength = this.extensionGoal;
    }

    public static @Nullable AttachmentEndpoint resolveAttachmentEndpoint(final ServerLevel level, final TowRopeAttachment attachment) {
        if (attachment.isBlock()) {
            return resolveBlockEndpoint(level, attachment.blockPos());
        }
        if (attachment.isEntity()) {
            return resolveEntityEndpoint(level.getEntity(attachment.entityId()));
        }
        return null;
    }

    private static @Nullable AttachmentEndpoint resolveBlockEndpoint(final ServerLevel level, final BlockPos blockPos) {
        final BlockEntity blockEntity = level.getBlockEntity(blockPos);
        final Vector3d towPoint = TowAnchorPoints.resolveBlockTowPoint(blockEntity);
        if (towPoint == null) {
            return null;
        }

        final SubLevel containing = Sable.HELPER.getContaining(blockEntity);
        return new AttachmentEndpoint(towPoint, containing instanceof ServerSubLevel serverSubLevel ? serverSubLevel : null);
    }

    private static @Nullable AttachmentEndpoint resolveEntityEndpoint(final @Nullable Entity entity) {
        if (entity == null || !entity.isAlive()) {
            return null;
        }

        final SubLevel containing = Sable.HELPER.getContaining(entity);
        final Vec3 point = TowAnchorPoints.resolveEntityTowPoint(entity);
        return new AttachmentEndpoint(JOMLConversion.toJOML(point), containing instanceof ServerSubLevel serverSubLevel ? serverSubLevel : null);
    }

    private void updateWinchExtension(final ServerLevel level) {
        if (!this.startAttachment.isBlock()) {
            return;
        }

        final BlockEntity blockEntity = level.getBlockEntity(this.startAttachment.blockPos());
        if (!(blockEntity instanceof RopeWinchBlockEntity ropeWinchBlockEntity)) {
            return;
        }

        float movementSpeed = ropeWinchBlockEntity.getMovementSpeed();
        final double desiredExtension = this.getDesiredExtension();
        final double currentExtension = this.getCurrentExtension();
        final double maxStretchMultiplier = 1.0 + (SimConfigService.INSTANCE.server().blocks.maxRopeStretchAllowed.get() / 100.0);

        if (currentExtension > desiredExtension * maxStretchMultiplier) {
            movementSpeed = Math.max(0.0F, movementSpeed);
        }

        final double maxRange = SimConfigService.INSTANCE.server().blocks.maxRopeRange.get();
        if (currentExtension > maxRange) {
            movementSpeed = Math.min(0.0F, movementSpeed);
        }

        double extension = this.extensionGoal + movementSpeed;
        final int minPointCount = 2;

        if (extension < 1.0 && this.points.size() == minPointCount) {
            extension = 1.0;
        } else {
            while (extension < 0.0) {
                this.removeFirstPoint();
                extension += SEGMENT_LENGTH;

                if (extension < 1.0 && this.points.size() == minPointCount) {
                    extension = 1.0;
                    break;
                }
            }

            while (extension > SEGMENT_LENGTH) {
                final AttachmentEndpoint startEndpoint = resolveAttachmentEndpoint(level, this.startAttachment);
                if (startEndpoint == null) {
                    break;
                }

                final Vec3 anchorPoint = Sable.HELPER.projectOutOfSubLevel(level, JOMLConversion.toMojang(startEndpoint.localPoint()));
                this.addPoint(new Vector3d(anchorPoint.x, anchorPoint.y, anchorPoint.z));
                extension -= SEGMENT_LENGTH;
            }

            if (extension < 1.0 && this.points.size() <= minPointCount) {
                extension = 1.0;
            }
        }

        this.extensionGoal = extension;
    }

    private double getDesiredExtension() {
        return this.extensionGoal + (this.points.size() - 2) * SEGMENT_LENGTH;
    }

    private double getCurrentExtension() {
        double totalExtension = 0.0;
        for (int i = 0; i < this.points.size() - 1; i++) {
            totalExtension += this.points.get(i).distance(this.points.get(i + 1));
        }
        return totalExtension;
    }

    public record AttachmentEndpoint(Vector3d localPoint, @Nullable ServerSubLevel subLevel) {
    }
}
