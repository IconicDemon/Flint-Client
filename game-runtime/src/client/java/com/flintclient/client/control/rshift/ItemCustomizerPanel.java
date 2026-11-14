package com.flintclient.client.control.rshift;

import static com.flintclient.client.control.rshift.MenuStyle.d;
import static com.flintclient.client.control.rshift.MenuStyle.r;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.feature.HeldItems;
import com.flintclient.client.control.model.BoolSetting;
import com.flintclient.client.control.model.FloatSetting;
import com.flintclient.client.control.model.HeldItemsSetting;
import com.flintclient.client.control.model.HeldTransform;
import com.flintclient.client.control.model.IdKind;
import com.flintclient.client.control.model.ModuleDescriptor;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.SettingDescriptor;
import com.flintclient.client.control.model.SettingSection;
import com.flintclient.client.control.model.mods.VisualMods.ViewModel;
import com.flintclient.client.control.rshift.MenuStyle.Label;
import com.flintclient.client.control.rshift.MenuStyle.Type;
import com.flintclient.client.control.rshift.PageControls.Switch;
import com.flintclient.client.control.rshift.PageControls.TextButton;
import com.flintclient.client.platform.Ids;
import com.flintclient.client.platform.ItemPainter;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.client.ui.kit.Pressable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Item Customizer's page, Flint's own: three sections, each a title with its switch over a card. First Person Items: every
 * item's place and the empty hand's (each a switch and a chevron that opens its position, rotation and scale in the card), then
 * the items and kinds of item with a place of their own, added from the item in your hand or the searchable list, each opened
 * the same way or taken off. Animations: the swing, eating and re-equip switches in two columns, the swing speed under its
 * switch. Dropped Item Size: every item's size, the size of the items you choose and that list. Only one place is open at a
 * time; a section that is off is dimmed but stays editable. On 1.8.9 only what its backend honours is listed (no shields).
 */
final class ItemCustomizerPanel extends Node implements PagePanel {
	private static final ModuleDescriptor MODULE = ViewModel.DESCRIPTOR;
	/** The design's spacing, as the settings list's: a section's title, under it, between sections; inside a card. */
	private static final float TITLE = 30;
	private static final float UNDER_TITLE = 8;
	private static final float GAP = 20;
	private static final float CARD_PAD = 24;
	private static final float COLUMN_GAP = 64;
	private static final float ROW_GAP = 8;
	private static final String ITEMS = "@items";
	private static final String HANDS = "@hands";
	private final Host host;
	private final Card firstPerson;
	private final Card animations;
	private final Card dropped;
	private final Node itemsLine;
	private final Node handsLine;
	private final Editor itemsEditor;
	private final Editor handsEditor;
	private final Caption heldCaption = new Caption("Selected items", "Their own place, over every item's");
	private final Caption droppedCaption = new Caption("Selected items", "These take the selected size");
	private final ButtonRow heldButtons;
	private final ButtonRow droppedButtons;
	private final List<Node> animationLines = new ArrayList<>();
	private final @Nullable Node swingSpeedValue;
	private final List<Node> droppedLines = new ArrayList<>();
	private final Map<String, EntryRow> heldRows = new HashMap<>();
	private final Map<String, Editor> heldEditors = new HashMap<>();
	private final Map<String, EntryRow> droppedRows = new HashMap<>();
	/** Which place is open: {@link #ITEMS}, {@link #HANDS}, an entry's id, or null. */
	private @Nullable String open;
	private long builtFor = Long.MIN_VALUE;

	ItemCustomizerPanel(Host host) {
		this.host = host;
		// The item list once (on 1.8.9 that also makes the pictures the rows draw).
		IdCatalog.of(IdKind.ITEM);
		firstPerson = add(new Card("First Person Items", "Where your items and hand sit", ViewModel.FIRST_PERSON));
		animations = add(new Card("Animations", "Your own swing, eating and re-equip", ViewModel.ANIMATIONS));
		dropped = add(new Card("Dropped Item Size", "How big items on the ground look", ViewModel.DROPPED));
		itemsLine = firstPerson.add(placeLine("Items", ViewModel.ITEMS, ITEMS, "every item's place"));
		handsLine = firstPerson.add(placeLine("Empty Hands", ViewModel.EMPTY_HANDS, HANDS, "the empty hand's place"));
		itemsEditor = firstPerson.add(new Editor(settingParts(ViewModel.ITEM_TRANSFORM)));
		handsEditor = firstPerson.add(new Editor(settingParts(ViewModel.HAND_TRANSFORM)));
		firstPerson.add(heldCaption);
		heldButtons = firstPerson.add(new ButtonRow(
			new TextButton("Add held item", Glyph.PLUS, PageControls.Look.PLAIN, this::addHeld),
			new TextButton("Add item or category", Glyph.SEARCH, PageControls.Look.PLAIN, () -> pickHeld())));
		for (BoolSetting s : List.of(ViewModel.SWING_SCALE, ViewModel.IGNORE_HASTE, ViewModel.IGNORE_FATIGUE, ViewModel.ROTATIONLESS_EAT,
			ViewModel.CANCEL_REEQUIP, ViewModel.SWING_SPEED)) {
			Node line = line(s.label(), s);
			if (line != null) {
				animationLines.add(animations.add(line));
			}
		}
		Node speed = line("Swing speed", ViewModel.SWING_SPEED_VALUE);
		swingSpeedValue = speed == null ? null : animations.add(speed);
		for (FloatSetting s : List.of(ViewModel.DROPPED_SCALE, ViewModel.DROPPED_SELECTED_SCALE)) {
			Node line = line(s.label(), s);
			if (line != null) {
				droppedLines.add(dropped.add(line));
			}
		}
		dropped.add(droppedCaption);
		droppedButtons = dropped.add(new ButtonRow(new TextButton("Add item", Glyph.SEARCH, PageControls.Look.PLAIN, () -> pickDropped())));
		if (!Catalog.shows(row("", ViewModel.FIRST_PERSON))) {
			firstPerson.setVisible(false);
		}
		if (!Catalog.shows(row("", ViewModel.ANIMATIONS))) {
			animations.setVisible(false);
		}
		if (!Catalog.shows(row("", ViewModel.DROPPED))) {
			dropped.setVisible(false);
		}
	}

	// ---------- the model ----------

	private static ModuleState state() {
		return ControlState.layout().module(MODULE);
	}

	private static Catalog.Row row(String label, SettingDescriptor<?> setting) {
		return new Catalog.Row(label, Catalog.kindOf(setting), MODULE, setting, false);
	}

	/** A standard row for {@code setting}, or null when this Minecraft line doesn't honour it. */
	private @Nullable Node line(String label, SettingDescriptor<?> setting) {
		Catalog.Row row = row(label, setting);
		return Catalog.shows(row) ? Rows.line(host, row) : null;
	}

	private static void changed(boolean changed) {
		if (changed) {
			ControlState.settings().recordChange(MODULE.id());
		}
	}

	private static Map<String, HeldTransform> entries() {
		return state().get(ViewModel.HELD);
	}

	/** The categories this Minecraft has (1.8.9 has no shields). */
	private static List<HeldItems.Category> categories() {
		Map<String, String> items = Ids.items();
		List<HeldItems.Category> out = new ArrayList<>();
		for (HeldItems.Category c : HeldItems.Category.values()) {
			if (items.containsKey(c.picture())) {
				out.add(c);
			}
		}
		return out;
	}

	/** An entry's name: a category's, or the item's as the game shows it. */
	private static String nameOf(String id) {
		HeldItems.Category c = HeldItems.Category.of(id);
		return c != null ? c.label() : IdCatalog.name(IdKind.ITEM, id);
	}

	/** The item drawn for an entry. */
	private static String pictureOf(String id) {
		HeldItems.Category c = HeldItems.Category.of(id);
		return c != null ? c.picture() : id;
	}

	/** Adds {@code id} with every item's place to start from (so it starts where it is now), and opens it. */
	private void addEntry(String id) {
		Map<String, HeldTransform> now = entries();
		if (!now.containsKey(id)) {
			HeldTransform start = state().bool(ViewModel.ITEMS) ? HeldItems.read(state(), ViewModel.ITEM_TRANSFORM) : HeldTransform.NONE;
			changed(state().set(ViewModel.HELD, ViewModel.HELD.with(now, id, start)));
		}
		open = id;
		invalidate();
	}

	private void addHeld() {
		String id = Ids.heldItem();
		if (id != null) {
			addEntry(id);
		}
	}

	private void pickHeld() {
		List<ListPicker.Item> choices = new ArrayList<>();
		for (HeldItems.Category c : categories()) {
			choices.add(new ListPicker.Item(c.id(), c.label(), "Category", true, c.picture()));
		}
		choices.addAll(IdCatalog.of(IdKind.ITEM));
		host.openChooser(heldButtons, "Add an item or category", choices, false, () -> "", this::addEntry, null);
	}

	private void pickDropped() {
		host.openChooser(droppedButtons, "Add an item", IdCatalog.of(IdKind.ITEM), false, () -> "", id -> {
			ModuleState s = state();
			changed(s.set(ViewModel.DROPPED_ITEMS, ViewModel.DROPPED_ITEMS.with(s.ids(ViewModel.DROPPED_ITEMS), id, true)));
			invalidate();
		}, null);
	}

	private void toggle(String key) {
		open = Objects.equals(open, key) ? null : key;
		invalidate();
	}

	// ---------- Reset Settings ----------

	@Override
	public void resetAll() {
		ModuleState s = state();
		boolean any = false;
		for (SettingSection section : MODULE.sections()) {
			if (!section.title().equals("Mod")) {
				for (SettingDescriptor<?> setting : section.settings()) {
					any |= s.resetSetting(setting);
				}
			}
		}
		changed(any);
		open = null;
		invalidate();
	}

	// ---------- the rows of a place ----------

	/** Every item's or the empty hand's place: the setting's switch, and a chevron that opens its numbers. */
	private Node placeLine(String label, BoolSetting setting, String key, String what) {
		Rows.Access access = Rows.Access.of(row(label, setting));
		Switch toggle = new Switch(() -> (Boolean) access.get(), () -> access.set(!(Boolean) access.get()));
		Parts.Square chevron = new Parts.Square(Glyph.CHEVRON_RIGHT, "Change " + what, () -> toggle(key)).active(() -> key.equals(open));
		return new Rows.Line(label, access, new SwitchOpen(toggle, chevron, () -> key.equals(open)));
	}

	/** The nine rows of a place kept in settings (every item's, the empty hand's). */
	private List<Node> settingParts(List<FloatSetting> parts) {
		List<Node> out = new ArrayList<>(HeldTransform.SIZE);
		for (FloatSetting part : parts) {
			out.add(Rows.line(host, row(part.label(), part)));
		}
		return out;
	}

	/** The nine rows of an entry's place (kept in the list). */
	private List<Node> entryParts(String id) {
		List<Node> out = new ArrayList<>(HeldTransform.SIZE);
		for (int i = 0; i < HeldTransform.SIZE; i++) {
			EntryPart part = new EntryPart(id, i);
			out.add(new Rows.Line("XYZ".substring(i % 3, i % 3 + 1), part,
				Rows.decimal(part, ViewModel.HELD.component(i))));
		}
		return out;
	}

	/** One number of an entry's place. */
	private static final class EntryPart extends Rows.Access {
		private final String id;
		private final int index;

		EntryPart(String id, int index) {
			this.id = id;
			this.index = index;
		}

		private HeldTransform now() {
			HeldTransform t = entries().get(id);
			return t == null ? HeldTransform.NONE : t;
		}

		@Override
		Object get() {
			return now().get(index);
		}

		@Override
		boolean set(Object value) {
			Map<String, HeldTransform> entries = entries();
			if (!entries.containsKey(id)) {
				return false;
			}
			float v = ViewModel.HELD.component(index).clampFloat((Float) value);
			boolean changed = state().set(ViewModel.HELD, ViewModel.HELD.with(entries, id, now().with(index, v)));
			changed(changed);
			return changed;
		}

		@Override
		boolean isDefault() {
			return now().get(index) == HeldTransform.NONE.get(index);
		}

		@Override
		boolean reset() {
			return set(HeldTransform.NONE.get(index));
		}

		@Override
		ModuleDescriptor module() {
			return MODULE;
		}
	}

	// ---------- the structure: rows come and go with the lists and the open place ----------

	/** What the page lists now, as one number: when it changes, the rows are made again and laid out. */
	private long structure() {
		ModuleState s = state();
		long key = Objects.hashCode(open);
		key = key * 31 + entries().keySet().hashCode();
		key = key * 31 + s.ids(ViewModel.DROPPED_ITEMS).hashCode();
		key = key * 31 + (s.bool(ViewModel.SWING_SPEED) ? 1 : 2);
		key = key * 31 + System.identityHashCode(s);
		return key;
	}

	private void rebuild() {
		Map<String, HeldTransform> entries = entries();
		if (open != null && !open.equals(ITEMS) && !open.equals(HANDS) && !entries.containsKey(open)) {
			open = null;
		}
		// Rows of entries still there are kept (a slider being dragged stays put).
		for (String id : new ArrayList<>(heldRows.keySet())) {
			if (!entries.containsKey(id)) {
				firstPerson.remove(heldRows.remove(id));
				Editor editor = heldEditors.remove(id);
				if (editor != null) {
					firstPerson.remove(editor);
				}
			}
		}
		List<Card.Block> fp = new ArrayList<>();
		fp.add(new Card.Block(itemsLine, false));
		itemsEditor.setVisible(ITEMS.equals(open));
		fp.add(new Card.Block(itemsEditor, false));
		fp.add(new Card.Block(handsLine, false));
		handsEditor.setVisible(HANDS.equals(open));
		fp.add(new Card.Block(handsEditor, false));
		fp.add(new Card.Block(heldCaption, false));
		for (String id : entries.keySet()) {
			EntryRow rowNode = heldRows.computeIfAbsent(id, k -> firstPerson.add(new EntryRow(k, true)));
			fp.add(new Card.Block(rowNode, false));
			if (id.equals(open)) {
				Editor editor = heldEditors.computeIfAbsent(id, k -> firstPerson.add(new Editor(entryParts(k))));
				editor.setVisible(true);
				fp.add(new Card.Block(editor, false));
			} else {
				Editor editor = heldEditors.get(id);
				if (editor != null) {
					editor.setVisible(false);
				}
			}
		}
		fp.add(new Card.Block(heldButtons, false));
		firstPerson.blocks(fp);

		List<Card.Block> an = new ArrayList<>();
		for (Node line : animationLines) {
			an.add(new Card.Block(line, true));
		}
		if (swingSpeedValue != null) {
			boolean on = state().bool(ViewModel.SWING_SPEED);
			swingSpeedValue.setVisible(on);
			if (on) {
				an.add(new Card.Block(swingSpeedValue, true));
			}
		}
		animations.blocks(an);

		List<String> listed = state().ids(ViewModel.DROPPED_ITEMS);
		for (String id : new ArrayList<>(droppedRows.keySet())) {
			if (!listed.contains(id)) {
				dropped.remove(droppedRows.remove(id));
			}
		}
		List<Card.Block> dr = new ArrayList<>();
		for (Node line : droppedLines) {
			dr.add(new Card.Block(line, true));
		}
		dr.add(new Card.Block(droppedCaption, false));
		for (String id : listed) {
			dr.add(new Card.Block(droppedRows.computeIfAbsent(id, k -> dropped.add(new EntryRow(k, false))), true));
		}
		dr.add(new Card.Block(droppedButtons, false));
		dropped.blocks(dr);
	}

	// ---------- layout ----------

	private boolean twoColumns(float width) {
		float inner = width - 2 * d(CARD_PAD);
		return inner >= d(760) && inner * measure().scale() / MenuStyle.pixelScale() >= 500;
	}

	private void ensureBuilt() {
		long key = structure();
		if (key != builtFor) {
			builtFor = key;
			rebuild();
		}
	}

	@Override
	public float prefHeight(float width) {
		ensureBuilt();
		float total = 0;
		for (Card card : List.of(firstPerson, animations, dropped)) {
			if (card.visible()) {
				total += (total > 0 ? d(GAP) : 0) + card.prefHeight(width);
			}
		}
		return total;
	}

	@Override
	protected void layout() {
		ensureBuilt();
		float at = y;
		for (Card card : List.of(firstPerson, animations, dropped)) {
			if (card.visible()) {
				float h = card.prefHeight(w);
				card.setBounds(x, at, w, h);
				at += h + d(GAP);
			}
		}
	}

	@Override
	protected void paint(Canvas c) {
		if (structure() != builtFor) {
			// A list or the open place changed: rows come and go, the page is laid out again.
			invalidate();
		}
	}

	// ---------- parts ----------

	/** A section: its title (and what it does, quieter) with its switch, over a card of rows; dimmed while it is off. */
	private final class Card extends Node {
		record Block(Node node, boolean paired) {
		}

		private final Label title;
		private final Label about;
		private final BoolSetting setting;
		private final Switch toggle;
		private List<Block> blocks = List.of();
		private float dim = -1;
		/** The card's top below the title, from the section's own top (a scroll moves the section without laying it out again). */
		private float cardTop;

		Card(String title, String about, BoolSetting setting) {
			this.title = new Label(Type.SECTION_TITLE, title);
			this.about = new Label(Type.ROW_NOTE, about);
			this.setting = setting;
			Rows.Access access = Rows.Access.of(row(title, setting));
			this.toggle = add(new Switch(() -> (Boolean) access.get(), () -> access.set(!(Boolean) access.get())));
			toggle.name(title);
			toggle.tooltip(title + "\n" + setting.description());
		}

		void blocks(List<Block> value) {
			blocks = value;
		}

		private boolean on() {
			return state().bool(setting);
		}

		/** Lays the blocks out from {@code top} (or only measures, with {@code apply} false); the card's height. */
		private float place(float left, float top, float width, boolean apply) {
			float inner = width - 2 * d(CARD_PAD);
			boolean two = twoColumns(width);
			float colW = two ? (inner - d(COLUMN_GAP)) / 2 : inner;
			float at = top + d(CARD_PAD);
			boolean first = true;
			for (int i = 0; i < blocks.size();) {
				Block b = blocks.get(i);
				if (!b.node().visible()) {
					i++;
					continue;
				}
				if (!first) {
					at += d(ROW_GAP);
				}
				first = false;
				if (b.paired() && two) {
					Block next = null;
					int j = i + 1;
					while (j < blocks.size() && !blocks.get(j).node().visible()) {
						j++;
					}
					if (j < blocks.size() && blocks.get(j).paired()) {
						next = blocks.get(j);
					}
					float h = b.node().prefHeight(colW);
					if (next != null) {
						h = Math.max(h, next.node().prefHeight(colW));
					}
					if (apply) {
						b.node().setBounds(left + d(CARD_PAD), at, colW, h);
						if (next != null) {
							next.node().setBounds(left + d(CARD_PAD) + colW + d(COLUMN_GAP), at, colW, h);
						}
					}
					at += h;
					i = next != null ? j + 1 : i + 1;
				} else {
					float h = b.node().prefHeight(inner);
					if (apply) {
						b.node().setBounds(left + d(CARD_PAD), at, inner, h);
					}
					at += h;
					i++;
				}
			}
			return at + d(CARD_PAD) - top;
		}

		@Override
		public float prefHeight(float width) {
			return d(TITLE) + d(UNDER_TITLE) + place(0, 0, width, false);
		}

		@Override
		protected void layout() {
			float sh = r(Switch.H);
			float sw = toggle.prefWidth();
			toggle.setBounds(x + w - sw, y + (d(TITLE) - sh) / 2, sw, sh);
			cardTop = d(TITLE) + d(UNDER_TITLE);
			place(x, y + cardTop, w, true);
		}

		@Override
		protected void paint(Canvas c) {
			dim = MenuStyle.ease(dim, !on());
			float cy = y + d(TITLE) / 2;
			MenuStyle.fitted(c, title, x, cy, MenuStyle.TEXT, w - toggle.width() - d(24));
			float after = x + title.width(c) + d(14);
			float room = toggle.x() - d(24) - after;
			if (room > d(80)) {
				MenuStyle.fitted(c, about, after, cy + d(1), MenuStyle.NEUTRAL, room);
			}
			MenuStyle.block(c, x, y + cardTop, w, h - cardTop, MenuStyle.radius(), 0, 0);
		}

		@Override
		protected void paintChildren(Canvas c) {
			float opacity = c.opacity();
			for (int i = 0; i < childCount(); i++) {
				Node child = child(i);
				boolean content = child != toggle;
				c.setOpacity(opacity * (content ? 1 - 0.5F * Math.max(0, dim) : 1));
				paintChild(c, child);
			}
			c.setOpacity(opacity);
		}
	}

	/** A small heading inside a card, with a quieter note beside it. */
	private static final class Caption extends Node {
		private final Label title;
		private final Label note;

		Caption(String title, String note) {
			this.title = new Label(Type.ROW_LABEL, title);
			this.note = new Label(Type.ROW_NOTE, note);
		}

		@Override
		public float prefHeight(float width) {
			return d(34);
		}

		@Override
		protected void paint(Canvas c) {
			float cy = y + h / 2 + d(4);
			MenuStyle.fitted(c, title, x, cy, MenuStyle.TEXT, w);
			float after = x + title.width(c) + d(12);
			if (after < x + w - d(60)) {
				MenuStyle.fitted(c, note, after, cy + d(1), MenuStyle.NEUTRAL, x + w - after);
			}
			// A hairline above it, the card's rows split from the list.
			int y0 = c.px(y);
			c.fill(c.px(x), y0, c.px(x + w), y0 + Math.max(1, c.px(d(1))), MenuStyle.ROW_RULE);
		}
	}

	/** A place's switch and the chevron that opens its numbers (pointing down while open). */
	private static final class SwitchOpen extends Node {
		private final Switch toggle;
		private final Parts.Square chevron;
		private final java.util.function.BooleanSupplier opened;

		SwitchOpen(Switch toggle, Parts.Square chevron, java.util.function.BooleanSupplier opened) {
			this.toggle = add(toggle);
			this.chevron = add(chevron);
			this.opened = opened;
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
			chevron.setBounds(x + sw + r(16), y + (h - r(40)) / 2, r(40), r(40));
		}

		@Override
		protected void paintOver(Canvas c) {
			// The chevron turns down while its numbers are open.
			if (opened.getAsBoolean()) {
				float cx = chevron.x() + chevron.width() / 2;
				float cy = chevron.y() + chevron.height() / 2;
				MenuStyle.box(c, chevron.x(), chevron.y(), chevron.width(), chevron.height(), Math.min(MenuStyle.radius(), chevron.height() / 2),
					MenuStyle.CHIP);
				Glyph.CHEVRON_DOWN.draw(c, cx, cy, chevron.height() * 0.5F, MenuStyle.ACCENT);
			}
		}

		@Override
		public void setDisabled(String reason) {
			toggle.setDisabled(reason);
			chevron.setDisabled(reason);
		}

		@Override
		public void setEnabled(boolean value) {
			toggle.setEnabled(value);
			chevron.setEnabled(value);
		}
	}

	/** One entry of a list: its picture, its name (and "Category" for a kind of item); a chevron to its place, and a remove. */
	private final class EntryRow extends Pressable {
		private static final float PICTURE = 28;
		private final String id;
		private final boolean held;
		private final Label name;
		private final Label kind = new Label(Type.ROW_NOTE, "Category");
		private final boolean category;
		private final Parts.@Nullable Square chevron;
		private final Parts.Square remove;

		EntryRow(String id, boolean held) {
			super(null);
			this.id = id;
			this.held = held;
			this.category = HeldItems.Category.of(id) != null;
			this.name = new Label(Type.ROW_LABEL, nameOf(id));
			name(name.text());
			if (held) {
				setAction(() -> toggle(id));
				chevron = add(new Parts.Square(Glyph.CHEVRON_RIGHT, "Change " + name.text() + "'s place", () -> toggle(id)).active(
					() -> id.equals(open)));
			} else {
				chevron = null;
			}
			remove = add(new Parts.Square(Glyph.TRASH, "Take " + name.text() + " off the list", this::remove));
		}

		private void remove() {
			ModuleState s = state();
			if (held) {
				changed(s.set(ViewModel.HELD, ViewModel.HELD.without(s.get(ViewModel.HELD), id)));
			} else {
				changed(s.set(ViewModel.DROPPED_ITEMS, ViewModel.DROPPED_ITEMS.with(s.ids(ViewModel.DROPPED_ITEMS), id, false)));
			}
			ItemCustomizerPanel.this.invalidate();
		}

		@Override
		public boolean interactive() {
			return held;
		}

		@Override
		public float prefHeight(float width) {
			return r(Rows.H_PLAIN);
		}

		@Override
		protected void layout() {
			float size = r(40);
			remove.setBounds(x + w - size, y + (h - size) / 2, size, size);
			if (chevron != null) {
				chevron.setBounds(remove.x() - r(12) - size, y + (h - size) / 2, size, size);
			}
		}

		@Override
		protected void paint(Canvas c) {
			boolean opened = held && id.equals(open);
			if (opened || hover > 0.01F) {
				MenuStyle.box(c, x - d(8), y, w + d(16), h, MenuStyle.radius(), Palette.withAlpha(MenuStyle.TEXT, 0.03F * Math.max(hover, opened ? 1 : 0)));
			}
			if (opened) {
				// The open entry: a short accent bar at its left.
				MenuStyle.box(c, x - d(8), y + h * 0.25F, d(3), h * 0.5F, d(1.5F), MenuStyle.ACCENT);
			}
			float cy = y + h / 2;
			int size = Math.max(16, Math.round(c.px(r(PICTURE)) / 16F) * 16);
			Object stack = Ids.stack(pictureOf(id));
			if (stack != null) {
				ItemPainter.item(c.frame(), stack, c.px(x), c.px(cy) - size / 2, size, null, false);
			}
			float left = x + Math.max(r(PICTURE), size / (float) c.scale()) + r(14);
			float right = (chevron != null ? chevron.x() : remove.x()) - d(16);
			MenuStyle.fitted(c, name, left, cy, MenuStyle.ROW_TEXT, right - left);
			if (category) {
				float after = left + name.width(c) + d(12);
				if (after < right - d(40)) {
					MenuStyle.fitted(c, kind, after, cy + d(1), MenuStyle.NEUTRAL, right - after);
				}
			}
		}
	}

	/**
	 * A place's numbers, opened in the card under its row: Position, Rotation and Scale, each with its X, Y and Z, side by side
	 * when there is room; and a way back to Minecraft's place.
	 */
	private final class Editor extends Node {
		private static final String[] GROUPS = {"Position", "Rotation", "Scale"};
		private final List<Node> parts;
		private final Label[] titles = new Label[3];
		private final TextButton reset;
		private int columns = 1;

		Editor(List<Node> parts) {
			this.parts = parts;
			for (Node part : parts) {
				add(part);
			}
			for (int i = 0; i < 3; i++) {
				titles[i] = new Label(Type.ROW_NOTE, GROUPS[i]);
			}
			reset = add(new TextButton("Back to Minecraft's place", Glyph.RESET, PageControls.Look.PLAIN, this::resetPlace));
			setVisible(false);
		}

		private void resetPlace() {
			for (Node part : parts) {
				if (part instanceof Rows.Line line) {
					line.resetValue();
				}
			}
		}

		private float rowH() {
			return r(Rows.H_PLAIN);
		}

		/** The narrowest a group can be: its slider, the reset arrow before it and a short name. */
		private float minColumn() {
			Node first = parts.get(0);
			float control = first instanceof Rows.Line line ? line.controlWidth() : r(500);
			return control + r(40) + r(14) + r(70);
		}

		private int columnsFor(float width) {
			float inner = width - 2 * d(20);
			float gap = d(40);
			return Math.max(1, Math.min(3, (int) Math.floor((inner + gap) / (minColumn() + gap))));
		}

		private float groupHeight() {
			return d(26) + 3 * rowH() + 2 * d(4);
		}

		@Override
		public float prefHeight(float width) {
			int cols = columnsFor(width);
			int rows = (3 + cols - 1) / cols;
			return d(16) + rows * groupHeight() + (rows - 1) * d(14) + d(12) + r(PageControls.TextButton.H) * 0.62F + d(16);
		}

		@Override
		protected void layout() {
			columns = columnsFor(w);
			float inner = w - 2 * d(20);
			float gap = d(40);
			float colW = (inner - (columns - 1) * gap) / columns;
			for (int g = 0; g < 3; g++) {
				int col = g % columns;
				int rowIndex = g / columns;
				float gx = x + d(20) + col * (colW + gap);
				float gy = y + d(16) + rowIndex * (groupHeight() + d(14)) + d(26);
				for (int k = 0; k < 3; k++) {
					parts.get(g * 3 + k).setBounds(gx, gy + k * (rowH() + d(4)), colW, rowH());
				}
			}
			float bh = r(PageControls.TextButton.H) * 0.62F;
			float bw = reset.prefWidth();
			reset.setBounds(x + w - d(20) - bw, y + h - d(16) - bh, bw, bh);
		}

		@Override
		protected void paint(Canvas c) {
			MenuStyle.box(c, x, y, w, h, MenuStyle.radius(), Palette.withAlpha(MenuStyle.TEXT, 0.025F));
			float inner = w - 2 * d(20);
			float gap = d(40);
			float colW = (inner - (columns - 1) * gap) / columns;
			for (int g = 0; g < 3; g++) {
				float gx = x + d(20) + (g % columns) * (colW + gap);
				float gy = y + d(16) + (g / columns) * (groupHeight() + d(14));
				MenuStyle.fitted(c, titles[g], gx, gy + d(10), MenuStyle.ACCENT, colW);
			}
		}
	}

	/** A row of buttons at a card's foot, from the left. */
	private static final class ButtonRow extends Node {
		private final TextButton[] buttons;

		ButtonRow(TextButton... buttons) {
			this.buttons = buttons;
			for (TextButton b : buttons) {
				add(b);
			}
		}

		@Override
		public float prefHeight(float width) {
			return r(PageControls.TextButton.H) * 0.62F + d(8);
		}

		@Override
		protected void layout() {
			float at = x;
			float bh = r(PageControls.TextButton.H) * 0.62F;
			for (TextButton b : buttons) {
				float bw = b.prefWidth();
				b.setBounds(at, y + d(8), bw, bh);
				at += bw + d(12);
			}
		}
	}
}
