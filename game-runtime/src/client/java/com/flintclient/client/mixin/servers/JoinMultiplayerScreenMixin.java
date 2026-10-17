package com.flintclient.client.mixin.servers;

import com.flintclient.client.ui.screen.QuickPlayMultiplayer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Quick Play in Minecraft's Multiplayer screen: one button in the header's right corner, "Add to Quick Play" or "Remove
 * from Quick Play" for the selected saved server (inactive for LAN games and with nothing selected). Editing a pinned
 * server moves its pin with it; deleting one unpins it. The rest of the screen is Minecraft's own, unchanged.
 */
@Mixin(JoinMultiplayerScreen.class)
public abstract class JoinMultiplayerScreenMixin extends Screen {
	@Shadow
	protected ServerSelectionList serverSelectionList;

	@Shadow
	private ServerData editingServer;

	@Unique
	private @Nullable Button flint$quickPlay;

	/** The address of the server being edited, from before the edit (null when none). */
	@Unique
	private @Nullable String flint$editedFrom;

	protected JoinMultiplayerScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void flint$addQuickPlay(CallbackInfo ci) {
		int w = QuickPlayMultiplayer.width(width, font.width(title));
		flint$quickPlay = addRenderableWidget(Button.builder(QuickPlayMultiplayer.label(null, false), button -> {
			QuickPlayMultiplayer.toggle(serverSelectionList.getSelected());
			flint$sync();
		}).bounds(QuickPlayMultiplayer.x(width, w), QuickPlayMultiplayer.Y, w, QuickPlayMultiplayer.HEIGHT).build());
		flint$sync();
	}

	@Inject(method = "repositionElements", at = @At("TAIL"))
	private void flint$placeQuickPlay(CallbackInfo ci) {
		if (flint$quickPlay != null) {
			int w = QuickPlayMultiplayer.width(width, font.width(title));
			flint$quickPlay.setWidth(w);
			flint$quickPlay.setX(QuickPlayMultiplayer.x(width, w));
			flint$sync();
		}
	}

	@Inject(method = "onSelectedChange", at = @At("TAIL"))
	private void flint$selectionChanged(CallbackInfo ci) {
		flint$sync();
	}

	@Unique
	private void flint$sync() {
		if (flint$quickPlay != null && serverSelectionList != null) {
			QuickPlayMultiplayer.sync(flint$quickPlay, serverSelectionList.getSelected());
		}
	}

	@Inject(method = "editServerCallback", at = @At("HEAD"))
	private void flint$beforeEdit(boolean result, CallbackInfo ci) {
		flint$editedFrom = result && serverSelectionList.getSelected() instanceof ServerSelectionList.OnlineServerEntry entry
			? entry.getServerData().ip : null;
	}

	@Inject(method = "editServerCallback", at = @At("TAIL"))
	private void flint$afterEdit(boolean result, CallbackInfo ci) {
		String from = flint$editedFrom;
		flint$editedFrom = null;
		if (from != null && editingServer != null) {
			QuickPlayMultiplayer.edited(from, editingServer.name, editingServer.ip);
		}
	}

	@Inject(method = "deleteCallback", at = @At("HEAD"))
	private void flint$beforeDelete(boolean result, CallbackInfo ci) {
		if (result && serverSelectionList.getSelected() instanceof ServerSelectionList.OnlineServerEntry entry) {
			QuickPlayMultiplayer.deleted(entry.getServerData().ip);
		}
	}
}
