package com.flintclient.client.control.feature;

import java.util.Objects;

/**
 * One waypoint: a name, a block position in a dimension ({@code "minecraft:overworld"}), a colour ({@code 0xRRGGBB}),
 * whether it shows, a group (empty: none) and what it shows in the world: its name, its distance, a beam, the block
 * highlighted. Immutable; edits make a new one.
 */
public record Waypoint(String name, int x, int y, int z, String dimension, int rgb, boolean visible, String group, boolean showText,
	boolean showDistance, boolean beam, boolean highlight) {
	public static final int MAX_GROUP = 24;
	public static final int MAX_NAME = 32;
	/** Coordinates are kept within Minecraft's world border range. */
	public static final int LIMIT = 30_000_000;

	public Waypoint {
		name = cleanName(name);
		x = clamp(x);
		y = Math.clamp(y, -2048, 2048);
		z = clamp(z);
		dimension = Objects.requireNonNull(dimension);
		rgb &= 0xFFFFFF;
		group = cleanGroup(group);
	}

	/** A waypoint with the usual extras: no group, its name and distance shown, a beam, no block highlight. */
	public Waypoint(String name, int x, int y, int z, String dimension, int rgb, boolean visible) {
		this(name, x, y, z, dimension, rgb, visible, "", true, true, true, false);
	}

	static String cleanGroup(String group) {
		if (group == null) {
			return "";
		}
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < group.length() && out.length() < MAX_GROUP; i++) {
			char ch = group.charAt(i);
			if (ch == '§') {
				i++;
			} else if (!Character.isISOControl(ch)) {
				out.append(ch);
			}
		}
		return out.toString().strip();
	}

	private static int clamp(int value) {
		return Math.clamp(value, -LIMIT, LIMIT);
	}

	static String cleanName(String name) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < name.length() && out.length() < MAX_NAME; i++) {
			char ch = name.charAt(i);
			if (!Character.isISOControl(ch) && ch != '§') {
				out.append(ch);
			}
		}
		String cleaned = out.toString().strip();
		return cleaned.isEmpty() ? "Waypoint" : cleaned;
	}

	public Waypoint withName(String value) {
		return new Waypoint(value, x, y, z, dimension, rgb, visible, group, showText, showDistance, beam, highlight);
	}

	public Waypoint withPosition(int nx, int ny, int nz) {
		return new Waypoint(name, nx, ny, nz, dimension, rgb, visible, group, showText, showDistance, beam, highlight);
	}

	public Waypoint withColor(int value) {
		return new Waypoint(name, x, y, z, dimension, value, visible, group, showText, showDistance, beam, highlight);
	}

	public Waypoint withVisible(boolean value) {
		return new Waypoint(name, x, y, z, dimension, rgb, value, group, showText, showDistance, beam, highlight);
	}

	public Waypoint withDimension(String value) {
		return new Waypoint(name, x, y, z, value, rgb, visible, group, showText, showDistance, beam, highlight);
	}

	public Waypoint withGroup(String value) {
		return new Waypoint(name, x, y, z, dimension, rgb, visible, value, showText, showDistance, beam, highlight);
	}

	/** The world display switches: name, distance, beam, block highlight. */
	public Waypoint withShows(boolean text, boolean distance, boolean showBeam, boolean showHighlight) {
		return new Waypoint(name, x, y, z, dimension, rgb, visible, group, text, distance, showBeam, showHighlight);
	}

	/** Whether a search (lower case words) matches its name or group. */
	public boolean matches(String query) {
		String text = (name + " " + group).toLowerCase(java.util.Locale.ROOT);
		for (String word : query.toLowerCase(java.util.Locale.ROOT).trim().split("\\s+")) {
			if (!word.isEmpty() && !text.contains(word)) {
				return false;
			}
		}
		return true;
	}

	/** Distance in blocks from ({@code px}, {@code py}, {@code pz}) to the block's centre. */
	public double distanceTo(double px, double py, double pz) {
		double dx = x + 0.5 - px;
		double dy = y + 0.5 - py;
		double dz = z + 0.5 - pz;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}
}
