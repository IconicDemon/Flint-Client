package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263MoreMods;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Totem: a player's totem pops counted, your own burst of particles and the big totem on screen as the module says. */
@Mixin(ClientPacketListener.class)
abstract class TotemEventsMixin {
	@Inject(method = "handleEntityEvent", at = @At("HEAD"))
	private void flint$count(ClientboundEntityEventPacket packet, CallbackInfo callback) {
		Minecraft mc = Minecraft.getInstance();
		if (packet.getEventId() == 35 && mc.level != null && mc.isSameThread()) {
			Mc263MoreMods.totemPopped(packet.getEntity(mc.level));
		}
	}

	@WrapWithCondition(method = "handleEntityEvent", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/particle/ParticleEngine;createTrackingEmitter(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/particles/ParticleOptions;I)V"))
	private boolean flint$ownParticles(ParticleEngine engine, Entity entity, ParticleOptions particle, int lifetime) {
		return particle != ParticleTypes.TOTEM_OF_UNDYING || Mc263MoreMods.totemParticles(entity);
	}

	/** Minecraft 26.2 shows the big totem through the game renderer. */
	@WrapWithCondition(method = "handleEntityEvent", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/GameRenderer;displayItemActivation(Lnet/minecraft/world/item/ItemStack;)V"))
	private boolean flint$popAnimation(net.minecraft.client.renderer.GameRenderer renderer, ItemStack stack) {
		return Mc263MoreMods.popAnimation();
	}
}
