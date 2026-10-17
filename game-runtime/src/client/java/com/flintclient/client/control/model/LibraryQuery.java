package com.flintclient.client.control.model;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The Modules page's search and filters. Search ignores case and accents ({@code "coördinates"} finds
 * Coordinates) and matches every typed word against the start of a word in a module's name, description or
 * keywords. Results keep a stable order: category order, then name; except the Recent chip, which lists the
 * most recently changed module first. Runs when the query or a filter changes, never per frame.
 */
public final class LibraryQuery {
	/** How many recently changed modules the Recent chip remembers. */
	public static final int MAX_RECENTS = 4;

	private static final Pattern MARKS = Pattern.compile("\\p{M}+");
	private static final Comparator<ModuleDescriptor> ORDER = Comparator
		.comparing(ModuleDescriptor::category)
		.thenComparing(d -> fold(d.name()));

	private LibraryQuery() {
	}

	/** A filter chip above the grid. */
	public record Chip(Kind kind, @Nullable Category category) {
		public enum Kind {
			ALL,
			FAVORITES,
			RECENT,
			CATEGORY
		}

		public static final Chip ALL = new Chip(Kind.ALL, null);
		public static final Chip FAVORITES = new Chip(Kind.FAVORITES, null);
		public static final Chip RECENT = new Chip(Kind.RECENT, null);

		public static Chip of(Category category) {
			return new Chip(Kind.CATEGORY, category);
		}

		public String label() {
			return switch (kind) {
				case ALL -> "All";
				case FAVORITES -> "Favorites";
				case RECENT -> "Recent";
				case CATEGORY -> category == null ? "" : category.label();
			};
		}
	}

	/**
	 * The chips to show: All, then Favorites and Recent only when they would list something, then each
	 * category that has a module.
	 */
	public static List<Chip> chips(Collection<String> favorites, List<String> recents) {
		List<Chip> chips = new ArrayList<>();
		chips.add(Chip.ALL);
		if (!known(favorites).isEmpty()) {
			chips.add(Chip.FAVORITES);
		}
		if (!known(recents).isEmpty()) {
			chips.add(Chip.RECENT);
		}
		for (Category category : Category.values()) {
			for (ModuleDescriptor module : ModuleRegistry.core()) {
				if (module.category() == category) {
					chips.add(Chip.of(category));
					break;
				}
			}
		}
		return chips;
	}

	/** The modules to show for this search text and chip. */
	public static List<ModuleDescriptor> filter(String query, Chip chip, Collection<String> favorites, List<String> recents) {
		List<String> tokens = tokens(query);
		List<ModuleDescriptor> result = new ArrayList<>();
		if (chip.kind() == Chip.Kind.RECENT) {
			for (String id : known(recents)) {
				ModuleDescriptor module = ModuleRegistry.get(id);
				if (matches(module, tokens)) {
					result.add(module);
				}
			}
			return result;
		}
		for (ModuleDescriptor module : ModuleRegistry.core()) {
			boolean shown = switch (chip.kind()) {
				case ALL, RECENT -> true;
				case FAVORITES -> favorites.contains(module.id());
				case CATEGORY -> module.category() == chip.category();
			};
			if (shown && matches(module, tokens)) {
				result.add(module);
			}
		}
		result.sort(ORDER);
		return result;
	}

	/** Whether {@code module} matches the search text (empty text matches everything). */
	public static boolean matches(ModuleDescriptor module, String query) {
		return matches(module, tokens(query));
	}

	private static boolean matches(ModuleDescriptor module, List<String> tokens) {
		if (tokens.isEmpty()) {
			return true;
		}
		List<String> words = new ArrayList<>();
		words(module.name(), words);
		words(module.description(), words);
		for (String keyword : module.keywords()) {
			words(keyword, words);
		}
		for (String token : tokens) {
			boolean found = false;
			for (String word : words) {
				if (word.startsWith(token)) {
					found = true;
					break;
				}
			}
			if (!found) {
				return false;
			}
		}
		return true;
	}

	/**
	 * The recents list after {@code moduleId} changed: it moves to the front, appears once, and only the
	 * newest {@value #MAX_RECENTS} are kept.
	 */
	public static List<String> withRecent(List<String> recents, String moduleId) {
		List<String> next = new ArrayList<>(MAX_RECENTS);
		next.add(moduleId);
		for (String id : recents) {
			if (next.size() == MAX_RECENTS) {
				break;
			}
			if (!next.contains(id)) {
				next.add(id);
			}
		}
		return List.copyOf(next);
	}

	/** Lower case with accents and other combining marks removed, for comparing what people type. */
	public static String fold(String text) {
		String decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD);
		return MARKS.matcher(decomposed).replaceAll("").toLowerCase(Locale.ROOT);
	}

	/** The search text as folded words. */
	static List<String> tokens(String query) {
		List<String> tokens = new ArrayList<>();
		words(query, tokens);
		return tokens;
	}

	/** Splits folded {@code text} at anything that isn't a letter or digit. */
	private static void words(String text, List<String> out) {
		String folded = fold(text);
		int start = -1;
		for (int i = 0; i <= folded.length(); i++) {
			boolean letter = i < folded.length() && Character.isLetterOrDigit(folded.charAt(i));
			if (letter && start < 0) {
				start = i;
			} else if (!letter && start >= 0) {
				out.add(folded.substring(start, i));
				start = -1;
			}
		}
	}

	/** The ids that name a registered module, in order and without repeats. */
	private static List<String> known(Collection<String> ids) {
		List<String> result = new ArrayList<>();
		for (String id : ids) {
			if (ModuleRegistry.find(id) != null && !result.contains(id)) {
				result.add(id);
			}
		}
		return result;
	}
}
