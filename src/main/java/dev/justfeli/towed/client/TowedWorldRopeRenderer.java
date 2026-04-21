package dev.justfeli.towed.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllBlocks;
import dev.ryanhcode.sable.api.math.OrientedBoundingBox3d;
import dev.simulated_team.simulated.content.blocks.rope.strand.client.ClientRopePoint;
import dev.simulated_team.simulated.index.SimPartialModels;
import dev.simulated_team.simulated.util.SimMathUtils;
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

import java.util.List;

public final class TowedWorldRopeRenderer {
    private static final Vector3d UP_AXIS = new Vector3d(0, 1, 0);
    private static final Vector3d DOWN_AXIS = new Vector3d(0, -1, 0);

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

            final int firstSegmentIndex = findFirstRenderableSegment(points);
            if (firstSegmentIndex < 0) {
                continue;
            }

            renderRopeSegments(minecraft, poseStack, cameraPos, points, partialTick, firstSegmentIndex, knot, middle, vertexConsumer);
        }
        poseStack.popPose();

        bufferSource.endBatch(RenderType.solid());
    }

    private static int findFirstRenderableSegment(final List<ClientRopePoint> points) {
        int firstSegmentIndex = 0;
        while (firstSegmentIndex + 1 < points.size()
                && points.get(firstSegmentIndex).position().distanceSquared(points.get(firstSegmentIndex + 1).position()) < 1.0E-3) {
            firstSegmentIndex++;
        }
        return firstSegmentIndex + 1 < points.size() ? firstSegmentIndex : -1;
    }

    private static void renderRopeSegments(final Minecraft minecraft,
                                           final PoseStack poseStack,
                                           final Vec3 cameraPos,
                                           final List<ClientRopePoint> points,
                                           final float partialTick,
                                           final int firstSegmentIndex,
                                           final SuperByteBuffer knot,
                                           final SuperByteBuffer middle,
                                           final VertexConsumer vertexConsumer) {
        final Vector3d segmentStart = new Vector3d();
        final Vector3d segmentEnd = new Vector3d();
        final Vector3d segmentNormal = new Vector3d();
        final Quaternionf orientation = new Quaternionf();

        points.get(firstSegmentIndex).renderPos(partialTick, segmentStart);

        for (int i = firstSegmentIndex + 1; i < points.size(); i++) {
            points.get(i).renderPos(partialTick, segmentEnd);
            segmentNormal.set(segmentEnd).sub(segmentStart);
            final double length = segmentNormal.length();
            if (length <= 1.0E-4) {
                segmentStart.set(segmentEnd);
                continue;
            }

            segmentNormal.div(length);
            updateOrientation(orientation, segmentNormal);
            renderSegment(
                    minecraft,
                    poseStack,
                    cameraPos,
                    segmentStart,
                    orientation,
                    length,
                    i > firstSegmentIndex + 1,
                    knot,
                    middle,
                    vertexConsumer
            );
            segmentStart.set(segmentEnd);
        }
    }

    private static void updateOrientation(final Quaternionf orientation, final Vector3d normal) {
        if (normal.dot(OrientedBoundingBox3d.UP) < -0.15) {
            orientation.set(SimMathUtils.getQuaternionfFromVectorRotation(DOWN_AXIS, normal));
            orientation.rotateZ((float) Math.PI);
            return;
        }

        orientation.set(SimMathUtils.getQuaternionfFromVectorRotation(UP_AXIS, normal));
    }

    private static void renderSegment(final Minecraft minecraft,
                                      final PoseStack poseStack,
                                      final Vec3 cameraPos,
                                      final Vector3d globalRenderPos,
                                      final Quaternionf orientation,
                                      final double length,
                                      final boolean renderKnot,
                                      final SuperByteBuffer knot,
                                      final SuperByteBuffer middle,
                                      final VertexConsumer vertexConsumer) {
        poseStack.pushPose();
        poseStack.translate(globalRenderPos.x - cameraPos.x, globalRenderPos.y - cameraPos.y, globalRenderPos.z - cameraPos.z);
        poseStack.mulPose(orientation);
        poseStack.translate(-0.5, -0.5, -0.5);

        final int worldLight = LevelRenderer.getLightColor(minecraft.level, BlockPos.containing(globalRenderPos.x, globalRenderPos.y, globalRenderPos.z));
        if (renderKnot) {
            knot.light(worldLight).renderInto(poseStack, vertexConsumer);
        }

        poseStack.translate(0.0, 0.5, 0.0);
        poseStack.scale(1.0f, (float) length, 1.0f);
        middle.light(worldLight).renderInto(poseStack, vertexConsumer);
        poseStack.popPose();
    }
}
