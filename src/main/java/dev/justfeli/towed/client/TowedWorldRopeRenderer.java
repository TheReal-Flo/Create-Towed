package dev.justfeli.towed.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllBlocks;
import dev.ryanhcode.sable.api.math.OrientedBoundingBox3d;
import dev.simulated_team.simulated.content.blocks.rope.strand.client.ClientRopePoint;
import dev.simulated_team.simulated.index.SimPartialModels;
import dev.simulated_team.simulated.util.SimMathUtils;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.List;

public final class TowedWorldRopeRenderer {
    private TowedWorldRopeRenderer() {
    }

    public static void renderAll(final PoseStack poseStack, final float partialTick, final Vec3 cameraPos) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }

        final ClientTowRopeManager manager = ClientTowRopeManager.getOrCreate(minecraft.level);
        if (manager == null) {
            return;
        }

        final SuperByteBuffer middle = CachedBuffers.partialFacing(SimPartialModels.ROPE, AllBlocks.ROPE.getDefaultState(), Direction.NORTH);
        final SuperByteBuffer knot = CachedBuffers.partialFacing(SimPartialModels.ROPE_KNOT, AllBlocks.ROPE.getDefaultState(), Direction.NORTH);
        final MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();
        final VertexConsumer vertexConsumer = bufferSource.getBuffer(RenderType.solid());

        poseStack.pushPose();
        for (final TowedClientRopeStrand rope : manager.getAll()) {
            final List<ClientRopePoint> points = rope.getPoints();
            if (points.size() <= 1) {
                continue;
            }

            final ObjectArrayList<RopeRenderPoint> renderPoints = buildRenderPoints(partialTick, points);
            if (renderPoints.isEmpty()) {
                continue;
            }

            for (int i = 1; i < renderPoints.size(); i++) {
                final RopeRenderPoint point0 = renderPoints.get(i - 1);
                final RopeRenderPoint point1 = renderPoints.get(i);
                final Vector3d globalRenderPos = new Vector3d(point0.position());
                final Quaternionf orientation = new Quaternionf(point0.orientation());
                final double length = point1.position().distance(point0.position());

                poseStack.pushPose();
                poseStack.translate(globalRenderPos.x - cameraPos.x, globalRenderPos.y - cameraPos.y, globalRenderPos.z - cameraPos.z);
                poseStack.mulPose(orientation);
                poseStack.translate(-0.5, -0.5, -0.5);

                final int worldLight = LevelRenderer.getLightColor(minecraft.level, BlockPos.containing(globalRenderPos.x, globalRenderPos.y, globalRenderPos.z));
                if (i > 1) {
                    knot.light(worldLight).renderInto(poseStack, vertexConsumer);
                }

                poseStack.translate(0.0, 0.5, 0.0);
                poseStack.scale(1.0f, (float) length, 1.0f);
                middle.light(worldLight).renderInto(poseStack, vertexConsumer);
                poseStack.popPose();
            }
        }
        poseStack.popPose();

        bufferSource.endBatch(RenderType.solid());
    }

    private static ObjectArrayList<RopeRenderPoint> buildRenderPoints(final float partialTick, final List<ClientRopePoint> inputPoints) {
        final ObjectArrayList<RopeRenderPoint> ropeRenderPoints = new ObjectArrayList<>();
        final ObjectArrayList<ClientRopePoint> points = new ObjectArrayList<>(inputPoints);

        while (points.size() >= 2 && points.getFirst().position().distanceSquared(points.get(1).position()) < 1e-3) {
            points.removeFirst();
        }

        if (points.size() <= 1) {
            return ropeRenderPoints;
        }

        final Vector3dc pointZeroPosition = points.get(0).renderPos(partialTick, new Vector3d());
        final Vector3dc pointOnePosition = points.get(1).renderPos(partialTick, new Vector3d());
        final Vector3d normal = pointOnePosition.sub(pointZeroPosition, new Vector3d()).normalize();

        final Quaternionf runningRotation;
        if (normal.dot(OrientedBoundingBox3d.UP) < 0) {
            runningRotation = SimMathUtils.getQuaternionfFromVectorRotation(new Vector3d(0, -1, 0), normal);
            runningRotation.rotateZ((float) Math.PI);
        } else {
            runningRotation = SimMathUtils.getQuaternionfFromVectorRotation(new Vector3d(0, 1, 0), normal);
        }

        ropeRenderPoints.add(new RopeRenderPoint(new Quaternionf(runningRotation), new Vector3d(pointZeroPosition)));

        final Vector3d runningNormal = new Vector3d();
        final Vector3d bPos = new Vector3d();
        final Vector3d aPos = new Vector3d();

        for (int i = 2; i < points.size(); i++) {
            final ClientRopePoint pointA = points.get(i - 1);
            final ClientRopePoint pointB = points.get(i);

            runningNormal.set(pointB.renderPos(partialTick, bPos))
                    .sub(pointA.renderPos(partialTick, aPos))
                    .normalize();

            if (runningNormal.dot(OrientedBoundingBox3d.UP) < -0.15) {
                runningRotation.set(SimMathUtils.getQuaternionfFromVectorRotation(new Vector3d(0, -1, 0), runningNormal));
                runningRotation.rotateZ((float) Math.PI);
            } else {
                runningRotation.set(SimMathUtils.getQuaternionfFromVectorRotation(new Vector3d(0, 1, 0), runningNormal));
            }

            ropeRenderPoints.add(new RopeRenderPoint(new Quaternionf(runningRotation), pointA.renderPos(partialTick, new Vector3d())));
        }

        ropeRenderPoints.add(new RopeRenderPoint(new Quaternionf(runningRotation), points.getLast().renderPos(partialTick, new Vector3d())));
        return ropeRenderPoints;
    }

    private record RopeRenderPoint(Quaternionf orientation, Vector3d position) {
    }
}
