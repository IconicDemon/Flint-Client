package com.flintclient.client.hotload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HotLoadRuleTest {
	private static final Set<String> LOADED = Set.of("minecraft", "fabricloader", "fabric-api", "java", "cloth-config");

	private static JsonObject fmj(String extra) {
		return JsonParser.parseString("{\"schemaVersion\":1,\"id\":\"demo\",\"version\":\"1.0\",\"environment\":\"client\"" + extra + "}").getAsJsonObject();
	}

	private static HotLoadRule.Verdict check(JsonObject fmj, List<String> entries, List<String> nested) {
		return HotLoadRule.check(fmj, entries, nested, LOADED::contains);
	}

	private static final List<String> CLASSES = List.of("fabric.mod.json", "com/demo/", "com/demo/DemoClient.class", "assets/demo/icon.png",
		"assets/demo/lang/en_us.json", "assets/demo/lang/de_de.json");

	@Test
	void aPlainClientModQualifies() {
		HotLoadRule.Verdict v = check(fmj(",\"entrypoints\":{\"client\":[\"com.demo.DemoClient\"],\"modmenu\":[\"com.demo.Menu\"]},\"depends\":{\"fabric-api\":\"*\"}"),
			CLASSES, List.of());
		assertTrue(v.ok(), v.reason());
	}

	@Test
	void mixinsAndWidenersNeedASwap() {
		assertFalse(check(fmj(",\"mixins\":[\"demo.mixins.json\"]"), CLASSES, List.of()).ok());
		assertFalse(check(fmj(",\"mixins\":[{\"config\":\"demo.mixins.json\",\"environment\":\"client\"}]"), CLASSES, List.of()).ok());
		assertFalse(check(fmj(",\"accessWidener\":\"demo.accesswidener\""), CLASSES, List.of()).ok());
		assertFalse(check(fmj(",\"languageAdapters\":{\"kotlin\":\"x.Adapter\"}"), CLASSES, List.of()).ok());
		// An empty list declares nothing.
		assertTrue(check(fmj(",\"mixins\":[]"), CLASSES, List.of()).ok());
	}

	@Test
	void startupAndContentEntrypointsNeedASwap() {
		for (String key : List.of("main", "server", "preLaunch", "mm:early_risers")) {
			HotLoadRule.Verdict v = check(fmj(",\"entrypoints\":{\"client\":[\"a.B\"],\"" + key + "\":[\"a.C\"]}"), CLASSES, List.of());
			assertFalse(v.ok(), key);
			assertEquals("has a " + key + " entrypoint", v.reason());
		}
	}

	@Test
	void contentThatNeedsAReloadNeedsASwap() {
		assertFalse(check(fmj(""), List.of("data/demo/recipe/x.json"), List.of()).ok());
		assertFalse(check(fmj(""), List.of("assets/demo/textures/gui/hud.png"), List.of()).ok());
		assertFalse(check(fmj(""), List.of("assets/demo/sounds.json"), List.of()).ok());
		assertFalse(check(fmj(""), List.of("assets/demo/lang/en_us/extra.json"), List.of()).ok());
		assertTrue(check(fmj(""), List.of("assets/", "assets/demo/", "data/"), List.of()).ok(), "folders alone are nothing");
	}

	@Test
	void nestedJarsMustBeLoadedAlready() {
		assertTrue(check(fmj(""), CLASSES, List.of("cloth-config")).ok());
		assertFalse(check(fmj(""), CLASSES, List.of("some-lib")).ok());
		assertFalse(check(fmj(""), CLASSES, java.util.Arrays.asList((String) null)).ok());
	}

	@Test
	void serverModsLoadedModsAndOldSchemasAreRefused() {
		assertFalse(check(JsonParser.parseString("{\"schemaVersion\":1,\"id\":\"demo\",\"environment\":\"server\"}").getAsJsonObject(), CLASSES, List.of()).ok());
		assertFalse(check(JsonParser.parseString("{\"schemaVersion\":1,\"id\":\"cloth-config\"}").getAsJsonObject(), CLASSES, List.of()).ok());
		assertFalse(check(JsonParser.parseString("{\"id\":\"demo\"}").getAsJsonObject(), CLASSES, List.of()).ok());
		assertFalse(HotLoadRule.check(null, CLASSES, List.of(), LOADED::contains).ok());
	}
}
