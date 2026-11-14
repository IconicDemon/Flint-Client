package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Reconnect;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reconnect: its button under the disconnected screen's own button. Minecraft 1.19.2's disconnected screen has no layout: its
 * "Back to server list" button sits under the reason (half a line below its middle), kept 30 above the bottom, as init places
 * it; a resize runs init again, which places both again.
 */
@Mixin(DisconnectedScreen.class)
abstract class DisconnectedScreenReconnectMixin extends Screen {
	@Shadow
	@Final
	private Screen parent;
	@Shadow
	private int textHeight;

	protected DisconnectedScreenReconnectMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void flint$reconnect(CallbackInfo callback) {
		int backBottom = Math.min(height / 2 + textHeight / 2 + 9, height - 30) + 20;
		Button button = Mc263Reconnect.button(this, parent, backBottom);
		if (button != null) {
			addRenderableWidget(button);
		}
	}
}
