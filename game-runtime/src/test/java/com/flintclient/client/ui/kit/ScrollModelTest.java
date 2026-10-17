package com.flintclient.client.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ScrollModelTest {
	@Test
	void theTargetStaysWithinTheContent() {
		ScrollModel scroll = new ScrollModel();
		scroll.setSizes(500, 200);
		assertEquals(300, scroll.maxOffset());
		scroll.scrollBy(-50);
		assertEquals(0, scroll.target());
		scroll.scrollBy(1000);
		assertEquals(300, scroll.target());
	}

	@Test
	void contentThatFitsDoesNotScroll() {
		ScrollModel scroll = new ScrollModel();
		scroll.setSizes(150, 200);
		assertFalse(scroll.scrollable());
		scroll.scrollBy(40);
		assertEquals(0, scroll.target());
		assertEquals(200, scroll.thumbSize(200, 16), "the thumb fills the track");
	}

	@Test
	void theOffsetEasesToTheTargetAndArrives() {
		ScrollModel scroll = new ScrollModel();
		scroll.setSizes(1000, 100);
		scroll.scrollBy(100);
		assertTrue(scroll.step(0.5F));
		assertEquals(50, scroll.offset());
		for (int i = 0; i < 20; i++) {
			scroll.step(0.5F);
		}
		assertEquals(100, scroll.offset());
		assertFalse(scroll.step(0.5F));
		scroll.scrollTo(300, true);
		assertEquals(300, scroll.offset());
	}

	@Test
	void shrinkingContentKeepsThePositionButClampsIt() {
		ScrollModel scroll = new ScrollModel();
		scroll.setSizes(1000, 100);
		scroll.scrollTo(400, true);
		scroll.setSizes(1200, 100);
		assertEquals(400, scroll.offset(), "kept when the content grows");
		scroll.setSizes(300, 100);
		assertEquals(200, scroll.offset());
		assertEquals(200, scroll.target());
	}

	@Test
	void ensureVisibleMovesTheLeastNeeded() {
		ScrollModel scroll = new ScrollModel();
		scroll.setSizes(1000, 100);
		scroll.ensureVisible(20, 40, 6);
		assertEquals(0, scroll.target(), "already in view");
		scroll.ensureVisible(150, 170, 6);
		assertEquals(76, scroll.target(), "its bottom (plus margin) at the bottom edge");
		scroll.ensureVisible(30, 50, 6);
		assertEquals(24, scroll.target(), "its top (minus margin) at the top edge");
		scroll.ensureVisible(500, 700, 6);
		assertEquals(494, scroll.target(), "taller than the view: its top wins");
	}

	@Test
	void theThumbTracksTheOffsetAndDraggingItInverts() {
		ScrollModel scroll = new ScrollModel();
		scroll.setSizes(400, 100);
		assertEquals(25, scroll.thumbSize(100, 16));
		scroll.scrollTo(150, true);
		assertEquals(37.5F, scroll.thumbPos(100, 16), 1e-4);
		assertEquals(150, scroll.offsetForThumb(37.5F, 100, 16), 1e-3);
		assertEquals(300, scroll.offsetForThumb(500, 100, 16), "clamped");
		scroll.setSizes(100_000, 100);
		assertEquals(16, scroll.thumbSize(100, 16), "a minimum size to grab");
	}
}
