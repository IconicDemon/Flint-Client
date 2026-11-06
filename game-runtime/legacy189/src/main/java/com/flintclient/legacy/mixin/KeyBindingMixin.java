package com.flintclient.legacy.mixin;

import com.flintclient.legacy.Flint;
import net.minecraft.client.options.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Toggle Sprint holds the sprint or sneak key down while its toggle is on. */
@Mixin(KeyBinding.class)
public abstract class KeyBindingMixin {
	@Inject(method = "isPressed", at = @At("HEAD"), cancellable = true)
	private void flint$toggled(CallbackInfoReturnable<Boolean> cir) {
		if (Flint.ready() && Flint.modules().toggleSprint.holds((KeyBinding) (Object) this)) {
			cir.setReturnValue(true);
		}
	}
}
