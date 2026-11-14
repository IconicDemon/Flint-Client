package com.flintclient.client.mc;

/**
 * Newer Minecraft's chat text, as Flint's main menu uses it on Minecraft 1.8.9 (src/mc189/shared-sources.rules maps the 26.x
 * GUI types the menu's shared files name onto this package): a translation key with its arguments, or a literal, read as a
 * plain string. Keys are Flint's own (its en_us.json, {@link Lang}) or Minecraft's (1.8.9's own translation where it has the
 * key, see {@link Lang#vanilla}). Render thread.
 */
public final class Component {
	private final String key;
	private final Object[] args;
	private final boolean literal;
	private String text;

	private Component(String key, Object[] args, boolean literal) {
		this.key = key;
		this.args = args;
		this.literal = literal;
	}

	public static Component translatable(String key, Object... args) {
		return new Component(key, args, false);
	}

	public static Component literal(String text) {
		return new Component(text, new Object[0], true);
	}

	/** The text as the player reads it (worked out once). */
	public String getString() {
		String out = text;
		if (out == null) {
			out = literal ? key : format(Lang.get(key), args);
			text = out;
		}
		return out;
	}

	/** A translation's %s and %1$s places filled in, as newer Minecraft fills them (a missing argument leaves the place as it is). */
	static String format(String pattern, Object[] args) {
		if (args.length == 0 || pattern.indexOf('%') < 0) {
			return pattern.replace("%%", "%");
		}
		StringBuilder out = new StringBuilder(pattern.length() + 16);
		int next = 0;
		for (int i = 0; i < pattern.length(); i++) {
			char c = pattern.charAt(i);
			if (c != '%' || i + 1 >= pattern.length()) {
				out.append(c);
				continue;
			}
			char d = pattern.charAt(i + 1);
			if (d == '%') {
				out.append('%');
				i++;
			} else if (d == 's') {
				out.append(arg(args, next++));
				i++;
			} else if (Character.isDigit(d)) {
				int end = i + 1;
				while (end < pattern.length() && Character.isDigit(pattern.charAt(end))) {
					end++;
				}
				if (end + 1 < pattern.length() && pattern.charAt(end) == '$' && pattern.charAt(end + 1) == 's') {
					out.append(arg(args, Integer.parseInt(pattern.substring(i + 1, end)) - 1));
					i = end + 1;
				} else {
					out.append(c);
				}
			} else {
				out.append(c);
			}
		}
		return out.toString();
	}

	private static String arg(Object[] args, int i) {
		if (i < 0 || i >= args.length) {
			return "";
		}
		Object a = args[i];
		return a instanceof Component component ? component.getString() : String.valueOf(a);
	}

	@Override
	public String toString() {
		return getString();
	}

	/** For logging: the key (or the literal text). */
	public String key() {
		return key;
	}
}
