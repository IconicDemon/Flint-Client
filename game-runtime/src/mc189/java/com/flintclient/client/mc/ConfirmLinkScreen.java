package com.flintclient.client.mc;

import com.flintclient.client.platform.MenuHost;
import java.net.URI;

/** Opening a web address after Minecraft's link confirmation, as newer Minecraft names it (see {@link Component}; {@link MenuHost#openLink}). */
public final class ConfirmLinkScreen {
	private ConfirmLinkScreen() {
	}

	/** Asks first, over {@code parent}, which comes back after it. */
	public static void confirmLinkNow(net.minecraft.client.gui.screen.Screen parent, URI uri) {
		MenuHost.openLink(parent, uri.toString());
	}
}
