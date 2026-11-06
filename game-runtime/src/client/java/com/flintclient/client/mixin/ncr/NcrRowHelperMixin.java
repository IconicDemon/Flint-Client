package com.flintclient.client.mixin.ncr;

import com.flintclient.client.control.model.mods.ServerMods.NoChatReports;
import com.flintclient.client.control.platform.Mc263ChatSafety;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No Chat Reports' Remove telemetry button: Options leaves out "Telemetry Data" while telemetry is off.
 *
 * <p>Adapted from No Chat Reports (Aizistral, WTFPL; META-INF/licenses/WTFPL-NoChatReports.txt).
 */
@Mixin(GridLayout.RowHelper.class)
abstract class NcrRowHelperMixin {
	@Inject(method = "addChild(Lnet/minecraft/client/gui/layouts/LayoutElement;)Lnet/minecraft/client/gui/layouts/LayoutElement;", at = @At("HEAD"),
		cancellable = true)
	private void flint$telemetryButton(LayoutElement element, CallbackInfoReturnable<LayoutElement> callback) {
		if (element instanceof Button button && button.getMessage().getContents() instanceof TranslatableContents key
			&& "options.telemetry".equals(key.getKey()) && Mc263ChatSafety.with(NoChatReports.DISABLE_TELEMETRY)
			&& Mc263ChatSafety.bool(NoChatReports.REMOVE_TELEMETRY_BUTTON)) {
			callback.setReturnValue(button);
		}
	}
}
