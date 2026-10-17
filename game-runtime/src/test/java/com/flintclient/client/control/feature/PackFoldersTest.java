package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackFoldersTest {
	@TempDir
	Path dir;

	@Test
	void foldersAreLabelsThatSurviveARestart() throws Exception {
		Path file = dir.resolve("pack-folders.json");
		PackFolders f = new PackFolders(file);
		assertEquals("PvP", f.create("PvP"));
		assertEquals("pvp 2", f.create("pvp"), "names are unique, ignoring case; the typed case is kept");
		assertTrue(f.move("file/Faithful.zip", "PvP"));
		assertTrue(f.move("file/Bare Bones.zip", "pvp 2"));
		assertTrue(Files.exists(file), "saved at once");
		PackFolders again = new PackFolders(file);
		assertEquals(List.of("PvP", "pvp 2"), again.folders());
		assertEquals("PvP", again.folderOf("file/Faithful.zip"));
	}

	@Test
	void renameAndRemoveKeepPacksSafe() {
		PackFolders f = new PackFolders(null);
		f.create("Old");
		f.create("Other");
		f.move("file/a.zip", "Old");
		assertFalse(f.rename("Old", "other"), "taken (any case)");
		assertFalse(f.rename("Old", "   "), "empty");
		assertTrue(f.rename("Old", "New"));
		assertEquals("New", f.folderOf("file/a.zip"));
		assertTrue(f.remove("New"));
		assertNull(f.folderOf("file/a.zip"), "back to Unsorted");
		assertFalse(f.move("file/a.zip", "Missing"));
	}

	@Test
	void nextCyclesThroughFoldersThenUnsorted() {
		PackFolders f = new PackFolders(null);
		assertNull(f.next(null));
		f.create("A");
		f.create("B");
		assertEquals("A", f.next(null));
		assertEquals("B", f.next("A"));
		assertNull(f.next("B"));
	}

	@Test
	void namesAreCleanedAndSearchMatchesEveryWord() {
		assertEquals("Hello", PackFolders.clean("  He§llo\n "));
		assertEquals(PackFolders.MAX_NAME, PackFolders.clean("x".repeat(100)).length());
		assertTrue(PackFolders.matches("16x pvp", "Clean PvP", "A 16x pack"));
		assertFalse(PackFolders.matches("32x", "Clean PvP", "A 16x pack"));
	}

	@Test
	void aDamagedFileStartsEmpty() throws Exception {
		Path file = dir.resolve("pack-folders.json");
		Files.writeString(file, "{not json");
		PackFolders f = new PackFolders(file);
		assertEquals(List.of(), f.folders());
	}
}
