package com.flintclient.client.control.rshift;

import static com.flintclient.client.control.rshift.MenuStyle.r;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.model.Appearance;
import com.flintclient.client.control.model.BoolSetting;
import com.flintclient.client.control.model.ChoiceSetting;
import com.flintclient.client.control.model.FloatSetting;
import com.flintclient.client.control.model.GameOptions;
import com.flintclient.client.control.model.HudThemeId;
import com.flintclient.client.control.model.IdKind;
import com.flintclient.client.control.model.IdListSetting;
import com.flintclient.client.control.model.IdSetting;
import com.flintclient.client.control.model.IdValuesSetting;
import com.flintclient.client.control.model.IntSetting;
import com.flintclient.client.control.model.InterfaceSize;
import com.flintclient.client.control.model.KeyConflicts;
import com.flintclient.client.control.model.KeySetting;
import com.flintclient.client.control.model.Labelled;
import com.flintclient.client.control.model.ModuleDescriptor;
import com.flintclient.client.control.model.ModuleSettings;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.OptionSetting;
import com.flintclient.client.control.model.RgbSetting;
import com.flintclient.client.control.model.SettingDescriptor;
import com.flintclient.client.control.model.TextSetting;
import com.flintclient.client.control.model.VideoOptions;
import com.flintclient.client.platform.Ids;
import com.flintclient.client.platform.ItemPainter;
import com.flintclient.client.platform.MinecraftOptions;
import com.flintclient.client.control.rshift.MenuStyle.Label;
import com.flintclient.client.control.rshift.MenuStyle.Type;
import com.flintclient.client.control.rshift.PageControls.ColorSwatch;
import com.flintclient.client.control.rshift.PageControls.KeyBind;
import com.flintclient.client.control.rshift.PageControls.PickField;
import com.flintclient.client.control.rshift.PageControls.Segmented;
import com.flintclient.client.control.rshift.PageControls.Slider;
import com.flintclient.client.control.rshift.PageControls.Switch;
import com.flintclient.client.control.store.ControlSettings;
import com.flintclient.client.platform.MenuHost;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FontFace;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.client.ui.kit.Pressable;
import com.flintclient.client.ui.kit.TextField;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * A catalog row as a line of a settings page: its label (the reference's name), a note under it, and the control of the
 * Flint setting it is bound to, which reads the model every frame and writes it at once. A row Flint doesn't have is
 * shown dimmed and marked "Not in Flint yet", with no control, so nothing on a page pretends to work.
 */
final class Rows {
	/** A row with a note under its name, and a plain one (Dawn's 32: its tallest control); the list puts 8 between rows. */
	static final float H = 90;
	static final float H_PLAIN = 57.6F;

	private Rows() {
	}

	/** The line for {@code row}. */
	static Node line(Host host, Catalog.Row row) {
		if (!row.inFlint()) {
			return new Missing(row.label(), row.unavailable());
		}
		if (row.action() != null && !row.action().equals(Catalog.LINK)) {
			return new Line(row.label(), new Button(actionNote(row.action())), new PageControls.TextButton(actionText(row.action()), Glyph.FOLDER,
				PageControls.Look.PLAIN, () -> MenuHost.openFolder(row.action())));
		}
		Access access = Access.of(row);
		if (row.action() != null) {
			// A shortcut to a mod: its switch, and its page a click away.
			Catalog.Mod mod = Catalog.get().modFor(row.module().id());
			String name = mod != null ? mod.name() : row.module().name();
			Node control = new Shortcut(new Switch(() -> (Boolean) access.get(), () -> access.set(!(Boolean) access.get())),
				new Parts.Square(Glyph.CHEVRON_RIGHT, "Open " + name + " settings", () -> host.openModule(row.module().id())));
			return new Line(row.label(), new Button(row.module().description()), control);
		}
		Node control = control(host, access, row);
		Line line = new Line(row.label(), access, control);
		if (row.setting() instanceof IdValuesSetting values && values.addable()) {
			// What it added are rows of their own, each with its remove: no reset (it would remove them all at once).
			line.withoutReset();
		}
		return line;
	}

	private static String actionText(String action) {
		return "Open folder";
	}

	private static String actionNote(String action) {
		return switch (action) {
			case "screenshots" -> "Opens Minecraft's screenshots folder (F2 saves there)";
			case "resourcepacks" -> "Opens the folder Minecraft reads resource packs from";
			default -> action;
		};
	}

	/** A row that only does something when pressed (no value of its own): its tooltip says what. */
	private static final class Button extends Access {
		private final @Nullable String about;

		Button(@Nullable String about) {
			this.about = about;
		}

		@Override
		Object get() {
			return Boolean.FALSE;
		}

		@Override
		boolean set(Object value) {
			return false;
		}

		@Override
		boolean isDefault() {
			return true;
		}

		@Override
		boolean reset() {
			return false;
		}

		@Override
		@Nullable String about() {
			return about;
		}
	}

	/** A mod's switch with a chevron to its page beside it. */
	private static final class Shortcut extends Node {
		private final Switch toggle;
		private final Parts.Square open;

		Shortcut(Switch toggle, Parts.Square open) {
			this.toggle = add(toggle);
			this.open = add(open);
		}

		@Override
		public float prefWidth() {
			return toggle.prefWidth() + r(16) + r(40);
		}

		@Override
		protected void layout() {
			float sw = toggle.prefWidth();
			float sh = r(Switch.H);
			toggle.setBounds(x, y + (h - sh) / 2, sw, sh);
			open.setBounds(x + sw + r(16), y + (h - r(40)) / 2, r(40), r(40));
		}

		@Override
		public void setDisabled(String reason) {
			toggle.setDisabled(reason);
			open.setDisabled(reason);
		}

		@Override
		public void setEnabled(boolean value) {
			toggle.setEnabled(value);
			open.setEnabled(value);
		}
	}

	// ---------- reading and writing a setting wherever it lives ----------

	/** One setting (or a module's on/off): on the profile in use (looked up each time, as profiles switch) or Flint's own. */
	abstract static class Access {
		abstract Object get();

		/** Writes {@code value}; whether it changed anything. */
		abstract boolean set(Object value);

		abstract boolean isDefault();

		abstract boolean reset();

		/** Why the setting does nothing right now (another setting makes it moot), or null. */
		@Nullable String inactive() {
			return null;
		}

		@Nullable SettingDescriptor<?> setting() {
			return null;
		}

		@Nullable ModuleDescriptor module() {
			return null;
		}

		/** What the row does, for its tooltip (the setting's description), or null. */
		@Nullable String about() {
			SettingDescriptor<?> setting = setting();
			return setting != null ? setting.description() : null;
		}

		/** The row's button removes it (an item the player added) rather than resetting it. */
		boolean removes() {
			return false;
		}

		static Access of(Catalog.Row row) {
			if (row.look()) {
				return new Look(row.module(), row.setting());
			}
			if (row.minecraft()) {
				return new Vanilla(row.setting());
			}
			if (row.global()) {
				return new Global(row.setting());
			}
			if (row.setting() == null) {
				return new Enabled(row.module());
			}
			return new Module(row.module(), row.setting());
		}
	}

	private static final class Module extends Access {
		private final ModuleDescriptor module;
		private final SettingDescriptor<?> setting;

		Module(ModuleDescriptor module, SettingDescriptor<?> setting) {
			this.module = module;
			this.setting = setting;
		}

		private ModuleState state() {
			return ControlState.layout().module(module);
		}

		@Override
		Object get() {
			return state().get(setting);
		}

		@Override
		@SuppressWarnings("unchecked")
		boolean set(Object value) {
			return changed(state().set((SettingDescriptor<Object>) setting, value));
		}

		@Override
		boolean isDefault() {
			return state().isDefault(setting);
		}

		@Override
		boolean reset() {
			return changed(state().resetSetting(setting));
		}

		@Override
		@Nullable String inactive() {
			return ModuleSettings.inactiveReason(state(), setting);
		}

		@Override
		SettingDescriptor<?> setting() {
			return setting;
		}

		@Override
		ModuleDescriptor module() {
			return module;
		}

		private boolean changed(boolean changed) {
			if (changed) {
				ControlState.settings().recordChange(module.id());
			}
			return changed;
		}
	}

	/**
	 * A field of the HUD look: a module's own (it follows the profile until set) or the profile's (it follows the style).
	 * Shows what is drawn now; the reset arrow puts the field back to following.
	 */
	private static final class Look extends Access {
		private final @Nullable ModuleDescriptor module;
		private final SettingDescriptor<?> field;

		Look(@Nullable ModuleDescriptor module, SettingDescriptor<?> field) {
			this.module = module;
			this.field = field;
		}

		private Appearance own() {
			return module != null ? ControlState.layout().module(module).appearance() : ControlState.layout().appearance();
		}

		/** The style drawn now: the module's, else the profile's, else Flint's. */
		private HudThemeId style() {
			HudThemeId own = module != null ? ControlState.layout().module(module).appearance().theme() : null;
			HudThemeId profile = ControlState.layout().appearance().theme();
			return own != null ? own : profile != null ? profile : HudThemeId.FLINT;
		}

		private Object effective(SettingDescriptor<?> f) {
			Object value = own().get(f);
			if (value == null && module != null) {
				value = ControlState.layout().appearance().get(f);
			}
			if (value == null) {
				value = f == Appearance.THEME ? style() : Appearance.styleDefault(f, style());
			}
			return value;
		}

		@Override
		Object get() {
			return effective(field);
		}

		@Override
		boolean set(Object value) {
			return write(value);
		}

		private boolean write(@Nullable Object value) {
			Appearance next = own().with(field, value);
			boolean changed = module != null ? ControlState.layout().module(module).setAppearance(next) : ControlState.layout().setAppearance(next);
			if (changed && module != null) {
				ControlState.settings().recordChange(module.id());
			}
			return changed;
		}

		@Override
		boolean isDefault() {
			return own().get(field) == null;
		}

		@Override
		boolean reset() {
			return write(null);
		}

		@Override
		@Nullable String inactive() {
			if ((field == Appearance.BACKGROUND_COLOR || field == Appearance.BACKGROUND_OPACITY) && !(Boolean) effective(Appearance.BACKGROUND)) {
				return "Turn the background on first";
			}
			if ((field == Appearance.BORDER_COLOR || field == Appearance.BORDER_THICKNESS) && !(Boolean) effective(Appearance.BORDER)) {
				return "Turn the border on first";
			}
			if (module == null) {
				return null;
			}
			if (field == Appearance.SHOW_LABEL) {
				return MenuHost.noLabelReason(module.id(), style());
			}
			if (field == Appearance.PADDING) {
				return MenuHost.noPaddingReason(module.id());
			}
			if (field == Appearance.ACCENT_COLOR) {
				return MenuHost.noAccentReason(module.id());
			}
			return null;
		}

		@Override
		SettingDescriptor<?> setting() {
			return field;
		}

		@Override
		@Nullable ModuleDescriptor module() {
			return module;
		}
	}

	/** A whole module on or off (a row that switches a Flint module, or a mod card). */
	static final class Enabled extends Access {
		private final ModuleDescriptor module;

		Enabled(ModuleDescriptor module) {
			this.module = module;
		}

		@Override
		Object get() {
			return ControlState.layout().module(module).enabled();
		}

		@Override
		boolean set(Object value) {
			boolean changed = module.status().usable() && ControlState.layout().module(module).setEnabled((Boolean) value);
			if (changed) {
				ControlState.settings().recordChange(module.id());
			}
			return changed;
		}

		@Override
		boolean isDefault() {
			return true;
		}

		@Override
		boolean reset() {
			return false;
		}

		@Override
		@Nullable String inactive() {
			return module.status().usable() ? null : module.statusNote();
		}

		@Override
		ModuleDescriptor module() {
			return module;
		}
	}

	/** One of Flint's client settings ({@link ControlSettings}); they have typed accessors, matched here by descriptor. */
	private static final class Global extends Access {
		private final SettingDescriptor<?> setting;

		Global(SettingDescriptor<?> setting) {
			this.setting = setting;
		}

		@Override
		Object get() {
			ControlSettings s = ControlState.settings();
			if (setting == ControlSettings.SOUNDS) {
				return s.soundsEnabled();
			} else if (setting == ControlSettings.VOLUME) {
				return s.soundVolumePercent();
			} else if (setting == ControlSettings.ANIMATIONS) {
				return s.animations();
			} else if (setting == ControlSettings.FOLLOW_REDUCED_MOTION) {
				return s.followMinecraftReducedMotion();
			} else if (setting == ControlSettings.TRANSITION_BLUR) {
				return s.transitionBlur();
			} else if (setting == ControlSettings.GAMEPLAY_BLUR) {
				return s.gameplayBlur();
			} else if (setting == ControlSettings.GAMEPLAY_BLUR_STRENGTH) {
				return s.gameplayBlurStrength();
			} else if (setting == ControlSettings.INTERFACE_SIZE) {
				return s.interfaceSize();
			} else if (setting == ControlSettings.HIGH_CONTRAST) {
				return s.highContrast();
			} else if (setting == ControlSettings.SHOW_WITH_DEBUG_SCREEN) {
				return s.showHudWithDebugScreen();
			} else if (setting == GameOptions.HUD_EDITOR_KEY) {
				return MenuHost.hudEditorKey();
			} else if (setting == GameOptions.MENU_KEY) {
				return MenuHost.menuKey();
			} else if (GameOptions.ALL.contains(setting)) {
				return s.game(setting);
			}
			throw new IllegalStateException("not a client setting: " + setting.id());
		}

		@Override
		boolean set(Object value) {
			if (value.equals(get())) {
				return false;
			}
			ControlSettings s = ControlState.settings();
			if (setting == ControlSettings.SOUNDS) {
				s.setSoundsEnabled((Boolean) value);
			} else if (setting == ControlSettings.VOLUME) {
				s.setSoundVolumePercent((Integer) value);
			} else if (setting == ControlSettings.ANIMATIONS) {
				s.setAnimations((Boolean) value);
			} else if (setting == ControlSettings.FOLLOW_REDUCED_MOTION) {
				s.setFollowMinecraftReducedMotion((Boolean) value);
			} else if (setting == ControlSettings.TRANSITION_BLUR) {
				s.setTransitionBlur((Boolean) value);
			} else if (setting == ControlSettings.GAMEPLAY_BLUR) {
				s.setGameplayBlur((Boolean) value);
				ControlState.applyGameplayBlur();
			} else if (setting == ControlSettings.GAMEPLAY_BLUR_STRENGTH) {
				s.setGameplayBlurStrength((Integer) value);
				ControlState.applyGameplayBlur();
			} else if (setting == ControlSettings.INTERFACE_SIZE) {
				s.setInterfaceSize((InterfaceSize) value);
			} else if (setting == ControlSettings.HIGH_CONTRAST) {
				s.setHighContrast((Boolean) value);
			} else if (setting == ControlSettings.SHOW_WITH_DEBUG_SCREEN) {
				s.setShowHudWithDebugScreen((Boolean) value);
			} else if (setting == GameOptions.HUD_EDITOR_KEY) {
				MenuHost.setHudEditorKey((String) value);
			} else if (setting == GameOptions.MENU_KEY) {
				MenuHost.setMenuKey((String) value);
			} else if (GameOptions.ALL.contains(setting)) {
				setGame(s, setting, value);
			}
			return true;
		}

		private static <T> void setGame(ControlSettings s, SettingDescriptor<T> option, Object value) {
			s.setGame(option, option.coerce(value));
		}

		@Override
		@Nullable String inactive() {
			if (setting == GameOptions.MINIMAL_BOBBING) {
				return (Boolean) MinecraftOptions.get(VideoOptions.VIEW_BOBBING) ? null : "Only with View bobbing on";
			}
			if (setting == ControlSettings.GAMEPLAY_BLUR) {
				// The Motion Blur mod is the switch for this effect; this one stays in sight only while an old setting has it on.
				return ControlState.settings().gameplayBlur() ? null : "The Motion Blur mod does this";
			}
			return GameOptions.inactiveReason(setting, b -> ControlState.settings().gameBool(b));
		}

		@Override
		boolean isDefault() {
			return setting.defaultValue().equals(get());
		}

		@Override
		boolean reset() {
			return set(setting.defaultValue());
		}

		@Override
		SettingDescriptor<?> setting() {
			return setting;
		}
	}

	/**
	 * One of Minecraft's own video options ({@link VideoOptions}): read from and written to Minecraft's options each time, so
	 * this row and Minecraft's Video Settings always agree. Hidden on a version without it.
	 */
	private static final class Vanilla extends Access {
		private final SettingDescriptor<?> setting;

		Vanilla(SettingDescriptor<?> setting) {
			this.setting = setting;
		}

		@Override
		Object get() {
			return MinecraftOptions.get(setting);
		}

		@Override
		boolean set(Object value) {
			return MinecraftOptions.set(setting, value);
		}

		@Override
		boolean isDefault() {
			return setting.defaultValue().equals(get());
		}

		@Override
		boolean reset() {
			return set(setting.defaultValue());
		}

		@Override
		@Nullable String inactive() {
			return MinecraftOptions.unavailable(setting);
		}

		@Override
		SettingDescriptor<?> setting() {
			return setting;
		}
	}

	/**
	 * One id of a list the player adds to (UHC Overlay's items), as a row of its own: its value, and a button that takes it
	 * off the list.
	 */
	private static final class Entry extends Access {
		private final Access list;
		private final IdValuesSetting values;
		private final String id;

		Entry(Access list, IdValuesSetting values, String id) {
			this.list = list;
			this.values = values;
			this.id = id;
		}

		@SuppressWarnings("unchecked")
		private java.util.Map<String, Integer> now() {
			return (java.util.Map<String, Integer>) list.get();
		}

		@Override
		Object get() {
			return values.of(now(), id);
		}

		@Override
		boolean set(Object value) {
			return list.set(values.with(now(), id, (Integer) value));
		}

		/** Never at a default: its button (remove) is always there. */
		@Override
		boolean isDefault() {
			return false;
		}

		@Override
		boolean reset() {
			return list.set(values.with(now(), id, values.entryDefault()));
		}

		@Override
		@Nullable String inactive() {
			return list.inactive();
		}

		@Override
		@Nullable ModuleDescriptor module() {
			return list.module();
		}

		@Override
		@Nullable String about() {
			return null;
		}

		@Override
		boolean removes() {
			return true;
		}
	}

	/**
	 * The ids a player has added to a list (UHC Overlay's items), each a row of its own in the list's section, above the row
	 * that adds them: its picture and name, a slider for its value and a button that removes it. The rows follow the list as
	 * ids come and go (a row of an id still there is kept, so a slider being dragged stays put).
	 */
	static final class AddedRows {
		private final Access list;
		private final IdValuesSetting values;
		private final java.util.Map<String, Line> made = new java.util.HashMap<>();
		private java.util.Set<String> shown = java.util.Set.of();

		private AddedRows(Access list, IdValuesSetting values) {
			this.list = list;
			this.values = values;
		}

		/** The added ids' rows for {@code row}, or null when it isn't a list the player adds to. */
		static @Nullable AddedRows of(Catalog.Row row) {
			if (!row.inFlint() || row.action() != null || !(row.setting() instanceof IdValuesSetting values) || !values.addable()) {
				return null;
			}
			return new AddedRows(Access.of(row), values);
		}

		@SuppressWarnings("unchecked")
		private java.util.Map<String, Integer> now() {
			return (java.util.Map<String, Integer>) list.get();
		}

		/** Whether ids were added or removed since the rows were last asked for. */
		boolean changed() {
			return !now().keySet().equals(shown);
		}

		/** A row per added id, by name. */
		List<Line> lines() {
			java.util.Map<String, Integer> now = now();
			shown = java.util.Set.copyOf(now.keySet());
			made.keySet().retainAll(shown);
			List<String> ids = new ArrayList<>(shown);
			ids.sort(java.util.Comparator.comparing((String id) -> IdCatalog.name(values.kind(), id), String.CASE_INSENSITIVE_ORDER));
			List<Line> out = new ArrayList<>(ids.size());
			for (String id : ids) {
				out.add(made.computeIfAbsent(id, this::line));
			}
			return out;
		}

		private Line line(String id) {
			Entry entry = new Entry(list, values, id);
			// The value the list leaves out (1.00×) isn't on the slider: the row's button removes the item instead.
			int low = values.entryDefault() == values.min() ? values.min() + values.step() : values.min();
			Slider slider = new Slider(low, values.max(), values.step(), "", () -> (Integer) entry.get(), v -> entry.set(v)).display(values::format,
				text -> {
					Double typed = MenuMath.parseNumber(text.replace("×", "").replace("x", "").trim());
					return typed == null ? null : (int) Math.round(values.shownAsSize() ? typed * 100 : typed);
				});
			Line line = new Line(IdCatalog.name(values.kind(), id), entry, slider);
			if (values.kind() == IdKind.ITEM) {
				line.picture(id);
			}
			return line;
		}
	}

	// ---------- controls ----------

	private static Node control(Host host, Access a, Catalog.Row row) {
		SettingDescriptor<?> setting = a.setting();
		if (setting == null) {
			return new Switch(() -> (Boolean) a.get(), () -> a.set(!(Boolean) a.get()));
		}
		String name = row.label();
		return switch (setting) {
			case BoolSetting bool -> new Switch(() -> (Boolean) a.get(), () -> a.set(!(Boolean) a.get()));
			case IntSetting number -> whole(a, number, a instanceof Vanilla);
			case FloatSetting number -> decimal(a, number);
			case RgbSetting rgb -> {
				ColorSwatch[] swatch = new ColorSwatch[1];
				ColorPicker.Clear clear = clearFor(a, rgb);
				swatch[0] = new ColorSwatch(() -> (Integer) a.get(), clear == null ? null : clear.on(), () -> host.openColorPicker(swatch[0], name,
					rgb.defaultValue(), () -> (Integer) a.get(), v -> a.set(v), clear));
				yield swatch[0];
			}
			case ChoiceSetting<?> choice -> choice(host, a, choice, name);
			case OptionSetting option -> {
				String[] labels = option.labels().toArray(new String[0]);
				if (a instanceof Vanilla) {
					// Only the choices this Minecraft has (1.8.9's graphics: Fast and Fancy).
					labels = java.util.Arrays.copyOf(labels, MinecraftOptions.choices(option));
				}
				yield fits(labels) ? new Segmented(labels, () -> (Integer) a.get(), i -> a.set(i)) : longChoice(host, a, labels, name);
			}
			case KeySetting key -> new KeyBind(() -> (String) a.get(), v -> a.set(v));
			case TextSetting text -> textField(a, text);
			case IdSetting id -> {
				PickField[] field = new PickField[1];
				boolean sound = id.kind() == IdKind.SOUND;
				field[0] = new PickField(() -> IdCatalog.name(id.kind(), (String) a.get()), () -> host.openChooser(field[0], name, IdCatalog.of(id.kind()),
					true, () -> (String) a.get(), v -> a.set(v), sound ? v -> IdCatalog.preview(v, 1F) : null));
				yield field[0];
			}
			case IdValuesSetting values -> valuesField(host, a, values, name);
			case IdListSetting list -> listField(host, a, list, name);
			default -> throw new IllegalArgumentException("no control for " + setting);
		};
	}

	/**
	 * How colour {@code rgb} becomes see-through, when it can: through the opacity beside it that reaches 0 (a module's
	 * {@code fooColor} and {@code fooOpacity}, its {@code backgroundColor} and a background {@code opacity}, or the HUD look's
	 * background colour and opacity). Transparent sets that opacity to 0; picking a colour again gives it back its default
	 * (full when that is 0). A {@linkplain RgbSetting#clearable() clearable} colour (a background with no opacity of its own)
	 * is set to {@link RgbSetting#CLEAR} instead, and picking a colour simply replaces that. Null for a colour that is always
	 * drawn solid (text, borders, lines).
	 */
	private static ColorPicker.@Nullable Clear clearFor(Access a, RgbSetting rgb) {
		if (rgb.clearable()) {
			return new ColorPicker.Clear(() -> RgbSetting.isClear((Integer) a.get()), () -> a.set(RgbSetting.CLEAR), () -> {
			});
		}
		Access opacity = null;
		IntSetting setting = null;
		if (a instanceof Look && rgb == Appearance.BACKGROUND_COLOR) {
			setting = Appearance.BACKGROUND_OPACITY;
			opacity = new Look(a.module(), setting);
		} else if (a instanceof Module && a.module() != null) {
			setting = opacityFor(a.module(), rgb);
			opacity = setting == null ? null : new Module(a.module(), setting);
		}
		if (opacity == null || setting.min() > 0) {
			return null;
		}
		Access o = opacity;
		int back = setting.defaultValue() > 0 ? setting.defaultValue() : setting.max();
		return new ColorPicker.Clear(() -> (Integer) o.get() == 0, () -> o.set(0), () -> o.set(back));
	}

	/** The opacity setting that goes with colour {@code rgb} in {@code module}, or null. */
	static @Nullable IntSetting opacityFor(ModuleDescriptor module, RgbSetting rgb) {
		String id = rgb.id();
		String paired = id.endsWith("Color") ? id.substring(0, id.length() - "Color".length()) + "Opacity" : null;
		for (SettingDescriptor<?> s : module.settings()) {
			if (s instanceof IntSetting i && i.id().equals(paired)) {
				return i;
			}
		}
		if (id.equals("backgroundColor")) {
			for (SettingDescriptor<?> s : module.settings()) {
				if (s instanceof IntSetting i && i.id().equals("opacity") && i.label().toLowerCase(Locale.ROOT).contains("background")) {
					return i;
				}
			}
		}
		return null;
	}

	/** Up to four short options fit side by side; more take a field that opens the searchable list. */
	private static boolean fits(String[] labels) {
		int total = 0;
		for (String label : labels) {
			total += label.length() + 4;
		}
		return labels.length <= 4 && total <= 44;
	}

	private static <E extends Enum<E> & Labelled> Node choice(Host host, Access a, ChoiceSetting<E> choice, String name) {
		List<E> options = choice.options();
		String[] labels = new String[options.size()];
		for (int i = 0; i < labels.length; i++) {
			labels[i] = options.get(i).label();
		}
		if (fits(labels)) {
			return new Segmented(labels, () -> options.indexOf(a.get()), i -> a.set(options.get(i)));
		}
		Access byIndex = new Access() {
			@Override
			Object get() {
				return options.indexOf(a.get());
			}

			@Override
			boolean set(Object value) {
				return a.set(options.get((Integer) value));
			}

			@Override
			boolean isDefault() {
				return a.isDefault();
			}

			@Override
			boolean reset() {
				return a.reset();
			}
		};
		return longChoice(host, byIndex, labels, name);
	}

	/** A choice with too many options for a segmented control: its option in a field; pressing it opens the list. */
	private static Node longChoice(Host host, Access byIndex, String[] labels, String name) {
		List<ListPicker.Item> items = new ArrayList<>(labels.length);
		for (int i = 0; i < labels.length; i++) {
			items.add(new ListPicker.Item(Integer.toString(i), labels[i], ""));
		}
		PickField[] field = new PickField[1];
		field[0] = new PickField(() -> labels[Math.clamp((Integer) byIndex.get(), 0, labels.length - 1)], () -> host.openChooser(field[0], name, items,
			false, () -> Integer.toString((Integer) byIndex.get()), v -> byIndex.set(Integer.parseInt(v)), null));
		return field[0];
	}

	/**
	 * A whole-number setting: its named values shown by name ("Auto"); one of Minecraft's options stops where Minecraft does
	 * now (render distance on this PC, the GUI scale this window fits), and one that makes Minecraft rebuild the world or its
	 * GUI applies when the slider is let go.
	 */
	private static Slider whole(Access a, IntSetting number, boolean minecraft) {
		int max = minecraft ? MinecraftOptions.max(number) : number.max();
		Slider slider = new Slider(number.min(), max, number.step(), number.unit(), () -> (Integer) a.get(), v -> a.set(v));
		if (number.hasNames()) {
			slider.display(number::format, MenuMath::parseWhole);
		}
		return minecraft && VideoOptions.appliesOnRelease(number) ? slider.onRelease() : slider;
	}

	/** A decimal setting in whole steps of its step size, shown with the step's decimals. */
	private static Slider decimal(Access a, FloatSetting number) {
		int steps = Math.round((number.max() - number.min()) / number.step());
		int decimals = MenuMath.decimals(number.step());
		return new Slider(0, steps, 1, "", () -> Math.round(((Float) a.get() - number.min()) / number.step()),
			v -> a.set(number.min() + v * number.step())).display(
				v -> String.format(Locale.ROOT, "%." + decimals + "f", number.min() + v * number.step()) + number.unit(),
				text -> {
					Double typed = MenuMath.parseNumber(text);
					return typed == null ? null : (int) Math.round((typed - number.min()) / number.step());
				});
	}

	private static Node textField(Access a, TextSetting text) {
		TextField field = new TextField(text.label(), text.maxLength()) {
			private @Nullable FontFace face;

			@Override
			public float prefWidth() {
				return r(330);
			}

			@Override
			protected void layout() {
				FontFace now = Type.VALUE.face();
				if (now != face) {
					face = now;
					look(new TextField.Look(now, MenuStyle.FIELD, MenuStyle.FIELD_EDGE, MenuStyle.SEARCH_EDGE_HOVER, MenuStyle.SEARCH_FOCUS, r(12), r(18),
						0, 0, MenuStyle.ROW_TEXT, MenuStyle.LABEL, MenuStyle.LABEL));
				}
				super.layout();
			}

			@Override
			protected void paint(Canvas c) {
				// Follows the model (a reset, another profile) while it isn't being edited.
				if (!focused() && !text().equals(a.get())) {
					setText((String) a.get());
				}
				super.paint(c);
			}
		};
		field.setText((String) a.get());
		field.onChange(a::set);
		return field;
	}

	/** A value for each of many ids: how many are changed; pressing opens the list with − and + per row. */
	private static Node valuesField(Host host, Access a, IdValuesSetting values, String name) {
		boolean sound = values.kind() == IdKind.SOUND;
		ListPicker.Values model = new ListPicker.Values() {
			@SuppressWarnings("unchecked")
			private java.util.Map<String, Integer> now() {
				return (java.util.Map<String, Integer>) a.get();
			}

			@Override
			public int get(String id) {
				return values.of(now(), id);
			}

			@Override
			public void set(String id, int value) {
				a.set(values.with(now(), id, value));
			}

			@Override
			public int min() {
				return values.min();
			}

			@Override
			public int max() {
				return values.max();
			}

			@Override
			public int step() {
				return values.step();
			}

			@Override
			public int defaultValue() {
				return values.entryDefault();
			}

			@Override
			public String unit() {
				return values.unit();
			}

			@Override
			public String format(int value) {
				return values.format(value);
			}

			@Override
			public int addValue() {
				return values.addValue();
			}
		};
		PickField[] field = new PickField[1];
		// A list the player adds to (UHC Overlay's items) reads as a search: its icon, and how many are in it.
		field[0] = new PickField(() -> {
			int n = ((java.util.Map<?, ?>) a.get()).size();
			if (values.addable()) {
				// The items added are rows of their own above this one.
				return "Search items to add";
			}
			return n == 0 ? "None changed" : n == 1 ? "1 changed" : n + " changed";
		}, () -> host.openValues(field[0], name, IdCatalog.of(values.kind()), model, sound ? v -> IdCatalog.preview(v, model.get(v) / 100F) : null),
			values.addable() ? Glyph.SEARCH : null);
		return field[0];
	}

	/**
	 * A set of ids: how many are in it; pressing opens the list with a tick per row. Slots and slot bindings: pressing
	 * twice unlocks (unbinds) all.
	 */
	private static Node listField(Host host, Access a, IdListSetting list, String name) {
		if (list.kind() == IdKind.SLOT || list.kind() == IdKind.SLOT_BINDING) {
			boolean lock = list.kind() == IdKind.SLOT;
			String done = lock ? " locked" : " bound";
			String undo = lock ? "unlock" : "unbind";
			long[] askedAt = {0};
			return new PickField(() -> {
				int n = ((List<?>) a.get()).size();
				if (n > 0 && System.currentTimeMillis() - askedAt[0] < 3000) {
					return "Press again to " + undo + " all";
				}
				return n == 0 ? "None" + done : n == 1 ? "1" + done + " · press to " + undo : n + done + " · press to " + undo + " all";
			}, () -> {
				if (((List<?>) a.get()).isEmpty()) {
					return;
				}
				if (System.currentTimeMillis() - askedAt[0] < 3000) {
					askedAt[0] = 0;
					a.set(List.of());
				} else {
					askedAt[0] = System.currentTimeMillis();
				}
			});
		}
		ListPicker.Values model = new ListPicker.Values() {
			@SuppressWarnings("unchecked")
			private List<String> now() {
				return (List<String>) a.get();
			}

			@Override
			public int get(String id) {
				return now().contains(id) ? 1 : 0;
			}

			@Override
			public void set(String id, int value) {
				a.set(list.with(now(), id, value == 1));
			}

			@Override
			public int min() {
				return 0;
			}

			@Override
			public int max() {
				return 1;
			}

			@Override
			public int step() {
				return 1;
			}

			@Override
			public int defaultValue() {
				return 0;
			}

			@Override
			public String unit() {
				return "";
			}
		};
		PickField[] field = new PickField[1];
		field[0] = new PickField(() -> {
			int n = ((List<?>) a.get()).size();
			return n == 0 ? "None" : n == 1 ? "1 item" : n + " items";
		}, () -> host.openValues(field[0], name, IdCatalog.of(list.kind()), model, null));
		return field[0];
	}

	// ---------- lines ----------

	/**
	 * A row Flint has, as Dawn's: its name (16, grey) at the left, the control at the right, a reset arrow before it while
	 * the setting isn't at its default; a note under the name when it is off for a reason or shares its key.
	 */
	static final class Line extends Node {
		private static final float RESET = 40;
		/** An added item's picture before its name. */
		private static final float PICTURE = 28;
		private static final float RESET_GAP = 14;
		private final Label label;
		private final Label note = new Label(Type.ROW_NOTE, "");
		private final @Nullable String description;
		private final Access access;
		private final Node control;
		private final Reset reset;
		private @Nullable String shownReason;
		private @Nullable String conflict;
		private Object conflictFor = "\0";
		private float dim = -1;
		/** An item drawn before the name (an added item's row), or null. */
		private @Nullable String picture;
		private boolean resettable = true;

		Line(String text, Access access, Node control) {
			this.label = new Label(Type.ROW_LABEL, text);
			this.description = access.about();
			this.access = access;
			this.control = add(control);
			control.name(text);
			this.reset = add(new Reset(access, text));
			reset.setVisible(false);
			if (description != null) {
				// What it does reads in a tooltip over the row, so the page stays a clean list.
				tooltip(text + "\n" + description);
			}
		}

		/** Never shows the reset arrow. */
		void withoutReset() {
			resettable = false;
		}

		/** Draws item {@code id} before the name, as the game draws it in an inventory. */
		Line picture(String id) {
			picture = id;
			return this;
		}

		/** Whether the setting does something with the others as they are (one that doesn't waits out of sight). */
		boolean applies() {
			return access.inactive() == null;
		}

		/** Hovered (for its tooltip) but never pressed: clicks go to the control. */
		@Override
		public boolean interactive() {
			return description != null;
		}

		@Override
		public boolean focusable() {
			return false;
		}

		@Override
		public float prefHeight(float width) {
			return note.text().isEmpty() && conflict == null ? r(H_PLAIN) : r(H);
		}

		@Override
		protected void layout() {
			float cw = control.prefWidth();
			float ch = control instanceof Switch ? r(Switch.H) : control instanceof Slider ? r(Slider.H)
				: control instanceof ColorSwatch ? r(ColorSwatch.H) : control instanceof PageControls.Segmented ? r(PageControls.Segmented.H)
				: control instanceof TextField ? r(46.8F) : r(43.2F);
			control.setBounds(x + w - cw, y + (h - ch) / 2, cw, ch);
			reset.setBounds(control.x() - r(RESET_GAP) - r(RESET), y + (h - r(RESET)) / 2, r(RESET), r(RESET));
		}

		/** The note for a key setting: other Flint settings and Minecraft controls on the same key; worked out when the key changes. */
		private void checkConflict() {
			if (!(access.setting() instanceof KeySetting key) || access.module() == null) {
				return;
			}
			Object bound = access.get();
			if (bound.equals(conflictFor)) {
				return;
			}
			conflictFor = bound;
			List<String> uses = KeyConflicts.flintUses(ControlState.layout(), (String) bound, access.module().id(), key.id());
			// Minecraft's controls don't act while chat is open.
			if (!key.chatOnly()) {
				for (String mapping : MenuHost.mappingsUsing((String) bound, key.screensOnly())) {
					uses.add("Minecraft: " + mapping);
				}
			}
			conflict = KeyConflicts.warning(uses);
			invalidate();
		}

		@Override
		protected void paint(Canvas c) {
			// Moot settings are disabled with their reason as the note; worked out only when the reason changes.
			String reason = access.inactive();
			if (!java.util.Objects.equals(reason, shownReason)) {
				shownReason = reason;
				if (reason == null) {
					control.setEnabled(true);
				} else {
					control.setDisabled(reason);
				}
				invalidate();
			}
			checkConflict();
			boolean showReset = resettable && reason == null && !access.isDefault();
			if (showReset != reset.visible()) {
				reset.setVisible(showReset);
			}
			String text = reason != null ? reason : conflict != null ? conflict : "";
			note.set(text);
			dim = MenuStyle.ease(dim, reason != null);
			float labelLeft = x;
			float cy = y + h / 2;
			if (picture != null) {
				// A whole multiple of its 16 pixels, so it stays crisp; its place is kept while the game can't draw items yet
				// (the title screen on 26.x).
				int size = Math.max(16, Math.round(c.px(r(PICTURE)) / 16F) * 16);
				Object stack = Ids.stack(picture);
				if (stack != null) {
					ItemPainter.item(c.frame(), stack, c.px(x), c.px(cy) - size / 2, size, null, false);
				}
				labelLeft += Math.max(r(PICTURE), size / (float) c.scale()) + r(12);
			}
			float room = reset.x() - MenuStyle.d(16) - labelLeft;
			float opacity = c.opacity();
			c.setOpacity(opacity * (1 - 0.5F * dim));
			if (text.isEmpty()) {
				MenuStyle.fitted(c, label, labelLeft, cy, MenuStyle.NEUTRAL, room);
			} else {
				// The name and the note a line apart at least, whatever size Minecraft's font is drawn at (9 pixels a line).
				float line = 9F * MenuStyle.pixelScale() / c.scale();
				float above = Math.max(r(14), line * 0.6F);
				float below = Math.max(r(18), line * 0.6F);
				MenuStyle.fitted(c, label, labelLeft, cy - above, MenuStyle.NEUTRAL, room);
				// Why a row is off ("Turn the border on first") is a quiet hint; a shared key is worth a warning.
				int ink = conflict != null && reason == null ? MenuStyle.WARN_TEXT : Palette.withAlpha(MenuStyle.NEUTRAL, 0.75F);
				MenuStyle.fitted(c, note, labelLeft, cy + below, ink, room);
			}
			c.setOpacity(opacity);
		}



		@Override
		protected void paintChildren(Canvas c) {
			float opacity = c.opacity();
			c.setOpacity(opacity * (1 - 0.55F * Math.max(0, dim)));
			try {
				super.paintChildren(c);
			} finally {
				c.setOpacity(opacity);
			}
		}
	}

	/** Back to this setting's default. */
	private static final class Reset extends Pressable {
		private final boolean removes;

		Reset(Access access, String label) {
			super(access::reset);
			removes = access.removes();
			name(removes ? "Remove " + label : "Reset " + label + " to its default");
			tooltip(removes ? "Remove" : "Back to default");
		}

		@Override
		protected void paint(Canvas c) {
			MenuStyle.box(c, x, y, w, h, w / 2, Palette.withAlpha(0x14FFFFFF, hover));
			(removes ? Glyph.TRASH : Glyph.RESET).draw(c, x + w / 2, y + h / 2, r(removes ? 18 : 22), Palette.lerp(MenuStyle.NEUTRAL, MenuStyle.TEXT,
				hover));
			MenuStyle.focusRing(c, x, y, w, h, w / 2, focus);
		}
	}

	/**
	 * A row Flint doesn't have: its label dimmed and a quiet tag where the control would be, NOT IN FLINT YET (work Flint can
	 * do) or UNAVAILABLE (it needs an outside service), with why unless its whole mod or page already says. Not focusable.
	 */
	static final class Missing extends Node {
		private final Label label;
		private final Label tag;
		private final @Nullable Label why;

		Missing(String text, @Nullable String unavailable) {
			this.label = new Label(Type.ROW_LABEL, text);
			this.tag = new Label(Type.BADGE, unavailable == null ? "NOT IN FLINT YET" : "UNAVAILABLE");
			this.why = unavailable == null || unavailable.isEmpty() ? null : new Label(Type.ROW_NOTE, unavailable);
			name(text + (unavailable == null ? ", not in Flint yet" : why == null ? ", unavailable" : ", unavailable: " + unavailable));
		}

		@Override
		public float prefHeight(float width) {
			return r(H_PLAIN);
		}

		@Override
		protected void paint(Canvas c) {
			float cy = y + h / 2;
			float tagW = tag.width(c) + r(32);
			float tagX = x + w - tagW;
			MenuStyle.box(c, tagX, cy - r(22), tagW, r(44), r(22), MenuStyle.DISABLED);
			MenuStyle.centered(c, tag, tagX + tagW / 2, cy, MenuStyle.DISABLED_TEXT, tagW);
			float room = tagX - r(24) - x;
			if (why == null) {
				MenuStyle.fitted(c, label, x, cy, Palette.withAlpha(MenuStyle.NEUTRAL, 0.6F), room);
			} else {
				MenuStyle.fitted(c, label, x, cy - r(12), Palette.withAlpha(MenuStyle.NEUTRAL, 0.6F), room);
				MenuStyle.fitted(c, why, x, cy + r(16), MenuStyle.CHIP_COUNT, room);
			}
		}
	}
}
