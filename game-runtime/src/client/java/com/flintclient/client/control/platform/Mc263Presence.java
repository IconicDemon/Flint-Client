package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.feature.FlintPresence;
import com.flintclient.client.control.feature.PresenceTargets;
import com.flintclient.client.control.model.GameOptions;
import com.flintclient.client.control.store.PresenceClient;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft's side of {@link FlintPresence}: once a second, the account the game plays as and the other players around
 * ({@link PresenceTargets}: the player entities in the world, nearest first, then the player list's entries, then the players
 * the server keeps out of the list), on which connection, with the "Show I'm on Flint" and "Flint icon on nametags" options,
 * then the daily rewards; and a leave when the game closes.
 * Fault-isolated (a failure turns presence off for the session, never the game).
 */
public final class Mc263Presence {
	private static final HookGuard GUARD = new HookGuard("Flint presence",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	/** Read every this many ticks: presence changes over minutes, the list over seconds. */
	private static final int EVERY = 20;
	private static final PresenceTargets TARGETS = new PresenceTargets();
	private static final List<AbstractClientPlayer> NEAR = new ArrayList<>();
	private static int ticks;
	private static boolean closing;
	/** The connection the players around are on, and the number {@link FlintPresence} knows it by (another for the next). */
	private static @Nullable ClientPacketListener connectionSeen;
	private static long server;

	private Mc263Presence() {
	}

	/** Every client tick: the full update once a second, and at once on another connection (the marks there go then). */
	public static void tick() {
		if (!GUARD.on()) {
			return;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			if (++ticks % EVERY != 0 && mc.getConnection() == connectionSeen) {
				return;
			}
			if (!closing) {
				closing = true;
				Runtime.getRuntime().addShutdownHook(new Thread(() -> FlintPresence.get().close(), "Flint presence leave"));
			}
			User user = mc.getUser();
			FlintPresence.Account account = user == null || user.getProfileId() == null ? null
				: new FlintPresence.Account(user.getProfileId(), user.getName(), user.getAccessToken());
			ClientPacketListener connection = mc.getConnection();
			if (connection != connectionSeen) {
				connectionSeen = connection;
				server = connection == null ? 0 : server + 1;
			}
			var settings = ControlState.settings();
			boolean show = settings.gameBool(GameOptions.FLINT_BADGE);
			LocalPlayer player = mc.player;
			TARGETS.begin(PresenceClient.get().asksAnyone(), account == null ? null : account.id(), player == null ? null : player.getUUID());
			if (show && connection != null) {
				gather(connection, mc.level, player);
			}
			FlintPresence.get().tick(System.nanoTime(), account, settings.gameBool(GameOptions.SHARE_PRESENCE), show, TARGETS.players(), server);
			com.flintclient.client.control.feature.DailyRewards.get().update(System.currentTimeMillis());
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	/**
	 * The players around, most important first: the ones drawn in the world (nearest first), then the player list's, then the
	 * ones the server keeps out of it (servers that draw their own list, or list fake entries, keep real players unlisted).
	 */
	private static void gather(ClientPacketListener connection, @Nullable ClientLevel level, @Nullable LocalPlayer player) {
		if (level != null && player != null) {
			NEAR.clear();
			for (AbstractClientPlayer other : level.players()) {
				if (other != player) {
					NEAR.add(other);
				}
			}
			NEAR.sort(Comparator.comparingDouble(other -> other.distanceToSqr(player)));
			for (int i = 0; i < NEAR.size() && !TARGETS.full(); i++) {
				TARGETS.add(NEAR.get(i).getUUID());
			}
			NEAR.clear();
		}
		for (PlayerInfo info : connection.getListedOnlinePlayers()) {
			TARGETS.add(info.getProfile().id());
		}
		for (PlayerInfo info : connection.getOnlinePlayers()) {
			TARGETS.add(info.getProfile().id());
		}
	}
}
