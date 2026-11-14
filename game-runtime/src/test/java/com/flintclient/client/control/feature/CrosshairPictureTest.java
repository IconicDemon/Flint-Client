package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.model.ModuleRegistry;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.PlayerMods.Crosshair;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** The crosshair picture the menu's preview (and 1.8.9's crosshair) is drawn from, and the drawing pad's strokes. */
class CrosshairPictureTest {
	private static ModuleState crosshair() {
		ModuleState s = new ModuleState(ModuleRegistry.get(Crosshair.ID));
		s.set(Crosshair.INVERT, false);
		return s;
	}

	private static int[] plain(int w, int h, int argb) {
		int[] out = new int[w * h];
		Arrays.fill(out, argb);
		return out;
	}

	@Test
	void theDefaultCrossHasFourArmsAndAnEmptyMiddle() {
		ModuleState s = crosshair();
		CrosshairPicture p = new CrosshairPicture(40, 40);
		p.draw(s, 2, plain(40, 40, 0xFF336699), new CrosshairDrawing(), 0);
		// Gap 2 and arms 3 long at GUI scale 2: the arms reach from 4 to 10 pixels out.
		assertEquals(0xFFFFFFFF, p.at(20, 20 - 6), "the top arm, white");
		assertEquals(0xFFFFFFFF, p.at(20 + 6, 20));
		assertEquals(0, p.at(20, 20), "nothing in the gap");
		assertEquals(0, p.at(20, 20 - 12), "nothing past the arm");
	}

	@Test
	void adaptiveColourInvertsWhatIsBehind() {
		ModuleState s = crosshair();
		s.set(Crosshair.INVERT, true);
		CrosshairPicture p = new CrosshairPicture(40, 40);
		p.draw(s, 2, plain(40, 40, 0xFF336699), new CrosshairDrawing(), 0);
		assertEquals(0xFFCC9966, p.at(20, 14));
		CrosshairPicture marked = new CrosshairPicture(40, 40);
		marked.drawAt(s, 2, null, new CrosshairDrawing(), 0, 20, 20, 20, 20);
		assertTrue(marked.inverted(20, 14), "with no background, the pixel is marked for a renderer that inverts");
	}

	@Test
	void scaleAndGuiScaleGrowItAndOffsetMovesIt() {
		ModuleState s = crosshair();
		CrosshairPicture small = new CrosshairPicture(80, 80);
		small.draw(s, 2, plain(80, 80, 0xFF000000), new CrosshairDrawing(), 0);
		s.set(Crosshair.SCALE, 200);
		CrosshairPicture big = new CrosshairPicture(80, 80);
		big.draw(s, 2, plain(80, 80, 0xFF000000), new CrosshairDrawing(), 0);
		assertEquals(0, small.at(40, 40 - 18));
		assertNotEquals(0, big.at(40, 40 - 18), "twice the size reaches twice as far");
		s.set(Crosshair.SCALE, 100);
		s.set(Crosshair.OFFSET_X, 5);
		CrosshairPicture moved = new CrosshairPicture(80, 80);
		moved.draw(s, 2, plain(80, 80, 0xFF000000), new CrosshairDrawing(), 0);
		assertNotEquals(0, moved.at(40 + 10, 40 - 6), "5 GUI units right is 10 pixels at scale 2");
	}

	@Test
	void theDrawnStyleIsTheDrawingOneGuiUnitAPixel() {
		ModuleState s = crosshair();
		s.set(Crosshair.TYPE, 7);
		CrosshairDrawing drawing = new CrosshairDrawing();
		int mid = drawing.width() / 2;
		assertTrue(drawing.line(mid, mid - 5, mid, mid + 5, true));
		assertEquals(11, drawing.count());
		CrosshairPicture p = new CrosshairPicture(120, 120);
		p.draw(s, 2, plain(120, 120, 0xFF000000), drawing, 0);
		assertNotEquals(0, p.at(60, 60 - 9));
		assertEquals(0, p.at(60 + 4, 60));
		assertNotNull(p.touched());
		drawing.clear();
		p.draw(s, 2, plain(120, 120, 0xFF000000), drawing, 0);
		assertEquals(0, p.at(60, 60 - 9), "a cleared drawing leaves nothing behind");
	}

	@Test
	void aStrokeFillsEverySquareItPassesAndErasing() {
		CrosshairDrawing d = new CrosshairDrawing();
		assertTrue(d.line(0, 0, 10, 4, true));
		assertTrue(d.at(0, 0));
		assertTrue(d.at(10, 4));
		assertEquals(11, d.count(), "no gaps along a fast stroke");
		int revision = d.revision();
		assertFalse(d.line(0, 0, 10, 4, true), "drawing over drawn squares changes nothing");
		assertEquals(revision, d.revision());
		assertTrue(d.line(0, 0, 10, 4, false));
		assertEquals(0, d.count());
		assertFalse(d.set(-1, 3, true), "off the grid");
	}

	@Test
	void theSceneIsAnOpaqueDayAndTheSameEachTime() {
		int[] a = CrosshairPicture.scene(96, 48);
		assertTrue(Arrays.equals(a, CrosshairPicture.scene(96, 48)));
		for (int argb : a) {
			assertEquals(0xFF, argb >>> 24);
		}
		assertNotEquals(a[0], a[a.length - 1], "sky above, ground below");
	}

	@Test
	void theBackdropsArePlainOpaqueAndDarkerAtTheEdges() {
		for (boolean light : new boolean[] {false, true}) {
			int[] b = CrosshairPicture.backdrop(64, 32, light);
			for (int argb : b) {
				assertEquals(0xFF, argb >>> 24);
			}
			assertTrue((b[0] & 0xFF) < (b[16 * 64 + 20] & 0xFF), "a soft vignette");
		}
		assertTrue((CrosshairPicture.backdrop(8, 8, true)[9] & 0xFF) > (CrosshairPicture.backdrop(8, 8, false)[9] & 0xFF));
	}
}
