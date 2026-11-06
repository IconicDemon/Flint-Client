package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.Input;
import com.flintclient.client.control.platform.Mc263Features;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Toggle Sneak/Sprint on Minecraft 1.21.1: after the keys are read, a latched toggle holds sneak, as holding the key would.
 * 1.21.1 reads sprint from its key mapping as the player moves, so a latched sprint is kept for LocalPlayerSprintMixin.
 */
@Mixin(KeyboardInput.class)
abstract class KeyboardInputMixin {
	@Inject(method = "tick", at = @At("TAIL"))
	private void flint$toggles(boolean slow, float slowFactor, CallbackInfo callback) {
		KeyboardInput input = (KeyboardInput) (Object) this;
		boolean sprintKey = Minecraft.getInstance().options.keySprint.isDown();
		Input held = Mc263Features.movementInput(new Input(input.up, input.down, input.left, input.right, input.jumping, input.shiftKeyDown,
			sprintKey));
		input.shiftKeyDown = held.shift();
		Input.latchedSprint = held.sprint() && !sprintKey;
	}
}
