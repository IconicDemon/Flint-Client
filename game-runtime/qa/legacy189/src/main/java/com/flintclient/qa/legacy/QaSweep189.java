package com.flintclient.qa.legacy;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The crash sweep on Minecraft 1.8.9 (dev only), as the modern QA mod's QaSweep: {@code sweep title|mods [WxH]|pause|rshift|net|summary}
 * driven by Flint's shared registries (ModuleRegistry, the settings' own clamp), in 1.8.9's terms: Flint's screens are clicked by
 * their elements' names (QaHooks), vanilla's by their buttons; the window resized through LWJGL 2's Display. Java 8.
 */
public final class QaSweep189 {
	private static final Logger LOG = LogManager.getLogger("FlintQA");
	private static final Pattern DENY = Pattern.compile("\\b(quit|disconnect|realms|discord|link|website|browser|report|feedback|delete|remove|"
		+ "sign (in|out)|log (in|out)|account|join|connect|direct|play|create|re-create|lan|telemetry|copy|folder|open|reset|yes|proceed|confirm|"
		+ "buy|purchase|send|invite|accept|decline|block|unfriend|exit|leave|stop|restart|update|download|install|uninstall|import|export|backup|"
		+ "close)\\b", Pattern.CASE_INSENSITIVE);
	private static final Pattern DENY_SCREEN = Pattern.compile("Confirm|Link|Delete|Warning|Alert|Popup|Disconnected|Connect", Pattern.CASE_INSENSITIVE);
	private static final Pattern DENY_MODULE = Pattern.compile("^(discord)$");

	private interface Action {
		void run(Minecraft mc) throws Exception;
	}

	private static final class Task {
		final String context;
		final Action action;
		final int frames;

		Task(String context, int frames, Action action) {
			this.context = context;
			this.action = action;
			this.frames = frames;
		}
	}

	private static final Deque<Task> TASKS = new ArrayDeque<Task>();
	private static final Map<String, String> FAILS = new LinkedHashMap<String, String>();
	private static final List<String> SKIPPED = new ArrayList<String>();
	private static int swept;
	private static int faultsSeen;
	private static String phase;
	private static Task current;
	private static long waitUntilFrame;
	private static boolean startupSeen;
	private static int expectedFaults;

	private QaSweep189() {
	}

	public static boolean step(Minecraft mc, String[] c) throws Exception {
		String what = c.length > 1 ? c[1] : "mods";
		if (phase == null) {
			phase = what;
			TASKS.clear();
			if (!startupSeen) {
				startupSeen = true;
				faultsSeen = 0;
				collectFaults("before the sweep (start-up)");
			}
			faultsSeen = faultCount();
			FlintQa.log("sweep " + what + " starts");
			if (what.equals("title") || what.equals("pause")) {
				planButtons(what);
			} else if (what.equals("mods")) {
				planModules(c.length > 2 ? c[2] : "1920x1080", c.length > 3 ? java.util.Arrays.asList(c[3].split(",")) : null);
			} else if (what.equals("rshift")) {
				planMenu(mc);
			} else if (what.equals("net")) {
				planNet();
			} else if (what.equals("summary")) {
				summary();
				phase = null;
				return true;
			} else {
				throw new IllegalArgumentException("sweep " + what + "?");
			}
		}
		if (current != null) {
			if (LegacyFrames.frames < waitUntilFrame) {
				return false;
			}
			collectFaults(current.context);
			current = null;
		}
		Task next = TASKS.poll();
		if (next == null) {
			FlintQa.log("sweep " + phase + " done: swept=" + swept + " failures=" + FAILS.size());
			phase = null;
			return true;
		}
		current = next;
		swept++;
		LOG.info("QA sweep at " + next.context);
		try {
			next.action.run(mc);
		} catch (Throwable e) {
			fail(next.context, "threw " + e, e);
		}
		waitUntilFrame = LegacyFrames.frames + Math.max(1, next.frames);
		return false;
	}

	// ---------- modules and settings ----------

	private static void planModules(final String size, List<String> only) throws Exception {
		List<?> modules = (List<?>) call(cls("com.flintclient.client.control.model.ModuleRegistry"), "all");
		int half = modules.size() / 2;
		int n = 0;
		final List<String> ids = new ArrayList<String>();
		for (Object descriptor : modules) {
			String id = (String) call(descriptor, "id");
			String name = (String) call(descriptor, "name");
			boolean usable = (Boolean) call(call(descriptor, "status"), "usable");
			boolean runs = (Boolean) call(cls("com.flintclient.client.control.rshift.Catalog"), "runs", descriptor);
			if (!usable || !runs) {
				SKIPPED.add("module " + name + " (not on this line)");
				continue;
			}
			if (only != null && !only.contains(id)) {
				continue;
			}
			if (DENY_MODULE.matcher(id).matches()) {
				SKIPPED.add("module " + name + " (never switched on from a QA game)");
				continue;
			}
			if (n++ == half) {
				TASKS.add(new Task("window " + size + " (menu open)", 10, new Action() {
					public void run(Minecraft mc) throws Exception {
						resize(mc, size);
					}
				}));
			}
			ids.add(id);
			planModule(descriptor, id, name);
		}
		TASKS.add(new Task("every module on at once", 60, new Action() {
			public void run(Minecraft mc) throws Exception {
				mc.openScreen(null);
				for (String id : ids) {
					call(state(id), "setEnabled", true);
				}
			}
		}));
		TASKS.add(new Task("every module on at once: the Right Shift menu", 20, new Action() {
			public void run(Minecraft mc) throws Exception {
				openPage(mc, null);
			}
		}));
		TASKS.add(new Task("every module back to its default", 10, new Action() {
			public void run(Minecraft mc) throws Exception {
				mc.openScreen(null);
				for (String id : ids) {
					call(state(id), "reset");
				}
			}
		}));
	}

	private static void planModule(Object descriptor, final String id, String name) throws Exception {
		final Object[] saved = new Object[1];
		TASKS.add(new Task(name + ": on", 10, new Action() {
			public void run(Minecraft mc) throws Exception {
				mc.openScreen(null);
				Object state = state(id);
				saved[0] = call(state, "copy");
				call(state, "setEnabled", true);
			}
		}));
		TASKS.add(new Task(name + ": its page in the Right Shift menu", 6, new Action() {
			public void run(Minecraft mc) throws Exception {
				openPage(mc, id);
			}
		}));
		for (final Object setting : (List<?>) call(descriptor, "settings")) {
			String sid = (String) call(setting, "id");
			for (final Object value : values(setting)) {
				String shown = String.valueOf(value);
				if (shown.length() > 24) {
					shown = shown.substring(0, 21) + "...";
				}
				TASKS.add(new Task(name + " / " + sid + " = " + shown, 1, new Action() {
					public void run(Minecraft mc) throws Exception {
						call(state(id), "set", setting, value);
					}
				}));
			}
			// values() ends with the default: the setting is back to it before the next one.
		}
		TASKS.add(new Task(name + ": in the world with its page closed", 3, new Action() {
			public void run(Minecraft mc) {
				mc.openScreen(null);
			}
		}));
		TASKS.add(new Task(name + ": back as it was", 2, new Action() {
			public void run(Minecraft mc) throws Exception {
				if (saved[0] != null) {
					call(state(id), "copyFrom", saved[0]);
				}
			}
		}));
	}

	private static List<Object> values(Object setting) throws Exception {
		List<Object> out = new ArrayList<Object>();
		String kind = setting.getClass().getSimpleName();
		Object def = call(setting, "defaultValue");
		if (kind.equals("BoolSetting")) {
			out.add(!(Boolean) def);
		} else if (kind.equals("IntSetting")) {
			int min = (Integer) call(setting, "min");
			int max = (Integer) call(setting, "max");
			out.add(call(setting, "coerce", Integer.MIN_VALUE));
			out.add(call(setting, "coerce", Integer.MAX_VALUE));
			out.add(call(setting, "coerce", (int) (((long) min + max) / 2)));
		} else if (kind.equals("FloatSetting")) {
			float min = (Float) call(setting, "min");
			float max = (Float) call(setting, "max");
			out.add(call(setting, "coerce", -Float.MAX_VALUE));
			out.add(call(setting, "coerce", Float.MAX_VALUE));
			out.add(call(setting, "coerce", (min + max) / 2));
		} else if (kind.equals("ChoiceSetting") || kind.equals("ColorSetting")) {
			out.addAll((List<?>) call(setting, "options"));
		} else if (kind.equals("OptionSetting")) {
			int size = (Integer) call(setting, "size");
			for (int i = 0; i < size; i++) {
				out.add(i);
			}
		} else if (kind.equals("RgbSetting")) {
			out.add(0x000000);
			out.add(0xFFFFFF);
			out.add(0xFF7A1A);
			if ((Boolean) call(setting, "clearable")) {
				out.add(setting.getClass().getField("CLEAR").get(null));
			}
		} else if (kind.equals("TextSetting")) {
			int max = (Integer) call(setting, "maxLength");
			out.add("");
			out.add("QA §csweep ✓ 😀 %s {} \\n");
			StringBuilder w = new StringBuilder();
			for (int i = 0; i < Math.max(1, Math.min(max, 4096)); i++) {
				w.append('W');
			}
			out.add(w.toString());
		} else if (kind.equals("KeySetting") || kind.equals("IdSetting")) {
			out.add("");
		} else if (kind.equals("IdListSetting")) {
			out.add(Collections.emptyList());
		} else if (kind.equals("IdValuesSetting") || kind.equals("HeldItemsSetting")) {
			out.add(Collections.emptyMap());
		} else {
			SKIPPED.add("setting kind " + kind + " (only its default is swept)");
		}
		out.add(def);
		return out;
	}

	private static Object state(String id) throws Exception {
		Object layout = call(cls("com.flintclient.client.control.ControlState"), "layout");
		Object state = call(layout, "module", id);
		if (state == null) {
			throw new IllegalStateException("no module state for " + id);
		}
		return state;
	}

	private static void openPage(Minecraft mc, String moduleId) throws Exception {
		Class<?> menu = cls("com.flintclient.client.control.rshift.ModMenuScreen");
		if (moduleId != null) {
			call(menu, "showModule", moduleId);
		} else {
			call(menu, "showMods");
		}
		mc.openScreen(null);
		call(cls("com.flintclient.client.control.ControlCenter"), "requestOpen", (Object) null);
		Screen now = mc.screen;
		if (now == null || !menu.isInstance(now)) {
			throw new IllegalStateException("the Right Shift menu didn't open (now " + (now == null ? "none" : now.getClass().getSimpleName()) + ")");
		}
	}

	/** The window to W x H through LWJGL 2 (as dragging its edge), then Minecraft told, as its own resize check would. */
	private static void resize(Minecraft mc, String size) throws Exception {
		String[] wh = size.split("x");
		int w = Integer.parseInt(wh[0]);
		int h = Integer.parseInt(wh[1]);
		org.lwjgl.opengl.Display.setDisplayMode(new org.lwjgl.opengl.DisplayMode(w, h));
		for (Method m : Minecraft.class.getDeclaredMethods()) {
			Class<?>[] p = m.getParameterTypes();
			if (!Modifier.isStatic(m.getModifiers()) && m.getReturnType() == void.class && p.length == 2 && p[0] == int.class && p[1] == int.class
				&& m.getName().toLowerCase(Locale.ROOT).contains("resize")) {
				m.setAccessible(true);
				m.invoke(mc, Math.max(1, org.lwjgl.opengl.Display.getWidth()), Math.max(1, org.lwjgl.opengl.Display.getHeight()));
				FlintQa.log("sweep: window now " + mc.width + "x" + mc.height);
				return;
			}
		}
		SKIPPED.add("telling Minecraft the window resized (no resize method found)");
	}

	// ---------- screens ----------

	private static void planButtons(final String where) {
		TASKS.add(new Task(where + ": open", 10, new Action() {
			public void run(Minecraft mc) throws Exception {
				base(mc, where);
			}
		}));
		TASKS.add(new Task(where + ": its buttons", 1, new Action() {
			public void run(Minecraft mc) throws Exception {
				final Screen base = mc.screen;
				List<Task> found = new ArrayList<Task>();
				for (final String label : allowed(mc, base)) {
					found.add(new Task(where + " > " + label, 12, new Action() {
						public void run(Minecraft mm) throws Exception {
							press(mm, label);
						}
					}));
					found.add(new Task(where + " > " + label + " > its buttons", 1, new Action() {
						public void run(Minecraft mm) throws Exception {
							Screen opened = mm.screen;
							List<Task> inner = new ArrayList<Task>();
							if (opened != null && opened != base && !isBase(opened, where)) {
								final String on = opened.getClass().getSimpleName();
								if (kit(opened)) {
									for (final String tab : FlintQa.clickable(mm)) {
										if (tab.toLowerCase(Locale.ROOT).endsWith(" tab") && !DENY.matcher(tab).find()) {
											inner.add(new Task(where + " > " + label + " > " + tab, 10, new Action() {
												public void run(Minecraft x) throws Exception {
													press(x, tab);
												}
											}));
										}
									}
								} else if (!DENY_SCREEN.matcher(on).find()) {
									for (final String inside : allowed(mm, opened)) {
										inner.add(new Task(where + " > " + label + " > " + inside, 10, new Action() {
											public void run(Minecraft x) throws Exception {
												press(x, inside);
											}
										}));
										inner.add(new Task(where + " > " + label + " > " + inside + ": back", 6, new Action() {
											public void run(Minecraft x) {
												back(x, on);
											}
										}));
									}
								}
							}
							inner.add(new Task(where + " > " + label + ": back to the " + where, 8, new Action() {
								public void run(Minecraft x) throws Exception {
									back(x, null);
									base(x, where);
								}
							}));
							pushFront(inner);
						}
					}));
				}
				pushFront(found);
			}
		}));
	}

	private static void planMenu(final Minecraft mc) {
		final boolean world = mc.world != null;
		if (world) {
			TASKS.add(new Task("Right Shift: the HUD page", 15, new Action() {
				public void run(Minecraft m) throws Exception {
					m.openScreen((Screen) cls("com.flintclient.client.control.rshift.HudPageScreen").getConstructors()[0].newInstance((Object) null));
				}
			}));
		}
		TASKS.add(new Task("Right Shift: the window", 15, new Action() {
			public void run(Minecraft m) throws Exception {
				Object parent = m.screen;
				if (world) {
					m.openScreen(null);
					parent = null;
				}
				call(cls("com.flintclient.client.control.rshift.ModMenuScreen"), "showMods");
				call(cls("com.flintclient.client.control.ControlCenter"), "requestOpen", parent);
			}
		}));
		TASKS.add(new Task("Right Shift: its tabs", 1, new Action() {
			public void run(Minecraft m) throws Exception {
				List<Task> found = new ArrayList<Task>();
				for (final String tab : FlintQa.clickable(m)) {
					if (tab.toLowerCase(Locale.ROOT).endsWith(" tab") && !DENY.matcher(tab).find()) {
						found.add(new Task("Right Shift > " + tab, 12, new Action() {
							public void run(Minecraft x) throws Exception {
								press(x, tab);
							}
						}));
					}
				}
				found.add(new Task("Right Shift: closed", 15, new Action() {
					public void run(Minecraft x) {
						FlintQa.escape(x);
					}
				}));
				pushFront(found);
			}
		}));
	}

	// ---------- the safety net itself ----------

	private static void planNet() throws Exception {
		final Class<?> faults = cls("com.flintclient.client.control.feature.Faults");
		final String fps = "fps";
		TASKS.add(new Task("safety net: FPS on", 5, new Action() {
			public void run(Minecraft m) throws Exception {
				m.openScreen(null);
				call(state(fps), "setEnabled", true);
			}
		}));
		TASKS.add(new Task("safety net: FPS made to throw while drawing", 10, new Action() {
			public void run(Minecraft m) throws Exception {
				expectedFaults = 1;
				call(faults, "qaInject", fps, false);
			}
		}));
		TASKS.add(new Task("safety net: FPS switched off, the game goes on", 5, new Action() {
			public void run(Minecraft m) throws Exception {
				int index = (Integer) call(cls("com.flintclient.client.control.model.ModuleRegistry"), "indexOf", fps);
				check("safety net: a HUD module that throws is switched off for the session", (Boolean) call(faults, "off", index) && m.world != null);
				rearm();
			}
		}));
		TASKS.add(new Task("safety net: the Right Shift menu", 10, new Action() {
			public void run(Minecraft m) throws Exception {
				openPage(m, null);
			}
		}));
		TASKS.add(new Task("safety net: the menu made to throw while drawing", 5, new Action() {
			public void run(Minecraft m) throws Exception {
				expectedFaults = 1;
				call(faults, "qaInject", "", true);
			}
		}));
		// A screen that broke closes at its next tick, and 1.8.9 draws many frames a tick: a tick goes by before the check.
		TASKS.add(new Task("safety net: a tick for the menu to close", 1, new Action() {
			public void run(Minecraft m) {
			}
		}));
		TASKS.add(new Task("safety net: the menu closed, the game goes on", 5, new Action() {
			public void run(Minecraft m) throws Exception {
				Screen now = m.screen;
				boolean closed = now == null || !cls("com.flintclient.client.control.rshift.ModMenuScreen").isInstance(now);
				check("safety net: a Flint screen that throws closes instead of crashing", closed && m.world != null);
				rearm();
			}
		}));
	}

	private static void check(String what, boolean ok) {
		if (ok) {
			FlintQa.log("PASS " + what);
		} else {
			fail(what, "did not happen", null);
		}
	}

	// ---------- helpers ----------

	private static void base(Minecraft mc, String where) {
		Screen s = mc.screen;
		if (s != null && isBase(s, where)) {
			return;
		}
		if (where.equals("pause")) {
			mc.openScreen(new net.minecraft.client.gui.screen.menu.GameMenuScreen());
		} else {
			mc.openScreen(null);
		}
	}

	private static boolean isBase(Screen s, String where) {
		String n = s.getClass().getSimpleName();
		return where.equals("pause") ? n.contains("GameMenuScreen") : n.contains("TitleScreen");
	}

	private static void back(Minecraft mc, String until) {
		for (int i = 0; i < 5; i++) {
			Screen s = mc.screen;
			if (s == null || s.getClass().getSimpleName().contains("TitleScreen") || s.getClass().getSimpleName().contains("GameMenuScreen")
				|| (until != null && s.getClass().getSimpleName().equals(until))) {
				return;
			}
			FlintQa.escape(mc);
			if (mc.screen == s) {
				// Escape did nothing here: closed as the game would.
				mc.openScreen(null);
			}
		}
	}

	private static List<String> allowed(Minecraft mc, Screen screen) throws Exception {
		List<String> out = new ArrayList<String>();
		if (screen == null) {
			return out;
		}
		for (String label : FlintQa.clickable(mc)) {
			if (DENY.matcher(label).find()) {
				note("button '" + label + "' on " + screen.getClass().getSimpleName() + " (not pressed by the sweep)");
				continue;
			}
			out.add(label);
			if (out.size() >= 40) {
				break;
			}
		}
		return out;
	}

	private static void press(Minecraft mc, String label) throws Exception {
		if (FlintQa.clickNamed(mc, label)) {
			return;
		}
		// A cycling button shows its new value once pressed ("Difficulty: Normal" is then "Difficulty: Hard"): the same name.
		int colon = label.indexOf(": ");
		if (colon > 0) {
			String stem = label.substring(0, colon + 2);
			for (String now : FlintQa.clickable(mc)) {
				if (now.startsWith(stem) && FlintQa.clickNamed(mc, now)) {
					return;
				}
			}
		}
		note("'" + label + "' on " + (mc.screen == null ? "no screen" : mc.screen.getClass().getSimpleName()) + " (gone by the time it was pressed)");
	}

	private static boolean kit(Screen screen) {
		try {
			return cls("com.flintclient.client.ui.kit.KitScreen").isInstance(screen);
		} catch (ClassNotFoundException e) {
			return false;
		}
	}

	private static void note(String skipped) {
		if (!SKIPPED.contains(skipped)) {
			SKIPPED.add(skipped);
		}
	}

	private static void pushFront(List<Task> tasks) {
		for (int i = tasks.size() - 1; i >= 0; i--) {
			TASKS.addFirst(tasks.get(i));
		}
	}

	// ---------- failures ----------

	/** The net armed again, and on 1.8.9 the menu too (a menu that failed stays shut for the session otherwise). */
	private static void rearm() throws Exception {
		call(cls("com.flintclient.client.control.feature.Faults"), "rearm");
		try {
			Field failed = cls("com.flintclient.client.platform.MenuHost").getDeclaredField("failed");
			failed.setAccessible(true);
			failed.setBoolean(null, false);
		} catch (NoSuchFieldException e) {
			// A later runtime without it.
		}
	}

	private static int faultCount() {
		try {
			return (Integer) call(cls("com.flintclient.client.control.feature.Faults"), "count");
		} catch (Exception e) {
			return 0;
		}
	}

	private static void collectFaults(String context) throws Exception {
		Class<?> faults = cls("com.flintclient.client.control.feature.Faults");
		int count = (Integer) call(faults, "count");
		if (count == faultsSeen) {
			return;
		}
		List<?> records = (List<?>) call(faults, "records");
		boolean unexpected = false;
		for (int i = faultsSeen; i < Math.min(count, records.size()); i++) {
			Object r = records.get(i);
			Throwable error = (Throwable) call(r, "error");
			if (expectedFaults > 0 && String.valueOf(error.getMessage()).startsWith("QA: a failure injected")) {
				expectedFaults--;
				FlintQa.log("sweep: the injected failure was caught: " + r);
				continue;
			}
			unexpected = true;
			fail(context, "Flint's safety net caught: " + r, error);
		}
		faultsSeen = count;
		// Armed again so the next failure shows too; not after the net's own check's failure, which looks at what it did first.
		if (unexpected) {
			rearm();
		}
	}

	private static void fail(String context, String what, Throwable error) {
		String line = context + ": " + what;
		FAILS.put(context + "#" + FAILS.size(), line);
		if (error != null) {
			LOG.error("QA FAIL sweep " + line, error);
		} else {
			FlintQa.log("FAIL sweep " + line);
		}
		FlintQa.countFailure();
	}

	private static void summary() {
		FlintQa.log("sweep summary: swept=" + swept + " failures=" + FAILS.size() + " skipped=" + SKIPPED.size());
		for (String s : SKIPPED) {
			FlintQa.log("sweep skipped " + s);
		}
		if (FAILS.isEmpty()) {
			FlintQa.log("PASS sweep: " + swept + " steps, no failure");
		}
		for (String line : FAILS.values()) {
			FlintQa.log("sweep FAIL " + line);
		}
	}

	// ---------- reflection ----------

	private static Class<?> cls(String name) throws ClassNotFoundException {
		return Class.forName(name);
	}

	private static Object call(Object target, String name, Object... args) throws Exception {
		boolean statics = target instanceof Class<?>;
		Class<?> k = statics ? (Class<?>) target : target.getClass();
		for (Class<?> c = k; c != null; c = c.getSuperclass()) {
			for (Method m : c.getDeclaredMethods()) {
				if (m.getName().equals(name) && m.getParameterTypes().length == args.length && Modifier.isStatic(m.getModifiers()) == statics
					&& fits(m, args)) {
					m.setAccessible(true);
					try {
						return m.invoke(statics ? null : target, args);
					} catch (java.lang.reflect.InvocationTargetException e) {
						Throwable cause = e.getCause();
						if (cause instanceof Exception) {
							throw (Exception) cause;
						}
						throw new RuntimeException(cause);
					}
				}
			}
		}
		throw new NoSuchMethodException(k.getName() + "." + name + "/" + args.length);
	}

	private static boolean fits(Method m, Object[] args) {
		Class<?>[] types = m.getParameterTypes();
		for (int i = 0; i < args.length; i++) {
			if (args[i] == null) {
				if (types[i].isPrimitive()) {
					return false;
				}
				continue;
			}
			Class<?> t = types[i].isPrimitive() ? box(types[i]) : types[i];
			if (!t.isInstance(args[i])) {
				return false;
			}
		}
		return true;
	}

	private static Class<?> box(Class<?> p) {
		return p == int.class ? Integer.class : p == boolean.class ? Boolean.class : p == float.class ? Float.class : p == double.class ? Double.class
			: p == long.class ? Long.class : p;
	}
}
