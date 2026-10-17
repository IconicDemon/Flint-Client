package com.flintclient.client.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PerfConfigTest {
	/** What the launcher writes (schema 1). */
	private static final String LAUNCHER_FILE = """
		{ "schema": 1,
		  "writtenBy": "Flint 0.5.0",
		  "automaticOptimization": true,
		  "profile": { "gameVersion": "26.3", "loader": "fabric" },
		  "hardware": { "memoryMb": 16384, "logicalCpus": 8, "performanceCores": 4, "efficiencyCores": 4,
		                "arch": "aarch64", "os": "macos" },
		  "jvm": { "policy": "legacy", "heapMaxMb": 4096 },
		  "modules": {},
		  "dev": { "frameStatsLog": false } }
		""";

	@TempDir
	Path dir;

	private static void assertDefaults(PerfConfig config) {
		assertTrue(config.automaticOptimization());
		assertFalse(config.frameStatsLog());
		assertFalse(config.gpuResources());
		assertFalse(config.fontTiming());
		assertTrue(config.enabled("any.module", true), "no overrides");
		assertFalse(config.enabled("any.module", false), "no overrides");
	}

	private Path write(String json) throws IOException {
		Path file = dir.resolve(PerfConfig.FILE_NAME);
		Files.writeString(file, json, StandardCharsets.UTF_8);
		return file;
	}

	@Test
	void aMissingFileMeansDefaults() {
		PerfConfig config = PerfConfig.load(dir.resolve(PerfConfig.FILE_NAME));
		assertDefaults(config);
		assertEquals("defaults (no flint-performance.json)", config.source());
	}

	@Test
	void readsTheLauncherFile() throws IOException {
		PerfConfig config = PerfConfig.load(write(LAUNCHER_FILE));
		assertDefaults(config);
		assertEquals("flint-performance.json from Flint 0.5.0", config.source());
	}

	@Test
	void readsTheSettingTheOverridesAndTheDeveloperFlag() {
		PerfConfig config = PerfConfig.parse("""
			{ "schema": 1, "automaticOptimization": false,
			  "modules": { "on.module": true, "off.module": false },
			  "dev": { "frameStatsLog": true } }
			""");
		assertFalse(config.automaticOptimization());
		assertTrue(config.frameStatsLog());
		assertFalse(config.gpuResources(), "each developer flag is its own");
		assertFalse(config.fontTiming(), "each developer flag is its own");
		assertTrue(config.enabled("on.module", false), "an override wins over the default");
		assertFalse(config.enabled("off.module", true), "an override wins over the default");
		assertTrue(config.enabled("other.module", true), "modules without an override keep their default");
		assertFalse(config.enabled("other.module", false), "modules without an override keep their default");
	}

	@Test
	void readsTheGraphicsDeveloperFlags() {
		PerfConfig both = PerfConfig.parse("{\"dev\": {\"gpuResources\": true, \"fontTiming\": true}}");
		assertTrue(both.gpuResources());
		assertTrue(both.fontTiming());
		assertFalse(both.frameStatsLog());
		assertTrue(both.automaticOptimization());
		PerfConfig one = PerfConfig.parse("{\"dev\": {\"frameStatsLog\": false, \"fontTiming\": true}}");
		assertFalse(one.gpuResources());
		assertTrue(one.fontTiming());
	}

	@Test
	void missingKeysAndUnknownKeysKeepDefaults() {
		assertDefaults(PerfConfig.parse("{}"));
		assertDefaults(PerfConfig.parse("{\"schema\": 1, \"future\": [1, {\"x\": null}], \"dev\": {\"later\": 3}}"));
		assertEquals("flint-performance.json", PerfConfig.parse("{}").source());
	}

	@Test
	void corruptJsonIsRejected() {
		for (String json : new String[] {"", "{", "{\"automaticOptimization\": true", "not json at all", "{} {}", "[]", "null", "true"}) {
			assertThrows(IllegalArgumentException.class, () -> PerfConfig.parse(json), json);
		}
	}

	@Test
	void wrongTypesAreRejected() {
		for (String json : new String[] {
			"{\"automaticOptimization\": \"false\"}",
			"{\"automaticOptimization\": 0}",
			"{\"automaticOptimization\": null}",
			"{\"modules\": []}",
			"{\"modules\": {\"some.module\": \"true\"}}",
			"{\"modules\": {\"some.module\": 1}}",
			"{\"dev\": true}",
			"{\"dev\": {\"frameStatsLog\": \"yes\"}}",
			"{\"dev\": {\"gpuResources\": 1}}",
			"{\"dev\": {\"fontTiming\": null}}",
			"{\"writtenBy\": 5}",
			"{\"schema\": \"1\"}",
			"{\"schema\": 1.5}",
			"{\"schema\": 0}",
		}) {
			assertThrows(IllegalArgumentException.class, () -> PerfConfig.parse(json), json);
		}
	}

	@Test
	void aNewerSchemaIsRejected() {
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
			() -> PerfConfig.parse("{\"schema\": 2, \"automaticOptimization\": false}"));
		assertTrue(error.getMessage().contains("newer"), error.getMessage());
	}

	@Test
	void anUnusableFileMeansDefaults() throws IOException {
		for (String json : new String[] {
			"{\"automaticOptimization\": false,",
			"{\"automaticOptimization\": false, \"modules\": {\"x\": \"on\"}}",
			"{\"schema\": 2, \"automaticOptimization\": false, \"dev\": {\"frameStatsLog\": true}}",
		}) {
			PerfConfig config = PerfConfig.load(write(json));
			assertDefaults(config);
			assertEquals("defaults (flint-performance.json unusable)", config.source(), json);
		}
	}
}
