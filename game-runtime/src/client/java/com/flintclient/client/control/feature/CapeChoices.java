package com.flintclient.client.control.feature;

import com.flintclient.client.control.store.StoreCosmeticsClient;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * What you pick in Flint's Cosmetics window ({@code rshift/FlintCosmeticsScreen}), handed to the game's {@link StoreCapes} so
 * the cape is on you at once, before the Flint Store answers. Platform-free (every line's Cosmetics window and Store capes
 * meet here): the window calls in from the game's thread or its worker, and {@link StoreCapes} takes the notes on the game's
 * thread every tick.
 *
 * <ul>
 * <li>{@link #choose}: you picked a cape (its picture as the Flint app checked it: the PNG the Store hashed, decoded) or None.
 * Shown on you at once, from that picture: nothing is downloaded for it.</li>
 * <li>{@link #settled}: the Store accepted the pick (it then stays until the Store's public answer agrees, or for
 * {@link StoreCapes#CONFIRMED_HOLD_NANOS} at most) or refused it (it goes at once: what the Store last said is shown again).
 * A pick nobody settles goes after {@link StoreCapes#PENDING_HOLD_NANOS}.</li>
 * <li>{@link #seen}: a picture the app checked, kept in the game's cape cache by its SHA-256 (the cache rehashes every file it
 * reads), so the Store's answer for it later loads from disk.</li>
 * </ul>
 *
 * <p>Nothing here can make a cape appear for good: a pick only ever shows over what the Store says, for one of the account's
 * own items (the window lists only what the Store says it owns), and only until the Store has answered.
 */
public final class CapeChoices {
	/** Notes waiting beyond this are dropped (nobody takes them: Store capes are off or failed). */
	static final int MAX_NOTES = 32;

	sealed interface Note permits Chose, Settled, Seen {
	}

	/** {@code sha256} null: None (no Flint cape). {@code image}: the cape's texture, or null when it isn't known yet. */
	record Chose(UUID player, long serial, @Nullable String sha256, CapePng.@Nullable Image image) implements Note {
	}

	record Settled(UUID player, long serial, boolean accepted) implements Note {
	}

	record Seen(String sha256, byte[] png) implements Note {
	}

	private static final ConcurrentLinkedQueue<Note> NOTES = new ConcurrentLinkedQueue<>();
	private static final AtomicInteger SIZE = new AtomicInteger();
	private static final AtomicLong SERIAL = new AtomicLong();
	private static final AtomicLong LAUNCHER = new AtomicLong();

	private CapeChoices() {
	}

	/**
	 * {@code player} picked the cape whose texture hashes to {@code sha256} ({@code image}, decoded; null when it isn't known
	 * yet), or None ({@code sha256} null). Answers the pick's serial, for {@link #settled}.
	 */
	public static long choose(UUID player, @Nullable String sha256, CapePng.@Nullable Image image) {
		long serial = SERIAL.incrementAndGet();
		post(new Chose(player, serial, sha256, image));
		return serial;
	}

	/** The Store accepted ({@code accepted}) or refused the pick {@code serial}. */
	public static void settled(UUID player, long serial, boolean accepted) {
		post(new Settled(player, serial, accepted));
	}

	/**
	 * A cape texture the Flint app checked against the Store's SHA-256 ({@code png}, as the Store hashed it), kept in the game's
	 * cape cache. Answers its SHA-256. Any thread.
	 */
	public static String seen(byte[] png) {
		String sha256 = StoreCapes.sha256(png);
		if (png.length <= StoreCosmeticsClient.MAX_ASSET_BYTES) {
			post(new Seen(sha256, png));
		}
		return sha256;
	}

	/** The launcher said something was put on or taken off (its handoff changed): an open Cosmetics window reads its list again. */
	public static void launcherChanged() {
		LAUNCHER.incrementAndGet();
	}

	/** Counts {@link #launcherChanged}. */
	public static long launcherChanges() {
		return LAUNCHER.get();
	}

	private static void post(Note note) {
		if (SIZE.incrementAndGet() > MAX_NOTES) {
			// Nobody takes them: the oldest goes.
			if (NOTES.poll() != null) {
				SIZE.decrementAndGet();
			}
		}
		NOTES.add(note);
	}

	/** The next note, or null. Game thread ({@link StoreCapes}). */
	static @Nullable Note take() {
		Note note = NOTES.poll();
		if (note != null) {
			SIZE.decrementAndGet();
		}
		return note;
	}

	/** Forgets every note (tests). */
	static void clear() {
		while (take() != null) {
			// Drained.
		}
	}
}
