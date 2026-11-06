package com.flintclient.client.control.model.mods;

import com.flintclient.client.control.hud.Anchor;
import com.flintclient.client.control.hud.HudPlacement;
import com.flintclient.client.control.model.BoolSetting;
import com.flintclient.client.control.model.BoxStyle;
import com.flintclient.client.control.model.Category;
import com.flintclient.client.control.model.FloatSetting;
import com.flintclient.client.control.model.IconId;
import com.flintclient.client.control.model.IdKind;
import com.flintclient.client.control.model.IdListSetting;
import com.flintclient.client.control.model.IntSetting;
import com.flintclient.client.control.model.KeySetting;
import com.flintclient.client.control.model.ModuleDescriptor;
import com.flintclient.client.control.model.ModuleDescriptor.Requirement;
import com.flintclient.client.control.model.OptionSetting;
import com.flintclient.client.control.model.RgbSetting;
import com.flintclient.client.control.model.SettingDescriptor;
import com.flintclient.client.control.model.TextSetting;

/** How the world looks on your screen. Visual only: nothing here changes the world or what the server knows. */
public final class VisualMods {
	private VisualMods() {
	}

	public static final class TimeChanger {
		public static final String ID = "time_changer";
		public static final OptionSetting MODE = new OptionSetting("mode", "Time source", null, 0, "fixed", "Fixed time", "real", "Real-world time");
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

	/**
	 * Fog Customizer: Custom Fog (Setadokalo, MIT) with its Default, Water, Powder Snow and per-dimension configs (the Nether's
	 * off to start, two more dimensions of your choice), each with its mode and distances; plus Flint's no fog, distance,
	 * color and clear water and lava.
	 */
	public static final class FogCustomizer {
		public static final String ID = "fog_customizer";
		public static final BoolSetting NO_FOG = new BoolSetting("noFog", "No fog", "Removes the distance fog in the open", false);
		public static final IntSetting DISTANCE = new IntSetting("distance", "Fog distance multiplier", null, 100, 50, 300, 10, "%");
		public static final BoolSetting OVERRIDE_COLOR = new BoolSetting("overrideColor", "Override fog color", false);
		public static final RgbSetting COLOR = new RgbSetting("color", "Fog color", null, 0xC0D8FF);
		public static final BoolSetting CLEAR_WATER = new BoolSetting("clearWater", "Clear water", "See much further under water", false);
		public static final BoolSetting CLEAR_LAVA = new BoolSetting("clearLava", "Clear lava", "See a little in lava", false);

		/** One of Custom Fog's fog configs. */
		public record Config(String key, String title, BoolSetting enabled, OptionSetting type, FloatSetting linearStart, FloatSetting linearEnd,
			FloatSetting exp, FloatSetting exp2) {
			SettingDescriptor<?>[] settings() {
				return new SettingDescriptor<?>[] {enabled, type, linearStart, linearEnd, exp, exp2};
			}
		}

		static Config config(String key, String title, boolean enabled, int type, float start, float end, float exp, float exp2) {
			return new Config(key, title, new BoolSetting(key + ".enabled", "Config enabled", "Off: Minecraft's own fog here", enabled),
				new OptionSetting(key + ".type", "Fog mode", null, type, "linear", "Linear", "exponential", "Exponential", "exponential_two",
					"Exponential^2", "none", "None"),
				new FloatSetting(key + ".linearStart", "Linear fog start", "The percentage of your render distance to start the linear fog "
					+ "fade out at", start, 0, 200, 0.5F, "%"),
				new FloatSetting(key + ".linearEnd", "Linear fog end", "The percentage of your render distance to end the linear fog fade "
					+ "out at", end, 0, 200, 0.5F, "%"),
				new FloatSetting(key + ".exp", "Exponential density", "The density of fog in Exponential mode", exp, 0, 100, 0.01F, ""),
				new FloatSetting(key + ".exp2", "Exponential^2 density", "The density of fog in Exponential^2 mode", exp2, 0, 10000, 0.05F, ""));
		}

		public static final Config DEFAULT = config("default", "Default", true, 0, 25, 100, 3.0F, 1.75F);
		public static final Config WATER = config("water", "Water", true, 1, 25, 100, 2.0F, 0.05F);
		public static final Config SNOW = config("snow", "Powder Snow", true, 0, 0, 140, 85.0F, 9800.05F);
		public static final Config NETHER = config("nether", "The Nether", false, 0, 25, 100, 3.0F, 1.75F);
		public static final Config DIMENSION_1 = config("dimension1", "Dimension", true, 0, 25, 100, 3.0F, 1.75F);
		public static final Config DIMENSION_2 = config("dimension2", "Second Dimension", true, 0, 25, 100, 3.0F, 1.75F);
		public static final TextSetting DIMENSION_1_ID = new TextSetting("dimension1.id", "Dimension ID",
			"Like minecraft:the_end; empty: not used", "", 64);
		public static final TextSetting DIMENSION_2_ID = new TextSetting("dimension2.id", "Dimension ID",
			"Like minecraft:the_end; empty: not used", "", 64);
		public static final java.util.List<Config> CONFIGS = java.util.List.of(DEFAULT, WATER, SNOW, NETHER, DIMENSION_1, DIMENSION_2);

		public static final ModuleDescriptor DESCRIPTOR = build();

		private static ModuleDescriptor build() {
			ModuleDescriptor.Builder b = ModuleDescriptor.builder(ID, "Fog Customizer", "Custom fog settings and clear water")
				.keywords("fog", "water", "lava", "view distance", "custom fog", "snow", "nether").icon(IconId.GAUGE).category(Category.VISUAL)
				.section("General", NO_FOG, DISTANCE, OVERRIDE_COLOR, COLOR).section("Clear Water and Lava", CLEAR_WATER, CLEAR_LAVA);
			for (Config c : CONFIGS) {
				SettingDescriptor<?>[] own = c.settings();
				if (c == DIMENSION_1 || c == DIMENSION_2) {
					SettingDescriptor<?>[] with = new SettingDescriptor<?>[own.length + 1];
					with[0] = c == DIMENSION_1 ? DIMENSION_1_ID : DIMENSION_2_ID;
					System.arraycopy(own, 0, with, 1, own.length);
					own = with;
				}
				b.section(c.title(), own);
			}
			return b.needs(Requirement.WORLD)
				.note("Custom Fog's fog: in the open, under water and in powder snow, each config sets its own mode (Linear from a start "
					+ "to an end share of your render distance; Exponential; Exponential^2; None) where it is enabled. Dimensions without "
					+ "a config of their own use Default; lava and Blindness keep Minecraft's fog; the sky's fog reaches your render "
					+ "distance. Exponential modes are drawn with Minecraft's own fog ramp between the distances where they reach 5% and "
					+ "95% (Flint doesn't replace Minecraft's or Sodium's shaders). No fog, the distance multiplier, the color and clear "
					+ "water and lava are Flint's and apply on top.").build();
		}
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

	/**
	 * Motion Blur: Natural Motion Blur 1.4.5 (LGPL-3.0) rebuilt on every Minecraft line, its options, ranges and defaults (Velocity
	 * based, strength 1.00, refresh rate scaling on, Uniform), plus Quality and Blur when moving. The rules are in
	 * {@code control/fx/NmbTiming}; the passes in each line's {@code control/fx/MotionBlur}. Settings saved by Flint's earlier
	 * Classic trail ({@code blurAlgorithm: classic}, its default for a day) read as the default, Velocity based (an unknown
	 * option reads as the default).
	 */
	public static final class MotionBlur {
		public static final String ID = "motion_blur";
		public static final OptionSetting ALGORITHM = new OptionSetting("blurAlgorithm", "Blur algorithm",
			"Velocity based (recommended): each pixel blurs along its own motion, with no added input delay. Frame blending: the "
				+ "frames drawn within one monitor refresh are blended, like f0e's blur (it needs more FPS than your refresh rate at "
				+ "1.00). Hybrid blending: both, the smoothest and the heaviest. Accumulation (max): a bright trail, as LabyMod Mix, "
				+ "Lunar v1 and BLC 2.0. Accumulation (mix): an even trail, as LabyMod Max, Lunar v2 and v3 and Badlion. Trails add a "
				+ "little delay to what you see", 0,
			"velocity_based", "Velocity based", "frame_blending", "Frame blending", "hybrid_blending", "Hybrid blending", "accumulation_max",
			"Accumulation (max)", "accumulation_mix", "Accumulation (mix)");
		public static final FloatSetting STRENGTH = new FloatSetting("motionBlurStrength", "Motion blur strength",
			"How strong the blur is. 1.00 blurs each frame along the motion your monitor shows in one refresh: the natural amount", 1, 0, 2,
			0.01F, "");
		public static final BoolSetting REFRESH_SCALING = new BoolSetting("refreshRateScaling", "Refresh rate scaling",
			"Keeps the blur as long as one monitor refresh at any FPS: 600 FPS on 60 Hz blurs each frame 10 times as far, 60 FPS or "
				+ "less as set, so high frame rates don't strobe. Off: the strength is fixed per frame", true);
		public static final OptionSetting PROFILE = new OptionSetting("blurProfile", "Blur profile",
			"Uniform: an even box blur, the crispest. Weighted: a smoother falloff at the ends of the blur, slightly softer", 0, "uniform",
			"Uniform", "weighted", "Weighted");
		public static final OptionSetting QUALITY = new OptionSetting("quality", "Quality",
			"Samples along each pixel's motion. Performance: half as many, for weaker graphics cards. Balanced: Natural Motion Blur's "
				+ "own. High: twice as many, the cleanest fast turns. A still camera costs nothing at any quality", 1, "performance",
			"Performance", "balanced", "Balanced", "high", "High");
		public static final BoolSetting MOVEMENT = new BoolSetting("movementBlur", "Blur when moving",
			"Blurs the world as you walk, sprint, fly or ride, not only as you turn. Players and mobs aren't blurred by your movement", true);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Motion Blur", "Blur your vision when you turn")
			.keywords("motion blur", "blur", "smooth", "trail", "natural", "nmb").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("Motion Blur Options", ALGORITHM, STRENGTH, REFRESH_SCALING, PROFILE, QUALITY, MOVEMENT)
			.note("Natural Motion Blur's blur, the same on every Minecraft version: the world blurs as you turn and move, players and mobs "
				+ "aren't smeared by your own movement, and your hand and the HUD stay sharp. It looks the same at any frame rate. Velocity "
				+ "based draws one or two full-screen passes only while the camera moves.").build();
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
		public static final BoolSetting ROUNDED = new BoolSetting("roundedCorners", "Rounded corners", "Of the background", false);
		public static final BoolSetting BORDER = new BoolSetting("border", "Border", "A line around the background", false);
		public static final BoolSetting BORDER_PLAYERS = new BoolSetting("playersOnly", "Borders players only", "Mobs' nametags keep none",
			true);
		public static final RgbSetting BORDER_COLOR = new RgbSetting("borderColor", "Border color", null, 0xFFFFFF);
		public static final BoolSetting BORDER_CHROMA = new BoolSetting("borderChroma", "Chroma border", "Cycles through the rainbow", false);
		// Hurt nametag
		public static final BoolSetting HURT = new BoolSetting("hurtNametag", "Hurt nametag",
			"Tints a player's or mob's nametag while it flashes red from a hit", false);
		public static final RgbSetting HURT_COLOR = new RgbSetting("hurtColor", "Hurt color", null, 0xFF0000);
		public static final IntSetting HURT_STRENGTH = new IntSetting("hurtColorStrength", "Hurt color strength", null, 100, 5, 100, 5, "%");
		// Chat bubbles
		public static final BoolSetting BUBBLES = new BoolSetting("chatBubbles", "Chat bubbles",
			"Players' recent chat messages above their heads for a few seconds", false);
		public static final IntSetting BUBBLE_CHARS = new IntSetting("maxChatBubbleChars", "Max chat bubble characters",
			"A longer message is cut short with …", 48, 8, 72, 1, "");
		public static final IntSetting BUBBLE_MAX = new IntSetting("maxChatBubbles", "Max chat bubbles", "Per player; the newest sits lowest", 3,
			1, 8, 1, "");
		public static final IntSetting BUBBLE_FADE_IN = new IntSetting("bubbleFadeIn", "Bubble fade-in", null, 200, 50, 500, 50, " ms");
		public static final FloatSetting BUBBLE_DELAY = new FloatSetting("bubbleFadeOutDelay", "Bubble fade-out delay",
			"How long a message stays before it fades", 6, 0.5F, 24, 0.5F, " s");
		public static final IntSetting BUBBLE_FADE_OUT = new IntSetting("bubbleFadeOut", "Bubble fade-out", null, 200, 50, 500, 50, " ms");
		public static final IntSetting BUBBLE_OBSCURED = new IntSetting("obscuredBubbleOpacity", "Obscured bubble opacity",
			"Behind blocks and other players", 25, 0, 100, 5, "%");
		public static final RgbSetting BUBBLE_TEXT = new RgbSetting("bubblesTextColor", "Text color", null, 0xFFFFFF);
		public static final BoolSetting BUBBLE_BACKGROUND = new BoolSetting("bubbleBackground", "Bubble background", true);
		public static final RgbSetting BUBBLE_BACKGROUND_COLOR = new RgbSetting("bubbleBackgroundColor", "Bubble background color", null,
			0x000000);
		public static final IntSetting BUBBLE_BACKGROUND_OPACITY = new IntSetting("bubbleBackgroundOpacity", "Bubble background opacity", null,
			50, 0, 100, 5, "%");
		public static final BoolSetting BUBBLE_BORDER = new BoolSetting("bubbleBorder", "Bubble border", false);
		public static final RgbSetting BUBBLE_BORDER_COLOR = new RgbSetting("bubbleBorderColor", "Bubble border color", null, 0xFFFFFF);
		public static final IntSetting BUBBLE_BORDER_OPACITY = new IntSetting("bubbleBorderOpacity", "Bubble border opacity", null, 50, 5, 100,
			5, "%");
		public static final FloatSetting BUBBLE_BORDER_THICKNESS = new FloatSetting("bubbleBorderThickness", "Bubble border thickness", null, 1,
			0.5F, 2, 0.5F, " px");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Nametags", "Modify nametag mechanics")
			.keywords("nametag", "name", "perspective", "f5", "ping", "chat bubbles", "hurt", "border").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("General", OWN, ALWAYS_NAMED, ALWAYS_TAMED, PING).section("Toggle", TOGGLE_ALL, TOGGLE_PLAYERS, TOGGLE_MESSAGE)
			.section("Hurt Nametag", HURT, HURT_COLOR, HURT_STRENGTH)
			.section("Style", SHADOW, OPACITY, BACKGROUND, BACKGROUND_COLOR, BACKGROUND_OPACITY, ROUNDED, BORDER, BORDER_PLAYERS, BORDER_COLOR,
				BORDER_CHROMA, SIZE, OWN_COLOR, OWN_RGB)
			.section("Chat Bubbles", BUBBLES, BUBBLE_CHARS, BUBBLE_MAX, BUBBLE_FADE_IN, BUBBLE_DELAY, BUBBLE_FADE_OUT)
			.section("Bubble Style", BUBBLE_OBSCURED, BUBBLE_TEXT, BUBBLE_BACKGROUND, BUBBLE_BACKGROUND_COLOR, BUBBLE_BACKGROUND_OPACITY,
				BUBBLE_BORDER, BUBBLE_BORDER_COLOR, BUBBLE_BORDER_OPACITY, BUBBLE_BORDER_THICKNESS)
			.note("Minecraft's own rules still decide who has a nametag: teams that hide them, invisible players and F1 stay hidden. "
				+ "Ping is the server's own number for each player. Chat bubbles show what a player said in chat, including chat a "
				+ "server sends as plain text in a usual \"Name: message\" form; private messages and server notices get none.")
			.build();
	}

	public static final class BlockOutline {
		public static final String ID = "block_outline";
		public static final RgbSetting COLOR = new RgbSetting("color", "Outline color", null, 0x000000);
		public static final IntSetting OPACITY = new IntSetting("opacity", "Outline opacity", null, 40, 10, 100, 5, "%");
		public static final IntSetting THICKNESS = new IntSetting("thickness", "Outline thickness", "Times Minecraft's own line width", 100, 50,
			400, 25, "%");
		public static final BoolSetting FILL = new BoolSetting("fill", "Fill", "Tints the block's faces", false);
		public static final RgbSetting FILL_COLOR = new RgbSetting("fillColor", "Fill color", null, 0xFFFFFF);
		public static final IntSetting FILL_OPACITY = new IntSetting("fillOpacity", "Fill opacity", null, 15, 0, 60, 5, "%");
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
		// CleanView (MIT): particles kept out of your face in first person.
		public static final IntSetting EATING = new IntSetting("eatingVisibility", "Eating particles",
			"How many eating, drinking and item-break crumbs in the filter area still show", 100, 0, 100, 1, "%");
		public static final IntSetting EFFECTS = new IntSetting("effectVisibility", "Status effect particles",
			"How many potion swirls in the filter area, within 2 blocks of you, still show", 100, 0, 100, 1, "%");
		public static final IntSetting FILTER_DISTANCE = new IntSetting("particleDistance", "Particle filter distance",
			"1: your face, 2: your body, 3 and more: that many blocks ahead of you as well. 0 filters nothing", 1, 0, 16, 1, "");
		public static final BoolSetting OPACITY_ON = new BoolSetting("opacityEnabled", "Particle opacity",
			"Makes every particle near your eyes see-through", false);
		public static final IntSetting OPACITY = new IntSetting("opacityPercent", "Particle opacity %", null, 100, 0, 100, 1, "%");
		public static final IntSetting OPACITY_DISTANCE = new IntSetting("opacityDistance", "Opacity distance",
			"How far from your eyes, in blocks", 1, 0, 16, 1, "");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Particle Changer", "Change and multiply particles")
			.keywords("particles", "crits", "effects", "sharpness", "cleanview", "potion", "eating").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("Hit Particles", CRITS, SHARPNESS, ALWAYS_SHARPNESS)
			.section("Particle Visibility (First Person Only)", EATING, EFFECTS, FILTER_DISTANCE, OPACITY_ON, OPACITY, OPACITY_DISTANCE)
			.note("Only what you see changes. More particles cost some frame time, and Minecraft's own Particles setting still applies. "
				+ "Particle visibility is CleanView's: in first person, a share of the eating and potion particles that would appear in "
				+ "front of your face (or around your body, or ahead of you) never appear, and particles near your eyes can be made "
				+ "see-through, from any source.")
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

	/**
	 * Item Physics: Realistic Item Drops' look (CC-BY-SA-4.0; items lie flat from the moment they drop) or Flint's tumble,
	 * plus every option of Dropped Item Tweaks (LGPL-3.0): no bob, how many copies a stack shows, and item scales.
	 */
	public static final class ItemPhysics {
		public static final String ID = "item_physics";
		public static final OptionSetting STYLE = new OptionSetting("physicsStyle", "Physics style",
			"Realistic: Realistic Item Drops' look, lying flat from the start. Tumble: end over end while falling. None: Minecraft's own "
				+ "bob and spin", 0, "realistic", "Realistic", "tumble", "Tumble", "none", "None");
		public static final IntSetting ROTATION_SPEED = new IntSetting("rotationSpeed", "Rotation speed",
			"How fast dropped items tumble while they fall; 0% drops them without turning", 50, 0, 200, 5, "%");
		public static final BoolSetting BLOCKS_ON_SIDE = new BoolSetting("blocksOnSide", "Blocks lie on their side",
			"Block items turned onto their side, top facing south, as Realistic Item Drops lays them", true);
		public static final BoolSetting SAME_HEADING = new BoolSetting("sameHeading", "Same heading for all",
			"Every item faces south; off: each item its own way", true);
		public static final BoolSetting REALISTIC_SIZE = new BoolSetting("realisticSize", "Realistic size",
			"Flat items 1.25 times and blocks 2.5 times Minecraft's dropped size", true);
		public static final BoolSetting ONE_MODEL = new BoolSetting("oneModel", "One model per stack", "A stack shows as one item", true);
		public static final BoolSetting LANDING_PARTICLES = new BoolSetting("landingParticles", "Landing particles",
			"A burst of the block's particles when a block item lands", true);
		// Dropped Item Tweaks
		public static final BoolSetting STATIC = new BoolSetting("staticItems", "Static dropped items",
			"Remove the bobbing of items dropped on the ground", false);
		public static final IntSetting STACK_COUNT = new IntSetting("dropStackCount", "Dropped stack item count",
			"The max amount of dropped items to render; this does not scale like Minecraft's. 0 turns it off", 0, 0, 64, 1, "");
		public static final FloatSetting ITEM_SCALE = new FloatSetting("itemScale", "Item scale", "The scale of dropped items", 1, 0, 3, 0.1F, "×");
		public static final FloatSetting CUSTOM_SCALE = new FloatSetting("uhcOverlay", "Custom item scale",
			"Custom scale for the items you list (instead of Item scale). 0 turns it off", 0, 0, 3, 0.1F, "×");
		public static final IdListSetting CUSTOM_ITEMS = new IdListSetting("uhcItemList", "Custom item scale list",
			"The items the custom scale is for", IdKind.ITEM, java.util.List.of("minecraft:apple", "minecraft:golden_apple",
				"minecraft:enchanted_golden_apple", "minecraft:gold_ingot", "minecraft:gold_nugget", "minecraft:gold_block", "minecraft:player_head"));
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Item Physics", "Cool item dropping physics")
			.keywords("items", "drop", "physics", "ground", "realistic", "static", "scale", "uhc").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("Item Physics", STYLE, ROTATION_SPEED)
			.section("Realistic", BLOCKS_ON_SIDE, SAME_HEADING, REALISTIC_SIZE, ONE_MODEL, LANDING_PARTICLES)
			.section("Dropped Item Tweaks", STATIC, STACK_COUNT, ITEM_SCALE, CUSTOM_SCALE, CUSTOM_ITEMS)
			.note("Only how dropped items look changes: where they are, picking them up and despawning stay Minecraft's (Realistic Item "
				+ "Drops' server-side parts, items that never despawn and a pickup spot beside the item, aren't copied, so each item "
				+ "lies where it really is). Realistic lays flat items face down and blocks on their side from the moment they drop; "
				+ "Tumble turns them end over end while they fall and lays them down where they land.").build();
	}

	public static final class Skins3d {
		public static final String ID = "3d_skins";
		// 3D Skin Layers (tr7zw; behaviour only, Flint's own code): its settings with its ranges and defaults.
		public static final IntSetting DISTANCE = new IntSetting("renderDistanceLOD", "Detail distance",
			"Players and heads further away (blocks) show Minecraft's flat layers (cheaper)", 14, 4, 64, 1, " blocks");
		public static final BoolSetting OTHERS = new BoolSetting("showOthers", "Show others' skins", "Off: only your own skin is 3D", true);
		public static final BoolSetting HAT = new BoolSetting("hat", "3D hat", true);
		public static final BoolSetting JACKET = new BoolSetting("jacket", "3D jacket", true);
		public static final BoolSetting LEFT_SLEEVE = new BoolSetting("leftSleeve", "3D left sleeve", true);
		public static final BoolSetting RIGHT_SLEEVE = new BoolSetting("rightSleeve", "3D right sleeve", true);
		public static final BoolSetting LEFT_PANTS = new BoolSetting("leftPants", "3D left pants", true);
		public static final BoolSetting RIGHT_PANTS = new BoolSetting("rightPants", "3D right pants", true);
		public static final FloatSetting VOXEL = new FloatSetting("baseVoxelSize", "Base voxel size",
			"How far the sleeve and trouser pixels stand out", 1.15F, 1.001F, 1.4F, 0.001F, "");
		public static final FloatSetting HEAD = new FloatSetting("headVoxelSize", "Head voxel size", "How far the hat stands out", 1.18F,
			1.001F, 1.25F, 0.001F, "");
		public static final FloatSetting TORSO = new FloatSetting("bodyVoxelWidthSize", "Body voxel width size",
			"How far the jacket stands out at the sides; more can touch the arms", 1.05F, 1.001F, 1.4F, 0.001F, "");
		public static final BoolSetting HEADS = new BoolSetting("heads", "3D player heads", "Placed player heads get a 3D hat too", true);
		public static final BoolSetting SKULL_ITEMS = new BoolSetting("enableSkullsItems", "3D head items",
			"Player heads held, worn and in slots get a 3D hat too", true);
		public static final FloatSetting SKULL = new FloatSetting("skullVoxelSize", "Skull voxel size", "How far a player head's hat stands out",
			1.1F, 1.001F, 1.2F, 0.001F, "");
		public static final FloatSetting FIRST_PERSON = new FloatSetting("firstPersonPixelScaling", "First person voxel size",
			"Your own sleeves when you look at your hands", 1.1F, 1.02F, 1.3F, 0.001F, "");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "3D Skins", "Give skin layers depth")
			.keywords("skins", "3d", "layers", "model", "voxel").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("Player", HAT, JACKET, LEFT_SLEEVE, RIGHT_SLEEVE, LEFT_PANTS, RIGHT_PANTS, VOXEL, HEAD, TORSO)
			.section("Heads", HEADS, SKULL_ITEMS, SKULL).section("Other", FIRST_PERSON, DISTANCE, OTHERS)
			.note("Each pixel of a skin's outer layer becomes a small box standing out of the body, with whole edges and corners; "
				+ "see-through pixels stay empty. Built once per skin into a few merged faces, so it costs little to draw. Beyond the "
				+ "detail distance, and in small pictures in menus, players and heads keep Minecraft's flat layers; a hat under a worn "
				+ "head or pumpkin stays flat.").build();
	}

	public static final class WavyCapes {
		public static final String ID = "wavy_capes";
		// Wavey Capes (tr7zw; behaviour only, Flint's own code): its settings, with its ranges and defaults.
		public static final OptionSetting STYLE = new OptionSetting("capeStyle", "Cape style", "Smooth bends like cloth; Blocky bends pixel by pixel",
			0, "smooth", "Smooth", "blocky", "Blocky");
		public static final OptionSetting WIND_MODE = new OptionSetting("windMode", "Wind mode", "Waves: ripples run down the cape", 0, "none",
			"None", "waves", "Waves");
		public static final OptionSetting MOVEMENT = new OptionSetting("capeMovement", "Cape movement",
			"Vanilla: Minecraft's own. Simulation: cloth physics; 3D swings sideways too. Dungeons: stiff, bending at the middle", 2,
			"vanilla", "Vanilla", "basic_simulation", "Simulation", "basic_simulation_3d", "Simulation 3D", "dungeons", "Dungeons");
		public static final BoolSetting OTHERS = new BoolSetting("others", "Other players' capes", "Off: only your own cape moves", true);
		public static final IntSetting GRAVITY = new IntSetting("gravity", "Gravity", "How quickly it falls back to hanging", 25, 5, 32, 1, "");
		public static final IntSetting HEIGHT = new IntSetting("heightMultiplier", "Height multiplier",
			"How much going up and down lifts or drops it", 6, 4, 16, 1, "");
		public static final IntSetting SIDEWAYS = new IntSetting("straveMultiplier", "Strafe multiplier", "How much moving sideways swings it", 2,
			0, 10, 1, "");
		public static final BoolSetting GRAVITY_VECTOR = new BoolSetting("computeGravityVector", "Compute gravity vector",
			"Hangs along the body however it's turned (flying, swimming), not straight down", false);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Wavy Capes", "Capes that move with you")
			.keywords("cape", "capes", "wavy", "physics", "cloth", "minecon", "wind").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("Cape", STYLE, WIND_MODE, MOVEMENT, OTHERS).section("Physics", GRAVITY, HEIGHT, SIDEWAYS, GRAVITY_VECTOR)
			.needs(Requirement.WORLD)
			.note("The capes Minecraft already shows (Mojang and Minecon capes) hang and move like cloth: they stream out behind as you run, "
				+ "lift as you fall, swing round as you turn and never pass into your back or a 3D jacket. Elytras and capes in menus are "
				+ "unchanged. Flint adds no capes of its own.").build();
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

	/** 2D Items: FlatDrop's look (1.8.9 Forge; behaviour only, Flint's own code): flat items as upright sprites that turn to face you. */
	public static final class Items2d {
		public static final String ID = "2d_items";
		public static final OptionSetting RENDER_AS = new OptionSetting("renderAs", "Render as",
			"Sprite: upright, turning to face you, as FlatDrop draws it. Flat: keeps Minecraft's spin", 0, "sprite", "Sprite", "flat", "Flat");
		public static final BoolSetting BLOCKS_3D = new BoolSetting("blocks3d", "Blocks stay 3D",
			"Block-shaped items keep Minecraft's 3D model, as FlatDrop leaves them", true);
		public static final BoolSetting ONE_SPRITE = new BoolSetting("oneSprite", "One sprite per stack", "A stack shows as one sprite", true);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "2D Items", "Show dropped items as flat sprites")
			.keywords("items", "2d", "sprites", "flat", "flatdrop").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("2D Items", RENDER_AS, BLOCKS_3D, ONE_SPRITE)
			.note("Dropped items on the ground show as their inventory icon, flat (no thickness), half a block tall and bobbing as "
				+ "Minecraft's do, with your resource pack's textures. Items in hands, inventories and item frames are unchanged. With "
				+ "Item Physics on, the icons fall and lie down as Item Physics decides (a sprite can't face you while lying down).").build();
	}

	/** Falling Leaves (RandomMcSomethin, Fourmisain, BrekiTomasson; MIT): its config, general, leaf settings and experimental. */
	public static final class FallingLeaves {
		public static final String ID = "falling_leaves";
		public static final BoolSetting VANILLA_PARTICLES = new BoolSetting("alwaysUseVanillaParticles", "Always use vanilla particles",
			"Minecraft's own falling leaves for every leaf block, at this module's spawn rates", false);
		public static final BoolSetting VANILLA_TEXTURES = new BoolSetting("useVanillaTextures", "Use vanilla textures",
			"Minecraft's leaf particle textures, tinted as Falling Leaves tints its own", false);
		public static final IntSetting LEAF_SIZE = new IntSetting("leafSize", "Leaf size", null, 5, 1, 10, 1, "");
		public static final IntSetting LIFESPAN = new IntSetting("leafLifespan", "Leaf lifespan",
			"How long a leaf lasts, in ticks (20 a second)", 200, 100, 600, 1, " ticks");
		public static final IntSetting SPAWN_RATE = new IntSetting("leafSpawnRate", "Leaf spawn rate",
			"How many leaves fall: 20 is Falling Leaves' normal rate", 20, 0, 40, 1, "");
		public static final IntSetting CONIFER_RATE = new IntSetting("coniferLeafSpawnRate", "Conifer leaf spawn rate",
			"The same for conifer leaves (spruce)", 0, 0, 40, 1, "");
		public static final IntSetting SNOW_RATE = new IntSetting("snowflakeSpawnRate", "Snowflake spawn rate",
			"Snowflakes falling from leaves in snowy places", 30, 0, 40, 1, "");
		public static final BoolSetting PLAYER_PLACED = new BoolSetting("dropFromPlayerPlacedBlocks", "Drop from player placed blocks",
			true);
		public static final BoolSetting ON_HIT = new BoolSetting("leavesOnBlockHit", "Leaves on block hit", true);
		public static final IntSetting FREE_SPACE = new IntSetting("minimumFreeSpaceBelow", "Minimum free space below",
			"Leaves only fall where there is this much room under the block (half blocks)", 1, 1, 20, 1, "");
		public static final BoolSetting WIND = new BoolSetting("windEnabled", "Wind",
			"Leaves drift on a wind that changes with the weather", true);
		public static final TextSetting WINDLESS = new TextSetting("windlessDimensions", "Windless dimensions",
			"Dimension ids, separated by commas", "minecraft:the_nether, minecraft:the_end", 400);
		// Leaf settings: Falling Leaves' per-block table, as lists.
		public static final TextSetting BLOCK_RATES = new TextSetting("leafSettings.spawnRateFactor", "Spawn rate per block",
			"block id=percent of the normal rate, separated by commas (jungle leaves drop none)",
			"minecraft:jungle_leaves=0, minecraft:cherry_leaves=1000, minecraft:pale_oak_leaves=200", 800);
		public static final TextSetting CONIFERS = new TextSetting("leafSettings.isConiferBlock", "Conifer leaf blocks",
			"Block ids that drop conifer needles, at the conifer rate", "minecraft:spruce_leaves", 800);
		public static final TextSetting VANILLA_BLOCKS = new TextSetting("leafSettings.particleImplementation", "Vanilla particle blocks",
			"Block ids that keep Minecraft's own falling leaves (petals and the like)",
			"minecraft:cherry_leaves, minecraft:pale_oak_leaves", 800);
		public static final TextSetting NO_BREAKING = new TextSetting("leafSettings.spawnBreakingLeaves", "No breaking leaves",
			"Block ids that drop no leaves when hit or when they decay", "", 800);
		// Experimental
		public static final TextSetting SPAWNERS = new TextSetting("leafSpawners", "Leaf spawners",
			"Other blocks that drop leaves, as block ids (with [property=value] to narrow them), separated by commas", "", 800);
		public static final FloatSetting FALL_FACTOR = new FloatSetting("fallSpawnRateFactor", "Fall spawn rate factor",
			"With a seasons mod: the spawn rate in fall is multiplied by this", 1.8F, 0, 10, 0.1F, "×");
		public static final FloatSetting WINTER_FACTOR = new FloatSetting("winterSpawnRateFactor", "Winter spawn rate factor",
			"With a seasons mod: the spawn rate in winter is multiplied by this", 0.1F, 0, 10, 0.1F, "×");
		public static final IntSetting START_RADIUS = new IntSetting("startingSpawnRadius", "Starting spawn radius",
			"No leaves fall within this many blocks of you", 0, 0, 23, 1, " blocks");
		public static final FloatSetting DECAY_FACTOR = new FloatSetting("decaySpawnRateFactor", "Decay spawn rate factor",
			"Leaves that will decay drop this many times as often", 2.6F, 0, 10, 0.1F, "×");
		public static final IntSetting MAX_DECAY = new IntSetting("maxDecayLeaves", "Max decay leaves",
			"Leaves dropped when a leaf block decays (single player); 0: none", 9, 0, 30, 1, "");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Falling Leaves", "Leaves and snow drift down from trees")
			.keywords("leaves", "falling", "particles", "wind", "snow", "trees").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("General", VANILLA_PARTICLES, VANILLA_TEXTURES, LEAF_SIZE, LIFESPAN, SPAWN_RATE, CONIFER_RATE, SNOW_RATE, PLAYER_PLACED,
				ON_HIT, FREE_SPACE, WIND, WINDLESS)
			.section("Leaf Settings", BLOCK_RATES, CONIFERS, VANILLA_BLOCKS, NO_BREAKING)
			.section("Experimental", SPAWNERS, FALL_FACTOR, WINTER_FACTOR, START_RADIUS, DECAY_FACTOR, MAX_DECAY)
			.note("Leaf blocks drop leaves coloured like the tree they fall from, which spin, drift on the wind and settle on the ground "
				+ "or float on water; snowy trees shed snowflakes. Only your own view changes. The fall and winter factors need a seasons "
				+ "mod, which Flint doesn't detect, so they don't apply.").build();
	}

	/** Model Gap Fix (MehVahdJukaar, GPL-3.0): its config, default and macOS sections. */
	public static final class ModelGapFix {
		public static final String ID = "model_gap_fix";
		public static final FloatSetting EXPANSION = new FloatSetting("item_quads_expansion", "Quad expansion",
			"Enlarges each quad to hide gaps. Keep it as close to 0 as possible: above 0 adds a few quads per item model, which "
				+ "HD packs may feel. Try 0.002 or 0.008", 0, -0.1F, 0.1F, 0.0001F, "");
		public static final FloatSetting INDENT = new FloatSetting("item_quads_indent", "Quad indent",
			"Moves each side quad toward the item's centre, to hide gaps", 0.0001F, -0.1F, 0.1F, 0.0001F, "");
		public static final FloatSetting MAC_EXPANSION = new FloatSetting("mac_os.item_quads_expansion", "Quad expansion (macOS)",
			"As Quad expansion, on macOS", 0, -0.1F, 0.1F, 0.0001F, "");
		public static final FloatSetting MAC_INDENT = new FloatSetting("mac_os.item_quads_indent", "Quad indent (macOS)",
			"As Quad indent, on macOS", 0.0099F, -0.1F, 0.1F, 0.0001F, "");
		public static final FloatSetting MAC_SHRINK = new FloatSetting("mac_os.shrink_ratio_multiplier", "Shrink ratio multiplier",
			"Some macOS systems bleed between atlas textures, so there the atlas shrinking is only reduced: 0 removes it as on other "
				+ "systems, 1 keeps Minecraft's", 1, 0, 1, 0.01F, "");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Model Gap Fix", "Remove the gaps in item models")
			.keywords("model", "gap", "fix", "items", "seams", "lines").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("Default", EXPANSION, INDENT).section("macOS", MAC_EXPANSION, MAC_INDENT, MAC_SHRINK)
			.note("Removes the thin see-through lines between the pixels of held and dropped items, and the shrinking that makes block "
				+ "textures look cut at their edges. Changes reload the resource packs. Minecraft 1.21.11 and later build item models "
				+ "without these gaps themselves, so there the module has nothing to fix.").build();
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

	public static final class AutohideHud {
		public static final String ID = "autohide_hud";
		public static final IntSetting DELAY = new IntSetting("hideDelay", "Hide delay", "How long nothing must change first (20 ticks a second)",
			40, 1, 300, 1, " ticks");
		public static final BoolSetting ANIMATE = new BoolSetting("hideAnimation", "Hide animation", "Slide away instead of vanishing", true);
		public static final IntSetting DURATION = new IntSetting("hideAnimationDuration", "Hide animation duration", null, 10, 1, 40, 1,
			" ticks");
		public static final BoolSetting SEPARATELY = new BoolSetting("hideSeparately", "Hide separately",
			"Each part comes back only when it changes. Off: any change brings them all back", false);
		public static final BoolSetting HEALTH = new BoolSetting("hideHealthArmor", "Hide health and armor", true);
		public static final BoolSetting FOOD = new BoolSetting("hideFoodAir", "Hide food and air", true);
		public static final BoolSetting HOTBAR = new BoolSetting("hideHotbarExp", "Hide hotbar and experience", true);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Autohide HUD", "Hide health, food and the hotbar when idle")
			.keywords("hud", "hide", "hotbar", "health", "hunger", "minimal").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("General", DELAY, ANIMATE, DURATION, SEPARATELY).section("Elements", HEALTH, FOOD, HOTBAR).needs(Requirement.WORLD)
			.note("They come back at once when they change: you take damage or heal, eat, run out of air, scroll the hotbar or gain "
				+ "experience.").build();
	}

	public static final class ViewModel {
		public static final String ID = "view_model";
		public static final FloatSetting SHIELD_HEIGHT = new FloatSetting("shieldHeight", "Shield height", "Lower (negative) or raise a held shield",
			0, -1, 1, 0.1F, "");
		public static final BoolSetting SCALE = new BoolSetting("scale", "Scale", false);
		public static final FloatSetting SCALE_X = new FloatSetting("scaleX", "X", null, 1, 0, 3, 0.1F, "×");
		public static final FloatSetting SCALE_Y = new FloatSetting("scaleY", "Y", null, 1, 0, 3, 0.1F, "×");
		public static final FloatSetting SCALE_Z = new FloatSetting("scaleZ", "Z", null, 1, 0, 3, 0.1F, "×");
		public static final BoolSetting POSITION = new BoolSetting("position", "Position", false);
		public static final FloatSetting POSITION_X = new FloatSetting("positionX", "X", "Right is positive (mirrored for the other hand)", 0, -2,
			2, 0.1F, "");
		public static final FloatSetting POSITION_Y = new FloatSetting("positionY", "Y", "Up is positive", 0, -2, 2, 0.1F, "");
		public static final FloatSetting POSITION_Z = new FloatSetting("positionZ", "Z", "Toward you is positive", 0, -2, 2, 0.1F, "");
		public static final BoolSetting ROTATION = new BoolSetting("rotation", "Rotation", false);
		public static final FloatSetting ROTATION_X = new FloatSetting("rotationX", "X", null, 0, -180, 180, 1, "°");
		public static final FloatSetting ROTATION_Y = new FloatSetting("rotationY", "Y", null, 0, -180, 180, 1, "°");
		public static final FloatSetting ROTATION_Z = new FloatSetting("rotationZ", "Z", null, 0, -180, 180, 1, "°");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "View Model", "Move, scale and rotate the item in your hand")
			.keywords("viewmodel", "hand", "item", "position", "shield").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("General", SHIELD_HEIGHT).section("Scale", SCALE, SCALE_X, SCALE_Y, SCALE_Z)
			.section("Position", POSITION, POSITION_X, POSITION_Y, POSITION_Z).section("Rotation", ROTATION, ROTATION_X, ROTATION_Y, ROTATION_Z)
			.needs(Requirement.WORLD).note("Only what you see in first person: the item, not your empty hand, and never your reach.").build();
	}

	public static final class Hotbar {
		public static final String ID = "hotbar";
		public static final FloatSetting SCALE = new FloatSetting("scale", "Hotbar size",
			"Against Minecraft's; the rest of the HUD keeps your GUI scale", 1, 0.5F, 3, 0.05F, "×");
		public static final BoolSetting EXPERIENCE_FOLLOWS = new BoolSetting("experienceFollows", "Experience bar follows hotbar",
			"The experience bar (and its level) takes the hotbar's size", true);
		public static final FloatSetting EXPERIENCE_SCALE = new FloatSetting("experienceScale", "Experience bar size",
			"Its own size, when it doesn't follow the hotbar", 1, 0.5F, 3, 0.05F, "×");
		public static final BoolSetting HEALTH_FOLLOWS = new BoolSetting("healthFollows", "Hearts follow hotbar",
			"The hearts take the hotbar's size", true);
		public static final FloatSetting HEALTH_SCALE = new FloatSetting("healthScale", "Hearts size",
			"Their own size, when they don't follow the hotbar", 1, 0.5F, 3, 0.05F, "×");
		public static final BoolSetting HUNGER_FOLLOWS = new BoolSetting("hungerFollows", "Hunger follows hotbar",
			"Hunger, air bubbles and a mount's hearts take the hotbar's size", true);
		public static final FloatSetting HUNGER_SCALE = new FloatSetting("hungerScale", "Hunger size",
			"Hunger, air bubbles and a mount's hearts, when they don't follow the hotbar", 1, 0.5F, 3, 0.05F, "×");
		public static final BoolSetting ARMOR_FOLLOWS = new BoolSetting("armorFollows", "Armor bar follows hotbar",
			"The armor bar over the hearts takes the hotbar's size", true);
		public static final FloatSetting ARMOR_SCALE = new FloatSetting("armorScale", "Armor bar size",
			"Its own size, when it doesn't follow the hotbar", 1, 0.5F, 3, 0.05F, "×");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Hotbar",
				"Resize the hotbar, experience bar, hearts, hunger and armor bar")
			.keywords("hotbar", "size", "scale", "slots", "hearts", "health", "hunger", "food", "armor", "armour", "experience", "xp", "bars")
			.icon(IconId.GAUGE).category(Category.VISUAL).section("Hotbar", SCALE)
			.section("Experience bar", EXPERIENCE_FOLLOWS, EXPERIENCE_SCALE).section("Hearts", HEALTH_FOLLOWS, HEALTH_SCALE)
			.section("Hunger", HUNGER_FOLLOWS, HUNGER_SCALE).section("Armor bar", ARMOR_FOLLOWS, ARMOR_SCALE)
			.needs(Requirement.WORLD)
			.note("Each bar takes the hotbar's size or its own, and stays just above the hotbar as Minecraft stacks them.").build();
	}

	public static final class WeatherChanger {
		public static final String ID = "weather_changer";
		public static final OptionSetting MODE = new OptionSetting("mode", "Weather mode", "Default: the world's own", 1, "default", "Default",
			"clear", "Clear", "rain", "Rain", "thunder", "Thunder");
		public static final RgbSetting COLOR = new RgbSetting("color", "Rain / snow color", null, 0xFFFFFF);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Weather Changer", "Choose the weather you see on your side")
			.keywords("weather", "rain", "snow", "thunder", "clear").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("Weather", MODE, COLOR).needs(Requirement.WORLD)
			.note("Only your sky, rain and snow follow it; the world's real weather (and mobs, crops and beds) is untouched.").build();
	}

	static ModuleDescriptor notBuilt(String id, String name, String description, String why, String... keywords) {
		return ModuleDescriptor.builder(id, name, description).keywords(keywords).category(Category.VISUAL).notBuilt(why).build();
	}
}
