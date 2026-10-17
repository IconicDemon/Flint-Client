package com.flintclient.client.control.menu;

import static com.flintclient.client.control.menu.MenuStyle.r;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.feature.Features;
import com.flintclient.client.control.feature.PackFolders;
import com.flintclient.client.control.menu.MenuStyle.Label;
import com.flintclient.client.control.menu.MenuStyle.Type;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.UtilityMods.PackOrganizer;
import com.flintclient.client.control.platform.Mc263Packs;
import com.flintclient.client.control.platform.Mc263Packs.PackInfo;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FontFace;
import com.flintclient.client.render.Icon;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.kit.KitScreen;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.client.ui.kit.Pressable;
import com.flintclient.client.ui.kit.SearchField;
import com.flintclient.client.ui.kit.TextField;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Pack Organizer's page body: every installed resource pack, grouped in Flint's folders (then Unsorted), with a search,
 * a way to make, rename and delete folders, each pack's folder, and its on/off switch. Switches only mark a change; Apply
 * turns the packs on or off through Minecraft (which reloads resources), Discard forgets the changes. Folders are labels
 * ({@link PackFolders}): moving a pack between them changes nothing in Minecraft.
 */
final class PacksPanel extends Node {
	private static final float BAR = 64;
	private static final float HEAD_H = 70;
	private static final float ROW_H = 84;

	private final SearchField search = add(new SearchField("Search packs", 60));
	private final PageControls.TextButton newFolder;
	private final PageControls.TextButton apply;
	private final PageControls.TextButton discard;
	private final Label pending = new Label(Type.ROW_NOTE, "");
	private final Label empty = new Label(Type.EMPTY_NOTE, "");
	private final List<Node> items = new ArrayList<>();
	private final Map<String, Boolean> changes = new LinkedHashMap<>();
	private List<PackInfo> packs = List.of();
	private @Nullable FontFace searchFace;
	private String builtFor = "\0";
	private String deleteAsked = "";
	private long deleteAskedAt;

	PacksPanel() {
		newFolder = add(new PageControls.TextButton("New folder", Icon.PLUS, PageControls.Look.PLAIN, () -> {
			Features.packFolders().create("New folder");
			builtFor = "\0";
		}));
		apply = add(new PageControls.TextButton("Apply", Icon.CHECK, PageControls.Look.ACCENT, this::applyChanges));
		discard = add(new PageControls.TextButton("Discard", null, PageControls.Look.PLAIN, () -> {
			changes.clear();
			builtFor = "\0";
		}));
		search.name("Search packs");
		search.onChange(text -> builtFor = "\0");
		reload();
	}

	private static ModuleState module() {
		return ControlState.layout().module(PackOrganizer.ID);
	}

	private void reload() {
		packs = Mc263Packs.packs();
		builtFor = "\0";
	}

	private void applyChanges() {
		if (!changes.isEmpty()) {
			Mc263Packs.apply(new LinkedHashMap<>(changes));
			changes.clear();
			reload();
		}
	}

	private boolean on(PackInfo pack) {
		return changes.getOrDefault(pack.id(), pack.selected());
	}

	private void toggle(PackInfo pack) {
		boolean want = !on(pack);
		if (want == pack.selected()) {
			changes.remove(pack.id());
		} else {
			changes.put(pack.id(), want);
		}
	}

	/** What the list depends on; the rows are rebuilt when it changes (and never while a folder name is being typed). */
	private String signature() {
		ModuleState m = module();
		return Features.packFolders().revision() + "|" + search.text() + "|" + m.bool(PackOrganizer.SHOW_INCOMPATIBLE) + "|"
			+ m.option(PackOrganizer.SORT) + "|" + packs.size() + "|" + changes.hashCode();
	}

	private boolean editing() {
		KitScreen screen = screen();
		if (screen == null || screen.focused() == null) {
			return false;
		}
		for (Node n = screen.focused(); n != null; n = n.parent()) {
			if (n instanceof FolderHead) {
				return true;
			}
		}
		return false;
	}

	private void rebuild() {
		builtFor = signature();
		for (Node item : items) {
			remove(item);
		}
		items.clear();
		ModuleState m = module();
		boolean incompatible = m.bool(PackOrganizer.SHOW_INCOMPATIBLE);
		boolean onFirst = m.option(PackOrganizer.SORT) == 1;
		String query = search.text();
		PackFolders folders = Features.packFolders();
		List<PackInfo> shown = new ArrayList<>();
		for (PackInfo pack : packs) {
			if ((incompatible || pack.compatible()) && PackFolders.matches(query, pack.title(), pack.description())) {
				shown.add(pack);
			}
		}
		Comparator<PackInfo> byName = Comparator.comparing(PackInfo::title, String.CASE_INSENSITIVE_ORDER);
		shown.sort(onFirst ? Comparator.comparing((PackInfo p) -> !on(p)).thenComparing(byName) : byName);
		List<String> groups = new ArrayList<>(folders.folders());
		groups.add(null);
		for (String group : groups) {
			List<PackInfo> inGroup = new ArrayList<>();
			for (PackInfo pack : shown) {
				if (Objects.equals(folders.folderOf(pack.id()), group)) {
					inGroup.add(pack);
				}
			}
			if (group == null && inGroup.isEmpty() && !folders.folders().isEmpty()) {
				continue;
			}
			items.add(add(new FolderHead(group, inGroup.size())));
			for (PackInfo pack : inGroup) {
				items.add(add(new PackRow(pack)));
			}
		}
		invalidate();
	}

	@Override
	public float prefHeight(float width) {
		float total = r(BAR) + r(20);
		for (Node item : items) {
			total += item instanceof FolderHead ? r(HEAD_H) : r(ROW_H);
		}
		return total + (changes.isEmpty() ? 0 : r(BAR) + r(20)) + r(20);
	}

	@Override
	protected void layout() {
		float bar = r(BAR);
		float at = x + w;
		at -= newFolder.prefWidth();
		newFolder.setBounds(at, y, newFolder.prefWidth(), bar);
		search.setBounds(x, y, Math.max(r(200), at - r(16) - x), bar);
		FontFace face = Type.SEARCH.face();
		if (face != searchFace) {
			searchFace = face;
			search.look(new TextField.Look(face, MenuStyle.SEARCH, MenuStyle.SEARCH_EDGE, MenuStyle.SEARCH_EDGE_HOVER, MenuStyle.SEARCH_FOCUS, r(15),
				r(24), r(30), r(18), MenuStyle.SEARCH_TEXT, MenuStyle.SEARCH_HINT, MenuStyle.SEARCH_HINT));
		}
		float top = y + bar + r(20);
		for (Node item : items) {
			float ih = item instanceof FolderHead ? r(HEAD_H) : r(ROW_H);
			item.setBounds(x, top, w, ih - r(8));
			top += ih;
		}
		boolean any = !changes.isEmpty();
		apply.setVisible(any);
		discard.setVisible(any);
		if (any) {
			float by = top + r(10);
			apply.setBounds(x + w - apply.prefWidth(), by, apply.prefWidth(), bar);
			discard.setBounds(apply.x() - r(12) - discard.prefWidth(), by, discard.prefWidth(), bar);
		}
	}

	@Override
	protected void paint(Canvas c) {
		if (!signature().equals(builtFor) && !editing()) {
			rebuild();
		}
		if (!changes.isEmpty()) {
			int n = changes.size();
			pending.set(n + (n == 1 ? " pack change" : " pack changes") + " waiting · Apply reloads resources");
			MenuStyle.fitted(c, pending, x + r(8), apply.y() + apply.height() / 2, MenuStyle.ACCENT, discard.x() - x - r(24));
		}
		if (items.isEmpty()) {
			empty.set(packs.isEmpty() ? "No resource packs found" : "No pack matches the search");
			MenuStyle.text(c, empty, x + r(8), y + r(BAR) + r(60), MenuStyle.EMPTY_NOTE);
		}
	}

	/** A folder's header: its name (renamed in place), how many packs, and delete (press twice). Unsorted can't be changed. */
	private final class FolderHead extends Node {
		private final @Nullable String folder;
		private final Label title = new Label(Type.SECTION_TITLE, "");
		private final Label count = new Label(Type.ROW_NOTE, "");
		private final @Nullable TextField name;
		private final @Nullable Bin bin;
		private @Nullable FontFace face;

		FolderHead(@Nullable String folder, int packs) {
			this.folder = folder;
			title.set(folder == null ? "Unsorted" : folder);
			count.set(packs == 1 ? "1 pack" : packs + " packs");
			if (folder != null) {
				name = add(new TextField("Folder name", PackFolders.MAX_NAME));
				name.setText(folder);
				name.name("Rename folder " + folder);
				name.onSubmit(text -> {
					if (!Features.packFolders().rename(folder, text)) {
						name.setText(folder);
					}
					KitScreen screen = screen();
					if (screen != null) {
						screen.focus(null);
					}
				});
				name.onEscape(() -> {
					name.setText(folder);
					KitScreen screen = screen();
					if (screen != null) {
						screen.focus(null);
					}
				});
				bin = add(new Bin(folder));
			} else {
				name = null;
				bin = null;
			}
		}

		@Override
		protected void layout() {
			FontFace now = Type.VALUE.face();
			TextField field = name;
			if (field != null && now != face) {
				face = now;
				field.look(new TextField.Look(now, MenuStyle.FIELD, MenuStyle.FIELD_EDGE, MenuStyle.SEARCH_EDGE_HOVER, MenuStyle.SEARCH_FOCUS, r(12),
					r(16), 0, 0, MenuStyle.ROW_TEXT, MenuStyle.LABEL, MenuStyle.LABEL));
			}
			float cy = y + h / 2;
			Bin delete = bin;
			if (delete != null) {
				delete.setBounds(x + w - r(52), cy - r(26), r(52), r(52));
			}
			if (field != null) {
				field.setBounds(x, cy - r(26), Math.min(r(420), w / 2), r(52));
			}
		}

		@Override
		protected void paint(Canvas c) {
			float cy = y + h / 2;
			if (name == null) {
				MenuStyle.icon(c, Icon.FOLDER, x + r(16), cy, r(28), MenuStyle.LABEL);
				MenuStyle.text(c, title, x + r(44), cy, MenuStyle.ROW_TEXT);
				MenuStyle.text(c, count, x + r(44) + title.width(c) + r(16), cy, MenuStyle.LABEL);
			} else {
				MenuStyle.text(c, count, name.x() + name.width() + r(16), cy, MenuStyle.LABEL);
			}
		}

		/** Delete the folder (its packs go to Unsorted), after a second press within three seconds. */
		private final class Bin extends Pressable {
			private final String folder;

			Bin(String folder) {
				super(null);
				this.folder = folder;
				setAction(() -> {
					if (deleteAsked.equals(folder) && System.currentTimeMillis() - deleteAskedAt < 3000) {
						deleteAsked = "";
						Features.packFolders().remove(folder);
					} else {
						deleteAsked = folder;
						deleteAskedAt = System.currentTimeMillis();
					}
				});
				name("Delete folder " + folder);
				tooltip("Delete folder (press twice); its packs go to Unsorted");
			}

			@Override
			protected void paint(Canvas c) {
				boolean asking = deleteAsked.equals(folder) && System.currentTimeMillis() - deleteAskedAt < 3000;
				MenuStyle.box(c, x, y, w, h, r(12), asking ? Palette.withAlpha(MenuStyle.DANGER, 0.35F) : Palette.lerp(MenuStyle.CHIP,
					MenuStyle.CHIP_HOVER, hover));
				MenuStyle.icon(c, Icon.TRASH, x + w / 2, y + h / 2, r(26), asking ? MenuStyle.DANGER_TEXT : MenuStyle.CHIP_TEXT);
				MenuStyle.focusRing(c, x, y, w, h, r(12), focus);
			}
		}
	}

	/** One pack: on/off (waiting for Apply), its name and description, a note when it's made for another version, its folder. */
	private final class PackRow extends Node {
		private final PackInfo pack;
		private final PageControls.Switch toggle;
		private final PageControls.PickField folder;
		private final Label title = new Label(Type.ROW_LABEL, "");
		private final Label description = new Label(Type.ROW_NOTE, "");
		private final Label badge = new Label(Type.BADGE, "");

		PackRow(PackInfo pack) {
			this.pack = pack;
			toggle = add(new PageControls.Switch(() -> on(pack), () -> {
				toggle(pack);
				invalidate();
			}));
			toggle.name((pack.selected() ? "Turn off " : "Turn on ") + pack.title());
			if (pack.required()) {
				toggle.setDisabled("Minecraft needs this pack");
			}
			folder = add(new PageControls.PickField(() -> {
				String f = Features.packFolders().folderOf(pack.id());
				return f == null ? "Unsorted" : f;
			}, () -> {
				PackFolders folders = Features.packFolders();
				folders.move(pack.id(), folders.next(folders.folderOf(pack.id())));
			}));
			folder.name("Folder of " + pack.title());
			folder.tooltip("Press to move it to the next folder");
			title.set(pack.title());
			description.set(pack.description());
			badge.set(pack.compatible() ? "" : pack.compatibility());
		}

		@Override
		protected void layout() {
			float cy = y + h / 2;
			toggle.setBounds(x + r(20), cy - r(PageControls.Switch.H) / 2, r(PageControls.Switch.W), r(PageControls.Switch.H));
			float fw = Math.min(r(300), w * 0.28F);
			folder.setBounds(x + w - r(20) - fw, cy - r(26), fw, r(52));
		}

		@Override
		protected void paint(Canvas c) {
			boolean changed = changes.containsKey(pack.id());
			MenuStyle.framed(c, x, y, w, h, r(16), MenuStyle.SECTION_CARD, changed ? Palette.withAlpha(MenuStyle.ACCENT, 0.7F) : MenuStyle.SECTION_EDGE);
			float left = toggle.x() + toggle.width() + r(22);
			float room = folder.x() - r(20) - left;
			float cy = y + h / 2;
			MenuStyle.fitted(c, title, left, cy - r(13), pack.compatible() ? MenuStyle.ROW_TEXT : MenuStyle.LABEL, room);
			if (!pack.compatible()) {
				MenuStyle.fitted(c, badge, left, cy + r(16), MenuStyle.DANGER_TEXT, room);
			} else {
				MenuStyle.fitted(c, description, left, cy + r(16), MenuStyle.LABEL, room);
			}
		}
	}
}
