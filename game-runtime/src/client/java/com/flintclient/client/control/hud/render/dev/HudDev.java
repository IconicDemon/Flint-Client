package com.flintclient.client.control.hud.render.dev;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.hud.Anchor;
import com.flintclient.client.control.hud.HudPlacement;
import com.flintclient.client.control.hud.render.HudRenderer;
import com.flintclient.client.control.model.HudColor;
import com.flintclient.client.control.model.HudThemeId;
import com.flintclient.client.control.model.Layout;
import com.flintclient.client.control.model.ModuleRegistry;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.Preset;
import com.flintclient.client.control.model.SettingDescriptor;
import com.google.gson.JsonPrimitive;
import java.util.Locale;

/**
 * Development only: lets a scripted test drive (the dev tour's {@code call} command) turn HUD modules on and change
 * their settings on the active layout, so each option's effect can be seen and screenshotted without clicking
 * through the Control Center. Nothing calls it in normal play and it holds no state: it does nothing unless one of
 * its methods is called. Changes are made in memory as the Control Center would make them (the layout is saved
 * only if the game later saves it). Every method takes plain values ({@code Object}: the tour passes strings,
 * numbers and booleans) and returns a short line the tour logs. Render thread.
 */
public final class HudDev {
	/** Keystrokes keys shown as held (bit i: forward, left, back, right, attack, use, jump); 0 unless {@link #hold} set it. */
	private static int heldKeys;

	private HudDev() {
	}

	/**
	 * Shows the keystrokes module's keys of {@code mask} as held, as if pressed (bit 0 forward, 1 left, 2 back, 3
	 * right, 4 attack, 5 use, 6 jump); 0 lets go. A test drive can't press real keys, and this is how it sees the
	 * pressed styles.
	 */
	public static String hold(Object mask) {
		heldKeys = (int) number(mask);
		return "holding " + Integer.toBinaryString(heldKeys);
	}

	/** Whether {@link #hold} shows keystrokes key {@code index} as held (false in normal play). */
	public static boolean held(int index) {
		return (heldKeys >> index & 1) != 0;
	}

	/** Exactly these modules on (comma-separated ids, or "none"), every other module off. */
	public static String only(Object ids) {
		String list = "," + String.valueOf(ids).toLowerCase(Locale.ROOT) + ",";
		Layout layout = ControlState.layout();
		StringBuilder on = new StringBuilder();
		for (ModuleState module : layout.modules()) {
			boolean wanted = list.contains("," + module.id() + ",");
			module.setEnabled(wanted);
			if (wanted) {
				on.append(module.id()).append(' ');
			}
		}
		return "on: " + on.toString().strip();
	}

	/** Sets a module setting from a plain value, read as the layout file would read it. */
	public static String set(Object moduleId, Object settingId, Object value) {
		ModuleState module = module(moduleId);
		SettingDescriptor<?> setting = module.descriptor().setting(String.valueOf(settingId));
		if (setting == null) {
			return "no setting " + settingId + " in " + moduleId;
		}
		JsonPrimitive json = switch (value) {
			case Boolean b -> new JsonPrimitive(b);
			case Number n -> new JsonPrimitive(n);
			default -> new JsonPrimitive(String.valueOf(value));
		};
		module.setFromJson(setting, json);
		return module.id() + "." + setting.id() + " = " + module.get(setting);
	}

	/** Every setting and style override of the module back to its default. */
	public static String reset(Object moduleId) {
		ModuleState module = module(moduleId);
		module.reset();
		return "reset " + module.id();
	}

	/** The layout's shared style: flint, classic or minimal. */
	public static String style(Object theme) {
		Layout layout = ControlState.layout();
		layout.setAppearance(layout.appearance().withTheme(HudThemeId.valueOf(String.valueOf(theme).toUpperCase(Locale.ROOT))));
		return "style " + layout.appearance().theme();
	}

	/** The layout's shared accent colour (a palette name: default, sky, mint...). */
	public static String accent(Object color) {
		Layout layout = ControlState.layout();
		layout.setAppearance(layout.appearance().withAccentColor(HudColor.valueOf(String.valueOf(color).toUpperCase(Locale.ROOT))));
		return "accent " + layout.appearance().accentColor();
	}

	/** The layout's shared text colour (a palette name). */
	public static String text(Object color) {
		Layout layout = ControlState.layout();
		layout.setAppearance(layout.appearance().withTextColor(HudColor.valueOf(String.valueOf(color).toUpperCase(Locale.ROOT))));
		return "text " + layout.appearance().textColor();
	}

	/** Moves a module: anchor name (TOP_LEFT...) and offsets in GUI units. */
	public static String place(Object moduleId, Object anchor, Object x, Object y) {
		ModuleState module = module(moduleId);
		HudPlacement placement = module.placement();
		module.setPlacement(new HudPlacement(Anchor.valueOf(String.valueOf(anchor).toUpperCase(Locale.ROOT)), number(x), number(y),
			placement.scale()));
		return module.id() + " at " + module.placement();
	}

	/** A module's size step (0.75, 1, 1.25...). */
	public static String size(Object moduleId, Object scale) {
		ModuleState module = module(moduleId);
		module.setPlacement(module.placement().withScale(number(scale)));
		return module.id() + " size " + module.placement().scale();
	}

	/** Shows a preset (minimal, balanced, pvp) on the live HUD as setup's preview would; "none" clears it. */
	public static String preview(Object preset) {
		String name = String.valueOf(preset).toUpperCase(Locale.ROOT);
		if (name.equals("NONE")) {
			HudRenderer.setPreviewLayout(null);
			return "preview cleared";
		}
		Preset chosen = Preset.valueOf(name);
		HudRenderer.setPreviewLayout(chosen.create("preview", chosen.label(), chosen.theme()));
		return "preview " + chosen.label();
	}

	/** Every module's summary and last HUD rectangle, for the log. */
	public static String report() {
		StringBuilder out = new StringBuilder();
		float[] rect = new float[4];
		for (int i = 0; i < ModuleRegistry.size(); i++) {
			String id = ModuleRegistry.all().get(i).id();
			out.append(id).append("=\"").append(HudRenderer.summary(id)).append('"');
			if (HudRenderer.lastRect(id, rect)) {
				out.append(String.format(Locale.ROOT, "@%.1f,%.1f %.1fx%.1f", rect[0], rect[1], rect[2], rect[3]));
			}
			out.append("; ");
		}
		return out.toString();
	}

	private static ModuleState module(Object moduleId) {
		ModuleState module = ControlState.layout().module(String.valueOf(moduleId));
		if (module == null) {
			throw new IllegalArgumentException("no module " + moduleId);
		}
		return module;
	}

	private static float number(Object value) {
		return value instanceof Number n ? n.floatValue() : Float.parseFloat(String.valueOf(value));
	}
}
