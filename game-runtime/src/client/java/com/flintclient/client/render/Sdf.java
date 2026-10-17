package com.flintclient.client.render;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolution-independent shapes for masks: icons are stroked paths on a 24-unit grid, the Flint mark is
 * filled polygons. Each shape is rasterised once per size with analytic antialiasing (coverage from the
 * signed distance to the outline), so it is sharp at every GUI scale and costs nothing per frame.
 */
public final class Sdf {
	private Sdf() {
	}

	/** A stroked path: a list of line segments with a stroke width, in its own unit grid. */
	public static final class Path {
		final List<float[]> segments = new ArrayList<>();
		private float x;
		private float y;

		public Path move(float x, float y) {
			this.x = x;
			this.y = y;
			return this;
		}

		public Path line(float x, float y) {
			segments.add(new float[] {this.x, this.y, x, y});
			this.x = x;
			this.y = y;
			return this;
		}

		/** A circular arc around (cx, cy), angles in degrees (0 = +x, 90 = +y, i.e. downwards). */
		public Path arc(float cx, float cy, float r, float from, float to) {
			int steps = Math.max(4, (int) Math.ceil(Math.abs(to - from) / 10.0F));
			double a0 = Math.toRadians(from);
			move(cx + r * (float) Math.cos(a0), cy + r * (float) Math.sin(a0));
			for (int i = 1; i <= steps; i++) {
				double a = Math.toRadians(from + (to - from) * i / steps);
				line(cx + r * (float) Math.cos(a), cy + r * (float) Math.sin(a));
			}
			return this;
		}

		public Path circle(float cx, float cy, float r) {
			return arc(cx, cy, r, 0, 360);
		}

		/**
		 * A quadratic curve from the current point to (x, y), pulled towards (cx, cy). It is flattened into
		 * short segments (the distance code only knows lines), fine enough that no facet shows at 48 px.
		 */
		public Path quad(float cx, float cy, float x, float y) {
			float x0 = this.x;
			float y0 = this.y;
			int steps = 12;
			for (int i = 1; i <= steps; i++) {
				float t = i / (float) steps;
				float u = 1 - t;
				line(u * u * x0 + 2 * u * t * cx + t * t * x, u * u * y0 + 2 * u * t * cy + t * t * y);
			}
			return this;
		}

		/** A round dot at (x, y) whose diameter is the stroke plus {@code 2 * r}. */
		public Path dot(float x, float y, float r) {
			return r <= 0 ? move(x, y).line(x, y) : circle(x, y, r);
		}

		/** A closed outline through the given points ({@code x0, y0, x1, y1, ...}). */
		public Path polygon(float... xy) {
			move(xy[0], xy[1]);
			for (int i = 2; i < xy.length; i += 2) {
				line(xy[i], xy[i + 1]);
			}
			return line(xy[0], xy[1]);
		}

		/** This path mirrored left to right inside a grid {@code width} wide (for paired icons such as undo and redo). */
		public Path mirrored(float width) {
			Path out = new Path();
			for (float[] s : segments) {
				out.segments.add(new float[] {width - s[0], s[1], width - s[2], s[3]});
			}
			return out;
		}

		/** A rectangle outline with rounded corners. */
		public Path roundRect(float x0, float y0, float x1, float y1, float r) {
			move(x0 + r, y0).line(x1 - r, y0);
			arc(x1 - r, y0 + r, r, -90, 0);
			line(x1, y1 - r);
			arc(x1 - r, y1 - r, r, 0, 90);
			line(x0 + r, y1);
			arc(x0 + r, y1 - r, r, 90, 180);
			line(x0, y0 + r);
			return arc(x0 + r, y0 + r, r, 180, 270);
		}

		/** Distance from (px, py) to the nearest segment. */
		float distance(float px, float py) {
			float best = Float.MAX_VALUE;
			for (float[] s : segments) {
				float dx = s[2] - s[0];
				float dy = s[3] - s[1];
				float len = dx * dx + dy * dy;
				float t = len == 0 ? 0 : Math.clamp(((px - s[0]) * dx + (py - s[1]) * dy) / len, 0, 1);
				float ex = px - (s[0] + t * dx);
				float ey = py - (s[1] + t * dy);
				best = Math.min(best, ex * ex + ey * ey);
			}
			return (float) Math.sqrt(best);
		}
	}

	/** A filled polygon. */
	public record Polygon(float[] xs, float[] ys, int argb) {
		/** A white polygon through {@code x0, y0, x1, y1, ...} (for masks tinted when drawn). */
		public static Polygon of(float... xy) {
			float[] xs = new float[xy.length / 2];
			float[] ys = new float[xy.length / 2];
			for (int i = 0; i < xs.length; i++) {
				xs[i] = xy[2 * i];
				ys[i] = xy[2 * i + 1];
			}
			return new Polygon(xs, ys, 0xFFFFFF);
		}

		/** Signed distance: negative inside. */
		float signedDistance(float px, float py) {
			int n = xs.length;
			float best = Float.MAX_VALUE;
			boolean inside = false;
			for (int i = 0, j = n - 1; i < n; j = i++) {
				float dx = xs[i] - xs[j];
				float dy = ys[i] - ys[j];
				float len = dx * dx + dy * dy;
				float t = len == 0 ? 0 : Math.clamp(((px - xs[j]) * dx + (py - ys[j]) * dy) / len, 0, 1);
				float ex = px - (xs[j] + t * dx);
				float ey = py - (ys[j] + t * dy);
				best = Math.min(best, ex * ex + ey * ey);
				if ((ys[i] > py) != (ys[j] > py) && px < (xs[j] - xs[i]) * (py - ys[i]) / (ys[j] - ys[i]) + xs[i]) {
					inside = !inside;
				}
			}
			float d = (float) Math.sqrt(best);
			return inside ? -d : d;
		}
	}

	/** Pixel coverage (0..1) for a signed distance in pixels, as a one-pixel box filter. */
	static float coverage(float distancePx) {
		return Math.clamp(0.5F - distancePx, 0.0F, 1.0F);
	}
}
