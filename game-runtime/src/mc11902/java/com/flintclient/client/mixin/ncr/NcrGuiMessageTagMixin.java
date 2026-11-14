package com.flintclient.client.mixin.ncr;

import com.flintclient.client.control.model.mods.ServerMods.NoChatReports;
import com.flintclient.client.control.platform.Mc263ChatSafety;
import net.minecraft.client.GuiMessageTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No Chat Reports: system messages without the grey bar Minecraft marks them with (Hide system indicators). Minecraft 1.19.2
 * has one system tag (the single-player one came in 1.19.3).
 *
 * <p>Adapted from No Chat Reports (Aizistral, WTFPL; META-INF/licenses/WTFPL-NoChatReports.txt).
 */
@Mixin(GuiMessageTag.class)
abstract class NcrGuiMessageTagMixin {
	@Inject(method = "system", at = @At("HEAD"), cancellable = true)
	private static void flint$noTag(CallbackInfoReturnable<GuiMessageTag> callback) {
		if (Mc263ChatSafety.with(NoChatReports.HIDE_SYSTEM)) {
			callback.setReturnValue(null);
		}
	}
}
