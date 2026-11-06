package com.flintclient.client.control.feature;

import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.MoreMods.Subtitles;

/**
 * Subtitles' Nearby Sound Activity: the sounds heard lately, kept as the rows of the bottom-right list, oldest at the
 * bottom (the list grows upward as sounds arrive), each with its caption, the place its arrow points to and when it was
 * last heard.
 *
 * <p>Event-driven: each Minecraft line's sound hook hands every sound with a caption to {@link #heard} once, as the game
 * plays it (26.x and 1.21.x listen to the sound engine as Minecraft's own subtitles do; 1.8.9, which has no subtitles, maps
 * its sound names to captions). The list is bounded ({@link #CAPACITY} rows, pooled: nothing is allocated once the pool is
 * made) and old rows go by {@link #expire}. A sound that repeats refreshes its row instead of adding one: within a few
 * blocks of where the row began (Same place), by its caption alone (Same caption), or never (Off).
 *
 * <p>Same place keeps each row anchored where its first sound was: a repeat joins it only near that anchor, and the arrow
 * points at the anchor. So repeats can't chain a row along a line or a ring of the same sound (thirty cows around you are a
 * row per stretch of the ring, each with an arrow that stays put), and a row's arrow moves only as you turn. Of the two ways
 * to keep the arrow still (the anchor, or the latest repeat near it) the anchor reads calmer: the latest repeat still hops
 * around within the anchor's few blocks. Same caption, one row per caption wherever the sound is, points at the latest. The
 * way to each sound is worked out per frame from where the camera is and which way it faces ({@link #direction}).
 *
 * <p>Pure Java, so every line runs this same code; render thread only (the sound engine plays and the HUD draws there).
 */
public final class SoundActivity {
	/** The rows the list can hold. */
	public static final int CAPACITY = Subtitles.MOST_ENTRIES;

	// What kind of sound it is, for the Sound categories switches.
	public static final int AMBIENT = 0;
	public static final int BLOCKS = 1;
	public static final int ENTITIES = 2;
	public static final int COMBAT = 3;
	public static final int PLAYERS = 4;
	/** Music, the interface, voices: no switch leaves these out. */
	public static final int OTHER = 5;

	// Its colour under Subtitles' Color (Minecraft's sound categories), or none.
	public static final int NO_KIND = -1;
	public static final int KIND_MUSIC = 0;
	public static final int KIND_RECORDS = 1;
	public static final int KIND_WEATHER = 2;
	public static final int KIND_BLOCKS = 3;
	public static final int KIND_HOSTILE = 4;
	public static final int KIND_NEUTRAL = 5;
	public static final int KIND_PLAYERS = 6;
	public static final int KIND_AMBIENT = 7;
	public static final int KIND_VOICE = 8;

	// The way to a sound from where you look.
	/** No position to point at (music, the interface, a sound played at the listener). */
	public static final int NONE = 0;
	/** Right where you are: no arrow. */
	public static final int NEAR = 1;
	public static final int FRONT = 2;
	public static final int RIGHT = 3;
	public static final int BEHIND = 4;
	public static final int LEFT = 5;

	/** Duplicate-event grouping's choices, in {@link Subtitles#GROUPING}'s order. */
	public static final int SAME_PLACE = 0;
	public static final int SAME_CAPTION = 1;
	public static final int NO_GROUPING = 2;

	/** How close a repeat must be to where a row began (its anchor) to count as the same place, in blocks. */
	static final double PLACE_BLOCKS = 6;
	/** The most a row's count shows: more reads "×99+". */
	public static final int MOST_COUNT = 99;
	/** "×2" to "×99", then "×99+": made once, so a count changing never allocates. */
	private static final String[] COUNT_LABELS = countLabels();
	/** Closer than this (horizontally) a sound is where you are; a little farther once it is, so the arrow never flickers. */
	static final double NEAR_BLOCKS = 1.25;
	static final double NEAR_LEAVE_BLOCKS = 1.75;
	/** Within this many degrees of straight ahead a sound is in front (no arrow), within it of straight back behind... */
	static final double FRONT_DEGREES = 32;
	static final double BEHIND_DEGREES = 148;
	/** ...and a direction is kept until the sound is this many degrees past the edge (no flicker when you turn slowly). */
	static final double HYSTERESIS_DEGREES = 8;

	private static final SoundActivity INSTANCE = new SoundActivity();
	private static final int MODULE = Mods.index(Subtitles.ID);

	/** One row: what it says and where its arrow points. The view keeps what it draws with in {@link #view}. */
	public static final class Entry {
		String text = "";
		int group;
		int kind = NO_KIND;
		boolean positioned;
		/** Where the arrow points: the row's anchor, where its first sound was (Same caption: where its latest was). */
		double x;
		double y;
		double z;
		/** When the row came, and when its sound was last heard (System.nanoTime). */
		long first;
		long last;
		/** How many times its sound was heard while the row showed. */
		int count;
		/** The last direction drawn ({@link #direction}'s hysteresis), and the row's place as drawn (eased). */
		public int shownDirection = NONE;
		public float shownRow = Float.NaN;
		/** Anything the view keeps with the row (its text, ready to draw). */
		public Object view;

		public String text() {
			return text;
		}

		public int group() {
			return group;
		}

		public int kind() {
			return kind;
		}

		public boolean positioned() {
			return positioned;
		}

		public double x() {
			return x;
		}

		public double y() {
			return y;
		}

		public double z() {
			return z;
		}

		public long first() {
			return first;
		}

		public long last() {
			return last;
		}

		public int count() {
			return count;
		}
	}

	/** Rows in order of arrival, oldest first; [count, CAPACITY) are spare. */
	private final Entry[] rows = new Entry[CAPACITY];
	private int count;
	/** Sounds handed in since the start (for the view's caches and the tests). */
	private long heard;

	SoundActivity() {
		for (int i = 0; i < CAPACITY; i++) {
			rows[i] = new Entry();
		}
	}

	public static SoundActivity get() {
		return INSTANCE;
	}

	/** Subtitles' state on the profile in use. */
	public static ModuleState state() {
		return Mods.state(MODULE);
	}

	/** Whether the list is on: Subtitles on, with Nearby Sound Activity. A field read or two. */
	public static boolean on() {
		return Mods.on(MODULE) && Mods.state(MODULE).bool(Subtitles.ACTIVITY);
	}

	/**
	 * Whether a sound of {@code group}, {@code distance} blocks away (NaN: no position) and audible up to {@code range} blocks
	 * as Minecraft plays it, is shown with {@code s}'s settings; {@code own}: one the player made.
	 */
	public static boolean wanted(ModuleState s, int group, double distance, double range, boolean own) {
		boolean included = switch (group) {
			case AMBIENT -> s.bool(Subtitles.INCLUDE_AMBIENT);
			case BLOCKS -> s.bool(Subtitles.INCLUDE_BLOCKS);
			case ENTITIES -> s.bool(Subtitles.INCLUDE_ENTITIES);
			case COMBAT -> s.bool(Subtitles.INCLUDE_COMBAT);
			case PLAYERS -> s.bool(Subtitles.INCLUDE_PLAYERS);
			default -> true;
		};
		if (!included || own && !s.bool(Subtitles.OWN_SOUNDS)) {
			return false;
		}
		return Double.isNaN(distance) || distance <= s.integer(Subtitles.MAX_DISTANCE) && !(distance > range);
	}

	/** How long a row lasts after its sound was last heard with {@code s}'s settings (shown, then fading), in nanoseconds. */
	public static long lifeNanos(ModuleState s) {
		return shownNanos(s) + millis(s.decimal(Subtitles.FADE)) * 1_000_000L;
	}

	/** How long a row shows at full strength after its sound was last heard (Display duration), in nanoseconds. */
	public static long shownNanos(ModuleState s) {
		return millis(s.decimal(Subtitles.DURATION)) * 1_000_000L;
	}

	/** Seconds as a whole number of milliseconds (the settings step by tenths: 0.6 is 600, not 599.99997). */
	private static long millis(float seconds) {
		return Math.round(seconds * 1000.0);
	}

	/** A sound with a caption was heard: {@link #heard(String, int, int, boolean, double, double, double, long, int, int, long)} with {@code s}'s settings. */
	public void heard(ModuleState s, String text, int group, int kind, boolean positioned, double x, double y, double z, long now) {
		heard(text, group, kind, positioned, x, y, z, now, s.option(Subtitles.GROUPING), s.integer(Subtitles.MAX_ENTRIES), lifeNanos(s));
	}

	/**
	 * A sound captioned {@code text} was heard at ({@code x}, {@code y}, {@code z}) (when {@code positioned}). A repeat of a
	 * row's sound (as {@code grouping} says: within {@link #PLACE_BLOCKS} of its anchor, or by caption) refreshes that row: it
	 * is heard again now and counted, and keeps its place in the list and its arrow (Same caption's arrow moves to the repeat).
	 * Otherwise a new row comes on top, and when {@code max} rows already show, the one heard longest ago goes. Allocation-free.
	 */
	public void heard(String text, int group, int kind, boolean positioned, double x, double y, double z, long now, int grouping, int max,
		long lifeNanos) {
		heard++;
		expire(now, lifeNanos);
		Entry same = grouping == NO_GROUPING ? null : repeatOf(text, positioned, x, y, z, grouping);
		if (same != null) {
			same.last = now;
			same.count++;
			same.group = group;
			same.kind = kind;
			if (grouping == SAME_CAPTION) {
				// One row per caption wherever it is: it points at the latest. Same place keeps its anchor (see the class).
				same.positioned = positioned;
				same.x = x;
				same.y = y;
				same.z = z;
			}
			return;
		}
		int limit = Math.clamp(max, 1, CAPACITY);
		while (count >= limit) {
			remove(stalest());
		}
		Entry e = rows[count++];
		e.text = text;
		e.group = group;
		e.kind = kind;
		e.positioned = positioned;
		e.x = x;
		e.y = y;
		e.z = z;
		e.first = now;
		e.last = now;
		e.count = 1;
		e.shownDirection = NONE;
		e.shownRow = Float.NaN;
	}

	/** The row a sound captioned {@code text} at that place repeats (Same place: the nearest anchor within reach), or null. */
	private Entry repeatOf(String text, boolean positioned, double x, double y, double z, int grouping) {
		Entry best = null;
		double bestDistance = Double.POSITIVE_INFINITY;
		for (int i = 0; i < count; i++) {
			Entry e = rows[i];
			if (!e.text.equals(text)) {
				continue;
			}
			if (grouping == SAME_CAPTION) {
				return e;
			}
			if (positioned != e.positioned) {
				continue;
			}
			double d = positioned ? distance(e.x, e.y, e.z, x, y, z) : 0;
			if (d <= PLACE_BLOCKS && d < bestDistance) {
				best = e;
				bestDistance = d;
			}
		}
		return best;
	}

	/** The index of the row heard longest ago (the first of equals). */
	private int stalest() {
		int at = 0;
		for (int i = 1; i < count; i++) {
			if (rows[i].last < rows[at].last) {
				at = i;
			}
		}
		return at;
	}

	/** Drops the rows whose sound was last heard more than {@code lifeNanos} ago. Allocation-free. */
	public void expire(long now, long lifeNanos) {
		for (int i = count - 1; i >= 0; i--) {
			if (now - rows[i].last > lifeNanos) {
				remove(i);
			}
		}
	}

	/** Keeps at most {@code max} rows, dropping those heard longest ago (Maximum visible entries was lowered). */
	public void trim(int max) {
		int limit = Math.clamp(max, 1, CAPACITY);
		while (count > limit) {
			remove(stalest());
		}
	}

	private void remove(int index) {
		Entry gone = rows[index];
		System.arraycopy(rows, index + 1, rows, index, count - index - 1);
		// Back to the spare rows, with the view's text object, for the next row to use.
		rows[--count] = gone;
	}

	/** Every row goes (the list was turned off, the world left). */
	public void clear() {
		count = 0;
	}

	/** The rows showing. */
	public int count() {
		return count;
	}

	/** Row {@code i}, oldest first (0 is the bottom one). */
	public Entry row(int i) {
		if (i < 0 || i >= count) {
			throw new IndexOutOfBoundsException(i);
		}
		return rows[i];
	}

	/** Sounds handed in so far. */
	public long heardCount() {
		return heard;
	}

	/** What a row heard {@code count} times shows after its caption: nothing for one, "×2" to "×99", then "×99+". */
	public static String countLabel(int count) {
		if (count < 2) {
			return "";
		}
		return COUNT_LABELS[Math.min(count, MOST_COUNT + 1) - 2];
	}

	private static String[] countLabels() {
		String[] labels = new String[MOST_COUNT];
		for (int n = 2; n <= MOST_COUNT; n++) {
			labels[n - 2] = "×" + n;
		}
		labels[MOST_COUNT - 1] = "×" + MOST_COUNT + "+";
		return labels;
	}

	private static final String[] DIRECTION_NAMES = {"none", "near", "front", "right", "behind", "left"};

	/**
	 * The list as one line of text, bottom row first: each caption, how many times its sound was heard and the way its arrow
	 * points as last drawn (for QA's log: {@code call ...SoundActivity.describe}). Allocates; never called while playing.
	 */
	public static String describe() {
		SoundActivity a = INSTANCE;
		StringBuilder out = new StringBuilder().append(a.count).append(" rows, ").append(a.heard).append(" sounds heard");
		for (int i = 0; i < a.count; i++) {
			Entry e = a.rows[i];
			out.append(" | ").append(e.text).append(" x").append(e.count).append(' ').append(DIRECTION_NAMES[e.shownDirection]);
		}
		return out.toString();
	}

	// ---------- direction ----------

	/**
	 * The way to a sound at ({@code sx}, {@code sz}) from a listener at ({@code lx}, {@code lz}) facing {@code yaw} (Minecraft's
	 * degrees: 0 faces south, +z; 90 faces west), as {@link #FRONT}, {@link #RIGHT}, {@link #BEHIND}, {@link #LEFT} or
	 * {@link #NEAR}. Height doesn't count: an arrow says left or right, not up. {@code previous} is the direction drawn last, so a
	 * sound on an edge keeps its arrow until it is clearly past it.
	 */
	public static int direction(double lx, double lz, float yaw, double sx, double sz, int previous) {
		double dx = sx - lx;
		double dz = sz - lz;
		double near = previous == NEAR ? NEAR_LEAVE_BLOCKS : NEAR_BLOCKS;
		if (dx * dx + dz * dz < near * near) {
			return NEAR;
		}
		double rad = Math.toRadians(yaw);
		double sin = Math.sin(rad);
		double cos = Math.cos(rad);
		// Facing yaw, ahead is (-sin, cos) and the right hand is (-cos, -sin): facing south (+z), east (+x) is on the left.
		double ahead = -dx * sin + dz * cos;
		double right = -dx * cos - dz * sin;
		double angle = Math.toDegrees(Math.atan2(right, ahead));
		double off = Math.abs(angle);
		double front = previous == FRONT ? FRONT_DEGREES + HYSTERESIS_DEGREES : FRONT_DEGREES;
		double behind = previous == BEHIND ? BEHIND_DEGREES - HYSTERESIS_DEGREES : BEHIND_DEGREES;
		if (off < front) {
			return FRONT;
		}
		if (off > behind) {
			return BEHIND;
		}
		// Left and right meet only straight ahead and straight behind, inside those two, so a side never flickers to the other.
		return angle > 0 ? RIGHT : LEFT;
	}

	// ---------- kinds of sound ----------

	/**
	 * Whether a modern sound id's path ({@code entity.arrow.shoot}) is a combat sound: attacks, players hurt or dying,
	 * explosions and TNT, arrows, tridents, crossbows, shields, thrown things (pearls, potions, snowballs, wind charges), a
	 * totem used. Pure string checks, no allocation.
	 */
	public static boolean combat(String path) {
		return path.contains(".explode") || path.contains("explosion") || path.contains(".attack") || path.contains(".shoot")
			|| path.contains(".throw") || path.startsWith("entity.player.hurt") || path.startsWith("entity.player.death")
			|| path.startsWith("entity.arrow.") || path.startsWith("entity.spectral_arrow.") || path.startsWith("item.crossbow.")
			|| path.startsWith("item.bow.") || path.startsWith("entity.trident.") || path.startsWith("item.trident.")
			|| path.startsWith("item.shield.") || path.startsWith("entity.ender_pearl.") || path.startsWith("entity.potion.")
			|| path.startsWith("entity.splash_potion.") || path.startsWith("entity.lingering_potion.") || path.startsWith("entity.tnt.")
			|| path.startsWith("block.tnt.") || path.startsWith("entity.end_crystal.") || path.startsWith("entity.wind_charge.")
			|| path.startsWith("entity.creeper.primed") || path.startsWith("item.totem.") || path.startsWith("item.mace.")
			|| path.startsWith("item.firecharge.") || path.startsWith("entity.fishing_bobber.throw");
	}

	static double distance(double ax, double ay, double az, double bx, double by, double bz) {
		double dx = ax - bx;
		double dy = ay - by;
		double dz = az - bz;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}
}
