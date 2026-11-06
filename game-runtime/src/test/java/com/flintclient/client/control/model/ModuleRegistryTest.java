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
		// Three tiles with their times, beneficial and harmful lines: (3 × 24 + 2) × (2 × 33 + 2).
		ModuleId.EFFECTS, new float[] {74, 68});
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
		assertEquals(119, ModuleRegistry.size(), "the 63 modules (Flint Music left), 14 added for the Right Shift menu (Time to Toast Control, Hotbar), 10 more (Auto Text to Discord), 7 HUD ones (Block Indicator to Item Info), 8 looks (Shield Status to Dark Mode), 6 tools (Animations to Sound Filters), Wavy Capes, Minimap, World Map, Clumps, No Chat Reports, Sound Physics, Ambient Sounds, Health Indicators, Model Gap Fix, Falling Leaves and Music Player");
		assertEquals(List.of("wavy_capes", "minimap", "world_map", "clumps", "no_chat_reports", "sound_physics", "ambient_sounds", "health_indicators",
			"model_gap_fix", "falling_leaves", "music_player"), ids.subList(ids.size() - 11, ids.size()), "new modules are only ever appended");
		assertEquals(ModuleRegistry.core(), ModuleRegistry.all().subList(0, 8));
		assertThrows(UnsupportedOperationException.class, () -> ModuleRegistry.all().add(ModuleRegistry.all().get(0)));
	}

	@Test
	void lookupById() {
		assertSame(ModuleRegistry.all().get(4), ModuleRegistry.get("coordinates"));
		assertEquals(4, ModuleRegistry.indexOf("coordinates"));
		assertNull(ModuleRegistry.find("teleporter"));
		assertEquals(-1, ModuleRegistry.indexOf("teleporter"));
		assertThrows(IllegalArgumentException.class, () -> ModuleRegistry.get("teleporter"));
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
		assertSettings(ModuleId.FPS, "displayMode", "background", "backgroundType", "width", "height", "horizontalPadding", "verticalPadding", "border", "textColor", "textShadow",
			"reversed", "align", "backgroundColor", "backgroundOpacity", "borderColor", "borderThickness", "cornerRadius", "showLabel",
			"showFrameTime", "showLow", "interval", "window", "decimals");
		assertSettings(ModuleId.PING, "displayMode", "background", "backgroundType", "width", "height", "horizontalPadding", "verticalPadding", "border", "textColor", "textShadow",
			"labelPosition", "showBars", "align", "backgroundColor", "backgroundOpacity", "borderColor", "borderThickness", "cornerRadius", "colorByLatency");
		assertSettings(ModuleId.CPS, "displayMode", "background", "backgroundType", "width", "height", "horizontalPadding", "verticalPadding", "border", "textColor", "textShadow",
			"buttons", "format", "labelPosition", "align", "backgroundColor", "backgroundOpacity", "borderColor", "borderThickness", "cornerRadius");
		assertSettings(ModuleId.KEYSTROKES, "layout", "showCps", "cpsDisplayMode", "cpsReversed", "labels", "clicksDisplayMode", "jumpDisplayMode",
			"keySize", "spacing", "corners", "pressedStyle", "animType", "direction", "pressedColor", "textActiveColor", "fadeMs");
		assertSettings(ModuleId.COORDINATES, "layout", "alignment", "decimals", "labels", "showDirection", "showAngle", "formatColor",
			"showCoordinates", "showXZCoordinates", "showYCoordinate", "showMovementDirection", "showBlockCoordinates", "showChunkCoordinates",
			"formattedChunk", "showBiome", "showChunkCounter", "streamlineOther", "showKeyChunk", "showKeyBiome", "showKeyCounter", "otherDimension",
			"copyCoordinates");
		assertSettings(ModuleId.DIRECTION, "type", "format", "width", "visibleDegrees", "markerSize", "markerColor", "shadow", "displayWaypointDots",
			"showAxis", "showDegrees");
		assertSettings(ModuleId.ARMOR, "displayStyle", "widgetShown", "offhandSlotBehavior", "durabilityDisplay", "reversed", "iconsShown",
			"showSword", "showPickaxe", "showAxe", "showMace", "showTrident", "showBow", "showShield", "mainHandDurability", "offHandDurability",
			"textPosition", "textScale", "textBold", "textShadow", "textColorMode", "textColor", "anchor", "side", "layout", "pushBossbars",
			"pushStatusEffectIcons", "pushSubtitles", "offsetX", "offsetY", "playBreakSound", "warningShown", "warningBobIntensity",
			"minDurabilityValue", "minDurabilityPercentage");
		assertSettings(ModuleId.EFFECTS, "view", "showDuration", "showNames", "showLevel", "useCustomEffectNameColor", "durationColor", "tileDirection",
			"tileSplit", "tileTime", "tileLevel", "tileShadow", "tileGap", "tileBackground", "tileColor", "tileOpacity", "tileBorderColor", "tileHarmfulBorderColor", "displayStyle",
			"layout", "textAlignment", "twoLines", "showIcons", "spacing", "blinkEnding", "durationBlinkSeconds", "effectLossSound", "effectLossSoundVolume",
			"durationFormat", "durationShorthand", "sort", "hideVanillaEffects", "hideInventoryStatus", "barColorMode", "barBackgroundColor", "barBackgroundOpacity",
			"barBeneficialColor", "barBeneficialOpacity", "barHarmfulColor", "barHarmfulOpacity", "barNeutralColor", "barNeutralOpacity",
			"barMaxRemainingDuration", "barMinAmbientAge", "hudBarsEnabled", "hudBarsDirection", "hudBarsRelativeToEnd", "hudBarsThickness",
			"hudBarsCollinearPadding", "hudBarsCollinearOffset", "hudBarsOrthogonalOffset", "inventoryBarsEnabled", "inventoryBarsDirection",
			"inventoryBarsRelativeToEnd", "inventoryBarsThickness", "inventoryBarsCollinearPadding", "inventoryBarsCollinearOffset",
			"inventoryBarsOrthogonalOffset");
	}

	@Test
	void sectionsStartWithDisplayAndTuckDetailsUnderAdvanced() {
		assertSections(ModuleId.FPS, "Style", "Background and border+", "Advanced+", "Mod");
		assertSections(ModuleId.PING, "Style", "Display", "Background and border+", "Advanced+", "Mod");
		assertSections(ModuleId.CPS, "Style", "Display", "Background and border+", "Mod");
		assertSections(ModuleId.KEYSTROKES, "Display", "Style", "Advanced+", "Mod");
		assertSections(ModuleId.COORDINATES, "Display", "Coordinates", "Extras", "Advanced+", "Mod");
		assertSections(ModuleId.DIRECTION, "Display", "Compass", "Advanced+", "Mod");
		assertSections(ModuleId.ARMOR, "Style", "Items", "Text", "Position", "Warning", "Mod");
		assertSections(ModuleId.EFFECTS, "Display", "Tiles", "List", "Ending", "Advanced+", "Status Effect Bars", "HUD Bars", "Inventory Bars", "Mod");
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

	private static void assertSettings(String id, String... ownIds) {
		ModuleDescriptor module = ModuleRegistry.get(id);
		// Every usable module ends with its toggle key and notification.
		String[] settingIds = java.util.Arrays.copyOf(ownIds, ownIds.length + 2);
		settingIds[ownIds.length] = "modKey";
		settingIds[ownIds.length + 1] = "modKeyNotify";
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
		// Armor: uku's Armor HUD's defaults.
		assertEquals(Orientation.HORIZONTAL, ModuleSettings.Armor.LAYOUT.defaultValue());
		assertEquals(List.of("Hotbar", "Rounded Corners", "Rounded", "None"), ModuleSettings.Armor.STYLE.labels());
		assertEquals(0, ModuleSettings.Armor.STYLE.defaultValue());
		assertEquals("not_empty", ModuleSettings.Armor.WIDGET_SHOWN.keys().get(ModuleSettings.Armor.WIDGET_SHOWN.defaultValue()));
		assertEquals("adhere", ModuleSettings.Armor.OFFHAND_SLOT.keys().get(ModuleSettings.Armor.OFFHAND_SLOT.defaultValue()));
		assertEquals("bar", ModuleSettings.Armor.DURABILITY.keys().get(ModuleSettings.Armor.DURABILITY.defaultValue()));
		assertEquals("hotbar", ModuleSettings.Armor.ANCHOR.keys().get(ModuleSettings.Armor.ANCHOR.defaultValue()));
		assertEquals("left", ModuleSettings.Armor.SIDE.keys().get(ModuleSettings.Armor.SIDE.defaultValue()));
		assertEquals(false, ModuleSettings.Armor.REVERSED.defaultValue());
		assertEquals(true, ModuleSettings.Armor.ICONS.defaultValue());
		assertEquals(true, ModuleSettings.Armor.OFFHAND_DURABILITY.defaultValue());
		assertEquals(false, ModuleSettings.Armor.MAINHAND_DURABILITY.defaultValue());
		assertEquals(true, ModuleSettings.Armor.PUSH_BOSSBARS.defaultValue());
		assertEquals(true, ModuleSettings.Armor.BREAK_SOUND.defaultValue());
		assertEquals(true, ModuleSettings.Armor.WARNING.defaultValue());
		assertEquals(3, ModuleSettings.Armor.BOB.defaultValue());
		assertEquals(20, ModuleSettings.Armor.MIN_VALUE.defaultValue());
		assertRange(ModuleSettings.Armor.MIN_PERCENT, 10, 0, 100, 1);
		assertEquals("%", ModuleSettings.Armor.MIN_PERCENT.unit());
		// Flint's additions: the text's own size and weight, and the item slots, all as Armor looked before them.
		assertRange(ModuleSettings.Armor.TEXT_SCALE, 100, 50, 200, 5);
		assertEquals("%", ModuleSettings.Armor.TEXT_SCALE.unit());
		assertEquals(false, ModuleSettings.Armor.TEXT_BOLD.defaultValue());
		for (BoolSetting item : ModuleSettings.Armor.ITEMS) {
			assertEquals(false, item.defaultValue(), item.id());
		}
		assertEquals(List.of("top_center", "top", "vertical_center", "bottom", "hotbar", "free"), ModuleSettings.Armor.ANCHOR.keys(),
			"the saved keys of uku's anchors never change; Free is added last");
		assertEquals(List.of("Percent", "Number", "Bar", "Hidden"), labels(DurabilityFormat.values()));
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
	void effectsTilesAndListEachGreyTheOthersRows() {
		Layout layout = new Layout("x", "X");
		ModuleState effects = layout.module(ModuleId.EFFECTS);
		assertEquals(ModuleSettings.Effects.TILES, effects.option(ModuleSettings.Effects.VIEW), "tiles by default");
		assertNull(ModuleSettings.inactiveReason(effects, ModuleSettings.Effects.TILE_DIRECTION));
		assertEquals("Only in the List layout", ModuleSettings.inactiveReason(effects, ModuleSettings.Effects.TEXT_SIDE));
		assertEquals("With tiles, only in the Column direction", ModuleSettings.inactiveReason(effects, ModuleSettings.Effects.SHOW_NAMES));
		assertEquals("Only with the Custom tile background", ModuleSettings.inactiveReason(effects, ModuleSettings.Effects.TILE_COLOR));
		assertEquals("The tiles take the place of Minecraft's icons",
			ModuleSettings.inactiveReason(effects, ModuleSettings.Effects.HIDE_VANILLA_EFFECTS));
		effects.set(ModuleSettings.Effects.TILE_DIRECTION, 1);
		assertNull(ModuleSettings.inactiveReason(effects, ModuleSettings.Effects.SHOW_NAMES));
		effects.set(ModuleSettings.Effects.VIEW, ModuleSettings.Effects.LIST);
		assertEquals("Only in the Tiles layout", ModuleSettings.inactiveReason(effects, ModuleSettings.Effects.TILE_DIRECTION));
		assertNull(ModuleSettings.inactiveReason(effects, ModuleSettings.Effects.TEXT_SIDE));
		assertNull(ModuleSettings.inactiveReason(effects, ModuleSettings.Effects.HIDE_VANILLA_EFFECTS));
		ModuleState inventory = layout.module(com.flintclient.client.control.model.mods.UtilityMods.Inventory.ID);
		assertFalse(inventory.bool(com.flintclient.client.control.model.mods.UtilityMods.Inventory.SEARCH), "the search bar starts off");
		assertEquals("Turn on Inventory search bar first",
			ModuleSettings.inactiveReason(inventory, com.flintclient.client.control.model.mods.UtilityMods.Inventory.SEARCH_KEY));
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
		assertNull(ModuleSettings.inactiveReason(armor, ModuleSettings.Armor.BOB));
		armor.set(ModuleSettings.Armor.WARNING, false);
		assertEquals("Turn on Show Warning first", ModuleSettings.inactiveReason(armor, ModuleSettings.Armor.BOB));
		assertNull(ModuleSettings.inactiveReason(armor, ModuleSettings.Armor.OFFHAND_SLOT));
		armor.set(ModuleSettings.Armor.ANCHOR, 1);
		assertEquals("Only with the Hotbar anchor", ModuleSettings.inactiveReason(armor, ModuleSettings.Armor.OFFHAND_SLOT));
		assertEquals("Only with the Top anchor on the right", ModuleSettings.inactiveReason(armor, ModuleSettings.Armor.PUSH_EFFECTS));
		armor.set(ModuleSettings.Armor.SIDE, 1);
		assertNull(ModuleSettings.inactiveReason(armor, ModuleSettings.Armor.PUSH_EFFECTS));
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
			HudRect ping = null;
			for (ModuleDescriptor module : ModuleRegistry.core()) {
				float[] size = SIZES.get(module.id());
				HudRect rect = module.defaultPlacement().toAbsolute(screen[0], screen[1], size[0], size[1]);
				assertTrue(rect.within(screen[0], screen[1]), module.id() + " on " + screen[0] + "x" + screen[1]);
				if (module.id().equals(ModuleId.PING)) {
					ping = rect;
				}
				rects.add(module.id().equals(ModuleId.EFFECTS) ? underPing(rect, ping) : rect);
			}
			assertNoOverlap(rects, screen);
		}
		// The Quick Hub layout: FPS top-left, Ping top-right, Effects as tiles in Minecraft's top-right corner, Armor bottom-right.
		assertEquals(HudPlacement.of(Anchor.TOP_LEFT, 4, 4), ModuleRegistry.get(ModuleId.FPS).defaultPlacement());
		assertEquals(HudPlacement.of(Anchor.TOP_RIGHT, 4, 4), ModuleRegistry.get(ModuleId.PING).defaultPlacement());
		assertEquals(HudPlacement.of(Anchor.TOP_RIGHT, 1, 1), ModuleRegistry.get(ModuleId.EFFECTS).defaultPlacement());
		assertEquals(HudPlacement.of(Anchor.BOTTOM_RIGHT, 4, 4), ModuleRegistry.get(ModuleId.ARMOR).defaultPlacement());
	}

	/**
	 * Effects at its default spot as the HUD draws it: Minecraft's top-right corner is Ping's default spot too, so the tiles step
	 * 2 units under Ping's readout when it shows there (HudRenderer.effectsTop). {@code ping} is null when Ping is off.
	 */
	static HudRect underPing(HudRect effects, HudRect ping) {
		return ping != null && effects.overlaps(ping) ? new HudRect(effects.x(), ping.bottom() + 2, effects.width(), effects.height()) : effects;
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
		assertEquals(Orientation.HORIZONTAL, armor.choice(ModuleSettings.Armor.LAYOUT));
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

	@Test
	void musicPlayerIsAnOffByDefaultHudCardInTheTopLeftClearOfTheOriginalModules() {
		ModuleDescriptor music = ModuleRegistry.get(com.flintclient.client.control.model.mods.MusicMods.MusicPlayer.ID);
		assertEquals("Music Player", music.name());
		assertTrue(music.hud());
		assertTrue(music.status().usable());
		assertEquals(ModuleDescriptor.Requirement.NONE, music.requirement(), "music plays on the title screen too");
		assertTrue(music.keywords().size() >= 3 && music.keywords().size() <= 6);
		assertFalse(new Layout("x", "X").module(music).enabled(), "every mod starts off");
		assertSettings(music.id(), "hideWhenIdle", "hideWhenPaused", "musicAppsOnly", "albumArt", "albumArtSize", "artist", "appName",
			"progressBar", "progressBarThickness", "times", "buttons", "scrollTitle", "width", "colors", "songNameColor", "artistNameColor",
			"durationColor", "progressBarColor", "playPauseKey", "skipKey", "previousKey", "forwardKey", "backKey", "seekStep", "motion",
			"dimWhenPaused");
		assertSections(music.id(), "General", "Display", "Colors", "Controls", "Motion", "Mod");
		for (KeySetting key : com.flintclient.client.control.model.mods.MusicMods.MusicPlayer.KEYS) {
			assertEquals(KeySetting.NONE, key.defaultValue(), key.id() + " starts unbound");
		}
		assertEquals(Anchor.TOP_LEFT, music.defaultPlacement().anchor());
		// The card at its widest default size (150 × 36 units) never covers an original module at its default spot.
		for (float[] screen : SCREENS) {
			HudRect card = music.defaultPlacement().toAbsolute(screen[0], screen[1], 150, 36);
			assertTrue(card.within(screen[0], screen[1]));
			for (ModuleDescriptor module : ModuleRegistry.core()) {
				float[] size = SIZES.get(module.id());
				HudRect rect = module.defaultPlacement().toAbsolute(screen[0], screen[1], size[0], size[1]);
				assertFalse(card.overlaps(rect), module.id() + " on " + screen[0] + "x" + screen[1]);
			}
		}
	}

	@Test
	void musicPlayersColorsAndJumpWaitForTheirSwitches() {
		ModuleState music = new Layout("x", "X").module(ModuleRegistry.get(com.flintclient.client.control.model.mods.MusicMods.MusicPlayer.ID));
		assertEquals("Only with Custom colors", ModuleSettings.inactiveReason(music,
			com.flintclient.client.control.model.mods.MusicMods.MusicPlayer.TITLE_COLOR));
		music.set(com.flintclient.client.control.model.mods.MusicMods.MusicPlayer.COLORS,
			com.flintclient.client.control.model.mods.MusicMods.MusicPlayer.ART_COLORS);
		assertEquals("Only with Custom colors", ModuleSettings.inactiveReason(music,
			com.flintclient.client.control.model.mods.MusicMods.MusicPlayer.PROGRESS_COLOR), "Album art picks the bar's colour itself");
		music.set(com.flintclient.client.control.model.mods.MusicMods.MusicPlayer.COLORS,
			com.flintclient.client.control.model.mods.MusicMods.MusicPlayer.CUSTOM_COLORS);
		assertNull(ModuleSettings.inactiveReason(music, com.flintclient.client.control.model.mods.MusicMods.MusicPlayer.TITLE_COLOR));
		music.set(com.flintclient.client.control.model.mods.MusicMods.MusicPlayer.TIMES, false);
		assertEquals("Turn on Times first", ModuleSettings.inactiveReason(music,
			com.flintclient.client.control.model.mods.MusicMods.MusicPlayer.TIME_COLOR));
		assertEquals("Set Forward or Rewind first", ModuleSettings.inactiveReason(music,
			com.flintclient.client.control.model.mods.MusicMods.MusicPlayer.SEEK_STEP));
		music.set(com.flintclient.client.control.model.mods.MusicMods.MusicPlayer.FORWARD_KEY, "key.keyboard.right.bracket");
		assertNull(ModuleSettings.inactiveReason(music, com.flintclient.client.control.model.mods.MusicMods.MusicPlayer.SEEK_STEP));
	}

	@Test
	void musicPlayersLookAndMotionSettingsStartCalmAndWaitForTheirSwitches() {
		ModuleState music = new Layout("x", "X").module(ModuleRegistry.get(com.flintclient.client.control.model.mods.MusicMods.MusicPlayer.ID));
		assertEquals(com.flintclient.client.control.model.mods.MusicMods.MusicPlayer.MOTION_CALM,
			music.option(com.flintclient.client.control.model.mods.MusicMods.MusicPlayer.MOTION), "Calm is the default");
		assertEquals(100, music.integer(com.flintclient.client.control.model.mods.MusicMods.MusicPlayer.ART_SIZE), "the art as before");
		assertEquals(2, music.integer(com.flintclient.client.control.model.mods.MusicMods.MusicPlayer.BAR_THICKNESS), "the bar as before");
		assertTrue(music.bool(com.flintclient.client.control.model.mods.MusicMods.MusicPlayer.DIM_PAUSED));
		assertNull(ModuleSettings.inactiveReason(music, com.flintclient.client.control.model.mods.MusicMods.MusicPlayer.SCROLL));
		music.set(com.flintclient.client.control.model.mods.MusicMods.MusicPlayer.MOTION,
			com.flintclient.client.control.model.mods.MusicMods.MusicPlayer.MOTION_OFF);
		assertEquals("Motion is Off: long titles end in …", ModuleSettings.inactiveReason(music,
			com.flintclient.client.control.model.mods.MusicMods.MusicPlayer.SCROLL));
		music.set(com.flintclient.client.control.model.mods.MusicMods.MusicPlayer.ALBUM_ART, false);
		assertEquals("Turn on Album art first", ModuleSettings.inactiveReason(music,
			com.flintclient.client.control.model.mods.MusicMods.MusicPlayer.ART_SIZE));
		music.set(com.flintclient.client.control.model.mods.MusicMods.MusicPlayer.PROGRESS, false);
		assertEquals("Turn on Progress bar first", ModuleSettings.inactiveReason(music,
			com.flintclient.client.control.model.mods.MusicMods.MusicPlayer.BAR_THICKNESS));
		music.set(com.flintclient.client.control.model.mods.MusicMods.MusicPlayer.HIDE_PAUSED, true);
		assertEquals("Hidden while paused", ModuleSettings.inactiveReason(music,
			com.flintclient.client.control.model.mods.MusicMods.MusicPlayer.DIM_PAUSED));
	}
}
