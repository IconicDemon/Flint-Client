package com.flintclient.client.mixin.ncr;

import com.flintclient.client.control.model.mods.ServerMods.NoChatReports;
import com.flintclient.client.control.platform.Mc263ChatSafety;
import com.flintclient.client.ui.screen.ChatSafetyScreens;
import com.flintclient.client.ui.widget.SpriteButton;
import net.minecraft.client.gui.ComponentPath;
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
 * No Chat Reports in the chat screen: the safety status button (bottom right; it never takes the focus from the input), the
 * first message held back in Always mode until signing starts, and the last message kept to send again once signing does.
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

	@Inject(method = "handleChatInput", at = @At("HEAD"), cancellable = true)
	private void flint$holdForSigning(String message, boolean addToHistory, CallbackInfo callback) {
		if (Mc263ChatSafety.chatInput(((ChatScreen) (Object) this).normalizeChatMessage(message))) {
			callback.cancel();
		}
	}

	@Inject(method = "normalizeChatMessage", at = @At("RETURN"))
	private void flint$lastMessage(String message, CallbackInfoReturnable<String> callback) {
		Mc263ChatSafety.lastMessage(callback.getReturnValue());
	}

	@Override
	protected void changeFocus(ComponentPath path) {
		ComponentPath leaf = path;
		while (leaf instanceof ComponentPath.Path node && leaf != node.childPath()) {
			leaf = node.childPath();
		}
		if (flint$safety == null || leaf.component() != flint$safety) {
			super.changeFocus(path);
		}
	}

	@Override
	public void setFocused(@Nullable GuiEventListener listener) {
		if (flint$safety == null || listener != flint$safety) {
			super.setFocused(listener);
		}
	}
}
