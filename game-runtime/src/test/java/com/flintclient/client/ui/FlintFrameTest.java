package com.flintclient.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FlintFrameTest {
	/** Records what the gate asks for, in order. */
	private static final class Recorder implements FlintFrame.Work {
		final List<String> calls = new ArrayList<>();

		@Override
		public void start() {
			calls.add("start");
		}

		@Override
		public void resume() {
			calls.add("resume");
		}

		@Override
		public void end() {
			calls.add("end");
		}
	}

	@Test
	void theHudAndAScreenInOneFrameShareIt() {
		Object graphics = new Object();
		assertFalse(FlintFrame.isNewFrame(7, graphics, 7, graphics));
	}

	@Test
	void aNewCounterOrANewExtractorStartsAFrame() {
		Object graphics = new Object();
		assertTrue(FlintFrame.isNewFrame(8, graphics, 7, graphics), "the frame counter moved on");
		assertTrue(FlintFrame.isNewFrame(7, new Object(), 7, graphics), "a frame extracted without a frame-time sample");
		assertTrue(FlintFrame.isNewFrame(0, graphics, -1, null), "the first frame");
	}

	@Test
	void aScreenAfterTheHudResumesTheFrameInsteadOfStartingAnother() {
		Recorder work = new Recorder();
		FlintFrame.Gate gate = new FlintFrame.Gate(work);
		Object frame1 = new Object();
		// The HUD draws first and uploads its shapes, then the screen draws over it in the same frame.
		gate.begin(1, frame1);
		gate.end();
		gate.begin(1, frame1);
		gate.end();
		// Motion ticks and the atlas frees last frame's textures once; the screen only resumes batching.
		assertEquals(List.of("start", "end", "resume", "end"), work.calls);

		work.calls.clear();
		Object frame2 = new Object();
		gate.begin(2, frame2);
		gate.end();
		assertEquals(List.of("start", "end"), work.calls, "the next frame starts afresh");

		work.calls.clear();
		gate.begin(2, new Object());
		assertEquals(List.of("start"), work.calls, "a panorama capture's extract (no frame sample) is its own frame");
	}
}
