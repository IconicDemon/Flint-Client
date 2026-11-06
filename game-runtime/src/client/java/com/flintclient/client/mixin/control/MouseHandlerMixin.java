package com.flintclient.client.mixin.control;

import com.flintclient.client.control.ControlCenter;
import com.flintclient.client.control.feature.MouseButtons;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts clicks for the CPS module. {@code onButton} hands a press to the key bindings ({@code
 * KeyMapping.click}) only when it's a press and no screen or overlay is open, after screens and global keys
 * have had their turn, so hooking that call counts exactly the clicks that reach gameplay. Passing the
 * argument through unchanged, rather than injecting a callback, allocates nothing.
 *
 * <p>Also records every button's state first thing ({@link MouseButtons}), before a screen can take the event: what Flint's
 * keys bound to a mouse button read. A button event is a click, so the callback object doesn't matter.
 */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	@Inject(method = "onButton", at = @At("HEAD"))
	private void flint$buttonState(long handle, MouseButtonInfo button, int action, CallbackInfo callback) {
		if (handle != 0L && handle == minecraft.getWindow().handle()) {
			MouseButtons.event(button.button(), action == 1);
		}
	}

	@ModifyArg(method = "onButton", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/KeyMapping;click(Lcom/mojang/blaze3d/platform/InputConstants$Key;)V"))
	private InputConstants.Key flint$countClick(InputConstants.Key button) {
		ControlCenter.onClick(button.getValue());
		return button;
	}
}
