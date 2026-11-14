package com.flintclient.client.cosmetics;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The part of Molang a cosmetic's animation may use (exactly the subset the Store agreed, cosmetics contract), compiled once
 * into a tree that evaluates without allocating: numbers, {@code + - * /}, unary minus, parentheses, {@code query.anim_time} /
 * {@code q.anim_time} (seconds into the animation), {@code query.life_time} / {@code q.life_time} (seconds the model has been
 * shown), {@code math.pi}, {@code math.sin} and {@code math.cos} (degrees, as Bedrock), {@code math.abs}, {@code math.clamp},
 * {@code math.lerp}; at most {@link #MAX_LENGTH} characters. Anything else (variables, comparisons, other functions) is refused: {@link #compile} answers null and the caller ignores that
 * channel. Pure Java.
 */
public final class Molang {
	/** The longest expression read: Blockbench's expressions are a few dozen characters. */
	public static final int MAX_LENGTH = 128;
	/** Nesting deeper than this is refused (a hostile file can't overflow the stack). */
	static final int MAX_DEPTH = 32;

	/** A compiled expression; {@code time}: seconds into the animation, {@code life}: seconds the model has been shown. */
	public interface Expr {
		double eval(double time, double life);

		/** Whether it is a number (it never changes). */
		default boolean constant() {
			return false;
		}
	}

	/** A number. */
	public record Const(double value) implements Expr {
		@Override
		public double eval(double time, double life) {
			return value;
		}

		@Override
		public boolean constant() {
			return true;
		}
	}

	private record Time() implements Expr {
		@Override
		public double eval(double time, double life) {
			return time;
		}
	}

	private record Life() implements Expr {
		@Override
		public double eval(double time, double life) {
			return life;
		}
	}

	private record Neg(Expr a) implements Expr {
		@Override
		public double eval(double time, double life) {
			return -a.eval(time, life);
		}
	}

	private record Bin(char op, Expr a, Expr b) implements Expr {
		@Override
		public double eval(double time, double life) {
			double x = a.eval(time, life);
			double y = b.eval(time, life);
			return switch (op) {
				case '+' -> x + y;
				case '-' -> x - y;
				case '*' -> x * y;
				default -> y == 0 ? 0 : x / y;
			};
		}
	}

	private record Call(int fn, Expr[] args) implements Expr {
		@Override
		public double eval(double time, double life) {
			double a = args.length > 0 ? args[0].eval(time, life) : 0;
			double b = args.length > 1 ? args[1].eval(time, life) : 0;
			double c = args.length > 2 ? args[2].eval(time, life) : 0;
			return switch (fn) {
				case SIN -> Math.sin(Math.toRadians(a));
				case COS -> Math.cos(Math.toRadians(a));
				case ABS -> Math.abs(a);
				case FLOOR -> Math.floor(a);
				case CEIL -> Math.ceil(a);
				case ROUND -> Math.round(a);
				case TRUNC -> a < 0 ? Math.ceil(a) : Math.floor(a);
				case SQRT -> a < 0 ? 0 : Math.sqrt(a);
				case EXP -> Math.exp(Math.min(a, 64));
				case LN -> a <= 0 ? 0 : Math.log(a);
				case POW -> finite(Math.pow(a, b));
				case MIN -> Math.min(a, b);
				case MAX -> Math.max(a, b);
				case CLAMP -> Math.max(b, Math.min(c, a));
				case LERP -> a + (b - a) * c;
				case MOD -> b == 0 ? 0 : a % b;
				default -> 0;
			};
		}
	}

	private static final int SIN = 0;
	private static final int COS = 1;
	private static final int ABS = 2;
	private static final int FLOOR = 3;
	private static final int CEIL = 4;
	private static final int ROUND = 5;
	private static final int TRUNC = 6;
	private static final int SQRT = 7;
	private static final int EXP = 8;
	private static final int LN = 9;
	private static final int POW = 10;
	private static final int MIN = 11;
	private static final int MAX = 12;
	private static final int CLAMP = 13;
	private static final int LERP = 14;
	private static final int MOD = 15;
	/** The functions the Store allows, by index (the others above are kept for a later contract, not reachable). */
	private static final String[] NAMES = {"sin", "cos", "abs", null, null, null, null, null, null, null, null, null, null, "clamp", "lerp", null};
	private static final int[] ARITY = {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 3, 3, 2};

	private static double finite(double v) {
		return Double.isFinite(v) ? v : 0;
	}

	private final String text;
	private int at;

	private Molang(String text) {
		this.text = text;
	}

	/** {@code source} compiled, or null when it uses anything this subset doesn't have (or is too long, or malformed). */
	public static @Nullable Expr compile(String source) {
		if (source.length() > MAX_LENGTH) {
			return null;
		}
		String s = source.trim().toLowerCase(Locale.ROOT);
		// A trailing ";" (Blockbench writes one for complex expressions) ends the only statement.
		if (s.endsWith(";")) {
			s = s.substring(0, s.length() - 1).trim();
		}
		if (s.isEmpty()) {
			return new Const(0);
		}
		Molang parser = new Molang(s);
		try {
			Expr e = parser.sum(0);
			parser.space();
			if (parser.at != s.length()) {
				return null;
			}
			return fold(e);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** Folds a constant expression into one number. */
	private static Expr fold(Expr e) {
		if (e.constant() || e instanceof Time || e instanceof Life) {
			return e;
		}
		if (constantTree(e)) {
			return new Const(finite(e.eval(0, 0)));
		}
		return e;
	}

	private static boolean constantTree(Expr e) {
		if (e instanceof Const) {
			return true;
		}
		if (e instanceof Neg n) {
			return constantTree(n.a());
		}
		if (e instanceof Bin b) {
			return constantTree(b.a()) && constantTree(b.b());
		}
		if (e instanceof Call c) {
			for (Expr a : c.args()) {
				if (!constantTree(a)) {
					return false;
				}
			}
			return true;
		}
		return false;
	}

	private void space() {
		while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
			at++;
		}
	}

	private boolean eat(char c) {
		space();
		if (at < text.length() && text.charAt(at) == c) {
			at++;
			return true;
		}
		return false;
	}

	private static IllegalArgumentException bad() {
		return new IllegalArgumentException();
	}

	private Expr sum(int depth) {
		if (depth > MAX_DEPTH) {
			throw bad();
		}
		Expr e = product(depth);
		while (true) {
			if (eat('+')) {
				e = new Bin('+', e, product(depth));
			} else if (eat('-')) {
				e = new Bin('-', e, product(depth));
			} else {
				return e;
			}
		}
	}

	private Expr product(int depth) {
		Expr e = unary(depth);
		while (true) {
			if (eat('*')) {
				e = new Bin('*', e, unary(depth));
			} else if (eat('/')) {
				e = new Bin('/', e, unary(depth));
			} else {
				return e;
			}
		}
	}

	private Expr unary(int depth) {
		if (depth > MAX_DEPTH) {
			throw bad();
		}
		if (eat('-')) {
			return new Neg(unary(depth + 1));
		}
		if (eat('+')) {
			return unary(depth + 1);
		}
		return atom(depth);
	}

	private Expr atom(int depth) {
		space();
		if (at >= text.length()) {
			throw bad();
		}
		char c = text.charAt(at);
		if (c == '(') {
			at++;
			Expr e = sum(depth + 1);
			if (!eat(')')) {
				throw bad();
			}
			return e;
		}
		if (Character.isDigit(c) || c == '.') {
			int start = at;
			while (at < text.length() && (Character.isDigit(text.charAt(at)) || text.charAt(at) == '.')) {
				at++;
			}
			// An exponent (1e-3) and Molang's float suffix (1.5f).
			if (at < text.length() && text.charAt(at) == 'e') {
				int mark = at;
				at++;
				if (at < text.length() && (text.charAt(at) == '-' || text.charAt(at) == '+')) {
					at++;
				}
				if (at < text.length() && Character.isDigit(text.charAt(at))) {
					while (at < text.length() && Character.isDigit(text.charAt(at))) {
						at++;
					}
				} else {
					at = mark;
				}
			}
			String number = text.substring(start, at);
			if (at < text.length() && text.charAt(at) == 'f') {
				at++;
			}
			try {
				double v = Double.parseDouble(number);
				if (!Double.isFinite(v)) {
					throw bad();
				}
				return new Const(v);
			} catch (NumberFormatException e) {
				throw bad();
			}
		}
		if (Character.isLetter(c) || c == '_') {
			int start = at;
			while (at < text.length() && (Character.isLetterOrDigit(text.charAt(at)) || text.charAt(at) == '_' || text.charAt(at) == '.')) {
				at++;
			}
			String name = text.substring(start, at);
			switch (name) {
				case "query.anim_time", "q.anim_time" -> {
					return new Time();
				}
				case "query.life_time", "q.life_time" -> {
					return new Life();
				}
				case "math.pi" -> {
					return new Const(Math.PI);
				}
				default -> {
				}
			}
			if (!name.startsWith("math.")) {
				throw bad();
			}
			String fn = name.substring(5);
			int index = -1;
			for (int i = 0; i < NAMES.length; i++) {
				if (fn.equals(NAMES[i])) {
					index = i;
					break;
				}
			}
			if (index < 0 || !eat('(')) {
				throw bad();
			}
			Expr[] args = new Expr[ARITY[index]];
			for (int i = 0; i < args.length; i++) {
				if (i > 0 && !eat(',')) {
					throw bad();
				}
				args[i] = sum(depth + 1);
			}
			if (!eat(')')) {
				throw bad();
			}
			return new Call(index, args);
		}
		throw bad();
	}
}
