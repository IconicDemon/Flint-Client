package com.flintclient.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class AtlasLayoutTest {
	private static long key(int n) {
		return MaskKey.of(MaskKey.ROUND_RECT, n, 0, 0, 0);
	}

	private static List<AtlasLayout.Slot> all(AtlasLayout layout) {
		List<AtlasLayout.Slot> slots = new ArrayList<>();
		layout.slots().forEach(slots::add);
		return slots;
	}

	/** Every shape inside the texture, with at least a one-pixel gap to every other. */
	private static void assertPacked(AtlasLayout layout) {
		List<AtlasLayout.Slot> slots = all(layout);
		for (AtlasLayout.Slot a : slots) {
			assertTrue(a.u >= 1 && a.v >= 1 && a.u + a.width + 1 <= layout.width() && a.v + a.height + 1 <= layout.height(),
				"inside the texture with a margin");
			for (AtlasLayout.Slot b : slots) {
				if (a != b) {
					boolean apart = a.u + a.width + 1 <= b.u || b.u + b.width + 1 <= a.u || a.v + a.height + 1 <= b.v || b.v + b.height + 1 <= a.v;
					assertTrue(apart, "shapes overlap or touch");
				}
			}
		}
	}

	@Test
	void shapesNeverOverlapAndKeepAGap() {
		AtlasLayout layout = new AtlasLayout();
		Random random = new Random(7);
		layout.nextFrame();
		for (int i = 0; i < 150; i++) {
			layout.add(key(i), 1 + random.nextInt(300), 1 + random.nextInt(90));
		}
		assertEquals(150, layout.size());
		assertPacked(layout);
	}

	@Test
	void growingNeverMovesAShape() {
		AtlasLayout layout = new AtlasLayout();
		Random random = new Random(11);
		Map<Long, int[]> placed = new HashMap<>();
		int grown = 0;
		layout.nextFrame();
		for (int i = 0; i < 200; i++) {
			AtlasLayout.Change change = layout.add(key(i), 1 + random.nextInt(i % 20 == 0 ? 900 : 120), 1 + random.nextInt(60));
			assertTrue(change != AtlasLayout.Change.REPACKED, "nothing to evict inside one frame below the cap");
			if (change == AtlasLayout.Change.GROWN) {
				grown++;
			}
			AtlasLayout.Slot slot = layout.get(key(i));
			placed.put(slot.key, new int[] {slot.u, slot.v});
			for (AtlasLayout.Slot earlier : all(layout)) {
				int[] at = placed.get(earlier.key);
				assertEquals(at[0], earlier.u);
				assertEquals(at[1], earlier.v);
			}
		}
		assertTrue(grown >= 2 && grown <= 8, "the texture is replaced a handful of times, not once per shape: " + grown);
		assertPacked(layout);
	}

	@Test
	void aFrameOfNewShapesNeedsOneUploadAndTheNextNone() {
		AtlasLayout layout = new AtlasLayout();
		layout.nextFrame();
		layout.add(key(0), 40, 40);
		layout.clean();
		layout.nextFrame();
		int replaced = 0;
		for (int i = 1; i <= 12; i++) {
			if (layout.add(key(i), 30, 20) != AtlasLayout.Change.NONE) {
				replaced++;
			}
		}
		assertEquals(0, replaced, "new shapes that fit go into the existing texture");
		assertTrue(layout.dirty());
		int top = Integer.MAX_VALUE;
		int bottom = 0;
		for (int i = 1; i <= 12; i++) {
			AtlasLayout.Slot slot = layout.get(key(i));
			top = Math.min(top, slot.v);
			bottom = Math.max(bottom, slot.v + slot.height);
		}
		assertEquals(top - 1, layout.dirtyTop(), "one upload covers exactly the rows of the new shapes and the gap rows around them");
		assertEquals(bottom + 1, layout.dirtyBottom());
		assertTrue(bottom < layout.height(), "not the whole texture");
		layout.clean();

		layout.nextFrame();
		for (int i = 0; i <= 12; i++) {
			assertNotNull(layout.get(key(i)));
		}
		assertFalse(layout.dirty(), "a frame that only draws known shapes uploads nothing");
	}

	@Test
	void everyRowInUseHasBeenUploadedGapsIncluded() {
		// A new texture's GPU memory isn't cleared, so every row in use, the one-pixel gaps between shelves
		// included, must have gone up at least once to read as transparent like the old atlas.
		AtlasLayout layout = new AtlasLayout();
		Random random = new Random(5);
		boolean[] uploaded = new boolean[0];
		for (int frame = 0; frame < 300; frame++) {
			layout.nextFrame();
			for (int i = 0; i < 3; i++) {
				if (layout.add(key(frame * 3 + i), 1 + random.nextInt(200), 1 + random.nextInt(50)) != AtlasLayout.Change.NONE) {
					uploaded = new boolean[layout.height()];
				}
			}
			for (int row = layout.dirtyTop(); row < layout.dirtyBottom(); row++) {
				uploaded[row] = true;
			}
			layout.clean();
			for (int row = 0; row < layout.usedHeight(); row++) {
				assertTrue(uploaded[row], "frame " + frame + ": row " + row + " was never uploaded");
			}
		}
	}

	@Test
	void aNewTextureUploadsEverythingInUse() {
		AtlasLayout layout = new AtlasLayout();
		layout.nextFrame();
		assertEquals(AtlasLayout.Change.GROWN, layout.add(key(0), 100, 100), "the first shape makes the texture");
		layout.clean();
		assertEquals(AtlasLayout.Change.GROWN, layout.add(key(1), 600, 20));
		assertEquals(0, layout.dirtyTop());
		assertEquals(layout.usedHeight(), layout.dirtyBottom());
		AtlasLayout.Slot wide = layout.get(key(1));
		assertTrue(wide.v + wide.height < layout.usedHeight());
	}

	@Test
	void theCacheIsCappedAndKeepsWhatWasDrawnRecently() {
		AtlasLayout layout = new AtlasLayout();
		layout.nextFrame();
		for (int i = 0; i < AtlasLayout.MAX_SHAPES; i++) {
			layout.add(key(i), 6, 4);
		}
		layout.nextFrame();
		for (int i = 0; i < 10; i++) {
			layout.get(key(i));
		}
		layout.nextFrame();
		for (int i = 0; i < 5; i++) {
			layout.get(key(i));
		}
		Map<Long, int[]> before = new HashMap<>();
		for (int i = 0; i < 10; i++) {
			AtlasLayout.Slot slot = layout.get(key(i));
			before.put(slot.key, new int[] {slot.u, slot.v});
		}
		assertEquals(AtlasLayout.Change.REPACKED, layout.add(key(10_000), 6, 4));
		assertEquals(11, layout.size(), "the ten shapes drawn this frame or the last, and the new one");
		for (int i = 0; i < 10; i++) {
			AtlasLayout.Slot slot = layout.get(key(i));
			assertEquals(before.get(slot.key)[0], slot.fromU, "where to copy its pixels from");
			assertEquals(before.get(slot.key)[1], slot.fromV);
		}
		assertEquals(-1, layout.get(key(10_000)).fromU, "the new shape has nothing to copy");
		assertNull(layout.get(key(100)), "shapes not drawn for two frames are gone");
		assertEquals(0, layout.dirtyTop());
		assertEquals(layout.usedHeight(), layout.dirtyBottom());
		assertPacked(layout);
	}

	@Test
	void shapesOfEverChangingSizeNeverGrowItPastTheLimit() {
		AtlasLayout layout = new AtlasLayout();
		int repacks = 0;
		for (int frame = 0; frame < 400; frame++) {
			layout.nextFrame();
			// The same few controls every frame, plus a tooltip at a new size (a ping time changing).
			for (int i = 0; i < 20; i++) {
				if (layout.get(key(i)) == null) {
					layout.add(key(i), 120, 30);
				}
			}
			if (layout.add(key(1000 + frame), 300 + frame % 200, 60 + frame % 50) == AtlasLayout.Change.REPACKED) {
				repacks++;
			}
			assertTrue(layout.width() <= AtlasLayout.MAX_SIZE && layout.height() <= AtlasLayout.MAX_SIZE,
				"frame " + frame + ": " + layout.width() + "x" + layout.height());
			assertTrue(layout.size() <= AtlasLayout.MAX_SHAPES);
			layout.clean();
		}
		assertTrue(repacks > 0, "old tooltips were dropped");
		for (int i = 0; i < 20; i++) {
			assertNotNull(layout.get(key(i)), "shapes in use survive every repack");
		}
		assertPacked(layout);
	}

	@Test
	void shapesDrawnTogetherAlwaysFitEvenPastTheLimit() {
		AtlasLayout layout = new AtlasLayout();
		layout.nextFrame();
		for (int i = 0; i < 30; i++) {
			layout.add(key(i), 1000, 400);
		}
		assertEquals(30, layout.size(), "nothing drawn this frame is dropped");
		assertTrue(layout.height() > AtlasLayout.MAX_SIZE);
		assertPacked(layout);
	}

	@Test
	void aShapeWiderThanTheAtlasWidensIt() {
		AtlasLayout layout = new AtlasLayout();
		layout.nextFrame();
		layout.add(key(0), 10, 10);
		assertEquals(AtlasLayout.Change.GROWN, layout.add(key(1), 1500, 40));
		assertEquals(2048, layout.width());
		assertPacked(layout);
	}

	@Test
	void clearingForgetsEverything() {
		AtlasLayout layout = new AtlasLayout();
		layout.nextFrame();
		layout.add(key(0), 10, 10);
		layout.clear();
		assertEquals(0, layout.size());
		assertNull(layout.get(key(0)));
		assertFalse(layout.dirty());
		assertEquals(AtlasLayout.Change.GROWN, layout.add(key(0), 10, 10), "a new texture after clearing");
	}
}
