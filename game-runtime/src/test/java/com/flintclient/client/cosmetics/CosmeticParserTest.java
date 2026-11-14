package com.flintclient.client.cosmetics;

import static com.flintclient.client.cosmetics.Placeholders.bytes;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.cosmetics.CosmeticAnimation.Clip;
import com.flintclient.client.cosmetics.CosmeticLimits.Refused;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class CosmeticParserTest {
	// ---------- Molang ----------

	@Test
	void molangReadsTheSubsetCosmeticsUse() {
		assertEquals(2.5, Molang.compile("2.5").eval(0, 0), 1e-9);
		assertTrue(Molang.compile("-12.5 * 2").constant());
		assertEquals(-25, Molang.compile("-12.5 * 2").eval(0, 0), 1e-9);
		// Degrees, as Molang: sin(90) = 1.
		assertEquals(1, Molang.compile("math.sin(90)").eval(0, 0), 1e-9);
		assertEquals(50, Molang.compile("math.sin(query.anim_time*360)*50").eval(0.25, 0), 1e-6);
		assertEquals(12.5 - 3, Molang.compile("12.5-math.sin(q.anim_time*360)*3").eval(0.25, 0), 1e-6);
		assertEquals(4, Molang.compile("q.life_time * 2").eval(0, 2), 1e-9);
		assertEquals(3, Molang.compile("math.clamp(5, 0, 3)").eval(0, 0), 1e-9);
		assertEquals(7.5, Molang.compile("math.lerp(5, 10, 0.5);").eval(0, 0), 1e-9);
		assertEquals(0, Molang.compile("1 / 0").eval(0, 0), 1e-9);
		assertEquals(1e-3, Molang.compile("1e-3").eval(0, 0), 1e-12);
	}

	@Test
	void molangRefusesWhatItDoesNotKnow() {
		assertNull(Molang.compile("variable.flap"));
		assertNull(Molang.compile("query.is_sneaking ? 1 : 0"));
		assertNull(Molang.compile("math.random(0, 1)"));
		assertNull(Molang.compile("1 +"));
		assertNull(Molang.compile("(".repeat(100) + "1" + ")".repeat(100)));
		assertNull(Molang.compile("1".repeat(Molang.MAX_LENGTH + 1)));
	}

	// ---------- geometry ----------

	@Test
	void placeholdersParseAndBake() throws Refused {
		for (String g : List.of(Placeholders.WINGS, Placeholders.HEADWEAR, Placeholders.BODYWEAR, Placeholders.AURA, Placeholders.PET,
			Placeholders.GLOVES, Placeholders.SHIELD)) {
			CosmeticGeometry geometry = CosmeticGeometry.parse(bytes(g));
			CosmeticModel model = CosmeticModel.bake(geometry, CosmeticModel.BODY, false);
			assertTrue(model.quadCount > 0, g);
		}
	}

	@Test
	void bonesComeParentsFirstAndAnchorsAreRead() throws Refused {
		CosmeticGeometry g = CosmeticGeometry.parse(bytes(Placeholders.WINGS));
		assertEquals("anchor_body", g.bones().get(0).name());
		assertEquals(0, g.bones().get(1).parent());
		CosmeticModel gloves = CosmeticModel.bake(CosmeticGeometry.parse(bytes(Placeholders.GLOVES)), CosmeticModel.BODY, false);
		assertEquals(CosmeticModel.RIGHT_ARM, gloves.anchor[0]);
		assertEquals(CosmeticModel.LEFT_ARM, gloves.anchor[1]);
		assertEquals((1 << CosmeticModel.RIGHT_ARM) | (1 << CosmeticModel.LEFT_ARM), gloves.anchorsUsed);
		assertEquals(CosmeticModel.RIGHT_ARM, CosmeticModel.anchorNamed("anchor_rightarm"));
		assertEquals(CosmeticModel.LEFT_LEG, CosmeticModel.anchorNamed("Anchor_Left_Leg"));
	}

	@Test
	void modelSpaceIsMinecraftsPlayerModel() throws Refused {
		// The backpack: Bedrock x -3..3, y 14..22, z 2..5 (inflated 0.25) is model x -3..3, y 2..10 (24 - y), z 2..5, in blocks.
		CosmeticModel pack = CosmeticModel.bake(CosmeticGeometry.parse(bytes(Placeholders.BODYWEAR)), CosmeticModel.BODY, false);
		assertEquals(-3.25F / 16, pack.bounds[0], 1e-6);
		assertEquals(1.75F / 16, pack.bounds[1], 1e-6);
		assertEquals(1.75F / 16, pack.bounds[2], 1e-6);
		assertEquals(3.25F / 16, pack.bounds[3], 1e-6);
		assertEquals(10.25F / 16, pack.bounds[4], 1e-6);
		assertEquals(5.25F / 16, pack.bounds[5], 1e-6);
		// Six faces of a box; the front (north) face takes Minecraft's box layout: (u + d, v + d) to (u + d + w, v + d + h).
		assertEquals(6, pack.quadCount);
		float u0 = Float.MAX_VALUE;
		float u1 = -Float.MAX_VALUE;
		for (int i = 0; i < 4; i++) {
			u0 = Math.min(u0, pack.quads[i * 5 + 3]);
			u1 = Math.max(u1, pack.quads[i * 5 + 3]);
		}
		assertEquals(3F / 32, u0, 1e-6);
		assertEquals(9F / 32, u1, 1e-6);
		// A right-arm glove is relative to the arm's pivot (model -5, 2, 0): x -8..-4 → -3..1 (inflated 0.3).
		CosmeticModel gloves = CosmeticModel.bake(CosmeticGeometry.parse(bytes(Placeholders.GLOVES)), CosmeticModel.BODY, false);
		float minX = Float.MAX_VALUE;
		for (int q = gloves.quadStart[0]; q < gloves.quadStart[1]; q++) {
			for (int i = 0; i < 4; i++) {
				minX = Math.min(minX, gloves.quads[q * CosmeticModel.QUAD + i * 5]);
			}
		}
		assertEquals(-3.3F / 16, minX, 1e-6);
	}

	@Test
	void flatPlanesKeepOnlyTheirTwoFaces() throws Refused {
		CosmeticModel wings = CosmeticModel.bake(CosmeticGeometry.parse(bytes(Placeholders.WINGS)), CosmeticModel.BODY, false);
		assertEquals(4, wings.quadCount);
	}

	@Test
	void aMirroredModelIsOnTheOtherSide() throws Refused {
		CosmeticGeometry g = CosmeticGeometry.parse(bytes(Placeholders.PET));
		CosmeticModel right = CosmeticModel.bake(g, CosmeticModel.BODY, false);
		CosmeticModel left = CosmeticModel.bake(g, CosmeticModel.BODY, true);
		assertTrue(right.bounds[3] < 0, "the pet sits on the right (model -x)");
		assertEquals(-right.bounds[3], left.bounds[0], 1e-6);
		assertEquals(-right.bounds[0], left.bounds[3], 1e-6);
	}

	@Test
	void limitsAreEnforced() {
		// More cubes than the Store allows.
		StringBuilder many = new StringBuilder("{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{\"bones\":[{\"name\":\"anchor_body\",\"pivot\":[0,24,0],\"cubes\":[");
		for (int i = 0; i <= CosmeticLimits.MAX_CUBES; i++) {
			many.append(i == 0 ? "" : ",").append("{\"origin\":[0,0,0],\"size\":[1,1,1],\"uv\":[0,0]}");
		}
		many.append("]}]}]}");
		assertRefused(many.toString(), "cubes");
		// Too large a file, nested too deep, not JSON, an old format, a huge number, a negative size, a UV grid too big.
		assertThrows(Refused.class, () -> CosmeticGeometry.parse(new byte[CosmeticLimits.MAX_GEOMETRY_BYTES + 1]));
		assertRefused("[".repeat(1000), "deep");
		assertRefused("{not json", "JSON");
		assertRefused("{\"format_version\":\"1.8.0\",\"geometry.x\":{}}", "format");
		assertRefused(Placeholders.BODYWEAR.replace("[-3, 14, 2]", "[1e30, 14, 2]"), "range");
		assertRefused(Placeholders.BODYWEAR.replace("[6, 8, 3]", "[6, 8000, 3]"), "range");
		assertRefused(Placeholders.BODYWEAR.replace("\"texture_width\": 32", "\"texture_width\": 4096"), "texture_width");
		assertRefused(Placeholders.BODYWEAR.replace("[0, 24, 0]", "\"x\""), "three numbers");
		assertRefused(Placeholders.BODYWEAR.replace("\"name\": \"anchor_body\",", "\"name\": \"anchor_body\", \"poly_mesh\": {},"), "poly_mesh");
		// Molang: the agreed subset only, 128 characters at most.
		assertNull(Molang.compile("math.sqrt(4)"));
		assertNull(Molang.compile("1+".repeat(64) + "1"));
		// Animations: too long for an emote, too many keyframes.
		assertThrows(Refused.class, () -> CosmeticAnimation.parse(bytes(Placeholders.EMOTE.replace("\"animation_length\": 2", "\"animation_length\": 11")),
			CosmeticLimits.MAX_EMOTE_SECONDS));
		StringBuilder frames = new StringBuilder("{\"animations\":{\"a.b.idle\":{\"bones\":{\"x\":{\"rotation\":{");
		for (int i = 0; i <= CosmeticLimits.MAX_KEYFRAMES; i++) {
			frames.append(i == 0 ? "" : ",").append('"').append(i * 0.01).append("\":[0,0,0]");
		}
		frames.append("}}}}}}");
		assertThrows(Refused.class, () -> CosmeticAnimation.parse(bytes(frames.toString()), 100));
	}

	@Test
	void aBoneLoopBecomesARoot() throws Refused {
		CosmeticGeometry g = CosmeticGeometry.parse(bytes("""
			{"format_version":"1.12.0","minecraft:geometry":[{"bones":[{"name":"a","parent":"b","pivot":[0,0,0]},
			{"name":"b","parent":"a","pivot":[0,0,0]},{"name":"c","parent":"missing","pivot":[0,0,0]}]}]}"""));
		assertEquals(3, g.bones().size());
		assertEquals(-1, g.bones().get(0).parent());
	}

	private static void assertRefused(String json, String reason) {
		Refused r = assertThrows(Refused.class, () -> CosmeticGeometry.parse(bytes(json)));
		assertTrue(r.getMessage().contains(reason), r.getMessage());
	}

	// ---------- animations and poses ----------

	@Test
	void keyframesInterpolate() throws Refused {
		CosmeticAnimation a = CosmeticAnimation.parse(bytes(Placeholders.WINGS_ANIMATION), CosmeticLimits.MAX_ANIMATION_SECONDS);
		Clip fly = a.clip("fly");
		assertNotNull(fly);
		assertEquals(CosmeticAnimation.Loop.LOOP, fly.loop());
		float[] out = new float[3];
		fly.tracks().get(0).rotation().sample(0.125, 0, out, 0);
		assertArrayEquals(new float[] {0, -20, 0}, out, 1e-4F);
		// Looping: 0.625 s is 0.125 s into the clip.
		fly.tracks().get(0).rotation().sample(fly.time(0.625), 0, out, 0);
		assertArrayEquals(new float[] {0, -20, 0}, out, 1e-4F);
		Clip idle = a.clip("idle");
		idle.tracks().get(1).rotation().sample(0.5, 0, out, 0);
		assertEquals(15, out[1], 1e-4);
	}

	@Test
	void catmullRomStepAndEasing() throws Refused {
		CosmeticAnimation a = CosmeticAnimation.parse(bytes(Placeholders.PET_ANIMATION), 10);
		float[] out = new float[3];
		a.clip("idle").tracks().get(0).rotation().sample(0.5, 0, out, 0);
		assertEquals(-15, out[0], 1e-4);
		a.clip("idle").tracks().get(0).rotation().sample(0.25, 0, out, 0);
		assertTrue(out[0] < -7.5 && out[0] > -15, "a smooth curve overshoots the straight line toward the peak: " + out[0]);
		CosmeticAnimation stepped = CosmeticAnimation.parse(bytes("""
			{"animations":{"animation.x.idle":{"bones":{"b":{"position":{"0":{"vector":[0,0,0]},"1":{"vector":[8,0,0],"easing":"easeOutSine"},
			"2":{"post":[0,0,0],"lerp_mode":"step"},"3":[4,4,4]}}}}}}"""), 10);
		var channel = stepped.clip("idle").tracks().get(0).position();
		channel.sample(0.5, 0, out, 0);
		assertEquals(8 * Math.sin(Math.PI / 4), out[0], 1e-4);
		channel.sample(2.5, 0, out, 0);
		assertEquals(0, out[0], 1e-6);
		channel.sample(9, 0, out, 0);
		assertArrayEquals(new float[] {4, 4, 4}, out, 1e-6F);
	}

	@Test
	void unsupportedExpressionsRestSafely() throws Refused {
		CosmeticAnimation a = CosmeticAnimation.parse(bytes("""
			{"animations":{"animation.x.idle":{"loop":true,"bones":{"b":{"rotation":["variable.x", "query.ground_speed", 5],
			"scale":"variable.size"}}}}}"""), 10);
		float[] out = new float[3];
		a.clip("idle").tracks().get(0).rotation().sample(0, 0, out, 0);
		assertArrayEquals(new float[] {0, 0, 5}, out);
		// Molang only inside a value list: a bare expression for a whole channel is ignored.
		assertNull(a.clip("idle").tracks().get(0).scale());
	}

	@Test
	void poseTurnsBonesAboutTheirPivots() throws Refused {
		CosmeticModel model = CosmeticModel.bake(CosmeticGeometry.parse(bytes(Placeholders.AURA)), CosmeticModel.BODY, false);
		CosmeticAnimation animation = CosmeticAnimation.parse(bytes(Placeholders.AURA_ANIMATION), 120);
		ModelPose pose = new ModelPose(model, animation);
		assertTrue(pose.animated());
		int ring = model.bone("ring");
		// A quarter turn (1 s of 4): a point 8 px in front of the ring's axis (model z -8) goes to the side (x -8: y turns x to -z).
		pose.pose(ModelPose.IDLE, 1, 1);
		float[] p = pose.transform(ring, 0, 12F / 16, -8F / 16);
		assertEquals(-8F / 16, p[0], 1e-4);
		assertEquals(0, p[2], 1e-4);
		// And it bobs: sin(90°) · 2 px up (model -y).
		assertEquals((12 - 2) / 16F, p[1], 1e-4);
	}

	@Test
	void emoteBonesSample() throws Refused {
		CosmeticAnimation a = CosmeticAnimation.parse(bytes(Placeholders.EMOTE), CosmeticLimits.MAX_EMOTE_SECONDS);
		Clip wave = a.clips().values().iterator().next();
		assertEquals("flint_test_wave", wave.name());
		assertEquals(CosmeticAnimation.Loop.ONCE, wave.loop());
		assertTrue(wave.done(2.1));
		EmotePose pose = new EmotePose();
		pose.sample(wave, 0.3, 0.3);
		assertEquals(Math.toRadians(150), pose.rotation[EmotePose.RIGHT_ARM * 3 + 2], 1e-4);
		assertTrue(pose.moves(EmotePose.RIGHT_ARM));
		assertTrue(!pose.moves(EmotePose.LEFT_LEG));
	}

	// ---------- the website's conventions (store src/lib/cosmetic-model.ts), one test each ----------

	@Test
	void thinPlatesShowEachSideOwnArt() throws Refused {
		// A wing plane (no thickness in z): its back (south) face sits a hair behind its front (north) one.
		CosmeticModel wings = CosmeticModel.bake(CosmeticGeometry.parse(bytes(Placeholders.WINGS)), CosmeticModel.BODY, false);
		float north = wings.quads[2];
		float south = wings.quads[CosmeticModel.QUAD + 2];
		assertEquals(CosmeticModel.PLATE / 16, Math.abs(south - north), 1e-6);
		assertEquals(4, wings.quadCount);
	}

	@Test
	void boxUvFloorsSizesAndDownFacesBackEdgeIsOnTop() throws Refused {
		// A 2.5 x 2 x 2 cube at box UV (0, 0): its width counts as 2; the down face spans u 4..6, v 0..2 with v 0 at the back.
		CosmeticModel m = CosmeticModel.bake(CosmeticGeometry.parse(bytes("""
			{"format_version": "1.12.0", "minecraft:geometry": [{"description": {"texture_width": 16, "texture_height": 16},
			"bones": [{"name": "anchor_body", "pivot": [0, 24, 0], "cubes": [{"origin": [0, 20, 0], "size": [2.5, 2, 2], "uv": [0, 0]}]}]}]}""")),
			CosmeticModel.BODY, false);
		int down = 5;
		float uMin = Float.MAX_VALUE;
		float uMax = -Float.MAX_VALUE;
		for (int i = 0; i < 4; i++) {
			int o = down * CosmeticModel.QUAD + i * 5;
			uMin = Math.min(uMin, m.quads[o + 3]);
			uMax = Math.max(uMax, m.quads[o + 3]);
			// The back edge (+z) has the rectangle's top (v 0), the front edge its bottom (v 2).
			assertEquals(m.quads[o + 2] > 0.5F / 16 ? 0 : 2F / 16, m.quads[o + 4], 1e-6, "corner " + i);
		}
		assertEquals(4F / 16, uMin, 1e-6);
		assertEquals(6F / 16, uMax, 1e-6);
	}

	@Test
	void emotesBlendInAndOutOver150Ms() throws Refused {
		Clip wave = CosmeticAnimation.parse(bytes(Placeholders.EMOTE), CosmeticLimits.MAX_EMOTE_SECONDS).clips().values().iterator().next();
		assertEquals(0.5, EmotePose.weight(wave, 0.075), 1e-4);
		assertEquals(1, EmotePose.weight(wave, 1), 1e-6);
		assertEquals(0.5, EmotePose.weight(wave, 2 - 0.075), 1e-4);
		EmotePose pose = new EmotePose();
		pose.sample(wave, 0.3, 0.3);
		float[] parts = new float[EmotePose.PARTS * EmotePose.STRIDE];
		pose.apply(parts, 0.5F);
		// Half of 150°, turned a little more by the body it hangs from (-1.5° there, half of it).
		assertEquals(Math.toRadians(150) / 2, parts[EmotePose.RIGHT_ARM * EmotePose.STRIDE + 5], 0.02);
	}

	// ---------- fewer faces, the same pixels ----------

	@Test
	void wallsBetweenTouchingCubesGoWithoutChangingAPixel() throws Refused {
		// Two opaque boxes side by side in one bone: the faces between them are hidden; everything else stays.
		CosmeticModel two = CosmeticModel.bake(CosmeticGeometry.parse(bytes("""
			{"format_version": "1.12.0", "minecraft:geometry": [{"description": {"texture_width": 16, "texture_height": 16},
			"bones": [{"name": "anchor_body", "pivot": [0, 24, 0], "cubes": [{"origin": [0, 20, 0], "size": [2, 2, 2], "uv": [0, 0]},
			{"origin": [2, 20, 0], "size": [2, 2, 2], "uv": [0, 0]}]}]}]}""")), CosmeticModel.BODY, false);
		int[] opaque = new int[16 * 16];
		java.util.Arrays.fill(opaque, 0xFF808080);
		assertEquals(12, two.quadCount);
		assertEquals(10, two.withoutInnerWalls(opaque, 16, 16, 1).quadCount);
		// Seen through a see-through face, the walls stay: dropping them would show.
		int[] holes = opaque.clone();
		for (int i = 0; i < holes.length; i += 2) {
			holes[i] = 0;
		}
		assertEquals(12, two.withoutInnerWalls(holes, 16, 16, 1).quadCount);
	}

	@Test
	void othersDrawAtMostTheBiggestFaces() {
		float[] area = new float[CosmeticModel.LOD_QUADS + 100];
		for (int i = 0; i < area.length; i++) {
			area[i] = i;
		}
		float cut = CosmeticModel.lodArea(area, area.length);
		int kept = 0;
		for (float a : area) {
			if (a >= cut) {
				kept++;
			}
		}
		assertEquals(CosmeticModel.LOD_QUADS, kept);
		assertEquals(0, CosmeticModel.lodArea(area, CosmeticModel.LOD_QUADS));
	}

	/**
	 * Over every reference item with its texture: faces with no visible pixel and walls between touching cubes go, pixel for
	 * pixel the same (the walls' check renders each model before and after), and another player's copy is capped. Counted.
	 */
	@Test
	void referenceModelsLoseHiddenFacesOnly() throws IOException {
		String dir = System.getenv("FLINT_COSMETICS_REFERENCE");
		Assumptions.assumeTrue(dir != null && Files.isDirectory(Path.of(dir)), "FLINT_COSMETICS_REFERENCE not set");
		int models = 0;
		int baked = 0;
		int visible = 0;
		int lean = 0;
		int capped = 0;
		long nanos = 0;
		try (Stream<Path> files = Files.list(Path.of(dir))) {
			for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".geo.json")).toList()) {
				Path png = f.resolveSibling(f.getFileName().toString().replace(".geo.json", ".png"));
				if (!Files.isRegularFile(png)) {
					continue;
				}
				CosmeticModel m;
				com.flintclient.client.control.feature.CapePng.Image image;
				try {
					m = CosmeticModel.bake(CosmeticGeometry.parse(Files.readAllBytes(f)), CosmeticModel.BODY, false);
					image = com.flintclient.client.control.feature.CapePng.decode(Files.readAllBytes(png), 1024, 1024);
				} catch (Refused | com.flintclient.client.control.feature.CapePng.Refused e) {
					continue;
				}
				long t0 = System.nanoTime();
				CosmeticModel v = m.visibleOnly(image.argb(), image.width(), image.height(), 1);
				CosmeticModel w = v.withoutInnerWalls(image.argb(), image.width(), image.height(), 1);
				nanos += System.nanoTime() - t0;
				models++;
				baked += m.quadCount;
				visible += v.quadCount;
				lean += w.quadCount;
				int c = 0;
				for (int q = 0; q < w.quadCount; q++) {
					if (w.area[q] >= w.lodArea) {
						c++;
					}
				}
				assertTrue(c <= CosmeticModel.LOD_QUADS);
				capped += c;
			}
		}
		System.out.printf(java.util.Locale.ROOT, "reference faces: %d models, %d baked, %d with visible pixels, %d without hidden walls (%.1f ms), %d for others (cap %d)%n",
			models, baked, visible, lean, nanos / 1e6, capped, CosmeticModel.LOD_QUADS);
		assertTrue(lean <= visible && visible <= baked);
	}

	// ---------- the reference cosmetics (a folder on the dev PC; never in the repo) ----------

	/**
	 * Every geometry and animation in {@code FLINT_COSMETICS_REFERENCE} (a folder of real cosmetics, used only as test input on
	 * the dev PC) parses and bakes within the limits, timed. Skipped where the folder isn't set.
	 */
	@Test
	void everyReferenceFileParses() throws IOException {
		String dir = System.getenv("FLINT_COSMETICS_REFERENCE");
		Assumptions.assumeTrue(dir != null && Files.isDirectory(Path.of(dir)), "FLINT_COSMETICS_REFERENCE not set");
		List<Path> geometries = new ArrayList<>();
		List<Path> animations = new ArrayList<>();
		try (Stream<Path> files = Files.list(Path.of(dir))) {
			files.forEach(f -> {
				String n = f.getFileName().toString();
				if (n.endsWith(".geo.json")) {
					geometries.add(f);
				} else if (n.endsWith(".animation.json")) {
					animations.add(f);
				}
			});
		}
		List<String> failed = new ArrayList<>();
		long geometryNanos = 0;
		long bakeNanos = 0;
		int quads = 0;
		int maxQuads = 0;
		for (Path f : geometries) {
			byte[] b = Files.readAllBytes(f);
			try {
				long t0 = System.nanoTime();
				CosmeticGeometry g = CosmeticGeometry.parse(b);
				long t1 = System.nanoTime();
				CosmeticModel m = CosmeticModel.bake(g, CosmeticModel.BODY, false);
				long t2 = System.nanoTime();
				geometryNanos += t1 - t0;
				bakeNanos += t2 - t1;
				quads += m.quadCount;
				maxQuads = Math.max(maxQuads, m.quadCount);
			} catch (Refused e) {
				failed.add(f.getFileName() + ": " + e.getMessage());
			}
		}
		long animationNanos = 0;
		long sampleNanos = 0;
		int clips = 0;
		float[] out = new float[3];
		for (Path f : animations) {
			byte[] b = Files.readAllBytes(f);
			try {
				long t0 = System.nanoTime();
				CosmeticAnimation a = CosmeticAnimation.parse(b, CosmeticLimits.MAX_ANIMATION_SECONDS);
				animationNanos += System.nanoTime() - t0;
				long t1 = System.nanoTime();
				for (Clip c : a.clips().values()) {
					clips++;
					for (int s = 0; s < 60; s++) {
						double t = c.time(s / 30.0);
						for (var track : c.tracks()) {
							if (track.rotation() != null) {
								track.rotation().sample(t, s / 30.0, out, 0);
							}
							if (track.position() != null) {
								track.position().sample(t, s / 30.0, out, 0);
							}
							if (track.scale() != null) {
								track.scale().sample(t, s / 30.0, out, 0);
							}
						}
					}
				}
				sampleNanos += System.nanoTime() - t1;
			} catch (Refused e) {
				failed.add(f.getFileName() + ": " + e.getMessage());
			}
		}
		System.out.printf(java.util.Locale.ROOT,
			"reference cosmetics: %d geometries parsed in %.1f ms (baked in %.1f ms; %d quads, at most %d), %d animations in %.1f ms (%d clips, 60 samples each in %.1f ms)%n",
			geometries.size(), geometryNanos / 1e6, bakeNanos / 1e6, quads, maxQuads, animations.size(), animationNanos / 1e6, clips,
			sampleNanos / 1e6);
		assertTrue(failed.isEmpty(), failed.size() + " refused: " + String.join("; ", failed));
		assertTrue(geometries.size() + animations.size() > 0);
	}
}
