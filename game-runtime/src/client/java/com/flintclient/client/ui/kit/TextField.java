package com.flintclient.client.ui.kit;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FontFace;
import com.flintclient.client.render.Icon;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.TextLine;
import com.flintclient.client.ui.Motion;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.util.StringUtil;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * A single-line text field, 18 units tall: a faint fill with radius 5 and no outline until it has focus (then a
 * one-pixel ember hairline at 45%), the placeholder in faint body text, the caret (blinking unless motion is
 * reduced), selection, and an optional search icon and clear button ({@link SearchField}). Editing
 * follows {@link TextEditState}: Backspace/Delete, Left/Right, Home/End, Shift to select, the word
 * modifier for whole words (Ctrl, or Cmd on macOS as in Minecraft's own edit box, and Alt/Option), and
 * select all, copy, paste and cut with Minecraft's edit shortcut key (Cmd on macOS, Ctrl elsewhere:
 * {@code InputWithModifiers.isSelectAll()} etc.). Text longer than the field scrolls to keep the caret in
 * view. Measurements are redone only when the text, caret or scale changes.
 */
public class TextField extends Node {
	@FunctionalInterface
	public interface Listener {
		void changed(String text);
	}

	public static final float HEIGHT = 22;
	private static final float PAD = 7;
	private static final float ICON = 9;
	private static final float ICON_GAP = 5;
	private static final float CLEAR = 10;
	private static final float RADIUS = 6;
	private static final long BLINK_MILLIS = 530;

	/**
	 * How a field is drawn when it isn't the kit's own field: its face, colours and metrics in UI units (the Control
	 * Center's search and name fields). Editing, selection and scrolling are the same either way.
	 */
	public record Look(FontFace face, int fill, int outline, int outlineHover, int focusOutline, float radius, float pad,
		float icon, float iconGap, int text, int placeholder, int iconColor) {
	}

	protected final TextEditState state;
	private TextLine text = new TextLine(Type.LABEL, "");
	private TextLine placeholder;
	private TextLine probe = new TextLine(Type.LABEL, "");
	private @Nullable Look look;
	private final boolean search;
	private @Nullable Listener onChange;
	private @Nullable Listener onSubmit;
	private @Nullable Runnable onEscape;
	private int measuredVersion = -1;
	private int measuredScale = -1;
	private int caretPx;
	private int selStartPx;
	private int selEndPx;
	private int textPx;
	private int scrollPx;
	private long caretSince;
	private float clearHover;

	public TextField(String placeholder, int maxLength) {
		this(placeholder, maxLength, false);
	}

	protected TextField(String placeholder, int maxLength, boolean search) {
		this.state = new TextEditState(maxLength);
		this.placeholder = new TextLine(Type.LABEL, placeholder);
		this.search = search;
		this.h = HEIGHT;
	}

	/** Draws the field in {@code look} from now on. */
	public TextField look(Look look) {
		this.look = look;
		text = new TextLine(look.face(), "");
		placeholder = new TextLine(look.face(), placeholder.text());
		probe = new TextLine(look.face(), "");
		measuredVersion = -1;
		invalidate();
		return this;
	}

	private float pad() {
		return look != null ? look.pad() : PAD;
	}

	private float iconSize() {
		return look != null ? look.icon() : ICON;
	}

	private float iconGap() {
		return look != null ? look.iconGap() : ICON_GAP;
	}

	public TextField onChange(@Nullable Listener listener) {
		this.onChange = listener;
		return this;
	}

	/** Enter in the field. */
	public TextField onSubmit(@Nullable Listener listener) {
		this.onSubmit = listener;
		return this;
	}

	/**
	 * Escape in the field runs {@code action} (cancel an inline rename) instead of closing the dialog or
	 * screen; without one, Escape does what it does elsewhere.
	 */
	public TextField onEscape(@Nullable Runnable action) {
		this.onEscape = action;
		return this;
	}

	@Override
	public boolean handlesEscape() {
		return onEscape != null;
	}

	public String text() {
		return state.text();
	}

	/** Replaces the text without telling the listener (the model changed elsewhere). */
	public void setText(String value) {
		state.set(value);
	}

	public TextEditState state() {
		return state;
	}

	public void setPlaceholder(String value) {
		placeholder.set(value);
	}

	@Override
	public boolean interactive() {
		return true;
	}

	@Override
	public boolean focusable() {
		return true;
	}

	@Override
	public boolean acceptsText() {
		return true;
	}

	@Override
	public float prefWidth() {
		return 128;
	}

	@Override
	public float prefHeight(float width) {
		return HEIGHT;
	}

	@Override
	public String narration() {
		String name = hasName() ? accessibleName() : placeholder.text();
		String value = state.isEmpty() ? "empty" : state.text();
		return withReason(name + ", edit box, " + value);
	}

	@Override
	protected void focusChanged(boolean focused) {
		caretSince = Util.getMillis();
		if (!focused) {
			state.moveTo(state.caret(), false);
		}
	}

	private void changed() {
		caretSince = Util.getMillis();
		if (onChange != null) {
			onChange.changed(state.text());
		}
	}

	// ---------- geometry (units) ----------

	private float textLeft() {
		return x + pad() + (search ? iconSize() + iconGap() : 0);
	}

	private float textRight() {
		return x + w - pad() - (showClear() ? CLEAR + 2 : 0);
	}

	private boolean showClear() {
		return search && !state.isEmpty() && enabled();
	}

	private boolean onClear(float mx) {
		return showClear() && mx >= x + w - pad() - CLEAR - 2;
	}

	/** The caret index nearest a point (binary search over prefix widths; only on clicks). */
	private int indexAt(float mx) {
		Canvas c = measure();
		float target = (mx - textLeft()) * c.scale() + scrollPx;
		String value = state.text();
		int lo = 0;
		int hi = value.length();
		while (lo < hi) {
			int mid = (lo + hi + 1) >>> 1;
			probe.set(value.substring(0, mid));
			if (c.width(probe) <= target) {
				lo = mid;
			} else {
				hi = mid - 1;
			}
		}
		// Snap to the nearer side of the character under the point.
		if (lo < value.length()) {
			probe.set(value.substring(0, lo));
			int before = c.width(probe);
			probe.set(value.substring(0, TextEditState.next(value, lo)));
			int after = c.width(probe);
			if (target - before > (after - before) / 2.0F) {
				lo = TextEditState.next(value, lo);
			}
		}
		return lo;
	}

	// ---------- input ----------

	@Override
	public boolean mouseDown(float mx, float my, int button) {
		if (button != Kit.LEFT_BUTTON) {
			return false;
		}
		if (onClear(mx)) {
			state.set("");
			changed();
			if (screen != null) {
				screen.playClick();
			}
			return false;
		}
		boolean extend = Minecraft.getInstance().hasShiftDown();
		state.moveTo(indexAt(mx), extend);
		caretSince = Util.getMillis();
		return true;
	}

	@Override
	public void mouseDrag(float mx, float my) {
		state.moveTo(indexAt(mx), true);
	}

	@Override
	public boolean keyDown(KeyEvent event) {
		if (event.isEscape()) {
			if (onEscape == null) {
				return false;
			}
			onEscape.run();
			return true;
		}
		boolean shift = event.hasShiftDown();
		boolean word = event.hasControlDownWithQuirk() || event.hasAltDown();
		int key = event.key();
		int before = state.version();
		String text = null;
		if (event.isSelectAll()) {
			state.selectAll();
		} else if (event.isCopy()) {
			if (state.hasSelection()) {
				Minecraft.getInstance().keyboardHandler.setClipboard(state.selected());
			}
		} else if (event.isCut()) {
			String cut = state.cut();
			if (!cut.isEmpty()) {
				Minecraft.getInstance().keyboardHandler.setClipboard(cut);
				text = cut;
			}
		} else if (event.isPaste()) {
			state.insert(StringUtil.filterText(Minecraft.getInstance().keyboardHandler.getClipboard()));
			text = "";
		} else if (key == InputConstants.KEY_BACKSPACE) {
			state.backspace(word);
			text = "";
		} else if (key == InputConstants.KEY_DELETE) {
			state.delete(word);
			text = "";
		} else if (event.isLeft()) {
			state.left(shift, word);
		} else if (event.isRight()) {
			state.right(shift, word);
		} else if (key == InputConstants.KEY_HOME) {
			state.home(shift);
		} else if (key == InputConstants.KEY_END) {
			state.end(shift);
		} else if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
			if (onSubmit != null) {
				onSubmit.changed(state.text());
			}
		} else {
			// Space is typed (charTyped), not an activation; Up/Down move between rows.
			return key == InputConstants.KEY_SPACE;
		}
		if (text != null && state.version() != before) {
			changed();
		} else {
			caretSince = Util.getMillis();
		}
		return true;
	}

	@Override
	public boolean charTyped(int codepoint) {
		int before = state.version();
		state.insert(Character.toString(codepoint));
		if (state.version() != before) {
			changed();
		}
		return true;
	}

	// ---------- drawing ----------

	private void measureText(Canvas c) {
		if (measuredVersion == state.version() && measuredScale == c.scale()) {
			return;
		}
		measuredVersion = state.version();
		measuredScale = c.scale();
		String value = state.text();
		text.set(value);
		textPx = c.width(text);
		caretPx = prefixWidth(c, value, state.caret());
		selStartPx = prefixWidth(c, value, state.selectionStart());
		selEndPx = prefixWidth(c, value, state.selectionEnd());
	}

	private int prefixWidth(Canvas c, String value, int end) {
		if (end <= 0) {
			return 0;
		}
		if (end >= value.length()) {
			return c.width(text);
		}
		probe.set(value.substring(0, end));
		return c.width(probe);
	}

	@Override
	protected void paint(Canvas c) {
		measureText(c);
		int x0 = px0(c);
		int y0 = py0(c);
		int pw = pxW(c);
		int ph = pxH(c);
		boolean active = focused() && enabled();
		Look styled = look;
		int r = c.px(styled != null ? styled.radius() : RADIUS);
		if (styled != null) {
			c.roundRect(x0, y0, pw, ph, r, styled.fill());
			int edge = active ? styled.focusOutline() : Palette.lerp(styled.outline(), styled.outlineHover(), hover);
			if ((edge >>> 24) != 0) {
				c.ring(x0, y0, pw, ph, r, active && Kit.prefs().highContrast() ? 2 : 1, edge);
			}
		} else {
			c.roundRect(x0, y0, pw, ph, r, Tokens2.WELL);
			c.ring(x0, y0, pw, ph, r, 1, active ? 0 : Palette.lerp(Tokens2.HAIRLINE, Tokens2.HAIRLINE_STRONG, hover));
			if (active) {
				c.ring(x0, y0, pw, ph, r, Kit.prefs().highContrast() ? 2 : 1, Palette.withAlpha(Tokens2.EMBER, 0.45F));
			} else if (Kit.prefs().highContrast()) {
				c.ring(x0, y0, pw, ph, r, 1, Tokens2.hairlineStrong());
			}
		}
		int center = y0 + ph / 2;
		float alpha = enabled() ? 1 : 0.5F;
		if (search) {
			int icon = c.px(iconSize());
			int iconColor = styled != null ? styled.iconColor() : active ? Tokens2.TEXT_DIM : Tokens2.muted();
			c.icon(Icon.SEARCH, c.px(x + pad()), center - icon / 2, icon, Palette.withAlpha(iconColor, alpha));
		}

		int left = c.px(textLeft());
		int right = c.px(textRight());
		int area = Math.max(1, right - left);
		// Keep the caret in view; never scroll past the end of the text.
		if (caretPx - scrollPx > area - 1) {
			scrollPx = caretPx - area + 1;
		} else if (caretPx - scrollPx < 0) {
			scrollPx = caretPx;
		}
		scrollPx = Math.clamp(scrollPx, 0, Math.max(0, textPx - area + 1));
		int baseline = c.baselineFor(text, center);
		if (state.isEmpty()) {
			c.text(placeholder, left, baseline, Palette.withAlpha(styled != null ? styled.placeholder() : Tokens2.faint(), alpha), area);
		} else {
			// Clipped only when it overflows: scissors are whole GUI units, and a short text needs none.
			boolean clip = textPx > area;
			if (clip) {
				c.pushClip(left - c.px(1.5F), y0, right + c.px(1), y0 + ph);
			}
			try {
				if (active && state.hasSelection()) {
					int top = center - c.px(4.5F);
					c.fill(left + selStartPx - scrollPx, top, left + selEndPx - scrollPx, center + c.px(4.5F), Tokens2.SELECTION);
				}
				c.text(text, left - scrollPx, baseline, Palette.withAlpha(styled != null ? styled.text() : Tokens2.TEXT, alpha));
			} finally {
				if (clip) {
					c.popClip();
				}
			}
		}
		if (active) {
			long since = Util.getMillis() - caretSince;
			boolean shown = Kit.reduced() || (since / BLINK_MILLIS) % 2 == 0;
			int cx = left + caretPx - scrollPx;
			if (shown && cx >= left - 1 && cx <= right) {
				c.fill(cx, center - c.px(4), cx + Math.max(1, Kit.hairline(c)), center + c.px(4), Tokens2.EMBER_TEXT);
			}
			if (screen != null) {
				float ux = (float) cx / c.scale();
				screen.setTextInputArea(ux, y, ux + 1, y + h);
			}
		}
		if (showClear()) {
			boolean over = hovered() && screen != null && onClear(screen.mouseX());
			clearHover = Kit.approach(clearHover, over ? 1 : 0, Motion.HOVER);
			int size = c.px(CLEAR);
			int cx = c.px(x + w - pad() - CLEAR + 1);
			int cy = center - size / 2;
			if (clearHover > 0.01F) {
				c.roundRect(cx, cy, size, size, size / 2, Palette.withAlpha(Tokens2.PRESS, clearHover));
			}
			int glyph = c.px(CLEAR * 0.7F);
			c.icon(Icon.CLOSE, cx + (size - glyph) / 2, cy + (size - glyph) / 2, glyph, Palette.lerp(Tokens2.muted(), Tokens2.TEXT, clearHover));
		}
	}
}
