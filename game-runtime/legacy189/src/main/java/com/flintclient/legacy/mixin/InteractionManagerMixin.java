package com.flintclient.legacy.mixin;

import com.flintclient.legacy.mods.Combat;
import net.minecraft.client.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.living.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Your attacks, for Reach Display and Combo Display. */
@Mixin(ClientPlayerInteractionManager.class)
public abstract class InteractionManagerMixin {
	@Inject(method = "attackEntity", at = @At("HEAD"))
	private void flint$attack(PlayerEntity player, Entity target, CallbackInfo ci) {
		Combat.attacked(target);
	}
}
