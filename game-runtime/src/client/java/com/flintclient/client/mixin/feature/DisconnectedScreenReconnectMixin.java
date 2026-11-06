package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Reconnect;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Reconnect: its button under the disconnected screen's own buttons, kept there when the window is resized. */
@Mixin(DisconnectedScreen.class)
abstract class DisconnectedScreenReconnectMixin extends Screen {
	@Shadow
	@Final
	private Screen parent;
	@Shadow
	@Final
	private LinearLayout layout;

	protected DisconnectedScreenReconnectMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void flint$reconnect(CallbackInfo callback) {
		Button button = Mc263Reconnect.button(this, parent, layout.getY() + layout.getHeight());
		if (button != null) {
			addRenderableWidget(button);
		}
	}

	@Inject(method = "repositionElements", at = @At("TAIL"))
	private void flint$placeReconnect(CallbackInfo callback) {
		Mc263Reconnect.place(this, layout.getY() + layout.getHeight());
	}
}
