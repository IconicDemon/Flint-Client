package com.flintclient.client.control.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LibraryQueryTest {
	private static List<String> ids(List<ModuleDescriptor> modules) {
		List<String> ids = new ArrayList<>();
		for (ModuleDescriptor module : modules) {
			ids.add(module.id());
		}
		return ids;
	}

	private static List<String> search(String query) {
		return ids(LibraryQuery.filter(query, LibraryQuery.Chip.ALL, Set.of(), List.of()));
	}

	@Test
	void everythingInCategoryThenNameOrder() {
		assertEquals(List.of("fps", "ping", "armor", "cps", "effects", "keystrokes", "coordinates", "direction"), search(""));
		assertEquals(search(""), search("   "), "blank search is no search");
	}

	@Test
	void searchMatchesNamesDescriptionsAndKeywords() {
		assertEquals(List.of("fps"), search("fps"));
		assertEquals(List.of("fps"), search("FPS"), "case-insensitive");
		assertEquals(List.of("fps"), search("framerate"), "keyword");
		assertEquals(List.of("fps"), search("frame"), "word prefix");
		assertEquals(List.of("ping"), search("latency"));
		assertEquals(List.of("coordinates"), search("coord"));
		assertEquals(List.of("direction"), search("compass"));
		assertEquals(List.of("armor"), search("armour"), "British spelling as a keyword");
		assertEquals(List.of("effects"), search("potions"));
		assertTrue(search("pvp").containsAll(List.of("cps", "keystrokes")));
	}

	@Test
	void searchIgnoresAccents() {
		assertEquals(List.of("coordinates"), search("coördinates"), "coördinates");
		assertEquals(List.of("coordinates"), search("COÖRD"));
		assertEquals(List.of("coordinates"), search("coördinates"), "a combining diaeresis too");
		assertEquals("eaeu", LibraryQuery.fold("Éàëü"));
	}

	@Test
	void everyWordMustMatch() {
		assertEquals(List.of("cps"), search("click speed"));
		assertEquals(List.of(), search("click latency"));
		assertEquals(List.of("ping"), search("server  ms"), "extra spaces don't matter");
	}

	@Test
	void noMatchMeansAnEmptyList() {
		assertEquals(List.of(), search("minimap"));
		assertEquals(List.of(), search("zzz"));
	}

	@Test
	void categoryChipsFilter() {
		assertEquals(List.of("fps", "ping"), ids(LibraryQuery.filter("", LibraryQuery.Chip.of(Category.PERFORMANCE), Set.of(), List.of())));
		assertEquals(List.of("armor", "cps", "effects", "keystrokes"),
			ids(LibraryQuery.filter("", LibraryQuery.Chip.of(Category.COMBAT), Set.of(), List.of())));
		assertEquals(List.of("coordinates", "direction"), ids(LibraryQuery.filter("", LibraryQuery.Chip.of(Category.WORLD), Set.of(), List.of())));
		assertEquals(List.of("keystrokes"), ids(LibraryQuery.filter("keys", LibraryQuery.Chip.of(Category.COMBAT), Set.of(), List.of())));
		assertEquals(List.of(), ids(LibraryQuery.filter("fps", LibraryQuery.Chip.of(Category.WORLD), Set.of(), List.of())));
	}

	@Test
	void favoritesFilterInStableOrder() {
		Set<String> favorites = Set.of("direction", "fps", "effects", "minimap");
		assertEquals(List.of("fps", "effects", "direction"), ids(LibraryQuery.filter("", LibraryQuery.Chip.FAVORITES, favorites, List.of())));
		assertEquals(List.of("effects"), ids(LibraryQuery.filter("potion", LibraryQuery.Chip.FAVORITES, favorites, List.of())));
	}

	@Test
	void recentsListTheNewestFirst() {
		List<String> recents = List.of("armor", "fps", "minimap", "cps");
		assertEquals(List.of("armor", "fps", "cps"), ids(LibraryQuery.filter("", LibraryQuery.Chip.RECENT, Set.of(), recents)),
			"unknown ids are skipped");
		assertEquals(List.of("fps"), ids(LibraryQuery.filter("frames", LibraryQuery.Chip.RECENT, Set.of(), recents)));
	}

	@Test
	void recentsKeepTheLastFourChangedWithoutRepeats() {
		List<String> recents = List.of();
		for (String id : List.of("fps", "ping", "cps", "fps", "armor", "effects")) {
			recents = LibraryQuery.withRecent(recents, id);
		}
		assertEquals(List.of("effects", "armor", "fps", "cps"), recents);
		assertEquals(List.of("cps", "effects", "armor", "fps"), LibraryQuery.withRecent(recents, "cps"));
		assertEquals(recents, LibraryQuery.withRecent(recents, "effects"), "already newest: unchanged");
	}

	@Test
	void favoritesAndRecentChipsOnlyWhenTheyHaveSomething() {
		assertEquals(List.of("All", "Performance", "Combat", "World"), labels(LibraryQuery.chips(Set.of(), List.of())));
		assertEquals(List.of("All", "Performance", "Combat", "World"), labels(LibraryQuery.chips(Set.of("minimap"), List.of("gone"))),
			"ids of unknown modules don't count");
		assertEquals(List.of("All", "Favorites", "Performance", "Combat", "World"), labels(LibraryQuery.chips(Set.of("fps"), List.of())));
		assertEquals(List.of("All", "Favorites", "Recent", "Performance", "Combat", "World"),
			labels(LibraryQuery.chips(Set.of("fps"), List.of("cps"))));
		assertEquals(List.of("All", "Recent", "Performance", "Combat", "World"), labels(LibraryQuery.chips(Set.of(), List.of("cps"))));
	}

	private static List<String> labels(List<LibraryQuery.Chip> chips) {
		List<String> labels = new ArrayList<>();
		for (LibraryQuery.Chip chip : chips) {
			labels.add(chip.label());
		}
		return labels;
	}
}
