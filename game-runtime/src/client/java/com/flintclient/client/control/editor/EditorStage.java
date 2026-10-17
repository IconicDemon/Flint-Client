package com.flintclient.client.control.editor;

import com.flintclient.client.control.hud.ScaleSteps;
import com.flintclient.client.control.hud.VanillaAreas;
import com.flintclient.client.control.hud.render.HudScene;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.TextLine;
import com.flintclient.client.ui.kit.Kit;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.client.ui.kit.Tokens2;
import com.flintclient.client.ui.kit.Type;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.jspecify.annotations.Nullable;

/**
 * The editing surface: the whole screen under the toolbar, inspector, hub and palette. The editor paints the modules and
 * guides under every control; the surface takes the pointer (presses select and start drags, which hold the pointer
 * until release; the wheel over the selection changes its size) and draws the marks over the modules, in the kit's
 * units so they stay one pixel thin: a faint outline round the module under the pointer, and for the selection four
 * ember corner brackets 2 units out, a small tag above it ("FPS · 1×") and the round size handle at its bottom-right
 * corner.
 *
 * <p>It is a tab stop: Enter or Space selects the next module that is shown (Shift goes back), arrow keys move it and
 * {@code [} {@code ]} size it. While it has keyboard focus it draws the kit's focus ring around the selection (around
 * the whole surface when nothing is selected). Hovering a module that covers Minecraft's hotbar or health bars says
 * why it is outlined in the warning colour.
 */
final class EditorStage extends Node {
	private static final TextLine[][] WARNINGS = {
		{new TextLine(Type.META, VanillaGuides.warning(VanillaGuides.HOTBAR))},
		{new TextLine(Type.META, VanillaGuides.warning(VanillaGuides.STATUS))}};

	/** How far the selection's marks reach beyond the module, in GUI units: half the scale handle, the furthest. */
	private static final float SELECTION_REACH = HudEditor.HANDLE / 2;
	private static final float BRACKET_GAP = 2;
	private static final float BRACKET_ARM = 4;
	private static final float HANDLE = 4;
	private static final float TAG_H = 11;
	private static final float TAG_PAD = 4;
	private static final float TAG_GAP = 4;
	private static final int HOVER_OUTLINE = 0x38FFFFFF;

	private final HudEditor editor;
	private int tipModule = HudEditor.NONE;

	EditorStage(HudEditor editor) {
		this.editor = editor;
		name("HUD editor");
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
	public String narration() {
		int selected = editor.selected();
		if (selected < 0) {
			return "HUD editor. Enter selects a module; Modules in the toolbar adds one.";
		}
		return "HUD editor, " + editor.module(selected).descriptor().name()
			+ " selected. Arrow keys move it, Shift moves it further, brackets change its size, Enter selects the next module.";
	}

	@Override
	public boolean mouseDown(float mx, float my, int button) {
		if (button == Kit.RIGHT_BUTTON) {
			return editor.pressSecondary(editor.toGuiX(mx), editor.toGuiY(my));
		}
		if (button != Kit.LEFT_BUTTON) {
			return false;
		}
		return editor.press(editor.toGuiX(mx), editor.toGuiY(my), shiftDown());
	}

	private static boolean shiftDown() {
		return com.flintclient.client.control.platform.Mc263ControlPlatform.keyDown(com.mojang.blaze3d.platform.InputConstants.KEY_LSHIFT)
			|| com.flintclient.client.control.platform.Mc263ControlPlatform.keyDown(com.mojang.blaze3d.platform.InputConstants.KEY_RSHIFT);
	}

	@Override
	public void mouseDrag(float mx, float my) {
		editor.dragTo(editor.toGuiX(mx), editor.toGuiY(my));
	}

	@Override
	public void mouseUp(float mx, float my, int button) {
		editor.release();
	}

	@Override
	public boolean scroll(float mx, float my, double amount) {
		return editor.scroll(editor.toGuiX(mx), editor.toGuiY(my), amount);
	}

	@Override
	public boolean keyDown(KeyEvent event) {
		// Enter or Space: the next module that is shown (Shift: the previous one), so every module can be reached.
		if (event.isSelection()) {
			if (editor.selectNext(event.hasShiftDown() ? -1 : 1) && screen() != null) {
				screen().announce(this);
			}
			return true;
		}
		return false;
	}

	/** The module's rectangle in UI units into {@code out}: x0, y0, x1, y1. */
	private void rect(HudScene scene, int index, float[] out) {
		out[0] = editor.toUiX(scene.x(index));
		out[1] = editor.toUiY(scene.y(index));
		out[2] = editor.toUiX(scene.x(index) + scene.width(index));
		out[3] = editor.toUiY(scene.y(index) + scene.height(index));
	}

	private final float[] box = new float[4];

	@Override
	protected void paint(Canvas c) {
		HudScene scene = editor.scene();
		int hovered = editor.hovered();
		int selected = editor.selected();
		if (hovered >= 0 && hovered != selected && scene.state(hovered) != HudScene.OFF && !editor.dragging()) {
			rect(scene, hovered, box);
			int g = c.px(1);
			int x0 = c.px(box[0]) - g;
			int y0 = c.px(box[1]) - g;
			int x1 = c.px(box[2]) + g;
			int y1 = c.px(box[3]) + g;
			c.ring(x0, y0, x1 - x0, y1 - y0, c.px(2), 1, HOVER_OUTLINE);
		}
		if (!editor.chromeHidden()) {
			// The rest of the selection: a thin ember outline each; the primary gets the brackets and handle.
			for (int i = 0; i < scene.size(); i++) {
				if (i != selected && editor.inGroup(i) && scene.state(i) != HudScene.OFF) {
					rect(scene, i, box);
					int g = c.px(1);
					c.ring(c.px(box[0]) - g, c.px(box[1]) - g, c.px(box[2] - box[0]) + 2 * g, c.px(box[3] - box[1]) + 2 * g, c.px(2), 1,
						Palette.withAlpha(Tokens2.EMBER, 0.8F));
				}
			}
		}
		if (selected >= 0 && scene.state(selected) != HudScene.OFF && !editor.chromeHidden()) {
			paintSelection(c, scene, selected);
		}
		if (editor.selectionBox(box)) {
			int x0 = c.px(editor.toUiX(box[0]));
			int y0 = c.px(editor.toUiY(box[1]));
			int x1 = c.px(editor.toUiX(box[2]));
			int y1 = c.px(editor.toUiY(box[3]));
			c.fill(x0, y0, x1, y1, Palette.withAlpha(Tokens2.EMBER, 0.10F));
			c.ring(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0), 0, 1, Palette.withAlpha(Tokens2.EMBER, 0.7F));
		}
		paintFocus(c, scene, selected);
		paintHint(c);
	}

	/** Four ember corner brackets 2 units out, the tag above, and the size handle at the bottom-right corner. */
	private void paintSelection(Canvas c, HudScene scene, int selected) {
		rect(scene, selected, box);
		int x0 = c.px(box[0] - BRACKET_GAP);
		int y0 = c.px(box[1] - BRACKET_GAP);
		int x1 = c.px(box[2] + BRACKET_GAP);
		int y1 = c.px(box[3] + BRACKET_GAP);
		int arm = c.px(BRACKET_ARM);
		int ember = Tokens2.EMBER;
		// A hairline that holds up over a bright world: a pixel, two from 4 pixels per unit.
		int t = Math.max(1, Math.round(c.scale() * 0.4F));
		// Top-left, top-right, bottom-left, bottom-right: each an L of two strokes.
		c.fill(x0, y0, x0 + arm, y0 + t, ember);
		c.fill(x0, y0, x0 + t, y0 + arm, ember);
		c.fill(x1 - arm, y0, x1, y0 + t, ember);
		c.fill(x1 - t, y0, x1, y0 + arm, ember);
		c.fill(x0, y1 - t, x0 + arm, y1, ember);
		c.fill(x0, y1 - arm, x0 + t, y1, ember);
		c.fill(x1 - arm, y1 - t, x1, y1, ember);
		c.fill(x1 - t, y1 - arm, x1, y1, ember);
		// The size handle: a light dot ringed in ember on the module's corner.
		int handle = c.px(HANDLE);
		int ring = handle + 2;
		int hx = c.px(box[2]) - ring / 2;
		int hy = c.px(box[3]) - ring / 2;
		c.roundRect(hx, hy, ring, ring, ring / 2, ember);
		c.roundRect(hx + 1, hy + 1, handle, handle, handle / 2, Tokens2.TEXT);
		// The mini-bar names the module and its size; no tag of its own.
	}

	/**
	 * The first time the editor opens in a session, a quiet line at the bottom centre says how it works, then fades
	 * after a few seconds.
	 */
	private void paintHint(Canvas c) {
		long now = net.minecraft.util.Util.getMillis();
		if (hintStart == 0) {
			hintStart = now;
			hintShown = !hintSeen;
			hintSeen = true;
		}
		if (!hintShown) {
			return;
		}
		float age = (now - hintStart) / 1000.0F;
		float alpha = age < 0.4F ? age / 0.4F : age < 5.5F ? 1 : Math.max(0, 1 - (age - 5.5F) / 0.8F);
		if (alpha <= 0.01F || editor.dragging()) {
			return;
		}
		// Just above Minecraft's hotbar and health bars, so it never sits on them.
		int vanilla = (int) Math.round(Minecraft.getInstance().getWindow().getGuiScale()
			* (VanillaAreas.HOTBAR_HEIGHT + VanillaAreas.STATUS_HEIGHT));
		int center = c.px(y + h) - vanilla - c.px(14);
		int cx = c.px(x + w / 2);
		int textW = c.width(hint);
		int padX = c.px(9);
		int ph = c.px(16);
		c.roundRect(cx - textW / 2 - padX, center - ph / 2, textW + 2 * padX, ph, ph / 2, Palette.withAlpha(0xCC131211, alpha));
		c.textCentered(hint, cx, c.baselineFor(hint, center), Palette.withAlpha(0x99FFFFFF, alpha));
	}

	private static boolean hintSeen;
	private boolean hintShown;
	private long hintStart;
	private final TextLine hint = new TextLine(Type.NOTE, "Drag to move · Scroll to resize · Right-drag selects several · ? lists every key");

	static String scaleLabel(float scale) {
		int step = ScaleSteps.index(scale);
		return switch (step) {
			case 0 -> "0.5×";
			case 1 -> "0.75×";
			case 3 -> "1.25×";
			case 4 -> "1.5×";
			case 5 -> "2×";
			default -> "1×";
		};
	}

	/** The focus ring (keyboard focus only): around the selection, or inside the screen's edge with none. */
	private void paintFocus(Canvas c, HudScene scene, int selected) {
		if (focus <= 0.01F) {
			return;
		}
		int line = Kit.prefs().highContrast() ? 2 : 1;
		int color = Palette.withAlpha(Tokens2.EMBER, focus);
		if (selected < 0 || scene.state(selected) == HudScene.OFF) {
			int inset = c.px(2);
			c.ringCorners(px0(c) + inset, py0(c) + inset, pxW(c) - 2 * inset, pxH(c) - 2 * inset, c.px(Tokens2.RADIUS_DECK), line, color);
			return;
		}
		// Clear of the brackets and the handle, then the kit's usual distance.
		float gap = Tokens2.FOCUS_GAP + BRACKET_GAP + 1;
		int x0 = c.px(editor.toUiX(scene.x(selected) - SELECTION_REACH / 2) - gap);
		int y0 = c.px(editor.toUiY(scene.y(selected) - SELECTION_REACH / 2) - gap);
		int x1 = c.px(editor.toUiX(scene.x(selected) + scene.width(selected) + SELECTION_REACH / 2) + gap);
		int y1 = c.px(editor.toUiY(scene.y(selected) + scene.height(selected) + SELECTION_REACH / 2) + gap);
		c.ringCorners(x0, y0, x1 - x0, y1 - y0, c.px(4), line, color);
	}

	@Override
	public TextLine @Nullable [] tooltipLines() {
		tipModule = HudEditor.NONE;
		if (screen() == null || editor.dragging()) {
			return null;
		}
		int module = editor.scene().hit(editor.toGuiX(screen().mouseX()), editor.toGuiY(screen().mouseY()));
		if (module < 0) {
			return null;
		}
		int area = editor.overlap(module);
		if (area < 0) {
			return null;
		}
		tipModule = module;
		return WARNINGS[area];
	}

	@Override
	protected void tooltipAnchor(float[] out) {
		HudScene scene = editor.scene();
		if (tipModule < 0) {
			super.tooltipAnchor(out);
			return;
		}
		out[0] = editor.toUiX(scene.x(tipModule));
		out[1] = editor.toUiY(scene.y(tipModule));
		out[2] = editor.toUiX(scene.width(tipModule));
		out[3] = editor.toUiY(scene.height(tipModule));
	}
}
