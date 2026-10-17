package com.flintclient.client.module.screenshots;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ThumbnailsTest {
	private final Thumbnails<String> thumbnails = new Thumbnails<>();
	private final List<String> released = new ArrayList<>();
	/** Decodes requested, in order, with their tickets. */
	private final Map<Thumbnails.Want, Thumbnails.Ticket> requested = new LinkedHashMap<>();

	private void keepOnly(int from, int to, int width, int height) {
		requested.clear();
		thumbnails.keepOnly(from, to, width, height, true, released::add, requested::put);
	}

	private static Thumbnails.Want want(int index, int width, int height) {
		return new Thumbnails.Want(index, width, height, true);
	}

	@Test
	void picturesAreFoundByScreenshotIndex() {
		keepOnly(0, 3, 100, 50);
		assertEquals(List.of(want(0, 100, 50), want(1, 100, 50), want(2, 100, 50)), List.copyOf(requested.keySet()));
		requested.forEach((want, ticket) -> assertTrue(thumbnails.finish(want, ticket, "picture " + want.index())));
		assertEquals("picture 0", thumbnails.picture(0));
		assertEquals("picture 2", thumbnails.picture(2));
		assertNull(thumbnails.picture(3));
		assertEquals(0, thumbnails.loading());
	}

	@Test
	void aPageChangeCancelsQueuedDecodesAndFreesOldPictures() {
		keepOnly(0, 3, 100, 50);
		Map<Thumbnails.Want, Thumbnails.Ticket> firstPage = new LinkedHashMap<>(requested);
		thumbnails.finish(want(0, 100, 50), firstPage.get(want(0, 100, 50)), "picture 0");

		keepOnly(3, 6, 100, 50);
		assertEquals(List.of("picture 0"), released);
		assertNull(thumbnails.picture(0));
		Thumbnails.Ticket queued = firstPage.get(want(1, 100, 50));
		assertFalse(queued.wanted(), "the decoder skips it before reading the file");
		assertFalse(thumbnails.finish(want(1, 100, 50), queued, "late"), "a late result is refused, so the caller frees it");
		assertNull(thumbnails.picture(1));
		assertEquals(3, requested.size(), "the new page is requested");
		assertEquals(3, thumbnails.loading());
	}

	@Test
	void aNewSizeReplacesPicturesAtTheOldOne() {
		keepOnly(0, 2, 100, 50);
		Map<Thumbnails.Want, Thumbnails.Ticket> small = new LinkedHashMap<>(requested);
		thumbnails.finish(want(0, 100, 50), small.get(want(0, 100, 50)), "small 0");

		keepOnly(0, 2, 200, 100);
		assertEquals(List.of("small 0"), released);
		assertEquals(List.of(want(0, 200, 100), want(1, 200, 100)), List.copyOf(requested.keySet()));
		assertFalse(thumbnails.finish(want(1, 100, 50), small.get(want(1, 100, 50)), "small 1"));
		assertNull(thumbnails.picture(1), "never the old size");
	}

	@Test
	void decodesStillWantedAreNotRequestedTwice() {
		keepOnly(0, 3, 100, 50);
		Map<Thumbnails.Want, Thumbnails.Ticket> first = new LinkedHashMap<>(requested);
		keepOnly(1, 4, 100, 50);
		assertEquals(List.of(want(3, 100, 50)), List.copyOf(requested.keySet()), "1 and 2 are already on the way");
		assertTrue(first.get(want(1, 100, 50)).wanted());
		assertFalse(first.get(want(0, 100, 50)).wanted());
	}

	@Test
	void aCancelledTicketDoesNotDisturbALaterRequestForTheSamePicture() {
		keepOnly(0, 1, 100, 50);
		Thumbnails.Ticket old = requested.get(want(0, 100, 50));
		keepOnly(1, 2, 100, 50);
		keepOnly(0, 1, 100, 50);
		Thumbnails.Ticket current = requested.get(want(0, 100, 50));
		assertFalse(thumbnails.finish(want(0, 100, 50), old, "old"));
		assertEquals(1, thumbnails.loading(), "the new request is still waited for");
		assertTrue(thumbnails.finish(want(0, 100, 50), current, "new"));
		assertEquals("new", thumbnails.picture(0));
	}

	@Test
	void closingTheScreenCancelsEverythingAndFreesEveryPicture() {
		keepOnly(0, 3, 100, 50);
		Map<Thumbnails.Want, Thumbnails.Ticket> page = new LinkedHashMap<>(requested);
		thumbnails.finish(want(2, 100, 50), page.get(want(2, 100, 50)), "picture 2");
		thumbnails.clear(released::add);
		assertEquals(List.of("picture 2"), released);
		assertEquals(0, thumbnails.loading());
		assertFalse(page.get(want(0, 100, 50)).wanted(), "decodes still queued are skipped");
		assertFalse(page.get(want(1, 100, 50)).wanted());
		assertFalse(thumbnails.finish(want(0, 100, 50), page.get(want(0, 100, 50)), "late"));
		assertNull(thumbnails.picture(0));
	}

	@Test
	void aFailedDecodeIsTriedAgainNextTime() {
		keepOnly(0, 1, 100, 50);
		assertTrue(thumbnails.finish(want(0, 100, 50), requested.get(want(0, 100, 50)), null), "still wanted, so the failure is reported");
		assertNull(thumbnails.picture(0));
		keepOnly(0, 1, 100, 50);
		assertEquals(List.of(want(0, 100, 50)), List.copyOf(requested.keySet()));
	}
}
