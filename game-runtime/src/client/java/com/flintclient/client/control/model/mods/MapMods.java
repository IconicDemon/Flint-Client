package com.flintclient.client.control.model.mods;

import com.flintclient.client.control.hud.Anchor;
import com.flintclient.client.control.hud.HudPlacement;
import com.flintclient.client.control.model.BoolSetting;
import com.flintclient.client.control.model.Category;
import com.flintclient.client.control.model.FloatSetting;
import com.flintclient.client.control.model.IconId;
import com.flintclient.client.control.model.IntSetting;
import com.flintclient.client.control.model.KeySetting;
import com.flintclient.client.control.model.ModuleDescriptor;
import com.flintclient.client.control.model.ModuleDescriptor.Requirement;
import com.flintclient.client.control.model.OptionSetting;
import com.flintclient.client.control.model.RgbSetting;
import com.flintclient.client.control.model.TextSetting;

/**
 * Flint's own maps: the Minimap on the HUD and the full-screen World Map. Both draw from one map of what you have explored,
 * built from the chunks Minecraft has loaded, kept per world or server and per dimension under the game folder
 * ({@code flint/maps/}). The map's look (block colours, shading, water) is the World Map's; the minimap uses it too.
 */
public final class MapMods {
	private MapMods() {
	}

	public static final class Minimap {
		public static final String ID = "minimap";
		// Map
		public static final OptionSetting SHAPE = new OptionSetting("shape", "Shape", null, 1, "round", "Round", "square", "Square");
		public static final IntSetting SIZE = new IntSetting("size", "Size", "Its width on the HUD, before its scale", 110, 60, 240, 5, "");
		public static final BoolSetting ROTATE = new BoolSetting("rotate", "Rotate with you", "Off: north always up", true);
		/** Xaero's zoom steps (a block is half a step's pixels across); the keys go round them. */
		public static final OptionSetting ZOOM = new OptionSetting("zoomStep", "Zoom",
			"Xaero's steps: 1 shows the most, 6 the closest. The zoom keys go round them", 1, "0.5", "0.5", "1", "1", "2", "2", "3", "3",
			"4", "4", "5", "5", "6", "6");
		public static final IntSetting OPACITY = new IntSetting("opacity", "Map opacity", null, 100, 10, 100, 5, "%");
		public static final BoolSetting LIGHTING = new BoolSetting("lighting", "Day and night", "The map darkens at night", true);
		public static final BoolSetting CHUNK_GRID = new BoolSetting("chunkGrid", "Chunk grid", "Lines on chunk borders", false);
		public static final BoolSetting SLIME_CHUNKS = new BoolSetting("slimeChunks", "Slime chunks", "Singleplayer only: the seed is needed",
			false);
		// Frame
		public static final OptionSetting FRAME = new OptionSetting("frame", "Frame", "Classic: Xaero's thin double line", 3, "flint", "Flint",
			"thick", "Thick", "none", "None", "classic", "Classic");
		public static final RgbSetting FRAME_COLOR = new RgbSetting("frameColor", "Frame color", null, 0x101010);
		public static final OptionSetting COMPASS = new OptionSetting("compass", "Compass", "N, E, S and W around the map", 1, "off", "Off",
			"frame", "On frame", "map", "On map");
		public static final RgbSetting NORTH_COLOR = new RgbSetting("northColor", "North color", null, 0xFF8139);
		public static final FloatSetting ARROW_SCALE = new FloatSetting("arrowSize", "Arrow scale", "Your arrow, on this map and the world map",
			1.5F, 1, 2, 0.1F, "×");
		public static final OptionSetting ARROW_COLOR = new OptionSetting("arrowColors", "Arrow color", "Team: your scoreboard team's color",
			0, "red", "Red", "green", "Green", "blue", "Blue", "yellow", "Yellow", "purple", "Purple", "white", "White", "black", "Black",
			"dark_red", "Dark red", "team", "Team", "custom", "Custom");
		public static final RgbSetting ARROW_CUSTOM = new RgbSetting("arrowColor", "Custom arrow color", "When Arrow color is Custom",
			0xFF8139);
		public static final IntSetting ARROW_OPACITY = new IntSetting("arrowOpacity", "Arrow opacity", null, 100, 10, 100, 5, "%");
		public static final BoolSetting HIDE_WITH_F3 = new BoolSetting("hideWithF3", "Hide with debug screen", true);
		// Cave mode
		public static final OptionSetting CAVE_MODE = new OptionSetting("caveMode", "Cave mode",
			"Auto: with a roof over you and the blocks round you (always, in the Nether), as Xaero's", 1, "off", "Off", "auto", "Auto",
			"always", "Always");
		public static final IntSetting CAVE_DEPTH = new IntSetting("caveDepth", "Cave depth", "How far down a cave map looks", 30, 8, 64, 2,
			" blocks");
		public static final BoolSetting CAVE_ZOOM = new BoolSetting("caveZoom", "Zoom in underground", "Caves show at twice the zoom (6 at most)",
			true);
		public static final KeySetting CAVE_KEY = new KeySetting("caveKey", "Cave mode key", "Switches cave mode on and off by hand",
			KeySetting.NONE);
		/** {@link #LAYER}'s Auto: cave mode decides (Xaero's "go all the way to the left"). */
		public static final int LAYER_AUTO = -65;
		public static final IntSetting LAYER = new IntSetting("caveLayerY", "Layer height",
			"Auto: cave mode decides. A height: the minimap always shows the layer under it, as Xaero's manual cave mode (only places "
				+ "mapped at that height show; the layer is read round you as you go)", LAYER_AUTO, LAYER_AUTO, 320, 1, "")
			.named(LAYER_AUTO, "Auto");
		public static final KeySetting LAYER_UP_KEY = new KeySetting("layerUpKey", "Layer up",
			"16 blocks higher (past the top of the world: back to Auto)", "key.keyboard.page.up");
		public static final KeySetting LAYER_DOWN_KEY = new KeySetting("layerDownKey", "Layer down",
			"16 blocks lower (from Auto: starts at your feet)", "key.keyboard.page.down");
		// Entities
		public static final BoolSetting RADAR = new BoolSetting("radar", "Entity radar", "What's around you: players, mobs, items", true);
		public static final BoolSetting PLAYERS = new BoolSetting("players", "Players", true);
		public static final BoolSetting PLAYER_HEADS = new BoolSetting("playerHeads", "Players as heads", "Their faces; off: dots", true);
		public static final RgbSetting PLAYER_COLOR = new RgbSetting("playerColor", "Player color", "Their dots", 0xFFFFFF);
		public static final BoolSetting HOSTILE = new BoolSetting("hostile", "Hostile mobs", true);
		public static final OptionSetting HOSTILE_ICONS = new OptionSetting("hostileIcons", "Hostile mobs as",
			"Heads: each mob's own face, as Xaero's minimap draws it", 0, "heads", "Heads", "dots", "Dots");
		public static final RgbSetting HOSTILE_COLOR = new RgbSetting("hostileColor", "Hostile color", "Their dots", 0xFF4D4D);
		public static final BoolSetting PASSIVE = new BoolSetting("passive", "Friendly mobs", "Animals, villagers, golems…", true);
		public static final OptionSetting PASSIVE_ICONS = new OptionSetting("passiveIcons", "Friendly mobs as",
			"Heads: each mob's own face, as Xaero's minimap draws it", 0, "heads", "Heads", "dots", "Dots");
		public static final RgbSetting PASSIVE_COLOR = new RgbSetting("passiveColor", "Friendly color", "Their dots", 0xF2F2F2);
		public static final BoolSetting ITEMS = new BoolSetting("items", "Items", "Dropped items, as dots", false);
		public static final RgbSetting ITEM_COLOR = new RgbSetting("itemColor", "Item color", null, 0xFFD24D);
		public static final BoolSetting OTHERS = new BoolSetting("others", "Other entities", "Boats, minecarts, arrows…, as dots", false);
		public static final RgbSetting OTHER_COLOR = new RgbSetting("otherColor", "Other color", null, 0xA0A0A0);
		public static final OptionSetting NAMES = new OptionSetting("radarNames", "Names", "Under the heads and dots", 0, "off", "Off", "players",
			"Players", "all", "Everyone");
		public static final FloatSetting ICON_SCALE = new FloatSetting("iconScale", "Head size", "A mob's head is as many pixels as in its model, "
			+ "times this", 1, 0.5F, 2, 0.1F, "×");
		public static final FloatSetting DOT_SIZE = new FloatSetting("dotSize", "Dot size", null, 1, 0.5F, 3, 0.1F, "×");
		public static final BoolSetting HEIGHT_FADE = new BoolSetting("heightFade", "Fade above and below",
			"Dots more than 8 blocks above or below you are fainter", true);
		public static final IntSetting HEIGHT_RANGE = new IntSetting("heightRange", "Height range",
			"Entities further above or below are left out (mobs in caves under you); 0: any", 24, 0, 128, 4, " blocks");
		// Waypoints
		public static final BoolSetting WAYPOINTS = new BoolSetting("waypoints", "Waypoints", true);
		public static final BoolSetting WAYPOINT_NAMES = new BoolSetting("waypointNames", "Waypoint names", false);
		public static final FloatSetting WAYPOINT_SCALE = new FloatSetting("waypointScale", "Waypoint scale", null, 1, 0.5F, 2, 0.1F, "×");
		public static final BoolSetting EDGE_WAYPOINTS = new BoolSetting("edgeWaypoints", "Far waypoints on the edge",
			"Ones beyond the map wait at its edge, toward them", true);
		// Information
		public static final BoolSetting COORDINATES = new BoolSetting("coordinates", "Coordinates", true);
		public static final BoolSetting BIOME = new BoolSetting("biome", "Biome", false);
		public static final BoolSetting FACING = new BoolSetting("facing", "Facing", false);
		public static final BoolSetting OTHER_DIMENSION = new BoolSetting("otherDimension", "Nether coordinates",
			"The matching place in the Overworld or the Nether", false);
		public static final BoolSetting TIME = new BoolSetting("gameTime", "Game time", false);
		public static final IntSetting INFO_BACKGROUND = new IntSetting("infoBackground", "Text background", null, 40, 0, 100, 5, "%");
		// Keys
		public static final KeySetting ZOOM_IN_KEY = new KeySetting("zoomInKey", "Zoom in", null, "key.keyboard.i");
		public static final KeySetting ZOOM_OUT_KEY = new KeySetting("zoomOutKey", "Zoom out", null, "key.keyboard.o");
		public static final KeySetting ENLARGE_KEY = new KeySetting("enlargeKey", "Enlarge",
			"Held: the map grows in the middle of the screen (north up when square), as Xaero's", "key.keyboard.z");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Minimap", "A map of what's around you, as you move")
			.keywords("map", "radar", "minimap", "xaero", "navigation", "caves").icon(IconId.COMPASS).category(Category.WORLD)
			.section("Map", SHAPE, SIZE, ROTATE, ZOOM, OPACITY, LIGHTING, CHUNK_GRID, SLIME_CHUNKS)
			.section("Frame", FRAME, FRAME_COLOR, COMPASS, NORTH_COLOR, ARROW_SCALE, ARROW_COLOR, ARROW_CUSTOM, ARROW_OPACITY, HIDE_WITH_F3)
			.section("Cave Mode", CAVE_MODE, CAVE_DEPTH, CAVE_ZOOM, CAVE_KEY, LAYER, LAYER_UP_KEY, LAYER_DOWN_KEY)
			.section("Entity Radar", RADAR, PLAYERS, PLAYER_HEADS, PLAYER_COLOR, HOSTILE, HOSTILE_ICONS, HOSTILE_COLOR, PASSIVE, PASSIVE_ICONS,
				PASSIVE_COLOR, ITEMS, ITEM_COLOR, OTHERS, OTHER_COLOR, NAMES, ICON_SCALE, DOT_SIZE, HEIGHT_FADE, HEIGHT_RANGE)
			.section("Waypoints", WAYPOINTS, WAYPOINT_NAMES, WAYPOINT_SCALE, EDGE_WAYPOINTS)
			.section("Information", COORDINATES, BIOME, FACING, OTHER_DIMENSION, TIME, INFO_BACKGROUND)
			.section("Key Settings", ZOOM_IN_KEY, ZOOM_OUT_KEY, ENLARGE_KEY)
			.hud(HudPlacement.of(Anchor.TOP_RIGHT, 4, 24)).needs(Requirement.WORLD)
			.note("Built from the chunks Minecraft has loaded around you, a few at a time, and kept on this computer per world or "
				+ "server (flint/maps in the game folder), so places you've been show again when you come back. The terrain's look is "
				+ "the World Map's.").build();
	}

	public static final class WorldMap {
		public static final String ID = "world_map";
		public static final KeySetting OPEN_KEY = new KeySetting("openKey", "Open world map", null, "key.keyboard.m");
		// The map's look (shared with the minimap)
		public static final OptionSetting COLORS = new OptionSetting("colors", "Block colors", "Textures: each block's own colors", 0, "textures",
			"Textures", "vanilla", "Map colors", "height", "Height");
		public static final OptionSetting SHADING = new OptionSetting("shading", "Terrain slopes", "3D: sunlight from the north-west on every "
			+ "slope, as Xaero's maps", 2, "off", "Off", "slopes", "2D", "relief", "3D");
		public static final BoolSetting HEIGHT_TINT = new BoolSetting("heightTint", "Terrain depth", "Higher ground brighter, lower ground darker",
			true);
		public static final BoolSetting SEE_WATER = new BoolSetting("seeThroughWater", "See through water", "Shallows show their floor", true);
		public static final BoolSetting PLANTS = new BoolSetting("plants", "Flowers",
			"Grass, tall grass and torches never show (the ground under them does), as on Xaero's maps", true);
		public static final BoolSetting GLASS = new BoolSetting("glass", "Stained glass", "Tints what's under it. Off: looked through", true);
		// View
		public static final BoolSetting PLAYERS = new BoolSetting("players", "Other players", "The players you can see", true);
		public static final BoolSetting MOBS = new BoolSetting("mobs", "Mobs and items",
			"Around you, as the minimap’s entity radar shows them (its groups, height range and icons)", true);
		public static final BoolSetting WAYPOINTS = new BoolSetting("waypoints", "Waypoints", true);
		public static final OptionSetting WAYPOINT_NAMES = new OptionSetting("waypointNames", "Waypoint names",
			"Always: a name that would cover another waits until you zoom in (the nearest show first)", 1, "hover", "On hover", "always",
			"Always");
		public static final BoolSetting GRID = new BoolSetting("grid", "Grid", "Chunk lines close up, region lines far out", false);
		public static final BoolSetting REMEMBER = new BoolSetting("remember", "Open where you left it", "Off: centred on you each time",
			false);
		public static final BoolSetting SCROLL_ZOOM_TO_MOUSE = new BoolSetting("zoomToMouse", "Zoom toward the mouse", true);
		/** {@link #LAYER}'s Auto (the minimap's cave layer while it shows one, else the surface) and Surface. */
		public static final int LAYER_AUTO = -66;
		public static final int LAYER_SURFACE = -65;
		public static final IntSetting LAYER = new IntSetting("layerY", "Map layer",
			"What the map shows, as the slider on its left edge: Auto (the minimap's cave layer underground), the surface, or the "
				+ "layer under a height (only places mapped at that height show)", LAYER_AUTO, LAYER_AUTO, 320, 1, "")
			.named(LAYER_AUTO, "Auto").named(LAYER_SURFACE, "Surface");
		// Waypoints
		public static final BoolSetting TELEPORT = new BoolSetting("teleport", "Teleport from the map",
			"Where commands let you: singleplayer with cheats, or servers that allow it", true);
		public static final TextSetting TELEPORT_COMMAND = new TextSetting("teleportCommand", "Teleport command", "{x} {y} {z} are filled in",
			"tp @s {x} {y} {z}", 64);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "World Map", "A full-screen map of everywhere you've been")
			.keywords("map", "world map", "xaero", "explore", "navigation").icon(IconId.COMPASS).category(Category.WORLD)
			.section("Key Settings", OPEN_KEY)
			.section("Map Look", COLORS, SHADING, HEIGHT_TINT, SEE_WATER, PLANTS, GLASS)
			.section("View", LAYER, PLAYERS, MOBS, WAYPOINTS, WAYPOINT_NAMES, GRID, REMEMBER, SCROLL_ZOOM_TO_MOUSE)
			.section("Waypoints", TELEPORT, TELEPORT_COMMAND)
			.needs(Requirement.WORLD)
			.note("Press the key to open it: drag to move, scroll to zoom, right-click a place to make a waypoint there or teleport, and "
				+ "right-click a waypoint to edit, share or delete it. It shows what you've explored with Flint on, per world or server "
				+ "and per dimension, kept on this computer only.").build();
	}
}
