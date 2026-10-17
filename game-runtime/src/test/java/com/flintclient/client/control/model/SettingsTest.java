package com.flintclient.client.control.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

class SettingsTest {
	private static final IntSetting SIZE = new IntSetting("size", "Size", null, 16, 12, 24, 1, "");
	private static final IntSetting OPACITY = new IntSetting("opacity", "Opacity", null, 70, 0, 100, 5, "%");
	private static final IntSetting ODD = new IntSetting("odd", "Odd", null, 1, 1, 10, 4, "");
	private static final FloatSetting VOLUME = new FloatSetting("volume", "Volume", null, 0.6F, 0.0F, 1.0F, 0.05F, "%");
	private static final BoolSetting FLAG = new BoolSetting("flag", "Flag", "Explains", true);
	private static final ChoiceSetting<DurabilityFormat> FORMAT = new ChoiceSetting<>("format", "Format", null, DurabilityFormat.class,
		DurabilityFormat.PERCENT);
	private static final ColorSetting COLOR = new ColorSetting("color", "Color", null, HudColor.DEFAULT);

	private static final JsonElement[] JUNK = {null, JsonNull.INSTANCE, new JsonObject(), new JsonArray(), new JsonPrimitive("text"),
		JsonParser.parseString("\"16\"")};

	@Test
	void describesItself() {
		assertEquals("flag", FLAG.id());
		assertEquals("Flag", FLAG.label());
		assertEquals("Explains", FLAG.description());
		assertNull(SIZE.description());
		assertEquals(Boolean.TRUE, FLAG.defaultValue());
		assertEquals("%", OPACITY.unit());
		assertEquals(5, OPACITY.step());
	}

	@Test
	void boolReadsOnlyBooleans() {
		assertEquals(false, FLAG.parse(new JsonPrimitive(false)));
		assertEquals(true, FLAG.parse(new JsonPrimitive(true)));
		for (JsonElement junk : JUNK) {
			assertEquals(true, FLAG.parse(junk), String.valueOf(junk));
		}
		assertEquals(true, FLAG.parse(new JsonPrimitive(0)), "a number is not a boolean");
		assertEquals(true, FLAG.parse(new JsonPrimitive("false")), "a string is not a boolean");
		assertEquals(new JsonPrimitive(false), FLAG.toJson(false));
		assertTrue(FLAG.valid(false));
		assertFalse(FLAG.valid(1));
		assertFalse(FLAG.valid(null));
	}

	@Test
	void intClampsAndSnapsToTheStep() {
		assertEquals(12, SIZE.clamp(3));
		assertEquals(24, SIZE.clamp(99));
		assertEquals(17, SIZE.clamp(17));
		assertEquals(70, OPACITY.clamp(72), "snaps down to the nearest step");
		assertEquals(75, OPACITY.clamp(73), "snaps up to the nearest step");
		assertEquals(100, OPACITY.clamp(1000));
		assertEquals(0, OPACITY.clamp(-5));
		assertEquals(9, ODD.clamp(10), "the last whole step stops short of max");
		assertEquals(5, ODD.clamp(6));
		assertTrue(OPACITY.valid(70));
		assertFalse(OPACITY.valid(72), "off the step");
		assertFalse(OPACITY.valid(105), "out of range");
		assertFalse(OPACITY.valid(70L), "wrong type");
		assertFalse(OPACITY.valid(70.0F), "wrong type");
	}

	@Test
	void intParsesNumbersClampedAndEverythingElseAsDefault() {
		assertEquals(20, SIZE.parse(new JsonPrimitive(20)));
		assertEquals(17, SIZE.parse(new JsonPrimitive(16.7)), "rounded to the nearest step");
		assertEquals(24, SIZE.parse(new JsonPrimitive(1e12)), "huge numbers clamp");
		assertEquals(12, SIZE.parse(new JsonPrimitive(-3)));
		for (JsonElement junk : JUNK) {
			assertEquals(16, SIZE.parse(junk), String.valueOf(junk));
		}
		assertEquals(16, SIZE.parse(new JsonPrimitive(true)));
		assertEquals(16, SIZE.parse(JsonParser.parseString("NaN")), "lenient JSON's NaN is not a value");
		assertEquals(16, SIZE.parse(JsonParser.parseString("Infinity")));
		assertEquals(new JsonPrimitive(18), SIZE.toJson(18));
	}

	@Test
	void floatClampsSnapsAndStaysExact() {
		assertEquals(0.6F, VOLUME.clamp(0.6F));
		assertEquals(0.6F, VOLUME.clamp(0.61F), "snaps to 0.05 steps");
		assertEquals(0.65F, VOLUME.clamp(0.64F));
		assertEquals(1.0F, VOLUME.clamp(7F));
		assertEquals(0.0F, VOLUME.clamp(-1F));
		assertEquals(0.6F, VOLUME.clamp(Float.NaN), "NaN means the default");
		assertEquals(0.6F, VOLUME.clamp(Float.POSITIVE_INFINITY), "infinity means the default");
		for (int i = 0; i <= 20; i++) {
			float stepped = VOLUME.clampFloat(i * 0.05);
			assertEquals(Float.parseFloat(String.valueOf(i * 5 / 100.0)), stepped, "step " + i + " is the exact decimal");
			assertTrue(VOLUME.valid(stepped));
		}
		assertFalse(VOLUME.valid(0.61F));
		assertFalse(VOLUME.valid(Float.NaN));
		assertFalse(VOLUME.valid(0.6));
		assertEquals(0.25F, VOLUME.parse(new JsonPrimitive(0.25)));
		assertEquals(1.0F, VOLUME.parse(new JsonPrimitive(2)));
		for (JsonElement junk : JUNK) {
			assertEquals(0.6F, VOLUME.parse(junk), String.valueOf(junk));
		}
		assertEquals("0.6", VOLUME.toJson(0.6F).toString(), "written as the short decimal");
	}

	@Test
	void manyDecimalStepsStillReachTheEnd() {
		FloatSetting fine = new FloatSetting("fine", "Fine", null, 1.0F, 0.0F, 100.0F, 0.05F, "");
		assertEquals(100.0F, fine.clampFloat(100));
		assertEquals(100.0F, fine.clampFloat(1000));
		assertTrue(fine.valid(100.0F));
		assertEquals(37.45F, fine.clampFloat(37.46));
		FloatSetting quarters = new FloatSetting("q", "Q", null, 0.5F, 0.5F, 2.0F, 0.25F, "");
		assertEquals(2.0F, quarters.clampFloat(2));
		FloatSetting tenths = new FloatSetting("t", "T", null, 0.0F, 0.0F, 1.0F, 0.1F, "");
		assertEquals(1.0F, tenths.clampFloat(1));
		assertTrue(tenths.valid(0.3F));
	}

	@Test
	void choiceReadsNamesInAnyCaseAndWritesLowerCase() {
		assertEquals(DurabilityFormat.NUMBER, FORMAT.parse(new JsonPrimitive("number")));
		assertEquals(DurabilityFormat.NONE, FORMAT.parse(new JsonPrimitive("NONE")));
		assertEquals(DurabilityFormat.PERCENT, FORMAT.parse(new JsonPrimitive("Percent")));
		assertEquals(DurabilityFormat.PERCENT, FORMAT.parse(new JsonPrimitive("bars")), "unknown option means the default");
		for (JsonElement junk : JUNK) {
			assertEquals(DurabilityFormat.PERCENT, FORMAT.parse(junk), String.valueOf(junk));
		}
		assertEquals(DurabilityFormat.PERCENT, FORMAT.parse(new JsonPrimitive(1)), "ordinals are not accepted");
		assertEquals(new JsonPrimitive("number"), FORMAT.toJson(DurabilityFormat.NUMBER));
		assertEquals(4, FORMAT.options().size());
		assertTrue(FORMAT.valid(DurabilityFormat.NONE));
		assertFalse(FORMAT.valid(Orientation.VERTICAL));
		assertFalse(FORMAT.valid("none"));
	}

	@Test
	void colorChoosesFromThePalette() {
		assertEquals(HudColor.EMBER, COLOR.parse(new JsonPrimitive("ember")));
		assertEquals(HudColor.DEFAULT, COLOR.parse(new JsonPrimitive("#FF0000")), "only palette colours");
		assertEquals(new JsonPrimitive("lavender"), COLOR.toJson(HudColor.LAVENDER));
		assertEquals(8, COLOR.options().size());
		assertEquals(0, HudColor.DEFAULT.argb());
		assertTrue(HudColor.DEFAULT.isDefault());
		assertEquals(0xFFF3ECE2, HudColor.WHITE.argb());
		assertEquals(0xFFE7962F, HudColor.EMBER.argb());
		assertEquals(0xFFE3C58F, HudColor.SAND.argb());
		assertEquals(0xFF8EC3E6, HudColor.SKY.argb());
		assertEquals(0xFF8FD3B6, HudColor.MINT.argb());
		assertEquals(0xFFE8A0B4, HudColor.ROSE.argb());
		assertEquals(0xFFB8A6E8, HudColor.LAVENDER.argb());
		for (HudColor color : HudColor.values()) {
			assertFalse(color.label().isEmpty());
		}
	}

	@Test
	void coerceKeepsTheTypeOrFallsBack() {
		assertEquals(24, SIZE.coerce(30));
		assertEquals(16, SIZE.coerce("30"));
		assertEquals(16, SIZE.coerce(null));
		assertEquals(DurabilityFormat.PERCENT, FORMAT.coerce(Orientation.HORIZONTAL));
	}

	@Test
	void badRangesAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> new IntSetting("a", "A", null, 5, 10, 1, 1, ""));
		assertThrows(IllegalArgumentException.class, () -> new IntSetting("a", "A", null, 5, 0, 10, 0, ""));
		assertThrows(IllegalArgumentException.class, () -> new IntSetting("a", "A", null, 11, 0, 10, 1, ""));
		assertThrows(IllegalArgumentException.class, () -> new IntSetting("a", "A", null, 3, 0, 10, 2, ""), "default off the step");
		assertThrows(IllegalArgumentException.class, () -> new FloatSetting("a", "A", null, 0.5F, 0, 1, 0, ""));
		assertThrows(IllegalArgumentException.class, () -> new FloatSetting("a", "A", null, 2F, 0, 1, 0.1F, ""));
		assertThrows(IllegalArgumentException.class, () -> new FloatSetting("a", "A", null, 0.33F, 0, 1, 0.25F, ""), "default off the step");
	}

	@Test
	void interfaceSizeNeverExceedsWhatFits() {
		assertEquals(3, InterfaceSize.fit(1280, 720, 380, 230), "1280/380 = 3, 720/230 = 3");
		assertEquals(2, InterfaceSize.fit(854, 480, 380, 230));
		assertEquals(1, InterfaceSize.fit(300, 200, 380, 230), "at least 1 even when it doesn't fit");
		assertEquals(2, InterfaceSize.AUTO.uiScale(2, 3));
		assertEquals(3, InterfaceSize.LARGER.uiScale(2, 3));
		assertEquals(3, InterfaceSize.LARGER.uiScale(3, 3), "larger only when it fits");
		assertEquals(1, InterfaceSize.SMALLER.uiScale(2, 3));
		assertEquals(1, InterfaceSize.SMALLER.uiScale(1, 3), "never below 1");
		assertEquals(2, InterfaceSize.AUTO.uiScale(4, 2), "auto is capped by the fit too");
		assertEquals(1, InterfaceSize.AUTO.uiScale(2, 0));
	}
}
