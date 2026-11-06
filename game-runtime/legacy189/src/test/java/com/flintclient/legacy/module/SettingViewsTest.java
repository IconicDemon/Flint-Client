package com.flintclient.legacy.module;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.model.ChoiceSetting;
import com.flintclient.client.control.model.FloatSetting;
import com.flintclient.client.control.model.IntSetting;
import com.flintclient.client.control.model.KeySetting;
import com.flintclient.client.control.model.LabelPosition;
import com.flintclient.client.control.model.OptionSetting;
import com.flintclient.client.control.model.RgbSetting;
import com.flintclient.legacy.shared.Slot;
import org.junit.jupiter.api.Test;
import org.lwjgl.input.Keyboard;

/** The 1.8.9 menu's views over shared settings: each shows and stores the shared value, converted for its control. */
class SettingViewsTest {
	@Test
	void numbersSnapToTheSharedStepAndMayShowANarrowerRange() {
		IntSetting shared = new IntSetting("n", "Number", null, 45, 0, 100, 5, "%");
		Slot slot = Slot.memory(45);
		Setting.Int view = new Setting.Int(slot, shared);
		view.set(52);
		assertEquals(50, slot.get());
		assertEquals("50%", view.format(view.value()));
		Setting.Int narrow = new Setting.Int(Slot.memory(95), shared, 10, 80);
		assertEquals(80, narrow.value(), "a value outside reads as the nearest end");
		narrow.set(3);
		assertEquals(10, narrow.value());
		assertTrue(new Setting.Int(Slot.memory(45), shared).isDefault());
	}

	@Test
	void decimalsShowOnASliderInParts() {
		FloatSetting shared = new FloatSetting("f", "Size", null, 1, 0.5F, 1.5F, 0.05F, "×");
		Slot slot = Slot.memory(1F);
		Setting.Decimal view = new Setting.Decimal(slot, shared, 100, "%", 0.8F, 1.2F);
		assertEquals(80, view.min);
		assertEquals(120, view.max);
		assertEquals(100, view.value());
		view.set(113);
		assertEquals(1.15F, (Float) slot.get(), 1e-6);
		assertEquals("115%", view.format(115));
		assertEquals("1.15×", new Setting.Decimal(Slot.memory(1F), shared, 100, "×").format(115));
		assertEquals(100, view.defaultValue());
	}

	@Test
	void choicesOfEnumsAndOptionsKeepTheSharedKeys() {
		ChoiceSetting<LabelPosition> label = new ChoiceSetting<>("labelPosition", "Label", null, LabelPosition.class, LabelPosition.AFTER);
		Slot slot = Slot.memory(LabelPosition.AFTER);
		Setting.Choice c = new Setting.Choice(slot, label);
		assertArrayEquals(new String[] {"After", "Before", "Hidden"}, c.options);
		c.set(1);
		assertEquals(LabelPosition.BEFORE, slot.get());
		assertEquals(LabelPosition.BEFORE, c.<LabelPosition>value());
		assertTrue(c.is(ChoiceSetting.key(LabelPosition.BEFORE)));
		OptionSetting display = new OptionSetting("display", "Display", null, 0, "both", "Block + Label", "label", "Label only");
		Slot index = Slot.memory(0);
		Setting.Choice o = new Setting.Choice(index, display);
		o.set(1);
		assertEquals(1, index.get());
		assertTrue(o.is("label"));
		o.set(9);
		assertEquals(1, o.index(), "clamped to the options");
	}

	@Test
	void aColourAndItsOpacityAreOneSwatch() {
		RgbSetting rgb = new RgbSetting("backgroundColor", "Background color", null, 0x000000);
		Slot color = Slot.memory(0x000000);
		Slot percent = Slot.memory(45);
		Setting.Color c = new Setting.Color(color, rgb, percent, true);
		assertTrue(c.alpha);
		assertEquals(0x73000000, c.argb(), "45% of 255 is 115");
		c.set(0x80FF8139);
		assertEquals(0xFF8139, color.get());
		assertEquals(50, percent.get());
		Slot alpha = Slot.memory(255);
		Setting.Color a = new Setting.Color(Slot.memory(0xFFFFFF), rgb, alpha, false);
		a.set(0x40123456);
		assertEquals(0x40, alpha.get());
		assertEquals(0x40123456, a.argb());
		Setting.Color opaque = new Setting.Color(Slot.memory(0x112233), rgb);
		assertFalse(opaque.alpha);
		assertEquals(0xFF112233, opaque.argb());
	}

	@Test
	void keysShowAs189CodesAndStoreSharedNames() {
		KeySetting shared = new KeySetting("key", "Zoom key", null, "key.keyboard.c");
		Slot slot = Slot.memory("key.keyboard.c");
		Setting.Key k = new Setting.Key(slot, shared);
		assertEquals(Keyboard.KEY_C, k.code());
		k.set(Keyboard.KEY_V);
		assertEquals("key.keyboard.v", slot.get());
		k.set(-98);
		assertEquals("key.mouse.middle", slot.get());
		k.set(0);
		assertEquals("", slot.get());
		assertEquals(Keyboard.KEY_C, k.defaultValue());
	}

	@Test
	void labelsReadInTheMenusTitleCase() {
		assertEquals("Background Opacity", Setting.titleCase("Background opacity"));
		assertEquals("Show “FPS”", Setting.titleCase("Show “FPS”"));
	}
}
