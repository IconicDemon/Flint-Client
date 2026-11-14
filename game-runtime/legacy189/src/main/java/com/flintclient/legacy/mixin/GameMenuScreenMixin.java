package com.flintclient.legacy.mixin;

import com.flintclient.client.control.model.GameOptions;
import com.flintclient.legacy.shared.Shared;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.menu.GameMenuScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.options.ServerListEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Server leave confirmation, as on every line (General › Confirm before leaving): on a server, the pause menu's Disconnect asks
 * "Leave the server?" first; Yes leaves as Disconnect does, No comes back to the pause menu. Single player leaves at once.
 */
@Mixin(GameMenuScreen.class)
public abstract class GameMenuScreenMixin extends Screen {
	/** Minecraft 1.8.9's id for the pause menu's Disconnect (Save and Quit to Title in single player). */
	@Unique
	private static final int DISCONNECT = 1;
	@Unique
	private static final int CONFIRM_ID = 31_102_011;
	/** Set while the confirmed Disconnect runs, so it goes through. */
	@Unique
	private static boolean flint$confirmed;

	@Inject(method = "buttonClicked", at = @At("HEAD"), cancellable = true)
	private void flint$confirmLeave(ButtonWidget button, CallbackInfo ci) {
		if (button.id != DISCONNECT || flint$confirmed || !flint$asks()) {
			return;
		}
		ci.cancel();
		Minecraft mc = Minecraft.getInstance();
		ServerListEntry server = mc.getCurrentServerEntry();
		GameMenuScreen pause = (GameMenuScreen) (Object) this;
		mc.openScreen(new ConfirmScreen((yes, id) -> {
			if (yes) {
				((GameMenuScreenMixin) (Object) pause).flint$leave(button);
			} else {
				mc.openScreen(pause);
			}
		}, "Leave the server?", server == null ? "" : server.name, CONFIRM_ID));
	}

	/** Disconnect, as the pause menu's own button does it. */
	@Unique
	private void flint$leave(ButtonWidget button) {
		flint$confirmed = true;
		try {
			buttonClicked(button);
		} catch (Exception e) {
			Minecraft.getInstance().openScreen(null);
		} finally {
			flint$confirmed = false;
		}
	}

	/** On a server (not single player or LAN hosted here) with the setting on. */
	@Unique
	private static boolean flint$asks() {
		try {
			Minecraft mc = Minecraft.getInstance();
			return !mc.isIntegratedServerRunning() && mc.getCurrentServerEntry() != null && Shared.settings().gameBool(GameOptions.LEAVE_CONFIRM);
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}
}
