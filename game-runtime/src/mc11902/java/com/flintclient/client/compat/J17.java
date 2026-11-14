package com.flintclient.client.compat;

import java.util.List;

/**
 * Java 21's library methods the shared sources use, for Minecraft 1.19.x's Java 17 (shared-sources.rules points the calls
 * here). Each behaves exactly as the Java 21 method it stands for, exceptions included.
 */
public final class J17 {
	private J17() {
	}

	// ---------- Math.clamp (Java 21) ----------

	public static int clamp(long value, int min, int max) {
		if (min > max) {
			throw new IllegalArgumentException(min + " > " + max);
		}
		return (int) Math.min(max, Math.max(value, min));
	}

	public static long clamp(long value, long min, long max) {
		if (min > max) {
			throw new IllegalArgumentException(min + " > " + max);
		}
		return Math.min(max, Math.max(value, min));
	}

	public static double clamp(double value, double min, double max) {
		if (!(min < max)) {
			if (Double.isNaN(min)) {
				throw new IllegalArgumentException("min is NaN");
			}
			if (Double.isNaN(max)) {
				throw new IllegalArgumentException("max is NaN");
			}
			if (Double.compare(min, max) > 0) {
				throw new IllegalArgumentException(min + " > " + max);
			}
		}
		return Math.min(max, Math.max(value, min));
	}

	public static float clamp(float value, float min, float max) {
		if (!(min < max)) {
			if (Float.isNaN(min)) {
				throw new IllegalArgumentException("min is NaN");
			}
			if (Float.isNaN(max)) {
				throw new IllegalArgumentException("max is NaN");
			}
			if (Float.compare(min, max) > 0) {
				throw new IllegalArgumentException(min + " > " + max);
			}
		}
		return Math.min(max, Math.max(value, min));
	}

	// ---------- List.getFirst and the other sequenced-collection methods (Java 21) ----------

	public static <T> T getFirst(List<T> list) {
		if (list.isEmpty()) {
			throw new java.util.NoSuchElementException();
		}
		return list.get(0);
	}

	public static <T> T getLast(List<T> list) {
		if (list.isEmpty()) {
			throw new java.util.NoSuchElementException();
		}
		return list.get(list.size() - 1);
	}

	public static <T> T removeFirst(List<T> list) {
		if (list.isEmpty()) {
			throw new java.util.NoSuchElementException();
		}
		return list.remove(0);
	}

	public static <T> T removeLast(List<T> list) {
		if (list.isEmpty()) {
			throw new java.util.NoSuchElementException();
		}
		return list.remove(list.size() - 1);
	}

	public static <T> void addFirst(List<T> list, T element) {
		list.add(0, element);
	}

	// ---------- Math.ceilDiv (Java 18) ----------

	public static int ceilDiv(int x, int y) {
		int q = x / y;
		return (x ^ y) >= 0 && q * y != x ? q + 1 : q;
	}

	public static long ceilDiv(long x, long y) {
		long q = x / y;
		return (x ^ y) >= 0 && q * y != x ? q + 1 : q;
	}

	// ---------- Thread.threadId (Java 19) ----------

	@SuppressWarnings("deprecation")
	public static long threadId(Thread thread) {
		return thread.getId();
	}
}
