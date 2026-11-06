package com.flintclient.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The shared sources that must stay platform-free:
 * <ul>
 * <li>those Minecraft 1.8.9 compiles (src/mc189/shared-sources.rules: its includes, less its skips): 1.8.9 is a different
 * engine, so a Minecraft call there breaks the 1.8.9 build;</li>
 * <li>those declared platform-free (src/platform-free.rules): the menu, the kit and the canvas, which reach the game only
 * through the platform classes ({@code platform/}), which each engine provides at the same names.</li>
 * </ul>
 * This test runs in the 26.x and 1.21.x builds, so such a change fails where it is made, not later in a 1.8.9 build. Each
 * kept file must not:
 * <ul>
 * <li>mention {@code net.minecraft}, {@code com.mojang}, {@code org.joml} or {@code org.lwjgl};</li>
 * <li>import or name a shared class outside the kept set (or the 1.8.9 overlay's own files, src/mc189/java; or, for the
 * platform-free set, a platform class);</li>
 * <li>use a class of its own package that isn't kept (no import is needed for that).</li>
 * </ul>
 * Comments and text in quotes don't count (javac resolves neither as code).
 */
class PlatformFreeSourcesTest {
	private static final Pattern PLATFORM = Pattern.compile("\\b(net\\.minecraft|com\\.mojang|org\\.joml|org\\.lwjgl)\\.");
	private static final Pattern SHARED_NAME = Pattern.compile("\\bcom\\.flintclient\\.client((?:\\.[A-Za-z_][A-Za-z0-9_]*)+)(\\.\\*)?");
	/** What javac doesn't resolve as code: text blocks, strings, chars and comments (matched left to right, so a quote in a comment or slashes in a string stay theirs). */
	private static final Pattern NOT_CODE = Pattern.compile("\"\"\".*?\"\"\"|\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])+'|/\\*.*?\\*/|//[^\\n]*",
		Pattern.DOTALL);
	private static final String ROOT_PACKAGE = "com/flintclient/client/";
	/** The platform classes: each engine has its own file of the same name (26.x and 1.21.x in src/client/java). */
	private static final String PLATFORM_DIR = ROOT_PACKAGE + "platform/";

	/** The game-runtime folder (the tests run in it, or in legacy/ for the 1.21.x lines). */
	private static Path gameRuntime() {
		for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
			if (Files.isRegularFile(dir.resolve("src/mc189/shared-sources.rules")) && Files.isDirectory(dir.resolve("src/client/java"))) {
				return dir;
			}
		}
		throw new IllegalStateException("no game-runtime folder above " + Path.of("").toAbsolutePath());
	}

	/** A rules path as SharedSources reads it: relative to src/client/java, or to its com/flintclient/client package. */
	private static String rulesPath(String p) {
		return p.startsWith("com/") ? p : ROOT_PACKAGE + p;
	}

	/** Every .java file under {@code root}, by its path relative to it (forward slashes). */
	private static Set<String> javaFiles(Path root) throws IOException {
		Set<String> out = new TreeSet<>();
		if (!Files.isDirectory(root)) {
			return out;
		}
		try (Stream<Path> walk = Files.walk(root)) {
			walk.filter(f -> f.toString().endsWith(".java")).forEach(f -> out.add(root.relativize(f).toString().replace('\\', '/')));
		}
		return out;
	}

	/** The files a rules file keeps from the shared sources: the includes' matches, less the skips. */
	private static Set<String> kept(Path runtime, String rules, Set<String> shared) throws IOException {
		List<PathMatcher> includes = new ArrayList<>();
		Set<String> skips = new TreeSet<>();
		for (String raw : Files.readAllLines(runtime.resolve(rules), StandardCharsets.UTF_8)) {
			String line = raw.strip();
			if (line.startsWith("include ")) {
				includes.add(FileSystems.getDefault().getPathMatcher("glob:" + rulesPath(line.substring(8).strip())));
			} else if (line.startsWith("skip ")) {
				skips.add(rulesPath(line.substring(5).strip()));
			}
		}
		assertFalse(includes.isEmpty(), rules + " lists no includes");
		Set<String> out = new TreeSet<>();
		for (String rel : shared) {
			if (!skips.contains(rel) && includes.stream().anyMatch(m -> m.matches(Path.of(rel)))) {
				out.add(rel);
			}
		}
		return out;
	}

	@Test
	void the_shared_sources_1_8_9_compiles_never_touch_minecraft() throws IOException {
		Path runtime = gameRuntime();
		Path sharedRoot = runtime.resolve("src/client/java");
		Set<String> shared = javaFiles(sharedRoot);
		Set<String> overlay = javaFiles(runtime.resolve("src/mc189/java"));
		Set<String> kept = kept(runtime, "src/mc189/shared-sources.rules", shared);
		assertTrue(kept.size() > 50, "the 1.8.9 include list keeps only " + kept.size() + " files");
		check(sharedRoot, shared, kept, overlay, "Minecraft 1.8.9 compiles these shared files (src/mc189/shared-sources.rules), so they must stay "
			+ "platform-free. Move the call behind a version's compat or platform code, or keep the file out of 1.8.9:\n");
	}

	@Test
	void the_platform_free_shared_sources_never_touch_minecraft() throws IOException {
		Path runtime = gameRuntime();
		Path sharedRoot = runtime.resolve("src/client/java");
		Set<String> shared = javaFiles(sharedRoot);
		Set<String> declared = kept(runtime, "src/platform-free.rules", shared);
		Set<String> allowed = new TreeSet<>(kept(runtime, "src/mc189/shared-sources.rules", shared));
		allowed.addAll(declared);
		for (String file : shared) {
			if (file.startsWith(PLATFORM_DIR)) {
				allowed.add(file);
			}
		}
		assertTrue(declared.size() > 50, "src/platform-free.rules keeps only " + declared.size() + " files");
		TreeMap<String, List<String>> problems = new TreeMap<>();
		collect(sharedRoot, shared, declared, allowed, Set.of(), problems);
		report(problems, "These shared files are declared platform-free (src/platform-free.rules): every engine compiles them, reaching the "
			+ "game only through the platform classes (platform/). Move the call into a platform class, or out of the file:\n");
	}

	/** {@code kept} checked against {@code kept} and the overlay's files. */
	private static void check(Path sharedRoot, Set<String> shared, Set<String> kept, Set<String> overlay, String heading) throws IOException {
		Set<String> allowed = new TreeSet<>(kept);
		allowed.addAll(overlay);
		TreeMap<String, List<String>> problems = new TreeMap<>();
		collect(sharedRoot, shared, kept, allowed, overlay, problems);
		report(problems, heading);
	}

	/**
	 * Each of {@code kept} (less those the {@code overlay} replaces with a line's own copy) against the rules above: what it
	 * may name is {@code allowed}.
	 */
	private static void collect(Path sharedRoot, Set<String> shared, Set<String> kept, Set<String> allowed, Set<String> overlay,
		TreeMap<String, List<String>> problems) throws IOException {
		for (String rel : kept) {
			if (overlay.contains(rel)) {
				continue;
			}
			String code = NOT_CODE.matcher(Files.readString(sharedRoot.resolve(rel), StandardCharsets.UTF_8)).replaceAll(" ");
			List<String> found = new ArrayList<>();
			Matcher platform = PLATFORM.matcher(code);
			while (platform.find()) {
				found.add("mentions " + platform.group(1));
			}
			Matcher named = SHARED_NAME.matcher(code);
			while (named.find()) {
				for (String target : files(named.group(1), named.group(2) != null, shared, overlay)) {
					if (!allowed.contains(target)) {
						found.add("uses com.flintclient.client" + named.group(1) + (named.group(2) != null ? ".*" : "") + " (" + target
							+ ", which isn't in the set)");
					}
				}
			}
			String dir = rel.substring(0, rel.lastIndexOf('/') + 1);
			for (String other : shared) {
				if (other.startsWith(dir) && other.indexOf('/', dir.length()) < 0 && !allowed.contains(other)) {
					String simple = other.substring(dir.length(), other.length() - ".java".length());
					if (Pattern.compile("\\b" + Pattern.quote(simple) + "\\b").matcher(code).find()) {
						found.add("uses " + simple + " of its own package, which isn't in the set");
					}
				}
			}
			if (!found.isEmpty()) {
				problems.put(rel, found.stream().distinct().toList());
			}
		}
	}

	private static void report(TreeMap<String, List<String>> problems, String heading) {
		if (!problems.isEmpty()) {
			StringBuilder text = new StringBuilder(heading);
			problems.forEach((file, found) -> found.forEach(f -> text.append("  ").append(file).append(": ").append(f).append('\n')));
			fail(text.toString());
		}
	}

	/**
	 * The files a qualified name needs, relative to src/client/java: for {@code .control.model.Foo}, {@code .control.model.Foo.Inner}
	 * or {@code .control.Foo.member}, the longest prefix that is a file; for a package wildcard ({@code .control.model.*}),
	 * every file of that package (types of a wildcard-imported package can be used without naming them). None for a package
	 * name alone.
	 */
	private static List<String> files(String dotted, boolean wildcard, Set<String> shared, Set<String> overlay) {
		String[] parts = dotted.substring(1).split("\\.");
		if (wildcard) {
			String dir = ROOT_PACKAGE + String.join("/", parts) + "/";
			return shared.stream().filter(f -> f.startsWith(dir) && f.indexOf('/', dir.length()) < 0).toList();
		}
		for (int n = parts.length; n > 0; n--) {
			String file = ROOT_PACKAGE + String.join("/", java.util.Arrays.copyOf(parts, n)) + ".java";
			if (shared.contains(file) || overlay.contains(file)) {
				return List.of(file);
			}
		}
		return List.of();
	}
}
