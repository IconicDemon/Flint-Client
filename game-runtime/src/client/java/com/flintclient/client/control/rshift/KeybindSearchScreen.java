package com.flintclient.client.control.rshift;

import static com.flintclient.client.control.rshift.MenuStyle.r;

import com.flintclient.client.control.platform.Mc263Tools;
import com.flintclient.client.control.rshift.MenuStyle.Label;
import com.flintclient.client.control.rshift.MenuStyle.Type;
import com.flintclient.client.platform.KeyInput;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FontFace;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.kit.KitScreen;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.client.ui.kit.SearchField;
import com.flintclient.client.ui.kit.TextField;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Keybind Search: a small popup over the game to find any keybind by its name or its category (Minecraft's, Flint's, other
 * mods'), each listed with the key it's on. Enter (or a click) closes the popup and presses that keybind as a key would,
 * even one without a key of its own. Up and Down move through the results; Escape closes. In the menu's theme.
 */
public final class KeybindSearchScreen extends KitScreen {
	private final int rows;
	private final boolean onlyUnbound;
	private @Nullable Panel panel;

	public KeybindSearchScreen(int rows, boolean onlyUnbound) {
		super("Keybind Search", 240, 140);
		this.rows = rows;
		this.onlyUnbound = onlyUnbound;
	}

	@Override
	protected int computeUiScale(int windowW, int windowH) {
		return MenuStyle.apply(windowW, windowH);
	}

	@Override
	protected float tooltipScale() {
		return MenuStyle.tooltipScale();
	}

	@Override
	protected boolean minecraftFont() {
		return true;
	}

	/** Dawn's tooltip: a #2C3033 panel with 8 corners, no edge, the title in white over grey lines. */
	@Override
	protected TooltipLook tooltipLook() {
		return MenuStyle.tooltipLook();
	}

	/** One scale for every word on the screen, from the window (see {@link MenuStyle#pixelScale}). */
	@Override
	protected int minecraftFontScale() {
		return MenuStyle.pixelScale();
	}

	@Override
	protected void build(Node root) {
		panel = root.add(new Panel());
	}

	@Override
	protected void layoutContent(float width, float height) {
		if (panel != null) {
			panel.setBounds(0, 0, width, height);
		}
	}

	@Override
	protected void initialFocus(boolean keyboard) {
		if (panel != null) {
			focus(panel.search);
		}
	}

	/** Up and Down move through the results while you type. */
	@Override
	protected boolean keyDown(KeyInput event) {
		if (panel != null && (event.isUp() || event.isDown())) {
			return panel.keyDown(event);
		}
		return super.keyDown(event);
	}

	/** One keybind as listed: its name, its category and its key, all lowercased into one string to search. */
	private record Entry(KeyMapping mapping, String name, String category, String key, String haystack) {
	}

	private final class Panel extends Node {
		private static final float PANEL_W = 1280;
		private static final float ROW_H = 84;
		private static final float PAD = 28;
		private final Label title = new Label(Type.SECTION_TITLE, "Keybind Search");
		private final Label hint = new Label(Type.ROW_NOTE, "Enter uses it  ·  Esc closes");
		private final Label empty = new Label(Type.ROW_LABEL, "No keybind matches");
		private final Label[] names;
		private final Label[] categories;
		private final Label[] keys;
		final SearchField search = add(new SearchField("Search keybinds", 48));
		private final List<Entry> all = new ArrayList<>();
		private final List<Entry> shown = new ArrayList<>();
		private int first;
		private int highlighted;
		private @Nullable FontFace searchFace;
		private float px;
		private float py;
		private float ph;
		private float listY;

		Panel() {
			names = new Label[rows];
			categories = new Label[rows];
			keys = new Label[rows];
			for (int i = 0; i < rows; i++) {
				names[i] = new Label(Type.ROW_LABEL, "");
				categories[i] = new Label(Type.ROW_NOTE, "");
				keys[i] = new Label(Type.VALUE, "");
			}
			search.name("Search keybinds");
			search.onChange(text -> refilter());
			search.onSubmit(text -> use());
			search.onEscape(KeybindSearchScreen.this::onClose);
			KeyMapping[] mappings = Minecraft.getInstance().options.keyMappings;
			for (KeyMapping mapping : Arrays.asList(mappings)) {
				if (onlyUnbound && !mapping.isUnbound()) {
					continue;
				}
				String name = Component.translatable(mapping.getName()).getString();
				String category = mapping.getCategory().label().getString();
				String key = mapping.isUnbound() ? "No key" : mapping.getTranslatedKeyMessage().getString();
				all.add(new Entry(mapping, name, category, key, (name + " " + category + " " + key).toLowerCase(Locale.ROOT)));
			}
			all.sort(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
			refilter();
		}

		/** The keybinds matching every typed word: those whose name matches first (starting with it before containing it). */
		private void refilter() {
			shown.clear();
			String query = search.text().toLowerCase(Locale.ROOT).trim();
			String[] words = query.split("\\s+");
			for (Entry entry : all) {
				if (ListPicker.matches(entry.haystack(), words)) {
					shown.add(entry);
				}
			}
			if (!query.isEmpty()) {
				shown.sort(Comparator.comparingInt((Entry entry) -> rank(entry, query)));
			}
			first = 0;
			highlighted = 0;
		}

		private static int rank(Entry entry, String query) {
			String name = entry.name().toLowerCase(Locale.ROOT);
			return name.startsWith(query) ? 0 : name.contains(query) ? 1 : 2;
		}

		private void use() {
			if (highlighted < 0 || highlighted >= shown.size()) {
				return;
			}
			KeyMapping mapping = shown.get(highlighted).mapping();
			onClose();
			Mc263Tools.press(mapping);
		}

		private void move(int by) {
			if (shown.isEmpty()) {
				return;
			}
			highlighted = Math.clamp(highlighted + by, 0, shown.size() - 1);
			if (highlighted < first) {
				first = highlighted;
			} else if (highlighted >= first + rows) {
				first = highlighted - rows + 1;
			}
		}

		@Override
		public boolean interactive() {
			return true;
		}

		@Override
		public boolean keyDown(KeyInput event) {
			if (event.isDown() || event.isUp()) {
				move(event.isDown() ? 1 : -1);
				return true;
			}
			return super.keyDown(event);
		}

		private int slotAt(float mx, float my) {
			if (mx < px + r(PAD) || mx > px + r(PANEL_W) - r(PAD) || my < listY) {
				return -1;
			}
			int slot = (int) Math.floor((my - listY) / r(ROW_H));
			return slot >= 0 && slot < rows && first + slot < shown.size() ? slot : -1;
		}

		@Override
		public boolean mouseDown(float mx, float my, int button) {
			if (!(mx >= px && mx < px + r(PANEL_W) && my >= py && my < py + ph)) {
				onClose();
				return true;
			}
			int slot = slotAt(mx, my);
			if (slot >= 0) {
				highlighted = first + slot;
				use();
			}
			return true;
		}

		@Override
		public boolean scroll(float mx, float my, double amount) {
			first = Math.clamp(first - (int) Math.signum(amount), 0, Math.max(0, shown.size() - rows));
			return true;
		}

		@Override
		protected void layout() {
			float pw = Math.min(r(PANEL_W), w - r(32));
			ph = r(PAD) + r(40) + r(20) + r(58) + r(16) + rows * r(ROW_H) + r(16) + r(30) + r(PAD);
			px = x + (w - pw) / 2;
			py = y + Math.max(r(16), (h - ph) * 0.38F);
			float inner = px + r(PAD);
			float top = py + r(PAD) + r(40) + r(20);
			search.setBounds(inner, top, pw - 2 * r(PAD), r(58));
			listY = top + r(58) + r(16);
			FontFace face = Type.SEARCH.face();
			if (face != searchFace) {
				searchFace = face;
				search.look(new TextField.Look(face, MenuStyle.SEARCH, MenuStyle.SEARCH_EDGE, MenuStyle.SEARCH_EDGE_HOVER, MenuStyle.SEARCH_FOCUS, r(15),
					r(24), r(30), r(18), MenuStyle.SEARCH_TEXT, MenuStyle.SEARCH_HINT, MenuStyle.SEARCH_HINT));
			}
		}

		@Override
		protected void paint(Canvas c) {
			float pw = Math.min(r(PANEL_W), w - r(32));
			int x0 = c.px(px);
			int y0 = c.px(py);
			int w0 = c.px(px + pw) - x0;
			int h0 = c.px(py + ph) - y0;
			for (int i = 3; i >= 1; i--) {
				int spread = c.px(r(8 * i));
				c.roundRect(x0 - spread, y0 - spread + c.px(r(10)), w0 + 2 * spread, h0 + 2 * spread, c.px(r(22)) + spread,
					Palette.withAlpha(0xFF000000, 0.12F));
			}
			MenuStyle.block(c, px, py, pw, ph, MenuStyle.radius(), 0, 0);
			MenuStyle.text(c, title, px + r(PAD), py + r(PAD) + r(20), MenuStyle.ROW_TEXT);
			MenuStyle.text(c, hint, px + r(PAD), py + ph - r(PAD) - r(15), MenuStyle.LABEL);
			float inner = px + r(PAD);
			float right = px + pw - r(PAD);
			KitScreen screen = screen();
			int hover = screen != null ? slotAt(screen.mouseX(), screen.mouseY()) : -1;
			if (shown.isEmpty()) {
				MenuStyle.text(c, empty, inner + r(16), listY + r(ROW_H) / 2, MenuStyle.LABEL);
			}
			for (int slot = 0; slot < rows && first + slot < shown.size(); slot++) {
				int index = first + slot;
				Entry entry = shown.get(index);
				float top = listY + slot * r(ROW_H);
				float cy = top + r(ROW_H) / 2;
				if (slot == hover || index == highlighted) {
					MenuStyle.box(c, inner, top + r(3), right - inner, r(ROW_H) - r(6), r(12), slot == hover ? MenuStyle.CHIP_HOVER : MenuStyle.CHIP);
				}
				if (index == highlighted) {
					MenuStyle.box(c, inner, top + r(16), r(5), r(ROW_H) - r(32), r(2.5F), MenuStyle.ACCENT);
				}
				keys[slot].set(entry.key());
				float keyW = keys[slot].width(c) + r(28);
				float keyX = right - keyW - r(10);
				MenuStyle.box(c, keyX, cy - r(22), keyW, r(44), r(9), entry.mapping().isUnbound() ? MenuStyle.CHIP : MenuStyle.PILL);
				MenuStyle.centered(c, keys[slot], keyX + keyW / 2, cy, entry.mapping().isUnbound() ? MenuStyle.LABEL : MenuStyle.ROW_TEXT, keyW);
				names[slot].set(entry.name());
				categories[slot].set(entry.category());
				float textX = inner + r(22);
				float textW = Math.max(r(40), keyX - r(16) - textX);
				MenuStyle.fitted(c, names[slot], textX, cy - r(11), MenuStyle.ROW_TEXT, textW);
				MenuStyle.fitted(c, categories[slot], textX, cy + r(15), MenuStyle.LABEL, textW);
			}
			if (shown.size() > rows) {
				float trackH = rows * r(ROW_H);
				float thumbH = Math.max(r(30), trackH * rows / shown.size());
				float thumbY = listY + (trackH - thumbH) * first / Math.max(1, shown.size() - rows);
				MenuStyle.box(c, right + r(8), thumbY, r(6), thumbH, r(3), MenuStyle.SCROLL_THUMB);
			}
		}
	}
}
