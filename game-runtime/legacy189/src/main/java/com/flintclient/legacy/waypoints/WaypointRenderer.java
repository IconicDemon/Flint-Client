package com.flintclient.legacy.waypoints;

import com.flintclient.client.platform.Frame;
import com.flintclient.client.platform.HudData;
import com.flintclient.legacy.mods.Waypoints;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.client.render.vertex.BufferBuilder;
import net.minecraft.client.render.vertex.DefaultVertexFormat;
import net.minecraft.client.render.vertex.Tesselator;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

/**
 * Draws waypoints on 1.8.9 the way wWaypoints does.
 *
 * <ul>
 * <li>In the world, after the translucent blocks and before your hand ({@link #world}): each waypoint's block within your
 * render distance, its edges in its colour and its faces tinted, hidden behind blocks unless Render Through Walls is on. The
 * camera's matrices are kept from that moment for the labels.</li>
 * <li>Over the world, first in the HUD ({@link #labels}): each label projected from 2.5 blocks over its block with the matrices
 * the world was drawn with (so it stays on its block while you walk, bob and turn), sized by its distance from the camera, the
 * farthest drawn first. The Icon style is the first letter in a dark square; the distance under it shows while you look at it.
 * A label fades out as you reach it. One you aim at comes to the top, and the labels it covers that are farther dim.</li>
 * </ul>
 * Render thread; nothing is allocated per frame but the small text of a distance that changed.
 */
public final class WaypointRenderer {
	private static final int CAP = 200;
	private static final FloatBuffer MODELVIEW = BufferUtils.createFloatBuffer(16);
	private static final FloatBuffer PROJECTION = BufferUtils.createFloatBuffer(16);
	private static final IntBuffer VIEWPORT = BufferUtils.createIntBuffer(16);
	private static final float[] VIEW = new float[16];
	private static final float[] PROJ = new float[16];
	private static final int[] PORT = new int[4];
	private static boolean captured;
	/** The camera entity's interpolated feet: what the world's vertices were drawn relative to. */
	private static double baseX;
	private static double baseY;
	private static double baseZ;

	private static final Waypoint[] W = new Waypoint[CAP];
	private static final float[] SX = new float[CAP];
	private static final float[] SY = new float[CAP];
	private static final double[] CAM_DIST = new double[CAP];
	private static final int[] DIST = new int[CAP];
	private static final float[] ALPHA = new float[CAP];
	private static final float[] ANGLE = new float[CAP];
	private static final float[] DIM = new float[CAP];
	private static final boolean[] ELIGIBLE = new boolean[CAP];
	private static final float[] AIM = new float[CAP];
	private static final Map<Waypoint, float[]> AIM_BY = new IdentityHashMap<Waypoint, float[]>();
	private static final String[] DISTANCE_TEXT = new String[CAP];
	private static long lastAimNanos;

	private WaypointRenderer() {
	}

	private static Waypoints mod() {
		return com.flintclient.legacy.Flint.modules().waypoints;
	}

	// ---------- in the world ----------

	/** From the world pass, just before the hand: keeps the camera's matrices and draws the waypoints' blocks. */
	public static void world(float tickDelta) {
		Minecraft mc = Minecraft.getInstance();
		Entity camera = mc.getCamera();
		if (camera == null || mc.world == null) {
			captured = false;
			return;
		}
		MODELVIEW.clear();
		PROJECTION.clear();
		VIEWPORT.clear();
		GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MODELVIEW);
		GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, PROJECTION);
		GL11.glGetInteger(GL11.GL_VIEWPORT, VIEWPORT);
		MODELVIEW.get(VIEW);
		PROJECTION.get(PROJ);
		for (int i = 0; i < 4; i++) {
			PORT[i] = VIEWPORT.get(i);
		}
		baseX = camera.prevX + (camera.x - camera.prevX) * tickDelta;
		baseY = camera.prevY + (camera.y - camera.prevY) * tickDelta;
		baseZ = camera.prevZ + (camera.z - camera.prevZ) * tickDelta;
		captured = true;
		Waypoints m = mod();
		if (Waypoints.hidden() || m.display.is("label") || mc.player == null) {
			return;
		}
		Waypoint[] all = Waypoints.store().array();
		int dimension = mc.player.dimension;
		double reach = Math.max(2, mc.options.viewDistance * 16);
		int beyond = m.hideBeyond.value();
		int outline = Math.round(m.outlineOpacity.value() * 2.55F);
		int fill = Math.round(m.fillOpacity.value() * 2.55F);
		boolean any = false;
		for (Waypoint w : all) {
			if (!w.visible || w.death || w.dimension != dimension) {
				continue;
			}
			double d = w.distanceTo(mc.player.x, mc.player.y, mc.player.z);
			if (d > reach || beyond > 0 && d > beyond) {
				continue;
			}
			if (!any) {
				begin3d(m.throughWalls.on());
				any = true;
			}
			Box box = shape(mc, w).moved(-baseX, -baseY, -baseZ);
			if (fill > 0) {
				faces(box, fill << 24 | w.rgb);
			}
			if (outline > 0 && m.outlineThickness.value() > 0) {
				GL11.glLineWidth(Math.max(1, m.outlineThickness.decimal()));
				edges(box, outline << 24 | w.rgb);
			}
		}
		if (any) {
			end3d();
		}
	}

	/** The block's own outline shape (slabs, stairs, buttons fit), else a whole block; a hair larger so it never flickers. */
	private static Box shape(Minecraft mc, Waypoint w) {
		BlockPos pos = new BlockPos(w.x, w.y, w.z);
		Block block = mc.world.getBlockState(pos).getBlock();
		Box box;
		if (block.getMaterial() != Material.AIR) {
			block.updateShape(mc.world, pos);
			box = block.getOutlineShape(mc.world, pos);
		} else {
			box = new Box(w.x, w.y, w.z, w.x + 1, w.y + 1, w.z + 1);
		}
		return box.grown(0.006, 0.006, 0.006);
	}

	private static void begin3d(boolean throughWalls) {
		GlStateManager.enableBlend();
		GlStateManager.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
		GlStateManager.disableTexture();
		GlStateManager.disableLighting();
		GlStateManager.disableAlphaTest();
		GlStateManager.disableFog();
		GlStateManager.depthMask(false);
		GlStateManager.disableCull();
		if (throughWalls) {
			GlStateManager.disableDepthTest();
		}
	}

	private static void end3d() {
		GL11.glLineWidth(1);
		GlStateManager.enableDepthTest();
		GlStateManager.enableCull();
		GlStateManager.depthMask(true);
		GlStateManager.enableAlphaTest();
		GlStateManager.enableTexture();
		GlStateManager.disableBlend();
		GlStateManager.color4f(1, 1, 1, 1);
	}

	private static void faces(Box b, int argb) {
		int r = (argb >> 16) & 255;
		int g = (argb >> 8) & 255;
		int bl = argb & 255;
		int a = argb >>> 24;
		Tesselator t = Tesselator.getInstance();
		BufferBuilder buf = t.getBuffer();
		buf.begin(GL11.GL_QUADS, DefaultVertexFormat.POSITION_COLOR);
		quad(buf, b.minX, b.minY, b.minZ, b.maxX, b.minY, b.minZ, b.maxX, b.minY, b.maxZ, b.minX, b.minY, b.maxZ, r, g, bl, a);
		quad(buf, b.minX, b.maxY, b.minZ, b.minX, b.maxY, b.maxZ, b.maxX, b.maxY, b.maxZ, b.maxX, b.maxY, b.minZ, r, g, bl, a);
		quad(buf, b.minX, b.minY, b.minZ, b.minX, b.maxY, b.minZ, b.maxX, b.maxY, b.minZ, b.maxX, b.minY, b.minZ, r, g, bl, a);
		quad(buf, b.minX, b.minY, b.maxZ, b.maxX, b.minY, b.maxZ, b.maxX, b.maxY, b.maxZ, b.minX, b.maxY, b.maxZ, r, g, bl, a);
		quad(buf, b.minX, b.minY, b.minZ, b.minX, b.minY, b.maxZ, b.minX, b.maxY, b.maxZ, b.minX, b.maxY, b.minZ, r, g, bl, a);
		quad(buf, b.maxX, b.minY, b.minZ, b.maxX, b.maxY, b.minZ, b.maxX, b.maxY, b.maxZ, b.maxX, b.minY, b.maxZ, r, g, bl, a);
		t.end();
	}

	private static void quad(BufferBuilder buf, double x0, double y0, double z0, double x1, double y1, double z1, double x2, double y2, double z2,
		double x3, double y3, double z3, int r, int g, int b, int a) {
		buf.vertex(x0, y0, z0).color(r, g, b, a).nextVertex();
		buf.vertex(x1, y1, z1).color(r, g, b, a).nextVertex();
		buf.vertex(x2, y2, z2).color(r, g, b, a).nextVertex();
		buf.vertex(x3, y3, z3).color(r, g, b, a).nextVertex();
	}

	private static void edges(Box b, int argb) {
		int r = (argb >> 16) & 255;
		int g = (argb >> 8) & 255;
		int bl = argb & 255;
		int a = argb >>> 24;
		Tesselator t = Tesselator.getInstance();
		BufferBuilder buf = t.getBuffer();
		buf.begin(GL11.GL_LINES, DefaultVertexFormat.POSITION_COLOR);
		double[] xs = {b.minX, b.maxX};
		double[] ys = {b.minY, b.maxY};
		double[] zs = {b.minZ, b.maxZ};
		for (double y : ys) {
			for (double z : zs) {
				line(buf, b.minX, y, z, b.maxX, y, z, r, g, bl, a);
			}
			for (double x : xs) {
				line(buf, x, y, b.minZ, x, y, b.maxZ, r, g, bl, a);
			}
		}
		for (double x : xs) {
			for (double z : zs) {
				line(buf, x, b.minY, z, x, b.maxY, z, r, g, bl, a);
			}
		}
		t.end();
	}

	private static void line(BufferBuilder buf, double x0, double y0, double z0, double x1, double y1, double z1, int r, int g, int b, int a) {
		buf.vertex(x0, y0, z0).color(r, g, b, a).nextVertex();
		buf.vertex(x1, y1, z1).color(r, g, b, a).nextVertex();
	}

	// ---------- the labels ----------

	/** Where a point relative to {@link #baseX} lands on the GUI ({@code out}); false behind the camera. */
	private static boolean project(double rx, double ry, double rz, float guiScale, float[] out) {
		double vx = VIEW[0] * rx + VIEW[4] * ry + VIEW[8] * rz + VIEW[12];
		double vy = VIEW[1] * rx + VIEW[5] * ry + VIEW[9] * rz + VIEW[13];
		double vz = VIEW[2] * rx + VIEW[6] * ry + VIEW[10] * rz + VIEW[14];
		double vw = VIEW[3] * rx + VIEW[7] * ry + VIEW[11] * rz + VIEW[15];
		double cx = PROJ[0] * vx + PROJ[4] * vy + PROJ[8] * vz + PROJ[12] * vw;
		double cy = PROJ[1] * vx + PROJ[5] * vy + PROJ[9] * vz + PROJ[13] * vw;
		double cw = PROJ[3] * vx + PROJ[7] * vy + PROJ[11] * vz + PROJ[15] * vw;
		if (cw < 0.05) {
			return false;
		}
		double px = PORT[0] + (cx / cw + 1) / 2 * PORT[2];
		double py = PORT[1] + (cy / cw + 1) / 2 * PORT[3];
		int height = Minecraft.getInstance().height;
		out[0] = (float) (px / guiScale);
		out[1] = (float) ((height - py) / guiScale);
		return true;
	}

	private static final float[] AT = new float[2];

	/** From the HUD, before Minecraft's own: every waypoint label of this dimension (GUI pixels). */
	public static void labels(float tickDelta) {
		Minecraft mc = Minecraft.getInstance();
		if (!captured || mc.player == null || Waypoints.hidden()) {
			return;
		}
		Waypoints m = mod();
		if (m.display.is("block")) {
			return;
		}
		Waypoint[] all = Waypoints.store().array();
		if (all.length == 0) {
			return;
		}
		float gs = Frame.windowGuiScale();
		float screenW = HudData.guiWidth();
		float screenH = HudData.guiHeight();
		// The camera in the world's relative frame (third person included) and the way it looks: from the view matrix.
		double camX = -(VIEW[0] * VIEW[12] + VIEW[1] * VIEW[13] + VIEW[2] * VIEW[14]);
		double camY = -(VIEW[4] * VIEW[12] + VIEW[5] * VIEW[13] + VIEW[6] * VIEW[14]);
		double camZ = -(VIEW[8] * VIEW[12] + VIEW[9] * VIEW[13] + VIEW[10] * VIEW[14]);
		double lookX = -VIEW[2];
		double lookY = -VIEW[6];
		double lookZ = -VIEW[10];
		double px = mc.player.prevX + (mc.player.x - mc.player.prevX) * tickDelta;
		double py = mc.player.prevY + (mc.player.y - mc.player.prevY) * tickDelta;
		double pz = mc.player.prevZ + (mc.player.z - mc.player.prevZ) * tickDelta;
		int dimension = mc.player.dimension;
		int beyond = m.hideBeyond.value();
		double height = m.labelHeight.decimal();
		float margin = 64;
		int n = 0;
		for (Waypoint w : all) {
			if (n >= CAP) {
				break;
			}
			if (!w.visible || w.dimension != dimension) {
				continue;
			}
			double dist = w.distanceTo(px, py, pz);
			if (beyond > 0 && dist > beyond) {
				continue;
			}
			float alpha = w.death ? LabelMath.nearFade(dist, 7.5, 5) : LabelMath.nearFade(dist, 2, 1);
			if (alpha <= 0.02F) {
				continue;
			}
			double rx = w.x + 0.5 - baseX;
			double ry = w.y + 1 + height - baseY;
			double rz = w.z + 0.5 - baseZ;
			if (!project(rx, ry, rz, gs, AT) || AT[0] < -margin || AT[1] < -margin || AT[0] > screenW + margin || AT[1] > screenH + margin) {
				continue;
			}
			double dx = rx - camX;
			double dy = ry - camY;
			double dz = rz - camZ;
			W[n] = w;
			SX[n] = AT[0];
			SY[n] = AT[1];
			CAM_DIST[n] = Math.sqrt(dx * dx + dy * dy + dz * dz);
			int blocks = (int) Math.round(dist);
			if (DIST[n] != blocks || DISTANCE_TEXT[n] == null) {
				DIST[n] = blocks;
				DISTANCE_TEXT[n] = LabelMath.distanceText(blocks);
			}
			ALPHA[n] = alpha;
			ANGLE[n] = (float) LabelMath.angle(lookX, lookY, lookZ, dx, dy, dz);
			n++;
		}
		if (n == 0) {
			return;
		}
		sortFarFirst(n);
		focus(n);
		begin2d();
		try {
			// Every label, farthest first; the one aimed at waits for the second pass.
			for (int i = 0; i < n; i++) {
				float a = ALPHA[i] * (1 - Math.min(0.7F, DIM[i])) * (1 - AIM[i]);
				if (a > 0.02F) {
					draw(m, W[i], SX[i], SY[i], CAM_DIST[i], DISTANCE_TEXT[i], ANGLE[i], a);
				}
			}
			for (int i = 0; i < n; i++) {
				if (ELIGIBLE[i] && AIM[i] > 0.02F) {
					draw(m, W[i], SX[i], SY[i], CAM_DIST[i], DISTANCE_TEXT[i], ANGLE[i], ALPHA[i] * AIM[i]);
				}
			}
		} finally {
			end2d();
		}
	}

	/** Insertion sort, farthest from the camera first (a few dozen labels at most, nearly sorted from the last frame). */
	private static void sortFarFirst(int n) {
		for (int i = 1; i < n; i++) {
			Waypoint w = W[i];
			float sx = SX[i];
			float sy = SY[i];
			double cd = CAM_DIST[i];
			int dist = DIST[i];
			String text = DISTANCE_TEXT[i];
			float alpha = ALPHA[i];
			float angle = ANGLE[i];
			int j = i - 1;
			while (j >= 0 && CAM_DIST[j] < cd) {
				W[j + 1] = W[j];
				SX[j + 1] = SX[j];
				SY[j + 1] = SY[j];
				CAM_DIST[j + 1] = CAM_DIST[j];
				DIST[j + 1] = DIST[j];
				DISTANCE_TEXT[j + 1] = DISTANCE_TEXT[j];
				ALPHA[j + 1] = ALPHA[j];
				ANGLE[j + 1] = ANGLE[j];
				j--;
			}
			W[j + 1] = w;
			SX[j + 1] = sx;
			SY[j + 1] = sy;
			CAM_DIST[j + 1] = cd;
			DIST[j + 1] = dist;
			DISTANCE_TEXT[j + 1] = text;
			ALPHA[j + 1] = alpha;
			ANGLE[j + 1] = angle;
		}
	}

	/**
	 * wWaypoints' focus: a label within 7 degrees of where you aim (fully within 2) that overlaps another (44 × 22 GUI pixels)
	 * comes to the top, easing in at 9 per second and out at 20; the labels it overlaps that are farther dim by 45%.
	 */
	private static void focus(int n) {
		long now = System.nanoTime();
		float dt = lastAimNanos == 0 ? 0.016F : Math.min(0.1F, (now - lastAimNanos) / 1e9F);
		lastAimNanos = now;
		for (int i = 0; i < n; i++) {
			DIM[i] = 0;
			ELIGIBLE[i] = false;
		}
		for (int i = 0; i < n; i++) {
			float target = LabelMath.aimAlpha(ANGLE[i]);
			if (target < 0.72F) {
				continue;
			}
			for (int j = 0; j < n; j++) {
				if (i != j && Math.abs(SX[i] - SX[j]) <= 44 && Math.abs(SY[i] - SY[j]) <= 22) {
					ELIGIBLE[i] = true;
					if (CAM_DIST[j] > CAM_DIST[i]) {
						DIM[j] = Math.max(DIM[j], 0.45F * target);
					}
				}
			}
		}
		for (int i = 0; i < n; i++) {
			float[] aim = AIM_BY.get(W[i]);
			if (!ELIGIBLE[i]) {
				if (aim != null) {
					AIM_BY.remove(W[i]);
				}
				AIM[i] = 0;
				continue;
			}
			if (aim == null) {
				aim = new float[1];
				AIM_BY.put(W[i], aim);
			}
			float target = LabelMath.aimAlpha(ANGLE[i]);
			float rate = target > aim[0] ? 9 : 20;
			aim[0] = aim[0] < target ? Math.min(target, aim[0] + rate * dt) : Math.max(target, aim[0] - rate * dt);
			AIM[i] = aim[0];
		}
		if (AIM_BY.size() > 64) {
			AIM_BY.clear();
		}
	}

	private static void begin2d() {
		GlStateManager.enableBlend();
		GlStateManager.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
		GlStateManager.disableAlphaTest();
		GlStateManager.disableLighting();
		GlStateManager.disableDepthTest();
	}

	private static void end2d() {
		GlStateManager.enableTexture();
		GlStateManager.enableDepthTest();
		GlStateManager.enableAlphaTest();
		GlStateManager.color4f(1, 1, 1, 1);
		GlStateManager.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
	}

	// ---------- one label (wWaypoints' composition, in GUI pixels before its distance scale) ----------

	/** Minecraft's line height (the reference text is sized against it) and the 16-pixel cell everything is measured in. */
	private static final float LINE = 9;
	private static final float CELL = 16;
	/** wWaypoints' element scale: a setting of 1 shows at 70%. */
	private static final float VISUAL = 0.7F;
	private static final float DISTANCE_SCALE = 0.75F * VISUAL;

	private static void draw(Waypoints m, Waypoint w, float sx, float sy, double camDistance, String distanceText, float angle, float alpha) {
		Minecraft mc = Minecraft.getInstance();
		TextRenderer font = mc.textRenderer;
		boolean icon = m.style.is("icon") || m.style.is("icon_colored") || w.death;
		boolean colored = !w.death && (m.style.is("icon_colored") || m.style.is("text_colored"));
		boolean shadow = m.textShadow.on();
		float shadowK = m.shadowOpacity.value() / 100F;
		float bgK = m.labelOpacity.value() / 100F;
		// The main element: the letter in a square cell, or the name.
		String text = icon ? w.letter() : w.title();
		boolean ascii = ascii(text);
		int inkW = Math.max(1, font.getWidth(text) - (ascii ? 1 : 0));
		int inkH = ascii ? 7 : 9;
		float mainScale;
		float mainW;
		float mainH;
		float outline;
		float pad;
		float fit = 1;
		if (icon) {
			mainScale = VISUAL;
			pad = 0.10F * CELL * mainScale;
			outline = colored ? 0.50F * 0.2F * CELL * mainScale : 0;
			fit = Math.max(0.1F, Math.min(Math.min(CELL / inkW, CELL / inkH), Math.min(2.5F, CELL / Math.max(1, LINE - 2))));
			mainW = CELL * mainScale + 2 * pad + 2 * outline;
			mainH = mainW;
		} else {
			mainScale = VISUAL * CELL / LINE;
			pad = (colored ? 0.15F : 0.10F) * LINE * mainScale;
			outline = colored ? 0.35F * 0.2F * CELL * VISUAL : 0;
			mainW = inkW * mainScale + 2 * pad + 2 * outline;
			mainH = Math.max(inkH * mainScale + 2 * pad, CELL * VISUAL + 2 * pad) + 2 * outline;
		}
		// The distance under it.
		boolean distance = m.showDistance.on() && distanceText != null;
		float dScale = DISTANCE_SCALE * CELL / LINE;
		float dPad = 0.10F * LINE * dScale;
		int dInkW = distance ? Math.max(1, font.getWidth(distanceText) - 1) : 0;
		float dW = dInkW * dScale + 2 * dPad;
		float dH = Math.max(7 * dScale + 2 * dPad, CELL * DISTANCE_SCALE + 2 * dPad);
		float gap = 3 * Math.min(icon ? VISUAL : mainScale, dScale);
		float total = mainH + (distance ? gap + dH : 0);

		float scale = LabelMath.scale(camDistance) * m.labelScale.value() / 100F;
		GlStateManager.pushMatrix();
		GlStateManager.translatef(sx, sy, 0);
		GlStateManager.scalef(scale, scale, 1);
		float top = -total / 2;
		int rgb = w.rgb;
		int bgRgb = colored ? rgb : 0;
		int textA = Math.round(255 * alpha);
		// Main: outline, background, content.
		float mx = -mainW / 2;
		if (outline > 0) {
			ring(mx, top, mainW, mainH, outline, textA << 24 | rgb);
		}
		fill(mx + outline, top + outline, mainW - 2 * outline, mainH - 2 * outline, Math.round(255 * bgK * alpha) << 24 | bgRgb);
		if (icon) {
			float cx = mx + outline + pad;
			float cy = top + outline + pad;
			float visibleW = fit * inkW;
			float visibleH = fit * inkH;
			if (w.death) {
				// Minecraft 1.8.9's font has no skull of its own size: Flint's icon font draws it, in window pixels.
				float gs = Frame.windowGuiScale();
				float size = CELL * mainScale * 0.78F * scale * gs;
				float wx = (sx + 0) * gs;
				float wy = (sy + (cy + CELL * mainScale / 2) * scale) * gs;
				com.flintclient.legacy.ui.Gfx.begin();
				if (shadow) {
					com.flintclient.legacy.ui.Glyph.SKULL.draw(wx + scale * gs * 1.1F, wy + scale * gs * 1.1F, size, Math.round(textA * shadowK) << 24);
				}
				com.flintclient.legacy.ui.Glyph.SKULL.draw(wx, wy, size, textA << 24 | 0xFFFFFF);
				com.flintclient.legacy.ui.Gfx.end();
				begin2d();
			} else {
				GlStateManager.pushMatrix();
				GlStateManager.translatef(cx, cy, 0);
				GlStateManager.scalef(mainScale, mainScale, 1);
				GlStateManager.translatef((CELL - visibleW) / 2, (CELL - visibleH) / 2, 0);
				GlStateManager.scalef(fit, fit, 1);
				text(font, text, 0, ascii ? 0 : -1, textA, shadow ? Math.round(textA * shadowK) : 0);
				GlStateManager.popMatrix();
			}
		} else {
			float contentW = inkW * mainScale;
			float contentH = inkH * mainScale;
			GlStateManager.pushMatrix();
			GlStateManager.translatef(-contentW / 2, top + (mainH - contentH) / 2, 0);
			GlStateManager.scalef(mainScale, mainScale, 1);
			text(font, text, 0, ascii ? 0 : -1, textA, shadow ? Math.round(textA * shadowK) : 0);
			GlStateManager.popMatrix();
		}
		if (distance) {
			float dAlpha = alpha * (m.distanceFacing.on() ? LabelMath.facingAlpha(angle) : 1);
			if (dAlpha > 0.02F) {
				float dy = top + mainH + gap;
				fill(-dW / 2, dy, dW, dH, Math.round(255 * bgK * dAlpha) << 24);
				int a = Math.round(255 * dAlpha);
				GlStateManager.pushMatrix();
				GlStateManager.translatef(-dInkW * dScale / 2, dy + (dH - 7 * dScale) / 2, 0);
				GlStateManager.scalef(dScale, dScale, 1);
				text(font, distanceText, 0, 0, a, shadow ? Math.round(a * shadowK) : 0);
				GlStateManager.popMatrix();
			}
		}
		GlStateManager.popMatrix();
	}

	private static boolean ascii(String text) {
		for (int i = 0; i < text.length(); i++) {
			if (text.charAt(i) >= 128) {
				return false;
			}
		}
		return true;
	}

	/** White text with wWaypoints' shadow: black at {@code shadowA}, one font pixel right and down (not Minecraft's). */
	private static void text(TextRenderer font, String text, float x, float y, int alpha, int shadowA) {
		if (alpha < 5) {
			return;
		}
		GlStateManager.enableTexture();
		if (shadowA >= 5) {
			font.draw(text, x + 1, y + 1, shadowA << 24, false);
		}
		font.draw(text, x, y, alpha << 24 | 0xFFFFFF, false);
		GlStateManager.enableBlend();
		GlStateManager.disableAlphaTest();
	}

	private static void fill(float x, float y, float w, float h, int argb) {
		if ((argb >>> 24) == 0 || w <= 0 || h <= 0) {
			return;
		}
		GlStateManager.disableTexture();
		Tesselator t = Tesselator.getInstance();
		BufferBuilder b = t.getBuffer();
		b.begin(GL11.GL_QUADS, DefaultVertexFormat.POSITION_COLOR);
		int r = (argb >> 16) & 255;
		int g = (argb >> 8) & 255;
		int bl = argb & 255;
		int a = argb >>> 24;
		b.vertex(x, y, 0).color(r, g, bl, a).nextVertex();
		b.vertex(x, y + h, 0).color(r, g, bl, a).nextVertex();
		b.vertex(x + w, y + h, 0).color(r, g, bl, a).nextVertex();
		b.vertex(x + w, y, 0).color(r, g, bl, a).nextVertex();
		t.end();
		GlStateManager.enableTexture();
	}

	private static void ring(float x, float y, float w, float h, float t, int argb) {
		fill(x, y, w, t, argb);
		fill(x, y + h - t, w, t, argb);
		fill(x, y + t, t, h - 2 * t, argb);
		fill(x + w - t, y + t, t, h - 2 * t, argb);
	}
}
