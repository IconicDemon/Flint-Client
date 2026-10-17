package com.flintclient.client.control.model;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A layout profile: a name, a shared HUD style and the state of every module. One file per layout, and the
 * file is the portable unit (it holds nothing Minecraft-specific, such as key bindings), so it can be
 * exported and imported as is.
 *
 * <p>Every registered module always has a state here, in registry order, so renderers can walk
 * {@link #modules()} by index without allocating.
 */
public final class Layout {
	private final String id;
	private String name;
	private Appearance appearance = shared(Appearance.DEFAULTS);
	private final ModuleState[] modules;
	private final List<ModuleState> moduleList;
	private int revision;

	/** A layout with the default style and every module off at its default position. */
	public Layout(String id, String name) {
		this.id = Objects.requireNonNull(id);
		this.name = Objects.requireNonNull(name);
		List<ModuleDescriptor> all = ModuleRegistry.all();
		this.modules = new ModuleState[all.size()];
		for (int i = 0; i < modules.length; i++) {
			modules[i] = new ModuleState(all.get(i));
		}
		this.moduleList = List.of(modules);
	}

	/** The file name without {@code .json}; never changes (renaming changes only the name). */
	public String id() {
		return id;
	}

	public String name() {
		return name;
	}

	/** Changes the display name. The caller checks it first ({@link LayoutNames#nameError}). */
	public boolean setName(String value) {
		if (name.equals(Objects.requireNonNull(value))) {
			return false;
		}
		name = value;
		revision++;
		return true;
	}

	/**
	 * The shared style every module inherits. The theme, text colour and padding are always set; the background,
	 * its opacity and the accent colour are null until set, meaning "whatever the style does" (Classic's panel is
	 * lighter than Flint's, Minimal has none; each style has its own label colour); {@code showLabel} stays per
	 * module (null here, meaning on).
	 */
	public Appearance appearance() {
		return appearance;
	}

	/**
	 * Sets the shared style; a missing theme, colour or padding takes the default, and a missing background, accent or
	 * opacity follows the style. Returns whether this changed anything.
	 */
	public boolean setAppearance(Appearance value) {
		Appearance next = shared(value);
		if (appearance.equals(next)) {
			return false;
		}
		appearance = next;
		revision++;
		return true;
	}

	/** Every module's state, in registry order. */
	public List<ModuleState> modules() {
		return moduleList;
	}

	/** The state of the module with this id, or null when there is no such module. */
	public @Nullable ModuleState module(String moduleId) {
		int index = ModuleRegistry.indexOf(moduleId);
		return index < 0 ? null : modules[index];
	}

	public ModuleState module(ModuleDescriptor descriptor) {
		ModuleState state = module(descriptor.id());
		if (state == null) {
			throw new IllegalArgumentException("unknown module " + descriptor.id());
		}
		return state;
	}

	/** What a module draws with: its own overrides over this layout's style. Allocates: cache it per {@link #revision()}. */
	public Appearance effectiveAppearance(ModuleState module) {
		return Appearance.effective(module.appearance(), appearance);
	}

	public int enabledCount() {
		int count = 0;
		for (ModuleState module : modules) {
			if (module.enabled()) {
				count++;
			}
		}
		return count;
	}

	/** Every module back to its default position and size (the HUD editor's Reset). */
	public boolean resetPositions() {
		boolean changed = false;
		for (ModuleState module : modules) {
			changed |= module.resetPlacement();
		}
		return changed;
	}

	/**
	 * Grows with every real change to the name, the style or any module; never goes back. A store compares it
	 * with the revision it last saved.
	 */
	public long revision() {
		long total = revision;
		for (ModuleState module : modules) {
			total += module.revision();
		}
		return total;
	}

	/** A deep copy with the same id and name (the HUD editor's snapshot). */
	public Layout copy() {
		return copyAs(id, name);
	}

	/** A deep copy under another id and name (duplicating, or starting from the current layout). */
	public Layout copyAs(String newId, String newName) {
		Layout copy = new Layout(newId, newName);
		copy.appearance = appearance;
		for (int i = 0; i < modules.length; i++) {
			copy.modules[i].copyFrom(modules[i]);
		}
		return copy;
	}

	/**
	 * Puts back the style and modules of {@code snapshot} (from {@link #copy()}) in place, so anything holding
	 * this layout or its module states sees the restored values. The id and name stay. It counts as a change
	 * (the revision grows) whenever anything differed, so the HUD editor decides whether there is anything to
	 * discard by comparing with its snapshot ({@code !layout.equals(snapshot)}), not by revision.
	 */
	public boolean restore(Layout snapshot) {
		boolean changed = setAppearance(snapshot.appearance);
		for (int i = 0; i < modules.length; i++) {
			changed |= modules[i].copyFrom(snapshot.modules[i]);
		}
		return changed;
	}

	private static Appearance shared(Appearance value) {
		Appearance full = value.over(Appearance.DEFAULTS);
		return full.withShowLabel(null);
	}

	/** Equal when the id, name, style and every module match (revisions are not part of it). */
	@Override
	public boolean equals(Object o) {
		return o instanceof Layout other && id.equals(other.id) && name.equals(other.name) && appearance.equals(other.appearance)
			&& Arrays.equals(modules, other.modules);
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, name, appearance, Arrays.hashCode(modules));
	}

	@Override
	public String toString() {
		return "Layout[" + id + " \"" + name + "\" " + appearance + " " + Arrays.toString(modules) + "]";
	}
}
