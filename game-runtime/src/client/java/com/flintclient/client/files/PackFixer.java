package com.flintclient.client.files;

import com.flintclient.client.FlintLog;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.jspecify.annotations.Nullable;

/**
 * Resource packs, shader packs and worlds put into the game's folders in a form the game can't read, made readable, on
 * every Minecraft line (this class touches no Minecraft: each line's own code points it at the game folder, asks it to
 * look while a screen showing one of the folders is open, and shows what it says).
 *
 * <ul>
 * <li>A .rar, .7z or .tar archive (Minecraft and Iris read packs only as .zip files or folders, worlds only as folders):
 * opened with the computer's own archiver ({@code tar}, which is libarchive's bsdtar on Windows 10 and 11 and on macOS)
 * and made into what the game reads.
 * <li>A resource pack .zip with its pack a folder down (a pack zipped together with its folder, as many downloads are), with
 * backslashes in its paths, or holding several packs (or packs zipped inside it): each pack becomes its own .zip with
 * {@code pack.mcmeta} at the top.
 * <li>A world in any archive: unpacked into its own folder.
 * <li>A pack or world folder with its content one folder down (a download unpacked into a folder of the same name): the
 * inner folder takes its place.
 * </ul>
 *
 * What is made takes the archive's place, under its name; the archive itself is kept as it was in {@code
 * flint/originals/<folder>} of the game folder, never deleted. Everything runs on one low-priority worker: a file still being
 * copied in waits until it holds still, and the screen showing the folder picks up the result (Minecraft's pack screen
 * watches its folder; the line's code refreshes the others).
 */
public final class PackFixer {
	/** The folders looked after, with what marks one pack or world in them. */
	public enum Kind {
		RESOURCE_PACKS("resourcepacks", "your resource packs", "a resource pack", "packs", "pack.mcmeta", false),
		SHADER_PACKS("shaderpacks", "your shader packs", "a shader pack", "shader packs", "shaders", true),
		WORLDS("saves", "your worlds", "a world", "worlds", "level.dat", false);

		final String folder;
		final String where;
		final String what;
		final String plural;
		/** The file (or, with {@link #markerIsFolder}, the folder) at the top of one pack or world. */
		final String marker;
		final boolean markerIsFolder;

		Kind(String folder, String where, String what, String plural, String marker, boolean markerIsFolder) {
			this.folder = folder;
			this.where = where;
			this.what = what;
			this.plural = plural;
			this.marker = marker;
			this.markerIsFolder = markerIsFolder;
		}

		/** The folder's name in the game folder. */
		public String folder() {
			return folder;
		}
	}

	/** What the worker tells the line's code (on the worker's thread). */
	public interface Listener {
		/**
		 * {@code message} for the player; {@code changed} when {@code kind}'s folder has something new in it (the screen
		 * showing it should look again).
		 */
		void said(Kind kind, String message, boolean changed);
	}

	/** How long a file must hold still (same size and time) before it is taken: it may still be being copied in. */
	static final long SETTLE_MILLIS = 1200;
	/** The most an archive may unpack to, and the most entries it may have: past them it is left as it is. */
	static final long MAX_BYTES = 8L << 30;
	static final int MAX_ENTRIES = 400_000;
	/** Archives inside an archive are opened this many levels down. */
	private static final int MAX_DEPTH = 2;
	private static final List<String> ARCHIVES = List.of(".rar", ".7z", ".tar", ".tar.gz", ".tgz", ".tar.xz", ".txz", ".tar.bz2", ".tbz2");

	private static final Object LOCK = new Object();
	private static @Nullable PackFixer instance;
	private static @Nullable ScheduledExecutorService worker;

	private final Path gameDir;
	private final Listener listener;
	/** The command that turns an archive into a .zip (bsdtar); null: none on this computer. Found on first use. */
	private final Archiver archiver;
	/** Files already dealt with (fine as they are, made readable, or not a pack) as they were then. */
	private final Map<Path, Stamp> judged = new HashMap<>();
	/** Files seen at the last look, to tell whether they still change. */
	private final Map<Path, Stamp> lastSeen = new HashMap<>();
	/** Files that failed once as they are now (a second failure in the same state is final). */
	private final Map<Path, Stamp> failedOnce = new HashMap<>();
	private final Map<Kind, AtomicBoolean> queued = new EnumMap<>(Kind.class);

	PackFixer(Path gameDir, Listener listener, Archiver archiver) {
		this.gameDir = gameDir;
		this.listener = listener;
		this.archiver = archiver;
		for (Kind kind : Kind.values()) {
			queued.put(kind, new AtomicBoolean());
		}
	}

	/** Starts looking after {@code gameDir}'s folders, telling {@code listener}. Once, at start. */
	public static void start(Path gameDir, Listener listener) {
		synchronized (LOCK) {
			if (instance != null) {
				return;
			}
			ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
				Thread thread = new Thread(r, "Flint pack fixer");
				thread.setDaemon(true);
				thread.setPriority(Thread.MIN_PRIORITY);
				return thread;
			});
			executor.setRemoveOnCancelPolicy(true);
			worker = executor;
			instance = new PackFixer(gameDir.toAbsolutePath().normalize(), listener, Archiver.system());
		}
	}

	/**
	 * Looks at {@code kind}'s folder soon (on the worker; at most one look waits at a time): what is new or changed is
	 * taken once it holds still. {@code tell}: also say why a file there isn't readable (while a screen shows the folder).
	 */
	public static void look(Kind kind, boolean tell) {
		look(kind, tell, 0);
	}

	/** {@link #look} for every folder, after {@code delayMillis} (at start, out of the way of the game's own loading). */
	public static void lookAll(boolean tell, long delayMillis) {
		for (Kind kind : Kind.values()) {
			look(kind, tell, delayMillis);
		}
	}

	private static void look(Kind kind, boolean tell, long delayMillis) {
		PackFixer fixer;
		ScheduledExecutorService executor;
		synchronized (LOCK) {
			fixer = instance;
			executor = worker;
		}
		if (fixer == null || executor == null || !fixer.queued.get(kind).compareAndSet(false, true)) {
			return;
		}
		executor.schedule(() -> {
			fixer.queued.get(kind).set(false);
			try {
				fixer.scan(kind, tell, true);
			} catch (RuntimeException | LinkageError e) {
				FlintLog.LOGGER.warn("Flint: couldn't look at the {} folder", kind.folder, e);
			}
		}, delayMillis, TimeUnit.MILLISECONDS);
	}

	/** Looks at {@code kind}'s folder now (the worker, or a test). {@code settle}: wait for new files to hold still. */
	void scan(Kind kind, boolean tell, boolean settle) {
		Path folder = gameDir.resolve(kind.folder);
		if (!Files.isDirectory(folder)) {
			return;
		}
		List<Path> entries = new ArrayList<>();
		try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
			for (Path file : files) {
				entries.add(file);
			}
		} catch (IOException e) {
			return;
		}
		entries.sort(Comparator.naturalOrder());
		Set<Path> present = new java.util.HashSet<>(entries);
		boolean again = false;
		for (Path file : entries) {
			String name = file.getFileName().toString();
			if (name.startsWith(".")) {
				continue;
			}
			BasicFileAttributes attributes;
			try {
				attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			} catch (IOException e) {
				continue;
			}
			Format format = attributes.isDirectory() ? Format.FOLDER : attributes.isRegularFile() ? Format.of(name) : Format.NONE;
			if (format == Format.NONE || (format == Format.ZIP && kind == Kind.SHADER_PACKS)) {
				// Iris reads shader pack .zips as they are, its pack a folder down or not.
				continue;
			}
			Stamp stamp = format == Format.FOLDER ? Stamp.of(file, attributes) : new Stamp(attributes.size(), attributes.lastModifiedTime().toMillis());
			if (stamp.equals(judged.get(file))) {
				continue;
			}
			if (settle && !stamp.equals(lastSeen.put(file, stamp))) {
				again = true;
				continue;
			}
			again |= take(kind, file, format, stamp, tell);
		}
		lastSeen.keySet().removeIf(path -> path.startsWith(folder) && !present.contains(path));
		judged.keySet().removeIf(path -> path.startsWith(folder) && !present.contains(path));
		failedOnce.keySet().removeIf(path -> path.startsWith(folder) && !present.contains(path));
		if (again) {
			look(kind, tell, SETTLE_MILLIS);
		}
	}

	/** Deals with one file or folder; true when it should be looked at again later. */
	private boolean take(Kind kind, Path file, Format format, Stamp stamp, boolean tell) {
		String name = file.getFileName().toString();
		try {
			Outcome outcome = format == Format.FOLDER ? fixFolder(kind, file) : fixArchive(kind, file, format);
			judged.put(file, stamp);
			failedOnce.remove(file);
			switch (outcome.result) {
				case FINE -> {
				}
				case FIXED -> {
					FlintLog.LOGGER.info("Flint: made {} readable in {}: {} (the original is kept in {})", name, kind.folder, outcome.made,
						gameDir.relativize(outcome.kept));
					listener.said(kind, ready(kind, outcome.made), true);
				}
				case NOT_OURS -> {
					// Folders that are no pack are left unmentioned (mods keep folders of their own there); files are told.
					if (tell && format != Format.FOLDER) {
						listener.said(kind, "“" + shortName(name) + "” isn't " + kind.what + (kind == Kind.RESOURCE_PACKS ? " (no pack.mcmeta in it)" : ""), false);
					}
				}
			}
			return false;
		} catch (Unreadable e) {
			judged.put(file, stamp);
			FlintLog.LOGGER.warn("Flint: couldn't open {} in {}: {}", name, kind.folder, e.detail);
			listener.said(kind, "Couldn't open “" + shortName(name) + "”: " + e.getMessage(), false);
			return false;
		} catch (IOException | RuntimeException e) {
			// Busy (still being copied, open elsewhere) or damaged: once more when it has held still, then it is told.
			if (stamp.equals(failedOnce.put(file, stamp))) {
				judged.put(file, stamp);
				FlintLog.LOGGER.warn("Flint: couldn't make {} in {} readable", name, kind.folder, e);
				listener.said(kind, "Couldn't open “" + shortName(name) + "”: it looks damaged or not fully downloaded", false);
				return false;
			}
			return true;
		}
	}

	private static String ready(Kind kind, List<String> made) {
		String first = shortName(Format.stem(made.get(0)));
		return made.size() == 1 ? "“" + first + "” is ready in " + kind.where : made.size() + " " + kind.plural + " from one file are ready in " + kind.where;
	}

	/** A name as shown in a notice: long ones end in an ellipsis. */
	static String shortName(String name) {
		return name.length() <= 40 ? name : name.substring(0, 38).stripTrailing() + "…";
	}

	// ---------- archives ----------

	private Outcome fixArchive(Kind kind, Path file, Format format) throws IOException {
		Path work = workDir();
		try {
			Path zip = file;
			if (format == Format.OTHER) {
				zip = work.resolve("archive.zip");
				archiver.toZip(file, zip, work);
			}
			List<Built> built = new ArrayList<>();
			Contents contents = Contents.read(zip);
			if (kind != Kind.WORLDS && format == Format.ZIP && contents.fineAsItIs(kind)) {
				return Outcome.FINE_AS_IT_IS;
			}
			collect(kind, contents, Format.stem(file.getFileName().toString()), work, 0, built);
			if (built.isEmpty()) {
				return Outcome.NOT_A_PACK;
			}
			return place(kind, file, built);
		} finally {
			deleteTree(work);
		}
	}

	/** What {@code contents} holds of {@code kind}: each pack or world made, in {@code work}; archives inside are opened. */
	private void collect(Kind kind, Contents contents, String stem, Path work, int depth, List<Built> built) throws IOException {
		List<String> roots = contents.roots(kind);
		for (String root : roots) {
			// One: the archive's own name; several: each its folder's.
			String name = roots.size() == 1 || root.isEmpty() ? stem : Format.lastSegment(root);
			Path out = work.resolve("made-" + built.size());
			if (kind == Kind.WORLDS) {
				contents.extract(root, out);
				built.add(new Built(out, Names.safe(name)));
			} else {
				contents.rewrite(root, out);
				built.add(new Built(out, Names.safe(name) + ".zip"));
			}
		}
		if (!roots.isEmpty() || depth >= MAX_DEPTH) {
			return;
		}
		// No pack at any level: packs zipped one by one inside it ("All my packs.zip").
		for (String inner : contents.innerArchives()) {
			Format format = Format.of(inner);
			if (kind == Kind.SHADER_PACKS && format == Format.ZIP) {
				Path copy = work.resolve("inner-" + built.size() + ".zip");
				contents.copyOut(inner, copy);
				if (Contents.read(copy).roots(kind).isEmpty()) {
					Files.deleteIfExists(copy);
				} else {
					built.add(new Built(copy, Names.safe(Format.stem(Format.lastSegment(inner))) + ".zip"));
				}
				continue;
			}
			Path copy = work.resolve("inner-" + built.size() + (format == Format.ZIP ? ".zip" : ".bin"));
			contents.copyOut(inner, copy);
			Path zip = copy;
			if (format == Format.OTHER) {
				zip = work.resolve("inner-" + built.size() + "-as.zip");
				try {
					archiver.toZip(copy, zip, work);
				} catch (Unreadable e) {
					continue;
				}
			}
			collect(kind, Contents.read(zip), Format.stem(Format.lastSegment(inner)), work, depth + 1, built);
		}
	}

	// ---------- folders ----------

	/** A folder with its pack or world one folder down (and nothing else there): the inner folder takes its place. */
	private Outcome fixFolder(Kind kind, Path folder) throws IOException {
		if (hasMarker(kind, folder)) {
			return Outcome.FINE_AS_IT_IS;
		}
		Path inner = null;
		try (Stream<Path> children = Files.list(folder)) {
			for (Path child : (Iterable<Path>) children::iterator) {
				String name = child.getFileName().toString();
				if (Names.junk(name)) {
					continue;
				}
				if (inner != null || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
					return Outcome.NOT_A_PACK;
				}
				inner = child;
			}
		}
		if (inner == null || !hasMarker(kind, inner)) {
			return Outcome.NOT_A_PACK;
		}
		// The inner folder moves up beside it under a passing name, the emptied folder goes to originals, and the inner
		// one takes its name.
		Path parent = folder.getParent();
		Path passing = Names.unique(parent, "." + folder.getFileName() + ".flint-moving");
		Files.move(inner, passing);
		Path kept;
		try {
			kept = keep(kind, folder);
		} catch (IOException e) {
			Files.move(passing, inner);
			throw e;
		}
		Path target = Names.unique(parent, folder.getFileName().toString());
		Files.move(passing, target);
		return Outcome.fixed(List.of(target.getFileName().toString()), kept);
	}

	private static boolean hasMarker(Kind kind, Path folder) {
		Path marker = folder.resolve(kind.marker);
		return kind.markerIsFolder ? Files.isDirectory(marker) : Files.isRegularFile(marker);
	}

	// ---------- putting it in place ----------

	/** The original goes to originals, what was made takes its place; undone if anything can't be put in place. */
	private Outcome place(Kind kind, Path original, List<Built> built) throws IOException {
		Path folder = original.getParent();
		Path kept = keep(kind, original);
		List<Path> placed = new ArrayList<>();
		try {
			Set<String> taken = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
			for (Built made : built) {
				Path target = Names.unique(folder, made.name, taken);
				taken.add(target.getFileName().toString());
				Files.move(made.path, target);
				placed.add(target);
			}
		} catch (IOException | RuntimeException e) {
			for (Path path : placed) {
				deleteTree(path);
			}
			Files.move(kept, original);
			throw e;
		}
		List<String> names = new ArrayList<>();
		for (Path path : placed) {
			names.add(path.getFileName().toString());
		}
		return Outcome.fixed(names, kept);
	}

	/** Moves {@code original} to {@code flint/originals/<folder>/}, under a name not taken there yet. */
	private Path keep(Kind kind, Path original) throws IOException {
		Path dir = gameDir.resolve("flint").resolve("originals").resolve(kind.folder);
		Files.createDirectories(dir);
		Path kept = Names.unique(dir, original.getFileName().toString());
		Files.move(original, kept);
		return kept;
	}

	private Path workDir() throws IOException {
		Path staging = gameDir.resolve("flint").resolve("unpacking");
		Files.createDirectories(staging);
		return Files.createTempDirectory(staging, "fix-");
	}

	static void deleteTree(Path path) {
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(path)) {
			for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
				try {
					Files.deleteIfExists(p);
				} catch (IOException e) {
					// Left for the next clean-up.
				}
			}
		} catch (IOException e) {
			// Left for the next clean-up.
		}
	}

	// ---------- pieces ----------

	/** A file's state: size and time (a folder: its newest entry's time and its entry count, one level down). */
	record Stamp(long size, long modified) {
		static Stamp of(Path folder, BasicFileAttributes attributes) {
			long newest = attributes.lastModifiedTime().toMillis();
			long count = 0;
			try (Stream<Path> children = Files.list(folder)) {
				for (Path child : (Iterable<Path>) children::iterator) {
					count++;
					try {
						newest = Math.max(newest, Files.getLastModifiedTime(child, LinkOption.NOFOLLOW_LINKS).toMillis());
					} catch (IOException e) {
						// Counted without its time.
					}
				}
			} catch (IOException e) {
				// The folder's own time only.
			}
			return new Stamp(count, newest);
		}
	}

	private record Built(Path path, String name) {
	}

	private enum Result {
		FINE, FIXED, NOT_OURS
	}

	private record Outcome(Result result, List<String> made, Path kept) {
		static final Outcome FINE_AS_IT_IS = new Outcome(Result.FINE, List.of(), Path.of(""));
		static final Outcome NOT_A_PACK = new Outcome(Result.NOT_OURS, List.of(), Path.of(""));

		static Outcome fixed(List<String> made, Path kept) {
			return new Outcome(Result.FIXED, made, kept);
		}
	}

	/** An archive that can't be opened here, with what the player is told ({@link #getMessage}) and what is logged. */
	static final class Unreadable extends IOException {
		final String detail;

		Unreadable(String message, String detail) {
			super(message);
			this.detail = detail;
		}
	}

	/** What a file in the folders is to this class. */
	enum Format {
		ZIP, OTHER, FOLDER, NONE;

		static Format of(String name) {
			String lower = name.toLowerCase(Locale.ROOT);
			if (lower.endsWith(".zip")) {
				return ZIP;
			}
			for (String extension : ARCHIVES) {
				if (lower.endsWith(extension)) {
					return OTHER;
				}
			}
			return NONE;
		}

		/** The name without its archive extension. */
		static String stem(String name) {
			String lower = name.toLowerCase(Locale.ROOT);
			for (String extension : ARCHIVES) {
				if (lower.endsWith(extension)) {
					return name.substring(0, name.length() - extension.length());
				}
			}
			return lower.endsWith(".zip") ? name.substring(0, name.length() - 4) : name;
		}

		static String lastSegment(String path) {
			String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
			return trimmed.substring(trimmed.lastIndexOf('/') + 1);
		}
	}

	/** File names: what to leave out, what Windows can't take, and the next free name. */
	static final class Names {
		private Names() {
		}

		/** Things zip tools and file browsers add that are no part of a pack. */
		static boolean junk(String segment) {
			return segment.equals("__MACOSX") || segment.equals(".DS_Store") || segment.equalsIgnoreCase("Thumbs.db")
				|| segment.equalsIgnoreCase("desktop.ini") || segment.startsWith("._");
		}

		/** {@code name} as a file name every system takes. */
		static String safe(String name) {
			String cleaned = name.replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", "_").strip();
			while (cleaned.endsWith(".")) {
				cleaned = cleaned.substring(0, cleaned.length() - 1).strip();
			}
			if (cleaned.length() > 120) {
				cleaned = cleaned.substring(0, 120).strip();
			}
			return cleaned.isEmpty() ? "Unpacked" : cleaned;
		}

		static Path unique(Path dir, String name) {
			return unique(dir, name, Set.of());
		}

		/** {@code name} in {@code dir}, or "name (2)", "name (3)"... when it is taken (or in {@code alsoTaken}). */
		static Path unique(Path dir, String name, Set<String> alsoTaken) {
			Path path = dir.resolve(name);
			if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !alsoTaken.contains(name)) {
				return path;
			}
			int dot = name.toLowerCase(Locale.ROOT).endsWith(".zip") ? name.length() - 4 : name.length();
			for (int i = 2; ; i++) {
				String candidate = name.substring(0, dot) + " (" + i + ")" + name.substring(dot);
				path = dir.resolve(candidate);
				if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !alsoTaken.contains(candidate)) {
					return path;
				}
			}
		}
	}

	/** A .zip's entries, by their cleaned-up names (forward slashes, no leading slash; unsafe and junk ones left out). */
	static final class Contents {
		private final Path zip;
		private final Charset charset;
		/** Cleaned name → the entry's own name in the zip. */
		private final Map<String, String> files = new LinkedHashMap<>();
		private final Set<String> folders = new LinkedHashSet<>();
		private boolean renamed;

		private Contents(Path zip, Charset charset) {
			this.zip = zip;
			this.charset = charset;
		}

		static Contents read(Path zip) throws IOException {
			try {
				return read(zip, StandardCharsets.UTF_8);
			} catch (ZipException | IllegalArgumentException e) {
				// Names in the old DOS code page (zips made on Windows without the UTF-8 flag).
				return read(zip, Charset.isSupported("IBM437") ? Charset.forName("IBM437") : StandardCharsets.ISO_8859_1);
			}
		}

		private static Contents read(Path zip, Charset charset) throws IOException {
			Contents contents = new Contents(zip, charset);
			try (ZipFile file = new ZipFile(zip.toFile(), charset)) {
				int count = 0;
				for (ZipEntry entry : Collections.list(file.entries())) {
					if (++count > MAX_ENTRIES) {
						throw new Unreadable("it has too many files in it", count + " entries");
					}
					String name = clean(entry.getName());
					if (name == null) {
						continue;
					}
					contents.renamed |= !name.equals(entry.getName());
					if (entry.isDirectory()) {
						contents.folders.add(name.endsWith("/") ? name : name + "/");
					} else {
						contents.files.put(name, entry.getName());
						for (int at = name.indexOf('/'); at >= 0; at = name.indexOf('/', at + 1)) {
							contents.folders.add(name.substring(0, at + 1));
						}
					}
				}
			}
			return contents;
		}

		/** A safe, cleaned entry name, or null to leave it out (junk, or one that would reach outside its folder). */
		static @Nullable String clean(String raw) {
			String name = raw.replace('\\', '/');
			while (name.startsWith("/") || name.startsWith("./")) {
				name = name.substring(name.startsWith("/") ? 1 : 2);
			}
			if (name.isEmpty() || name.contains(":")) {
				return null;
			}
			for (String segment : name.split("/")) {
				if (segment.equals("..") || segment.equals(".") || Names.junk(segment)) {
					return null;
				}
			}
			return name;
		}

		/** True when the game reads this .zip as it is: one pack, at the top, with plain names. */
		boolean fineAsItIs(Kind kind) {
			if (renamed) {
				return false;
			}
			return kind.markerIsFolder ? folders.contains(kind.marker + "/") : files.containsKey(kind.marker);
		}

		/** The folders ("" for the top) each holding one pack or world, outermost only. */
		List<String> roots(Kind kind) {
			Set<String> found = new TreeSet<>(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder()));
			if (kind.markerIsFolder) {
				for (String folder : folders) {
					if (folder.equals(kind.marker + "/") || folder.endsWith("/" + kind.marker + "/")) {
						found.add(folder.substring(0, folder.length() - kind.marker.length() - 1));
					}
				}
			} else {
				for (String file : files.keySet()) {
					if (file.equals(kind.marker) || file.endsWith("/" + kind.marker)) {
						found.add(file.substring(0, file.length() - kind.marker.length()));
					}
				}
			}
			List<String> outermost = new ArrayList<>();
			for (String root : found) {
				if (outermost.stream().noneMatch(root::startsWith)) {
					outermost.add(root);
				}
			}
			return outermost;
		}

		/** Archives inside, a few levels down at most. */
		List<String> innerArchives() {
			List<String> inner = new ArrayList<>();
			for (String file : files.keySet()) {
				if (Format.of(file) != Format.NONE && file.chars().filter(c -> c == '/').count() <= 2) {
					inner.add(file);
				}
			}
			return inner;
		}

		/** What is under {@code root}, as a .zip of its own with {@code root} as its top. */
		void rewrite(String root, Path out) throws IOException {
			long[] total = {0};
			try (ZipFile file = new ZipFile(zip.toFile(), charset);
				ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(out))) {
				output.setLevel(Deflater.BEST_SPEED);
				for (Map.Entry<String, String> entry : files.entrySet()) {
					if (!entry.getKey().startsWith(root)) {
						continue;
					}
					ZipEntry source = file.getEntry(entry.getValue());
					ZipEntry copy = new ZipEntry(entry.getKey().substring(root.length()));
					if (source.getLastModifiedTime() != null) {
						copy.setLastModifiedTime(source.getLastModifiedTime());
					}
					output.putNextEntry(copy);
					try (InputStream in = file.getInputStream(source)) {
						copy(in, output, total);
					}
					output.closeEntry();
				}
			}
		}

		/** What is under {@code root}, unpacked into the folder {@code out}. */
		void extract(String root, Path out) throws IOException {
			long[] total = {0};
			Files.createDirectories(out);
			Path base = out.toAbsolutePath().normalize();
			try (ZipFile file = new ZipFile(zip.toFile(), charset)) {
				for (String folder : folders) {
					if (folder.startsWith(root) && folder.length() > root.length()) {
						Files.createDirectories(inside(base, folder.substring(root.length())));
					}
				}
				for (Map.Entry<String, String> entry : files.entrySet()) {
					if (!entry.getKey().startsWith(root)) {
						continue;
					}
					ZipEntry source = file.getEntry(entry.getValue());
					Path target = inside(base, entry.getKey().substring(root.length()));
					Files.createDirectories(target.getParent());
					try (InputStream in = file.getInputStream(source); OutputStream output = Files.newOutputStream(target)) {
						copy(in, output, total);
					}
					FileTime time = source.getLastModifiedTime();
					if (time != null) {
						Files.setLastModifiedTime(target, time);
					}
				}
			}
		}

		/** One entry, as a file of its own. */
		void copyOut(String name, Path out) throws IOException {
			long[] total = {0};
			try (ZipFile file = new ZipFile(zip.toFile(), charset);
				InputStream in = file.getInputStream(file.getEntry(files.get(name)));
				OutputStream output = Files.newOutputStream(out)) {
				copy(in, output, total);
			}
		}

		private static Path inside(Path base, String relative) throws IOException {
			Path target = base.resolve(relative).normalize();
			if (!target.startsWith(base) || target.equals(base)) {
				throw new IOException("an entry reaches outside its folder: " + relative);
			}
			return target;
		}

		private static void copy(InputStream in, OutputStream out, long[] total) throws IOException {
			byte[] buffer = new byte[64 * 1024];
			for (int read; (read = in.read(buffer)) > 0; ) {
				total[0] += read;
				if (total[0] > MAX_BYTES) {
					throw new Unreadable("it unpacks to more than 8 GB", total[0] + " bytes");
				}
				out.write(buffer, 0, read);
			}
		}
	}

	/** Turns other archives into a .zip with the computer's own archiver. */
	interface Archiver {
		/** {@code archive} as the .zip {@code zip} (uncompressed: it is rewritten anyway); {@code work} is a scratch folder. */
		void toZip(Path archive, Path zip, Path work) throws IOException;

		/** bsdtar: Windows' own tar.exe (Windows 10 and 11), macOS's tar, or a bsdtar on the PATH (Linux). */
		static Archiver system() {
			return (archive, zip, work) -> {
				String tar = Tar.find();
				String type = archive.getFileName().toString();
				type = type.substring(Math.max(0, type.lastIndexOf('.'))).toLowerCase(Locale.ROOT);
				if (tar == null) {
					throw new Unreadable("this computer can't open " + type + " files; unpack it, or save it as a .zip", "no bsdtar found");
				}
				// Plain names in the scratch folder: some tar builds read only the system code page's names from their
				// command line, and the archive's name (and the game folder's) may have any letters in it.
				Path input = work.resolve("input" + type);
				try {
					Files.createLink(input, archive);
				} catch (IOException | UnsupportedOperationException e) {
					Files.copy(archive, input);
				}
				Path output = work.resolve("output.zip");
				ProcessBuilder builder = new ProcessBuilder(tar, "--format", "zip", "--options", "zip:compression=store,zip:hdrcharset=UTF-8",
					"-cf", output.getFileName().toString(), "@" + input.getFileName().toString());
				builder.directory(work.toFile());
				builder.redirectErrorStream(true);
				Process process = builder.start();
				String said;
				try (InputStream out = process.getInputStream()) {
					said = new String(out.readAllBytes(), StandardCharsets.UTF_8).strip();
				}
				try {
					if (!process.waitFor(10, TimeUnit.MINUTES)) {
						process.destroyForcibly();
						throw new Unreadable("it took too long to open", "tar ran for 10 minutes");
					}
				} catch (InterruptedException e) {
					process.destroyForcibly();
					Thread.currentThread().interrupt();
					throw new IOException("interrupted", e);
				}
				Files.deleteIfExists(input);
				if (process.exitValue() != 0 || !Files.isRegularFile(output)) {
					throw new Unreadable(Tar.reason(said), "tar exited with " + process.exitValue() + ": " + said);
				}
				try {
					Files.move(output, zip);
				} catch (FileAlreadyExistsException e) {
					Files.delete(zip);
					Files.move(output, zip);
				}
			};
		}
	}

	/** Finding bsdtar, and putting what it says in the player's words. */
	static final class Tar {
		private static volatile @Nullable String found;
		private static volatile boolean looked;

		private Tar() {
		}

		static @Nullable String find() {
			if (looked) {
				return found;
			}
			List<String> candidates = new ArrayList<>();
			String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
			if (os.contains("win")) {
				String root = System.getenv("SystemRoot");
				candidates.add((root == null ? "C:\\Windows" : root) + "\\System32\\tar.exe");
			} else {
				candidates.add("/usr/bin/bsdtar");
				candidates.add("/usr/local/bin/bsdtar");
				candidates.add("/opt/homebrew/bin/bsdtar");
				if (os.contains("mac")) {
					candidates.add("/usr/bin/tar");
				}
			}
			for (String candidate : candidates) {
				if (Files.isRegularFile(Path.of(candidate)) && isBsdtar(candidate)) {
					found = candidate;
					break;
				}
			}
			looked = true;
			return found;
		}

		private static boolean isBsdtar(String tar) {
			try {
				Process process = new ProcessBuilder(tar, "--version").redirectErrorStream(true).start();
				String version;
				try (InputStream out = process.getInputStream()) {
					version = new String(out.readAllBytes(), StandardCharsets.UTF_8);
				}
				process.waitFor(10, TimeUnit.SECONDS);
				return version.contains("bsdtar") || version.contains("libarchive");
			} catch (IOException e) {
				return false;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}

		static String reason(String said) {
			String lower = said.toLowerCase(Locale.ROOT);
			if (lower.contains("encrypt") || lower.contains("password") || lower.contains("passphrase")) {
				return "it's password-protected; unpack it with its password, or save it as a .zip";
			}
			if (lower.contains("volume")) {
				return "it's split into parts; unpack it, or save it as a .zip";
			}
			if (lower.contains("truncated") || lower.contains("damaged") || lower.contains("corrupt") || lower.contains("premature")) {
				return "it looks damaged or not fully downloaded";
			}
			if (lower.contains("unrecognized") || lower.contains("unsupported") || lower.contains("not supported")) {
				return "this computer can't open this kind of archive; unpack it, or save it as a .zip";
			}
			return "it couldn't be unpacked; unpack it, or save it as a .zip";
		}
	}
}
