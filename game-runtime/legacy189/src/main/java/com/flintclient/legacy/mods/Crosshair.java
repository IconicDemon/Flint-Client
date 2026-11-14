package com.flintclient.legacy.mods;

import com.flintclient.client.control.feature.CrosshairDrawing;
import com.flintclient.client.control.feature.CrosshairPicture;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.PlayerMods;
import com.flintclient.client.platform.Frame;
import com.flintclient.legacy.module.Module;
import com.flintclient.legacy.module.Setting;
import com.flintclient.legacy.ui.Gfx;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.platform.GlStateManager;
import org.lwjgl.opengl.GL11;

/**
 * Your own crosshair in place of Minecraft's, on 1.8.9: every style the other lines have (cross, circle, square, triangle, arrow,
 * Minecraft's debug arms and your drawn one), its arms' length, gap and thickness, its rotation, scale and offset, its colour or a rainbow
 * and opacity, an outline and a dot, or Minecraft's own colours inverted behind it (adaptive colour). It is the shared
 * {@link CrosshairPicture} the menu's preview draws, in whole window pixels at the screen's centre, so the preview and the
 * game always agree; worked out again only when a setting or the drawing changes. Vanilla leaves Minecraft's own.
 */
public final class Crosshair extends Module {
	private static final int VANILLA = 0;
	public final Setting.Choice type = option(PlayerMods.Crosshair.TYPE);
	public final Setting.Color color = color(PlayerMods.Crosshair.COLOR, PlayerMods.Crosshair.ALPHA, false);
	public final Setting.Bool invert = bool(PlayerMods.Crosshair.INVERT);
	public final Setting.Int width = integer(PlayerMods.Crosshair.WIDTH);
	public final Setting.Int height = integer(PlayerMods.Crosshair.HEIGHT);
	public final Setting.Int gap = integer(PlayerMods.Crosshair.GAP);
	public final Setting.Int thickness = integer(PlayerMods.Crosshair.THICKNESS);
	public final Setting.Int rotation = integer(PlayerMods.Crosshair.ROTATION);
	public final Setting.Int scale = integer(PlayerMods.Crosshair.SCALE);
	public final Setting.Int offsetX = integer(PlayerMods.Crosshair.OFFSET_X);
	public final Setting.Int offsetY = integer(PlayerMods.Crosshair.OFFSET_Y);
	public final Setting.Bool outline = bool(PlayerMods.Crosshair.OUTLINE);
	public final Setting.Color outlineColor = color(PlayerMods.Crosshair.OUTLINE_COLOR, PlayerMods.Crosshair.OUTLINE_ALPHA, false);
	public final Setting.Bool dot = bool(PlayerMods.Crosshair.DOT);
	public final Setting.Color dotColor = color(PlayerMods.Crosshair.DOT_COLOR, PlayerMods.Crosshair.DOT_ALPHA, false);
	public final Setting.Bool rainbow = bool(PlayerMods.Crosshair.RAINBOW);
	public final Setting.Int rainbowSpeed = integer(PlayerMods.Crosshair.RAINBOW_SPEED);
	private CrosshairPicture picture;
	private long drawnFor = Long.MIN_VALUE;
	/** The picture's runs: x0, x1, y, colour (0 for an inverted run) for each, from the picture's corner. */
	private int[] runs = new int[256];
	private int runCount;
	private int half;

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
		int gs = Frame.windowGuiScale();
		ModuleState s = state();
		CrosshairDrawing drawing = CrosshairDrawing.shared();
		float ppu = gs * Math.max(0.01F, scale.value() / 100.0F);
		int reach = Math.min(4096, (int) Math.ceil(CrosshairPicture.extent(s, drawing) * ppu) + 4);
		int ox = offsetX.value() * gs;
		int oy = offsetY.value() * gs;
		// The picture holds the crosshair and the dot (which stays on the screen's centre, without the offset).
		int need = Math.max(reach + Math.max(Math.abs(ox), Math.abs(oy)), 8);
		// The rainbow moves every frame (about 60 steps a second, as the other lines count it).
		int ticks = rainbow.on() ? (int) (System.currentTimeMillis() / 16 % 125000) : 0;
		long key = ((((long) System.identityHashCode(s) * 31 + s.revision()) * 31 + drawing.revision()) * 31 + gs) * 31 + need;
		key = key * 31 + ticks;
		if (picture == null || key != drawnFor) {
			drawnFor = key;
			half = need;
			if (picture == null || picture.width() != 2 * half) {
				picture = new CrosshairPicture(2 * half, 2 * half);
			}
			picture.drawAt(s, gs, null, drawing, ticks, half + ox, half + oy, half, half);
			runs();
		}
		int cx = mc.width / gs / 2 * gs;
		int cy = mc.height / gs / 2 * gs;
		int left = cx - half;
		int top = cy - half;
		Gfx.begin();
		for (int i = 0; i < runCount; i++) {
			int at = i * 4;
			if (runs[at + 3] != 0) {
				Gfx.rect(left + runs[at], top + runs[at + 2], runs[at + 1] - runs[at], 1, runs[at + 3]);
			}
		}
		Gfx.flush();
		// Adaptive colour: white drawn so that it inverts what is behind it.
		GlStateManager.blendFuncSeparate(GL11.GL_ONE_MINUS_DST_COLOR, GL11.GL_ONE_MINUS_SRC_COLOR, GL11.GL_ONE, GL11.GL_ZERO);
		for (int i = 0; i < runCount; i++) {
			int at = i * 4;
			if (runs[at + 3] == 0) {
				Gfx.rect(left + runs[at], top + runs[at + 2], runs[at + 1] - runs[at], 1, 0xFFFFFFFF);
			}
		}
		Gfx.end();
		GlStateManager.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
	}

	/** The picture as runs of one colour along each row (inverted runs apart). */
	private void runs() {
		runCount = 0;
		int[] box = picture.touched();
		if (box == null) {
			return;
		}
		int[] px = picture.pixels();
		int w = picture.width();
		for (int y = box[1]; y <= box[3]; y++) {
			int x = box[0];
			while (x <= box[2]) {
				int color = px[y * w + x];
				if (color == 0) {
					x++;
					continue;
				}
				boolean inv = picture.inverted(x, y);
				int end = x + 1;
				while (end <= box[2] && px[y * w + end] == color && picture.inverted(end, y) == inv) {
					end++;
				}
				if ((runCount + 1) * 4 > runs.length) {
					runs = java.util.Arrays.copyOf(runs, runs.length * 2);
				}
				int at = runCount * 4;
				runs[at] = x;
				runs[at + 1] = end;
				runs[at + 2] = y;
				runs[at + 3] = inv ? 0 : color;
				runCount++;
				x = end;
			}
		}
	}
}
