package com.flintclient.client.mixin.ncr;

import com.flintclient.client.control.platform.Mc263ChatSafety;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No Chat Reports: a server list ping's answer says whether the server prevents chat reports (the "Safe Server" icon).
 *
 * <p>Adapted from No Chat Reports (Aizistral, WTFPL; META-INF/licenses/WTFPL-NoChatReports.txt).
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ServerStatusPinger$1")
abstract class NcrStatusListenerMixin {
	@Inject(method = "handleStatusResponse", at = @At("HEAD"))
	private void flint$status(ClientboundStatusResponsePacket packet, CallbackInfo callback) {
		Mc263ChatSafety.statusResponse(this, packet.status());
	}
}
