package com.flintclient.client.control.hud.render;

import com.flintclient.client.control.hud.FpsBox;
import com.flintclient.client.control.model.BoxStyle;
import com.flintclient.client.control.model.FpsDisplayMode;
import com.flintclient.client.control.model.FpsSizing;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.TextAlign;
import com.flintclient.client.render.Palette;

/**
 * A counter drawn as one or a few lines of text in the {@link BoxStyle} look (in a box, between brackets, or just text),
 * the same way the FPS counter is. A module says only what its text is ({@link #compose}); this view lays out the box,
 * applies the colours and shadow, and redraws.
 *
 * <p>The text is composed into a char buffer at most {@link #refreshNanos()} apart (allocation-free); a line's String
 * is made only when its text changed, so a steady counter allocates nothing, and a moving one (view angles while
 * turning) at most once per refresh.
 */
abstract class TextModuleView extends ModuleView {
	static final int MAX_LINES = 6;
	private static final long DEFAULT_REFRESH = 50_000_000L;
	private static final HudText OPEN = new HudText("[");
	private static final HudText CLOSE = new HudText("]");

	private final BoxStyle style;
	private final HudText[] lines = new HudText[MAX_LINES];
	private final String[] shown = new String[MAX_LINES];
	/** Per line: its own colour ({@code 0xAARRGGBB}), or 0 for the text colour. Set by {@link #compose}. */
	final int[] colors = new int[MAX_LINES];
	private final int[] lineW = new int[MAX_LINES];
	private final char[] buffer = new char[256];
	private final FpsBox box = new FpsBox();
	private final FpsBox.Hold hold = new FpsBox.Hold();
	private int lineCount;
	private boolean visible;
	private long composedAt;
	private int composedMode = -1;
	private int composedRevision = -1;
	private int heldRevision = -1;
	private long now;
	private int textW;
	private int textH;

	TextModuleView(String moduleId, int surface, BoxStyle style) {
		super(moduleId, surface);
		this.style = style;
		for (int i = 0; i < MAX_LINES; i++) {
			lines[i] = new HudText("");
		}
	}

	/**
	 * Writes the text into {@code out} (lines separated by {@code '\n'}) and returns its length, or -1 to show nothing
	 * (on the HUD; previews then show the placeholder). In {@link #SAMPLE} mode it writes a fixed, clearly sample text.
	 * May set {@link #colors}. Must not allocate.
	 */
	abstract int compose(ModuleState state, int mode, long nowNanos, char[] out);

	/** How often the text is composed again. */
	long refreshNanos() {
		return DEFAULT_REFRESH;
	}

	@Override
	final void update(ModuleState state, int mode, long nowNanos) {
		now = nowNanos;
		if (state.revision() != heldRevision) {
			heldRevision = state.revision();
			hold.reset();
		}
		if (mode == composedMode && state.revision() == composedRevision && nowNanos - composedAt < refreshNanos()) {
			return;
		}
		composedMode = mode;
		composedRevision = state.revision();
		composedAt = nowNanos;
		java.util.Arrays.fill(colors, 0);
		int length = mode == PLACEHOLDER ? HudFormat.text("—", buffer, 0) : compose(state, mode, nowNanos, buffer);
		if (length < 0 && mode != HUD) {
			length = HudFormat.text("—", buffer, 0);
		}
		visible = length >= 0;
		if (!visible) {
			lineCount = 0;
			return;
		}
		int line = 0;
		int start = 0;
		for (int i = 0; i <= length && line < MAX_LINES; i++) {
			if (i == length || buffer[i] == '\n') {
				setLine(line++, start, i);
				start = i + 1;
			}
		}
		lineCount = line;
	}

	private void setLine(int index, int from, int to) {
		String last = shown[index];
		int length = to - from;
		boolean same = last != null && last.length() == length;
		for (int i = 0; same && i < length; i++) {
			same = last.charAt(i) == buffer[from + i];
		}
		if (!same) {
			String text = new String(buffer, from, length);
			shown[index] = text;
			lines[index].set(text);
		}
	}

	/** The module's colours and shadow over whatever the shared look resolved (this view's style is its own). */
	private void applyLook(HudStyle s, ModuleState state) {
		int text = 0xFF000000 | state.rgb(style.textColor);
		s.text = text;
		s.label = text;
		s.muted = Palette.withAlpha(text, 0.72F);
		s.minecraftShadow = true;
		s.shadowPx = state.bool(style.textShadow) ? Math.max(1, s.classic() ? s.textScale : Math.round(0.75F * s.u)) : 0;
	}

	@Override
	final void measure(HudPen p, ModuleState state) {
		if (!visible || lineCount == 0) {
			width = 0;
			height = 0;
			return;
		}
		applyLook(p.s, state);
		boolean brackets = state.choice(style.displayMode) == FpsDisplayMode.BRACKETS;
		int bracketW = brackets ? p.width(OPEN, HudFaces.VALUE) + p.width(CLOSE, HudFaces.VALUE) + p.s.thin : 0;
		textW = 0;
		for (int i = 0; i < lineCount; i++) {
			lineW[i] = p.width(lines[i], HudFaces.VALUE) + bracketW;
			textW = Math.max(textW, lineW[i]);
		}
		textH = linesHeight(p, lineCount);
		FpsDisplayMode mode = state.choice(style.displayMode);
		FpsSizing sizing = state.choice(style.backgroundType);
		box.layout(textW, textH, p.s.u, mode, state.bool(style.background), sizing, state.integer(style.width), state.integer(style.height),
			state.bool(style.border), state.integer(style.borderThickness), state.integer(style.cornerRadius), state.choice(style.align));
		if (box.box && sizing == FpsSizing.DYNAMIC) {
			int held = hold.width(box.width, now);
			if (held > box.width) {
				box.textX = (held - textW) / 2;
				box.width = held;
			}
		}
		width = box.width;
		height = box.height;
	}

	@Override
	final void draw(HudPen p, ModuleState state, int x, int y) {
		if (width <= 0) {
			return;
		}
		applyLook(p.s, state);
		if (box.box) {
			int radius = box.radiusPx;
			if (box.fill) {
				int fill = Palette.withAlpha(0xFF000000 | state.rgb(style.backgroundColor), state.integer(style.backgroundOpacity) / 100.0F);
				if ((fill >>> 24) != 0) {
					p.box(x, y, width, height, radius, fill);
				}
			}
			if (box.borderPx > 0) {
				p.ring(x, y, width, height, radius, box.borderPx, 0xFF000000 | state.rgb(style.borderColor));
			}
		}
		boolean brackets = state.choice(style.displayMode) == FpsDisplayMode.BRACKETS;
		TextAlign align = state.choice(style.align);
		int top = y + box.textY;
		for (int i = 0; i < lineCount; i++) {
			int spare = textW - lineW[i];
			int lx = x + box.textX + (align == TextAlign.LEFT || !box.box && i == 0 && lineCount == 1 ? 0
				: align == TextAlign.RIGHT ? spare : spare / 2);
			int baseline = p.baseline(top + i * (p.s.lineH + p.s.lineGap));
			int color = colors[i] != 0 ? colors[i] : p.s.text;
			int at = lx;
			if (brackets) {
				at = p.text(OPEN, HudFaces.VALUE, at, baseline, p.s.text) + p.s.thin / 2;
			}
			at = p.text(lines[i], HudFaces.VALUE, at, baseline, color);
			if (brackets) {
				p.text(CLOSE, HudFaces.VALUE, at + p.s.thin / 2, baseline, p.s.text);
			}
		}
	}

	@Override
	final boolean panelled() {
		return false;
	}

	@Override
	final boolean ownLook() {
		return true;
	}

	@Override
	@org.jspecify.annotations.Nullable String noLabelReason(com.flintclient.client.control.model.HudThemeId theme) {
		return "Set on the module's own page";
	}

	@Override
	@org.jspecify.annotations.Nullable String noPaddingReason() {
		return "The module sets its own box on its page";
	}

	@Override
	int summary(ModuleState state, char[] out, long nowNanos) {
		int length = compose(state, HUD, nowNanos, out);
		for (int i = 0; i < length; i++) {
			if (out[i] == '\n') {
				out[i] = ' ';
			}
		}
		return length;
	}
}
