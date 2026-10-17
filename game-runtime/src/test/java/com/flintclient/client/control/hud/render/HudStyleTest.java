package com.flintclient.client.control.hud.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.flintclient.client.control.hud.ScaleSteps;
import com.flintclient.client.control.model.Appearance;
import com.flintclient.client.control.model.HudColor;
import com.flintclient.client.control.model.HudThemeId;
import com.flintclient.client.control.model.Layout;
import com.flintclient.client.control.model.ModuleId;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.ui.kit.Tokens;
import java.lang.management.ManagementFactory;
import java.util.List;
import org.junit.jupiter.api.Test;

class HudStyleTest {
	private static final List<Appearance> LAYOUTS = List.of(Appearance.DEFAULTS, Appearance.DEFAULTS.withTheme(HudThemeId.CLASSIC),
		Appearance.DEFAULTS.withTheme(HudThemeId.MINIMAL), Appearance.DEFAULTS.withBackground(false),
		Appearance.DEFAULTS.withBackgroundOpacity(30).withTextColor(HudColor.SKY), Appearance.DEFAULTS.withPadding(0).withTheme(HudThemeId.CLASSIC),
		Appearance.DEFAULTS.withTheme(HudThemeId.MINIMAL).withBackground(true), Appearance.DEFAULTS.withAccentColor(HudColor.MINT),
		Appearance.DEFAULTS.withTheme(HudThemeId.CLASSIC).withAccentColor(HudColor.ROSE));
	private static final List<Appearance> MODULES = List.of(Appearance.INHERIT, Appearance.INHERIT.withTheme(HudThemeId.MINIMAL),
		Appearance.INHERIT.withTheme(HudThemeId.CLASSIC).withBackgroundOpacity(85), Appearance.INHERIT.withBackground(true),
		Appearance.INHERIT.withBackground(false).withPadding(8), Appearance.INHERIT.withTextColor(HudColor.ROSE).withShowLabel(false),
		Appearance.INHERIT.withTextColor(HudColor.DEFAULT).withTheme(HudThemeId.FLINT), Appearance.INHERIT.withAccentColor(HudColor.SKY),
		Appearance.INHERIT.withAccentColor(HudColor.DEFAULT).withTheme(HudThemeId.MINIMAL));

	@Test
	void resolvesExactlyAsTheModelsEffectiveAppearance() {
		HudStyle style = new HudStyle();
		for (Appearance shared : LAYOUTS) {
			Layout layout = new Layout("test", "Test");
			layout.setAppearance(shared);
			ModuleState module = layout.module(ModuleId.FPS);
			for (Appearance own : MODULES) {
				module.setAppearance(own);
				Appearance expected = layout.effectiveAppearance(module);
				style.resolve(layout.appearance(), module.appearance(), null, 1.0F, 2);
				String what = shared + " / " + own;
				assertEquals(expected.theme(), style.theme, what);
				assertEquals(expected.background(), style.background, what);
				assertEquals(Math.round(255 * (expected.backgroundOpacity() / 100.0F)), style.panel >>> 24, what);
				assertEquals(expected.showLabel(), style.showLabel, what);
				int text = expected.textColor().isDefault() ? (expected.theme() == HudThemeId.CLASSIC ? 0xFFFFFFFF : Tokens.TEXT)
					: expected.textColor().argb();
				assertEquals(text, style.text, what);
				assertEquals(Math.round(expected.padding() * 2.0F), style.padX, what);
				assertEquals(expected.accentColor().isDefault() ? Tokens.ACCENT : expected.accentColor().argb(), style.accent, what);
				if (!expected.accentColor().isDefault()) {
					assertEquals(expected.accentColor().argb(), style.label, what);
				}
			}
		}
	}

	@Test
	void aStyleOverrideActsAsTheModulesOwnChoice() {
		Layout layout = new Layout("test", "Test");
		ModuleState module = layout.module(ModuleId.COORDINATES);
		HudStyle style = new HudStyle();
		for (HudThemeId theme : HudThemeId.values()) {
			style.resolve(layout.appearance(), module.appearance(), theme, 1.0F, 3);
			Appearance expected = Appearance.effective(module.appearance().withTheme(theme), layout.appearance());
			assertEquals(theme, style.theme);
			assertEquals(expected.background(), style.background, "the panel follows the shown style: " + theme);
		}
	}

	@Test
	void theAccentTintsLabelsInEveryStyleAndDefaultKeepsEachStylesOwn() {
		HudStyle style = new HudStyle();
		style.set(HudThemeId.FLINT, HudColor.DEFAULT, true, 70, 3, true, 1.0F, 2);
		assertEquals(Tokens.ACCENT_TEXT, style.label, "Flint's ember");
		assertEquals(Tokens.ACCENT, style.accent);
		style.set(HudThemeId.CLASSIC, HudColor.DEFAULT, true, 40, 3, true, 1.0F, 2);
		assertEquals(0xFFAAAAAA, style.label, "Classic's grey");
		assertEquals(0xFF000000, style.ink);
		style.set(HudThemeId.MINIMAL, HudColor.DEFAULT, false, 70, 3, true, 1.0F, 2);
		assertEquals(0xB8F3ECE2, style.label, "Minimal's dimmed text");
		for (HudThemeId theme : HudThemeId.values()) {
			style.set(theme, HudColor.DEFAULT, true, 70, 3, true, 1.0F, 2, HudColor.SKY);
			assertEquals(HudColor.SKY.argb(), style.label, theme + ": labels take the accent");
			assertEquals(HudColor.SKY.argb(), style.accent, theme + ": pressed keys too");
			assertEquals(theme == HudThemeId.CLASSIC ? 0xFFFFFFFF : Tokens.TEXT, style.text, "the text keeps its own colour");
		}
	}

	@Test
	void aOneLineModuleWithDefaultPaddingIsElevenUnitsInEveryStyle() {
		HudStyle style = new HudStyle();
		for (HudThemeId theme : HudThemeId.values()) {
			for (int guiScale = 1; guiScale <= 8; guiScale++) {
				style.set(theme, HudColor.DEFAULT, true, 70, 3, true, 1.0F, guiScale);
				assertEquals(11 * guiScale, style.lineH + 2 * style.padY, theme + " at GUI scale " + guiScale);
			}
		}
	}

	@Test
	void everySizeStepScalesTheMetricsAndClassicStaysOnWholePixels() {
		HudStyle style = new HudStyle();
		for (int i = 0; i < ScaleSteps.count(); i++) {
			float step = ScaleSteps.step(i);
			for (int guiScale = 1; guiScale <= 6; guiScale++) {
				style.set(HudThemeId.FLINT, HudColor.DEFAULT, true, 70, 3, true, step, guiScale);
				assertEquals(Math.round(7 * step * guiScale), style.lineH);
				assertEquals(guiScale, style.textScale, "Flint text is rasterised for the pixel scale, sized by the step");
				style.set(HudThemeId.CLASSIC, HudColor.DEFAULT, true, 40, 3, true, step, guiScale);
				assertEquals(Math.max(1, Math.round(guiScale * step)), style.textScale, "Minecraft's font at whole pixels per font pixel");
				assertEquals(9 * style.textScale, style.lineH);
				assertEquals(style.textScale, style.shadowPx, "Minecraft's shadow is one font pixel");
			}
		}
	}

	@Test
	void shadowsFollowEachStyle() {
		HudStyle style = new HudStyle();
		style.set(HudThemeId.FLINT, HudColor.DEFAULT, true, 70, 3, true, 1.0F, 2);
		assertEquals(0, style.shadowFor(0xFFFFFFFF), "Flint has its panel instead");
		style.set(HudThemeId.CLASSIC, HudColor.DEFAULT, true, 40, 3, true, 1.0F, 2);
		assertEquals(0xFF3F3F3F, style.shadowFor(0xFFFFFFFF), "Minecraft's: a quarter of the colour");
		style.set(HudThemeId.MINIMAL, HudColor.DEFAULT, false, 70, 3, true, 1.0F, 2);
		assertEquals(0x99000000, style.shadowFor(0xFFF3ECE2), "black at 60 %");
		assertEquals(0x4D000000, style.shadowFor(0x80F3ECE2), "fading text fades its shadow");
		for (int guiScale = 1; guiScale <= 8; guiScale++) {
			style.set(HudThemeId.MINIMAL, HudColor.DEFAULT, false, 70, 3, true, 2.0F, guiScale);
			assertEquals(1, style.shadowPx, "Minimal's shadow is one pixel at every scale (GUI " + guiScale + ")");
		}
	}

	@Test
	void resolvingEveryFrameAllocatesNothing() {
		assumeTrue(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean);
		com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		assumeTrue(threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled());
		Layout layout = new Layout("test", "Test");
		layout.setAppearance(Appearance.DEFAULTS.withTheme(HudThemeId.CLASSIC));
		ModuleState module = layout.module(ModuleId.EFFECTS);
		module.setAppearance(Appearance.INHERIT.withBackgroundOpacity(55));
		HudStyle style = new HudStyle();
		long sink = 0;
		for (int i = 0; i < 2_000; i++) {
			style.resolve(layout.appearance(), module.appearance(), null, ScaleSteps.step(i % 6), 1 + i % 4);
		}
		long before = threads.getCurrentThreadAllocatedBytes();
		for (int i = 0; i < 50_000; i++) {
			style.resolve(layout.appearance(), module.appearance(), (i & 7) == 0 ? HudThemeId.MINIMAL : null, ScaleSteps.step(i % 6), 1 + i % 4);
			sink += style.lineH + style.panel;
		}
		long allocated = threads.getCurrentThreadAllocatedBytes() - before;
		assertTrue(sink != 0);
		assertTrue(allocated < 2048, allocated + " bytes for 50,000 resolves");
	}

	@Test
	void faceIndicesRoundTrip() {
		for (HudThemeId theme : HudThemeId.values()) {
			for (int role = 0; role < HudFaces.ROLES; role++) {
				for (int step = 0; step < HudFaces.STEPS; step++) {
					int index = HudFaces.index(theme, role, step);
					assertTrue(index >= 0 && index < HudFaces.COUNT);
					assertEquals(theme, HudFaces.theme(index));
					assertEquals(role, HudFaces.role(index));
					assertEquals(ScaleSteps.step(step), HudFaces.step(index));
				}
			}
		}
	}
}
