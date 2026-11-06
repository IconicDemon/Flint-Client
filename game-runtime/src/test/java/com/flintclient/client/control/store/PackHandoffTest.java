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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackHandoffTest {
	/** One change exactly as the launcher writes it (app/src-tauri/src/content/livepacks.rs, serde_json pretty). */
	static String change(long revision, String kind, String file, String title, String action, boolean enable) {
		return "    {\n      \"revision\": " + revision + ",\n      \"kind\": \"" + kind + "\",\n      \"file\": \"" + file + "\",\n      \"title\": \""
			+ title + "\",\n      \"action\": \"" + action + "\",\n      \"enable\": " + enable + "\n    }";
	}

	static String launcher(long revision, String... changes) {
		return "{\n  \"schema\": 1,\n  \"revision\": " + revision + ",\n  \"changes\": [\n" + String.join(",\n", changes) + "\n  ]\n}";
	}

	/** Replaces the file the way the launcher does (a new file renamed over the old), with a modification time of its own. */
	private static void write(Path dir, String json, long millis) throws Exception {
		Path temp = dir.resolve("." + PackHandoff.FILE + ".flint-tmp");
		Files.writeString(temp, json, StandardCharsets.UTF_8);
		Files.setLastModifiedTime(temp, FileTime.fromMillis(millis));
		Files.move(temp, dir.resolve(PackHandoff.FILE), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	@Test
	void theLaunchersFileReadsAsItsChanges() {
		List<PackHandoff.Change> changes = PackHandoff.parse(launcher(1_762_380_000_124L,
			change(1_762_380_000_123L, "resourcepack", "Dirt Tint.zip", "Dirt Tint", "added", true),
			change(1_762_380_000_124L, "shaderpack", "Tint.zip", "Tint", "added", false),
			change(1_762_380_000_124L, "resourcepack", "Old.zip", "Old", "removed", false)));
		assertNotNull(changes);
		assertEquals(3, changes.size());
		assertEquals(new PackHandoff.Change(1_762_380_000_123L, PackHandoff.Kind.RESOURCE_PACK, "Dirt Tint.zip", "Dirt Tint", false, true, null), changes.get(0));
		assertEquals(new PackHandoff.Change(1_762_380_000_124L, PackHandoff.Kind.SHADER_PACK, "Tint.zip", "Tint", false, false, null), changes.get(1));
		assertTrue(changes.get(2).removed());

		List<PackHandoff.Change> data = PackHandoff.parse("{\"schema\":1,\"revision\":5,\"changes\":[{\"revision\":5,\"kind\":\"datapack\",\"file\":\"Trees.zip\","
			+ "\"title\":\"Trees\",\"action\":\"added\",\"enable\":true,\"world\":\"New World\"}]}");
		assertEquals(List.of(new PackHandoff.Change(5, PackHandoff.Kind.DATA_PACK, "Trees.zip", "Trees", false, false, "New World")), data,
			"a data pack is never turned on: it loads at /reload");
	}

	@Test
	void aFileThisRuntimeCantReadGivesNothing() {
		assertNull(PackHandoff.parse("not json"));
		assertNull(PackHandoff.parse("[1]"));
		assertNull(PackHandoff.parse("{\"schema\":2,\"revision\":1,\"changes\":[]}"), "another layout");
		assertNull(PackHandoff.parse("{\"schema\":\"1\",\"revision\":1,\"changes\":[]}"));
		assertNull(PackHandoff.parse("{\"schema\":1,\"revision\":1}"), "no change list");
		assertNull(PackHandoff.parse("{\"schema\":1,\"revision\":1,\"changes\":{}}"));
		assertEquals(List.of(), PackHandoff.parse("{\"schema\":1,\"revision\":1,\"changes\":[]}"));
		// One broken change leaves the others.
		List<PackHandoff.Change> some = PackHandoff.parse(launcher(3, change(1, "resourcepack", "a.zip", "A", "added", true), "    7",
			change(2, "mod", "sodium.jar", "Sodium", "added", true), change(2, "resourcepack", "b.zip", "B", "renamed", true),
			change(-1, "resourcepack", "c.zip", "C", "added", true), change(3, "resourcepack", "d.zip", "D", "added", true)));
		assertEquals(List.of("a.zip", "d.zip"), some.stream().map(PackHandoff.Change::file).toList());
		// A data pack without a plain world is left out.
		assertEquals(List.of(), PackHandoff.parse("{\"schema\":1,\"revision\":5,\"changes\":[{\"revision\":5,\"kind\":\"datapack\",\"file\":\"T.zip\",\"action\":\"added\"},"
			+ "{\"revision\":5,\"kind\":\"datapack\",\"file\":\"T.zip\",\"action\":\"added\",\"world\":\"../other\"}]}"));
	}

	@Test
	void aChangeMustNameAPlainFileInItsOwnFolder(@TempDir Path game) {
		for (String bad : new String[] {"../outside.zip", "..\\outside.zip", "a/b.zip", "a\\b.zip", "C:pack.zip", "C:\\pack.zip", "/etc/passwd", ".hidden.zip",
			"..", ".", "", " pack.zip", "pack.zip ", "pack.zip.", "CON", "nul.zip", "com1.zip", "LPT9", "a\u0000b.zip", "line\nbreak.zip", "x".repeat(181) + ".zip"}) {
			assertFalse(PackHandoff.plainName(bad), bad);
			assertEquals(List.of(), PackHandoff.parse(launcher(1, change(1, "resourcepack", bad.replace("\\", "\\\\").replace("\u0000", "\\u0000").replace("\n", "\\n"),
				"X", "added", true))), "dropped: " + bad);
		}
		for (String good : new String[] {"Dirt Tint.zip", "Dirt Tint (2).zip", "BSL_v8.2.09.zip", "Faithful 32x - 1.21.zip", "connect.zip", "pack", "Été ☀.zip"}) {
			assertTrue(PackHandoff.plainName(good), good);
		}
		PackHandoff.Change pack = new PackHandoff.Change(1, PackHandoff.Kind.RESOURCE_PACK, "Dirt Tint.zip", "Dirt Tint", false, true, null);
		assertEquals(game.toAbsolutePath().normalize().resolve("resourcepacks").resolve("Dirt Tint.zip"), PackHandoff.path(game, pack));
		PackHandoff.Change shader = new PackHandoff.Change(1, PackHandoff.Kind.SHADER_PACK, "Tint.zip", "Tint", false, true, null);
		assertEquals(game.toAbsolutePath().normalize().resolve("shaderpacks").resolve("Tint.zip"), PackHandoff.path(game, shader));
		PackHandoff.Change data = new PackHandoff.Change(1, PackHandoff.Kind.DATA_PACK, "T.zip", "T", false, false, "New World");
		assertEquals(game.toAbsolutePath().normalize().resolve("saves").resolve("New World").resolve("datapacks").resolve("T.zip"), PackHandoff.path(game, data));
		// Made by hand (not through parse): still refused.
		assertNull(PackHandoff.path(game, new PackHandoff.Change(1, PackHandoff.Kind.RESOURCE_PACK, "../../x.zip", "x", false, true, null)));
		assertNull(PackHandoff.path(game, new PackHandoff.Change(1, PackHandoff.Kind.DATA_PACK, "T.zip", "T", false, false, "..")));
		assertNull(PackHandoff.path(game, new PackHandoff.Change(1, PackHandoff.Kind.DATA_PACK, "T.zip", "T", false, false, null)));
	}

	@Test
	void titlesAreOnePlainLine() {
		assertEquals("Dirt Tint", PackHandoff.title("Dirt Tint", "x.zip"));
		assertEquals("cRed Title", PackHandoff.title("\u00a7cRed\n Title", "x.zip"), "no formatting codes or line breaks");
		assertEquals("evil", PackHandoff.title("\u202eevil\u200b", "x.zip"), "no direction overrides or invisible characters");
		assertEquals("Faithful", PackHandoff.title(null, "Faithful.zip"));
		assertEquals("Faithful", PackHandoff.title("  ", "Faithful.ZIP"));
		assertEquals("folder pack", PackHandoff.title("", "folder pack"));
		String long_ = PackHandoff.title("x".repeat(200), "x.zip");
		assertEquals(PackHandoff.MAX_TITLE, long_.length());
		assertTrue(long_.endsWith("…"));
	}

	@Test
	void eachChangeIsHandedOutOnceAndNothingFromBeforeTheGameStarted(@TempDir Path game) throws Exception {
		long started = 1_762_380_000_000L;
		// Left from an earlier session (the launcher clears it at launch; another launcher might not).
		write(game, launcher(started - 5000, change(started - 5000, "resourcepack", "old.zip", "Old", "added", true)), 1_000_000L);
		PackHandoff handoff = new PackHandoff(game, started);
		assertEquals(List.of(), handoff.poll(), "written before this game started: never applied");
		assertEquals(List.of(), handoff.poll(), "unchanged: not read again");

		String first = change(started + 100, "resourcepack", "Dirt Tint.zip", "Dirt Tint", "added", true);
		write(game, launcher(started + 100, change(started - 5000, "resourcepack", "old.zip", "Old", "added", true), first), 2_000_000L);
		List<PackHandoff.Change> got = handoff.poll();
		assertEquals(List.of("Dirt Tint.zip"), got.stream().map(PackHandoff.Change::file).toList());
		assertTrue(got.get(0).enable());

		// Two installs a moment apart, both seen at the next look; the first one is not handed out again.
		String second = change(started + 200, "shaderpack", "Tint.zip", "Tint", "added", true);
		String third = change(started + 201, "resourcepack", "Dirt Tint.zip", "Dirt Tint", "removed", false);
		write(game, launcher(started + 201, first, second, third), 3_000_000L);
		got = handoff.poll();
		assertEquals(List.of("Tint.zip", "Dirt Tint.zip"), got.stream().map(PackHandoff.Change::file).toList());
		assertTrue(got.get(1).removed());

		// The same content written again (a new time): nothing new.
		write(game, launcher(started + 201, first, second, third), 4_000_000L);
		assertEquals(List.of(), handoff.poll());
	}

	@Test
	void aDamagedOrOversizedFileIsSkippedAndTheNextGoodOneRead(@TempDir Path game) throws Exception {
		PackHandoff handoff = new PackHandoff(game, 0);
		assertEquals(List.of(), handoff.poll(), "no file");

		write(game, "{\"schema\":1,\"revision\":", 1_000_000L);
		assertEquals(List.of(), handoff.poll(), "cut short");

		StringBuilder big = new StringBuilder(launcher(10, change(10, "resourcepack", "big.zip", "Big", "added", true)));
		big.insert(1, " ".repeat(PackHandoff.MAX_BYTES));
		write(game, big.toString(), 2_000_000L);
		assertEquals(List.of(), handoff.poll(), "over 64 KiB: not read");

		write(game, launcher(11, change(11, "resourcepack", "good.zip", "Good", "added", true)), 3_000_000L);
		assertEquals(List.of("good.zip"), handoff.poll().stream().map(PackHandoff.Change::file).toList());

		Files.delete(game.resolve(PackHandoff.FILE));
		assertEquals(List.of(), handoff.poll(), "removed: nothing");
		write(game, launcher(11, change(11, "resourcepack", "good.zip", "Good", "added", true)), 4_000_000L);
		assertEquals(List.of(), handoff.poll(), "back with what was already handed out: nothing again");
	}

	@Test
	void onlyTheNewestChangesAreRead() {
		String[] many = new String[40];
		for (int i = 0; i < many.length; i++) {
			many[i] = change(i + 1, "resourcepack", "p" + i + ".zip", "P" + i, "added", false);
		}
		List<PackHandoff.Change> changes = PackHandoff.parse(launcher(40, many));
		assertEquals(PackHandoff.MAX_CHANGES, changes.size());
		assertEquals("p8.zip", changes.get(0).file());
		assertEquals("p39.zip", changes.get(changes.size() - 1).file());
	}
}
