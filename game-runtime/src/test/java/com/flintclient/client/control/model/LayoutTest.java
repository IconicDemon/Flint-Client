package com.flintclient.client.control.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.hud.Anchor;
import com.flintclient.client.control.hud.HudPlacement;
import org.junit.jupiter.api.Test;

class LayoutTest {
	@Test
	void aNewLayoutHasEveryModuleOffInRegistryOrder() {
		Layout layout = new Layout("default", "Default");
		assertEquals(0, layout.enabledCount());
		assertEquals(ModuleRegistry.size(), layout.modules().size());
		for (int i = 0; i < ModuleRegistry.size(); i++) {
			assertSame(ModuleRegistry.all().get(i), layout.modules().get(i).descriptor());
		}
		assertSame(layout.modules().get(3), layout.module("keystrokes"));
		assertNull(layout.module("minimap"));
		assertEquals(HudThemeId.FLINT, layout.appearance().theme());
		assertNull(layout.appearance().backgroundOpacity(), "the panel follows the style");
		assertNull(layout.appearance().background());
		assertEquals(70, layout.effectiveAppearance(layout.module(ModuleId.FPS)).backgroundOpacity());
		assertNull(layout.appearance().showLabel(), "labels stay a per-module choice");
	}

	@Test
	void theSharedStyleIsAlwaysComplete() {
		Layout layout = new Layout("a", "A");
		assertTrue(layout.setAppearance(Appearance.INHERIT.withTheme(HudThemeId.MINIMAL)));
		assertEquals(HudThemeId.MINIMAL, layout.appearance().theme());
		assertEquals(HudColor.DEFAULT, layout.appearance().textColor(), "missing fields take the defaults");
		assertEquals(3, layout.appearance().padding());
		assertFalse(layout.setAppearance(layout.appearance()));
	}

	@Test
	void revisionGrowsWithEveryChangeAnywhere() {
		Layout layout = new Layout("a", "A");
		long start = layout.revision();
		layout.module(ModuleId.FPS).setEnabled(true);
		long afterModule = layout.revision();
		assertTrue(afterModule > start);
		layout.setName("B");
		assertTrue(layout.revision() > afterModule);
		long afterName = layout.revision();
		layout.setName("B");
		assertEquals(afterName, layout.revision(), "same name, no change");
	}

	@Test
	void aCopyIsDeepAndEqual() {
		Layout layout = new Layout("a", "A");
		layout.module(ModuleId.FPS).setEnabled(true);
		layout.module(ModuleId.FPS).set(ModuleSettings.Fps.SHOW_LOW, true);
		Layout copy = layout.copy();
		assertEquals(layout, copy);
		assertEquals(layout.hashCode(), copy.hashCode());
		copy.module(ModuleId.FPS).set(ModuleSettings.Fps.SHOW_LOW, false);
		copy.module(ModuleId.PING).setEnabled(true);
		assertTrue(layout.module(ModuleId.FPS).bool(ModuleSettings.Fps.SHOW_LOW), "the original is untouched");
		assertFalse(layout.module(ModuleId.PING).enabled());
		assertNotEquals(layout, copy);
		Layout renamed = layout.copyAs("b", "B");
		assertEquals("b", renamed.id());
		assertEquals("B", renamed.name());
		assertEquals(1, renamed.enabledCount());
	}

	@Test
	void restorePutsTheSnapshotBackInPlace() {
		Layout layout = new Layout("a", "A");
		ModuleState fps = layout.module(ModuleId.FPS);
		fps.setEnabled(true);
		Layout snapshot = layout.copy();
		fps.setPlacement(HudPlacement.of(Anchor.BOTTOM_RIGHT, 20, 20));
		fps.setAppearance(Appearance.INHERIT.withTheme(HudThemeId.CLASSIC));
		layout.module(ModuleId.ARMOR).setEnabled(true);
		layout.setAppearance(Appearance.DEFAULTS.withTextColor(HudColor.ROSE));
		long before = layout.revision();
		assertTrue(layout.restore(snapshot));
		assertEquals(snapshot, layout);
		assertSame(fps, layout.module(ModuleId.FPS), "the same state objects, so renderers keep working");
		assertTrue(layout.revision() > before, "restoring is a change too");
		assertFalse(layout.restore(snapshot));
	}

	@Test
	void resetPositionsMovesEveryModuleHome() {
		Layout layout = new Layout("a", "A");
		layout.module(ModuleId.CPS).setPlacement(HudPlacement.of(Anchor.CENTER, 0, 0));
		assertTrue(layout.resetPositions());
		assertEquals(ModuleRegistry.get(ModuleId.CPS).defaultPlacement(), layout.module(ModuleId.CPS).placement());
		assertFalse(layout.resetPositions());
	}

	@Test
	void effectiveAppearanceUsesTheModuleThenTheLayout() {
		Layout layout = new Layout("a", "A");
		layout.setAppearance(Appearance.DEFAULTS.withTheme(HudThemeId.CLASSIC));
		ModuleState fps = layout.module(ModuleId.FPS);
		assertEquals(HudThemeId.CLASSIC, layout.effectiveAppearance(fps).theme());
		fps.setAppearance(Appearance.INHERIT.withTheme(HudThemeId.MINIMAL));
		assertEquals(HudThemeId.MINIMAL, layout.effectiveAppearance(fps).theme());
		assertEquals(true, layout.effectiveAppearance(fps).showLabel());
	}

	@Test
	void eachStyleBringsItsOwnPanelUnlessOneIsChosen() {
		Layout layout = new Layout("a", "A");
		ModuleState fps = layout.module(ModuleId.FPS);
		Appearance flint = layout.effectiveAppearance(fps);
		assertEquals(true, flint.background());
		assertEquals(70, flint.backgroundOpacity());
		fps.setAppearance(Appearance.INHERIT.withTheme(HudThemeId.CLASSIC));
		assertEquals(40, layout.effectiveAppearance(fps).backgroundOpacity(), "a Classic module in a Flint layout gets Classic's panel");
		fps.setAppearance(Appearance.INHERIT.withTheme(HudThemeId.MINIMAL));
		assertEquals(false, layout.effectiveAppearance(fps).background(), "Minimal draws no panel");
		fps.setAppearance(Appearance.INHERIT.withTheme(HudThemeId.MINIMAL).withBackground(true));
		assertEquals(true, layout.effectiveAppearance(fps).background(), "unless the module asks for one");
		layout.setAppearance(layout.appearance().withBackgroundOpacity(55));
		fps.setAppearance(Appearance.INHERIT.withTheme(HudThemeId.CLASSIC));
		assertEquals(55, layout.effectiveAppearance(fps).backgroundOpacity(), "a chosen opacity wins over the style's");
		for (HudThemeId style : HudThemeId.values()) {
			assertTrue(Appearance.BACKGROUND_OPACITY.valid(style.defaultBackgroundOpacity()), style + " opacity is on a step");
			Appearance effective = Appearance.effective(Appearance.INHERIT, Appearance.INHERIT.withTheme(style));
			assertTrue(effective.theme() != null && effective.textColor() != null && effective.background() != null
				&& effective.backgroundOpacity() != null && effective.padding() != null && effective.showLabel() != null, "nothing is left unset");
		}
	}
}
