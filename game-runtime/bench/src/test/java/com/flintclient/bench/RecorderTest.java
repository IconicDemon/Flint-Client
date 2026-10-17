package com.flintclient.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecorderTest {
	@Test
	void frameRecorderStopsAtCapacityAndFlagsOverflow() {
		FrameRecorder recorder = new FrameRecorder(2, 1);
		recorder.onFrame(16_000_000, 4_000_000, 100);
		recorder.onFrame(17_000_000, 5_000_000, 200);
		recorder.onFrame(18_000_000, 6_000_000, 300);
		assertEquals(2, recorder.frames);
		assertTrue(recorder.framesOverflowed);
		assertEquals(200, recorder.frameEndNs[1]);

		recorder.tickStarted(1_000);
		recorder.tickEnded(3_500);
		recorder.tickStarted(4_000);
		recorder.tickEnded(5_000);
		assertEquals(1, recorder.ticks);
		assertEquals(2_500, recorder.tickNs[0]);
		assertTrue(recorder.ticksOverflowed);
	}

	@Test
	void quickModeDividesSegmentDurationsByFive() {
		assertEquals(30_000_000_000L, Segment.durationNs(30, false));
		assertEquals(6_000_000_000L, Segment.durationNs(30, true));
		Segment segment = new Segment("static", 1_000, Segment.durationNs(1, false), 0, 0, 0);
		assertFalse(segment.isDue(1_000_000_000));
		assertTrue(segment.isDue(1_000_001_000));
		assertEquals(0.5, segment.secondsIn(500_001_000), 1e-9);
	}

	@Test
	void resultSlicesEachSegmentFromTheSharedRecordings(@TempDir Path dir) throws Exception {
		FrameRecorder frames = new FrameRecorder(10, 10);
		SampleLog samples = new SampleLog(10);
		GcRing gc = new GcRing(4);
		Result result = new Result("unit", 42, true);
		result.anchor(0, 5);

		// Three frames before the segment, two inside it, one after.
		for (int i = 0; i < 3; i++) {
			frames.onFrame(10_000_000, 1_000_000, i * 10_000_000L);
		}
		Segment segment = new Segment("static", 30_000_000, 1_000_000_000, frames.frames, frames.ticks, samples.count);
		result.segments.add(segment);
		frames.onFrame(16_400_000, 4_000_499, 46_400_000);
		frames.onFrame(16_600_000, 4_000_500, 63_000_000);
		frames.tickStarted(40_000_000);
		frames.tickEnded(40_250_000);
		int s = samples.next(50_000_000);
		samples.heapUsed[s] = 512L * 1024 * 1024;
		samples.loadedChunks[s] = 625;
		samples.serverTickNs[s] = 3_250_000;
		samples.playerX[s] = 12.346;
		samples.throttle[s] = 0;
		samples.focused[s] = 1;
		segment.end(70_000_000, frames.frames, frames.ticks, samples.count, true);
		frames.onFrame(99_000_000, 1_000_000, 80_000_000);
		gc.add(55_000_000, 1234, "G1 Young Generation", "end of minor GC", "G1 Evacuation Pause", 7);

		Path out = dir.resolve("nested/result.json");
		ResultWriter.writeAtomically(out, result, frames, samples, gc, new String[] {"NONE", "WINDOW_ICONIFIED"});
		assertFalse(Files.exists(dir.resolve("nested/result.json.tmp")));

		JsonObject root = JsonParser.parseString(Files.readString(out)).getAsJsonObject();
		assertEquals(1, root.get("schema").getAsInt());
		assertTrue(root.getAsJsonObject("load").get("createToJoinMs").isJsonNull());
		assertTrue(root.get("error").isJsonNull());

		JsonObject written = root.getAsJsonArray("segments").get(0).getAsJsonObject();
		assertEquals("[16400,16600]", written.get("frameUs").toString());
		assertEquals("[4000,4001]", written.get("renderUs").toString());
		assertEquals("[46400000,63000000]", written.get("frameEndNs").toString());
		assertEquals(70_000_000, written.get("endNs").getAsLong());
		assertEquals("[250]", written.get("tickUs").toString());
		assertEquals("[3.25]", written.get("serverTickMs").toString());
		assertEquals("[512.0]", written.get("heapMb").toString());
		assertEquals("[null]", written.get("heapCommittedMb").toString());
		assertEquals("[625]", written.get("loadedChunks").toString());
		assertTrue(written.get("renderedSections").isJsonNull());
		assertEquals("[12.35]", written.get("playerX").toString());
		assertEquals("[\"NONE\"]", written.get("throttle").toString());
		assertEquals("[true]", written.get("focused").toString());
		assertTrue(written.get("complete").getAsBoolean());

		JsonArray events = root.getAsJsonArray("gc");
		assertEquals(1, events.size());
		assertEquals(7, events.get(0).getAsJsonObject().get("durationMs").getAsLong());
		assertEquals(6, root.getAsJsonObject("recorder").get("frames").getAsInt());
	}

	@Test
	void anOpenSegmentIsWrittenUpToTheLatestRecording() {
		// What the watchdog or the exit hook writes when the run stops inside a segment.
		FrameRecorder frames = new FrameRecorder(10, 10);
		SampleLog samples = new SampleLog(10);
		Result result = new Result("unit", 1, false);
		frames.onFrame(10_000_000, 1_000_000, 10_000_000);
		result.segments.add(new Segment("travel", 15_000_000, 45_000_000_000L, frames.frames, frames.ticks, samples.count));
		frames.onFrame(12_000_000, 2_000_000, 22_000_000);
		frames.onFrame(13_000_000, 3_000_000, 35_000_000);
		samples.next(30_000_000);
		result.error = "game exited before the scenario finished";

		StringBuilder out = new StringBuilder();
		ResultWriter.write(new JsonOut(out), result, frames, samples, new GcRing(2), new String[0]);

		JsonObject root = JsonParser.parseString(out.toString()).getAsJsonObject();
		JsonObject segment = root.getAsJsonArray("segments").get(0).getAsJsonObject();
		assertTrue(segment.get("endNs").isJsonNull());
		assertFalse(segment.get("complete").getAsBoolean());
		assertEquals("[12000,13000]", segment.get("frameUs").toString());
		assertEquals("[15000]", segment.get("sampleUs").toString());
		// A sample with nothing known: whole optional series become null, the rest hold nulls.
		assertTrue(segment.get("renderedSections").isJsonNull());
		assertEquals("[null]", segment.get("loadedChunks").toString());
		assertEquals("[null]", segment.get("playerX").toString());
		assertEquals("[null]", segment.get("throttle").toString());
		assertEquals("game exited before the scenario finished", root.get("error").getAsString());
	}
}
