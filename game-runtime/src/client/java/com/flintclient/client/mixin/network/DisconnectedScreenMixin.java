package com.flintclient.client.mixin.network;

import com.flintclient.client.network.platform.NetworkHooks;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every disconnected screen goes through this constructor (main thread). It's how a failure without a
 * login under way shows itself (the connector thread's own errors), so the tracker ends such an attempt
 * here; for every other attempt state it does nothing.
 */
@Mixin(DisconnectedScreen.class)
abstract class DisconnectedScreenMixin {
	@Inject(
		method = "<init>(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/DisconnectionDetails;Lnet/minecraft/network/chat/Component;)V",
		at = @At("RETURN"))
	private void flint$shown(Screen parent, Component title, DisconnectionDetails details, Component buttonText, CallbackInfo callback) {
		NetworkHooks.disconnectedScreen(details);
	}
}
