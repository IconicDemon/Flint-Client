package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.model.BoolSetting;
import com.flintclient.client.control.model.FloatSetting;
import com.flintclient.client.control.model.IntSetting;
import com.flintclient.client.control.model.ModuleDescriptor;
import com.flintclient.client.control.model.ModuleId;
import com.flintclient.client.control.model.ModuleRegistry;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.OptionSetting;
import com.flintclient.client.control.model.SettingDescriptor;
import com.flintclient.client.control.platform.HookGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The safety net: a failing module is switched off for the session, a failing path or screen is recorded, values are clamped. */
class FaultsTest {
	private static final String ZOOM = com.flintclient.client.control.model.mods.PlayerMods.Zoom.ID;

	@BeforeEach
	@AfterEach
	void clean() {
		Faults.reset();
	}

	@Test
	void aModuleThatThrowsIsSwitchedOffForTheSession() {
		int zoom = ModuleRegistry.indexOf(ZOOM);
		int fps = ModuleRegistry.indexOf(ModuleId.FPS);
		int before = ServerRules.revision();
		assertFalse(Faults.off(zoom));
		assertFalse(ServerRules.blocked(zoom));

		assertTrue(Faults.moduleFailed(zoom, "ticking", new IllegalStateException("boom")));

		assertTrue(Faults.off(zoom));
		// What Mods.on, the HUD and the mod card read: the module is off, whatever the player set.
		assertTrue(ServerRules.blocked(zoom));
		assertNotEquals(before, ServerRules.revision(), "the HUD rebinds without the module");
		assertEquals("Off after an error: Flint turned it off for this session", ServerRules.reason(ZOOM));
		// The others keep running.
		assertFalse(Faults.off(fps));
		assertFalse(ServerRules.blocked(fps));
		assertEquals("Zoom was turned off · error", Faults.takeNotice());
		assertNull(Faults.takeNotice(), "said once");
	}

	@Test
	void aModuleFailingEveryFrameIsReportedOnce() {
		int fps = ModuleRegistry.indexOf(ModuleId.FPS);
		assertTrue(Faults.moduleFailed(fps, "drawing", new IllegalStateException("first")));
		for (int i = 0; i < 100; i++) {
			assertFalse(Faults.moduleFailed(fps, "drawing", new IllegalStateException("again")));
		}
		assertEquals(1, Faults.count());
		assertEquals("first", Faults.records().get(0).error().getMessage());
		assertEquals("FPS", Faults.records().get(0).where());
	}

	@Test
	void anUnknownModuleIsOnlyRecorded() {
		assertFalse(Faults.moduleFailed("no-such-module", "ticking", new IllegalStateException()));
		assertFalse(Faults.moduleFailed(-1, "ticking", new IllegalStateException()));
		assertFalse(Faults.moduleFailed(ModuleRegistry.size(), "ticking", new IllegalStateException()));
		assertEquals(1, Faults.count());
	}

	@Test
	void aHookPathThatFailsIsRecordedWithANotice() {
		HookGuard guard = new HookGuard("chat heads", (path, error) -> {
		});
		guard.fail(new IllegalStateException("boom"));
		assertFalse(guard.on());
		assertEquals(1, Faults.count());
		assertEquals("path", Faults.records().get(0).kind());
		assertEquals("Chat heads turned off · error", Faults.takeNotice());
	}

	@Test
	void aReporterThatThrowsStillRecordsTheFailure() {
		HookGuard guard = new HookGuard("toasts", (path, error) -> {
			throw new IllegalStateException("the reporter broke too");
		});
		try {
			guard.fail(new IllegalStateException("boom"));
		} catch (IllegalStateException expected) {
			// The guard's caller sees the reporter's error; the path is off and the net knows.
		}
		assertFalse(guard.on());
		assertEquals(1, Faults.count());
	}

	@Test
	void aScreenThatClosesIsRecorded() {
		Faults.screenFailed("Flint menu", "drawing", new IllegalStateException("boom"));
		assertEquals("screen", Faults.records().get(0).kind());
		assertEquals("Flint menu closed · error", Faults.takeNotice());
	}

	@Test
	void manyFailuresAtOnceMakeOneNotice() {
		Faults.moduleFailed(ModuleRegistry.indexOf(ZOOM), "ticking", new IllegalStateException());
		Faults.pathFailed("chat heads", new IllegalStateException());
		Faults.screenFailed("Cosmetics", "drawing", new IllegalStateException());
		assertEquals("Zoom was turned off and 2 more · errors", Faults.takeNotice());
		assertNull(Faults.takeNotice());
	}

	@Test
	void rearmDoesNothingOutsideQa() {
		int zoom = ModuleRegistry.indexOf(ZOOM);
		Faults.moduleFailed(zoom, "ticking", new IllegalStateException());
		Faults.rearm();
		assertTrue(Faults.off(zoom), "only a QA tour arms a failed module again");
		Faults.qaInject(ModuleId.FPS, true);
		assertEquals(-1, Faults.qaFailModule);
		assertFalse(Faults.qaFailScreen);
	}

	@Test
	void everySettingOfEveryModuleTakesItsExtremesWithoutThrowing() {
		// As the crash sweep sets them, but in the model alone: out of range is clamped, the wrong type or null is the default.
		for (ModuleDescriptor module : ModuleRegistry.all()) {
			ModuleState state = new ModuleState(module);
			for (SettingDescriptor<?> setting : module.settings()) {
				@SuppressWarnings("unchecked")
				SettingDescriptor<Object> s = (SettingDescriptor<Object>) setting;
				for (Object value : new Object[] {Integer.MIN_VALUE, Integer.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, Float.NaN,
					Float.POSITIVE_INFINITY, "", "x".repeat(10_000), Boolean.TRUE, null, new Object()}) {
					state.set(s, value);
					Object now = state.get(s);
					assertTrue(s.valid(now), module.id() + "." + s.id() + " = " + now + " after setting " + value);
				}
				state.resetSetting(s);
			}
		}
	}

	@Test
	void numbersAreClampedToTheirRange() {
		IntSetting size = new IntSetting("qaSize", "Size", null, 16, 12, 24, 2, "");
		assertEquals(12, size.coerce(Integer.MIN_VALUE));
		assertEquals(24, size.coerce(Integer.MAX_VALUE));
		assertEquals(16, size.coerce("sixteen"));
		FloatSetting zoom = new FloatSetting("qaZoom", "Zoom", null, 4F, 1F, 10F, 0.5F, "x");
		assertEquals(1F, zoom.coerce(-Float.MAX_VALUE));
		assertEquals(10F, zoom.coerce(Float.MAX_VALUE));
		assertEquals(4F, zoom.coerce(Float.NaN));
		OptionSetting mode = new OptionSetting("qaMode", "Mode", null, 1, "a", "A", "b", "B");
		assertEquals(1, mode.coerce(-1));
		assertEquals(1, mode.coerce(2));
		BoolSetting on = new BoolSetting("qaOn", "On", null, true);
		assertEquals(true, on.coerce(null));
	}
}
