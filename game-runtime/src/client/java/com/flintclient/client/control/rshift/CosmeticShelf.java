package com.flintclient.client.control.rshift;

import com.flintclient.client.FlintLog;
import com.flintclient.client.control.feature.CapeChoices;
import com.flintclient.client.control.feature.CapeFrames;
import com.flintclient.client.control.feature.CapePng;
import com.flintclient.client.control.feature.StoreCapes;
import com.flintclient.client.control.store.CapeAnimation;
import com.flintclient.client.module.account.LauncherLink.LinkException;
import com.flintclient.client.module.social.SocialLink;
import com.flintclient.client.module.social.SocialLink.Cosmetics;
import com.flintclient.client.module.social.SocialLink.Item;
import com.flintclient.client.platform.Clock;
import com.flintclient.client.platform.Texture;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * What the Cosmetics window shows, kept for the whole game session so it opens at once: the account's last list, every item's
 * picture (uploaded once), each animated cape's frames as one texture ({@link Reel}: every frame uploaded once, side by side,
 * played by drawing another part of it at the wall clock's frame, as the worn cape plays) and the capes' PNGs (worn at once
 * on a pick).
 *
 * <ul>
 * <li>Warmed before the window opens ({@link #warm}: the title screen and the Right Shift menu ask for the list, then every
 * picture and animation within the budgets, in the window's order). Decoded frames wait in the Java heap only until their
 * upload, at most {@link #PENDING_BYTES} of them: past that, the next animations wait to be decoded.</li>
 * <li>Pictures come from the Flint app (the game link), strips through the game's Store capes ({@link StoreCapes#strip}: its
 * disk cache, the Store's hosts only, SHA-256 checked); both are read and decoded on a small pool ({@link #THREADS} threads),
 * what is on screen first. Only the upload is the render thread's ({@link #pump}): within {@link #UPLOAD_BYTES} a frame,
 * tiles on screen first, so no frame hitches.</li>
 * <li>Kept within budgets, not dropped when the window closes: past {@link #REEL_BYTES} of reels (or {@link #POSTER_TEXELS}
 * of pictures), those drawn least recently give their texture back (never one drawn a moment ago). A picture or strip that
 * couldn't be read is asked for again when the window next opens.</li>
 * </ul>
 * Render thread unless said. Same on every Minecraft line (only {@link Texture} is the engine's).
 */
public final class CosmeticShelf {
	/** Loader threads: decoding is parallel, a little below the game's priority. */
	static final int THREADS = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
	/** One animated cape's frames at most (more halve its scale). */
	static final long MOTION_BYTES = 8L * 1024 * 1024;
	/** Every reel's texture together (GPU memory; the Java heap keeps none once uploaded). */
	static final long REEL_BYTES = 96L * 1024 * 1024;
	/** Decoded frames waiting for their upload (Java heap) at most: past this, animations wait to be decoded. */
	static final long PENDING_BYTES = 24L * 1024 * 1024;
	/** Uploaded per frame at most (always one picture or reel, however large). */
	static final long UPLOAD_BYTES = 4L * 1024 * 1024;
	/** The time a frame spends uploading at most, after its first upload (the rest go next frame). */
	static final long UPLOAD_NANOS = 3_000_000L;
	/** Every picture's texels together. */
	static final long POSTER_TEXELS = 8L * 1024 * 1024;
	/** The capes' PNGs kept for wearing one at once (past this, a pick waits for the Store). */
	static final long ART_BYTES = 24L * 1024 * 1024;
	/** {@link #warm} asks for the list again at most this often. */
	static final long WARM_MILLIS = 30_000;
	/** A reel or picture not drawn for this long may give its texture back when room is needed. */
	static final long IDLE_MILLIS = 250;

	private static @Nullable CosmeticShelf shared;

	static CosmeticShelf get() {
		CosmeticShelf s = shared;
		if (s == null) {
			s = new CosmeticShelf();
			shared = s;
		}
		return s;
	}

	/**
	 * The title screen or the Right Shift menu showed: the list is asked for (at most every {@link #WARM_MILLIS}) and, once in,
	 * every picture and the first animations are loaded, so the window opens with them. Render thread; never throws.
	 */
	public static void warmUp() {
		try {
			get().warm();
		} catch (RuntimeException | LinkageError e) {
			FlintLog.LOGGER.debug("Flint cosmetics: warming up failed", e);
		}
	}

	/** {@link #pump} from a screen that isn't the window (the menu, the title screen): finished loads get uploaded meanwhile. */
	public static void pumpShared() {
		CosmeticShelf s = shared;
		if (s != null) {
			try {
				s.pump();
			} catch (RuntimeException | LinkageError e) {
				FlintLog.LOGGER.debug("Flint cosmetics: an upload failed", e);
			}
		}
	}

	// ---------- the pool: jobs at the front go first (what is on screen) ----------

	private final ThreadPoolExecutor pool;
	private final ConcurrentLinkedDeque<Job> jobs = new ConcurrentLinkedDeque<>();
	/** Finished work, run on the render thread at the next {@link #pump}. */
	private final ConcurrentLinkedQueue<Runnable> done = new ConcurrentLinkedQueue<>();
	/** Reels decoded and not uploaded yet (any thread adds, the render thread takes away), and reel jobs waiting for room. */
	private final AtomicLong decodedBytes = new AtomicLong();
	private final ConcurrentLinkedQueue<Job> parked = new ConcurrentLinkedQueue<>();

	private CosmeticShelf() {
		pool = new ThreadPoolExecutor(THREADS, THREADS, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
			Thread thread = new Thread(task, "Flint cosmetics");
			thread.setDaemon(true);
			thread.setPriority(Thread.NORM_PRIORITY - 1);
			return thread;
		});
		pool.allowCoreThreadTimeOut(true);
	}

	/** Work for the pool, run once however often it is queued. */
	private abstract static class Job implements Runnable {
		private final AtomicBoolean taken = new AtomicBoolean();
		/** Moved to the front once (its tile came on screen). */
		boolean bumped;
		/** What its result takes until uploaded (a reel's frames; 0 for anything else). */
		long bytes;

		boolean take() {
			return taken.compareAndSet(false, true);
		}

		boolean started() {
			return taken.get();
		}
	}

	private void submit(Job job, boolean first) {
		if (first) {
			jobs.addFirst(job);
		} else {
			jobs.addLast(job);
		}
		try {
			pool.execute(this::runOne);
		} catch (RejectedExecutionException e) {
			// Never (the pool isn't shut down); the job then waits for the next one.
		}
	}

	/** A tile on screen wants {@code job}: it goes to the front if it hasn't started (once). */
	private void bump(@Nullable Job job) {
		if (job != null && !job.bumped && !job.started()) {
			job.bumped = true;
			submit(job, true);
		}
	}

	/** Pool thread: the first job not yet taken (a reel's waits while {@link #PENDING_BYTES} of frames wait for their upload). */
	private void runOne() {
		for (Job job; (job = jobs.pollFirst()) != null;) {
			long pending = decodedBytes.get();
			if (job.bytes > 0 && !job.started() && pending > 0 && pending + job.bytes > PENDING_BYTES) {
				parked.add(job);
				continue;
			}
			if (job.take()) {
				try {
					job.run();
				} catch (RuntimeException | LinkageError e) {
					FlintLog.LOGGER.debug("Flint cosmetics: loading failed", e);
				}
				return;
			}
		}
	}

	// ---------- the list ----------

	private @Nullable Cosmetics list;
	private @Nullable String listFor;
	private long warmedAt = Long.MIN_VALUE / 4;
	private boolean listAsked;

	/** The account's last list, if it was read this session (the window shows it at once while it reads it again). */
	@Nullable Cosmetics list(@Nullable String who) {
		return who != null && who.equals(listFor) ? list : null;
	}

	/** The window read the list: kept for the next time it opens. */
	void remember(String who, Cosmetics got) {
		Cosmetics now = list(who);
		if (now == null || got.version() < 0 || now.version() < 0 || got.version() >= now.version()) {
			list = got;
			listFor = who;
		}
	}

	private void warm() {
		long now = Clock.millis();
		if (listAsked || now - warmedAt < WARM_MILLIS) {
			return;
		}
		SocialLink link = SocialLink.get();
		String who = FlintFriendsScreen.playing();
		if (link == null || who == null) {
			// Not yet (or no Flint app): asked again in a second.
			warmedAt = now - WARM_MILLIS + 1000;
			return;
		}
		warmedAt = now;
		listAsked = true;
		submit(new Job() {
			@Override
			public void run() {
				Cosmetics got = null;
				try {
					got = link.cosmetics(who);
				} catch (LinkException | RuntimeException e) {
					// The window reads it when it opens.
				}
				Cosmetics answer = got;
				done.add(() -> {
					listAsked = false;
					if (answer != null) {
						remember(who, answer);
						preload(who, answer);
					}
				});
			}
		}, false);
	}

	/**
	 * Every item's picture and animation (within the budgets), in the window's order (what is worn first), behind what is on
	 * screen: the list just came (warming up, or the window read it).
	 */
	void preload(String who, Cosmetics c) {
		List<Item> order = new ArrayList<>();
		for (String slot : SocialLink.SLOTS) {
			int first = order.size();
			String worn = c.worn(slot);
			for (Item item : c.items()) {
				if (slot.equals(item.slot())) {
					if (item.id().equals(worn)) {
						order.add(first, item);
					} else {
						order.add(item);
					}
				}
			}
		}
		// Then what has no slot (emotes, kinds a newer Store has).
		for (Item item : c.items()) {
			if (item.slot() == null) {
				order.add(item);
			}
		}
		for (Item item : order) {
			String key = key(item);
			poster(key, who, item, false);
			reel(key, item, false);
		}
	}

	// ---------- pictures ----------

	/** An item's picture: its texture once up; the decoded pixels until then. */
	static final class Poster {
		static final int ASKED = 0;
		static final int DECODED = 1;
		static final int UP = 2;
		static final int FAILED = 3;

		final String key;
		int state = ASKED;
		@Nullable Texture texture;
		int width;
		int height;
		int @Nullable [] pixels;
		@Nullable Job job;
		long drawnAt;
		/** A cape's PNG and its SHA-256, for wearing it at once ({@link #capeArt}); null when there is no room. */
		byte @Nullable [] png;
		@Nullable String sha256;

		Poster(String key) {
			this.key = key;
		}

		long texels() {
			return (long) width * height;
		}
	}

	private final Map<String, Poster> posters = new HashMap<>();
	private long posterTexels;
	private long artBytes;

	/** An item's key here: the item and its picture's version (a tile makes it once; drawing then allocates nothing). */
	static String key(Item item) {
		return item.id() + "-v" + item.art();
	}

	/**
	 * The item's picture to draw ({@code key}: {@link #key}), or null (none, or on its way: asked for now, first in line when
	 * {@code onScreen}). Render thread.
	 */
	@Nullable Poster poster(String key, @Nullable String who, Item item, boolean onScreen) {
		if (item.art() < 0) {
			return null;
		}
		Poster p = posters.get(key);
		long now = Clock.millis();
		if (p == null) {
			// (Only here: SocialLink.get makes a new object, and drawing must allocate nothing.)
			SocialLink link = SocialLink.get();
			if (who == null || link == null) {
				return null;
			}
			p = new Poster(key);
			p.drawnAt = now;
			posters.put(key, p);
			Poster asked = p;
			boolean cape = "cape".equals(item.kind());
			Job job = new Job() {
				@Override
				public void run() {
					Decoded d = null;
					byte[] png = null;
					try {
						png = link.art(who, item.id());
						d = decode(png, cape);
					} catch (LinkException | RuntimeException e) {
						// No picture: the tile keeps its gem.
					}
					Decoded decoded = d;
					byte[] bytes = png;
					done.add(() -> decoded(asked, decoded, bytes));
				}
			};
			p.job = job;
			submit(job, onScreen);
		} else if (onScreen && p.state == Poster.ASKED) {
			bump(p.job);
		}
		if (onScreen) {
			p.drawnAt = now;
		}
		return p.state == Poster.UP ? p : null;
	}

	private void decoded(Poster p, @Nullable Decoded d, byte @Nullable [] png) {
		p.job = null;
		if (posters.get(p.key) != p) {
			return;
		}
		if (d == null) {
			p.state = Poster.FAILED;
			return;
		}
		p.width = d.width();
		p.height = d.height();
		p.pixels = d.pixels();
		p.state = Poster.DECODED;
		if (d.sha256() != null && png != null && artBytes + png.length <= ART_BYTES) {
			p.png = png;
			p.sha256 = d.sha256();
			artBytes += png.length;
		}
		waiting.add(p);
	}

	/** A cape's whole texture (what goes on you when picked), if its PNG is here: decoded now. */
	@Nullable CapeArt capeArt(Item item) {
		Poster p = posters.get(key(item));
		byte[] png = p == null ? null : p.png;
		if (png == null || p.sha256 == null) {
			return null;
		}
		try {
			return new CapeArt(p.sha256, CapePng.decode(png, 4096, 2048));
		} catch (CapePng.Refused e) {
			return null;
		}
	}

	// ---------- animated capes ----------

	/**
	 * An animated cape's frames in one texture: {@link #cols} frames to a row, {@link #frameW} × {@link #frameH} each (its front,
	 * at {@link #scale} pixels per unit), uploaded once; a frame is drawn as that part of it.
	 */
	static final class Reel {
		static final int ASKED = 0;
		static final int DECODED = 1;
		static final int UP = 2;
		static final int FAILED = 3;

		final String key;
		final CapeAnimation animation;
		final int scale;
		final int frameW;
		final int frameH;
		final int cols;
		final int width;
		final int height;
		final long bytes;
		final AtomicBoolean cancelled = new AtomicBoolean();
		int state = ASKED;
		int @Nullable [] pixels;
		@Nullable Texture texture;
		@Nullable Job job;
		long drawnAt;

		Reel(String key, CapeAnimation animation, int scale) {
			this.key = key;
			this.animation = animation;
			this.scale = scale;
			this.frameW = CapeFrames.FRONT_WIDTH * scale;
			this.frameH = CapeFrames.FRONT_HEIGHT * scale;
			int n = animation.frameCount();
			this.cols = Math.max(1, Math.min(n, MAX_SIDE / frameW));
			this.width = cols * frameW;
			this.height = (n + cols - 1) / cols * frameH;
			this.bytes = (long) width * height * 4;
		}

		boolean up() {
			return state == UP && texture != null;
		}

		/** Where frame {@code f} sits in the texture (left, top). */
		int u(int f) {
			return f % cols * frameW;
		}

		int v(int f) {
			return f / cols * frameH;
		}
	}

	/** The widest (and tallest) reel texture: every graphics card takes it. */
	static final int MAX_SIDE = 4096;

	/** Reels by animation (two items of the same animation share one), and what they take together (asked, decoded or up). */
	private final Map<String, Reel> reels = new HashMap<>();
	private long reelBytes;
	/** Manifests read, by item and picture version, and the items whose manifest didn't read (they stay still). */
	private final Map<String, CapeAnimation> manifests = new HashMap<>();
	private final Set<String> still = new HashSet<>();
	/** Each animated item's reel key (its animation's, at its scale), made once. */
	private final Map<String, String> reelKeys = new HashMap<>();
	private long roomAt;

	/** Whether {@code item} ({@code key}: {@link #key}) is an animated cape this game can play (its manifest reads, and the Store capes are up). */
	boolean animated(String key, Item item) {
		return manifest(key, item) != null;
	}

	private @Nullable CapeAnimation manifest(String key, Item item) {
		if (item.animation() == null || !"cape".equals(item.kind())) {
			return null;
		}
		CapeAnimation a = manifests.get(key);
		if (a != null || still.contains(key)) {
			return a;
		}
		StoreCapes<?> capes = StoreCapes.shared();
		if (capes == null) {
			return null;
		}
		a = CapeAnimation.parse(item.animation(), capes.client().endpoint());
		if (a == null) {
			still.add(key);
		} else {
			manifests.put(key, a);
		}
		return a;
	}

	/** The scale an animation's reel is kept at: its own, at most 16 px per unit, halved until it fits {@link #MOTION_BYTES}. */
	static int reelScale(CapeAnimation animation) {
		int scale = Math.min(animation.scale(), CapeFrames.MAX_SCALE);
		while (scale > 1 && (long) animation.frameCount() * CapeFrames.FRONT_WIDTH * scale * CapeFrames.FRONT_HEIGHT * scale * 4 > MOTION_BYTES) {
			scale /= 2;
		}
		return scale;
	}

	/**
	 * The item's reel ({@code itemKey}: {@link #key}; null: a still cape, the Store capes aren't up, its strips failed, or no
	 * room: the picture then stays); asked for now when it isn't loaded, first in line when {@code onScreen}. Render thread.
	 */
	@Nullable Reel reel(String itemKey, Item item, boolean onScreen) {
		CapeAnimation animation = manifest(itemKey, item);
		StoreCapes<?> capes = StoreCapes.shared();
		if (animation == null || capes == null) {
			return null;
		}
		int scale = reelScale(animation);
		String key = reelKeys.get(itemKey);
		if (key == null) {
			key = animation.key() + "-" + scale;
			reelKeys.put(itemKey, key);
		}
		if (failedReels.contains(key)) {
			return null;
		}
		Reel r = reels.get(key);
		long now = Clock.millis();
		if (r != null) {
			if (r.state == Reel.FAILED) {
				return null;
			}
			r.drawnAt = onScreen ? now : r.drawnAt;
			if (onScreen && r.state == Reel.ASKED) {
				bump(r.job);
			}
			return r;
		}
		Reel fresh = new Reel(key, animation, scale);
		if (fresh.width > MAX_SIDE || fresh.height > MAX_SIDE) {
			return null;
		}
		if (reelBytes + fresh.bytes > REEL_BYTES && !(onScreen && makeRoom(fresh.bytes, now))) {
			return null;
		}
		reelBytes += fresh.bytes;
		fresh.drawnAt = now;
		reels.put(key, fresh);
		Job job = new Job() {
			@Override
			public void run() {
				int[] pixels = fresh.cancelled.get() ? null : cut(capes, fresh);
				if (pixels != null) {
					decodedBytes.addAndGet(fresh.bytes);
				}
				done.add(() -> cutDone(fresh, pixels));
			}
		};
		job.bytes = fresh.bytes;
		fresh.job = job;
		submit(job, onScreen);
		return fresh;
	}

	/**
	 * The reels are full: those not drawn for {@link #IDLE_MILLIS} (scrolled away, or another window's) give theirs back, least
	 * recently drawn first, at most every 250 ms; true when {@code bytes} more then fit.
	 */
	private boolean makeRoom(long bytes, long now) {
		if (now - roomAt < 250) {
			return false;
		}
		roomAt = now;
		List<Reel> idle = new ArrayList<>();
		for (Reel r : reels.values()) {
			if (now - r.drawnAt > IDLE_MILLIS) {
				idle.add(r);
			}
		}
		idle.sort((a, b) -> Long.compare(a.drawnAt, b.drawnAt));
		for (Reel r : idle) {
			if (reelBytes + bytes <= REEL_BYTES) {
				break;
			}
			drop(r);
		}
		return reelBytes + bytes <= REEL_BYTES;
	}

	private void drop(Reel r) {
		reels.remove(r.key);
		r.cancelled.set(true);
		waiting.remove(r);
		if (r.pixels != null) {
			decodedBytes.addAndGet(-r.bytes);
		}
		r.pixels = null;
		reelBytes -= r.bytes;
		Texture t = r.texture;
		r.texture = null;
		if (t != null) {
			t.release();
		}
		r.state = Reel.FAILED;
	}

	/**
	 * Pool thread: every strip read (or downloaded) and checked, decoded at the size the manifest gives, each frame's front cut
	 * at the reel's scale into its place in the reel, in the layout {@link Texture} uploads as it is. Null when a strip fails
	 * (the picture stays) or the reel was dropped meanwhile.
	 */
	private static int @Nullable [] cut(StoreCapes<?> capes, Reel reel) {
		CapeAnimation animation = reel.animation;
		int[] out = new int[reel.width * reel.height];
		int[][] whole = new int[animation.frameCount()][];
		try {
			for (int s = 0; s < animation.strips().size(); s++) {
				if (reel.cancelled.get()) {
					return null;
				}
				CapeAnimation.Strip strip = animation.strips().get(s);
				int height = strip.frames() * animation.frameHeight();
				CapePng.Image image = CapePng.decode(capes.strip(strip), animation.frameWidth(), height);
				if (image.width() != animation.frameWidth() || image.height() != height) {
					return null;
				}
				int first = animation.firstFrame(s);
				CapeFrames.slice(image.argb(), animation.scale(), first, strip.frames(), reel.scale, Texture.WRITE_LAYOUT, whole);
				for (int f = first; f < first + strip.frames(); f++) {
					place(whole[f], reel, f, out);
					whole[f] = null;
				}
			}
		} catch (Exception e) {
			// Offline, refused or not a PNG: the picture stays.
			return null;
		}
		return out;
	}

	/** Copies frame {@code f}'s front (a whole frame at the reel's scale) to its place in {@code out}. */
	static void place(int[] frame, Reel reel, int f, int[] out) {
		int s = reel.scale;
		int frameRow = CapeAnimation.ATLAS_WIDTH * s;
		int left = reel.u(f);
		int top = reel.v(f);
		for (int y = 0; y < reel.frameH; y++) {
			System.arraycopy(frame, (s + y) * frameRow + s, out, (top + y) * reel.width + left, reel.frameW);
		}
	}

	private void cutDone(Reel r, int @Nullable [] pixels) {
		r.job = null;
		if (reels.get(r.key) != r) {
			if (pixels != null) {
				decodedBytes.addAndGet(-r.bytes);
			}
			return;
		}
		if (pixels == null) {
			// It stays known as failed (the picture shows) until the window opens again.
			reels.remove(r.key);
			reelBytes -= r.bytes;
			failedReels.add(r.key);
			return;
		}
		r.pixels = pixels;
		r.state = Reel.DECODED;
		waiting.add(r);
	}

	/** Reels whose strips didn't load: asked for again when the window next opens. */
	private final Set<String> failedReels = new HashSet<>();

	/** The window opened: what failed is asked for again (offline then, maybe not now). */
	void opened() {
		failedReels.clear();
		Iterator<Poster> it = posters.values().iterator();
		while (it.hasNext()) {
			if (it.next().state == Poster.FAILED) {
				it.remove();
			}
		}
	}

	// ---------- uploads ----------

	/** Decoded pictures and reels waiting for their upload. */
	private final List<Object> waiting = new ArrayList<>();
	/** The render thread's time uploading, in all and the most in one frame (the log line). */
	private long uploadNanos;
	private long slowestUpload;

	/**
	 * Once a frame from the window (and from the menu or the title screen, warming up): takes finished loads, then uploads
	 * what waits, within {@link #UPLOAD_BYTES}: what was drawn a moment ago first. Allocation-free when there is nothing new.
	 * Render thread.
	 */
	void pump() {
		for (Runnable r; (r = done.poll()) != null;) {
			r.run();
		}
		if (!parked.isEmpty() && decodedBytes.get() < PENDING_BYTES) {
			// Room again: the animations that waited go next, in their order.
			for (Job job; (job = parked.poll()) != null;) {
				jobs.addLast(job);
				try {
					pool.execute(this::runOne);
				} catch (RejectedExecutionException e) {
					// Never (the pool isn't shut down).
				}
			}
		}
		if (waiting.isEmpty()) {
			return;
		}
		long now = Clock.millis();
		long start = System.nanoTime();
		long budget = UPLOAD_BYTES;
		boolean any = false;
		// Twice through: first what is on screen, then the rest.
		// Within the bytes and {@link #UPLOAD_NANOS} a frame (hundreds of small pictures come in a burst for a big collection).
		for (int pass = 0; pass < 2 && budget > 0; pass++) {
			for (int i = 0; i < waiting.size() && budget > 0 && !(any && System.nanoTime() - start > UPLOAD_NANOS);) {
				Object o = waiting.get(i);
				long drawn = o instanceof Reel r ? r.drawnAt : ((Poster) o).drawnAt;
				long bytes = o instanceof Reel r ? r.bytes : ((Poster) o).texels() * 4;
				if (pass == 0 && now - drawn > IDLE_MILLIS || any && bytes > budget) {
					i++;
					continue;
				}
				waiting.remove(i);
				upload(o);
				any = true;
				budget -= bytes;
			}
		}
		long took = System.nanoTime() - start;
		uploadNanos += took;
		slowestUpload = Math.max(slowestUpload, took);
	}

	private void upload(Object o) {
		if (o instanceof Reel r) {
			int[] pixels = r.pixels;
			r.pixels = null;
			if (pixels == null) {
				return;
			}
			decodedBytes.addAndGet(-r.bytes);
			if (reels.get(r.key) != r) {
				return;
			}
			try {
				r.texture = Texture.uploadFixed("cosmetics/reel-" + safe(r.key), r.width, r.height, pixels);
				r.state = Reel.UP;
			} catch (RuntimeException e) {
				FlintLog.LOGGER.warn("Flint cosmetics: an animation couldn't be uploaded ({}x{}): {}", r.width, r.height, e.toString());
				drop(r);
			}
			return;
		}
		Poster p = (Poster) o;
		int[] pixels = p.pixels;
		p.pixels = null;
		if (pixels == null || posters.get(p.key) != p) {
			return;
		}
		if (posterTexels + p.texels() > POSTER_TEXELS) {
			freePosters(p.texels());
		}
		try {
			p.texture = Texture.uploadFixed("cosmetics/" + safe(p.key), p.width, p.height, pixels);
			p.state = Poster.UP;
			posterTexels += p.texels();
		} catch (RuntimeException e) {
			p.state = Poster.FAILED;
		}
	}

	/** Pictures not drawn a moment ago give their texture back (least recently drawn first) until {@code texels} more fit. */
	private void freePosters(long texels) {
		long now = Clock.millis();
		List<Poster> idle = new ArrayList<>();
		for (Poster p : posters.values()) {
			if (p.state == Poster.UP && now - p.drawnAt > IDLE_MILLIS) {
				idle.add(p);
			}
		}
		idle.sort((a, b) -> Long.compare(a.drawnAt, b.drawnAt));
		for (Poster p : idle) {
			if (posterTexels + texels <= POSTER_TEXELS) {
				break;
			}
			posters.remove(p.key);
			p.state = Poster.FAILED;
			posterTexels -= p.texels();
			if (p.png != null) {
				artBytes -= p.png.length;
				p.png = null;
			}
			Texture t = p.texture;
			p.texture = null;
			if (t != null) {
				t.release();
			}
		}
	}

	private static String safe(String key) {
		return key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "");
	}

	/** One line for the log: what is kept. */
	String describe() {
		int up = 0;
		for (Reel r : reels.values()) {
			up += r.up() ? 1 : 0;
		}
		return posters.size() + " pictures (" + posterTexels * 4 / 1024 + " KiB), " + up + " of " + reels.size() + " animations up ("
			+ reelBytes / 1024 + " KiB), " + waiting.size() + " waiting; uploads took " + uploadNanos / 1_000_000 + " ms, at most "
			+ slowestUpload / 100_000 / 10.0 + " ms a frame";
	}

	// ---------- decoding (pool threads) ----------

	/** A decoded picture in {@link Texture#WRITE_LAYOUT}: a cape's front (with the PNG's hash), or the whole image for anything else. */
	record Decoded(int width, int height, int[] pixels, @Nullable String sha256) {
	}

	/** A cape's whole texture as the Flint app checked it (the PNG the Store hashed), and that hash. */
	record CapeArt(String sha256, CapePng.Image image) {
	}

	static @Nullable Decoded decode(byte[] png, boolean cape) {
		try {
			CapePng.Image image = CapePng.decode(png, 4096, 2048);
			if (!cape) {
				return image.width() > 1024 || image.height() > 1024 ? null
					: new Decoded(image.width(), image.height(), layout(image.argb()), null);
			}
			// The texture goes to the game's cape cache (by its hash), so the Store's answer for it loads from disk.
			String sha256 = CapeChoices.seen(png);
			int[] size = new int[2];
			int[] front = FlintCosmeticsScreen.capeFront(image, size);
			return size[0] > 0 && size[1] > 0 ? new Decoded(size[0], size[1], layout(front), sha256) : null;
		} catch (CapePng.Refused e) {
			return null;
		}
	}

	/** ARGB pixels in the layout {@link Texture#uploadFixed} takes, in place. */
	private static int[] layout(int[] argb) {
		CapeFrames.Layout to = Texture.WRITE_LAYOUT;
		if (to != CapeFrames.Layout.ARGB) {
			for (int i = 0; i < argb.length; i++) {
				argb[i] = to.from(argb[i]);
			}
		}
		return argb;
	}
}
