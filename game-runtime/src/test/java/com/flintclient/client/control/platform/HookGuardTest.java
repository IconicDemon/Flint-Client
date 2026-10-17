package com.flintclient.client.control.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HookGuardTest {
	private final List<String> reports = new ArrayList<>();
	private final List<RuntimeException> errors = new ArrayList<>();

	private HookGuard guard(String path) {
		return new HookGuard(path, (failedPath, error) -> {
			reports.add(failedPath);
			errors.add(error);
		});
	}

	@Test
	void aPathRunsUntilItFails() {
		HookGuard hud = guard("hud");
		assertTrue(hud.on());
		assertEquals("hud", hud.path());
		IllegalStateException boom = new IllegalStateException("boom");
		hud.fail(boom);
		assertFalse(hud.on());
		assertEquals(List.of("hud"), reports);
		assertSame(boom, errors.getFirst());
	}

	@Test
	void onlyTheFirstFailureIsReported() {
		HookGuard hud = guard("hud");
		hud.fail(new IllegalStateException("first"));
		hud.fail(new IllegalStateException("second"));
		hud.fail(new IllegalArgumentException("third"));
		assertEquals(1, reports.size(), "a path failing every frame can't flood the log");
		assertEquals("first", errors.getFirst().getMessage());
		assertFalse(hud.on());
	}

	@Test
	void otherPathsKeepRunning() {
		HookGuard hud = guard("hud");
		HookGuard cps = guard("cps");
		hud.fail(new IllegalStateException());
		assertFalse(hud.on());
		assertTrue(cps.on());
		cps.fail(new IllegalStateException());
		assertEquals(List.of("hud", "cps"), reports);
	}

	@Test
	void aPathNeedsANameAndAReporter() {
		assertThrows(NullPointerException.class, () -> new HookGuard(null, (path, error) -> {}));
		assertThrows(NullPointerException.class, () -> new HookGuard("hud", null));
	}
}
