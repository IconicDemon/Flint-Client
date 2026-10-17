package com.flintclient.client.perf;

import java.util.StringJoiner;
import java.util.function.Predicate;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Which well-known performance mods are loaded, taken once at start-up, so Flint's modules can step aside
 * where one of them already covers the same ground. None does yet: today's modules only touch Flint's own
 * menu, so this is only logged.
 */
public record Compat(
	boolean sodium,
	boolean iris,
	boolean lithium,
	boolean ferritecore,
	boolean immediatelyfast,
	boolean entityculling,
	boolean dynamicFps,
	boolean moreculling,
	boolean modernfix,
	boolean c2me
) {
	public static Compat detect() {
		return of(FabricLoader.getInstance()::isModLoaded);
	}

	/** {@code loaded} answers by Fabric mod id. */
	static Compat of(Predicate<String> loaded) {
		return new Compat(
			loaded.test("sodium"),
			loaded.test("iris"),
			loaded.test("lithium"),
			loaded.test("ferritecore"),
			loaded.test("immediatelyfast"),
			loaded.test("entityculling"),
			loaded.test("dynamic_fps"),
			loaded.test("moreculling"),
			loaded.test("modernfix"),
			loaded.test("c2me")
		);
	}

	/** The loaded ones by mod id, for example "sodium, lithium", or "none". */
	public String describe() {
		StringJoiner present = new StringJoiner(", ").setEmptyValue("none");
		add(present, sodium, "sodium");
		add(present, iris, "iris");
		add(present, lithium, "lithium");
		add(present, ferritecore, "ferritecore");
		add(present, immediatelyfast, "immediatelyfast");
		add(present, entityculling, "entityculling");
		add(present, dynamicFps, "dynamic_fps");
		add(present, moreculling, "moreculling");
		add(present, modernfix, "modernfix");
		add(present, c2me, "c2me");
		return present.toString();
	}

	private static void add(StringJoiner present, boolean loaded, String id) {
		if (loaded) {
			present.add(id);
		}
	}
}
