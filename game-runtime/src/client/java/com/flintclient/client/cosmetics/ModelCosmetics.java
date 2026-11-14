package com.flintclient.client.cosmetics;

import com.flintclient.client.FlintLog;
import com.flintclient.client.control.feature.CapePng;
import com.flintclient.client.control.store.StoreCosmeticsClient;
import com.flintclient.client.control.store.StoreEndpoint;
import com.flintclient.client.cosmetics.CosmeticLimits.Refused;
import com.flintclient.client.cosmetics.ModelItem.FileRef;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The 3D cosmetics players wear (wings, headwear, bodywear, auras, pets, shields, gloves), from the same public lookup as
 * Store capes ({@code StoreCapes} hands each answer over: {@link #answered}), and the models themselves: downloaded from an
 * address the Store's endpoint allows (or the disk cache, by SHA-256, rehashed on every read), checked against the Store's
 * SHA-256 and byte count, parsed and baked within {@link CosmeticLimits} on a low-priority thread, then the texture uploaded on
 * the game's thread ({@link #upload}), at most a few a tick. A model or texture that breaks a limit is refused with a reason in
 * the log and not tried again for {@link #REFUSED_NANOS}; the player shows without it. Nothing on this computer can make a
 * cosmetic appear on anyone: only the Store's answer for that player (and, for Flint's developers, {@link DevCosmetics} with
 * a system property, on yourself and test players only).
 *
 * <p>Budgets: at most {@link #MAX_LOADED} models and {@link #MAX_TEXELS} texels of textures at once; one nobody in view wears is
 * released after {@link #RELEASE_NANOS}. A texture's frames are one texture (a vertical strip), shown by moving the texture
 * coordinates, so playing them writes nothing. {@link #worn} may be read from any thread (an immutable snapshot); everything
 * else runs on the game's thread. Pure Java (the textures are the engine's: {@link Textures}).
 */
public final class ModelCosmetics<T> {
	static final int MAX_LOADED = 96;
	/** 64 MiB of RGBA: sixteen 1024×1024 textures, or thousands of small ones. */
	static final long MAX_TEXELS = 16L * 1024 * 1024;
	static final long RELEASE_NANOS = TimeUnit.MINUTES.toNanos(2);
	static final long RETRY_NANOS = TimeUnit.MINUTES.toNanos(1);
	static final long REFUSED_NANOS = TimeUnit.MINUTES.toNanos(30);
	/** An answer older than this (the Store unreachable since) is dropped: nothing shows until it answers again. */
	static final long STALE_NANOS = TimeUnit.MINUTES.toNanos(30);
	static final long FORGET_NANOS = TimeUnit.MINUTES.toNanos(5);
	static final int MAX_CACHE_FILES = 512;
	static final int MAX_PLAYERS = 400;
	private static final long DOWNLOAD_TIMEOUT_SECONDS = 20;

	/** The engine's textures, uploaded and released on the game's thread. */
	public interface Textures<T> {
		/** A {@code width × height} texture of {@code argb} pixels (row by row), named {@code name} ([a-z0-9/._-]). */
		T upload(String name, int width, int height, int[] argb);

		void release(T texture);
	}

	public static final int LOADING = 0;
	public static final int READY = 1;
	public static final int UPLOADED = 2;
	public static final int FAILED = 3;

	/** One model as loaded: shared by everyone who wears the same files. */
	public static final class Loaded<T> {
		public final ModelItem item;
		volatile int state = LOADING;
		/** Baked geometry (null: a texture-only shield, or an emote). */
		public @Nullable CosmeticModel model;
		public @Nullable CosmeticAnimation animation;
		CapePng.@Nullable Image image;
		public @Nullable T texture;
		public int textureWidth;
		public int textureHeight;
		/** Its texture has partly transparent pixels: drawn blended (else cut out, which needs no sorting). */
		public volatile boolean translucent;
		long lastUsed;
		long retryAt;
		@Nullable String reason;
		/** When it was first drawn (Molang's life time counts from it; the render thread sets it). */
		public long shownAt = Long.MIN_VALUE;

		Loaded(ModelItem item) {
			this.item = item;
		}

		public int state() {
			return state;
		}

		public @Nullable String reason() {
			return reason;
		}

		long texels() {
			return (long) textureWidth * textureHeight;
		}

		/** Ready to draw: uploaded (or an emote, which has nothing to upload). */
		public boolean drawable() {
			return state == UPLOADED;
		}
	}

	private record Result<T>(Loaded<T> loaded, @Nullable CosmeticModel model, @Nullable CosmeticAnimation animation,
		CapePng.@Nullable Image image, @Nullable Refused refused, @Nullable String retry) {
	}

	private static final class Player {
		/** What the Store said they wear, by {@link CosmeticKind#WORN} index (null: nothing). */
		final ModelItem[] items = new ModelItem[CosmeticKind.WORN.length];
		long answeredAt;
		long lastWanted;
	}

	private final StoreCosmeticsClient client;
	private final @Nullable Path cacheDir;
	private final Textures<T> textures;
	private final Executor loader;
	private final Map<UUID, Player> players = new HashMap<>();
	private final Map<String, Loaded<T>> loaded = new HashMap<>();
	private final ArrayDeque<Loaded<T>> ready = new ArrayDeque<>();
	private final ConcurrentLinkedQueue<Result<T>> results = new ConcurrentLinkedQueue<>();
	/** Extra wearers: dev items, previews (by player; they show over the Store's answer while set). */
	private final Map<UUID, ModelItem[]> extra = new HashMap<>();
	private volatile Map<UUID, Loaded<T>[]> visible = Map.of();
	private boolean dirty;
	private boolean enabled = true;
	private long now;
	private int answers;
	private int refusedCount;
	private boolean prunedCache;
	private @Nullable Path devRoot;

	public ModelCosmetics(StoreCosmeticsClient client, @Nullable Path cacheDir, Textures<T> textures, Executor loader) {
		this.client = client;
		this.cacheDir = cacheDir;
		this.textures = textures;
		this.loader = loader;
	}

	/** One low-priority daemon thread for loading models. */
	public static Executor loaderThread() {
		return Executors.newSingleThreadExecutor(r -> {
			Thread thread = new Thread(r, "Flint 3D cosmetics");
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY);
			return thread;
		});
	}

	public StoreEndpoint endpoint() {
		return client.endpoint();
	}

	/** Lets local files under {@code root} be read ({@link DevCosmetics}: Flint's developers only). */
	public void allowLocal(@Nullable Path root) {
		devRoot = root == null ? null : root.toAbsolutePath().normalize();
	}

	// ---------- what players wear ----------

	/** A lookup the Store answered ({@code StoreCapes.Answers}): the asked players' worn 3D slots. Game thread. */
	public void answered(List<UUID> asked, StoreCosmeticsClient.Lookup lookup) {
		answers++;
		for (UUID id : asked) {
			JsonObject entry = lookup.players().get(id);
			Player p = players.get(id);
			if (p == null) {
				if (entry == null || players.size() >= MAX_PLAYERS) {
					continue;
				}
				p = new Player();
				p.lastWanted = now;
				players.put(id, p);
			}
			p.answeredAt = now;
			for (int k = 0; k < CosmeticKind.WORN.length; k++) {
				CosmeticKind kind = CosmeticKind.WORN[k];
				ModelItem item = entry == null ? null : ModelItem.read(object(entry, kind.slot), kind, client.endpoint());
				ModelItem was = p.items[k];
				if (item == null ? was != null : was == null || !item.key().equals(was.key())) {
					p.items[k] = item;
					dirty = true;
				}
			}
		}
	}

	private static @Nullable JsonObject object(JsonObject entry, String key) {
		return entry.get(key) != null && entry.get(key).isJsonObject() ? entry.get(key).getAsJsonObject() : null;
	}

	/** Items {@code player} shows over the Store's answer (dev items, a preview); null takes them off. Game thread. */
	public void wearExtra(UUID player, ModelItem @Nullable [] items) {
		if (items == null) {
			if (extra.remove(player) != null) {
				dirty = true;
			}
			return;
		}
		ModelItem[] byKind = new ModelItem[CosmeticKind.WORN.length];
		for (ModelItem item : items) {
			int k = index(item.kind());
			if (k >= 0 && byKind[k] == null) {
				byKind[k] = item;
			}
		}
		extra.put(player, byKind);
		dirty = true;
	}

	static int index(CosmeticKind kind) {
		for (int k = 0; k < CosmeticKind.WORN.length; k++) {
			if (CosmeticKind.WORN[k] == kind) {
				return k;
			}
		}
		return -1;
	}

	/**
	 * Once a second or so: {@code inView}, the players who may be drawn (you first). Their models are wanted (loaded if they
	 * aren't), those of players gone for a while are released. {@code on}: the player's "show cosmetics" setting. Game thread.
	 */
	public void update(long now, boolean on, List<UUID> inView) {
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
		for (UUID id : inView) {
			Player p = players.get(id);
			if (p != null) {
				p.lastWanted = now;
			}
		}
		Iterator<Map.Entry<UUID, Player>> it = players.entrySet().iterator();
		while (it.hasNext()) {
			Player p = it.next().getValue();
			if (now - p.lastWanted > FORGET_NANOS || now - p.answeredAt > STALE_NANOS) {
				it.remove();
				dirty = true;
			}
		}
		for (UUID id : inView) {
			Player p = players.get(id);
			if (p != null) {
				for (ModelItem item : p.items) {
					if (item != null) {
						want(item, now);
					}
				}
			}
			ModelItem[] x = extra.get(id);
			if (x != null) {
				for (ModelItem item : x) {
					if (item != null) {
						want(item, now);
					}
				}
			}
		}
		release(now);
		if (dirty) {
			publish();
		}
	}

	/** The models {@code player} shows now, by {@link CosmeticKind#WORN} index (null entries: none), or null. Any thread. */
	public Loaded<T> @Nullable [] worn(UUID player) {
		return visible.get(player);
	}

	/** Whether {@code player} shows any 3D cosmetic. Any thread. */
	public boolean wears(UUID player) {
		return visible.containsKey(player);
	}

	/**
	 * A model for {@code item} outside what anyone wears (the Cosmetics window's preview, the emote wheel): loading starts if it
	 * hasn't, and it is kept while asked for at least every {@link #RELEASE_NANOS}. Game thread.
	 */
	public Loaded<T> request(ModelItem item) {
		return want(item, now);
	}

	// ---------- the Cosmetics window's turntables ----------

	/** At most this many turntables are kept (the oldest asked for go first). */
	static final int MAX_THUMBS = 48;

	/** One item's turntable ({@link ModelThumbnails}): {@link #texture} once made and uploaded. */
	public static final class Thumb<T> {
		public final int size;
		volatile int state = LOADING;
		public @Nullable T texture;
		int @Nullable [] argb;
		@Nullable String reason;

		Thumb(int size) {
			this.size = size;
		}

		public int state() {
			return state;
		}
	}

	private record ThumbResult<T>(Thumb<T> thumb, int @Nullable [] argb, @Nullable String reason) {
	}

	private final Map<String, Thumb<T>> thumbs = new java.util.LinkedHashMap<>(16, 0.75F, true);
	private final ConcurrentLinkedQueue<ThumbResult<T>> thumbResults = new ConcurrentLinkedQueue<>();

	/**
	 * {@code item}'s turntable of {@code size}-pixel cells: made on the loader thread the first time it is asked for (its files
	 * from the disk cache once loaded), then uploaded by {@link #upload}. Game thread.
	 */
	public Thumb<T> thumbnail(ModelItem item, int size) {
		String key = item.key() + "@" + size;
		Thumb<T> t = thumbs.get(key);
		if (t != null) {
			return t;
		}
		Thumb<T> made = new Thumb<>(size);
		thumbs.put(key, made);
		while (thumbs.size() > MAX_THUMBS) {
			Iterator<Thumb<T>> it = thumbs.values().iterator();
			Thumb<T> old = it.next();
			it.remove();
			if (old.texture != null) {
				textures.release(old.texture);
				old.texture = null;
			}
			old.state = FAILED;
		}
		try {
			loader.execute(() -> thumbResults.add(makeThumb(made, item)));
		} catch (RejectedExecutionException e) {
			made.state = FAILED;
		}
		return made;
	}

	private ThumbResult<T> makeThumb(Thumb<T> t, ModelItem item) {
		try {
			if (item.geometry() == null || item.texture() == null) {
				return new ThumbResult<>(t, null, "no model to show");
			}
			CosmeticModel model = CosmeticModel.bake(CosmeticGeometry.parse(fetch(item.geometry(), CosmeticLimits.MAX_GEOMETRY_BYTES)),
				item.anchor(), false);
			CosmeticAnimation animation = item.animation() == null ? null
				: CosmeticAnimation.parse(fetch(item.animation(), CosmeticLimits.MAX_ANIMATION_BYTES), CosmeticLimits.MAX_ANIMATION_SECONDS);
			CapePng.Image image = texture(fetch(item.texture(), CosmeticLimits.MAX_TEXTURE_BYTES), item.frames());
			ModelPose pose = new ModelPose(model, animation);
			return new ThumbResult<>(t, ModelThumbnails.render(model, pose, image.argb(), image.width(), image.height(), item.frames(), t.size), null);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new ThumbResult<>(t, null, "interrupted");
		} catch (Exception e) {
			return new ThumbResult<>(t, null, String.valueOf(e));
		}
	}

	private void takeThumbs() {
		ThumbResult<T> r;
		int uploaded = 0;
		while (uploaded < 2 && (r = thumbResults.poll()) != null) {
			Thumb<T> t = r.thumb();
			if (t.state != LOADING) {
				continue;
			}
			if (r.argb() == null) {
				t.state = FAILED;
				t.reason = r.reason();
				continue;
			}
			int side = ModelThumbnails.atlasSize(t.size);
			try {
				t.texture = textures.upload("cosmetics3d/thumb-" + Integer.toHexString(System.identityHashCode(t)), side, side, r.argb());
				t.state = UPLOADED;
				uploaded++;
			} catch (RuntimeException e) {
				t.state = FAILED;
				t.reason = e.toString();
			}
		}
	}

	// ---------- loading ----------

	private Loaded<T> want(ModelItem item, long now) {
		String key = item.key();
		Loaded<T> l = loaded.get(key);
		if (l == null) {
			l = new Loaded<>(item);
			l.lastUsed = now;
			if (loaded.size() >= MAX_LOADED) {
				evict(now);
			}
			loaded.put(key, l);
			start(l);
			return l;
		}
		l.lastUsed = now;
		if (l.state == FAILED && now - l.retryAt >= 0) {
			l.state = LOADING;
			l.reason = null;
			start(l);
		}
		return l;
	}

	private void start(Loaded<T> l) {
		try {
			loader.execute(() -> results.add(load(l)));
		} catch (RejectedExecutionException e) {
			l.state = FAILED;
			l.retryAt = now + RETRY_NANOS;
			l.reason = "the loader is stopped";
		}
	}

	/** Loads, checks, parses and bakes {@code l}'s files (the loader thread). */
	private Result<T> load(Loaded<T> l) {
		ModelItem item = l.item;
		try {
			CosmeticModel model = null;
			CosmeticAnimation animation = null;
			CapePng.Image image = null;
			if (item.geometry() != null) {
				CosmeticGeometry g = CosmeticGeometry.parse(fetch(item.geometry(), CosmeticLimits.MAX_GEOMETRY_BYTES));
				model = CosmeticModel.bake(g, item.anchor(), item.mirror());
			}
			if (item.animation() != null) {
				animation = CosmeticAnimation.parse(fetch(item.animation(), CosmeticLimits.MAX_ANIMATION_BYTES),
					item.kind() == CosmeticKind.EMOTE ? CosmeticLimits.MAX_EMOTE_SECONDS : CosmeticLimits.MAX_ANIMATION_SECONDS);
				if (item.kind() == CosmeticKind.EMOTE && animation.clips().isEmpty()) {
					throw new Refused("an emote without an animation");
				}
			}
			if (item.texture() != null) {
				image = texture(fetch(item.texture(), CosmeticLimits.MAX_TEXTURE_BYTES), item.frames());
				// The contract: the texture is the geometry's UV grid (Minecraft's 64×64 shield for a texture-only one) at 1–8×.
				gridScale(image, model == null ? 64 : model.textureWidth, model == null ? 64 : model.textureHeight, item.frames());
				l.translucent = CosmeticModel.translucent(image.argb());
				if (model != null) {
					model = model.visibleOnly(image.argb(), image.width(), image.height(), item.frames())
						.withoutInnerWalls(image.argb(), image.width(), image.height(), item.frames());
					if (l.translucent) {
						// Only the faces with partly transparent pixels need blending (and sorting every frame); the rest are cut out.
						model.markBlended(image.argb(), image.width(), image.height(), item.frames());
					}
					// Closed opaque boxes: their faces turned from the camera are hidden behind their own, so needn't be drawn.
					model.markSolidBoxes(image.argb(), image.width(), image.height(), item.frames(), l.translucent ? 255 : CosmeticModel.OPAQUE_ALPHA);
				}
			}
			return new Result<>(l, model, animation, image, null, null);
		} catch (Refused e) {
			return new Result<>(l, null, null, null, e, null);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new Result<>(l, null, null, null, null, "interrupted");
		} catch (Exception e) {
			Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
			if (cause instanceof StoreCosmeticsClient.Refused r) {
				// Not there, or not from a host the Store allows: not worth asking again soon.
				return new Result<>(l, null, null, null, new Refused(r.getMessage()), null);
			}
			return new Result<>(l, null, null, null, null, String.valueOf(cause));
		}
	}

	/** Refuses a texture that isn't the UV grid ({@code gw × gh}, {@code frames} tall) at a whole 1–8×. */
	static void gridScale(CapePng.Image image, int gw, int gh, int frames) throws Refused {
		int s = image.width() / gw;
		if (s < 1 || s > 8 || image.width() != gw * s || image.height() != gh * s * frames) {
			throw new Refused("texture " + image.width() + "x" + image.height() + " isn't the " + gw + "x" + gh + " UV grid"
				+ (frames > 1 ? " (" + frames + " frames)" : "") + " at 1-8x");
		}
	}

	/** A texture within the limits: at most {@link CosmeticLimits#MAX_TEXTURE_SIDE} a side, its height a whole number of frames. */
	static CapePng.Image texture(byte[] png, int frames) throws Refused {
		CapePng.Image image;
		try {
			image = CapePng.decode(png, CosmeticLimits.MAX_TEXTURE_SIDE, CosmeticLimits.MAX_TEXTURE_SIDE);
		} catch (CapePng.Refused e) {
			throw new Refused("texture: " + e.getMessage());
		}
		if (image.height() % frames != 0) {
			throw new Refused("texture " + image.width() + "x" + image.height() + " isn't " + frames + " frames tall");
		}
		return image;
	}

	/**
	 * Where a model's files come from: the one place a shared download and cache layer plugs in. It must answer exactly
	 * {@code file}'s bytes (at most {@code maxBytes}) or throw; the bytes are checked against the hash and size again here.
	 */
	public interface Fetcher {
		byte[] fetch(FileRef file, int maxBytes) throws Exception;
	}

	private volatile @Nullable Fetcher fetcher;

	/** Another download layer (null: this class's own: its disk cache, the CDN address first, then the Store's). */
	public void useFetcher(@Nullable Fetcher f) {
		fetcher = f;
	}

	/** Files the Flint app holds ({@link ModelItem#linked}): by item id and part, as the app checked them. */
	public interface LinkFiles {
		byte[] file(String id, String part) throws Exception;
	}

	private volatile @Nullable LinkFiles linkFiles;

	/** Where the app's files come from (the game link); none: such items don't load. */
	public void useLinkFiles(@Nullable LinkFiles l) {
		linkFiles = l;
	}

	/** {@code file}'s bytes, checked against its hash and size (the app's files: their size; the app checked the hash). */
	private byte[] fetch(FileRef file, int maxBytes) throws Exception {
		if (ModelItem.LINK.equals(file.url().getScheme())) {
			LinkFiles l = linkFiles;
			String[] path = file.url().getPath().split("/");
			if (l == null || path.length != 3) {
				throw new Refused("the Flint app isn't there to give this item's files");
			}
			byte[] bytes = l.file(path[1], path[2]);
			if (bytes.length != file.bytes() || bytes.length > maxBytes) {
				throw new Refused("the file is " + bytes.length + " bytes, not the " + file.bytes() + " the Store said");
			}
			return bytes;
		}
		Fetcher f = fetcher;
		if (f == null || "file".equalsIgnoreCase(file.url().getScheme())) {
			return ownFetch(file, maxBytes);
		}
		byte[] bytes = f.fetch(file, Math.min(file.bytes(), maxBytes));
		if (bytes.length != file.bytes() || !sha256(bytes).equals(file.sha256())) {
			throw new Refused("the file isn't the one the Store hashed");
		}
		return bytes;
	}

	/** {@code file}'s bytes, checked against its hash and size: the disk cache's copy, else downloaded (or read, for dev items). */
	private byte[] ownFetch(FileRef file, int maxBytes) throws Exception {
		int cap = Math.min(file.bytes(), maxBytes);
		byte[] cached = cached(file.sha256());
		if (cached != null) {
			return cached;
		}
		byte[] bytes;
		if ("file".equalsIgnoreCase(file.url().getScheme())) {
			bytes = local(file.url(), cap);
		} else {
			bytes = null;
			if (file.cdn() != null) {
				try {
					bytes = client.download(file.cdn(), cap).get(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
					if (bytes.length != file.bytes() || !sha256(bytes).equals(file.sha256())) {
						bytes = null;
					}
				} catch (ExecutionException | TimeoutException e) {
					// The Store's own address next.
				}
			}
			if (bytes == null) {
				bytes = client.download(file.url(), cap).get(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			}
		}
		if (bytes.length != file.bytes()) {
			throw new Refused("the file is " + bytes.length + " bytes, not the " + file.bytes() + " the Store said");
		}
		if (!sha256(bytes).equals(file.sha256())) {
			throw new Refused("the file isn't the one the Store hashed");
		}
		store(file.sha256(), bytes);
		return bytes;
	}

	private byte[] local(java.net.URI uri, int cap) throws IOException, Refused {
		Path root = devRoot;
		Path path = Path.of(uri).toAbsolutePath().normalize();
		if (root == null || !path.startsWith(root)) {
			throw new Refused("a local file outside the dev items folder");
		}
		if (Files.size(path) > cap) {
			throw new Refused("a local file larger than " + cap + " bytes");
		}
		return Files.readAllBytes(path);
	}

	private @Nullable byte[] cached(String sha256) {
		if (cacheDir == null) {
			return null;
		}
		Path f = cacheDir.resolve(sha256);
		try {
			if (!Files.isRegularFile(f) || Files.size(f) > CosmeticLimits.MAX_TEXTURE_BYTES) {
				return null;
			}
			byte[] bytes = Files.readAllBytes(f);
			if (sha256(bytes).equals(sha256)) {
				return bytes;
			}
			Files.deleteIfExists(f);
		} catch (IOException e) {
			// Read it again from the Store.
		}
		return null;
	}

	private void store(String sha256, byte[] bytes) {
		if (cacheDir == null) {
			return;
		}
		try {
			Files.createDirectories(cacheDir);
			Path f = cacheDir.resolve(sha256);
			if (Files.exists(f)) {
				return;
			}
			Path tmp = cacheDir.resolve(sha256 + ".tmp");
			Files.write(tmp, bytes);
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			if (!prunedCache) {
				prunedCache = true;
				pruneCache();
			}
		} catch (IOException e) {
			// No disk cache this time.
		}
	}

	private void pruneCache() {
		try (var files = Files.list(cacheDir)) {
			List<Path> all = new ArrayList<>(files.filter(Files::isRegularFile).toList());
			if (all.size() <= MAX_CACHE_FILES) {
				return;
			}
			all.sort(java.util.Comparator.comparing(p -> {
				try {
					return Files.getLastModifiedTime(p);
				} catch (IOException e) {
					return java.nio.file.attribute.FileTime.fromMillis(0);
				}
			}));
			for (int i = 0; i < all.size() - MAX_CACHE_FILES; i++) {
				Files.deleteIfExists(all.get(i));
			}
		} catch (IOException | RuntimeException e) {
			// Tried again next session.
		}
	}

	static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Takes what the loader finished. */
	private void take(long now) {
		Result<T> r;
		while ((r = results.poll()) != null) {
			Loaded<T> l = r.loaded();
			if (loaded.get(l.item.key()) != l || l.state != LOADING) {
				continue;
			}
			if (r.refused() != null || r.retry() != null) {
				l.state = FAILED;
				l.reason = r.refused() != null ? r.refused().getMessage() : r.retry();
				l.retryAt = now + (r.refused() != null ? REFUSED_NANOS : RETRY_NANOS);
				if (r.refused() != null) {
					refusedCount++;
				}
				FlintLog.LOGGER.warn("Flint 3D cosmetics: {} {} {}: {}", l.item.kind().id, l.item.id(), r.refused() != null ? "refused" : "not loaded",
					l.reason);
				continue;
			}
			l.model = r.model();
			l.animation = r.animation();
			l.image = r.image();
			l.state = READY;
			ready.add(l);
		}
	}

	/** Every tick: uploads at most {@code budget} textures that finished loading. Game thread. */
	public void upload(int budget) {
		take(now);
		takeThumbs();
		for (int done = 0; done < budget && !ready.isEmpty(); done++) {
			Loaded<T> l = ready.poll();
			if (l.state != READY || loaded.get(l.item.key()) != l) {
				continue;
			}
			CapePng.Image image = l.image;
			l.image = null;
			try {
				if (image != null) {
					if (texels() + (long) image.width() * image.height() > MAX_TEXELS) {
						evict(now);
					}
					l.texture = textures.upload("cosmetics3d/" + l.item.key().replace(':', '_').substring(0, Math.min(40, l.item.key().length()))
						+ "-" + Integer.toHexString(l.item.key().hashCode()), image.width(), image.height(), image.argb());
					l.textureWidth = image.width();
					l.textureHeight = image.height();
				}
				l.state = UPLOADED;
				dirty = true;
			} catch (RuntimeException e) {
				l.state = FAILED;
				l.retryAt = now + REFUSED_NANOS;
				l.reason = "couldn't be uploaded: " + e;
			}
		}
		if (dirty) {
			publish();
		}
	}

	private long texels() {
		long sum = 0;
		for (Loaded<T> l : loaded.values()) {
			if (l.texture != null) {
				sum += l.texels();
			}
		}
		return sum;
	}

	/** Drops the model used longest ago that nobody in view wears (one at a time). */
	private void evict(long now) {
		Loaded<T> oldest = null;
		for (Loaded<T> l : loaded.values()) {
			if (now - l.lastUsed > TimeUnit.SECONDS.toNanos(5) && (oldest == null || l.lastUsed - oldest.lastUsed < 0)) {
				oldest = l;
			}
		}
		if (oldest != null) {
			drop(oldest);
		}
	}

	private void release(long now) {
		List<Loaded<T>> gone = null;
		for (Loaded<T> l : loaded.values()) {
			if (now - l.lastUsed > RELEASE_NANOS) {
				if (gone == null) {
					gone = new ArrayList<>();
				}
				gone.add(l);
			}
		}
		if (gone != null) {
			gone.forEach(this::drop);
		}
	}

	private void drop(Loaded<T> l) {
		loaded.remove(l.item.key());
		dirty = true;
		publish();
		if (l.texture != null) {
			textures.release(l.texture);
			l.texture = null;
		}
		l.state = FAILED;
		l.retryAt = now;
	}

	@SuppressWarnings("unchecked")
	private void publish() {
		dirty = false;
		Map<UUID, Loaded<T>[]> next = new HashMap<>();
		Function<UUID, Loaded<T>[]> make = id -> (Loaded<T>[]) new Loaded<?>[CosmeticKind.WORN.length];
		if (enabled) {
			for (Map.Entry<UUID, Player> e : players.entrySet()) {
				fill(next, e.getKey(), e.getValue().items, make);
			}
			for (Map.Entry<UUID, ModelItem[]> e : extra.entrySet()) {
				fill(next, e.getKey(), e.getValue(), make);
			}
		}
		visible = Map.copyOf(next);
	}

	private void fill(Map<UUID, Loaded<T>[]> next, UUID id, ModelItem[] items, Function<UUID, Loaded<T>[]> make) {
		for (int k = 0; k < items.length; k++) {
			ModelItem item = items[k];
			Loaded<T> l = item == null ? null : loaded.get(item.key());
			if (l != null && l.state == UPLOADED) {
				next.computeIfAbsent(id, make)[k] = l;
			}
		}
	}

	/** Forgets everything and releases every texture (cosmetics off, another Store). Game thread. */
	public void clear() {
		visible = Map.of();
		for (Loaded<T> l : loaded.values()) {
			if (l.texture != null) {
				textures.release(l.texture);
				l.texture = null;
			}
			l.state = FAILED;
		}
		loaded.clear();
		ready.clear();
		for (Thumb<T> t : thumbs.values()) {
			if (t.texture != null) {
				textures.release(t.texture);
				t.texture = null;
			}
			t.state = FAILED;
		}
		thumbs.clear();
		players.clear();
		dirty = false;
	}

	/** One line for the log or a dev tour. */
	public String describe() {
		int uploaded = 0;
		int loading = 0;
		int failed = 0;
		for (Loaded<T> l : loaded.values()) {
			switch (l.state) {
				case UPLOADED -> uploaded++;
				case FAILED -> failed++;
				default -> loading++;
			}
		}
		return "3D cosmetics: " + players.size() + " players known, " + visible.size() + " shown, " + uploaded + " models up, " + loading
			+ " loading, " + failed + " failed (" + refusedCount + " refused), " + texels() / 1024 + " K texels, " + answers + " answers";
	}

	/** The reason {@code item} failed, or null (dev tours). */
	public @Nullable String failure(ModelItem item) {
		Loaded<T> l = loaded.get(item.key());
		return l == null ? null : l.reason;
	}
}
