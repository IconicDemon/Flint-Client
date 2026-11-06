package com.flintclient.client.control.feature;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Waypoints shared in chat. Sharing sends one readable line anyone can follow ("[Flint waypoint] Home at x 120, y 64, z -30
 * (Overworld)"); a line like it in chat gets a click to add it. Also understood: Xaero's minimap's shared form
 * ({@code xaero-waypoint:Name:N:x:y:z:...:Internal-overworld-waypoints}, read by its documented shape) and coordinates
 * written with their letters ("x: 120 y: 64 z: -30", "X 120 / Y 64 / Z -30") or as three numbers ("120, 64, -30").
 * The click runs a command Flint answers itself ({@link #COMMAND}); nothing is sent to the server. Version-neutral.
 */
public final class WaypointShare {
	/** The command a click in chat runs, answered by Flint on this computer. */
	public static final String COMMAND = "flintwaypoint";
	private static final Pattern FLINT = Pattern.compile(
		"\\[Flint waypoint] (.{1,32}?) at x (-?\\d{1,8}), y (-?\\d{1,4}), z (-?\\d{1,8})(?: \\(([^)]{1,40})\\))?");
	private static final Pattern XAERO = Pattern.compile(
		"xaero-waypoint:([^:]{1,32}):[^:]{0,3}:(-?\\d{1,8}):(~|-?\\d{1,4}):(-?\\d{1,8}):\\d{1,2}:(?:true|false):\\d{1,3}:(?:Internal[-_])?([a-z_\\-]{1,40})");
	private static final Pattern LETTERS = Pattern.compile(
		"(?i)\\bx\\s*[:=]?\\s*(-?\\d{1,8})[\\s,;/|]+y\\s*[:=]?\\s*(-?\\d{1,4})[\\s,;/|]+z\\s*[:=]?\\s*(-?\\d{1,8})\\b");
	private static final Pattern NUMBERS = Pattern.compile("(?<![\\d.\\-])(-?\\d{1,8}),\\s?(-?\\d{1,3}),\\s?(-?\\d{1,8})(?![\\d.])");

	/** A waypoint found in a chat line: its name (null: none given), position and dimension (null: unknown). */
	public record Found(@Nullable String name, int x, int y, int z, @Nullable String dimension) {
	}

	private WaypointShare() {
	}

	/** The line sharing {@code w} sends. */
	public static String line(Waypoint w) {
		return "[Flint waypoint] " + w.name().replace('(', '[').replace(')', ']') + " at x " + w.x() + ", y " + w.y() + ", z " + w.z() + " ("
			+ dimensionName(w.dimension()) + ")";
	}

	/** "Overworld", "Nether", "End", or the dimension's own name. */
	public static String dimensionName(String dimension) {
		return switch (dimension) {
			case "minecraft:overworld" -> "Overworld";
			case "minecraft:the_nether" -> "Nether";
			case "minecraft:the_end" -> "End";
			default -> {
				String path = dimension.substring(dimension.indexOf(':') + 1).replace('_', ' ');
				yield path.isEmpty() ? dimension : Character.toUpperCase(path.charAt(0)) + path.substring(1);
			}
		};
	}

	/** The dimension a shared name stands for, or null. */
	public static @Nullable String dimensionOf(@Nullable String name) {
		if (name == null) {
			return null;
		}
		String n = name.strip().toLowerCase(Locale.ROOT).replace(' ', '_');
		return switch (n) {
			case "overworld", "minecraft:overworld" -> "minecraft:overworld";
			case "nether", "the_nether", "minecraft:the_nether" -> "minecraft:the_nether";
			case "end", "the_end", "minecraft:the_end" -> "minecraft:the_end";
			default -> n.contains(":") ? n : null;
		};
	}

	/** The first waypoint in a chat line, or null. {@code numbersAlone}: also three bare numbers ("120, 64, -30"). */
	public static @Nullable Found find(String text, boolean numbersAlone) {
		Matcher m = FLINT.matcher(text);
		if (m.find()) {
			return new Found(m.group(1).strip(), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)),
				dimensionOf(m.group(5)));
		}
		m = XAERO.matcher(text);
		if (m.find()) {
			int y = m.group(3).equals("~") ? 64 : Integer.parseInt(m.group(3));
			String dim = m.group(5).replace("-waypoints", "").replace("_waypoints", "");
			return new Found(m.group(1).strip(), Integer.parseInt(m.group(2)), y, Integer.parseInt(m.group(4)), dimensionOf(dim));
		}
		m = LETTERS.matcher(text);
		if (m.find()) {
			return new Found(null, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), null);
		}
		if (numbersAlone) {
			m = NUMBERS.matcher(text);
			while (m.find()) {
				int y = Integer.parseInt(m.group(2));
				if (y >= -64 && y <= 320) {
					return new Found(null, Integer.parseInt(m.group(1)), y, Integer.parseInt(m.group(3)), null);
				}
			}
		}
		return null;
	}

	/** The command a click on a found waypoint runs: dimension, x, y, z, then the name. */
	public static String command(Found f, String fallbackDimension, String fallbackName) {
		String dim = f.dimension() != null ? f.dimension() : fallbackDimension;
		String name = f.name() != null ? f.name() : fallbackName;
		return COMMAND + " add " + dim + " " + f.x() + " " + f.y() + " " + f.z() + " " + name;
	}

	/** A click's command read back ({@code flintwaypoint add <dim> <x> <y> <z> <name>}), or null when it isn't one. */
	public static @Nullable Found parseCommand(String command) {
		String c = command.startsWith("/") ? command.substring(1) : command;
		if (!c.startsWith(COMMAND + " add ")) {
			return null;
		}
		String[] parts = c.substring(COMMAND.length() + 5).split(" ", 5);
		if (parts.length < 4) {
			return null;
		}
		try {
			String name = parts.length == 5 ? parts[4].strip() : null;
			return new Found(name == null || name.isEmpty() ? null : name, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
				Integer.parseInt(parts[3]), parts[0]);
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
