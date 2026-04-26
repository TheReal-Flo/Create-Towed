package dev.justfeli.towed.tow;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.object.rope.RopeHandle;
import dev.ryanhcode.sable.api.physics.object.rope.RopePhysicsObject;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.simulated_team.simulated.content.blocks.rope.rope_winch.RopeWinchBlockEntity;
import dev.simulated_team.simulated.service.SimConfigService;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public final class ServerTowRope extends RopePhysicsObject {
    private static final double SEGMENT_LENGTH = 1.0;
    private static final int INVALID_ATTACHMENT_GRACE_TICKS = 40;
    // Contact sampling is intentionally coarse; exact per-block support changes
    // are barely visible but expensive when many tow ropes are active.
    private static final long CONTACT_PROFILE_CACHE_TICKS = 10L;
    private static final int CONTACT_SAMPLE_AXIS_COUNT = 3;
    private static final int CONTACT_VERTICAL_SCAN_BLOCKS = 3;
    private static final double SLACK_DISTANCE_EPSILON = 0.04;
    private static final double WALKING_HYSTERESIS_DISTANCE = 0.18;
    private static final double DRAGGING_STRETCH_THRESHOLD = 0.22;
    private static final double LIFTED_PULL_THRESHOLD = 0.3;
    private static final double STUCK_IMPULSE_THRESHOLD = 0.012;
    private static final int STUCK_TICK_THRESHOLD = 6;
    private static final double CONTRAPTION_FREE_BREAKAWAY_MASS = 32.0;
    private static final double CONTRAPTION_BREAKAWAY_FORCE_SCALE = 0.08;

    private final UUID ropeId;
    private final TowRopeAttachment startAttachment;
    private final TowRopeAttachment endAttachment;
    private double extensionGoal;
    private double appliedFirstSegmentLength;
    private int invalidAttachmentTicks;
    private @Nullable AttachmentEndpoint resolvedStartEndpoint;
    private @Nullable AttachmentEndpoint resolvedEndEndpoint;
    private @Nullable ContactProfile cachedContactProfile;
    private @Nullable ServerSubLevel cachedContactProfileSubLevel;
    private long cachedContactProfileTick;
    private EntityTowControlState entityTowControlState;
    private int stuckTowTicks;
    private double lastContraptionRequiredForce;
    private @Nullable Vector3d lastStartEndpointWakePoint;
    private @Nullable Vector3d lastEndEndpointWakePoint;

    public ServerTowRope(final UUID ropeId, final TowRopeAttachment startAttachment, final TowRopeAttachment endAttachment, final Collection<Vector3d> points) {
        super(points, 0.125);
        this.ropeId = ropeId;
        this.startAttachment = startAttachment;
        this.endAttachment = endAttachment;
        this.extensionGoal = this.points.size() > 1 ? this.points.get(0).distance(this.points.get(1)) : SEGMENT_LENGTH;
        this.appliedFirstSegmentLength = this.extensionGoal;
        this.invalidAttachmentTicks = 0;
        this.resolvedStartEndpoint = null;
        this.resolvedEndEndpoint = null;
        this.cachedContactProfile = null;
        this.cachedContactProfileSubLevel = null;
        this.cachedContactProfileTick = Long.MIN_VALUE;
        this.entityTowControlState = EntityTowControlState.SLACK;
        this.stuckTowTicks = 0;
        this.lastContraptionRequiredForce = 0.0;
        this.lastStartEndpointWakePoint = null;
        this.lastEndEndpointWakePoint = null;
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

    public Collection<BlockPos> trackingBlockPositions(final ServerLevel level) {
        final List<BlockPos> trackingPositions = new ArrayList<>(3);
        this.addTrackingBlockPos(trackingPositions, this.trackingBlockPos());
        this.addTrackingEndpointPos(level, trackingPositions, this.resolvedStartEndpoint);
        this.addTrackingEndpointPos(level, trackingPositions, this.resolvedEndEndpoint);
        return trackingPositions;
    }

    private void addTrackingEndpointPos(final ServerLevel level,
                                        final List<BlockPos> trackingPositions,
                                        final @Nullable AttachmentEndpoint endpoint) {
        if (endpoint == null) {
            return;
        }

        final Vec3 projected = Sable.HELPER.projectOutOfSubLevel(level, JOMLConversion.toMojang(endpoint.localPoint()));
        this.addTrackingBlockPos(trackingPositions, BlockPos.containing(projected));
    }

    private void addTrackingBlockPos(final List<BlockPos> trackingPositions, final @Nullable BlockPos blockPos) {
        if (blockPos != null && !trackingPositions.contains(blockPos)) {
            trackingPositions.add(blockPos);
        }
    }

    public boolean matchesBlock(final BlockPos blockPos) {
        return this.startAttachment.matchesBlock(blockPos) || this.endAttachment.matchesBlock(blockPos);
    }

    public boolean matchesEntity(final UUID entityId) {
        return this.startAttachment.matchesEntity(entityId) || this.endAttachment.matchesEntity(entityId);
    }

    public TowRopeState toState() {
        final List<Vector3d> copiedPoints = new ArrayList<>(this.points.size());
        for (final Vector3d point : this.points) {
            copiedPoints.add(new Vector3d(point));
        }
        return new TowRopeState(this.ropeId, this.startAttachment, this.endAttachment, copiedPoints);
    }

    public boolean serverTick(final ServerLevel level) {
        final boolean persistentStateChanged = this.updateWinchExtension(level);
        this.assistEntityRecovery(level);
        return persistentStateChanged;
    }

    public boolean refreshAttachments(final ServerLevel level) {
        final AttachmentEndpoint startEndpoint = resolveAttachmentEndpoint(level, this.startAttachment);
        final AttachmentEndpoint endEndpoint = resolveAttachmentEndpoint(level, this.endAttachment);

        if (startEndpoint == null || endEndpoint == null) {
            this.resolvedStartEndpoint = null;
            this.resolvedEndEndpoint = null;
            return false;
        }

        this.resolvedStartEndpoint = startEndpoint;
        this.resolvedEndEndpoint = endEndpoint;
        this.setPhysicsAttachment(RopeHandle.AttachmentPoint.START, this.startAttachment, startEndpoint);
        this.setPhysicsAttachment(RopeHandle.AttachmentPoint.END, this.endAttachment, endEndpoint);
        this.wakeIfEntityEndpointMoved(startEndpoint, endEndpoint);
        return true;
    }

    private void setPhysicsAttachment(final RopeHandle.AttachmentPoint attachmentPoint,
                                      final TowRopeAttachment attachment,
                                      final AttachmentEndpoint endpoint) {
        if (attachment.isEntity()) {
            return;
        }

        this.setAttachment(attachmentPoint, endpoint.localPoint(), endpoint.subLevel());
    }

    private void wakeIfEntityEndpointMoved(final AttachmentEndpoint startEndpoint, final AttachmentEndpoint endEndpoint) {
        boolean moved = false;
        if (this.startAttachment.isEntity()) {
            moved |= this.hasEndpointMovedSinceWake(startEndpoint.localPoint(), true);
        }
        if (this.endAttachment.isEntity()) {
            moved |= this.hasEndpointMovedSinceWake(endEndpoint.localPoint(), false);
        }

        if (moved && this.isActive()) {
            this.wakeUp();
        }
    }

    private boolean hasEndpointMovedSinceWake(final Vector3d point, final boolean startSide) {
        final @Nullable Vector3d previousPoint = startSide ? this.lastStartEndpointWakePoint : this.lastEndEndpointWakePoint;
        final boolean moved = previousPoint == null || previousPoint.distanceSquared(point) > 1.0E-5;
        if (moved) {
            if (startSide) {
                this.lastStartEndpointWakePoint = new Vector3d(point);
            } else {
                this.lastEndEndpointWakePoint = new Vector3d(point);
            }
        }
        return moved;
    }

    public AttachmentState getAttachmentState(final ServerLevel level) {
        final AttachmentState startState = this.getAttachmentState(level, this.startAttachment, true);
        if (startState != AttachmentState.READY) {
            return startState;
        }

        return this.getAttachmentState(level, this.endAttachment, false);
    }

    public boolean shouldBreakForInvalidAttachments() {
        this.invalidAttachmentTicks++;
        return this.invalidAttachmentTicks >= INVALID_ATTACHMENT_GRACE_TICKS;
    }

    public void clearInvalidAttachmentGrace() {
        this.invalidAttachmentTicks = 0;
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
    public void onAddition(final SubLevelPhysicsSystem physicsSystem) {
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

    private AttachmentState getAttachmentState(final ServerLevel level, final TowRopeAttachment attachment, final boolean startSide) {
        if (attachment.isBlock()) {
            final BlockPos blockPos = attachment.blockPos();
            if (!level.isLoaded(blockPos)) {
                return AttachmentState.NOT_LOADED;
            }

            return TowAnchorPoints.resolveBlockTowPoint(level.getBlockEntity(blockPos)) != null
                    ? AttachmentState.READY
                    : AttachmentState.INVALID;
        }

        if (attachment.isEntity()) {
            final Entity entity = level.getEntity(attachment.entityId());
            if (entity != null && entity.isAlive()) {
                return AttachmentState.READY;
            }

            final BlockPos loadHint = this.getEntityLoadHint(startSide);
            return level.isLoaded(loadHint) ? AttachmentState.INVALID : AttachmentState.NOT_LOADED;
        }

        return AttachmentState.INVALID;
    }

    private BlockPos getEntityLoadHint(final boolean startSide) {
        final Vector3d endpoint = this.points.get(startSide ? 0 : this.points.size() - 1);
        return BlockPos.containing(endpoint.x, endpoint.y, endpoint.z);
    }

    public void physicsTick(final SubLevelPhysicsSystem physicsSystem, final double timeStep) {
        this.applyEntityTowPhysics(physicsSystem, timeStep);
    }

    private void applyEntityTowPhysics(final SubLevelPhysicsSystem physicsSystem, final double timeStep) {
        final ServerLevel level = physicsSystem.getLevel();
        final TowRopeAttachment entityAttachment;
        if (this.startAttachment.isEntity()) {
            entityAttachment = this.startAttachment;
        } else if (this.endAttachment.isEntity()) {
            entityAttachment = this.endAttachment;
        } else {
            return;
        }

        if (this.points.size() < 2) {
            return;
        }

        final TowRopeAttachment blockAttachment = this.getBlockAttachment();
        if (blockAttachment == null) {
            this.resetEntityTowControl();
            return;
        }

        final AttachmentEndpoint anchorEndpoint = this.getResolvedBlockEndpoint(level, blockAttachment);
        if (anchorEndpoint == null) {
            this.resetEntityTowControl();
            return;
        }

        final Entity entity = level.getEntity(entityAttachment.entityId());
        if (!(entity instanceof Mob mob) || !mob.isAlive()) {
            this.resetEntityTowControl();
            return;
        }

        final Vec3 towPoint = TowAnchorPoints.resolveEntityTowPoint(mob);
        final Vec3 anchorPoint = this.projectEndpointOutOfSubLevel(level, anchorEndpoint);
        final Vec3 pullVector = anchorPoint.subtract(towPoint);
        final double distance = pullVector.length();
        if (distance <= 1.0E-4) {
            this.resetEntityTowControl();
            return;
        }

        final TowEntityProfile profile = TowEntityProfile.from(mob);
        final double stretch = Math.max(0.0, distance - this.getEntityFreeMovementDistance(profile));
        if (stretch <= 0.0) {
            this.resetEntityTowControl();
            return;
        }

        final Vec3 normalizedPull = pullVector.scale(1.0 / distance);
        final Vector3d ropeVelocity = Sable.HELPER.getVelocity(level, JOMLConversion.toJOML(anchorPoint), new Vector3d());
        final Vector3d entityVelocity = Sable.HELPER.getVelocity(level, JOMLConversion.toJOML(towPoint), new Vector3d());
        final double relativeSpeedAlongRope =
                (ropeVelocity.x - entityVelocity.x) * normalizedPull.x
                        + (ropeVelocity.y - entityVelocity.y) * normalizedPull.y
                        + (ropeVelocity.z - entityVelocity.z) * normalizedPull.z;
        final double surfaceFriction = this.resolveSurfaceFriction(level, mob, towPoint);
        final double horizontalPullFactor = Mth.clamp(Math.sqrt(normalizedPull.x * normalizedPull.x + normalizedPull.z * normalizedPull.z), 0.0, 1.0);
        final double ropeLoad = profile.ropeLoadForStretch(stretch, relativeSpeedAlongRope);
        final double contraptionRequiredForce = this.resolveContraptionRequiredForce(level, normalizedPull);
        this.lastContraptionRequiredForce = contraptionRequiredForce;
        final double tractionForce = profile.tractionForce(mob.onGround(), surfaceFriction) * horizontalPullFactor;
        final double availableTractionForce = Math.max(0.0, tractionForce - contraptionRequiredForce);
        final double appliedTowForce = Math.min(ropeLoad, availableTractionForce);
        this.applyContraptionTowForce(physicsSystem, normalizedPull, appliedTowForce);
        final double tractionImpulse = profile.tractionImpulse(appliedTowForce, timeStep);
        final double counterImpulse = profile.counterImpulse(ropeLoad, availableTractionForce, timeStep) * 0.35;
        final double totalImpulse = tractionImpulse + counterImpulse;
        final EntityTowControlState controlState = this.resolveTowControlState(
                mob,
                profile,
                stretch,
                normalizedPull,
                tractionForce,
                contraptionRequiredForce,
                totalImpulse,
                ropeVelocity,
                entityVelocity
        );
        this.entityTowControlState = controlState;

        if (totalImpulse <= 1.0E-4 && controlState == EntityTowControlState.SLACK) {
            return;
        }

        final boolean pullingUpward = normalizedPull.y > 0.0;
        final boolean anchorRisingFasterThanEntity = ropeVelocity.y > entityVelocity.y;
        final double upwardPullFactor = Mth.clamp(normalizedPull.y, 0.0, 1.0);
        final double verticalScale = normalizedPull.y > 0.0
                ? Mth.lerp(upwardPullFactor, 1.05, 1.45)
                : (!mob.onGround() && anchorRisingFasterThanEntity ? 0.0 : 0.015);
        final double liftOffBoost = pullingUpward && mob.onGround()
                ? Mth.clamp(0.05 + stretch * 0.12 + totalImpulse * 0.45, 0.05, 0.28) * upwardPullFactor * TowEntityProfile.ENTITY_FORCE_MULTIPLIER
                : 0.0;
        final double controlImpulseScale = controlState.impulseScale();
        final Vec3 impulse = new Vec3(
                normalizedPull.x * totalImpulse * controlImpulseScale,
                normalizedPull.y * totalImpulse * verticalScale * controlImpulseScale + liftOffBoost,
                normalizedPull.z * totalImpulse * controlImpulseScale
        );

        mob.setDeltaMovement(mob.getDeltaMovement().add(impulse));
        mob.hasImpulse = true;

        if (mob.horizontalCollision && mob.onGround() && controlState.shouldStepUp()) {
            mob.setDeltaMovement(mob.getDeltaMovement().add(0.0, 0.18 + upwardPullFactor * 0.06, 0.0));
            mob.setOnGround(false);
            return;
        }

        if (pullingUpward && impulse.y > 0.015 && mob.onGround()) {
            mob.setOnGround(false);
        }
    }

    private void assistEntityRecovery(final ServerLevel level) {
        final TowRopeAttachment entityAttachment;
        if (this.startAttachment.isEntity()) {
            entityAttachment = this.startAttachment;
        } else if (this.endAttachment.isEntity()) {
            entityAttachment = this.endAttachment;
        } else {
            return;
        }

        if (this.points.size() < 2) {
            return;
        }

        final TowRopeAttachment blockAttachment = this.getBlockAttachment();
        if (blockAttachment == null) {
            return;
        }

        final AttachmentEndpoint anchorEndpoint = this.getResolvedBlockEndpoint(level, blockAttachment);
        if (anchorEndpoint == null) {
            return;
        }

        final Entity entity = level.getEntity(entityAttachment.entityId());
        if (!(entity instanceof Mob mob) || !mob.isAlive()) {
            return;
        }

        final PathNavigation navigation = mob.getNavigation();
        if (!this.entityTowControlState.usesRecoveryPathing()) {
            this.stopNavigation(mob);
            return;
        }

        final Vec3 towPoint = TowAnchorPoints.resolveEntityTowPoint(mob);
        final Vec3 anchorPoint = this.projectEndpointOutOfSubLevel(level, anchorEndpoint);
        final double distance = anchorPoint.distanceTo(towPoint);
        final TowEntityProfile profile = TowEntityProfile.from(mob);
        if (distance <= this.getEntityHardPullDistance(profile)) {
            this.stopNavigation(mob);
            return;
        }

        final double stretch = Math.max(0.0, distance - this.getEntityFreeMovementDistance(profile));
        final double surfaceFriction = this.resolveSurfaceFriction(level, mob, towPoint);
        navigation.moveTo(
                anchorPoint.x,
                anchorPoint.y,
                anchorPoint.z,
                profile.recoverySpeedForStretch(stretch, mob.onGround(), surfaceFriction, this.lastContraptionRequiredForce)
        );
    }

    private void resetEntityTowControl() {
        this.entityTowControlState = EntityTowControlState.SLACK;
        this.stuckTowTicks = 0;
        this.lastContraptionRequiredForce = 0.0;
    }

    private void stopNavigation(final Mob mob) {
        final PathNavigation navigation = mob.getNavigation();
        if (!navigation.isDone()) {
            navigation.stop();
        }
    }

    private Vec3 projectEndpointOutOfSubLevel(final ServerLevel level, final AttachmentEndpoint endpoint) {
        return Sable.HELPER.projectOutOfSubLevel(level, JOMLConversion.toMojang(endpoint.localPoint()));
    }

    private double getEntityFreeMovementDistance(final TowEntityProfile profile) {
        return this.getDesiredExtension() + profile.slackDistance();
    }

    private double getEntityHardPullDistance(final TowEntityProfile profile) {
        return this.getDesiredExtension() + profile.hardPullDistance();
    }

    public void updateVisualEntityEndpoint(final ServerLevel level) {
        if (this.points.size() < 2) {
            return;
        }

        final boolean entityAtStart;
        final TowRopeAttachment entityAttachment;
        if (this.startAttachment.isEntity()) {
            entityAtStart = true;
            entityAttachment = this.startAttachment;
        } else if (this.endAttachment.isEntity()) {
            entityAtStart = false;
            entityAttachment = this.endAttachment;
        } else {
            return;
        }

        final AttachmentEndpoint entityEndpoint = resolveAttachmentEndpoint(level, entityAttachment);
        if (entityEndpoint == null) {
            return;
        }

        final Vec3 projected = this.projectEndpointOutOfSubLevel(level, entityEndpoint);
        final int entityPointIndex = entityAtStart ? 0 : this.points.size() - 1;
        final Vector3d currentEntityPoint = this.points.get(entityPointIndex);
        final Vector3d delta = new Vector3d(projected.x - currentEntityPoint.x, projected.y - currentEntityPoint.y, projected.z - currentEntityPoint.z);
        if (delta.lengthSquared() <= 1.0E-8) {
            return;
        }

        final double maxIndex = this.points.size() - 1.0;
        for (int i = 0; i < this.points.size(); i++) {
            final double entityWeight = entityAtStart ? (maxIndex - i) / maxIndex : i / maxIndex;
            this.points.get(i).fma(entityWeight, delta);
        }
    }

    private void applyContraptionTowForce(final SubLevelPhysicsSystem physicsSystem,
                                          final Vec3 entityToRopeDirection,
                                          final double towForce) {
        if (towForce <= 1.0E-5) {
            return;
        }

        final TowRopeAttachment blockAttachment = this.getBlockAttachment();
        if (blockAttachment == null) {
            return;
        }

        final AttachmentEndpoint endpoint = this.getResolvedBlockEndpoint(physicsSystem.getLevel(), blockAttachment);
        if (endpoint == null || endpoint.subLevel() == null || endpoint.subLevel().isRemoved()) {
            return;
        }

        final ServerSubLevel subLevel = endpoint.subLevel();
        final Vec3 contraptionPullDirection = entityToRopeDirection.scale(-1.0);
        final Vec3 localPull = subLevel.logicalPose().transformNormalInverse(contraptionPullDirection);
        if (localPull.lengthSqr() <= 1.0E-8) {
            return;
        }

        final Vector3d localForce = new Vector3d(localPull.x, localPull.y, localPull.z).normalize().mul(towForce);
        physicsSystem.getPhysicsHandle(subLevel).applyImpulseAtPoint(endpoint.localPoint(), localForce);
    }

    private double resolveContraptionRequiredForce(final ServerLevel level,
                                                   final Vec3 normalizedPull) {
        final TowRopeAttachment blockAttachment = this.getBlockAttachment();
        if (blockAttachment == null) {
            return 0.0;
        }

        final AttachmentEndpoint endpoint = this.getResolvedBlockEndpoint(level, blockAttachment);
        if (endpoint == null || endpoint.subLevel() == null || endpoint.subLevel().isRemoved()) {
            return 0.0;
        }

        final ServerSubLevel subLevel = endpoint.subLevel();
        if (subLevel.getMassTracker() == null || subLevel.getMassTracker().isInvalid()) {
            return 0.0;
        }

        final Vec3 localPull = subLevel.logicalPose().transformNormalInverse(normalizedPull);
        if (localPull.lengthSqr() <= 1.0E-8) {
            return 0.0;
        }

        final Vector3d localDirection = new Vector3d(localPull.x, localPull.y, localPull.z).normalize();
        final double inverseNormalMass = subLevel.getMassTracker().getInverseNormalMass(endpoint.localPoint(), localDirection);
        if (inverseNormalMass <= 1.0E-8) {
            return Double.POSITIVE_INFINITY;
        }

        final double effectiveMass = Mth.clamp(1.0 / inverseNormalMass, 0.0, 512.0);
        final ContactProfile contactProfile = this.getCachedContraptionContactProfile(level, subLevel);
        final double rollingForceMultiplier = contactProfile.hasRollingSupport() ? 0.45 : 1.0;
        final double breakawayMass = Math.max(0.0, Math.cbrt(effectiveMass) - Math.cbrt(CONTRAPTION_FREE_BREAKAWAY_MASS));
        return breakawayMass * CONTRAPTION_BREAKAWAY_FORCE_SCALE * contactProfile.resistanceModifier() * rollingForceMultiplier;
    }

    private @Nullable TowRopeAttachment getBlockAttachment() {
        if (this.startAttachment.isBlock()) {
            return this.startAttachment;
        }
        if (this.endAttachment.isBlock()) {
            return this.endAttachment;
        }
        return null;
    }

    private @Nullable AttachmentEndpoint getResolvedBlockEndpoint(final ServerLevel level, final TowRopeAttachment blockAttachment) {
        if (blockAttachment == this.startAttachment && this.resolvedStartEndpoint != null) {
            return this.resolvedStartEndpoint;
        }
        if (blockAttachment == this.endAttachment && this.resolvedEndEndpoint != null) {
            return this.resolvedEndEndpoint;
        }
        return resolveAttachmentEndpoint(level, blockAttachment);
    }

    private EntityTowControlState resolveTowControlState(final Mob mob,
                                                         final TowEntityProfile profile,
                                                         final double stretch,
                                                         final Vec3 normalizedPull,
                                                         final double tractionForce,
                                                         final double contraptionRequiredForce,
                                                         final double totalImpulse,
                                                         final Vector3d ropeVelocity,
                                                         final Vector3d entityVelocity) {
        final double upwardPullFactor = Mth.clamp(normalizedPull.y, 0.0, 1.0);
        if (stretch <= SLACK_DISTANCE_EPSILON) {
            this.stuckTowTicks = 0;
            return EntityTowControlState.SLACK;
        }

        if (upwardPullFactor > LIFTED_PULL_THRESHOLD && (!mob.onGround() || ropeVelocity.y > entityVelocity.y + 0.02)) {
            this.stuckTowTicks = 0;
            return EntityTowControlState.LIFTED;
        }

        final double walkingStretchThreshold = profile.hardPullDistance()
                + (this.entityTowControlState == EntityTowControlState.WALKING ? WALKING_HYSTERESIS_DISTANCE : 0.0);
        if (stretch <= walkingStretchThreshold && tractionForce >= contraptionRequiredForce * 0.85) {
            this.stuckTowTicks = 0;
            return EntityTowControlState.WALKING;
        }

        if (stretch >= DRAGGING_STRETCH_THRESHOLD && totalImpulse <= STUCK_IMPULSE_THRESHOLD) {
            this.stuckTowTicks++;
            if (this.stuckTowTicks >= STUCK_TICK_THRESHOLD) {
                return EntityTowControlState.STUCK;
            }
        } else {
            this.stuckTowTicks = 0;
        }

        return EntityTowControlState.DRAGGING;
    }

    private ContactProfile getCachedContraptionContactProfile(final ServerLevel level, final ServerSubLevel subLevel) {
        final long gameTime = level.getGameTime();
        if (this.cachedContactProfile != null
                && this.cachedContactProfileSubLevel == subLevel
                && gameTime - this.cachedContactProfileTick < CONTACT_PROFILE_CACHE_TICKS) {
            return this.cachedContactProfile;
        }

        final ContactProfile resolvedProfile = this.resolveContraptionContactProfile(level, subLevel);
        this.cachedContactProfile = resolvedProfile;
        this.cachedContactProfileSubLevel = subLevel;
        this.cachedContactProfileTick = gameTime;
        return resolvedProfile;
    }

    private ContactProfile resolveContraptionContactProfile(final ServerLevel level, final ServerSubLevel subLevel) {
        final var bounds = subLevel.getPlot().getBoundingBox();
        if (bounds.volume() <= 0) {
            return new ContactProfile(0.45, false);
        }

        final int minX = bounds.minX();
        final int maxX = bounds.maxX();
        final int minY = bounds.minY();
        final int maxY = bounds.maxY();
        final int minZ = bounds.minZ();
        final int maxZ = bounds.maxZ();

        final int yScanMax = Math.min(maxY, minY + CONTACT_VERTICAL_SCAN_BLOCKS - 1);

        double resistanceTotal = 0.0;
        int contactSamples = 0;
        int rollingSamples = 0;

        final BlockPos.MutableBlockPos localPos = new BlockPos.MutableBlockPos();
        for (int xIndex = 0; xIndex < CONTACT_SAMPLE_AXIS_COUNT; xIndex++) {
            final int localX = sampleBoundingCoordinate(minX, maxX, xIndex);
            if (xIndex > 0 && localX == sampleBoundingCoordinate(minX, maxX, xIndex - 1)) {
                continue;
            }

            for (int zIndex = 0; zIndex < CONTACT_SAMPLE_AXIS_COUNT; zIndex++) {
                final int localZ = sampleBoundingCoordinate(minZ, maxZ, zIndex);
                if (zIndex > 0 && localZ == sampleBoundingCoordinate(minZ, maxZ, zIndex - 1)) {
                    continue;
                }

                final BlockPos contactPos = this.findContraptionContactBlock(level, localPos, localX, localZ, minY, yScanMax);
                if (contactPos == null) {
                    continue;
                }

                final ContactSample sample = this.resolveContactSample(level, subLevel, contactPos);
                resistanceTotal += sample.resistance();
                contactSamples++;
                if (sample.rollingContact()) {
                    rollingSamples++;
                }
            }
        }

        if (contactSamples == 0) {
            return new ContactProfile(0.45, false);
        }

        final boolean hasRollingSupport = rollingSamples > 0;
        double resistanceModifier = Mth.clamp(resistanceTotal / contactSamples, 0.2, 1.35);
        if (hasRollingSupport) {
            final double rollingRatio = rollingSamples / (double) contactSamples;
            final double rollingBias = Mth.lerp((float) Math.sqrt(rollingRatio), 0.75, 0.3);
            resistanceModifier = Mth.clamp(resistanceModifier * rollingBias, 0.08, 1.0);
        }

        return new ContactProfile(resistanceModifier, hasRollingSupport);
    }

    private static int sampleBoundingCoordinate(final int min, final int max, final int index) {
        return switch (index) {
            case 0 -> min;
            case CONTACT_SAMPLE_AXIS_COUNT - 1 -> max;
            default -> min + ((max - min) / 2);
        };
    }

    private @Nullable BlockPos findContraptionContactBlock(final ServerLevel level,
                                                           final BlockPos.MutableBlockPos localPos,
                                                           final int localX,
                                                           final int localZ,
                                                           final int minY,
                                                           final int yScanMax) {
        BlockPos firstSolid = null;
        for (int localY = minY; localY <= yScanMax; localY++) {
            localPos.set(localX, localY, localZ);
            final BlockState state = level.getBlockState(localPos);
            if (!state.isAir() && PhysicsBlockPropertyHelper.getMass(level, localPos, state) > 0.0) {
                if (this.isRollingContactBlock(state)) {
                    return localPos.immutable();
                }

                if (firstSolid == null) {
                    firstSolid = localPos.immutable();
                }
            }
        }

        return firstSolid;
    }

    private ContactSample resolveContactSample(final ServerLevel level, final ServerSubLevel subLevel, final BlockPos localContactPos) {
        final BlockState contactState = level.getBlockState(localContactPos);
        final boolean rollingContact = this.isRollingContactBlock(contactState);
        final Vec3 globalContactPoint = subLevel.logicalPose().transformPosition(Vec3.atCenterOf(localContactPos));
        final BlockPos worldSupportPos = BlockPos.containing(globalContactPoint.x, globalContactPoint.y - 0.55, globalContactPoint.z);
        if (!level.isLoaded(worldSupportPos)) {
            return new ContactSample(0.25, rollingContact);
        }

        final FluidState supportFluid = level.getFluidState(worldSupportPos);
        final FluidState currentFluid = level.getFluidState(BlockPos.containing(globalContactPoint));
        if (!supportFluid.isEmpty() || !currentFluid.isEmpty()) {
            final double floatingScale = PhysicsBlockPropertyHelper.getFloatingScale(contactState);
            final double floatingDrag = PhysicsBlockPropertyHelper.getFloatingMaterial(contactState) != null
                    ? 0.12
                    : 0.18;
            return new ContactSample(Mth.clamp(floatingDrag + (floatingScale * 0.08), 0.08, 0.32), rollingContact);
        }

        final BlockState supportState = level.getBlockState(worldSupportPos);
        if (supportState.isAir()) {
            final BlockPos belowSupportPos = worldSupportPos.below();
            if (!level.isLoaded(belowSupportPos)) {
                return new ContactSample(0.22, rollingContact);
            }

            final FluidState belowFluid = level.getFluidState(belowSupportPos);
            if (!belowFluid.isEmpty()) {
                return new ContactSample(0.18, rollingContact);
            }
        }

        final double supportFriction = this.mapSurfaceFriction(supportState);
        final double rollingMultiplier = rollingContact ? 0.35 : 1.0;
        final double slopePenalty = 1.0 - Math.abs(subLevel.logicalPose().transformNormal(Vec3.atLowerCornerOf(Direction.UP.getNormal())).y) * 0.25;
        return new ContactSample(Mth.clamp(supportFriction * rollingMultiplier * slopePenalty, 0.08, 1.35), rollingContact);
    }

    private boolean isRollingContactBlock(final BlockState state) {
        final String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return path.contains("wheel")
                || path.contains("roller")
                || path.contains("bogey")
                || path.contains("track")
                || path.contains("rail");
    }

    private double mapSurfaceFriction(final BlockState surfaceState) {
        final double friction = PhysicsBlockPropertyHelper.getFriction(surfaceState);

        if (friction < 1.0) {
            return Mth.clamp(0.1 + (friction * 0.9), 0.1, 1.0);
        }

        return Mth.clamp(friction, 1.0, 1.5);
    }

    private double resolveSurfaceFriction(final ServerLevel level, final Mob mob, final Vec3 towPoint) {
        if (!mob.onGround()) {
            return 0.25;
        }

        final BlockPos surfacePos = BlockPos.containing(towPoint.x, mob.getBoundingBox().minY - 0.05, towPoint.z);
        if (!level.isLoaded(surfacePos)) {
            return 1.0;
        }

        return this.mapSurfaceFriction(level.getBlockState(surfacePos));
    }

    private boolean updateWinchExtension(final ServerLevel level) {
        if (!this.startAttachment.isBlock()) {
            return false;
        }

        final BlockEntity blockEntity = level.getBlockEntity(this.startAttachment.blockPos());
        if (!(blockEntity instanceof RopeWinchBlockEntity ropeWinchBlockEntity)) {
            return false;
        }

        float movementSpeed = ropeWinchBlockEntity.getMovementSpeed();
        final int previousPointCount = this.points.size();
        final double previousExtensionGoal = this.extensionGoal;
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
        return previousPointCount != this.points.size() || !Mth.equal(previousExtensionGoal, this.extensionGoal);
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

    public enum AttachmentState {
        READY,
        NOT_LOADED,
        INVALID
    }

    private record ContactProfile(double resistanceModifier, boolean hasRollingSupport) {
    }

    private record ContactSample(double resistance, boolean rollingContact) {
    }

    public enum EntityTowControlState {
        SLACK(false, 0.0, false),
        WALKING(true, 0.22, false),
        DRAGGING(false, 1.0, true),
        STUCK(false, 0.7, true),
        LIFTED(false, 1.0, false);

        private final boolean usesRecoveryPathing;
        private final double impulseScale;
        private final boolean stepUp;

        EntityTowControlState(final boolean usesRecoveryPathing,
                              final double impulseScale,
                              final boolean stepUp) {
            this.usesRecoveryPathing = usesRecoveryPathing;
            this.impulseScale = impulseScale;
            this.stepUp = stepUp;
        }

        public boolean usesRecoveryPathing() {
            return this.usesRecoveryPathing;
        }

        public double impulseScale() {
            return this.impulseScale;
        }

        public boolean shouldStepUp() {
            return this.stepUp;
        }
    }
}
