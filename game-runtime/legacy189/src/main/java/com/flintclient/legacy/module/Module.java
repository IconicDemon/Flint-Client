package com.flintclient.legacy.module;

import com.flintclient.client.control.model.Appearance;
import com.flintclient.client.control.model.BoolSetting;
import com.flintclient.client.control.model.ChoiceSetting;
import com.flintclient.client.control.model.FloatSetting;
import com.flintclient.client.control.model.IntSetting;
import com.flintclient.client.control.model.KeySetting;
import com.flintclient.client.control.model.ModuleDescriptor;
import com.flintclient.client.control.model.ModuleRegistry;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.OptionSetting;
import com.flintclient.client.control.model.RgbSetting;
import com.flintclient.client.control.model.SettingDescriptor;
import com.flintclient.client.control.rshift.Catalog;
import com.flintclient.legacy.shared.Shared;
import com.flintclient.legacy.shared.Slot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A Flint mod on 1.8.9: the 1.8.9 backend of one of Flint's shared modules ({@link ModuleRegistry}). Whether it is on, its
 * settings, its HUD placement and its toggle key live in the shared layout in use, the same as on every Minecraft line; its card
 * (name, description, categories) and its page's sections and rows come from the Right Shift catalog. The page shows the shared
 * settings this backend honours ({@link #bind}); the others wait out of sight until 1.8.9 has them.
 */
public abstract class Module {
	/** The shared module's id (its key in layout files). */
	public final String id;
	/** The shared module; null only for Flint's own settings page ({@code General}). */
	public final ModuleDescriptor descriptor;
	private final int index;
	/** The card picture's name ({@code textures/mods/<picture>.png}). */
	public final String picture;
	public final String name;
	public final String description;
	/** The categories its card is listed under (the catalog's: HUD, PvP...). */
	public final List<String> categories;
	private final List<Setting<?>> bound = new ArrayList<Setting<?>>();
	private final Map<SettingDescriptor<?>, Setting<?>> byShared = new IdentityHashMap<SettingDescriptor<?>, Setting<?>>();
	/** Shared settings this mod reads from its state without a view of its own (see {@link #reads}). */
	private final Set<SettingDescriptor<?>> read = Collections.newSetFromMap(new IdentityHashMap<SettingDescriptor<?>, Boolean>());
	private boolean lastEnabled;
	/** The key that turns it on or off in a world, and whether that says so on screen (the shared module's). */
	public final Setting.Key toggleKey;
	public final Setting.Bool toggleNotify;

	/** A titled group of settings. */
	protected Module(String id, String picture) {
		this.id = id;
		this.descriptor = ModuleRegistry.get(id);
		this.index = ModuleRegistry.indexOf(id);
		this.picture = picture;
		Catalog.Mod card = Catalog.get().modFor(id);
		this.name = card != null && card.module() == descriptor ? card.name() : descriptor.name();
		this.description = card != null && card.module() == descriptor ? card.description() : descriptor.description();
		List<String> cats = new ArrayList<String>();
		if (card != null) {
			for (Catalog.Category c : card.categories()) {
				cats.add(c.label());
			}
		}
		this.categories = Collections.unmodifiableList(cats);
		this.toggleKey = key(descriptor.modKey());
		this.toggleNotify = bool(descriptor.modKeyNotify());
	}

	/** Flint's own settings page (no shared module: its rows are Flint's client settings). */
	protected Module(String id, String picture, String name, String description) {
		this.id = id;
		this.descriptor = null;
		this.index = -1;
		this.picture = picture;
		this.name = name;
		this.description = description;
		this.categories = Collections.emptyList();
		this.toggleKey = null;
		this.toggleNotify = null;
	}

	/** Its state in the layout in use. */
	public final ModuleState state() {
		return Shared.state(index);
	}

	// ---------- binding shared settings ----------

	/** Shows {@code setting} on the page (on the catalog's row for it) and lets the mod read it. */
	protected final <S extends Setting<?>> S bind(S setting) {
		bound.add(setting);
		byShared.put(setting.shared, setting);
		return setting;
	}

	protected final Slot slot(SettingDescriptor<?> setting) {
		return Slot.module(this::state, setting);
	}

	/** A field of this module's HUD look. */
	protected final Slot look(SettingDescriptor<?> field) {
		return Slot.look(this::state, field);
	}

	protected final Setting.Bool bool(BoolSetting s) {
		return bind(new Setting.Bool(slot(s), s));
	}

	protected final Setting.Int integer(IntSetting s) {
		return bind(new Setting.Int(slot(s), s));
	}

	protected final Setting.Choice choice(ChoiceSetting<?> s) {
		return bind(new Setting.Choice(slot(s), s));
	}

	protected final Setting.Choice option(OptionSetting s) {
		return bind(new Setting.Choice(slot(s), s));
	}

	protected final Setting.Color color(RgbSetting s) {
		return bind(new Setting.Color(slot(s), s));
	}

	/** A colour and its opacity setting (a percentage when {@code percent}, else 0..255), as one swatch. */
	protected final Setting.Color color(RgbSetting s, IntSetting opacity, boolean percent) {
		return bind(new Setting.Color(slot(s), s, slot(opacity), percent));
	}

	protected final Setting.Key key(KeySetting s) {
		return bind(new Setting.Key(slot(s), s));
	}

	/** A decimal setting on a slider in {@code factor} parts of its unit. */
	protected final Setting.Decimal decimal(FloatSetting s, int factor, String unit) {
		return bind(new Setting.Decimal(slot(s), s, factor, unit));
	}

	/** The look's {@code field} (a switch). */
	protected final Setting.Bool lookBool(BoolSetting field) {
		return bind(new Setting.Bool(look(field), field));
	}

	/** The look's palette colour {@code field} (text, accent). */
	protected final Setting.Choice lookPalette(ChoiceSetting<?> field) {
		return bind(new Setting.Choice(look(field), field));
	}

	/** The look's panel colour with its opacity, as one swatch. */
	protected final Setting.Color lookPanel() {
		return bind(new Setting.Color(look(Appearance.BACKGROUND_COLOR), Appearance.BACKGROUND_COLOR, look(Appearance.BACKGROUND_OPACITY), true));
	}

	protected final Setting.Color lookColor(RgbSetting field) {
		return bind(new Setting.Color(look(field), field));
	}

	protected final Setting.Int lookInt(IntSetting field) {
		return bind(new Setting.Int(look(field), field));
	}

	/** The setting showing {@code shared} on 1.8.9, or null when 1.8.9 doesn't have it. */
	public Setting<?> bound(SettingDescriptor<?> shared) {
		return byShared.get(shared);
	}

	/**
	 * Declares that this mod reads {@code shared} straight from its state (text it shows, ids it counts) without a 1.8.9 view of
	 * it: the shared menu edits it like any other row.
	 */
	protected final void reads(SettingDescriptor<?>... shared) {
		Collections.addAll(read, shared);
	}

	/** Whether this mod honours {@code shared}: a setting it binds or reads, which the shared menu then shows. */
	public boolean honours(SettingDescriptor<?> shared) {
		return byShared.containsKey(shared) || read.contains(shared);
	}

	// ---------- on and off ----------

	public boolean enabled() {
		return descriptor == null || state().enabled();
	}

	public void setEnabled(boolean on) {
		if (descriptor != null && state().setEnabled(on)) {
			Shared.settings().recordChange(id);
		}
		sync();
	}

	/** Runs {@link #onEnable}/{@link #onDisable} when it was switched since last asked (by the menu, a key, a new profile). */
	public final void sync() {
		boolean on = enabled();
		if (on != lastEnabled) {
			lastEnabled = on;
			if (on) {
				onEnable();
			} else {
				onDisable();
			}
		}
	}

	protected void onEnable() {
	}

	protected void onDisable() {
	}

	/** Every client tick while it's on (and a world is loaded). */
	public void tick() {
	}

	public List<Setting<?>> settings() {
		return Collections.unmodifiableList(bound);
	}

	/** Every setting back to its default, its look too, as Reset Settings does on every line (whether it's on is left alone). */
	public void resetSettings() {
		if (descriptor != null) {
			state().reset();
		} else {
			for (Setting<?> s : bound) {
				s.reset();
			}
		}
	}
}
