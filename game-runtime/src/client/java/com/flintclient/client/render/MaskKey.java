package com.flintclient.client.render;

/**
 * A mask atlas shape's kind and parameters packed into one {@code long}, so finding a cached shape
 * allocates nothing. Bits, from the lowest: kind (3), {@code a} (16), {@code b} (16), {@code c} (15),
 * {@code d} (14). Every combination inside those ranges has its own key; a value outside them is
 * rejected rather than allowed to collide with another shape.
 */
final class MaskKey {
	static final int CORNER = 0;
	static final int NOTCH = 1;
	static final int ROUND_RECT = 2;
	static final int RING = 3;
	static final int RING_CORNER = 4;
	static final int ICON = 5;
	static final int MARK = 6;

	static final int MAX_A = 0xFFFF;
	static final int MAX_B = 0xFFFF;
	static final int MAX_C = 0x7FFF;
	static final int MAX_D = 0x3FFF;

	private MaskKey() {
	}

	static long of(int kind, int a, int b, int c, int d) {
		if (kind < CORNER || kind > MARK || a < 0 || a > MAX_A || b < 0 || b > MAX_B || c < 0 || c > MAX_C || d < 0 || d > MAX_D) {
			throw new IllegalArgumentException("Mask parameters out of range: kind " + kind + ", " + a + ", " + b + ", " + c + ", " + d);
		}
		return kind | (long) a << 3 | (long) b << 19 | (long) c << 35 | (long) d << 50;
	}

	static int kind(long key) {
		return (int) (key & 7);
	}

	static int a(long key) {
		return (int) (key >>> 3 & MAX_A);
	}

	static int b(long key) {
		return (int) (key >>> 19 & MAX_B);
	}

	static int c(long key) {
		return (int) (key >>> 35 & MAX_C);
	}

	static int d(long key) {
		return (int) (key >>> 50 & MAX_D);
	}
}
