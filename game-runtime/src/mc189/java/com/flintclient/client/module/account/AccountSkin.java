package com.flintclient.client.module.account;

import com.flintclient.client.platform.Faces;

/**
 * The skins the main menu's account pill and card draw, on Minecraft 1.8.9: what {@code mc.PlayerFaceExtractor} takes, "" for
 * the signed-in player's own and an account's UUID for another (Flint's {@link Faces} loads and caches them, with the retries
 * the 26.x AccountSkin makes).
 */
public final class AccountSkin {
	private AccountSkin() {
	}

	/** The signed-in player's skin. */
	public static Object current() {
		return "";
	}

	/** The skin of one of Flint's accounts ({@code id} in Flint's form or Minecraft's); the game's own is {@link #current}. */
	public static Object of(String id) {
		String hex = id.replace("-", "");
		return hex.equalsIgnoreCase(Faces.ownId()) ? "" : hex;
	}

	public static String username() {
		return Faces.ownName();
	}
}
