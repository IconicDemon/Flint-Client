package com.flintclient.client.control.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.ui.Motion;
import org.junit.jupiter.api.Test;

class OpenStateTest {
	private static final float FRAME = 1.0F / 60.0F;

	@Test
	void startsClosedAndInvisible() {
		OpenState state = new OpenState();
		assertEquals(OpenState.Phase.CLOSED, state.phase());
		assertEquals(0.0F, state.alpha());
		assertEquals(0.0F, state.progress());
		assertFalse(state.acceptsInput());
		assertFalse(state.advance(FRAME), "nothing to report");
	}

	@Test
	void opensIn180MillisecondsRisingIntoPlace() {
		OpenState state = new OpenState();
		state.open(false);
		assertEquals(OpenState.Phase.OPENING, state.phase());
		assertEquals(0.0F, state.alpha());
		assertEquals(8.0F, state.offsetY(), "starts 8 units low");
		assertTrue(state.acceptsInput(), "input works while opening");
		state.advance(0.09F);
		assertEquals(0.5F, state.progress(), 1e-4F);
		assertEquals(1 - 0.125F, state.alpha(), 1e-4F, "ease-out cubic at the halfway point");
		assertEquals(8.0F * 0.125F, state.offsetY(), 1e-4F);
		assertFalse(state.advance(0.0899F));
		assertEquals(OpenState.Phase.OPENING, state.phase(), "not quite 180 ms yet");
		assertFalse(state.advance(0.0002F));
		assertEquals(OpenState.Phase.OPEN, state.phase());
		assertEquals(1.0F, state.alpha());
		assertEquals(0.0F, state.offsetY());
		assertEquals(1.0F, state.progress());
	}

	@Test
	void openingIsMonotonicAtAnyFrameRate() {
		for (float frame : new float[] {1 / 30F, 1 / 60F, 1 / 144F, 1 / 240F}) {
			OpenState state = new OpenState();
			state.open(false);
			float lastAlpha = 0;
			float lastOffset = 8;
			int frames = 0;
			while (state.phase() == OpenState.Phase.OPENING) {
				state.advance(frame);
				assertTrue(state.alpha() >= lastAlpha && state.offsetY() <= lastOffset);
				lastAlpha = state.alpha();
				lastOffset = state.offsetY();
				frames++;
			}
			assertEquals(Math.ceil(OpenState.OPEN_SECONDS / frame), frames, 1, "takes 180 ms whatever the frame rate");
		}
	}

	@Test
	void closesIn110MillisecondsAndReportsTheEndOnce() {
		OpenState state = new OpenState();
		state.open(true);
		state.close(false);
		assertEquals(OpenState.Phase.CLOSING, state.phase());
		assertFalse(state.acceptsInput(), "input is ignored while closing");
		assertEquals(1.0F, state.alpha());
		assertEquals(0.0F, state.offsetY());
		assertFalse(state.advance(0.1F));
		assertTrue(state.offsetY() > 3.9F && state.offsetY() <= 4.0F, "sinks towards 4 units");
		assertTrue(state.alpha() < 0.01F);
		assertTrue(state.advance(0.011F), "done at 110 ms");
		assertEquals(OpenState.Phase.CLOSED, state.phase());
		assertFalse(state.advance(FRAME), "reported only once");
	}

	@Test
	void closingBeforeOpeningStillReportsTheEnd() {
		OpenState state = new OpenState();
		state.close(false);
		assertTrue(state.advance(FRAME), "so the screen still goes away");
		assertFalse(state.advance(FRAME), "once");
		state.open(false);
		state.close(true);
		state.close(false);
		assertTrue(state.advance(FRAME));
		assertFalse(state.advance(FRAME), "two closes, one report");
	}

	@Test
	void reducedMotionIsInstant() {
		OpenState state = new OpenState();
		state.open(true);
		assertEquals(OpenState.Phase.OPEN, state.phase());
		assertEquals(1.0F, state.alpha());
		assertEquals(0.0F, state.offsetY());
		state.close(true);
		assertEquals(OpenState.Phase.CLOSED, state.phase());
		assertTrue(state.advance(0), "the next frame reports the end, even with no time passed");
		assertFalse(state.advance(0));
	}

	@Test
	void instantCloseInterruptsAClosingAnimation() {
		OpenState state = new OpenState();
		state.open(true);
		state.close(false);
		state.advance(0.03F);
		state.close(true);
		assertTrue(state.advance(0));
	}

	@Test
	void reversingKeepsTheCurrentOpacity() {
		OpenState state = new OpenState();
		state.open(false);
		state.advance(0.05F);
		float alpha = state.alpha();
		state.close(false);
		assertEquals(alpha, state.alpha(), 1e-3F, "closing mid-open fades out from where it was");
		state.advance(0.02F);
		float closingAlpha = state.alpha();
		state.open(false);
		assertEquals(OpenState.Phase.OPENING, state.phase());
		assertEquals(closingAlpha, state.alpha(), 1e-3F, "reopening mid-close fades in from where it was");
	}

	@Test
	void reversingKeepsThePositionToo() {
		OpenState state = new OpenState();
		state.open(false);
		state.advance(0.05F);
		float offset = state.offsetY();
		assertTrue(offset > 1 && offset < 7);
		state.close(false);
		assertEquals(offset, state.offsetY(), 1e-4F, "closing mid-open starts where the window was");
		float last = state.offsetY();
		while (state.phase() == OpenState.Phase.CLOSING) {
			state.advance(FRAME / 4);
			if (state.phase() == OpenState.Phase.CLOSING) {
				assertTrue(state.offsetY() >= last - 1e-5F, "and keeps sinking");
				assertTrue(state.offsetY() - last < 0.5F, "smoothly");
				last = state.offsetY();
			}
		}
		assertTrue(last > 3.9F, "ending where a normal close ends");

		state.open(true);
		state.close(false);
		state.advance(0.05F);
		offset = state.offsetY();
		assertTrue(offset > 1 && offset < 4);
		state.open(false);
		assertEquals(offset, state.offsetY(), 1e-4F, "reopening mid-close starts where the window was");
		last = state.offsetY();
		while (state.phase() == OpenState.Phase.OPENING) {
			state.advance(FRAME / 4);
			assertTrue(state.offsetY() <= last + 1e-5F, "and keeps rising");
			last = state.offsetY();
		}
		assertEquals(0.0F, state.offsetY());
	}

	@Test
	void openingTwiceDoesNotRestart() {
		OpenState state = new OpenState();
		state.open(false);
		state.advance(0.1F);
		float alpha = state.alpha();
		state.open(false);
		assertEquals(alpha, state.alpha());
	}

	@Test
	void badFrameTimesAreIgnored() {
		OpenState state = new OpenState();
		state.open(false);
		state.advance(Float.NaN);
		state.advance(-1);
		state.advance(Float.POSITIVE_INFINITY);
		assertEquals(0.0F, state.progress());
	}

	@Test
	void easeOutMatchesMotion() {
		assertEquals(0.0F, OpenState.easeOut(0));
		assertEquals(1.0F, OpenState.easeOut(1));
		assertEquals(1.0F, OpenState.easeOut(3));
		assertEquals(0.875F, OpenState.easeOut(0.5F), 1e-6F);
		for (int i = -10; i <= 110; i++) {
			assertEquals(Motion.easeOut(i / 100.0F), OpenState.easeOut(i / 100.0F), "the same curve as the rest of Flint at " + i + "%");
		}
	}
}
