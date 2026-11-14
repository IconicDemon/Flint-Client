package com.flintclient.client.control.rshift;

import static com.flintclient.client.control.rshift.MenuStyle.d;

import com.flintclient.client.control.rshift.MenuStyle.Label;
import com.flintclient.client.control.rshift.MenuStyle.Type;
import com.flintclient.client.control.rshift.SocialParts.Button;
import com.flintclient.client.control.rshift.SocialParts.Lines;
import com.flintclient.client.platform.Clock;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.kit.Kit;
import com.flintclient.client.ui.kit.Node;

/**
 * The Library's question before something goes that other things need ("Iris needs Sodium · Remove Iris too?"), in the
 * menu's own style (the Cosmetics notice's card): a title, a sentence, Cancel and a quiet-red confirming button. Escape, a
 * click outside or Cancel keep everything; only the confirming button acts.
 */
final class LibraryConfirm extends Node {
	private static final float W = 600;
	private static final float PAD = 32;
	private static final float ICON = 44;

	private final Label title;
	private final Lines body;
	private final Button cancel;
	private final Button confirm;
	private final long openedAt = Clock.millis();
	private int rise;
	private float opacity = 1;
	private float cardX;
	private float cardY;
	private float cardW;
	private float cardH;

	LibraryConfirm(String title, String body, String confirmLabel, Runnable onConfirm) {
		this.title = new Label(Type.EMPTY, title);
		this.body = new Lines(Type.EMPTY_NOTE, body);
		this.cancel = add(new Button("Cancel", null, PageControls.Look.PLAIN, this::close));
		this.confirm = add(new Button(confirmLabel, Glyph.TRASH, PageControls.Look.DANGER, () -> {
			close();
			onConfirm.run();
		}));
		name(title);
	}

	private void close() {
		if (screen() != null) {
			screen().closeModal(this);
		}
	}

	@Override
	public boolean interactive() {
		return true;
	}

	@Override
	public String narration() {
		return title.text() + ". " + body.text();
	}

	@Override
	public boolean mouseDown(float mx, float my, int button) {
		if (mx < cardX || mx > cardX + cardW || my < cardY || my > cardY + cardH) {
			close();
		}
		return false;
	}

	private float textW() {
		return cardW - 2 * d(PAD) - d(ICON) - d(18);
	}

	@Override
	protected void layout() {
		cardW = Math.min(d(W), w - 2 * d(24));
		float textH = d(26) + d(10) + body.height(measure(), textW());
		cardH = d(PAD) + Math.max(d(ICON), textH) + d(28) + d(Button.H) + d(PAD);
		cardX = x + Math.round((w - cardW) / 2);
		cardY = y + Math.round((h - cardH) / 2);
		float bw = Math.max(d(140), confirm.prefWidth());
		float cw = Math.max(d(110), cancel.prefWidth());
		float by = cardY + cardH - d(PAD) - d(Button.H);
		confirm.setBounds(cardX + cardW - d(PAD) - bw, by, bw, d(Button.H));
		cancel.setBounds(cardX + cardW - d(PAD) - bw - d(10) - cw, by, cw, d(Button.H));
	}

	@Override
	protected void paint(Canvas c) {
		float t = Kit.reduced() ? 1 : Math.min(1, (Clock.millis() - openedAt) / 160.0F);
		float eased = 1 - (1 - t) * (1 - t) * (1 - t);
		rise = Math.round(c.px(d(10)) * (1 - eased));
		opacity = c.opacity();
		c.setOpacity(opacity * eased);
		c.pushOffset(0, rise);
		MenuStyle.block(c, cardX, cardY, cardW, cardH, MenuStyle.radius(), 0, 0);
		float ix = cardX + d(PAD);
		float iy = cardY + d(PAD);
		MenuStyle.box(c, ix, iy, d(ICON), d(ICON), d(ICON) / 2, Palette.withAlpha(MenuStyle.ACCENT, 0.14F));
		Glyph.INFO.draw(c, ix + d(ICON) / 2, iy + d(ICON) / 2, d(20), MenuStyle.ACCENT);
		float tx = ix + d(ICON) + d(18);
		MenuStyle.text(c, title, tx, iy + d(13), MenuStyle.EMPTY_TEXT);
		body.paint(c, tx, iy + d(26) + d(10), textW(), MenuStyle.SENTENCE_TEXT);
	}

	@Override
	protected void paintOver(Canvas c) {
		c.popOffset();
		c.setOpacity(opacity);
	}
}
