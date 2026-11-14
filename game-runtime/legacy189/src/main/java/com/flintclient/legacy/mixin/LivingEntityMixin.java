package com.flintclient.legacy.mixin;

import com.flintclient.legacy.Flint;
import net.minecraft.entity.living.LivingEntity;
import net.minecraft.entity.particle.ParticleType;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Animations' Disable Camera Potion Particles: your own effect swirls aren't made while you're in first person. Item Customizer's
 * swing speed, Haste and Mining Fatigue: how long your own swing takes.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@Redirect(method = "tickStatusEffects", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/World;addParticle(Lnet/minecraft/entity/particle/ParticleType;DDDDDD[I)V"))
	private void flint$potionParticles(World world, ParticleType type, double x, double y, double z, double dx, double dy, double dz,
		int[] data) {
		if (!Flint.ready() || Flint.modules().hitColor.potionParticles((LivingEntity) (Object) this)) {
			world.addParticle(type, x, y, z, dx, dy, dz, data);
		}
	}

	@com.llamalad7.mixinextras.injector.ModifyReturnValue(method = "getArmSwingDuration", at = @At("RETURN"))
	private int flint$swing(int ticks) {
		if (!Flint.ready()) {
			return ticks;
		}
		try {
			return Flint.modules().itemCustomizer.swingTicks((LivingEntity) (Object) this, ticks);
		} catch (RuntimeException | LinkageError e) {
			Flint.fault(Flint.modules().itemCustomizer, e);
			return ticks;
		}
	}
}
