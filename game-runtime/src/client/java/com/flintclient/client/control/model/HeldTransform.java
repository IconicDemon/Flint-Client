package com.flintclient.client.control.model;

/**
 * Where a held item (or the empty hand) sits in first person: moved in view space (X right, Y up, Z toward you; mirrored for
 * the other hand), turned about itself (degrees) and sized per axis. {@link #NONE} leaves it where Minecraft draws it. Pure.
 */
public record HeldTransform(float x, float y, float z, float rotX, float rotY, float rotZ, float scaleX, float scaleY, float scaleZ) {
	/** Minecraft's own place: nothing moved, turned or sized. */
	public static final HeldTransform NONE = new HeldTransform(0, 0, 0, 0, 0, 0, 1, 1, 1);
	/** How many numbers a transform is (position, rotation, scale; X, Y, Z each). */
	public static final int SIZE = 9;

	/** Component {@code i}: 0-2 position, 3-5 rotation, 6-8 scale. */
	public float get(int i) {
		return switch (i) {
			case 0 -> x;
			case 1 -> y;
			case 2 -> z;
			case 3 -> rotX;
			case 4 -> rotY;
			case 5 -> rotZ;
			case 6 -> scaleX;
			case 7 -> scaleY;
			case 8 -> scaleZ;
			default -> throw new IndexOutOfBoundsException(i);
		};
	}

	/** This transform with component {@code i} set to {@code value}. */
	public HeldTransform with(int i, float value) {
		float[] v = toArray();
		v[i] = value;
		return of(v);
	}

	public float[] toArray() {
		return new float[] {x, y, z, rotX, rotY, rotZ, scaleX, scaleY, scaleZ};
	}

	public static HeldTransform of(float[] v) {
		return new HeldTransform(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8]);
	}

	/** Whether it moves the item at all. */
	public boolean moves() {
		return x != 0 || y != 0 || z != 0;
	}

	public boolean turns() {
		return rotX != 0 || rotY != 0 || rotZ != 0;
	}

	public boolean sizes() {
		return scaleX != 1 || scaleY != 1 || scaleZ != 1;
	}

	/** Whether it changes anything (else Minecraft's own pose is left alone, at no cost). */
	public boolean changes() {
		return moves() || turns() || sizes();
	}
}
