package com.flintclient.client.control.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.model.HudThemeId;
import com.flintclient.client.control.model.Layout;
import com.flintclient.client.control.model.ModuleId;
import com.flintclient.client.control.model.Preset;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OnboardingTest {
	@TempDir
	Path config;

	private ControlSettings settings;
	private LayoutStore layouts;

	private Onboarding onboarding() {
		settings = new ControlSettings(config, JsonStoreTest.CLOCK);
		layouts = new LayoutStore(config, settings, JsonStoreTest.CLOCK);
		return new Onboarding(settings, layouts);
	}

	private long layoutFiles() throws IOException {
		Path dir = config.resolve("flintclient").resolve("layouts");
		if (!Files.isDirectory(dir)) {
			return 0;
		}
		try (Stream<Path> files = Files.list(dir)) {
			return files.count();
		}
	}

	@Test
	void showsForANewPlayerOnly() throws IOException {
		assertTrue(onboarding().shouldShow());
		Path dir = config.resolve("flintclient").resolve("layouts");
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("mine.json"), "{\"name\": \"Mine\"}", StandardCharsets.UTF_8);
		assertFalse(onboarding().shouldShow(), "existing layouts: never shown by itself");
	}

	@Test
	void skippingOnlyMarksItDone() throws IOException {
		Onboarding onboarding = onboarding();
		onboarding.skip();
		assertTrue(settings.onboardingCompleted());
		assertFalse(settings.dirty(), "saved");
		assertEquals(0, layoutFiles(), "no layout created");
		assertEquals(0, layouts.active().enabledCount());
		assertFalse(onboarding().shouldShow(), "not again after a restart");
	}

	@Test
	void finishingCreatesTheChosenPresetAndSwitchesToIt() throws IOException {
		Onboarding onboarding = onboarding();
		LayoutResult result = onboarding.finish(Preset.BALANCED, HudThemeId.CLASSIC);
		assertTrue(result.succeeded());
		assertEquals("balanced", layouts.activeId());
		Layout active = layouts.active();
		assertEquals("Balanced", active.name());
		assertEquals(HudThemeId.CLASSIC, active.appearance().theme());
		assertEquals(6, active.enabledCount());
		assertTrue(settings.onboardingCompleted());
		assertEquals("balanced", settings.activeLayout());
		assertFalse(settings.dirty());
		assertEquals(1, layoutFiles(), "only the new layout is written");
		assertFalse(onboarding().shouldShow());
	}

	@Test
	void runningItAgainNeverEditsAnExistingLayout() {
		Onboarding first = onboarding();
		first.finish(Preset.MINIMAL, HudThemeId.MINIMAL);
		layouts.active().module(ModuleId.PING).setEnabled(true);
		layouts.save();
		Onboarding again = onboarding();
		LayoutResult result = again.finish(Preset.MINIMAL, HudThemeId.FLINT);
		assertEquals("minimal-2", result.id());
		assertEquals("Minimal 2", layouts.active().name(), "a new layout, numbered");
		assertTrue(layouts.get("minimal").module(ModuleId.PING).enabled(), "the first one is untouched");
		assertEquals(HudThemeId.MINIMAL, layouts.get("minimal").appearance().theme());
	}
}
