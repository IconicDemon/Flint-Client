package com.flintclient.client.control.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.model.mods.PlayerMods.KillSounds;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The id settings (one sound or item, a value per id, a set of ids): reading, writing, clamping and a migration. */
class IdSettingsTest {
	private static final IdSetting SOUND = new IdSetting("s", "Sound", null, IdKind.SOUND, "minecraft:entity.experience_orb.pickup");
	private static final IdValuesSetting VALUES = new IdValuesSetting("v", "Volumes", null, IdKind.SOUND, 100, 0, 100, 5, "%");
	private static final IdListSetting LIST = new IdListSetting("l", "Items", null, IdKind.ITEM, List.of());

	@Test
	void oneIdReadsOnlyRealIds() {
		assertEquals("minecraft:block.anvil.land", SOUND.parse(JsonParser.parseString("\"minecraft:block.anvil.land\"")));
		assertEquals("", SOUND.parse(JsonParser.parseString("\"\"")), "empty is None");
		assertEquals(SOUND.defaultValue(), SOUND.parse(JsonParser.parseString("\"Not An Id\"")));
		assertEquals(SOUND.defaultValue(), SOUND.parse(JsonParser.parseString("12")));
		assertTrue(SOUND.valid("mymod:custom/sound"));
		assertFalse(SOUND.valid("minecraft:"));
	}

	@Test
	void valuesKeepOnlyChangedClampedEntries() {
		Map<String, Integer> read = VALUES.parse(JsonParser.parseString(
			"{\"minecraft:a.b\": 50, \"minecraft:c\": 100, \"minecraft:d\": 230, \"minecraft:e\": 32, \"bad id\": 10, \"minecraft:f\": \"x\"}"));
		assertEquals(Map.of("minecraft:a.b", 50, "minecraft:e", 30), read, "100 is the default (dropped), 230 clamps to 100, 32 to 30");
		assertEquals(50, VALUES.of(read, "minecraft:a.b"));
		assertEquals(100, VALUES.of(read, "minecraft:zzz"));
		Map<String, Integer> next = VALUES.with(read, "minecraft:a.b", 100);
		assertEquals(Map.of("minecraft:e", 30), next, "back to the default removes it");
		assertEquals("{\"minecraft:a.b\":50,\"minecraft:e\":30}", VALUES.toJson(read).toString());
		assertTrue(VALUES.valid(read));
		assertFalse(VALUES.valid(Map.of("minecraft:x", 100)), "a default entry isn't stored");
	}

	@Test
	void listIsSortedAndUnique() {
		List<String> read = LIST.parse(JsonParser.parseString("[\"minecraft:diamond\", \"minecraft:apple\", \"minecraft:diamond\", \"nope\", 4]"));
		assertEquals(List.of("minecraft:apple", "minecraft:diamond"), read);
		assertEquals(List.of("minecraft:apple"), LIST.with(read, "minecraft:diamond", false));
		assertEquals(List.of("minecraft:apple", "minecraft:bread", "minecraft:diamond"), LIST.with(read, "minecraft:bread", true));
		assertTrue(LIST.valid(read));
		assertFalse(LIST.valid(List.of("minecraft:b", "minecraft:a")), "unsorted isn't a stored value");
	}

	@Test
	void killSoundsOldSingleSoundBecomesEveryKind() {
		ModuleState module = new ModuleState(ModuleRegistry.get(KillSounds.ID));
		JsonObject settings = JsonParser.parseString("{\"sound\": \"anvil\", \"volume\": 40}").getAsJsonObject();
		for (SettingDescriptor<?> setting : module.descriptor().settings()) {
			module.setFromJson(setting, settings.get(setting.id()));
		}
		ModuleMigrations.apply(module, settings);
		assertEquals("minecraft:block.anvil.land", module.text(KillSounds.MELEE));
		assertEquals("minecraft:block.anvil.land", module.text(KillSounds.PLAYER));
		assertEquals(40, module.integer(KillSounds.BOW_VOLUME));
		assertEquals(List.of("sound", "volume"), ModuleMigrations.retiredKeys(module.descriptor()));
	}

	@Test
	void killSoundsNewFileIsLeftAlone() {
		ModuleState module = new ModuleState(ModuleRegistry.get(KillSounds.ID));
		JsonObject settings = JsonParser.parseString("{\"sound\": \"anvil\", \"meleeSound\": \"\"}").getAsJsonObject();
		for (SettingDescriptor<?> setting : module.descriptor().settings()) {
			module.setFromJson(setting, settings.get(setting.id()));
		}
		ModuleMigrations.apply(module, settings);
		assertEquals("", module.text(KillSounds.MELEE));
	}
}
