package com.flintclient.client.compat.gizmos;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Minecraft 1.21.11's debug gizmos ({@code net.minecraft.gizmos.Gizmos}) for 1.21.10, which has none: the calls Flint's
 * world drawing makes (lines, boxes, flat quads, labels) are kept and drawn into the level's buffers as 1.21.11 draws
 * them. As in 1.21.11, what is emitted during a client tick ({@link #tickCollection}) is drawn every frame until the
 * next tick, and what is emitted while a frame is drawn ({@link #collect}) for that frame only. Lines keep their width in
 * screen pixels (camera-facing strips).
 */
public final class Gizmos {
	private record Line(Vec3 from, Vec3 to, int color, float width) {
	}

	private record Quad(Vec3 a, Vec3 b, Vec3 c, Vec3 d, int color) {
	}

	private record Box(AABB box, int color) {
	}

	private record Point(Vec3 pos, int color, float size) {
	}

	private record Text(String text, Vec3 pos, TextGizmo.Style style, GizmoProperties properties) {
	}

	/** One collection: a tick's (kept until the next tick) or a frame's. */
	private static final class Batch {
		final List<Line> lines = new ArrayList<>();
		final List<Quad> quads = new ArrayList<>();
		final List<Box> boxes = new ArrayList<>();
		final List<Text> texts = new ArrayList<>();
		final List<Point> points = new ArrayList<>();

		void clear() {
			lines.clear();
			quads.clear();
			boxes.clear();
			texts.clear();
			points.clear();
		}

		boolean isEmpty() {
			return lines.isEmpty() && quads.isEmpty() && boxes.isEmpty() && texts.isEmpty() && points.isEmpty();
		}
	}

	private static final Batch TICK = new Batch();
	private static final Batch FRAME = new Batch();
	/** Where calls go now (null: no collection open, as 1.21.11 drops them). */
	private static Batch open;
	private static final GizmoProperties NONE = new GizmoProperties();

	private Gizmos() {
	}

	public static GizmoProperties line(Vec3 from, Vec3 to, int color) {
		return line(from, to, color, 2.5F);
	}

	public static GizmoProperties line(Vec3 from, Vec3 to, int color, float width) {
		if (open != null) {
			open.lines.add(new Line(from, to, color, width));
		}
		return NONE;
	}

	public static GizmoProperties rect(Vec3 a, Vec3 b, Vec3 c, Vec3 d, GizmoStyle style) {
		if (open != null && style.hasFill()) {
			open.quads.add(new Quad(a, b, c, d, style.fill()));
		}
		if (style.hasStroke()) {
			line(a, b, style.stroke(), style.strokeWidth());
			line(b, c, style.stroke(), style.strokeWidth());
			line(c, d, style.stroke(), style.strokeWidth());
			line(d, a, style.stroke(), style.strokeWidth());
		}
		return NONE;
	}

	public static GizmoProperties cuboid(AABB box, GizmoStyle style) {
		if (open != null && style.hasFill()) {
			open.boxes.add(new Box(box, style.fill()));
		}
		if (style.hasStroke()) {
			int c = style.stroke();
			float w = style.strokeWidth();
			double x0 = box.minX;
			double y0 = box.minY;
			double z0 = box.minZ;
			double x1 = box.maxX;
			double y1 = box.maxY;
			double z1 = box.maxZ;
			for (double y : new double[] {y0, y1}) {
				line(new Vec3(x0, y, z0), new Vec3(x1, y, z0), c, w);
				line(new Vec3(x1, y, z0), new Vec3(x1, y, z1), c, w);
				line(new Vec3(x1, y, z1), new Vec3(x0, y, z1), c, w);
				line(new Vec3(x0, y, z1), new Vec3(x0, y, z0), c, w);
			}
			line(new Vec3(x0, y0, z0), new Vec3(x0, y1, z0), c, w);
			line(new Vec3(x1, y0, z0), new Vec3(x1, y1, z0), c, w);
			line(new Vec3(x1, y0, z1), new Vec3(x1, y1, z1), c, w);
			line(new Vec3(x0, y0, z1), new Vec3(x0, y1, z1), c, w);
		}
		return NONE;
	}

	/** A line with an arrow head at {@code to}, as 1.21.11's arrow gizmo: four head lines a tenth as long (0.1 to 1 block). */
	public static GizmoProperties arrow(Vec3 from, Vec3 to, int color) {
		return arrow(from, to, color, 2.5F);
	}

	public static GizmoProperties arrow(Vec3 from, Vec3 to, int color, float width) {
		line(from, to, color, width);
		org.joml.Quaternionf turn = new org.joml.Quaternionf().rotationTo(new org.joml.Vector3f(1, 0, 0), to.subtract(from).toVector3f().normalize());
		float l = (float) net.minecraft.util.Mth.clamp(to.distanceTo(from) * 0.1F, 0.1F, 1.0);
		float[][] head = {{-l, l, 0}, {-l, 0, l}, {-l, -l, 0}, {-l, 0, -l}};
		for (float[] h : head) {
			org.joml.Vector3f v = turn.transform(h[0], h[1], h[2], new org.joml.Vector3f());
			line(to, to.add(v.x, v.y, v.z), color, width);
		}
		return NONE;
	}

	/** A square {@code size} screen pixels wide facing the camera, as 1.21.11's point gizmo. */
	public static GizmoProperties point(Vec3 pos, int color, float size) {
		if (open != null) {
			open.points.add(new Point(pos, color, size));
		}
		return NONE;
	}

	public static GizmoProperties billboardText(String text, Vec3 pos, TextGizmo.Style style) {
		GizmoProperties properties = new GizmoProperties();
		if (open != null) {
			open.texts.add(new Text(text, pos, style, properties));
		}
		return properties;
	}

	/** Runs {@code emit} (Flint's per-tick emitters): what it asks for is drawn every frame until the next tick. */
	public static void tickCollection(Runnable emit) {
		TICK.clear();
		Batch was = open;
		open = TICK;
		try {
			emit.run();
		} finally {
			open = was;
		}
	}

	/** Runs {@code emit} (Flint's per-frame emitters), then draws it and the last tick's, relative to {@code camera}. */
	public static void collect(Runnable emit, PoseStack pose, MultiBufferSource.BufferSource buffers, Camera camera) {
		FRAME.clear();
		Batch was = open;
		open = FRAME;
		try {
			emit.run();
		} finally {
			open = was;
		}
		try {
			if (!TICK.isEmpty()) {
				draw(TICK, pose, buffers, camera);
			}
			if (!FRAME.isEmpty()) {
				draw(FRAME, pose, buffers, camera);
			}
		} finally {
			FRAME.clear();
		}
	}

	private static void draw(Batch batch, PoseStack pose, MultiBufferSource.BufferSource buffers, Camera camera) {
		Vec3 cam = camera.position();
		Minecraft mc = Minecraft.getInstance();
		Matrix4f matrix = pose.last().pose();
		if (!batch.boxes.isEmpty()) {
			for (Box b : batch.boxes) {
				int c = b.color;
				DebugRenderer.renderFilledBox(pose, buffers, b.box.move(-cam.x, -cam.y, -cam.z), (c >> 16 & 0xFF) / 255F, (c >> 8 & 0xFF) / 255F,
					(c & 0xFF) / 255F, (c >>> 24) / 255F);
			}
			buffers.endBatch(RenderType.debugFilledBox());
		}
		if (!batch.quads.isEmpty() || !batch.lines.isEmpty() || !batch.points.isEmpty()) {
			VertexConsumer quads = buffers.getBuffer(RenderType.debugQuads());
			for (Quad q : batch.quads) {
				Vec3 a = q.a.subtract(cam);
				Vec3 b = q.b.subtract(cam);
				Vec3 c = q.c.subtract(cam);
				// A quad's back face isn't drawn, as 1.21.11's gizmo quads (McCompat.rectBothSides gives both sides).
				if (b.subtract(a).cross(c.subtract(a)).dot(a) >= 0) {
					continue;
				}
				quad(quads, matrix, a, b, c, q.d.subtract(cam), q.color);
			}
			// Pixels to blocks at one block's distance: the vertical field of view over the window's height.
			double perPixel = 2 * Math.tan(Math.toRadians(mc.options.fov().get()) / 2) / Math.max(1, mc.getWindow().getHeight());
			for (Line l : batch.lines) {
				strip(quads, matrix, l.from.subtract(cam), l.to.subtract(cam), l.color, l.width * perPixel / 2);
			}
			for (Point pt : batch.points) {
				// A square facing the camera, size pixels across at its distance; both windings, so no side is culled.
				Vec3 v = pt.pos.subtract(cam);
				Vec3 right = v.cross(new Vec3(0, 1, 0));
				if (right.lengthSqr() < 1e-12) {
					right = new Vec3(1, 0, 0);
				}
				double half = pt.size * perPixel / 2 * v.length();
				right = right.normalize().scale(half);
				Vec3 up = right.cross(v).normalize().scale(half);
				Vec3 a = v.subtract(right).subtract(up);
				Vec3 b = v.add(right).subtract(up);
				Vec3 c = v.add(right).add(up);
				Vec3 d = v.subtract(right).add(up);
				quad(quads, matrix, a, b, c, d, pt.color);
				quad(quads, matrix, d, c, b, a, pt.color);
			}
			buffers.endBatch(RenderType.debugQuads());
		}
		if (!batch.texts.isEmpty()) {
			Font font = mc.font;
			for (Text t : batch.texts) {
				pose.pushPose();
				pose.translate(t.pos.x - cam.x, t.pos.y - cam.y, t.pos.z - cam.z);
				pose.mulPose(camera.rotation());
				float k = t.style.scale() / 16F;
				pose.scale(k, -k, k);
				float x = -font.width(t.text) / 2F;
				font.drawInBatch(t.text, x, 0, t.style.color(), false, pose.last().pose(), buffers,
					t.properties.onTop ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL, 0, 0xF000F0);
				pose.popPose();
			}
			buffers.endBatch();
		}
	}

	/** One line {@code width} screen pixels wide, between camera-relative points, into a {@code debugQuads} buffer. */
	public static void drawLine(VertexConsumer quads, Matrix4f matrix, Vec3 a, Vec3 b, int color, float width) {
		Minecraft mc = Minecraft.getInstance();
		double perPixel = 2 * Math.tan(Math.toRadians(mc.options.fov().get()) / 2) / Math.max(1, mc.getWindow().getHeight());
		strip(quads, matrix, a, b, color, width * perPixel / 2);
	}

	/**
	 * A line as a strip facing the camera, {@code half} blocks wide at one block's distance (wider further away). A long
	 * line passing near the camera is cut into pieces short against their distance, so its width follows the distance
	 * along it rather than only at its far ends.
	 */
	private static void strip(VertexConsumer out, Matrix4f matrix, Vec3 a, Vec3 b, int color, double half) {
		strip(out, matrix, a, b, color, half, 0);
	}

	private static void strip(VertexConsumer out, Matrix4f matrix, Vec3 a, Vec3 b, int color, double half, int depth) {
		if (depth < 12 && a.distanceTo(b) > 0.5 * Math.min(a.length(), b.length()) + 0.25) {
			Vec3 mid = a.add(b).scale(0.5);
			strip(out, matrix, a, mid, color, half, depth + 1);
			strip(out, matrix, mid, b, color, half, depth + 1);
			return;
		}
		Vec3 dir = b.subtract(a);
		Vec3 sideA = dir.cross(a);
		Vec3 sideB = dir.cross(b);
		if (sideA.lengthSqr() < 1e-12 || sideB.lengthSqr() < 1e-12) {
			return;
		}
		sideA = sideA.normalize().scale(half * a.length());
		sideB = sideB.normalize().scale(half * b.length());
		quad(out, matrix, a.subtract(sideA), a.add(sideA), b.add(sideB), b.subtract(sideB), color);
	}

	private static void quad(VertexConsumer out, Matrix4f matrix, Vec3 a, Vec3 b, Vec3 c, Vec3 d, int color) {
		vertex(out, matrix, a, color);
		vertex(out, matrix, b, color);
		vertex(out, matrix, c, color);
		vertex(out, matrix, d, color);
	}

	private static void vertex(VertexConsumer out, Matrix4f matrix, Vec3 p, int color) {
		out.addVertex(matrix, (float) p.x, (float) p.y, (float) p.z).setColor(color);
	}
}
