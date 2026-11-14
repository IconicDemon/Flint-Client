package com.flintclient.legacy.mixin;

import com.flintclient.client.mc.Component;
import com.flintclient.client.module.servers.QuickPlay;
import com.flintclient.client.module.servers.ServerShortcuts;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.menu.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.menu.multiplayer.MultiplayerServerListWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ServerListEntryWidget;
import net.minecraft.client.options.ServerListEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The Quick Play button in Minecraft 1.8.9's Multiplayer screen, as on every line ({@code QuickPlayMultiplayer} on 26.x): at the
 * header's right corner, it pins the selected saved server to Flint's main menu (its left rail), or unpins it. Its label follows
 * the selection; a pin that can't be made says why on the button for a moment (1.8.9 has no notices).
 */
@Mixin(MultiplayerScreen.class)
public abstract class MultiplayerScreenMixin extends Screen {
	@Unique
	private static final int QUICK_PLAY = 7_406;
	@Unique
	private static final int WIDTH = 124;
	@Unique
	private static final int MIN_WIDTH = 66;
	@Unique
	private static final int SHORT_BELOW = 110;
	@Unique
	private static final int MARGIN = 6;
	@Unique
	private static final long PROBLEM_MILLIS = 2500;

	@Shadow
	private MultiplayerServerListWidget serverList;

	@Unique
	private ButtonWidget flint$quickPlay;
	@Unique
	private String flint$problem;
	@Unique
	private long flint$problemAt;

	@Inject(method = "init", at = @At("TAIL"))
	private void flint$addQuickPlay(CallbackInfo ci) {
		// As much as fits right of the centred title (1.8.9's "Play Multiplayer"), never under the short label's width.
		int title = textRenderer == null ? 100 : textRenderer.getWidth(net.minecraft.client.resource.language.I18n.translate("multiplayer.title"));
		int w = Math.max(MIN_WIDTH, Math.min(WIDTH, (width - title) / 2 - 2 * MARGIN));
		flint$quickPlay = new ButtonWidget(QUICK_PLAY, width - MARGIN - w, 6, w, 20, "");
		buttons.add(flint$quickPlay);
		flint$sync();
	}

	@Inject(method = "tick", at = @At("TAIL"))
	private void flint$syncQuickPlay(CallbackInfo ci) {
		flint$sync();
	}

	@Inject(method = "buttonClicked", at = @At("HEAD"), cancellable = true)
	private void flint$toggleQuickPlay(ButtonWidget button, CallbackInfo ci) {
		if (button.id != QUICK_PLAY) {
			return;
		}
		ci.cancel();
		ServerListEntry server = flint$selected();
		if (server == null) {
			return;
		}
		if (ServerShortcuts.pinned(server.ip)) {
			ServerShortcuts.unpin(server.ip);
		} else {
			flint$problem = switch (ServerShortcuts.pin(server.name, server.ip)) {
				case ADDED, ALREADY_PINNED -> null;
				case FULL -> Component.translatable("flintclient.quickplay.full", QuickPlay.MAX).getString();
				case INVALID -> Component.translatable("flintclient.quickplay.invalid").getString();
			};
			flint$problemAt = System.currentTimeMillis();
		}
		flint$sync();
	}

	/** The selected saved server (not a LAN game), or null. */
	@Unique
	private ServerListEntry flint$selected() {
		if (serverList == null) {
			return null;
		}
		int at = serverList.getCurrentServerIndex();
		if (at < 0) {
			return null;
		}
		try {
			return serverList.getEntry(at) instanceof ServerListEntryWidget saved ? saved.fetchServer() : null;
		} catch (IndexOutOfBoundsException e) {
			return null;
		}
	}

	/** Label and state for the selected entry: only a saved server can be pinned. */
	@Unique
	private void flint$sync() {
		ButtonWidget button = flint$quickPlay;
		if (button == null) {
			return;
		}
		ServerListEntry server = flint$selected();
		button.active = server != null;
		if (flint$problem != null && System.currentTimeMillis() - flint$problemAt < PROBLEM_MILLIS) {
			button.message = flint$problem;
			return;
		}
		flint$problem = null;
		boolean pinned = server != null && ServerShortcuts.pinned(server.ip);
		boolean small = button.getWidth() < SHORT_BELOW;
		button.message = Component.translatable(small ? (pinned ? "flintclient.quickplay.remove.short" : "flintclient.quickplay.add.short")
			: pinned ? "flintclient.quickplay.remove" : "flintclient.quickplay.add").getString();
	}
}
