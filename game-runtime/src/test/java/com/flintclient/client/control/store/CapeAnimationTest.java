package com.flintclient.client.control.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.feature.AnimatedCapeFixture;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.net.URI;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class CapeAnimationTest {
	private static final StoreEndpoint STORE = StoreEndpoint.defaults();
	private static final String SHA = "9164506a34433704262b961558bb623767caf1677848b790f7e4255815089e3b";

	/** A valid manifest of {@code durations} at {@code scale}, strips of made-up hashes at the Store's bucket. */
	static JsonObject manifest(int scale, int... durations) {
		int per = CapeAnimation.framesPerStrip(17 * scale);
		JsonObject a = new JsonObject();
		a.addProperty("v", 1);
		a.addProperty("layout", "cape-atlas-22x17");
		a.addProperty("width", 22 * scale);
		a.addProperty("height", 17 * scale);
		a.addProperty("scale", scale);
		a.addProperty("frame_count", durations.length);
		JsonArray ds = new JsonArray();
		int total = 0;
		for (int d : durations) {
			ds.add(d);
			total += d;
		}
		a.add("durations_ms", ds);
		a.addProperty("duration_ms", total);
		a.addProperty("frames_per_strip", per);
		JsonArray strips = new JsonArray();
		for (int first = 0, k = 0; first < durations.length; first += per, k++) {
			JsonObject s = new JsonObject();
			String hash = String.format("%064x", k + 1);
			s.addProperty("path", "animated/" + hash + ".png");
			s.addProperty("sha256", hash);
			s.addProperty("bytes", 100_000);
			s.addProperty("frames", Math.min(per, durations.length - first));
			s.addProperty("url", AnimatedCapeFixture.BUCKET + "animated/" + hash + ".png");
			strips.add(s);
		}
		a.add("strips", strips);
		JsonObject source = new JsonObject();
		source.addProperty("format", "webp");
		source.addProperty("sha256", SHA);
		source.addProperty("bytes", 4096);
		a.add("source", source);
		return a;
	}

	private static JsonObject standard() {
		return manifest(16, AnimatedCapeFixture.STANDARD);
	}

	private static CapeAnimation parse(JsonElement json) {
		return CapeAnimation.parse(json, STORE);
	}

	/** {@code change} applied to a copy of the standard manifest refuses it. */
	private static void refused(String what, Consumer<JsonObject> change) {
		JsonObject a = standard();
		change.accept(a);
		assertNull(parse(a), what);
	}

	private static JsonObject strip(JsonObject a, int k) {
		return a.getAsJsonArray("strips").get(k).getAsJsonObject();
	}

	@Test
	void theStoresStandardFixtureIsRead() {
		CapeAnimation a = parse(standard());
		assertNotNull(a);
		assertEquals(16, a.scale());
		assertEquals(352, a.frameWidth());
		assertEquals(272, a.frameHeight());
		assertEquals(10, a.frameCount());
		assertEquals(1030, a.loopMillis());
		assertEquals(7, a.framesPerStrip());
		assertEquals(2, a.strips().size());
		assertEquals(7, a.strips().get(0).frames());
		assertEquals(3, a.strips().get(1).frames());
		assertEquals(7, a.firstFrame(1));
		assertEquals(260, a.duration(4));
		assertEquals(200_000, a.stripBytes());
		assertEquals(URI.create(AnimatedCapeFixture.BUCKET + "animated/" + String.format("%064x", 1) + ".png"), a.strips().get(0).url());
		assertEquals(16, a.key().length());
		assertEquals(a, parse(standard()), "the same manifest reads the same");
		assertEquals(a.key(), parse(standard()).key());
	}

	@Test
	void everyScaleAndItsStripsAreRead() {
		int[][] expected = {{4, 30}, {8, 15}, {16, 7}, {32, 3}};
		for (int[] e : expected) {
			int[] durations = new int[120];
			java.util.Arrays.fill(durations, 100);
			CapeAnimation a = parse(manifest(e[0], durations));
			assertNotNull(a, "scale " + e[0]);
			assertEquals(e[1], a.framesPerStrip());
			assertEquals((120 + e[1] - 1) / e[1], a.strips().size());
			assertEquals(120 - (a.strips().size() - 1) * e[1], a.strips().get(a.strips().size() - 1).frames(), "the last strip holds the rest");
		}
	}

	@Test
	void aStaticCapeHasNoAnimation() {
		assertNull(CapeAnimation.parse(null, STORE));
		assertNull(parse(JsonNull.INSTANCE));
		assertNull(parse(new JsonPrimitive("animated")));
		assertNull(parse(new JsonArray()));
	}

	@Test
	void anyOtherVersionOrLayoutIsRefused() {
		refused("version 2", a -> a.addProperty("v", 2));
		refused("no version", a -> a.remove("v"));
		refused("version as text", a -> a.addProperty("v", "1"));
		refused("another layout", a -> a.addProperty("layout", "cape-atlas-64x32"));
		refused("no layout", a -> a.remove("layout"));
	}

	@Test
	void theFrameSizeMustBeTheAtlasAtAStoreScale() {
		refused("scale 12", a -> {
			a.addProperty("scale", 12);
			a.addProperty("width", 22 * 12);
			a.addProperty("height", 17 * 12);
		});
		refused("scale 64", a -> {
			a.addProperty("scale", 64);
			a.addProperty("width", 22 * 64);
			a.addProperty("height", 17 * 64);
		});
		refused("width of another scale", a -> a.addProperty("width", 176));
		refused("height of another scale", a -> a.addProperty("height", 136));
		refused("a 64x32 texture's size", a -> {
			a.addProperty("width", 1024);
			a.addProperty("height", 512);
		});
		refused("fractional scale", a -> a.addProperty("scale", 16.5));
	}

	@Test
	void frameCountsAndDurationsMustBeWithinTheStoresLimits() {
		assertNull(parse(manifest(16, 100)), "one frame isn't an animation");
		int[] many = new int[121];
		java.util.Arrays.fill(many, 50);
		assertNull(parse(manifest(4, many)), "121 frames");
		assertNull(parse(manifest(16, 100, 9)), "a frame under 10 ms");
		assertNull(parse(manifest(16, 100, 10_001)), "a frame over 10 s");
		assertNull(parse(manifest(16, 10_000, 10_000, 10_000, 10_000, 10_000, 10_000, 1)), "over 60 s a loop");
		assertNotNull(parse(manifest(16, 10_000, 10_000, 10_000, 10_000, 10_000, 10_000)), "exactly 60 s");
		assertNotNull(parse(manifest(16, 10, 10_000)), "the limits themselves");
		refused("frame_count not the durations' count", a -> a.addProperty("frame_count", 9));
		refused("duration_ms not their sum", a -> a.addProperty("duration_ms", 1031));
		refused("a fractional duration", a -> a.getAsJsonArray("durations_ms").set(0, new JsonPrimitive(40.5)));
		refused("a duration as text", a -> a.getAsJsonArray("durations_ms").set(0, new JsonPrimitive("40")));
		refused("a missing duration", a -> a.getAsJsonArray("durations_ms").set(0, JsonNull.INSTANCE));
		refused("durations not a list", a -> a.addProperty("durations_ms", 40));
		refused("a negative loop", a -> a.addProperty("duration_ms", -1030));
	}

	@Test
	void stripsMustHoldTheFramesTheLayoutSays() {
		refused("frames_per_strip not floor(2048 / height)", a -> a.addProperty("frames_per_strip", 8));
		refused("a strip missing", a -> a.getAsJsonArray("strips").remove(1));
		refused("a strip too many", a -> a.getAsJsonArray("strips").add(strip(a, 1).deepCopy()));
		refused("a strip with other frames", a -> strip(a, 0).addProperty("frames", 6));
		refused("the last strip holding more", a -> strip(a, 1).addProperty("frames", 7));
		refused("strips not a list", a -> a.add("strips", new JsonObject()));
		refused("a strip that isn't an object", a -> a.getAsJsonArray("strips").set(0, new JsonPrimitive("x")));
	}

	@Test
	void everyStripNeedsAHashAndASizeWithinTheLimits() {
		refused("a short hash", a -> strip(a, 0).addProperty("sha256", "abc"));
		refused("a hash that isn't hex", a -> strip(a, 0).addProperty("sha256", "z".repeat(64)));
		refused("no hash", a -> strip(a, 0).remove("sha256"));
		refused("no size", a -> strip(a, 0).remove("bytes"));
		refused("size 0", a -> strip(a, 0).addProperty("bytes", 0));
		refused("a strip over 8 MiB", a -> strip(a, 0).addProperty("bytes", 8 * 1024 * 1024 + 1));
		JsonObject five = manifest(32, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100);
		for (int k = 0; k < 5; k++) {
			strip(five, k).addProperty("bytes", 8 * 1024 * 1024);
		}
		assertNull(parse(five), "five strips of 8 MiB are over 32 MiB together");
		JsonObject four = manifest(32, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100);
		for (int k = 0; k < 4; k++) {
			strip(four, k).addProperty("bytes", 8 * 1024 * 1024);
		}
		assertNotNull(parse(four), "four strips of 8 MiB are exactly 32 MiB");
		CapeAnimation upper = parse(standard());
		JsonObject a = standard();
		strip(a, 0).addProperty("sha256", strip(a, 0).get("sha256").getAsString().toUpperCase());
		assertEquals(upper, parse(a), "a hash in capitals is the same hash");
	}

	@Test
	void stripsComeOnlyFromWhereTheStoreKeepsAssets() {
		refused("plain http", a -> strip(a, 0).addProperty("url", "http://abcdefghijklmnopqrst.supabase.co/storage/v1/object/public/cosmetic-assets/x.png"));
		refused("another host", a -> strip(a, 0).addProperty("url", "https://evil.example/animated/x.png"));
		refused("another bucket", a -> strip(a, 0).addProperty("url", "https://abcdefghijklmnopqrst.supabase.co/storage/v1/object/public/other/x.png"));
		refused("a query", a -> strip(a, 0).addProperty("url", AnimatedCapeFixture.BUCKET + "animated/x.png?download=1"));
		refused("credentials", a -> strip(a, 0).addProperty("url", "https://user:pw@store.flintclient.com/animated/x.png"));
		refused("a dot-dot segment", a -> strip(a, 0).addProperty("url", AnimatedCapeFixture.BUCKET + "animated/../x.png"));
		refused("no address", a -> strip(a, 0).remove("url"));
		refused("not an address", a -> strip(a, 0).addProperty("url", "https://store.flintclient.com/a b.png"));
		refused("an address too long", a -> strip(a, 0).addProperty("url", "https://store.flintclient.com/" + "a".repeat(2100) + ".png"));
		JsonObject a = standard();
		strip(a, 0).addProperty("url", "https://store.flintclient.com/test-capes/strip.png");
		assertNotNull(parse(a), "the Store's own origin");
		StoreEndpoint local = StoreEndpoint.parse("http://127.0.0.1:8787", "test");
		JsonObject dev = standard();
		strip(dev, 0).addProperty("url", "http://127.0.0.1:8787/test-capes/strip.png");
		strip(dev, 1).addProperty("url", "http://127.0.0.1:8787/test-capes/strip2.png");
		assertNotNull(CapeAnimation.parse(dev, local), "a Store on this computer serves its own strips");
		assertNull(parse(dev), "but no other Store's");
	}

	@Test
	void unknownFieldsAndProvenanceAreIgnored() {
		JsonObject a = standard();
		a.remove("source");
		a.addProperty("future", "field");
		strip(a, 0).remove("path");
		assertNotNull(parse(a));
	}

	@Test
	void framesFollowTheirOwnDurations() {
		CapeAnimation a = parse(standard());
		// 40, 80, 120, 60, 260, 50, 90, 150, 70, 110: frame 0 is [0, 40), frame 1 [40, 120), frame 2 [120, 240)...
		assertEquals(0, a.frameAt(0));
		assertEquals(0, a.frameAt(39));
		assertEquals(1, a.frameAt(40));
		assertEquals(1, a.frameAt(119));
		assertEquals(2, a.frameAt(120));
		assertEquals(4, a.frameAt(300));
		assertEquals(4, a.frameAt(559));
		assertEquals(5, a.frameAt(560));
		assertEquals(9, a.frameAt(1029));
		int[] durations = AnimatedCapeFixture.STANDARD;
		int t = 0;
		for (int f = 0; f < durations.length; f++) {
			for (int ms = 0; ms < durations[f]; ms++, t++) {
				assertEquals(f, a.frameAt(t), "at " + t + " ms");
			}
		}
	}

	@Test
	void theLoopRepeatsOnTheWallClock() {
		CapeAnimation a = parse(standard());
		assertEquals(0, a.frameAt(1030), "the loop starts again");
		assertEquals(1, a.frameAt(1030 + 40));
		long now = 1_762_000_000_123L;
		assertEquals(a.frameAt(now % 1030), a.frameAt(now), "now % loop");
		assertEquals(a.frameAt(now), a.frameAt(now + 1030L * 1_000_000), "a million loops later");
		assertEquals(9, a.frameAt(-1), "before 1970 too (floorMod)");
	}

	@Test
	void everyClientShowsTheSameFrameAtTheSameMoment() {
		CapeAnimation mine = parse(standard());
		CapeAnimation theirs = parse(standard());
		long start = 1_762_000_000_000L;
		for (long t = start; t < start + 5000; t += 7) {
			assertEquals(mine.frameAt(t), theirs.frameAt(t));
		}
	}

	@Test
	void differentAnimationsHaveDifferentKeys() {
		CapeAnimation a = parse(standard());
		JsonObject other = standard();
		strip(other, 1).addProperty("sha256", "f".repeat(64));
		assertNotEquals(a.key(), parse(other).key(), "other frames");
		JsonObject slower = manifest(16, 41, 80, 120, 60, 260, 50, 90, 150, 70, 110);
		assertNotEquals(a.key(), parse(slower).key(), "other timing");
		assertNotEquals(a, parse(slower));
		assertTrue(a.toString().contains("10 frames"));
	}

	@Test
	void hostileJsonIsRefusedNotThrown() {
		refused("an enormous scale", a -> a.addProperty("scale", 1e300));
		refused("a NaN-like duration", a -> a.getAsJsonArray("durations_ms").set(0, new JsonPrimitive(Double.MAX_VALUE)));
		JsonElement deep = JsonParser.parseString("[".repeat(200) + "]".repeat(200));
		JsonObject a = standard();
		a.add("strips", deep);
		assertNull(parse(a));
	}
}
