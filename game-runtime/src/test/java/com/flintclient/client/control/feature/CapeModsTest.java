package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CapeModsTest {
	/** A game with exactly these Fabric mod ids loaded. */
	private static List<String> loadedWith(String... ids) {
		Set<String> loaded = Set.of(ids);
		return CapeMods.loaded(loaded::contains);
	}

	@Test
	void aCapeModLoadedInTheGameIsNamed() {
		assertEquals(List.of("Capes"), loadedWith("fabricloader", "minecraft", "flintclient", "capes"));
		assertEquals(List.of("Essential"), loadedWith("essential-container"), "Essential's mods-folder jar");
		assertEquals(List.of("Essential"), loadedWith("essential", "essential-container", "essential-loader"), "named once");
		assertEquals(List.of("MinecraftCapes"), loadedWith("minecraftcapes"), "its 1.8.9 (Ornithe) build has the same id");
	}

	@Test
	void modsThatOnlyAnimateTheCapeOrChangeDownloadedSkinsNeverCount() {
		assertEquals(List.of(), loadedWith("waveycapes", "legacy-waveycapes", "elytra_physics", "skin_overrides", "customskinloader-bootstrap",
			"fabrictailor", "capecommand", "sodium", "flintclient"));
		assertEquals(List.of(), loadedWith(), "a game without mods");
		assertEquals(List.of(), loadedWith("capes-addon", "essentials"), "ids match whole");
	}

	@Test
	void severalAreNamedInTheListsOrderEachOnce() {
		assertEquals(List.of("Capes", "Cosmetica", "MinecraftCapes", "Custom Capes"),
			loadedWith("minecraftcapes", "capesmod", "cosmetica-core", "cosmetica", "ep-custom-capes", "capes"));
	}

	@Test
	void theNoticeSaysWhichModsAreOnAndWhatToDo() {
		assertEquals("A cape mod is on", CapeMods.noticeTitle(List.of("Capes")));
		assertEquals("The Capes mod is on in this game and can hide your Flint cape here. Turn that mod off in the Flint app to see your cape.",
			CapeMods.notice(List.of("Capes")));
		assertEquals("Cape mods are on", CapeMods.noticeTitle(List.of("Capes", "Essential")));
		assertEquals("The Capes and Essential mods are on in this game and can hide your Flint cape here. Turn those mods off in the Flint app to "
			+ "see your cape.", CapeMods.notice(List.of("Capes", "Essential")));
		assertEquals("The Capes mod is on and can hide your Flint cape in this game.", CapeMods.hint(List.of("Capes")));
		assertEquals("The Capes, Essential and SkinMC mods are on and can hide your Flint cape in this game.",
			CapeMods.hint(List.of("Capes", "Essential", "SkinMC")));
	}

	@Test
	void theListIsWellFormed() {
		Set<String> seen = new HashSet<>();
		for (CapeMods.Known mod : CapeMods.KNOWN) {
			assertTrue(!mod.name().isBlank() && !mod.ids().isEmpty(), mod.name());
			for (String id : mod.ids()) {
				assertTrue(id.matches("[a-z0-9_-]+"), id + ": Fabric mod ids are lower case");
				assertTrue(seen.add(id), id + " is listed twice");
			}
		}
	}
}
