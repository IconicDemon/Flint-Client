package com.flintclient.client.mixin.ncr;

import com.flintclient.client.control.platform.Mc263ChatSafety;
import com.flintclient.client.ui.screen.ChatSafetyScreens;
import com.flintclient.client.ui.widget.SpriteButton;
import java.util.List;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No Chat Reports in the multiplayer menu: its reload and on/off buttons, stacked right of the footer's buttons.
 *
 * <p>Adapted from No Chat Reports (Aizistral, WTFPL; META-INF/licenses/WTFPL-NoChatReports.txt).
 */
@Mixin(JoinMultiplayerScreen.class)
abstract class NcrJoinMultiplayerScreenMixin {
	@Shadow
	@Final
	private HeaderAndFooterLayout layout;

	@Inject(method = "init", at = @At("HEAD"))
	private void flint$buttons(CallbackInfo callback) {
		if (!Mc263ChatSafety.on()) {
			return;
		}
		List<SpriteButton> buttons = ChatSafetyScreens.multiplayerButtons();
		if (buttons.isEmpty()) {
			return;
		}
		LinearLayout column = layout.addToFooter(LinearLayout.vertical().spacing(4), settings -> settings.paddingLeft(336));
		for (SpriteButton button : buttons) {
			column.addChild(button);
		}
	}
}
