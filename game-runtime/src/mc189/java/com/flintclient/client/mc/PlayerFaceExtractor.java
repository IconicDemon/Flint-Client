package com.flintclient.client.mc;

import com.flintclient.client.platform.Faces;
import com.flintclient.client.platform.Frame;

/**
 * A player's face from their skin (with its hat layer), as newer Minecraft draws it, for Flint's main menu on Minecraft 1.8.9
 * (see {@link Component}): through Flint's {@link Faces}. The "skin" is what 1.8.9's {@code module.account.AccountSkin} hands
 * out: "" for the player's own, else the account's UUID.
 */
public final class PlayerFaceExtractor {
	private PlayerFaceExtractor() {
	}

	public static void extractRenderState(GuiGraphicsExtractor graphics, Object skin, int x, int y, int size) {
		String uuid = skin instanceof String s ? s : "";
		if (uuid.isEmpty()) {
			Faces.own(Frame.get(), x, y, size);
		} else {
			Faces.of(Frame.get(), uuid, x, y, size);
		}
	}
}
