package com.flintclient.client.control.model;

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
		public static final BoxStyle STYLE = new BoxStyle(56, 160, 13, FpsSizing.DYNAMIC, 0xFFFFFF, true);

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

		private Coordinates() {
		}
	}

	public static final class Direction {
		public static final ChoiceSetting<DirectionFormat> FORMAT = new ChoiceSetting<>("format", "Format",
			"Compact: N. Name: North. Detailed: North · −Z · 172°.", DirectionFormat.class, DirectionFormat.NAME);
		public static final BoolSetting SHOW_AXIS = new BoolSetting("showAxis", "Axis",
			"Which way the coordinates grow, like −Z. In the Detailed format.", true);
		public static final BoolSetting SHOW_DEGREES = new BoolSetting("showDegrees", "Degrees", "In the Detailed format", true);

		private Direction() {
		}
	}

	public static final class Armor {
		public static final ChoiceSetting<Orientation> LAYOUT = new ChoiceSetting<>("layout", "Layout", null, Orientation.class,
			Orientation.VERTICAL);
		public static final ChoiceSetting<DurabilityFormat> DURABILITY = new ChoiceSetting<>("durability", "Durability",
			"Bar draws a thin bar of the durability left", DurabilityFormat.class, DurabilityFormat.PERCENT);
		public static final BoolSetting SHOW_HANDS = new BoolSetting("showHands", "Held items", "Main hand and off hand", false);
		public static final BoolSetting HIDE_EMPTY = new BoolSetting("hideEmpty", "Hide empty slots", true);
		public static final BoolSetting LOW_WARNING = new BoolSetting("lowWarning", "Low durability warning",
			"A warning sign and color when an item is nearly broken", true);
		public static final IntSetting LOW_THRESHOLD = new IntSetting("lowThreshold", "Warn below", "Durability left that counts as low",
			10, 5, 50, 5, "%");

		private Armor() {
		}
	}

	public static final class Effects {
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
		if (setting == Direction.SHOW_AXIS || setting == Direction.SHOW_DEGREES) {
			return module.choice(Direction.FORMAT) == DirectionFormat.DETAILED ? null : "Only in the Detailed format";
		}
		if (setting == Keystrokes.SHOW_CPS) {
			return module.choice(Keystrokes.LAYOUT).mouse() ? null : "Needs the mouse buttons in the layout";
		}
		if (setting == Keystrokes.PRESSED_COLOR) {
			return module.choice(Keystrokes.PRESSED_STYLE) == PressedStyle.INVERT ? "Invert uses the text color" : null;
		}
		if (setting == Armor.LOW_THRESHOLD) {
			return module.bool(Armor.LOW_WARNING) ? null : "Turn on the low durability warning first";
		}
		if (setting == Effects.DURATION_FORMAT) {
			return module.bool(Effects.SHOW_DURATION) ? null : "Only when Time left is on";
		}
		return null;
	}
}
