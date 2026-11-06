package com.flintclient.client.control.hud.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Music Player's motion (the title's slide, the eases) and its "Album art" colours. */
class MusicMotionTest {
	@Test
	void aCalmSlideStartsAndEndsGentlyAndNeverJumps() {
		float travel = 180;
		float speed = 13;
		float ramp = 0.7F;
		float total = MusicPlayerView.slideSeconds(travel, speed, ramp);
		assertEquals(travel / speed + ramp, total, 1.0E-4F);
		assertEquals(0, MusicPlayerView.slideOffset(0, travel, speed, ramp), 1.0E-4F);
		assertEquals(travel, MusicPlayerView.slideOffset(total, travel, speed, ramp), 1.0E-3F);
		// Slow at the start and at the end (half the speed's distance over the first ramp), steady in between.
		float firstStep = MusicPlayerView.slideOffset(0.05F, travel, speed, ramp);
		assertTrue(firstStep < speed * 0.05F / 2, "eases in: " + firstStep);
		float lastStep = travel - MusicPlayerView.slideOffset(total - 0.05F, travel, speed, ramp);
		assertTrue(lastStep < speed * 0.05F / 2, "eases out: " + lastStep);
		// Never backwards, never faster than the speed: no jump anywhere (60 FPS steps).
		float before = 0;
		for (float t = 0; t <= total + 0.02F; t += 1 / 60F) {
			float now = MusicPlayerView.slideOffset(t, travel, speed, ramp);
			assertTrue(now >= before - 1.0E-3F, "at " + t);
			assertTrue(now - before <= speed / 60F + 1.0E-3F, "at " + t);
			before = now;
		}
	}

	@Test
	void aSnappySlideIsTheFirstSteadyOneAndAShortCalmOneStillArrives() {
		assertEquals(20 * 1.5F, MusicPlayerView.slideOffset(1.5F, 100, 20, 0), 1.0E-4F);
		assertEquals(100, MusicPlayerView.slideOffset(9, 100, 20, 0), 1.0E-4F);
		assertEquals(5, MusicPlayerView.slideSeconds(100, 20, 0), 1.0E-4F);
		// Shorter than the two ramps: it eases the whole way and still ends in place.
		float total = MusicPlayerView.slideSeconds(4, 13, 0.7F);
		assertEquals(4, MusicPlayerView.slideOffset(total, 4, 13, 0.7F), 1.0E-3F);
		assertEquals(2, MusicPlayerView.slideOffset(total / 2, 4, 13, 0.7F), 1.0E-3F);
	}

	@Test
	void easesSettleWithoutOvershootAndOffIsAtOnce() {
		assertEquals(1, MusicPlayerView.approach(0, 1, 0.016F, 0), "Off: at once");
		float v = 0;
		for (int i = 0; i < 30; i++) {
			float next = MusicPlayerView.approach(v, 1, 1 / 60F, 0.32F);
			assertTrue(next > v && next <= 1);
			v = next;
		}
		assertTrue(v > 0.7F && v < 1, "about half a second in: " + v);
		assertEquals(0, MusicPlayerView.ease(-1));
		assertEquals(1, MusicPlayerView.ease(2));
		assertEquals(0.5F, MusicPlayerView.ease(0.5F), 1.0E-6F);
	}

	@Test
	void albumArtGivesItsOwnCalmColourAndAGreyCoverGivesNone() {
		int side = 32;
		int[] orange = new int[side * side];
		Arrays.fill(orange, 0xFFE7962F);
		int accent = MusicPlayerView.accentOf(orange);
		int r = accent >> 16 & 255;
		int g = accent >> 8 & 255;
		int b = accent & 255;
		assertTrue(r > g && g > b, "still orange: " + Integer.toHexString(accent));
		assertTrue(Math.max(r, Math.max(g, b)) >= 220, "bright enough to read on a dark panel");
		assertTrue(b > 0x2F, "calmer than the pure colour: " + Integer.toHexString(accent));

		int[] grey = new int[side * side];
		Arrays.fill(grey, 0xFF808080);
		assertEquals(-1, MusicPlayerView.accentOf(grey), "a black and white cover: the style's accent instead");

		// Mostly dark grey with a blue band: the blue is what the eye picks, and what the bar gets.
		int[] band = new int[side * side];
		Arrays.fill(band, 0xFF202020);
		Arrays.fill(band, 0, side * 6, 0xFF2F6FE7);
		int blue = MusicPlayerView.accentOf(band);
		assertTrue((blue & 255) > (blue >> 16 & 255), "blue: " + Integer.toHexString(blue));

		// Transparent corners don't count.
		int[] clear = new int[side * side];
		assertEquals(-1, MusicPlayerView.accentOf(clear));
	}
}
