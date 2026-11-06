package com.flintclient.client.control.rshift;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.flintclient.client.control.feature.CapePng;
import org.junit.jupiter.api.Test;

/** What the Friends and Cosmetics windows work out before drawing: status words, times, error words, a cape's front. */
class SocialPartsTest {
	@Test
	void aFriendsStatusLineSaysWhatTheyAreDoing() {
		long now = 10_000_000_000L;
		assertEquals("In game", SocialParts.statusLine("online", true, 0, now));
		assertEquals("Busy", SocialParts.statusLine("busy", false, 0, now));
		assertEquals("Offline", SocialParts.statusLine("offline", false, 0, now));
		assertEquals("Offline · just now", SocialParts.statusLine("offline", false, now - 20_000, now));
		assertEquals("Offline · 5 min ago", SocialParts.statusLine("offline", false, now - 5 * 60_000, now));
		assertEquals("Offline · 2 h ago", SocialParts.statusLine("offline", false, now - 2 * 3_600_000, now));
		assertEquals("Offline · 3 d ago", SocialParts.statusLine("offline", false, now - 3 * 86_400_000L, now));
		assertEquals("Invisible", SocialParts.statusWord("invisible"));
	}

	@Test
	void flintsCodesAreTheAppsWordsAndAnythingElseIsCalm() {
		assertEquals("That player isn't on Flint yet. They need to open Flint once before you can add them.", SocialParts.errorText("not_on_flint"));
		assertEquals("That didn't work. Try again.", SocialParts.errorText("weird_new_code"));
		assertEquals("That didn't work. Try again.", SocialParts.errorText(null));
	}

	@Test
	void aCapesFrontIsCutAtItsOwnScale() {
		// A 64 × 32 cape whose pixels say where they are (x + 100 * y).
		int[] argb = new int[64 * 32];
		for (int y = 0; y < 32; y++) {
			for (int x = 0; x < 64; x++) {
				argb[y * 64 + x] = x + 100 * y;
			}
		}
		int[] size = new int[2];
		int[] front = FlintCosmeticsScreen.capeFront(new CapePng.Image(64, 32, argb), size);
		assertArrayEquals(new int[] {10, 16}, size);
		assertEquals(1 + 100, front[0], "starts one pixel in from the top left");
		assertEquals(10 + 100 * 16, front[front.length - 1]);
		// An HD cape (16 pixels per unit) keeps all of its detail.
		int[] hd = FlintCosmeticsScreen.capeFront(new CapePng.Image(1024, 512, new int[1024 * 512]), size);
		assertArrayEquals(new int[] {160, 256}, size);
		assertEquals(160 * 256, hd.length);
	}
}
