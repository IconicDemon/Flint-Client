package com.flintclient.client.control.rshift;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.flintclient.client.control.model.MenuTheme;
import com.flintclient.client.render.NoticeStyle;
import com.flintclient.client.render.Palette;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * No theme may have hard-to-read text in game: the Right Shift menu's colours for every theme (as {@code MenuStyle} makes them)
 * and the notices', checked against WCAG AA: text and grey text 4.5:1 on the window, a block and a card; the meanings (on, a
 * warning, danger) 4.5:1 where they are drawn; the text on an accent fill 4.5:1; the accent as a mark 3:1 (UI).
 */
class ThemeContrastTest {
	@AfterEach
	void backToEmber() {
		MenuStyle.useSeeThrough(1, false);
		MenuStyle.computeColors(MenuTheme.Look.FLINT);
		Palette.useLight(false);
		Palette.useEmberAccent();
	}

	@Test
	void everyThemeReadsInGame() {
		List<String> failures = new ArrayList<>();
		for (MenuTheme.Look look : MenuTheme.Look.values()) {
			MenuStyle.computeColors(look);
			String t = look.label() + ": ";
			for (int[] pair : new int[][] {{MenuStyle.TEXT, MenuStyle.BG}, {MenuStyle.TEXT, MenuStyle.BG2}, {MenuStyle.TEXT, MenuStyle.CARD},
				{MenuStyle.NEUTRAL, MenuStyle.BG}, {MenuStyle.NEUTRAL, MenuStyle.BG2}}) {
				need(failures, t + "text " + hex(pair[0]) + " on " + hex(pair[1]), pair[0], pair[1], 4.5);
			}
			need(failures, t + "on-text on its fill", MenuStyle.ENABLED_TEXT, MenuStyle.ENABLED, 4.5);
			need(failures, t + "a warning", MenuStyle.WARN_TEXT, MenuStyle.BG, 4.5);
			need(failures, t + "danger text", MenuStyle.DANGER_TEXT, MenuStyle.BG, 4.5);
			need(failures, t + "the accent as a mark", MenuStyle.ACCENT, MenuStyle.BG, 3);
			// Ember keeps Dawn's white on orange (the menu's own look, chosen by the owner): checked as large text, its tabs' bold
			// capitals.
			need(failures, t + "text on the accent", MenuStyle.ON_ACCENT, MenuStyle.ACCENT, look == MenuTheme.Look.FLINT ? 2.2 : 4.5);
			Palette.useLight(look.light());
			for (int behind : new int[] {0xFF000000, 0xFF808080, 0xFFFFFFFF}) {
				int pill = Palette.over(NoticeStyle.FILL, behind);
				need(failures, t + "a notice over " + hex(behind), NoticeStyle.TEXT, pill, look.light() ? 4.5 : behind == 0xFFFFFFFF ? 3 : 4.5);
			}
		}
		assertEquals(List.of(), failures);
	}

	/**
	 * General › Menu transparency, every step, blurred or not, in every theme: over the game's average (a mid grey) behind the
	 * see-through panel, the text keeps 7:1 and the grey text today's 4.5:1 bar (lightened as needed).
	 */
	@Test
	void seeThroughMenusStayReadableInEveryTheme() {
		List<String> failures = new ArrayList<>();
		for (MenuTheme.Look look : MenuTheme.Look.values()) {
			for (int percent = 0; percent <= 50; percent += 5) {
				for (boolean blurred : new boolean[] {true, false}) {
					float a = MenuStyle.seeThroughFor(percent, blurred);
					MenuStyle.useSeeThrough(a, true);
					MenuStyle.computeColors(look);
					int behind = Palette.over(Palette.withAlpha(MenuStyle.BG, a), MenuStyle.BACKDROP);
					String t = look.label() + " at " + percent + "%" + (blurred ? " blurred" : "") + ": ";
					need(failures, t + "text", MenuStyle.TEXT, behind, 7);
					need(failures, t + "grey text", MenuStyle.NEUTRAL, behind, 4.5);
				}
			}
		}
		assertEquals(List.of(), failures);
		org.junit.jupiter.api.Assertions.assertTrue(MenuStyle.seeThroughFor(30, true) < 0.80F, "the default is more see-through than before (0.80)");
		assertEquals(1F, MenuStyle.seeThroughFor(0, true), 1e-6, "0 is solid");
	}

	private static void need(List<String> failures, String what, int fg, int bg, double min) {
		double ratio = contrast(fg, Palette.over(bg, 0xFF000000));
		if (ratio < min) {
			failures.add(String.format("%s: %.2f:1 < %.1f:1", what, ratio, min));
		}
	}

	private static String hex(int argb) {
		return String.format("%08X", argb);
	}

	private static double contrast(int a, int b) {
		double la = Palette.luminance(a);
		double lb = Palette.luminance(b);
		return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
	}
}
