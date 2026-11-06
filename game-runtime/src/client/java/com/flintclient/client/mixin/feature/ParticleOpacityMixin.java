package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263CleanView;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.particle.SingleQuadParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Particle Changer's particle opacity (CleanView): a quad particle near your eyes in first person is drawn see-through. The
 * quad's camera-relative position is the method's own x, y and z, so nothing is allocated per particle.
 */
@Mixin(SingleQuadParticle.class)
abstract class ParticleOpacityMixin {
	@ModifyArg(method = "extractRotatedQuad(Lnet/minecraft/client/renderer/state/level/QuadParticleRenderState;Lorg/joml/Quaternionf;FFFF)V",
		index = 13, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/state/level/QuadParticleRenderState;add(Lnet/minecraft/client/particle/SingleQuadParticle$Layer;FFFFFFFFFFFFII)V"))
	private int flint$opacity(int color, @Local(argsOnly = true, ordinal = 0) float x, @Local(argsOnly = true, ordinal = 1) float y,
		@Local(argsOnly = true, ordinal = 2) float z) {
		return Mc263CleanView.color(x, y, z, color);
	}
}
