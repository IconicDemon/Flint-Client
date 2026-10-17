package com.flintclient.bench;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Writes the result JSON (schema 1). Frame and tick arrays are integer microseconds; other durations are
 * numbers in the unit their key names. The file is written to a temporary sibling and renamed into place,
 * so a reader never sees half a result. Uses no Minecraft classes, so it is unit-tested directly.
 */
final class ResultWriter {
	static final int SCHEMA = 1;

	private ResultWriter() {
	}

	static void writeAtomically(Path out, Result result, FrameRecorder frames, SampleLog samples, GcRing gc, String[] throttleNames)
		throws IOException {
		Path target = out.toAbsolutePath();
		Files.createDirectories(target.getParent());
		Path temp = target.resolveSibling(target.getFileName() + ".tmp");
		try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
			write(new JsonOut(writer), result, frames, samples, gc, throttleNames);
		}
		Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	static void write(JsonOut json, Result result, FrameRecorder frames, SampleLog samples, GcRing gc, String[] throttleNames) {
		json.beginObject();
		json.key("schema").value(SCHEMA);
		json.key("label").value(result.label);
		json.key("seed").value(result.seed);
		json.key("quick").value(result.quick);
		json.key("env").value(result.env);
		json.key("startup").beginObject().key("titleMs").value(result.titleMs).endObject();
		json.key("load").value(result.load);
		json.key("reload").value(result.reload);
		json.key("scenario").value(result.scenario);

		json.key("segments").beginArray();
		for (Segment segment : result.segments) {
			segment(json, segment, frames, samples, throttleNames);
		}
		json.endArray();

		json.key("gc").beginArray();
		for (GcRing.Event event : gc.snapshot()) {
			json.beginObject()
				.key("tNs").value(event.tNs())
				.key("uptimeMs").value(event.uptimeMs())
				.key("name").value(event.name())
				.key("action").value(event.action())
				.key("cause").value(event.cause())
				.key("durationMs").value(event.durationMs())
				.endObject();
		}
		json.endArray();
		json.key("gcDropped").value(gc.dropped());

		json.key("anchors").beginArray();
		for (long[] anchor : result.anchors) {
			json.beginObject().key("nanoTime").value(anchor[0]).key("uptimeMs").value(anchor[1]).endObject();
		}
		json.endArray();

		json.key("recorder").beginObject()
			.key("frames").value(frames.frames)
			.key("frameCapacity").value(frames.frameNs.length)
			.key("framesOverflowed").value(frames.framesOverflowed)
			.key("ticks").value(frames.ticks)
			.key("ticksOverflowed").value(frames.ticksOverflowed)
			.key("samples").value(samples.count)
			.key("samplesOverflowed").value(samples.overflowed)
			.key("footprintMb").value(mb(frames.footprintBytes()))
			.endObject();

		json.key("phase").value(result.phase);
		json.key("error").value(result.error);
		json.key("errorStack").value(result.errorStack);
		json.endObject();
	}

	private static void segment(JsonOut json, Segment segment, FrameRecorder f, SampleLog s, String[] throttleNames) {
		// Clamp to what was recorded, in case the watchdog writes while the client thread is mid-update.
		int frameFrom = Math.min(segment.frameFrom, f.frames);
		int frameTo = Math.max(frameFrom, Math.min(segment.isOpen() ? f.frames : segment.frameTo, f.frames));
		int tickFrom = Math.min(segment.tickFrom, f.ticks);
		int tickTo = Math.max(tickFrom, Math.min(segment.isOpen() ? f.ticks : segment.tickTo, f.ticks));
		int sampleFrom = Math.min(segment.sampleFrom, s.count);
		int sampleTo = Math.max(sampleFrom, Math.min(segment.isOpen() ? s.count : segment.sampleTo, s.count));

		json.beginObject();
		json.key("name").value(segment.name);
		json.key("startNs").value(segment.startNs);
		// Still open only when the watchdog or the exit hook writes mid-segment.
		if (segment.isOpen()) {
			json.key("endNs").nullValue();
		} else {
			json.key("endNs").value(segment.endNs);
		}
		json.key("plannedMs").value(Math.round(segment.durationNs / 1e6));
		json.key("complete").value(segment.complete);

		json.key("frameUs").beginArray();
		for (int i = frameFrom; i < frameTo; i++) {
			json.value(micros(f.frameNs[i]));
		}
		json.endArray();
		json.key("renderUs").beginArray();
		for (int i = frameFrom; i < frameTo; i++) {
			json.value(micros(f.renderNs[i]));
		}
		json.endArray();
		// System.nanoTime() when each frame ended (same clock as startNs and the anchors): lets analysis line
		// frames up with GC pauses without rebuilding the ends from summed frame times. analyze.py reads it.
		json.key("frameEndNs").beginArray();
		for (int i = frameFrom; i < frameTo; i++) {
			json.value(f.frameEndNs[i]);
		}
		json.endArray();

		json.key("tickUs").beginArray();
		for (int i = tickFrom; i < tickTo; i++) {
			json.value(micros(f.tickNs[i]));
		}
		json.endArray();

		json.key("sampleUs").beginArray();
		for (int i = sampleFrom; i < sampleTo; i++) {
			json.value(micros(s.atNs[i] - segment.startNs));
		}
		json.endArray();
		json.key("serverTickMs").beginArray();
		for (int i = sampleFrom; i < sampleTo; i++) {
			if (s.serverTickNs[i] < 0) {
				json.nullValue();
			} else {
				json.value(millis(s.serverTickNs[i]));
			}
		}
		json.endArray();
		mbSeries(json, "heapMb", s.heapUsed, sampleFrom, sampleTo);
		mbSeries(json, "heapCommittedMb", s.heapCommitted, sampleFrom, sampleTo);
		mbSeries(json, "nonHeapMb", s.nonHeapUsed, sampleFrom, sampleTo);
		intSeries(json, "loadedChunks", s.loadedChunks, sampleFrom, sampleTo, false);
		intSeries(json, "renderedSections", s.renderedSections, sampleFrom, sampleTo, true);
		intSeries(json, "compileQueue", s.compileQueue, sampleFrom, sampleTo, true);
		json.key("playerX").beginArray();
		for (int i = sampleFrom; i < sampleTo; i++) {
			double x = s.playerX[i];
			json.value(Double.isNaN(x) ? x : Math.round(x * 100) / 100.0); // NaN (no player) is written as null
		}
		json.endArray();
		json.key("throttle").beginArray();
		for (int i = sampleFrom; i < sampleTo; i++) {
			int reason = s.throttle[i];
			json.value(reason >= 0 && reason < throttleNames.length ? throttleNames[reason] : null);
		}
		json.endArray();
		json.key("focused").beginArray();
		for (int i = sampleFrom; i < sampleTo; i++) {
			if (s.focused[i] == SampleLog.UNKNOWN) {
				json.nullValue();
			} else {
				json.value(s.focused[i] == 1);
			}
		}
		json.endArray();

		json.key("serverTickWindowMs");
		if (segment.serverTickWindowNs == null) {
			json.nullValue();
		} else {
			json.beginArray();
			for (long tick : segment.serverTickWindowNs) {
				json.value(millis(tick));
			}
			json.endArray();
		}
		json.endObject();
	}

	private static void mbSeries(JsonOut json, String key, long[] bytes, int from, int to) {
		json.key(key).beginArray();
		for (int i = from; i < to; i++) {
			if (bytes[i] < 0) {
				json.nullValue();
			} else {
				json.value(mb(bytes[i]));
			}
		}
		json.endArray();
	}

	// Series that only some renderers provide are written as null as a whole when never known.
	private static void intSeries(JsonOut json, String key, int[] values, int from, int to, boolean nullWhenUnknown) {
		json.key(key);
		if (nullWhenUnknown && !SampleLog.anyKnown(values, from, to)) {
			json.nullValue();
			return;
		}
		json.beginArray();
		for (int i = from; i < to; i++) {
			if (values[i] == SampleLog.UNKNOWN) {
				json.nullValue();
			} else {
				json.value(values[i]);
			}
		}
		json.endArray();
	}

	static long micros(long nanos) {
		return Math.round(nanos / 1e3);
	}

	/** Milliseconds with microsecond precision. */
	static double millis(long nanos) {
		return Math.round(nanos / 1e3) / 1e3;
	}

	/** Mebibytes with one decimal. */
	static double mb(long bytes) {
		return Math.round(bytes / (1024.0 * 1024.0) * 10) / 10.0;
	}
}
