package com.flintclient.bench;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Everything the run learns besides the bulk recordings: environment, load times, segments, anchors and
 * the outcome. Filled on the client thread; the lists are copy-on-write because the watchdog thread may
 * have to write a result while the client thread is stuck.
 */
final class Result {
	final String label;
	final long seed;
	final boolean quick;
	final Map<String, Object> env = new LinkedHashMap<>();
	Long titleMs;
	// Keys exist from the start so a value that was never reached is written as null.
	final Map<String, Object> load = keys("createStartNs", "createToJoinMs", "createToTerrainMs");
	final Map<String, Object> reload = keys("leaveMs", "openStartNs", "openToJoinMs", "openToTerrainMs");
	final Map<String, Object> scenario = new LinkedHashMap<>();
	final List<Segment> segments = new CopyOnWriteArrayList<>();
	final List<long[]> anchors = new CopyOnWriteArrayList<>();
	volatile String phase = "";
	volatile String error;
	volatile String errorStack;

	Result(String label, long seed, boolean quick) {
		this.label = label;
		this.seed = seed;
		this.quick = quick;
	}

	/** Pairs System.nanoTime() with JVM uptime, so uptime-stamped logs (-Xlog:gc) line up with frames. */
	void anchor(long nanoTime, long uptimeMs) {
		anchors.add(new long[] {nanoTime, uptimeMs});
	}

	/** The segment still being recorded, or null. */
	Segment openSegment() {
		if (segments.isEmpty()) {
			return null;
		}
		Segment last = segments.get(segments.size() - 1);
		return last.isOpen() ? last : null;
	}

	private static Map<String, Object> keys(String... names) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (String name : names) {
			map.put(name, null);
		}
		return map;
	}
}
