package com.flintclient.client.control.feature;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The chat calculator's arithmetic: {@code + - * / %}, powers ({@code ^}), brackets, {@code pi} and {@code e}, your
 * position ({@code x y z}) and a few functions (square root, logarithms, trigonometry in degrees). A line typed as
 * {@code =2+2*3} is worked out here; commas separate several sums. Pure Java.
 */
public final class ChatCalculator {
	/** Function and constant names, for completing one as it is typed. */
	private static final List<String> NAMES = List.of("acos(", "asin(", "atan(", "cos(", "e", "ln(", "log(", "pi", "sin(", "sqrt(", "tan(", "x",
		"y", "z");

	private ChatCalculator() {
	}

	/** Where you stand, for {@code x}, {@code y} and {@code z}. */
	public record Position(double x, double y, double z) {
		public static final Position ORIGIN = new Position(0, 0, 0);
	}

	/** One sum and its answer, or why it has none ({@code error} not null). */
	public record Result(String sum, double value, @Nullable String error) {
	}

	/** Whether {@code line} asks for the calculator ("=" and something after it). */
	public static boolean isSum(String line) {
		return line.length() > 1 && line.charAt(0) == '=' && !line.substring(1).isBlank();
	}

	/**
	 * Whether a line that starts with "=" is a chat message after all, to be sent as typed: "=)", "=D", "==>" (no sum, and
	 * not a digit in it). A line with a digit stays the calculator's, so a sum with a typo ("=2+") shows its error instead of
	 * going out to everyone.
	 */
	public static boolean chatAfterAll(String line, List<Result> results) {
		if (results.isEmpty() || results.get(0).error() == null) {
			return results.isEmpty();
		}
		for (int i = 0; i < line.length(); i++) {
			if (Character.isDigit(line.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	/** Each comma-separated sum after the "=" of {@code line}, worked out; stops after the first that fails. */
	public static List<Result> solve(String line, Position at) {
		String body = line.startsWith("=") ? line.substring(1) : line;
		java.util.ArrayList<Result> out = new java.util.ArrayList<>();
		for (String sum : body.split(",", -1)) {
			String s = sum.strip();
			if (s.isEmpty()) {
				continue;
			}
			try {
				double value = new Parser(s.toLowerCase(Locale.ROOT), at).whole();
				if (!Double.isFinite(value)) {
					out.add(new Result(s, value, "No finite answer"));
					break;
				}
				out.add(new Result(s, value, null));
			} catch (IllegalArgumentException e) {
				out.add(new Result(s, Double.NaN, e.getMessage()));
				break;
			}
		}
		return out;
	}

	/** {@code value} with at most {@code places} decimals, without trailing zeros ("2.5", "4", "-0.333"). */
	public static String format(double value, int places) {
		BigDecimal rounded = BigDecimal.valueOf(value).setScale(Math.max(0, places), RoundingMode.HALF_UP).stripTrailingZeros();
		return rounded.signum() == 0 ? "0" : rounded.toPlainString();
	}

	/** The rest of a function or constant name being typed at the end of {@code line} ("=sq" gives "rt("), else "". */
	public static String completion(String line) {
		int end = line.length();
		int start = end;
		while (start > 0 && Character.isLetter(line.charAt(start - 1))) {
			start--;
		}
		if (start == end || start == 0) {
			return "";
		}
		String typed = line.substring(start, end).toLowerCase(Locale.ROOT);
		for (String name : NAMES) {
			if (name.startsWith(typed) && !name.equals(typed)) {
				return name.substring(typed.length());
			}
		}
		return "";
	}

	/** A recursive-descent reader of one sum: sum := term (± term)*; term := power (×÷% power)*; power := unary (^ power)?. */
	private static final class Parser {
		private final String text;
		private final Position at;
		private int pos;

		Parser(String text, Position at) {
			this.text = text;
			this.at = at;
		}

		double whole() {
			double value = sum();
			skipSpaces();
			if (pos < text.length()) {
				throw fail("Unexpected " + text.charAt(pos));
			}
			return value;
		}

		private double sum() {
			double value = term();
			while (true) {
				if (eat('+')) {
					value += term();
				} else if (eat('-')) {
					value -= term();
				} else {
					return value;
				}
			}
		}

		private double term() {
			double value = unary();
			while (true) {
				if (eat('*')) {
					value *= unary();
				} else if (eat('/')) {
					value /= unary();
				} else if (eat('%')) {
					value %= unary();
				} else {
					return value;
				}
			}
		}

		/** A sign binds looser than a power: -2^2 is -4, 2^-1 is 0.5. */
		private double unary() {
			if (eat('+')) {
				return unary();
			}
			if (eat('-')) {
				return -unary();
			}
			return power();
		}

		private double power() {
			double base = atom();
			if (eat('^')) {
				// Right to left, as written: 2^3^2 is 2^9.
				return Math.pow(base, unary());
			}
			return base;
		}

		private double bracket() {
			double value = sum();
			if (!eat(')')) {
				throw fail("A bracket isn't closed");
			}
			return value;
		}

		private double atom() {
			skipSpaces();
			if (eat('(')) {
				return bracket();
			}
			int start = pos;
			if (pos < text.length() && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '.')) {
				while (pos < text.length() && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '.')) {
					pos++;
				}
				try {
					return Double.parseDouble(text.substring(start, pos));
				} catch (NumberFormatException e) {
					throw fail("Not a number: " + text.substring(start, pos));
				}
			}
			while (pos < text.length() && Character.isLetter(text.charAt(pos))) {
				pos++;
			}
			String name = text.substring(start, pos);
			switch (name) {
				case "" -> throw fail(pos < text.length() ? "Unexpected " + text.charAt(pos) : "The sum ends too soon");
				case "pi" -> {
					return Math.PI;
				}
				case "e" -> {
					return Math.E;
				}
				case "x" -> {
					return at.x();
				}
				case "y" -> {
					return at.y();
				}
				case "z" -> {
					return at.z();
				}
				default -> {
					if (!NAMES.contains(name + "(")) {
						throw fail("No function called " + name);
					}
					// sin(30)^2 squares the sine; sqrt 16 works without brackets too.
					double arg = eat('(') ? bracket() : unary();
					return switch (name) {
						case "sqrt" -> Math.sqrt(arg);
						case "log" -> Math.log10(arg);
						case "ln" -> Math.log(arg);
						case "sin" -> Math.sin(Math.toRadians(arg));
						case "cos" -> Math.cos(Math.toRadians(arg));
						case "tan" -> Math.tan(Math.toRadians(arg));
						case "asin" -> Math.toDegrees(Math.asin(arg));
						case "acos" -> Math.toDegrees(Math.acos(arg));
						case "atan" -> Math.toDegrees(Math.atan(arg));
						default -> throw fail("No function called " + name);
					};
				}
			}
		}

		private boolean eat(char c) {
			skipSpaces();
			if (pos < text.length() && text.charAt(pos) == c) {
				pos++;
				return true;
			}
			return false;
		}

		private void skipSpaces() {
			while (pos < text.length() && text.charAt(pos) == ' ') {
				pos++;
			}
		}

		private IllegalArgumentException fail(String why) {
			return new IllegalArgumentException(why);
		}
	}
}
