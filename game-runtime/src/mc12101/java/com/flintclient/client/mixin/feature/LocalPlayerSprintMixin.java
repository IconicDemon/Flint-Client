package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.Input;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Toggle Sprint on Minecraft 1.21.1: the player reads the sprint key as it moves (both reads in aiStep are the sprint key's),
 * and a latched sprint reads as held (KeyboardInputMixin latches it each tick).
 */
@Mixin(LocalPlayer.class)
abstract class LocalPlayerSprintMixin {
	@ModifyExpressionValue(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;isDown()Z"))
	private boolean flint$sprintHeld(boolean down) {
		return down || Input.latchedSprint;
	}
}
