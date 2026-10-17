package com.flintclient.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.module.servers.QuickPlay;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FlintConfigTest {
	@TempDir
	Path dir;

	private FlintConfig read() {
		FlintConfig config = new FlintConfig(dir.resolve("flintclient.json"));
		config.load();
		return config;
	}

	@Test
	void noFileMeansNothingPinned() {
		assertTrue(read().quickPlay().isEmpty());
		assertFalse(Files.exists(dir.resolve("flintclient.json")), "reading writes nothing");
	}

	@Test
	void pinsSurviveARestartInOrder() {
		FlintConfig first = read();
		List<QuickPlay.Entry> pins = List.of(new QuickPlay.Entry("One", "one.test"), new QuickPlay.Entry("Two", "two.test:25570"));
		first.setQuickPlay(pins);
		assertEquals(pins, read().quickPlay());
	}

	@Test
	void theOldFiveSlotFileKeepsOnlyAssignedSlotsAndOtherKeys() throws Exception {
		Files.writeString(dir.resolve("flintclient.json"), """
			{"shortcuts": [null, {"name": "Hub", "address": "hub.test"}, null, null, {"address": "bare.test"}], "future": 7}
			""", StandardCharsets.UTF_8);
		FlintConfig config = read();
		assertEquals(List.of(new QuickPlay.Entry("Hub", "hub.test"), new QuickPlay.Entry("bare.test", "bare.test")), config.quickPlay());
		config.setQuickPlay(config.quickPlay());
		String written = Files.readString(dir.resolve("flintclient.json"), StandardCharsets.UTF_8);
		assertFalse(written.contains("shortcuts"), "the old key goes once the file is written");
		assertTrue(written.contains("\"future\": 7"), "unknown keys are kept");
		assertEquals(config.quickPlay(), read().quickPlay());
	}

	@Test
	void anAllEmptyOldFileMeansNothingPinned() throws Exception {
		Files.writeString(dir.resolve("flintclient.json"), "{\"shortcuts\": [null, null, null, null, null]}", StandardCharsets.UTF_8);
		assertTrue(read().quickPlay().isEmpty());
	}

	@Test
	void aDamagedFileMeansNothingPinned() throws Exception {
		Files.writeString(dir.resolve("flintclient.json"), "{ not json", StandardCharsets.UTF_8);
		assertTrue(read().quickPlay().isEmpty());
		Files.writeString(dir.resolve("flintclient.json"), "{\"quickPlay\": [{\"address\": \"a b\"}, 5, {\"name\": \"x\"}]}", StandardCharsets.UTF_8);
		assertTrue(read().quickPlay().isEmpty(), "unusable entries are dropped");
	}
}
