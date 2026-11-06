package com.flintclient.legacy.shared;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.hud.Anchor;
import com.flintclient.client.control.hud.HudPlacement;
import com.flintclient.client.control.model.Appearance;
import com.flintclient.client.control.model.CpsFormat;
import com.flintclient.client.control.model.FpsDisplayMode;
import com.flintclient.client.control.model.GameOptions;
import com.flintclient.client.control.model.HudColor;
import com.flintclient.client.control.model.KeystrokesLayout;
import com.flintclient.client.control.model.LabelPosition;
import com.flintclient.client.control.model.Layout;
import com.flintclient.client.control.model.ModuleRegistry;
import com.flintclient.client.control.model.ModuleSettings;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.HudMods;
import com.flintclient.client.control.model.mods.PlayerMods;
import com.flintclient.client.control.model.mods.ToolMods;
import com.flintclient.client.control.model.mods.UtilityMods;
import com.flintclient.client.control.model.mods.VisualMods;
import com.flintclient.client.control.store.ControlSettings;
import com.flintclient.client.control.store.LayoutStore;
import com.flintclient.shaded.gson.JsonObject;
import com.flintclient.shaded.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Carrying 1.8.9's old settings file over to the shared settings, with two real files from QA tours (every 1.8.9 mod configured;
 * Waypoints and the default looks).
 */
class Migration189Test {
	@TempDir
	Path config;

	private static byte[] fixture(String name) throws IOException {
		try (InputStream in = Migration189Test.class.getResourceAsStream("/legacy189/" + name)) {
			assertNotNull(in, name);
			return in.readAllBytes();
		}
	}

	/** Puts a fixture where a 1.8.9 profile has its old file; returns that file. */
	private Path install(String name) throws IOException {
		Path dir = config.resolve(ControlSettings.DIR);
		Files.createDirectories(dir);
		Path file = dir.resolve(Migration189.FILE);
		Files.write(file, fixture(name));
		return file;
	}

	private static ModuleState module(Layout layout, String id) {
		return layout.modules().get(ModuleRegistry.indexOf(id));
	}

	@Test
	void everyModCarriesItsSwitchPlacementAndSettings() throws IOException {
		Path old = install("mods-tour.json");
		ControlSettings settings = new ControlSettings(config);
		settings.load();
		LayoutStore layouts = new LayoutStore(config, settings);
		layouts.load();
		Migration189.Result result = Migration189.migrateFile(config, layouts.active(), settings);
		assertNotNull(result);
		assertEquals(23, result.modules(), "every mod in the file (it has no Waypoints entry)");
		Layout layout = layouts.active();

		ModuleState fps = module(layout, "fps");
		assertTrue(fps.enabled());
		assertEquals(new HudPlacement(Anchor.TOP, -10, 78, 1), fps.placement(), "a centre anchor keeps its offsets");
		assertTrue(fps.bool(ModuleSettings.Fps.SHOW_LABEL));
		assertFalse(fps.bool(ModuleSettings.Fps.REVERSED), "suffix: 80 FPS");
		assertEquals(FpsDisplayMode.BACKGROUND, fps.choice(ModuleSettings.Fps.DISPLAY_MODE));
		assertEquals(0x000000, fps.rgb(ModuleSettings.Fps.BACKGROUND_COLOR));
		assertEquals(45, fps.integer(ModuleSettings.Fps.BACKGROUND_OPACITY), "#6F is 44%, on the shared 5% step");
		assertEquals(5, fps.integer(ModuleSettings.Fps.STYLE.horizontalPadding));
		assertEquals(4, fps.integer(ModuleSettings.Fps.STYLE.verticalPadding));
		assertTrue(fps.bool(ModuleSettings.Fps.TEXT_SHADOW));
		assertEquals(2, fps.integer(ModuleSettings.Fps.BORDER_THICKNESS), "1 pixel is 2 half units");

		assertEquals(new HudPlacement(Anchor.BOTTOM_RIGHT, 4, 4, 1), module(layout, "armor").placement(), "right and bottom offsets flip");
		// Armor is placed as uku's Armor HUD places it: the old bottom-right spot becomes its bottom anchor on the right, 4 in.
		ModuleState armor = module(layout, "armor");
		assertEquals(3, armor.option(ModuleSettings.Armor.ANCHOR), "bottom");
		assertEquals(1, armor.option(ModuleSettings.Armor.SIDE), "right");
		assertEquals(4, armor.integer(ModuleSettings.Armor.OFFSET_X));
		assertEquals(4, armor.integer(ModuleSettings.Armor.OFFSET_Y));
		assertEquals(new HudPlacement(Anchor.LEFT, 0, -15.399994F, 1.3F), module(layout, "coordinates").placement());
		assertEquals(new HudPlacement(Anchor.BOTTOM, 124, 3, 1), module(layout, "saturation").placement());

		ModuleState cps = module(layout, "cps");
		assertEquals(CpsFormat.BAR, cps.choice(ModuleSettings.Cps.FORMAT), "1.8.9 showed 12 | 4");
		assertEquals(LabelPosition.AFTER, cps.choice(ModuleSettings.Cps.LABEL_POSITION));

		ModuleState crosshair = module(layout, "crosshair");
		assertTrue(crosshair.enabled());
		assertEquals(0x55FF55, crosshair.rgb(PlayerMods.Crosshair.COLOR));
		assertEquals(255, crosshair.integer(PlayerMods.Crosshair.ALPHA));
		assertEquals(3, crosshair.integer(PlayerMods.Crosshair.WIDTH), "5 pixels at GUI scale 2");
		assertEquals(1, crosshair.integer(PlayerMods.Crosshair.THICKNESS));
		assertEquals(0xC0, crosshair.integer(PlayerMods.Crosshair.OUTLINE_ALPHA));

		ModuleState scoreboard = module(layout, "scoreboard");
		assertTrue(scoreboard.bool(UtilityMods.Scoreboard.NUMBERS));
		assertEquals(30, scoreboard.integer(UtilityMods.Scoreboard.OPACITY), "#50 is 31%");
		assertFalse(scoreboard.bool(UtilityMods.Scoreboard.SHADOW));

		ModuleState animations = module(layout, "animations");
		assertTrue(animations.enabled(), "Hit Color was on");
		assertTrue(animations.bool(ToolMods.Animations.CUSTOM_DAMAGE));

		ModuleState lighting = module(layout, "lighting");
		assertFalse(lighting.enabled());

		assertTrue(module(layout, "item_counter").text(HudMods.ItemCounter.EXTRA).contains("minecraft:splash_potion"));
		assertEquals(1.0F, settings.game(GameOptions.MENU_SCALE), 1e-6);

		// Saved, then the old file moves aside, and a new start reads the same from the shared files.
		assertTrue(settings.save());
		assertTrue(layouts.save());
		byte[] before = Files.readAllBytes(old);
		assertTrue(result.backUp());
		assertFalse(Files.exists(old));
		assertArrayEquals(before, Files.readAllBytes(old.resolveSibling(Migration189.BACKUP)), "the backup is the old file as it was");

		ControlSettings again = new ControlSettings(config);
		again.load();
		LayoutStore reread = new LayoutStore(config, again);
		reread.load();
		assertNull(Migration189.migrateFile(config, reread.active(), again), "nothing left to carry over");
		assertEquals(fps, module(reread.active(), "fps"), "FPS read back as migrated");
		assertEquals(module(layout, "crosshair"), module(reread.active(), "crosshair"));
		assertEquals(module(layout, "keystrokes"), module(reread.active(), "keystrokes"));
	}

	@Test
	void waypointsKeysAndTheDefaultLooksCarryOver() throws IOException {
		install("java21-tour.json");
		ControlSettings settings = new ControlSettings(config);
		settings.load();
		LayoutStore layouts = new LayoutStore(config, settings);
		layouts.load();
		Migration189.Result result = Migration189.migrateFile(config, layouts.active(), settings);
		assertEquals(24, result.modules());
		Layout layout = layouts.active();

		ModuleState waypoints = module(layout, "waypoints");
		assertTrue(waypoints.enabled());
		assertEquals("key.keyboard.n", waypoints.text(UtilityMods.Waypoints.CREATE_KEY), "1.8.9 key 49 is N");
		assertEquals("", waypoints.text(UtilityMods.Waypoints.TOGGLE_KEY));
		assertEquals(0, waypoints.option(UtilityMods.Waypoints.LABEL_STYLE), "Icon");
		assertEquals(2.5F, waypoints.decimal(UtilityMods.Waypoints.LABEL_HEIGHT), 1e-6);
		assertEquals(2.0F, waypoints.decimal(UtilityMods.Waypoints.OUTLINE_THICKNESS), 1e-6);

		ModuleState keys = module(layout, "keystrokes");
		assertEquals(KeystrokesLayout.KEYS_MOUSE_JUMP, keys.choice(ModuleSettings.Keystrokes.LAYOUT));
		assertTrue(keys.bool(ModuleSettings.Keystrokes.SHOW_CPS));
		assertEquals(120, keys.integer(ModuleSettings.Keystrokes.FADE_MS), "100 ms is nearest 120");
		assertEquals(HudColor.WHITE, keys.choice(ModuleSettings.Keystrokes.PRESSED_COLOR));
		Appearance look = keys.appearance();
		assertEquals(Boolean.TRUE, look.background());
		assertEquals(Integer.valueOf(0x000000), look.backgroundColor());
		assertEquals(Integer.valueOf(40), look.backgroundOpacity(), "#66 is 40%");
		assertEquals(HudColor.DEFAULT, look.textColor(), "white was the default");
		assertEquals(Boolean.TRUE, look.textShadow());

		ModuleState clock = module(layout, "clock");
		assertEquals(2, clock.option(HudMods.Clock.TIME_FORMAT), "12-hour without seconds");
		assertEquals(1, clock.option(HudMods.Clock.ORIENTATION), "one line, as 1.8.9 showed it");

		ModuleState zoom = module(layout, "zoom");
		assertEquals(100, zoom.integer(PlayerMods.Zoom.RELATIVE_SENSITIVITY));
		ModuleState outline = module(layout, "block_outline");
		assertEquals(100, outline.integer(VisualMods.BlockOutline.THICKNESS), "2 pixels is Minecraft's line");
	}

	@Test
	void applyingTheSameFileAgainChangesNothing() throws IOException {
		JsonObject root = JsonParser.parseString(new String(fixture("mods-tour.json"), StandardCharsets.UTF_8)).getAsJsonObject();
		ControlSettings settings = new ControlSettings(config);
		settings.load();
		Layout once = new Layout("a", "A");
		Migration189.migrate(root, once, settings);
		Layout twice = new Layout("b", "B");
		Migration189.migrate(root, twice, settings);
		Migration189.migrate(root, twice, settings);
		for (int i = 0; i < once.modules().size(); i++) {
			assertEquals(once.modules().get(i), twice.modules().get(i), once.modules().get(i).id());
		}
	}

	@Test
	void noFileOrADamagedOneIsLeftAlone() throws IOException {
		ControlSettings settings = new ControlSettings(config);
		settings.load();
		Layout layout = new Layout("a", "A");
		assertNull(Migration189.migrateFile(config, layout, settings), "no file");
		Path dir = config.resolve(ControlSettings.DIR);
		Files.createDirectories(dir);
		Path file = dir.resolve(Migration189.FILE);
		Files.writeString(file, "{\"modules\": {\"fps\": ");
		assertNull(Migration189.migrateFile(config, layout, settings));
		assertTrue(Files.exists(file), "kept for the player");
		assertFalse(Files.exists(dir.resolve(Migration189.BACKUP)));
		assertEquals(new Layout("a", "A").modules().get(0), layout.modules().get(0), "nothing changed");
	}

	@Test
	void everyOld189IdHasASharedModule() {
		for (String shared : Migration189.SHARED_IDS.values()) {
			assertTrue(ModuleRegistry.indexOf(shared) >= 0, shared);
		}
		assertEquals(24, Migration189.SHARED_IDS.size());
		assertEquals(24, Migration189.SHARED_IDS.values().stream().distinct().count(), "one shared module per 1.8.9 mod");
	}

	@Test
	void oldPlacementsKeepTheirSpot() {
		JsonObject hud = JsonParser.parseString("{\"anchorX\":2,\"anchorY\":0,\"x\":-4,\"y\":24,\"scale\":1.5}").getAsJsonObject();
		HudPlacement p = Migration189.placement(hud, HudPlacement.of(Anchor.TOP_LEFT, 0, 0));
		assertEquals(Anchor.TOP_RIGHT, p.anchor());
		// On a 480 × 270 screen, a 40 × 10 module at 1.5 (60 × 15): 1.8.9 put its left edge at 480 - 4 - 60.
		assertEquals(480 - 4 - 60, p.left(480, 60), 1e-4);
		assertEquals(24, p.top(270, 15), 1e-4);
		assertEquals(1.5F, p.scale(), 1e-6);
		assertEquals(HudColor.EMBER, Migration189.palette(0xFFFF8139, true));
		assertEquals(HudColor.DEFAULT, Migration189.palette(0xFFFFFFFF, true));
		assertEquals(44, Migration189.percent(0x6F000000));
	}
}
