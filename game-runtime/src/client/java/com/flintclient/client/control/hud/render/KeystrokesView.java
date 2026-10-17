package com.flintclient.client.control.hud.render;

import com.flintclient.client.control.ControlCenter;
import com.flintclient.client.control.hud.CpsCounter;
import com.flintclient.client.control.hud.render.dev.HudDev;
import com.flintclient.client.control.model.HudColor;
import com.flintclient.client.control.model.HudThemeId;
import com.flintclient.client.control.model.KeyLabelStyle;
import com.flintclient.client.control.model.KeystrokesLayout;
import com.flintclient.client.control.model.ModuleId;
import com.flintclient.client.control.model.ModuleSettings;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.PressedStyle;
import com.flintclient.client.control.platform.Mc263ControlPlatform;
import com.flintclient.client.control.platform.Mc263HudData;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.kit.Kit;
import com.flintclient.client.ui.kit.Tokens;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.locale.Language;
import org.jspecify.annotations.Nullable;

/**
 * Keystrokes: the movement keys (forward above left, back and right), then by default the attack and use buttons
 * and the jump key, each a small key showing the name of the key it is bound to now, or arrows (↑ ← ↓ →) on the
 * movement keys. A held key is filled with the pressed colour (its label turns dark), outlined in it, or inverted
 * (the key takes the text colour, the label the panel's), easing in and out over the chosen fade (instant with
 * reduced motion), so the shape or contrast changes and not only a colour. The state is the physical key (toggle
 * sneak and friends don't fool it). Optionally the mouse keys show their clicks per second under the label.
 *
 * <p>Key size, the gap between keys and the corner radius are the module's own; each key is its own panel in the
 * module's style, with no panel around the whole module. Labels are rebuilt only when a binding or the language
 * changes. The arrows are one arrow glyph turned (Flint's typeface has only the up and down arrows), or Minecraft's
 * own arrows in the Classic style.
 */
final class KeystrokesView extends ModuleView {
	private static final int KEYS = 7;
	private static final int MOVEMENT = 4;
	private static final int ATTACK = 4;
	private static final int USE = 5;
	private static final int JUMP = 6;
	private static final HudText ARROW = new HudText("↑");
	/** Minecraft's font has all four arrows: forward, left, back, right. */
	private static final HudText[] CLASSIC_ARROWS = {new HudText("↑"), new HudText("←"), new HudText("↓"), new HudText("→")};
	/** Quarter turns of {@link #ARROW} for forward, left, back and right. */
	private static final int[] TURNS = {0, 3, 2, 1};

	private final HudText[] labels = new HudText[KEYS];
	private final InputConstants.Key[] bound = new InputConstants.Key[KEYS];
	private final float[] press = new float[KEYS];
	private final boolean[] down = new boolean[KEYS];
	/** Key rectangles in pixels from the module's corner: x, y, w, h per key (hidden keys have w 0). */
	private final int[] rects = new int[4 * KEYS];
	private final HudField leftCps = new HudField(1, HudField.TEXT);
	private final HudField rightCps = new HudField(1, HudField.TEXT);
	private KeystrokesLayout layout = KeystrokesLayout.KEYS_MOUSE_JUMP;
	private boolean showCps;
	private boolean arrows;
	private PressedStyle pressedStyle = PressedStyle.FILL;
	private HudColor pressedColor = HudColor.DEFAULT;
	private float fadeTau;
	private @Nullable Language language;
	/** The summary's movement-key letters ("WASD"), rebuilt only when a binding or the language changes. */
	private final InputConstants.Key[] summaryBound = new InputConstants.Key[MOVEMENT];
	private final char[] summaryKeys = new char[MOVEMENT * 2];
	private int summaryKeysLength;
	private @Nullable Language summaryLanguage;

	KeystrokesView(int surface) {
		super(ModuleId.KEYSTROKES, surface);
		for (int i = 0; i < KEYS; i++) {
			labels[i] = new HudText(KeyLabels.UNBOUND);
		}
	}

	@Override
	boolean panelled() {
		return false;
	}

	@Override
	@Nullable String noLabelReason(HudThemeId theme) {
		return "Each key shows its own name, so there is no label";
	}

	@Override
	@Nullable String noPaddingReason() {
		return "Each key is its own panel, so there is no padding around them";
	}

	@Override
	void update(ModuleState state, int mode, long nowNanos) {
		layout = state.choice(ModuleSettings.Keystrokes.LAYOUT);
		showCps = state.bool(ModuleSettings.Keystrokes.SHOW_CPS) && layout.mouse();
		arrows = state.choice(ModuleSettings.Keystrokes.LABELS) == KeyLabelStyle.ARROWS;
		pressedStyle = state.choice(ModuleSettings.Keystrokes.PRESSED_STYLE);
		pressedColor = state.choice(ModuleSettings.Keystrokes.PRESSED_COLOR);
		// The approach settles in about three time constants: 60 ms of fade is a 20 ms constant.
		fadeTau = state.integer(ModuleSettings.Keystrokes.FADE_MS) / 3000.0F;
		// Key names are translated: a language change renames them even though no binding changed.
		Language language = Language.getInstance();
		boolean renamed = language != this.language;
		this.language = language;
		for (int i = 0; i < KEYS; i++) {
			KeyMapping mapping = Mc263HudData.keystroke(i);
			InputConstants.Key key = Mc263ControlPlatform.boundKey(mapping);
			if (key != bound[i] || renamed) {
				bound[i] = key;
				labels[i].set(label(mapping, key));
			}
			down[i] = mode != PLACEHOLDER && (Mc263ControlPlatform.physicallyDown(mapping) || HudDev.held(i));
		}
		if (showCps && mode != PLACEHOLDER) {
			long now = ControlCenter.nowMillis();
			leftCps.set(ControlCenter.cps().countLastSecond(CpsCounter.LEFT, now));
			rightCps.set(ControlCenter.cps().countLastSecond(CpsCounter.RIGHT, now));
		} else {
			leftCps.clear();
			rightCps.clear();
		}
	}

	/** The key's short name (built when the binding changes, never per frame). */
	private static String label(KeyMapping mapping, InputConstants.Key key) {
		if (mapping.isUnbound()) {
			return KeyLabels.UNBOUND;
		}
		if (key.getType() == InputConstants.Type.MOUSE) {
			return KeyLabels.mouse(key.getValue());
		}
		return KeyLabels.keyboard(Mc263ControlPlatform.keyName(mapping).getString());
	}

	@Override
	void measure(HudPen p, ModuleState state) {
		int key = Math.max(4, p.s.px(state.integer(ModuleSettings.Keystrokes.KEY_SIZE)));
		int gap = p.s.px(state.integer(ModuleSettings.Keystrokes.SPACING));
		int full = 3 * key + 2 * gap;
		int row = key + gap;
		place(0, row, 0, key, key);
		place(1, 0, row, key, key);
		place(2, row, row, key, key);
		place(3, 2 * row, row, key, key);
		int bottom = 2 * row;
		if (layout.mouse()) {
			int half = (full - gap) / 2;
			place(ATTACK, 0, bottom, half, key);
			place(USE, half + gap, bottom, full - half - gap, key);
			bottom += row;
		} else {
			place(ATTACK, 0, 0, 0, 0);
			place(USE, 0, 0, 0, 0);
		}
		if (layout.jump()) {
			int bar = Math.max(p.s.lineH, Math.round(key * 0.55F));
			place(JUMP, 0, bottom, full, bar);
			bottom += bar + gap;
		} else {
			place(JUMP, 0, 0, 0, 0);
		}
		width = full;
		height = bottom - gap;
	}

	private void place(int key, int x, int y, int w, int h) {
		rects[4 * key] = x;
		rects[4 * key + 1] = y;
		rects[4 * key + 2] = w;
		rects[4 * key + 3] = h;
	}

	@Override
	void draw(HudPen p, ModuleState state, int x, int y) {
		HudStyle s = p.s;
		int corners = s.px(state.integer(ModuleSettings.Keystrokes.CORNERS));
		int line = Math.max(1, s.unitPx / 3);
		int outline = Math.max(1, s.px(1));
		int color = pressedColor.isDefault() ? s.accent : pressedColor.argb();
		for (int i = 0; i < KEYS; i++) {
			press[i] = Kit.approach(press[i], down[i] ? 1.0F : 0.0F, fadeTau);
			int w = rects[4 * i + 2];
			if (w <= 0) {
				continue;
			}
			int kx = x + rects[4 * i];
			int ky = y + rects[4 * i + 1];
			int h = rects[4 * i + 3];
			int radius = Math.min(corners, Math.min(w, h) / 2);
			float pressed = press[i];
			if (s.background) {
				p.box(kx, ky, w, h, radius, s.panel);
			} else {
				// No panel: a faint outline keeps each key's shape readable over the world.
				p.ring(kx, ky, w, h, radius, line, Palette.withAlpha(s.text, 0.3F));
			}
			int ink = s.text;
			if (pressed > 0.01F) {
				switch (pressedStyle) {
					case FILL -> {
						p.box(kx, ky, w, h, radius, Palette.withAlpha(color, pressed));
						ink = Palette.lerp(s.text, Tokens.ACCENT_INK, pressed);
					}
					case OUTLINE -> {
						p.ring(kx, ky, w, h, radius, outline, Palette.withAlpha(color, pressed));
						ink = Palette.lerp(s.text, color, pressed);
					}
					case INVERT -> {
						p.box(kx, ky, w, h, radius, Palette.withAlpha(s.text, pressed));
						ink = Palette.lerp(s.text, s.ink, pressed);
					}
				}
			}
			drawLabel(p, i, kx, ky, w, h, ink);
		}
	}

	private void drawLabel(HudPen p, int key, int kx, int ky, int w, int h, int ink) {
		HudStyle s = p.s;
		if (arrows && key < MOVEMENT) {
			if (s.classic()) {
				HudText arrow = CLASSIC_ARROWS[key];
				p.textCentered(arrow, HudFaces.VALUE, kx + w / 2, ky + (h + p.cap(HudFaces.VALUE)) / 2, ink, w);
			} else {
				p.textTurned(ARROW, HudFaces.VALUE, kx + w / 2, ky + h / 2, TURNS[key], ink);
			}
			return;
		}
		HudText label = labels[key];
		int role = key == JUMP || label.text(s.theme).length() > 1 ? HudFaces.LABEL : HudFaces.VALUE;
		int maxPx = Math.max(1, w - 2 * Math.max(1, s.px(1.5F)));
		boolean cps = showCps && (key == ATTACK || key == USE);
		if (!cps) {
			int cap = p.cap(role);
			p.textCentered(label, role, kx + w / 2, ky + (h + cap) / 2, ink, maxPx);
			return;
		}
		// Label above, clicks per second below, both small.
		HudField count = key == ATTACK ? leftCps : rightCps;
		int cap = p.cap(HudFaces.LABEL);
		int gap = Math.max(1, s.px(2));
		int top = ky + (h - (2 * cap + gap)) / 2;
		p.textCentered(label, HudFaces.LABEL, kx + w / 2, top + cap, ink, maxPx);
		int digitsWidth = count.known ? p.width(count.digits, count.length, HudFaces.LABEL) : p.width(HudField.DASH, HudFaces.LABEL);
		int left = kx + (w - digitsWidth) / 2;
		if (count.known) {
			p.digits(count.digits, count.length, HudFaces.LABEL, left, top + 2 * cap + gap, ink);
		} else {
			p.text(HudField.DASH, HudFaces.LABEL, left, top + 2 * cap + gap, ink);
		}
	}

	@Override
	int summary(ModuleState state, char[] out, long nowNanos) {
		Language language = Language.getInstance();
		boolean changed = language != summaryLanguage;
		summaryLanguage = language;
		for (int i = 0; i < MOVEMENT; i++) {
			InputConstants.Key key = Mc263ControlPlatform.boundKey(Mc263HudData.keystroke(i));
			changed |= key != summaryBound[i];
			summaryBound[i] = key;
		}
		if (changed) {
			// "WASD" when every movement key has a one-letter name; otherwise the summary just says "Keys".
			summaryKeysLength = 0;
			for (int i = 0; i < MOVEMENT; i++) {
				KeyMapping mapping = Mc263HudData.keystroke(i);
				String name = label(mapping, summaryBound[i]);
				if (name.length() != 1 || name.equals(KeyLabels.UNBOUND)) {
					summaryKeysLength = 0;
					break;
				}
				summaryKeys[summaryKeysLength++] = name.charAt(0);
			}
		}
		return HudFormat.keystrokesSummary(summaryKeys, summaryKeysLength, state.choice(ModuleSettings.Keystrokes.LAYOUT), out);
	}
}
