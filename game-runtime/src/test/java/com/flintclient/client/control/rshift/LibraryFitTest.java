package com.flintclient.client.control.rshift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The in-game Library window at every window size the owner checks (854×480 to 3840×2160, 21:9 and 32:9-ish ultrawides) and
 * every Flint UI scaling: the window stays on screen, the tabs keep their words, the search keeps room to type (beside the
 * tabs, or on a row of its own), at least one whole result shows (the rest scroll), and a result keeps room for its name
 * beside its buttons. Minecraft's GUI scale doesn't enter: Flint's windows size themselves from the window
 * ({@link MenuStyle#factorFor}), so GUI scale 1–4 and auto give the same layout. Sizes are laid out as
 * {@code WindowScreen} and {@link LibraryPage} do, in design pixels; text is measured as Minecraft's font draws it (about
 * 6 font pixels a character at {@link MenuStyle#pixelScaleFor} window pixels each).
 */
class LibraryFitTest {
	private static final int[][] SIZES = {{854, 480}, {1280, 720}, {1366, 768}, {1920, 1080}, {2560, 1080}, {2560, 1440}, {3440, 1440}, {3840, 2160}};
	private static final float[] SCALING = {0.5F, 0.75F, 1F, 1.25F, 1.5F};
	private static final String[] TABS = {"Mods", "Resource Packs", "Shaders", "Your mods"};

	@Test
	void theLibraryFitsEveryWindow() {
		for (int[] size : SIZES) {
			for (float scaling : SCALING) {
				check(size[0], size[1], scaling);
			}
		}
	}

	private static void check(int w, int h, float scaling) {
		String at = w + "x" + h + " at " + scaling;
		float f = MenuStyle.factorFor(w, h, scaling);
		float d = 1.8F * f;
		int pixel = MenuStyle.pixelScaleFor(f);
		// WindowScreen: the block within the window's edges and its back button, at most 1320 × 820 design pixels.
		float edge = 24;
		float side = 52;
		float winW = Math.min(w / d - 2 * edge - 2 * side, 1320);
		float winH = Math.max(Math.min(460, h / d - 2 * edge), Math.min(820, h / d - 2 * edge));
		assertTrue(winW * d <= w && winH * d <= h + 1, at + ": the window is inside the screen");
		float inner = winW - 2 * 28;
		// The tabs: their words always whole (narrowed, never cut).
		float[] text = new float[TABS.length];
		float[] pref = new float[TABS.length];
		for (int i = 0; i < TABS.length; i++) {
			text[i] = TABS[i].length() * 6 * pixel / d;
			pref[i] = text[i] + 36;
		}
		float[] widths = LibraryPage.tabWidths(pref, inner, 8);
		float used = -8;
		for (int i = 0; i < TABS.length; i++) {
			used += widths[i] + 8;
			assertTrue(widths[i] >= text[i] + 8, at + ": the tab " + TABS[i] + " keeps its words (" + widths[i] + " for " + text[i] + ")");
		}
		assertTrue(used <= inner + 0.5F, at + ": the tabs fit the row");
		// The search: beside the tabs when there are 240 design pixels, else a whole row of its own.
		float beside = Math.min(inner - used - 24, Math.min(460, inner * 0.42F));
		boolean ownRow = LibraryPage.searchOwnRow(beside, 240);
		float fieldW = ownRow ? inner : beside;
		assertTrue(fieldW >= 240, at + ": room to type a search (" + fieldW + ")");
		// The results: at least one whole card under the bar; more scroll.
		float top = 28 + 56 + 8 + 44 + (ownRow ? 44 + 10 : 0) + 18;
		float list = winH - top - 28 - 26;
		assertTrue(list >= 124, at + ": one whole result shows (" + list + " for 124)");
		// A card: its name keeps 260 design pixels beside the Install button at least.
		float cardW = inner;
		int shown = LibraryPage.buttonsShown(cardW, 18, 76, 260, 10, new float[] {130, 240, 260});
		assertTrue(shown >= 1, at);
		assertTrue(cardW - 2 * 18 - 76 - 18 - 130 >= 260, at + ": the name has room beside Install");
	}

	@Test
	void narrowWindowsGiveTheSearchItsOwnRowAndDropExtraButtons() {
		assertTrue(LibraryPage.searchOwnRow(200, 240));
		assertTrue(!LibraryPage.searchOwnRow(300, 240));
		float[] widths = LibraryPage.tabWidths(new float[] {100, 200, 100, 100}, 400, 8);
		float total = -8;
		for (float x : widths) {
			total += x + 8;
		}
		assertEquals(400, total, 0.01F, "narrowed to the row exactly");
		assertEquals(1, LibraryPage.buttonsShown(600, 18, 76, 260, 10, new float[] {130, 240, 260}), "Install alone on a narrow card");
		assertEquals(3, LibraryPage.buttonsShown(1300, 18, 76, 260, 10, new float[] {130, 240, 260}), "all three on a wide one");
	}
}
