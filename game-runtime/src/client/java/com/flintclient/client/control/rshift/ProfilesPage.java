package com.flintclient.client.control.rshift;

import static com.flintclient.client.control.rshift.MenuStyle.d;
import static com.flintclient.client.control.rshift.MenuStyle.r;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.model.Layout;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.rshift.MenuStyle.Label;
import com.flintclient.client.control.rshift.MenuStyle.Type;
import com.flintclient.client.control.rshift.PageControls.TextButton;
import com.flintclient.client.control.store.LayoutInfo;
import com.flintclient.client.control.store.LayoutResult;
import com.flintclient.client.control.store.LayoutStore;
import com.flintclient.client.control.store.ProfileShareClient;
import com.flintclient.client.control.store.ShareCodes;
import com.flintclient.client.control.store.ShortCodes;
import com.flintclient.client.platform.Clock;
import com.flintclient.client.platform.Services;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FontFace;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.kit.KitScreen;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.client.ui.kit.Pressable;
import com.flintclient.client.ui.kit.ScrollView;
import com.flintclient.client.ui.kit.TextField;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Profiles: a set of mods, their settings and the HUD for each way the player plays (PvP, SMP...). Under the title, a new
 * profile's name with Create Profile (checked as it's typed; a new profile starts with every mod off) and a profile code
 * with Import; one line under them says what the last action did. Then the profiles as cards, three across as Dawn lays
 * them out, the one in use first: its name and how many mods it has on, the names of those mods as small chips, and at the
 * bottom its main action (Use, or the green In use) with Share, Clone, Rename and Delete as one row of icon buttons
 * (Rename edits the name in place: Save · Cancel; Delete asks on the card first; the one in use can't be deleted). The
 * last card makes a new profile. Share copies a new 6-character code ({@code K7QX3M}, kept 30 days by Flint's servers) to the
 * clipboard to send to a friend, and says it; without the servers it copies the long code ({@code FLINT-...}, the profile
 * itself) and says why. Typed or pasted under Import, either makes the same profile on their Flint and switches to it. A
 * profile is Flint's layout, in {@code config/flintclient/layouts/<id>.json}.
 */
final class ProfilesPage extends Node {
	private static final float PAD = 40;
	private static final float HEADER = 150;
	/** Design pixels: the toolbar's gap under the header, the status line, and the cards. */
	private static final float TOOLBAR_GAP = 18;
	private static final float STATUS = 34;
	private static final float CARD_H = 176;
	private static final float CARD_GAP = 14;
	private static final long CONFIRM_MILLIS = 6000;
	private static final long COPIED_MILLIS = 1600;

	private final Label title = new Label(Type.PAGE_TITLE, "Profiles");
	private final Label about = new Label(Type.EMPTY_NOTE, "A set of mods, settings and HUD for each way you play: PvP, SMP, building. Share one as a code.");
	private final Label message = new Label(Type.ROW_NOTE, "");
	private final NameField code;
	private final TextButton importButton;
	private final NameField name;
	private final TextButton create;
	private final Grid grid = new Grid();
	private final ScrollView scroll;
	private boolean messageIsError;
	private boolean messageWorking;
	private long messageAt;
	/** The latest Share and Import waiting on Flint's servers: an answer for an older one is dropped. */
	private int shareTicket;
	private int importTicket;

	ProfilesPage() {
		name = add(new NameField("New profile name"));
		name.name("Profile name");
		name.onChange(text -> checkName());
		name.onSubmit(text -> create());
		create = add(new TextButton("Create Profile", Glyph.PLUS, PageControls.Look.ACCENT, this::create));
		create.tooltip("A new profile, with every mod off, and switches to it");
		code = add(new NameField("Type or paste a profile code", ShareCodes.MAX_CODE_CHARS));
		code.name("Profile code to import");
		code.onSubmit(text -> importCode());
		importButton = add(new TextButton("Import", Glyph.DOWNLOAD, PageControls.Look.PLAIN, this::importCode));
		importButton.tooltip("Makes the profile a friend shared with you, and switches to it (the clipboard's code when the field is empty)");
		scroll = add(new ScrollView(grid));
		rebuild();
	}

	private static LayoutStore store() {
		return ControlState.layouts();
	}

	private void say(@Nullable String text, boolean error) {
		message.set(text == null ? "" : text);
		messageIsError = error;
		messageWorking = false;
		messageAt = Clock.millis();
	}

	private void checkName() {
		String typed = name.text().strip();
		String error = typed.isEmpty() ? null : store().nameError(typed, null);
		say(error, error != null);
		create.setEnabled(error == null);
	}

	/** The name typed (or, with none, the field focused so one can be). */
	private void create() {
		String typed = name.text().strip();
		if (typed.isEmpty()) {
			say("Type a name for the new profile first.", true);
			KitScreen screen = screen();
			if (screen != null) {
				screen.focus(name);
			}
			return;
		}
		LayoutResult made = store().createBlank(typed);
		if (!made.succeeded()) {
			say(made.error(), true);
			return;
		}
		LayoutResult switched = store().switchTo(made.id());
		name.setText("");
		say(switched.succeeded() ? "Created \"" + ControlState.layout().name() + "\" with every mod off, and switched to it." : switched.error(),
			!switched.succeeded());
		rebuild();
	}

	/** A line that something is on its way (an hourglass instead of a tick). */
	private void sayWorking(String text) {
		say(text, false);
		messageWorking = true;
	}

	/**
	 * A new code for {@code info}, on the clipboard; {@code copied} runs once one is. With Flint's servers it is a 6-character
	 * code (asked for off the game's thread; "Making a code..." meanwhile); without them, or when they fail, the long code,
	 * with a line that says why.
	 */
	private void share(LayoutInfo info, Runnable copied) {
		ProfileShareClient client = ProfileShareClient.get();
		String payload = client.configured() ? store().sharePayload(info.id()) : null;
		if (payload == null) {
			copyLongCode(info, copied, client.configured() ? null : "This Flint has no short codes, so a long code");
			return;
		}
		int ticket = ++shareTicket;
		sayWorking("Making a code for \"" + info.name() + "\"...");
		client.create(payload).whenComplete((result, error) -> Services.onRenderThread(() -> {
			if (ticket != shareTicket) {
				// A newer Share took over.
				return;
			}
			if (result != null && result.ok()) {
				Services.setClipboard(result.value());
				say("Code " + result.value() + " copied. Send it to a friend; they type it under Import. It works for 30 days.", false);
				copied.run();
				return;
			}
			ProfileShareClient.Status status = result == null ? ProfileShareClient.Status.FAILED : result.status();
			copyLongCode(info, copied, switch (status) {
				case OFFLINE -> "Flint's servers couldn't be reached, so a long code";
				case BUSY -> "Short codes are busy right now, so a long code";
				case REFUSED -> "This profile is too big for a short code, so a long code";
				default -> "Short codes aren't available right now, so a long code";
			});
		}));
	}

	/** The long code for {@code info} on the clipboard; {@code why} (or null) starts the line that says so. */
	private void copyLongCode(LayoutInfo info, Runnable copied, @Nullable String why) {
		String text = store().shareCode(info.id());
		if (text == null) {
			say("Flint couldn't read \"" + info.name() + "\".", true);
			return;
		}
		Services.setClipboard(text);
		say(why == null ? "Code for \"" + info.name() + "\" copied. Send it to a friend; they paste it under Import."
			: why + " for \"" + info.name() + "\" was copied. It works the same: they paste it under Import.", false);
		copied.run();
	}

	/**
	 * The typed code (or the clipboard's, when the field is empty) as a new profile, switched to. A 6-character code is
	 * fetched from Flint's servers first ("Fetching..." meanwhile), then read exactly as a long code's profile.
	 */
	private void importCode() {
		String typed = code.text().strip();
		String text = typed.isEmpty() ? Services.clipboard() : typed;
		String shortCode = ShortCodes.parse(text);
		if (shortCode != null) {
			fetch(shortCode);
			return;
		}
		imported(store().importCode(text));
	}

	private void fetch(String shortCode) {
		ProfileShareClient client = ProfileShareClient.get();
		if (!client.configured()) {
			say("This Flint can't read short codes (it has no connection to Flint's servers). Ask for a long FLINT- code.", true);
			return;
		}
		int ticket = ++importTicket;
		sayWorking("Fetching " + shortCode + "...");
		client.fetch(shortCode).whenComplete((result, error) -> Services.onRenderThread(() -> {
			if (ticket != importTicket) {
				return;
			}
			ProfileShareClient.Status status = result == null ? ProfileShareClient.Status.FAILED : result.status();
			if (status == ProfileShareClient.Status.OK && result.value() != null) {
				// Untrusted: read exactly as a long code's profile (size cap, structure, schema, name cleaned).
				imported(store().importText(result.value()));
				return;
			}
			say(switch (status) {
				case NOT_FOUND -> "Code " + shortCode + " not found or expired. Codes work for 30 days.";
				case OFFLINE -> "Flint's servers couldn't be reached. Check your connection and try again.";
				case BUSY -> "Too many tries right now. Wait a minute and try again.";
				default -> "Flint couldn't fetch " + shortCode + " right now. Try again later.";
			}, true);
		}));
	}

	/** What an import did: the new profile switched to, or why there is none. */
	private void imported(LayoutResult made) {
		if (!made.succeeded()) {
			say(made.error(), true);
			return;
		}
		code.setText("");
		LayoutResult switched = store().switchTo(made.id());
		say(switched.succeeded() ? "Imported \"" + ControlState.layout().name() + "\" and switched to it." : switched.error(), !switched.succeeded());
		rebuild();
	}

	private void use(LayoutInfo info) {
		LayoutResult result = store().switchTo(info.id());
		say(result.succeeded() ? "Now using \"" + info.name() + "\"." : result.error(), !result.succeeded());
		rebuild();
	}

	private void cloneProfile(LayoutInfo info) {
		LayoutResult made = store().duplicate(info.id());
		say(made.succeeded() ? "Cloned \"" + info.name() + "\"." : made.error(), !made.succeeded());
		rebuild();
	}

	private void delete(LayoutInfo info) {
		LayoutResult result = store().delete(info.id());
		say(result.succeeded() ? "Deleted \"" + info.name() + "\"." : result.error(), !result.succeeded());
		rebuild();
	}

	private boolean rename(LayoutInfo info, String text) {
		LayoutResult result = store().rename(info.id(), text.strip());
		if (!result.succeeded()) {
			say(result.error(), true);
			return false;
		}
		say("Renamed to \"" + text.strip() + "\".", false);
		rebuild();
		return true;
	}

	/** The cards again from the store (after any change): the one in use first, then by name, then the new-profile card. */
	private void rebuild() {
		grid.clearChildren();
		List<LayoutInfo> all = store().list();
		for (LayoutInfo info : all) {
			if (info.active()) {
				grid.add(new Item(info, all.size()));
			}
		}
		for (LayoutInfo info : all) {
			if (!info.active()) {
				grid.add(new Item(info, all.size()));
			}
		}
		grid.add(new NewCard(all.size() <= 1));
		invalidate();
	}

	@Override
	protected void layout() {
		float left = x + r(PAD);
		float right = x + w - r(PAD);
		float bh = r(TextButton.H);
		float top = y + r(HEADER) + d(TOOLBAR_GAP);
		float half = (right - left - d(24)) / 2;
		float cw = create.prefWidth();
		name.setBounds(left, top, Math.max(d(120), half - cw - d(10)), bh);
		create.setBounds(name.right() + d(10), top, cw, bh);
		float iw = importButton.prefWidth();
		importButton.setBounds(right - iw, top, iw, bh);
		float codeX = left + half + d(24);
		code.setBounds(codeX, top, Math.max(d(120), importButton.x() - d(10) - codeX), bh);
		float listY = top + bh + d(STATUS);
		scroll.flatBar(MenuStyle.SCROLL_THUMB, r(30), r(120));
		scroll.setBounds(x, listY, w, Math.max(0, y + h - listY));
	}

	@Override
	protected void paint(Canvas c) {
		float bar = r(HEADER);
		float left = x + r(PAD);
		float text = MenuStyle.titleBlock(c, left, y, x + w - r(PAD), bar);
		MenuStyle.fitted(c, title, text, y + bar / 2 - r(15), MenuStyle.TAB_TEXT_SELECTED, x + w - r(PAD) - r(40) - text);
		MenuStyle.fitted(c, about, text, y + bar / 2 + r(22), MenuStyle.INFO, x + w - r(PAD) - r(40) - text);
		c.fill(c.px(left), c.px(y + bar) - MenuStyle.edge(), c.px(x + w - r(PAD)), c.px(y + bar), MenuStyle.HEADER_RULE);
		if (!message.text().isEmpty()) {
			// What the last action did: a green tick or a red mark, then the words; it fades in.
			float k = MenuStyle.appear(messageAt, 180);
			float cy = name.bottom() + d(STATUS) / 2;
			int ink = messageIsError ? MenuStyle.DANGER_TEXT : MenuStyle.SENTENCE_TEXT;
			int mark = messageIsError ? MenuStyle.DANGER : messageWorking ? MenuStyle.LABEL : MenuStyle.SUCCESS_TEXT;
			Glyph glyph = messageIsError ? Glyph.INFO : messageWorking ? Glyph.HOURGLASS : Glyph.CHECK;
			glyph.draw(c, left + d(8), cy, d(13), Palette.withAlpha(mark, k));
			MenuStyle.fitted(c, message, left + d(24), cy, Palette.withAlpha(ink, k), w - 2 * r(PAD) - d(24));
		}
	}

	/** The cards, three across (two or one on a narrow window), each the same height, with a gap. */
	private static final class Grid extends Node {
		private int columns(float width) {
			return width >= d(840) ? 3 : width >= d(520) ? 2 : 1;
		}

		@Override
		public float prefHeight(float width) {
			int cols = columns(width - 2 * r(PAD));
			int rows = (childCount() + cols - 1) / cols;
			return rows * (d(CARD_H) + d(CARD_GAP)) + d(CARD_GAP);
		}

		@Override
		protected void layout() {
			float inner = w - 2 * r(PAD);
			int cols = columns(inner);
			float cw = (inner - (cols - 1) * d(CARD_GAP)) / cols;
			for (int i = 0; i < childCount(); i++) {
				int col = i % cols;
				int row = i / cols;
				child(i).setBounds(x + r(PAD) + col * (cw + d(CARD_GAP)), y + d(4) + row * (d(CARD_H) + d(CARD_GAP)), cw, d(CARD_H));
			}
		}
	}

	/**
	 * One profile's card (Dawn's 3 px block): a tile with the profiles icon, its name and "n mods on", the first of its mods'
	 * names as chips, then Use (or the green In use) at the bottom left and its icon buttons at the bottom right. Renaming puts a
	 * field over the name with Save and Cancel; Delete asks on the card.
	 */
	private final class Item extends Node {
		private static final float PADDING = 18;
		private static final float TILE = 46;
		private static final float BUTTON = 36;
		private static final int MOST_CHIPS = 8;
		private static final float CHIP_H = 24;
		private static final float CHIP_SIDE = 9;
		private static final float CHIP_GAP = 6;
		/** The chips that fit across (then "+n" for the rest), worked out as the card is laid out. */
		private final List<Label> shownChips = new ArrayList<>();
		private final LayoutInfo info;
		private final Label label;
		private final Label detail;
		private final Label inUse = new Label(Type.CARD_BUTTON, "In use");
		private final Label noMods;
		private final Label more = new Label(Type.CAPTION, "");
		private final Label askDelete = new Label(Type.ROW_NOTE, "Delete this profile?");
		private final List<Label> chips = new ArrayList<>();
		private int total;
		private final @Nullable TextButton use;
		private final IconButton shareButton;
		private final IconButton cloneButton;
		private final IconButton renameButton;
		private final IconButton deleteButton;
		private final NameField field;
		private final TextButton save;
		private final TextButton cancel;
		private final TextButton confirmDelete;
		private final TextButton keep;
		private boolean renaming;
		private long deleteAskedAt;
		private float on = -1;

		Item(LayoutInfo info, int count) {
			this.info = info;
			this.label = new Label(Type.NAME, info.name());
			int n = info.enabledModules();
			this.detail = new Label(Type.ROW_NOTE, (n == 1 ? "1 mod on" : n + " mods on")
				+ (info.notice() != null ? " · " + info.notice() : ""));
			this.noMods = new Label(Type.ROW_NOTE, info.active() ? "No mods on yet" : "Use it to choose its mods");
			collectChips();
			use = info.active() ? null : add(new TextButton("Use", Glyph.PLAY, PageControls.Look.ACCENT, () -> use(info)));
			if (use != null) {
				use.name("Use " + info.name());
			}
			shareButton = add(new IconButton(Glyph.SHARE, "Share", "Copies a code for this profile to send to a friend",
				() -> share(info, this::flashCopied)));
			cloneButton = add(new IconButton(Glyph.COPY, "Clone", "Makes a copy of this profile", () -> cloneProfile(info)));
			renameButton = add(new IconButton(Glyph.PEN, "Rename", "Renames this profile", () -> setRenaming(true)));
			deleteButton = add(new IconButton(Glyph.TRASH, "Delete", "Deletes this profile", this::askDelete).danger());
			for (IconButton b : new IconButton[] {shareButton, cloneButton, renameButton, deleteButton}) {
				b.name(b.word + " " + info.name());
			}
			if (info.active() || count <= 1) {
				deleteButton.setDisabled(count <= 1 ? "You need at least one profile" : "Switch to another profile before deleting this one");
			}
			if (info.readOnly()) {
				renameButton.setDisabled("This profile can't be changed");
			}
			field = add(new NameField(info.name()));
			field.name("New name for " + info.name());
			field.onSubmit(text -> {
				if (rename(info, text)) {
					setRenaming(false);
				}
			});
			field.onEscape(() -> setRenaming(false));
			save = add(new TextButton("Save", null, PageControls.Look.ACCENT, () -> {
				if (rename(info, field.text())) {
					setRenaming(false);
				}
			}));
			cancel = add(new TextButton("Cancel", null, PageControls.Look.PLAIN, () -> setRenaming(false)));
			confirmDelete = add(new TextButton("Delete", Glyph.TRASH, PageControls.Look.DANGER, () -> delete(info)));
			confirmDelete.name("Delete " + info.name() + " for good");
			keep = add(new TextButton("Keep", null, PageControls.Look.PLAIN, () -> setAsking(false)));
			setRenaming(false);
			setAsking(false);
		}

		/** The names of the first mods it has on, in the menu's order. */
		private void collectChips() {
			Layout layout = store().get(info.id());
			if (layout == null) {
				return;
			}
			for (ModuleState module : layout.modules()) {
				if (!module.enabled()) {
					continue;
				}
				Catalog.Mod mod = Catalog.get().modFor(module.id());
				if (mod == null) {
					continue;
				}
				total++;
				if (chips.size() < MOST_CHIPS) {
					chips.add(new Label(Type.CAPTION, mod.name()));
				}
			}
		}

		private void flashCopied() {
			shareButton.flash(Glyph.CHECK, COPIED_MILLIS);
		}

		private void setRenaming(boolean value) {
			renaming = value;
			if (value) {
				setAsking(false);
			}
			field.setVisible(value);
			save.setVisible(value);
			cancel.setVisible(value);
			showActions(!value && deleteAskedAt == 0);
			if (value) {
				field.setText(info.name());
				KitScreen screen = screen();
				if (screen != null) {
					screen.focus(field);
				}
			}
			invalidate();
		}

		private void askDelete() {
			setAsking(true);
		}

		private void setAsking(boolean value) {
			deleteAskedAt = value ? Clock.millis() : 0;
			confirmDelete.setVisible(value);
			keep.setVisible(value);
			showActions(!value && !renaming);
			if (value) {
				KitScreen screen = screen();
				if (screen != null && screen.focusVisible()) {
					screen.focus(keep);
				}
			}
			invalidate();
		}

		private void showActions(boolean value) {
			if (use != null) {
				use.setVisible(value);
			}
			shareButton.setVisible(value);
			cloneButton.setVisible(value);
			renameButton.setVisible(value);
			deleteButton.setVisible(value);
		}

		@Override
		public String narration() {
			return info.name() + (info.active() ? ", in use" : "") + ", " + detail.text();
		}

		/** As many chips as fit in one row (a long name that doesn't is passed over), keeping room for "+n" for the rest. */
		private void fitChips() {
			Canvas c = measure();
			float side = d(CHIP_SIDE);
			float right = x + w - d(PADDING);
			float at = x + d(PADDING);
			shownChips.clear();
			// The "+n" room, at its widest.
			more.set("+" + total);
			float reserve = d(CHIP_GAP) + more.width(c) + 2 * side;
			for (int i = 0; i < chips.size(); i++) {
				float cw = chips.get(i).width(c) + 2 * side;
				boolean last = i == total - 1 && shownChips.size() == i;
				if (at + cw + (last ? 0 : reserve) > right) {
					continue;
				}
				shownChips.add(chips.get(i));
				at += cw + d(CHIP_GAP);
			}
			more.set("+" + (total - shownChips.size()));
		}

		@Override
		protected void layout() {
			fitChips();
			float pad = d(PADDING);
			float bh = d(BUTTON);
			float by = y + h - pad - bh;
			float at = x + w - pad;
			if (renaming) {
				for (TextButton b : new TextButton[] {save, cancel}) {
					float bw = b.prefWidth();
					at -= bw;
					b.setBounds(at, by, bw, bh);
					at -= d(8);
				}
				float fx = x + pad + d(TILE) + d(14);
				field.setBounds(fx, y + pad + (d(TILE) - bh) / 2, x + w - pad - fx, bh);
				return;
			}
			if (deleteAskedAt != 0) {
				for (TextButton b : new TextButton[] {confirmDelete, keep}) {
					float bw = b.prefWidth();
					at -= bw;
					b.setBounds(at, by, bw, bh);
					at -= d(8);
				}
				return;
			}
			for (IconButton b : new IconButton[] {deleteButton, renameButton, cloneButton, shareButton}) {
				at -= bh;
				b.setBounds(at, by, bh, bh);
				at -= d(8);
			}
			if (use != null) {
				use.setBounds(x + pad, by, Math.min(use.prefWidth() + d(12), at - d(8) - x - pad), bh);
			}
		}

		@Override
		protected void paint(Canvas c) {
			if (deleteAskedAt != 0 && Clock.millis() - deleteAskedAt >= CONFIRM_MILLIS) {
				setAsking(false);
			}
			on = MenuStyle.ease(on, info.active());
			float rad = MenuStyle.radius();
			float pad = d(PADDING);
			MenuStyle.block(c, x, y, w, h, rad, 0, 0);
			MenuStyle.chosen(c, x, y, w, h, rad, on);
			// The tile: the profiles icon, in the accent while this profile is in use.
			float tile = d(TILE);
			MenuStyle.box(c, x + pad, y + pad, tile, tile, rad, Palette.lerp(MenuStyle.SOFT_ON_BLOCK, Palette.withAlpha(MenuStyle.ACCENT, 0.16F), on));
			Glyph.LAYERS.draw(c, x + pad + tile / 2, y + pad + tile / 2, d(20), Palette.lerp(MenuStyle.NEUTRAL, MenuStyle.ACCENT, on));
			float textX = x + pad + tile + d(14);
			if (!renaming) {
				MenuStyle.fitted(c, label, textX, y + pad + tile / 2 - d(10), MenuStyle.NAME_ON, x + w - pad - textX);
				MenuStyle.fitted(c, detail, textX, y + pad + tile / 2 + d(11), MenuStyle.LABEL, x + w - pad - textX);
			}
			// The names of its mods as chips (as many as fit, then "+n"), or a word that it has none on yet.
			float stripY = y + pad + tile + d(22);
			if (deleteAskedAt != 0) {
				MenuStyle.fitted(c, askDelete, x + pad, stripY, MenuStyle.DANGER_TEXT, w - 2 * pad);
			} else if (chips.isEmpty()) {
				MenuStyle.fitted(c, noMods, x + pad, stripY, MenuStyle.EMPTY_NOTE, w - 2 * pad);
			} else {
				float chipH = d(CHIP_H);
				float side = d(CHIP_SIDE);
				float at = x + pad;
				for (Label chip : shownChips) {
					float cw = chip.width(c) + 2 * side;
					MenuStyle.box(c, at, stripY - chipH / 2, cw, chipH, Math.min(MenuStyle.radius(), chipH / 2), MenuStyle.SOFT_ON_BLOCK);
					MenuStyle.text(c, chip, at + side, stripY, MenuStyle.CHIP_TEXT);
					at += cw + d(CHIP_GAP);
				}
				if (total > shownChips.size()) {
					float cw = more.width(c) + 2 * side;
					MenuStyle.box(c, at, stripY - chipH / 2, cw, chipH, Math.min(MenuStyle.radius(), chipH / 2), MenuStyle.SOFT);
					MenuStyle.text(c, more, at + side, stripY, MenuStyle.LABEL);
				}
			}
			if (info.active() && !renaming && deleteAskedAt == 0) {
				// In use: the green of an Enabled mod, where the others have Use.
				float bh = d(BUTTON);
				float by = y + h - pad - bh;
				float bw = d(16) + d(14) + d(8) + inUse.width(c) + d(16);
				MenuStyle.box(c, x + pad, by, bw, bh, Math.min(rad, bh / 2), MenuStyle.ENABLED);
				Glyph.CHECK.draw(c, x + pad + d(16) + d(7), by + bh / 2, d(13), MenuStyle.ENABLED_TEXT);
				MenuStyle.text(c, inUse, x + pad + d(16) + d(14) + d(8), by + bh / 2, MenuStyle.ENABLED_TEXT);
			}
		}
	}

	/** The last card: a new profile. Its press goes to the name field above (or makes the profile when a name is typed). */
	private final class NewCard extends Pressable {
		private final Label head = new Label(Type.NAME, "New profile");
		private final Label line;

		NewCard(boolean onlyOne) {
			super(null);
			this.line = new Label(Type.ROW_NOTE, onlyOne ? "One for each way you play" : "Starts with every mod off");
			setAction(ProfilesPage.this::create);
			name("New profile");
			tooltip("Type its name above, then Create Profile");
		}

		@Override
		protected void paint(Canvas c) {
			float rad = MenuStyle.radius();
			MenuStyle.box(c, x, y, w, h, rad, Palette.withAlpha(MenuStyle.TEXT, 0.02F + 0.03F * hover));
			MenuStyle.ring(c, x, y, w, h, rad, MenuStyle.edge(), Palette.lerp(MenuStyle.SOFT_EDGE, Palette.withAlpha(MenuStyle.ACCENT, 0.6F), hover));
			MenuStyle.pressed(c, x, y, w, h, rad, press);
			float circle = d(44);
			float cy = y + h / 2 - d(22);
			MenuStyle.box(c, x + w / 2 - circle / 2, cy - circle / 2, circle, circle, circle / 2,
				Palette.lerp(MenuStyle.SOFT_ON_BLOCK, Palette.withAlpha(MenuStyle.ACCENT, 0.18F), hover));
			Glyph.PLUS.draw(c, x + w / 2, cy, d(18), Palette.lerp(MenuStyle.NEUTRAL, MenuStyle.ACCENT, hover));
			MenuStyle.centered(c, head, x + w / 2, cy + circle / 2 + d(18), Palette.lerp(MenuStyle.NAME_OFF, MenuStyle.NAME_ON, hover), w - d(24));
			MenuStyle.centered(c, line, x + w / 2, cy + circle / 2 + d(40), MenuStyle.LABEL, w - d(24));
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}
	}

	/**
	 * A square icon button, as the mod cards' gear: the text at 5% with a grey glyph that the pointer brightens (Delete's
	 * turns red). Its tooltip names it and says what it does. It can show another glyph for a moment (Share's tick once
	 * the code is copied).
	 */
	private static final class IconButton extends Pressable {
		private final Glyph glyph;
		final String word;
		private boolean danger;
		private @Nullable Glyph flashGlyph;
		private long flashUntil;

		IconButton(Glyph glyph, String word, String does, Runnable action) {
			super(action);
			this.glyph = glyph;
			this.word = word;
			tooltip(word + "\n" + does);
		}

		IconButton danger() {
			this.danger = true;
			return this;
		}

		void flash(Glyph shown, long millis) {
			flashGlyph = shown;
			flashUntil = Clock.millis() + millis;
		}

		@Override
		public String narration() {
			return withReason(accessibleName() + ", button");
		}

		@Override
		protected void paint(Canvas c) {
			float rad = Math.min(MenuStyle.radius(), h / 2);
			MenuStyle.button(c, x, y, w, h, false, hover, press);
			boolean flashing = flashGlyph != null && Clock.millis() < flashUntil;
			int ink = flashing ? MenuStyle.SUCCESS_TEXT
				: Palette.lerp(MenuStyle.NEUTRAL, danger ? MenuStyle.DANGER : MenuStyle.TEXT, hover);
			if (!enabled()) {
				ink = Palette.withAlpha(MenuStyle.NEUTRAL, 0.45F);
			}
			(flashing ? flashGlyph : glyph).draw(c, x + w / 2, y + h / 2, h * 0.42F, ink);
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}
	}

	/** A name field in the window's look. */
	private static final class NameField extends TextField {
		private @Nullable FontFace face;

		NameField(String placeholder) {
			this(placeholder, 40);
		}

		NameField(String placeholder, int maxChars) {
			super(placeholder, maxChars);
		}

		@Override
		protected void layout() {
			FontFace now = Type.VALUE.face();
			if (now != face) {
				face = now;
				look(new TextField.Look(now, MenuStyle.FIELD, MenuStyle.FIELD_EDGE, MenuStyle.SEARCH_EDGE_HOVER, MenuStyle.SEARCH_FOCUS, r(12), r(18), 0, 0,
					MenuStyle.ROW_TEXT, MenuStyle.LABEL, MenuStyle.LABEL));
			}
			super.layout();
		}
	}
}
