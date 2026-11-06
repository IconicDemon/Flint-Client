package com.flintclient.client.mixin.ncr;

import com.flintclient.client.control.platform.Mc263ChatSafety;
import net.minecraft.commands.arguments.ArgumentSignatures;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No Chat Reports in the world this client hosts: signed commands arrive without their argument signatures.
 *
 * <p>Adapted from No Chat Reports (Aizistral, WTFPL; META-INF/licenses/WTFPL-NoChatReports.txt).
 */
@Mixin(ServerboundChatCommandSignedPacket.class)
abstract class NcrServerboundCommandSignedMixin {
	@Inject(method = "argumentSignatures", at = @At("RETURN"), cancellable = true)
	private void flint$unsigned(CallbackInfoReturnable<ArgumentSignatures> callback) {
		if (Mc263ChatSafety.hostIgnoresSigning()) {
			callback.setReturnValue(ArgumentSignatures.EMPTY);
		}
	}
}
