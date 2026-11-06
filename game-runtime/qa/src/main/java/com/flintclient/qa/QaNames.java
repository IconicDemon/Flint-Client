package com.flintclient.qa;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minecraft's names as the tour writes them (Mojang's) for the game it runs in. 26.x keeps those names, so this is the
 * identity there; 1.21.x runs under intermediary names, and its QA build bundles the translation (flintqa-names.tsv, from
 * Loom's mappings: every class, and the members of the classes the tour reaches by reflection).
 */
final class QaNames {
	/** A runtime class name (binary, dots) to Mojang's. */
	private static final Map<String, String> CLASSES = new HashMap<>();
	/** "runtime owner.Mojang name" to the member's runtime names (overloads may differ). */
	private static final Map<String, List<String>> MEMBERS = new HashMap<>();

	static {
		try (InputStream in = QaNames.class.getResourceAsStream("/flintqa-names.tsv")) {
			if (in != null) {
				BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
				for (String line; (line = reader.readLine()) != null;) {
					String[] p = line.split("\t");
					if (p[0].equals("c")) {
						CLASSES.put(p[1], p[2]);
					} else {
						MEMBERS.computeIfAbsent(p[1] + "." + p[2], k -> new ArrayList<>()).add(p[3]);
					}
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private QaNames() {
	}

	/** The class's simple name as Mojang's names have it ("TitleScreen", not "class_442"). */
	static String simpleName(Class<?> c) {
		String named = CLASSES.get(c.getName());
		return named == null ? c.getSimpleName() : named.substring(Math.max(named.lastIndexOf('.'), named.lastIndexOf('$')) + 1);
	}

	/** A field of {@code owner} by its Mojang name, made accessible. */
	static Field field(Class<?> owner, String name) throws NoSuchFieldException {
		for (String n : names(owner, name)) {
			try {
				Field f = owner.getDeclaredField(n);
				f.setAccessible(true);
				return f;
			} catch (NoSuchFieldException e) {
				// another name
			}
		}
		throw new NoSuchFieldException(owner.getName() + "." + name);
	}

	/** A method of {@code owner} (its own, or a public one it inherits) by its Mojang name, made accessible. */
	static Method method(Class<?> owner, String name, Class<?>... params) throws NoSuchMethodException {
		for (String n : names(owner, name)) {
			try {
				Method m = owner.getDeclaredMethod(n, params);
				m.setAccessible(true);
				return m;
			} catch (NoSuchMethodException e) {
				try {
					return owner.getMethod(n, params);
				} catch (NoSuchMethodException e2) {
					// another name
				}
			}
		}
		throw new NoSuchMethodException(owner.getName() + "." + name);
	}

	private static List<String> names(Class<?> owner, String name) {
		List<String> out = new ArrayList<>(MEMBERS.getOrDefault(owner.getName() + "." + name, List.of()));
		out.add(name);
		return out;
	}
}
