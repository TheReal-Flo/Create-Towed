package dev.justfeli.towed.tow;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

public record TowEntityProfile(double weight, double strength) {
    private static final double MIN_HITBOX_VOLUME = 0.125;
    private static final double MIN_HITBOX_CROSS_SECTION = 0.25;

    public static TowEntityProfile from(final Entity entity) {
        final AABB bounds = entity.getBoundingBox();
        final double volume = Math.max(bounds.getXsize() * bounds.getYsize() * bounds.getZsize(), MIN_HITBOX_VOLUME);
        final double crossSection = Math.max(Math.max(bounds.getXsize(), bounds.getZsize()) * bounds.getYsize(), MIN_HITBOX_CROSS_SECTION);

        final double weight = Mth.clamp(0.15 + (Math.cbrt(volume) * 0.45), 0.25, 2.25);
        final double strength = Mth.clamp(1.0 + (Math.sqrt(crossSection) * 1.1), 1.1, 5.0);
        return new TowEntityProfile(weight, strength);
    }

    public double slackDistance() {
        return 0.65 + (this.weight * 0.18);
    }

    public double hardPullDistance() {
        return this.slackDistance() + 0.5 + (this.weight * 0.2);
    }

    public double navigationSpeed(final double distance) {
        final double excess = Math.max(0.0, distance - this.slackDistance());
        final double responsiveness = this.strength / this.weight;
        return Mth.clamp(0.9 + (excess * 0.18 * responsiveness), 0.8, 1.8);
    }

    public double impulseMagnitude(final double distance) {
        final double responsiveness = this.strength / this.weight;
        final double softPull = Math.max(0.0, distance - this.slackDistance()) * 0.09 * responsiveness;
        final double hardPull = Math.max(0.0, distance - this.hardPullDistance()) * 0.07 * Math.max(1.0, responsiveness * 0.75);
        return Mth.clamp(softPull + hardPull, 0.0, 0.45);
    }
}
