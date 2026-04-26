package dev.justfeli.towed.tow;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

public record TowEntityProfile(double weight, double strength) {
    static final double ENTITY_FORCE_MULTIPLIER = 0.5;
    private static final double MIN_HITBOX_VOLUME = 0.125;
    private static final double MIN_HITBOX_CROSS_SECTION = 0.25;

    public static TowEntityProfile from(final Entity entity) {
        final AABB bounds = entity.getBoundingBox();
        final double volume = Math.max(bounds.getXsize() * bounds.getYsize() * bounds.getZsize(), MIN_HITBOX_VOLUME);
        final double crossSection = Math.max(Math.max(bounds.getXsize(), bounds.getZsize()) * bounds.getYsize(), MIN_HITBOX_CROSS_SECTION);

        final double weight = Mth.clamp(0.12 + (Math.cbrt(volume) * 0.38), 0.2, 1.8);
        final double strength = Mth.clamp(Math.sqrt(crossSection) * 0.7, 0.05, 2.5);
        return new TowEntityProfile(weight, strength);
    }

    public double slackDistance() {
        return 0.42 + (this.weight * 0.12);
    }

    public double hardPullDistance() {
        return this.slackDistance() + 0.18 + (this.weight * 0.08);
    }

    public double effectiveStrength(final double surfaceFriction) {
        return this.strength * Mth.clamp(surfaceFriction, 0.1, 1.5);
    }

    public double recoverySpeed(final double distance,
                                final boolean grounded,
                                final double surfaceFriction,
                                final double contraptionRequiredForce) {
        final double excess = Math.max(0.0, distance - this.slackDistance());
        return this.recoverySpeedForStretch(excess, grounded, surfaceFriction, contraptionRequiredForce);
    }

    public double recoverySpeedForStretch(final double stretch,
                                          final boolean grounded,
                                          final double surfaceFriction,
                                          final double contraptionRequiredForce) {
        final double driveForce = Math.max(0.15, this.tractionForce(grounded, surfaceFriction) - contraptionRequiredForce);
        final double responsiveness = driveForce / this.weight;
        return Mth.clamp(0.95 + (stretch * 0.38 * responsiveness), 0.9, 2.4);
    }

    public double springStrength() {
        return 0.7 + (this.weight * 0.55);
    }

    public double dampingStrength() {
        return 0.22 + (this.weight * 0.18);
    }

    public double tractionForce(final boolean grounded, final double surfaceFriction) {
        final double tractionMultiplier = grounded ? 0.32 : 0.08;
        return this.effectiveStrength(surfaceFriction) * tractionMultiplier * ENTITY_FORCE_MULTIPLIER;
    }

    public double tractionImpulse(final double usableTractionForce, final double timeStep) {
        return Mth.clamp(usableTractionForce * timeStep / this.weight, 0.0, 0.2);
    }

    public double ropeLoad(final double distance, final double relativeSpeedAlongRope) {
        final double stretch = Math.max(0.0, distance - this.slackDistance());
        return this.ropeLoadForStretch(stretch, relativeSpeedAlongRope);
    }

    public double ropeLoadForStretch(final double stretch, final double relativeSpeedAlongRope) {
        return Math.max(0.0, stretch * this.springStrength() + relativeSpeedAlongRope * this.dampingStrength());
    }

    public double counterImpulse(final double ropeLoad,
                                 final double availableTractionForce,
                                 final double timeStep) {
        final double resistedTension = Math.max(0.0, ropeLoad - availableTractionForce);
        return Mth.clamp(resistedTension * timeStep / this.weight, 0.0, 0.18) * ENTITY_FORCE_MULTIPLIER;
    }
}
