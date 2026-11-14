package com.flintclient.client.control.rshift;

import static com.flintclient.client.control.rshift.MenuStyle.r;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.ThemeSync;
import com.flintclient.client.control.model.MenuTheme;
import com.flintclient.client.control.rshift.MenuStyle.Label;
import com.flintclient.client.control.rshift.MenuStyle.Type;
import com.flintclient.client.platform.KeyInput;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.kit.KitScreen;
import com.flintclient.client.ui.kit.Pressable;

/**
 * Flint settings › Theme: every theme as a swatch on one line (its page colour with its accent in the middle), the chosen one
 * ringed and named beside them; the one under the pointer is named instead. A pick shows at once here and is sent to the Flint
 * app, so the app and the game stay in the same theme ({@link ThemeSync}). The arrow keys move the choice. Every line.
 */
final class ThemeSwatches extends Pressable {
	/** A swatch's size and the gap between two (reference pixels), and the room kept for the name. */
	private static final float DISC = 40;
	private static final float GAP = 16;
	private static final float NAME = 330;
	private static final MenuTheme.Look[] LOOKS = MenuTheme.Look.values();
	private final Label[] names = new Label[LOOKS.length];
	private final float[] eased = new float[LOOKS.length];
	private int hot = -1;

	ThemeSwatches() {
		super(null);
		for (int i = 0; i < LOOKS.length; i++) {
			names[i] = new Label(Type.SEGMENT, LOOKS[i].label());
			eased[i] = -1;
		}
	}

	private static int chosen() {
		return ControlState.settings().menuTheme().look().ordinal();
	}

	private static void pick(int i) {
		MenuTheme theme = ControlState.settings().menuTheme();
		MenuTheme.Look look = LOOKS[i];
		if (theme.look() != look) {
			ControlState.settings().setMenuTheme(theme.withLook(look));
			ThemeSync.chosenInGame(look);
		}
	}

	@Override
	public float prefWidth() {
		return r(NAME) + LOOKS.length * r(DISC) + (LOOKS.length - 1) * r(GAP);
	}

	private float discX(int i) {
		return x + w - LOOKS.length * r(DISC) - (LOOKS.length - 1) * r(GAP) + i * (r(DISC) + r(GAP));
	}

	private int at(float mx) {
		for (int i = 0; i < LOOKS.length; i++) {
			float left = discX(i);
			if (mx >= left - r(GAP) / 2 && mx < left + r(DISC) + r(GAP) / 2) {
				return i;
			}
		}
		return -1;
	}

	@Override
	public boolean mouseDown(float mx, float my, int button) {
		int i = at(mx);
		if (i >= 0 && enabled()) {
			pick(i);
		}
		return super.mouseDown(mx, my, button);
	}

	@Override
	public boolean keyDown(KeyInput event) {
		int i = chosen();
		if (event.isLeft() && i > 0) {
			pick(i - 1);
			return true;
		}
		if (event.isRight() && i < LOOKS.length - 1) {
			pick(i + 1);
			return true;
		}
		return super.keyDown(event);
	}

	@Override
	public String narration() {
		int i = chosen();
		return withReason(accessibleName() + ", " + LOOKS[i].label() + ", " + (i + 1) + " of " + LOOKS.length);
	}

	@Override
	protected void paint(Canvas c) {
		KitScreen screen = screen();
		hot = hovered() && screen != null ? at(screen.mouseX()) : -1;
		int chosen = chosen();
		float d = r(DISC);
		float cy = y + h / 2;
		for (int i = 0; i < LOOKS.length; i++) {
			MenuTheme.Look look = LOOKS[i];
			eased[i] = MenuStyle.ease(eased[i], i == chosen);
			float t = eased[i];
			float left = discX(i);
			float top = cy - d / 2;
			float lift = i == hot ? 1 : 0;
			// The theme's page, edged so a light one shows on a light menu and a dark one on a dark menu.
			MenuStyle.box(c, left, top, d, d, d / 2, 0xFF000000 | look.background);
			MenuStyle.ring(c, left, top, d, d, d / 2, Math.max(1, c.px(MenuStyle.d(1))), Palette.withAlpha(MenuStyle.TEXT, 0.22F + 0.25F * lift));
			float dot = d * (0.46F + 0.06F * lift);
			MenuStyle.box(c, left + (d - dot) / 2, cy - dot / 2, dot, dot, dot / 2, 0xFF000000 | look.accent);
			if (t > 0.01F) {
				float out = r(6);
				MenuStyle.ring(c, left - out, top - out, d + 2 * out, d + 2 * out, d / 2 + out, Math.max(1, c.px(MenuStyle.d(2))),
					Palette.withAlpha(MenuStyle.TEXT, t));
			}
		}
		int named = hot >= 0 ? hot : chosen;
		float nameRight = discX(0) - r(GAP) - r(8);
		Label name = names[named];
		float nw = Math.min(name.width(c), r(NAME) - r(GAP));
		MenuStyle.fitted(c, name, nameRight - nw, cy, hot >= 0 && hot != chosen ? MenuStyle.NEUTRAL : MenuStyle.TEXT, r(NAME) - r(GAP));
		MenuStyle.focusRing(c, discX(0), y, w - (discX(0) - x), h, h / 2, focus);
	}
}
