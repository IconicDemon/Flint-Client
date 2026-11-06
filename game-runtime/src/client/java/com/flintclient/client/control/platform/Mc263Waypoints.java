package com.flintclient.client.control.platform;

import com.flintclient.client.control.feature.Features;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.feature.Waypoint;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.UtilityMods;
import com.flintclient.client.mixin.feature.GameRendererBobInvoker;
import com.flintclient.client.platform.Frame;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.PixelFace;
import com.flintclient.client.render.TextLine;
import com.flintclient.client.render.WaypointBadge;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Waypoint labels over the world, as wWaypoints draws them: projected onto the HUD from where the waypoint is, Label height
 * blocks above its block.
 *
 * <ul>
 * <li>A label is its badge (Icon presets: the first letter; Text presets: the name), and under it the distance ("[37m]",
 * "[1.2K]", "[12K]", "[1.2M]") in a small dark box, shown while you look at the waypoint (within 5 degrees, gone by 9).</li>
 * <li>Its size follows wWaypoints' curve over distance: about full size up close, 58% at a thousand blocks, a third far
 * away; times Label size (and, with Zoom with FOV, the zoom).</li>
 * <li>It fades out as you reach it (Fade start to Fade end), a death waypoint within 7.5 to 5 blocks, and the Hide key's
 * near-only step fades the far ones over their last 8 blocks.</li>
 * <li>Nearest on top; the label you aim at (within 2 to 7 degrees of the crosshair) comes to the front and the farther labels
 * it covers dim.</li>
 * <li>Projected with the same matrices the world was drawn with (camera, hurt tilt, view bobbing), so a label stays over its
 * block while you walk, turn or change your field of view; at any distance, only in front of the camera.</li>
 * </ul>
 * Render thread; nothing allocated per frame once the labels' text lines are cached.
 */
public final class Mc263Waypoints {
	private static final int WAYPOINTS = Mods.index(UtilityMods.Waypoints.ID);
	/** wWaypoints' label at full size, in GUI units: the badge, the distance box and the gap between them. */
	private static final float BADGE = 13.44F;
	private static final float DISTANCE_BOX = 10.08F;
	private static final float GAP = 1.575F;
	/** GUI units per font pixel: the badge's letters, a name and the distance. */
	private static final float LETTER_FONT = BADGE / 9;
	private static final float DISTANCE_FONT = 0.9333F;
	/** How fast the label you aim at comes to the front (per second) and goes back. */
	private static final float AIM_IN = 9;
	private static final float AIM_OUT = 20;

	private static final Matrix4f MATRIX = new Matrix4f();
	private static final Vector4f CLIP = new Vector4f();
	private static final float[] AT = new float[2];
	private static final List<Label> LABELS = new ArrayList<>();
	/** Per waypoint: how far the label you aim at has come to the front (0..1) and the frame it was last seen. */
	private static final Map<Waypoint, float[]> AIM = new IdentityHashMap<>();
	private static long lastNanos;
	private static int frame;

	private Mc263Waypoints() {
	}

	/** A label on screen this frame. */
	private static final class Label {
		Waypoint wp;
		float x;
		float y;
		double distance;
		float angle;
		float alpha;
		float scale;
		float dim;
		float aim;
	}

	/**
	 * The world's projection this frame (camera projection, hurt tilt, view bobbing, view rotation) into {@code out};
	 * returns the camera position it is relative to.
	 */
	private static Vec3 worldProjection(Minecraft mc, Matrix4f out) {
		CameraRenderState camera = com.flintclient.client.compat.McCompat.cameraState();
		PoseStack bob = new PoseStack();
		GameRendererBobInvoker renderer = (GameRendererBobInvoker) mc.gameRenderer;
		renderer.flint$bobHurt(camera, bob);
		if (mc.options.bobView().get()) {
			renderer.flint$bobView(camera, bob);
		}
		out.set(camera.projectionMatrix).mul(bob.last().pose()).mul(camera.viewRotationMatrix);
		return camera.pos;
	}

	/** Where a world point falls on the GUI ({@code out[0], out[1]}), or false when it is behind the camera. */
	private static boolean project(Matrix4f matrix, Vec3 camera, double x, double y, double z, int w, int h, float[] out) {
		CLIP.set((float) (x - camera.x), (float) (y - camera.y), (float) (z - camera.z), 1).mul(matrix);
		if (CLIP.w < 0.05F) {
			return false;
		}
		out[0] = (CLIP.x / CLIP.w + 1) / 2 * w;
		out[1] = (1 - CLIP.y / CLIP.w) / 2 * h;
		return true;
	}

	private static Label label(int index) {
		while (LABELS.size() <= index) {
			LABELS.add(new Label());
		}
		return LABELS.get(index);
	}

	/**
	 * wWaypoints' size over distance (its "universal" curve): a logistic step down around 25 blocks and another around ten
	 * thousand, from about 103% up close to 33% far away.
	 */
	static float sizeAt(double distance) {
		double x = Math.log10(Math.max(0.01, distance));
		double near = 1.0 / (1.0 + Math.exp(2.8 * (x - 1.4)));
		double far = 1.0 / (1.0 + Math.exp(2.5 * (x - 4.0)));
		return (float) ((33.0 + 43.0 * near + 27.0 * far) / 100.0);
	}

	/** 1 within {@code full} degrees of where you look, 0 from {@code gone}, smooth between. */
	private static float facing(float angle, float full, float gone) {
		if (angle <= full) {
			return 1;
		}
		if (angle >= gone) {
			return 0;
		}
		float t = 1 - (angle - full) / (gone - full);
		return t * t * (3 - 2 * t);
	}

	/** Draws this dimension's shown waypoints' labels ({@code partialTick}: for your position between ticks). */
	public static void draw(GuiGraphicsExtractor g, Minecraft mc, float partialTick) {
		String dimension = Mc263Features.dimension();
		List<Waypoint> all = Features.waypoints().all();
		LocalPlayer player = mc.player;
		Entity viewer = mc.getCameraEntity();
		if (dimension == null || all.isEmpty() || player == null || viewer == null) {
			return;
		}
		ModuleState state = Mods.state(WAYPOINTS);
		if (state.option(UtilityMods.Waypoints.DISPLAY) == 2) {
			return;
		}
		int max = state.integer(UtilityMods.Waypoints.MAX_DISTANCE);
		int within = Mc263Features.shownWithin();
		boolean allSets = state.bool(UtilityMods.Waypoints.ALL_SETS);
		float height = state.decimal(UtilityMods.Waypoints.LABEL_HEIGHT);
		float size = state.decimal(UtilityMods.Waypoints.LABEL_SCALE);
		float fadeStart = state.decimal(UtilityMods.Waypoints.FADE_START);
		float fadeEnd = state.decimal(UtilityMods.Waypoints.FADE_END);
		if (fadeStart < fadeEnd) {
			float t = fadeStart;
			fadeStart = fadeEnd;
			fadeEnd = t;
		}
		var store = Features.waypoints();
		Vec3 camera = worldProjection(mc, MATRIX);
		Vec3 you = player.getPosition(partialTick);
		Vec3 look = viewer.getViewVector(partialTick);
		int w = g.guiWidth();
		int h = g.guiHeight();
		float fov = 1;
		if (state.bool(UtilityMods.Waypoints.ZOOM_WITH_FOV)) {
			fov = zoom(mc, camera, look, w, h);
		}
		int count = 0;
		frame++;
		for (Waypoint wp : all) {
			if (!wp.showText() || !store.shown(wp, allSets) || !wp.dimension().equals(dimension)) {
				continue;
			}
			double lx = wp.x() + 0.5;
			double ly = wp.y() + height;
			double lz = wp.z() + 0.5;
			double px = lx - you.x;
			double py = wp.y() + 1 - you.y;
			double pz = lz - you.z;
			double fromYou = Math.sqrt(px * px + py * py + pz * pz);
			if (max > 0 && fromYou > max || within >= 0 && fromYou > within) {
				continue;
			}
			float alpha = 1;
			if (wp.death()) {
				alpha = (float) Math.clamp((fromYou - 5) / 2.5, 0, 1);
			} else if (fadeStart > 0) {
				alpha = fromYou <= fadeEnd ? 0 : fromYou >= fadeStart ? 1 : (float) ((fromYou - fadeEnd) / Math.max(0.001, fadeStart - fadeEnd));
			}
			if (within >= 0 && fromYou > within - 8) {
				alpha *= (float) Math.clamp((within - fromYou) / 8, 0, 1);
			}
			if (alpha <= 0.02F || !project(MATRIX, camera, lx, ly, lz, w, h, AT)) {
				continue;
			}
			if (AT[0] < -64 || AT[0] > w + 64 || AT[1] < -64 || AT[1] > h + 64) {
				continue;
			}
			double cx = lx - camera.x;
			double cy = ly - camera.y;
			double cz = lz - camera.z;
			double distance = Math.sqrt(cx * cx + cy * cy + cz * cz);
			double dot = distance < 1e-6 ? 1 : (cx * look.x + cy * look.y + cz * look.z) / distance;
			Label l = label(count++);
			l.wp = wp;
			l.x = AT[0];
			l.y = AT[1];
			l.distance = distance;
			l.angle = (float) Math.toDegrees(Math.acos(Math.clamp(dot, -1, 1)));
			l.alpha = alpha;
			l.scale = Math.clamp(sizeAt(distance) * size * fov, 0.05F, 20);
			l.dim = 0;
		}
		if ((frame & 63) == 0) {
			AIM.values().removeIf(v -> frame - v[1] > 120);
		}
		if (count == 0) {
			return;
		}
		List<Label> shown = LABELS.subList(0, count);
		focus(shown);
		// Farthest first, so the nearest is on top; then the ones you aim at, over everything.
		shown.sort((a, b) -> Double.compare(b.distance, a.distance));
		Canvas c = Canvas.begin(Frame.of(g));
		int font = PixelFace.begin(PixelFace.BY_SIZE);
		try {
			int gs = c.guiScale();
			for (Label l : shown) {
				drawLabel(c, state, l, gs, l.alpha * (1 - l.dim) * (1 - l.aim));
			}
			for (Label l : shown) {
				if (l.aim > 0.02F) {
					drawLabel(c, state, l, gs, l.alpha * l.aim);
				}
			}
		} finally {
			PixelFace.end(font);
			c.end();
		}
	}

	/**
	 * Brings the label you aim at to the front: one within 7 degrees of the crosshair that another label crowds (within 44 by
	 * 22 GUI units) fades to the front, smoothly, and the farther labels it covers dim.
	 */
	private static void focus(List<Label> shown) {
		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 0.016F : Math.min(0.1F, (now - lastNanos) / 1e9F);
		lastNanos = now;
		for (Label l : shown) {
			float target = facing(l.angle, 2, 7);
			boolean crowded = false;
			if (target >= 0.72F) {
				for (Label o : shown) {
					if (o != l && Math.abs(o.x - l.x) <= 44 && Math.abs(o.y - l.y) <= 22) {
						crowded = true;
						if (o.distance > l.distance) {
							o.dim = Math.max(o.dim, 0.45F * target);
						}
					}
				}
			}
			float[] aim = AIM.computeIfAbsent(l.wp, k -> new float[2]);
			aim[1] = frame;
			float goal = crowded ? target : 0;
			aim[0] = goal > aim[0] ? Math.min(goal, aim[0] + AIM_IN * dt) : Math.max(goal, aim[0] - AIM_OUT * dt);
			l.aim = aim[0];
		}
	}

	/** One label at {@code alpha}: its badge and, under it, the distance. */
	private static void drawLabel(Canvas c, ModuleState s, Label l, int gs, float alpha) {
		if (alpha <= 0.02F) {
			return;
		}
		int style = s.option(UtilityMods.Waypoints.LABEL_STYLE);
		boolean text = style == 1 || style == 3;
		boolean colored = style == 2 || style == 3 || style == 4;
		boolean simple = style == 4;
		boolean distance = !simple && s.bool(UtilityMods.Waypoints.SHOW_DISTANCE) && l.wp.showDistance();
		float distanceAlpha = !distance ? 0 : s.bool(UtilityMods.Waypoints.DISTANCE_FACING) ? facing(l.angle, 5, 9) : 1;
		if (distance && !s.bool(UtilityMods.Waypoints.ALWAYS_DISTANCE)) {
			distanceAlpha *= (float) Math.clamp((l.distance - 16) / 4, 0, 1);
		}
		int background = Math.round(s.integer(UtilityMods.Waypoints.LABEL_OPACITY) * 2.55F) << 24;
		int shadow = s.bool(UtilityMods.Waypoints.TEXT_SHADOW) ? Math.round(s.integer(UtilityMods.Waypoints.SHADOW_OPACITY) * 2.55F) : 0;
		float unit = l.scale * gs;
		float total = distance ? BADGE + GAP + DISTANCE_BOX : simple ? BADGE * 1.6F : BADGE;
		float top = l.y * gs - total * unit / 2;
		float cx = l.x * gs;
		float badgeY = top + BADGE * unit / 2;
		// The badge, drawn at a whole font scale and zoomed to the exact size.
		float want = BADGE * unit;
		int k = Math.max(1, (int) Math.ceil(LETTER_FONT * unit));
		int box = 9 * k;
		float zoom = want / box;
		String letters = text ? l.wp.name() : l.wp.label();
		c.pushZoom(zoom, cx, badgeY);
		if (colored) {
			WaypointBadge.draw(c, l.wp.death(), letters, l.wp.rgb(), cx, badgeY, box, alpha);
		} else {
			WaypointBadge.drawPlain(c, l.wp.death(), letters, cx, badgeY, box, background, shadow, alpha);
		}
		c.popOffset();
		if (simple) {
			pointer(c, cx, badgeY + want / 2 + 0.2F * want, 0.65F * want, l.wp.death() ? 0xFF34343A : 0xFF000000 | l.wp.rgb(), alpha);
		}
		if (distanceAlpha > 0.02F) {
			float dy = top + (BADGE + GAP) * unit + DISTANCE_BOX * unit / 2;
			int dk = Math.max(1, (int) Math.ceil(DISTANCE_FONT * unit));
			float dz = DISTANCE_FONT * unit / dk;
			TextLine line = Mc263Minimap.label(WaypointBadge.distanceText(l.distance), PixelFace.fixed(dk));
			c.pushZoom(dz, cx, dy);
			int tw = c.width(line);
			int bh = Math.round(DISTANCE_BOX / DISTANCE_FONT * dk);
			int bw = tw + Math.round(1.8F * dk);
			float a = alpha * distanceAlpha;
			if ((background >>> 24) > 0) {
				c.roundRectCorners(Math.round(cx - bw / 2.0F), Math.round(dy - bh / 2.0F), bw, bh, Math.max(1, Math.round(bh * 0.12F)),
					Palette.withAlpha(background, a));
			}
			int baseline = c.baselineFor(line, Math.round(dy));
			int tx = Math.round(cx - tw / 2.0F);
			if (shadow > 0) {
				c.text(line, tx + dk, baseline + dk, Palette.withAlpha(shadow << 24, a));
			}
			c.text(line, tx, baseline, Palette.withAlpha(0xFFFFFFFF, a));
			c.popOffset();
		}
	}

	/** The Simple preset's pointer: a downward triangle {@code side} px wide, its top centred at ({@code cx}, {@code y}). */
	private static void pointer(Canvas c, float cx, float y, float side, int argb, float alpha) {
		int rows = Math.max(2, Math.round(side * 0.6F));
		int x0 = Math.round(cx);
		int y0 = Math.round(y);
		int dark = Palette.withAlpha(0xC0000000, alpha);
		int fill = Palette.withAlpha(argb, alpha);
		for (int i = 0; i < rows; i++) {
			int half = Math.round(side / 2 * (1 - i / (float) rows));
			c.fill(x0 - half - 1, y0 + i, x0 + half + 1, y0 + i + 1, dark);
			if (half > 0) {
				c.fill(x0 - half, y0 + i, x0 + half, y0 + i + 1, fill);
			}
		}
	}

	/**
	 * Zoom with FOV: how much a zoom (or any narrower field of view than your setting) enlarges the world on screen, measured
	 * from where a point a block to the side of where you look lands.
	 */
	private static float zoom(Minecraft mc, Vec3 camera, Vec3 look, int w, int h) {
		double rx = -look.z;
		double rz = look.x;
		double len = Math.sqrt(rx * rx + rz * rz);
		if (len < 1e-4) {
			return 1;
		}
		double ax = camera.x + look.x * 10 + rx / len;
		double ay = camera.y + look.y * 10;
		double az = camera.z + look.z * 10 + rz / len;
		if (!project(MATRIX, camera, ax, ay, az, w, h, AT)) {
			return 1;
		}
		float focal = Math.abs(AT[0] - w / 2.0F) * 10;
		float reference = (float) (h / 2.0 / Math.tan(Math.toRadians(mc.options.fov().get()) / 2));
		return reference > 1 ? Math.clamp(focal / reference, 0.25F, 50) : 1;
	}
}
