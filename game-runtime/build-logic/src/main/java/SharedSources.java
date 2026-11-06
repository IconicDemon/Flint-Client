import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The shared sources adapted for another Minecraft line (the obfuscated 1.21.x lines, and the Minecraft-free part of the
 * shared sources for 1.8.9): a copy of src/client/java with the line's rules applied, so the shared files themselves (and
 * every 26.x build) stay untouched. Used by ../legacy and ../legacy189 (their buildSrc compiles this folder). A line's overlay may build on another
 * (1.21.10 on 1.21.11): the overlays are layers, applied in turn, each to what the ones before it made. Rules file, one
 * rule per line:
 *
 * <pre>
 * # a comment
 * word OLD NEW              whole-word rename in every file
 * word-in GLOB OLD NEW      the same in the files the glob matches (relative like PATH below; ** for any folders)
 * replace OLD NEW           literal replacement in every file (a moved class's package, say)
 * s-in GLOB REGEX =&gt; REPL  regex replace in the files the glob matches; must match somewhere
 * file PATH                 the rules after it, up to the next "file", apply to this file only (relative to
 *                           src/client/java, or to com/flintclient/client)
 * s REGEX =&gt; REPLACEMENT    regex replace in that file ($1 groups allowed); must match at least once
 * s* REGEX =&gt; REPLACEMENT   the same, matching any number of times (zero included)
 * skip PATH                 leave the file out (the overlay has a file of its own, or the line doesn't need it)
 * include GLOB              keep only the files the layer's includes match (with none, every file is kept): a line that
 *                           takes part of the shared sources (1.8.9: the Minecraft-free packages); each must match a file.
 *                           For the tests, shared-tests.rules has its own includes (those in shared-sources.rules name
 *                           source files)
 * </pre>
 *
 * A layer's rules apply to the shared files as the layers before it left them, their overlay files included; then the
 * layer's own java files are added, replacing any of the same path (no rules of that layer apply to them). A rule that
 * matches nothing, or a file or skip that names a missing file, fails the build: when a file changes, the rule is
 * revisited.
 */
public final class SharedSources {
	private record Replace(Pattern pattern, String replacement, boolean optional, String text) {
	}

	/** A rename or replacement over many files (those its glob matches, or all); it must match somewhere. */
	private record Global(Pattern pattern, String replacement, java.nio.file.PathMatcher files, String text, int[] hits) {
	}

	/** An include: the files it keeps, and how many it matched (it must match one). */
	private record Include(java.nio.file.PathMatcher files, String text, int[] hits) {
	}

	private record Rules(List<Global> globals, Map<String, List<Replace>> perFile, Set<String> skips, List<Include> includes) {
	}

	private SharedSources() {
	}

	/** One layer's overlay: its rules and its java files (either may be missing). */
	public record Layer(java.io.File rules, java.io.File java) {
	}

	/** The single-overlay form (the overlay's rules, then its java files). */
	public static void adapt(java.io.File shared, java.io.File overlay, java.io.File rules, java.io.File out) {
		adapt(shared, List.of(new Layer(rules, overlay)), out);
	}

	/**
	 * The shared sources through each layer in turn (base first), into {@code out}: every file the line compiles. Layer
	 * {@code i} is {@code rules.get(i)} and {@code java.get(i)}.
	 */
	public static void adapt(java.io.File shared, List<java.io.File> rules, List<java.io.File> java, java.io.File out) {
		List<Layer> layers = new ArrayList<>();
		for (int i = 0; i < rules.size(); i++) {
			layers.add(new Layer(rules.get(i), java.get(i)));
		}
		adapt(shared, layers, out);
	}

	/** The shared sources through each layer in turn (base first), into {@code out}: every file the line compiles. */
	public static void adapt(java.io.File shared, List<Layer> layers, java.io.File out) {
		try {
			Map<String, String> tree = read(shared.toPath());
			List<String> problems = new ArrayList<>();
			for (Layer layer : layers) {
				Rules rules = parse(layer.rules().toPath(), false);
				Map<String, String> own = layer.java() != null && layer.java().isDirectory() ? read(layer.java().toPath()) : Map.of();
				tree = apply(tree, rules, own.keySet(), problems, true, layer.rules().getName());
				tree.putAll(own);
			}
			fail(problems);
			write(tree, out.toPath());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** The single-overlay form of {@link #adaptTests(java.io.File, List, java.io.File)}. */
	public static void adaptTests(java.io.File tests, java.io.File rules, java.io.File testRules, java.io.File out) {
		adaptTests(tests, List.of(rules, testRules), out);
	}

	/**
	 * The unit tests (src/test/java), for each layer in turn: the layer's shared-sources renames (its per-file rules are
	 * for the shared sources), then its test rules (shared-tests.rules, when the layer has one), whose per-file rules
	 * name test files. {@code ruleFiles}: each layer's shared-sources.rules and shared-tests.rules, in pairs.
	 */
	public static void adaptTests(java.io.File tests, List<java.io.File> ruleFiles, java.io.File out) {
		try {
			Map<String, String> tree = read(tests.toPath());
			List<String> problems = new ArrayList<>();
			for (int i = 0; i < ruleFiles.size(); i += 2) {
				tree = apply(tree, parse(ruleFiles.get(i).toPath(), true), Set.of(), problems, false, ruleFiles.get(i).getName());
				java.io.File testRules = ruleFiles.get(i + 1);
				if (testRules.exists()) {
					tree = apply(tree, parse(testRules.toPath(), false), Set.of(), problems, false, testRules.getName());
				}
			}
			fail(problems);
			write(tree, out.toPath());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static Rules parse(Path rules, boolean globalsOnly) throws IOException {
		List<Global> globals = new ArrayList<>();
		Map<String, List<Replace>> perFile = new HashMap<>();
		Set<String> skips = new HashSet<>();
		List<Include> includes = new ArrayList<>();
		if (!Files.exists(rules)) {
			return new Rules(globals, perFile, skips, includes);
		}
		String current = null;
		int lineNo = 0;
		for (String raw : Files.readAllLines(rules, StandardCharsets.UTF_8)) {
			lineNo++;
			String line = raw.strip();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			String where = rules.getFileName() + ":" + lineNo;
			if (line.startsWith("word ") || line.startsWith("word-in ") || line.startsWith("replace ")) {
				String[] p = line.split("\\s+");
				boolean in = p[0].equals("word-in");
				if (p.length != (in ? 4 : 3)) {
					throw new IllegalArgumentException(where + ": word OLD NEW | word-in GLOB OLD NEW | replace OLD NEW");
				}
				String from = p[in ? 2 : 1];
				String to = p[in ? 3 : 2];
				Pattern pattern = p[0].equals("replace") ? Pattern.compile(Pattern.quote(from)) : Pattern.compile("\\b" + Pattern.quote(from) + "\\b");
				java.nio.file.PathMatcher files = in ? java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + path(p[1])) : null;
				globals.add(new Global(pattern, Matcher.quoteReplacement(to), files, where + " " + line, new int[1]));
			} else if (line.startsWith("s-in ")) {
				String rest = line.substring(5).strip();
				int space = rest.indexOf(' ');
				String body = rest.substring(space + 1);
				if (body.endsWith(" =>")) {
					body += " ";
				}
				int arrow = body.indexOf(" => ");
				if (space < 0 || arrow < 0) {
					throw new IllegalArgumentException(where + ": s-in GLOB REGEX => REPLACEMENT");
				}
				java.nio.file.PathMatcher files = java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + path(rest.substring(0, space)));
				globals.add(new Global(Pattern.compile(body.substring(0, arrow)), unescape(body.substring(arrow + 4)), files, where + " " + line, new int[1]));
			} else if (line.startsWith("file ")) {
				current = path(line.substring(5).strip());
				if (!globalsOnly) {
					perFile.computeIfAbsent(current, k -> new ArrayList<>());
				}
			} else if (line.startsWith("include ")) {
				if (!globalsOnly) {
					java.nio.file.PathMatcher files = java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + path(line.substring(8).strip()));
					includes.add(new Include(files, where + " " + line, new int[1]));
				}
			} else if (line.startsWith("skip ")) {
				if (!globalsOnly) {
					skips.add(path(line.substring(5).strip()));
				}
			} else if (line.startsWith("s ") || line.startsWith("s* ")) {
				if (current == null) {
					throw new IllegalArgumentException(where + ": an s rule needs a file before it");
				}
				boolean optional = line.startsWith("s* ");
				String body = line.substring(optional ? 3 : 2);
				if (body.endsWith(" =>")) {
					body += " ";
				}
				int arrow = body.indexOf(" => ");
				if (arrow < 0) {
					throw new IllegalArgumentException(where + ": s REGEX => REPLACEMENT");
				}
				if (!globalsOnly) {
					perFile.get(current).add(new Replace(Pattern.compile(body.substring(0, arrow)), unescape(body.substring(arrow + 4)), optional,
						where + " " + body.substring(0, arrow)));
				}
			} else {
				throw new IllegalArgumentException(where + ": unknown rule: " + line);
			}
		}
		return new Rules(globals, perFile, skips, includes);
	}

	/**
	 * The files of {@code tree} with the rules applied, leaving out the skipped ones and those the layer replaces
	 * ({@code replaced}); a rule that matches nothing is a problem (globals only when {@code checkGlobals}).
	 */
	private static Map<String, String> apply(Map<String, String> tree, Rules rules, Set<String> replaced, List<String> problems,
		boolean checkGlobals, String name) {
		Map<String, String> out = new TreeMap<>();
		for (Map.Entry<String, String> e : tree.entrySet()) {
			String rel = e.getKey();
			if (replaced.contains(rel) || rules.skips().contains(rel) || !included(rules, rel)) {
				continue;
			}
			String text = e.getValue();
			for (Global g : rules.globals()) {
				if (g.files != null && !g.files.matches(Path.of(rel))) {
					continue;
				}
				Matcher m = g.pattern.matcher(text);
				if (m.find()) {
					g.hits[0]++;
					text = m.replaceAll(g.replacement);
				}
			}
			for (Replace r : rules.perFile().getOrDefault(rel, List.of())) {
				Matcher m = r.pattern.matcher(text);
				if (!m.find()) {
					if (!r.optional) {
						problems.add(r.text + " matches nothing in " + rel);
					}
					continue;
				}
				text = m.replaceAll(r.replacement);
			}
			out.put(rel, text);
		}
		for (Include i : rules.includes()) {
			if (i.hits[0] == 0) {
				problems.add(i.text + " matches nothing");
			}
		}
		for (String rel : rules.perFile().keySet()) {
			if (!tree.containsKey(rel)) {
				problems.add(name + " names a file that doesn't exist: " + rel);
			} else if (!out.containsKey(rel) && !replaced.contains(rel) && !rules.skips().contains(rel)) {
				problems.add(name + " patches a file its includes leave out: " + rel);
			} else if (replaced.contains(rel) && !rules.perFile().get(rel).isEmpty()) {
				problems.add(name + " patches a file its overlay replaces: " + rel);
			}
		}
		if (checkGlobals) {
			for (Global g : rules.globals()) {
				if (g.hits[0] == 0) {
					problems.add(g.text + " matches nothing");
				}
			}
		}
		for (String rel : rules.skips()) {
			if (!tree.containsKey(rel)) {
				problems.add(name + " skips a file that doesn't exist: " + rel);
			}
		}
		return out;
	}

	/** Whether the layer keeps the file: it has no includes, or one of them matches it (every match is counted). */
	private static boolean included(Rules rules, String rel) {
		if (rules.includes().isEmpty()) {
			return true;
		}
		boolean kept = false;
		for (Include i : rules.includes()) {
			if (i.files.matches(Path.of(rel))) {
				i.hits[0]++;
				kept = true;
			}
		}
		return kept;
	}

	private static void fail(List<String> problems) {
		if (!problems.isEmpty()) {
			throw new IllegalStateException("the overlay's rules are out of date:\n  " + String.join("\n  ", problems));
		}
	}

	/** Every file under {@code root} by its relative path; rules match LF line ends, whatever a checkout gave the file. */
	private static Map<String, String> read(Path root) throws IOException {
		Map<String, String> out = new TreeMap<>();
		List<Path> files;
		try (Stream<Path> walk = Files.walk(root)) {
			files = walk.filter(Files::isRegularFile).toList();
		}
		for (Path f : files) {
			out.put(rel(root, f), Files.readString(f, StandardCharsets.UTF_8).replace("\r\n", "\n"));
		}
		return out;
	}

	private static void write(Map<String, String> tree, Path out) throws IOException {
		deleteTree(out);
		Files.createDirectories(out);
		for (Map.Entry<String, String> e : tree.entrySet()) {
			Path target = out.resolve(e.getKey());
			Files.createDirectories(target.getParent());
			Files.writeString(target, e.getValue(), StandardCharsets.UTF_8);
		}
	}

	/** A replacement as written in the rules: backslash-n and backslash-t are a newline and a tab ($1 and \$ keep their meaning). */
	private static String unescape(String replacement) {
		return replacement.replace("\\n", "\n").replace("\\t", "\t");
	}

	/** A path in the rules: relative to src/client/java, or to its com/flintclient/client package when it doesn't start with com/. */
	private static String path(String p) {
		return p.startsWith("com/") ? p : "com/flintclient/client/" + p;
	}

	private static String rel(Path root, Path file) {
		return root.relativize(file).toString().replace('\\', '/');
	}

	private static void deleteTree(Path dir) throws IOException {
		if (!Files.exists(dir)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(dir)) {
			for (Path p : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
				Files.delete(p);
			}
		}
	}
}
