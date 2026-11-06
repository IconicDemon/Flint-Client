package com.flintclient.client.mixin.ncr;

import com.flintclient.client.control.platform.Mc263ChatSafety;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.options.ChatOptionsScreen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No Chat Reports: Chat Settings' "Only Show Secure Chat" can't be turned on (there are no signatures to check).
 *
 * <p>Adapted from No Chat Reports (Aizistral, WTFPL; META-INF/licenses/WTFPL-NoChatReports.txt).
 */
@Mixin(OptionsSubScreen.class)
abstract class NcrOptionsSubScreenMixin {
	@Shadow
	protected OptionsList list;

	@Inject(method = "init", at = @At("TAIL"))
	private void flint$secureChat(CallbackInfo callback) {
		if ((Object) this instanceof ChatOptionsScreen && list != null && Mc263ChatSafety.enabled()) {
			AbstractWidget option = list.findOption(Minecraft.getInstance().options.onlyShowSecureChat());
			if (option != null) {
				option.active = false;
			}
		}
	}
}
