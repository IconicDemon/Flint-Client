package com.flintclient.client.ui.screen;

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
import com.flintclient.client.ui.widget.IconButton;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
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
	/** A step of the page threw: it draws and takes nothing more, and goes back to its parent at the next tick. */
	private boolean broken;

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

	/**
	 * The safety net for Flint's pages (Screenshots, Utilities, What's new, Credits): a page that throws while drawing or
	 * handling the mouse goes back to the screen it was opened from instead of taking the game down. Logged once with its
	 * stack, told to {@code feature.Faults} (the player's toast, the QA sweep's record).
	 */
	private void broke(String step, RuntimeException error) {
		if (broken) {
			return;
		}
		broken = true;
		com.flintclient.client.FlintClient.LOGGER.error("Flint: the {} page failed while {}; it goes back instead of crashing the game",
			getClass().getSimpleName(), step, error);
		com.flintclient.client.control.feature.Faults.screenFailed(getTitle().getString(), step, error);
	}

	@Override
	public void tick() {
		if (broken) {
			if (minecraft != null && minecraft.gui.screen() == this) {
				minecraft.gui.setScreen(parent);
			}
			return;
		}
		super.tick();
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
		Canvas c = Canvas.beginAtGuiScale(Frame.of(graphics), MenuScale.pixels());
		try {
			Backdrop.paint(c, width, height, Backdrop.PAGE);
		} finally {
			c.end();
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int guiMouseX, int guiMouseY, float partialTick) {
		if (broken) {
			return;
		}
		// Everything draws in menu units, inside one frame at the menu's scale; the widgets' canvases follow it.
		Canvas c = Canvas.beginAtGuiScale(Frame.of(graphics), MenuScale.pixels());
		try {
			c.text(heading, c.px(MARGIN + HEADER_H + 6), c.baselineFor(heading, c.px(MARGIN + HEADER_H / 2.0F)), Palette.TEXT);
			paintPage(c);
			super.extractRenderState(graphics, (int) MenuScale.toMenu(guiMouseX), (int) MenuScale.toMenu(guiMouseY), partialTick);
			FlintTooltip.draw(graphics, width, height);
		} catch (RuntimeException e) {
			broke("drawing", e);
		} finally {
			c.end();
		}
		MaskAtlas.get().endFrame();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		try {
			return !broken && super.mouseClicked(MenuScale.toMenu(event), doubleClick);
		} catch (RuntimeException e) {
			broke("handling a click", e);
			return true;
		}
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		try {
			return !broken && super.mouseReleased(MenuScale.toMenu(event));
		} catch (RuntimeException e) {
			broke("handling a click", e);
			return true;
		}
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		return super.mouseDragged(MenuScale.toMenu(event), MenuScale.toMenu(dx), MenuScale.toMenu(dy));
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		try {
			return !broken && super.mouseScrolled(MenuScale.toMenu(x), MenuScale.toMenu(y), scrollX, scrollY);
		} catch (RuntimeException e) {
			broke("handling the wheel", e);
			return true;
		}
	}

	@Override
	public void mouseMoved(double x, double y) {
		super.mouseMoved(MenuScale.toMenu(x), MenuScale.toMenu(y));
	}
}
