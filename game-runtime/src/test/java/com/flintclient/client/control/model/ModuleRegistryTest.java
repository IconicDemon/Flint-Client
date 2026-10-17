package com.flintclient.client.control.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.hud.Anchor;
import com.flintclient.client.control.hud.HudPlacement;
import com.flintclient.client.control.hud.HudRect;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModuleRegistryTest {
	/** Default module sizes in GUI units (stand-ins until the renderers measure them). */
	static final Map<String, float[]> SIZES = Map.of(
		ModuleId.FPS, new float[] {40, 11},
		ModuleId.PING, new float[] {44, 11},
		ModuleId.CPS, new float[] {52, 11},
		ModuleId.COORDINATES, new float[] {60, 31},
		ModuleId.DIRECTION, new float[] {64, 11},
		ModuleId.KEYSTROKES, new float[] {52, 52},
		ModuleId.ARMOR, new float[] {40, 72},
		ModuleId.EFFECTS, new float[] {92, 40});
	/** GUI-scaled screen sizes from Minecraft's smallest to a large monitor. */
	static final float[][] SCREENS = {{320, 240}, {427, 240}, {480, 270}, {640, 360}, {683, 384}, {960, 540}, {1920, 1080}};

	@Test
	void theEightOriginalModulesComeFirstInAFixedOrder() {
		List<String> ids = new ArrayList<>();
		for (ModuleDescriptor module : ModuleRegistry.all()) {
			ids.add(module.id());
		}
		assertEquals(List.of("fps", "ping", "cps", "keystrokes", "coordinates", "direction", "armor", "effects"), ids.subList(0, 8));
		assertEquals(ModuleId.CORE, ids.subList(0, 8));
		assertEquals(ModuleId.ALL, ids);
		assertEquals(64, ModuleRegistry.size(), "one module per card of the menu");
		assertEquals(ModuleRegistry.core(), ModuleRegistry.all().subList(0, 8));
		assertThrows(UnsupportedOperationException.class, () -> ModuleRegistry.all().add(ModuleRegistry.all().get(0)));
	}

	@Test
	void lookupById() {
		assertSame(ModuleRegistry.all().get(4), ModuleRegistry.get("coordinates"));
		assertEquals(4, ModuleRegistry.indexOf("coordinates"));
		assertNull(ModuleRegistry.find("minimap"));
		assertEquals(-1, ModuleRegistry.indexOf("minimap"));
		assertThrows(IllegalArgumentException.class, () -> ModuleRegistry.get("minimap"));
	}

	@Test
	void categoriesIconsAndNames() {
		assertModule(ModuleId.FPS, "FPS", Category.PERFORMANCE, IconId.GAUGE);
		assertModule(ModuleId.PING, "Ping", Category.PERFORMANCE, IconId.SIGNAL);
		assertModule(ModuleId.CPS, "CPS", Category.COMBAT, IconId.MOUSE);
		assertModule(ModuleId.KEYSTROKES, "Keystrokes", Category.COMBAT, IconId.KEYS);
		assertModule(ModuleId.COORDINATES, "Coordinates", Category.WORLD, IconId.PIN);
		assertModule(ModuleId.DIRECTION, "Direction", Category.WORLD, IconId.COMPASS);
		assertModule(ModuleId.ARMOR, "Armor", Category.COMBAT, IconId.SHIELD);
		assertModule(ModuleId.EFFECTS, "Effects", Category.COMBAT, IconId.FLASK);
		assertEquals("Performance", Category.PERFORMANCE.label());
		assertEquals("Combat", Category.COMBAT.label());
		assertEquals("World", Category.WORLD.label());
	}

	private static void assertModule(String id, String name, Category category, IconId icon) {
		ModuleDescriptor module = ModuleRegistry.get(id);
		assertEquals(name, module.name());
		assertEquals(category, module.category());
		assertEquals(icon, module.icon());
		assertFalse(module.description().isBlank());
		assertTrue(module.keywords().size() >= 3 && module.keywords().size() <= 6, id + " has 3-6 keywords");
		assertFalse(module.sections().isEmpty());
	}

	@Test
	void everyModuleHasExactlyItsSettings() {
		assertSettings(ModuleId.FPS, "displayMode", "background", "backgroundType", "width", "height", "border", "textColor", "textShadow",
			"reversed", "align", "backgroundColor", "backgroundOpacity", "borderColor", "borderThickness", "cornerRadius", "showLabel",
			"showFrameTime", "showLow", "interval", "window", "decimals");
		assertSettings(ModuleId.PING, "labelPosition", "showBars", "colorByLatency");
		assertSettings(ModuleId.CPS, "buttons", "format", "labelPosition");
		assertSettings(ModuleId.KEYSTROKES, "layout", "showCps", "labels", "keySize", "spacing", "corners", "pressedStyle", "pressedColor",
			"fadeMs");
		assertSettings(ModuleId.COORDINATES, "layout", "decimals", "labels", "showDirection", "otherDimension");
		assertSettings(ModuleId.DIRECTION, "format", "showAxis", "showDegrees");
		assertSettings(ModuleId.ARMOR, "layout", "durability", "showHands", "hideEmpty", "lowWarning", "lowThreshold");
		assertSettings(ModuleId.EFFECTS, "layout", "showIcons", "showNames", "showLevel", "showDuration", "durationFormat", "spacing", "sort",
			"blinkEnding", "hideVanillaEffects");
	}

	@Test
	void sectionsStartWithDisplayAndTuckDetailsUnderAdvanced() {
		assertSections(ModuleId.FPS, "Style", "Background and border+", "Advanced+");
		assertSections(ModuleId.PING, "Display", "Advanced+");
		assertSections(ModuleId.CPS, "Display");
		assertSections(ModuleId.KEYSTROKES, "Display", "Style", "Advanced+");
		assertSections(ModuleId.COORDINATES, "Display", "Advanced+");
		assertSections(ModuleId.DIRECTION, "Display", "Advanced+");
		assertSections(ModuleId.ARMOR, "Display", "Advanced+");
		assertSections(ModuleId.EFFECTS, "Display", "Advanced+");
		assertTrue(SettingSection.advanced("A", ModuleSettings.Fps.SHOW_LOW).advanced());
		assertFalse(SettingSection.of("A", ModuleSettings.Fps.SHOW_LOW).advanced());
	}

	/** Section titles in order; a trailing "+" marks an advanced (collapsed) section. */
	private static void assertSections(String id, String... titles) {
		List<String> actual = new ArrayList<>();
		for (SettingSection section : ModuleRegistry.get(id).sections()) {
			actual.add(section.title() + (section.advanced() ? "+" : ""));
		}
		assertEquals(List.of(titles), actual, id);
	}

	private static void assertSettings(String id, String... settingIds) {
		ModuleDescriptor module = ModuleRegistry.get(id);
		List<String> actual = new ArrayList<>();
		int flattened = 0;
		for (SettingSection section : module.sections()) {
			assertFalse(section.title().isBlank());
			assertFalse(section.settings().isEmpty(), "no empty sections");
			flattened += section.settings().size();
		}
		for (SettingDescriptor<?> setting : module.settings()) {
			actual.add(setting.id());
			assertSame(setting, module.setting(setting.id()));
			assertEquals(actual.size() - 1, module.indexOf(setting));
		}
		assertEquals(List.of(settingIds), actual);
		assertEquals(flattened, module.settings().size());
		assertNull(module.setting("nope"));
	}

	@Test
	void defaultsAreTheCleanFormAndExtrasAreOptIn() {
		assertEquals(true, ModuleSettings.Fps.SHOW_LABEL.defaultValue());
		assertEquals(false, ModuleSettings.Fps.REVERSED.defaultValue());
		assertEquals(false, ModuleSettings.Fps.SHOW_FRAME_TIME.defaultValue());
		assertEquals(false, ModuleSettings.Fps.SHOW_LOW.defaultValue());
		assertEquals(FpsInterval.HALF_SECOND, ModuleSettings.Fps.INTERVAL.defaultValue());
		assertEquals(FpsWindow.HALF_SECOND, ModuleSettings.Fps.WINDOW.defaultValue());
		assertEquals(FpsDecimals.NONE, ModuleSettings.Fps.DECIMALS.defaultValue());
		assertEquals(LabelPosition.AFTER, ModuleSettings.Ping.LABEL_POSITION.defaultValue());
		assertEquals(false, ModuleSettings.Ping.SHOW_BARS.defaultValue(), "bars are opt-in now");
		assertEquals(false, ModuleSettings.Ping.COLOR_BY_LATENCY.defaultValue());
		assertEquals(CpsButtons.BOTH, ModuleSettings.Cps.BUTTONS.defaultValue());
		assertEquals(CpsFormat.DOT, ModuleSettings.Cps.FORMAT.defaultValue());
		assertEquals(LabelPosition.AFTER, ModuleSettings.Cps.LABEL_POSITION.defaultValue());
		assertEquals(KeystrokesLayout.KEYS_MOUSE_JUMP, ModuleSettings.Keystrokes.LAYOUT.defaultValue());
		assertEquals(false, ModuleSettings.Keystrokes.SHOW_CPS.defaultValue());
		assertEquals(KeyLabelStyle.BOUND, ModuleSettings.Keystrokes.LABELS.defaultValue());
		assertRange(ModuleSettings.Keystrokes.KEY_SIZE, 16, 12, 24, 1);
		assertRange(ModuleSettings.Keystrokes.SPACING, 2, 0, 6, 1);
		assertRange(ModuleSettings.Keystrokes.CORNERS, 3, 0, 6, 1);
		assertEquals(PressedStyle.FILL, ModuleSettings.Keystrokes.PRESSED_STYLE.defaultValue());
		assertEquals(HudColor.DEFAULT, ModuleSettings.Keystrokes.PRESSED_COLOR.defaultValue());
		assertRange(ModuleSettings.Keystrokes.FADE_MS, 60, 0, 120, 60);
		assertEquals("ms", ModuleSettings.Keystrokes.FADE_MS.unit());
		assertEquals(CoordinatesLayout.VERTICAL, ModuleSettings.Coordinates.LAYOUT.defaultValue());
		assertEquals(Decimals.NONE, ModuleSettings.Coordinates.DECIMALS.defaultValue());
		assertEquals(List.of("0", "0.0", "0.00"), labels(Decimals.values()));
		assertEquals(2, Decimals.TWO.places());
		assertEquals(true, ModuleSettings.Coordinates.LABELS.defaultValue());
		assertEquals(false, ModuleSettings.Coordinates.SHOW_DIRECTION.defaultValue());
		assertEquals(false, ModuleSettings.Coordinates.OTHER_DIMENSION.defaultValue());
		assertEquals("Nether/Overworld equivalent", ModuleSettings.Coordinates.OTHER_DIMENSION.label());
		assertEquals(DirectionFormat.NAME, ModuleSettings.Direction.FORMAT.defaultValue());
		assertEquals(true, ModuleSettings.Direction.SHOW_AXIS.defaultValue());
		assertEquals(true, ModuleSettings.Direction.SHOW_DEGREES.defaultValue());
		assertEquals(Orientation.VERTICAL, ModuleSettings.Armor.LAYOUT.defaultValue());
		assertEquals(DurabilityFormat.PERCENT, ModuleSettings.Armor.DURABILITY.defaultValue());
		assertEquals(List.of("Percent", "Number", "Bar", "Hidden"), labels(DurabilityFormat.values()));
		assertEquals(false, ModuleSettings.Armor.SHOW_HANDS.defaultValue());
		assertEquals(true, ModuleSettings.Armor.HIDE_EMPTY.defaultValue());
		assertEquals(true, ModuleSettings.Armor.LOW_WARNING.defaultValue());
		assertRange(ModuleSettings.Armor.LOW_THRESHOLD, 10, 5, 50, 5);
		assertEquals("%", ModuleSettings.Armor.LOW_THRESHOLD.unit());
		assertEquals(Orientation.VERTICAL, ModuleSettings.Effects.LAYOUT.defaultValue());
		assertEquals(true, ModuleSettings.Effects.SHOW_ICONS.defaultValue());
		assertEquals(true, ModuleSettings.Effects.SHOW_NAMES.defaultValue());
		assertEquals(true, ModuleSettings.Effects.SHOW_LEVEL.defaultValue());
		assertEquals(true, ModuleSettings.Effects.SHOW_DURATION.defaultValue());
		assertEquals(DurationFormat.CLOCK, ModuleSettings.Effects.DURATION_FORMAT.defaultValue());
		assertRange(ModuleSettings.Effects.SPACING, 2, 0, 6, 1);
		assertEquals(EffectSort.TIME_LEFT, ModuleSettings.Effects.SORT.defaultValue());
		assertEquals(true, ModuleSettings.Effects.BLINK_ENDING.defaultValue());
		assertEquals(true, ModuleSettings.Effects.HIDE_VANILLA_EFFECTS.defaultValue());
		assertEquals("Hide Minecraft's effect icons", ModuleSettings.Effects.HIDE_VANILLA_EFFECTS.label());
		assertEquals(HudColor.DEFAULT, Appearance.ACCENT_COLOR.defaultValue());
		assertNull(Appearance.DEFAULTS.accentColor(), "the accent follows the style until one is chosen");
		assertEquals(HudColor.DEFAULT, Appearance.effective(Appearance.INHERIT, Appearance.DEFAULTS).accentColor());
	}

	@Test
	void choicesHaveShortLabels() {
		assertEquals(List.of("After", "Before", "Hidden"), labels(LabelPosition.values()));
		assertEquals(List.of("0.25 s", "0.5 s", "1 s"), labels(FpsInterval.values()));
		assertEquals(List.of("0.5 s", "1 s", "2 s"), labels(FpsWindow.values()));
		assertEquals(List.of(1, 2, 4), List.of(FpsInterval.QUARTER_SECOND.refreshes(), FpsInterval.HALF_SECOND.refreshes(),
			FpsInterval.ONE_SECOND.refreshes()));
		assertEquals(List.of(2, 4, 8), List.of(FpsWindow.HALF_SECOND.refreshes(), FpsWindow.ONE_SECOND.refreshes(),
			FpsWindow.TWO_SECONDS.refreshes()));
		assertEquals(List.of("0", "0.0"), labels(FpsDecimals.values()));
		assertEquals(List.of("Left", "Right", "Both", "Combined"), labels(CpsButtons.values()));
		assertEquals(List.of("8 · 3", "8 | 3", "L 8  R 3"), labels(CpsFormat.values()));
		assertEquals(List.of("Keys", "Keys + mouse", "Keys, mouse and jump"), labels(KeystrokesLayout.values()));
		assertEquals(List.of("Your keys", "Arrows"), labels(KeyLabelStyle.values()));
		assertEquals(List.of("Fill", "Outline", "Invert"), labels(PressedStyle.values()));
		assertEquals(List.of("Stacked", "Single line"), labels(CoordinatesLayout.values()));
		assertEquals(List.of("Compact", "Name", "Detailed"), labels(DirectionFormat.values()));
		assertEquals(List.of("1:30", "90s"), labels(DurationFormat.values()));
		assertEquals(List.of("Time left", "Name"), labels(EffectSort.values()));
		for (ModuleDescriptor module : ModuleRegistry.all()) {
			for (SettingDescriptor<?> setting : module.settings()) {
				assertTrue(setting.label().length() <= 30, module.id() + "." + setting.id() + " has a short label");
				if (setting instanceof ChoiceSetting<?> choice) {
					for (Object option : choice.options()) {
						String label = ((Labelled) option).label();
						assertFalse(label.isBlank());
						assertTrue(label.length() <= 22, label);
					}
				}
			}
		}
		assertEquals(KeystrokesLayout.KEYS, KeystrokesLayout.of(false, true), "jump without mouse buttons: keys only");
		assertEquals(KeystrokesLayout.KEYS_MOUSE, KeystrokesLayout.of(true, false));
		assertEquals(KeystrokesLayout.KEYS_MOUSE_JUMP, KeystrokesLayout.of(true, true));
	}

	@Test
	void theOneLowSettingExplainsItsWaitUnderItsLabel() {
		assertTrue(ModuleSettings.Fps.SHOW_LOW.explain());
		assertTrue(ModuleSettings.Fps.SHOW_LOW.description().contains("5 seconds and 300 frames"));
		assertTrue(ModuleSettings.Fps.SHOW_LOW.description().contains("—"), "it says what shows until then");
		assertFalse(ModuleSettings.Fps.SHOW_FRAME_TIME.explain(), "a tooltip is enough");
		assertFalse(new BoolSetting("x", "X", true).explain());
	}

	@Test
	void labelControlsReplaceShowLabelWhereTheyExist() {
		assertNull(ModuleSettings.labelPosition(ModuleRegistry.get(ModuleId.FPS)), "FPS has Reversed and Show label instead");
		assertSame(ModuleSettings.Fps.SHOW_LABEL, ModuleSettings.labelSetting(ModuleRegistry.get(ModuleId.FPS)));
		assertSame(ModuleSettings.Ping.LABEL_POSITION, ModuleSettings.labelPosition(ModuleRegistry.get(ModuleId.PING)));
		assertSame(ModuleSettings.Cps.LABEL_POSITION, ModuleSettings.labelPosition(ModuleRegistry.get(ModuleId.CPS)));
		assertNull(ModuleSettings.labelPosition(ModuleRegistry.get(ModuleId.COORDINATES)));
		assertSame(ModuleSettings.Coordinates.LABELS, ModuleSettings.labelSetting(ModuleRegistry.get(ModuleId.COORDINATES)));
		for (String id : List.of(ModuleId.KEYSTROKES, ModuleId.DIRECTION, ModuleId.ARMOR, ModuleId.EFFECTS)) {
			assertNull(ModuleSettings.labelSetting(ModuleRegistry.get(id)), id + " keeps the shared Show label");
		}
	}

	@Test
	void settingsThatAnotherSettingMakesMootSayWhy() {
		Layout layout = new Layout("x", "X");
		ModuleState cps = layout.module(ModuleId.CPS);
		assertNull(ModuleSettings.inactiveReason(cps, ModuleSettings.Cps.FORMAT));
		cps.set(ModuleSettings.Cps.BUTTONS, CpsButtons.COMBINED);
		assertEquals("Only when Buttons is Both", ModuleSettings.inactiveReason(cps, ModuleSettings.Cps.FORMAT));
		assertNull(ModuleSettings.inactiveReason(cps, ModuleSettings.Cps.LABEL_POSITION));
		ModuleState direction = layout.module(ModuleId.DIRECTION);
		assertEquals("Only in the Detailed format", ModuleSettings.inactiveReason(direction, ModuleSettings.Direction.SHOW_AXIS));
		direction.set(ModuleSettings.Direction.FORMAT, DirectionFormat.DETAILED);
		assertNull(ModuleSettings.inactiveReason(direction, ModuleSettings.Direction.SHOW_DEGREES));
		ModuleState keys = layout.module(ModuleId.KEYSTROKES);
		assertNull(ModuleSettings.inactiveReason(keys, ModuleSettings.Keystrokes.SHOW_CPS));
		keys.set(ModuleSettings.Keystrokes.LAYOUT, KeystrokesLayout.KEYS);
		assertEquals("Needs the mouse buttons in the layout", ModuleSettings.inactiveReason(keys, ModuleSettings.Keystrokes.SHOW_CPS));
		keys.set(ModuleSettings.Keystrokes.PRESSED_STYLE, PressedStyle.INVERT);
		assertEquals("Invert uses the text color", ModuleSettings.inactiveReason(keys, ModuleSettings.Keystrokes.PRESSED_COLOR));
		ModuleState armor = layout.module(ModuleId.ARMOR);
		assertNull(ModuleSettings.inactiveReason(armor, ModuleSettings.Armor.LOW_THRESHOLD));
		armor.set(ModuleSettings.Armor.LOW_WARNING, false);
		assertEquals("Turn on the low durability warning first", ModuleSettings.inactiveReason(armor, ModuleSettings.Armor.LOW_THRESHOLD));
		ModuleState effects = layout.module(ModuleId.EFFECTS);
		effects.set(ModuleSettings.Effects.SHOW_DURATION, false);
		assertEquals("Only when Time left is on", ModuleSettings.inactiveReason(effects, ModuleSettings.Effects.DURATION_FORMAT));
		assertNull(ModuleSettings.inactiveReason(effects, ModuleSettings.Cps.FORMAT), "another module's setting: no answer");
	}

	private static void assertRange(IntSetting setting, int defaultValue, int min, int max, int step) {
		assertEquals(defaultValue, setting.defaultValue(), setting.id());
		assertEquals(min, setting.min(), setting.id());
		assertEquals(max, setting.max(), setting.id());
		assertEquals(step, setting.step(), setting.id());
	}

	private static List<String> labels(Labelled[] options) {
		List<String> out = new ArrayList<>();
		for (Labelled option : options) {
			out.add(option.label());
		}
		return out;
	}

	@Test
	void pingExplainsWhereItsNumberComesFrom() {
		assertNotNull(ModuleRegistry.get(ModuleId.PING).note());
		assertNull(ModuleRegistry.get(ModuleId.FPS).note());
	}

	@Test
	void availabilityFollowsRealState() {
		ModuleDescriptor ping = ModuleRegistry.get(ModuleId.PING);
		assertEquals(Availability.NETWORK_CORE_OFF, ping.availability(new ControlContext(true, true, true, false)));
		assertEquals(Availability.NETWORK_CORE_OFF, ping.availability(new ControlContext(true, true, false, false)));
		assertEquals(Availability.SERVERS_ONLY, ping.availability(new ControlContext(true, false, false, true)));
		assertEquals(Availability.SERVERS_ONLY, ping.availability(new ControlContext(true, false, false, false)),
			"in singleplayer, turning Network Core on wouldn't help, so don't suggest it");
		assertEquals(Availability.SERVERS_ONLY, ping.availability(ControlContext.TITLE_SCREEN));
		assertEquals(Availability.WAITING_FOR_SERVER, ping.availability(new ControlContext(true, true, false, true)));
		assertEquals(Availability.AVAILABLE, ping.availability(new ControlContext(true, true, true, true)));
		for (String id : List.of(ModuleId.COORDINATES, ModuleId.DIRECTION, ModuleId.ARMOR, ModuleId.EFFECTS)) {
			assertEquals(Availability.NEEDS_WORLD, ModuleRegistry.get(id).availability(ControlContext.TITLE_SCREEN), id);
			assertEquals(Availability.AVAILABLE, ModuleRegistry.get(id).availability(new ControlContext(true, false, false, false)), id);
		}
		for (String id : List.of(ModuleId.FPS, ModuleId.CPS, ModuleId.KEYSTROKES)) {
			assertEquals(Availability.AVAILABLE, ModuleRegistry.get(id).availability(new ControlContext(false, false, false, false)), id);
		}
		assertEquals("Flint Network Core is off", Availability.NETWORK_CORE_OFF.reason());
		assertEquals("Shows on multiplayer servers", Availability.SERVERS_ONLY.reason());
		assertEquals("Shows in a world", Availability.NEEDS_WORLD.reason());
		assertEquals("SERVERS ONLY", Availability.SERVERS_ONLY.status());
		assertEquals("NEEDS A WORLD", Availability.NEEDS_WORLD.status());
		assertEquals("NETWORK CORE OFF", Availability.NETWORK_CORE_OFF.status());
		assertNull(Availability.AVAILABLE.reason());
		assertNull(Availability.AVAILABLE.status());
		assertTrue(Availability.AVAILABLE.available());
		for (Availability availability : Availability.values()) {
			if (!availability.available()) {
				String status = availability.status();
				assertNotNull(status);
				assertEquals(status.toUpperCase(java.util.Locale.ROOT), status, "status lines are CAPS");
			}
		}
	}

	@Test
	void defaultPlacementsNeverOverlapAtDefaultSizes() {
		for (float[] screen : SCREENS) {
			List<HudRect> rects = new ArrayList<>();
			for (ModuleDescriptor module : ModuleRegistry.core()) {
				float[] size = SIZES.get(module.id());
				HudRect rect = module.defaultPlacement().toAbsolute(screen[0], screen[1], size[0], size[1]);
				assertTrue(rect.within(screen[0], screen[1]), module.id() + " on " + screen[0] + "x" + screen[1]);
				rects.add(rect);
			}
			assertNoOverlap(rects, screen);
		}
		// The Quick Hub layout: FPS top-left, Ping top-right, Effects on the left, Armor bottom-right.
		assertEquals(HudPlacement.of(Anchor.TOP_LEFT, 4, 4), ModuleRegistry.get(ModuleId.FPS).defaultPlacement());
		assertEquals(HudPlacement.of(Anchor.TOP_RIGHT, 4, 4), ModuleRegistry.get(ModuleId.PING).defaultPlacement());
		assertEquals(HudPlacement.of(Anchor.LEFT, 4, 0), ModuleRegistry.get(ModuleId.EFFECTS).defaultPlacement());
		assertEquals(HudPlacement.of(Anchor.BOTTOM_RIGHT, 4, 4), ModuleRegistry.get(ModuleId.ARMOR).defaultPlacement());
	}

	static void assertNoOverlap(List<HudRect> rects, float[] screen) {
		for (int i = 0; i < rects.size(); i++) {
			for (int j = i + 1; j < rects.size(); j++) {
				assertFalse(rects.get(i).overlaps(rects.get(j)), rects.get(i) + " overlaps " + rects.get(j) + " on " + screen[0] + "x" + screen[1]);
			}
		}
	}

	@Test
	void onlyAModulesOwnSettingConstantsWork() {
		Layout layout = new Layout("x", "X");
		ModuleState armor = layout.module(ModuleId.ARMOR);
		assertEquals(Orientation.VERTICAL, armor.choice(ModuleSettings.Armor.LAYOUT));
		assertEquals(-1, armor.descriptor().indexOf(ModuleSettings.Effects.LAYOUT), "same id and type, another module");
		assertEquals(-1, armor.descriptor().indexOf(ModuleSettings.Coordinates.LAYOUT), "same id, another module");
		assertThrows(IllegalArgumentException.class, () -> armor.choice(ModuleSettings.Coordinates.LAYOUT));
		assertThrows(IllegalArgumentException.class, () -> armor.bool(ModuleSettings.Fps.SHOW_LOW));
	}

	@Test
	void settingIdsAreUniqueWithinAModule() {
		for (ModuleDescriptor module : ModuleRegistry.all()) {
			Set<String> ids = new HashSet<>();
			for (SettingDescriptor<?> setting : module.settings()) {
				assertTrue(ids.add(setting.id()), module.id() + "." + setting.id());
			}
		}
		assertThrows(IllegalArgumentException.class, () -> new ModuleDescriptor("x", "X", "x", List.of(), IconId.GAUGE, Category.WORLD,
			List.of(SettingSection.of("A", ModuleSettings.Fps.SHOW_LOW), SettingSection.of("B", ModuleSettings.Fps.SHOW_LOW)),
			HudPlacement.of(Anchor.TOP_LEFT, 0, 0), ModuleDescriptor.Requirement.NONE, null));
	}
}
