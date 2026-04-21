package dev.justfeli.towed.tow;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import foundry.veil.api.util.CodecUtil;
import net.minecraft.core.UUIDUtil;
import org.joml.Vector3d;

import java.util.List;
import java.util.UUID;

public record TowRopeState(UUID ropeId, TowRopeAttachment startAttachment, TowRopeAttachment endAttachment, List<Vector3d> points) {
    public static final Codec<TowRopeState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("rope_id").forGetter(TowRopeState::ropeId),
            TowRopeAttachment.CODEC.fieldOf("start_attachment").forGetter(TowRopeState::startAttachment),
            TowRopeAttachment.CODEC.fieldOf("end_attachment").forGetter(TowRopeState::endAttachment),
            CodecUtil.VECTOR3D_CODEC.listOf().fieldOf("points").forGetter(TowRopeState::points)
    ).apply(instance, TowRopeState::new));
}
