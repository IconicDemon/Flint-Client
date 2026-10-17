package com.flintclient.client.ui.kit;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Icon;
import com.flintclient.client.render.TextLine;
import net.minecraft.client.input.KeyEvent;
import org.jspecify.annotations.Nullable;

/**
 * A text button for an action that needs a second thought ("Reset to defaults"): pressed, it turns in place into
 * the question and two text buttons, "Reset FPS settings?  Reset · Cancel", instead of opening a dialog. Focus goes
 * to Cancel (never to the confirming button), Escape cancels, and so does focus leaving it.
 */
public class InlineConfirm extends Node {
	public static final float HEIGHT = Button.TEXT_HEIGHT;
	private static final float GAP = 6;

	private final Button trigger;
	private final ConfirmButton confirm;
	private final ConfirmButton cancel;
	private final TextLine question;
	private final TextLine dot = new TextLine(Type.META, "·");
	private final Runnable action;
	private boolean confirming;
	private boolean alignRight;

	public InlineConfirm(String label, @Nullable Icon icon, String question, String confirmLabel, Runnable action) {
		this.action = action;
		this.question = new TextLine(Type.META, question);
		this.trigger = add(new Button(label, Button.Variant.GHOST, this::ask).icon(icon).iconSize(7));
		this.confirm = add(new ConfirmButton(this, confirmLabel, Button.Variant.DANGER_TEXT, this::confirmed));
		this.cancel = add(new ConfirmButton(this, "Cancel", Button.Variant.GHOST, () -> cancel(true)));
		this.h = HEIGHT;
		showState();
	}

	/** Lays it out against its right edge (actions at the end of a row). */
	public InlineConfirm alignRight() {
		this.alignRight = true;
		return this;
	}

	public Button trigger() {
		return trigger;
	}

	public void setQuestion(String text) {
		question.set(text);
	}

	public boolean confirming() {
		return confirming;
	}

	/** Disables the trigger with the reason (a reset with nothing to reset), or enables it again ({@code null}). */
	public void setReason(@Nullable String reason) {
		if (reason == null) {
			trigger.setEnabled(true);
		} else {
			trigger.setDisabled(reason);
			if (confirming) {
				cancel(false);
			}
		}
	}

	private void ask() {
		confirming = true;
		showState();
		KitScreen screen = screen();
		if (screen != null) {
			if (screen.focusVisible()) {
				screen.focusFromKeyboard(cancel);
			} else {
				screen.focus(null);
			}
			screen.announce(this);
		}
	}

	private void confirmed() {
		confirming = false;
		showState();
		action.run();
		refocusTrigger();
	}

	/** Back to the button; {@code refocus}: from the keyboard, focus returns to it. */
	public void cancel(boolean refocus) {
		if (!confirming) {
			return;
		}
		confirming = false;
		showState();
		if (refocus) {
			refocusTrigger();
		}
	}

	private void refocusTrigger() {
		KitScreen screen = screen();
		if (screen != null && screen.focusVisible() && trigger.canFocus()) {
			screen.focusFromKeyboard(trigger);
		}
	}

	private void showState() {
		trigger.setVisible(!confirming);
		confirm.setVisible(confirming);
		cancel.setVisible(confirming);
		invalidate();
	}

	@Override
	public String narration() {
		return confirming ? question.text() : trigger.narration();
	}

	@Override
	public float prefWidth() {
		return confirming ? questionWidth() + GAP + confirm.prefWidth() + 2 * GAP + dotWidth() + cancel.prefWidth() : trigger.prefWidth();
	}

	@Override
	public float prefHeight(float width) {
		return HEIGHT;
	}

	private float questionWidth() {
		Canvas c = measure();
		return (float) c.width(question) / c.scale();
	}

	private float dotWidth() {
		Canvas c = measure();
		return (float) c.width(dot) / c.scale();
	}

	@Override
	protected void layout() {
		float tw = trigger.prefWidth();
		trigger.setBounds(alignRight ? x + w - tw : x, y, tw, HEIGHT);
		float cancelW = cancel.prefWidth();
		float confirmW = confirm.prefWidth();
		float right = x + w;
		float buttons = confirmW + 2 * GAP + dotWidth() + cancelW;
		float start = alignRight ? right - buttons : Math.min(x + questionWidth() + GAP, right - buttons);
		confirm.setBounds(start, y, confirmW, HEIGHT);
		cancel.setBounds(start + confirmW + 2 * GAP + dotWidth(), y, cancelW, HEIGHT);
	}

	@Override
	protected void paint(Canvas c) {
		if (!confirming) {
			return;
		}
		int center = c.px(y + h / 2);
		int questionRight = c.px(confirm.x() - GAP);
		int left = alignRight ? Math.max(px0(c), questionRight - c.width(question)) : px0(c);
		c.text(question, left, c.baselineFor(question, center), Tokens2.TEXT_DIM, Math.max(0, questionRight - left));
		int dotX = c.px(confirm.x() + confirm.width() + GAP);
		c.text(dot, dotX, c.baselineFor(dot, center), Tokens2.faint());
	}

	/** Confirm and Cancel: Escape cancels; losing focus to something else cancels too. */
	private static final class ConfirmButton extends Button {
		private final InlineConfirm owner;

		ConfirmButton(InlineConfirm owner, String label, Variant variant, Runnable action) {
			super(label, variant, Type.META, action);
			this.owner = owner;
		}

		@Override
		public boolean handlesEscape() {
			return true;
		}

		@Override
		public boolean keyDown(KeyEvent event) {
			if (event.isEscape()) {
				owner.cancel(true);
				return true;
			}
			return super.keyDown(event);
		}

		@Override
		protected void focusChanged(boolean focused) {
			KitScreen screen = screen();
			if (!focused && screen != null) {
				Node now = screen.focused();
				if (now != owner.confirm && now != owner.cancel && now != null) {
					owner.cancel(false);
				}
			}
		}
	}
}
