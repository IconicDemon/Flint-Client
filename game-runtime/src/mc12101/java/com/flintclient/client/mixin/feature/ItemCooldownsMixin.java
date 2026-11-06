package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Features;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemCooldowns;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cooldowns: which cooldowns start and end, and how long they last. Minecraft 1.21.1 keeps a cooldown per item (its id is the group). */
@Mixin(ItemCooldowns.class)
abstract class ItemCooldownsMixin {
	@Inject(method = "onCooldownStarted", at = @At("HEAD"))
	private void flint$started(Item item, int duration, CallbackInfo callback) {
		Mc263Features.cooldownStarted(BuiltInRegistries.ITEM.getKey(item), duration);
	}

	@Inject(method = "onCooldownEnded", at = @At("HEAD"))
	private void flint$ended(Item item, CallbackInfo callback) {
		Mc263Features.cooldownEnded(BuiltInRegistries.ITEM.getKey(item));
	}
}
