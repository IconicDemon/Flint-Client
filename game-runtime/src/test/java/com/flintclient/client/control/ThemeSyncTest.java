package com.flintclient.client.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.model.MenuTheme;
import com.flintclient.client.render.NoticeStyle;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.kit.Tokens;
import com.flintclient.client.ui.kit.Tokens2;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThemeSyncTest {
	@AfterEach
	void backToEmber() {
		Palette.useEmberAccent();
	}

	@Test
	void everyLauncherThemeHasItsLook() {
		assertEquals(MenuTheme.Look.FLINT, MenuTheme.Look.forLauncher("ember"));
		assertEquals(MenuTheme.Look.ICE_CREAM, MenuTheme.Look.forLauncher("obsidian"), "Obsidian was remade as Ice Cream");
		assertEquals(MenuTheme.Look.ICE_CREAM, MenuTheme.Look.forLauncher("icecream"));
		assertEquals(MenuTheme.Look.AURORA, MenuTheme.Look.forLauncher("aurora"));
		assertEquals(MenuTheme.Look.MOSS, MenuTheme.Look.forLauncher("moss"));
		assertEquals(MenuTheme.Look.BLOODSTONE, MenuTheme.Look.forLauncher("bloodstone"));
		assertEquals(MenuTheme.Look.DUSK, MenuTheme.Look.forLauncher(" Dusk "));
		assertEquals(MenuTheme.Look.ICE_CREAM, MenuTheme.Look.forLauncher("frost"), "the app's older names read as it reads them");
		assertNull(MenuTheme.Look.forLauncher("nebula"), "a newer app's theme leaves the look as it is");
		assertNull(MenuTheme.Look.forLauncher(null));
		for (MenuTheme.Look look : MenuTheme.Look.values()) {
			assertEquals(look, MenuTheme.Look.forLauncher(look.launcherId()), "round trip " + look);
		}
		assertEquals("ember", MenuTheme.Look.FLINT.launcherId());
	}

	@Test
	void theLaunchConfigNamesTheTheme(@TempDir Path dir) throws Exception {
		assertEquals("dusk", ThemeSync.themeIn("{\"schema\":1,\"ui\":{\"theme\":\"dusk\"}}"));
		assertNull(ThemeSync.themeIn("{\"schema\":1}"), "an older app's config has none");
		assertNull(ThemeSync.themeIn("{\"ui\":{\"theme\":3}}"));
		assertNull(ThemeSync.themeIn("[]"));
		Path file = dir.resolve("flint-performance.json");
		assertNull(ThemeSync.launchTheme(file), "no file");
		Files.writeString(file, "{not json");
		assertNull(ThemeSync.launchTheme(file), "unreadable");
		Files.writeString(file, "{\"ui\":{\"theme\":\"moss\"}}");
		assertEquals("moss", ThemeSync.launchTheme(file));
	}

	@Test
	void theAccentFollowsTheLookEverywhere() {
		assertTrue(Palette.ember());
		ThemeSync.useLook(MenuTheme.Look.DUSK);
		assertEquals(0xFF9276BA, Palette.ACCENT, "Dusk's accent, as the app's");
		assertEquals(0xFFA287C7, Palette.ACCENT_HOVER);
		assertEquals(0xFF7A619F, Palette.ACCENT_PRESS);
		assertEquals(0xFFB9A3DC, Palette.ACCENT_TEXT);
		assertEquals(Palette.ACCENT, Tokens.ACCENT, "the kit follows");
		assertEquals(Palette.ACCENT, Tokens2.EMBER, "the deck follows");
		assertEquals(Palette.ACCENT_TEXT, NoticeStyle.ACCENT, "notices follow");
		assertEquals(Palette.withAlpha(Palette.ACCENT, 0.38F), Tokens.SELECTION);
		assertEquals(0x339276BA, Palette.themed(0x33FF8139), "an orange of a screen's own takes the accent, at its alpha");
		ThemeSync.useLook(MenuTheme.Look.ICE_CREAM);
		assertEquals(0xFFE07A93, Palette.ACCENT, "Ice Cream: strawberry");
		assertEquals(0xFF2A211C, Palette.ACCENT_INK, "cocoa ink on the light accent");
		assertTrue(Palette.light(), "Ice Cream is the light theme");
		assertEquals(0xFF2A211C, NoticeStyle.TEXT, "notices turn light: cocoa text on cream");
		ThemeSync.useLook(MenuTheme.Look.AURORA);
		assertEquals(0xFF6B7FE3, Palette.ACCENT, "Blueberry (saved as aurora): its blueberry accent");
		assertTrue(!Palette.light());
		assertEquals(0xFFFFFFFF, NoticeStyle.TEXT);
		ThemeSync.useLook(MenuTheme.Look.FLINT);
		assertTrue(Palette.ember());
		assertEquals(Palette.EMBER_ACCENT, Palette.ACCENT, "Ember is Flint's orange, as before");
		assertEquals(0x33FF8139, Palette.themed(0x33FF8139), "and a screen's own orange stays exactly itself");
		assertEquals(0xFFEC9A3C, Tokens2.EMBER_TEXT);
	}

	@Test
	void anAccentAloneGetsItsShades() {
		int version = Palette.accentVersion();
		Palette.useAccent(0xFF3366CC);
		assertEquals(0xFF3366CC, Palette.ACCENT);
		assertTrue(Palette.ACCENT_HOVER != Palette.ACCENT && Palette.ACCENT_PRESS != Palette.ACCENT);
		assertEquals(0xFFFFFFFF, Palette.ACCENT_INK, "white ink on a dark accent");
		assertTrue(Palette.accentVersion() != version);
	}

	@Test
	void textOnEveryThemesAccentIsReadable() {
		for (MenuTheme.Look look : MenuTheme.Look.values()) {
			ThemeSync.useLook(look);
			assertTrue(contrast(Palette.ACCENT_INK, Palette.ACCENT) >= 4.5, look + ": ink on the accent");
			int page = look.light() ? 0xFF000000 | look.background : 0xFF0C0C0E;
			assertTrue(contrast(Palette.ACCENT_TEXT, page) >= 4.5, look + ": the accent's text tint on its page");
		}
	}

	private static double contrast(int a, int b) {
		double la = Palette.luminance(a);
		double lb = Palette.luminance(b);
		return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
	}
}
