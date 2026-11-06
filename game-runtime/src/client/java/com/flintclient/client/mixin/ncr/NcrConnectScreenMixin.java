package com.flintclient.client.mixin.ncr;

import com.flintclient.client.control.platform.Mc263ChatSafety;
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

/** No Chat Reports: a new connection starts the chat safety over, for this server.
 *
 * <p>Adapted from No Chat Reports (Aizistral, WTFPL; META-INF/licenses/WTFPL-NoChatReports.txt).
 */
@Mixin(ConnectScreen.class)
abstract class NcrConnectScreenMixin {
	@Inject(method = "startConnecting", at = @At("HEAD"))
	private static void flint$connecting(Screen parent, Minecraft minecraft, ServerAddress address, @Nullable ServerData data, boolean quickPlay,
		@Nullable TransferState transfer, CallbackInfo callback) {
		Mc263ChatSafety.connecting(address, data);
	}
}
