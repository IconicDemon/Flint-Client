package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Visuals;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Particle Changer: the server's critical and enchanted hit particles, shown as many times over as chosen. */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerFeatureMixin {
	@WrapOperation(method = "handleAnimate", at = @At(value = "INVOKE", ordinal = 0,
		target = "Lnet/minecraft/client/particle/ParticleEngine;createTrackingEmitter(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/particles/ParticleOptions;)V"))
	private void flint$crits(ParticleEngine engine, Entity entity, ParticleOptions type, Operation<Void> original) {
		for (int i = Mc263Visuals.hitParticles(true); i > 0; i--) {
			original.call(engine, entity, type);
		}
	}

	@WrapOperation(method = "handleAnimate", at = @At(value = "INVOKE", ordinal = 1,
		target = "Lnet/minecraft/client/particle/ParticleEngine;createTrackingEmitter(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/particles/ParticleOptions;)V"))
	private void flint$enchantedHits(ParticleEngine engine, Entity entity, ParticleOptions type, Operation<Void> original) {
		for (int i = Mc263Visuals.hitParticles(false); i > 0; i--) {
			original.call(engine, entity, type);
		}
	}
}
