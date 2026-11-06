package com.flintclient.legacy.mods;

import com.flintclient.client.control.model.mods.PlayerMods;
import com.flintclient.client.platform.Frame;
import com.flintclient.client.platform.HudData;
import com.flintclient.legacy.module.Module;
import com.flintclient.legacy.module.Setting;
import com.flintclient.legacy.ui.Gfx;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.platform.GlStateManager;
import org.lwjgl.opengl.GL11;

/**
 * Your own crosshair in place of Minecraft's: a cross, a circle or a square, its arms' length, gap and thickness in GUI units at
 * its scale, its colour and opacity, an outline and a dot, or Minecraft's own colours inverted behind it (adaptive colour).
 * Drawn in whole window pixels at the screen's centre, wherever Minecraft would draw its own. The 1.8.9 backend of Crosshair;
 * the other lines' arrow, triangle and drawn crosshairs are crosses here for now, and Vanilla leaves Minecraft's own.
 */
public final class Crosshair extends Module {
	private static final int VANILLA = 0;
	private static final int CIRCLE = 2;
	private static final int SQUARE = 3;
	public final Setting.Choice type = option(PlayerMods.Crosshair.TYPE);
	public final Setting.Color color = color(PlayerMods.Crosshair.COLOR, PlayerMods.Crosshair.ALPHA, false);
	public final Setting.Bool invert = bool(PlayerMods.Crosshair.INVERT);
	public final Setting.Int width = integer(PlayerMods.Crosshair.WIDTH);
	public final Setting.Int height = integer(PlayerMods.Crosshair.HEIGHT);
	public final Setting.Int gap = integer(PlayerMods.Crosshair.GAP);
	public final Setting.Int thickness = integer(PlayerMods.Crosshair.THICKNESS);
	public final Setting.Int scale = integer(PlayerMods.Crosshair.SCALE);
	public final Setting.Bool outline = bool(PlayerMods.Crosshair.OUTLINE);
	public final Setting.Color outlineColor = color(PlayerMods.Crosshair.OUTLINE_COLOR, PlayerMods.Crosshair.OUTLINE_ALPHA, false);
	public final Setting.Bool dot = bool(PlayerMods.Crosshair.DOT);
	public final Setting.Color dotColor = color(PlayerMods.Crosshair.DOT_COLOR, PlayerMods.Crosshair.DOT_ALPHA, false);

	public Crosshair() {
		super("crosshair", "crosshair");
	}

	/** Whether Flint draws the crosshair (Vanilla keeps Minecraft's). */
	public boolean replaces() {
		return enabled() && type.index() != VANILLA;
	}

	/** Draws the crosshair at the window's centre (from the GUI hook, where Minecraft draws its own). */
	public void draw() {
		Minecraft mc = Minecraft.getInstance();
		int cx = mc.width / 2;
		int cy = mc.height / 2;
		float k = Frame.windowGuiScale() * scale.value() / 100F;
		Gfx.begin();
		if (invert.on()) {
			Gfx.flush();
			GlStateManager.blendFuncSeparate(GL11.GL_ONE_MINUS_DST_COLOR, GL11.GL_ONE_MINUS_SRC_COLOR, GL11.GL_ONE, GL11.GL_ZERO);
		}
		int c = invert.on() ? 0xFFFFFFFF : color.argb();
		if (outline.on() && !invert.on()) {
			shape(cx, cy, k, 1, outlineColor.argb());
		}
		shape(cx, cy, k, 0, c);
		if (dot.on()) {
			Gfx.flush();
			GlStateManager.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
			int d = Math.max(2, Math.round(2 * k));
			Gfx.round(cx - d / 2.0F, cy - d / 2.0F, d, d, d / 2.0F, dotColor.argb());
		}
		Gfx.end();
		GlStateManager.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
	}

	/** The shape, grown by {@code grow} pixels on every side (1 for the outline under it), {@code k} pixels per unit. */
	private void shape(int cx, int cy, float k, int grow, int argb) {
		int t = Math.max(1, Math.round(thickness.value() * k));
		int w = Math.round(width.value() * k);
		int h = Math.round(height.value() * k);
		int g = Math.round(gap.value() * k);
		// The centre square every arm lines up with: t pixels wide, starting half of it left of (above) the centre.
		int x0 = cx - t / 2;
		int y0 = cy - t / 2;
		int kind = type.index();
		if (kind == CIRCLE) {
			int r = Math.max(w, h) + g + grow;
			Gfx.ring(cx - r, cy - r, 2 * r, 2 * r, r, t + 2 * grow, argb);
		} else if (kind == SQUARE) {
			int r = Math.max(w, h) + g + grow;
			Gfx.ring(cx - r, cy - r, 2 * r, 2 * r, 0, t + 2 * grow, argb);
		} else {
			arm(x0, y0 - g - h, t, h, grow, argb);
			arm(x0, y0 + t + g, t, h, grow, argb);
			arm(x0 - g - w, y0, w, t, grow, argb);
			arm(x0 + t + g, y0, w, t, grow, argb);
		}
	}

	private static void arm(int x, int y, int w, int h, int grow, int argb) {
		if (w > 0 && h > 0) {
			Gfx.rect(x - grow, y - grow, w + 2 * grow, h + 2 * grow, argb);
		}
	}
}
