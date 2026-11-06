package com.flintclient.client.mixin.ncr;

import com.flintclient.client.compat.McCompat;
import com.flintclient.client.control.platform.Mc263ChatSafety;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No Chat Reports in the world this client hosts: chat arrives without its signature (nothing to keep or relay).
 *
 * <p>Adapted from No Chat Reports (Aizistral, WTFPL; META-INF/licenses/WTFPL-NoChatReports.txt).
 */
@Mixin(ServerboundChatPacket.class)
abstract class NcrServerboundChatPacketMixin {
	@Inject(method = "signature", at = @At("RETURN"), cancellable = true)
	private void flint$unsigned(CallbackInfoReturnable<Object> callback) {
		if (Mc263ChatSafety.hostIgnoresSigning()) {
			callback.setReturnValue(McCompat.noSignature());
		}
	}
}
