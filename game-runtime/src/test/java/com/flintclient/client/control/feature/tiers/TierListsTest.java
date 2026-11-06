package com.flintclient.client.control.feature.tiers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.feature.tiers.TierLists.Highest;
import com.flintclient.client.control.feature.tiers.TierLists.Ranking;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TierListsTest {
	private static final Ranking HT2 = new Ranking(2, 0, null, null, 0, false);
	private static final Ranking LT4 = new Ranking(4, 1, null, null, 0, false);
	private static final Map<String, Ranking> PLAYER = Map.of("sword", LT4, "axe", HT2);

	@Test
	void theChosenGamemodesTierShowsUnlessAlwaysHighest() {
		assertEquals("sword", TierLists.shown(PLAYER, "sword", Highest.NEVER).orElseThrow().mode());
		assertEquals("sword", TierLists.shown(PLAYER, "sword", Highest.NOT_FOUND).orElseThrow().mode());
		assertEquals("axe", TierLists.shown(PLAYER, "sword", Highest.ALWAYS).orElseThrow().mode());
	}

	@Test
	void unrankedInTheGamemodeShowsTheHighestOnlyWhenAsked() {
		assertTrue(TierLists.shown(PLAYER, "mace", Highest.NEVER).isEmpty());
		assertEquals(HT2, TierLists.shown(PLAYER, "mace", Highest.NOT_FOUND).orElseThrow().ranking());
		assertTrue(TierLists.shown(Map.of(), "mace", Highest.ALWAYS).isEmpty());
	}

	@Test
	void labelsReadAsTheListsWriteThem() {
		assertEquals("HT2", TierLists.label(HT2));
		assertEquals("LT4", TierLists.label(LT4));
		assertEquals("RHT1", TierLists.label(new Ranking(3, 1, 1, 0, 0, true)), "a retired player's peak");
		assertEquals(60, TierLists.points(new Ranking(1, 0, null, null, 0, false)));
	}

	@Test
	void offlinePlayersAreNeverLookedUp() {
		UUID offline = UUID.nameUUIDFromBytes("OfflinePlayer:Steve".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		assertTrue(TierLists.rankings(offline).isEmpty());
	}

	@Test
	void onlyRealTiersAreKeptAndOnlyAFewPerPlayer() {
		Map<String, Ranking> answer = new java.util.HashMap<>();
		answer.put("sword", LT4);
		answer.put("axe", null);
		answer.put("mace", new Ranking(0, 0, null, null, 0, false));
		answer.put("pot", new Ranking(2, 3, null, null, 0, false));
		answer.put("x".repeat(33), HT2);
		answer.put("", HT2);
		assertEquals(Map.of("sword", LT4), TierLists.kept(answer));
		Map<String, Ranking> flood = new java.util.HashMap<>();
		for (int i = 0; i < 10_000; i++) {
			flood.put("mode" + i, HT2);
		}
		assertEquals(32, TierLists.kept(flood).size(), "an odd answer can't fill memory");
	}

	/** Feeds a body to {@code body} in chunks, as the HTTP client does. */
	private static java.util.concurrent.CompletableFuture<String> feed(TierLists.CappedBody body, String... chunks) {
		boolean[] cancelled = {false};
		body.onSubscribe(new java.util.concurrent.Flow.Subscription() {
			@Override
			public void request(long n) {
			}

			@Override
			public void cancel() {
				cancelled[0] = true;
			}
		});
		for (String chunk : chunks) {
			body.onNext(java.util.List.of(java.nio.ByteBuffer.wrap(chunk.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
			if (cancelled[0]) {
				break;
			}
		}
		if (!cancelled[0]) {
			body.onComplete();
		}
		return body.getBody().toCompletableFuture();
	}

	@Test
	void anAnswerIsReadUpToItsLimitAndNoFurther() {
		assertEquals("{\"sword\":{}}", feed(new TierLists.CappedBody(64), "{\"sword\"", ":{}}").join());
		assertEquals("ü", feed(new TierLists.CappedBody(2), "ü").join(), "the limit counts bytes");
		var tooBig = feed(new TierLists.CappedBody(10), "0123456789", "a", "never read");
		assertTrue(tooBig.isCompletedExceptionally(), "one byte past the limit fails and stops the download");
	}
}
