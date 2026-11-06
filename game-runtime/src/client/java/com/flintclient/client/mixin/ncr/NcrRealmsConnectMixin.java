package com.flintclient.client.mixin.ncr;

import com.flintclient.client.control.platform.Mc263ChatSafety;
import net.minecraft.realms.RealmsConnect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No Chat Reports: a Realm is never safe, and always signs.
 *
 * <p>Adapted from No Chat Reports (Aizistral, WTFPL; META-INF/licenses/WTFPL-NoChatReports.txt).
 */
@Mixin(RealmsConnect.class)
abstract class NcrRealmsConnectMixin {
	@Inject(method = "connect", at = @At("HEAD"))
	private void flint$realms(CallbackInfo callback) {
		Mc263ChatSafety.connectingToRealms();
	}
}
