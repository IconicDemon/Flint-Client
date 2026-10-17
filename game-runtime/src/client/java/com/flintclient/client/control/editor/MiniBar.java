package com.flintclient.client.control.editor;

import com.flintclient.client.control.hud.ScaleSteps;
import com.flintclient.client.control.model.HudThemeId;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Icon;
import com.flintclient.client.render.TextLine;
import com.flintclient.client.ui.kit.IconButton;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.client.ui.kit.Segmented;
import com.flintclient.client.ui.kit.Tokens2;
import com.flintclient.client.ui.kit.Type;

/**
 * The selected module's contextual bar: a small capsule floating just above it (below it when there is no room) with
 * the module's name, its size step down and up, its style (a cycler: Layout, Flint, Classic, Minimal), a gear that
 * opens the inspector and an eye that takes it off the HUD. The quick things are at hand without covering the screen
 * with a panel. Every change is one undo step.
 */
final class MiniBar extends Node {
	static final float HEIGHT = 18;
	private static final float PAD = 7;
	private static final float BUTTON = 14;
	private static final float GAP = 6;
	private static final String[] STYLES = {"Layout", "Flint", "Classic", "Minimal"};

	private final HudEditor editor;
	private final TextLine name = new TextLine(Type.META, "");
	private final TextLine size = new TextLine(Type.META, "");
	private final IconButton smaller;
	private final IconButton larger;
	private final Segmented style;
	private final IconButton gear;
	private final IconButton hide;
	private float shownScale = -1;
	private float dividerX;
	/**
	 * The bar's width, measured when it paints (at the interface scale). {@link #placeFor} runs while the editor draws
	 * the HUD at Minecraft's GUI scale, where text can't be measured for the interface, so it uses this.
	 */
	private float measuredWidth = 170;
	private float nameWidth = 40;
	private float styleWidth = 51;

	MiniBar(HudEditor editor) {
		this.editor = editor;
		smaller = add(new IconButton(Icon.MINUS, "Smaller ([)", BUTTON, () -> editor.stepScale(-1)).iconSize(8));
		larger = add(new IconButton(Icon.PLUS, "Larger (])", BUTTON, () -> editor.stepScale(1)).iconSize(8));
		style = add(new Segmented(STYLES, 0, i -> {
			int index = editor.selected();
			if (index >= 0) {
				ModuleState state = editor.module(index);
				editor.session().edit(() -> state.setAppearance(state.appearance().withTheme(i == 0 ? null : HudThemeId.values()[i - 1])));
			}
		}));
		style.name("Style");
		gear = add(new IconButton(Icon.GEAR, "Settings (I)", BUTTON, editor::toggleInspector).iconSize(9));
		hide = add(new IconButton(Icon.EYE_OFF, "Take off the HUD", BUTTON, editor::hideSelected).iconSize(9));
		this.h = HEIGHT;
	}

	void bind(int index) {
		if (index < 0) {
			return;
		}
		ModuleState state = editor.module(index);
		name.set(state.descriptor().name());
		HudThemeId theme = state.appearance().theme();
		style.setSelected(theme == null ? 0 : theme.ordinal() + 1);
		shownScale = -1;
		invalidate();
	}

	private float widthFor(float nameW) {
		return PAD + nameW + GAP + 2 + BUTTON + 22 + BUTTON + GAP + styleWidth + GAP + 2 * BUTTON + PAD - 2;
	}

	@Override
	public float prefWidth() {
		return measuredWidth;
	}

	/** Places the bar over (or under) the module's rectangle {@code x0..y1} in UI units, kept on screen and clear of the toolbar. */
	void placeFor(float x0, float y0, float x1, float y1, float screenW, float screenH) {
		float bw = measuredWidth;
		float bx = Math.clamp((x0 + x1) / 2 - bw / 2, 4, Math.max(4, screenW - bw - 4));
		float by = y0 - 7 - HEIGHT;
		float toolbarBottom = EditorToolbar.TOP + EditorToolbar.HEIGHT + 4;
		if (by < 4 || by < toolbarBottom && bx < screenW / 2 + 150 && bx + bw > screenW / 2 - 150) {
			by = y1 + 7;
		}
		by = Math.min(by, screenH - HEIGHT - 4);
		if (Math.abs(bx - x) > 0.01F || Math.abs(by - y) > 0.01F || Math.abs(bw - w) > 0.01F) {
			setBounds(Math.round(bx), Math.round(by), bw, HEIGHT);
		}
	}

	@Override
	public boolean interactive() {
		// The capsule's own background takes presses, so they never reach the module under it.
		return true;
	}

	@Override
	protected void layout() {
		float cy = y + (h - BUTTON) / 2;
		float at = x + PAD + nameWidth + GAP;
		dividerX = at;
		at += 2 + GAP / 2;
		smaller.setBounds(at, cy, BUTTON, BUTTON);
		at += BUTTON + 22;
		larger.setBounds(at, cy, BUTTON, BUTTON);
		at += BUTTON + GAP;
		float sw = styleWidth;
		style.setBounds(at, y + 1, sw, h - 2);
		at += sw + GAP;
		gear.setBounds(at, cy, BUTTON, BUTTON);
		at += BUTTON;
		hide.setBounds(at, cy, BUTTON, BUTTON);
	}

	@Override
	protected void paint(Canvas c) {
		float measuredName = (float) c.width(name) / c.scale();
		float measuredStyle = style.cyclerWidth();
		if (measuredName != nameWidth || measuredStyle != styleWidth) {
			nameWidth = measuredName;
			styleWidth = measuredStyle;
			measuredWidth = widthFor(measuredName);
			invalidate();
		}
		int index = editor.selected();
		if (index >= 0) {
			float scale = editor.module(index).placement().scale();
			if (scale != shownScale) {
				shownScale = scale;
				size.set(EditorStage.scaleLabel(scale));
			}
			gear.setActive(editor.inspectorOpen());
			larger.setEnabled(ScaleSteps.next(scale) != scale);
			smaller.setEnabled(ScaleSteps.previous(scale) != scale);
		}
		EditorToolbar.paintCapsule(c, x, y, w, h);
		int center = c.px(y + h / 2);
		c.text(name, c.px(x + PAD), c.baselineFor(name, center), Tokens2.TEXT);
		int dx = c.px(dividerX);
		c.fill(dx, c.px(y + 4), dx + 1, c.px(y + h - 4), Tokens2.HAIRLINE_STRONG);
		int sizeCenter = c.px(smaller.x() + BUTTON + 11);
		c.textCentered(size, sizeCenter, c.baselineFor(size, center), Tokens2.TEXT_DIM);
	}
}
