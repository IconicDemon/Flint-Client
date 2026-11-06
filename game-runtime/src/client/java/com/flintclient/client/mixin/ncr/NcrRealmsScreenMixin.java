package com.flintclient.client.mixin.ncr;

import com.flintclient.client.compat.McCompat;
import com.flintclient.client.ui.screen.ChatSafetyScreens;
import com.mojang.realmsclient.RealmsMainScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** No Chat Reports' Realms warning: shown first, whatever opens Realms (the warning's Proceed then opens it).
 *
 * <p>Adapted from No Chat Reports (Aizistral, WTFPL; META-INF/licenses/WTFPL-NoChatReports.txt).
 */
@Mixin(targets = McCompat.SCREENS_CLASS)
abstract class NcrRealmsScreenMixin {
	@ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
	private Screen flint$realmsWarning(Screen screen) {
		if (screen instanceof RealmsMainScreen) {
			Screen warning = ChatSafetyScreens.beforeRealms(screen);
			if (warning != null) {
				return warning;
			}
		}
		return screen;
	}
}
