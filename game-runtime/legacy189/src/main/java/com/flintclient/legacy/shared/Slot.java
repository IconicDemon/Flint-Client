package com.flintclient.legacy.shared;

import com.flintclient.client.control.model.Appearance;
import com.flintclient.client.control.model.HudThemeId;
import com.flintclient.client.control.model.Layout;
import com.flintclient.client.control.model.ModuleSettings;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.SettingDescriptor;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Where a 1.8.9 setting's value lives in Flint's shared settings: a module's setting, a field of a module's HUD look, or one of
 * Flint's client settings. The menu's rows and the mods read and write through it, so 1.8.9 keeps no values of its own.
 */
public interface Slot {
	Object get();

	void set(Object value);

	Object defaultValue();

	/** Grows with every change of the value (and others beside it); the views compare it to know when to remake text. */
	int revision();

	/** Why the setting does nothing now (another one is off), or null: such a row waits out of sight, as on every line. */
	default @Nullable String reason() {
		return null;
	}

	/** {@code setting} of the module whose state {@code state} gives (the state of the layout in use). */
	static Slot module(Supplier<ModuleState> state, SettingDescriptor<?> setting) {
		return new Slot() {
			@Override
			public Object get() {
				return state.get().get(setting);
			}

			@Override
			@SuppressWarnings({"unchecked", "rawtypes"})
			public void set(Object value) {
				ModuleState s = state.get();
				if (s.set((SettingDescriptor) setting, value)) {
					Shared.settings().recordChange(s.id());
				}
			}

			@Override
			public Object defaultValue() {
				return setting.defaultValue();
			}

			@Override
			public int revision() {
				return state.get().revision();
			}

			@Override
			public @Nullable String reason() {
				return ModuleSettings.inactiveReason(state.get(), setting);
			}
		};
	}

	/**
	 * A field of the module's HUD look ({@link Appearance}): the module's own choice, else the profile's, else the style's. Set
	 * here, it becomes the module's own.
	 */
	static Slot look(Supplier<ModuleState> state, SettingDescriptor<?> field) {
		return new Slot() {
			@Override
			public Object get() {
				Object own = state.get().appearance().get(field);
				return own != null ? own : defaultValue();
			}

			@Override
			public void set(Object value) {
				ModuleState s = state.get();
				if (s.setAppearance(s.appearance().with(field, value))) {
					Shared.settings().recordChange(s.id());
				}
			}

			@Override
			public Object defaultValue() {
				Layout layout = Shared.layout();
				Object shared = layout.appearance().get(field);
				return shared != null ? shared : Appearance.styleDefault(field, theme(state.get(), layout));
			}

			@Override
			public int revision() {
				return state.get().revision() * 31 + (int) Shared.layout().revision();
			}
		};
	}

	/** The HUD style a module draws in: its own, else the profile's, else Flint's. */
	static HudThemeId theme(ModuleState state, Layout layout) {
		HudThemeId own = state.appearance().theme();
		HudThemeId shared = layout.appearance().theme();
		return own != null ? own : shared != null ? shared : HudThemeId.FLINT;
	}

	/** A value kept only in memory, for a control that edits something else (a waypoint's colour in its editor). */
	static Slot memory(Object initial) {
		return new Slot() {
			private Object value = initial;
			private int revision;

			@Override
			public Object get() {
				return value;
			}

			@Override
			public void set(Object next) {
				if (!next.equals(value)) {
					value = next;
					revision++;
				}
			}

			@Override
			public Object defaultValue() {
				return initial;
			}

			@Override
			public int revision() {
				return revision;
			}
		};
	}

	/** One of Flint's client settings ({@code GameOptions}). */
	static Slot game(SettingDescriptor<?> option) {
		return new Slot() {
			private int revision;

			@Override
			public Object get() {
				return Shared.settings().game(option);
			}

			@Override
			@SuppressWarnings({"unchecked", "rawtypes"})
			public void set(Object value) {
				if (Shared.settings().setGame((SettingDescriptor) option, value)) {
					revision++;
				}
			}

			@Override
			public Object defaultValue() {
				return option.defaultValue();
			}

			@Override
			public int revision() {
				return revision;
			}
		};
	}
}
