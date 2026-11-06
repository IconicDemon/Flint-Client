package com.flintclient.client.mixin.ncr;

import com.flintclient.client.control.platform.Mc263ChatSafety;
import com.flintclient.client.ui.screen.ChatSafetyScreens;
import com.flintclient.client.ui.widget.SpriteButton;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No Chat Reports' buttons on the server list (Minecraft 1.21.1): a column just right of the two rows of buttons, as on the
 * later versions (1.21.1's server list lays its buttons out by hand, 308 wide, centred in the bottom 64 pixels).
 */
@Mixin(JoinMultiplayerScreen.class)
abstract class NcrJoinMultiplayerScreenMixin extends Screen {
	private NcrJoinMultiplayerScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void flint$buttons(CallbackInfo callback) {
		if (!Mc263ChatSafety.on()) {
			return;
		}
		List<SpriteButton> buttons = ChatSafetyScreens.multiplayerButtons();
		int x = width / 2 + 154 + 4;
		int y = height - 54;
		for (SpriteButton button : buttons) {
			button.setPosition(x, y);
			addRenderableWidget(button);
			y += 24;
		}
	}
}
