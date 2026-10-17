package com.flintclient.client.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PerformanceCoreTest {
	private static final Compat NO_MODS = Compat.of(id -> false);

	/** Stands in for a later optimization: on by default only with Automatic Optimization. */
	private record Optimization(String id) implements PerfModule {
		@Override
		public boolean defaultEnabled(PerfConfig config, Compat compat) {
			return config.automaticOptimization() && !compat.sodium();
		}

		@Override
		public void start() {
		}
	}

	/** Writes down its callbacks; {@code failOn} names the one that throws ("start", "joined" or "left"). */
	private record Recorder(String id, String failOn, List<String> calls) implements PerfModule {
		private void call(String name) {
			calls.add(id + " " + name);
			if (name.equals(failOn)) {
				throw new IllegalStateException("test failure");
			}
		}

		@Override
		public boolean defaultEnabled(PerfConfig config, Compat compat) {
			return true;
		}

		@Override
		public void start() {
			call("start");
		}

		@Override
		public void onWorldJoined() {
			call("joined");
		}

		@Override
		public void onWorldLeft() {
			call("left");
		}
	}

	private static List<String> enabled(String json, Compat compat) {
		List<PerfModule> all = List.of(new Optimization("a"), new FrameStatsLogModule(), new Optimization("b"));
		return PerformanceCore.select(all, PerfConfig.parse(json), compat).stream().map(PerfModule::id).toList();
	}

	@Test
	void modulesFollowTheirDefaults() {
		assertEquals(List.of("a", "b"), enabled("{}", NO_MODS));
		assertEquals(List.of(), enabled("{\"automaticOptimization\": false}", NO_MODS), "Automatic Optimization off");
		assertEquals(List.of(), enabled("{}", Compat.of(id -> id.equals("sodium"))), "a default can depend on other mods");
	}

	@Test
	void anOverrideWinsOverTheDefault() {
		assertEquals(List.of("b"), enabled("{\"modules\": {\"a\": false}}", NO_MODS));
		assertEquals(List.of("a"), enabled("{\"automaticOptimization\": false, \"modules\": {\"a\": true}}", NO_MODS));
		assertEquals(List.of("a", "b"), enabled("{\"modules\": {\"a\": true, \"b\": true}}", Compat.of(id -> id.equals("sodium"))));
	}

	@Test
	void theFrameStatsLogFollowsTheDeveloperFlagOnly() {
		assertEquals(List.of("a", "dev.frameStatsLog", "b"), enabled("{\"dev\": {\"frameStatsLog\": true}}", NO_MODS));
		assertEquals(List.of("dev.frameStatsLog"),
			enabled("{\"automaticOptimization\": false, \"dev\": {\"frameStatsLog\": true}}", NO_MODS),
			"measurement stays available with Automatic Optimization off");
		assertEquals(List.of("a", "b"), enabled("{\"dev\": {\"frameStatsLog\": true}, \"modules\": {\"dev.frameStatsLog\": false}}", NO_MODS));
	}

	@Test
	void worldsAreJoinedAndLeftWhenTheLevelComesAndGoes() {
		List<String> calls = new ArrayList<>();
		try {
			PerformanceCore.start(List.of(new Recorder("a", "", calls)));
			PerformanceCore.onScreenSet(null, false);
			assertEquals(List.of("a start"), calls, "menus before a world change nothing");
			PerformanceCore.onScreenSet(null, true);
			PerformanceCore.onScreenSet(null, true);
			assertEquals(List.of("a start", "a joined"), calls, "screens inside a world don't join it again");
			PerformanceCore.onScreenSet(null, false);
			PerformanceCore.onScreenSet(null, false);
			PerformanceCore.onScreenSet(null, true);
			assertEquals(List.of("a start", "a joined", "a left", "a joined"), calls);
		} finally {
			PerformanceCore.onScreenSet(null, false);
			PerformanceCore.start(List.of());
		}
	}

	@Test
	void aModuleThatThrowsIsTurnedOffAndTheOthersCarryOn() {
		List<String> calls = new ArrayList<>();
		try {
			PerformanceCore.start(List.of(new Recorder("broken", "start", calls), new Recorder("joins", "joined", calls), new Recorder("ok", "", calls)));
			PerformanceCore.onScreenSet(null, true);
			PerformanceCore.onScreenSet(null, false);
			PerformanceCore.onScreenSet(null, true);
			assertEquals(List.of("broken start", "joins start", "ok start", "joins joined", "ok joined", "ok left", "ok joined"), calls);
		} finally {
			PerformanceCore.onScreenSet(null, false);
			PerformanceCore.start(List.of());
		}
	}

	private static List<String> enabledOfAll(String json, Compat compat) {
		return PerformanceCore.select(PerformanceCore.all(), PerfConfig.parse(json), compat).stream().map(PerfModule::id).toList();
	}

	@Test
	void everyModuleHasItsOwnId() {
		List<String> ids = PerformanceCore.all().stream().map(PerfModule::id).toList();
		assertEquals(List.of("dev.frameStatsLog", "menuResources", "atlasBatching", "decodeExecutor", "fontPreload", "graphicsCore",
			"dev.gpuResources", "dev.fontTiming", "networkCore", "dev.network"), ids);
		assertEquals(ids.size(), Set.copyOf(ids).size());
	}

	@Test
	void graphicsCoreHearsAWorldJoinAfterMenuResources() {
		List<String> ids = PerformanceCore.all().stream().map(PerfModule::id).toList();
		assertTrue(ids.indexOf("menuResources") < ids.indexOf("graphicsCore"), "its leak check reads what menuResources released");
		assertTrue(ids.indexOf("menuResources") < ids.indexOf("dev.gpuResources"));
	}

	@Test
	void theMenuModulesFollowAutomaticOptimization() {
		assertEquals(List.of("menuResources", "atlasBatching", "decodeExecutor", "fontPreload", "graphicsCore", "networkCore"), enabledOfAll("{}", NO_MODS));
		assertEquals(List.of("graphicsCore", "networkCore"), enabledOfAll("{\"automaticOptimization\": false}", NO_MODS),
			"graphicsCore and networkCore only read, so they aren't optimizations");
		assertEquals(List.of("menuResources", "atlasBatching", "decodeExecutor", "fontPreload", "graphicsCore", "networkCore"),
			enabledOfAll("{}", Compat.of(id -> true)), "other performance mods don't touch Flint's menu");
	}

	@Test
	void eachMenuModuleCanBeSwitchedOnItsOwn() {
		assertEquals(List.of("menuResources", "decodeExecutor", "fontPreload", "graphicsCore", "networkCore"),
			enabledOfAll("{\"modules\": {\"atlasBatching\": false}}", NO_MODS));
		assertEquals(List.of("atlasBatching", "decodeExecutor", "fontPreload", "graphicsCore", "networkCore"),
			enabledOfAll("{\"modules\": {\"menuResources\": false}}", NO_MODS));
		assertEquals(List.of("menuResources", "atlasBatching", "fontPreload", "graphicsCore", "networkCore"),
			enabledOfAll("{\"modules\": {\"decodeExecutor\": false}}", NO_MODS));
		assertEquals(List.of("menuResources", "atlasBatching", "decodeExecutor", "graphicsCore", "networkCore"),
			enabledOfAll("{\"modules\": {\"fontPreload\": false}}", NO_MODS));
		assertEquals(List.of("menuResources", "atlasBatching", "decodeExecutor", "fontPreload", "networkCore"),
			enabledOfAll("{\"modules\": {\"graphicsCore\": false}}", NO_MODS));
		assertEquals(List.of("decodeExecutor", "graphicsCore", "networkCore"),
			enabledOfAll("{\"automaticOptimization\": false, \"modules\": {\"decodeExecutor\": true}}", NO_MODS));
		assertEquals(List.of("fontPreload", "graphicsCore", "networkCore"),
			enabledOfAll("{\"automaticOptimization\": false, \"modules\": {\"fontPreload\": true}}", NO_MODS));
		assertEquals(List.of("dev.frameStatsLog", "atlasBatching", "graphicsCore", "networkCore"),
			enabledOfAll("{\"automaticOptimization\": false, \"dev\": {\"frameStatsLog\": true}, \"modules\": {\"atlasBatching\": true}}", NO_MODS));
	}

	@Test
	void theNetworkCoreOnlyObservesSoItIsAlwaysOnUnlessOverridden() {
		assertEquals(List.of("menuResources", "atlasBatching", "decodeExecutor", "fontPreload", "graphicsCore"),
			enabledOfAll("{\"modules\": {\"networkCore\": false}}", NO_MODS), "an override switches it off for diagnosis");
		assertEquals(List.of("graphicsCore", "networkCore", "dev.network"),
			enabledOfAll("{\"automaticOptimization\": false, \"dev\": {\"network\": true}}", NO_MODS),
			"its developer log follows its own flag");
		assertEquals(List.of("menuResources", "atlasBatching", "decodeExecutor", "fontPreload", "graphicsCore", "networkCore"),
			enabledOfAll("{\"dev\": {\"network\": true}, \"modules\": {\"dev.network\": false}}", NO_MODS));
	}

	@Test
	void theDeveloperLogsFollowTheirFlagsOnly() {
		assertEquals(List.of("menuResources", "atlasBatching", "decodeExecutor", "fontPreload", "graphicsCore", "dev.gpuResources", "dev.fontTiming",
			"networkCore"), enabledOfAll("{\"dev\": {\"gpuResources\": true, \"fontTiming\": true}}", NO_MODS));
		assertEquals(List.of("graphicsCore", "dev.fontTiming", "networkCore"),
			enabledOfAll("{\"automaticOptimization\": false, \"dev\": {\"fontTiming\": true}}", NO_MODS),
			"font timing stays available with fontPreload off, for the before and after");
		assertEquals(List.of("graphicsCore", "dev.gpuResources", "networkCore"),
			enabledOfAll("{\"automaticOptimization\": false, \"modules\": {\"dev.gpuResources\": true}}", NO_MODS),
			"a module override (which the launcher keeps) turns one on without the dev flag");
		assertEquals(List.of("menuResources", "atlasBatching", "decodeExecutor", "fontPreload", "graphicsCore", "networkCore"),
			enabledOfAll("{\"dev\": {\"gpuResources\": true}, \"modules\": {\"dev.gpuResources\": false}}", NO_MODS));
	}

	@Test
	void runningMeansStartedAndNotTurnedOff() {
		List<String> calls = new ArrayList<>();
		try {
			PerformanceCore.start(List.of(new Recorder("broken", "start", calls), new Recorder("joins", "joined", calls), new Recorder("ok", "", calls)));
			assertFalse(PerformanceCore.running("broken"), "failed to start");
			assertTrue(PerformanceCore.running("joins"));
			assertTrue(PerformanceCore.running("ok"));
			assertFalse(PerformanceCore.running("menuResources"), "not enabled");
			PerformanceCore.onScreenSet(null, true);
			assertFalse(PerformanceCore.running("joins"), "turned off after failing");
			assertTrue(PerformanceCore.running("ok"));
		} finally {
			PerformanceCore.onScreenSet(null, false);
			PerformanceCore.start(List.of());
		}
	}

	@Test
	void compatListsTheLoadedModsByModId() {
		assertEquals("none", NO_MODS.describe());
		Set<String> loaded = Set.of("sodium", "dynamic_fps", "c2me", "fabric-api");
		Compat compat = Compat.of(loaded::contains);
		assertEquals("sodium, dynamic_fps, c2me", compat.describe());
		assertTrue(compat.dynamicFps());
		assertFalse(compat.iris());
	}
}
