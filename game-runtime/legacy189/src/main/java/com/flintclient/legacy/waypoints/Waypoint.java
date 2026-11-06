package com.flintclient.legacy.waypoints;

/**
 * One waypoint on 1.8.9: a name, a block in a dimension (Minecraft 1.8.9's numbers: 0 the Overworld, -1 the Nether, 1 the End),
 * a colour (0xRRGGBB), whether it shows, and whether it marks where you died. Edited in place by the store (which saves and
 * counts each change); the renderer reads the fields every frame.
 */
public final class Waypoint {
	public static final int MAX_NAME = 32;
	/** Coordinates are kept within Minecraft's world border range. */
	public static final int LIMIT = 30_000_000;

	String name;
	int x;
	int y;
	int z;
	int dimension;
	int rgb;
	boolean visible = true;
	boolean death;

	public Waypoint(String name, int x, int y, int z, int dimension, int rgb) {
		this.name = clean(name);
		this.x = clamp(x);
		this.y = Math.max(-2048, Math.min(2048, y));
		this.z = clamp(z);
		this.dimension = dimension;
		this.rgb = rgb & 0xFFFFFF;
	}

	private static int clamp(int v) {
		return Math.max(-LIMIT, Math.min(LIMIT, v));
	}

	/** {@code name} without control characters or colour codes, at most {@link #MAX_NAME} long, trimmed ("" allowed). */
	static String clean(String name) {
		if (name == null) {
			return "";
		}
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < name.length() && out.length() < MAX_NAME; i++) {
			char ch = name.charAt(i);
			if (ch == '§') {
				i++;
			} else if (!Character.isISOControl(ch)) {
				out.append(ch);
			}
		}
		return out.toString().trim();
	}

	public String name() {
		return name;
	}

	public int x() {
		return x;
	}

	public int y() {
		return y;
	}

	public int z() {
		return z;
	}

	public int dimension() {
		return dimension;
	}

	public int rgb() {
		return rgb;
	}

	public boolean visible() {
		return visible;
	}

	public boolean death() {
		return death;
	}

	/** The letter its Icon label shows: its name's first character in capitals, "W" for a waypoint with no name. */
	public String letter() {
		if (name.isEmpty()) {
			return "W";
		}
		int cp = name.codePointAt(0);
		return new String(Character.toChars(Character.toUpperCase(cp)));
	}

	/** What its Text label and the list show: its name, or its coordinates when it has none. */
	public String title() {
		return name.isEmpty() ? x + " " + y + " " + z : name;
	}

	/** Distance in blocks from ({@code px}, {@code py}, {@code pz}) to the top centre of its block. */
	public double distanceTo(double px, double py, double pz) {
		double dx = x + 0.5 - px;
		double dy = y + 1 - py;
		double dz = z + 0.5 - pz;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}
}
