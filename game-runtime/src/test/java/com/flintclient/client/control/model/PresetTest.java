package com.flintclient.client.control.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.hud.Anchor;
import com.flintclient.client.control.hud.HudPlacement;
import com.flintclient.client.control.hud.HudRect;
import com.flintclient.client.control.hud.VanillaAreas;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PresetTest {
	private static List<String> enabled(Layout layout) {
		List<String> ids = new ArrayList<>();
		for (ModuleState module : layout.modules()) {
			if (module.enabled()) {
				ids.add(module.id());
			}
		}
		return ids;
	}

	@Test
	void minimal() {
		Layout layout = Preset.MINIMAL.create("minimal", "Minimal", Preset.MINIMAL.theme());
		assertEquals(List.of("fps", "coordinates"), enabled(layout));
		assertEquals(HudThemeId.MINIMAL, layout.appearance().theme());
		assertEquals(HudPlacement.of(Anchor.TOP_LEFT, 4, 4), layout.module(ModuleId.FPS).placement());
		assertEquals(HudPlacement.of(Anchor.TOP_LEFT, 4, 19), layout.module(ModuleId.COORDINATES).placement());
		assertEquals("FPS · Coordinates", Preset.MINIMAL.summary());
	}

	@Test
	void balanced() {
		Layout layout = Preset.BALANCED.create("balanced", "Balanced", HudThemeId.FLINT);
		assertEquals(List.of("fps", "ping", "coordinates", "direction", "armor", "effects"), enabled(layout));
		assertEquals(HudThemeId.FLINT, Preset.BALANCED.theme());
		assertEquals(HudPlacement.of(Anchor.LEFT, 4, 0), layout.module(ModuleId.EFFECTS).placement());
		assertEquals(HudPlacement.of(Anchor.BOTTOM_RIGHT, 4, 4), layout.module(ModuleId.ARMOR).placement());
		assertTrue(layout.module(ModuleId.EFFECTS).bool(ModuleSettings.Effects.HIDE_VANILLA_EFFECTS));
	}

	@Test
	void pvp() {
		Layout layout = Preset.PVP.create("pvp", "PvP", HudThemeId.FLINT);
		assertEquals(List.of("fps", "ping", "cps", "keystrokes", "armor", "effects"), enabled(layout));
		assertEquals(HudPlacement.of(Anchor.BOTTOM_LEFT, 4, 40), layout.module(ModuleId.KEYSTROKES).placement());
		assertEquals("PvP", Preset.PVP.label());
		assertEquals(HudThemeId.FLINT, Preset.PVP.theme());
	}

	@Test
	void theChosenStyleWins() {
		assertEquals(HudThemeId.CLASSIC, Preset.PVP.create("p", "P", HudThemeId.CLASSIC).appearance().theme());
	}

	@Test
	void applyingTurnsEverythingElseOff() {
		Layout layout = new Layout("a", "A");
		for (ModuleState module : layout.modules()) {
			module.setEnabled(true);
		}
		Preset.MINIMAL.applyTo(layout, HudThemeId.MINIMAL);
		assertEquals(List.of("fps", "coordinates"), enabled(layout));
	}

	@Test
	void noPresetOverlapsAtDefaultSizesOnAnyScreen() {
		for (Preset preset : Preset.values()) {
			Layout layout = preset.create("p", "P", preset.theme());
			for (float[] screen : ModuleRegistryTest.SCREENS) {
				List<HudRect> rects = new ArrayList<>();
				for (ModuleState module : layout.modules()) {
					if (!module.enabled()) {
						continue;
					}
					float[] size = ModuleRegistryTest.SIZES.get(module.id());
					HudRect rect = module.placement().toAbsolute(screen[0], screen[1], size[0], size[1]);
					assertTrue(rect.within(screen[0], screen[1]), preset + " " + module.id() + " fits " + screen[0] + "x" + screen[1]);
					rects.add(rect);
				}
				ModuleRegistryTest.assertNoOverlap(rects, screen);
			}
		}
	}

	@Test
	void presetsAndDefaultsStayClearOfTheHotbarAndStatusBars() {
		for (float[] screen : ModuleRegistryTest.SCREENS) {
			HudRect hotbar = VanillaAreas.hotbar(screen[0], screen[1]);
			HudRect status = VanillaAreas.statusBars(screen[0], screen[1]);
			List<String> where = new ArrayList<>();
			List<HudRect> rects = new ArrayList<>();
			for (Preset preset : Preset.values()) {
				for (ModuleState module : preset.create("p", "P", preset.theme()).modules()) {
					if (module.enabled()) {
						where.add(preset + " " + module.id());
						rects.add(rect(module.id(), module.placement(), screen));
					}
				}
			}
			for (ModuleDescriptor module : ModuleRegistry.core()) {
				where.add("default " + module.id());
				rects.add(rect(module.id(), module.defaultPlacement(), screen));
			}
			for (int i = 0; i < rects.size(); i++) {
				String on = " on " + screen[0] + "x" + screen[1];
				assertFalse(rects.get(i).overlaps(hotbar), where.get(i) + " covers the hotbar" + on);
				assertFalse(rects.get(i).overlaps(status), where.get(i) + " covers the status bars" + on);
			}
		}
	}

	@Test
	void effectsStayClearOfABossBarWhereBothFit() {
		// Narrower than 374 units (4:3 windows at the largest GUI scale) the corner and the centred bar can't both
		// fit; Minecraft's own effect icons overlap the bar there too.
		HudPlacement effects = ModuleRegistry.get(ModuleId.EFFECTS).defaultPlacement();
		for (float[] screen : ModuleRegistryTest.SCREENS) {
			if (screen[0] >= 374) {
				assertFalse(rect(ModuleId.EFFECTS, effects, screen).overlaps(VanillaAreas.bossBars(screen[0], 1)), "on " + screen[0] + "x" + screen[1]);
			}
		}
		for (Preset preset : Preset.values()) {
			ModuleState module = preset.create("p", "P", preset.theme()).module(ModuleId.EFFECTS);
			assertTrue(!module.enabled() || module.placement().equals(effects), preset + " uses the default spot");
		}
	}

	private static HudRect rect(String moduleId, HudPlacement placement, float[] screen) {
		float[] size = ModuleRegistryTest.SIZES.get(moduleId);
		return placement.toAbsolute(screen[0], screen[1], size[0], size[1]);
	}

	@Test
	void stackedModulesLeaveAGap() {
		for (Preset preset : Preset.values()) {
			Layout layout = preset.create("p", "P", preset.theme());
			List<HudRect> left = new ArrayList<>();
			for (ModuleState module : layout.modules()) {
				if (module.enabled() && module.placement().anchor() == Anchor.TOP_LEFT) {
					float[] size = ModuleRegistryTest.SIZES.get(module.id());
					left.add(module.placement().toAbsolute(640, 360, size[0], size[1]));
				}
			}
			left.sort((a, b) -> Float.compare(a.y(), b.y()));
			for (int i = 1; i < left.size(); i++) {
				assertEquals(4.0F, left.get(i).y() - left.get(i - 1).bottom(), 0.001F, preset + " keeps 4 units between stacked modules");
			}
		}
	}
}
