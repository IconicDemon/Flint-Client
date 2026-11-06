package com.flintclient.client.ui;

import com.flintclient.client.FlintClient;
import com.flintclient.client.FlintPreLaunch;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Whether Flint's screens and widgets still fit the running Minecraft. On the version a jar was built for they always do. On a
 * newer one (a 26.4 the launcher gave the 26.3 runtime) Minecraft may have reworked the classes they extend, and a screen or
 * widget that no longer fits would crash the game the moment Minecraft draws it: a method Minecraft made abstract that Flint
 * doesn't implement, or a Flint method meant to replace Minecraft's whose Minecraft counterpart now takes other arguments
 * (so Minecraft never calls it). Such a class is refused and the caller shows Minecraft's own screen instead. Checked once
 * per class.
 */
public final class UiFit {
	private static final String FLINT = "com.flintclient.";
	private static final ClassValue<Boolean> FITS = new ClassValue<>() {
		@Override
		protected Boolean computeValue(Class<?> type) {
			return check(type);
		}
	};

	private UiFit() {
	}

	/** Whether every class given fits this Minecraft (always true on the version the jar was built for). */
	public static boolean fits(Class<?>... types) {
		if (!FlintPreLaunch.untested()) {
			return true;
		}
		for (Class<?> type : types) {
			if (!FITS.get(type)) {
				return false;
			}
		}
		return true;
	}

	private static boolean check(Class<?> type) {
		try {
			String problem = problem(type);
			if (problem == null) {
				return true;
			}
			FlintClient.LOGGER.warn("Flint: {} doesn't fit this Minecraft ({}); Minecraft's own screen is shown instead", type.getSimpleName(), problem);
		} catch (RuntimeException | LinkageError e) {
			FlintClient.LOGGER.warn("Flint: {} doesn't fit this Minecraft ({}); Minecraft's own screen is shown instead", type.getSimpleName(), e.toString());
		}
		return false;
	}

	private static String problem(Class<?> type) {
		List<Class<?>> chain = new ArrayList<>();
		for (Class<?> c = type; c != null; c = c.getSuperclass()) {
			chain.add(c);
		}
		List<Class<?>> interfaces = interfaces(chain);

		// A method Minecraft declares abstract that nothing below implements.
		for (Class<?> owner : concat(chain, interfaces)) {
			for (Method method : owner.getDeclaredMethods()) {
				if (Modifier.isAbstract(method.getModifiers()) && !implemented(method, chain, interfaces)) {
					return "nothing implements " + owner.getSimpleName() + "." + method.getName() + Arrays.toString(method.getParameterTypes());
				}
			}
		}

		// A Flint method named like one of Minecraft's (an override), where Minecraft's now takes other arguments.
		Set<String> minecraftNames = new HashSet<>();
		Set<String> minecraftSignatures = new HashSet<>();
		for (Class<?> c : concat(chain, interfaces)) {
			if (c.getName().startsWith(FLINT)) {
				continue;
			}
			for (Method method : c.getDeclaredMethods()) {
				if (overridable(method)) {
					minecraftNames.add(method.getName());
					minecraftSignatures.add(signature(method));
				}
			}
		}
		for (Class<?> c : chain) {
			if (!c.getName().startsWith(FLINT)) {
				continue;
			}
			for (Method method : c.getDeclaredMethods()) {
				if (overridable(method) && !method.isSynthetic() && minecraftNames.contains(method.getName())
					&& !minecraftSignatures.contains(signature(method))) {
					return c.getSimpleName() + "." + method.getName() + Arrays.toString(method.getParameterTypes()) + " overrides nothing";
				}
			}
		}
		return null;
	}

	private static boolean implemented(Method abstractMethod, List<Class<?>> chain, List<Class<?>> interfaces) {
		for (Class<?> c : chain) {
			Method found = declared(c, abstractMethod);
			if (found != null) {
				return !Modifier.isAbstract(found.getModifiers());
			}
		}
		for (Class<?> c : interfaces) {
			Method found = declared(c, abstractMethod);
			if (found != null && found.isDefault()) {
				return true;
			}
		}
		return false;
	}

	private static Method declared(Class<?> c, Method like) {
		try {
			return c.getDeclaredMethod(like.getName(), like.getParameterTypes());
		} catch (NoSuchMethodException e) {
			return null;
		}
	}

	private static boolean overridable(Method method) {
		int modifiers = method.getModifiers();
		return !Modifier.isStatic(modifiers) && !Modifier.isPrivate(modifiers) && !method.isBridge();
	}

	private static String signature(Method method) {
		return method.getName() + Arrays.toString(method.getParameterTypes());
	}

	private static List<Class<?>> interfaces(List<Class<?>> chain) {
		List<Class<?>> out = new ArrayList<>();
		Deque<Class<?>> queue = new ArrayDeque<>();
		for (Class<?> c : chain) {
			queue.addAll(Arrays.asList(c.getInterfaces()));
		}
		Set<Class<?>> seen = new HashSet<>();
		while (!queue.isEmpty()) {
			Class<?> next = queue.poll();
			if (seen.add(next)) {
				out.add(next);
				queue.addAll(Arrays.asList(next.getInterfaces()));
			}
		}
		return out;
	}

	private static List<Class<?>> concat(List<Class<?>> a, List<Class<?>> b) {
		List<Class<?>> out = new ArrayList<>(a);
		out.addAll(b);
		return out;
	}
}
