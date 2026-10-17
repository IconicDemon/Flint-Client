package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.hud.TextOut;
import org.junit.jupiter.api.Test;

/** Toggle sprint/sneak, zoom easing, freelook, chat stacking and the HUD's text writer. */
class FeatureLogicTest {
	@Test
	void aTapLatchesAToggleAndTheNextTapReleasesIt() {
		MovementToggles m = new MovementToggles();
		m.tick(true, false, true, true);
		assertTrue(m.holdSprint(), "press edge: on");
		m.tick(true, false, true, true);
		assertTrue(m.holdSprint(), "held: still on, no flicker");
		m.tick(false, false, true, true);
		m.tick(true, false, true, true);
		assertFalse(m.holdSprint(), "second tap: off");
		m.tick(false, true, true, true);
		assertTrue(m.holdSneak());
		m.tick(false, true, true, false);
		assertFalse(m.holdSneak(), "the toggle turned off lets go");
		m.tick(true, false, false, false);
		assertFalse(m.holdSprint(), "with the toggle off a press latches nothing");
	}

	@Test
	void zoomEasesToItsLevelAndBackExactly() {
		ZoomState z = new ZoomState();
		long ms = 1_000_000L;
		assertEquals(1, z.factor(0, 160 * ms, ZoomState.Easing.OUT_QUINTIC));
		z.set(true, 4, 0);
		assertEquals(4, z.factor(160 * ms, 160 * ms, ZoomState.Easing.OUT_QUINTIC), 1e-4);
		float mid = z.factor(80 * ms, 160 * ms, ZoomState.Easing.LINEAR);
		assertEquals(2, mid, 1e-3, "eased in log space: halfway is the geometric middle");
		assertTrue(z.scroll(1, 200 * ms));
		assertEquals(5, z.factor(400 * ms, 0, ZoomState.Easing.LINEAR), 1e-4, "one notch: 1.25 times");
		z.set(false, 4, 500 * ms);
		assertEquals(1, z.factor(700 * ms, 160 * ms, ZoomState.Easing.OUT_EXPONENTIAL), 1e-4, "back to exactly 1");
		assertFalse(z.scroll(1, 800 * ms), "no scrolling when not zoomed");
		z.set(true, 4, 900 * ms);
		assertEquals(4, z.factor(2000 * ms, 0, ZoomState.Easing.LINEAR), 1e-4, "the scroll is forgotten after a zoom ends");
		for (int i = 0; i < 40; i++) {
			z.scroll(1, 3000 * ms);
		}
		assertEquals(ZoomState.MAX, z.factor(4000 * ms, 0, ZoomState.Easing.LINEAR), 1e-3);
		for (ZoomState.Easing e : ZoomState.Easing.values()) {
			assertEquals(0, e.apply(0), 1e-6, e.name());
			assertEquals(1, e.apply(1), 1e-6, e.name());
		}
	}

	@Test
	void freelookTurnsItsOwnAnglesWithinVanillasPitchLimits() {
		FreelookState f = new FreelookState();
		f.start(90, 10);
		f.turn(100, 0, false, false);
		assertEquals(105, f.yaw(), 1e-4);
		f.turn(0, 1000, false, true);
		assertEquals(-90, f.pitch(), 1e-4, "clamped");
		f.stop();
		assertFalse(f.active());
	}

	@Test
	void repeatedChatMessagesStack() {
		ChatStack s = new ChatStack();
		Object first = new Object();
		assertNull(s.accept("hi", true));
		s.shown("hi", first);
		assertSame(first, s.accept("hi", true));
		assertEquals(2, s.count());
		Object second = new Object();
		s.shown("hi", second);
		assertNull(s.accept("other", true));
		s.shown("other", new Object());
		assertNull(s.accept("hi", true), "consecutive only: an older copy doesn't stack");
		s.shown("hi", new Object());
		assertNull(s.accept("x", false));
		s.shown("x", new Object());
		assertEquals(1, s.count());
		s.accept("other", false);
		assertEquals(2, s.count(), "any recent copy stacks when not consecutive-only");
	}

	@Test
	void textOutWritesNumbersWithoutAllocating() {
		char[] buffer = new char[32];
		TextOut o = new TextOut().start(buffer);
		o.text("Yaw ").fixed(-12.345, 1).text(" ").two(7).ch(':').two(42).text(" ").integer(1200);
		assertEquals("Yaw -12.3 07:42 1200", new String(buffer, 0, o.length()));
		o.start(buffer).fixed(Double.NaN, 2);
		assertEquals("—", new String(buffer, 0, o.length()));
		o.start(new char[4]).text("overflowing");
		assertEquals(4, o.length(), "never writes past the buffer");
	}
}
