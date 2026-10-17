package com.flintclient.client.control.model;

import java.util.Collection;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Rules for layout names and ids. Names are what players see (1–32 characters, unique ignoring case); ids are
 * file names made from the name ({@code [a-z0-9-]{1,40}}), so they are safe on every file system and never
 * change after a rename. New ids also avoid the names Windows keeps for devices ({@code con}, {@code nul},
 * {@code com1}…), which can't be used as file names there even with an extension.
 */
public final class LayoutNames {
	public static final int MAX_NAME = 32;
	public static final int MAX_ID = 40;
	private static final Pattern ID = Pattern.compile("[a-z0-9-]{1," + MAX_ID + "}");
	private static final Pattern WINDOWS_DEVICE = Pattern.compile("con|prn|aux|nul|com[0-9]|lpt[0-9]");

	private LayoutNames() {
	}

	public static boolean validId(@Nullable String id) {
		return id != null && ID.matcher(id).matches();
	}

	/**
	 * The name as it would be saved: each run of control characters (a pasted line break, a tab) becomes one
	 * space where it separates words, and surrounding spaces are trimmed.
	 */
	public static String clean(String name) {
		StringBuilder out = new StringBuilder(name.length());
		boolean gap = false;
		for (int i = 0; i < name.length(); ) {
			int c = name.codePointAt(i);
			i += Character.charCount(c);
			if (Character.isISOControl(c)) {
				gap = true;
				continue;
			}
			if (gap && !out.isEmpty() && !Character.isWhitespace(out.charAt(out.length() - 1)) && !Character.isWhitespace(c)) {
				out.append(' ');
			}
			gap = false;
			out.appendCodePoint(c);
		}
		return out.toString().strip();
	}

	/** Whether {@code id} is a name Windows reserves for a device, so it can't be a file name there. */
	public static boolean reservedOnWindows(String id) {
		return WINDOWS_DEVICE.matcher(id).matches();
	}

	/**
	 * Why {@code name} can't be used, or null when it can. {@code otherNames} are the other layouts' names (leave
	 * out the one being renamed, so changing only the case is allowed).
	 */
	public static @Nullable String nameError(String name, Collection<String> otherNames) {
		String cleaned = clean(name);
		if (cleaned.isEmpty()) {
			return "Enter a name";
		}
		if (cleaned.codePointCount(0, cleaned.length()) > MAX_NAME) {
			return "Use " + MAX_NAME + " characters or fewer";
		}
		for (String other : otherNames) {
			if (other.strip().equalsIgnoreCase(cleaned)) {
				return "Another layout is already called \"" + other.strip() + "\"";
			}
		}
		return null;
	}

	/** {@code base}, or {@code base 2}, {@code base 3}… whichever isn't taken (ignoring case). */
	public static String uniqueName(String base, Collection<String> names) {
		String cleaned = clean(base);
		if (cleaned.isEmpty()) {
			cleaned = "Layout";
		}
		for (int n = 1; ; n++) {
			String suffix = n == 1 ? "" : " " + n;
			String head = cleaned;
			while (head.codePointCount(0, head.length()) + suffix.length() > MAX_NAME) {
				head = head.substring(0, head.offsetByCodePoints(head.length(), -1)).strip();
			}
			String candidate = head + suffix;
			boolean taken = false;
			for (String name : names) {
				if (name.strip().equalsIgnoreCase(candidate)) {
					taken = true;
					break;
				}
			}
			if (!taken) {
				return candidate;
			}
		}
	}

	/**
	 * A file id for {@code name}: accents dropped, lower case, anything else as single dashes; then {@code -2},
	 * {@code -3}… until {@code taken} says no (a Windows device name counts as taken). Never longer than
	 * {@value #MAX_ID}.
	 */
	public static String uniqueId(String name, Predicate<String> taken) {
		String base = slug(name);
		for (int n = 1; ; n++) {
			String suffix = n == 1 ? "" : "-" + n;
			String head = base.length() + suffix.length() > MAX_ID ? trimDashes(base.substring(0, MAX_ID - suffix.length())) : base;
			if (head.isEmpty()) {
				head = "layout";
			}
			String candidate = head + suffix;
			if (!reservedOnWindows(candidate) && !taken.test(candidate)) {
				return candidate;
			}
		}
	}

	/** The id-shaped form of a name ({@code "PvP · Ranked"} → {@code "pvp-ranked"}); {@code "layout"} when nothing is left. */
	public static String slug(String name) {
		String folded = LibraryQuery.fold(name).toLowerCase(Locale.ROOT);
		StringBuilder out = new StringBuilder(folded.length());
		boolean dash = false;
		for (int i = 0; i < folded.length(); i++) {
			char c = folded.charAt(i);
			if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
				if (dash && !out.isEmpty()) {
					out.append('-');
				}
				dash = false;
				out.append(c);
			} else {
				dash = true;
			}
		}
		String slug = trimDashes(out.length() > MAX_ID ? out.substring(0, MAX_ID) : out.toString());
		return slug.isEmpty() ? "layout" : slug;
	}

	private static String trimDashes(String value) {
		int start = 0;
		int end = value.length();
		while (start < end && value.charAt(start) == '-') {
			start++;
		}
		while (end > start && value.charAt(end - 1) == '-') {
			end--;
		}
		return value.substring(start, end);
	}
}
