package com.flintclient.client.control.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.model.mods.AmbientSoundsSettings;
import com.flintclient.client.control.model.mods.SoundMods;
import com.flintclient.client.control.model.mods.SoundPhysicsSettings;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class SoundModsTest {
	private static final String ROOT = "assets/flintclient/ambientsounds/basic/";

	private static JsonObject json(String path) throws IOException {
		try (InputStream in = SoundModsTest.class.getClassLoader().getResourceAsStream(path)) {
			assertNotNull(in, path);
			return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	private static List<String> files(String folder) throws IOException, URISyntaxException {
		URL url = SoundModsTest.class.getClassLoader().getResource(ROOT + folder);
		assertNotNull(url, folder);
		try (Stream<Path> list = Files.list(Path.of(url.toURI()))) {
			return list.map(p -> p.getFileName().toString().replace(".json", "")).sorted().toList();
		}
	}

	private static List<String> names(JsonElement sounds) {
		List<String> out = new ArrayList<>();
		if (sounds != null) {
			for (JsonElement s : sounds.getAsJsonArray()) {
				out.add(s.getAsJsonObject().get("name").getAsString());
			}
		}
		return out;
	}

	@Test
	void ambientSettingsMatchTheEngineData() throws Exception {
		assertEquals(files("dimensions"), List.of(AmbientSoundsSettings.DIMENSIONS).stream().sorted().toList());
		List<String> categories = new ArrayList<>();
		for (String[] c : AmbientSoundsSettings.CATEGORIES) {
			categories.add(c[0]);
			JsonObject data = json(ROOT + "sound_categories/" + c[0] + ".json");
			assertEquals(c[1], data.has("parent") ? data.get("parent").getAsString() : "", c[0]);
		}
		assertEquals(files("sound_categories"), categories.stream().sorted().toList());
		Map<String, List<String>> collections = new HashMap<>();
		for (String c : files("sound_collections")) {
			collections.put(c, names(json(ROOT + "sound_collections/" + c + ".json").get("sounds")).stream().map(n -> c + "." + n).toList());
		}
		Map<String, List<String>> regions = new HashMap<>();
		for (String d : files("dimensions")) {
			JsonObject dim = json(ROOT + "dimensions/" + d + ".json");
			if (dim.has("regions")) {
				for (JsonElement r : dim.getAsJsonArray("regions")) {
					regions.put(d + "." + r.getAsJsonObject().get("name").getAsString(), sounds(r.getAsJsonObject(), collections));
				}
			}
		}
		for (String r : files("regions")) {
			regions.put(r, sounds(json(ROOT + "regions/" + r + ".json"), collections));
		}
		assertEquals(regions.size(), AmbientSoundsSettings.REGIONS.length);
		for (String[] region : AmbientSoundsSettings.REGIONS) {
			List<String> sounds = regions.get(region[0]);
			assertNotNull(sounds, region[0]);
			assertEquals(sounds, List.of(region).subList(1, region.length), region[0]);
		}
	}

	@Test
	void timeCurvesListTheirHoursInOrder() throws Exception {
		// The engine reads a curve's points in file order: a reordered file (as JavaScript writes "17.5" after "24") breaks it.
		for (String folder : List.of("sound_categories", "regions")) {
			for (String name : files(folder)) {
				checkTimes(json(ROOT + folder + "/" + name + ".json"), folder + "/" + name);
			}
		}
	}

	private static void checkTimes(JsonElement element, String where) {
		if (element.isJsonArray()) {
			for (JsonElement e : element.getAsJsonArray()) {
				checkTimes(e, where);
			}
		} else if (element.isJsonObject()) {
			for (Map.Entry<String, JsonElement> e : element.getAsJsonObject().entrySet()) {
				if (e.getKey().equals("time") && e.getValue().isJsonObject()) {
					double last = Double.NEGATIVE_INFINITY;
					for (String hour : e.getValue().getAsJsonObject().keySet()) {
						if (hour.matches("-?[\\d.]+")) {
							assertTrue(Double.parseDouble(hour) > last, where + " time " + hour);
							last = Double.parseDouble(hour);
						}
					}
				} else {
					checkTimes(e.getValue(), where);
				}
			}
		}
	}

	private static List<String> sounds(JsonObject region, Map<String, List<String>> collections) {
		List<String> out = new ArrayList<>(names(region.get("sounds")));
		if (region.has("sound-collections")) {
			for (JsonElement c : region.getAsJsonArray("sound-collections")) {
				out.addAll(collections.getOrDefault(c.getAsString(), List.of()));
			}
		}
		return out;
	}

	@Test
	void soundPhysicsKeepsTheModsDefaults() {
		ModuleState state = new ModuleState(ModuleRegistry.get(SoundMods.SoundPhysics.ID));
		assertEquals(1F, state.decimal(SoundPhysicsSettings.ATTENUATION_FACTOR));
		assertEquals(0.9F, state.decimal(SoundPhysicsSettings.UNDERWATER_FILTER));
		assertEquals(32, state.integer(SoundPhysicsSettings.RAY_COUNT));
		assertEquals(4, state.integer(SoundPhysicsSettings.RAY_BOUNCES));
		assertEquals(101, SoundPhysicsSettings.SOUND_TYPES.length);
		int stone = List.of(SoundPhysicsSettings.SOUND_TYPES).indexOf("STONE");
		assertEquals(1.5F, state.decimal(SoundPhysicsSettings.REFLECTIVITY[stone]));
		int wool = List.of(SoundPhysicsSettings.SOUND_TYPES).indexOf("WOOL");
		assertEquals(1.5F, state.decimal(SoundPhysicsSettings.OCCLUSION[wool]));
		assertEquals(0.1F, state.decimal(SoundPhysicsSettings.REFLECTIVITY[wool]));
		assertEquals(0, (int) state.values(SoundPhysicsSettings.SOUND_RATES).get("minecraft:weather.rain"));
		// Setting rain back to "no limit" takes it out of the list.
		Map<String, Integer> rates = SoundPhysicsSettings.SOUND_RATES.with(state.values(SoundPhysicsSettings.SOUND_RATES), "minecraft:weather.rain", -1);
		assertTrue(!rates.containsKey("minecraft:weather.rain"));
	}

	@Test
	void settingsWaitForWhatTheyNeed() {
		ModuleState state = new ModuleState(ModuleRegistry.get(SoundMods.SoundPhysics.ID));
		assertEquals("Turn on Update long sounds first", ModuleSettings.inactiveReason(state, SoundPhysicsSettings.UPDATE_INTERVAL));
		state.set(SoundPhysicsSettings.UNSAFE_LEVEL_ACCESS, true);
		assertEquals("Direct world access is on", ModuleSettings.inactiveReason(state, SoundPhysicsSettings.CLONE_RANGE));
	}

}
