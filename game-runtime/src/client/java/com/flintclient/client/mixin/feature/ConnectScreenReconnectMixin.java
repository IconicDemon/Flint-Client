package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Reconnect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Reconnect: where each server connection goes (a server-list join, a direct join, a transfer), to go back to it. */
@Mixin(ConnectScreen.class)
abstract class ConnectScreenReconnectMixin {
	@Inject(method = "startConnecting", at = @At("HEAD"))
	private static void flint$remember(Screen parent, Minecraft minecraft, ServerAddress address, ServerData data, boolean quickPlay,
		@Nullable TransferState transfer, CallbackInfo callback) {
		Mc263Reconnect.connecting(address, data);
	}
}
