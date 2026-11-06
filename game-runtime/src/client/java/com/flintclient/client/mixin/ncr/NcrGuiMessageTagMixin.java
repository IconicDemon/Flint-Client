package com.flintclient.client.mixin.ncr;

import com.flintclient.client.control.model.mods.ServerMods.NoChatReports;
import com.flintclient.client.control.platform.Mc263ChatSafety;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No Chat Reports' Hide system message indicators: system messages get no tag (no grey bar).
 *
 * <p>Adapted from No Chat Reports (Aizistral, WTFPL; META-INF/licenses/WTFPL-NoChatReports.txt).
 */
@Mixin(GuiMessageTag.class)
abstract class NcrGuiMessageTagMixin {
	@Inject(method = {"system", "systemSinglePlayer"}, at = @At("HEAD"), cancellable = true)
	private static void flint$noTag(CallbackInfoReturnable<GuiMessageTag> callback) {
		if (Mc263ChatSafety.with(NoChatReports.HIDE_SYSTEM)) {
			callback.setReturnValue(null);
		}
	}
}
