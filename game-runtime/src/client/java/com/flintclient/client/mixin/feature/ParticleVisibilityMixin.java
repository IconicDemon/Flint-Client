package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263CleanView;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Particle Changer's first-person visibility (CleanView): a particle the filter leaves out is never created. */
@Mixin(ParticleEngine.class)
abstract class ParticleVisibilityMixin {
	@Inject(method = "createParticle", at = @At("HEAD"), cancellable = true)
	private void flint$filter(ParticleOptions options, double x, double y, double z, double xd, double yd, double zd,
		CallbackInfoReturnable<Particle> callback) {
		if (!Mc263CleanView.create(options, x, y, z)) {
			callback.setReturnValue(null);
		}
	}
}
