package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Features;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Toggle Sneak/Sprint: after the keys are read, a latched toggle holds sprint or sneak, as holding the key would. */
@Mixin(KeyboardInput.class)
abstract class KeyboardInputMixin {
	@Inject(method = "tick", at = @At("TAIL"))
	private void flint$toggles(CallbackInfo callback) {
		ClientInput input = (ClientInput) (Object) this;
		input.keyPresses = Mc263Features.movementInput(input.keyPresses);
	}
}
