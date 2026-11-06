package com.flintclient.client.ui.widget;

import com.flintclient.client.FlintClient;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.client.gui.components.AbstractWidget;

/**
 * Shows or hides a Minecraft widget from outside its class. Up to 26.3 a widget's {@code visible} field is public; 26.4 made
 * it protected behind {@code isVisible()}/{@code setVisible(boolean)}. Flint reads and writes the field while it is public
 * and, the first time the JVM refuses (a newer Minecraft), switches to the methods for the rest of the run. Any thread.
 */
public final class WidgetVisibility {
	private static volatile boolean viaMethods;
	private static volatile boolean warned;
	private static MethodHandle setter;
	private static MethodHandle getter;

	private WidgetVisibility() {
	}

	public static void set(AbstractWidget widget, boolean visible) {
		if (!viaMethods) {
			try {
				widget.visible = visible;
				return;
			} catch (IllegalAccessError e) {
				viaMethods = true;
			}
		}
		try {
			setter().invokeExact(widget, visible);
		} catch (Throwable e) {
			warn(e);
		}
	}

	public static boolean get(AbstractWidget widget) {
		if (!viaMethods) {
			try {
				return widget.visible;
			} catch (IllegalAccessError e) {
				viaMethods = true;
			}
		}
		try {
			return (boolean) getter().invokeExact(widget);
		} catch (Throwable e) {
			warn(e);
			return true;
		}
	}

	private static MethodHandle setter() throws ReflectiveOperationException {
		MethodHandle handle = setter;
		if (handle == null) {
			handle = MethodHandles.publicLookup().findVirtual(AbstractWidget.class, "setVisible", MethodType.methodType(void.class, boolean.class));
			setter = handle;
		}
		return handle;
	}

	private static MethodHandle getter() throws ReflectiveOperationException {
		MethodHandle handle = getter;
		if (handle == null) {
			handle = MethodHandles.publicLookup().findVirtual(AbstractWidget.class, "isVisible", MethodType.methodType(boolean.class));
			getter = handle;
		}
		return handle;
	}

	private static void warn(Throwable e) {
		if (!warned) {
			warned = true;
			FlintClient.LOGGER.warn("Flint: can't show or hide a Minecraft widget on this version ({})", e.toString());
		}
	}
}
