package com.flintclient.client.control.store;

import com.flintclient.client.control.model.Appearance;
import com.flintclient.client.control.model.HudThemeId;
import com.flintclient.client.control.model.Layout;
import com.flintclient.client.control.model.LayoutNames;
import com.flintclient.client.control.model.LibraryQuery;
import com.flintclient.client.control.model.Preset;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Layout profiles, one file each in {@code config/flintclient/layouts/<id>.json} (see {@link LayoutCodec}).
 * All files are read once, on first use (or by {@link #load()}, which the Control Center calls at client start
 * so the first HUD frame doesn't). When there are none, an in-memory layout called "Default" with no modules
 * on is used; it becomes a file only once it is changed.
 *
 * <p>Each layout tracks its own unsaved changes: it is dirty when its content differs from what was last read
 * or written (a change that is undone again doesn't count). Files are written only by {@link #save()} and by
 * the explicit actions here (create, duplicate, rename, delete, switch), which save what they touch. The layout
 * in use is remembered in {@link ControlSettings} whenever it is chosen or first written, so a restart comes
 * back to it. A file that can't be read is listed as damaged with its notice and can only be deleted, so it is
 * never overwritten silently; it is deleted only once a copy is kept next to it. A file from a newer Flint can
 * be used (and deleted on purpose) but is never written.
 */
public final class LayoutStore {
	public static final String DIR = "layouts";
	public static final String DEFAULT_NAME = "Default";
	private static final Logger LOGGER = LoggerFactory.getLogger("Flint Client");
	private static final String DAMAGED = "This layout couldn't be read, so it can only be deleted.";

	private final Path dir;
	private final ControlSettings settings;
	private final Clock clock;
	private final List<Entry> entries = new ArrayList<>();
	private @Nullable Entry active;
	private boolean loaded;
	private int switches;

	/** Layouts under {@code configDir} (Fabric's config folder); the active one is remembered in {@code settings}. */
	public LayoutStore(Path configDir, ControlSettings settings) {
		this(configDir, settings, Clock.systemDefaultZone());
	}

	LayoutStore(Path configDir, ControlSettings settings, Clock clock) {
		this.dir = configDir.resolve(ControlSettings.DIR).resolve(DIR);
		this.settings = settings;
		this.clock = clock;
	}

	/** The folder holding the layout files. */
	public Path directory() {
		return dir;
	}

	/** The layout in use. The same object until a switch, so the HUD can keep it between frames. */
	public Layout active() {
		return activeEntry().layout();
	}

	public String activeId() {
		return activeEntry().id;
	}

	/** Grows by one on every switch, so the HUD knows to rebuild what it cached for the old layout. */
	public int switchCount() {
		return switches;
	}

	/** The layout with this id, or null when there is none or it couldn't be read. */
	public @Nullable Layout get(String id) {
		Entry entry = entry(id);
		return entry == null ? null : entry.layout;
	}

	/** Reads every layout file now if that hasn't happened yet (otherwise the first call that needs them does). */
	public void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		if (Files.isDirectory(dir)) {
			try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
				for (Path file : files) {
					String name = file.getFileName().toString();
					String id = name.substring(0, name.length() - ".json".length());
					// Kept copies ("x.damaged-….json") and stray files don't have an id-shaped name.
					if (LayoutNames.validId(id) && Files.isRegularFile(file)) {
						entries.add(read(id, file));
					}
				}
			} catch (IOException | RuntimeException e) {
				LOGGER.warn("Couldn't list the layouts in {}", dir, e);
			}
		}
		entries.sort(Comparator.comparing(e -> e.id));
		String wanted = settings.activeLayout();
		Entry chosen = wanted == null ? null : entry(wanted);
		if (chosen == null || chosen.layout == null) {
			chosen = null;
			for (Entry entry : entries) {
				if (entry.layout != null && (chosen == null || LibraryQuery.fold(entry.name()).compareTo(LibraryQuery.fold(chosen.name())) < 0)) {
					chosen = entry;
				}
			}
		}
		if (chosen == null) {
			active = createDefault();
		} else {
			active = chosen;
			// Remember the choice when it was a fallback (in memory, saved with the settings), so the same layout
			// comes back after a restart even when more layouts are added. A no-op for the remembered one.
			settings.setActiveLayout(chosen.id);
		}
	}

	/** Every layout, by name. */
	public List<LayoutInfo> list() {
		load();
		List<Entry> sorted = new ArrayList<>(entries);
		sorted.sort(Comparator.comparing((Entry e) -> LibraryQuery.fold(e.name())).thenComparing(e -> e.id));
		List<LayoutInfo> rows = new ArrayList<>(sorted.size());
		Entry current = activeEntry();
		for (Entry entry : sorted) {
			Layout layout = entry.layout;
			HudThemeId theme = layout == null ? Appearance.DEFAULTS.theme() : layout.appearance().theme();
			rows.add(new LayoutInfo(entry.id, entry.name(), entry.modifiedMillis, layout == null ? 0 : layout.enabledCount(),
				theme == null ? HudThemeId.FLINT : theme, entry == current, layout == null, entry.store.readOnly(),
				layout == null ? noticeOrDamaged(entry) : entry.store.notice()));
		}
		return rows;
	}

	/** Whether any layout file was found or has been written (a damaged one counts). */
	public boolean anyLayoutFileExists() {
		load();
		for (Entry entry : entries) {
			if (entry.onDisk) {
				return true;
			}
		}
		return false;
	}

	/** Uses another layout (applies to the HUD at once) and saves what is pending. */
	public LayoutResult switchTo(String id) {
		Entry target = entry(id);
		if (target == null) {
			return LayoutResult.failed("That layout no longer exists.");
		}
		if (target.layout == null) {
			return LayoutResult.failed(noticeOrDamaged(target));
		}
		if (target != active) {
			active = target;
			switches++;
		}
		settings.setActiveLayout(target.id);
		save();
		settings.save();
		return LayoutResult.ok(target.id);
	}

	/**
	 * Makes and saves a new layout called {@code name}: a copy of the current layout when {@code preset} is
	 * null, otherwise the preset in {@code theme} (the preset's own style when null). Doesn't switch to it.
	 */
	public LayoutResult create(String name, @Nullable Preset preset, @Nullable HudThemeId theme) {
		load();
		String error = nameError(name, null);
		if (error != null) {
			return LayoutResult.failed(error);
		}
		String cleaned = LayoutNames.clean(name);
		String id = LayoutNames.uniqueId(cleaned, this::idTaken);
		Layout layout;
		if (preset == null) {
			layout = active().copyAs(id, cleaned);
			if (theme != null) {
				layout.setAppearance(layout.appearance().withTheme(theme));
			}
		} else {
			layout = preset.create(id, cleaned, theme == null ? preset.theme() : theme);
		}
		return add(id, layout);
	}

	/** Saves a copy of a layout as "<name> copy" (numbered when taken). Doesn't switch to it. */
	public LayoutResult duplicate(String id) {
		Entry source = entry(id);
		if (source == null) {
			return LayoutResult.failed("That layout no longer exists.");
		}
		if (source.layout == null) {
			return LayoutResult.failed(noticeOrDamaged(source));
		}
		String name = uniqueName(source.layout.name() + " copy");
		String newId = LayoutNames.uniqueId(name, this::idTaken);
		return add(newId, source.layout.copyAs(newId, name));
	}

	/** Renames a layout (1–32 characters, unique ignoring case) and saves it. The id and file name stay. */
	public LayoutResult rename(String id, String name) {
		Entry entry = entry(id);
		if (entry == null) {
			return LayoutResult.failed("That layout no longer exists.");
		}
		Layout layout = entry.layout;
		if (layout == null) {
			return LayoutResult.failed(noticeOrDamaged(entry));
		}
		if (entry.store.readOnly()) {
			String notice = entry.store.notice();
			return LayoutResult.failed(notice == null ? "This layout can't be changed." : notice);
		}
		String error = nameError(name, id);
		if (error != null) {
			return LayoutResult.failed(error);
		}
		String previous = layout.name();
		layout.setName(LayoutNames.clean(name));
		if (entry.dirty() && !write(entry)) {
			// Keep memory and disk telling the same story.
			layout.setName(previous);
			return LayoutResult.failed("Couldn't save the layout.");
		}
		return LayoutResult.ok(id);
	}

	/**
	 * Deletes a layout and its file (the caller asks the player first). Never the last layout, and never the
	 * one in use: switch away first. A file that couldn't be read is deleted only once a copy of it is kept
	 * next to it (the copy is never touched); a file from a newer Flint is deleted like any other, on purpose.
	 */
	public LayoutResult delete(String id) {
		Entry entry = entry(id);
		if (entry == null) {
			return LayoutResult.failed("That layout no longer exists.");
		}
		if (entries.size() <= 1) {
			return LayoutResult.failed("You need at least one layout.");
		}
		if (entry == activeEntry()) {
			return LayoutResult.failed("Switch to another layout before deleting this one.");
		}
		if (entry.layout == null && entry.onDisk && entry.store.keepCopy() == null) {
			return LayoutResult.failed("Flint couldn't keep a copy of this file, so it wasn't deleted.");
		}
		if (entry.onDisk) {
			try {
				Files.deleteIfExists(entry.store.file());
			} catch (IOException e) {
				LOGGER.warn("Couldn't delete {}", entry.store.file(), e);
				return LayoutResult.failed("Couldn't delete the layout file.");
			}
		}
		entries.remove(entry);
		return LayoutResult.ok(id);
	}

	/** The longest text {@link #importText} reads (a profile is a few kilobytes). */
	public static final int MAX_IMPORT_CHARS = 200_000;

	/**
	 * Layout {@code id} as text to share: the same JSON as its file (without the file id), for the clipboard; null when
	 * it doesn't exist or couldn't be read.
	 */
	public @Nullable String exportText(String id) {
		Entry entry = entry(id);
		if (entry == null || entry.layout == null) {
			return null;
		}
		com.google.gson.JsonObject json = LayoutCodec.toJson(entry.layout);
		json.remove("id");
		return new com.google.gson.GsonBuilder().disableHtmlEscaping().create().toJson(json);
	}

	/**
	 * Makes and saves a new layout from text {@link #exportText} made (its name numbered if taken). Anything it doesn't
	 * know is left out and unusable values become defaults, as when reading a file. Doesn't switch to it.
	 */
	public LayoutResult importText(@Nullable String text) {
		load();
		if (text == null || text.isBlank()) {
			return LayoutResult.failed("The clipboard is empty. Copy a Flint profile first.");
		}
		if (text.length() > MAX_IMPORT_CHARS) {
			return LayoutResult.failed("That's too long to be a Flint profile.");
		}
		com.google.gson.JsonElement parsed;
		try {
			parsed = com.google.gson.JsonParser.parseString(text.strip());
		} catch (RuntimeException e) {
			return LayoutResult.failed("The clipboard doesn't hold a Flint profile.");
		}
		if (!parsed.isJsonObject() || !parsed.getAsJsonObject().has("modules") || !parsed.getAsJsonObject().get("modules").isJsonObject()) {
			return LayoutResult.failed("The clipboard doesn't hold a Flint profile.");
		}
		com.google.gson.JsonObject root = parsed.getAsJsonObject();
		com.google.gson.JsonElement schema = root.get(JsonStore.SCHEMA_KEY);
		if (schema != null && schema.isJsonPrimitive() && schema.getAsJsonPrimitive().isNumber() && schema.getAsDouble() > LayoutCodec.SCHEMA) {
			return LayoutResult.failed("This profile is from a newer Flint. Update Flint to import it.");
		}
		String given = JsonFields.string(root, "name");
		String cleaned = given == null ? "" : LayoutNames.clean(given);
		if (cleaned.codePointCount(0, cleaned.length()) > LayoutNames.MAX_NAME - 3) {
			// Room for " 2" when the name is taken.
			cleaned = cleaned.substring(0, cleaned.offsetByCodePoints(0, LayoutNames.MAX_NAME - 3)).strip();
		}
		String name = uniqueName(cleaned.isEmpty() ? "Imported profile" : cleaned);
		String id = LayoutNames.uniqueId(name, this::idTaken);
		Layout layout = LayoutCodec.read(root, id);
		layout.setName(name);
		return add(id, layout);
	}

	/**
	 * Why {@code name} can't be used for a layout, or null when it can. {@code exceptId} is the layout being
	 * renamed (its own name doesn't count as taken), or null.
	 */
	public @Nullable String nameError(String name, @Nullable String exceptId) {
		load();
		List<String> others = new ArrayList<>();
		for (Entry entry : entries) {
			if (!entry.id.equals(exceptId)) {
				others.add(entry.name());
			}
		}
		return LayoutNames.nameError(name, others);
	}

	/** {@code base}, numbered if a layout already has that name. */
	public String uniqueName(String base) {
		load();
		List<String> names = new ArrayList<>();
		for (Entry entry : entries) {
			names.add(entry.name());
		}
		return LayoutNames.uniqueName(base, names);
	}

	/**
	 * Whether any layout has unsaved changes. This compares with the files, not with what the HUD editor
	 * started from: the editor asks "Discard changes?" by comparing the layout with its own snapshot
	 * ({@code !layout.equals(snapshot)}).
	 */
	public boolean dirty() {
		load();
		for (Entry entry : entries) {
			if (entry.dirty()) {
				return true;
			}
		}
		return false;
	}

	/** Whether this layout has unsaved changes. */
	public boolean dirty(String id) {
		Entry entry = entry(id);
		return entry != null && entry.dirty();
	}

	/** Writes every layout with unsaved changes. Returns false when one couldn't be written (or may not be). */
	public boolean save() {
		load();
		boolean all = true;
		for (Entry entry : entries) {
			if (entry.dirty()) {
				all &= write(entry);
			}
		}
		return all;
	}

	private LayoutResult add(String id, Layout layout) {
		Entry entry = new Entry(id, new JsonStore(dir.resolve(id + ".json"), LayoutCodec.SCHEMA, clock));
		entry.layout = layout;
		entry.savedRevision = -1;
		entry.saved = null;
		entries.add(entry);
		if (!write(entry)) {
			entries.remove(entry);
			return LayoutResult.failed("Couldn't save the new layout.");
		}
		return LayoutResult.ok(id);
	}

	private boolean write(Entry entry) {
		Layout layout = entry.layout;
		if (layout == null || entry.store.readOnly()) {
			return false;
		}
		LayoutCodec.write(layout, entry.store.root());
		entry.store.markDirty();
		if (!entry.store.save()) {
			return false;
		}
		entry.savedRevision = layout.revision();
		entry.saved = layout.copy();
		entry.onDisk = true;
		entry.modifiedMillis = clock.millis();
		if (entry == active && !entry.id.equals(settings.activeLayout())) {
			// The layout in use has just become a file (the unsaved Default, usually): remember it, so a restart
			// doesn't pick another layout by name.
			settings.setActiveLayout(entry.id);
			settings.save();
		}
		return true;
	}

	private Entry activeEntry() {
		load();
		if (active == null) {
			active = createDefault();
		}
		return active;
	}

	private @Nullable Entry entry(String id) {
		load();
		for (Entry entry : entries) {
			if (entry.id.equals(id)) {
				return entry;
			}
		}
		return null;
	}

	private boolean idTaken(String id) {
		return entry(id) != null || Files.exists(dir.resolve(id + ".json"));
	}

	private Entry read(String id, Path file) {
		JsonStore store = new JsonStore(file, LayoutCodec.SCHEMA, clock);
		Entry entry = new Entry(id, store);
		entry.onDisk = true;
		try {
			entry.modifiedMillis = Files.getLastModifiedTime(file).toMillis();
		} catch (IOException e) {
			entry.modifiedMillis = -1;
		}
		JsonStore.State state = store.state();
		if (state == JsonStore.State.LOADED || state == JsonStore.State.NEWER) {
			Layout layout = LayoutCodec.read(store.root(), id);
			entry.layout = layout;
			entry.savedRevision = layout.revision();
			entry.saved = layout.copy();
		}
		return entry;
	}

	/** The in-memory "Default" layout (no modules on); written only once changed. */
	private Entry createDefault() {
		String id = LayoutNames.uniqueId(DEFAULT_NAME, this::idTaken);
		Entry entry = new Entry(id, new JsonStore(dir.resolve(id + ".json"), LayoutCodec.SCHEMA, clock));
		Layout layout = new Layout(id, uniqueName(DEFAULT_NAME));
		entry.layout = layout;
		entry.savedRevision = layout.revision();
		entry.saved = layout.copy();
		entries.add(entry);
		return entry;
	}

	/** Why a damaged layout can't be used; unlike other files, it isn't replaced by defaults. */
	private static String noticeOrDamaged(Entry entry) {
		Path copy = entry.store.damagedCopy();
		if (copy != null) {
			return entry.store.file().getFileName() + " couldn't be read. Flint saved a copy as " + copy.getFileName()
				+ "; this layout can only be deleted.";
		}
		String notice = entry.store.notice();
		return notice == null ? DAMAGED : notice;
	}

	/** One layout file (or the unsaved default). */
	private static final class Entry {
		final String id;
		final JsonStore store;
		/** Null when the file couldn't be read. */
		@Nullable Layout layout;
		/** The layout's revision when it last matched {@link #saved}; a quick "nothing changed" check. */
		long savedRevision;
		/** A copy of what was last read or written (null before a new layout's first write). */
		@Nullable Layout saved;
		long modifiedMillis = -1;
		/** A file exists for it (found when listing, or written since). */
		boolean onDisk;

		Entry(String id, JsonStore store) {
			this.id = id;
			this.store = store;
		}

		Layout layout() {
			if (layout == null) {
				throw new IllegalStateException("damaged layout " + id);
			}
			return layout;
		}

		String name() {
			return layout == null ? id : layout.name();
		}

		/** Whether the layout differs from its file: a changed revision, unless the content came back to the same. */
		boolean dirty() {
			if (layout == null || layout.revision() == savedRevision) {
				return false;
			}
			if (layout.equals(saved)) {
				savedRevision = layout.revision();
				return false;
			}
			return true;
		}
	}
}
