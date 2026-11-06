package com.flintclient.client.control.feature;

import com.flintclient.client.control.store.PresenceClient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Who {@link FlintPresence} asks about, gathered once a second: the players drawn around you first (the player entities in
 * the world, nearest first), then the player list's entries, then the players the server knows but keeps out of the list.
 * Many servers draw their own player list (the TAB plugin's layouts, fake entries) or keep real players unlisted (1.19.3
 * and later can), so the list alone would leave out the players you actually see. Each player once, never you, at most
 * {@link PresenceClient#MAX_LOOKUP}.
 *
 * <p>Only Mojang accounts are asked about (version-4 UUIDs, the only ones Flint's servers can know): offline-mode players
 * (name-based UUIDs) and server NPCs would only take room. Services on this computer (development, QA's offline players)
 * are asked about anyone. Game thread; reuses its lists, so {@link #players} is valid until the next {@link #begin}.
 */
public final class PresenceTargets {
	private final List<UUID> players = new ArrayList<>();
	private final List<UUID> view = Collections.unmodifiableList(players);
	private final Set<UUID> seen = new HashSet<>();
	private final int max;
	private boolean anyId;

	public PresenceTargets() {
		this(PresenceClient.MAX_LOOKUP);
	}

	PresenceTargets(int max) {
		this.max = max;
	}

	/**
	 * Starts a new gathering: {@code anyId} asks about any UUID (services on this computer); {@code account} and
	 * {@code you} (the account the game plays as and your player in the world, which an offline-mode server gives another
	 * UUID) are never asked about.
	 */
	public void begin(boolean anyId, @Nullable UUID account, @Nullable UUID you) {
		this.anyId = anyId;
		players.clear();
		seen.clear();
		if (account != null) {
			seen.add(account);
		}
		if (you != null) {
			seen.add(you);
		}
	}

	/** One more player, in order of importance (a player already gathered, you, or one past the limit is left out). */
	public void add(@Nullable UUID player) {
		if (player == null || players.size() >= max || player.version() != 4 && !anyId || !seen.add(player)) {
			return;
		}
		players.add(player);
	}

	/** Whether the limit is reached (the rest can be skipped). */
	public boolean full() {
		return players.size() >= max;
	}

	/** The players gathered, most important first. */
	public List<UUID> players() {
		return view;
	}
}
