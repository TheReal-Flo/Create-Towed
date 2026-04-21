package dev.justfeli.towed.client;

import dev.justfeli.towed.tow.TowRopeAttachment;
import dev.simulated_team.simulated.content.blocks.rope.strand.client.ClientRopePoint;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.createmod.catnip.data.WorldAttached;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;

public final class ClientTowRopeManager {
    private static final WorldAttached<ClientTowRopeManager> WORLD_ATTACHED = new WorldAttached<>(ClientTowRopeManager::create);

    private final Map<UUID, TowedClientRopeStrand> ropes = new Object2ObjectOpenHashMap<>();
    private ClientTowRopeManager() {
    }

    public static @Nullable ClientTowRopeManager getOrCreate(final Level level) {
        return WORLD_ATTACHED.get(level);
    }

    private static ClientTowRopeManager create(final LevelAccessor level) {
        if (!(level instanceof ClientLevel)) {
            return null;
        }
        return new ClientTowRopeManager();
    }

    public void receive(final UUID ropeId, final TowRopeAttachment startAttachment, final TowRopeAttachment endAttachment, final int interpolationTick, final List<Vector3d> incomingPoints) {
        final TowedClientRopeStrand rope = this.ropes.computeIfAbsent(ropeId, TowedClientRopeStrand::new);
        rope.setStopped(false);
        rope.setAttachments(startAttachment, endAttachment);

        final ObjectArrayList<ClientRopePoint> points = rope.getPoints();
        while (points.size() < incomingPoints.size()) {
            final Vector3d position = incomingPoints.get(incomingPoints.size() - points.size() - 1);
            points.addFirst(new ClientRopePoint(new Vector3d(position), new Vector3d(position), new ObjectArrayList<>()));
        }

        while (points.size() > incomingPoints.size()) {
            points.removeFirst();
        }

        for (int i = 0; i < incomingPoints.size(); i++) {
            points.get(i).snapshots().add(new ClientRopePoint.Snapshot(interpolationTick, new Vector3d(incomingPoints.get(i))));
        }
    }

    public void remove(final UUID ropeId) {
        this.ropes.remove(ropeId);
    }

    public Iterable<TowedClientRopeStrand> getAll() {
        return this.ropes.values();
    }

    public void tickInterpolation(final Level level, final double interpolationTick) {
        final List<UUID> staleRopes = new ArrayList<>();
        for (final TowedClientRopeStrand rope : this.ropes.values()) {
            rope.tickTowInterpolation(level, interpolationTick);
            if (rope.shouldDiscard(level)) {
                staleRopes.add(rope.getUuid());
            }
        }

        for (final UUID ropeId : staleRopes) {
            this.remove(ropeId);
        }
    }
}
