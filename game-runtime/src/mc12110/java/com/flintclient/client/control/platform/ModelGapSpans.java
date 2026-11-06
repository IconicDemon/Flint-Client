/*
 * Adapted from Model Gap Fix by MehVahdJukaar (https://github.com/MehVahdJukaar/modelfix-multi), GPL-3.0: the licence text is
 * in META-INF/licenses/GPL-3.0-ModelGapFix.txt. This file is covered by the GPL-3.0.
 */
package com.flintclient.client.control.platform;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;

/** ItemModelGenerator's Span and SpanFacing are package-private: reached through method handles, looked up once by their
 * intermediary names (the game's at run time, Mojang's in development). */
public final class ModelGapSpans {
	public static final MethodHandle IS_HORIZONTAL;
	public static final MethodHandle FACING;
	public static final MethodHandle ANCHOR;
	public static final MethodHandle MAX;
	public static final MethodHandle EXPAND;
	public static final MethodHandle NEW;
	private static final String SPAN = "net.minecraft.class_801$class_802";
	private static final String FACING_CLASS = "net.minecraft.class_801$class_803";

	static {
		try {
			MappingResolver names = FabricLoader.getInstance().getMappingResolver();
			Class<?> span = Class.forName(names.mapClassName("intermediary", SPAN));
			Class<?> facing = Class.forName(names.mapClassName("intermediary", FACING_CLASS));
			MethodHandles.Lookup lookup = MethodHandles.lookup();
			IS_HORIZONTAL = method(lookup, facing, names.mapMethodName("intermediary", FACING_CLASS, "method_3491", "()Z"))
				.asType(MethodType.methodType(boolean.class, Object.class));
			FACING = method(lookup, span, names.mapMethodName("intermediary", SPAN, "method_3484", "()Lnet/minecraft/class_801$class_803;"))
				.asType(MethodType.methodType(Object.class, Object.class));
			ANCHOR = method(lookup, span, names.mapMethodName("intermediary", SPAN, "method_3486", "()I"))
				.asType(MethodType.methodType(int.class, Object.class));
			MAX = method(lookup, span, names.mapMethodName("intermediary", SPAN, "method_3485", "()I"))
				.asType(MethodType.methodType(int.class, Object.class));
			EXPAND = method(lookup, span, names.mapMethodName("intermediary", SPAN, "method_3483", "(I)V"), int.class)
				.asType(MethodType.methodType(void.class, Object.class, int.class));
			java.lang.reflect.Constructor<?> make = span.getDeclaredConstructor(facing, int.class, int.class);
			make.setAccessible(true);
			NEW = lookup.unreflectConstructor(make).asType(MethodType.methodType(Object.class, Object.class, int.class, int.class));
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private static MethodHandle method(MethodHandles.Lookup lookup, Class<?> owner, String name, Class<?>... args) throws ReflectiveOperationException {
		java.lang.reflect.Method m = owner.getDeclaredMethod(name, args);
		m.setAccessible(true);
		return lookup.unreflect(m);
	}

	private ModelGapSpans() {
	}
}
