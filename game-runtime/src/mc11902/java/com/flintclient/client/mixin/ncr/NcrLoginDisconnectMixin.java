package com.flintclient.client.mixin.ncr;

import com.flintclient.client.compat.net.LoginSigning;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * No Chat Reports when a Minecraft 1.19.2 server insists on signed chat: it turns a client that gave no key away at login, so
 * that's where the signing mode is asked (compat.net.LoginSigning: Prompt shows its warning, On Demand joins again signing).
 * Only the screen Minecraft is about to show is held back: the login's own disconnect handling (and the network hooks') run as
 * before. 1.19.3 and later ask in chat instead (NcrChatListenerMixin), so later versions have no such hook.
 *
 * <p>Adapted from No Chat Reports (Aizistral, WTFPL; META-INF/licenses/WTFPL-NoChatReports.txt).
 */
@Mixin(ClientHandshakePacketListenerImpl.class)
abstract class NcrLoginDisconnectMixin {
	@Shadow
	@Final
	private Screen parent;

	@WrapOperation(method = "onDisconnect", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/Minecraft;setScreen(Lnet/minecraft/client/gui/screens/Screen;)V"))
	private void flint$signingRequired(Minecraft minecraft, Screen screen, Operation<Void> original, @Local(argsOnly = true) Component reason) {
		if (screen instanceof DisconnectedScreen && LoginSigning.turnedAway(reason, parent)) {
			return;
		}
		original.call(minecraft, screen);
	}
}
