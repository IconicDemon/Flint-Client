package com.flintclient.client.control.platform.ambient;

/** Ambient Sounds' "Silent dimensions" patterns: a dimension id containing what the player typed, {@code *} for any run. */
final class AmbientGlob {
	private AmbientGlob() {
	}

	/**
	 * Whether {@code text} contains {@code glob}, where {@code *} stands for any run of characters and everything else is
	 * itself: "Silent dimensions" is what the player typed, so a bracket or a brace is a letter there, never a pattern that
	 * fails to compile (it was a regular expression, and a "{" in it threw every tick). Allocation-free.
	 */
	static boolean contains(String text, String glob) {
		int from = 0;
		int start = 0;
		while (start <= glob.length()) {
			int star = glob.indexOf('*', start);
			int end = star < 0 ? glob.length() : star;
			int length = end - start;
			if (length > 0) {
				int found = -1;
				for (int i = from; i + length <= text.length(); i++) {
					if (text.regionMatches(i, glob, start, length)) {
						found = i;
						break;
					}
				}
				if (found < 0) {
					return false;
				}
				from = found + length;
			}
			if (star < 0) {
				return true;
			}
			start = star + 1;
		}
		return true;
	}

}
