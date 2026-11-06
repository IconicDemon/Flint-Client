package com.flintclient.client.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.flintclient.client.files.PackFixer.Kind;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackFixerTest {
	@TempDir
	Path game;

	private final List<String> said = new ArrayList<>();
	private final List<Boolean> changed = new ArrayList<>();

	private PackFixer fixer(PackFixer.Archiver archiver) {
		return new PackFixer(game, (kind, message, folderChanged) -> {
			said.add(message);
			changed.add(folderChanged);
		}, archiver);
	}

	private PackFixer fixer() {
		return fixer((archive, zip, work) -> {
			throw new PackFixer.Unreadable("no archiver in this test", "test");
		});
	}

	private static byte[] zip(Map<String, String> entries) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream out = new ZipOutputStream(bytes)) {
			for (Map.Entry<String, String> entry : entries.entrySet()) {
				out.putNextEntry(new ZipEntry(entry.getKey()));
				out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
				out.closeEntry();
			}
		}
		return bytes.toByteArray();
	}

	private static Map<String, String> entries(String... pairs) {
		Map<String, String> map = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			map.put(pairs[i], pairs[i + 1]);
		}
		return map;
	}

	private Path put(Kind kind, String name, byte[] content) throws IOException {
		Path folder = game.resolve(kind.folder());
		Files.createDirectories(folder);
		return Files.write(folder.resolve(name), content);
	}

	private static TreeSet<String> names(Path zip) throws IOException {
		try (ZipFile file = new ZipFile(zip.toFile())) {
			TreeSet<String> names = new TreeSet<>();
			for (ZipEntry entry : Collections.list(file.entries())) {
				names.add(entry.getName());
			}
			return names;
		}
	}

	private TreeSet<String> listing(Kind kind) throws IOException {
		try (var files = Files.list(game.resolve(kind.folder()))) {
			return files.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toCollection(TreeSet::new));
		}
	}

	@Test
	void aPackZippedWithItsFolderComesOutWithItsPackAtTheTop() throws IOException {
		byte[] original = zip(entries("My Pack/pack.mcmeta", "{}", "My Pack/assets/minecraft/textures/a.png", "png", "__MACOSX/My Pack/._a.png", "x"));
		put(Kind.RESOURCE_PACKS, "My Pack.zip", original);
		fixer().scan(Kind.RESOURCE_PACKS, true, false);
		Path made = game.resolve("resourcepacks/My Pack.zip");
		assertEquals(new TreeSet<>(List.of("assets/minecraft/textures/a.png", "pack.mcmeta")), names(made));
		assertTrue(java.util.Arrays.equals(original, Files.readAllBytes(game.resolve("flint/originals/resourcepacks/My Pack.zip"))), "kept as it was");
		assertEquals(List.of("“My Pack” is ready in your resource packs"), said);
		assertEquals(List.of(true), changed);
		assertFalse(Files.exists(game.resolve("flint/unpacking")) && Files.list(game.resolve("flint/unpacking")).findAny().isPresent(), "no scratch left");
	}

	@Test
	void aPackTheGameReadsIsLeftAlone() throws IOException {
		Path pack = put(Kind.RESOURCE_PACKS, "Fine.zip", zip(entries("pack.mcmeta", "{}", "assets/a.png", "png")));
		long before = Files.getLastModifiedTime(pack).toMillis();
		PackFixer fixer = fixer();
		fixer.scan(Kind.RESOURCE_PACKS, true, false);
		fixer.scan(Kind.RESOURCE_PACKS, true, false);
		assertEquals(before, Files.getLastModifiedTime(pack).toMillis());
		assertEquals(List.of(), said);
		assertFalse(Files.exists(game.resolve("flint/originals")));
	}

	@Test
	void severalPacksInOneZipBecomeOneZipEach() throws IOException {
		put(Kind.RESOURCE_PACKS, "Bundle.zip", zip(entries("Bundle/Red/pack.mcmeta", "{}", "Bundle/Red/a.png", "r", "Bundle/Blue/pack.mcmeta", "{}")));
		fixer().scan(Kind.RESOURCE_PACKS, false, false);
		assertEquals(new TreeSet<>(List.of("Blue.zip", "Red.zip")), listing(Kind.RESOURCE_PACKS));
		assertEquals(new TreeSet<>(List.of("a.png", "pack.mcmeta")), names(game.resolve("resourcepacks/Red.zip")));
		assertEquals(List.of("2 packs from one file are ready in your resource packs"), said);
	}

	@Test
	void packsZippedInsideAZipAreTakenOut() throws IOException {
		byte[] ready = zip(entries("pack.mcmeta", "{}", "a.png", "a"));
		byte[] nested = zip(entries("Inner/pack.mcmeta", "{}"));
		ByteArrayOutputStream outer = new ByteArrayOutputStream();
		try (ZipOutputStream out = new ZipOutputStream(outer)) {
			out.putNextEntry(new ZipEntry("Packs/Ready 16x.zip"));
			out.write(ready);
			out.closeEntry();
			out.putNextEntry(new ZipEntry("Packs/Nested.zip"));
			out.write(nested);
			out.closeEntry();
		}
		put(Kind.RESOURCE_PACKS, "All my packs.zip", outer.toByteArray());
		fixer().scan(Kind.RESOURCE_PACKS, false, false);
		assertEquals(new TreeSet<>(List.of("Nested.zip", "Ready 16x.zip")), listing(Kind.RESOURCE_PACKS));
		assertEquals(new TreeSet<>(List.of("pack.mcmeta")), names(game.resolve("resourcepacks/Nested.zip")));
	}

	@Test
	void aZipWithoutAPackIsOnlyTalkedAboutWhenAsked() throws IOException {
		Path file = put(Kind.RESOURCE_PACKS, "Notes.zip", zip(entries("readme.txt", "hi")));
		PackFixer fixer = fixer();
		fixer.scan(Kind.RESOURCE_PACKS, false, false);
		assertEquals(List.of(), said);
		Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5000));
		fixer.scan(Kind.RESOURCE_PACKS, true, false);
		assertEquals(List.of("“Notes.zip” isn't a resource pack (no pack.mcmeta in it)"), said);
		assertTrue(Files.isRegularFile(file), "left where it is");
		fixer.scan(Kind.RESOURCE_PACKS, true, false);
		assertEquals(1, said.size(), "said once");
	}

	@Test
	void backslashedNamesAreStraightened() throws IOException {
		put(Kind.RESOURCE_PACKS, "Win.zip", zip(entries("pack.mcmeta", "{}", "assets\\minecraft\\a.png", "a")));
		fixer().scan(Kind.RESOURCE_PACKS, false, false);
		assertEquals(new TreeSet<>(List.of("assets/minecraft/a.png", "pack.mcmeta")), names(game.resolve("resourcepacks/Win.zip")));
	}

	@Test
	void aWorldInAZipBecomesAWorldFolder() throws IOException {
		put(Kind.WORLDS, "Parkour Map.zip", zip(entries("Parkour Map v2/level.dat", "lvl", "Parkour Map v2/region/r.0.0.mca", "r")));
		fixer().scan(Kind.WORLDS, false, false);
		assertEquals("lvl", Files.readString(game.resolve("saves/Parkour Map/level.dat")));
		assertTrue(Files.isRegularFile(game.resolve("saves/Parkour Map/region/r.0.0.mca")));
		assertTrue(Files.isRegularFile(game.resolve("flint/originals/saves/Parkour Map.zip")));
		assertEquals(List.of("“Parkour Map” is ready in your worlds"), said);
	}

	@Test
	void entriesReachingOutsideTheirFolderAreLeftOut() throws IOException {
		put(Kind.WORLDS, "Sneaky.zip", zip(entries("../../escaped.txt", "x", "/abs.txt", "y", "W/level.dat", "lvl", "W/C:evil", "z")));
		fixer().scan(Kind.WORLDS, false, false);
		assertTrue(Files.isRegularFile(game.resolve("saves/Sneaky/level.dat")));
		for (Path near : List.of(game, game.resolve("saves"), game.getParent(), game.resolve("flint/unpacking"))) {
			assertFalse(Files.exists(near.resolve("escaped.txt")), near.toString());
		}
		try (var files = Files.list(game.resolve("saves/Sneaky"))) {
			assertEquals(List.of("level.dat"), files.map(p -> p.getFileName().toString()).toList());
		}
	}

	@Test
	void aPackFolderWithThePackOneFolderDownIsFlattened() throws IOException {
		Path inner = game.resolve("resourcepacks/Faithful/Faithful");
		Files.createDirectories(inner.resolve("assets"));
		Files.writeString(inner.resolve("pack.mcmeta"), "{}");
		Files.writeString(game.resolve("resourcepacks/Faithful/Thumbs.db"), "x");
		fixer().scan(Kind.RESOURCE_PACKS, false, false);
		assertTrue(Files.isRegularFile(game.resolve("resourcepacks/Faithful/pack.mcmeta")));
		assertTrue(Files.isDirectory(game.resolve("resourcepacks/Faithful/assets")));
		assertEquals(new TreeSet<>(List.of("Faithful")), listing(Kind.RESOURCE_PACKS));
		assertTrue(Files.isRegularFile(game.resolve("flint/originals/resourcepacks/Faithful/Thumbs.db")), "what was left is kept");
		assertEquals(List.of("“Faithful” is ready in your resource packs"), said);
	}

	@Test
	void foldersThatAreNoPackAreLeftAloneAndUnmentioned() throws IOException {
		Files.createDirectories(game.resolve("saves/some-mod-data/a"));
		Files.createDirectories(game.resolve("saves/some-mod-data/b"));
		Files.createDirectories(game.resolve("resourcepacks/My packs/Red"));
		fixer().scan(Kind.WORLDS, true, false);
		fixer().scan(Kind.RESOURCE_PACKS, true, false);
		assertEquals(List.of(), said);
		assertTrue(Files.isDirectory(game.resolve("saves/some-mod-data/a")) && Files.isDirectory(game.resolve("resourcepacks/My packs/Red")));
		assertFalse(Files.exists(game.resolve("flint/originals")));
	}

	@Test
	void aWorldFolderReadyAsItIsIsLeftAlone() throws IOException {
		Files.createDirectories(game.resolve("saves/New World/region"));
		Files.writeString(game.resolve("saves/New World/level.dat"), "lvl");
		fixer().scan(Kind.WORLDS, true, false);
		assertEquals(List.of(), said);
		assertTrue(Files.isRegularFile(game.resolve("saves/New World/level.dat")));
	}

	@Test
	void anArchiveThatCantBeOpenedIsToldAndKept() throws IOException {
		Path rar = put(Kind.RESOURCE_PACKS, "Pack.rar", new byte[] {'R', 'a', 'r', '!', 0x1A, 7, 1, 0});
		fixer().scan(Kind.RESOURCE_PACKS, false, false);
		assertEquals(List.of("Couldn't open “Pack.rar”: no archiver in this test"), said);
		assertTrue(Files.isRegularFile(rar));
		assertFalse(Files.exists(game.resolve("flint/originals")));
	}

	@Test
	void aFileStillChangingWaitsUntilItHoldsStill() throws IOException {
		put(Kind.RESOURCE_PACKS, "Copying.zip", zip(entries("Copying/pack.mcmeta", "{}")));
		PackFixer fixer = fixer();
		fixer.scan(Kind.RESOURCE_PACKS, false, true);
		assertTrue(Files.isRegularFile(game.resolve("resourcepacks/Copying.zip")) && said.isEmpty(), "first sight: wait");
		fixer.scan(Kind.RESOURCE_PACKS, false, true);
		assertEquals(1, said.size(), "held still: taken");
	}

	@Test
	void aNameAlreadyTakenGetsANumber() throws IOException {
		put(Kind.RESOURCE_PACKS, "Same.rar", new byte[] {1});
		put(Kind.RESOURCE_PACKS, "Same.zip", zip(entries("pack.mcmeta", "{}")));
		byte[] converted = zip(entries("Same/pack.mcmeta", "{}"));
		fixer((archive, zip, work) -> Files.write(zip, converted)).scan(Kind.RESOURCE_PACKS, false, false);
		assertEquals(new TreeSet<>(List.of("Same (2).zip", "Same.zip")), listing(Kind.RESOURCE_PACKS));
	}

	/** The real thing: a .7z made by this computer's bsdtar, opened by it again (Windows 10 and 11, macOS). */
	@Test
	void aSevenZipPackIsUnpackedWithTheComputersOwnArchiver() throws Exception {
		String tar = PackFixer.Tar.find();
		assumeTrue(tar != null, "no bsdtar on this computer");
		Path source = game.resolve("source");
		Files.createDirectories(source.resolve("Ünïcode Pack/assets"));
		Files.writeString(source.resolve("Ünïcode Pack/pack.mcmeta"), "{\"pack\":{}}");
		Files.writeString(source.resolve("Ünïcode Pack/assets/a.txt"), "a");
		Files.createDirectories(game.resolve("resourcepacks"));
		// Plain names on tar's command line (Windows' tar reads them in the system code page); the pack's own name, inside
		// the archive, and the archive's name in the folder have any letters.
		Process process = new ProcessBuilder(tar, "--format", "7zip", "-cf", "../made.7z", ".").directory(source.toFile()).redirectErrorStream(true).start();
		String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assumeTrue(process.waitFor() == 0, "this bsdtar can't write .7z: " + out);
		Files.move(game.resolve("made.7z"), game.resolve("resourcepacks/Pack ✦.7z"));
		fixer(PackFixer.Archiver.system()).scan(Kind.RESOURCE_PACKS, false, false);
		assertEquals(List.of("“Pack ✦” is ready in your resource packs"), said);
		assertEquals(new TreeSet<>(List.of("assets/a.txt", "pack.mcmeta")), names(game.resolve("resourcepacks/Pack ✦.zip")));
		assertTrue(Files.isRegularFile(game.resolve("flint/originals/resourcepacks/Pack ✦.7z")));
	}

	@Test
	void shaderPackArchivesComeOutWithTheirShadersFolderAtTheTop() throws IOException {
		put(Kind.SHADER_PACKS, "Shady.rar", new byte[] {1});
		byte[] converted = zip(entries("Shady v1/shaders/composite.fsh", "x", "Shady v1/readme.txt", "r"));
		fixer((archive, zip, work) -> Files.write(zip, converted)).scan(Kind.SHADER_PACKS, false, false);
		assertEquals(new TreeSet<>(List.of("readme.txt", "shaders/composite.fsh")), names(game.resolve("shaderpacks/Shady.zip")));
		Path untouched = put(Kind.SHADER_PACKS, "Nested.zip", zip(entries("Nested/shaders/a.fsh", "x")));
		fixer().scan(Kind.SHADER_PACKS, true, false);
		assertTrue(Files.isRegularFile(untouched), "Iris reads shader .zips as they are");
	}
}
