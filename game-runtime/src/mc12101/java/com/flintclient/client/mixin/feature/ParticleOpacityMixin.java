package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263CleanView;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.particle.SingleQuadParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Particle Changer's particle opacity (CleanView) on Minecraft 1.21.1: a quad particle near your eyes in first person is
 * drawn see-through. 1.21.1 writes each corner in renderVertex, whose first three numbers are the quad's camera-relative
 * position, so its alpha is lowered there; nothing is allocated per particle.
 */
@Mixin(SingleQuadParticle.class)
abstract class ParticleOpacityMixin {
	@ModifyArg(method = "renderVertex", index = 3, at = @At(value = "INVOKE",
		target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;setColor(FFFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
	private float flint$opacity(float alpha, @Local(argsOnly = true, ordinal = 0) float x, @Local(argsOnly = true, ordinal = 1) float y,
		@Local(argsOnly = true, ordinal = 2) float z) {
		int color = Math.round(Math.max(0, Math.min(1, alpha)) * 255) << 24 | 0xFFFFFF;
		int shown = Mc263CleanView.color(x, y, z, color);
		return shown == color ? alpha : (shown >>> 24) / 255.0F;
	}
}
