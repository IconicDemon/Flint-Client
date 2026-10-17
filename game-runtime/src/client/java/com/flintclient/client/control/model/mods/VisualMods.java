package com.flintclient.client.control.model.mods;

import com.flintclient.client.control.hud.Anchor;
import com.flintclient.client.control.hud.HudPlacement;
import com.flintclient.client.control.model.BoolSetting;
import com.flintclient.client.control.model.BoxStyle;
import com.flintclient.client.control.model.Category;
import com.flintclient.client.control.model.IconId;
import com.flintclient.client.control.model.IntSetting;
import com.flintclient.client.control.model.KeySetting;
import com.flintclient.client.control.model.ModuleDescriptor;
import com.flintclient.client.control.model.ModuleDescriptor.Requirement;
import com.flintclient.client.control.model.OptionSetting;
import com.flintclient.client.control.model.RgbSetting;

/** How the world looks on your screen. Visual only: nothing here changes the world or what the server knows. */
public final class VisualMods {
	private VisualMods() {
	}

	public static final class TimeChanger {
		public static final String ID = "time_changer";
		public static final OptionSetting MODE = new OptionSetting("mode", "Time", null, 0, "fixed", "Fixed time", "real", "Real-world time");
		public static final IntSetting HOUR = new IntSetting("hour", "Time of day", "The hour you see, from 0 to 23", 12, 0, 23, 1, ":00");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Time Changer", "Change the time locally")
			.keywords("time", "day", "night", "sky").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("Time", MODE, HOUR).needs(Requirement.WORLD)
			.note("Only your sky, light and fog follow it; the world's real time (crops, mobs, beds) is untouched.").build();
	}

	public static final class FovChanger {
		public static final String ID = "fov_changer";
		public static final IntSetting BOW = new IntSetting("bow", "Bow zoom", "How much drawing a bow narrows your view", 100, 0, 200, 10, "%");
		public static final IntSetting FLYING = new IntSetting("flying", "Flying modifier", null, 100, 0, 200, 10, "%");
		public static final IntSetting SPEED = new IntSetting("speed", "Sprint and speed modifier", "Sprinting, Speed and Slowness widen or narrow "
			+ "your view this much", 100, 0, 200, 10, "%");
		public static final BoolSetting SPYGLASS = new BoolSetting("spyglass", "Spyglass zoom", "Off: the spyglass doesn't zoom", true);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "FOV Changer", "Customize field of view")
			.keywords("fov", "field of view", "sprint", "bow").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("Modifiers", BOW, FLYING, SPEED, SPYGLASS)
			.note("Scales Minecraft's own FOV effects (100% is vanilla). Minecraft's FOV Effects slider still applies on top.").build();
	}

	public static final class FogCustomizer {
		public static final String ID = "fog_customizer";
		public static final BoolSetting NO_FOG = new BoolSetting("noFog", "No fog", "Removes the distance fog in the open", false);
		public static final IntSetting DISTANCE = new IntSetting("distance", "Fog distance multiplier", null, 100, 50, 300, 10, "%");
		public static final BoolSetting OVERRIDE_COLOR = new BoolSetting("overrideColor", "Override fog color", false);
		public static final RgbSetting COLOR = new RgbSetting("color", "Fog color", null, 0xC0D8FF);
		public static final BoolSetting CLEAR_WATER = new BoolSetting("clearWater", "Clear water", "See much further under water", false);
		public static final BoolSetting CLEAR_LAVA = new BoolSetting("clearLava", "Clear lava", "See a little in lava", false);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Fog Customizer", "Custom fog settings and clear water")
			.keywords("fog", "water", "lava", "view distance").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("General", NO_FOG, DISTANCE, OVERRIDE_COLOR, COLOR).section("Clear Water and Lava", CLEAR_WATER, CLEAR_LAVA)
			.needs(Requirement.WORLD).build();
	}

	public static final class MenuBlur {
		public static final String ID = "menu_blur";
		public static final IntSetting AMOUNT = new IntSetting("amount", "Blur amount", "0 turns menu blur off", 5, 0, 10, 1, "");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Menu Blur", "Blur the game behind menus")
			.keywords("blur", "menu", "background", "screen").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("Blur", AMOUNT)
			.note("Sets Minecraft's own Menu Background Blur while on, and puts yours back when turned off.").build();
	}

	public static final class Lighting {
		public static final String ID = "lighting";
		public static final OptionSetting MODE = new OptionSetting("mode", "Brightness mode", null, 0, "fullbright", "Fullbright", "custom",
			"Custom Brightness");
		public static final IntSetting BRIGHTNESS = new IntSetting("brightness", "Custom brightness", "100% is Minecraft's Bright", 300, 100,
			1000, 50, "%");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Lighting", "Adjust your brightness beyond Minecraft's limit")
			.keywords("fullbright", "gamma", "brightness", "night vision").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("Brightness Settings", MODE, BRIGHTNESS)
			.note("Changes only how bright you see things; light levels (and mob spawning) are unchanged.").build();
	}

	public static final class ChunkBorders {
		public static final String ID = "chunk_borders";
		public static final KeySetting TOGGLE_KEY = new KeySetting("toggleKey", "Toggle chunk borders",
			"Shows or hides the borders; None: they show whenever the module is on", KeySetting.NONE);
		public static final BoolSetting GRID = new BoolSetting("grid", "Grid", "Lines across the walls of your chunk", true);
		public static final IntSetting GRID_SIZE = new IntSetting("gridSize", "Grid size", "Blocks between grid lines", 2, 1, 8, 1, "");
		public static final IntSetting GRID_THICKNESS = new IntSetting("gridThickness", "Line thickness", null, 1, 1, 8, 1, "");
		public static final RgbSetting GRID_COLOR = new RgbSetting("gridColor", "Grid color", null, 0xFFFF00);
		public static final BoolSetting INNER = new BoolSetting("inner", "Inner corners", "The corners of the chunk you're in", true);
		public static final IntSetting INNER_THICKNESS = new IntSetting("innerThickness", "Line thickness", null, 3, 1, 8, 1, "");
		public static final RgbSetting INNER_COLOR = new RgbSetting("innerColor", "Inner chunk corner color", null, 0x4040FF);
		public static final BoolSetting OUTER = new BoolSetting("outer", "Outer corners", "The corners of the chunks around yours", true);
		public static final IntSetting OUTER_THICKNESS = new IntSetting("outerThickness", "Line thickness", null, 3, 1, 8, 1, "");
		public static final RgbSetting OUTER_COLOR = new RgbSetting("outerColor", "Outer chunk corner color", null, 0xFF0000);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Chunk Borders", "View the border of the current chunk")
			.keywords("chunk", "borders", "f3+g", "grid").icon(IconId.GAUGE).category(Category.WORLD)
			.section("Toggle", TOGGLE_KEY).section("Grid", GRID, GRID_SIZE, GRID_THICKNESS, GRID_COLOR)
			.section("Inner Corners", INNER, INNER_THICKNESS, INNER_COLOR).section("Outer Corners", OUTER, OUTER_THICKNESS, OUTER_COLOR)
			.needs(Requirement.WORLD)
			.note("Draws the 16 by 16 chunk you stand in. The grid's level lines cover 32 blocks above and below you.").build();
	}

	public static final class GuiScale {
		public static final String ID = "gui_scale";
		public static final OptionSetting SCALE = new OptionSetting("scale", "GUI scale", "Auto is the largest that fits your window", 3, "auto",
			"Auto", "1", "1", "2", "2", "3", "3", "4", "4", "5", "5");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "GUI Scale", "Choose a GUI scale per profile")
			.keywords("gui scale", "interface", "size", "hud size").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("Scale", SCALE)
			.note("Sets Minecraft's own GUI Scale while on (a scale your window can't fit uses the largest that fits), and puts yours back "
				+ "when turned off.").build();
	}

	public static final class MotionBlur {
		public static final String ID = "motion_blur";
		public static final IntSetting AMOUNT = new IntSetting("amount", "Blur amount", "Higher leaves longer trails; make sure it's not too high",
			50, 5, 100, 5, "%");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Motion Blur", "Blur your vision when you turn")
			.keywords("motion blur", "blur", "smooth", "trail").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("Motion Blur", AMOUNT)
			.note("One extra full-screen pass and one window-sized texture while on; the hand and the HUD stay sharp.").build();
	}

	public static final class Nametags {
		public static final String ID = "nametags";
		public static final BoolSetting OWN = new BoolSetting("own", "Nametag in perspective", "Your own nametag in third person", true);
		public static final BoolSetting ALWAYS_NAMED = new BoolSetting("alwaysNamed", "Always mob nametags",
			"Named mobs show their name without being looked at", false);
		public static final BoolSetting ALWAYS_TAMED = new BoolSetting("alwaysTamed", "Always tamed nametags",
			"Your named pets show their name without being looked at", true);
		public static final BoolSetting PING = new BoolSetting("includePing", "Include ping", "Players' latency after their name, on servers",
			false);
		public static final KeySetting TOGGLE_ALL = new KeySetting("toggleAll", "Toggle all nametags", null, KeySetting.NONE);
		public static final KeySetting TOGGLE_PLAYERS = new KeySetting("togglePlayers", "Toggle player nametags only", null, KeySetting.NONE);
		public static final BoolSetting TOGGLE_MESSAGE = new BoolSetting("toggleMessage", "Display toggle message",
			"Says “Nametags hidden/shown” above the hotbar", true);
		public static final BoolSetting SHADOW = new BoolSetting("textShadow", "Text shadow", false);
		public static final IntSetting OPACITY = new IntSetting("opacity", "Nametag opacity", "The text", 100, 20, 100, 5, "%");
		public static final BoolSetting BACKGROUND = new BoolSetting("background", "Background", true);
		public static final RgbSetting BACKGROUND_COLOR = new RgbSetting("backgroundColor", "Background color", null, 0x000000);
		public static final IntSetting BACKGROUND_OPACITY = new IntSetting("backgroundOpacity", "Background opacity", null, 25, 0, 100, 5, "%");
		public static final IntSetting SIZE = new IntSetting("size", "Nametag size", null, 100, 50, 200, 10, "%");
		public static final BoolSetting OWN_COLOR = new BoolSetting("ownColor", "Replace own nametag color", false);
		public static final RgbSetting OWN_RGB = new RgbSetting("ownRgb", "Own nametag color", null, 0xFF8139);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Nametags", "Modify nametag mechanics")
			.keywords("nametag", "name", "perspective", "f5", "ping").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("General", OWN, ALWAYS_NAMED, ALWAYS_TAMED, PING).section("Toggle", TOGGLE_ALL, TOGGLE_PLAYERS, TOGGLE_MESSAGE)
			.section("Style", SHADOW, OPACITY, BACKGROUND, BACKGROUND_COLOR, BACKGROUND_OPACITY, SIZE, OWN_COLOR, OWN_RGB)
			.note("Minecraft's own rules still decide who has a nametag: teams that hide them, invisible players and F1 stay hidden. "
				+ "Ping is the server's own number for each player.")
			.partial("Rounded corners, borders, the hurt nametag and chat bubbles aren't built yet").build();
	}

	public static final class BlockOutline {
		public static final String ID = "block_outline";
		public static final RgbSetting COLOR = new RgbSetting("color", "Outline color", null, 0x000000);
		public static final IntSetting OPACITY = new IntSetting("opacity", "Outline opacity", null, 40, 10, 100, 5, "%");
		public static final IntSetting THICKNESS = new IntSetting("thickness", "Outline thickness", "Times Minecraft's own line width", 100, 50,
			400, 25, "%");
		public static final BoolSetting FILL = new BoolSetting("fill", "Fill", "Tints the block's faces", false);
		public static final RgbSetting FILL_COLOR = new RgbSetting("fillColor", "Fill color", null, 0xFFFFFF);
		public static final IntSetting FILL_OPACITY = new IntSetting("fillOpacity", "Fill opacity", null, 15, 5, 60, 5, "%");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Block Outline", "Add overlay and outline to blocks")
			.keywords("outline", "block", "selection", "highlight").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("General", COLOR, OPACITY, THICKNESS).section("Fill", FILL, FILL_COLOR, FILL_OPACITY)
			.note("Only the block you already look at; nothing hidden is outlined. Minecraft's High Contrast Block Outline still wins while on.")
			.build();
	}

	public static final class ParticleChanger {
		public static final String ID = "particle_changer";
		public static final IntSetting CRITS = new IntSetting("critMultiplier", "Critical hit particles", "Times as many as Minecraft shows", 1, 1, 10,
			1, "\u00D7");
		public static final IntSetting SHARPNESS = new IntSetting("sharpnessMultiplier", "Enchanted hit particles", "Times as many as Minecraft shows",
			1, 1, 10, 1, "\u00D7");
		public static final BoolSetting ALWAYS_SHARPNESS = new BoolSetting("alwaysSharpness", "Always sharpness",
			"Enchanted hit particles on every hit you land with an enchanted weapon", false);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Particle Changer", "Change and multiply particles")
			.keywords("particles", "crits", "effects", "sharpness").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("Hit Particles", CRITS, SHARPNESS, ALWAYS_SHARPNESS)
			.note("Only what you see changes. More particles cost some frame time, and Minecraft's own Particles setting still applies.")
			.build();
	}

	public static final class ColorSaturation {
		public static final String ID = "color_saturation";
		public static final IntSetting HUE = new IntSetting("hue", "Hue", "Turns every color around the color wheel", 0, -180, 180, 5, "°");
		public static final IntSetting SATURATION = new IntSetting("saturation", "Saturation", "-100 is grey, 100 twice as colorful", 0, -100,
			100, 5, "");
		public static final IntSetting BRIGHTNESS = new IntSetting("brightness", "Brightness", null, 0, -100, 100, 5, "");
		public static final IntSetting CONTRAST = new IntSetting("contrast", "Contrast", null, 0, -100, 100, 5, "");
		public static final BoolSetting GRAYSCALE = new BoolSetting("grayscale", "Grayscale", "Removes all color", false);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Color Saturation",
				"Adjust your display color saturation settings")
			.keywords("saturation", "color", "contrast", "hue", "grayscale").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("General", HUE, SATURATION, BRIGHTNESS, CONTRAST, GRAYSCALE)
			.note("Changes the colors of the world and your hand, not the HUD, menus or chat. At 0 everything is untouched and costs nothing; "
				+ "otherwise one extra full-screen pass a frame. Screenshots of the world keep their normal colors.").build();
	}

	public static final class ItemPhysics {
		public static final String ID = "item_physics";
		public static final IntSetting ROTATION_SPEED = new IntSetting("rotationSpeed", "Rotation speed",
			"How fast dropped items tumble while they fall; 0% drops them without turning", 50, 0, 200, 5, "%");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Item Physics", "Cool item dropping physics")
			.keywords("items", "drop", "physics", "ground").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("Item Physics", ROTATION_SPEED)
			.note("Dropped items tumble as they fall and lie down where they land, instead of floating and spinning. Only how they look "
				+ "changes: where they are, picking them up and despawning are Minecraft's own.").build();
	}

	public static final class Skins3d {
		public static final String ID = "3d_skins";
		public static final IntSetting DISTANCE = new IntSetting("detailDistance", "Detail distance",
			"Players further away show Minecraft's flat layers (cheaper)", 24, 4, 96, 4, " blocks");
		public static final BoolSetting OTHERS = new BoolSetting("showOthers", "Show others' skins", "Off: only your own skin is 3D", true);
		public static final BoolSetting HAT = new BoolSetting("hat", "3D hat", true);
		public static final BoolSetting JACKET = new BoolSetting("jacket", "3D jacket", true);
		public static final BoolSetting LEFT_SLEEVE = new BoolSetting("leftSleeve", "3D left sleeve", true);
		public static final BoolSetting RIGHT_SLEEVE = new BoolSetting("rightSleeve", "3D right sleeve", true);
		public static final BoolSetting LEFT_PANTS = new BoolSetting("leftPants", "3D left pants", true);
		public static final BoolSetting RIGHT_PANTS = new BoolSetting("rightPants", "3D right pants", true);
		public static final IntSetting VOXEL = new IntSetting("voxelSize", "Voxel size", "Sleeves and trousers: how wide each pixel-box is", 115, 100,
			150, 5, "%");
		public static final IntSetting TORSO = new IntSetting("torsoVoxelWidth", "Torso voxel width", null, 115, 100, 150, 5, "%");
		public static final IntSetting HEAD = new IntSetting("headVoxelSize", "Head voxel size", null, 115, 100, 150, 5, "%");
		public static final IntSetting FIRST_PERSON = new IntSetting("firstPersonVoxelSize", "First person voxel size",
			"Your own sleeves when you look at your hands", 110, 100, 150, 5, "%");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "3D Skins", "Give skin layers depth")
			.keywords("skins", "3d", "layers", "model", "voxel").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("Performance", DISTANCE, OTHERS)
			.section("Player Model Options", HAT, JACKET, LEFT_SLEEVE, RIGHT_SLEEVE, LEFT_PANTS, RIGHT_PANTS, VOXEL, TORSO, HEAD, FIRST_PERSON)
			.note("Each pixel of a skin's outer layer becomes a small box standing on the body; see-through pixels stay empty. Built once "
				+ "per skin and drawn with Minecraft's own model code; beyond the detail distance players keep Minecraft's flat layers.")
			.partial("3D skull blocks and skull items aren't built yet").build();
	}

	public static final class Momentum {
		public static final String ID = "momentum";
		public static final BoxStyle STYLE = new BoxStyle(70, 13);
		public static final BoolSetting GROUND_ONLY = new BoolSetting("groundOnly", "Only use ground speed",
			"Horizontal speed only: jumping and falling don't add to it", true);
		public static final BoolSetting AVERAGE = new BoolSetting("average", "Use average velocity",
			"The average of the last half second, so the number doesn't flicker", true);
		public static final IntSetting DECIMALS = new IntSetting("decimals", "Round to", "Decimal places", 2, 0, 3, 1, "");
		public static final OptionSetting LABEL = HudMods.labelMode("Prefix: Speed 4.32. Suffix: 4.32 b/s.");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Momentum", "Display your movement speed")
			.keywords("speed", "velocity", "momentum", "blocks per second", "bps").icon(IconId.GAUGE).category(Category.UTILITY)
			.section("Momentum", GROUND_ONLY, AVERAGE, DECIMALS, LABEL).section(STYLE.section("Style")).section(STYLE.detailSection())
			.style(STYLE).hud(HudPlacement.of(Anchor.TOP_RIGHT, 4, 81)).needs(Requirement.WORLD)
			.note("Blocks per second, from how far you moved each game tick (riding included). A teleport or a respawn restarts it.")
			.build();
	}

	public static final class Items2d {
		public static final String ID = "2d_items";
		public static final OptionSetting RENDER_AS = new OptionSetting("renderAs", "Render as",
			"Sprite always faces you; Flat keeps Minecraft's turning", 0, "sprite", "Sprite", "flat", "Flat");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "2D Items", "Show dropped items as flat sprites")
			.keywords("items", "2d", "sprites", "flat").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("2D Items", RENDER_AS)
			.note("Dropped items on the ground show as their inventory icon, flat, with your resource pack's textures. Items in hands, "
				+ "inventories and item frames are unchanged. With Item Physics on, the icons fall and lie down as Item Physics decides "
				+ "(Sprite can't face you while lying down).").build();
	}

	public static final class Overlay {
		public static final String ID = "overlay";
		public static final OptionSetting FIRE = new OptionSetting("fire", "Fire overlay", "When you're burning", 1, "normal", "Normal", "low", "Low",
			"hidden", "Hidden");
		public static final BoolSetting PUMPKIN = new BoolSetting("hidePumpkin", "Hide pumpkin blur", "When you wear a carved pumpkin", true);
		public static final BoolSetting POWDER_SNOW = new BoolSetting("hidePowderSnow", "Hide powder snow frost", false);
		public static final BoolSetting UNDERWATER = new BoolSetting("hideUnderwater", "Hide underwater tint", false);
		public static final BoolSetting PORTAL = new BoolSetting("hidePortal", "Hide portal swirl", "The purple screen in a nether portal", false);
		public static final BoolSetting NAUSEA = new BoolSetting("hideNausea", "Hide nausea overlay", false);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Overlay", "Change the screen overlays")
			.keywords("overlay", "fire", "pumpkin", "screen").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("Overlays", FIRE, PUMPKIN, POWDER_SNOW, UNDERWATER, PORTAL, NAUSEA)
			.note("Changes only overlays drawn on your own screen; nothing hidden in the world is revealed.").build();
	}

	public static final class LightOverlay {
		public static final String ID = "light_overlay";
		public static final KeySetting TOGGLE_KEY = new KeySetting("toggleKey", "Toggle key",
			"Shows or hides the markers; None: they show whenever the module is on", KeySetting.NONE);
		public static final BoolSetting STATE_MESSAGE = new BoolSetting("stateMessage", "State indicator",
			"Says “Light Overlay on/off” above the hotbar when you press the toggle key", true);
		public static final BoolSetting HOLDING_LIGHT = new BoolSetting("holdingLight", "Only holding a light source",
			"Torches, lanterns, glowstone and other blocks that give light", false);
		public static final BoolSetting HOLDING_PICKAXE = new BoolSetting("holdingPickaxe", "Only holding a pickaxe", false);
		public static final OptionSetting TYPE = new OptionSetting("type", "Type", null, 0, "number", "Number", "cross", "Cross", "solid",
			"Solid Top");
		public static final OptionSetting SHOW = new OptionSetting("show", "Show", "Every spot mobs could stand on, or only dark ones", 0,
			"all", "All", "dark", "Only dark");
		public static final BoolSetting SHOW_VALUE = new BoolSetting("showValue", "Show light value", "The number on crosses and solid tops too",
			false);
		public static final IntSetting CROSS_THICKNESS = new IntSetting("crossThickness", "Cross thickness", null, 2, 1, 5, 1, "");
		public static final IntSetting RANGE = new IntSetting("range", "Rendering range limit", "Blocks around you (and up to 8 above or below)",
			12, 4, 32, 1, " blocks");
		public static final IntSetting POLL = new IntSetting("pollTicks", "Poll rate", "How often the light is read again; lower is faster",
			10, 1, 40, 1, " ticks");
		public static final BoolSetting CULLING = new BoolSetting("culling", "Culling", "Skips spots behind you", true);
		public static final OptionSetting LIGHT_TYPE = new OptionSetting("lightType", "Light type", null, 2, "block", "Block", "sky", "Sky",
			"both", "Both");
		public static final BoolSetting COUNT_DARKEN = new BoolSetting("countSkyDarken", "Count sky darken",
			"Sky light drops at night and in storms, as it does for mobs", true);
		public static final OptionSetting COLOR_TYPE = new OptionSetting("colorType", "Color type", null, 0, "gradient", "Safe/Unsafe Gradient",
			"static", "Static");
		public static final RgbSetting DANGER_COLOR = new RgbSetting("dangerColor", "Danger color", null, 0xE53935);
		public static final RgbSetting SAFE_COLOR = new RgbSetting("safeColor", "Safe color", null, 0x66E05A);
		public static final IntSetting DANGER_LEVEL = new IntSetting("dangerLevel", "Danger light level",
			"At or below this is dark enough for most hostile mobs (0 since Minecraft 1.18)", 0, 0, 15, 1, "");
		public static final IntSetting SAFE_LEVEL = new IntSetting("safeLevel", "Safest light level", "Where the gradient reaches the safe color",
			15, 0, 15, 1, "");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Light Overlay", "Displays overlay indicating light levels")
			.keywords("light", "spawn", "mobs", "level", "torch").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("Activation", TOGGLE_KEY, STATE_MESSAGE, HOLDING_LIGHT, HOLDING_PICKAXE)
			.section("General", TYPE, SHOW, SHOW_VALUE, CROSS_THICKNESS, RANGE, POLL, CULLING, LIGHT_TYPE, COUNT_DARKEN)
			.section("Color", COLOR_TYPE, DANGER_COLOR, SAFE_COLOR, DANGER_LEVEL, SAFE_LEVEL).needs(Requirement.WORLD)
			.note("Marks the top of each block a mob could stand on near you, from the light the game already has; it never loads chunks. "
				+ "Dark means dark enough: whether a mob spawns also depends on the block, space, biome and difficulty.").build();
	}

	static ModuleDescriptor notBuilt(String id, String name, String description, String why, String... keywords) {
		return ModuleDescriptor.builder(id, name, description).keywords(keywords).category(Category.VISUAL).notBuilt(why).build();
	}
}
