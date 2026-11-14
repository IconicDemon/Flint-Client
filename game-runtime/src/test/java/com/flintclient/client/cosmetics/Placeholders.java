package com.flintclient.client.cosmetics;

import java.nio.charset.StandardCharsets;

/**
 * Tiny original placeholder models, one per kind (plain cubes made for these tests; no one else's art), in Bedrock geometry
 * and animation JSON as Blockbench writes it.
 */
final class Placeholders {
	private Placeholders() {
	}

	static byte[] bytes(String json) {
		return json.getBytes(StandardCharsets.UTF_8);
	}

	/** Two flat wings behind the back, flapping in idle. */
	static final String WINGS = """
		{"format_version": "1.12.0", "minecraft:geometry": [{"description": {"identifier": "geometry.flint_test_wings",
		  "texture_width": 32, "texture_height": 32},
		  "bones": [
		    {"name": "anchor_body", "pivot": [0, 24, 0]},
		    {"name": "wing_left", "parent": "anchor_body", "pivot": [1, 20, 3], "rotation": [0, -20, 0],
		      "cubes": [{"origin": [1, 12, 3], "size": [14, 12, 0], "uv": {"north": {"uv": [0, 0], "uv_size": [14, 12]},
		        "south": {"uv": [14, 0], "uv_size": [14, 12]}}}]},
		    {"name": "wing_right", "parent": "anchor_body", "pivot": [-1, 20, 3], "rotation": [0, 20, 0],
		      "cubes": [{"origin": [-15, 12, 3], "size": [14, 12, 0], "uv": {"north": {"uv": [0, 12], "uv_size": [14, 12]},
		        "south": {"uv": [14, 12], "uv_size": [14, 12]}}}]}
		  ]}]}""";

	static final String WINGS_ANIMATION = """
		{"format_version": "1.8.0", "animations": {
		  "animation.flint_test_wings.idle": {"loop": true, "animation_length": 2, "bones": {
		    "wing_left": {"rotation": {"vector": [0, "-math.sin(q.anim_time*180)*15", 0]}},
		    "wing_right": {"rotation": {"vector": [0, "math.sin(q.anim_time*180)*15", 0]}}}},
		  "animation.flint_test_wings.fly": {"loop": true, "animation_length": 0.5, "bones": {
		    "wing_left": {"rotation": {"0.0": [0, 0, 0], "0.25": [0, -40, 0], "0.5": [0, 0, 0]}},
		    "wing_right": {"rotation": {"0.0": [0, 0, 0], "0.25": [0, 40, 0], "0.5": [0, 0, 0]}}}}}}""";

	/** A blocky crown: a band round the top of the head and five points, box UV. */
	static final String HEADWEAR = """
		{"format_version": "1.12.0", "minecraft:geometry": [{"description": {"identifier": "geometry.flint_test_crown",
		  "texture_width": 32, "texture_height": 16},
		  "bones": [{"name": "anchor_head", "pivot": [0, 24, 0], "cubes": [
		    {"origin": [-4.5, 31.5, -4.5], "size": [9, 2, 1], "uv": [0, 0]}, {"origin": [-4.5, 31.5, 3.5], "size": [9, 2, 1], "uv": [0, 0]},
		    {"origin": [-4.5, 31.5, -3.5], "size": [1, 2, 7], "uv": [0, 3]}, {"origin": [3.5, 31.5, -3.5], "size": [1, 2, 7], "uv": [0, 3]},
		    {"origin": [-4.5, 33.5, -4.5], "size": [1, 2, 1], "uv": [20, 0]}, {"origin": [3.5, 33.5, -4.5], "size": [1, 2, 1], "uv": [20, 0]},
		    {"origin": [-4.5, 33.5, 3.5], "size": [1, 2, 1], "uv": [20, 0]}, {"origin": [3.5, 33.5, 3.5], "size": [1, 2, 1], "uv": [20, 0]},
		    {"origin": [-0.5, 33.5, -4.5], "size": [1, 3, 1], "uv": [24, 0]}]}]}]}""";

	/** A backpack: one box on the back. */
	static final String BODYWEAR = """
		{"format_version": "1.12.0", "minecraft:geometry": [{"description": {"identifier": "geometry.flint_test_pack",
		  "texture_width": 32, "texture_height": 32},
		  "bones": [{"name": "anchor_body", "pivot": [0, 24, 0], "cubes": [
		    {"origin": [-3, 14, 2], "size": [6, 8, 3], "uv": [0, 0], "inflate": 0.25}]}]}]}""";

	/** A ring of four cubes around the waist, turning. */
	static final String AURA = """
		{"format_version": "1.12.0", "minecraft:geometry": [{"description": {"identifier": "geometry.flint_test_aura",
		  "texture_width": 16, "texture_height": 16},
		  "bones": [{"name": "anchor_body", "pivot": [0, 24, 0]},
		    {"name": "ring", "parent": "anchor_body", "pivot": [0, 12, 0], "cubes": [
		      {"origin": [-1, 11, -9], "size": [2, 2, 2], "uv": [0, 0]}, {"origin": [-1, 11, 7], "size": [2, 2, 2], "uv": [0, 0]},
		      {"origin": [-9, 11, -1], "size": [2, 2, 2], "uv": [0, 0]}, {"origin": [7, 11, -1], "size": [2, 2, 2], "uv": [0, 0]}]}]}]}""";

	static final String AURA_ANIMATION = """
		{"format_version": "1.8.0", "animations": {"animation.flint_test_aura.idle": {"loop": true, "animation_length": 4,
		  "bones": {"ring": {"rotation": {"0.0": [0, 0, 0], "4.0": [0, 360, 0]}, "position": {"vector": [0, "math.sin(q.anim_time*90)*2", 0]}}}}}}""";

	/** A cube creature on the right shoulder with a head that bobs. */
	static final String PET = """
		{"format_version": "1.12.0", "minecraft:geometry": [{"description": {"identifier": "geometry.flint_test_pet",
		  "texture_width": 32, "texture_height": 32},
		  "bones": [{"name": "root", "pivot": [-6, 24, 0], "cubes": [{"origin": [-8, 24, -2], "size": [4, 3, 4], "uv": [0, 0]}]},
		    {"name": "head", "parent": "root", "pivot": [-6, 27, -1], "cubes": [{"origin": [-7.5, 27, -3], "size": [3, 3, 3], "uv": [0, 7]}]}]}]}""";

	static final String PET_ANIMATION = """
		{"format_version": "1.8.0", "animations": {"animation.flint_test_pet.idle": {"loop": true, "animation_length": 1,
		  "bones": {"head": {"rotation": {"0.0": {"post": [0, 0, 0], "lerp_mode": "catmullrom"},
		    "0.5": {"post": [-15, 0, 0], "lerp_mode": "catmullrom"}, "1.0": {"post": [0, 0, 0], "lerp_mode": "catmullrom"}}}}}}}""";

	/** Gloves: a cuff on each arm, from one geometry with both arm anchors. */
	static final String GLOVES = """
		{"format_version": "1.12.0", "minecraft:geometry": [{"description": {"identifier": "geometry.flint_test_gloves",
		  "texture_width": 32, "texture_height": 16},
		  "bones": [{"name": "anchor_right_arm", "pivot": [-5, 22, 0], "cubes": [{"origin": [-8, 12, -2], "size": [4, 3, 4], "uv": [0, 0], "inflate": 0.3}]},
		    {"name": "anchor_left_arm", "pivot": [5, 22, 0], "cubes": [{"origin": [4, 12, -2], "size": [4, 3, 4], "uv": [0, 0], "inflate": 0.3, "mirror": true}]}]}]}""";

	/** A shield in the vanilla shield's place (its plate x -6..6, y 1..23, z -2..-1), with a boss on the front. */
	static final String SHIELD = """
		{"format_version": "1.12.0", "minecraft:geometry": [{"description": {"identifier": "geometry.flint_test_shield",
		  "texture_width": 64, "texture_height": 64},
		  "bones": [{"name": "shield", "pivot": [0, 12, 0], "cubes": [
		    {"origin": [-6, 1, -2], "size": [12, 22, 1], "uv": [0, 0]}, {"origin": [-1, 9, -1], "size": [2, 6, 6], "uv": [26, 0]},
		    {"origin": [-2, 10, -3], "size": [4, 4, 1], "uv": [0, 24]}]}]}]}""";

	/** A wave: the right arm up and waving, the head turning, once (2 s). */
	static final String EMOTE = """
		{"format_version": "1.8.0", "animations": {"animation.flint_test_wave": {"loop": false, "animation_length": 2,
		  "bones": {"right_arm": {"rotation": {"0.0": [0, 0, 0], "0.3": [0, 0, 150], "0.7": [0, 0, 120], "1.1": [0, 0, 150], "1.5": [0, 0, 120], "2.0": [0, 0, 0]}},
		    "head": {"rotation": {"0.0": [0, 0, 0], "1.0": [0, 20, 0], "2.0": [0, 0, 0]}},
		    "body": {"rotation": {"0.0": [0, 0, 0], "1.0": [0, 0, -5], "2.0": [0, 0, 0]}}}}}}""";
}
