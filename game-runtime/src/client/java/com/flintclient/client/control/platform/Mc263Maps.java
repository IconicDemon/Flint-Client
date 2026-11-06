package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.Features;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.feature.WaypointStore;
import com.flintclient.client.control.feature.map.MapShading;
import com.flintclient.client.control.feature.map.MapWorld;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.MapMods.Minimap;
import com.flintclient.client.control.model.mods.MapMods.WorldMap;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * The maps' upkeep, once per client tick while the Minimap or the World Map is on:
 *
 * <ul>
 * <li><b>Which map.</b> One per world or server and dimension ({@code flint/maps/<world>/<dimension>}); switching world or
 * dimension writes the old one and opens the new.</li>
 * <li><b>Scanning.</b> The loaded chunks around you are swept a slice per tick, nearest first; a chunk is read when it is
 * new to the map, was sent again, or had a block change since it was last read (Minecraft marks changed chunks; the map
 * reads that mark and clears it). Reading stops for the tick when its time budget (about a millisecond, more while a lot is
 * waiting) runs out. A rescan that finds the same thing changes nothing.</li>
 * <li><b>Cave mode.</b> Underground (rock over your head and no sky light for a second) or by its key, a cave layer is read
 * from just above you down into the chunks the minimap shows. A height chosen for the minimap (its Layer height, or its layer
 * keys) or on the world map's layer slider is read the same way, from that height down; as Xaero's, layers are kept per 16
 * blocks of height on disk beside the surface map, only what was read at that height shows, and a few are kept in memory.</li>
 * <li><b>Keys.</b> The world map's key, the minimap's zoom keys, its cave key and its layer keys.</li>
 * </ul>
 * Fault-isolated: the first exception turns the maps off for the session. Client thread.
 */
public final class Mc263Maps {
	static final int MINIMAP = Mods.index(Minimap.ID);
	static final int WORLD_MAP = Mods.index(WorldMap.ID);
	private static final HookGuard GUARD = new HookGuard("maps",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} path failed; it's off for this session", path, error));
	/** Chunk offsets around you out to 32, nearest first. */
	private static final int[] RING = ring(32);
	private static final int CAVES_KEPT = 4;

	private static final BlockPos.MutableBlockPos ROOF = new BlockPos.MutableBlockPos();
	private static @Nullable String sessionKey;
	private static @Nullable MapWorld surface;
	/** The open dimension's maps folder (its surface map; cave layers under caves/), or null. */
	private static @Nullable Path dimensionFolder;
	private static @Nullable String dimension;
	private static boolean ceiling;
	private static int ceilingStart;
	private static final Map<Long, Integer> SEEN = new HashMap<>();
	private static final Map<Long, Integer> CAVE_SEEN = new HashMap<>();
	private static final LinkedHashMap<Long, Boolean> QUEUE = new LinkedHashMap<>();
	private static final Map<Integer, MapWorld> CAVES = new LinkedHashMap<>(8, 0.75F, true);
	private static @Nullable MapWorld cave;
	private static int caveStart = Integer.MIN_VALUE;
	private static boolean caveOn;
	/** A layer is being read (the minimap's cave mode, or the world map's chosen height); {@link #caveStart} is its top. */
	private static boolean layerOn;
	private static int caveTicks;
	private static boolean caveManual;
	private static boolean caveKeyWas;
	private static boolean layerUpWas;
	private static boolean layerDownWas;
	/**
	 * The world map's chosen height while it is open on this dimension (Integer.MIN_VALUE: none, it shows Auto or the
	 * surface); set by {@link WorldMapScreen} each frame.
	 */
	private static int worldMapLayer = Integer.MIN_VALUE;
	private static boolean openKeyWas;
	private static boolean zoomInWas;
	private static boolean zoomOutWas;
	private static boolean enlarged;
	private static float zoomShown = Float.NaN;
	private static long zoomFrame;
	private static int sweep;
	private static Mc263MapScanner.@Nullable Options options;
	private static long scanNanos;
	private static int scanned;

	private Mc263Maps() {
	}

	private static int[] ring(int radius) {
		List<int[]> all = new ArrayList<>();
		for (int dz = -radius; dz <= radius; dz++) {
			for (int dx = -radius; dx <= radius; dx++) {
				all.add(new int[] {dx, dz});
			}
		}
		all.sort(java.util.Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1]));
		int[] out = new int[all.size() * 2];
		for (int i = 0; i < all.size(); i++) {
			out[2 * i] = all.get(i)[0];
			out[2 * i + 1] = all.get(i)[1];
		}
		return out;
	}

	/** Whether either map is on. */
	public static boolean on() {
		return Mods.on(MINIMAP) || Mods.on(WORLD_MAP);
	}

	/** The surface map of where you are, or null (no world, or the maps are off). */
	public static @Nullable MapWorld surface() {
		return surface;
	}

	/**
	 * The layer being read now, or null: the minimap's cave layer while it shows one (cave mode, or a height chosen for it),
	 * or the world map's chosen height while the world map shows one here.
	 */
	public static @Nullable MapWorld caveLayer() {
		return layerOn ? cave : null;
	}

	/** The dimension the open map is of ("minecraft:overworld"), or null. */
	public static @Nullable String dimension() {
		return dimension;
	}

	/** The world's maps folder, or null outside a world. */
	static @Nullable Path worldFolder() {
		String key = Features.waypoints().worldKey();
		if (key == null) {
			return null;
		}
		return Minecraft.getInstance().gameDirectory.toPath().resolve("flint").resolve("maps").resolve(WaypointStore.safeName(key));
	}

	/** A dimension's maps folder name: "minecraft_the_nether". */
	static String folderName(String dimensionId) {
		return dimensionId.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_");
	}

	// ---------- the tick ----------

	/** From the client tick. */
	public static void tick() {
		if (!GUARD.on()) {
			return;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			ClientLevel level = mc.level;
			LocalPlayer player = mc.player;
			if (level == null || player == null || !on()) {
				close();
				return;
			}
			open(mc, level);
			MapWorld world = surface;
			if (world == null) {
				return;
			}
			keys(mc, level, player);
			readOptions(level);
			world.tick();
			updateCave(level, player);
			if (cave != null) {
				cave.tick();
			}
			scan(level, player);
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			close();
		}
	}

	private static void open(Minecraft mc, ClientLevel level) {
		String dim = level.dimension().identifier().toString();
		String world = Features.waypoints().worldKey();
		String key = world == null ? null : world + "|" + dim;
		if (java.util.Objects.equals(key, sessionKey)) {
			return;
		}
		close();
		if (key == null) {
			return;
		}
		Path folder = worldFolder();
		sessionKey = key;
		dimension = dim;
		dimensionFolder = folder == null ? null : folder.resolve(folderName(dim));
		surface = new MapWorld(dimensionFolder);
		ceiling = level.dimensionType().hasCeiling();
		ceilingStart = level.getMinY() + level.dimensionType().logicalHeight() - 1;
		Mc263MapScanner.forgetColors();
		WorldMapScreen.worldChanged();
	}

	/** Writes the open map and lets it go (the world was left, or the maps were turned off). */
	static void close() {
		if (surface != null) {
			surface.close();
			Mc263MapTiles.release(null);
		}
		surface = null;
		sessionKey = null;
		dimension = null;
		dimensionFolder = null;
		SEEN.clear();
		CAVE_SEEN.clear();
		QUEUE.clear();
		closeCaves();
		cave = null;
		caveOn = false;
		layerOn = false;
		caveStart = Integer.MIN_VALUE;
	}

	/** The world map, each frame while it shows this dimension: the height it shows ({@code y}), or Integer.MIN_VALUE for none. */
	static void worldMapLayer(int y) {
		worldMapLayer = y;
	}

	/** A height clamped into {@code level}'s blocks (a layer above the world's top starts at its top). */
	static int clampY(ClientLevel level, int y) {
		return Math.clamp(y, bottomY(level), topY(level));
	}

	/** The lowest block height of {@code level}. */
	static int bottomY(ClientLevel level) {
		return level.getMinY();
	}

	/** The highest block height of {@code level}. */
	static int topY(ClientLevel level) {
		return level.getMinY() + level.getHeight() - 1;
	}

	/** Writes and lets go every cave layer kept. */
	private static void closeCaves() {
		for (MapWorld layer : CAVES.values()) {
			Mc263MapTiles.release(layer);
			layer.close();
		}
		CAVES.clear();
	}

	private static void readOptions(ClientLevel level) {
		ModuleState wm = Mods.state(WORLD_MAP);
		Mc263MapScanner.Options now = new Mc263MapScanner.Options(wm.option(WorldMap.COLORS) == 1 ? 1 : 0, wm.bool(WorldMap.SEE_WATER),
			wm.bool(WorldMap.PLANTS), wm.bool(WorldMap.GLASS));
		if (!now.equals(options)) {
			if (options != null) {
				// What a column looks like changed: every loaded chunk is read again (the rest as you pass by).
				Mc263MapScanner.forgetColors();
				SEEN.clear();
				CAVE_SEEN.clear();
				closeCaves();
				cave = null;
				caveStart = Integer.MIN_VALUE;
			}
			options = now;
		}
		String dim = level.dimension().identifier().toString();
		Mc263MapTiles.look(new MapShading.Look(wm.option(WorldMap.SHADING), wm.bool(WorldMap.HEIGHT_TINT), wm.option(WorldMap.COLORS) == 2,
			level.getSeaLevel(), dim.equals("minecraft:overworld") ? MapShading.OVERWORLD_SHADOW : dim.equals("minecraft:the_nether")
				? MapShading.NETHER_SHADOW : MapShading.OTHER_SHADOW));
	}

	// ---------- keys ----------

	private static void keys(Minecraft mc, ClientLevel level, LocalPlayer player) {
		boolean play = Mc263Features.gameplay();
		ModuleState mini = Mods.state(MINIMAP);
		boolean open = play && Mods.on(WORLD_MAP) && Mc263Features.keyHeld(Mods.state(WORLD_MAP).text(WorldMap.OPEN_KEY));
		if (open && !openKeyWas) {
			mc.gui.setScreen(new WorldMapScreen());
		}
		openKeyWas = open;
		boolean minimap = Mods.on(MINIMAP);
		boolean in = play && minimap && Mc263Features.keyHeld(mini.text(Minimap.ZOOM_IN_KEY));
		boolean out = play && minimap && Mc263Features.keyHeld(mini.text(Minimap.ZOOM_OUT_KEY));
		if (in && !zoomInWas || out && !zoomOutWas) {
			// Xaero's: the keys go round the steps (past 6 back to the first), passing steps that look the same now (in
			// caves, where the zoom doubles up to 6).
			int n = Minimap.ZOOM.size();
			int zoom = mini.option(Minimap.ZOOM);
			float before = zoomTarget(zoom);
			int next = zoom;
			for (int i = 0; i < n; i++) {
				next = Math.floorMod(next + (in && !zoomInWas ? 1 : -1), n);
				if (zoomTarget(next) != before) {
					break;
				}
			}
			mini.set(Minimap.ZOOM, next);
			Mc263Features.actionbar(Component.literal("Minimap zoom " + Minimap.ZOOM.labels().get(next)));
		}
		zoomInWas = in;
		enlarged = play && minimap && Mc263Features.keyHeld(mini.text(Minimap.ENLARGE_KEY));
		zoomOutWas = out;
		boolean caveKey = play && minimap && Mc263Features.keyHeld(mini.text(Minimap.CAVE_KEY));
		if (caveKey && !caveKeyWas) {
			caveManual = !caveOn;
			caveOn = caveManual;
			caveTicks = 0;
			Mc263Features.actionbar(Component.literal(caveOn ? "Cave mode on" : "Cave mode off"));
		}
		caveKeyWas = caveKey;
		// The layer keys: the minimap's height 16 blocks up or down (from Auto it starts where the map reads now, or your feet).
		boolean up = play && minimap && Mc263Features.keyHeld(mini.text(Minimap.LAYER_UP_KEY));
		boolean down = play && minimap && Mc263Features.keyHeld(mini.text(Minimap.LAYER_DOWN_KEY));
		if (up && !layerUpWas || down && !layerDownWas) {
			int next = stepLayer(level, player, mini.integer(Minimap.LAYER), up && !layerUpWas ? 16 : -16);
			mini.set(Minimap.LAYER, next);
			caveTicks = 0;
			Mc263Features.actionbar(Component.literal(next == Minimap.LAYER_AUTO ? "Minimap layer: Auto" : "Minimap layer: Y " + next));
		}
		layerUpWas = up;
		layerDownWas = down;
	}

	/**
	 * The minimap's layer height {@code by} blocks from {@code now}: from Auto, from the layer shown (or your feet); past the
	 * world's top, Auto again; never below its bottom.
	 */
	private static int stepLayer(ClientLevel level, LocalPlayer player, int now, int by) {
		int top = Math.min(Minimap.LAYER.max(), level.getMinY() + level.getHeight() - 1);
		int bottom = Math.max(Minimap.LAYER_AUTO + 1, level.getMinY());
		if (now == Minimap.LAYER_AUTO && !layerOn) {
			// On the surface: up has nothing higher to show; down starts at your feet (the first cave under you shows).
			return by > 0 ? Minimap.LAYER_AUTO : Math.clamp(Mth.floor(player.getY()), bottom, top);
		}
		int base = now != Minimap.LAYER_AUTO ? now : caveStart;
		int next = base + by;
		if (next > top) {
			return Minimap.LAYER_AUTO;
		}
		return Math.max(bottom, next);
	}

	/**
	 * The minimap's zoom in pixels per block (GUI units) for zoom step {@code index}: as Xaero's, a block is half the step in
	 * pixels; underground with Zoom in underground the step doubles, to 6 at most.
	 */
	static float zoomTarget(int index) {
		float step = Float.parseFloat(Minimap.ZOOM.keys().get(Math.clamp(index, 0, Minimap.ZOOM.size() - 1)));
		if (caveOn && Mods.state(MINIMAP).bool(Minimap.CAVE_ZOOM)) {
			step = Math.min(6, Math.round(step * 2));
		}
		return step / 2;
	}

	/** The zoom the minimap is drawn at this frame: eased toward its step as Xaero's (a fifth of the way each 60th of a second). */
	static float minimapZoom() {
		float target = zoomTarget(Mods.state(MINIMAP).option(Minimap.ZOOM));
		long now = System.nanoTime();
		if (Float.isNaN(zoomShown) || now - zoomFrame > 1_000_000_000L) {
			zoomShown = target;
		} else {
			float off = target - zoomShown;
			zoomShown = Math.abs(off) < 0.002F ? target : target - off * (float) Math.pow(0.8, (now - zoomFrame) / 16_666_667.0);
		}
		zoomFrame = now;
		return zoomShown;
	}

	// ---------- cave mode ----------

	private static void updateCave(ClientLevel level, LocalPlayer player) {
		ModuleState mini = Mods.state(MINIMAP);
		int mode = Mods.on(MINIMAP) ? mini.option(Minimap.CAVE_MODE) : 0;
		int roofed = mode == 0 && !caveManual ? Integer.MAX_VALUE : caving(level, player);
		boolean want;
		if (mode == 0) {
			want = false;
			caveManual = false;
		} else if (mode == 2) {
			want = true;
		} else {
			want = roofed != Integer.MAX_VALUE;
		}
		if (caveManual) {
			want = true;
		}
		// A height chosen for the minimap (its setting or its layer keys, as Xaero's manual cave mode): always that layer.
		int chosen = Mods.on(MINIMAP) ? mini.integer(Minimap.LAYER) : Minimap.LAYER_AUTO;
		if (chosen != Minimap.LAYER_AUTO) {
			want = true;
		}
		if (want != caveOn) {
			// A second either way before it switches (Xaero's toggle timer: no flicker under a tree or a ledge).
			if (++caveTicks >= (caveManual || chosen != Minimap.LAYER_AUTO ? 0 : 20)) {
				caveOn = want;
				caveTicks = 0;
			}
		} else {
			caveTicks = 0;
		}
		int start;
		if (worldMapLayer != Integer.MIN_VALUE) {
			// The world map is open on a height: its layer is read round you (the minimap doesn't show behind it).
			start = clampY(level, worldMapLayer);
		} else if (!caveOn) {
			layerOn = false;
			return;
		} else if (chosen != Minimap.LAYER_AUTO) {
			start = clampY(level, chosen);
		} else {
			// Where the layer starts (Xaero's): the roof right over you, at most three blocks over your head.
			start = roofed != Integer.MAX_VALUE ? roofed : Mth.floor(player.getY()) + 4;
		}
		layerOn = true;
		int depth = mini.integer(Minimap.CAVE_DEPTH);
		if (start != caveStart) {
			caveStart = start;
			// Layers are kept per 16 blocks of height (as Xaero's), on disk beside the surface map.
			int band = Math.floorDiv(start, 16);
			MapWorld layer = CAVES.get(band);
			if (layer == null) {
				layer = new MapWorld(dimensionFolder == null ? null : dimensionFolder.resolve("caves").resolve(Integer.toString(band)));
				layer.maxFull(12);
				CAVES.put(band, layer);
				while (CAVES.size() > CAVES_KEPT) {
					Integer oldest = CAVES.keySet().iterator().next();
					MapWorld dropped = CAVES.remove(oldest);
					if (dropped != null) {
						Mc263MapTiles.release(dropped);
						dropped.close();
					}
				}
			}
			cave = layer;
			CAVE_SEEN.clear();
		}
		if (cave != null) {
			cave.cave(start, depth);
		}
	}

	/**
	 * Xaero's auto cave test: on every column of the 3 × 3 round you (at your head), no full sky light and something solid
	 * between there and the top of the column. Returns where the layer starts (the roof over you, at most three blocks over
	 * your head), or Integer.MAX_VALUE in the open. Always true under the Nether's roof.
	 */
	private static int caving(ClientLevel level, LocalPlayer player) {
		int y = Mth.floor(player.getY()) + 1;
		int bottom = level.getMinY();
		int topY = bottom + level.getHeight() - 1;
		if (y < bottom || y > topY) {
			return Integer.MAX_VALUE;
		}
		int x = Mth.floor(player.getX());
		int z = Mth.floor(player.getZ());
		int result = y + 3;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				int cx = x + dx;
				int cz = z + dz;
				LevelChunk chunk = level.getChunkSource().getChunk(cx >> 4, cz >> 4, ChunkStatus.FULL, false);
				if (chunk == null || level.getBrightness(LightLayer.SKY, ROOF.set(cx, y, cz)) >= 15) {
					return Integer.MAX_VALUE;
				}
				int top = Math.min(topY, chunk.getHeight(Heightmap.Types.WORLD_SURFACE, cx & 15, cz & 15));
				boolean roof = false;
				for (int ry = y; ry <= top; ry++) {
					if (Mc263MapScanner.roof(chunk.getBlockState(ROOF.set(cx, ry, cz)))) {
						if (dx == 0 && dz == 0) {
							result = Math.min(ry, y + 3);
						}
						roof = true;
						break;
					}
				}
				if (!roof) {
					return Integer.MAX_VALUE;
				}
			}
		}
		return result;
	}

	// ---------- scanning ----------

	private static long chunkKey(int cx, int cz) {
		return (long) cx << 32 | cz & 0xFFFFFFFFL;
	}

	private static void scan(ClientLevel level, LocalPlayer player) {
		MapWorld world = surface;
		Mc263MapScanner.Options o = options;
		if (world == null || o == null) {
			return;
		}
		int pcx = Mth.floor(player.getX()) >> 4;
		int pcz = Mth.floor(player.getZ()) >> 4;
		int radius = Math.min(32, Minecraft.getInstance().options.renderDistance().get() + 1);
		int count = (2 * radius + 1) * (2 * radius + 1);
		// The nearest few chunks every tick; the rest a slice at a time.
		int near = Math.min(count, 49);
		look(level, world, pcx, pcz, 0, near);
		int slice = Math.max(64, (count - near) / 8);
		int from = near + sweep;
		look(level, world, pcx, pcz, from, Math.min(count, from + slice));
		sweep = from + slice >= count ? 0 : sweep + slice;
		long start = System.nanoTime();
		long budget = QUEUE.size() > 64 ? 2_500_000L : 1_000_000L;
		// The cave layer is read as far as the minimap shows, and further while the world map is open (it shows the layer too).
		int caveRadius = !layerOn ? -1
			: Minecraft.getInstance().gui.screen() instanceof WorldMapScreen ? Math.max(caveRadius(), Math.min(12, radius - 1)) : caveRadius();
		var it = QUEUE.keySet().iterator();
		while (it.hasNext() && System.nanoTime() - start < budget) {
			long key = it.next();
			it.remove();
			int cx = (int) (key >> 32);
			int cz = (int) key;
			LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
			if (chunk == null) {
				continue;
			}
			int id = System.identityHashCode(chunk);
			if (!SEEN.containsKey(key) || SEEN.get(key) != id || chunk.isUnsaved()) {
				Mc263MapScanner.scan(world, level, chunk, o, ceiling ? ceilingStart : Integer.MIN_VALUE, ceiling ? level.getHeight() : 0, false);
				SEEN.put(key, id);
				CAVE_SEEN.remove(key);
				chunk.tryMarkSaved();
				scanned++;
			}
		}
		// The cave layer: the chunks the minimap shows, nearest first.
		MapWorld layer = cave;
		if (layerOn && layer != null) {
			int depth = Mods.state(MINIMAP).integer(Minimap.CAVE_DEPTH);
			for (int i = 0; i < RING.length / 2 && System.nanoTime() - start < budget + 500_000L; i++) {
				int dx = RING[2 * i];
				int dz = RING[2 * i + 1];
				if (Math.max(Math.abs(dx), Math.abs(dz)) > caveRadius) {
					if (dx * dx + dz * dz > caveRadius * caveRadius * 2) {
						break;
					}
					continue;
				}
				long key = chunkKey(pcx + dx, pcz + dz);
				LevelChunk chunk = level.getChunkSource().getChunk(pcx + dx, pcz + dz, ChunkStatus.FULL, false);
				if (chunk == null) {
					continue;
				}
				int id = System.identityHashCode(chunk);
				Integer seen = CAVE_SEEN.get(key);
				if (seen == null || seen != id) {
					Mc263MapScanner.scan(layer, level, chunk, o, caveStart, depth, true);
					CAVE_SEEN.put(key, id);
				}
			}
		}
		scanNanos += System.nanoTime() - start;
		if ((level.getGameTime() & 1023) == 0) {
			trimSeen(pcx, pcz, radius + 4);
		}
	}

	/** Queues the chunks at ring positions [from, to) that are loaded and new or changed. */
	private static void look(ClientLevel level, MapWorld world, int pcx, int pcz, int from, int to) {
		var source = level.getChunkSource();
		for (int i = from; i < to && 2 * i + 1 < RING.length; i++) {
			int cx = pcx + RING[2 * i];
			int cz = pcz + RING[2 * i + 1];
			LevelChunk chunk = source.getChunk(cx, cz, ChunkStatus.FULL, false);
			if (chunk == null) {
				continue;
			}
			long key = chunkKey(cx, cz);
			Integer seen = SEEN.get(key);
			if (seen == null || seen != System.identityHashCode(chunk) || chunk.isUnsaved()) {
				QUEUE.putIfAbsent(key, Boolean.TRUE);
			}
		}
	}

	/** The minimap's reach in chunks (its corner, turned). */
	private static int caveRadius() {
		ModuleState mini = Mods.state(MINIMAP);
		int size = enlarged ? Math.max(mini.integer(Minimap.SIZE), Mc263Minimap.ENLARGED) : mini.integer(Minimap.SIZE);
		float blocks = size / 2.0F / Math.max(0.25F, zoomTarget(mini.option(Minimap.ZOOM))) * 1.42F;
		return Math.clamp((int) Math.ceil(blocks / 16) + 1, 2, 12);
	}

	/** Forgets far chunks' marks (they are read again if you come back and they changed meanwhile). */
	private static void trimSeen(int pcx, int pcz, int radius) {
		SEEN.keySet().removeIf(k -> Math.abs((int) (k >> 32) - pcx) > radius || Math.abs((int) (long) k - pcz) > radius);
		CAVE_SEEN.keySet().removeIf(k -> Math.abs((int) (k >> 32) - pcx) > radius || Math.abs((int) (long) k - pcz) > radius);
	}

	/** For profiling: chunks read and time spent reading since the last call. */
	public static String stats() {
		String s = scanned + " chunks read in " + scanNanos / 1_000_000 + " ms; " + Mc263MapTiles.stats();
		scanned = 0;
		scanNanos = 0;
		return s;
	}

	/** For QA: what the map holds at block ({@code x}, {@code z}) of the surface map, and how the blocks there read. */
	public static String probe(int x, int z) {
		MapWorld world = surface;
		ClientLevel level = Minecraft.getInstance().level;
		if (world == null || level == null) {
			return "no map";
		}
		int packed = Mc263Minimap.column(world, x, z);
		LevelChunk chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
		StringBuilder out = new StringBuilder(String.format(java.util.Locale.ROOT, "map %08X;", packed));
		if (chunk != null) {
			int top = ceiling ? ceilingStart : chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
			for (int y = top, n = 0; y > level.getMinY() && n < 6; y--) {
				var state = chunk.getBlockState(new BlockPos(x, y, z));
				if (!state.isAir()) {
					out.append(' ').append(y).append('=').append(Mc263MapScanner.describe(state));
					n++;
				}
			}
		}
		return out.toString();
	}

	/** Whether the minimap's enlarge key is held (in game, no screen open). */
	public static boolean enlarged() {
		return enlarged && on();
	}

	/** Whether cave mode shows now. */
	public static boolean caveOn() {
		return caveOn;
	}
}
