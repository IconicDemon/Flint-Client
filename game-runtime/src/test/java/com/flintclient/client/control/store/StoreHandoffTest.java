package com.flintclient.client.control.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreHandoffTest {
	/** Exactly what the launcher writes (store-launcher-sync: app/src-tauri/src/store/handoff.rs, serde_json pretty). */
	static String launcher(String base, long revision) {
		return "{\n  \"schema\": 1,\n  \"contractVersion\": 1,\n  \"storeBaseUrl\": \"" + base + "\",\n  \"publicCosmeticsUrl\": \"" + base
			+ "/api/v1/players/cosmetics/\",\n  \"equipRevision\": " + revision + "\n}";
	}

	/** Replaces the file the way the launcher does (a new file renamed over the old), with a modification time of its own. */
	private static void write(Path dir, String json, long millis) throws Exception {
		Path temp = dir.resolve(".flint-store.json.tmp");
		Files.writeString(temp, json, StandardCharsets.UTF_8);
		Files.setLastModifiedTime(temp, FileTime.fromMillis(millis));
		Files.move(temp, dir.resolve(StoreHandoff.FILE), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	@Test
	void theLaunchersFileNamesTheStoreAndTheEquipRevision() {
		StoreHandoff.Contents c = StoreHandoff.parse(launcher("https://store.flintclient.com", 1_761_771_234_567L));
		assertEquals("https://store.flintclient.com", c.endpoint().origin());
		assertEquals(1_761_771_234_567L, c.equipRevision());
		assertEquals(0, StoreHandoff.parse(launcher("https://store.flintclient.com", 0)).equipRevision());
		assertEquals("http://localhost:3214", StoreHandoff.parse(launcher("http://localhost:3214", 0)).endpoint().origin());
		assertEquals("http://127.0.0.1:8787", StoreHandoff.parse(launcher("http://127.0.0.1:8787", 0)).endpoint().origin());
		assertNotNull(StoreHandoff.parse("{\"schema\":1,\"contractVersion\":1,\"storeBaseUrl\":\"https://store.flintclient.com\"}"),
			"publicCosmeticsUrl and equipRevision may be left out");
	}

	@Test
	void aFileThisRuntimeDoesntKnowIsIgnored() {
		String good = launcher("https://store.flintclient.com", 5);
		assertNull(StoreHandoff.parse(good.replace("\"schema\": 1", "\"schema\": 2")), "an unknown schema");
		assertNull(StoreHandoff.parse(good.replace("\"contractVersion\": 1", "\"contractVersion\": 2")), "an unknown contract");
		assertNull(StoreHandoff.parse(good.replace("\"schema\": 1,\n", "")), "no schema");
		assertNull(StoreHandoff.parse(good.replace("\"contractVersion\": 1,\n", "")), "no contract");
		assertNull(StoreHandoff.parse(good.replace("\"schema\": 1", "\"schema\": \"1\"")));
		assertNull(StoreHandoff.parse(launcher("http://store.flintclient.com", 0)), "plain http off this computer");
		assertNull(StoreHandoff.parse(launcher("http://192.168.1.20:3214", 0)));
		assertNull(StoreHandoff.parse(launcher("https://store.flintclient.com/shop", 0)));
		assertNull(StoreHandoff.parse(good.replace("https://store.flintclient.com/api", "https://evil.example/api")),
			"a lookup address that isn't the Store's");
		assertNull(StoreHandoff.parse(good.replace("\"equipRevision\": 5", "\"equipRevision\": -1")));
		assertNull(StoreHandoff.parse(good.replace("\"equipRevision\": 5", "\"equipRevision\": 1.5")));
		assertNull(StoreHandoff.parse("{\"schema\":1,\"contractVersion\":1}"));
		assertNull(StoreHandoff.parse("not json"));
		assertNull(StoreHandoff.parse("[1]"));
	}

	@Test
	void pollingReadsTheFileOnlyWhenItChanges(@TempDir Path game) throws Exception {
		StoreHandoff handoff = new StoreHandoff(game);
		assertFalse(handoff.poll(), "no file: nothing to tell");
		assertEquals(StoreEndpoint.defaults(), handoff.endpoint());
		assertEquals(0, handoff.equipRevision());

		write(game, launcher("http://127.0.0.1:8787", 0), 1_000_000L);
		assertTrue(handoff.poll());
		assertEquals("http://127.0.0.1:8787", handoff.endpoint().origin());
		assertFalse(handoff.poll(), "unchanged: not read again");

		write(game, launcher("http://127.0.0.1:8787", 1_761_771_234_567L), 2_000_000L);
		assertTrue(handoff.poll(), "the player equipped something in the launcher");
		assertEquals(1_761_771_234_567L, handoff.equipRevision());

		write(game, launcher("http://127.0.0.1:8787", 1_761_771_234_567L), 3_000_000L);
		assertFalse(handoff.poll(), "rewritten with the same contents: nothing new");

		write(game, launcher("http://127.0.0.1:8787", 0), 4_000_000L);
		assertTrue(handoff.poll(), "the launcher restarted: compared for inequality, not order");
		assertEquals(0, handoff.equipRevision());

		write(game, launcher("http://127.0.0.1:8787", 0).replace("\"schema\": 1", "\"schema\": 9"), 5_000_000L);
		assertTrue(handoff.poll());
		assertEquals(StoreEndpoint.defaults(), handoff.endpoint(), "an unknown file: the built-in Store");

		write(game, "x".repeat(StoreHandoff.MAX_BYTES + 1), 6_000_000L);
		assertFalse(handoff.poll(), "too large: still the built-in Store");
		assertEquals(StoreEndpoint.defaults(), handoff.endpoint());

		Files.delete(game.resolve(StoreHandoff.FILE));
		assertFalse(handoff.poll());
		assertEquals(StoreEndpoint.defaults(), handoff.endpoint());
	}

	@Test
	void theDevelopmentPropertyWinsButTheRevisionStillCounts(@TempDir Path game) throws Exception {
		write(game, launcher("https://store.flintclient.com", 7), 1_000_000L);
		StoreHandoff handoff = new StoreHandoff(game);
		handoff.poll();
		String before = System.getProperty(StoreEndpoint.PROPERTY);
		try {
			System.setProperty(StoreEndpoint.PROPERTY, "http://localhost:3214");
			assertEquals("http://localhost:3214", handoff.endpoint().origin());
			System.setProperty(StoreEndpoint.PROPERTY, "http://evil.example");
			assertEquals("https://store.flintclient.com", handoff.endpoint().origin(), "an unusable property is ignored");
		} finally {
			if (before == null) {
				System.clearProperty(StoreEndpoint.PROPERTY);
			} else {
				System.setProperty(StoreEndpoint.PROPERTY, before);
			}
		}
		assertEquals(7, handoff.equipRevision());
	}
}
