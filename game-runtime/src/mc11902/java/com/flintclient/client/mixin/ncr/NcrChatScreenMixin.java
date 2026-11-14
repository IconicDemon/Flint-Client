package com.flintclient.client.mixin.ncr;

import com.flintclient.client.control.model.mods.ServerMods.NoChatReports;
import com.flintclient.client.control.platform.Mc263ChatSafety;
import com.flintclient.client.ui.screen.ChatSafetyScreens;
import com.flintclient.client.ui.widget.SpriteButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No Chat Reports in the chat screen on Minecraft 1.19.2: the safety status button (bottom right; it never takes the focus from
 * the input, which Tab completion and the screen's own render keep on the input too), and the last message kept to send again
 * once signing starts. The message is taken as it's sent: 1.19.2 also tidies the line for chat preview as it's typed.
 *
 * <p>1.19.2 can't start signing in the middle of a session (the server learns your key at login only, compat.net.LoginSigning),
 * so Always signs from the join itself and the first message is never held back waiting for signing to start.
 *
 * <p>Adapted from No Chat Reports (Aizistral, WTFPL; META-INF/licenses/WTFPL-NoChatReports.txt).
 */
@Mixin(ChatScreen.class)
abstract class NcrChatScreenMixin extends Screen {
	@Unique
	private @Nullable SpriteButton flint$safety;

	protected NcrChatScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("HEAD"))
	private void flint$safetyButton(CallbackInfo callback) {
		flint$safety = null;
		if (Mc263ChatSafety.enabled() && Mc263ChatSafety.bool(NoChatReports.SHOW_SAFETY)) {
			flint$safety = addRenderableWidget(ChatSafetyScreens.safetyButton(width, height));
		}
	}

	@Inject(method = "handleChatInput", at = @At("HEAD"))
	private void flint$lastMessage(String message, boolean addToHistory, CallbackInfoReturnable<Boolean> callback) {
		Mc263ChatSafety.lastMessage(((ChatScreen) (Object) this).normalizeChatMessage(message));
	}

	@Override
	public void setFocused(@Nullable GuiEventListener listener) {
		if (flint$safety == null || listener != flint$safety) {
			super.setFocused(listener);
		}
	}
}
