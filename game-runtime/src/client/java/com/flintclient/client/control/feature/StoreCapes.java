package com.flintclient.client.control.feature;

import com.flintclient.client.FlintLog;
import com.flintclient.client.control.store.CapeAnimation;
import com.flintclient.client.control.store.StoreCosmeticsClient;
import com.flintclient.client.control.store.StoreCosmeticsClient.Asset;
import com.flintclient.client.control.store.StoreCosmeticsClient.Lookup;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * The capes players equipped in the Flint Store, for the players this game draws: what the Store's public lookup answers for
 * each of them ({@link StoreCosmeticsClient}), the texture downloaded, checked and uploaded, and nothing else. Equipping
 * happens in the launcher; the game holds no Store token and nothing on this computer (a file, a setting) can make a cape
 * appear: only the Store's answer for that player, now or within {@link #STALE_NANOS} of its last answer while it can't be
 * reached.
 *
 * <ul>
 * <li>Lookups: the players {@link #update} is given (yours first, then the nearest), up to {@link StoreCosmeticsClient#MAX_BATCH}
 * per request and one request at a time, at most one a {@link #MIN_INTERVAL_NANOS}; a newly seen player waits
 * {@link #DEBOUNCE_NANOS} so players arriving together share one request. Only version-4 UUIDs (Mojang accounts) are asked
 * about: offline-mode players and server NPCs are never sent.</li>
 * <li>An answer is kept for the Store's {@code max-age} (bounded to {@link #MIN_FRESH_NANOS}–{@link #MAX_FRESH_NANOS}), then
 * asked again. A player the answer leaves out wears nothing: an unequipped or revoked cape goes at the next refresh.</li>
 * <li>A failed lookup (offline, 429, 5xx, a malformed answer) keeps what was known and waits (Retry-After when given, else
 * doubling from {@link #RETRY_NANOS} to {@link #MAX_RETRY_NANOS}); an answer older than {@link #STALE_NANOS} is dropped.</li>
 * <li>Assets: loaded on one low-priority thread from the disk cache (by SHA-256, rehashed on every read) or downloaded from an
 * address {@code StoreEndpoint} allows, checked against the Store's SHA-256 and size, decoded by {@link CapePng} within the
 * cape limits, then uploaded on the game's thread ({@link #upload}). At most {@link #MAX_TEXTURES} textures and
 * {@link #MAX_TEXELS} texels; a texture nobody in view wears is released after {@link #RELEASE_NANOS}.</li>
 * <li>Animated capes ({@link CapeAnimation}): the poster first, exactly as a static cape; once it is up, the strips, one per
 * loader task (a poster asked for meanwhile doesn't wait for a whole animation), each cached and checked like a texture,
 * decoded and cut into frames ({@link CapeFrames}: at most 16 px per unit and {@link CapeFrames#MAX_BYTES} a cape, all
 * frames together at most {@link #MAX_FRAME_BYTES}; past that, capes nobody in view wears are dropped, least recently worn
 * first, and a cape that still doesn't fit keeps its poster until there is room). One texture per animation, shared by
 * everyone wearing it: the poster's own when the frames are its size. {@link #animate} writes a frame into it only when the
 * wall clock moved to another frame, and only for capes drawn in the last second. A strip that fails its checks leaves the
 * poster, retried like a texture.</li>
 * </ul>
 *
 * <p>{@link #update}, {@link #upload} and {@link #animate} run on the game's thread (Minecraft's render thread); {@link #texture}
 * may be called from any thread (it reads an immutable snapshot). Pure Java: the textures themselves are the engine's
 * ({@link Textures}).
 */
public final class StoreCapes<T> {
	static final long DEBOUNCE_NANOS = TimeUnit.MILLISECONDS.toNanos(750);
	static final long MIN_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);
	static final long DEFAULT_FRESH_NANOS = TimeUnit.SECONDS.toNanos(60);
	static final long MIN_FRESH_NANOS = TimeUnit.SECONDS.toNanos(15);
	/** The launcher's signal (something was equipped) asks about you again at most this often. */
	static final long SIGNAL_NANOS = TimeUnit.SECONDS.toNanos(5);
	static final long MAX_FRESH_NANOS = TimeUnit.MINUTES.toNanos(10);
	/** Entries this close to their refresh go along with a request that is being sent anyway. */
	static final long AHEAD_NANOS = TimeUnit.SECONDS.toNanos(5);
	static final long STALE_NANOS = TimeUnit.MINUTES.toNanos(30);
	/** A player out of view this long is forgotten (asked again if they come back). */
	static final long FORGET_NANOS = TimeUnit.MINUTES.toNanos(5);
	static final long RETRY_NANOS = TimeUnit.SECONDS.toNanos(30);
	static final long MAX_RETRY_NANOS = TimeUnit.MINUTES.toNanos(10);
	/** The longest Retry-After honoured as given. */
	static final long MAX_RETRY_AFTER_NANOS = TimeUnit.HOURS.toNanos(1);
	static final long ASSET_RETRY_NANOS = TimeUnit.MINUTES.toNanos(1);
	/** An asset that failed its checks isn't tried again for this long. */
	static final long ASSET_REFUSED_NANOS = TimeUnit.MINUTES.toNanos(30);
	static final long RELEASE_NANOS = TimeUnit.MINUTES.toNanos(2);
	static final int MAX_TEXTURES = 64;
	/** 16 MiB of RGBA: eight 1024×512 capes, or thousands of 64×32 ones. */
	static final long MAX_TEXELS = 4L * 1024 * 1024;
	/** Every animated cape's frames together: four capes at the most one may take ({@link CapeFrames#MAX_BYTES}). */
	static final long MAX_FRAME_BYTES = 96L * 1024 * 1024;
	/** An animation not drawn for this long isn't advanced (its texture keeps the frame it had). */
	static final long DRAWN_MILLIS = 1000;
	static final int MAX_PLAYERS = 400;
	/** Files kept in the disk cache (the oldest go first). */
	static final int MAX_CACHE_FILES = 256;
	/** Bytes kept in the disk cache: animation strips are up to 8 MiB each (the oldest files go first). */
	static final long MAX_CACHE_BYTES = 256L * 1024 * 1024;
	private static final long NEVER = Long.MIN_VALUE / 4;

	/** The engine's textures: uploaded, written and released on the game's thread. */
	public interface Textures<T> {
		/** A {@code width × height} texture of {@code argb} pixels (row by row), named {@code name} (lowercase, [a-z0-9/._-]). */
		T upload(String name, int width, int height, int[] argb);

		/**
		 * Writes {@code width × height} pixels (row by row, in {@link #frameLayout}) at the top left of {@code texture} (one of
		 * {@link #upload}'s, at least that large), uploading only that rectangle: an animated cape's frame.
		 */
		void write(T texture, int[] pixels, int width, int height);

		void release(T texture);

		/** The layout {@link #write} takes: frames are kept in it, so showing one is a copy. */
		default CapeFrames.Layout frameLayout() {
			return CapeFrames.Layout.ARGB;
		}
	}

	private static final class Entry {
		@Nullable Asset cape;
		/** Answered by the Store at least once (and not dropped as stale since). */
		boolean known;
		long firstSeen;
		long fetchedAt = NEVER;
		long freshUntil = NEVER;
		long lastWanted;
		int mark;
	}

	private static final int LOADING = 0;
	private static final int READY = 1;
	private static final int UPLOADED = 2;
	private static final int FAILED = 3;

	/** An animation's frames: not asked for yet, waiting for room, loading, loaded, playing, failed. */
	private static final int FRAMES_NONE = 0;
	private static final int FRAMES_WAITING = 1;
	private static final int FRAMES_LOADING = 2;
	private static final int FRAMES_READY = 3;
	private static final int FRAMES_PLAYING = 4;
	private static final int FRAMES_FAILED = 5;

	private static final class Loaded<T> {
		final Asset asset;
		/** The poster's hash, with the animation's key for an animated cape (so a static cape of the same poster is apart). */
		final String key;
		final @Nullable CapeAnimation animation;
		/** Pixels per unit the frames are kept and drawn at. */
		final int keptScale;
		int state;
		CapePng.@Nullable Image image;
		@Nullable T texture;
		/** Whether {@link #texture} draws anything where an elytra takes its texture from (see {@link #hasWings}). */
		boolean wings;
		long retryAt;
		long retry = ASSET_RETRY_NANOS;
		long lastUsed;
		boolean warned;
		// ---------- an animated cape's frames ----------
		int frames = FRAMES_NONE;
		int @Nullable [][] pixels;
		/** The frame the texture shows (-1: unknown). */
		int shown = -1;
		/** Set to cancel the strips being loaded; also tells their result apart from an earlier load's. */
		@Nullable AtomicBoolean loading;
		long framesRetryAt;
		long framesRetry = ASSET_RETRY_NANOS;
		boolean framesWarned;
		/** When it was last handed out for drawing (the wall clock of the last {@link #animate}); any thread writes it. */
		volatile long drawnAt = Long.MIN_VALUE / 4;
		@Nullable Shown<T> shownAs;

		Loaded(Asset asset, long maxCapeFrameBytes) {
			this.asset = asset;
			this.animation = asset.animation();
			this.key = key(asset);
			this.keptScale = animation == null ? 0 : CapeFrames.keptScale(animation, maxCapeFrameBytes);
		}

		long texels() {
			return (long) asset.width() * asset.height();
		}

		/** What its frames take (or will once loaded) while they are asked for. */
		long frameBytes() {
			return animation != null && frames >= FRAMES_LOADING && frames <= FRAMES_PLAYING ? CapeFrames.bytes(animation.frameCount(), keptScale)
				: 0;
		}

		int frameWidth() {
			return CapeAnimation.ATLAS_WIDTH * keptScale;
		}

		int frameHeight() {
			return CapeAnimation.ATLAS_HEIGHT * keptScale;
		}
	}

	/** What a player's cape is drawn with: the texture, and the animated cape it plays (null: a static cape). */
	private record Shown<T>(T texture, @Nullable Loaded<T> animated, boolean wings) {
	}

	private record Answer(List<UUID> asked, Lookup lookup) {
	}

	private record Result(String key, CapePng.@Nullable Image image, boolean refused, String reason) {
	}

	private record FramesResult(String key, AtomicBoolean token, int @Nullable [][] pixels, boolean refused, String reason) {
	}

	/** A download or file that must not be used (its hash or size isn't the Store's). */
	private static final class Mismatch extends Exception {
		Mismatch(String reason) {
			super(reason, null, false, false);
		}
	}

	private final StoreCosmeticsClient client;
	private final @Nullable Path cacheDir;
	private final Textures<T> textures;
	private final Executor loader;
	private final long maxFrameBytes;
	private final long maxCapeFrameBytes;
	// ---------- game thread ----------
	private final Map<UUID, Entry> entries = new HashMap<>();
	private final Map<String, Loaded<T>> assets = new HashMap<>();
	private final ArrayDeque<Loaded<T>> ready = new ArrayDeque<>();
	private final ArrayDeque<Loaded<T>> readyFrames = new ArrayDeque<>();
	private final List<Loaded<T>> playing = new ArrayList<>();
	private final List<UUID> order = new ArrayList<>();
	private boolean enabled;
	private boolean inFlight;
	private long backoffUntil = NEVER;
	private long nextRequestAt = NEVER;
	private long retry = RETRY_NANOS;
	private long now;
	private int mark;
	private int requests;
	private boolean failing;
	private boolean answeredOnce;
	private boolean visibleDirty;
	private boolean prunedCache;
	private long frameWrites;
	private long frameWriteNanos;
	private @Nullable UUID refreshSoon;
	// ---------- from the request and loader threads ----------
	private final ConcurrentLinkedQueue<Answer> answers = new ConcurrentLinkedQueue<>();
	private final ConcurrentLinkedQueue<Result> results = new ConcurrentLinkedQueue<>();
	private final ConcurrentLinkedQueue<FramesResult> frameResults = new ConcurrentLinkedQueue<>();
	// ---------- read from any thread ----------
	private volatile Map<UUID, Shown<T>> visible = Map.of();
	/** The wall clock {@link #animate} last ran at: what {@link #texture} marks an animation drawn with. */
	private volatile long frameMillis;

	/**
	 * {@code cacheDir}: where verified textures are kept between launches (null: none); {@code loader}: where assets are read,
	 * downloaded and decoded ({@link #loaderThread()} in the game).
	 */
	public StoreCapes(StoreCosmeticsClient client, @Nullable Path cacheDir, Textures<T> textures, Executor loader) {
		this(client, cacheDir, textures, loader, MAX_FRAME_BYTES, CapeFrames.MAX_BYTES);
	}

	/** With other frame budgets: all animations together, and one (tests). */
	StoreCapes(StoreCosmeticsClient client, @Nullable Path cacheDir, Textures<T> textures, Executor loader, long maxFrameBytes,
		long maxCapeFrameBytes) {
		this.client = client;
		this.cacheDir = cacheDir;
		this.textures = textures;
		this.loader = loader;
		this.maxFrameBytes = maxFrameBytes;
		this.maxCapeFrameBytes = maxCapeFrameBytes;
	}

	/** One low-priority daemon thread for asset loading. */
	public static Executor loaderThread() {
		return Executors.newSingleThreadExecutor(r -> {
			Thread thread = new Thread(r, "Flint Store capes");
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY);
			return thread;
		});
	}

	public StoreCosmeticsClient client() {
		return client;
	}

	/**
	 * The texture of the cape {@code player} wears now, or null (none, not loaded yet, or Store capes are off). Any thread. It is
	 * taken to be drawn: an animated cape is advanced by {@link #animate} for a second after.
	 */
	public @Nullable T texture(UUID player) {
		Shown<T> shown = visible.get(player);
		if (shown == null) {
			return null;
		}
		Loaded<T> animated = shown.animated();
		if (animated != null) {
			animated.drawnAt = frameMillis;
		}
		return shown.texture();
	}

	/** Whether {@code player} is shown with a Store cape now (without counting as drawn). Any thread. */
	public boolean wears(UUID player) {
		return visible.containsKey(player);
	}

	/**
	 * Whether the cape {@code player} wears now has elytra art (see {@link #hasWings}): an elytra takes its texture from the cape,
	 * as Minecraft's does, only then. Any thread.
	 */
	public boolean wings(UUID player) {
		Shown<T> shown = visible.get(player);
		return shown != null && shown.wings();
	}

	/**
	 * Whether a cape texture ({@code argb}, row by row, 64×32 per unit) draws anything in the elytra's part of the layout (x 22–45,
	 * y 0–21 at 1 px per unit), where Minecraft's elytra takes its texture from the wearer's cape. The Store's animated capes have
	 * none (their posters are transparent there), nor do capes drawn without wings: an elytra must then keep the texture Minecraft
	 * would give it, rather than wear an empty one and disappear.
	 */
	static boolean hasWings(int width, int height, int[] argb) {
		int s = width / 64;
		if (s < 1 || width != 64 * s || height != 32 * s || argb.length < width * height) {
			return false;
		}
		for (int y = 0; y < 22 * s; y++) {
			for (int x = 22 * s, row = y * width; x < 46 * s; x++) {
				if ((argb[row + x] >>> 24) != 0) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Once a frame, on the render thread, where the engine allows texture writes (between frames): brings every animated cape
	 * drawn in the last {@link #DRAWN_MILLIS} to the frame of {@code epochMillis} ({@link System#currentTimeMillis}). A texture
	 * is written only when its frame changed. Allocation-free.
	 */
	public void animate(long epochMillis) {
		frameMillis = epochMillis;
		for (int i = 0; i < playing.size(); i++) {
			Loaded<T> l = playing.get(i);
			if (epochMillis - l.drawnAt <= DRAWN_MILLIS && play(l, epochMillis)) {
				// It stopped (a write failed): the list changed under the index.
				i--;
			}
		}
	}

	/**
	 * Brings the animated cape {@code player} wears, if any, to the frame of {@code epochMillis}, now: for an engine that may
	 * write textures while drawing (Minecraft 1.8.9), just before the cape is drawn. Render thread.
	 */
	public void animate(UUID player, long epochMillis) {
		Shown<T> shown = visible.get(player);
		Loaded<T> l = shown == null ? null : shown.animated();
		if (l != null && l.frames == FRAMES_PLAYING) {
			frameMillis = epochMillis;
			l.drawnAt = epochMillis;
			play(l, epochMillis);
		}
	}

	/** Writes {@code l}'s frame for {@code epochMillis} if its texture shows another; true when that failed and it stopped. */
	private boolean play(Loaded<T> l, long epochMillis) {
		int[][] pixels = l.pixels;
		T texture = l.texture;
		if (pixels == null || texture == null) {
			return false;
		}
		int f = l.animation.frameAt(epochMillis);
		if (f == l.shown) {
			return false;
		}
		try {
			long start = System.nanoTime();
			textures.write(texture, pixels[f], l.frameWidth(), l.frameHeight());
			frameWriteNanos += System.nanoTime() - start;
			l.shown = f;
			frameWrites++;
			return false;
		} catch (RuntimeException e) {
			framesFailed(l, now, true, "couldn't be drawn: " + e);
			return true;
		}
	}

	/**
	 * Every second or so, on the game's thread: {@code now} from {@link System#nanoTime}; {@code on} whether Store capes are
	 * shown at all (off: nothing is asked, every texture is released); {@code players} who may be drawn, most important first
	 * (you, then the nearest).
	 */
	public void update(long now, boolean on, List<UUID> players) {
		this.now = now;
		take(now);
		if (!on) {
			if (enabled) {
				clear();
			}
			enabled = false;
			return;
		}
		enabled = true;
		order.clear();
		mark++;
		// A Store on this computer (development, QA's offline identity) may be asked about anyone.
		boolean anyId = client.endpoint().loopback();
		for (UUID id : players) {
			if (id == null || id.version() != 4 && !anyId || order.size() == MAX_PLAYERS) {
				continue;
			}
			Entry e = entries.get(id);
			if (e == null) {
				e = new Entry();
				e.firstSeen = now;
				entries.put(id, e);
			} else if (e.mark == mark) {
				continue;
			}
			e.mark = mark;
			e.lastWanted = now;
			order.add(id);
		}
		prune(now);
		request(now);
		for (UUID id : order) {
			Asset cape = entries.get(id).cape;
			if (cape != null) {
				want(cape, now);
			}
		}
		// Frames once every cape worn in view is marked used: only the others may make room.
		for (UUID id : order) {
			Asset cape = entries.get(id).cape;
			Loaded<T> l = cape == null || cape.animation() == null ? null : assets.get(key(cape));
			if (l != null) {
				wantFrames(l, now);
			}
		}
		release(now);
		if (visibleDirty) {
			publish();
		}
	}

	/** Every game tick: uploads at most {@code budget} textures (and animations) that finished loading. Game thread. */
	public void upload(int budget) {
		int done = 0;
		for (; done < budget && !ready.isEmpty(); done++) {
			Loaded<T> l = ready.poll();
			CapePng.Image image = l.image;
			l.image = null;
			if (l.state != READY || image == null || assets.get(l.key) != l) {
				continue;
			}
			try {
				l.texture = textures.upload("store-capes/" + l.key, image.width(), image.height(), image.argb());
				l.wings = hasWings(image.width(), image.height(), image.argb());
				l.state = UPLOADED;
				visibleDirty = true;
			} catch (RuntimeException e) {
				fail(l, now, true, "couldn't be uploaded: " + e);
			}
		}
		List<T> retired = null;
		for (; done < budget && !readyFrames.isEmpty(); done++) {
			Loaded<T> l = readyFrames.poll();
			if (l.frames != FRAMES_READY || assets.get(l.key) != l) {
				continue;
			}
			T poster = attach(l);
			if (poster != null) {
				if (retired == null) {
					retired = new ArrayList<>();
				}
				retired.add(poster);
			}
		}
		if (visibleDirty || retired != null) {
			publish();
		}
		if (retired != null) {
			// Nobody is handed a texture after it is gone: the snapshot goes first.
			retired.forEach(textures::release);
		}
	}

	/**
	 * Asks about {@code player} again at once, past any cache (at most every {@link #SIGNAL_NANOS}): the launcher said something
	 * was equipped or removed. Changes nothing by itself: the Store's answer decides.
	 */
	public void refresh(UUID player) {
		refreshSoon = player;
	}

	/** Forgets everything and releases every texture (Store capes off, or another Store). Game thread. */
	public void clear() {
		Map<UUID, Shown<T>> none = Map.of();
		visible = none;
		visibleDirty = false;
		for (Loaded<T> l : assets.values()) {
			stopFrames(l);
			if (l.texture != null) {
				textures.release(l.texture);
				l.texture = null;
			}
		}
		assets.clear();
		ready.clear();
		readyFrames.clear();
		playing.clear();
		entries.clear();
		refreshSoon = null;
	}

	/** One line for the log or a dev tour: players known, capes shown, textures, animations, requests, and whether the Store answers. */
	public String describe() {
		int wearing = 0;
		for (Entry e : entries.values()) {
			if (e.cape != null) {
				wearing++;
			}
		}
		int uploaded = 0;
		int animated = 0;
		for (Loaded<T> l : assets.values()) {
			if (l.state == UPLOADED) {
				uploaded++;
			}
			if (l.animation != null) {
				animated++;
			}
		}
		return "Store capes: " + client.endpoint().host() + ", " + entries.size() + " players known, " + wearing + " wearing, "
			+ visible.size() + " shown, " + uploaded + " textures, " + animated + " animated (" + playing.size() + " playing, "
			+ frameBytes() / (1024 * 1024) + " MiB of frames, " + frameWrites + " frame writes"
			+ (frameWrites == 0 ? "" : String.format(java.util.Locale.ROOT, " of %.1f µs on average", frameWriteNanos / 1000.0 / frameWrites))
			+ "), " + requests + " lookups"
			+ (failing ? ", waiting for the Store" : "");
	}

	// ---------- lookups ----------

	private void request(long now) {
		if (inFlight || now - backoffUntil < 0 || now - nextRequestAt < 0) {
			return;
		}
		// First what must be asked (in order: you, then the nearest): newcomers, expired answers, the launcher's signal.
		List<UUID> batch = new ArrayList<>();
		boolean due = false;
		int newcomers = 0;
		for (UUID id : order) {
			Entry e = entries.get(id);
			boolean must;
			if (!e.known) {
				must = true;
				newcomers++;
				due |= now - e.firstSeen >= DEBOUNCE_NANOS;
			} else if (id.equals(refreshSoon) && now - e.fetchedAt >= SIGNAL_NANOS) {
				must = true;
				due = true;
			} else {
				must = now - e.freshUntil >= 0;
				due |= must;
			}
			if (must && batch.size() < StoreCosmeticsClient.MAX_BATCH) {
				batch.add(id);
			}
		}
		// A full request's worth of newcomers needn't wait for more.
		due |= newcomers >= StoreCosmeticsClient.MAX_BATCH;
		if (!due || batch.isEmpty()) {
			return;
		}
		// Then, with room left, the answers that would be due within AHEAD_NANOS go along.
		for (UUID id : order) {
			if (batch.size() == StoreCosmeticsClient.MAX_BATCH) {
				break;
			}
			Entry e = entries.get(id);
			if (e.known && now - e.freshUntil < 0 && now - e.freshUntil >= -AHEAD_NANOS && !batch.contains(id)) {
				batch.add(id);
			}
		}
		boolean fresh = refreshSoon != null && batch.contains(refreshSoon);
		if (fresh) {
			refreshSoon = null;
		}
		inFlight = true;
		requests++;
		nextRequestAt = now + MIN_INTERVAL_NANOS;
		List<UUID> asked = List.copyOf(batch);
		client.lookup(asked, fresh).whenComplete((lookup, error) -> answers.add(new Answer(asked,
			lookup != null ? lookup : new Lookup(StoreCosmeticsClient.Status.FAILED, Map.of(), -1, -1))));
	}

	/** Picks up lookups and loaded assets that finished since the last update. */
	private void take(long now) {
		Answer a;
		while ((a = answers.poll()) != null) {
			inFlight = false;
			Lookup l = a.lookup();
			if (l.status() == StoreCosmeticsClient.Status.OK) {
				long fresh = l.maxAgeSeconds() < 0 ? DEFAULT_FRESH_NANOS
					: Math.max(MIN_FRESH_NANOS, Math.min(MAX_FRESH_NANOS, TimeUnit.SECONDS.toNanos(Math.min(l.maxAgeSeconds(), 86_400))));
				for (UUID id : a.asked()) {
					Entry e = entries.get(id);
					if (e == null) {
						// Forgotten (or Store capes turned off) while the request was out.
						continue;
					}
					Asset cape = l.capes().get(id);
					if (!e.known || !Objects.equals(cape, e.cape)) {
						visibleDirty = true;
					}
					e.cape = cape;
					e.known = true;
					e.fetchedAt = now;
					e.freshUntil = now + fresh;
				}
				retry = RETRY_NANOS;
				if (failing || !answeredOnce) {
					FlintLog.LOGGER.info("Flint Store capes: {} answers ({} of {} players asked wear a cape)", client.endpoint().host(),
						l.capes().size(), a.asked().size());
				}
				failing = false;
				answeredOnce = true;
			} else {
				long given = l.retryAfterSeconds() > 0 ? Math.min(TimeUnit.SECONDS.toNanos(l.retryAfterSeconds()), MAX_RETRY_AFTER_NANOS) : 0;
				long wait = Math.max(retry, given);
				backoffUntil = now + wait;
				retry = Math.min(retry * 2, MAX_RETRY_NANOS);
				if (!failing) {
					FlintLog.LOGGER.warn("Flint Store capes: {} {}; asking again in {} s (capes already shown stay up to {} min)",
						client.endpoint().host(), switch (l.status()) {
							case OFFLINE -> "can't be reached";
							case BUSY -> "is busy";
							default -> "answered in a way Flint doesn't understand";
						}, TimeUnit.NANOSECONDS.toSeconds(wait), TimeUnit.NANOSECONDS.toMinutes(STALE_NANOS));
				}
				failing = true;
			}
		}
		Result r;
		while ((r = results.poll()) != null) {
			Loaded<T> l = assets.get(r.key());
			if (l == null || l.state != LOADING) {
				continue;
			}
			if (r.image() != null) {
				l.image = r.image();
				l.state = READY;
				l.retry = ASSET_RETRY_NANOS;
				ready.add(l);
			} else {
				fail(l, now, r.refused(), r.reason());
			}
		}
		FramesResult f;
		while ((f = frameResults.poll()) != null) {
			Loaded<T> l = assets.get(f.key());
			if (l == null || l.frames != FRAMES_LOADING || l.loading != f.token()) {
				// Released, or loaded again since: the frames are dropped.
				continue;
			}
			l.loading = null;
			if (f.pixels() != null) {
				l.pixels = f.pixels();
				l.frames = FRAMES_READY;
				l.framesRetry = ASSET_RETRY_NANOS;
				readyFrames.add(l);
			} else {
				framesFailed(l, now, f.refused(), f.reason());
			}
		}
	}

	/** Forgets players out of view for {@link #FORGET_NANOS}, and drops answers older than {@link #STALE_NANOS}. */
	private void prune(long now) {
		Iterator<Entry> it = entries.values().iterator();
		while (it.hasNext()) {
			Entry e = it.next();
			if (now - e.lastWanted > FORGET_NANOS) {
				visibleDirty |= e.cape != null;
				it.remove();
			} else if (e.known && now - e.fetchedAt > STALE_NANOS) {
				// Too old to trust: no cape until the Store answers again.
				visibleDirty |= e.cape != null;
				e.cape = null;
				e.known = false;
				e.firstSeen = now - DEBOUNCE_NANOS;
			}
		}
	}

	// ---------- assets ----------

	/** The texture an asset is loaded as: its poster's hash, and its animation's key for an animated cape. */
	static String key(Asset asset) {
		CapeAnimation animation = asset.animation();
		return animation == null ? asset.sha256() : asset.sha256() + "." + animation.key();
	}

	private void want(Asset asset, long now) {
		String key = key(asset);
		Loaded<T> l = assets.get(key);
		if (l == null) {
			if (assets.size() >= MAX_TEXTURES || texels() + (long) asset.width() * asset.height() > MAX_TEXELS) {
				// Over the budget: no Store cape for this one until others are released.
				return;
			}
			l = new Loaded<>(asset, maxCapeFrameBytes);
			assets.put(key, l);
			start(l);
		} else if (l.state == FAILED && now - l.retryAt >= 0) {
			start(l);
		}
		l.lastUsed = now;
	}

	private long texels() {
		long total = 0;
		for (Loaded<T> l : assets.values()) {
			if (l.state != FAILED) {
				total += l.texels();
			}
		}
		return total;
	}

	private void start(Loaded<T> l) {
		l.state = LOADING;
		Asset asset = l.asset;
		String key = l.key;
		boolean prune = !prunedCache;
		prunedCache = true;
		try {
			loader.execute(() -> {
				if (prune) {
					pruneCache();
				}
				results.add(load(key, asset));
			});
		} catch (RejectedExecutionException e) {
			fail(l, now, false, "the loader is closed");
		}
	}

	private void fail(Loaded<T> l, long now, boolean refused, String reason) {
		l.state = FAILED;
		l.image = null;
		if (refused) {
			l.retryAt = now + ASSET_REFUSED_NANOS;
			if (!l.warned) {
				l.warned = true;
				FlintLog.LOGGER.warn("Flint Store capes: {} refused: {}", l.asset, reason);
			}
		} else {
			l.retryAt = now + l.retry;
			l.retry = Math.min(l.retry * 2, MAX_RETRY_NANOS);
		}
	}

	// ---------- animated capes ----------

	/** Every animated cape's frames, loaded or being loaded. */
	private long frameBytes() {
		long total = 0;
		for (Loaded<T> l : assets.values()) {
			total += l.frameBytes();
		}
		return total;
	}

	/** Asks for an animated cape's frames once its poster is up (again after a failure, once its wait is over). */
	private void wantFrames(Loaded<T> l, long now) {
		if (l.state != UPLOADED || l.animation == null) {
			return;
		}
		if (l.frames == FRAMES_NONE || l.frames == FRAMES_WAITING || l.frames == FRAMES_FAILED && now - l.framesRetryAt >= 0) {
			startFrames(l, now);
		}
	}

	private void startFrames(Loaded<T> l, long now) {
		CapeAnimation animation = l.animation;
		long need = CapeFrames.bytes(animation.frameCount(), l.keptScale);
		if (frameBytes() + need > maxFrameBytes) {
			makeRoom(need, now);
		}
		if (frameBytes() + need > maxFrameBytes) {
			if (l.frames != FRAMES_WAITING) {
				FlintLog.LOGGER.info("Flint Store capes: {} keeps its poster until other animations make room ({} MiB of frames shown)",
					l.asset, frameBytes() / (1024 * 1024));
			}
			l.frames = FRAMES_WAITING;
			return;
		}
		AtomicBoolean token = new AtomicBoolean();
		l.loading = token;
		l.frames = FRAMES_LOADING;
		StripsLoad load = new StripsLoad(l.key, animation, l.keptScale, textures.frameLayout(), token);
		try {
			loader.execute(load);
		} catch (RejectedExecutionException e) {
			framesFailed(l, now, false, "the loader is closed");
		}
	}

	/** Releases animated capes nobody in view wears (least recently worn first) until {@code need} more bytes of frames fit. */
	private void makeRoom(long need, long now) {
		List<Loaded<T>> idle = new ArrayList<>();
		for (Loaded<T> l : assets.values()) {
			if (l.frameBytes() > 0 && l.lastUsed != now && l.frames != FRAMES_LOADING) {
				idle.add(l);
			}
		}
		idle.sort(Comparator.comparingLong(l -> l.lastUsed));
		List<T> released = new ArrayList<>();
		for (Loaded<T> l : idle) {
			if (frameBytes() + need <= maxFrameBytes) {
				break;
			}
			assets.remove(l.key);
			stopFrames(l);
			if (l.texture != null) {
				released.add(l.texture);
				l.texture = null;
			}
			visibleDirty = true;
		}
		if (!released.isEmpty()) {
			publish();
			released.forEach(textures::release);
		}
	}

	/**
	 * Starts playing {@code l}'s loaded frames: in its poster's texture when they are its size, else in a texture of their own
	 * (64×32 at their scale, the cape region showing frame 0). Answers the poster texture it replaced, to release once the
	 * snapshot no longer hands it out.
	 */
	private @Nullable T attach(Loaded<T> l) {
		T poster = l.texture;
		int[][] pixels = l.pixels;
		if (l.state != UPLOADED || poster == null || pixels == null) {
			framesFailed(l, now, false, "its poster isn't up");
			return null;
		}
		T own = null;
		try {
			if (l.keptScale != l.asset.width() / 64) {
				int s = l.keptScale;
				own = textures.upload("store-capes/" + l.key + "-" + s, 64 * s, 32 * s, new int[64 * s * 32 * s]);
				textures.write(own, pixels[0], l.frameWidth(), l.frameHeight());
				l.texture = own;
				// Only the frames are drawn into it: nothing where an elytra looks.
				l.wings = false;
			}
			// The texture shows frame 0 (the poster is frame 0).
			l.shown = 0;
			l.frames = FRAMES_PLAYING;
			playing.add(l);
			visibleDirty = true;
			FlintLog.LOGGER.info("Flint Store capes: {} plays at {} px per unit ({} KiB of frames, {})", l.asset, l.keptScale,
				l.frameBytes() / 1024, own == null ? "in its poster's texture" : "in a texture of its own");
			return own == null ? null : poster;
		} catch (RuntimeException e) {
			if (own != null) {
				l.texture = poster;
				textures.release(own);
			}
			framesFailed(l, now, true, "couldn't be uploaded: " + e);
			return null;
		}
	}

	private void framesFailed(Loaded<T> l, long now, boolean refused, String reason) {
		stopFrames(l);
		l.frames = FRAMES_FAILED;
		if (refused) {
			l.framesRetryAt = now + ASSET_REFUSED_NANOS;
			if (!l.framesWarned) {
				l.framesWarned = true;
				FlintLog.LOGGER.warn("Flint Store capes: the animation of {} refused (its poster stays): {}", l.asset, reason);
			}
		} else {
			l.framesRetryAt = now + l.framesRetry;
			l.framesRetry = Math.min(l.framesRetry * 2, MAX_RETRY_NANOS);
		}
	}

	/** Drops {@code l}'s frames: a load in progress is cancelled, playing stops (the texture keeps the frame it shows). */
	private void stopFrames(Loaded<T> l) {
		AtomicBoolean loading = l.loading;
		if (loading != null) {
			loading.set(true);
			l.loading = null;
		}
		if (l.frames == FRAMES_PLAYING) {
			playing.remove(l);
		}
		l.pixels = null;
		l.shown = -1;
		if (l.frames != FRAMES_FAILED) {
			l.frames = FRAMES_NONE;
		}
	}

	/** Releases textures nobody in view has worn for {@link #RELEASE_NANOS}. */
	private void release(long now) {
		List<T> released = null;
		Iterator<Loaded<T>> it = assets.values().iterator();
		while (it.hasNext()) {
			Loaded<T> l = it.next();
			if (l.state != LOADING && now - l.lastUsed > RELEASE_NANOS) {
				stopFrames(l);
				if (l.texture != null) {
					if (released == null) {
						released = new ArrayList<>();
					}
					released.add(l.texture);
					l.texture = null;
				}
				it.remove();
				visibleDirty = true;
			}
		}
		if (released != null) {
			// Nobody is handed a texture after it is gone: the snapshot goes first.
			publish();
			released.forEach(textures::release);
		}
	}

	private void publish() {
		Map<UUID, Shown<T>> shown = new HashMap<>();
		for (Map.Entry<UUID, Entry> e : entries.entrySet()) {
			Asset cape = e.getValue().cape;
			Loaded<T> l = cape == null ? null : assets.get(key(cape));
			if (l != null && l.texture != null) {
				Shown<T> s = l.shownAs;
				if (s == null || s.texture() != l.texture || s.wings() != l.wings) {
					s = new Shown<>(l.texture, l.animation != null ? l : null, l.wings);
					l.shownAs = s;
				}
				shown.put(e.getKey(), s);
			}
		}
		visible = shown.isEmpty() ? Map.of() : Collections.unmodifiableMap(shown);
		visibleDirty = false;
	}

	// ---------- the loader thread ----------

	/** Reads (cache) or downloads {@code asset}, checks its hash and size, and decodes it. Loader thread. */
	private Result load(String key, Asset asset) {
		try {
			byte[] bytes = fetch(asset.sha256(), StoreCosmeticsClient.MAX_ASSET_BYTES, () -> client.download(asset));
			CapePng.Image image = CapePng.decode(bytes, StoreCosmeticsClient.MAX_CAPE_WIDTH, StoreCosmeticsClient.MAX_CAPE_HEIGHT);
			if (image.width() != asset.width() || image.height() != asset.height()) {
				return new Result(key, null, true, "it is " + image.width() + "x" + image.height() + ", not the size the Store gave");
			}
			store(asset.sha256(), bytes);
			return new Result(key, image, false, "");
		} catch (Exception e) {
			Failure failure = failure(e);
			return new Result(key, null, failure.refused(), failure.reason());
		}
	}

	private record Failure(boolean refused, String reason) {
	}

	/** Why a load failed, and whether that is worth trying again soon (offline, a timeout) or not (a refused file). */
	private static Failure failure(Exception e) {
		if (e instanceof Mismatch) {
			return new Failure(true, e.getMessage());
		}
		if (e instanceof CapePng.Refused) {
			return new Failure(true, "not a usable PNG: " + e.getMessage());
		}
		if (e instanceof ExecutionException) {
			Throwable cause = e.getCause();
			if (cause instanceof StoreCosmeticsClient.Refused) {
				return new Failure(true, cause.getMessage());
			}
			return new Failure(false, StoreCosmeticsClient.offline(e) ? "offline" : String.valueOf(cause));
		}
		if (e instanceof TimeoutException) {
			return new Failure(false, "timed out");
		}
		if (e instanceof InterruptedException) {
			Thread.currentThread().interrupt();
			return new Failure(false, "interrupted");
		}
		return new Failure(false, String.valueOf(e));
	}

	/**
	 * The file of hash {@code sha256}: the disk cache's copy while it still hashes to it (one that doesn't is deleted), else
	 * downloaded and checked. Loader thread.
	 */
	private byte[] fetch(String sha256, int maxBytes, Supplier<CompletableFuture<byte[]>> download)
		throws IOException, ExecutionException, TimeoutException, InterruptedException, Mismatch {
		Path file = cacheDir == null ? null : cacheDir.resolve(sha256 + ".png");
		if (file != null && Files.isRegularFile(file) && Files.size(file) <= maxBytes) {
			byte[] onDisk = Files.readAllBytes(file);
			if (sha256(onDisk).equals(sha256)) {
				return onDisk;
			}
			// Changed on disk: never used; downloaded again.
			Files.deleteIfExists(file);
		}
		byte[] bytes = download.get().get(30, TimeUnit.SECONDS);
		if (!sha256(bytes).equals(sha256)) {
			throw new Mismatch("its SHA-256 doesn't match the Store's");
		}
		return bytes;
	}

	/**
	 * An animated cape's strips, loaded one per loader task (a poster asked for meanwhile is loaded between two strips): each
	 * read or downloaded and checked as a texture, decoded at exactly the size the manifest gives, cut into frames at the kept
	 * scale. Posts the frames once every strip is in, or the first failure; posts nothing once cancelled.
	 */
	private final class StripsLoad implements Runnable {
		private final String key;
		private final CapeAnimation animation;
		private final int keptScale;
		private final CapeFrames.Layout layout;
		private final AtomicBoolean cancelled;
		private final int[][] pixels;
		private int strip;

		StripsLoad(String key, CapeAnimation animation, int keptScale, CapeFrames.Layout layout, AtomicBoolean cancelled) {
			this.key = key;
			this.animation = animation;
			this.keptScale = keptScale;
			this.layout = layout;
			this.cancelled = cancelled;
			this.pixels = new int[animation.frameCount()][];
		}

		@Override
		public void run() {
			if (cancelled.get()) {
				return;
			}
			try {
				CapeAnimation.Strip s = animation.strips().get(strip);
				byte[] bytes = fetch(s.sha256(), CapeAnimation.MAX_STRIP_BYTES, () -> client.download(s));
				int height = s.frames() * animation.frameHeight();
				CapePng.Image image = CapePng.decode(bytes, animation.frameWidth(), height);
				if (image.width() != animation.frameWidth() || image.height() != height) {
					throw new Mismatch("strip " + strip + " is " + image.width() + "x" + image.height() + ", not the size the Store gave");
				}
				CapeFrames.slice(image.argb(), animation.scale(), animation.firstFrame(strip), s.frames(), keptScale, layout, pixels);
				store(s.sha256(), bytes);
			} catch (Exception e) {
				Failure failure = failure(e);
				frameResults.add(new FramesResult(key, cancelled, null, failure.refused(), "strip " + strip + ": " + failure.reason()));
				return;
			}
			if (++strip == animation.strips().size()) {
				frameResults.add(new FramesResult(key, cancelled, pixels, false, ""));
				return;
			}
			try {
				loader.execute(this);
			} catch (RejectedExecutionException e) {
				frameResults.add(new FramesResult(key, cancelled, null, false, "the loader is closed"));
			}
		}
	}

	/** Writes a verified file to the cache unless it is there (best effort, atomically: a half-written file is never read). */
	private void store(String sha256, byte[] bytes) {
		if (cacheDir == null) {
			return;
		}
		Path file = cacheDir.resolve(sha256 + ".png");
		try {
			if (Files.isRegularFile(file) && Files.size(file) == bytes.length) {
				// Read from the cache (and checked) a moment ago.
				return;
			}
			Files.createDirectories(file.getParent());
			Path temp = Files.createTempFile(file.getParent(), "cape", ".tmp");
			try {
				Files.write(temp, bytes);
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} finally {
				Files.deleteIfExists(temp);
			}
		} catch (IOException | RuntimeException e) {
			// The texture is used anyway; it is downloaded again next time.
		}
	}

	/**
	 * Keeps the cache to {@link #MAX_CACHE_FILES} files and {@link #MAX_CACHE_BYTES} (the least recently written go) and removes
	 * leftovers. Loader thread.
	 */
	private void pruneCache() {
		if (cacheDir == null || !Files.isDirectory(cacheDir)) {
			return;
		}
		try (Stream<Path> files = Files.list(cacheDir)) {
			List<Path> textures = new ArrayList<>();
			for (Path f : (Iterable<Path>) files::iterator) {
				String name = f.getFileName().toString();
				if (name.endsWith(".tmp")) {
					Files.deleteIfExists(f);
				} else if (name.matches("[0-9a-f]{64}\\.png")) {
					textures.add(f);
				}
			}
			Map<Path, Long> time = new HashMap<>();
			Map<Path, Long> size = new HashMap<>();
			long total = 0;
			for (Path f : textures) {
				time.put(f, Files.getLastModifiedTime(f).toMillis());
				size.put(f, Files.size(f));
				total += size.get(f);
			}
			textures.sort(Comparator.comparingLong(time::get));
			int left = textures.size();
			for (Path f : textures) {
				if (left <= MAX_CACHE_FILES && total <= MAX_CACHE_BYTES) {
					break;
				}
				Files.deleteIfExists(f);
				left--;
				total -= size.get(f);
			}
		} catch (IOException | RuntimeException e) {
			// A cache that can't be tidied still works.
		}
	}

	static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** The players {@link #update} keeps entries for (tests). */
	Set<UUID> known() {
		return new HashSet<>(entries.keySet());
	}

	/** Every animated cape's frames, loaded or being loaded (tests). */
	long framesInMemory() {
		return frameBytes();
	}

	/** How many frames were written to textures so far (tests, the dev tour). */
	long frameWrites() {
		return frameWrites;
	}
}
