package com.flintclient.client.mixin.control;

import com.flintclient.client.control.ControlCenter;
import com.flintclient.client.control.feature.MouseButtons;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts clicks for the CPS module (Minecraft 1.21.1). {@code onPress} hands a press to the key bindings
 * ({@code KeyMapping.click}) only when it's a press and no screen or overlay is open, so hooking that call counts exactly
 * the clicks that reach gameplay. Passing the argument through unchanged allocates nothing.
 *
 * <p>Also records every button's state first thing ({@link MouseButtons}), before a screen can take the event: what Flint's
 * keys bound to a mouse button read.
 */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	@Inject(method = "onPress", at = @At("HEAD"))
	private void flint$buttonState(long handle, int button, int action, int modifiers, CallbackInfo callback) {
		if (handle != 0L && handle == minecraft.getWindow().getWindow()) {
			MouseButtons.event(button, action == 1);
		}
	}

	@ModifyArg(method = "onPress", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/KeyMapping;click(Lcom/mojang/blaze3d/platform/InputConstants$Key;)V"))
	private InputConstants.Key flint$countClick(InputConstants.Key button) {
		ControlCenter.onClick(button.getValue());
		return button;
	}
}
