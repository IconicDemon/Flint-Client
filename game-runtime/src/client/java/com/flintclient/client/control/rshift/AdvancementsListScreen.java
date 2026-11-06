package com.flintclient.client.control.rshift;

import static com.flintclient.client.control.rshift.MenuStyle.r;

import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.ToolMods.AdvancementsScreen;
import com.flintclient.client.control.rshift.MenuStyle.Label;
import com.flintclient.client.control.rshift.MenuStyle.Type;
import com.flintclient.client.platform.KeyInput;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.kit.KitScreen;
import com.flintclient.client.ui.kit.Node;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2fStack;
import org.jspecify.annotations.Nullable;

/**
 * Custom Advancements Screen: Minecraft's advancements as lists instead of a tree. The tabs on the left with how far each
 * is done; the chosen tab's advancements in the middle, what's missing first; and on the right the chosen advancement's
 * criteria, each in the Done or Missing color. Hidden advancements stay hidden until they're done, as Minecraft keeps
 * them. The window keeps the margins the settings give it. In the menu's theme.
 */
public final class AdvancementsListScreen extends KitScreen {
	private @Nullable Panel panel;

	public AdvancementsListScreen() {
		super("Advancements", 320, 200);
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
	}

	/** Up and Down choose an advancement wherever the focus is. */
	@Override
	protected boolean keyDown(KeyInput event) {
		if (panel != null && (event.isUp() || event.isDown())) {
			return panel.keyDown(event);
		}
		return super.keyDown(event);
	}

	/** One advancement as listed. */
	private record Entry(AdvancementHolder holder, DisplayInfo display, ItemStack icon, String title, String description, boolean done, float percent,
		String progress, List<String> criteria, List<Boolean> met) {
	}

	/** One tab (a root) with its advancements. */
	private record Tab(ItemStack icon, String title, List<Entry> entries, int done, int hidden) {
	}

	private final class Panel extends Node {
		private static final float TAB_H = 104;
		private static final float ROW_H = 92;
		private static final float CRITERION_H = 46;
		private static final float PAD = 30;
		private final List<Tab> tabs = new ArrayList<>();
		private final Label title = new Label(Type.SECTION_TITLE, "Advancements");
		private final Label total = new Label(Type.ROW_NOTE, "");
		private final Label missingOnlyLabel = new Label(Type.CHIP, "Missing only");
		private final Label empty = new Label(Type.ROW_LABEL, "");
		private final Label info = new Label(Type.SECTION_TITLE, "");
		private final Label infoType = new Label(Type.ROW_NOTE, "");
		private final Label infoProgress = new Label(Type.ROW_NOTE, "");
		private final List<Label> infoLines = new ArrayList<>();
		private final List<Label> tabTitles = new ArrayList<>();
		private final List<Label> tabCounts = new ArrayList<>();
		private final List<Label> rowTitles = new ArrayList<>();
		private final List<Label> rowNotes = new ArrayList<>();
		private final List<Label> rowStatus = new ArrayList<>();
		private final List<Label> criteria = new ArrayList<>();
		private final List<Entry> shown = new ArrayList<>();
		private int tab;
		private int chosen;
		private int firstRow;
		private int firstCriterion;
		private boolean missingOnly;
		private long loadedAt;
		private float wx;
		private float wy;
		private float ww;
		private float wh;
		private float tabsW;
		private float infoW;
		private float listX;
		private float listW;
		private float bodyY;
		private float chipX;
		private float chipW;

		Panel() {
			load();
		}

		private ModuleState settings() {
			return Mods.state(Mods.index(AdvancementsScreen.ID));
		}

		/** Reads the advancements from the server's last update (again each second, so progress shows as it happens). */
		private void load() {
			loadedAt = System.currentTimeMillis();
			tabs.clear();
			ClientPacketListener connection = Minecraft.getInstance().getConnection();
			if (connection == null) {
				return;
			}
			ClientAdvancements advancements = connection.getAdvancements();
			for (AdvancementNode root : com.flintclient.client.compat.McCompat.advancementTree(advancements).roots()) {
				DisplayInfo rootDisplay = root.advancement().display().orElse(null);
				if (rootDisplay == null) {
					continue;
				}
				List<Entry> entries = new ArrayList<>();
				int done = 0;
				int hidden = 0;
				for (AdvancementNode node : com.flintclient.client.compat.McCompat.advancementTree(advancements).nodes()) {
					if (node.root() != root) {
						continue;
					}
					DisplayInfo display = node.advancement().display().orElse(null);
					if (display == null) {
						continue;
					}
					AdvancementProgress progress = com.flintclient.client.compat.McCompat.advancementProgress(advancements).get(node.holder());
					boolean isDone = progress != null && progress.isDone();
					if (com.flintclient.client.compat.McCompat.hidden(display) && !isDone) {
						hidden++;
						continue;
					}
					List<String> names = new ArrayList<>();
					List<Boolean> met = new ArrayList<>();
					if (progress != null) {
						for (String criterion : progress.getCompletedCriteria()) {
							names.add(pretty(criterion));
							met.add(true);
						}
						for (String criterion : progress.getRemainingCriteria()) {
							names.add(pretty(criterion));
							met.add(false);
						}
					}
					Component text = progress == null ? null : progress.getProgressText();
					entries.add(new Entry(node.holder(), display, com.flintclient.client.compat.McCompat.icon(display).create(), com.flintclient.client.compat.McCompat.title(display).getString(), com.flintclient.client.compat.McCompat.description(display).getString(),
						isDone, progress == null ? 0 : progress.getPercent(), text == null ? "" : text.getString(), names, met));
					if (isDone) {
						done++;
					}
				}
				// What's missing first (the closest to done at the top), then what's done.
				entries.sort(Comparator.comparing(Entry::done).thenComparing(e -> -e.percent()).thenComparing(Entry::title));
				tabs.add(new Tab(com.flintclient.client.compat.McCompat.icon(rootDisplay).create(), com.flintclient.client.compat.McCompat.title(rootDisplay).getString(), entries, done, hidden));
			}
			tab = Math.clamp(tab, 0, Math.max(0, tabs.size() - 1));
			refilter();
			int all = 0;
			int allDone = 0;
			for (Tab t : tabs) {
				all += t.entries().size() + t.hidden();
				allDone += t.done();
			}
			total.set(allDone + " of " + all + " done");
		}

		/** "minecraft:plains" and "kill_a_mob" as "Plains" and "Kill A Mob". */
		private static String pretty(String criterion) {
			String name = criterion.substring(criterion.indexOf(':') + 1).replace('/', ' ').replace('_', ' ');
			StringBuilder out = new StringBuilder(name.length());
			boolean upper = true;
			for (char ch : name.toCharArray()) {
				out.append(upper ? Character.toUpperCase(ch) : ch);
				upper = ch == ' ';
			}
			return out.toString();
		}

		private void refilter() {
			Entry was = chosen >= 0 && chosen < shown.size() ? shown.get(chosen) : null;
			shown.clear();
			if (!tabs.isEmpty()) {
				for (Entry entry : tabs.get(tab).entries()) {
					if (!missingOnly || !entry.done()) {
						shown.add(entry);
					}
				}
			}
			chosen = 0;
			if (was != null) {
				for (int i = 0; i < shown.size(); i++) {
					if (shown.get(i).holder().equals(was.holder())) {
						chosen = i;
					}
				}
			}
		}

		@Override
		public boolean interactive() {
			return true;
		}

		@Override
		protected void layout() {
			ModuleState s = settings();
			KitScreen screen = screen();
			float mx = screen == null ? r(60) : screen.toUiX(s.integer(AdvancementsScreen.MARGIN_X)) - screen.toUiX(0);
			float my = screen == null ? r(60) : screen.toUiY(s.integer(AdvancementsScreen.MARGIN_Y)) - screen.toUiY(0);
			wx = x + Math.min(mx, w * 0.3F);
			wy = y + Math.min(my, h * 0.3F);
			ww = w - 2 * (wx - x);
			wh = h - 2 * (wy - y);
			tabsW = Math.min(r(640), ww * 0.26F);
			float infoUnits = screen == null ? r(600) : screen.toUiX(s.integer(AdvancementsScreen.INFO_WIDTH)) - screen.toUiX(0);
			infoW = Math.clamp(infoUnits * 2.6F, r(560), ww * 0.38F);
			listX = wx + r(PAD) + tabsW + r(PAD);
			listW = ww - (listX - wx) - infoW - r(PAD) * 2;
			bodyY = wy + r(PAD) + r(60) + r(PAD);
		}

		private int tabAt(float mx, float my) {
			if (mx < wx + r(PAD) || mx > wx + r(PAD) + tabsW || my < bodyY) {
				return -1;
			}
			int i = (int) Math.floor((my - bodyY) / r(TAB_H));
			return i >= 0 && i < tabs.size() ? i : -1;
		}

		private int rowAt(float mx, float my) {
			if (mx < listX || mx > listX + listW || my < bodyY) {
				return -1;
			}
			int i = firstRow + (int) Math.floor((my - bodyY) / r(ROW_H));
			return i >= firstRow && i < shown.size() && my < wy + wh - r(PAD) ? i : -1;
		}

		private int visibleRows() {
			return Math.max(1, (int) ((wy + wh - r(PAD) - bodyY) / r(ROW_H)));
		}

		@Override
		public boolean mouseDown(float mx, float my, int button) {
			if (mx >= chipX && mx < chipX + chipW && my >= wy + r(PAD) && my < wy + r(PAD) + r(56)) {
				missingOnly = !missingOnly;
				refilter();
				firstRow = 0;
				return true;
			}
			int t = tabAt(mx, my);
			if (t >= 0) {
				tab = t;
				chosen = 0;
				firstRow = 0;
				firstCriterion = 0;
				refilter();
				return true;
			}
			int row = rowAt(mx, my);
			if (row >= 0) {
				chosen = row;
				firstCriterion = 0;
			}
			return true;
		}

		@Override
		public boolean scroll(float mx, float my, double amount) {
			int by = -(int) Math.signum(amount);
			if (mx >= listX + listW) {
				firstCriterion = Math.max(0, firstCriterion + by);
			} else {
				firstRow = Math.clamp(firstRow + by, 0, Math.max(0, shown.size() - visibleRows()));
			}
			return true;
		}

		@Override
		public boolean keyDown(KeyInput event) {
			if (event.isDown() || event.isUp()) {
				chosen = Math.clamp(chosen + (event.isDown() ? 1 : -1), 0, Math.max(0, shown.size() - 1));
				firstCriterion = 0;
				if (chosen < firstRow) {
					firstRow = chosen;
				} else if (chosen >= firstRow + visibleRows()) {
					firstRow = chosen - visibleRows() + 1;
				}
				return true;
			}
			return super.keyDown(event);
		}

		private static Label label(List<Label> labels, int i, Type type) {
			while (labels.size() <= i) {
				labels.add(new Label(type, ""));
			}
			return labels.get(i);
		}

		@Override
		protected void paint(Canvas c) {
			if (System.currentTimeMillis() - loadedAt > 1000) {
				load();
			}
			ModuleState s = settings();
			int doneColor = 0xFF000000 | s.rgb(AdvancementsScreen.DONE);
			int missingColor = 0xFF000000 | s.rgb(AdvancementsScreen.MISSING);
			MenuStyle.block(c, wx, wy, ww, wh, MenuStyle.radius(), 0, 0);
			// Header: title, how many are done, the Missing only chip.
			MenuStyle.text(c, title, wx + r(PAD), wy + r(PAD) + r(28), MenuStyle.ROW_TEXT);
			MenuStyle.text(c, total, wx + r(PAD) + title.width(c) + r(24), wy + r(PAD) + r(30), MenuStyle.LABEL);
			chipW = missingOnlyLabel.width(c) + r(60);
			chipX = wx + ww - r(PAD) - chipW;
			MenuStyle.box(c, chipX, wy + r(PAD), chipW, r(56), r(10), missingOnly ? MenuStyle.CHIP_SELECTED : MenuStyle.CHIP);
			MenuStyle.centered(c, missingOnlyLabel, chipX + chipW / 2, wy + r(PAD) + r(28), missingOnly ? MenuStyle.CHIP_SELECTED_TEXT : MenuStyle.CHIP_TEXT,
				chipW);
			KitScreen screen = screen();
			float mx = screen == null ? -1 : screen.mouseX();
			float my = screen == null ? -1 : screen.mouseY();
			if (tabs.isEmpty()) {
				empty.set("No advancements yet");
				MenuStyle.text(c, empty, wx + r(PAD), bodyY + r(40), MenuStyle.LABEL);
				return;
			}
			// Tabs.
			int hoverTab = tabAt(mx, my);
			for (int i = 0; i < tabs.size(); i++) {
				Tab t = tabs.get(i);
				float top = bodyY + i * r(TAB_H);
				if (top + r(TAB_H) > wy + wh - r(PAD)) {
					break;
				}
				float left = wx + r(PAD);
				if (i == tab || i == hoverTab) {
					MenuStyle.box(c, left, top + r(4), tabsW, r(TAB_H) - r(8), r(14), i == tab ? MenuStyle.CARD_HOVER : MenuStyle.CHIP);
				}
				if (i == tab) {
					MenuStyle.box(c, left, top + r(22), r(5), r(TAB_H) - r(44), r(2.5F), MenuStyle.ACCENT);
				}
				item(c, t.icon(), left + r(22), top + r(TAB_H) / 2 - r(22), r(44));
				Label name = label(tabTitles, i, Type.ROW_LABEL);
				name.set(t.title());
				int all = t.entries().size() + t.hidden();
				Label count = label(tabCounts, i, Type.ROW_NOTE);
				count.set(t.done() + " / " + all);
				float textX = left + r(84);
				MenuStyle.fitted(c, name, textX, top + r(TAB_H) / 2 - r(14), MenuStyle.ROW_TEXT, tabsW - r(100));
				MenuStyle.fitted(c, count, textX, top + r(TAB_H) / 2 + r(14), MenuStyle.LABEL, tabsW - r(100));
				float barW = tabsW - r(100);
				float barY = top + r(TAB_H) - r(18);
				MenuStyle.box(c, textX, barY, barW, r(5), r(2.5F), MenuStyle.TRACK_OFF);
				if (t.done() > 0) {
					MenuStyle.box(c, textX, barY, Math.max(r(5), barW * t.done() / Math.max(1, all)), r(5), r(2.5F), doneColor);
				}
			}
			// The chosen tab's advancements.
			int hoverRow = rowAt(mx, my);
			int rows = visibleRows();
			if (shown.isEmpty()) {
				empty.set(missingOnly ? "Nothing missing here" : "Nothing here yet");
				MenuStyle.text(c, empty, listX + r(20), bodyY + r(ROW_H) / 2, MenuStyle.LABEL);
			}
			for (int slot = 0; slot < rows && firstRow + slot < shown.size(); slot++) {
				int index = firstRow + slot;
				Entry entry = shown.get(index);
				float top = bodyY + slot * r(ROW_H);
				float cy = top + r(ROW_H) / 2;
				if (index == chosen || index == hoverRow) {
					MenuStyle.box(c, listX, top + r(4), listW, r(ROW_H) - r(8), r(14), index == chosen ? MenuStyle.CARD_HOVER : MenuStyle.CHIP);
				}
				item(c, entry.icon(), listX + r(20), cy - r(22), r(44));
				Label status = label(rowStatus, slot, Type.VALUE);
				status.set(entry.done() ? "Done" : entry.progress().isEmpty() ? "Missing" : entry.progress());
				float statusW = status.width(c) + r(28);
				float statusX = listX + listW - statusW - r(16);
				MenuStyle.box(c, statusX, cy - r(22), statusW, r(44), r(9), Palette.withAlpha(entry.done() ? doneColor : missingColor, 0.16F));
				MenuStyle.centered(c, status, statusX + statusW / 2, cy, entry.done() ? doneColor : missingColor, statusW);
				Label name = label(rowTitles, slot, Type.ROW_LABEL);
				name.set(entry.title());
				Label note = label(rowNotes, slot, Type.ROW_NOTE);
				note.set(entry.description());
				float textX = listX + r(84);
				float textW = Math.max(r(40), statusX - r(16) - textX);
				MenuStyle.fitted(c, name, textX, cy - r(13), entry.done() ? MenuStyle.ROW_TEXT : MenuStyle.NAME_ON, textW);
				MenuStyle.fitted(c, note, textX, cy + r(15), MenuStyle.LABEL, textW);
			}
			if (shown.size() > rows) {
				float trackH = rows * r(ROW_H);
				float thumbH = Math.max(r(30), trackH * rows / shown.size());
				float thumbY = bodyY + (trackH - thumbH) * firstRow / Math.max(1, shown.size() - rows);
				MenuStyle.box(c, listX + listW + r(8), thumbY, r(6), thumbH, r(3), MenuStyle.SCROLL_THUMB);
			}
			// The chosen advancement: what it is and each of its criteria.
			float ix = wx + ww - r(PAD) - infoW;
			MenuStyle.box(c, ix, bodyY, infoW, wy + wh - r(PAD) - bodyY, r(16), MenuStyle.SECTION_CARD);
			if (chosen < 0 || chosen >= shown.size()) {
				return;
			}
			Entry entry = shown.get(chosen);
			float inner = ix + r(26);
			float innerW = infoW - r(52);
			item(c, entry.icon(), inner, bodyY + r(26), r(64));
			info.set(entry.title());
			MenuStyle.fitted(c, info, inner + r(84), bodyY + r(26) + r(20), MenuStyle.ROW_TEXT, innerW - r(84));
			infoType.set(com.flintclient.client.compat.McCompat.type(entry.display()).getDisplayName().getString());
			MenuStyle.fitted(c, infoType, inner + r(84), bodyY + r(26) + r(48), MenuStyle.LABEL, innerW - r(84));
			float y0 = bodyY + r(26) + r(64) + r(24);
			List<String> lines = wrap(c, entry.description(), innerW);
			for (int i = 0; i < lines.size(); i++) {
				Label line = label(infoLines, i, Type.ROW_NOTE);
				line.set(lines.get(i));
				MenuStyle.text(c, line, inner, y0 + i * r(32) + r(14), MenuStyle.SENTENCE_TEXT);
			}
			y0 += lines.size() * r(32) + r(20);
			int met = 0;
			for (boolean m : entry.met()) {
				met += m ? 1 : 0;
			}
			infoProgress.set(entry.criteria().isEmpty() ? "" : met + " of " + entry.criteria().size() + " criteria");
			MenuStyle.text(c, infoProgress, inner, y0 + r(14), MenuStyle.LABEL);
			y0 += r(40);
			int room = Math.max(0, (int) ((wy + wh - r(PAD) - r(16) - y0) / r(CRITERION_H)));
			firstCriterion = Math.clamp(firstCriterion, 0, Math.max(0, entry.criteria().size() - room));
			for (int slot = 0; slot < room && firstCriterion + slot < entry.criteria().size(); slot++) {
				int i = firstCriterion + slot;
				boolean m = entry.met().get(i);
				float cy = y0 + slot * r(CRITERION_H) + r(CRITERION_H) / 2;
				(m ? Glyph.CHECK : Glyph.CLOSE).draw(c, inner + r(14), cy, r(24), m ? doneColor : missingColor);
				Label name = label(criteria, slot, Type.ROW_LABEL);
				name.set(entry.criteria().get(i));
				MenuStyle.fitted(c, name, inner + r(42), cy, m ? doneColor : missingColor, innerW - r(42));
			}
		}

		/** {@code text} broken into lines no wider than {@code maxW} (at most four). */
		private List<String> wrap(Canvas c, String text, float maxW) {
			List<String> out = new ArrayList<>();
			Label probe = label(infoLines, 7, Type.ROW_NOTE);
			StringBuilder line = new StringBuilder();
			for (String word : text.split(" ")) {
				String next = line.isEmpty() ? word : line + " " + word;
				probe.set(next);
				if (probe.width(c) > maxW && !line.isEmpty()) {
					out.add(line.toString());
					line = new StringBuilder(word);
					if (out.size() == 4) {
						return out;
					}
				} else {
					line = new StringBuilder(next);
				}
			}
			if (!line.isEmpty()) {
				out.add(line.toString());
			}
			return out;
		}

		/** An item {@code size} units square, top-left at (x, y). */
		private static void item(Canvas c, ItemStack stack, float x, float y, float size) {
			if (stack.isEmpty()) {
				return;
			}
			Matrix3x2fStack pose = c.frame().graphics().pose();
			pose.pushMatrix();
			pose.translate(c.px(x), c.px(y));
			float px = c.px(x + size) - c.px(x);
			pose.scale(px / 16.0F, px / 16.0F);
			c.frame().graphics().item(stack, 0, 0);
			pose.popMatrix();
		}
	}
}
