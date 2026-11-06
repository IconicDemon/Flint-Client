package com.flintclient.client.module.account;

import com.flintclient.client.FlintClient;
import com.flintclient.client.compat.McCompat;
import com.flintclient.client.mixin.MinecraftSessionAccessor;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.realmsclient.client.RealmsClient;
import com.mojang.realmsclient.gui.RealmsDataFetcher;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.multiplayer.chat.report.ReportEnvironment;
import net.minecraft.client.multiplayer.chat.report.ReportingContext;
import net.minecraft.client.telemetry.ClientTelemetryManager;
import net.minecraft.util.Util;

/**
 * Switches the running game to another account without a restart, the way In-Game Account Switcher-style mods do: Minecraft's
 * {@code User} and everything Minecraft made from it at start-up are made again for the new session, as Minecraft makes them.
 *
 * <ul>
 * <li>the user (name, UUID, token; the launch's xuid and client id are kept) and its profile lookup (name, skin, name-ban
 * check);</li>
 * <li>the user API service and its properties (chat and multiplayer permissions, bans);</li>
 * <li>the social manager (blocked and hidden players) and, from 26.2, the friends list and presence;</li>
 * <li>telemetry, the chat signing keys ({@code ProfileKeyPairManager}), the reporting context and Realms' data fetcher.</li>
 * </ul>
 *
 * <p>Only outside a world, on the render thread. Everything that can fail (the user API service needs Mojang's answer for a
 * real token) is made before anything is replaced, so a failure leaves the game on its current account. Server resource
 * pack downloads keep naming the account the game started with until the next start (Minecraft builds that once).
 */
public final class SessionSwitch {
	private SessionSwitch() {
	}

	/** Thrown when the game can't switch now; its message is for the player. */
	public static final class SwitchException extends Exception {
		public SwitchException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	public static void apply(LauncherLink.Session session) throws SwitchException {
		Minecraft minecraft = Minecraft.getInstance();
		if (!minecraft.isSameThread()) {
			throw new IllegalStateException("account switches run on the render thread");
		}
		if (minecraft.level != null || minecraft.getConnection() != null) {
			throw new SwitchException("Leave the world first, then switch accounts.", null);
		}
		User old = minecraft.getUser();
		UUID id = uuid(session.uuid());
		boolean offline = session.offline();
		String token = session.accessToken();
		UserApiService api;
		try {
			api = offline ? UserApiService.OFFLINE : McCompat.userApiService(minecraft, token);
		} catch (Exception e) {
			throw new SwitchException("Minecraft's services didn't accept this account's sign-in. Try again in a moment.", e);
		}
		User user = McCompat.newUser(session.username(), id, token, old.getXuid(), old.getClientId());

		MinecraftSessionAccessor game = (MinecraftSessionAccessor) minecraft;
		game.flint$setUser(user);
		game.flint$setUserApiService(api);
		game.flint$setProfileFuture(McCompat.profileFuture(minecraft, id, offline));
		game.flint$setUserPropertiesFuture(CompletableFuture.supplyAsync(() -> {
			try {
				return api.fetchProperties();
			} catch (Exception e) {
				return UserApiService.OFFLINE_PROPERTIES;
			}
		}, Util.nonCriticalIoPool()));
		McCompat.replaceSocial(minecraft, api, token, offline);
		ClientTelemetryManager telemetry = game.flint$telemetryManager();
		game.flint$setTelemetryManager(new ClientTelemetryManager(minecraft, api, user));
		try {
			telemetry.close();
		} catch (RuntimeException e) {
			// The old session's telemetry just stops.
		}
		game.flint$setProfileKeyPairManager(offline ? ProfileKeyPairManager.EMPTY_KEY_MANAGER
			: ProfileKeyPairManager.create(api, user, minecraft.gameDirectory.toPath()));
		game.flint$setReportingContext(ReportingContext.create(ReportEnvironment.local(), api));
		game.flint$setRealmsDataFetcher(new RealmsDataFetcher(McCompat.realmsClient(minecraft)));
		FlintClient.LOGGER.info("Flint: switched account to {}", user.getName());
	}

	/** Realms keeps one client per run (from 1.21.10) with the user it was made for: dropped, so the next one is the new user's. */
	public static void forgetRealmsClient() {
		for (Field field : RealmsClient.class.getDeclaredFields()) {
			if (Modifier.isStatic(field.getModifiers()) && field.getType() == RealmsClient.class) {
				try {
					field.setAccessible(true);
					field.set(null, null);
				} catch (ReflectiveOperationException | RuntimeException e) {
					FlintClient.LOGGER.warn("Flint: Realms keeps the previous account until the next start", e);
				}
			}
		}
	}

	/** A UUID from Flint's form (32 hex digits) or Minecraft's (with dashes). */
	static UUID uuid(String text) {
		String hex = text.replace("-", "");
		if (hex.length() != 32) {
			throw new IllegalArgumentException("not a UUID");
		}
		return new UUID(Long.parseUnsignedLong(hex.substring(0, 16), 16), Long.parseUnsignedLong(hex.substring(16), 16));
	}
}
