package com.flintclient.client.ui.screen;

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
import net.minecraft.client.input.MouseButtonEvent;
import com.flintclient.client.ui.widget.IconButton;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Base for Flint's own pages: the menu's dark background, a back button and title in the top-left,
 * and content laid out by the subclass. Escape or Back returns to the menu exactly as it was.
 */
public abstract class FlintPageScreen extends Screen {
	protected static final int MARGIN = 14;
	protected static final int HEADER_H = 13;

	protected final Screen parent;
	private final TextLine heading;
	private IconButton back;

	protected FlintPageScreen(Screen parent, Component title) {
		super(title);
		this.parent = parent;
		this.heading = new TextLine(FlintFonts.HEADING, title.getString());
	}

	@Override
	protected final void init() {
		// Laid out in menu units: never smaller than Minecraft's automatic GUI scale (see MenuScale).
		MenuScale.adopt(this);
		back = addRenderableWidget(new IconButton(CommonComponents.GUI_BACK, Icon.CHEVRON_LEFT, this::onClose));
		back.tooltip(CommonComponents.GUI_BACK);
		back.setRectangle(HEADER_H, HEADER_H, MARGIN, MARGIN);
		initPage(MARGIN + HEADER_H + 12);
	}

	/** Adds the page's widgets; {@code contentTop} is the first free row below the header. */
	protected abstract void initPage(int contentTop);

	/** Draws page content that isn't a widget (text, panels); called before the widgets. */
	protected void paintPage(Canvas canvas) {
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		FlintFrame.begin(graphics);
		Canvas c = Canvas.beginAtGuiScale(graphics, MenuScale.pixels());
		try {
			Backdrop.paint(c, width, height, Backdrop.PAGE);
		} finally {
			c.end();
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int guiMouseX, int guiMouseY, float partialTick) {
		// Everything draws in menu units, inside one frame at the menu's scale; the widgets' canvases follow it.
		Canvas c = Canvas.beginAtGuiScale(graphics, MenuScale.pixels());
		try {
			c.text(heading, c.px(MARGIN + HEADER_H + 6), c.baselineFor(heading, c.px(MARGIN + HEADER_H / 2.0F)), Palette.TEXT);
			paintPage(c);
			super.extractRenderState(graphics, (int) MenuScale.toMenu(guiMouseX), (int) MenuScale.toMenu(guiMouseY), partialTick);
			FlintTooltip.draw(graphics, width, height);
		} finally {
			c.end();
		}
		MaskAtlas.get().endFrame();
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

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		return super.mouseScrolled(MenuScale.toMenu(x), MenuScale.toMenu(y), scrollX, scrollY);
	}

	@Override
	public void mouseMoved(double x, double y) {
		super.mouseMoved(MenuScale.toMenu(x), MenuScale.toMenu(y));
	}
}
