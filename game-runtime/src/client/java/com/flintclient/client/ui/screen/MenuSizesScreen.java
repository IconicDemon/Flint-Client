package com.flintclient.client.ui.screen;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.model.GameOptions;
import com.flintclient.client.control.model.IntSetting;
import com.flintclient.client.platform.Frame;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FlintFonts;
import com.flintclient.client.render.Icon;
import com.flintclient.client.render.MaskAtlas;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.TextLine;
import com.flintclient.client.ui.Backdrop;
import com.flintclient.client.ui.FlintFrame;
import com.flintclient.client.ui.FlintTooltip;
import com.flintclient.client.ui.MenuScale;
import com.flintclient.client.ui.Motion;
import com.flintclient.client.ui.screen.MenuGeometry.Part;
import com.flintclient.client.ui.widget.IconButton;
import com.flintclient.client.ui.widget.MenuButton;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Utilities › Menu sizes: a size for each part of Flint's main menu (the FLINT logo, the main buttons, the version line, the
 * Daily Rewards and What's New cards, the Flint | Vanilla switch, the account and its icons), 50 % to 200 % in steps of 10, and
 * Reset all. The main menu itself is drawn behind the page, laid out again at each change, so each size shows as it is picked;
 * the part a row changes is outlined while that row is under the pointer or focused. The page is a small card in the bottom-left
 * corner, the one corner the menu leaves free. Each change is saved at once (control-center.json's game options); Minecraft's own
 * title screen shows the switch and the cards at the same sizes.
 */
public final class MenuSizesScreen extends Screen {
	private static final int PANEL_W = 152;
	private static final int PAD = 7;
	private static final int HEADER_H = 13;
	private static final int NOTE_H = 6;
	private static final int ROW_H = 15;
	private static final int ROW_GAP = 2;
	private static final int STEP = 13;
	private static final int VALUE_W = 25;
	private static final int RESET_H = 15;
	/** The card's distance from the window's corner. */
	private static final int MARGIN = 10;
	private static final int PANEL_FILL = 0xF5111216;
	private static final float PANEL_RADIUS = 4.6F;
	private static final String[] PART_KEYS = {"logo", "buttons", "version", "rewards", "news", "switch", "topRight"};

	private final Screen parent;
	/** The main menu drawn behind the page (the one Utilities was opened from), or null: then the page's own backdrop. */
	private final @Nullable FlintTitleScreen menu;
	private final TextLine heading;
	private final TextLine note;
	private final List<Row> rows = new ArrayList<>();
	private MenuButton reset;
	private int panelX;
	private int panelY;
	private int panelH;
	/** The outline round the part being sized: which part, and how far it is shown (eased). */
	private @Nullable Part outlined;
	private float outline;

	/** One part's row: its name, its size, and Smaller and Larger. */
	private static final class Row {
		final Part part;
		final IntSetting setting;
		final Component name;
		final TextLine label;
		final TextLine value = new TextLine(FlintFonts.STRONG, "");
		IconButton smaller;
		IconButton larger;
		int y;

		Row(Part part) {
			this.part = part;
			this.setting = GameOptions.MENU_SIZES.get(part.ordinal());
			this.name = Component.translatable("flintclient.menuSizes.part." + PART_KEYS[part.ordinal()]);
			this.label = new TextLine(FlintFonts.LABEL, name.getString());
		}
	}

	public MenuSizesScreen(Screen parent, @Nullable FlintTitleScreen menu) {
		super(Component.translatable("flintclient.menuSizes.title"));
		this.parent = parent;
		this.menu = menu;
		this.heading = new TextLine(FlintFonts.HEADING, getTitle().getString());
		this.note = new TextLine(FlintFonts.SMALL, Component.translatable(menu != null ? "flintclient.menuSizes.note" : "flintclient.menuSizes.noteAlone").getString());
	}

	@Override
	protected void init() {
		// Laid out in menu units, as the menu behind it (see MenuScale).
		MenuScale.adopt(this);
		if (menu != null) {
			// The window may have changed since the menu was last laid out.
			relayMenu();
		}
		rows.clear();
		int parts = Part.values().length;
		panelH = 2 * PAD + HEADER_H + 5 + NOTE_H + 7 + parts * ROW_H + (parts - 1) * ROW_GAP + 8 + RESET_H;
		int panelW = Math.min(PANEL_W, width - 2 * MARGIN);
		if (menu != null) {
			// Bottom-left: clear of the logo and the buttons in the middle, the switch above and the cards at the right.
			panelX = Math.min(MARGIN, Math.max(0, width - panelW));
			panelY = Math.max(4, height - MARGIN - panelH);
		} else {
			panelX = (width - panelW) / 2;
			panelY = Math.max(4, (height - panelH) / 2);
		}
		int left = panelX + PAD;
		int right = panelX + panelW - PAD;

		IconButton back = addRenderableWidget(new IconButton(CommonComponents.GUI_BACK, Icon.CHEVRON_LEFT, this::onClose));
		back.tooltip(CommonComponents.GUI_BACK);
		back.setRectangle(HEADER_H, HEADER_H, left, panelY + PAD);

		int y = panelY + PAD + HEADER_H + 5 + NOTE_H + 7;
		IconButton first = null;
		for (Part part : Part.values()) {
			Row row = new Row(part);
			row.y = y;
			row.smaller = addRenderableWidget(new IconButton(Component.translatable("flintclient.menuSizes.smaller", row.name), Icon.MINUS,
				() -> step(row, -1)));
			row.larger = addRenderableWidget(new IconButton(Component.translatable("flintclient.menuSizes.larger", row.name), Icon.PLUS,
				() -> step(row, 1)));
			int buttonY = y + (ROW_H - STEP) / 2;
			row.larger.setRectangle(STEP, STEP, right - STEP, buttonY);
			row.smaller.setRectangle(STEP, STEP, right - STEP - VALUE_W - STEP, buttonY);
			rows.add(row);
			if (first == null) {
				first = row.larger;
			}
			y += ROW_H + ROW_GAP;
		}
		reset = addRenderableWidget(new MenuButton(Component.translatable("flintclient.menuSizes.reset"), Icon.RESET, MenuButton.Style.NEUTRAL,
			this::resetAll));
		reset.setRectangle(right - left, RESET_H, left, y - ROW_GAP + 8);
		refresh();
		setInitialFocus(first);
	}

	/** The size {@code row} shows now, in percent. */
	private static int size(Row row) {
		try {
			return ControlState.settings().gameInt(row.setting);
		} catch (RuntimeException | LinkageError e) {
			return row.setting.defaultValue();
		}
	}

	/** One step smaller ({@code -1}) or larger ({@code 1}): saved, and the menu behind laid out for it. */
	private void step(Row row, int direction) {
		IntSetting setting = row.setting;
		int next = Math.clamp(size(row) + direction * setting.step(), setting.min(), setting.max());
		set(List.of(row), next);
	}

	private void resetAll() {
		set(rows, -1);
	}

	/** Sets each of {@code changed} to {@code value} (-1: its default), saves, and lays the menu out again. */
	private void set(List<Row> changed, int value) {
		boolean any = false;
		try {
			for (Row row : changed) {
				any |= ControlState.settings().setGame(row.setting, value < 0 ? row.setting.defaultValue() : value);
			}
			if (any) {
				ControlState.saveAll();
			}
		} catch (RuntimeException | LinkageError e) {
			FlintClient.LOGGER.warn("Flint: couldn't save the menu sizes", e);
		}
		if (any) {
			relayMenu();
			refresh();
		}
	}

	/** The menu behind, laid out again for the sizes (and the window) as they are now. */
	private void relayMenu() {
		if (menu == null) {
			return;
		}
		try {
			menu.sizesChanged();
		} catch (RuntimeException | LinkageError e) {
			FlintClient.LOGGER.warn("Flint: couldn't lay the main menu out for its new sizes", e);
		}
	}

	/** Each row's size, Smaller and Larger available only short of the ends, Reset all only with a size changed. */
	private void refresh() {
		boolean changed = false;
		for (Row row : rows) {
			int now = size(row);
			row.value.set(row.setting.format(now));
			row.smaller.active = now > row.setting.min();
			row.larger.active = now < row.setting.max();
			changed |= now != row.setting.defaultValue();
		}
		reset.active = changed;
	}

	/** The row under the pointer, else (from the keyboard) the one whose buttons have the focus, or null. */
	private @Nullable Row litRow(int mouseX, int mouseY) {
		int panelW = Math.min(PANEL_W, width - 2 * MARGIN);
		for (Row row : rows) {
			if (mouseX >= panelX && mouseX < panelX + panelW && mouseY >= row.y - ROW_GAP / 2 && mouseY < row.y + ROW_H + ROW_GAP / 2) {
				return row;
			}
		}
		if (!minecraft.getLastInputType().isKeyboard()) {
			return null;
		}
		for (Row row : rows) {
			if (row.smaller.isFocused() || row.larger.isFocused()) {
				return row;
			}
		}
		return null;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		if (menu != null) {
			// The main menu as it is now, without the pointer: what each size looks like.
			menu.extractBackground(graphics, -1, -1, partialTick);
			menu.extractRenderState(graphics, -1, -1, partialTick);
			FlintFrame.begin(graphics);
			return;
		}
		FlintFrame.begin(graphics);
		Canvas c = Canvas.beginAtGuiScale(Frame.of(graphics), MenuScale.pixels());
		try {
			Backdrop.paint(c, width, height, Backdrop.PAGE);
		} finally {
			c.end();
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int guiMouseX, int guiMouseY, float partialTick) {
		int mouseX = (int) MenuScale.toMenu(guiMouseX);
		int mouseY = (int) MenuScale.toMenu(guiMouseY);
		// Over the menu behind: the card and its text come above the menu's own text.
		graphics.nextStratum();
		Canvas c = Canvas.beginAtGuiScale(Frame.of(graphics), MenuScale.pixels());
		try {
			Row lit = litRow(mouseX, mouseY);
			paintOutline(c, lit == null ? null : lit.part);
			paintPanel(c, lit);
			super.extractRenderState(graphics, mouseX, mouseY, partialTick);
			FlintTooltip.draw(graphics, width, height);
		} finally {
			c.end();
		}
		MaskAtlas.get().endFrame();
	}

	/** An ember outline round the part the lit row sizes, on the menu behind; it fades in and out, following the part. */
	private void paintOutline(Canvas c, @Nullable Part part) {
		if (menu == null) {
			return;
		}
		if (part != null) {
			outlined = part;
		}
		outline = Motion.toward(outline, part != null ? 1.0F : 0.0F, 140.0F);
		if (outline <= 0.0F || outlined == null) {
			return;
		}
		MenuGeometry.Box box = menu.partBounds(outlined);
		if (box == null) {
			return;
		}
		float t = Motion.smooth(outline);
		int pad = c.px(2.5F);
		int x = c.px(box.x()) - pad;
		int y = c.px(box.y()) - pad;
		int w = c.px(box.w()) + 2 * pad;
		int h = c.px(box.h()) + 2 * pad;
		int line = Math.max(1, Math.round(c.scale() / 3.0F));
		c.ring(x, y, w, h, c.px(3.5F), line, Palette.withAlpha(Palette.ACCENT, 0.9F * t));
	}

	private void paintPanel(Canvas c, @Nullable Row lit) {
		int panelW = Math.min(PANEL_W, width - 2 * MARGIN);
		int x = c.px(panelX);
		int y = c.px(panelY);
		int w = c.px(panelW);
		int h = c.px(panelH);
		int radius = c.px(PANEL_RADIUS);
		int border = Math.max(1, Math.round(c.scale() / 4.0F));
		// A soft shadow, then the card (as the account card's).
		for (int i = 3; i >= 1; i--) {
			int spread = c.px(1.2F * i);
			c.roundRect(x - spread, y - spread + c.px(1.5F), w + 2 * spread, h + 2 * spread, radius + spread, 0x14000000);
		}
		c.panel(x, y, w, h, radius, border, Palette.BORDER_HOVER, PANEL_FILL);
		int left = panelX + PAD;
		c.text(heading, c.px(left + HEADER_H + 5), c.baselineFor(heading, c.px(panelY + PAD + HEADER_H / 2.0F)), Palette.TEXT);
		int noteY = panelY + PAD + HEADER_H + 5;
		c.text(note, c.px(left), c.baselineFor(note, c.px(noteY + NOTE_H / 2.0F)), Palette.MUTED, c.px(panelW - 2 * PAD));
		int line = Math.max(1, Math.round(c.scale() / 4.0F));
		int separator = c.px(noteY + NOTE_H + 3.5F);
		c.fill(c.px(left), separator, c.px(panelX + panelW - PAD), separator + line, 0x14FFFFFF);
		for (Row row : rows) {
			boolean on = row == lit;
			int middle = c.px(row.y + ROW_H / 2.0F);
			int labelRoom = c.px(row.smaller.getX() - 4 - left);
			c.text(row.label, c.px(left), c.baselineFor(row.label, middle), on ? Palette.TEXT : Palette.TEXT_DIM, labelRoom);
			int valueCenter = c.px(row.smaller.getX() + STEP + VALUE_W / 2.0F);
			c.textCentered(row.value, valueCenter, c.baselineFor(row.value, middle), size(row) == row.setting.defaultValue() ? Palette.TEXT_DIM
				: Palette.ACCENT_TEXT);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		return super.mouseClicked(MenuScale.toMenu(event), doubleClick);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		return super.mouseReleased(MenuScale.toMenu(event));
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		return super.mouseDragged(MenuScale.toMenu(event), MenuScale.toMenu(dx), MenuScale.toMenu(dy));
	}

	/** The wheel over a row steps its size, as Smaller and Larger do. */
	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		double mx = MenuScale.toMenu(x);
		double my = MenuScale.toMenu(y);
		int panelW = Math.min(PANEL_W, width - 2 * MARGIN);
		if (scrollY != 0 && mx >= panelX && mx < panelX + panelW) {
			for (Row row : rows) {
				if (my >= row.y && my < row.y + ROW_H) {
					step(row, scrollY > 0 ? 1 : -1);
					return true;
				}
			}
		}
		return super.mouseScrolled(mx, my, scrollX, scrollY);
	}

	@Override
	public void mouseMoved(double x, double y) {
		super.mouseMoved(MenuScale.toMenu(x), MenuScale.toMenu(y));
	}
}
