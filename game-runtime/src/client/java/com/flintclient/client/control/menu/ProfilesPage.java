package com.flintclient.client.control.menu;

import static com.flintclient.client.control.menu.MenuStyle.r;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.menu.MenuStyle.Label;
import com.flintclient.client.control.menu.MenuStyle.Type;
import com.flintclient.client.control.store.LayoutInfo;
import com.flintclient.client.control.store.LayoutResult;
import com.flintclient.client.control.store.LayoutStore;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FontFace;
import com.flintclient.client.render.Icon;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.kit.KitScreen;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.client.ui.kit.Pressable;
import com.flintclient.client.ui.kit.ScrollView;
import com.flintclient.client.ui.kit.TextField;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * The PROFILES tab: every profile (a HUD layout: each module's on/off state, settings and place) as a card in a grid,
 * the one in use marked. A card's buttons use it, rename it in place (Enter keeps, Escape cancels), duplicate it, copy
 * it to the clipboard as text, and delete it (a second press within a few seconds confirms; never the one in use or the
 * first profile). Under the grid: a name field with "Create profile" (a copy of the one in use, switched to) and "Import"
 * (a new profile from one copied to the clipboard). Names are checked as they are typed (empty, too long, taken) and the
 * reason shows. Everything goes through Flint's layout store, so files and formats are the same as before.
 */
final class ProfilesPage extends Node {
	private static final String DEFAULT_ID = "default";
	private static final long CONFIRM_MILLIS = 4000;
	private static final float CARD_H = 250;
	private static final float GAP = 28;
	private static final float FORM_H = 76;
	private static final float INTRO = 80;

	private final ScrollView scroll;
	private final Grid grid = new Grid();
	private final TextField newName = new TextField("New profile name", 32);
	private final PageControls.TextButton create;
	private final PageControls.TextButton importButton;
	private final TextField rename = new TextField("Profile name", 32);
	private final Label intro = new Label(Type.SENTENCE,
		"A profile keeps every module's on/off state, settings and HUD place. Switch any time; changes save as you make them.");
	private final Label message = new Label(Type.ROW_NOTE, "");
	private boolean messageIsError;
	private @Nullable String renaming;
	private @Nullable String confirmDelete;
	private long confirmSince;
	private int builtSwitches = -1;
	private int builtCount = -1;
	private @Nullable FontFace fieldFace;

	ProfilesPage() {
		scroll = add(new ScrollView(grid));
		newName.name("New profile name");
		newName.onChange(text -> checkNewName());
		newName.onSubmit(text -> createProfile());
		create = add(new PageControls.TextButton("Create profile", Icon.PLUS, PageControls.Look.ACCENT, this::createProfile));
		create.tooltip("A copy of the profile in use, under the new name; Flint switches to it");
		importButton = add(new PageControls.TextButton("Import", Icon.COPY, PageControls.Look.PLAIN, this::importClipboard));
		importButton.tooltip("A new profile from one copied to the clipboard (Copy on a profile card)");
		add(newName);
		rename.onSubmit(this::commitRename);
		rename.onEscape(this::cancelRename);
		rename.name("Profile name");
		rename.setVisible(false);
		add(rename);
		rebuild();
	}

	private static LayoutStore store() {
		return ControlState.layouts();
	}

	/** Reads the profiles again (after a change here, or when the layout was switched elsewhere). */
	void rebuild() {
		List<LayoutInfo> list = store().list();
		grid.clearChildren();
		for (LayoutInfo info : list) {
			grid.add(new Card(info));
		}
		builtSwitches = store().switchCount();
		builtCount = list.size();
		invalidate();
	}

	private void say(@Nullable String text, boolean error) {
		message.set(text == null ? "" : text);
		messageIsError = error;
		invalidate();
	}

	// ---------- actions ----------

	private void checkNewName() {
		String name = newName.text().strip();
		if (name.isEmpty()) {
			say("", false);
			create.setEnabled(true);
			return;
		}
		String error = store().nameError(name, null);
		say(error, error != null);
		if (error == null) {
			create.setEnabled(true);
		} else {
			create.setDisabled(error);
		}
	}

	/** Create profile: a copy of the one in use under the typed name (or "<name> copy"), switched to. */
	private void createProfile() {
		String typed = newName.text().strip();
		String name = typed.isEmpty() ? store().uniqueName(ControlState.layout().name() + " copy") : typed;
		LayoutResult made = store().create(name, null, null);
		if (!made.succeeded() || made.id() == null) {
			say(made.error(), true);
			return;
		}
		LayoutResult switched = store().switchTo(made.id());
		newName.setText("");
		create.setEnabled(true);
		rebuild();
		say(switched.succeeded() ? "Created \"" + ControlState.layout().name() + "\" and switched to it." : switched.error(), !switched.succeeded());
	}

	private void importClipboard() {
		LayoutResult made = store().importText(Minecraft.getInstance().keyboardHandler.getClipboard());
		if (!made.succeeded() || made.id() == null) {
			say(made.error(), true);
			return;
		}
		LayoutResult switched = store().switchTo(made.id());
		rebuild();
		say(switched.succeeded() ? "Imported \"" + ControlState.layout().name() + "\" and switched to it." : switched.error(), !switched.succeeded());
	}

	private void use(LayoutInfo info) {
		LayoutResult result = store().switchTo(info.id());
		rebuild();
		say(result.succeeded() ? "Now using \"" + info.name() + "\"." : result.error(), !result.succeeded());
	}

	private void duplicate(LayoutInfo info) {
		LayoutResult made = store().duplicate(info.id());
		rebuild();
		say(made.succeeded() ? "Duplicated \"" + info.name() + "\"." : made.error(), !made.succeeded());
	}

	private void copy(LayoutInfo info) {
		String text = store().exportText(info.id());
		if (text == null) {
			say("This profile couldn't be read, so it wasn't copied.", true);
			return;
		}
		Minecraft.getInstance().keyboardHandler.setClipboard(text);
		say("Copied \"" + info.name() + "\" to the clipboard. Import it here or on another computer.", false);
	}

	private void askDelete(LayoutInfo info) {
		if (info.id().equals(confirmDelete)) {
			confirmDelete = null;
			LayoutResult result = store().delete(info.id());
			rebuild();
			say(result.succeeded() ? "Deleted \"" + info.name() + "\"." : result.error(), !result.succeeded());
			return;
		}
		confirmDelete = info.id();
		confirmSince = Util.getMillis();
		say("Press Delete again to delete \"" + info.name() + "\". This can't be undone.", true);
	}

	private void startRename(LayoutInfo info) {
		renaming = info.id();
		rename.setText(info.name());
		rename.setVisible(true);
		KitScreen kit = screen();
		if (kit != null) {
			kit.focus(rename);
		}
		invalidate();
	}

	private void commitRename(String text) {
		String id = renaming;
		if (id == null) {
			return;
		}
		LayoutResult result = store().rename(id, text);
		if (!result.succeeded()) {
			say(result.error(), true);
			return;
		}
		cancelRename();
		rebuild();
		say("Renamed to \"" + text.strip() + "\".", false);
	}

	private void cancelRename() {
		renaming = null;
		rename.setVisible(false);
		KitScreen kit = screen();
		if (kit != null && kit.focused() == rename) {
			kit.focus(null);
		}
		invalidate();
	}

	/** Esc: a rename or a pending delete is dropped first; otherwise the menu's. */
	boolean escape() {
		if (renaming != null) {
			cancelRename();
			return true;
		}
		if (confirmDelete != null) {
			confirmDelete = null;
			say("", false);
			return true;
		}
		return false;
	}

	// ---------- layout ----------

	@Override
	protected void layout() {
		FontFace face = Type.VALUE.face();
		if (face != fieldFace) {
			fieldFace = face;
			TextField.Look look = new TextField.Look(face, MenuStyle.FIELD, MenuStyle.FIELD_EDGE, MenuStyle.SEARCH_EDGE_HOVER, MenuStyle.SEARCH_FOCUS,
				r(14), r(22), 0, 0, MenuStyle.ROW_TEXT, MenuStyle.LABEL, MenuStyle.LABEL);
			newName.look(look);
			rename.look(look);
		}
		float formY = y + h - r(FORM_H) - r(52);
		float bh = r(PageControls.TextButton.H);
		float iw = importButton.prefWidth();
		importButton.setBounds(x + w - iw, formY + (r(FORM_H) - bh) / 2, iw, bh);
		float cw = create.prefWidth();
		create.setBounds(importButton.x() - r(16) - cw, importButton.y(), cw, bh);
		newName.setBounds(x, formY + (r(FORM_H) - r(64)) / 2, Math.max(r(200), create.x() - r(24) - x), r(64));
		scroll.flatBar(MenuStyle.SCROLL_THUMB, r(30), r(120));
		scroll.setBounds(x, y + r(INTRO), w, Math.max(0, formY - r(28) - y - r(INTRO)));
		Card target = null;
		for (int i = 0; i < grid.childCount(); i++) {
			if (grid.child(i) instanceof Card card && card.info.id().equals(renaming)) {
				target = card;
			}
		}
		if (target != null) {
			rename.setBounds(target.x() + r(24), target.y() + r(22), target.width() - r(48), r(64));
		} else if (renaming != null) {
			cancelRename();
		}
	}

	@Override
	protected void paint(Canvas c) {
		if (store().switchCount() != builtSwitches || store().list().size() != builtCount) {
			rebuild();
		}
		if (confirmDelete != null && Util.getMillis() - confirmSince > CONFIRM_MILLIS) {
			confirmDelete = null;
			say("", false);
		}
		MenuStyle.icon(c, Icon.INFO, x + r(15), y + r(INTRO) / 2 - r(10), r(30), MenuStyle.INFO);
		MenuStyle.fitted(c, intro, x + r(44), y + r(INTRO) / 2 - r(10), MenuStyle.SENTENCE_TEXT, w - r(44));
		// The form's card behind the name field and its buttons.
		float formY = y + h - r(FORM_H) - r(52);
		MenuStyle.framed(c, x - r(14), formY - r(14), w + r(28), r(FORM_H) + r(28), r(20), MenuStyle.SECTION_CARD, MenuStyle.SECTION_EDGE);
		if (!message.text().isEmpty()) {
			MenuStyle.fitted(c, message, x + r(6), y + h - r(22), messageIsError ? MenuStyle.DANGER_TEXT : MenuStyle.SENTENCE_TEXT, w - r(12));
		}
	}

	/** Three columns of cards (two when narrow). */
	private static final class Grid extends Node {
		private int columns(float width) {
			return width < r(1300) ? 2 : 3;
		}

		@Override
		public float prefHeight(float width) {
			int n = childCount();
			int cols = columns(width);
			int rows = (n + cols - 1) / cols;
			return rows == 0 ? 0 : rows * r(CARD_H) + (rows - 1) * r(GAP) + r(12);
		}

		@Override
		protected void layout() {
			int cols = columns(w);
			float cw = (w - (cols - 1) * r(GAP)) / cols;
			for (int i = 0; i < childCount(); i++) {
				child(i).setBounds(x + (i % cols) * (cw + r(GAP)), y + (i / cols) * (r(CARD_H) + r(GAP)), cw, r(CARD_H));
			}
		}
	}

	/** One profile: its name, what it holds, and its buttons. A click on the card itself uses it. */
	private final class Card extends Pressable {
		final LayoutInfo info;
		private final Label name;
		private final Label detail;
		private final Label inUse = new Label(Type.BADGE, "IN USE");
		private final Small useButton;
		private final Small renameButton;
		private final Small duplicateButton;
		private final Small copyButton;
		private final @Nullable Small deleteButton;
		private float on = -1;

		Card(LayoutInfo info) {
			super(null);
			this.info = info;
			this.name = new Label(Type.SECTION_TITLE, info.name());
			int n = info.enabledModules();
			String text = (n == 1 ? "1 module on" : n + " modules on") + " · " + info.theme().label() + " style";
			if (info.damaged()) {
				text = "This file couldn't be read; a copy was kept. It can only be deleted.";
			} else if (info.notice() != null) {
				text = info.notice();
			}
			this.detail = new Label(Type.ROW_NOTE, text);
			setAction(() -> {
				if (!info.active() && !info.damaged()) {
					use(info);
				}
			});
			name("Profile " + info.name() + (info.active() ? ", in use" : ""));
			useButton = add(new Small(Icon.CHECK, "Use", () -> use(info)));
			renameButton = add(new Small(Icon.EDIT, "Rename", () -> startRename(info)));
			duplicateButton = add(new Small(Icon.PLUS, "Duplicate", () -> duplicate(info)));
			copyButton = add(new Small(Icon.COPY, "Copy", () -> copy(info)));
			boolean deletable = !info.active() && !DEFAULT_ID.equals(info.id());
			deleteButton = deletable ? add(new Small(Icon.TRASH, "Delete", () -> askDelete(info))) : null;
			useButton.setVisible(!info.active() && !info.damaged());
			if (info.damaged()) {
				renameButton.setDisabled("This file couldn't be read");
				duplicateButton.setDisabled("This file couldn't be read");
				copyButton.setDisabled("This file couldn't be read");
			} else if (info.readOnly()) {
				renameButton.setDisabled("A newer Flint saved this profile, so it can't be changed here");
			}
		}

		@Override
		protected void layout() {
			float bh = r(58);
			float by = y + h - r(22) - bh;
			float at = x + r(22);
			for (Small b : new Small[] {useButton, renameButton, duplicateButton, copyButton, deleteButton}) {
				if (b == null || !b.visible()) {
					continue;
				}
				float bw = b.prefWidth();
				b.setBounds(at, by, bw, bh);
				at += bw + r(10);
			}
		}

		@Override
		protected void paint(Canvas c) {
			on = MenuControls.ease(on, info.active());
			boolean asking = info.id().equals(confirmDelete);
			int fill = Palette.lerp(Palette.lerp(MenuStyle.CARD, MenuStyle.CARD_HOVER, info.active() ? 0 : hover), MenuStyle.PILL, on);
			int edge = asking ? Palette.withAlpha(MenuStyle.DANGER, 0.8F) : Palette.lerp(Palette.lerp(MenuStyle.CARD_EDGE, MenuStyle.CARD_EDGE_HOVER, hover),
				MenuStyle.PILL_EDGE, on);
			MenuStyle.framed(c, x, y, w, h, r(24), fill, edge);
			if (!info.id().equals(renaming)) {
				float nameRoom = w - r(48) - (info.active() ? inUse.width(c) + r(44) : 0);
				MenuStyle.fitted(c, name, x + r(24), y + r(54), MenuStyle.ROW_TEXT, nameRoom);
			}
			if (info.active()) {
				float bw = inUse.width(c) + r(28);
				float bx = x + w - r(22) - bw;
				MenuStyle.box(c, bx, y + r(36), bw, r(36), r(10), MenuStyle.BADGE);
				MenuStyle.text(c, inUse, bx + r(14), y + r(54), MenuStyle.BADGE_TEXT);
			}
			MenuStyle.fitted(c, detail, x + r(24), y + r(104), info.damaged() || info.notice() != null ? MenuStyle.WARN_TEXT : MenuStyle.LABEL,
				w - r(48));
			MenuStyle.focusRing(c, x, y, w, h, r(24), focus);
		}
	}

	/** A small button on a card: an icon and a word; Delete turns red while it asks. */
	private final class Small extends Pressable {
		private final Icon icon;
		private final Label label;

		Small(Icon icon, String text, Runnable action) {
			super(action);
			this.icon = icon;
			this.label = new Label(Type.PAGE_BUTTON, text);
			name(text);
		}

		@Override
		public float prefWidth() {
			return r(18) + r(26) + r(10) + label.width(measure()) + r(20);
		}

		@Override
		protected void paint(Canvas c) {
			boolean danger = icon == Icon.TRASH && parent() instanceof Card card && card.info.id().equals(confirmDelete);
			int idle = danger ? Palette.withAlpha(MenuStyle.DANGER, 0.30F) : MenuStyle.CHIP;
			int fill = Palette.lerp(Palette.lerp(idle, danger ? Palette.withAlpha(MenuStyle.DANGER, 0.42F) : MenuStyle.CHIP_HOVER, hover), 0xFF000000,
				press * 0.18F);
			MenuStyle.box(c, x, y, w, h, r(14), fill);
			int ink = danger ? MenuStyle.DANGER_TEXT : Palette.lerp(MenuStyle.CHIP_TEXT, MenuStyle.TAB_TEXT_SELECTED, hover);
			if (!enabled()) {
				ink = Palette.withAlpha(ink, 0.4F);
			}
			MenuStyle.icon(c, icon, x + r(18) + r(13), y + h / 2, r(26), ink);
			MenuStyle.text(c, label, x + r(18) + r(26) + r(10), y + h / 2, ink);
			MenuStyle.focusRing(c, x, y, w, h, r(14), focus);
		}
	}
}
