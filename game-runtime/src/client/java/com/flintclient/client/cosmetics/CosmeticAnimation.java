package com.flintclient.client.cosmetics;

import com.flintclient.client.cosmetics.CosmeticLimits.Refused;
import com.flintclient.client.cosmetics.Molang.Const;
import com.flintclient.client.cosmetics.Molang.Expr;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A cosmetic's Bedrock animations ({@code animation.json}, as Blockbench saves them): per clip its length, whether it loops,
 * and per bone its rotation (degrees), position (pixels) and scale channels. A channel is one value, or keyframes with
 * {@code pre}/{@code post} values, {@code lerp_mode} {@code linear}, {@code catmullrom} or {@code step}, and Blockbench's
 * (GeckoLib's) {@code easing} names. Values are numbers or {@link Molang} expressions; one this subset can't read is left
 * at rest (0, or 1 for a scale). Clips are found by their short name: {@code animation.<id>.idle} is {@code idle}.
 * {@link #sample} writes a clip's pose without allocating. Pure Java.
 */
public record CosmeticAnimation(Map<String, Clip> clips) {
	public enum Loop {
		ONCE,
		LOOP,
		/** Bedrock's {@code hold_on_last_frame}. */
		HOLD
	}

	/** {@code length} in seconds (0: the last keyframe's time). */
	public record Clip(String name, double length, Loop loop, List<Track> tracks) {
		/** Seconds into the clip after {@code seconds} of playing. */
		public double time(double seconds) {
			if (length <= 0) {
				return 0;
			}
			if (loop == Loop.LOOP) {
				double t = seconds % length;
				return t < 0 ? t + length : t;
			}
			return Math.max(0, Math.min(seconds, length));
		}

		/** Whether a clip that doesn't loop has played to its end after {@code seconds}. */
		public boolean done(double seconds) {
			return loop == Loop.ONCE && seconds >= length;
		}
	}

	/** A bone's channels (null: not animated). */
	public record Track(String bone, @Nullable Channel rotation, @Nullable Channel position, @Nullable Channel scale) {
	}

	static final int LINEAR = 0;
	static final int CATMULLROM = 1;
	static final int STEP = 2;

	/** One channel's keyframes, in time order: {@code pre[k]} arrives at {@code times[k]}, {@code post[k]} leaves it. */
	public static final class Channel {
		final double[] times;
		final Expr[] pre;
		final Expr[] post;
		final byte[] lerp;
		final byte[] easing;
		final float[] easingArg;

		Channel(double[] times, Expr[] pre, Expr[] post, byte[] lerp, byte[] easing, float[] easingArg) {
			this.times = times;
			this.pre = pre;
			this.post = post;
			this.lerp = lerp;
			this.easing = easing;
			this.easingArg = easingArg;
		}

		public int keyframes() {
			return times.length;
		}

		/** Writes the value at {@code t} (seconds into the clip) to {@code out[at..at+2]}. */
		public void sample(double t, double life, float[] out, int at) {
			int n = times.length;
			if (n == 1 || t <= times[0]) {
				put(n == 1 ? post : pre, 0, t, life, out, at);
				return;
			}
			if (t >= times[n - 1]) {
				put(post, n - 1, t, life, out, at);
				return;
			}
			int k = 0;
			while (k + 1 < n && times[k + 1] <= t) {
				k++;
			}
			double span = times[k + 1] - times[k];
			double p = span <= 0 ? 1 : (t - times[k]) / span;
			if (lerp[k] == STEP) {
				put(post, k, t, life, out, at);
				return;
			}
			p = Easing.apply(easing[k + 1], easingArg[k + 1], p);
			boolean smooth = lerp[k] == CATMULLROM || lerp[k + 1] == CATMULLROM;
			for (int c = 0; c < 3; c++) {
				double a = post[k * 3 + c].eval(t, life);
				double b = pre[(k + 1) * 3 + c].eval(t, life);
				double v;
				if (smooth) {
					double before = k > 0 ? post[(k - 1) * 3 + c].eval(t, life) : a;
					double after = k + 2 < n ? pre[(k + 2) * 3 + c].eval(t, life) : b;
					v = catmullRom(before, a, b, after, p);
				} else {
					v = a + (b - a) * p;
				}
				out[at + c] = (float) v;
			}
		}

		private static void put(Expr[] values, int k, double t, double life, float[] out, int at) {
			for (int c = 0; c < 3; c++) {
				out[at + c] = (float) values[k * 3 + c].eval(t, life);
			}
		}
	}

	static double catmullRom(double p0, double p1, double p2, double p3, double t) {
		double t2 = t * t;
		double t3 = t2 * t;
		return 0.5 * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
	}

	/** The clip called {@code name} ({@code idle}, {@code walk}, {@code fly}, {@code swim}…), or null. */
	public @Nullable Clip clip(String name) {
		return clips.get(name.toLowerCase(Locale.ROOT));
	}

	/** Reads {@code bytes} (UTF-8 JSON), refusing anything past the limits; {@code maxSeconds}: the longest clip allowed. */
	public static CosmeticAnimation parse(byte[] bytes, double maxSeconds) throws Refused {
		if (bytes.length > CosmeticLimits.MAX_ANIMATION_BYTES) {
			throw new Refused("animation larger than " + CosmeticLimits.MAX_ANIMATION_BYTES / 1024 + " KB");
		}
		String text = new String(bytes, StandardCharsets.UTF_8);
		if (!CosmeticLimits.shallow(text)) {
			throw new Refused("animation nested too deep");
		}
		JsonElement root;
		try {
			root = JsonParser.parseString(text);
		} catch (RuntimeException e) {
			throw new Refused("animation isn't JSON");
		}
		JsonObject animations = root.isJsonObject() ? Json.object(root.getAsJsonObject(), "animations") : null;
		if (animations == null) {
			throw new Refused("no animations");
		}
		if (animations.size() > CosmeticLimits.MAX_ANIMATIONS) {
			throw new Refused(animations.size() + " animations (at most " + CosmeticLimits.MAX_ANIMATIONS + ")");
		}
		Map<String, Clip> clips = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> e : animations.entrySet()) {
			if (!e.getValue().isJsonObject() || e.getKey().length() > 200) {
				continue;
			}
			Clip clip = clip(shortName(e.getKey()), e.getValue().getAsJsonObject(), maxSeconds);
			clips.putIfAbsent(clip.name(), clip);
		}
		return new CosmeticAnimation(java.util.Collections.unmodifiableMap(clips));
	}

	/** {@code animation.<id>.idle} → {@code idle}; {@code animation.<id>.swim.null} → {@code swim.null}; others as they are. */
	static String shortName(String full) {
		String s = full.toLowerCase(Locale.ROOT);
		if (s.startsWith("animation.")) {
			int dot = s.indexOf('.', "animation.".length());
			if (dot > 0 && dot + 1 < s.length()) {
				return s.substring(dot + 1);
			}
			if (dot < 0 && s.length() > "animation.".length()) {
				return s.substring("animation.".length());
			}
		}
		return s;
	}

	private static Clip clip(String name, JsonObject a, double maxSeconds) throws Refused {
		Loop loop = Loop.ONCE;
		JsonElement l = a.get("loop");
		if (l instanceof JsonPrimitive p) {
			if (p.isBoolean() && p.getAsBoolean()) {
				loop = Loop.LOOP;
			} else if (p.isString() && p.getAsString().equals("hold_on_last_frame")) {
				loop = Loop.HOLD;
			}
		}
		List<Track> tracks = new ArrayList<>();
		JsonObject bones = Json.object(a, "bones");
		double last = 0;
		if (bones != null) {
			if (bones.size() > CosmeticLimits.MAX_ANIMATED_BONES) {
				throw new Refused(name + ": " + bones.size() + " bones (at most " + CosmeticLimits.MAX_ANIMATED_BONES + ")");
			}
			for (Map.Entry<String, JsonElement> b : bones.entrySet()) {
				if (!b.getValue().isJsonObject() || b.getKey().length() > CosmeticLimits.MAX_NAME) {
					continue;
				}
				JsonObject bone = b.getValue().getAsJsonObject();
				Channel rotation = channel(bone.get("rotation"), 0);
				Channel position = channel(bone.get("position"), 0);
				Channel scale = channel(bone.get("scale"), 1);
				for (Channel c : new Channel[] {rotation, position, scale}) {
					if (c != null) {
						last = Math.max(last, c.times[c.times.length - 1]);
					}
				}
				if (rotation != null || position != null || scale != null) {
					tracks.add(new Track(b.getKey(), rotation, position, scale));
				}
			}
		}
		double length = last;
		JsonElement len = a.get("animation_length");
		if (len instanceof JsonPrimitive p && p.isNumber()) {
			length = p.getAsDouble();
		}
		if (!Double.isFinite(length) || length < 0) {
			throw new Refused(name + ": a length that isn't one");
		}
		if (length > maxSeconds) {
			throw new Refused(name + ": " + length + " s long (at most " + maxSeconds + ")");
		}
		return new Clip(name, length, loop, List.copyOf(tracks));
	}

	/** A channel from its JSON (a value, or keyframes by time), or null when absent; {@code rest}: a component it can't read. */
	static @Nullable Channel channel(@Nullable JsonElement e, double rest) throws Refused {
		if (e == null || e.isJsonNull() || e instanceof JsonPrimitive p && p.isString()) {
			// Molang only inside a value list [x, y, z] (or pre/post), as the Store reads it: a bare expression is ignored.
			return null;
		}
		if (e.isJsonObject() && !e.getAsJsonObject().has("vector") && !e.getAsJsonObject().has("post") && !e.getAsJsonObject().has("pre")) {
			JsonObject keys = e.getAsJsonObject();
			List<double[]> order = new ArrayList<>();
			List<JsonElement> values = new ArrayList<>();
			for (Map.Entry<String, JsonElement> k : keys.entrySet()) {
				double time;
				try {
					time = Double.parseDouble(k.getKey());
				} catch (NumberFormatException ex) {
					continue;
				}
				if (!Double.isFinite(time) || time < 0 || time > 3600) {
					continue;
				}
				if (values.size() >= CosmeticLimits.MAX_KEYFRAMES) {
					throw new Refused("more than " + CosmeticLimits.MAX_KEYFRAMES + " keyframes in a channel");
				}
				order.add(new double[] {time, values.size()});
				values.add(k.getValue());
			}
			if (values.isEmpty()) {
				return null;
			}
			order.sort((x, y) -> Double.compare(x[0], y[0]));
			int n = order.size();
			double[] times = new double[n];
			Expr[] pre = new Expr[n * 3];
			Expr[] post = new Expr[n * 3];
			byte[] lerp = new byte[n];
			byte[] easing = new byte[n];
			float[] easingArg = new float[n];
			for (int i = 0; i < n; i++) {
				times[i] = order.get(i)[0];
				JsonElement v = values.get((int) order.get(i)[1]);
				keyframe(v, rest, pre, post, i);
				if (v.isJsonObject()) {
					JsonObject o = v.getAsJsonObject();
					String mode = Json.string(o, "lerp_mode");
					lerp[i] = (byte) ("catmullrom".equals(mode) ? CATMULLROM : "step".equals(mode) ? STEP : LINEAR);
					String ease = Json.string(o, "easing");
					easing[i] = (byte) Easing.of(ease);
					JsonElement args = o.get("easingArgs");
					if (args != null && args.isJsonArray() && args.getAsJsonArray().size() > 0
						&& args.getAsJsonArray().get(0) instanceof JsonPrimitive p && p.isNumber()) {
						easingArg[i] = (float) p.getAsDouble();
					}
				}
			}
			return new Channel(times, pre, post, lerp, easing, easingArg);
		}
		Expr[] value = new Expr[3];
		vector(e, rest, value, 0);
		Expr[] post = e.isJsonObject() && e.getAsJsonObject().has("post") ? new Expr[3] : value;
		if (post != value) {
			vector(e.getAsJsonObject().get("post"), rest, post, 0);
			if (!e.getAsJsonObject().has("pre")) {
				value = post;
			} else {
				vector(e.getAsJsonObject().get("pre"), rest, value, 0);
			}
		}
		return new Channel(new double[] {0}, value, post, new byte[1], new byte[1], new float[1]);
	}

	/** A keyframe's {@code pre} and {@code post} values into slot {@code i}. */
	private static void keyframe(JsonElement v, double rest, Expr[] pre, Expr[] post, int i) {
		if (v.isJsonObject() && (v.getAsJsonObject().has("pre") || v.getAsJsonObject().has("post"))) {
			JsonObject o = v.getAsJsonObject();
			JsonElement before = o.has("pre") ? o.get("pre") : o.get("post");
			JsonElement after = o.has("post") ? o.get("post") : o.get("pre");
			vector(before, rest, pre, i * 3);
			vector(after, rest, post, i * 3);
			return;
		}
		vector(v, rest, pre, i * 3);
		System.arraycopy(pre, i * 3, post, i * 3, 3);
	}

	/** Three components from a list, {@code {"vector": list}}, or one number for all three (a bare expression rests). */
	private static void vector(@Nullable JsonElement v, double rest, Expr[] out, int at) {
		if (v != null && v.isJsonObject()) {
			v = v.getAsJsonObject().get("vector");
		}
		if (v instanceof JsonPrimitive p && p.isString()) {
			v = null;
		}
		if (v != null && v.isJsonArray()) {
			JsonArray a = v.getAsJsonArray();
			for (int c = 0; c < 3; c++) {
				out[at + c] = value(a.size() == 0 ? null : a.get(Math.min(c, a.size() - 1)), rest);
			}
			return;
		}
		Expr one = value(v, rest);
		Arrays.fill(out, at, at + 3, one);
	}

	private static final Const ZERO = new Const(0);
	private static final Const ONE = new Const(1);

	private static Expr value(@Nullable JsonElement v, double rest) {
		Expr fallback = rest == 1 ? ONE : rest == 0 ? ZERO : new Const(rest);
		if (!(v instanceof JsonPrimitive p)) {
			return fallback;
		}
		if (p.isNumber()) {
			double d = p.getAsDouble();
			return Double.isFinite(d) ? new Const(d) : fallback;
		}
		if (p.isString()) {
			Expr e = Molang.compile(p.getAsString());
			return e == null ? fallback : e;
		}
		return fallback;
	}

	/** Blockbench's (GeckoLib's) easing curves by name; one Flint doesn't know is linear. */
	static final class Easing {
		static final String[] NAMES = {"linear", "step", "easeInSine", "easeOutSine", "easeInOutSine", "easeInQuad", "easeOutQuad",
			"easeInOutQuad", "easeInCubic", "easeOutCubic", "easeInOutCubic", "easeInQuart", "easeOutQuart", "easeInOutQuart", "easeInQuint",
			"easeOutQuint", "easeInOutQuint", "easeInExpo", "easeOutExpo", "easeInOutExpo", "easeInCirc", "easeOutCirc", "easeInOutCirc",
			"easeInBack", "easeOutBack", "easeInOutBack"};

		private Easing() {
		}

		static int of(@Nullable String name) {
			if (name == null) {
				return 0;
			}
			for (int i = 0; i < NAMES.length; i++) {
				if (NAMES[i].equalsIgnoreCase(name)) {
					return i;
				}
			}
			return 0;
		}

		static double apply(int kind, float arg, double t) {
			return switch (kind) {
				case 1 -> {
					double steps = arg >= 1 ? arg : 2;
					yield Math.min(1, Math.floor(t * steps) / steps);
				}
				case 2 -> 1 - Math.cos(t * Math.PI / 2);
				case 3 -> Math.sin(t * Math.PI / 2);
				case 4 -> -(Math.cos(Math.PI * t) - 1) / 2;
				case 5 -> in(t, 2);
				case 6 -> out(t, 2);
				case 7 -> inOut(t, 2);
				case 8 -> in(t, 3);
				case 9 -> out(t, 3);
				case 10 -> inOut(t, 3);
				case 11 -> in(t, 4);
				case 12 -> out(t, 4);
				case 13 -> inOut(t, 4);
				case 14 -> in(t, 5);
				case 15 -> out(t, 5);
				case 16 -> inOut(t, 5);
				case 17 -> t == 0 ? 0 : Math.pow(2, 10 * t - 10);
				case 18 -> t == 1 ? 1 : 1 - Math.pow(2, -10 * t);
				case 19 -> t == 0 ? 0 : t == 1 ? 1 : t < 0.5 ? Math.pow(2, 20 * t - 10) / 2 : (2 - Math.pow(2, -20 * t + 10)) / 2;
				case 20 -> 1 - Math.sqrt(1 - t * t);
				case 21 -> Math.sqrt(1 - (t - 1) * (t - 1));
				case 22 -> t < 0.5 ? (1 - Math.sqrt(1 - 4 * t * t)) / 2 : (Math.sqrt(1 - Math.pow(-2 * t + 2, 2)) + 1) / 2;
				case 23 -> {
					double c = arg != 0 ? arg : 1.70158;
					yield (c + 1) * t * t * t - c * t * t;
				}
				case 24 -> {
					double c = arg != 0 ? arg : 1.70158;
					double u = t - 1;
					yield 1 + (c + 1) * u * u * u + c * u * u;
				}
				case 25 -> {
					double c = (arg != 0 ? arg : 1.70158) * 1.525;
					yield t < 0.5 ? Math.pow(2 * t, 2) * ((c + 1) * 2 * t - c) / 2 : (Math.pow(2 * t - 2, 2) * ((c + 1) * (t * 2 - 2) + c) + 2) / 2;
				}
				default -> t;
			};
		}

		private static double in(double t, int p) {
			return Math.pow(t, p);
		}

		private static double out(double t, int p) {
			return 1 - Math.pow(1 - t, p);
		}

		private static double inOut(double t, int p) {
			return t < 0.5 ? Math.pow(2, p - 1) * Math.pow(t, p) : 1 - Math.pow(-2 * t + 2, p) / 2;
		}
	}
}
