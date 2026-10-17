package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Features;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemCooldowns;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cooldowns: the full length of each cooldown the server starts, so seconds left can be shown. */
@Mixin(ItemCooldowns.class)
abstract class ItemCooldownsMixin {
	@Inject(method = "onCooldownStarted", at = @At("HEAD"))
	private void flint$started(Identifier group, int duration, CallbackInfo callback) {
		Mc263Features.cooldownStarted(group, duration);
	}

	@Inject(method = "onCooldownEnded", at = @At("HEAD"))
	private void flint$ended(Identifier group, CallbackInfo callback) {
		Mc263Features.cooldownEnded(group);
	}
}
