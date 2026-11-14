package com.flintclient.client.cosmetics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.store.StoreEndpoint;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelItemTest {
	private static final StoreEndpoint STORE = java.util.Objects.requireNonNull(StoreEndpoint.parse("http://127.0.0.1:8787", "test"));
	private static final String HASH = "a".repeat(64);

	/** A lookup slot as the website's contract answer describes it. */
	private static JsonObject entry(String kind, String model) {
		return JsonParser.parseString("{\"cosmetic_id\": \"11111111-2222-4333-8444-555555555555\", \"kind\": \"" + kind + "\", \"asset\": {\"model\": "
			+ model + "}}").getAsJsonObject();
	}

	private static String file(String name, int bytes) {
		return "{\"path\": \"m/" + name + "\", \"sha256\": \"" + HASH + "\", \"bytes\": " + bytes + ", \"url\": \"http://127.0.0.1:8787/m/" + name + "\"}";
	}

	@Test
	void readsTheContractsModel() {
		ModelItem pet = ModelItem.read(entry("pet", "{\"geometry\": " + file("g.json", 900) + ", \"texture\": " + file("t.png", 300)
			+ ", \"animation\": null, \"thumbnail\": " + file("th.png", 100) + ", \"texture_frames\": 4, \"frame_ms\": [100, 120, 80, 60],"
			+ " \"attach\": {\"anchor\": \"anchor_body\", \"side\": \"left\"}, \"hides_cape\": false}"), CosmeticKind.PET, STORE);
		assertNotNull(pet);
		assertEquals(4, pet.frames());
		assertArrayEquals(new int[] {100, 120, 80, 60}, pet.frameMs());
		assertEquals(360, pet.cycleMs());
		assertTrue(pet.mirror(), "a pet on the left shoulder is drawn mirrored");
		assertNull(pet.animation());
		ModelItem wings = ModelItem.read(entry("wings", "{\"geometry\": " + file("g.json", 900) + ", \"texture\": " + file("t.png", 300)
			+ ", \"hides_cape\": true, \"frame_ms\": 75}"), CosmeticKind.WINGS, STORE);
		assertTrue(wings.hidesCape());
		assertEquals(1, wings.frames());
		assertEquals(CosmeticModel.BODY, wings.anchor());
		ModelItem gloves = ModelItem.read(entry("gloves", "{\"geometry\": " + file("g.json", 900) + ", \"texture\": " + file("t.png", 300) + "}"),
			CosmeticKind.GLOVES, STORE);
		assertEquals(CosmeticModel.RIGHT_ARM, gloves.anchor());
		ModelItem emote = ModelItem.read(entry("emote", "{\"geometry\": null, \"texture\": null, \"animation\": " + file("a.json", 500) + "}"), null,
			STORE);
		assertEquals(CosmeticKind.EMOTE, emote.kind());
		ModelItem shield = ModelItem.read(entry("shield", "{\"texture\": " + file("t.png", 300) + "}"), CosmeticKind.SHIELD, STORE);
		assertNull(shield.geometry(), "a texture-only shield");
	}

	@Test
	void itemsTheFlintAppSendsLoadTheirFilesThroughIt() {
		// As the launcher's game items carry a model: sizes, frames and anchor; no addresses or hashes.
		JsonObject wave = JsonParser.parseString("{\"id\": \"C6A771FE-05EE-4AA5-A587-592560C12AA6\", \"kind\": \"emote\", \"name\": \"Wave\", \"model\": "
			+ "{\"animation\": {\"bytes\": 763}, \"thumbnail\": {\"bytes\": 4000, \"width\": 512, \"height\": 512}, \"frameMs\": []}}").getAsJsonObject();
		ModelItem emote = ModelItem.read(wave, CosmeticKind.EMOTE, STORE);
		assertNotNull(emote);
		assertEquals("c6a771fe-05ee-4aa5-a587-592560c12aa6", emote.id());
		assertEquals(ModelItem.LINK, emote.animation().url().getScheme());
		assertEquals("/c6a771fe-05ee-4aa5-a587-592560c12aa6/animation", emote.animation().url().getPath());
		assertEquals(763, emote.animation().bytes());
		JsonObject pet = JsonParser.parseString("{\"id\": \"7d91db6d-3698-4be6-9ab0-2ee9108b1d8b\", \"kind\": \"pet\", \"model\": {\"geometry\": {\"bytes\": 1026}, \""
			+ "texture\": {\"bytes\": 93, \"width\": 16, \"height\": 32}, \"textureFrames\": 2, \"frameMs\": [100, 200], \"attach\": "
			+ "{\"anchor\": \"anchor_body\", \"side\": \"left\"}}}").getAsJsonObject();
		ModelItem p = ModelItem.read(pet, CosmeticKind.PET, STORE);
		assertEquals(2, p.frames());
		assertArrayEquals(new int[] {100, 200}, p.frameMs());
		assertTrue(p.mirror());
		assertEquals(1026, p.geometry().bytes());
		// Past the limits, or without the files a kind needs: nothing.
		assertNull(ModelItem.linked("7d91db6d-3698-4be6-9ab0-2ee9108b1d8b", CosmeticKind.PET, JsonParser.parseString("{\"geometry\": {\"bytes\": 999999999}}").getAsJsonObject()));
		assertNull(ModelItem.linked("../x", CosmeticKind.EMOTE, JsonParser.parseString("{\"animation\": {\"bytes\": 10}}").getAsJsonObject()));
	}

	@Test
	void refusesWhatBreaksTheContract() {
		String ok = file("g.json", 900);
		String tex = file("t.png", 300);
		// Another kind than the slot's, a host the Store doesn't allow, too big, a bad hash, too many frames, too fast a frame.
		assertNull(ModelItem.read(entry("pet", "{\"geometry\": " + ok + ", \"texture\": " + tex + "}"), CosmeticKind.WINGS, STORE));
		assertNull(ModelItem.read(entry("pet", "{\"geometry\": " + ok.replace("127.0.0.1:8787", "evil.example") + ", \"texture\": " + tex + "}"),
			CosmeticKind.PET, STORE));
		assertNull(ModelItem.read(entry("pet", "{\"geometry\": " + file("g.json", CosmeticLimits.MAX_GEOMETRY_BYTES + 1) + ", \"texture\": " + tex + "}"),
			CosmeticKind.PET, STORE));
		assertNull(ModelItem.read(entry("pet", "{\"geometry\": " + ok.replace(HASH, "xyz") + ", \"texture\": " + tex + "}"), CosmeticKind.PET, STORE));
		assertNull(ModelItem.read(entry("pet", "{\"geometry\": " + ok + ", \"texture\": " + tex + ", \"texture_frames\": 13}"), CosmeticKind.PET, STORE));
		assertNull(ModelItem.read(entry("pet", "{\"geometry\": " + ok + ", \"texture\": " + tex + ", \"texture_frames\": 2, \"frame_ms\": [100, 20]}"),
			CosmeticKind.PET, STORE));
		assertNull(ModelItem.read(entry("pet", "{\"texture\": " + tex + "}"), CosmeticKind.PET, STORE), "only a shield may have no geometry");
		assertNull(ModelItem.read(entry("emote", "{\"geometry\": null}"), CosmeticKind.EMOTE, STORE), "an emote needs its animation");
		assertNull(ModelItem.read(entry("cape", "{}"), null, STORE));
	}

	@Test
	void devItemsAreReadFromAFolder(@TempDir Path root) throws Exception {
		Path wings = Files.createDirectories(root.resolve("wings"));
		Files.writeString(wings.resolve("item.json"), "{\"kind\": \"wings\", \"texture_frames\": 2, \"frame_ms\": 100, \"wearers\": \"all\"}");
		Files.writeString(wings.resolve("model.geo.json"), Placeholders.WINGS);
		Files.write(wings.resolve("texture.png"), com.flintclient.client.control.feature.CapePng.encodeRgba(32, 64, new int[32 * 64]));
		Path emote = Files.createDirectories(root.resolve("wave"));
		Files.writeString(emote.resolve("item.json"), "{\"kind\": \"emote\"}");
		Files.writeString(emote.resolve("model.animation.json"), Placeholders.EMOTE);
		Files.createDirectories(root.resolve("not-an-item"));
		List<DevCosmetics.DevItem> items = DevCosmetics.read(root);
		assertEquals(2, items.size());
		assertTrue(items.get(1).everyone());
		assertEquals(CosmeticKind.WINGS, items.get(1).item().kind());
		assertEquals(2, items.get(1).item().frames());
		assertEquals(CosmeticKind.EMOTE, items.get(0).item().kind());
		assertFalse(items.get(0).everyone());
		// The texture checks: a strip whose height isn't a whole number of frames is refused.
		assertNotNull(ModelCosmetics.texture(Files.readAllBytes(wings.resolve("texture.png")), 2));
		org.junit.jupiter.api.Assertions.assertThrows(CosmeticLimits.Refused.class,
			() -> ModelCosmetics.texture(Files.readAllBytes(wings.resolve("texture.png")), 3));
		// The UV grid at a whole 1-8x: 32x64 is the 32x32 grid in 2 frames; a 64x64 grid at 0.5x, or a stretched one, isn't.
		var image = ModelCosmetics.texture(Files.readAllBytes(wings.resolve("texture.png")), 2);
		ModelCosmetics.gridScale(image, 32, 32, 2);
		org.junit.jupiter.api.Assertions.assertThrows(CosmeticLimits.Refused.class, () -> ModelCosmetics.gridScale(image, 64, 64, 2));
		org.junit.jupiter.api.Assertions.assertThrows(CosmeticLimits.Refused.class, () -> ModelCosmetics.gridScale(image, 32, 16, 2));
	}
}
