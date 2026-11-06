package com.flintclient.client.control.rshift;

import com.flintclient.client.control.model.Appearance;
import com.flintclient.client.control.model.GameOptions;
import com.flintclient.client.control.model.ModuleDescriptor;
import com.flintclient.client.control.model.ModuleRegistry;
import com.flintclient.client.control.model.SettingDescriptor;
import com.flintclient.client.control.model.SettingSection;
import com.flintclient.client.control.model.VideoOptions;
import com.flintclient.client.control.store.ControlSettings;
import com.flintclient.client.render.Icon;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What the Right Shift menu lists, in the reference menu's structure: its categories, its mods in its order, each mod's
 * sections and rows by the reference's names, and the five settings tabs (General, Social, Graphics, Performance,
 * Cosmetic). It comes from {@code assets/flintclient/rshift/catalog.json}; each row there names the Flint setting that
 * does the same thing, and is resolved here against {@link ModuleRegistry} and {@link ControlSettings}. A row without
 * one is something Flint doesn't have yet: the menu shows it, marked, with no control.
 *
 * <p>Every Flint setting appears exactly once: on the row it was mapped to, else in a "More in Flint" section of the mod
 * its module belongs to (or of the General tab, for Flint's own client settings). A Flint module that no reference mod
 * covers keeps a card of its own after the reference's mods. {@code CatalogTest} holds the file to these rules.
 * Read once; no Minecraft classes, so it is unit-tested as it is.
 */
public final class Catalog {
	static final String RESOURCE = "/assets/flintclient/rshift/catalog.json";
	/** Where a Flint module's leftover settings go on its mod's page. */
	static final String MORE = "More in Flint";

	/** The mod filter chips after "All", in the reference's order. */
	public enum Category {
		NEW("New"),
		HUD("HUD"),
		HYPIXEL("Hypixel"),
		PVP("PvP");

		private final String label;

		Category(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}

		static @Nullable Category of(String label) {
			for (Category c : values()) {
				if (c.label.equalsIgnoreCase(label)) {
					return c;
				}
			}
			return null;
		}
	}

	/**
	 * One row. {@code module} with {@code setting}: a module's setting; {@code module} alone: that module on or off;
	 * {@code global}: one of Flint's client settings ({@link ControlSettings}) or one of Minecraft's own video options
	 * ({@link VideoOptions}); {@code look}: a field of the HUD look ({@link Appearance}) of {@code module}, or of the profile
	 * when there is no module; none of them: not in Flint yet. {@code action}: a button row ({@code global}, no setting: what
	 * it does, {@link #ACTIONS}), or {@link #LINK} on a module row: a shortcut to a mod (its switch, and its page a click
	 * away), which the mod's own card still switches too.
	 */
	public record Row(String label, String kind, @Nullable ModuleDescriptor module, @Nullable SettingDescriptor<?> setting, boolean global,
		boolean look, @Nullable String unavailable, @Nullable String action) {
		public Row(String label, String kind, @Nullable ModuleDescriptor module, @Nullable SettingDescriptor<?> setting, boolean global) {
			this(label, kind, module, setting, global, false, null, null);
		}

		public Row(String label, String kind, @Nullable ModuleDescriptor module, @Nullable SettingDescriptor<?> setting, boolean global,
			boolean look) {
			this(label, kind, module, setting, global, look, null, null);
		}

		public Row(String label, String kind, @Nullable ModuleDescriptor module, @Nullable SettingDescriptor<?> setting, boolean global,
			boolean look, @Nullable String unavailable) {
			this(label, kind, module, setting, global, look, unavailable, null);
		}

		public boolean inFlint() {
			return module != null || global || look;
		}

		/** Whether this row turns a whole module on or off (a mod's own switch, not a shortcut to it). */
		public boolean toggle() {
			return module != null && setting == null && action == null;
		}

		/** Whether this row is one of Minecraft's own options (the value lives in Minecraft's {@code options.txt}). */
		public boolean minecraft() {
			return global && setting != null && VideoOptions.ALL.contains(setting);
		}
	}

	/** A row that is a shortcut to a mod ({@code link/<module id>}): its switch and a way to its page. */
	public static final String LINK = "link";
	/** What a button row ({@code action/<id>}) can do. */
	public static final List<String> ACTIONS = List.of("screenshots", "resourcepacks");

	public record Section(String title, List<Row> rows) {
		public Section {
			rows = List.copyOf(rows);
		}
	}

	/**
	 * A mod card. {@code module} is the Flint module its card switches on and off (null: Flint doesn't have it, so the card
	 * has no switch); {@code extra} marks a Flint module that isn't in the reference's list.
	 */
	public record Mod(String key, String name, String description, Icon icon, Set<Category> categories, @Nullable ModuleDescriptor module,
		List<Section> sections, boolean extra, @Nullable String unavailable) {
		public Mod(String key, String name, String description, Icon icon, Set<Category> categories, @Nullable ModuleDescriptor module,
			List<Section> sections, boolean extra) {
			this(key, name, description, icon, categories, module, sections, extra, null);
		}

		public Mod {
			categories = Collections.unmodifiableSet(categories.isEmpty() ? EnumSet.noneOf(Category.class) : EnumSet.copyOf(categories));
			sections = List.copyOf(sections);
		}

		public boolean in(@Nullable Category category) {
			return category == null || categories.contains(category);
		}

		/** The id favourites are saved under: the Flint module's (so favourites made before this menu carry over), else the mod's. */
		public String favoriteKey() {
			return module != null ? module.id() : "ref:" + key;
		}

		/** Where it goes in the list: the reference's own key ("armorstatus"), or a Flint module's name squeezed the same way. */
		String sortKey() {
			return extra ? name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "") : key;
		}
	}

	/** A settings tab beside the mods (General, Social, Graphics, Performance, Cosmetic). */
	public record Page(String key, String name, List<Section> sections, @Nullable String unavailable) {
		public Page {
			sections = List.copyOf(sections);
		}

		public Page(String key, String name, List<Section> sections) {
			this(key, name, sections, null);
		}
	}

	/** Volatile: the Right Shift menu's warm-up reads it on a worker. */
	private static volatile @Nullable Catalog loaded;

	private final List<Mod> mods;
	private final List<Page> pages;

	private Catalog(List<Mod> mods, List<Page> pages) {
		this.mods = List.copyOf(mods);
		this.pages = List.copyOf(pages);
	}

	/** The catalog, read from the jar the first time it's asked for. */
	/**
	 * What a Minecraft line runs, for the catalog to list. Every line but 1.8.9 runs every module and row Flint has (no engine is
	 * set); 1.8.9 lists the mods it has a backend for, and of each only the rows that backend honours, so nothing on a page is
	 * a switch that does nothing there.
	 */
	public interface Engine {
		/** Whether this line runs {@code module} (its card is listed). */
		boolean runs(ModuleDescriptor module);

		/** Whether this line honours {@code row} (a mod's setting, a client setting, a HUD look field, a button). */
		boolean shows(Row row);
	}

	private static volatile @Nullable Engine engine;

	/** Lists only what {@code e} runs from now on (the next {@link #get} reads the catalog again). Set once, at start-up. */
	public static void engine(Engine e) {
		engine = e;
		loaded = null;
	}

	/** Whether this line runs {@code module} (every line but 1.8.9 runs them all). */
	public static boolean runs(ModuleDescriptor module) {
		Engine e = engine;
		return e == null || e.runs(module);
	}

	public static Catalog get() {
		Catalog c = loaded;
		if (c == null) {
			try (InputStream in = Catalog.class.getResourceAsStream(RESOURCE)) {
				if (in == null) {
					throw new IllegalStateException("Flint's Right Shift catalog is missing from the jar: " + RESOURCE);
				}
				c = read(new InputStreamReader(in, StandardCharsets.UTF_8));
				if (engine != null) {
					c = c.on(engine);
				}
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			loaded = c;
		}
		return c;
	}

	public List<Mod> mods() {
		return mods;
	}

	public List<Page> pages() {
		return pages;
	}

	/** The mod whose card switches {@code moduleId}, or the card that lists it; null if none. */
	public @Nullable Mod modFor(String moduleId) {
		for (Mod m : mods) {
			if (m.module() != null && m.module().id().equals(moduleId)) {
				return m;
			}
		}
		for (Mod m : mods) {
			for (Section s : m.sections()) {
				for (Row r : s.rows()) {
					if (r.module() != null && r.module().id().equals(moduleId)) {
						return m;
					}
				}
			}
		}
		return null;
	}

	public @Nullable Mod mod(String key) {
		for (Mod m : mods) {
			if (m.key().equals(key)) {
				return m;
			}
		}
		return null;
	}

	/** Client settings the Theme page shows (its Sounds), so General doesn't list them again. */
	static final List<SettingDescriptor<?>> ON_THEME_PAGE = List.of(ControlSettings.SOUNDS, ControlSettings.VOLUME, GameOptions.MENU_SOUND);

	/**
	 * Flint's client settings a catalog row can name as {@code global/<id>}. Not listed (their values are kept, nothing shows
	 * them): Interface size (the menu's UI scaling is the one size setting), High-contrast outlines and Follow Minecraft's
	 * reduced motion (they changed only Flint's older screens), and Gameplay motion blur with its strength (the Motion Blur
	 * mod is the one switch for that effect; the row stays while an old setting has it on, so it can be turned off).
	 */
	static final List<SettingDescriptor<?>> GLOBALS = List.of(ControlSettings.SOUNDS, ControlSettings.VOLUME, ControlSettings.ANIMATIONS,
		ControlSettings.TRANSITION_BLUR, ControlSettings.GAMEPLAY_BLUR, ControlSettings.SHOW_WITH_DEBUG_SCREEN,
		GameOptions.MENU_KEY, GameOptions.HUD_EDITOR_KEY, GameOptions.NO_HOTBAR_LOOPING, GameOptions.MINIMAL_BOBBING, GameOptions.CLOCK_24H, GameOptions.LEAVE_KEY,
		GameOptions.LEAVE_CONFIRM, GameOptions.MOD_KEYS, GameOptions.INACTIVE_FPS, GameOptions.LIMIT_UNFOCUSED, GameOptions.LIMIT_IDLE,
		GameOptions.IDLE_MINUTES, GameOptions.IDLE_SECONDS, GameOptions.HUD_SCALE_STEP, GameOptions.MENU_SCALE,
		GameOptions.MENU_SOUND, GameOptions.FLINT_BADGE, GameOptions.SHARE_PRESENCE, GameOptions.STORE_CAPES, GameOptions.CAPE_ELYTRA,
		GameOptions.VANILLA_MAIN_MENU);

	/** Parses and resolves a catalog; an unknown module, setting or icon is an error (the test reads the shipped file). */
	static Catalog read(Reader reader) {
		JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
		Map<String, Icon> extraIcons = new LinkedHashMap<>();
		if (root.has("extraIcons")) {
			for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("extraIcons").entrySet()) {
				extraIcons.put(e.getKey(), Icon.valueOf(e.getValue().getAsString()));
			}
		}
		List<Page> pages = new ArrayList<>();
		for (JsonElement e : root.getAsJsonArray("pages")) {
			JsonObject o = e.getAsJsonObject();
			String unavailable = o.has("unavailable") ? o.get("unavailable").getAsString() : null;
			pages.add(new Page(o.get("key").getAsString(), o.get("name").getAsString(), sections(o.getAsJsonArray("sections"), unavailable),
				unavailable));
		}
		List<Mod> mods = new ArrayList<>();
		Set<String> last = new java.util.LinkedHashSet<>();
		for (JsonElement e : root.getAsJsonArray("mods")) {
			JsonObject o = e.getAsJsonObject();
			if (o.has("last") && o.get("last").getAsBoolean()) {
				last.add(o.get("key").getAsString());
			}
			EnumSet<Category> categories = EnumSet.noneOf(Category.class);
			for (JsonElement c : o.getAsJsonArray("categories")) {
				Category category = Category.of(c.getAsString());
				if (category == null) {
					throw new IllegalArgumentException("unknown category " + c);
				}
				categories.add(category);
			}
			ModuleDescriptor module = o.has("flint") ? ModuleRegistry.get(o.get("flint").getAsString()) : null;
			String unavailable = o.has("unavailable") ? o.get("unavailable").getAsString() : null;
			String description = o.get("description").getAsString();
			mods.add(new Mod(o.get("key").getAsString(), o.get("name").getAsString(), description.isEmpty() && module != null ? module.description()
				: description, Icon.valueOf(o.get("icon").getAsString()), categories, module, sections(o.getAsJsonArray("sections"), unavailable),
				false, unavailable));
		}
		return complete(mods, pages, extraIcons, last);
	}

	/** {@code whole}: why the whole mod or page is unavailable, which its rows then don't repeat (their reason becomes ""). */
	private static List<Section> sections(JsonArray array, @Nullable String whole) {
		List<Section> sections = new ArrayList<>();
		for (JsonElement e : array) {
			JsonObject o = e.getAsJsonObject();
			List<Row> rows = new ArrayList<>();
			for (JsonElement r : o.getAsJsonArray("rows")) {
				JsonObject row = r.getAsJsonObject();
				Row made = row(row.get("label").getAsString(), row.get("kind").getAsString(), row.has("bind") ? row.get("bind").getAsString() : null);
				String why = row.has("unavailable") ? row.get("unavailable").getAsString() : null;
				rows.add(why == null ? made : new Row(made.label(), made.kind(), made.module(), made.setting(), made.global(), made.look(),
					why.equals(whole) ? "" : why));
			}
			sections.add(new Section(o.get("title").getAsString(), rows));
		}
		return sections;
	}

	private static Row row(String label, String kind, @Nullable String bind) {
		if (bind == null) {
			return new Row(label, kind, null, null, false);
		}
		int slash = bind.indexOf('/');
		String owner = bind.substring(0, slash);
		String id = bind.substring(slash + 1);
		if (id.startsWith("look.")) {
			SettingDescriptor<?> field = Appearance.field(id.substring(5));
			if (field == null) {
				throw new IllegalArgumentException("no HUD look field " + bind);
			}
			if (owner.equals("profile")) {
				return new Row(label, kind, null, field, false, true);
			}
			ModuleDescriptor module = ModuleRegistry.get(owner);
			if (!panelLook(module)) {
				throw new IllegalArgumentException(owner + " draws its own look (" + bind + ")");
			}
			return new Row(label, kind, module, field, false, true);
		}
		if (owner.equals("global")) {
			for (SettingDescriptor<?> g : GLOBALS) {
				if (g.id().equals(id)) {
					return new Row(label, kind, null, g, true);
				}
			}
			throw new IllegalArgumentException("no Flint client setting " + id);
		}
		if (owner.equals("minecraft")) {
			SettingDescriptor<?> option = VideoOptions.byId(id);
			if (option == null) {
				throw new IllegalArgumentException("no Minecraft option " + id);
			}
			return new Row(label, kind, null, option, true);
		}
		if (owner.equals("action")) {
			if (!ACTIONS.contains(id)) {
				throw new IllegalArgumentException("no action " + id);
			}
			return new Row(label, kind, null, null, true, false, null, id);
		}
		if (owner.equals(LINK)) {
			return new Row(label, kind, ModuleRegistry.get(id), null, false, false, null, LINK);
		}
		ModuleDescriptor module = ModuleRegistry.get(owner);
		if (id.equals("@enabled")) {
			return new Row(label, kind, module, null, false);
		}
		SettingDescriptor<?> setting = module.setting(id);
		if (setting == null) {
			throw new IllegalArgumentException("no setting " + bind);
		}
		return new Row(label, kind, module, setting, false);
	}

	/**
	 * Adds what the file leaves implicit: each Flint module's settings that no row names (under "More in Flint" on the mod
	 * that has that module), Flint's own client settings on the General tab, and a card for every Flint module no
	 * reference mod covers. The cards the file marks {@code "last"} (Music Player) go after all the others, in the file's
	 * order.
	 */
	private static Catalog complete(List<Mod> given, List<Page> pages, Map<String, Icon> icons, Set<String> last) {
		// A mod Flint doesn't have is a preview of what's coming, so none of its rows work there: a Flint setting one names
		// stays on its own module's card instead (Animations names Hurt Cam's strength and Damage Tint's switch).
		List<Mod> mods = new ArrayList<>(given.size());
		for (Mod m : given) {
			mods.add(m.module() == null ? preview(m) : m);
		}
		Set<SettingDescriptor<?>> named = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<String> looks = new java.util.HashSet<>();
		Map<String, Integer> home = new LinkedHashMap<>();
		for (int i = 0; i < mods.size(); i++) {
			Mod m = mods.get(i);
			if (m.module() != null) {
				home.put(m.module().id(), i);
			}
			collect(m.sections(), named, looks);
		}
		for (Page p : pages) {
			collect(p.sections(), named, looks);
		}
		// A module switched by a row of a mod (not its card) lives on that mod's page.
		for (int i = 0; i < mods.size(); i++) {
			for (Section s : mods.get(i).sections()) {
				for (Row r : s.rows()) {
					if (r.toggle()) {
						home.putIfAbsent(r.module().id(), i);
					}
				}
			}
		}
		List<Mod> done = new ArrayList<>(mods);
		for (ModuleDescriptor module : ModuleRegistry.all()) {
			Integer at = home.get(module.id());
			List<Section> rest = leftovers(module, named);
			if (panelLook(module)) {
				List<Row> lookRows = new ArrayList<>();
				for (SettingDescriptor<?> field : Appearance.FIELDS) {
					if (!looks.contains(module.id() + "/" + field.id())) {
						lookRows.add(new Row(titleCase(field.label()), kindOf(field), module, field, false, true));
					}
				}
				if (!lookRows.isEmpty()) {
					rest.add(new Section(MORE + " · Look", lookRows));
				}
			}
			if (at != null) {
				if (!rest.isEmpty()) {
					Mod m = done.get(at);
					done.set(at, new Mod(m.key(), m.name(), m.description(), m.icon(), m.categories(), m.module(), joined(m.sections(), rest), m.extra(),
						m.unavailable()));
				}
				continue;
			}
			EnumSet<Category> categories = EnumSet.noneOf(Category.class);
			if (module.hud()) {
				categories.add(Category.HUD);
			}
			if (module.category() == com.flintclient.client.control.model.Category.COMBAT) {
				categories.add(Category.PVP);
			}
			// A module Flint keeps switched off (it needs another company's permission) gets no card.
			if (!module.status().usable()) {
				continue;
			}
			// Its own card: the Mod section (toggle key) first, as on every other page.
			List<Section> own = joined(List.of(), rest);
			own.sort(java.util.Comparator.comparing(s -> !s.title().equals("Mod")));
			done.add(new Mod(module.id(), module.name(), module.description(), icons.getOrDefault(module.id(), Icon.GAUGE), categories, module, own, true));
		}
		List<Page> pagesDone = new ArrayList<>(pages);
		List<Row> clientRows = new ArrayList<>();
		for (SettingDescriptor<?> g : GLOBALS) {
			if (!named.contains(g) && !ON_THEME_PAGE.contains(g)) {
				clientRows.add(new Row(g.label(), kindOf(g), null, g, true));
			}
		}
		// The reference lists mods alphabetically by key; Flint's own modules slot in among them. A card marked "last" closes the
		// list (as the reference's Media Player does).
		done.sort(java.util.Comparator.comparing(Mod::sortKey));
		for (String key : last) {
			for (int i = 0; i < done.size(); i++) {
				if (done.get(i).key().equals(key)) {
					done.add(done.remove(i));
					break;
				}
			}
		}
		// The profile's own HUD look (every module that doesn't choose its own follows it); the layout keeps no label switch.
		List<Row> profileLook = new ArrayList<>();
		for (SettingDescriptor<?> field : Appearance.FIELDS) {
			if (field != Appearance.SHOW_LABEL && !looks.contains("profile/" + field.id())) {
				profileLook.add(new Row(field.label(), kindOf(field), null, field, false, true));
			}
		}
		if (!pagesDone.isEmpty()) {
			Page general = pagesDone.get(0);
			List<Section> sections = new ArrayList<>(general.sections());
			// The menu's own key leads the Keybinds section.
			List<Row> menuKey = new ArrayList<>();
			clientRows.removeIf(row -> row.setting() == GameOptions.MENU_KEY && menuKey.add(row));
			for (int i = 0; i < sections.size() && !menuKey.isEmpty(); i++) {
				if (sections.get(i).title().equals("Keybinds")) {
					List<Row> rows = new ArrayList<>(menuKey);
					rows.addAll(sections.get(i).rows());
					sections.set(i, new Section("Keybinds", rows));
					menuKey.clear();
				}
			}
			clientRows.addAll(0, menuKey);
			// The Flint mark on nametags (and showing yours to others) is one of Flint's own touches: in plain view, not in the folded extras.
			List<Row> nametags = new ArrayList<>();
			clientRows.removeIf(row -> (row.setting() == GameOptions.FLINT_BADGE || row.setting() == GameOptions.SHARE_PRESENCE) && nametags.add(row));
			if (!nametags.isEmpty()) {
				sections.add(new Section("Nametags", nametags));
			}
			// What players equipped in the Flint Store (in the launcher): beside the marks, also in plain view.
			List<Row> cosmetics = new ArrayList<>();
			clientRows.removeIf(row -> (row.setting() == GameOptions.STORE_CAPES || row.setting() == GameOptions.CAPE_ELYTRA) && cosmetics.add(row));
			if (!cosmetics.isEmpty()) {
				sections.add(new Section("Cosmetics", cosmetics));
			}
			if (!clientRows.isEmpty()) {
				sections.add(new Section(MORE, clientRows));
			}
			if (!profileLook.isEmpty()) {
				sections.add(new Section(MORE + " · HUD look (this profile)", profileLook));
			}
			pagesDone.set(0, new Page(general.key(), general.name(), sections, general.unavailable()));
		}
		// A mod Flint can't offer at all (it needs another company's service) isn't listed; a working mod or tab lists only
		// the rows Flint runs, so nothing on a page is a placeholder.
		done.removeIf(m -> m.unavailable() != null);
		done.replaceAll(m -> m.module() == null ? m : new Mod(m.key(), m.name(), m.description(), m.icon(), m.categories(), m.module(),
			working(m.sections()), m.extra(), null));
		pagesDone.replaceAll(p -> p.unavailable() != null ? p : new Page(p.key(), p.name(), working(p.sections()), null));
		return new Catalog(done, pagesDone);
	}

	/** This catalog as {@code e} runs it: the mods it runs, each page and mod with only the rows it honours, and no empty page. */
	public Catalog on(Engine e) {
		List<Mod> mods = new ArrayList<>();
		for (Mod m : mods()) {
			if (m.module() != null && e.runs(m.module())) {
				mods.add(new Mod(m.key(), m.name(), m.description(), m.icon(), m.categories(), m.module(), shown(m.sections(), e), m.extra(),
					m.unavailable()));
			}
		}
		List<Page> pages = new ArrayList<>();
		for (Page p : pages()) {
			// A page Flint can't offer stays as it is (the window leaves it out on every line).
			List<Section> sections = p.unavailable() != null ? p.sections() : shown(p.sections(), e);
			if (!sections.isEmpty()) {
				pages.add(new Page(p.key(), p.name(), sections, p.unavailable()));
			}
		}
		return new Catalog(mods, pages);
	}

	/** {@code sections} with only the rows {@code e} honours (a link to a mod only when it runs the mod), leaving out empty ones. */
	private static List<Section> shown(List<Section> sections, Engine e) {
		List<Section> out = new ArrayList<>(sections.size());
		for (Section s : sections) {
			List<Row> rows = new ArrayList<>(s.rows().size());
			for (Row r : s.rows()) {
				if ((r.module() == null || e.runs(r.module())) && e.shows(r)) {
					rows.add(r);
				}
			}
			if (!rows.isEmpty()) {
				out.add(rows.size() == s.rows().size() ? s : new Section(s.title(), rows));
			}
		}
		return out;
	}

	/** {@code sections} with only the rows Flint runs, leaving out a section that has none. */
	private static List<Section> working(List<Section> sections) {
		List<Section> out = new ArrayList<>(sections.size());
		for (Section s : sections) {
			List<Row> rows = s.rows().stream().filter(Row::inFlint).toList();
			if (!rows.isEmpty()) {
				out.add(rows.size() == s.rows().size() ? s : new Section(s.title(), rows));
			}
		}
		return out;
	}

	private static void collect(List<Section> sections, Set<SettingDescriptor<?>> named, Set<String> looks) {
		for (Section s : sections) {
			for (Row r : s.rows()) {
				if (r.look()) {
					looks.add((r.module() != null ? r.module().id() : "profile") + "/" + r.setting().id());
				} else if (r.setting() != null) {
					named.add(r.setting());
				}
			}
		}
	}

	/** Whether {@code module} is drawn in the HUD look ({@link Appearance}): a HUD module that doesn't set its own look. */
	public static boolean panelLook(ModuleDescriptor module) {
		return module.hud() && module.boxStyle() == null && !module.drawnByMinecraft() && module.status().usable();
	}

	/** {@code mod} with none of its rows bound to Flint. */
	private static Mod preview(Mod mod) {
		List<Section> sections = new ArrayList<>(mod.sections().size());
		for (Section s : mod.sections()) {
			List<Row> rows = new ArrayList<>(s.rows().size());
			for (Row r : s.rows()) {
				rows.add(r.inFlint() ? new Row(r.label(), r.kind(), null, null, false, false, r.unavailable()) : r);
			}
			sections.add(new Section(s.title(), rows));
		}
		return new Mod(mod.key(), mod.name(), mod.description(), mod.icon(), mod.categories(), null, sections, mod.extra(), mod.unavailable());
	}

	/** Flint's sections a page folds until opened: the fine detail most players never change. */
	private static final Set<String> FOLDED = Set.of("Advanced", "Background and Border", "Look");

	/**
	 * A page's sections with a module's leftover ones ({@link #leftovers}) added: one whose title the page already has joins
	 * that section (a page never shows two of the same name); the rest follow, open, except the {@link #FOLDED} detail.
	 */
	private static List<Section> joined(List<Section> page, List<Section> rest) {
		List<Section> out = new ArrayList<>(page);
		for (Section s : rest) {
			String title = s.title().substring(MORE.length() + 3);
			int same = -1;
			for (int i = 0; i < out.size(); i++) {
				if (out.get(i).title().equalsIgnoreCase(title)) {
					same = i;
				}
			}
			if (same >= 0) {
				List<Row> rows = new ArrayList<>(out.get(same).rows());
				rows.addAll(s.rows());
				out.set(same, new Section(out.get(same).title(), rows));
			} else {
				out.add(new Section(FOLDED.contains(title) ? s.title() : title, s.rows()));
			}
		}
		return out;
	}

	/** A Flint label in the reference's Title Case ("Background and Border"); words already capitalized are kept. */
	static String titleCase(String label) {
		StringBuilder out = new StringBuilder(label.length());
		boolean first = true;
		for (String word : label.split(" ", -1)) {
			if (!first) {
				out.append(' ');
			}
			boolean small = !first && SMALL_WORDS.contains(word);
			out.append(!small && !word.isEmpty() && Character.isLowerCase(word.charAt(0)) ? Character.toUpperCase(word.charAt(0)) + word.substring(1) : word);
			first = false;
		}
		return out.toString();
	}

	private static final Set<String> SMALL_WORDS = Set.of("a", "an", "and", "as", "at", "by", "for", "in", "of", "on", "or", "per", "the", "to", "vs",
		"with");

	/** {@code module}'s settings no row names, in its own sections, titled "More in Flint · <section>". */
	private static List<Section> leftovers(ModuleDescriptor module, Set<SettingDescriptor<?>> named) {
		List<Section> rest = new ArrayList<>();
		for (SettingSection section : module.sections()) {
			List<Row> rows = new ArrayList<>();
			for (SettingDescriptor<?> setting : section.settings()) {
				if (!named.contains(setting)) {
					rows.add(new Row(titleCase(setting.label()), kindOf(setting), module, setting, false));
				}
			}
			if (!rows.isEmpty()) {
				rest.add(new Section(MORE + " · " + titleCase(section.title()), rows));
			}
		}
		return rest;
	}

	/** The catalog's word for how a Flint setting is edited. */
	static String kindOf(SettingDescriptor<?> setting) {
		return switch (setting) {
			case com.flintclient.client.control.model.BoolSetting b -> "toggle";
			case com.flintclient.client.control.model.IntSetting i -> "slider";
			case com.flintclient.client.control.model.FloatSetting f -> "slider";
			case com.flintclient.client.control.model.RgbSetting r -> "color";
			case com.flintclient.client.control.model.ColorSetting c -> "color";
			case com.flintclient.client.control.model.ChoiceSetting<?> c -> "choice";
			case com.flintclient.client.control.model.OptionSetting o -> "choice";
			case com.flintclient.client.control.model.KeySetting k -> "key";
			case com.flintclient.client.control.model.TextSetting t -> "text";
			default -> "list";
		};
	}
}
