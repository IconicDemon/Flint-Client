package com.flintclient.client.module.account;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * The signed-in player's skin, for the account pill. It uses the profile Minecraft already fetched at
 * start-up and Minecraft's own skin cache: no sign-in, token or keychain access. Until the skin has
 * loaded (or when offline) Minecraft's default skin for the player's UUID is shown, as Minecraft does.
 */
public final class AccountSkin {
	private static UUID owner;
	private static CompletableFuture<Optional<PlayerSkin>> skin;

	private AccountSkin() {
	}

	public static PlayerSkin current() {
		Minecraft minecraft = Minecraft.getInstance();
		User user = minecraft.getUser();
		if (skin == null || !user.getProfileId().equals(owner)) {
			owner = user.getProfileId();
			// getGameProfile() waits for the start-up profile lookup, so it runs off the render thread.
			skin = CompletableFuture.supplyAsync(minecraft::getGameProfile, Util.backgroundExecutor())
				.thenComposeAsync(profile -> minecraft.getSkinManager().get(profile), minecraft)
				.exceptionally(error -> Optional.empty());
		}
		return skin.getNow(Optional.empty()).orElseGet(() -> DefaultPlayerSkin.get(owner));
	}

	public static String username() {
		return Minecraft.getInstance().getUser().getName();
	}
}
