package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.flintclient.client.control.store.CapeAnimation;
import com.flintclient.client.control.store.StoreEndpoint;
import com.google.gson.JsonParser;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class CapeFramesTest {
	private static final long MIB = 1024 * 1024;

	private static CapeAnimation animation(int scale, int frames) {
		int[] durations = new int[frames];
		Arrays.fill(durations, 100);
		List<byte[]> strips = new java.util.ArrayList<>();
		int per = CapeAnimation.framesPerStrip(17 * scale);
		for (int first = 0; first < frames; first += per) {
			strips.add(new byte[] {(byte) first});
		}
		return CapeAnimation.parse(JsonParser.parseString(AnimatedCapeFixture.animationJson(scale, durations, strips, AnimatedCapeFixture.BUCKET)),
			StoreEndpoint.defaults());
	}

	@Test
	void framesAreKeptAtTheirScaleUpTo16AndWithinTheCapesBudget() {
		assertEquals(16, CapeFrames.keptScale(animation(16, 10), CapeFrames.MAX_BYTES), "a standard cape at full size");
		assertEquals(16, CapeFrames.keptScale(animation(16, 65), CapeFrames.MAX_BYTES), "65 standard frames take 23.7 MiB");
		assertEquals(8, CapeFrames.keptScale(animation(16, 66), CapeFrames.MAX_BYTES), "66 don't fit 24 MiB: halved");
		assertEquals(8, CapeFrames.keptScale(animation(16, 120), CapeFrames.MAX_BYTES));
		assertEquals(16, CapeFrames.keptScale(animation(32, 8), CapeFrames.MAX_BYTES), "HD is drawn at 16 px per unit, like its poster");
		assertEquals(8, CapeFrames.keptScale(animation(32, 120), CapeFrames.MAX_BYTES));
		assertEquals(4, CapeFrames.keptScale(animation(4, 120), CapeFrames.MAX_BYTES), "never above the animation's own scale");
		assertEquals(8, CapeFrames.keptScale(animation(8, 2), CapeFrames.MAX_BYTES));
		assertEquals(4, CapeFrames.keptScale(animation(16, 120), 3 * MIB), "a tighter budget halves again");
		assertEquals(65L * 352 * 272 * 4, CapeFrames.bytes(65, 16));
		assertEquals(120L * 176 * 136 * 4, CapeFrames.bytes(120, 8));
		for (int scale : new int[] {4, 8, 16, 32}) {
			for (int frames = 2; frames <= 120; frames++) {
				CapeAnimation a = animation(scale, frames);
				int kept = CapeFrames.keptScale(a, CapeFrames.MAX_BYTES);
				assertEquals(true, CapeFrames.bytes(frames, kept) <= CapeFrames.MAX_BYTES, scale + "/" + frames);
				assertEquals(0, a.scale() % kept, "kept at the scale divided by a power of two");
			}
		}
	}

	@Test
	void aStripIsCutIntoItsFrames() {
		int scale = 4;
		int n = 30;
		List<byte[]> strips = AnimatedCapeFixture.strips(scale, n);
		assertEquals(1, strips.size(), "thirty 68 px frames in one 2040 px strip");
		CapePng.Image image;
		try {
			image = CapePng.decode(strips.get(0), 88, 2040);
		} catch (CapePng.Refused e) {
			throw new AssertionError(e);
		}
		int[][] out = new int[n][];
		CapeFrames.slice(image.argb(), scale, 0, n, scale, CapeFrames.Layout.ARGB, out);
		for (int f = 0; f < n; f++) {
			assertArrayEquals(AnimatedCapeFixture.frame(f, n, scale), out[f], "frame " + f);
		}
	}

	@Test
	void framesGoWhereTheirStripSays() {
		int scale = 16;
		int n = 10;
		List<byte[]> strips = AnimatedCapeFixture.strips(scale, n);
		assertEquals(2, strips.size());
		int[][] out = new int[n][];
		for (int s = 0; s < 2; s++) {
			try {
				CapePng.Image image = CapePng.decode(strips.get(s), 352, 2048);
				CapeFrames.slice(image.argb(), scale, s * 7, image.height() / 272, scale, CapeFrames.Layout.ARGB, out);
			} catch (CapePng.Refused e) {
				throw new AssertionError(e);
			}
		}
		for (int f = 0; f < n; f++) {
			assertArrayEquals(AnimatedCapeFixture.frame(f, n, scale), out[f], "frame " + f);
		}
	}

	@Test
	void downscalingAveragesByAlpha() {
		// One 2×2 block per output pixel: a 22×17-unit frame at scale 2, kept at 1.
		int w = 44;
		int h = 34;
		int[] px = new int[w * h];
		// (0,0): four opaque pixels, red 0, 100, 200, 255.
		px[0] = 0xFF000000;
		px[1] = 0xFF640000;
		px[w] = 0xFFC80000;
		px[w + 1] = 0xFFFF0000;
		// (1,0): one opaque white pixel and three fully transparent ones: white at a quarter alpha (no black fringe).
		px[2] = 0xFFFFFFFF;
		// (2,0): a half-transparent pixel and three transparent: alpha 128 / 4 = 32, its colour as it is.
		px[4] = 0x8000FF00;
		// (3,0): one pixel of alpha 1: rounds to transparent, which is transparent black.
		px[6] = 0x01FFFFFF;
		int[] frame = CapeFrames.scaled(px, w, 0, 2, CapeFrames.Layout.ARGB);
		assertEquals(22 * 17, frame.length);
		assertEquals(0xFF000000 | (0 + 100 + 200 + 255 + 2) / 4 << 16, frame[0]);
		assertEquals(0x40FFFFFF, frame[1]);
		assertEquals(0x2000FF00, frame[2]);
		assertEquals(0, frame[3]);
		assertEquals(0, frame[4], "transparent stays transparent black");
	}

	@Test
	void anHdFrameIsHalvedToItsPostersScale() {
		int[] hd = AnimatedCapeFixture.frame(3, 8, 32);
		int[] kept = CapeFrames.scaled(hd, 22 * 32, 0, 2, CapeFrames.Layout.ARGB);
		// Every unit is one colour, so halving keeps it: the same frame drawn at 16 px per unit.
		assertArrayEquals(AnimatedCapeFixture.frame(3, 8, 16), kept);
		int[] quarter = CapeFrames.scaled(hd, 22 * 32, 0, 8, CapeFrames.Layout.ARGB);
		assertArrayEquals(AnimatedCapeFixture.frame(3, 8, 4), quarter);
	}

	@Test
	void framesAreKeptInTheEnginesLayout() {
		assertEquals(0x80112233, CapeFrames.Layout.ARGB.from(0x80112233));
		int rgba = CapeFrames.Layout.RGBA_BYTES.from(0x80112233);
		java.nio.ByteBuffer bytes = java.nio.ByteBuffer.allocate(4).order(ByteOrder.nativeOrder());
		bytes.putInt(rgba);
		assertArrayEquals(new byte[] {0x11, 0x22, 0x33, (byte) 0x80}, bytes.array(), "R, G, B, A in memory");
		int[] blue = new int[22 * 17];
		Arrays.fill(blue, 0xFF0000FF);
		int[] frame = CapeFrames.scaled(blue, 22, 0, 1, CapeFrames.Layout.RGBA_BYTES);
		assertEquals(CapeFrames.Layout.RGBA_BYTES.from(0xFF0000FF), frame[0], "converted as it is copied");
	}
}
