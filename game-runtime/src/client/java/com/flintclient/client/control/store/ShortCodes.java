package com.flintclient.client.control.store;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Short profile codes: 6 characters such as {@code K7QX3M}, from an alphabet without the look-alikes 0/O and 1/I/L, so a
 * code read out loud or typed from a screenshot comes out right. The profile itself is kept by Flint's servers
 * ({@link ProfileShareClient}); the long {@code FLINT-...} codes ({@link ShareCodes}) hold the whole profile and keep
 * working. Version-neutral.
 */
public final class ShortCodes {
	/** The characters a code is made of (the server makes codes from the same 31). */
	public static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
	public static final int LENGTH = 6;

	private ShortCodes() {
	}

	/**
	 * {@code text} as a short code (upper case), or null when it isn't one. Case doesn't matter, and spaces and line
	 * breaks (from a chat) are ignored, as is a {@code FLINT-} in front: a long code is always longer than six characters.
	 */
	public static @Nullable String parse(@Nullable String text) {
		if (text == null || text.length() > 64) {
			return null;
		}
		String code = text.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
		if (code.startsWith(ShareCodes.PREFIX)) {
			code = code.substring(ShareCodes.PREFIX.length());
		}
		if (code.length() != LENGTH) {
			return null;
		}
		for (int i = 0; i < LENGTH; i++) {
			if (ALPHABET.indexOf(code.charAt(i)) < 0) {
				return null;
			}
		}
		return code;
	}

	/** Whether {@code text} is a short code rather than a long one or a profile's JSON. */
	public static boolean isShortCode(@Nullable String text) {
		return parse(text) != null;
	}

	/** Whether {@code text} is six letters and digits that aren't a code: one of them is a 0, O, 1, I or L, which codes never use. */
	public static boolean looksMistyped(@Nullable String text) {
		if (text == null || text.length() > 64 || isShortCode(text)) {
			return false;
		}
		String code = text.replaceAll("\\s+", "");
		return code.length() == LENGTH && code.chars().allMatch(c -> c < 128 && Character.isLetterOrDigit(c));
	}
}
