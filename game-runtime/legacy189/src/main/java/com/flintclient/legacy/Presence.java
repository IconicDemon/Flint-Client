package com.flintclient.legacy;

import com.flintclient.client.control.feature.DailyRewards;
import com.flintclient.client.control.feature.FlintPresence;
import com.flintclient.client.control.feature.PresenceTargets;
import com.flintclient.client.control.model.GameOptions;
import com.flintclient.client.control.store.PresenceClient;
import com.flintclient.legacy.shared.Shared;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Session;
import net.minecraft.client.entity.living.player.LocalClientPlayerEntity;
import net.minecraft.client.network.handler.ClientPlayNetworkHandler;
import net.minecraft.entity.living.player.PlayerEntity;

/**
 * Minecraft 1.8.9's side of {@link FlintPresence}: once a second, the account the game plays as and "Show I'm on Flint", then
 * the daily rewards (claimed in the Daily Rewards window, from the title screen); and a leave when the game closes. The players
 * around are asked about, so their Flint marks show (Marks): the player entities in the world first, nearest first, then the
 * player list's ({@link PresenceTargets}; servers with their own tab layouts leave real players out of the list). A failure
 * turns it off for the session, never the game.
 */
final class Presence {
	private static final int EVERY = 20;
	private static final PresenceTargets TARGETS = new PresenceTargets();
	private static final List<PlayerEntity> NEAR = new ArrayList<>();
	private static int ticks;
	/** The connection the players around are on, and the number FlintPresence knows it by (another for the next). */
	private static ClientPlayNetworkHandler connectionSeen;
	private static long server;
	private static boolean failed;
	private static boolean closing;

	private Presence() {
	}

	/** Every client tick: the full update once a second, and at once on another connection (the marks there go then). */
	static void tick(Minecraft mc) {
		if (failed || ++ticks % EVERY != 0 && mc.getNetworkHandler() == connectionSeen) {
			return;
		}
		try {
			if (!closing) {
				closing = true;
				Runtime.getRuntime().addShutdownHook(new Thread(() -> FlintPresence.get().close(), "Flint presence leave"));
			}
			Session session = mc.getSession();
			UUID id = session == null || session.getProfile() == null ? null : session.getProfile().getId();
			FlintPresence.Account account = id == null ? null : new FlintPresence.Account(id, session.getUsername(), session.getAccessToken());
			ClientPlayNetworkHandler connection = mc.getNetworkHandler();
			if (connection != connectionSeen) {
				connectionSeen = connection;
				server = connection == null ? 0 : server + 1;
			}
			boolean show = Shared.settings().gameBool(GameOptions.FLINT_BADGE);
			LocalClientPlayerEntity player = mc.player;
			TARGETS.begin(PresenceClient.get().asksAnyone(), id, player == null ? null : player.getUuid());
			if (show && connection != null) {
				if (mc.world != null && player != null) {
					NEAR.clear();
					for (PlayerEntity other : mc.world.players) {
						if (other != player) {
							NEAR.add(other);
						}
					}
					NEAR.sort(Comparator.comparingDouble(other -> other.squaredDistanceTo(player)));
					for (int i = 0; i < NEAR.size() && !TARGETS.full(); i++) {
						TARGETS.add(NEAR.get(i).getUuid());
					}
					NEAR.clear();
				}
				for (net.minecraft.client.network.PlayerInfo info : connection.getOnlinePlayers()) {
					TARGETS.add(info.getProfile() == null ? null : info.getProfile().getId());
				}
			}
			FlintPresence.get().tick(System.nanoTime(), account, Shared.settings().gameBool(GameOptions.SHARE_PRESENCE), show, TARGETS.players(), server);
			DailyRewards.get().update(System.currentTimeMillis());
		} catch (RuntimeException | LinkageError e) {
			failed = true;
			Flint.LOG.warn("Flint: presence and daily rewards failed; they're off for this session", e);
		}
	}
}
