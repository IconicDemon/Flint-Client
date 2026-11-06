package com.flintclient.legacy.mixin;

import com.flintclient.client.control.hud.render.HudRenderer;
import com.flintclient.legacy.Flint;
import net.minecraft.client.gui.screen.game.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The shared HUD's own buttons with chat open (Music Player's previous, play/pause and next, and its bar), as on every line:
 * chat reports the pointer each frame so they light up under it, and a left click on one presses it instead of reaching chat.
 */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void flint$pointer(int mouseX, int mouseY, float tickDelta, CallbackInfo ci) {
		if (Flint.ready() && Flint.hudOn()) {
			HudRenderer.chatPointer(mouseX, mouseY);
		}
	}

	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void flint$click(int mouseX, int mouseY, int button, CallbackInfo ci) {
		if (Flint.ready() && Flint.hudOn() && HudRenderer.chatClick(mouseX, mouseY, button)) {
			ci.cancel();
		}
	}
}
