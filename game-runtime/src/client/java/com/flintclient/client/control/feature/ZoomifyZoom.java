/*
 * Adapted from Zoomify by isXander (https://github.com/isXander/Zoomify), licensed under the GNU Lesser General Public
 * License v3.0 (text in META-INF/licenses/LGPL-3.0-Zoomify.txt). Changed for Flint: ported from Kotlin to plain Java, with
 * the settings read through suppliers.
 */
package com.flintclient.client.control.feature;

import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Zoom's arithmetic, Zoomify's: a zoom divides the field of view by a divisor that eases from 1 to the initial zoom (and
 * back) over set times along a chosen curve, ticked 20 times a second and interpolated between ticks, times a scroll
 * zoom of {@code perStep^(steps)} that glides toward its target. Pure Java.
 */
public final class ZoomifyZoom {
	private final Interpolator initial;
	private final Interpolator scroll;
	private final IntSupplier initialZoom;
	private final IntSupplier zoomPerStep;
	private final IntSupplier maxScrollTiers;
	private double prevInitial;
	private double initialT;
	private boolean zoomingLastTick;
	private double prevScroll;
	private double scrollT;
	private int lastScrollTier;
	private boolean resetting;
	private double resetMultiplier;

	/** {@code zoomPerStep} in percent (150: each step zooms 1.5 times further). */
	public ZoomifyZoom(Interpolator initial, Interpolator scroll, IntSupplier initialZoom, IntSupplier zoomPerStep, IntSupplier maxScrollTiers) {
		this.initial = initial;
		this.scroll = scroll;
		this.initialZoom = initialZoom;
		this.zoomPerStep = zoomPerStep;
		this.maxScrollTiers = maxScrollTiers;
	}

	/** How far the scroll can zoom back out: far enough to get back to no zoom at all. */
	public int minScrollTiers() {
		double step = zoomPerStep.getAsInt() / 100.0;
		if (step <= 1.0) {
			return 0;
		}
		return -Math.clamp((int) Math.ceil(Math.log(initialZoom.getAsInt()) / Math.log(step)), 0, maxScrollTiers.getAsInt());
	}

	public int maxScrollTiers() {
		return maxScrollTiers.getAsInt();
	}

	/** One client tick ({@code tickDelta} 0.05 s). */
	public void tick(boolean zooming, int scrollTiers, double tickDelta) {
		if (zooming && !zoomingLastTick) {
			resetting = false;
		}
		double target = zooming ? 1.0 : 0.0;
		prevInitial = initialT;
		initialT = initial.tick(target, initialT, tickDelta);
		prevInitial = initial.modifyPrev(prevInitial);
		if (!initial.isSmooth()) {
			prevInitial = initialT;
		}
		zoomingLastTick = zooming;

		if (Math.abs(scrollTiers) > Math.abs(lastScrollTier)) {
			resetting = false;
		}
		int max = maxScrollTiers.getAsInt();
		double scrollTarget = max > 0 ? scrollTiers / (double) max : 0.0;
		prevScroll = scrollT;
		scrollT = scroll.tick(scrollTarget, scrollT, tickDelta);
		prevScroll = scroll.modifyPrev(prevScroll);
		if (!initial.isSmooth()) {
			prevInitial = initialT;
		}
		lastScrollTier = scrollTiers;
	}

	/** The divisor the field of view is divided by this frame, {@code tickDelta} into the tick. */
	public double divisor(float tickDelta) {
		double base = 1 / initialMultiplier(tickDelta);
		double t = resetting ? 0.0 : scroll.isSmooth() ? scroll.modify(lerp(tickDelta, prevScroll, scrollT)) : scrollT;
		double result = Math.clamp(base * Math.pow(zoomPerStep.getAsInt() / 100.0, t * maxScrollTiers.getAsInt()), 1.0, 500.0);
		if (initialT == 0.0 && scrollT == 0.0) {
			resetting = false;
		}
		if (!resetting) {
			resetMultiplier = 1 / result;
		}
		return result;
	}

	private double initialMultiplier(float tickDelta) {
		double t = initial.isSmooth() ? initial.modify(lerp(tickDelta, prevInitial, initialT)) : initialT;
		return lerp(t, 1.0, !resetting ? 1.0 / initialZoom.getAsInt() : resetMultiplier);
	}

	/** The zoom ended: zoom out from wherever the scroll took it, not from the initial zoom. */
	public void reset() {
		if (!resetting && scrollT != 0.0) {
			resetting = true;
			scrollT = 0.0;
			prevScroll = 0.0;
		}
	}

	/** Back to no zoom at once. */
	public void setToZero() {
		initialT = 0;
		prevInitial = 0;
		zoomingLastTick = false;
		scrollT = 0;
		prevScroll = 0;
		lastScrollTier = 0;
		resetting = false;
	}

	static double lerp(double t, double a, double b) {
		return a + t * (b - a);
	}

	// ---------- interpolators ----------

	public interface Interpolator {
		boolean isSmooth();

		double tick(double target, double current, double tickDelta);

		default double modify(double t) {
			return t;
		}

		default double modifyPrev(double t) {
			return t;
		}
	}

	/** Jumps straight to the target (the secondary zoom's scroll). */
	public static final Interpolator INSTANT = new Interpolator() {
		@Override
		public boolean isSmooth() {
			return false;
		}

		@Override
		public double tick(double target, double current, double tickDelta) {
			return target;
		}
	};

	/** Moves toward the target by an increment each tick. */
	public abstract static class Linear implements Interpolator {
		protected boolean goingIn = true;

		@Override
		public boolean isSmooth() {
			return true;
		}

		@Override
		public double tick(double target, double current, double tickDelta) {
			if (target > current) {
				goingIn = true;
				return Math.min(current + increment(false, tickDelta, target, current), target);
			} else if (target < current) {
				goingIn = false;
				return Math.max(current - increment(true, tickDelta, target, current), target);
			}
			goingIn = true;
			return target;
		}

		protected abstract double increment(boolean zoomingOut, double tickDelta, double target, double current);
	}

	/** Linear in time: the whole way in {@code timeIn} seconds, or back in {@code timeOut}. */
	public static class Timed extends Linear {
		private final DoubleSupplier timeIn;
		private final DoubleSupplier timeOut;

		public Timed(DoubleSupplier timeIn, DoubleSupplier timeOut) {
			this.timeIn = timeIn;
			this.timeOut = timeOut;
		}

		@Override
		public boolean isSmooth() {
			return goingIn && timeIn.getAsDouble() > 0.0 || !goingIn && timeOut.getAsDouble() > 0.0;
		}

		@Override
		protected double increment(boolean zoomingOut, double tickDelta, double target, double current) {
			return tickDelta / (zoomingOut ? timeOut.getAsDouble() : timeIn.getAsDouble());
		}
	}

	/** Timed, along a transition curve for each direction; a reversed zoom continues smoothly from where it was. */
	public static final class Transitioned extends Timed {
		private final Supplier<Transition> in;
		private final Supplier<Transition> out;
		private Transition active;
		private Transition inactive;
		private double prevTarget;
		private boolean justSwapped;

		/** {@code out} is the curve the player chose for zooming out; it runs backward, so its opposite is used. */
		public Transitioned(Supplier<Transition> in, Supplier<Transition> out, DoubleSupplier timeIn, DoubleSupplier timeOut) {
			super(timeIn, timeOut);
			this.in = in;
			this.out = () -> out.get().opposite();
			this.active = in.get();
			this.inactive = this.out.get();
		}

		@Override
		public boolean isSmooth() {
			return !justSwapped && super.isSmooth() && active != Transition.INSTANT;
		}

		@Override
		public double tick(double target, double current, double tickDelta) {
			double mod = current;
			if (target > current) {
				active = in.get();
				inactive = out.get();
				if (prevTarget < target && active.hasInverse()) {
					justSwapped = true;
					mod = active.inverse(inactive.apply(current));
				}
			} else if (target < current) {
				active = out.get();
				inactive = in.get();
				if (prevTarget > target && active.hasInverse()) {
					justSwapped = true;
					mod = active.inverse(inactive.apply(current));
				}
			}
			prevTarget = target;
			return active == Transition.INSTANT ? target : super.tick(target, mod, tickDelta);
		}

		@Override
		public double modify(double t) {
			return active.apply(t);
		}

		@Override
		public double modifyPrev(double t) {
			if (justSwapped) {
				justSwapped = false;
				return active.inverse(inactive.apply(t));
			}
			return t;
		}
	}

	/** Glides toward the target, a share of the way each tick (the scroll zoom); a smoothness of 1 jumps. */
	public static final class Smooth extends Linear {
		private final DoubleSupplier smoothness;

		public Smooth(DoubleSupplier smoothness) {
			this.smoothness = smoothness;
		}

		@Override
		public boolean isSmooth() {
			return smoothness.getAsDouble() != 1.0;
		}

		@Override
		public double tick(double target, double current, double tickDelta) {
			return !isSmooth() ? target : super.tick(target, current, tickDelta);
		}

		@Override
		protected double increment(boolean zoomingOut, double tickDelta, double target, double current) {
			return (!zoomingOut ? target - current : current - target) * smoothness.getAsDouble() / 0.05 * tickDelta;
		}
	}

	// ---------- the transition curves, in the order of Zoom's setting ----------

	public enum Transition {
		INSTANT,
		LINEAR,
		EASE_IN_SINE,
		EASE_OUT_SINE,
		EASE_IN_OUT_SINE,
		EASE_IN_QUAD,
		EASE_OUT_QUAD,
		EASE_IN_OUT_QUAD,
		EASE_IN_CUBIC,
		EASE_OUT_CUBIC,
		EASE_IN_OUT_CUBIC,
		EASE_IN_EXP,
		EASE_OUT_EXP,
		EASE_IN_OUT_EXP;

		private static final double LOG2_1023 = Math.log(1023.0) / Math.log(2.0);
		private static final double TEN_LN2 = 10.0 * Math.log(2.0);
		private static final double LN_1023 = Math.log(1023.0);
		private static final Transition[] ALL = values();

		public static Transition of(int index) {
			return index >= 0 && index < ALL.length ? ALL[index] : EASE_OUT_EXP;
		}

		public double apply(double t) {
			return switch (this) {
				case INSTANT, LINEAR -> t;
				case EASE_IN_SINE -> 1 - Math.cos(t * Math.PI / 2);
				case EASE_OUT_SINE -> Math.sin(t * Math.PI / 2);
				case EASE_IN_OUT_SINE -> -(Math.cos(Math.PI * t) - 1) / 2;
				case EASE_IN_QUAD -> t * t;
				case EASE_OUT_QUAD -> 1 - (1 - t) * (1 - t);
				case EASE_IN_OUT_QUAD -> t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
				case EASE_IN_CUBIC -> Math.pow(t, 3);
				case EASE_OUT_CUBIC -> 1 - Math.pow(1 - t, 3);
				case EASE_IN_OUT_CUBIC -> t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
				case EASE_IN_EXP -> t == 0.0 ? 0.0 : t == 1.0 ? 1.0 : Math.pow(2.0, 10.0 * t - LOG2_1023);
				case EASE_OUT_EXP -> t == 0.0 ? 0.0 : t == 1.0 ? 1.0 : 1.0 - Math.pow(2.0, 10.0 - LOG2_1023 - 10.0 * t);
				case EASE_IN_OUT_EXP -> t == 0.0 ? 0.0 : t == 1.0 ? 1.0
					: t < 0.5 ? Math.pow(2.0, 20.0 * t - LOG2_1023) : 1.0 - Math.pow(2.0, 10.0 - LOG2_1023 - 10.0 * t);
			};
		}

		public boolean hasInverse() {
			return switch (this) {
				case EASE_IN_SINE, EASE_OUT_SINE, EASE_IN_QUAD, EASE_OUT_QUAD, EASE_IN_CUBIC, EASE_OUT_CUBIC, EASE_IN_EXP, EASE_OUT_EXP -> true;
				default -> false;
			};
		}

		public double inverse(double x) {
			return switch (this) {
				case EASE_IN_SINE -> Math.acos(-(x - 1)) * 2 / Math.PI;
				case EASE_OUT_SINE -> Math.asin(x) * 2 / Math.PI;
				case EASE_IN_QUAD -> Math.sqrt(x);
				case EASE_OUT_QUAD -> -(Math.sqrt(-(x - 1)) - 1);
				case EASE_IN_CUBIC -> Math.pow(x, 1.0 / 3.0);
				case EASE_OUT_CUBIC -> -Math.pow(-x + 1, 1.0 / 3.0) + 1;
				case EASE_IN_EXP -> x == 0.0 ? 0.0 : x == 1.0 ? 1.0 : Math.log(1023 * x + 1) / TEN_LN2;
				case EASE_OUT_EXP -> x == 0.0 ? 0.0 : x == 1.0 ? 1.0 : -((Math.log(-((1023 * x - 1024) / 1023)) - TEN_LN2 + LN_1023) / TEN_LN2);
				default -> throw new UnsupportedOperationException(name());
			};
		}

		public Transition opposite() {
			return switch (this) {
				case EASE_IN_SINE -> EASE_OUT_SINE;
				case EASE_OUT_SINE -> EASE_IN_SINE;
				case EASE_IN_QUAD -> EASE_OUT_QUAD;
				case EASE_OUT_QUAD -> EASE_IN_QUAD;
				case EASE_IN_CUBIC -> EASE_OUT_CUBIC;
				case EASE_OUT_CUBIC -> EASE_IN_CUBIC;
				case EASE_IN_EXP -> EASE_OUT_EXP;
				case EASE_OUT_EXP -> EASE_IN_EXP;
				default -> this;
			};
		}
	}
}
