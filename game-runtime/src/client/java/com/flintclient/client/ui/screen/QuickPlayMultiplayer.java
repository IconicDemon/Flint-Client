package com.flintclient.client.ui.screen;

import com.flintclient.client.module.servers.QuickPlay;
import com.flintclient.client.module.servers.ServerShortcuts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The Quick Play button in Minecraft's Multiplayer screen (see {@code JoinMultiplayerScreenMixin}): where it goes, what
 * it says for the selected entry, and what pressing it does. Each change is saved at once and shows only in the button's
 * label and on the main menu (no toast); only a pin that can't be made (a full list, a server without an address) says
 * why in a short notice, and nothing is ever replaced.
 */
public final class QuickPlayMultiplayer {
	public static final int WIDTH = 124;
	/** The narrowest the button gets (with the short label) on a small screen. */
	private static final int MIN_WIDTH = 66;
	/** Below this width the button says "+ Quick Play" / "− Quick Play". */
	private static final int SHORT_BELOW = 110;
	public static final int HEIGHT = 20;
	public static final int Y = 6;
	private static final int MARGIN = 6;
	/** Flint's own notice id, so a second notice replaces the first rather than piling up. */
	private static final SystemToast.SystemToastId NOTICE = new SystemToast.SystemToastId(3000L);

	private QuickPlayMultiplayer() {
	}

	/**
	 * The button's width on a screen {@code width} GUI units wide whose title is {@code titleWidth} wide: as much of
	 * {@link #WIDTH} as fits right of the centred title, never under {@link #MIN_WIDTH}.
	 */
	public static int width(int width, int titleWidth) {
		int room = (width - titleWidth) / 2 - 2 * MARGIN;
		return Math.clamp(room, MIN_WIDTH, WIDTH);
	}

	/** The button's left edge (the header's right corner). */
	public static int x(int width, int buttonWidth) {
		return width - MARGIN - buttonWidth;
	}

	private static @Nullable ServerData saved(ServerSelectionList.@Nullable Entry entry) {
		return entry instanceof ServerSelectionList.OnlineServerEntry online ? online.getServerData() : null;
	}

	public static Component label(@Nullable ServerData server, boolean small) {
		boolean pinned = server != null && ServerShortcuts.pinned(server.ip);
		return Component.translatable(small ? (pinned ? "flintclient.quickplay.remove.short" : "flintclient.quickplay.add.short")
			: pinned ? "flintclient.quickplay.remove" : "flintclient.quickplay.add");
	}

	/** Label, state and tooltip for the selected entry: only a saved server can be pinned. */
	public static void sync(Button button, ServerSelectionList.@Nullable Entry entry) {
		ServerData server = saved(entry);
		button.setMessage(label(server, button.getWidth() < SHORT_BELOW));
		button.active = server != null;
		button.setTooltip(Tooltip.create(server == null ? Component.translatable("flintclient.quickplay.button.none")
			: Component.translatable("flintclient.quickplay.button.tooltip", QuickPlay.MAX)));
	}

	/**
	 * Pins the selected saved server, or unpins it when it is pinned. Done quietly (the button's label changes); only a pin
	 * that can't be made says why.
	 */
	public static void toggle(ServerSelectionList.@Nullable Entry entry) {
		ServerData server = saved(entry);
		if (server == null) {
			return;
		}
		if (ServerShortcuts.pinned(server.ip)) {
			ServerShortcuts.unpin(server.ip);
			return;
		}
		Component problem = switch (ServerShortcuts.pin(server.name, server.ip)) {
			case ADDED, ALREADY_PINNED -> null;
			case FULL -> Component.translatable("flintclient.quickplay.full", QuickPlay.MAX);
			case INVALID -> Component.translatable("flintclient.quickplay.invalid");
		};
		if (problem != null) {
			notice(problem);
		}
	}

	/** A saved server was edited: its pin follows it. */
	public static void edited(String oldAddress, String name, String newAddress) {
		ServerShortcuts.edited(oldAddress, name, newAddress);
	}

	/** A saved server is being deleted: it leaves Quick Play too. */
	public static void deleted(String address) {
		ServerShortcuts.unpin(address);
	}

	private static void notice(Component message) {
		Minecraft minecraft = Minecraft.getInstance();
		SystemToast.addOrUpdate(minecraft.gui.toastManager(), NOTICE, Component.translatable("flintclient.quickplay.toast.title"), message);
	}
}
