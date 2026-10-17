package com.flintclient.client.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.perf.PerfConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NetworkConfigTest {
	@TempDir
	Path dir;

	private PerfConfig load(String json) throws IOException {
		Path file = dir.resolve(PerfConfig.FILE_NAME);
		Files.writeString(file, json, StandardCharsets.UTF_8);
		return PerfConfig.load(file);
	}

	@Test
	void theDeveloperNetworkLogIsOffByDefault() throws IOException {
		assertFalse(PerfConfig.load(dir.resolve(PerfConfig.FILE_NAME)).network(), "no file");
		assertFalse(load("{\"schema\": 1, \"dev\": {\"frameStatsLog\": true}}").network());
	}

	@Test
	void theDeveloperNetworkLogFollowsItsFlag() throws IOException {
		PerfConfig config = load("{\"schema\": 1, \"dev\": {\"network\": true}}");
		assertTrue(config.network());
		assertFalse(config.frameStatsLog(), "each developer flag is its own");
		assertTrue(config.enabled("networkCore", true), "networkCore has no override");
	}

	@Test
	void aWrongTypeMeansDefaults() throws IOException {
		PerfConfig config = load("{\"dev\": {\"network\": \"yes\"}}");
		assertFalse(config.network());
		assertEquals("defaults (flint-performance.json unusable)", config.source());
	}
}
