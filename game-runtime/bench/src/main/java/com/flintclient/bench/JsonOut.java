package com.flintclient.bench;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.Map;

/**
 * A minimal streaming JSON writer: the result holds hundreds of thousands of numbers, so it is written
 * straight to the output instead of building a tree first. Commas are tracked per nesting level.
 * Non-finite doubles are written as {@code null} (JSON has no NaN).
 */
final class JsonOut {
	private static final int MAX_DEPTH = 32;

	private final Appendable out;
	// hasValue[d]: something was already written at nesting level d, so the next value needs a comma.
	private final boolean[] hasValue = new boolean[MAX_DEPTH];
	private int depth;
	private boolean afterKey;

	JsonOut(Appendable out) {
		this.out = out;
	}

	JsonOut beginObject() {
		return open('{');
	}

	JsonOut endObject() {
		return close('}');
	}

	JsonOut beginArray() {
		return open('[');
	}

	JsonOut endArray() {
		return close(']');
	}

	JsonOut key(String name) {
		separate();
		string(name);
		raw(":");
		afterKey = true;
		return this;
	}

	JsonOut value(long value) {
		separate();
		raw(Long.toString(value));
		return this;
	}

	JsonOut value(double value) {
		separate();
		raw(Double.isFinite(value) ? Double.toString(value) : "null");
		return this;
	}

	JsonOut value(boolean value) {
		separate();
		raw(value ? "true" : "false");
		return this;
	}

	JsonOut value(String value) {
		separate();
		if (value == null) {
			raw("null");
		} else {
			string(value);
		}
		return this;
	}

	JsonOut nullValue() {
		separate();
		raw("null");
		return this;
	}

	/** Writes maps, collections, strings, numbers, booleans and null; anything else as its toString(). */
	JsonOut value(Object value) {
		if (value == null) {
			return nullValue();
		} else if (value instanceof Map<?, ?> map) {
			beginObject();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				key(String.valueOf(entry.getKey())).value(entry.getValue());
			}
			return endObject();
		} else if (value instanceof Collection<?> items) {
			beginArray();
			for (Object item : items) {
				value(item);
			}
			return endArray();
		} else if (value instanceof Boolean flag) {
			return value(flag.booleanValue());
		} else if (value instanceof Double || value instanceof Float) {
			return value(((Number) value).doubleValue());
		} else if (value instanceof Number number) {
			return value(number.longValue());
		}
		return value(value.toString());
	}

	private JsonOut open(char bracket) {
		separate();
		if (depth + 1 >= MAX_DEPTH) {
			throw new IllegalStateException("JSON nested too deeply");
		}
		raw(String.valueOf(bracket));
		hasValue[++depth] = false;
		return this;
	}

	private JsonOut close(char bracket) {
		if (depth == 0) {
			throw new IllegalStateException("Unbalanced JSON " + bracket);
		}
		depth--;
		raw(String.valueOf(bracket));
		return this;
	}

	// Emits the comma before a value unless it is the first at its level or follows a key.
	private void separate() {
		if (afterKey) {
			afterKey = false;
			return;
		}
		if (hasValue[depth]) {
			raw(",");
		}
		hasValue[depth] = true;
	}

	private void string(String text) {
		StringBuilder escaped = new StringBuilder(text.length() + 2).append('"');
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			switch (c) {
				case '"' -> escaped.append("\\\"");
				case '\\' -> escaped.append("\\\\");
				case '\n' -> escaped.append("\\n");
				case '\r' -> escaped.append("\\r");
				case '\t' -> escaped.append("\\t");
				default -> {
					// Surrogates are escaped too: the UTF-8 file writer throws on a lone one (say, in an
					// exception message), which would lose the whole result. JSON readers rejoin pairs.
					if (c < 0x20 || Character.isSurrogate(c)) {
						escaped.append(String.format("\\u%04x", (int) c));
					} else {
						escaped.append(c);
					}
				}
			}
		}
		raw(escaped.append('"').toString());
	}

	private void raw(String text) {
		try {
			out.append(text);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
