package com.flintclient.client.control.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonStoreTest {
	/** 15 Oct 2026, 10:15:00 UTC. */
	static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-15T10:15:00Z"), ZoneOffset.UTC);

	@TempDir
	Path dir;

	private Path file() {
		return dir.resolve("flintclient").resolve("test.json");
	}

	private JsonStore store() {
		return new JsonStore(file(), 1, CLOCK);
	}

	private void write(String json) throws IOException {
		Files.createDirectories(file().getParent());
		Files.writeString(file(), json, StandardCharsets.UTF_8);
	}

	private List<String> files() throws IOException {
		try (Stream<Path> list = Files.list(file().getParent())) {
			return list.map(p -> p.getFileName().toString()).sorted().toList();
		}
	}

	@Test
	void aMissingFileMeansDefaultsAndNothingIsWrittenUntilAChange() {
		JsonStore store = store();
		assertEquals(JsonStore.State.MISSING, store.state());
		assertNull(store.notice());
		assertFalse(store.readOnly());
		assertEquals(1, store.root().get("schema").getAsInt());
		assertTrue(store.save(), "nothing to write is a success");
		assertFalse(Files.exists(file()), "nothing written while unchanged");
		store.root().addProperty("x", 1);
		store.markDirty();
		assertTrue(store.save());
		assertTrue(Files.exists(file()), "parent folders are created");
		assertFalse(store.dirty());
	}

	@Test
	void writesAtomicallyAndLeavesNoTemporaryFile() throws IOException {
		write("{\"schema\": 1, \"a\": 1}");
		JsonStore store = store();
		store.root().addProperty("a", 2);
		store.markDirty();
		assertTrue(store.save());
		assertEquals(List.of("test.json"), files(), "no .tmp left behind");
		assertEquals(2, JsonParser.parseString(Files.readString(file())).getAsJsonObject().get("a").getAsInt());
		JsonStore.writeAtomically(dir.resolve("deep/er/plain.json"), "{}");
		assertEquals("{}", Files.readString(dir.resolve("deep/er/plain.json")));
		assertFalse(Files.exists(dir.resolve("deep/er/plain.json.tmp")));
	}

	@Test
	void unknownKeysSurviveASave() throws IOException {
		write("""
			{"schema": 1, "future": {"x": [1, 2, {"y": null}]}, "known": {"keep": "me", "value": 1}, "after": true}
			""");
		JsonStore store = store();
		store.root().getAsJsonObject("known").addProperty("value", 2);
		store.markDirty();
		assertTrue(store.save());
		JsonObject saved = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
		assertEquals(JsonParser.parseString("{\"x\": [1, 2, {\"y\": null}]}"), saved.get("future"));
		assertEquals("me", saved.getAsJsonObject("known").get("keep").getAsString());
		assertEquals(2, saved.getAsJsonObject("known").get("value").getAsInt());
		assertTrue(saved.get("after").getAsBoolean());
		assertEquals(List.of("schema", "future", "known", "after"), List.copyOf(saved.keySet()), "key order is kept");
	}

	@Test
	void aCorruptFileIsCopiedAsideLeftAloneAndDefaultsUsed() throws IOException {
		String corrupt = "{\"schema\": 1, \"sounds\": {";
		write(corrupt);
		JsonStore store = store();
		assertEquals(JsonStore.State.DAMAGED, store.state());
		assertEquals(1, store.root().size(), "defaults: only the schema");
		assertEquals("test.json couldn't be read. Flint saved a copy as test.damaged-20261015-101500.json and is using defaults.",
			store.notice());
		Path copy = file().resolveSibling("test.damaged-20261015-101500.json");
		assertEquals(copy, store.damagedCopy());
		assertEquals(corrupt, Files.readString(copy), "an exact copy");
		assertEquals(corrupt, Files.readString(file()), "the original is untouched");
		assertFalse(store.readOnly());
		assertTrue(store.save(), "nothing changed, nothing written");
		assertEquals(corrupt, Files.readString(file()));
		store.root().addProperty("fresh", true);
		store.markDirty();
		assertTrue(store.save(), "a deliberate save replaces it");
		assertTrue(JsonParser.parseString(Files.readString(file())).getAsJsonObject().get("fresh").getAsBoolean());
		assertEquals(corrupt, Files.readString(copy), "the copy stays");
		assertNull(store.notice(), "nothing left to warn about once replaced");
		assertEquals(copy, store.damagedCopy());
	}

	@Test
	void aFileThatIsNotUtf8IsCopiedAsideLikeAnyDamage() throws IOException {
		// "Café" saved by an editor in Latin-1: é is the single byte 0xE9, which isn't valid UTF-8.
		byte[] latin1 = "{\"schema\": 1, \"name\": \"Café\"}".getBytes(StandardCharsets.ISO_8859_1);
		Files.createDirectories(file().getParent());
		Files.write(file(), latin1);
		JsonStore store = store();
		assertEquals(JsonStore.State.DAMAGED, store.state());
		assertFalse(store.readOnly(), "a later save may replace it");
		assertEquals("test.json couldn't be read. Flint saved a copy as test.damaged-20261015-101500.json and is using defaults.",
			store.notice());
		assertArrayEquals(latin1, Files.readAllBytes(store.damagedCopy()), "the exact bytes");
		assertArrayEquals(latin1, Files.readAllBytes(file()), "the original is untouched");
		store.root().addProperty("fresh", true);
		store.markDirty();
		assertTrue(store.save());
		assertArrayEquals(latin1, Files.readAllBytes(store.damagedCopy()), "the copy stays");
	}

	@Test
	void aByteOrderMarkIsNotDamage() throws IOException {
		write("\uFEFF{\"schema\": 1, \"v\": 3}");
		JsonStore store = store();
		assertEquals(JsonStore.State.LOADED, store.state());
		assertEquals(3, store.root().get("v").getAsInt());
	}

	@Test
	void keepCopyCopiesOnlyOnceAndReusesTheCopyMadeOnReading() throws IOException {
		write("{oops");
		JsonStore store = store();
		Path copy = store.damagedCopy();
		assertNotNull(copy);
		assertEquals(copy, store.keepCopy());
		assertEquals(List.of("test.damaged-20261015-101500.json", "test.json"), files());
	}

	@Test
	void theSameDamageIsCopiedOnlyOnce() throws IOException {
		write("not json {");
		store().state();
		JsonStore later = new JsonStore(file(), 1, Clock.offset(CLOCK, java.time.Duration.ofHours(1)));
		assertEquals(JsonStore.State.DAMAGED, later.state());
		assertEquals("test.damaged-20261015-101500.json", later.damagedCopy().getFileName().toString(), "reuses the identical copy");
		assertEquals(List.of("test.damaged-20261015-101500.json", "test.json"), files());
		write("different damage");
		JsonStore third = store();
		third.state();
		assertEquals("test.damaged-20261015-101500-2.json", third.damagedCopy().getFileName().toString(), "a new name for new damage");
	}

	@Test
	void anythingButAnObjectAtTheRootIsDamaged() throws IOException {
		for (String json : new String[] {"", "[]", "[1, 2]", "null", "true", "42", "\"text\"", "{} {}", "{\"a\": }"}) {
			write(json);
			JsonStore store = new JsonStore(file(), 1, CLOCK);
			assertEquals(JsonStore.State.DAMAGED, store.state(), json);
			assertNotNull(store.notice());
		}
	}

	@Test
	void aNewerSchemaIsReadButNeverWritten() throws IOException {
		String newer = "{\"schema\": 2, \"value\": 5, \"newThing\": {}}";
		write(newer);
		JsonStore store = store();
		assertEquals(JsonStore.State.NEWER, store.state());
		assertTrue(store.readOnly());
		assertEquals(2, store.fileSchema());
		assertEquals(5, store.root().get("value").getAsInt(), "what is understood is still read");
		assertEquals("Saved by a newer Flint version; changes won't be saved.", store.notice());
		store.root().addProperty("value", 6);
		store.markDirty();
		assertFalse(store.save());
		assertEquals(newer, Files.readString(file()), "never written");
		assertEquals(List.of("test.json"), files(), "and not copied either");
	}

	@Test
	void aMissingSchemaCountsAsTheFirstAndANumberInTextIsReadAsANumber() throws IOException {
		for (String json : new String[] {"{}", "{\"schema\": null}", "{\"schema\": \"1\"}", "{\"schema\": 1.0}"}) {
			write(json);
			JsonStore store = new JsonStore(file(), 1, CLOCK);
			assertEquals(JsonStore.State.LOADED, store.state(), json);
			assertEquals(1, store.fileSchema(), json);
			assertFalse(store.readOnly(), json);
		}
	}

	@Test
	void aSchemaThatCantBeTrustedIsNeverWritten() throws IOException {
		String[][] cases = {
			{"{\"schema\": \"2\"}", "2"},
			{"{\"schema\": 1e12}", String.valueOf(Integer.MAX_VALUE)},
			{"{\"schema\": 1.5}", "-1"},
			{"{\"schema\": 0}", "-1"},
			{"{\"schema\": -4}", "-1"},
			{"{\"schema\": true}", "-1"},
			{"{\"schema\": \"one\"}", "-1"},
			{"{\"schema\": [1]}", "-1"}};
		for (String[] testCase : cases) {
			String json = testCase[0];
			write(json);
			JsonStore store = new JsonStore(file(), 1, CLOCK);
			assertEquals(JsonStore.State.NEWER, store.state(), json);
			assertEquals(Integer.parseInt(testCase[1]), store.fileSchema(), json);
			assertTrue(store.readOnly(), json);
			assertNotNull(store.notice(), json);
			store.root().addProperty("changed", true);
			store.markDirty();
			assertFalse(store.save(), json);
			assertEquals(json, Files.readString(file()), "never written: " + json);
		}
		write("{\"schema\": 0}");
		assertEquals("test.json has a version number Flint doesn't recognise; changes won't be saved.", store().notice());
	}

	@Test
	void theFileIsReadOnce() throws IOException {
		write("{\"schema\": 1, \"v\": 1}");
		JsonStore store = store();
		assertEquals(1, store.root().get("v").getAsInt());
		write("{\"schema\": 1, \"v\": 2}");
		assertEquals(1, store.root().get("v").getAsInt(), "not re-read");
	}

	@Test
	void savingOnlyWhenDirty() throws IOException {
		write("{\"schema\": 1}");
		JsonStore store = store();
		store.root();
		Files.writeString(file(), "{\"schema\": 1, \"external\": true}");
		assertTrue(store.save());
		assertTrue(Files.readString(file()).contains("external"), "not dirty, so not written");
	}
}
