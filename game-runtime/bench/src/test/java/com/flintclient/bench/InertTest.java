package com.flintclient.bench;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Without -Dflintbench.out (as in this test JVM) the probe must do nothing at all. */
class InertTest {
	@Test
	void allocatesAndStartsNothingWhenTheOutputPathIsUnset() {
		assertNull(System.getProperty("flintbench.out"));
		assertFalse(FlintBench.ENABLED);
		assertNull(FlintBench.FRAMES);
		assertNull(FlintBench.SAMPLES);
		assertNull(FlintBench.GC);

		new FlintBench().onInitializeClient();
		boolean watchdogStarted = Thread.getAllStackTraces().keySet().stream()
			.anyMatch(thread -> thread.getName().startsWith("flintbench"));
		assertFalse(watchdogStarted);
	}
}
