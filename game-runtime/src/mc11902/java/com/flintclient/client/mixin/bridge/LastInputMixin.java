package com.flintclient.client.mixin.bridge;

import com.flintclient.client.compat.InputType;
import net.minecraft.client.KeyboardHandler;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Minecraft 1.19.2: Tab and the arrows make the keyboard the last input, as 1.20 records it ({@link InputType}). */
@Mixin(KeyboardHandler.class)
abstract class LastInputMixin {
	@Inject(method = "keyPress", at = @At("HEAD"))
	private void flint$keyboard(long window, int key, int scancode, int action, int modifiers, CallbackInfo callback) {
		if (action == GLFW.GLFW_RELEASE) {
			return;
		}
		if (key == GLFW.GLFW_KEY_TAB) {
			InputType.set(InputType.KEYBOARD_TAB);
		} else if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT) {
			InputType.set(InputType.KEYBOARD_ARROW);
		}
	}
}
