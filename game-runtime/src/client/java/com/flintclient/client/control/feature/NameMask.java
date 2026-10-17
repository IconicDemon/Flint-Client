package com.flintclient.client.control.feature;

import java.util.Map;

/**
 * Nick Hider's text replacement: player names in a piece of text swapped for what you see instead, as whole words only
 * ("Steve" in "Steve joined", never in "Steven"), any case. Pure Java; the version layer walks a chat message's text pieces
 * through it.
 */
public final class NameMask {
	private NameMask() {
	}

	/** {@code text} with every whole-word name (keys, any case) replaced by its value; the same string when none occur. */
	public static String apply(String text, Map<String, String> names) {
		if (text.isEmpty() || names.isEmpty()) {
			return text;
		}
		StringBuilder out = null;
		int i = 0;
		int copied = 0;
		while (i < text.length()) {
			if (!isWordChar(text.charAt(i)) || i > 0 && isWordChar(text.charAt(i - 1))) {
				i++;
				continue;
			}
			int end = i;
			while (end < text.length() && isWordChar(text.charAt(end))) {
				end++;
			}
			String replacement = null;
			String word = text.substring(i, end);
			for (Map.Entry<String, String> e : names.entrySet()) {
				if (e.getKey().equalsIgnoreCase(word)) {
					replacement = e.getValue();
					break;
				}
			}
			if (replacement != null) {
				if (out == null) {
					out = new StringBuilder(text.length() + 8);
				}
				out.append(text, copied, i).append(replacement);
				copied = end;
			}
			i = end;
		}
		if (out == null) {
			return text;
		}
		return out.append(text, copied, text.length()).toString();
	}

	/** Minecraft names are letters, digits and underscores. */
	static boolean isWordChar(char c) {
		return Character.isLetterOrDigit(c) || c == '_';
	}
}
