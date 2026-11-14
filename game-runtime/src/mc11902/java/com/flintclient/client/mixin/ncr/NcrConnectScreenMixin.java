package com.flintclient.client.mixin.ncr;

import com.flintclient.client.compat.net.LoginSigning;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No Chat Reports: a new connection starts the chat safety over, for this server (Minecraft 1.19.2). It's done just before the
 * connect screen connects, after startConnecting has left the last world (whose end starts the state over too) and before
 * connect asks the key manager for the login's key: whether this connection signs is decided there (compat.net.LoginSigning).
 *
 * <p>Adapted from No Chat Reports (Aizistral, WTFPL; META-INF/licenses/WTFPL-NoChatReports.txt).
 */
@Mixin(ConnectScreen.class)
abstract class NcrConnectScreenMixin {
	@Inject(method = "startConnecting", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/screens/ConnectScreen;connect(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/multiplayer/resolver/ServerAddress;)V"))
	private static void flint$connecting(Screen parent, Minecraft minecraft, ServerAddress address, @Nullable ServerData data, CallbackInfo callback) {
		LoginSigning.connecting(address, data);
	}
}
