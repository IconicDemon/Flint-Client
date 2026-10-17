package com.flintclient.client.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MaskAtlasFramingTest {
	@Test
	void aResumedFrameBatchesUntilItsOwnEnd() {
		MaskAtlas atlas = MaskAtlas.get();
		MaskAtlas.useBatching(true);
		try {
			// The HUD ended its part (nothing pending), then a screen resumes the same frame.
			atlas.endFrame();
			assertFalse(atlas.framing());
			atlas.resumeFrame();
			assertTrue(atlas.framing(), "new shapes wait for the screen's end");
			atlas.endFrame();
			assertFalse(atlas.framing(), "the screen's end uploads them");
			atlas.endFrame();
			assertFalse(atlas.framing(), "ending twice is harmless");
		} finally {
			MaskAtlas.useBatching(false);
			atlas.endFrame();
		}
	}

	@Test
	void withoutBatchingResumingDoesNothing() {
		MaskAtlas atlas = MaskAtlas.get();
		atlas.resumeFrame();
		assertFalse(atlas.framing(), "shapes upload straight away");
	}
}
