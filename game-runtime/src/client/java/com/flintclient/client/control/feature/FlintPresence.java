package com.flintclient.client.control.feature;

import com.flintclient.client.control.store.PresenceClient;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * Who around you is on Flint: proves your account to Flint's servers ({@link PresenceClient#announce}, then a heartbeat every
 * {@link #HEARTBEAT_NANOS}) and asks which of the players around you are on Flint ({@link PresenceTargets}: the players in the
 * world, nearest first, then the player list's), so {@link FlintPlayers} knows them and their Flint mark shows on nametags and
 * in the player list. "Show I'm on Flint" decides whether others may see you; the proof is kept either way, since
 * {@link DailyRewards} claims with its {@link #token}. Asking about others follows the mark being shown at all. Leaves when the
 * account changes or the game closes.
 *
 * <p>Ticked on the game's thread with the account, the players around now and the server they're on; requests run elsewhere
 * and their answers are picked up on the next tick, so {@link FlintPlayers} is only touched here. One request of each kind at
 * a time; failures back off (doubling from {@link #RETRY_NANOS} to {@link #MAX_RETRY_NANOS}); an account Mojang won't confirm
 * (an offline one) isn't asked again this session. The players around are asked about every {@link #LOOKUP_NANOS}, and
 * {@link #LOOKUP_MIN_NANOS} after the last lookup when someone new is around (not while lookups fail); on another server,
 * whoever was known goes at once and an answer still on its way for the last one is dropped. Pure Java.
 */
public final class FlintPresence {
	static final long HEARTBEAT_NANOS = TimeUnit.MINUTES.toNanos(5);
	/** The player list is asked about this often, and soon after it changes (but not more than every {@link #LOOKUP_MIN_NANOS}). */
	static final long LOOKUP_NANOS = TimeUnit.SECONDS.toNanos(60);
	static final long LOOKUP_MIN_NANOS = TimeUnit.SECONDS.toNanos(5);
	static final long RETRY_NANOS = TimeUnit.MINUTES.toNanos(1);
	static final long MAX_RETRY_NANOS = TimeUnit.MINUTES.toNanos(30);

	/** The account the game plays as: its id, name and Minecraft access token (handed to Mojang only). */
	public record Account(UUID id, String name, String accessToken) {
		@Override
		public String toString() {
			return "Account[" + id + ", " + name + "]";
		}
	}

	/** Where the proof of your account stands, for what needs it (daily rewards). */
	public enum Proof {
		/** This Flint was built without the services. */
		NOT_CONFIGURED,
		/** No account yet, or the proof is on its way. */
		WAITING,
		/** Mojang won't confirm this account (an offline one). */
		REFUSED,
		/** Flint's servers couldn't be reached for now (retried). */
		OFFLINE,
		/** Proven: {@link #token} is live. */
		PROVEN
	}

	private final PresenceClient client;
	// ---------- game thread ----------
	private @Nullable Account account;
	private @Nullable String token;
	private boolean refused;
	private boolean offline;
	private boolean visible = true;
	/** {@link #visible} changed since the server was last told. */
	private boolean visibilityChanged;
	private long nextAnnounce;
	private long nextBeat;
	private long nextLookup;
	private long lastLookup = Long.MIN_VALUE / 2;
	private long announceRetry = RETRY_NANOS;
	private long lookupRetry = RETRY_NANOS;
	/** Whether the last lookup failed (lookups then wait for their back-off, whoever comes). */
	private boolean lookupFailing;
	/** Who the last lookup asked about: someone else around is asked about soon. */
	private final Set<UUID> asked = new HashSet<>();
	/** The server the players around are on (the platform's number for its connection; 0: none). */
	private long server;
	/** Counts the servers: an answer asked for on an earlier one is never applied. */
	private int session;
	private boolean known;
	private boolean announcing;
	private boolean beating;
	private boolean looking;
	// ---------- answers, from the request threads ----------
	private volatile @Nullable For<PresenceClient.Announced> announced;
	private volatile @Nullable For<PresenceClient.Beat> beat;
	private volatile @Nullable Seen found;

	/** An answer with the account it was asked for: one for an account that isn't playing any more is never applied. */
	private record For<T>(UUID player, T answer) {
	}

	/** A lookup's answer with the server it was asked on ({@link #session}): one for an earlier server is never applied. */
	private record Seen(int session, PresenceClient.Found answer) {
	}

	public FlintPresence(PresenceClient client) {
		this.client = client;
	}

	private static volatile @Nullable FlintPresence shared;

	/** The presence for this game's services (made on first use). */
	public static FlintPresence get() {
		FlintPresence presence = shared;
		if (presence == null) {
			presence = new FlintPresence(PresenceClient.get());
			shared = presence;
		}
		return presence;
	}

	/** {@link #tick(long, Account, boolean, boolean, List, long)} with the players on one server (none: no server). */
	public void tick(long now, @Nullable Account playing, boolean share, boolean show, List<UUID> players) {
		tick(now, playing, share, show, players, players.isEmpty() ? 0 : 1);
	}

	/**
	 * One game tick: {@code now} from {@link System#nanoTime}; {@code playing} the account (null while there is none);
	 * {@code share} whether other Flint players may see you; {@code show} whether marks are shown (else nobody is asked
	 * about); {@code players} the other players around now, most important first ({@link PresenceTargets}; none outside a
	 * world); {@code server} which server they're on (any number the platform gives its connection, another for another
	 * server; 0 outside one).
	 */
	public void tick(long now, @Nullable Account playing, boolean share, boolean show, List<UUID> players, long server) {
		if (!client.configured()) {
			return;
		}
		if (server != this.server) {
			// Another server (or none): whoever was known there may not be here, and an answer on its way is for there.
			this.server = server;
			session++;
			forget();
		}
		take(now);
		if (playing == null || account == null || !playing.id().equals(account.id())) {
			leave();
			account = playing;
			// The previous account's mark never shows on this one: the default until Flint says this account's.
			FlintPlayers.setOwn(FlintPlayers.Tag.DEFAULT);
			refused = false;
			offline = false;
			announceRetry = RETRY_NANOS;
			nextAnnounce = now;
		}
		if (share != visible) {
			// Turned on or off: told at once (with a heartbeat, or the announce).
			visible = share;
			visibilityChanged = true;
		}
		if (playing == null) {
			// Nothing to prove.
		} else if (token == null) {
			if (!announcing && !refused && now - nextAnnounce >= 0) {
				announcing = true;
				visibilityChanged = false;
				UUID id = playing.id();
				client.announce(id, playing.name(), playing.accessToken(), visible).thenAccept(a -> announced = new For<>(id, a));
			}
		} else if (!beating && (visibilityChanged || now - nextBeat >= 0)) {
			beating = true;
			visibilityChanged = false;
			UUID id = playing.id();
			client.heartbeat(id, token, visible).thenAccept(b -> beat = new For<>(id, b));
		}
		lookup(now, show, players);
	}

	/** The live proof of your account (null until Flint's servers confirmed it). */
	public @Nullable String token() {
		return token;
	}

	/** The account {@link #token} proves. */
	public @Nullable Account account() {
		return account;
	}

	public Proof proof() {
		if (!client.configured()) {
			return Proof.NOT_CONFIGURED;
		}
		if (token != null) {
			return Proof.PROVEN;
		}
		return refused ? Proof.REFUSED : offline ? Proof.OFFLINE : Proof.WAITING;
	}

	/** The token was refused (it expired): proven again at once. */
	public void tokenExpired() {
		token = null;
		nextAnnounce = Math.min(nextAnnounce, System.nanoTime());
	}

	/**
	 * Asks about the players around: every {@link #LOOKUP_NANOS}, and {@link #LOOKUP_MIN_NANOS} after the last lookup when
	 * someone it didn't ask about is around (no lookup for players leaving: a player who isn't drawn shows no mark).
	 */
	private void lookup(long now, boolean show, List<UUID> players) {
		if (!show || players.isEmpty()) {
			forget();
			return;
		}
		if (looking) {
			return;
		}
		boolean due = now - nextLookup >= 0;
		if (!due && (lookupFailing || now - lastLookup < LOOKUP_MIN_NANOS || asked.containsAll(players))) {
			return;
		}
		looking = true;
		asked.clear();
		asked.addAll(players);
		lastLookup = now;
		int asking = session;
		client.lookup(new ArrayList<>(players)).thenAccept(f -> found = new Seen(asking, f));
	}

	/** Nobody around is known any more (another server or none, or marks turned off). */
	private void forget() {
		if (known) {
			FlintPlayers.clear();
			known = false;
		}
		asked.clear();
	}

	/** Whether {@code player} is the account playing now. */
	private boolean playing(UUID player) {
		return account != null && account.id().equals(player);
	}

	/** Picks up the answers that came in since the last tick. */
	private void take(long now) {
		For<PresenceClient.Announced> forA = announced;
		if (forA != null && !playing(forA.player())) {
			// Asked for an account that has gone since: dropped, and that account leaves (best effort) if it got in.
			announced = null;
			announcing = false;
			PresenceClient.Announced a = forA.answer();
			if (a.status() == PresenceClient.Status.OK && a.token() != null) {
				client.leave(forA.player(), a.token());
			}
		} else if (forA != null) {
			PresenceClient.Announced a = forA.answer();
			announced = null;
			announcing = false;
			offline = false;
			switch (a.status()) {
				case OK -> {
					token = a.token();
					nextBeat = now + HEARTBEAT_NANOS;
					announceRetry = RETRY_NANOS;
					FlintPlayers.setOwn(tag(a.tag()));
				}
				// Mojang won't confirm this account (an offline one): not asked again until the account changes.
				case UNVERIFIED -> refused = true;
				default -> {
					offline = true;
					nextAnnounce = now + announceRetry;
					announceRetry = Math.min(announceRetry * 2, MAX_RETRY_NANOS);
				}
			}
		}
		For<PresenceClient.Beat> forB = beat;
		if (forB != null) {
			beat = null;
			beating = false;
			PresenceClient.Beat b = forB.answer();
			if (!playing(forB.player())) {
				// For an account that has gone since: nothing of it applies to the one playing now.
			} else if (b.status() == PresenceClient.Status.EXPIRED) {
				token = null;
				nextAnnounce = now;
			} else {
				nextBeat = now + (b.status() == PresenceClient.Status.OK ? HEARTBEAT_NANOS : RETRY_NANOS);
				if (b.status() == PresenceClient.Status.OK && b.tag() != null) {
					// The mark Flint gives this account now: a new or removed one shows within a heartbeat.
					FlintPlayers.setOwn(tag(b.tag()));
				}
			}
		}
		Seen seen = found;
		if (seen != null) {
			found = null;
			looking = false;
			PresenceClient.Found f = seen.answer();
			if (seen.session() != session) {
				// Asked on the last server: nothing of it applies here (the players here are asked about next).
			} else if (f.status() == PresenceClient.Status.OK) {
				FlintPlayers.clear();
				for (Map.Entry<UUID, String> player : f.players().entrySet()) {
					FlintPlayers.set(player.getKey(), tag(player.getValue()));
				}
				known = !f.players().isEmpty();
				lookupRetry = RETRY_NANOS;
				lookupFailing = false;
				nextLookup = now + LOOKUP_NANOS;
			} else {
				// What was known stays shown meanwhile; a player gone is forgotten at the next answer.
				lookupFailing = true;
				nextLookup = now + lookupRetry;
				lookupRetry = Math.min(lookupRetry * 2, MAX_RETRY_NANOS);
			}
		}
	}

	/** Off Flint: the server forgets you at once (best effort), and proving starts over for the next account. */
	private void leave() {
		if (token != null && account != null) {
			client.leave(account.id(), token);
		}
		token = null;
	}

	/** When the game closes: leaves, waiting up to two seconds for the server to hear it. */
	public void close() {
		String t = token;
		Account a = account;
		token = null;
		if (t != null && a != null) {
			try {
				client.leave(a.id(), t).get(2, TimeUnit.SECONDS);
			} catch (Exception ignored) {
				// The server forgets a silent player on its own.
			}
		}
	}

	/** A mark's name from the server ("staff", "content_creator"); an unknown one is the default mark. */
	static FlintPlayers.Tag tag(String name) {
		for (FlintPlayers.Tag t : FlintPlayers.Tag.values()) {
			if (t.name().equalsIgnoreCase(name)) {
				return t;
			}
		}
		return FlintPlayers.Tag.DEFAULT;
	}
}
