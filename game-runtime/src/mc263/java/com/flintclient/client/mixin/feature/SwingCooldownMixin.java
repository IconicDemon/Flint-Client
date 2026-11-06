package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263AttackCooldown;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.SwingAnimation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Attack Indicator's Accurate cooldown: your swing restarts your attack cooldown (Minecraft 26.3's swing takes the swing animation). */
@Mixin(LivingEntity.class)
abstract class SwingCooldownMixin {
	@Inject(method = "swing(Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/component/SwingAnimation;Z)Z", at = @At("HEAD"))
	private void flint$accurateCooldown(InteractionHand hand, SwingAnimation animation, boolean sendToSwingingEntity,
		CallbackInfoReturnable<Boolean> callback) {
		if ((Object) this instanceof Player player && Mc263AttackCooldown.resetOnSwing(player)) {
			player.resetOnlyAttackStrengthTicker();
		}
	}
}
