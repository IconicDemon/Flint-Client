package com.flintclient.client.control.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Flint's shared cache of verified cosmetic files ({@link AssetCache}): the launcher's layout and rules, on the game's side. */
class AssetCacheTest {
	private static byte[] bytes(String text, int size) {
		byte[] b = new byte[size];
		byte[] t = text.getBytes(StandardCharsets.UTF_8);
		for (int i = 0; i < size; i++) {
			b[i] = t[i % t.length];
		}
		return b;
	}

	@Test
	void filesAreKeptByTheirSha256InTwoHexFolders(@TempDir Path dir) throws Exception {
		AssetCache cache = new AssetCache(dir);
		byte[] png = bytes("cape", 100);
		String sha = AssetCache.sha256(png);
		cache.put(sha, png);
		Path file = dir.resolve(sha.substring(0, 2)).resolve(sha);
		assertEquals(file, AssetCache.file(dir, sha));
		assertArrayEquals(png, Files.readAllBytes(file));
		assertArrayEquals(png, cache.read(sha, 1000));
		assertTrue(cache.has(sha));
		try (Stream<Path> left = Files.list(file.getParent())) {
			assertEquals(1, left.count(), "no temporary file left behind");
		}
	}

	@Test
	void bytesThatArentTheirHashAreNeverKept(@TempDir Path dir) {
		AssetCache cache = new AssetCache(dir);
		String sha = AssetCache.sha256(bytes("a", 10));
		cache.put(sha, bytes("b", 10));
		assertFalse(cache.has(sha));
		cache.put("../../evil", bytes("b", 10));
		assertNull(cache.read("../../evil", 100));
	}

	@Test
	void aFileThatChangedOnDiskIsDeletedSoItIsFetchedAgain(@TempDir Path dir) throws Exception {
		AssetCache cache = new AssetCache(dir);
		byte[] png = bytes("strip", 64);
		String sha = AssetCache.sha256(png);
		cache.put(sha, png);
		Files.write(cache.file(sha), bytes("tampered", 64));
		assertNull(cache.read(sha, 1000), "never used");
		assertFalse(Files.exists(cache.file(sha)), "deleted: the next load downloads it again");
		cache.put(sha, png);
		assertArrayEquals(png, cache.read(sha, 1000), "and the verified copy takes its place");
		assertNull(cache.read(sha, 10), "larger than the caller allows: not read");
	}

	@Test
	void theLeastRecentlyUsedGoFirstPastTheByteLimit(@TempDir Path dir) throws Exception {
		AssetCache cache = new AssetCache(dir, 2500, 100, null);
		List<String> shas = new ArrayList<>();
		long t = System.currentTimeMillis() - 3_600_000;
		for (int i = 0; i < 4; i++) {
			byte[] b = bytes("file" + i, 1000);
			String sha = AssetCache.sha256(b);
			cache.put(sha, b);
			Files.setLastModifiedTime(cache.file(sha), FileTime.fromMillis(t + i * 60_000L));
			shas.add(sha);
		}
		// Reading the oldest makes it the most recently used (it was last marked more than TOUCH_MILLIS ago).
		assertTrue(cache.read(shas.get(0), 10_000) != null);
		cache.prune();
		assertTrue(cache.has(shas.get(0)), "used a moment ago: kept");
		assertFalse(cache.has(shas.get(1)), "the least recently used goes first");
		assertFalse(cache.has(shas.get(2)));
		assertTrue(cache.has(shas.get(3)));
	}

	@Test
	void theFileLimitHoldsToo(@TempDir Path dir) throws Exception {
		AssetCache cache = new AssetCache(dir, Long.MAX_VALUE, 2, null);
		long t = System.currentTimeMillis() - 3_600_000;
		List<String> shas = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			byte[] b = bytes("n" + i, 10);
			String sha = AssetCache.sha256(b);
			cache.put(sha, b);
			Files.setLastModifiedTime(cache.file(sha), FileTime.fromMillis(t + i * 1000L));
			shas.add(sha);
		}
		cache.prune();
		assertFalse(cache.has(shas.get(0)));
		assertTrue(cache.has(shas.get(1)) && cache.has(shas.get(2)));
	}

	@Test
	void concurrentWritersNeverLeaveAHalfWrittenFile(@TempDir Path dir) throws Exception {
		AssetCache one = new AssetCache(dir);
		AssetCache other = new AssetCache(dir);
		List<byte[]> files = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			files.add(bytes("concurrent" + i, 256 * 1024 + i));
		}
		ExecutorService pool = Executors.newFixedThreadPool(8);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<?>> jobs = new ArrayList<>();
		try {
			for (int w = 0; w < 16; w++) {
				AssetCache cache = w % 2 == 0 ? one : other;
				int first = w;
				jobs.add(pool.submit(() -> {
					go.await();
					for (int k = 0; k < files.size(); k++) {
						byte[] b = files.get((first + k) % files.size());
						String sha = AssetCache.sha256(b);
						cache.put(sha, b);
						byte[] read = cache.read(sha, Integer.MAX_VALUE);
						// Present (whole and verified) or, on Windows, briefly replaced: never half a file.
						if (read != null) {
							assertArrayEquals(b, read);
						}
					}
					return null;
				}));
			}
			go.countDown();
			for (Future<?> job : jobs) {
				job.get(60, TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdownNow();
		}
		for (byte[] b : files) {
			assertArrayEquals(b, one.read(AssetCache.sha256(b), Integer.MAX_VALUE));
		}
		try (Stream<Path> all = Files.walk(dir)) {
			assertEquals(0, all.filter(p -> p.toString().endsWith(".tmp")).count(), "every temporary file renamed or removed");
		}
	}

	@Test
	void pruningKeepsAWritersFreshTempFileAndTheOldLayoutGoes(@TempDir Path dir) throws Exception {
		AssetCache cache = new AssetCache(dir);
		byte[] b = bytes("x", 10);
		String sha = AssetCache.sha256(b);
		cache.put(sha, b);
		Path folder = cache.file(sha).getParent();
		Path fresh = Files.writeString(folder.resolve("abc.1.tmp"), "being written");
		Path stale = Files.writeString(folder.resolve("abc.2.tmp"), "crashed");
		Files.setLastModifiedTime(stale, FileTime.fromMillis(System.currentTimeMillis() - AssetCache.STALE_TEMP_MILLIS - 60_000));
		Path oldLayout = Files.writeString(dir.resolve("0".repeat(64) + ".png"), "old per-profile file");
		cache.prune();
		assertTrue(Files.exists(fresh), "another process may still be writing it");
		assertFalse(Files.exists(stale));
		assertFalse(Files.exists(oldLayout), "the earlier flat layout is deleted");
		assertTrue(cache.has(sha));
	}

	@Test
	void aSharedCacheDeletesTheProfilesOldOneAndNoHandoffKeepsIt(@TempDir Path root) throws Exception {
		Path profile = root.resolve("profile").resolve("flint").resolve("cache").resolve("cosmetics");
		Files.createDirectories(profile);
		Files.writeString(profile.resolve("a".repeat(64) + ".png"), "old");
		AssetCache own = AssetCache.forGame(null, profile);
		assertEquals(profile, own.dir());
		AssetCache shared = AssetCache.forGame(root.resolve("shared"), profile);
		assertEquals(root.resolve("shared"), shared.dir());
		shared.prune();
		assertFalse(Files.exists(profile), "the per-profile cache is gone once the shared one is used");
	}

	@Test
	void downloadsAndHitsAreCounted(@TempDir Path dir) {
		AssetCache cache = new AssetCache(dir);
		byte[] b = bytes("count", 2048);
		String sha = AssetCache.sha256(b);
		assertNull(cache.read(sha, 10_000));
		cache.downloaded(sha, b.length, "cape");
		cache.put(sha, b);
		cache.read(sha, 10_000);
		cache.read(sha, 10_000);
		assertEquals(new AssetCache.Stats(1, 2048, 2, 4096), cache.stats());
	}
}
