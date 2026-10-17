package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.flintclient.client.control.feature.TierCache.Result;
import com.flintclient.client.control.feature.TierCache.Status;
import com.flintclient.client.control.feature.TierCache.Tier;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TierCacheTest {
	private static final UUID A = new UUID(1, 2);

	@Test
	void tiersParseAndRank() {
		assertEquals(new Tier(true, 1), Tier.parse("HT1"));
		assertEquals(new Tier(false, 5), Tier.parse("lt5"));
		assertNull(Tier.parse("HT6"));
		assertNull(Tier.parse("XT1"));
		assertEquals(10, new Tier(true, 1).rank());
		assertEquals(1, new Tier(false, 5).rank());
	}

	@Test
	void labelsNeverInventATier() {
		Result found = new Result(Status.FOUND, Map.of("sword", new Tier(true, 3), "axe", new Tier(false, 1)), 0);
		assertEquals("HT3", TierCache.label(found, "sword", false));
		assertEquals("Not Found", TierCache.label(found, "mace", false));
		assertEquals("LT1", TierCache.label(found, "mace", true), "highest when this gamemode has none");
		assertEquals("Not Found", TierCache.label(new Result(Status.NOT_FOUND, Map.of(), 0), "sword", true));
		assertEquals("Not Available", TierCache.label(new Result(Status.RATE_LIMITED, Map.of(), 0), "sword", true));
		assertEquals("…", TierCache.label(null, "sword", true));
	}

	@Test
	void resultsExpireByKind() {
		TierCache cache = new TierCache();
		cache.put(A, new Result(Status.UNAVAILABLE, Map.of(), 1000));
		assertEquals(Status.UNAVAILABLE, cache.get(A, 1000 + TierCache.FAILURE_MS).status());
		assertNull(cache.get(A, 1001 + TierCache.FAILURE_MS));
		cache.put(A, new Result(Status.FOUND, Map.of(), 0));
		assertEquals(Status.FOUND, cache.get(A, TierCache.FOUND_MS).status());
	}

	@Test
	void sizeIsBounded() {
		TierCache cache = new TierCache();
		for (int i = 0; i < TierCache.MAX + 10; i++) {
			cache.put(new UUID(0, i), new Result(Status.NOT_FOUND, Map.of(), 0));
		}
		assertNull(cache.get(new UUID(0, 0), 0), "oldest dropped");
		assertEquals(Status.NOT_FOUND, cache.get(new UUID(0, TierCache.MAX + 9), 0).status());
	}
}
