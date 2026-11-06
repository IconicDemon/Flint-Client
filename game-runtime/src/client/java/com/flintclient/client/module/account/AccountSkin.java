package com.flintclient.client.module.account;

import com.mojang.authlib.GameProfile;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.PlayerSkin;
import org.jspecify.annotations.Nullable;

/**
 * The signed-in player's skin, for the account pill, and the skins of Flint's other accounts, for the account card. It uses
 * the profile Minecraft already fetched at start-up (or after a switch) and Minecraft's own skin cache: no sign-in, token or
 * keychain access. Another account's profile is looked up as Minecraft looks up any player's. Until a skin has loaded (or
 * offline) Minecraft's default skin for the UUID is shown, as Minecraft does.
 *
 * <p>A lookup that fails (Mojang's session server timing out at start-up is common) is tried again, after 10 seconds and then
 * after doubling waits up to 5 minutes, while the face is still shown: Minecraft itself never retries its start-up lookup, so
 * the player's own face would otherwise stay the default skin's until they restart.
 */
public final class AccountSkin {
	private static final long FIRST_RETRY_MS = 10_000L;
	private static final long LAST_RETRY_MS = 300_000L;

	private static @Nullable Lookup own;
	private static final Map<UUID, Lookup> OTHERS = new HashMap<>();

	private AccountSkin() {
	}

	public static PlayerSkin current() {
		Minecraft minecraft = Minecraft.getInstance();
		User user = minecraft.getUser();
		UUID id = user.getProfileId();
		if (own == null || !own.id.equals(id)) {
			// getGameProfile() waits for Minecraft's start-up lookup, so it runs off the render thread. When that lookup failed
			// the profile has no textures and the answer is the default skin, which is asked again (a player who really wears
			// the default skin is asked once more, then kept).
			own = new Lookup(id, CompletableFuture.supplyAsync(minecraft::getGameProfile, Util.backgroundExecutor())
				.thenComposeAsync(profile -> skinOf(minecraft, profile), minecraft)
				.thenApply(found -> found.filter(skin -> !skin.equals(DefaultPlayerSkin.get(id)))));
		}
		return own.skin(minecraft);
	}

	/** The skin of one of Flint's accounts ({@code id} in Flint's form or Minecraft's); the game's own is {@link #current}. */
	public static PlayerSkin of(String id) {
		UUID uuid = SessionSwitch.uuid(id);
		Minecraft minecraft = Minecraft.getInstance();
		if (uuid.equals(minecraft.getUser().getProfileId())) {
			return current();
		}
		return OTHERS.computeIfAbsent(uuid, key -> new Lookup(key, fetch(minecraft, key))).skin(minecraft);
	}

	public static String username() {
		return Minecraft.getInstance().getUser().getName();
	}

	/** The profile from Mojang's session server and its skin; empty when the lookup failed. */
	private static CompletableFuture<Optional<PlayerSkin>> fetch(Minecraft minecraft, UUID id) {
		return CompletableFuture.supplyAsync(() -> com.flintclient.client.compat.McCompat.fetchProfile(minecraft, id), Util.nonCriticalIoPool())
			.thenComposeAsync(profile -> skinOf(minecraft, profile), minecraft);
	}

	private static CompletableFuture<Optional<PlayerSkin>> skinOf(Minecraft minecraft, @Nullable GameProfile profile) {
		return profile == null ? CompletableFuture.completedFuture(Optional.empty()) : minecraft.getSkinManager().get(profile);
	}

	/** One account's skin: loading, found, or waiting to be asked again. Used on the render thread only. */
	private static final class Lookup {
		final UUID id;
		private @Nullable CompletableFuture<Optional<PlayerSkin>> loading;
		private @Nullable PlayerSkin found;
		private long retryAt;
		private long wait = FIRST_RETRY_MS;

		Lookup(UUID id, CompletableFuture<Optional<PlayerSkin>> loading) {
			this.id = id;
			this.loading = loading.exceptionally(error -> Optional.empty());
		}

		PlayerSkin skin(Minecraft minecraft) {
			if (found != null) {
				return found;
			}
			if (loading != null && loading.isDone()) {
				Optional<PlayerSkin> result = loading.getNow(Optional.empty());
				loading = null;
				if (result.isPresent()) {
					found = result.get();
					return found;
				}
				retryAt = Util.getMillis() + wait;
				wait = Math.min(wait * 2, LAST_RETRY_MS);
			}
			if (loading == null && Util.getMillis() >= retryAt) {
				loading = fetch(minecraft, id).exceptionally(error -> Optional.empty());
			}
			return DefaultPlayerSkin.get(id);
		}
	}
}
