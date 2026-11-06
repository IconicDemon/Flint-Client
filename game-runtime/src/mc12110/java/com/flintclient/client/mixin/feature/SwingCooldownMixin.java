package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263AttackCooldown;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Attack Indicator's Accurate cooldown: your swing restarts your attack cooldown (Minecraft 1.21.10 has no item-swap ticker, so its own reset restarts only the attack ticker). */
@Mixin(LivingEntity.class)
abstract class SwingCooldownMixin {
	@Inject(method = "swing(Lnet/minecraft/world/InteractionHand;Z)V", at = @At("HEAD"))
	private void flint$accurateCooldown(InteractionHand hand, boolean sendToSwingingEntity, CallbackInfo callback) {
		if ((Object) this instanceof Player player && Mc263AttackCooldown.resetOnSwing(player)) {
			player.resetAttackStrengthTicker();
		}
	}
}
