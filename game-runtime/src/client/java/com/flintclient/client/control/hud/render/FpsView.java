package com.flintclient.client.control.hud.render;

import com.flintclient.client.control.hud.FpsBox;
import com.flintclient.client.control.hud.FpsText;
import com.flintclient.client.control.model.FpsDisplayMode;
import com.flintclient.client.control.model.FpsSizing;
import com.flintclient.client.control.model.FpsWindow;
import com.flintclient.client.control.model.HudThemeId;
import com.flintclient.client.control.model.ModuleId;
import com.flintclient.client.control.model.ModuleSettings;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.render.Palette;
import org.jspecify.annotations.Nullable;

/**
 * FPS: the measured frame rate ({@link FpsMeter}, from Minecraft's own per-frame samples), drawn with the FPS module's
 * own look. The same view draws the HUD, the HUD editor and the settings preview (whose numbers are a fixed sample), so
 * they always match.
 *
 * <p><b>Text.</b> The number and the "FPS" label in the order {@link FpsText} gives (the label after, before when
 * Reversed, or left out), between brackets in the Brackets mode, then the optional extras after a dot (frame time, 1%
 * low). In a box the number keeps three digit cells (one more with a decimal), so the box doesn't change width between
 * 60 and 144; without one it is packed ("[80 FPS]"). Colour, shadow and order come from the module's settings; the typeface follows the shared look.
 *
 * <p><b>Box.</b> In the Background mode {@link FpsBox} lays the box out (Static or Dynamic width, height, border,
 * corners, alignment); a Dynamic box grows at once but only shrinks after the narrower number has held for a second
 * ({@link FpsBox.Hold}). The box is the view's own, so the renderer adds no panel or padding ({@link #panelled}).
 *
 * <p>Nothing is formatted or allocated per frame: the number is redrawn from digit cells, and is only read again when
 * the meter refreshes (four times a second) at the chosen interval.
 */
final class FpsView extends ModuleView {
	/** What the settings preview shows: a fixed, clearly sample frame rate. */
	static final int SAMPLE_FPS = 80;
	private static final HudText LABEL = new HudText(FpsText.LABEL, FpsText.LABEL, "fps");
	private static final HudText OPEN = new HudText("[");
	private static final HudText CLOSE = new HudText("]");
	private static final HudText MS = new HudText("ms");
	private static final HudText LOW = new HudText("1% LOW", "1% low", "1% low");

	private final HudField value = new HudField(3, HudField.TEXT);
	private final HudField frameTime = new HudField(3, HudField.MUTED).affixes(null, MS);
	private final HudField low = new HudField(2, HudField.MUTED).affixes(LOW, null);
	private final FpsBox box = new FpsBox();
	private final FpsBox.Hold hold = new FpsBox.Hold();
	private boolean showFrameTime;
	private boolean showLow;
	private boolean brackets;
	/** In a box (the Background mode): numbers keep their digit cells so the box stays steady; otherwise they are packed. */
	private boolean boxed;
	/** The meter refresh the shown numbers were read at, and what they were read with. */
	private int shownRefresh = -1;
	private @Nullable FpsWindow shownWindow;
	private int shownDecimals = -1;
	private int shownMode = -1;
	/** The module revision the held box width belongs to. */
	private int heldRevision = -1;
	private long now;
	private int textW;

	FpsView(int surface) {
		super(ModuleId.FPS, surface);
	}

	@Override
	void update(ModuleState state, int mode, long nowNanos) {
		now = nowNanos;
		boolean label = state.bool(ModuleSettings.Fps.SHOW_LABEL);
		boolean reversed = state.bool(ModuleSettings.Fps.REVERSED);
		value.affixes(FpsText.labelFirst(label, reversed) ? LABEL : null, FpsText.labelAfter(label, reversed) ? LABEL : null);
		FpsDisplayMode display = state.choice(ModuleSettings.Fps.DISPLAY_MODE);
		brackets = display == FpsDisplayMode.BRACKETS;
		boxed = display == FpsDisplayMode.BACKGROUND;
		showFrameTime = state.bool(ModuleSettings.Fps.SHOW_FRAME_TIME);
		showLow = state.bool(ModuleSettings.Fps.SHOW_LOW);
		FpsWindow window = state.choice(ModuleSettings.Fps.WINDOW);
		int decimals = state.choice(ModuleSettings.Fps.DECIMALS).places();
		value.cells(3 + decimals);
		if (state.revision() != heldRevision) {
			heldRevision = state.revision();
			hold.reset();
		}
		if (mode == PLACEHOLDER) {
			value.clear();
			frameTime.clear();
			low.clear();
			shownRefresh = -1;
			shownMode = mode;
			return;
		}
		if (mode == SAMPLE) {
			if (shownMode != SAMPLE || decimals != shownDecimals) {
				value.setFixed(SAMPLE_FPS, decimals);
				frameTime.setFixed(1000.0 / SAMPLE_FPS, 1);
				low.set(64);
				shownDecimals = decimals;
				shownRefresh = -1;
			}
			shownMode = mode;
			return;
		}
		FpsMeter.use(nowNanos, showLow);
		int refresh = FpsMeter.refreshes();
		boolean settingsChanged = window != shownWindow || decimals != shownDecimals || shownMode != mode;
		boolean due = refresh != shownRefresh
			&& (shownRefresh < 0 || refresh < shownRefresh || refresh - shownRefresh >= state.choice(ModuleSettings.Fps.INTERVAL).refreshes()
				|| !value.known);
		if (!settingsChanged && !due) {
			return;
		}
		shownRefresh = refresh;
		shownWindow = window;
		shownDecimals = decimals;
		shownMode = mode;
		double rate = FpsMeter.rate(window);
		if (Double.isNaN(rate)) {
			// No frames measured yet (just started showing, or a world was joined): a dash, never a made-up number.
			value.clear();
			frameTime.clear();
		} else {
			value.setFixed(rate, decimals);
			frameTime.setFixed(1000.0 / rate, 1);
		}
		double slow = FpsMeter.low();
		if (Double.isNaN(slow)) {
			low.clear();
		} else {
			low.set(Math.round(slow));
		}
	}

	/** The module's own colours and shadow, over whatever the shared look resolved (this style is drawn for FPS only). */
	private static void applyLook(HudStyle s, ModuleState state) {
		int text = 0xFF000000 | state.rgb(ModuleSettings.Fps.TEXT_COLOR);
		s.text = text;
		s.label = text;
		s.muted = Palette.withAlpha(text, 0.72F);
		s.minecraftShadow = true;
		s.shadowPx = state.bool(ModuleSettings.Fps.TEXT_SHADOW) ? Math.max(1, s.classic() ? s.textScale : Math.round(0.75F * s.u)) : 0;
	}

	@Override
	void measure(HudPen p, ModuleState state) {
		applyLook(p.s, state);
		textW = boxed ? Math.max(run(p, false, 0, 0, false), run(p, false, 0, 0, true)) : run(p, false, 0, 0, false);
		int textH = p.s.lineH;
		FpsDisplayMode mode = state.choice(ModuleSettings.Fps.DISPLAY_MODE);
		FpsSizing sizing = state.choice(ModuleSettings.Fps.BACKGROUND_TYPE);
		box.layout(textW, textH, p.s.u, mode, state.bool(ModuleSettings.Fps.BACKGROUND), sizing, state.integer(ModuleSettings.Fps.WIDTH),
			state.integer(ModuleSettings.Fps.HEIGHT), state.bool(ModuleSettings.Fps.BORDER), state.integer(ModuleSettings.Fps.BORDER_THICKNESS),
			state.integer(ModuleSettings.Fps.CORNER_RADIUS), state.choice(ModuleSettings.Fps.ALIGN));
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
	void draw(HudPen p, ModuleState state, int x, int y) {
		applyLook(p.s, state);
		if (box.box) {
			int radius = box.radiusPx;
			if (box.fill) {
				int alpha = state.integer(ModuleSettings.Fps.BACKGROUND_OPACITY);
				int fill = Palette.withAlpha(0xFF000000 | state.rgb(ModuleSettings.Fps.BACKGROUND_COLOR), alpha / 100.0F);
				if ((fill >>> 24) != 0) {
					p.box(x, y, width, height, radius, fill);
				}
			}
			if (box.borderPx > 0) {
				p.ring(x, y, width, height, radius, box.borderPx, 0xFF000000 | state.rgb(ModuleSettings.Fps.BORDER_COLOR));
			}
		}
		run(p, true, x + box.textX, p.baseline(y + box.textY), false);
	}

	@Override
	boolean panelled() {
		return false;
	}

	@Override
	boolean ownLook() {
		return true;
	}

	/**
	 * The line from {@code x}; {@code slots} measures every number at its full cells (the line's steady width). The
	 * number alone keeps its cells (spare ones before a label after it); with extras the line is laid out tight, so
	 * no gap opens before a dot, and the box keeps the full-cell width.
	 */
	private int run(HudPen p, boolean draw, int x, int baseline, boolean slots) {
		boolean extras = showFrameTime || showLow;
		int at = x;
		if (brackets) {
			at = draw ? p.text(OPEN, HudFaces.VALUE, at, baseline, p.s.text) : at + p.width(OPEN, HudFaces.VALUE);
			at += p.s.thin / 2;
		}
		// Without a box nothing needs to stay steady, so "[80 FPS]" has no spare digit cell.
		boolean pack = !boxed || !slots;
		int end = value.run(p, draw, at, baseline, !boxed || extras && !slots);
		if (showFrameTime) {
			end = frameTime.run(p, draw, separator(p, draw, end, baseline), baseline, pack);
		}
		if (showLow) {
			end = low.run(p, draw, separator(p, draw, end, baseline), baseline, pack);
		}
		if (brackets) {
			end += p.s.thin / 2;
			end = draw ? p.text(CLOSE, HudFaces.VALUE, end, baseline, p.s.text) : end + p.width(CLOSE, HudFaces.VALUE);
		}
		return end - x;
	}

	@Override
	@Nullable String noLabelReason(HudThemeId theme) {
		return "Set by the FPS settings (Show “FPS”)";
	}

	@Override
	@Nullable String noPaddingReason() {
		return "FPS sets its own box in its settings";
	}

	@Override
	int summary(ModuleState state, char[] out, long nowNanos) {
		// Shown in the module list counts as shown: keep the meter recording while it is.
		FpsMeter.use(nowNanos, false);
		double rate = FpsMeter.rate(state.choice(ModuleSettings.Fps.WINDOW));
		return Double.isNaN(rate) ? -1 : HudFormat.fpsSummary((int) Math.round(rate), out);
	}
}
