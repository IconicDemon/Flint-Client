package com.flintclient.client.module.servers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.module.servers.QuickPlay.Entry;
import com.flintclient.client.module.servers.QuickPlay.Result;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuickPlayTest {
	private static List<Entry> pins(int n) {
		List<Entry> list = List.of();
		for (int i = 1; i <= n; i++) {
			list = QuickPlay.add(list, "Server " + i, "s" + i + ".example.test").list();
		}
		return list;
	}

	@Test
	void aNewPlayerHasNothingPinned() {
		assertTrue(QuickPlay.migrate(List.of()).isEmpty());
		assertTrue(QuickPlay.migrate(Arrays.asList(null, null, null, null, null)).isEmpty(), "empty old slots are not servers");
	}

	@Test
	void pinningAddsInOrderUpToFiveAndNeverReplaces() {
		List<Entry> five = pins(5);
		assertEquals(5, five.size());
		assertEquals("Server 1", five.get(0).name());
		QuickPlay.Change sixth = QuickPlay.add(five, "Sixth", "six.example.test");
		assertEquals(Result.FULL, sixth.result());
		assertSame(five, sixth.list(), "nothing is overwritten");
	}

	@Test
	void theSameServerIsPinnedOnce() {
		List<Entry> one = QuickPlay.add(List.of(), "Example", "Play.Example.net").list();
		assertEquals(Result.ALREADY_PINNED, QuickPlay.add(one, "Again", "play.example.net:25565").result());
		assertEquals(Result.ALREADY_PINNED, QuickPlay.add(one, "Again", " play.example.net ").result());
		assertEquals(Result.ADDED, QuickPlay.add(one, "Other port", "play.example.net:25566").result());
	}

	@Test
	void unusableAddressesAreRefused() {
		for (String bad : new String[] {"", "   ", "has space.example", "x".repeat(300)}) {
			assertEquals(Result.INVALID, QuickPlay.add(List.of(), "n", bad).result(), bad);
		}
	}

	@Test
	void removingUnpinsOnlyThatServer() {
		List<Entry> three = pins(3);
		List<Entry> two = QuickPlay.remove(three, "S2.EXAMPLE.TEST");
		assertEquals(List.of("s1.example.test", "s3.example.test"), two.stream().map(Entry::address).toList());
		assertSame(two, QuickPlay.remove(two, "not.pinned.test"));
	}

	@Test
	void anEditedServerKeepsItsPinUnderItsNewNameAndAddress() {
		List<Entry> two = pins(2);
		List<Entry> edited = QuickPlay.edited(two, "s1.example.test", "Renamed", "new.example.test");
		assertEquals(new Entry("Renamed", "new.example.test"), edited.get(0));
		assertEquals(2, edited.size());
		List<Entry> merged = QuickPlay.edited(two, "s1.example.test", "Dup", "s2.example.test");
		assertEquals(1, merged.size(), "edited into another pinned server: one pin");
		assertSame(two, QuickPlay.edited(two, "unpinned.test", "x", "y.test"));
	}

	@Test
	void reconcilingFollowsRenamesAndDropsDeletedServers() {
		List<Entry> three = pins(3);
		List<Entry> saved = List.of(new Entry("Server One Renamed", "s1.example.test"), new Entry("Server 3", "s3.example.test"));
		List<Entry> now = QuickPlay.reconcile(three, saved);
		assertEquals(List.of(new Entry("Server One Renamed", "s1.example.test"), new Entry("Server 3", "s3.example.test")), now);
		assertSame(now, QuickPlay.reconcile(now, saved), "nothing changed: the same list");
	}

	@Test
	void oldAssignedSlotsBecomePinsInOrder() {
		List<Entry> old = new ArrayList<>(Arrays.asList(null, new Entry("A", "a.test"), null, new Entry("A again", "A.TEST"), new Entry("B", "b.test")));
		assertEquals(List.of(new Entry("A", "a.test"), new Entry("B", "b.test")), QuickPlay.migrate(old));
	}

	@Test
	void namesAreCleanedAndFallBackToTheAddress() {
		assertEquals("Exple", QuickPlay.shortName("§6Ex§lple", "x.test"));
		assertEquals("x.test", QuickPlay.shortName("   ", "x.test"));
		assertEquals(64, QuickPlay.shortName("n".repeat(100), "x.test").length());
	}

	@Test
	void monogramsStandInForMissingIcons() {
		assertEquals("MG", QuickPlay.monogram("Mineplex Games"));
		assertEquals("HY", QuickPlay.monogram("hypixel"));
		assertEquals("PE", QuickPlay.monogram("play.example.net"));
		assertEquals("?", QuickPlay.monogram("  ***  "));
		assertFalse(QuickPlay.monogram("Server 2").isEmpty());
	}
}
