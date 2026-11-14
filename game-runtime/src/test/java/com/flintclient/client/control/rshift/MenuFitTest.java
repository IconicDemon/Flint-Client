package com.flintclient.client.control.rshift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.model.GameOptions;
import com.flintclient.client.control.model.MenuSize;
import org.junit.jupiter.api.Test;

/** The Right Shift menu at every window size: its scale from the window, its text scale, and the mods grid's columns. */
class MenuFitTest {
	@Test
	void theMenuFollowsTheWindowFromSmallLaptopsToEightK() {
		assertEquals(0.54F, MenuStyle.factorFor(1920, 1080, 1), 1e-6);
		assertEquals(0.72F, MenuStyle.factorFor(2560, 1440, 1), 1e-6);
		assertEquals(0.54F, MenuStyle.factorFor(2560, 1080, 1), 1e-6, "21:9 follows its height");
		assertEquals(0.5F, MenuStyle.factorFor(1600, 1200, 1), 1e-6, "4:3 follows its width");
		assertEquals(1.08F, MenuStyle.factorFor(3840, 2160, 1), 1e-6, "4K grows past the reference's own size");
		assertEquals(1.62F, MenuStyle.factorFor(3840, 2160, 1.5F), 1e-6, "UI Scaling above 1 works on 4K too");
		assertEquals(MenuStyle.MAX_FACTOR, MenuStyle.factorFor(7680, 4320, 1), 1e-6, "8K: at most twice the reference");
		assertEquals(MenuStyle.MIN_FACTOR, MenuStyle.factorFor(1280, 720, 1), 1e-6, "small windows keep the text's least room");
		assertEquals(MenuStyle.MIN_FACTOR, MenuStyle.factorFor(1366, 768, 0.5F), 1e-6);
	}

	@Test
	void textIsTwiceMinecraftsFontAtLeastAndGrowsWithTheWindow() {
		assertEquals(2, MenuStyle.pixelScaleFor(MenuStyle.factorFor(1280, 720, 1)));
		assertEquals(2, MenuStyle.pixelScaleFor(MenuStyle.factorFor(1920, 1080, 1)));
		assertEquals(2, MenuStyle.pixelScaleFor(MenuStyle.factorFor(2560, 1440, 1)));
		assertEquals(4, MenuStyle.pixelScaleFor(MenuStyle.factorFor(3840, 2160, 1)), "4K: as 2x at 1080p");
		assertEquals(5, MenuStyle.pixelScaleFor(MenuStyle.factorFor(5120, 2880, 1)));
		assertEquals(7, MenuStyle.pixelScaleFor(MenuStyle.factorFor(7680, 4320, 1)));
		for (float f = MenuStyle.MIN_FACTOR; f <= MenuStyle.MAX_FACTOR; f += 0.01F) {
			int p = MenuStyle.pixelScaleFor(f);
			assertTrue(p == 2 || p / 2.0F / (f * 1.8F) <= 1.1F + 1e-4F, "at " + f + " the text never outgrows the layout by more than a tenth");
		}
	}

	@Test
	void onSmallWindowsTheTextTakesMoreRoomThanTheLayoutGivesIt() {
		assertEquals(1.29F, MenuStyle.textRoomFor(MenuStyle.factorFor(1280, 720, 1)), 0.01F, "2x text in a layout at 0.43");
		assertEquals(1.29F, MenuStyle.textRoomFor(MenuStyle.factorFor(1366, 768, 1)), 0.01F);
		assertEquals(1.03F, MenuStyle.textRoomFor(MenuStyle.factorFor(1920, 1080, 1)), 0.01F, "1080p is Dawn's own size");
		assertEquals(1F, MenuStyle.textRoomFor(MenuStyle.factorFor(2560, 1440, 1)), 1e-6, "never less than the layout's");
		assertEquals(1.03F, MenuStyle.textRoomFor(MenuStyle.factorFor(3840, 2160, 1)), 0.01F, "4K: 4x text in a layout at 1.08");
		// The mods grid: four cards across at 1080p and up, three where the window is small and the text relatively large.
		assertEquals(4, gridColumns(1920, 1080));
		assertEquals(4, gridColumns(2560, 1440));
		assertEquals(4, gridColumns(3840, 2160));
		assertEquals(3, gridColumns(1280, 720));
		assertEquals(3, gridColumns(1024, 768));
		assertEquals(3, gridColumns(854, 480));
	}

	/** The mods grid's columns in a window of {@code w × h}, laid out as ModMenuWindow and ModsPage do (in window pixels). */
	private static int gridColumns(int w, int h) {
		float f = MenuStyle.factorFor(w, h, 1);
		float d = 1.8F * f;
		float win = Math.min(w - 2 * 24 * d, 1304 * d);
		return MenuMath.columns(win - 2 * 24 * d, 12 * d, 250 * d * MenuStyle.textRoomFor(f), 4);
	}

	@Test
	void theGridDropsAColumnRatherThanCrowdItsCards() {
		assertEquals(4, MenuMath.columns(1256, 12, 250, 4), "Dawn's window: four of 305");
		assertEquals(3, MenuMath.columns(1000, 12, 250, 4));
		assertEquals(2, MenuMath.columns(600, 12, 250, 4));
		assertEquals(1, MenuMath.columns(300, 12, 250, 4));
		assertEquals(1, MenuMath.columns(100, 12, 250, 4), "never none");
		assertEquals(2, MenuMath.columns(1256, 12, 440, 2), "compact rows: two");
		assertEquals(1, MenuMath.columns(800, 12, 440, 2));
		for (float inner = 100; inner < 4000; inner += 37) {
			int cols = MenuMath.columns(inner, 12, 250, 4);
			assertTrue(cols == 1 || (inner - (cols - 1) * 12) / cols >= 250, inner + " gives " + cols);
		}
	}

	@Test
	void menuSizeHasThreeStepsLargeIsTheOldSizeAndMediumTheDefault() {
		MenuSize[] sizes = MenuSize.values();
		assertEquals(3, sizes.length);
		assertEquals(MenuSize.MEDIUM, GameOptions.MENU_SIZE.defaultValue(), "a bit smaller than before by default");
		assertEquals(1F, MenuSize.LARGE.zoom(), 1e-6, "Large is the size before 1.0.4");
		assertEquals(0.54F, MenuStyle.factorFor(1920, 1080, MenuSize.LARGE.zoom()), 1e-6);
		assertEquals(0.49F, MenuStyle.factorFor(1920, 1080, MenuSize.MEDIUM.zoom()), 1e-6);
		assertEquals(MenuStyle.MIN_FACTOR, MenuStyle.factorFor(1920, 1080, MenuSize.SMALL.zoom()), 1e-6);
		for (int[] size : new int[][] {{854, 480}, {1280, 720}, {1920, 1080}, {2560, 1440}, {3840, 2160}}) {
			float last = 0;
			for (MenuSize s : sizes) {
				float f = MenuStyle.factorFor(size[0], size[1], s.zoom());
				assertTrue(f >= last && f >= MenuStyle.MIN_FACTOR && f <= MenuStyle.MAX_FACTOR, size[0] + "x" + size[1] + " " + s + ": " + f);
				// The text's room never runs out: at most the 1280 × 720 minimum's.
				assertTrue(MenuStyle.textRoomFor(f) <= MenuStyle.textRoomFor(MenuStyle.MIN_FACTOR) + 1e-4F, size[0] + "x" + size[1] + " " + s);
				last = f;
			}
		}
		assertTrue(MenuStyle.factorFor(2560, 1440, MenuSize.SMALL.zoom()) < MenuStyle.factorFor(2560, 1440, MenuSize.MEDIUM.zoom())
			&& MenuStyle.factorFor(2560, 1440, MenuSize.MEDIUM.zoom()) < MenuStyle.factorFor(2560, 1440, MenuSize.LARGE.zoom()), "1440p: three sizes");
	}
}
