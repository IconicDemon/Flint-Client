package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.model.ModuleRegistry;
import com.flintclient.client.control.model.ModuleSettings;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.PlayerMods.Crosshair;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Every Crosshair setting changes the picture where its style uses it (the game draws the same shapes), the rows a style doesn't
 * use say why, the presets set a shape and nothing else, and the pad's brush covers 1, 2 or 3 squares.
 */
class CrosshairSettingsTest {
	private static final int SIZE = 160;

	private static ModuleState crosshair(int type) {
		ModuleState s = new ModuleState(ModuleRegistry.get(Crosshair.ID));
		s.set(Crosshair.INVERT, false);
		s.set(Crosshair.TYPE, type);
		return s;
	}

	private static int[] picture(ModuleState s, CrosshairDrawing drawing) {
		CrosshairPicture p = new CrosshairPicture(SIZE, SIZE);
		int[] bg = new int[SIZE * SIZE];
		Arrays.fill(bg, 0xFF000000);
		p.draw(s, 2, bg, drawing, 0);
		return p.pixels().clone();
	}

	private static int count(int[] pixels) {
		int n = 0;
		for (int p : pixels) {
			n += p != 0 ? 1 : 0;
		}
		return n;
	}

	private static CrosshairDrawing plus() {
		CrosshairDrawing d = new CrosshairDrawing();
		int m = d.width() / 2;
		d.line(m - 4, m, m + 4, m, true);
		d.line(m, m - 4, m, m + 4, true);
		return d;
	}

	@Test
	void thicknessWidensEveryStyleThatHasStrokes() {
		for (int type : new int[] {1, 2, 3, 4, 5}) {
			ModuleState thin = crosshair(type);
			ModuleState thick = crosshair(type);
			thick.set(Crosshair.THICKNESS, 3);
			assertTrue(count(picture(thick, new CrosshairDrawing())) > count(picture(thin, new CrosshairDrawing())) + 8, "style " + type);
		}
	}

	@Test
	void sizeGapAndOutlineChangeWhatIsDrawn() {
		for (int type : new int[] {1, 2, 3, 4, 5}) {
			int[] base = picture(crosshair(type), new CrosshairDrawing());
			ModuleState gap = crosshair(type);
			gap.set(Crosshair.GAP, 6);
			assertFalse(Arrays.equals(base, picture(gap, new CrosshairDrawing())), "gap, style " + type);
			ModuleState outlined = crosshair(type);
			outlined.set(Crosshair.OUTLINE, true);
			assertFalse(Arrays.equals(base, picture(outlined, new CrosshairDrawing())), "outline, style " + type);
			if (type != 2) {
				ModuleState wide = crosshair(type);
				wide.set(Crosshair.WIDTH, 9);
				wide.set(Crosshair.HEIGHT, 9);
				assertFalse(Arrays.equals(base, picture(wide, new CrosshairDrawing())), "size, style " + type);
			}
		}
	}

	@Test
	void theDrawnStyleTakesItsColorOutlineScaleAndTurn() {
		CrosshairDrawing d = plus();
		int[] base = picture(crosshair(7), d);
		ModuleState outlined = crosshair(7);
		outlined.set(Crosshair.OUTLINE, true);
		assertTrue(count(picture(outlined, d)) > count(base), "an outline round the drawing");
		ModuleState bigger = crosshair(7);
		bigger.set(Crosshair.SCALE, 200);
		assertTrue(count(picture(bigger, d)) > count(base));
		ModuleState colored = crosshair(7);
		colored.set(Crosshair.COLOR, 0xFF8139);
		assertTrue(Arrays.stream(picture(colored, d)).anyMatch(p -> p == 0xFFFF8139));
	}

	@Test
	void theRainbowWinsOverAdaptiveColor() {
		ModuleState s = crosshair(1);
		s.set(Crosshair.INVERT, true);
		s.set(Crosshair.RAINBOW, true);
		Set<Integer> colors = new HashSet<>();
		for (int p : picture(s, new CrosshairDrawing())) {
			if (p != 0) {
				colors.add(p);
			}
		}
		assertFalse(colors.contains(0xFFFFFFFF), "not black inverted to white: the rainbow's colour");
	}

	@Test
	void rowsAStyleDoesntUseSayWhy() {
		ModuleState drawn = crosshair(7);
		assertNotNull(ModuleSettings.inactiveReason(drawn, Crosshair.THICKNESS));
		assertNotNull(ModuleSettings.inactiveReason(drawn, Crosshair.GAP));
		assertNull(ModuleSettings.inactiveReason(drawn, Crosshair.COLOR));
		ModuleState circle = crosshair(2);
		assertNull(ModuleSettings.inactiveReason(circle, Crosshair.THICKNESS), "a circle's ring takes the thickness");
		assertNotNull(ModuleSettings.inactiveReason(circle, Crosshair.WIDTH));
		assertNotNull(ModuleSettings.inactiveReason(circle, Crosshair.INVERT), "lines take the color");
		ModuleState cross = crosshair(1);
		assertNull(ModuleSettings.inactiveReason(cross, Crosshair.COLOR));
		cross.set(Crosshair.INVERT, true);
		assertNotNull(ModuleSettings.inactiveReason(cross, Crosshair.COLOR), "adaptive color decides");
		ModuleState vanilla = crosshair(0);
		assertNotNull(ModuleSettings.inactiveReason(vanilla, Crosshair.THICKNESS));
		assertNotNull(ModuleSettings.inactiveReason(vanilla, Crosshair.DOT));
		assertNull(ModuleSettings.inactiveReason(vanilla, Crosshair.SCALE));
	}

	private static void assertNotNull(Object o) {
		assertTrue(o != null);
	}

	private static void assertNotNull(Object o, String message) {
		assertTrue(o != null, message);
	}

	@Test
	void presetsSetTheShapeAndKeepTheRest() {
		assertTrue(CrosshairPresets.ALL.size() >= 8 && CrosshairPresets.ALL.size() <= 12);
		Set<String> names = new HashSet<>();
		Set<String> shapes = new HashSet<>();
		for (CrosshairPresets.Preset p : CrosshairPresets.ALL) {
			assertTrue(names.add(p.name()));
			ModuleState s = crosshair(1);
			s.set(Crosshair.COLOR, 0x123456);
			s.set(Crosshair.SCALE, 150);
			p.apply(s);
			assertTrue(p.matches(s), p.name());
			assertEquals(0x123456, s.rgb(Crosshair.COLOR), "the colour stays the player's");
			assertEquals(150, s.integer(Crosshair.SCALE));
			assertTrue(count(picture(s, new CrosshairDrawing())) > 0, p.name() + " draws something");
			assertTrue(shapes.add(Arrays.toString(picture(s, new CrosshairDrawing()))), p.name() + " looks like no other");
		}
		ModuleState s = crosshair(1);
		CrosshairPresets.ALL.get(0).apply(s);
		assertFalse(CrosshairPresets.ALL.get(1).matches(s));
	}

	@Test
	void theBrushCoversOneTwoOrThreeSquares() {
		CrosshairDrawing one = new CrosshairDrawing();
		one.line(10, 10, 10, 10, true, 1);
		assertEquals(1, one.count());
		CrosshairDrawing two = new CrosshairDrawing();
		two.line(10, 10, 10, 10, true, 2);
		assertEquals(4, two.count());
		CrosshairDrawing three = new CrosshairDrawing();
		three.line(10, 10, 14, 10, true, 3);
		assertEquals(3 * 7, three.count(), "a 3-wide stroke 5 squares long");
		three.line(12, 10, 12, 10, false, 3);
		assertEquals(3 * 7 - 9, three.count(), "erasing with the same brush");
		assertNotEquals(0, three.count());
	}
}
