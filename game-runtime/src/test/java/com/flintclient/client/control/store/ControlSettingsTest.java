package com.flintclient.client.control.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.model.InterfaceSize;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ControlSettingsTest {
	@TempDir
	Path config;

	private Path file() {
		return config.resolve("flintclient").resolve("control-center.json");
	}

	private void write(String json) throws IOException {
		Files.createDirectories(file().getParent());
		Files.writeString(file(), json, StandardCharsets.UTF_8);
	}

	private JsonObject saved() throws IOException {
		return JsonParser.parseString(Files.readString(file())).getAsJsonObject();
	}

	private ControlSettings settings() {
		return new ControlSettings(config, JsonStoreTest.CLOCK);
	}

	private static void assertDefaults(ControlSettings settings) {
		assertTrue(settings.soundsEnabled());
		assertEquals(0.6F, settings.soundVolume());
		assertEquals(60, settings.soundVolumePercent());
		assertTrue(settings.animations());
		assertTrue(settings.followMinecraftReducedMotion());
		assertEquals(InterfaceSize.AUTO, settings.interfaceSize());
		assertFalse(settings.highContrast());
		assertFalse(settings.showHudWithDebugScreen());
		assertEquals(List.of(), settings.favorites());
		assertEquals(List.of(), settings.recents());
		assertFalse(settings.onboardingCompleted());
		assertFalse(settings.hintShown());
		assertNull(settings.activeLayout());
	}

	@Test
	void defaultsWithoutAFileAndNothingWritten() {
		ControlSettings settings = settings();
		assertDefaults(settings);
		assertNull(settings.notice());
		assertTrue(settings.save());
		assertFalse(Files.exists(file()), "nothing written until something changes");
		assertEquals(file(), settings.file());
	}

	@Test
	void everyValueRoundTrips() throws IOException {
		ControlSettings settings = settings();
		settings.setSoundsEnabled(false);
		settings.setSoundVolumePercent(35);
		settings.setAnimations(false);
		settings.setFollowMinecraftReducedMotion(false);
		settings.setInterfaceSize(InterfaceSize.LARGER);
		settings.setHighContrast(true);
		settings.setShowHudWithDebugScreen(true);
		settings.setFavorite("fps", true);
		settings.setFavorite("armor", true);
		settings.recordChange("cps");
		settings.recordChange("ping");
		settings.setOnboardingCompleted(true);
		settings.setHintShown(true);
		settings.setActiveLayout("pvp-2");
		assertTrue(settings.dirty());
		assertTrue(settings.save());
		assertFalse(settings.dirty());

		ControlSettings read = settings();
		assertFalse(read.soundsEnabled());
		assertEquals(0.35F, read.soundVolume());
		assertEquals(35, read.soundVolumePercent());
		assertFalse(read.animations());
		assertFalse(read.followMinecraftReducedMotion());
		assertEquals(InterfaceSize.LARGER, read.interfaceSize());
		assertTrue(read.highContrast());
		assertTrue(read.showHudWithDebugScreen());
		assertEquals(List.of("fps", "armor"), read.favorites());
		assertEquals(List.of("ping", "cps"), read.recents());
		assertTrue(read.onboardingCompleted());
		assertTrue(read.hintShown());
		assertEquals("pvp-2", read.activeLayout());

		JsonObject json = saved();
		assertEquals(1, json.get("schema").getAsInt());
		assertEquals("larger", json.getAsJsonObject("accessibility").get("interfaceSize").getAsString(), "lower-case names");
		assertEquals("0.35", json.getAsJsonObject("sounds").get("volume").toString(), "a fraction, written as the short decimal");
		assertEquals(List.of("schema", "sounds", "motion", "accessibility", "hud", "library", "onboarding", "activeLayout"),
			List.copyOf(json.keySet()));
	}

	@Test
	void eachBadValueFallsBackOnItsOwn() throws IOException {
		write("""
			{"schema": 1,
			 "sounds": {"enabled": "yes", "volume": 7},
			 "motion": {"animations": false, "followMinecraftReducedMotion": 1},
			 "accessibility": {"interfaceSize": "huge", "highContrast": true},
			 "hud": [],
			 "library": {"favorites": ["fps", 3, null, "fps", "ping"], "recents": ["a", "b", "c", "d", "e", ""]},
			 "onboarding": {"completed": "true", "hintShown": true},
			 "activeLayout": "Not An Id!"}
			""");
		ControlSettings settings = settings();
		assertTrue(settings.soundsEnabled(), "wrong type: default");
		assertEquals(1.0F, settings.soundVolume(), "out of range: clamped");
		assertEquals(100, settings.soundVolumePercent());
		assertFalse(settings.animations(), "valid values are kept");
		assertTrue(settings.followMinecraftReducedMotion());
		assertEquals(InterfaceSize.AUTO, settings.interfaceSize(), "unknown option: default");
		assertTrue(settings.highContrast());
		assertFalse(settings.showHudWithDebugScreen(), "wrong container: default");
		assertEquals(List.of("fps", "ping"), settings.favorites(), "non-strings and repeats dropped");
		assertEquals(List.of("a", "b", "c", "d"), settings.recents(), "at most four");
		assertFalse(settings.onboardingCompleted());
		assertTrue(settings.hintShown());
		assertNull(settings.activeLayout(), "not a layout id");
		assertNull(settings.notice(), "bad values aren't a damaged file");
	}

	@Test
	void unknownKeysSurviveASave() throws IOException {
		write("""
			{"schema": 1, "sync": {"account": "invented"}, "sounds": {"enabled": true, "volume": 0.6, "pack": "soft"},
			 "library": {"favorites": ["fps"], "sortBy": "name"}}
			""");
		ControlSettings settings = settings();
		settings.setSoundVolumePercent(80);
		assertTrue(settings.save());
		JsonObject json = saved();
		assertEquals("invented", json.getAsJsonObject("sync").get("account").getAsString());
		assertEquals("soft", json.getAsJsonObject("sounds").get("pack").getAsString());
		assertEquals(0.8, json.getAsJsonObject("sounds").get("volume").getAsDouble(), 1e-6);
		assertEquals("name", json.getAsJsonObject("library").get("sortBy").getAsString());
		assertEquals("fps", json.getAsJsonObject("library").getAsJsonArray("favorites").get(0).getAsString());
	}

	@Test
	void aDamagedFileGivesDefaultsANoticeAndACopy() throws IOException {
		write("{\"sounds\": {\"enabled\": fals");
		ControlSettings settings = settings();
		assertDefaults(settings);
		assertEquals("control-center.json couldn't be read. Flint saved a copy as control-center.damaged-20261015-101500.json and is "
			+ "using defaults.", settings.notice());
		assertTrue(Files.exists(file().resolveSibling("control-center.damaged-20261015-101500.json")));
		assertFalse(settings.readOnly());
	}

	@Test
	void aNewerFileIsUsedButNeverWritten() throws IOException {
		String newer = "{\"schema\": 3, \"sounds\": {\"enabled\": false}, \"onboarding\": {\"completed\": true}}";
		write(newer);
		ControlSettings settings = settings();
		assertFalse(settings.soundsEnabled(), "understood values are used");
		assertTrue(settings.onboardingCompleted());
		assertTrue(settings.readOnly());
		assertEquals("Saved by a newer Flint version; changes won't be saved.", settings.notice());
		settings.setHighContrast(true);
		assertTrue(settings.highContrast(), "changes still apply for this session");
		assertFalse(settings.save());
		assertEquals(newer, Files.readString(file()));
	}

	@Test
	void settingTheSameValueIsNotAChange() {
		ControlSettings settings = settings();
		settings.setSoundsEnabled(true);
		settings.setSoundVolumePercent(60);
		settings.setSoundVolumePercent(61);
		settings.setInterfaceSize(InterfaceSize.AUTO);
		settings.setFavorite("fps", false);
		assertFalse(settings.dirty());
		settings.setSoundVolumePercent(62);
		assertEquals(60, settings.soundVolumePercent(), "snapped to 5% steps");
		assertFalse(settings.dirty());
		settings.setSoundVolumePercent(-20);
		assertEquals(0, settings.soundVolumePercent(), "clamped");
		assertEquals(0.0F, settings.soundVolume());
	}

	@Test
	void theVolumeSliderShowsPercentWhileTheFileKeepsAFraction() {
		assertEquals("%", ControlSettings.VOLUME.unit());
		assertEquals(0, ControlSettings.VOLUME.min());
		assertEquals(100, ControlSettings.VOLUME.max());
		assertEquals(5, ControlSettings.VOLUME.step());
		assertEquals(60, ControlSettings.VOLUME.defaultValue());
	}

	@Test
	void aFileThatIsNotUtf8IsCopiedAsideAndCanBeReplaced() throws IOException {
		Files.createDirectories(file().getParent());
		byte[] latin1 = "{\"schema\": 1, \"note\": \"Café\", \"sounds\": {\"enabled\": false}}".getBytes(StandardCharsets.ISO_8859_1);
		Files.write(file(), latin1);
		ControlSettings settings = settings();
		assertDefaults(settings);
		assertFalse(settings.readOnly(), "not stuck read-only");
		assertEquals("control-center.json couldn't be read. Flint saved a copy as control-center.damaged-20261015-101500.json and is "
			+ "using defaults.", settings.notice());
		settings.setOnboardingCompleted(true);
		assertTrue(settings.save(), "skipping setup is remembered");
		assertTrue(settings().onboardingCompleted());
		assertNull(settings.notice());
		assertArrayEquals(latin1, Files.readAllBytes(file().resolveSibling("control-center.damaged-20261015-101500.json")));
	}

	@Test
	void anOptionFromANewerFlintSurvivesUntilThatSettingChanges() throws IOException {
		write("{\"schema\": 1, \"accessibility\": {\"interfaceSize\": \"huge\", \"highContrast\": false}, \"sounds\": {\"volume\": 0.6}}");
		ControlSettings settings = settings();
		assertEquals(InterfaceSize.AUTO, settings.interfaceSize(), "read as the default");
		settings.setHighContrast(true);
		assertTrue(settings.save());
		assertEquals("huge", saved().getAsJsonObject("accessibility").get("interfaceSize").getAsString(), "kept while unchanged");
		settings.setInterfaceSize(InterfaceSize.LARGER);
		settings.setInterfaceSize(InterfaceSize.AUTO);
		settings.setSoundVolumePercent(65);
		assertTrue(settings.save());
		assertEquals("huge", saved().getAsJsonObject("accessibility").get("interfaceSize").getAsString(),
			"changed and changed back: still what the file says");
		assertEquals(0.65, saved().getAsJsonObject("sounds").get("volume").getAsDouble(), 1e-9);
		settings.setInterfaceSize(InterfaceSize.SMALLER);
		assertTrue(settings.save());
		assertEquals("smaller", saved().getAsJsonObject("accessibility").get("interfaceSize").getAsString(), "replaced once changed");
	}

	@Test
	void favoritesToggle() {
		ControlSettings settings = settings();
		assertTrue(settings.toggleFavorite("cps"));
		assertTrue(settings.isFavorite("cps"));
		assertFalse(settings.toggleFavorite("cps"));
		assertFalse(settings.isFavorite("cps"));
		assertThrows(UnsupportedOperationException.class, () -> settings.favorites().add("x"));
	}

	@Test
	void recentsAreNewestFirstWithoutRepeats() {
		ControlSettings settings = settings();
		for (String id : List.of("fps", "ping", "fps", "cps", "armor", "effects")) {
			settings.recordChange(id);
		}
		assertEquals(List.of("effects", "armor", "cps", "fps"), settings.recents());
	}

	@Test
	void motionFollowsBothSwitches() {
		ControlSettings settings = settings();
		assertFalse(settings.instantMotion(false));
		assertTrue(settings.instantMotion(true), "follows Minecraft's reduced motion by default");
		settings.setFollowMinecraftReducedMotion(false);
		assertFalse(settings.instantMotion(true));
		settings.setAnimations(false);
		assertTrue(settings.instantMotion(false), "animations off is always instant");
	}

	@Test
	void layoutIdsAreChecked() {
		assertThrows(IllegalArgumentException.class, () -> settings().setActiveLayout("../evil"));
	}

	@Test
	void theMinimumFileIsAllDefaults() throws IOException {
		write("{}");
		assertDefaults(settings());
		assertNotNull(settings().file());
	}

	@Test
	void theMenuThemeCompactViewAndFoldedSectionsSurviveARestart(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
		ControlSettings first = new ControlSettings(dir, JsonStoreTest.CLOCK);
		com.flintclient.client.control.model.MenuTheme theme = com.flintclient.client.control.model.MenuTheme.FLINT
			.withAccent(0x3366FF).withSurface(com.flintclient.client.control.model.MenuTheme.Surface.ONYX).withRoundness(60).withDim(80);
		first.setMenuTheme(theme);
		first.setCompactView(true);
		first.setSectionCollapsed("zoom/Smooth", true);
		first.setSectionCollapsed("fps/Style", true);
		first.setSectionCollapsed("fps/Style", false);
		assertTrue(first.save());
		ControlSettings second = new ControlSettings(dir, JsonStoreTest.CLOCK);
		assertEquals(theme, second.menuTheme());
		assertTrue(second.compactView());
		assertTrue(second.sectionCollapsed("zoom/Smooth"));
		assertFalse(second.sectionCollapsed("fps/Style"));
	}

	@Test
	void aMissingMenuSectionMeansFlintsOwnLook(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
		ControlSettings settings = new ControlSettings(dir, JsonStoreTest.CLOCK);
		assertEquals(com.flintclient.client.control.model.MenuTheme.FLINT, settings.menuTheme());
		assertFalse(settings.compactView());
		assertFalse(settings.dirty());
	}
}
