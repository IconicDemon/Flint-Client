package com.flintclient.client.control.feature;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

/**
 * Mods that put capes of their own on players. A Flint Store cape is the cape in the player's skin; these mods set the
 * skin's cape again after Flint does (or draw their own in the cape layer) whenever their service has a cape for that
 * player, so while one is loaded it can hide the Flint cape in this game. The Cosmetics window says so when a cape is worn there ({@code control/rshift/FlintCosmeticsScreen});
 * the equip itself goes ahead, as the cape is the account's.
 *
 * <p>Read on 2026-11-06 from each mod's own jar on Modrinth (its {@code fabric.mod.json} ids and the mixins that set the
 * cape; MinecraftCapes also in its Ornithe 1.8.9 build) and OptiFabric's source. Left out because the Flint cape still shows
 * with them: mods that only animate or restyle the skin's own cape (Wavey Capes, Legacy WaveyCapes, Elytra Physics), and mods
 * that change the textures Minecraft downloads, which Flint's cape goes on top of (skin overrides, CustomSkinLoader).
 * The launcher has the same list for its Store page (app/src-tauri/src/capemods.rs {@code CAPE_MODS}; its test
 * {@code the_game_has_the_same_list} reads the entries below, one {@code new Known(...)} per line, and fails when they
 * differ).
 */
public final class CapeMods {
	/** A mod by the name players know it by, and the Fabric mod ids its jars declare. */
	public record Known(String name, List<String> ids) {
		Known(String name, String... ids) {
			this(name, List.of(ids));
		}
	}

	public static final List<Known> KNOWN = List.of(
		new Known("Essential", "essential", "essential-container"),
		new Known("Capes", "capes"),
		new Known("Cape Provider", "cape-provider"),
		new Known("Better Capes", "bettercapes"),
		new Known("Cosmetica", "cosmetica", "cosmetica-core"),
		new Known("MinecraftCapes", "minecraftcapes"),
		new Known("MoreCosmetics", "morecosmetics"),
		new Known("OptiFabric", "optifabric"),
		new Known("Custom Capes", "ep-custom-capes"),
		new Known("OptiFine Capes Reborn", "optifine_capes_reborn"),
		new Known("SkinMC", "skinmc_mod"),
		new Known("Client Side Cosmetics", "clientsidecosmetics"),
		new Known("CapeMod", "capemod"),
		new Known("Team Capes", "team_capes"),
		new Known("Cape Provider X", "cape-provider-x"),
		new Known("Custom Capes", "capesmod"),
		new Known("Client Capes", "iicapemod")
	);

	private static @Nullable List<String> inThisGame;

	private CapeMods() {
	}

	/** The names of the cape mods {@code loaded} says are loaded (it answers by Fabric mod id), in list order, each once. */
	public static List<String> loaded(Predicate<String> loaded) {
		List<String> out = new ArrayList<>();
		for (Known mod : KNOWN) {
			if (!out.contains(mod.name()) && mod.ids().stream().anyMatch(loaded)) {
				out.add(mod.name());
			}
		}
		return List.copyOf(out);
	}

	/** The cape mods loaded in this game, from Fabric Loader's list (asked once: it doesn't change while the game runs). */
	public static List<String> inThisGame() {
		List<String> known = inThisGame;
		if (known == null) {
			try {
				known = loaded(FabricLoader.getInstance()::isModLoaded);
			} catch (RuntimeException | LinkageError e) {
				known = List.of();
			}
			inThisGame = known;
		}
		return known;
	}

	/** "The Capes mod", "The Capes and Essential mods", "The Capes, Essential and SkinMC mods". */
	static String named(List<String> names) {
		StringBuilder out = new StringBuilder("The ");
		for (int i = 0; i < names.size(); i++) {
			if (i > 0) {
				out.append(i == names.size() - 1 ? " and " : ", ");
			}
			out.append(names.get(i));
		}
		return out.append(names.size() == 1 ? " mod" : " mods").toString();
	}

	/** The notice's title. */
	public static String noticeTitle(List<String> names) {
		return names.size() == 1 ? "A cape mod is on" : "Cape mods are on";
	}

	/** What the notice says when a cape is worn while {@code names} are loaded. */
	public static String notice(List<String> names) {
		boolean one = names.size() == 1;
		return named(names) + (one ? " is" : " are") + " on in this game and can hide your Flint cape here. Turn "
			+ (one ? "that mod" : "those mods") + " off in the Flint app to see your cape.";
	}

	/** The Cosmetics window's quiet line while {@code names} are loaded. */
	public static String hint(List<String> names) {
		return named(names) + (names.size() == 1 ? " is" : " are") + " on and can hide your Flint cape in this game.";
	}
}
