package com.flintclient.client.ui.kit;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.TextLine;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A modal confirmation: a 220-unit deck-coloured card over a scrim, with a title, an optional body, optional extra
 * content (a name field, a choice) and its buttons right-aligned. Escape and Cancel cancel it. Focus
 * starts in the extra content or on Cancel, never on the confirming button, so Enter confirms only
 * when that button has been focused on purpose; destructive confirmations use {@link Button.Variant#DANGER}.
 * Show it with {@link KitScreen#showModal}; either button closes it.
 */
public class Dialog extends Node {
	public static final float WIDTH = 220;
	private static final float PAD = 14;
	private static final float TITLE_GAP = 5;
	private static final float SECTION_GAP = 12;
	private static final float BUTTON_GAP = 6;
	private static final float SHADOW = 2;

	private final TextLine title;
	private final @Nullable Wrap body;
	private final Button cancel;
	private final Button confirm;
	private final @Nullable Runnable onConfirm;
	private @Nullable Runnable onCancel;
	private @Nullable Node extra;
	private final List<Node> scratch = new ArrayList<>();
	private float cardX;
	private float cardY;
	private float cardH;

	public Dialog(String title, @Nullable String body, String confirmLabel, Button.Variant confirmVariant, @Nullable Runnable onConfirm) {
		this.title = new TextLine(Type.NAME, title);
		this.body = body == null || body.isEmpty() ? null : new Wrap(Type.BODY, body);
		this.onConfirm = onConfirm;
		this.cancel = add(new Button("Cancel", Button.Variant.SECONDARY, this::cancel));
		this.confirm = add(new Button(confirmLabel, confirmVariant, this::confirm));
	}

	public Dialog cancelLabel(String label) {
		cancel.setLabel(label);
		return this;
	}

	public Dialog onCancel(@Nullable Runnable onCancel) {
		this.onCancel = onCancel;
		return this;
	}

	/** Content between the body and the buttons (laid out full width at its preferred height). */
	public Dialog extra(Node node) {
		if (extra != null) {
			remove(extra);
		}
		extra = add(node);
		return this;
	}

	/** The confirming button (disable it while the extra content is invalid). */
	public Button confirmButton() {
		return confirm;
	}

	public Button cancelButton() {
		return cancel;
	}

	/** The extra content's first control, else Cancel. */
	public Node initialFocus() {
		if (extra != null) {
			scratch.clear();
			extra.collectFocusable(scratch);
			if (!scratch.isEmpty()) {
				return scratch.getFirst();
			}
		}
		return cancel;
	}

	public void cancel() {
		close();
		if (onCancel != null) {
			onCancel.run();
		}
	}

	public void confirm() {
		if (!confirm.enabled()) {
			return;
		}
		close();
		if (onConfirm != null) {
			onConfirm.run();
		}
	}

	private void close() {
		if (screen != null) {
			screen.closeModal(this);
		}
	}

	@Override
	public String narration() {
		return title.text();
	}

	private float bodyHeight(Canvas c) {
		return body == null ? 0 : TITLE_GAP + body.height(c, WIDTH - 2 * PAD);
	}

	@Override
	protected void layout() {
		Canvas c = measure();
		float inner = WIDTH - 2 * PAD;
		float titleH = Type.lineHeight(Type.NAME);
		float extraH = extra == null ? 0 : extra.prefHeight(inner);
		cardH = PAD + titleH + bodyHeight(c) + (extra == null ? 0 : SECTION_GAP + extraH) + SECTION_GAP + Button.HEIGHT + PAD;
		cardX = x + Math.round((w - WIDTH) / 2);
		cardY = y + Math.round((h - cardH) / 2);
		if (extra != null) {
			extra.setBounds(cardX + PAD, cardY + PAD + titleH + bodyHeight(c) + SECTION_GAP, inner, extraH);
		}
		float buttonY = cardY + cardH - PAD - Button.HEIGHT;
		float confirmW = confirm.prefWidth();
		float cancelW = cancel.prefWidth();
		float right = cardX + WIDTH - PAD;
		confirm.setBounds(right - confirmW, buttonY, confirmW, Button.HEIGHT);
		cancel.setBounds(right - confirmW - BUTTON_GAP - cancelW, buttonY, cancelW, Button.HEIGHT);
	}

	@Override
	protected void paint(Canvas c) {
		int x0 = c.px(cardX);
		int y0 = c.px(cardY);
		int cw = c.px(cardX + WIDTH) - x0;
		int ch = c.px(cardY + cardH) - y0;
		int r = c.px(Tokens2.RADIUS_DECK);
		// A soft offset shadow (one extra draw; no blur), then the card with its hairline.
		c.roundRectCorners(x0, y0 + c.px(SHADOW), cw, ch, r, 0x50000000);
		c.roundRectCorners(x0, y0, cw, ch, r, 0xFF141312);
		c.ringCorners(x0, y0, cw, ch, r, 1, Tokens2.hairlineStrong());
		float titleH = Type.lineHeight(Type.NAME);
		int textLeft = c.px(cardX + PAD);
		int textW = c.px(WIDTH - 2 * PAD);
		c.text(title, textLeft, c.baselineFor(title, c.px(cardY + PAD + titleH / 2)), Tokens2.TEXT, textW);
		if (body != null) {
			body.paint(c, cardX + PAD, cardY + PAD + titleH + TITLE_GAP, WIDTH - 2 * PAD, Tokens2.muted());
		}
	}
}
