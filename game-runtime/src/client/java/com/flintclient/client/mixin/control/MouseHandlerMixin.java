package com.flintclient.client.mixin.control;

import com.flintclient.client.control.ControlCenter;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Counts clicks for the CPS module. {@code onButton} hands a press to the key bindings ({@code
 * KeyMapping.click}) only when it's a press and no screen or overlay is open, after screens and global keys
 * have had their turn, so hooking that call counts exactly the clicks that reach gameplay. Passing the
 * argument through unchanged, rather than injecting a callback, allocates nothing.
 */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
	@ModifyArg(method = "onButton", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/KeyMapping;click(Lcom/mojang/blaze3d/platform/InputConstants$Key;)V"))
	private InputConstants.Key flint$countClick(InputConstants.Key button) {
		ControlCenter.onClick(button.getValue());
		return button;
	}
}
