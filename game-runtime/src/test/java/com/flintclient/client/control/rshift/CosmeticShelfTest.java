package com.flintclient.client.control.rshift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.feature.AnimatedCapeFixture;
import com.flintclient.client.control.feature.CapeFrames;
import com.flintclient.client.control.store.CapeAnimation;
import com.flintclient.client.control.store.StoreEndpoint;
import com.google.gson.JsonParser;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** An animated cape's reel: its scale, its layout (frames side by side) and where each frame's front lands in it. */
class CosmeticShelfTest {
	private static CapeAnimation animation(int scale, int frames) {
		int[] durations = new int[frames];
		Arrays.fill(durations, 50);
		return CapeAnimation.parse(JsonParser.parseString(AnimatedCapeFixture.animationJson(scale, durations,
			AnimatedCapeFixture.strips(scale, frames), AnimatedCapeFixture.BUCKET)), StoreEndpoint.defaults());
	}

	@Test
	void reelsKeepTheirScaleWithinOneCapesBudget() {
		// 8 frames at 16 px per unit: 1.25 MiB, kept as they are; HD capped at 16; 120 frames at 16 would take 19 MiB: halved.
		assertEquals(16, CosmeticShelf.reelScale(animation(16, 8)));
		assertEquals(16, CosmeticShelf.reelScale(animation(32, 8)));
		assertEquals(8, CosmeticShelf.reelScale(animation(16, 120)));
	}

	@Test
	void framesSitSideBySideWithinTheLargestTexture() {
		CosmeticShelf.Reel eight = new CosmeticShelf.Reel("k", animation(16, 8), 16);
		assertEquals(8, eight.cols);
		assertEquals(8 * 160, eight.width);
		assertEquals(256, eight.height);
		assertEquals(8L * 160 * 256 * 4, eight.bytes);
		CosmeticShelf.Reel long8 = new CosmeticShelf.Reel("k", animation(16, 120), 8);
		assertEquals(4096 / 80, long8.cols);
		assertEquals(3 * 128, long8.height);
		assertTrue(long8.width <= CosmeticShelf.MAX_SIDE && long8.bytes <= CosmeticShelf.MOTION_BYTES);
		// Frame 51 starts the second row.
		assertEquals(0, long8.u(51));
		assertEquals(128, long8.v(51));
		assertEquals(80 * 3, long8.u(54));
	}

	@Test
	void eachFramesFrontLandsInItsPlace() {
		int scale = 4;
		CosmeticShelf.Reel reel = new CosmeticShelf.Reel("k", animation(scale, 5), scale);
		int[] out = new int[reel.width * reel.height];
		int frameRow = CapeAnimation.ATLAS_WIDTH * scale;
		for (int f = 0; f < 5; f++) {
			int[] frame = AnimatedCapeFixture.frame(f, 5, scale);
			CosmeticShelf.place(frame, reel, f, out);
		}
		for (int f = 0; f < 5; f++) {
			int[] frame = AnimatedCapeFixture.frame(f, 5, scale);
			for (int y = 0; y < CapeFrames.FRONT_HEIGHT * scale; y++) {
				for (int x = 0; x < CapeFrames.FRONT_WIDTH * scale; x++) {
					// The front starts one unit in, one unit down (CapeFrames.front).
					assertEquals(frame[(scale + y) * frameRow + scale + x], out[(reel.v(f) + y) * reel.width + reel.u(f) + x], "frame " + f);
				}
			}
		}
	}
}
