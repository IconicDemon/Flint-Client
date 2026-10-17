package com.flintclient.client.ui.screen;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FlintFonts;
import com.flintclient.client.render.Icon;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.Paragraph;
import com.flintclient.client.render.TextLine;
import com.flintclient.client.ui.widget.MenuButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * An honest page for a Flint feature that doesn't exist yet. It says so plainly: no sample items,
 * prices, balances or fake content. Each feature gets its own page, so it can grow into the real thing.
 */
public final class ComingSoonScreen extends FlintPageScreen {
	private static final int CARD_W = 150;

	private final Icon icon;
	private final TextLine status;
	private final Paragraph body;
	private int cardY;

	private ComingSoonScreen(Screen parent, Component title, Icon icon, Component status, Component body) {
		super(parent, title);
		this.icon = icon;
		this.status = new TextLine(FlintFonts.STRONG, status.getString());
		this.body = new Paragraph(FlintFonts.LABEL, body.getString());
	}

	public static ComingSoonScreen cosmetics(Screen parent) {
		return new ComingSoonScreen(parent, Component.translatable("flintclient.menu.cosmetics"), Icon.GEM,
			Component.translatable("flintclient.soon.status"), Component.translatable("flintclient.cosmetics.body"));
	}

	public static ComingSoonScreen messages(Screen parent) {
		return new ComingSoonScreen(parent, Component.translatable("flintclient.menu.messages"), Icon.MESSAGE,
			Component.translatable("flintclient.soon.status"), Component.translatable("flintclient.messages.body"));
	}

	@Override
	protected void initPage(int contentTop) {
		cardY = Math.max(contentTop, height / 2 - 44);
		MenuButton done = addRenderableWidget(new MenuButton(Component.translatable("flintclient.back.menu"), null, MenuButton.Style.NEUTRAL, this::onClose));
		done.setRectangle(80, 15, (width - 80) / 2, Math.min(height - 24, cardY + 68));
		setInitialFocus(done);
	}

	@Override
	protected void paintPage(Canvas c) {
		int w = c.px(Math.min(CARD_W, width - 2 * MARGIN));
		int x = (c.px(width) - w) / 2;
		int y = c.px(cardY);
		int badge = c.px(16);
		int bx = (c.px(width) - badge) / 2;
		c.roundRect(bx, y, badge, badge, badge / 2, 0xFF1D1610);
		int iconPx = c.px(7);
		c.icon(icon, bx + (badge - iconPx) / 2, y + (badge - iconPx) / 2, iconPx, Palette.ACCENT_TEXT);
		int statusBaseline = y + badge + c.px(13);
		c.text(status, (c.px(width) - c.width(status)) / 2, statusBaseline, Palette.TEXT);
		int line = body.lineHeightPx(c);
		int baseline = statusBaseline + c.px(9);
		for (TextLine text : body.lines(c, w)) {
			c.text(text, (c.px(width) - c.width(text)) / 2, baseline, Palette.MUTED);
			baseline += line;
		}
	}
}
