package com.flintclient.client.control.feature.map;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * The map of one dimension of one world or server: its regions, read from and written to its folder
 * ({@code flint/maps/<world>/<dimension>/}) on one background thread, or kept only in memory (a cave layer: no folder).
 *
 * <ul>
 * <li>Regions are read when asked for ({@link #request}): the preview part for far, zoomed-out views, everything near the
 * player and for close views. Until a file is read the region draws as nothing; the scanner can write into a region
 * meanwhile, and what it saw wins over the file.</li>
 * <li>A changed region is written once it has been quiet for a while, at most every minute while it keeps changing, when it
 * leaves memory and when the world is left. Writes are copies, so the client thread never waits for the disk.</li>
 * <li>At most {@link #maxFull} regions keep their full data in memory (the least recently used shrink to their preview).</li>
 * <li>Biome names are kept once per map ({@code biomes.txt}); columns store an index.</li>
 * </ul>
 * Version-neutral. Client thread, except the file work.
 */
public final class MapWorld {
	private static final long QUIET_TICKS = 200;
	private static final long LONGEST_TICKS = 1200;
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Flint map files");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});
	private static final Set<MapWorld> OPEN = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
	private static boolean hooked;

	private final @Nullable Path folder;
	private final Map<Long, MapRegion> regions = new HashMap<>();
	private final Set<Long> onDisk = new HashSet<>();
	private final ConcurrentLinkedQueue<Runnable> done = new ConcurrentLinkedQueue<>();
	private final List<String> biomeNames = new ArrayList<>();
	private final Map<String, Integer> biomeIndex = new HashMap<>();
	private final Map<Long, Long> changedAt = new HashMap<>();
	private final Map<Long, Long> firstChangeAt = new HashMap<>();
	private boolean biomesChanged;
	private boolean listed;
	private volatile boolean closed;
	private long tick;
	private int maxFull = 40;
	private int revision;
	private int caveTop;
	private int caveDepth;
	private MapShading.@Nullable Look lookBase;
	private MapShading.@Nullable Look caveLook;

	/** A map kept in {@code folder} (read lazily), or in memory only when it is null. */
	public MapWorld(@Nullable Path folder) {
		this.folder = folder;
		if (folder != null) {
			hook();
			OPEN.add(this);
			IO.execute(this::list);
		} else {
			listed = true;
		}
	}

	private static synchronized void hook() {
		if (!hooked) {
			hooked = true;
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				for (MapWorld world : OPEN) {
					world.writeAllNow();
				}
			}, "Flint map save"));
		}
	}

	public @Nullable Path folder() {
		return folder;
	}

	public void maxFull(int value) {
		maxFull = Math.max(8, value);
	}

	/** Makes this a cave layer read from {@code top} down {@code depth} blocks (its shading runs its terrain depth down it). */
	public MapWorld cave(int top, int depth) {
		if (top != caveTop || depth != caveDepth) {
			caveTop = top;
			caveDepth = depth;
			caveLook = null;
		}
		return this;
	}

	/** A cave layer's start (the highest block read), or 0 for a surface map. */
	public int caveTop() {
		return caveTop;
	}

	/** How far down a cave layer reads, or 0 for a surface map. */
	public int caveDepth() {
		return caveDepth;
	}

	/** {@code base} as this map's textures use it: a cave layer's own (made once per look). */
	public MapShading.Look look(MapShading.Look base) {
		if (caveDepth <= 0) {
			return base;
		}
		if (lookBase != base || caveLook == null) {
			lookBase = base;
			caveLook = base.cave(caveTop, caveDepth);
		}
		return caveLook;
	}

	/** Grows whenever a region arrives from its file or a region is added (views that list regions cache by it). */
	public int revision() {
		return revision;
	}

	// ---------- reading the folder ----------

	private void list() {
		Path dir = folder;
		Set<Long> found = new HashSet<>();
		List<String> names = new ArrayList<>();
		try {
			if (dir != null && Files.isDirectory(dir)) {
				try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "r.*.fmap")) {
					for (Path file : files) {
						Long key = MapRegionFile.parse(file.getFileName().toString());
						if (key != null) {
							found.add(key);
						}
					}
				}
				Path biomes = dir.resolve("biomes.txt");
				if (Files.isRegularFile(biomes)) {
					for (String line : Files.readAllLines(biomes, StandardCharsets.UTF_8)) {
						names.add(line.strip());
					}
				}
			}
		} catch (IOException e) {
			// An unreadable folder: the map starts empty and is written again as you explore.
		}
		done.add(() -> {
			onDisk.addAll(found);
			if (biomeNames.isEmpty()) {
				for (String name : names) {
					biomeIndex.put(name, biomeNames.size());
					biomeNames.add(name);
				}
			}
			listed = true;
			revision++;
		});
	}

	/** Whether the folder has been listed (until then, which regions exist on disk isn't known). */
	public boolean listed() {
		return listed;
	}

	/** Every region with data: on disk or in memory, as {@link MapRegion#key}s (a copy). */
	public Set<Long> explored() {
		Set<Long> all = new HashSet<>(onDisk);
		for (Map.Entry<Long, MapRegion> e : regions.entrySet()) {
			if (!e.getValue().empty()) {
				all.add(e.getKey());
			}
		}
		return all;
	}

	// ---------- regions ----------

	/** The region if it is in memory (full or preview), else null. Marks it used. */
	public @Nullable MapRegion region(int rx, int rz) {
		MapRegion r = regions.get(MapRegion.key(rx, rz));
		if (r != null) {
			r.lastUse = tick;
		}
		return r;
	}

	/**
	 * Asks for a region: its preview ({@code full} false) or everything. Returns it when what was asked for is in memory,
	 * else null (it is read in the background when it is on disk).
	 */
	public @Nullable MapRegion request(int rx, int rz, boolean full) {
		long key = MapRegion.key(rx, rz);
		MapRegion r = regions.get(key);
		if (r != null) {
			r.lastUse = tick;
			if (full && !r.full() && !r.fullRequested) {
				r.fullRequested = true;
				if (onDisk.contains(key) && folder != null) {
					read(r, true);
				} else {
					r.allocate();
				}
			}
			return full ? r.full() ? r : null : r;
		}
		if (!listed || !onDisk.contains(key) || folder == null) {
			return null;
		}
		r = new MapRegion(rx, rz, false);
		r.lastUse = tick;
		r.fullRequested = full;
		regions.put(key, r);
		read(r, full);
		return null;
	}

	/** The region to write a scanned chunk into: in memory and full (its file, if any, merges in when read). */
	public MapRegion forWrite(int rx, int rz) {
		long key = MapRegion.key(rx, rz);
		MapRegion r = regions.get(key);
		if (r == null) {
			r = new MapRegion(rx, rz, true);
			r.fullRequested = true;
			regions.put(key, r);
			revision++;
			if (onDisk.contains(key) && folder != null) {
				read(r, true);
			}
		} else if (!r.full()) {
			r.allocate();
			if (!r.fullRequested) {
				r.fullRequested = true;
				if (onDisk.contains(key) && folder != null) {
					read(r, true);
				}
			}
		}
		r.lastUse = tick;
		return r;
	}

	/** Notes that a region changed (the scanner wrote into it), for its next write. */
	public void changed(MapRegion r) {
		long key = MapRegion.key(r.x, r.z);
		changedAt.put(key, tick);
		firstChangeAt.putIfAbsent(key, tick);
	}

	private void read(MapRegion r, boolean full) {
		Path dir = folder;
		if (dir == null || r.loading && !full) {
			return;
		}
		r.loading = true;
		Path file = dir.resolve(MapRegionFile.name(r.x, r.z));
		IO.execute(() -> {
			if (closed) {
				return;
			}
			MapRegion.Snapshot snapshot;
			try {
				snapshot = Files.isRegularFile(file) ? MapRegionFile.read(file, full) : null;
			} catch (IOException | RuntimeException e) {
				snapshot = null;
			}
			MapRegion.Snapshot read = snapshot;
			done.add(() -> {
				r.loading = false;
				if (read != null && regions.get(MapRegion.key(r.x, r.z)) == r) {
					r.merge(read);
					revision++;
				}
			});
		});
	}

	// ---------- biomes ----------

	/** The index + 1 of a biome's name (added to the palette the first time); 0 when the palette is full. */
	public int biome(String name) {
		Integer at = biomeIndex.get(name);
		if (at != null) {
			return at + 1;
		}
		if (biomeNames.size() >= 255) {
			return 0;
		}
		biomeIndex.put(name, biomeNames.size());
		biomeNames.add(name);
		biomesChanged = true;
		return biomeNames.size();
	}

	/** The biome name for an index + 1, or null. */
	public @Nullable String biomeName(int index) {
		return index <= 0 || index > biomeNames.size() ? null : biomeNames.get(index - 1);
	}

	// ---------- upkeep ----------

	/** Once per client tick: takes in finished reads, writes quiet regions, frees memory. */
	public void tick() {
		tick++;
		for (Runnable r; (r = done.poll()) != null; ) {
			r.run();
		}
		if (folder == null) {
			return;
		}
		if (!changedAt.isEmpty()) {
			List<Long> due = new ArrayList<>();
			for (Map.Entry<Long, Long> e : changedAt.entrySet()) {
				long first = firstChangeAt.getOrDefault(e.getKey(), e.getValue());
				if (tick - e.getValue() >= QUIET_TICKS || tick - first >= LONGEST_TICKS) {
					due.add(e.getKey());
				}
			}
			for (Long key : due) {
				MapRegion r = regions.get(key);
				changedAt.remove(key);
				firstChangeAt.remove(key);
				if (r != null) {
					save(r);
				}
			}
		}
		if (biomesChanged) {
			biomesChanged = false;
			List<String> names = List.copyOf(biomeNames);
			Path dir = folder;
			IO.execute(() -> writeBiomes(dir, names));
		}
		if ((tick & 31) == 0) {
			shrink();
		}
	}

	private void save(MapRegion r) {
		if (!r.unsaved() || !r.full() || r.loading || folder == null) {
			return;
		}
		MapRegion.Snapshot snapshot = r.snapshot();
		Path file = folder.resolve(MapRegionFile.name(r.x, r.z));
		long key = MapRegion.key(r.x, r.z);
		onDisk.add(key);
		IO.execute(() -> {
			try {
				MapRegionFile.write(file, snapshot);
			} catch (IOException e) {
				// The next change writes it again.
			}
		});
	}

	private static void writeBiomes(@Nullable Path dir, List<String> names) {
		if (dir == null) {
			return;
		}
		try {
			Files.createDirectories(dir);
			Path temp = dir.resolve("biomes.txt.tmp");
			Files.write(temp, names, StandardCharsets.UTF_8);
			Files.move(temp, dir.resolve("biomes.txt"), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
				java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			// Written again with the next new biome.
		}
	}

	/** Keeps at most {@link #maxFull} full regions (and drops long-unused previews), writing what changed first. */
	private void shrink() {
		int full = 0;
		for (MapRegion r : regions.values()) {
			full += r.full() ? 1 : 0;
		}
		if (full > maxFull) {
			List<MapRegion> byAge = new ArrayList<>();
			for (MapRegion r : regions.values()) {
				if (r.full() && !r.loading) {
					byAge.add(r);
				}
			}
			byAge.sort(java.util.Comparator.comparingLong(r -> r.lastUse));
			for (int i = 0; i < byAge.size() && full > maxFull; i++) {
				MapRegion r = byAge.get(i);
				if (tick - r.lastUse < 40) {
					break;
				}
				if (folder == null) {
					regions.remove(MapRegion.key(r.x, r.z));
				} else {
					save(r);
					r.updatePreview();
					r.shrink();
					r.fullRequested = false;
				}
				full--;
			}
		}
		if (regions.size() > 4096) {
			regions.values().removeIf(r -> !r.full() && !r.loading && tick - r.lastUse > 1200);
		}
	}

	/** Every region in memory (for views that walk them); not to be changed. */
	public Iterable<MapRegion> loaded() {
		return regions.values();
	}

	/** Forgets every region (a cave layer that moved, or the map was cleared); nothing is written. */
	public void clear() {
		regions.clear();
		changedAt.clear();
		firstChangeAt.clear();
		revision++;
	}

	/** Writes everything that changed and stops (the world was left). Reads still running are dropped. */
	public void close() {
		if (folder != null) {
			for (MapRegion r : regions.values()) {
				save(r);
			}
			if (biomesChanged) {
				biomesChanged = false;
				List<String> names = List.copyOf(biomeNames);
				Path dir = folder;
				IO.execute(() -> writeBiomes(dir, names));
			}
			IO.execute(() -> OPEN.remove(this));
		}
		closed = true;
	}

	/** At exit: writes every unsaved region on this thread (the game loop has stopped). */
	private void writeAllNow() {
		if (closed || folder == null) {
			return;
		}
		try {
			IO.shutdown();
			IO.awaitTermination(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		for (MapRegion r : new ArrayList<>(regions.values())) {
			if (r.unsaved() && r.full()) {
				try {
					MapRegionFile.write(folder.resolve(MapRegionFile.name(r.x, r.z)), r.snapshot());
				} catch (IOException | RuntimeException e) {
					// Nothing more to do at exit.
				}
			}
		}
	}

	/** For tests and tools: runs whatever the file thread finished. */
	void drain() {
		for (Runnable r; (r = done.poll()) != null; ) {
			r.run();
		}
	}

	/** For tests: waits until the file thread has done what was queued so far. */
	static void settle() throws Exception {
		IO.submit(() -> { }).get(10, TimeUnit.SECONDS);
	}

	/** Walks the regions in memory, full or not. */
	public void forEach(Consumer<MapRegion> action) {
		regions.values().forEach(action);
	}
}
