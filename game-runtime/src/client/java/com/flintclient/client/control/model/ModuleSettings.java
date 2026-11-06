package com.flintclient.client.control.model;

import com.flintclient.client.control.model.mods.CameraMods;
import com.flintclient.client.control.model.mods.HudMods;
import com.flintclient.client.control.model.mods.PlayerMods;
import com.flintclient.client.control.model.mods.UtilityMods;
import com.flintclient.client.control.model.mods.VisualMods;
import org.jspecify.annotations.Nullable;

/**
 * Every module's own settings, as constants the renderers read through {@link ModuleState}. The ids are the
 * keys in layout files, so they never change once released; a setting that replaces an older one reads the
 * old one as a migration ({@link ModuleMigrations}).
 *
 * <p>Defaults are the clean form of each module ("144 FPS", "32 ms", "North"): every extra is opt-in, and
 * each extra is independent of the others.
 */
public final class ModuleSettings {
	private ModuleSettings() {
	}

	/**
	 * The FPS counter. Its look is its own (it doesn't follow the shared style's colours or panel, only its typeface):
	 * the {@link FpsDisplayMode display mode}, the box behind it (fill, size, border, corners) and the text (colour,
	 * shadow, order), so what the settings page shows is exactly what the HUD draws.
	 */
	public static final class Fps {
		/** The counter's look: the same settings (and ids) every boxed counter has. */
		public static final BoxStyle STYLE = new BoxStyle(56, 160, 13, FpsSizing.DYNAMIC, 0xFFFFFF, false);

		// ---------- style ----------
		public static final ChoiceSetting<FpsDisplayMode> DISPLAY_MODE = STYLE.displayMode;
		public static final BoolSetting BACKGROUND = STYLE.background;
		public static final ChoiceSetting<FpsSizing> BACKGROUND_TYPE = STYLE.backgroundType;
		public static final IntSetting WIDTH = STYLE.width;
		public static final IntSetting HEIGHT = STYLE.height;
		public static final BoolSetting BORDER = STYLE.border;
		public static final RgbSetting TEXT_COLOR = STYLE.textColor;
		public static final BoolSetting TEXT_SHADOW = STYLE.textShadow;
		public static final BoolSetting REVERSED = new BoolSetting("reversed", "Reversed", "FPS 80 instead of 80 FPS", false);

		// ---------- box details ----------
		public static final ChoiceSetting<TextAlign> ALIGN = STYLE.align;
		public static final RgbSetting BACKGROUND_COLOR = STYLE.backgroundColor;
		public static final IntSetting BACKGROUND_OPACITY = STYLE.backgroundOpacity;
		public static final RgbSetting BORDER_COLOR = STYLE.borderColor;
		public static final IntSetting BORDER_THICKNESS = STYLE.borderThickness;
		public static final IntSetting CORNER_RADIUS = STYLE.cornerRadius;

		// ---------- what it shows ----------
		public static final BoolSetting SHOW_LABEL = new BoolSetting("showLabel", "Show \u201CFPS\u201D", "Off: just the number", true);
		public static final BoolSetting SHOW_FRAME_TIME = new BoolSetting("showFrameTime", "Frame time",
			"Adds the average time per frame, like \u201C· 6.9 ms\u201D", false);
		public static final BoolSetting SHOW_LOW = new BoolSetting("showLow", "1% low",
			"Adds the frame rate of your slowest 1% of frames, like \u201C· 1% low 57\u201D. It needs 5 seconds and 300 frames to "
				+ "measure, so it shows \u201C\u2014\u201D until then, and again after you join a world or resume.", false).explained();
		public static final ChoiceSetting<FpsInterval> INTERVAL = new ChoiceSetting<>("interval", "Update every",
			"How often the number changes", FpsInterval.class, FpsInterval.HALF_SECOND);
		public static final ChoiceSetting<FpsWindow> WINDOW = new ChoiceSetting<>("window", "Average over",
			"A longer window reads steadier but reacts more slowly", FpsWindow.class, FpsWindow.HALF_SECOND);
		public static final ChoiceSetting<FpsDecimals> DECIMALS = new ChoiceSetting<>("decimals", "Decimals", null, FpsDecimals.class,
			FpsDecimals.NONE);

		/** Why {@code setting} changes nothing for the FPS counter as it is set now, or null. */
		static @Nullable String inactiveReason(ModuleState fps, SettingDescriptor<?> setting) {
			if (STYLE.owns(setting)) {
				return STYLE.inactiveReason(fps, setting);
			}
			if (setting == REVERSED && !fps.bool(SHOW_LABEL)) {
				return "There is no label to move";
			}
			return null;
		}

		private Fps() {
		}
	}

	public static final class Ping {
		public static final ChoiceSetting<LabelPosition> LABEL_POSITION = new ChoiceSetting<>("labelPosition", "Label",
			"After: 32 ms. Before: Ping 32 ms. Hidden: 32.", LabelPosition.class, LabelPosition.AFTER);
		public static final BoolSetting SHOW_BARS = new BoolSetting("showBars", "Signal bars", "Four bars, fewer as the latency grows",
			false);
		public static final BoolSetting COLOR_BY_LATENCY = new BoolSetting("colorByLatency", "Color by latency",
			"Tints the number at Minecraft's player-list steps: 150, 300 and 600 ms", false);
		/** The boxed-counter look (display mode, box, text), as FPS has. */
		public static final BoxStyle STYLE = new BoxStyle(56, 160, 13, FpsSizing.DYNAMIC, 0xFFFFFF, false);

		private Ping() {
		}
	}

	public static final class Cps {
		public static final ChoiceSetting<CpsButtons> BUTTONS = new ChoiceSetting<>("buttons", "Buttons",
			"Combined adds left and right clicks together", CpsButtons.class, CpsButtons.BOTH);
		public static final ChoiceSetting<CpsFormat> FORMAT = new ChoiceSetting<>("format", "Format", "How Both shows the two counts",
			CpsFormat.class, CpsFormat.DOT);
		public static final ChoiceSetting<LabelPosition> LABEL_POSITION = new ChoiceSetting<>("labelPosition", "Label",
			"After: 8 · 3 CPS. Before: CPS 8 · 3. Hidden: 8 · 3.", LabelPosition.class, LabelPosition.AFTER);
		/** The boxed-counter look (display mode, box, text), as FPS has. */
		public static final BoxStyle STYLE = new BoxStyle(56, 160, 13, FpsSizing.DYNAMIC, 0xFFFFFF, false);

		private Cps() {
		}
	}

	public static final class Keystrokes {
		public static final ChoiceSetting<KeystrokesLayout> LAYOUT = new ChoiceSetting<>("layout", "Layout", null, KeystrokesLayout.class,
			KeystrokesLayout.KEYS_MOUSE_JUMP);
		public static final BoolSetting SHOW_CPS = new BoolSetting("showCps", "Clicks per second", "On the mouse buttons", false);
		public static final ChoiceSetting<KeyLabelStyle> LABELS = new ChoiceSetting<>("labels", "Key labels",
			"Your keys shows the keys you've bound; Arrows shows ↑ ← ↓ →", KeyLabelStyle.class, KeyLabelStyle.BOUND);
		public static final IntSetting KEY_SIZE = new IntSetting("keySize", "Key size", null, 16, 12, 24, 1, "");
		public static final IntSetting SPACING = new IntSetting("spacing", "Spacing", "Between keys", 2, 0, 6, 1, "");
		public static final IntSetting CORNERS = new IntSetting("corners", "Corners", "How rounded the keys are", 3, 0, 6, 1, "");
		public static final ChoiceSetting<PressedStyle> PRESSED_STYLE = new ChoiceSetting<>("pressedStyle", "When pressed", null,
			PressedStyle.class, PressedStyle.FILL);
		public static final ColorSetting PRESSED_COLOR = new ColorSetting("pressedColor", "Pressed color",
			"Default follows the accent color", HudColor.DEFAULT);
		public static final IntSetting FADE_MS = new IntSetting("fadeMs", "Press fade", "How long a press eases in and out; off with reduced motion",
			60, 0, 120, 60, "ms");
		public static final OptionSetting ANIMATION = new OptionSetting("animType", "Press animation",
			"Fade eases the whole key in; Sweep moves the colour across it", 0, "fade", "Fade", "fill", "Sweep");
		public static final OptionSetting FILL_DIRECTION = new OptionSetting("direction", "Fill direction", "Which way a press sweeps across the key", 2, "left",
			"Left", "right", "Right", "down", "Down", "up", "Up");
		public static final OptionSetting MOUSE_LABELS = new OptionSetting("clicksDisplayMode", "Mouse key labels", null, 1, "none", "None", "names",
			"Names", "arrows", "Arrows");
		public static final OptionSetting JUMP_LABEL = new OptionSetting("jumpDisplayMode", "Jump key label", null, 2, "none", "None", "names", "Name",
			"arrows", "Bar");
		public static final OptionSetting CPS_PLACE = new OptionSetting("cpsDisplayMode", "Clicks per second shown", null, 0, "with_clicks",
			"On the mouse keys", "separate", "In their own row");
		public static final BoolSetting CPS_REVERSED = new BoolSetting("cpsReversed", "CPS label first", "CPS 12 | 8 instead of 12 | 8 CPS", false);
		public static final ColorSetting TEXT_ACTIVE = new ColorSetting("textActiveColor", "Pressed text color",
			"Default keeps it readable on the press", HudColor.DEFAULT);

		private Keystrokes() {
		}
	}

	public static final class Coordinates {
		public static final ChoiceSetting<CoordinatesLayout> LAYOUT = new ChoiceSetting<>("layout", "Layout", null, CoordinatesLayout.class,
			CoordinatesLayout.VERTICAL);
		public static final ChoiceSetting<Decimals> DECIMALS = new ChoiceSetting<>("decimals", "Decimals", null, Decimals.class,
			Decimals.NONE);
		public static final BoolSetting LABELS = new BoolSetting("labels", "Labels", "The X, Y and Z before each number", true);
		public static final BoolSetting SHOW_DIRECTION = new BoolSetting("showDirection", "Facing", "Adds the way you're facing, like “N”",
			false);
		public static final BoolSetting OTHER_DIMENSION = new BoolSetting("otherDimension", "Nether/Overworld equivalent",
			"Shows the matching position in the other dimension (×8 or ÷8)", false);
		public static final BoolSetting SHOW_COORDINATES = new BoolSetting("showCoordinates", "Coordinates", "X, Y and Z", true);
		public static final BoolSetting SHOW_XZ = new BoolSetting("showXZCoordinates", "X and Z", true);
		public static final BoolSetting SHOW_Y = new BoolSetting("showYCoordinate", "Y", "Your height", true);
		public static final BoolSetting MOVEMENT = new BoolSetting("showMovementDirection", "Which way they grow",
			"A + or − by X and Z: how each changes as you walk forward", false);
		public static final BoolSetting SHOW_ANGLE = new BoolSetting("showAngle", "Angle", "Your facing in degrees, after the point", false);
		public static final OptionSetting ALIGNMENT = new OptionSetting("alignment", "Alignment", null, 0, "left", "Left", "right", "Right");
		public static final ColorSetting FORMAT_COLOR = new ColorSetting("formatColor", "Extras color", "The facing, the signs and the brackets",
			HudColor.DEFAULT);
		public static final BoolSetting BLOCK = new BoolSetting("showBlockCoordinates", "Block position", "The block you stand in, in whole numbers",
			false);
		public static final BoolSetting CHUNK = new BoolSetting("showChunkCoordinates", "Chunk", "Which chunk you're in", false);
		public static final BoolSetting IN_CHUNK = new BoolSetting("formattedChunk", "Place in the chunk", "Adds where in it you stand, 0 to 15",
			false);
		public static final BoolSetting BIOME = new BoolSetting("showBiome", "Biome", false);
		public static final BoolSetting LOADED = new BoolSetting("showChunkCounter", "Loaded chunks", "How many chunks your game holds", false);
		public static final BoolSetting ONE_LINE = new BoolSetting("streamlineOther", "Extras on one line",
			"Block, chunk, biome and loaded chunks share a line", false);
		public static final BoolSetting CHUNK_KEY = new BoolSetting("showKeyChunk", "Chunk label", true);
		public static final BoolSetting BIOME_KEY = new BoolSetting("showKeyBiome", "Biome label", true);
		public static final BoolSetting LOADED_KEY = new BoolSetting("showKeyCounter", "Loaded chunks label", true);
		public static final KeySetting COPY_KEY = new KeySetting("copyCoordinates", "Copy key", "Copies where you stand to the clipboard", "");

		private Coordinates() {
		}
	}

	public static final class Direction {
		public static final ChoiceSetting<DirectionFormat> FORMAT = new ChoiceSetting<>("format", "Format",
			"Compact: N. Name: North. Detailed: North · −Z · 172°.", DirectionFormat.class, DirectionFormat.NAME);
		public static final BoolSetting SHOW_AXIS = new BoolSetting("showAxis", "Axis",
			"Which way the coordinates grow, like −Z. In the Detailed format.", true);
		public static final BoolSetting SHOW_DEGREES = new BoolSetting("showDegrees", "Degrees", "In the Detailed format", true);
		public static final OptionSetting TYPE = new OptionSetting("type", "Type", "Text names the way you face; a compass shows the points around it",
			0, "text", "Text", "default", "Compass", "simple", "Compass, simple", "no_tick", "Compass, no ticks");
		public static final IntSetting WIDTH = new IntSetting("width", "Width", "The compass's length at 1× size", 150, 50, 300, 10, "");
		public static final IntSetting VISIBLE_DEGREES = new IntSetting("visibleDegrees", "Visible degrees", "How much of the turn the compass spans",
			180, 45, 360, 5, "°");
		public static final IntSetting MARKER_SIZE = new IntSetting("markerSize", "Marker size", "The notch under the middle", 3, 1, 9, 1, "");
		public static final RgbSetting MARKER_COLOR = new RgbSetting("markerColor", "Marker color", null, 0xFF8A1F);
		public static final BoolSetting SHADOW = new BoolSetting("shadow", "Letter shadow", "Under the compass's letters", true);
		public static final BoolSetting WAYPOINT_DOTS = new BoolSetting("displayWaypointDots", "Waypoint dots",
			"Your waypoints on the compass, in their colors", true);

		private Direction() {
		}
	}

	/**
	 * Armor: uku's Armor HUD (its options, defaults and ranges, in its Style, Position and Warning tabs). A strip of
	 * hotbar slots holding the armour you wear, next to the hotbar (or along a screen edge, or wherever it is dragged in the
	 * HUD editor), with each item's durability as its bar or as text and a bobbing warning sign on pieces about to break.
	 * Optional slots after the armour show a sword, pickaxe, axe, mace, trident, bow and shield the same way.
	 */
	public static final class Armor {
		// ---------- Style ----------
		public static final OptionSetting STYLE = new OptionSetting("displayStyle", "Style", "The slots' frame: Minecraft's hotbar, with "
			+ "rounded ends, as rounded single slots, or none", 0, "hotbar", "Hotbar", "rounded_corners", "Rounded Corners", "rounded", "Rounded",
			"none", "None");
		public static final OptionSetting WIDGET_SHOWN = new OptionSetting("widgetShown", "Show widget",
			"Always: every slot. If any present: every slot once you wear or carry anything it shows. Not empty: only what you wear and carry. "
				+ "Damaged pieces: only what is below the warning level", 2, "always", "Always", "if_any_present", "If Any Present", "not_empty",
			"Not Empty", "damaged_pieces", "Damaged Pieces");
		public static final OptionSetting OFFHAND_SLOT = new OptionSetting("offhandSlotBehavior", "Offhand slot",
			"Next to the hotbar: Adhere moves aside for the offhand slot and the attack indicator only while they show; Always leave space "
				+ "keeps their room", 1, "always_ignore", "Always Ignore", "adhere", "Adhere", "always_leave_space", "Always Leave Space");
		public static final OptionSetting DURABILITY = new OptionSetting("durabilityDisplay", "Durability display",
			"Bar: each item's own bar. Numeric and Percentage: the durability left as text", 0, "bar", "Bar", "numeric", "Numeric", "percentage",
			"Percentage");
		public static final BoolSetting REVERSED = new BoolSetting("reversed", "Reversed",
			"The strip the other way round: boots first instead of the helmet, the items before the armor", false);
		public static final BoolSetting ICONS = new BoolSetting("iconsShown", "Show icons",
			"An empty slot shows the outline of what goes there (with Show widget Always or If Any Present)", true);
		// ---------- Items: slots after the armour, each found in your hands or hotbar ----------
		public static final BoolSetting SWORD = new BoolSetting("showSword", "Sword",
			"A slot after the armor: the sword you hold, else the first one in your hotbar", false);
		public static final BoolSetting PICKAXE = new BoolSetting("showPickaxe", "Pickaxe",
			"A slot after the armor: the pickaxe you hold, else the first one in your hotbar", false);
		public static final BoolSetting AXE = new BoolSetting("showAxe", "Axe", "A slot after the armor: the axe you hold, else the first one in your "
			+ "hotbar", false);
		public static final BoolSetting MACE = new BoolSetting("showMace", "Mace", "A slot after the armor: the mace you hold, else the first one in "
			+ "your hotbar", false);
		public static final BoolSetting TRIDENT = new BoolSetting("showTrident", "Trident",
			"A slot after the armor: the trident you hold, else the first one in your hotbar", false);
		public static final BoolSetting BOW = new BoolSetting("showBow", "Bow",
			"A slot after the armor: the bow or crossbow you hold, else the first one in your hotbar", false);
		public static final BoolSetting SHIELD = new BoolSetting("showShield", "Shield",
			"A slot after the armor: the shield in your offhand, else the one you hold, else the first one in your hotbar", false);
		public static final BoolSetting MAINHAND_DURABILITY = new BoolSetting("mainHandDurability", "Show main hand durability",
			"The durability of what you hold, over its hotbar slot (as text: not with Bar), unless a slot after the armor shows it", false);
		public static final BoolSetting OFFHAND_DURABILITY = new BoolSetting("offHandDurability", "Show offhand durability",
			"The durability (and warning) of what you hold in your offhand, above its slot, unless the Shield slot shows it", true);
		// ---------- Text ----------
		public static final OptionSetting TEXT_POSITION = new OptionSetting("textPosition", "Text position",
			"Where each piece's durability text (and warning sign) goes. Auto: away from the hotbar or the screen's edge. Left and Right "
				+ "for a vertical strip, Above and Below for a horizontal one", 0, "auto", "Auto", "left", "Left", "right", "Right", "above",
			"Above", "below", "Below");
		/** The durability text's own size, in percent: 50 to 200 in steps of 5. */
		public static final IntSetting TEXT_SCALE = new IntSetting("textScale", "Text size",
			"The durability text's own size, whatever the HUD's size; it stays lined up with its slot", 100, 50, 200, 5, "%");
		public static final BoolSetting TEXT_BOLD = new BoolSetting("textBold", "Bold text", "The durability text in bold", false);
		public static final BoolSetting TEXT_SHADOW = new BoolSetting("textShadow", "Text shadow", "Minecraft's shadow under the durability text",
			true);
		public static final OptionSetting TEXT_COLOR_MODE = new OptionSetting("textColorMode", "Text color mode",
			"Durability: green to red as the piece wears, like its bar. Custom: the color below", 0, "durability", "Durability", "custom",
			"Custom");
		public static final RgbSetting TEXT_COLOR = new RgbSetting("textColor", "Text color", "With the Custom text color mode", 0xFFFFFF);
		// ---------- Position ----------
		/** {@link #ANCHOR}'s choices: uku's five, and Free (placed by its HUD placement, as the HUD editor drags it). */
		public static final int TOP_CENTER = 0;
		public static final int TOP = 1;
		public static final int VERTICAL_CENTER = 2;
		public static final int BOTTOM = 3;
		public static final int HOTBAR = 4;
		public static final int FREE = 5;
		public static final OptionSetting ANCHOR = new OptionSetting("anchor", "Anchor",
			"What the strip is placed against. Free: wherever you drag it in the HUD editor (Right Shift); dragging it there makes it Free",
			HOTBAR, "top_center", "Top Center", "top", "Top", "vertical_center", "Vertical Center", "bottom", "Bottom", "hotbar", "Hotbar", "free",
			"Free");
		public static final OptionSetting SIDE = new OptionSetting("side", "Side", "Which side of the hotbar (or of the screen) it goes on", 0, "left",
			"Left", "right", "Right");
		public static final ChoiceSetting<Orientation> LAYOUT = new ChoiceSetting<>("layout", "Orientation", null, Orientation.class,
			Orientation.HORIZONTAL);
		public static final BoolSetting PUSH_BOSSBARS = new BoolSetting("pushBossbars", "Push bossbars",
			"With the Top Center anchor, boss bars move down below the strip", true);
		public static final BoolSetting PUSH_EFFECTS = new BoolSetting("pushStatusEffectIcons", "Push effect icons",
			"With the Top anchor on the right, Minecraft's effect icons move down below the strip", true);
		public static final BoolSetting PUSH_SUBTITLES = new BoolSetting("pushSubtitles", "Push subtitles",
			"With the Bottom anchor on the right, subtitles move up above the strip", true);
		public static final IntSetting OFFSET_X = new IntSetting("offsetX", "X offset", "Away from the hotbar or the screen's side", 0, -500, 500, 1,
			"");
		public static final IntSetting OFFSET_Y = new IntSetting("offsetY", "Y offset", "Away from the screen's top or bottom", 0, -500, 500, 1, "");
		// ---------- Warning ----------
		public static final BoolSetting BREAK_SOUND = new BoolSetting("playBreakSound", "Play breaking sound",
			"A sound when a piece (or an item whose durability shows) wears down while below the warning level", true);
		public static final BoolSetting WARNING = new BoolSetting("warningShown", "Show warning", "A warning sign by pieces about to break", true);
		public static final IntSetting BOB = new IntSetting("warningBobIntensity", "Icon bob intensity", "How much the warning sign shakes; 0 keeps it "
			+ "still", 3, 0, 20, 1, "");
		public static final IntSetting MIN_VALUE = new IntSetting("minDurabilityValue", "Min durability",
			"A piece with this many uses left (or fewer) is about to break", 20, 0, 2500, 1, "");
		public static final IntSetting MIN_PERCENT = new IntSetting("minDurabilityPercentage", "Min durability percentage",
			"A piece with this much of its durability left (or less) is about to break", 10, 0, 100, 1, "%");

		/** The item slots' switches, in the order their slots follow the armour (the kinds of {@code control.feature.ArmorPieces}). */
		public static final java.util.List<BoolSetting> ITEMS = java.util.List.of(SWORD, PICKAXE, AXE, MACE, TRIDENT, BOW, SHIELD);

		private Armor() {
		}

		/** Whether its own anchor places it now (against the hotbar or a screen edge, as uku's Armor HUD does), not its HUD placement. */
		public static boolean anchored(ModuleState armor) {
			return armor.option(ANCHOR) != FREE;
		}

		/** Back against the hotbar on the left with no offsets, as it starts. Returns whether this changed anything. */
		static boolean placeHome(ModuleState armor) {
			boolean changed = armor.resetSetting(ANCHOR);
			changed |= armor.resetSetting(SIDE);
			changed |= armor.resetSetting(OFFSET_X);
			changed |= armor.resetSetting(OFFSET_Y);
			return changed;
		}
	}

	public static final class Effects {
		/** The look: Minecraft's effect tiles in the top-right corner (the default), or Flint's list of icon, name and time. */
		public static final int TILES = 0;
		public static final int LIST = 1;
		public static final OptionSetting VIEW = new OptionSetting("view", "Layout",
			"Tiles: Minecraft's effect tiles in the corner, the time and level on each. List: icon, name and time in rows", TILES, "tiles", "Tiles",
			"list", "List");
		// ---------- Tiles ----------
		public static final OptionSetting TILE_DIRECTION = new OptionSetting("tileDirection", "Direction",
			"Row: side by side, as Minecraft lays them out. Column: one above the other, with room for names", 0, "row", "Row", "column",
			"Column");
		public static final BoolSetting TILE_SPLIT = new BoolSetting("tileSplit", "Harmful on their own line",
			"As Minecraft shows them: harmful effects in a second row (or column)", true);
		public static final OptionSetting TILE_TIME = new OptionSetting("tileTime", "Time position", null, 1, "below", "Below the tile",
			"inside", "On the tile");
		/** The level in the tile's top-right corner, from level II (as Minecraft names levels): off, a numeral ("II") or a number ("2"). */
		public static final int TILE_LEVEL_OFF = 0;
		public static final int TILE_LEVEL_NUMBER = 2;
		public static final OptionSetting TILE_LEVEL = new OptionSetting("tileLevel", "Level on the tile",
			"In the tile's top-right corner, from level II", 1, "off", "Off", "roman", "II", "number", "2");
		public static final BoolSetting TILE_SHADOW = new BoolSetting("tileShadow", "Time shadow",
			"Minecraft's shadow under the time (and the names), so it reads over snow and sky", true);
		public static final IntSetting TILE_GAP = new IntSetting("tileGap", "Tile spacing", "Between tiles", 1, 0, 8, 1, "");
		public static final OptionSetting TILE_BACKGROUND = new OptionSetting("tileBackground", "Tile background",
			"Minecraft: its own effect tile (beacon effects in their own). Custom: the colors below", 0, "minecraft", "Minecraft", "custom",
			"Custom", "none", "None");
		public static final RgbSetting TILE_COLOR = new RgbSetting("tileColor", "Tile color", "Custom background only", 0x212121);
		public static final IntSetting TILE_OPACITY = new IntSetting("tileOpacity", "Tile opacity", "Custom background only", 85, 0, 100, 5,
			"%");
		public static final RgbSetting TILE_BORDER = new RgbSetting("tileBorderColor", "Border color", "Custom background only", 0x555555);
		public static final RgbSetting TILE_HARMFUL_BORDER = new RgbSetting("tileHarmfulBorderColor", "Harmful border color",
			"Custom background only", 0xB4504B);
		// ---------- List ----------
		public static final ChoiceSetting<Orientation> LAYOUT = new ChoiceSetting<>("layout", "Layout", null, Orientation.class,
			Orientation.VERTICAL);
		public static final BoolSetting SHOW_ICONS = new BoolSetting("showIcons", "Effect icons", true);
		public static final BoolSetting SHOW_NAMES = new BoolSetting("showNames", "Effect names", true);
		public static final BoolSetting SHOW_LEVEL = new BoolSetting("showLevel", "Level", "The level in Roman numerals, like II", true);
		public static final BoolSetting SHOW_DURATION = new BoolSetting("showDuration", "Time left", true);
		public static final ChoiceSetting<DurationFormat> DURATION_FORMAT = new ChoiceSetting<>("durationFormat", "Time format", null,
			DurationFormat.class, DurationFormat.CLOCK);
		public static final IntSetting SPACING = new IntSetting("spacing", "Spacing", "Between effects", 2, 0, 6, 1, "");
		public static final ChoiceSetting<EffectSort> SORT = new ChoiceSetting<>("sort", "Order", null, EffectSort.class,
			EffectSort.TIME_LEFT);
		public static final BoolSetting BLINK_ENDING = new BoolSetting("blinkEnding", "Blink when ending",
			"In the last 10 seconds; off with reduced motion", true);
		public static final BoolSetting HIDE_VANILLA_EFFECTS = new BoolSetting("hideVanillaEffects", "Hide Minecraft's effect icons",
			"Hides the icons in the top-right corner while this module is on", true).explained();
		public static final OptionSetting STYLE = new OptionSetting("displayStyle", "Display style", "Framed puts each icon in Minecraft's effect frame",
			0, "clean", "Clean", "framed", "Framed");
		public static final OptionSetting TEXT_SIDE = new OptionSetting("textAlignment", "Text position", "Where the text sits by its icon", 1, "left",
			"Left", "right", "Right", "bottom", "Below", "top", "Above", "embed", "On the icon");
		public static final BoolSetting SHORTHAND = new BoolSetting("durationShorthand", "Short time", "1m 30s instead of 1:30", false);
		public static final BoolSetting TWO_LINES = new BoolSetting("twoLines", "Two lines",
			"With the text beside the icon: the name above the time, by a larger icon, as Feather shows them", true);
		public static final IntSetting BLINK_SECONDS = new IntSetting("durationBlinkSeconds", "Blink from", "How long before the end it starts", 10, 1,
			60, 1, " s");
		public static final BoolSetting LOSS_SOUND = new BoolSetting("effectLossSound", "Sound when one ends", false);
		public static final IntSetting LOSS_VOLUME = new IntSetting("effectLossSoundVolume", "Sound volume", null, 50, 0, 100, 5, "%");
		public static final RgbSetting TIME_COLOR = new RgbSetting("durationColor", "Time color", null, 0xB4B4B4);
		public static final BoolSetting EFFECT_COLORS = new BoolSetting("useCustomEffectNameColor", "Effect colors", "Each name in its effect's own color",
			true);
		public static final BoolSetting HIDE_INVENTORY = new BoolSetting("hideInventoryStatus", "Hide inventory list",
			"Minecraft's list of effects beside your inventory (where Status Effect Bars draws its inventory bars)", false);

		// ---------- Status Effect Bars: a bar along each effect icon showing the time left ----------
		public static final OptionSetting BAR_COLOR_MODE = new OptionSetting("barColorMode", "Color mode",
			"Effect: the effect's particle color. Category: the effect category's color. Custom: the colors below", 0, "effect_color", "Effect",
			"category_color", "Category", "custom", "Custom");
		public static final RgbSetting BAR_BACKGROUND = new RgbSetting("barBackgroundColor", "Background color", "The part of the bar already used",
			0x000000);
		public static final IntSetting BAR_BACKGROUND_OPACITY = new IntSetting("barBackgroundOpacity", "Background opacity", null, 50, 0, 100, 1, "%");
		public static final RgbSetting BAR_BENEFICIAL = new RgbSetting("barBeneficialColor", "Beneficial bar color", "Custom mode only", 0xFFFFFF);
		public static final IntSetting BAR_BENEFICIAL_OPACITY = new IntSetting("barBeneficialOpacity", "Beneficial bar opacity", "Custom mode only", 50,
			0, 100, 1, "%");
		public static final RgbSetting BAR_HARMFUL = new RgbSetting("barHarmfulColor", "Harmful bar color", "Custom mode only", 0xFFFFFF);
		public static final IntSetting BAR_HARMFUL_OPACITY = new IntSetting("barHarmfulOpacity", "Harmful bar opacity", "Custom mode only", 50, 0,
			100, 1, "%");
		public static final RgbSetting BAR_NEUTRAL = new RgbSetting("barNeutralColor", "Neutral bar color", "Custom mode only", 0xFFFFFF);
		public static final IntSetting BAR_NEUTRAL_OPACITY = new IntSetting("barNeutralOpacity", "Neutral bar opacity", "Custom mode only", 50, 0,
			100, 1, "%");
		public static final IntSetting BAR_MAX_REMAINING = new IntSetting("barMaxRemainingDuration", "Maximum remaining duration",
			"Remaining time in seconds above which the bar is hidden; 0 for no limit", 0, 0, 3600, 1, " s");
		public static final IntSetting BAR_MIN_AMBIENT_AGE = new IntSetting("barMinAmbientAge", "Minimum beacon effect age",
			"Age of beacon effects in ticks under which their bars are hidden", 90, 0, 1200, 1, " ticks");
		/** The bar's layout on the HUD's effect icons ({@code hud} true) and in the inventory's effect list. */
		public static final BarLayout HUD_BARS = new BarLayout("hudBars", "HUD", 3, 2);
		public static final BarLayout INVENTORY_BARS = new BarLayout("inventoryBars", "Inventory", 4, 3);

		/** Where a bar sits on its icon: Status Effect Bars' layout options, one set for the HUD and one for the inventory. */
		public static final class BarLayout {
			public final BoolSetting enabled;
			public final OptionSetting direction;
			public final BoolSetting relativeToEnd;
			public final IntSetting thickness;
			public final IntSetting collinearPadding;
			public final IntSetting collinearOffset;
			public final IntSetting orthogonalOffset;

			BarLayout(String prefix, String where, int padding, int offset) {
				enabled = new BoolSetting(prefix + "Enabled", "Enabled", where.equals("HUD") ? "Bars on the HUD's effect icons (Minecraft's and this "
					+ "module's)" : "Bars in the effect list beside your inventory", true);
				direction = new OptionSetting(prefix + "Direction", "Direction", "Which way the bar runs as time passes", 0, "left_to_right",
					"Left to Right", "right_to_left", "Right to Left", "bottom_to_top", "Bottom to Top", "top_to_bottom", "Top to Bottom");
				relativeToEnd = new BoolSetting(prefix + "RelativeToEnd", "Align to bottom/right",
					"Align to the bottom (or the right, depending on the direction) instead of the top (or the left)", true);
				thickness = new IntSetting(prefix + "Thickness", "Thickness", null, 1, 0, 32, 1, "");
				collinearPadding = new IntSetting(prefix + "CollinearPadding", "Collinear padding", "Padding on both sides of the bar", padding, 0, 32,
					1, "");
				collinearOffset = new IntSetting(prefix + "CollinearOffset", "Collinear offset", "Offset along the bar's main axis", 0, -32, 32, 1, "");
				orthogonalOffset = new IntSetting(prefix + "OrthogonalOffset", "Orthogonal offset", "Offset perpendicular to the bar", offset, -32, 32,
					1, "");
			}

			public SettingDescriptor<?>[] settings() {
				return new SettingDescriptor<?>[] {enabled, direction, relativeToEnd, thickness, collinearPadding, collinearOffset, orthogonalOffset};
			}

			public boolean owns(SettingDescriptor<?> setting) {
				return setting == direction || setting == relativeToEnd || setting == thickness || setting == collinearPadding
					|| setting == collinearOffset || setting == orthogonalOffset;
			}
		}

		private Effects() {
		}
	}

	// ---------- questions settings panels ask ----------

	/** The module's label position setting (FPS, Ping, CPS), or null for a module without one. */
	public static @Nullable ChoiceSetting<LabelPosition> labelPosition(ModuleDescriptor module) {
		return switch (module.id()) {
			case ModuleId.PING -> Ping.LABEL_POSITION;
			case ModuleId.CPS -> Cps.LABEL_POSITION;
			default -> null;
		};
	}

	/**
	 * The module's own setting that decides its label (the label position, or the coordinates' X/Y/Z labels), or
	 * null when the shared "Show label" does. Settings panels leave "Show label" out for a module that has one.
	 */
	public static @Nullable SettingDescriptor<?> labelSetting(ModuleDescriptor module) {
		if (ModuleId.FPS.equals(module.id())) {
			return Fps.SHOW_LABEL;
		}
		ChoiceSetting<LabelPosition> position = labelPosition(module);
		if (position != null) {
			return position;
		}
		return ModuleId.COORDINATES.equals(module.id()) ? Coordinates.LABELS : null;
	}

	/**
	 * Why {@code setting} changes nothing for {@code module} as it is set now (another of its settings makes it
	 * moot, like the CPS format with one button), or null when it applies. Panels show the control disabled with
	 * this reason. Allocation-free; null for a setting that isn't the module's own.
	 */
	public static @Nullable String inactiveReason(ModuleState module, SettingDescriptor<?> setting) {
		if (module.descriptor().indexOf(setting) < 0) {
			return null;
		}
		if (setting == module.descriptor().modKeyNotify()) {
			return module.text(module.descriptor().modKey()).isEmpty() ? "Set a toggle key first" : null;
		}
		if (ModuleId.FPS.equals(module.id())) {
			return Fps.inactiveReason(module, setting);
		}
		BoxStyle style = module.descriptor().boxStyle();
		if (style != null && style.owns(setting)) {
			return style.inactiveReason(module, setting);
		}
		if (setting == Cps.FORMAT) {
			return module.choice(Cps.BUTTONS) == CpsButtons.BOTH ? null : "Only when Buttons is Both";
		}
		if (setting == Direction.FORMAT || setting == Direction.SHOW_AXIS || setting == Direction.SHOW_DEGREES) {
			if (module.option(Direction.TYPE) != 0) {
				return "Only for the Text type";
			}
			return setting == Direction.FORMAT || module.choice(Direction.FORMAT) == DirectionFormat.DETAILED ? null : "Only in the Detailed format";
		}
		if (setting == Direction.WIDTH || setting == Direction.VISIBLE_DEGREES || setting == Direction.MARKER_SIZE || setting == Direction.MARKER_COLOR
			|| setting == Direction.SHADOW || setting == Direction.WAYPOINT_DOTS) {
			return module.option(Direction.TYPE) != 0 ? null : "Only for a compass type";
		}
		if (setting == Keystrokes.SHOW_CPS) {
			return module.choice(Keystrokes.LAYOUT).mouse() ? null : "Needs the mouse buttons in the layout";
		}
		if (setting == Keystrokes.CPS_PLACE || setting == Keystrokes.CPS_REVERSED) {
			if (!module.choice(Keystrokes.LAYOUT).mouse()) {
				return "Needs the mouse buttons in the layout";
			}
			if (!module.bool(Keystrokes.SHOW_CPS)) {
				return "Turn on Clicks per second first";
			}
			return setting == Keystrokes.CPS_REVERSED && module.option(Keystrokes.CPS_PLACE) != 1 ? "Only in their own row" : null;
		}
		if (setting == Keystrokes.MOUSE_LABELS) {
			return module.choice(Keystrokes.LAYOUT).mouse() ? null : "Needs the mouse buttons in the layout";
		}
		if (setting == Keystrokes.JUMP_LABEL) {
			return module.choice(Keystrokes.LAYOUT).jump() ? null : "Needs the jump key in the layout";
		}
		if (setting == Keystrokes.ANIMATION) {
			return module.choice(Keystrokes.PRESSED_STYLE) == PressedStyle.FILL ? null : "Only when pressed keys fill";
		}
		if (setting == Keystrokes.FILL_DIRECTION) {
			if (module.choice(Keystrokes.PRESSED_STYLE) != PressedStyle.FILL) {
				return "Only when pressed keys fill";
			}
			return module.option(Keystrokes.ANIMATION) == 1 ? null : "Only with the Sweep animation";
		}
		if (setting == Keystrokes.PRESSED_COLOR) {
			return module.choice(Keystrokes.PRESSED_STYLE) == PressedStyle.INVERT ? "Invert uses the text color" : null;
		}
		if (setting == Coordinates.SHOW_XZ || setting == Coordinates.SHOW_Y) {
			return module.bool(Coordinates.SHOW_COORDINATES) ? null : "Turn on Coordinates first";
		}
		if (setting == Coordinates.MOVEMENT) {
			return module.bool(Coordinates.SHOW_COORDINATES) && module.bool(Coordinates.SHOW_XZ) ? null : "Needs X and Z shown";
		}
		if (setting == Coordinates.SHOW_ANGLE) {
			return module.bool(Coordinates.SHOW_DIRECTION) ? null : "Turn on Facing first";
		}
		if (setting == Coordinates.IN_CHUNK || setting == Coordinates.CHUNK_KEY) {
			return module.bool(Coordinates.CHUNK) ? null : "Turn on Chunk first";
		}
		if (setting == Coordinates.BIOME_KEY) {
			return module.bool(Coordinates.BIOME) ? null : "Turn on Biome first";
		}
		if (setting == Coordinates.LOADED_KEY) {
			return module.bool(Coordinates.LOADED) ? null : "Turn on Loaded chunks first";
		}
		if (setting == Coordinates.ONE_LINE || setting == Coordinates.ALIGNMENT) {
			return module.choice(Coordinates.LAYOUT) == CoordinatesLayout.VERTICAL ? null : "Only in the stacked layout";
		}
		if (ModuleId.ARMOR.equals(module.id())) {
			String armor = armorReason(module, setting);
			if (armor != null) {
				return armor;
			}
		}
		if (setting == Effects.BAR_BENEFICIAL || setting == Effects.BAR_BENEFICIAL_OPACITY || setting == Effects.BAR_HARMFUL
			|| setting == Effects.BAR_HARMFUL_OPACITY || setting == Effects.BAR_NEUTRAL || setting == Effects.BAR_NEUTRAL_OPACITY) {
			return module.option(Effects.BAR_COLOR_MODE) == 2 ? null : "Only in the Custom color mode";
		}
		if (Effects.HUD_BARS.owns(setting)) {
			return module.bool(Effects.HUD_BARS.enabled) ? null : "Turn on HUD bars first";
		}
		if (setting == Effects.INVENTORY_BARS.enabled) {
			return module.bool(Effects.HIDE_INVENTORY) ? "The inventory list is hidden" : null;
		}
		if (Effects.INVENTORY_BARS.owns(setting)) {
			return module.bool(Effects.HIDE_INVENTORY) ? "The inventory list is hidden"
				: module.bool(Effects.INVENTORY_BARS.enabled) ? null : "Turn on inventory bars first";
		}
		if (setting == Effects.DURATION_FORMAT) {
			return module.bool(Effects.SHOW_DURATION) ? null : "Only when Time left is on";
		}
		String effects = ModuleId.EFFECTS.equals(module.id()) ? effectsReason(module, setting) : null;
		if (effects != null) {
			return effects;
		}
		if (setting == CameraMods.Camera.EASING) {
			return module.bool(CameraMods.Camera.SMOOTH) ? null : "Only with Camera smooth zoom on";
		}
		if (setting == HudMods.Saturation.SHOW_FOOD || setting == HudMods.Saturation.SHOW_LABEL) {
			return module.bool(HudMods.Saturation.HUD) ? null : "Turn on HUD first";
		}
		if (setting == HudMods.Saturation.COLOR) {
			return module.bool(HudMods.Saturation.COLOR_OVERLAY) || module.bool(HudMods.Saturation.COLOR_GAIN)
				|| module.bool(HudMods.Saturation.COLOR_TOOLTIP) ? null : "Turn on one of the color switches below first";
		}
		if (setting == HudMods.Saturation.COLOR_OVERLAY) {
			return module.bool(HudMods.Saturation.OVERLAY) ? null : "Turn on Saturation overlay first";
		}
		if (setting == HudMods.Saturation.COLOR_GAIN) {
			return gainShown(module) ? null : "Turn on Saturation gain overlay first";
		}
		if (setting == HudMods.Saturation.COLOR_TOOLTIP) {
			return module.option(HudMods.Saturation.TOOLTIP) != 0 ? null : "Turn on Tooltip food values first";
		}
		if (setting == HudMods.Saturation.SATURATION_GAIN) {
			return module.bool(HudMods.Saturation.FOOD_GAIN) && module.bool(HudMods.Saturation.OVERLAY) ? null
				: "Needs Food gain overlay and Saturation overlay on";
		}
		if (setting == HudMods.Saturation.ANIMATION_SPEED || setting == HudMods.Saturation.FLASH_ALPHA
			|| setting == HudMods.Saturation.OFFHAND) {
			return module.bool(HudMods.Saturation.FOOD_GAIN) || module.bool(HudMods.Saturation.HEALTH_GAIN) ? null
				: "Turn on a gain overlay first";
		}
		if (setting == com.flintclient.client.control.model.mods.ServerMods.NoChatReports.ICON_X
			|| setting == com.flintclient.client.control.model.mods.ServerMods.NoChatReports.ICON_Y) {
			return module.bool(com.flintclient.client.control.model.mods.ServerMods.NoChatReports.VERIFIED_ICON) ? null
				: "Turn on Verified icon first";
		}
		if (setting == com.flintclient.client.control.model.mods.ServerMods.NoChatReports.REMOVE_TELEMETRY_BUTTON) {
			return module.bool(com.flintclient.client.control.model.mods.ServerMods.NoChatReports.DISABLE_TELEMETRY) ? null
				: "Turn on Disable telemetry first";
		}
		if (setting == com.flintclient.client.control.model.mods.ServerMods.NoChatReports.DEFAULT_MODE
			|| setting == com.flintclient.client.control.model.mods.ServerMods.NoChatReports.DEMAND_ON_SERVER
			|| setting == com.flintclient.client.control.model.mods.ServerMods.NoChatReports.SHOW_SAFETY) {
			return module.bool(com.flintclient.client.control.model.mods.ServerMods.NoChatReports.ENABLE_MOD) ? null
				: "Turn on Enable mod first";
		}
		if (setting == HudMods.Saturation.SHAKE) {
			return module.bool(HudMods.Saturation.OVERLAY) || module.bool(HudMods.Saturation.FOOD_GAIN)
				|| module.bool(HudMods.Saturation.HEALTH_GAIN) ? null : "Turn on an overlay first";
		}
		if (setting == HudMods.Saturation.NATURAL_REGEN) {
			return module.bool(HudMods.Saturation.HEALTH_GAIN) ? null : "Turn on Health gain overlay first";
		}
		if (setting == UtilityMods.ShulkerPreview.HINT || setting == UtilityMods.ShulkerPreview.KEY) {
			return module.bool(UtilityMods.ShulkerPreview.HOLD) ? null : "Turn on Hold a key to preview first";
		}
		if (setting == VisualMods.WavyCapes.GRAVITY || setting == VisualMods.WavyCapes.HEIGHT || setting == VisualMods.WavyCapes.SIDEWAYS
			|| setting == VisualMods.WavyCapes.GRAVITY_VECTOR) {
			int movement = module.option(VisualMods.WavyCapes.MOVEMENT);
			if (movement == VisualMods.WavyCapes.VANILLA) {
				return "Only with a simulated movement";
			}
			return setting == VisualMods.WavyCapes.SIDEWAYS && movement == VisualMods.WavyCapes.SIMULATION
				? "Simulation (2D) doesn't swing sideways" : null;
		}
		if (setting == VisualMods.Hotbar.EXPERIENCE_SCALE) {
			return module.bool(VisualMods.Hotbar.EXPERIENCE_FOLLOWS) ? "Following the hotbar's size" : null;
		}
		if (setting == VisualMods.Hotbar.HEALTH_SCALE) {
			return module.bool(VisualMods.Hotbar.HEALTH_FOLLOWS) ? "Following the hotbar's size" : null;
		}
		if (setting == VisualMods.Hotbar.HUNGER_SCALE) {
			return module.bool(VisualMods.Hotbar.HUNGER_FOLLOWS) ? "Following the hotbar's size" : null;
		}
		if (setting == VisualMods.Hotbar.ARMOR_SCALE) {
			return module.bool(VisualMods.Hotbar.ARMOR_FOLLOWS) ? "Following the hotbar's size" : null;
		}
		if (setting == VisualMods.AutohideHud.DURATION) {
			return module.bool(VisualMods.AutohideHud.ANIMATE) ? null : "Only with Hide animation on";
		}
		if (setting == VisualMods.ViewModel.SCALE_X || setting == VisualMods.ViewModel.SCALE_Y || setting == VisualMods.ViewModel.SCALE_Z) {
			return module.bool(VisualMods.ViewModel.SCALE) ? null : "Turn on Scale first";
		}
		if (setting == VisualMods.ViewModel.POSITION_X || setting == VisualMods.ViewModel.POSITION_Y || setting == VisualMods.ViewModel.POSITION_Z) {
			return module.bool(VisualMods.ViewModel.POSITION) ? null : "Turn on Position first";
		}
		if (setting == VisualMods.ViewModel.ROTATION_X || setting == VisualMods.ViewModel.ROTATION_Y || setting == VisualMods.ViewModel.ROTATION_Z) {
			return module.bool(VisualMods.ViewModel.ROTATION) ? null : "Turn on Rotation first";
		}
		if (setting == UtilityMods.Reconnect.ATTEMPTS || setting == UtilityMods.Reconnect.DELAY || setting == UtilityMods.Reconnect.INCREMENT_DELAY) {
			return module.bool(UtilityMods.Reconnect.AUTO) ? null : "Only with Auto reconnect on";
		}
		if (setting == UtilityMods.Reconnect.INCREMENT || setting == UtilityMods.Reconnect.MAX_DELAY) {
			return !module.bool(UtilityMods.Reconnect.AUTO) ? "Only with Auto reconnect on"
				: module.bool(UtilityMods.Reconnect.INCREMENT_DELAY) ? null : "Only with Increment delay on";
		}
		if (setting == UtilityMods.ToastControl.BLOCK_ADVANCEMENTS || setting == UtilityMods.ToastControl.BLOCK_RECIPES
			|| setting == UtilityMods.ToastControl.BLOCK_SYSTEM || setting == UtilityMods.ToastControl.BLOCK_TUTORIAL
			|| setting == UtilityMods.ToastControl.BLOCK_VANILLA || setting == UtilityMods.ToastControl.BLOCK_MODDED
			|| setting == UtilityMods.ToastControl.BLOCKED_CLASSES) {
			return module.bool(UtilityMods.ToastControl.BLOCK_ALL) ? "Block all is on" : null;
		}
		if (setting == com.flintclient.client.control.model.mods.MoreMods.Discord.DIMENSION_MESSAGE
			|| setting == com.flintclient.client.control.model.mods.MoreMods.Discord.DIMENSION_PREFIX) {
			return module.bool(com.flintclient.client.control.model.mods.MoreMods.Discord.DIMENSION_PRESENCE) ? null : "Turn on Dimension presence first";
		}
		if (setting == com.flintclient.client.control.model.mods.MoreMods.Discord.SCREENS
			|| setting == com.flintclient.client.control.model.mods.MoreMods.Discord.SCREEN_MESSAGE) {
			return module.bool(com.flintclient.client.control.model.mods.MoreMods.Discord.SCREEN_PRESENCE) ? null : "Turn on Screen presence first";
		}
		if (setting == UtilityMods.TabEditor.PING_SHADOW) {
			return module.bool(UtilityMods.TabEditor.PING_NUMBER) ? null : "Turn on Display ping as number first";
		}
		if (setting == UtilityMods.TabEditor.PING_GOOD || setting == UtilityMods.TabEditor.PING_OK || setting == UtilityMods.TabEditor.PING_BAD
			|| setting == UtilityMods.TabEditor.PING_TERRIBLE || setting == UtilityMods.TabEditor.PING_UNKNOWN || setting == UtilityMods.TabEditor.PING_MS) {
			return module.bool(UtilityMods.TabEditor.PING_NUMBER) || module.bool(UtilityMods.TabEditor.PING_NAMETAGS) ? null
				: "Turn on Display ping as number or Show ping on nametags first";
		}
		if (setting == UtilityMods.Titles.SCALE || setting == UtilityMods.Titles.AUTO_SCALE || setting == UtilityMods.Titles.TITLE_OFFSET
			|| setting == UtilityMods.Titles.SUBTITLE_OFFSET || setting == UtilityMods.Titles.OPACITY || setting == UtilityMods.Titles.NO_SHADOW) {
			return module.bool(UtilityMods.Titles.DISABLE) ? "Titles are disabled" : null;
		}
		if (UtilityMods.Chat.ID.equals(module.id())) {
			return chatInactiveReason(module, setting);
		}
		if (com.flintclient.client.control.model.mods.SoundMods.SoundPhysics.ID.equals(module.id())) {
			return com.flintclient.client.control.model.mods.SoundMods.inactiveReason(module, setting);
		}
		if (com.flintclient.client.control.model.mods.MoreMods.Subtitles.ID.equals(module.id())) {
			return com.flintclient.client.control.model.mods.MoreMods.Subtitles.inactiveReason(module, setting);
		}
		if (com.flintclient.client.control.model.mods.MusicMods.MusicPlayer.ID.equals(module.id())) {
			return com.flintclient.client.control.model.mods.MusicMods.MusicPlayer.inactiveReason(module, setting);
		}
		String nametags = nametagsReason(module, setting);
		if (nametags != null) {
			return nametags;
		}
		String parity = com.flintclient.client.control.model.mods.ParityReasons.reason(module, setting);
		return parity != null ? parity : inventoryReason(module, setting);
	}

	/**
	 * Armor: the text's rows wait for text durability, the anchors' details for their anchor (none apply once it is Free), and
	 * what Minecraft 1.8.9 doesn't have (an offhand, shields, tridents, maces) is greyed out there.
	 */
	private static @Nullable String armorReason(ModuleState module, SettingDescriptor<?> setting) {
		if (!com.flintclient.client.compat.McCompat.OFFHAND && (setting == Armor.OFFHAND_DURABILITY || setting == Armor.SHIELD
			|| setting == Armor.TRIDENT || setting == Armor.MACE)) {
			return "Not in Minecraft 1.8.9";
		}
		int anchor = module.option(Armor.ANCHOR);
		if (setting == Armor.ICONS) {
			int shown = module.option(Armor.WIDGET_SHOWN);
			return shown == 0 || shown == 1 ? null : "Only when Show Widget is Always or If Any Present";
		}
		if (setting == Armor.SIDE || setting == Armor.OFFSET_X || setting == Armor.OFFSET_Y) {
			return anchor == Armor.FREE ? "Free: where you drag it in the HUD editor" : null;
		}
		if (setting == Armor.OFFHAND_SLOT) {
			return anchor == Armor.HOTBAR ? null : "Only with the Hotbar anchor";
		}
		if (setting == Armor.PUSH_BOSSBARS) {
			return anchor == Armor.TOP_CENTER ? null : "Only with the Top Center anchor";
		}
		if (setting == Armor.PUSH_EFFECTS) {
			return anchor == Armor.TOP && module.option(Armor.SIDE) == 1 ? null : "Only with the Top anchor on the right";
		}
		if (setting == Armor.PUSH_SUBTITLES) {
			return anchor == Armor.BOTTOM && module.option(Armor.SIDE) == 1 ? null : "Only with the Bottom anchor on the right";
		}
		if (setting == Armor.BOB) {
			return module.bool(Armor.WARNING) ? null : "Turn on Show Warning first";
		}
		if (setting == Armor.TEXT_POSITION || setting == Armor.TEXT_SCALE || setting == Armor.TEXT_BOLD || setting == Armor.TEXT_SHADOW
			|| setting == Armor.TEXT_COLOR_MODE) {
			return module.option(Armor.DURABILITY) == 0 ? "Only with Numeric or Percentage durability" : null;
		}
		if (setting == Armor.TEXT_COLOR) {
			if (module.option(Armor.DURABILITY) == 0) {
				return "Only with Numeric or Percentage durability";
			}
			return module.option(Armor.TEXT_COLOR_MODE) == 1 ? null : "Only in the Custom text color mode";
		}
		return null;
	}

	/**
	 * Whether {@code module}'s own settings place it now rather than its HUD placement (Armor against the hotbar or a screen
	 * edge, as uku's Armor HUD does): the HUD editor still shows it where it is drawn, and moving or sizing it there hands it to
	 * its placement ({@link #free}).
	 */
	public static boolean placesItself(ModuleState module) {
		return ModuleId.ARMOR.equals(module.id()) && Armor.anchored(module);
	}

	/**
	 * The HUD editor moves or sizes {@code module}: one that placed itself ({@link #placesItself}) takes its HUD placement from
	 * now on (Armor becomes Free). Returns whether this changed anything.
	 */
	public static boolean free(ModuleState module) {
		return placesItself(module) && module.set(Armor.ANCHOR, Armor.FREE);
	}

	/**
	 * Effects: the Tiles layout's rows wait out the List layout and the List's wait out the Tiles; within the Tiles, names only
	 * fit beside a column and the custom colours only apply to the Custom background.
	 */
	private static @Nullable String effectsReason(ModuleState module, SettingDescriptor<?> setting) {
		boolean tiles = module.option(Effects.VIEW) == Effects.TILES;
		if (setting == Effects.TILE_DIRECTION || setting == Effects.TILE_SPLIT || setting == Effects.TILE_GAP || setting == Effects.TILE_SHADOW
			|| setting == Effects.TILE_BACKGROUND || setting == Effects.TILE_LEVEL) {
			return tiles ? null : "Only in the Tiles layout";
		}
		if (setting == Effects.TILE_TIME) {
			return !tiles ? "Only in the Tiles layout" : module.bool(Effects.SHOW_DURATION) ? null : "Only when Time left is on";
		}
		if (setting == Effects.TILE_COLOR || setting == Effects.TILE_OPACITY || setting == Effects.TILE_BORDER
			|| setting == Effects.TILE_HARMFUL_BORDER) {
			return !tiles ? "Only in the Tiles layout" : module.option(Effects.TILE_BACKGROUND) == 1 ? null : "Only with the Custom tile background";
		}
		if (setting == Effects.STYLE || setting == Effects.LAYOUT || setting == Effects.TEXT_SIDE || setting == Effects.TWO_LINES
			|| setting == Effects.SHOW_ICONS || setting == Effects.SPACING || setting == Effects.SHORTHAND) {
			return tiles ? "Only in the List layout" : null;
		}
		if (setting == Effects.SHOW_NAMES || setting == Effects.SHOW_LEVEL || setting == Effects.EFFECT_COLORS) {
			return tiles && module.option(Effects.TILE_DIRECTION) == 0 ? "With tiles, only in the Column direction" : null;
		}
		if (setting == Effects.HIDE_VANILLA_EFFECTS) {
			return tiles ? "The tiles take the place of Minecraft's icons" : null;
		}
		return null;
	}

	/** Saturation: the flashing saturation gain shows (AppleSkin draws it with the food gain and the saturation overlay). */
	private static boolean gainShown(ModuleState module) {
		return module.bool(HudMods.Saturation.FOOD_GAIN) && module.bool(HudMods.Saturation.OVERLAY)
			&& module.bool(HudMods.Saturation.SATURATION_GAIN);
	}

	/** Nametags: the hurt tint's, the background shape's and the chat bubbles' details wait for their switches. */
	private static @Nullable String nametagsReason(ModuleState module, SettingDescriptor<?> setting) {
		if (setting == PlayerMods.TierTagger.MODE || setting == PlayerMods.TierTagger.SUB_MODE) {
			// Each tier list has its own gamemodes: only the chosen list's row shows.
			boolean sub = module.option(PlayerMods.TierTagger.LIST) == 1;
			return (setting == PlayerMods.TierTagger.SUB_MODE) == sub ? null : "Another tier list's gamemodes";
		}
		if (setting == PlayerMods.TierTagger.RETIRED_COLOR) {
			return module.bool(PlayerMods.TierTagger.RETIRED) ? null : "Turn on Show retired tiers first";
		}
		if (!com.flintclient.client.compat.McCompat.SHAPED_NAMETAGS && (setting == VisualMods.Nametags.ROUNDED || setting == VisualMods.Nametags.BORDER
			|| setting == VisualMods.Nametags.BORDER_PLAYERS || setting == VisualMods.Nametags.BORDER_COLOR || setting == VisualMods.Nametags.BORDER_CHROMA
			|| setting == VisualMods.Nametags.BUBBLE_BORDER || setting == VisualMods.Nametags.BUBBLE_BORDER_COLOR
			|| setting == VisualMods.Nametags.BUBBLE_BORDER_OPACITY || setting == VisualMods.Nametags.BUBBLE_BORDER_THICKNESS)) {
			// Rows that would do nothing on this Minecraft wait out of sight.
			return "Needs Minecraft 26.3";
		}
		if (setting == VisualMods.Nametags.HURT_COLOR || setting == VisualMods.Nametags.HURT_STRENGTH) {
			return module.bool(VisualMods.Nametags.HURT) ? null : "Turn on Hurt nametag first";
		}
		if (setting == VisualMods.Nametags.ROUNDED || setting == VisualMods.Nametags.BORDER) {
			return module.bool(VisualMods.Nametags.BACKGROUND) ? null : "Turn on Background first";
		}
		if (setting == VisualMods.Nametags.BORDER_PLAYERS || setting == VisualMods.Nametags.BORDER_COLOR || setting == VisualMods.Nametags.BORDER_CHROMA) {
			if (!module.bool(VisualMods.Nametags.BACKGROUND) || !module.bool(VisualMods.Nametags.BORDER)) {
				return "Turn on Border first";
			}
			return setting == VisualMods.Nametags.BORDER_COLOR && module.bool(VisualMods.Nametags.BORDER_CHROMA) ? "Chroma border is on" : null;
		}
		if (setting == VisualMods.Nametags.BUBBLE_CHARS || setting == VisualMods.Nametags.BUBBLE_MAX || setting == VisualMods.Nametags.BUBBLE_FADE_IN
			|| setting == VisualMods.Nametags.BUBBLE_DELAY || setting == VisualMods.Nametags.BUBBLE_FADE_OUT || setting == VisualMods.Nametags.BUBBLE_OBSCURED
			|| setting == VisualMods.Nametags.BUBBLE_TEXT || setting == VisualMods.Nametags.BUBBLE_BACKGROUND) {
			return module.bool(VisualMods.Nametags.BUBBLES) ? null : "Turn on Chat bubbles first";
		}
		if (setting == VisualMods.Nametags.BUBBLE_BACKGROUND_COLOR || setting == VisualMods.Nametags.BUBBLE_BACKGROUND_OPACITY
			|| setting == VisualMods.Nametags.BUBBLE_BORDER) {
			return module.bool(VisualMods.Nametags.BUBBLES) && module.bool(VisualMods.Nametags.BUBBLE_BACKGROUND) ? null : "Turn on Bubble background first";
		}
		if (setting == VisualMods.Nametags.BUBBLE_BORDER_COLOR || setting == VisualMods.Nametags.BUBBLE_BORDER_OPACITY
			|| setting == VisualMods.Nametags.BUBBLE_BORDER_THICKNESS) {
			return module.bool(VisualMods.Nametags.BUBBLES) && module.bool(VisualMods.Nametags.BUBBLE_BACKGROUND) && module.bool(VisualMods.Nametags.BUBBLE_BORDER)
				? null : "Turn on Bubble border first";
		}
		return null;
	}

	private static @Nullable String chatInactiveReason(ModuleState chat, SettingDescriptor<?> setting) {
		if (setting == UtilityMods.Chat.SEARCH_KEY) {
			return chat.bool(UtilityMods.Chat.SEARCH) ? null : "Turn on Chat search first";
		}
		if (setting == UtilityMods.Chat.SMOOTH_TIME) {
			return chat.bool(UtilityMods.Chat.SMOOTH) ? null : "Turn on Animate new messages first";
		}
		if (setting == UtilityMods.Chat.INPUT_TIME || setting == UtilityMods.Chat.INPUT_OFFSET || setting == UtilityMods.Chat.INPUT_EASING) {
			return chat.bool(UtilityMods.Chat.SMOOTH_INPUT) ? null : "Turn on Smooth input bar first";
		}
		if (setting == UtilityMods.Chat.TAB_OWN || setting == UtilityMods.Chat.TAB_KEY || setting == UtilityMods.Chat.TAB_NAME) {
			return chat.bool(UtilityMods.Chat.TABS) ? null : "Turn on Chat tabs first";
		}
		if (setting == UtilityMods.Chat.TAB_WORDS) {
			return !chat.bool(UtilityMods.Chat.TABS) ? "Turn on Chat tabs first"
				: chat.text(UtilityMods.Chat.TAB_NAME).isBlank() ? "Name your tab first" : null;
		}
		if (setting == UtilityMods.Chat.CALC_PLACES || setting == UtilityMods.Chat.CALC_INPUT || setting == UtilityMods.Chat.CALC_OUTPUT
			|| setting == UtilityMods.Chat.CALC_ERROR) {
			return chat.bool(UtilityMods.Chat.CALC) ? null : "Turn on Chat calculator first";
		}
		String heads = com.flintclient.client.control.model.mods.ParityReasons.chatHeads(chat, setting);
		if (heads != null) {
			return heads;
		}
		if (setting == UtilityMods.Chat.TIME_FORMAT || setting == UtilityMods.Chat.TIME_COLOR) {
			return chat.bool(UtilityMods.Chat.TIMESTAMPS) ? null : "Turn on timestamps first";
		}
		if (setting == UtilityMods.Chat.CONSECUTIVE) {
			return chat.bool(UtilityMods.Chat.STACK) ? null : "Turn on Stack repeated messages first";
		}
		if (setting == UtilityMods.Chat.MENTION_COLOR) {
			return chat.bool(UtilityMods.Chat.MENTIONS) ? null : "Turn on Highlight mentions first";
		}
		return null;
	}

	/** Inventory: slot binding's, the slot decorations' and the animations' details wait for their switches. */
	private static @Nullable String inventoryReason(ModuleState module, SettingDescriptor<?> setting) {
		if (setting == UtilityMods.Inventory.HIGHLIGHT || setting == UtilityMods.Inventory.SEARCH_LORE || setting == UtilityMods.Inventory.SEARCH_KEY) {
			return module.bool(UtilityMods.Inventory.SEARCH) ? null : "Turn on Inventory search bar first";
		}
		if (setting == UtilityMods.Inventory.BIND_KEY || setting == UtilityMods.Inventory.BIND_COLOR || setting == UtilityMods.Inventory.BOUND) {
			return module.bool(UtilityMods.Inventory.BINDING) ? null : "Turn on Slot binding first";
		}
		if (setting == UtilityMods.Inventory.ENCHANTS_MAX || setting == UtilityMods.Inventory.ENCHANTS_BOOKS || setting == UtilityMods.Inventory.ENCHANTS_COLOR
			|| setting == UtilityMods.Inventory.ENCHANTS_TOP_COLOR || setting == UtilityMods.Inventory.ENCHANTS_HOTBAR) {
			return module.bool(UtilityMods.Inventory.ENCHANTS) ? null : "Turn on Enchantment summaries first";
		}
		if (setting == UtilityMods.Inventory.ANIMATION_MS || setting == UtilityMods.Inventory.EASING || setting == UtilityMods.Inventory.MOVE
			|| setting == UtilityMods.Inventory.SCALE) {
			return module.bool(UtilityMods.Inventory.ANIMATIONS) ? null : "Turn on Open/close animations first";
		}
		if (setting == UtilityMods.Inventory.DIRECTION) {
			return module.bool(UtilityMods.Inventory.ANIMATIONS) && module.bool(UtilityMods.Inventory.MOVE) ? null : "Turn on Move screen in/out first";
		}
		if (setting == UtilityMods.Inventory.RARITY_THICKNESS || setting == UtilityMods.Inventory.RARITY_HOTBAR) {
			return module.bool(UtilityMods.Inventory.RARITY) ? null : "Turn on Rarity border first";
		}
		if (setting == UtilityMods.Inventory.DURABILITY_SCALE || setting == UtilityMods.Inventory.DURABILITY_HOTBAR) {
			return module.bool(UtilityMods.Inventory.DURABILITY) ? null : "Turn on Durability text first";
		}
		if (setting == UtilityMods.Inventory.SELECTED_COLOR) {
			return module.bool(UtilityMods.Inventory.SELECTED_FRAME) ? null : "Turn on Selected slot frame first";
		}
		return null;
	}
}
