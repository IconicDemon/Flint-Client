package com.flintclient.client.module.account;

import com.flintclient.client.FlintClient;
import com.flintclient.legacy.mixin.MinecraftSessionAccessor;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Session;

/**
 * Switches the running game to another of Flint's accounts without a restart, on Minecraft 1.8.9: its {@code Session} (name,
 * UUID, token) is what joining a server signs in with and what the menu shows, and 1.8.9 builds nothing else from it at
 * start-up that newer Minecraft rebuilds (no chat signing, telemetry, reporting or Realms data). Only outside a world, on the
 * render thread.
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
		if (!minecraft.isOnSameThread()) {
			throw new IllegalStateException("account switches run on the render thread");
		}
		if (minecraft.world != null || minecraft.getNetworkHandler() != null) {
			throw new SwitchException("Leave the world first, then switch accounts.", null);
		}
		UUID id = uuid(session.uuid());
		String token = session.offline() ? "0" : session.accessToken();
		Session next = new Session(session.username(), id.toString().replace("-", ""), token, session.offline() ? "legacy" : "mojang");
		((MinecraftSessionAccessor) minecraft).flint$setSession(next);
		FlintClient.LOGGER.info("Flint: switched account to {}", next.getUsername());
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
