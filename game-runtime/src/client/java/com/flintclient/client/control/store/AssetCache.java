package com.flintclient.client.control.store;

import com.flintclient.client.FlintLog;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Flint's one cache of verified cosmetic files (cape textures, animation strips, 3D cosmetic files), shared by the launcher
 * ({@code app/src-tauri/src/store/asset_cache.rs}, the same layout and rules) and every game it starts, on every Minecraft line:
 * {@code <Flint data dir>/cache/assets/<first 2 hex>/<sha256>}, content-addressed, so a file is downloaded once per computer
 * whatever profile, account or session wants it. The game learns the folder from the launcher's handoff
 * ({@link StoreHandoff#assetCacheDir}); without one (a game Flint didn't start) it keeps its own in the profile
 * ({@link #forGame}).
 *
 * <ul>
 * <li>Only files whose SHA-256 the caller checked go in ({@link #put} checks again); every {@link #read} hashes the file again,
 * and one that doesn't hash to its name is deleted (it is then downloaded again).</li>
 * <li>Writes are atomic: a uniquely named {@code .tmp} file in the same folder, renamed into place, so a reader (this process
 * or another) never sees a half-written file. Two writers of the same file write the same bytes: the later rename wins, or
 * fails harmlessly (the file is there).</li>
 * <li>At most {@link #MAX_BYTES} (and {@link #MAX_FILES} files): {@link #prune} deletes the least recently used first. A read
 * marks a file used (its modification time, at most every {@link #TOUCH_MILLIS}: access times aren't kept on Windows).</li>
 * <li>{@link #downloaded} counts what came over the network, {@link #read} what didn't: one log line per download, so the
 * saving shows in the log.</li>
 * </ul>
 *
 * <p>Thread-safe; blocking (call it from a worker thread). Pure Java; version-neutral.
 */
public final class AssetCache {
	/** All files together: the least recently used go first past it. */
	public static final long MAX_BYTES = 1024L * 1024 * 1024;
	/** All files together, however small. */
	public static final int MAX_FILES = 16_384;
	/** A read marks a file used when it was last marked longer ago than this. */
	static final long TOUCH_MILLIS = 10 * 60 * 1000L;
	/** A {@code .tmp} file older than this is a crashed writer's and is deleted; younger ones may still be written. */
	static final long STALE_TEMP_MILLIS = 60 * 60 * 1000L;
	/** {@link #put} prunes after this many bytes written since the last prune. */
	static final long PRUNE_EVERY_BYTES = 64L * 1024 * 1024;
	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
	private static final Pattern FOLDER = Pattern.compile("[0-9a-f]{2}");

	private final Path dir;
	private final long maxBytes;
	private final int maxFiles;
	/** A folder of an earlier layout to delete at the first {@link #prune} (the profile's own cache, once a shared one is used). */
	private final @Nullable Path obsolete;
	private final AtomicBoolean obsoleteGone = new AtomicBoolean();
	private final AtomicLong downloads = new AtomicLong();
	private final AtomicLong downloadedBytes = new AtomicLong();
	private final AtomicLong hits = new AtomicLong();
	private final AtomicLong hitBytes = new AtomicLong();
	private final AtomicLong sincePrune = new AtomicLong();

	public AssetCache(Path dir) {
		this(dir, MAX_BYTES, MAX_FILES, null);
	}

	AssetCache(Path dir, long maxBytes, int maxFiles, @Nullable Path obsolete) {
		this.dir = dir;
		this.maxBytes = maxBytes;
		this.maxFiles = maxFiles;
		this.obsolete = obsolete;
	}

	/**
	 * The game's cache: the launcher's shared folder ({@code shared}, from its handoff) when it gave one, and the profile's
	 * old per-profile cache ({@code profileCache}) is then deleted; else {@code profileCache} itself (a game Flint didn't
	 * start), in the same layout.
	 */
	public static AssetCache forGame(@Nullable Path shared, Path profileCache) {
		if (shared == null) {
			FlintLog.LOGGER.info("Flint asset cache: {} (this profile's own: the launcher named no shared one)", profileCache);
			return new AssetCache(profileCache, MAX_BYTES, MAX_FILES, null);
		}
		FlintLog.LOGGER.info("Flint asset cache: {} (shared with the launcher and every profile)", shared);
		return new AssetCache(shared, MAX_BYTES, MAX_FILES, profileCache);
	}

	public Path dir() {
		return dir;
	}

	/** Where the file of hash {@code sha256} is kept in {@code dir}: {@code <dir>/<first 2 hex>/<sha256>}. */
	public static Path file(Path dir, String sha256) {
		return dir.resolve(sha256.substring(0, 2)).resolve(sha256);
	}

	public Path file(String sha256) {
		return file(dir, sha256);
	}

	/**
	 * The file of hash {@code sha256} (lowercase hex) while it still hashes to it and is at most {@code maxBytes}, else null
	 * (none, or one that changed on disk: deleted).
	 */
	public byte @Nullable [] read(String sha256, long maxBytes) {
		if (!SHA256.matcher(sha256).matches()) {
			return null;
		}
		Path file = file(sha256);
		try {
			BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
			if (!attributes.isRegularFile()) {
				return null;
			}
			if (attributes.size() > maxBytes) {
				return null;
			}
			byte[] bytes = Files.readAllBytes(file);
			if (!sha256(bytes).equals(sha256)) {
				// Changed on disk (or cut short by a crash outside Flint): never used; downloaded again.
				Files.deleteIfExists(file);
				return null;
			}
			long now = System.currentTimeMillis();
			if (now - attributes.lastModifiedTime().toMillis() > TOUCH_MILLIS) {
				try {
					Files.setLastModifiedTime(file, FileTime.fromMillis(now));
				} catch (IOException e) {
					// Only its place in the least-recently-used order.
				}
			}
			hits.incrementAndGet();
			hitBytes.addAndGet(bytes.length);
			return bytes;
		} catch (NoSuchFileException e) {
			return null;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** Whether the cache holds a file named {@code sha256} (not checked: {@link #read} checks). */
	public boolean has(String sha256) {
		return SHA256.matcher(sha256).matches() && Files.isRegularFile(file(sha256));
	}

	/**
	 * Keeps {@code bytes} as the file of hash {@code sha256}, atomically, unless it is there already (best effort: a cache that
	 * can't be written still lets the bytes be used). Bytes that don't hash to {@code sha256} are never kept.
	 */
	public void put(String sha256, byte[] bytes) {
		if (!SHA256.matcher(sha256).matches() || !sha256(bytes).equals(sha256)) {
			return;
		}
		Path file = file(sha256);
		try {
			if (Files.isRegularFile(file) && Files.size(file) == bytes.length) {
				// There (and checked when it is read).
				return;
			}
			Path folder = file.getParent();
			Files.createDirectories(folder);
			Path temp = Files.createTempFile(folder, sha256.substring(0, 16) + ".", ".tmp");
			try {
				Files.write(temp, bytes);
				try {
					Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException e) {
					// Another writer's rename got there first (or holds it open on Windows): the same bytes are in place.
				}
			} finally {
				Files.deleteIfExists(temp);
			}
		} catch (IOException | RuntimeException e) {
			return;
		}
		if (sincePrune.addAndGet(bytes.length) >= PRUNE_EVERY_BYTES) {
			prune();
		}
	}

	/** Counts a download ({@code what}: for the log, e.g. "cape", "preview strip") and logs one line with the totals. */
	public void downloaded(String sha256, long bytes, String what) {
		long n = downloads.incrementAndGet();
		long total = downloadedBytes.addAndGet(bytes);
		FlintLog.LOGGER.info("Flint asset cache: downloaded {} {} ({} KiB); this session {} downloads ({} KiB), {} read from the cache ({} KiB)",
			what, sha256.length() >= 12 ? sha256.substring(0, 12) : sha256, bytes / 1024, n, total / 1024, hits.get(), hitBytes.get() / 1024);
	}

	/** Downloads (and their bytes), and files read from the cache (and theirs), this session. */
	public record Stats(long downloads, long downloadedBytes, long hits, long hitBytes) {
	}

	public Stats stats() {
		return new Stats(downloads.get(), downloadedBytes.get(), hits.get(), hitBytes.get());
	}

	/**
	 * Keeps the cache within its bytes and files (the least recently used go first), deletes crashed writers' {@code .tmp} files
	 * and anything of an earlier layout in it, and the obsolete folder once. Best effort; blocking.
	 */
	public void prune() {
		sincePrune.set(0);
		if (obsolete != null && obsoleteGone.compareAndSet(false, true)) {
			deleteTree(obsolete);
		}
		if (!Files.isDirectory(dir)) {
			return;
		}
		record Kept(Path file, long time, long size) {
		}
		List<Kept> files = new ArrayList<>();
		long now = System.currentTimeMillis();
		try (DirectoryStream<Path> top = Files.newDirectoryStream(dir)) {
			for (Path entry : top) {
				String name = entry.getFileName().toString();
				if (Files.isDirectory(entry) && FOLDER.matcher(name).matches()) {
					try (DirectoryStream<Path> inner = Files.newDirectoryStream(entry)) {
						for (Path f : inner) {
							String n = f.getFileName().toString();
							BasicFileAttributes a = Files.readAttributes(f, BasicFileAttributes.class);
							if (n.endsWith(".tmp")) {
								if (now - a.lastModifiedTime().toMillis() > STALE_TEMP_MILLIS) {
									Files.deleteIfExists(f);
								}
							} else if (a.isRegularFile() && SHA256.matcher(n).matches() && n.startsWith(name)) {
								files.add(new Kept(f, a.lastModifiedTime().toMillis(), a.size()));
							}
						}
					} catch (IOException | RuntimeException e) {
						// That folder is tidied next time.
					}
				} else if (Files.isRegularFile(entry) && (name.endsWith(".png") || name.endsWith(".tmp"))) {
					// The per-profile cache's earlier layout (<sha256>.png): nothing reads it any more.
					Files.deleteIfExists(entry);
				}
			}
		} catch (IOException | RuntimeException e) {
			// A cache that can't be tidied still works.
		}
		files.sort(Comparator.comparingLong(Kept::time));
		long total = 0;
		for (Kept k : files) {
			total += k.size();
		}
		int left = files.size();
		for (Kept k : files) {
			if (left <= maxFiles && total <= maxBytes) {
				break;
			}
			try {
				Files.deleteIfExists(k.file());
			} catch (IOException | RuntimeException e) {
				// Being read by another process: it goes next time.
			}
			left--;
			total -= k.size();
		}
	}

	private static void deleteTree(Path root) {
		if (!Files.isDirectory(root)) {
			return;
		}
		try {
			Files.walkFileTree(root, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
					try {
						Files.deleteIfExists(file);
					} catch (IOException | RuntimeException e) {
						// Open elsewhere: next session.
					}
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(Path file, IOException exc) {
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path d, @Nullable IOException exc) {
					try {
						Files.deleteIfExists(d);
					} catch (IOException | RuntimeException e) {
						// A file in it is open elsewhere: the rest goes, it next session.
					}
					return FileVisitResult.CONTINUE;
				}
			});
			FlintLog.LOGGER.info("Flint asset cache: deleted the profile's old cosmetics cache {}", root);
		} catch (IOException | RuntimeException e) {
			// Tried again next session.
		}
	}

	/** Lowercase hex SHA-256 of {@code bytes}. */
	public static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
