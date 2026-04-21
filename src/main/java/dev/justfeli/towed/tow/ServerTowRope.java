package dev.justfeli.towed.tow;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.object.rope.RopeHandle;
import dev.ryanhcode.sable.api.physics.object.rope.RopePhysicsObject;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
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

import java.util.Collection;
import java.util.UUID;

public final class ServerTowRope extends RopePhysicsObject {
    private static final double SEGMENT_LENGTH = 1.0;
    private static final int INVALID_ATTACHMENT_GRACE_TICKS = 40;
    // Reuse within the current server tick so physics/recovery don't rescan twice,
    // but refresh every tick to keep towing force visually smooth.
    private static final long CONTACT_PROFILE_CACHE_TICKS = 1L;

    private final UUID ropeId;
    private final TowRopeAttachment startAttachment;
    private final TowRopeAttachment endAttachment;
    private double extensionGoal;
    private double appliedFirstSegmentLength;
    private int invalidAttachmentTicks;
    private @Nullable AttachmentEndpoint resolvedStartEndpoint;
    private @Nullable AttachmentEndpoint resolvedEndEndpoint;
    private @Nullable ContactProfile cachedContactProfile;
    private long cachedContactProfileTick;

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
        this.cachedContactProfileTick = Long.MIN_VALUE;
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

    public boolean matchesEntity(final UUID entityId) {
        return this.startAttachment.matchesEntity(entityId) || this.endAttachment.matchesEntity(entityId);
    }

    public TowRopeState toState() {
        return new TowRopeState(this.ropeId, this.startAttachment, this.endAttachment, this.points.stream().map(Vector3d::new).toList());
    }

    public void serverTick(final ServerLevel level) {
        this.updateWinchExtension(level);
        this.assistEntityRecovery(level);
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
        this.setAttachment(RopeHandle.AttachmentPoint.START, startEndpoint.localPoint(), startEndpoint.subLevel());
        this.setAttachment(RopeHandle.AttachmentPoint.END, endEndpoint.localPoint(), endEndpoint.subLevel());
        return true;
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

    public void physicsTick(final ServerLevel level, final double timeStep) {
        this.applyEntityTowPhysics(level, timeStep);
    }

    private void applyEntityTowPhysics(final ServerLevel level, final double timeStep) {
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

        if (this.points.size() < 2) {
            return;
        }

        final Entity entity = level.getEntity(entityAttachment.entityId());
        if (!(entity instanceof Mob mob) || !mob.isAlive()) {
            return;
        }

        final Vec3 towPoint = TowAnchorPoints.resolveEntityTowPoint(mob);
        final Vector3d interiorPoint = this.points.get(entityAtStart ? 1 : this.points.size() - 2);
        final Vec3 ropeDirectionPoint = JOMLConversion.toMojang(interiorPoint);
        final Vec3 pullVector = ropeDirectionPoint.subtract(towPoint);
        final double distance = pullVector.length();
        if (distance <= 1.0E-4) {
            return;
        }

        final TowEntityProfile profile = TowEntityProfile.from(mob);
        final double stretch = Math.max(0.0, distance - profile.slackDistance());
        if (stretch <= 0.0) {
            return;
        }

        final Vec3 normalizedPull = pullVector.scale(1.0 / distance);
        final Vector3d ropeVelocity = Sable.HELPER.getVelocity(level, interiorPoint, new Vector3d());
        final Vector3d entityVelocity = Sable.HELPER.getVelocity(level, JOMLConversion.toJOML(towPoint), new Vector3d());
        final double relativeSpeedAlongRope = ropeVelocity.sub(entityVelocity).dot(normalizedPull.x, normalizedPull.y, normalizedPull.z);
        final double surfaceFriction = this.resolveSurfaceFriction(level, mob, towPoint);
        final double horizontalPullFactor = Mth.clamp(Math.sqrt(normalizedPull.x * normalizedPull.x + normalizedPull.z * normalizedPull.z), 0.0, 1.0);
        final double ropeLoad = profile.ropeLoad(distance, relativeSpeedAlongRope);
        final double contraptionRequiredForce = this.resolveContraptionRequiredForce(level, normalizedPull, ropeLoad);
        final double tractionForce = profile.tractionForce(mob.onGround(), surfaceFriction) * horizontalPullFactor;
        final double availableTractionForce = Math.max(0.0, tractionForce - contraptionRequiredForce);
        final double tractionImpulse = profile.tractionImpulse(Math.min(ropeLoad, availableTractionForce), timeStep);
        final double counterImpulse = profile.counterImpulse(ropeLoad, availableTractionForce, timeStep) * 0.35;
        final double totalImpulse = tractionImpulse + counterImpulse;
        if (totalImpulse <= 1.0E-4) {
            return;
        }

        final boolean pullingUpward = normalizedPull.y > 0.0;
        final boolean anchorRisingFasterThanEntity = ropeVelocity.y > entityVelocity.y;
        final double upwardPullFactor = Mth.clamp(normalizedPull.y, 0.0, 1.0);
        final double verticalScale = normalizedPull.y > 0.0
                ? Mth.lerp(upwardPullFactor, 1.05, 1.45)
                : (!mob.onGround() && anchorRisingFasterThanEntity ? 0.0 : 0.015);
        final double liftOffBoost = pullingUpward && mob.onGround()
                ? Mth.clamp(0.05 + stretch * 0.12 + totalImpulse * 0.45, 0.05, 0.28) * upwardPullFactor
                : 0.0;
        final Vec3 impulse = new Vec3(
                normalizedPull.x * totalImpulse,
                normalizedPull.y * totalImpulse * verticalScale + liftOffBoost,
                normalizedPull.z * totalImpulse
        );

        mob.setDeltaMovement(mob.getDeltaMovement().add(impulse));
        mob.hasImpulse = true;

        if (pullingUpward && impulse.y > 0.015 && mob.onGround()) {
            mob.setOnGround(false);
        }
    }

    private void assistEntityRecovery(final ServerLevel level) {
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

        if (this.points.size() < 2) {
            return;
        }

        final Entity entity = level.getEntity(entityAttachment.entityId());
        if (!(entity instanceof Mob mob) || !mob.isAlive()) {
            return;
        }

        final Vec3 towPoint = TowAnchorPoints.resolveEntityTowPoint(mob);
        final Vector3d interiorPoint = this.points.get(entityAtStart ? 1 : this.points.size() - 2);
        final Vec3 ropeDirectionPoint = JOMLConversion.toMojang(interiorPoint);
        final double distance = ropeDirectionPoint.distanceTo(towPoint);
        final TowEntityProfile profile = TowEntityProfile.from(mob);
        final double surfaceFriction = this.resolveSurfaceFriction(level, mob, towPoint);
        final Vec3 pullVector = ropeDirectionPoint.subtract(towPoint);
        final double contraptionRequiredForce;
        if (pullVector.lengthSqr() <= 1.0E-8) {
            contraptionRequiredForce = 0.0;
        } else {
            final Vec3 normalizedPull = pullVector.normalize();
            final double ropeLoad = profile.ropeLoad(distance, 0.0);
            contraptionRequiredForce = this.resolveContraptionRequiredForce(level, normalizedPull, ropeLoad);
        }
        if (distance <= profile.hardPullDistance()) {
            return;
        }

        final PathNavigation navigation = mob.getNavigation();
        navigation.moveTo(
                ropeDirectionPoint.x,
                ropeDirectionPoint.y,
                ropeDirectionPoint.z,
                profile.recoverySpeed(distance, mob.onGround(), surfaceFriction, contraptionRequiredForce)
        );
    }

    private double resolveContraptionRequiredForce(final ServerLevel level,
                                                   final Vec3 normalizedPull,
                                                   final double ropeLoad) {
        final TowRopeAttachment blockAttachment;
        if (this.startAttachment.isBlock()) {
            blockAttachment = this.startAttachment;
        } else if (this.endAttachment.isBlock()) {
            blockAttachment = this.endAttachment;
        } else {
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
            return ropeLoad;
        }

        final double effectiveMass = Mth.clamp(1.0 / inverseNormalMass, 0.0, 512.0);
        final double baseResistanceShare = this.resolveNormalMassResistanceShare(effectiveMass);
        final ContactProfile contactProfile = this.getCachedContraptionContactProfile(level, subLevel);
        final double rollingForceMultiplier = contactProfile.hasRollingSupport() ? 0.45 : 1.0;
        return Mth.clamp(ropeLoad * baseResistanceShare * contactProfile.resistanceModifier() * rollingForceMultiplier, 0.0, ropeLoad);
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

    private double resolveNormalMassResistanceShare(final double effectiveMass) {
        final double softenedMass = Math.cbrt(effectiveMass);
        return Mth.clamp(softenedMass / (softenedMass + 1.35), 0.0, 1.0);
    }

    private ContactProfile getCachedContraptionContactProfile(final ServerLevel level, final ServerSubLevel subLevel) {
        final long gameTime = level.getGameTime();
        if (this.cachedContactProfile != null && gameTime - this.cachedContactProfileTick < CONTACT_PROFILE_CACHE_TICKS) {
            return this.cachedContactProfile;
        }

        final ContactProfile resolvedProfile = this.resolveContraptionContactProfile(level, subLevel);
        this.cachedContactProfile = resolvedProfile;
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

        final int xStep = Math.max(1, (maxX - minX + 1) / 6);
        final int zStep = Math.max(1, (maxZ - minZ + 1) / 6);
        final int yScanMax = Math.min(maxY, minY + 3);

        double resistanceTotal = 0.0;
        int contactSamples = 0;
        int rollingSamples = 0;

        final BlockPos.MutableBlockPos localPos = new BlockPos.MutableBlockPos();
        for (int localX = minX; localX <= maxX; localX += xStep) {
            for (int localZ = minZ; localZ <= maxZ; localZ += zStep) {
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

    public enum AttachmentState {
        READY,
        NOT_LOADED,
        INVALID
    }

    private record ContactProfile(double resistanceModifier, boolean hasRollingSupport) {
    }

    private record ContactSample(double resistance, boolean rollingContact) {
    }
}
