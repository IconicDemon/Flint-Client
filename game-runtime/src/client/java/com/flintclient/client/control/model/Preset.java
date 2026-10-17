package com.flintclient.client.control.model;

import com.flintclient.client.control.hud.Anchor;
import com.flintclient.client.control.hud.HudPlacement;
import java.util.List;

/**
 * The starting layouts offered by first-time setup and "New layout". Positions are GUI units from the anchor.
 * The y offsets leave a 4-unit gap between stacked modules at their default size (FPS 40×11, Ping 44×11,
 * CPS 52×11, Coordinates 60×31, Direction 64×11, Keystrokes 52×52, Armor 40×72, Effects 92×40), checked by
 * {@code PresetTest} on screens from 320×240 up; re-check them whenever a module's measured size changes.
 * <ul>
 * <li>Minimal (minimal style): FPS top-left (4, 4), Coordinates top-left (4, 19).</li>
 * <li>Balanced (Flint style): FPS (4, 4), Ping (4, 19), Coordinates (4, 34), Direction (4, 69), all top-left;
 * Effects top-right (4, 4) with Minecraft's effect icons hidden; Armor right (4, 0).</li>
 * <li>PvP (Flint style): FPS (4, 4), Ping (4, 19), CPS (4, 34), all top-left; Keystrokes bottom-left (4, 40);
 * Armor right (4, 0); Effects top-right (4, 4).</li>
 * </ul>
 */
public enum Preset implements Labelled {
	MINIMAL("Minimal", HudThemeId.MINIMAL, List.of(
		new Entry(ModuleId.FPS, HudPlacement.of(Anchor.TOP_LEFT, 4, 4)),
		new Entry(ModuleId.COORDINATES, HudPlacement.of(Anchor.TOP_LEFT, 4, 19)))),
	BALANCED("Balanced", HudThemeId.FLINT, List.of(
		new Entry(ModuleId.FPS, HudPlacement.of(Anchor.TOP_LEFT, 4, 4)),
		new Entry(ModuleId.PING, HudPlacement.of(Anchor.TOP_RIGHT, 4, 4)),
		new Entry(ModuleId.COORDINATES, HudPlacement.of(Anchor.TOP_LEFT, 4, 19)),
		new Entry(ModuleId.DIRECTION, HudPlacement.of(Anchor.TOP_LEFT, 4, 54)),
		new Entry(ModuleId.EFFECTS, HudPlacement.of(Anchor.LEFT, 4, 0)),
		new Entry(ModuleId.ARMOR, HudPlacement.of(Anchor.BOTTOM_RIGHT, 4, 4)))),
	PVP("PvP", HudThemeId.FLINT, List.of(
		new Entry(ModuleId.FPS, HudPlacement.of(Anchor.TOP_LEFT, 4, 4)),
		new Entry(ModuleId.PING, HudPlacement.of(Anchor.TOP_RIGHT, 4, 4)),
		new Entry(ModuleId.CPS, HudPlacement.of(Anchor.TOP_LEFT, 4, 19)),
		new Entry(ModuleId.KEYSTROKES, HudPlacement.of(Anchor.BOTTOM_LEFT, 4, 40)),
		new Entry(ModuleId.ARMOR, HudPlacement.of(Anchor.BOTTOM_RIGHT, 4, 4)),
		new Entry(ModuleId.EFFECTS, HudPlacement.of(Anchor.LEFT, 4, 0))));

	/** One module the preset turns on, and where. */
	public record Entry(String moduleId, HudPlacement placement) {
	}

	private final String label;
	private final HudThemeId theme;
	private final List<Entry> entries;

	Preset(String label, HudThemeId theme, List<Entry> entries) {
		this.label = label;
		this.theme = theme;
		this.entries = entries;
	}

	/** Also the name of the layout it creates. */
	@Override
	public String label() {
		return label;
	}

	/** The style it suggests (first-time setup lets the player pick another). */
	public HudThemeId theme() {
		return theme;
	}

	public List<Entry> entries() {
		return entries;
	}

	/** The modules it turns on, for the setup card: {@code "FPS · Coordinates"}. */
	public String summary() {
		StringBuilder out = new StringBuilder();
		for (Entry entry : entries) {
			if (!out.isEmpty()) {
				out.append(" · ");
			}
			out.append(ModuleRegistry.get(entry.moduleId()).name());
		}
		return out.toString();
	}

	/** A new layout with this preset's modules, in {@code theme}. */
	public Layout create(String id, String name, HudThemeId theme) {
		Layout layout = new Layout(id, name);
		applyTo(layout, theme);
		return layout;
	}

	/** Turns on exactly this preset's modules at its positions (every other module off) and sets the style. */
	public void applyTo(Layout layout, HudThemeId theme) {
		layout.setAppearance(layout.appearance().withTheme(theme));
		for (ModuleState module : layout.modules()) {
			module.setEnabled(false);
		}
		for (Entry entry : entries) {
			ModuleState module = layout.module(ModuleRegistry.get(entry.moduleId()));
			module.setEnabled(true);
			module.setPlacement(entry.placement());
		}
		if (this == BALANCED) {
			layout.module(ModuleRegistry.get(ModuleId.EFFECTS)).set(ModuleSettings.Effects.HIDE_VANILLA_EFFECTS, true);
		}
	}
}
