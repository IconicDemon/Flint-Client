package com.flintclient.client.mixin.bridge;

import com.flintclient.client.compat.InputType;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Minecraft 1.19.2: a mouse button makes the mouse the last input, as 1.20 records it ({@link InputType}). */
@Mixin(MouseHandler.class)
abstract class LastMouseInputMixin {
	@Inject(method = "onPress", at = @At("HEAD"))
	private void flint$mouse(long window, int button, int action, int modifiers, CallbackInfo callback) {
		InputType.set(InputType.MOUSE);
	}
}
