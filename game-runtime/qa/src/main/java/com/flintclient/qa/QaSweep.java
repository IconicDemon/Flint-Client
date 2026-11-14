package com.flintclient.qa;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The crash sweep (dev only): {@code sweep title|mods|pause|rshift|net|summary}, driven by Flint's own registries rather than
 * hand-written lists, so a mod or setting added later is swept without touching a tour.
 * <ul>
 * <li>{@code sweep mods [WxH]}: in a world, every module of {@code ModuleRegistry} in turn: switched on and drawn ~10 frames,
 * its page opened in the Right Shift menu, then every setting set to its minimum, maximum and middle (through the setting's
 * own clamp: {@code coerce(Integer.MIN_VALUE)} is its minimum), every choice, both boolean states, empty and long text, a
 * few frames each; then the module put back as it was. Half way, the window is resized to WxH (default 1920x1080) with the
 * menu open. Last, every module on at once for 60 frames.</li>
 * <li>{@code sweep title} / {@code sweep pause}: every button of the title screen (or the pause menu) clicked, and every
 * button of the screen that opens clicked too (one level down: Flint's own screens and vanilla's Options, Multiplayer,
 * Singleplayer, Language...), back each time; on a Flint kit screen its tabs. Buttons that would quit, leave, connect,
 * open a browser or a folder, delete or sign out are skipped and listed.</li>
 * <li>{@code sweep rshift}: the Right Shift menu (over the title screen, or the HUD page then the window in a world), every
 * tab.</li>
 * <li>{@code sweep net}: the safety net itself: a HUD module made to throw is switched off (the game goes on), a Flint
 * screen made to throw closes.</li>
 * <li>{@code sweep summary}: one PASS / FAIL line per thing swept that failed, and the totals.</li>
 * </ul>
 * Nothing stops the sweep: an exception from a step, and every failure Flint's safety net caught meanwhile ({@code
 * feature.Faults}: a hook path that turned itself off, a module switched off, a screen that closed), is recorded with where it
 * happened (module / setting / screen), and the net is armed again so the next failure shows too. A failure that takes the
 * game down is found from the last "QA sweep at" line before the crash. One task per client tick, then a few frames.
 */
public final class QaSweep {
	private static final Logger LOG = LoggerFactory.getLogger("FlintQA");
	/**
	 * What differs per Minecraft line, from the line's FlintQa: a click at a GUI point on a screen (press and release), and a
	 * widget's centre (1.19.x has public x/y fields where later lines have getX/getY).
	 */
	public interface Clicker {
		void click(Screen screen, double x, double y);
	}

	/** Labels of buttons the sweep never presses: they quit, leave, connect, open a browser, a folder or the clipboard, delete. */
	private static final Pattern DENY = Pattern.compile("\\b(quit|disconnect|realms|discord|link|website|browser|report|feedback|delete|remove|"
		+ "sign (in|out)|log (in|out)|account|join|connect|direct|play|create|re-create|lan|telemetry|copy|folder|open|reset|yes|proceed|confirm|"
		+ "buy|purchase|send|invite|accept|decline|block|unfriend|exit|leave|stop|restart|update|download|install|uninstall|import|export|backup)\\b",
		Pattern.CASE_INSENSITIVE);
	/** Screens whose buttons are never pressed (a link to confirm, a world to delete...). */
	private static final Pattern DENY_SCREEN = Pattern.compile("Confirm|Link|Delete|Warning|Alert|Popup|Disconnected|Connect", Pattern.CASE_INSENSITIVE);
	/**
	 * On Flint's own windows (Friends, Cosmetics, Discover...), the elements that open another screen (vanilla's or Flint's),
	 * pressed by {@code sweep deep}: Minecraft's friends list, options, language, the pages.
	 */
	private static final Pattern KIT_OPEN = Pattern.compile("\\b(friends|options|settings|language|screenshots|menu sizes|what.s new|utilities|"
		+ "store|discover|cosmetics|rewards|credits|privacy|controls|key ?binds|advancements|statistics)\\b", Pattern.CASE_INSENSITIVE);
	/** Modules never switched on by the sweep: Discord would set the owner's real Discord status from a QA game. */
	private static final Pattern DENY_MODULE = Pattern.compile("^(discord)$");

	private record Task(String context, Action action, int frames) {
	}

	@FunctionalInterface
	private interface Action {
		void run(Minecraft mc) throws Exception;
	}

	private static final Deque<Task> TASKS = new ArrayDeque<>();
	/** What failed, by context (in order), and how many things were swept. */
	private static final Map<String, String> FAILS = new LinkedHashMap<>();
	private static final List<String> SKIPPED = new ArrayList<>();
	private static int swept;
	private static int faultsSeen;
	private static String phase;
	private static Task current;
	private static long waitUntilFrame;
	private static int waitTicks;
	private static Clicker clicker;
	private static java.util.function.Function<AbstractWidget, double[]> centre;
	/** The safety net's own checks expect a failure: it isn't one. */
	private static boolean expectingFault;
	private static int expectedFaults;
	private static boolean startupSeen;

	private QaSweep() {
	}

	/** Runs one tick of {@code sweep ARGS}; true when that part of the sweep is done. */
	public static boolean step(Minecraft mc, String[] c, Clicker click, java.util.function.Function<AbstractWidget, double[]> widgetCentre)
		throws Exception {
		clicker = click;
		centre = widgetCentre;
		String what = c.length > 1 ? c[1] : "mods";
		if (phase == null) {
			phase = what;
			TASKS.clear();
			if (!startupSeen) {
				// What the safety net caught before the sweep (start-up, the title screen): failures too.
				startupSeen = true;
				faultsSeen = 0;
				collectFaults("before the sweep (start-up)");
			}
			faultsSeen = faultCount();
			FlintQa.log("sweep " + what + " starts");
			switch (what) {
				case "title", "pause" -> planButtons(mc, what);
				case "mods" -> planModules(mc, c.length > 2 ? c[2] : "1920x1080", c.length > 3 ? List.of(c[3].split(",")) : null);
				case "rshift" -> planMenu(mc);
				case "deep" -> planDeep(mc);
				case "net" -> planNet(mc);
				case "summary" -> {
					summary();
					phase = null;
					return true;
				}
				default -> throw new IllegalArgumentException("sweep " + what + "?");
			}
		}
		// The task before: its frames drawn, then whatever the safety net caught meanwhile is its failure.
		if (current != null) {
			if (QaFrames.frames < waitUntilFrame || waitTicks-- > 0) {
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
		waitUntilFrame = QaFrames.frames + Math.max(1, next.frames);
		waitTicks = 0;
		return false;
	}

	// ---------- modules and settings ----------

	/** Every module, or only those of {@code only} (module ids: a rerun of what failed, {@code sweep mods 1920x1080 ambient_sounds}). */
	private static void planModules(Minecraft mc, String size, List<String> only) throws Exception {
		List<?> modules = (List<?>) call(cls("com.flintclient.client.control.model.ModuleRegistry"), "all");
		int half = modules.size() / 2;
		int n = 0;
		List<Object> all = new ArrayList<>();
		for (Object descriptor : modules) {
			String id = (String) call(descriptor, "id");
			String name = (String) call(descriptor, "name");
			boolean usable = (boolean) call(call(descriptor, "status"), "usable");
			if (!usable) {
				SKIPPED.add("module " + name + " (not built on this line)");
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
				String[] wh = size.split("x");
				TASKS.add(new Task("window " + size + " (menu open)", m -> m.getWindow().setWindowed(Integer.parseInt(wh[0]), Integer.parseInt(wh[1])), 10));
			}
			all.add(descriptor);
			planModule(descriptor, id, name);
		}
		// Everything on at once: what the modules do to one another.
		TASKS.add(new Task("every module on at once", m -> {
			QaGame.setScreen(m, null);
			for (Object d : all) {
				Object state = state((String) call(d, "id"));
				call(state, "setEnabled", true);
			}
		}, 60));
		TASKS.add(new Task("every module on at once: the Right Shift menu", m -> openPage(m, null), 20));
		TASKS.add(new Task("every module back to its default", m -> {
			QaGame.setScreen(m, null);
			for (Object d : all) {
				Object state = state((String) call(d, "id"));
				call(state, "reset");
			}
		}, 10));
	}

	private static void planModule(Object descriptor, String id, String name) throws Exception {
		Object[] saved = new Object[1];
		TASKS.add(new Task(name + ": on", m -> {
			QaGame.setScreen(m, null);
			Object state = state(id);
			saved[0] = call(state, "copy");
			call(state, "setEnabled", true);
		}, 10));
		TASKS.add(new Task(name + ": its page in the Right Shift menu", m -> openPage(m, id), 6));
		for (Object setting : (List<?>) call(descriptor, "settings")) {
			String sid = (String) call(setting, "id");
			for (Object value : values(setting)) {
				String shown = String.valueOf(value);
				if (shown.length() > 24) {
					shown = shown.substring(0, 21) + "...";
				}
				TASKS.add(new Task(name + " / " + sid + " = " + shown, m -> {
					Object state = state(id);
					call(state, "set", setting, value);
				}, 1));
			}
			// values() ends with the default: the setting is back to it before the next one.
		}
		TASKS.add(new Task(name + ": in the world with its page closed", m -> QaGame.setScreen(m, null), 3));
		TASKS.add(new Task(name + ": back as it was", m -> {
			Object state = state(id);
			if (saved[0] != null) {
				call(state, "copyFrom", saved[0]);
			}
		}, 2));
	}

	/** The values a setting is swept through: its extremes and middle, every choice, both states... */
	private static List<Object> values(Object setting) throws Exception {
		List<Object> out = new ArrayList<>();
		String kind = setting.getClass().getSimpleName();
		Object def = call(setting, "defaultValue");
		switch (kind) {
			case "BoolSetting" -> {
				out.add(!(Boolean) def);
				out.add(def);
			}
			case "IntSetting" -> {
				int min = (int) call(setting, "min");
				int max = (int) call(setting, "max");
				out.add(call(setting, "coerce", Integer.MIN_VALUE));
				out.add(call(setting, "coerce", Integer.MAX_VALUE));
				out.add(call(setting, "coerce", (int) (((long) min + max) / 2)));
			}
			case "FloatSetting" -> {
				float min = (float) call(setting, "min");
				float max = (float) call(setting, "max");
				out.add(call(setting, "coerce", -Float.MAX_VALUE));
				out.add(call(setting, "coerce", Float.MAX_VALUE));
				out.add(call(setting, "coerce", (min + max) / 2));
			}
			case "ChoiceSetting", "ColorSetting" -> out.addAll((List<?>) call(setting, "options"));
			case "OptionSetting" -> {
				int size = (int) call(setting, "size");
				for (int i = 0; i < size; i++) {
					out.add(i);
				}
			}
			case "RgbSetting" -> {
				out.add(0x000000);
				out.add(0xFFFFFF);
				out.add(0xFF7A1A);
				if ((boolean) call(setting, "clearable")) {
					out.add(setting.getClass().getField("CLEAR").get(null));
				}
			}
			case "TextSetting" -> {
				int max = (int) call(setting, "maxLength");
				out.add("");
				out.add("QA §csweep ✓ 😀 %s {} \\n");
				out.add("W".repeat(Math.max(1, Math.min(max, 4096))));
			}
			case "KeySetting" -> out.add("");
			case "IdListSetting" -> out.add(List.of());
			case "IdValuesSetting", "HeldItemsSetting" -> out.add(Map.of());
			case "IdSetting" -> out.add("");
			default -> {
				// A kind added later: still set to its own default, and reported so the sweep learns it.
				SKIPPED.add("setting kind " + kind + " (only its default is swept)");
			}
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

	/** The Right Shift menu's window over the game, on {@code moduleId}'s page (null: the mods grid). */
	private static void openPage(Minecraft mc, String moduleId) throws Exception {
		Class<?> menu = cls("com.flintclient.client.control.rshift.ModMenuScreen");
		if (moduleId != null) {
			call(menu, "showModule", moduleId);
		} else {
			call(menu, "showMods");
		}
		QaGame.setScreen(mc, null);
		call(cls("com.flintclient.client.control.ControlCenter"), "openWindow", (Object) null);
		Screen now = QaGame.screen(mc);
		if (now == null || !menu.isInstance(now)) {
			throw new IllegalStateException("the Right Shift menu didn't open (now " + (now == null ? "none" : now.getClass().getSimpleName()) + ")");
		}
	}

	// ---------- screens ----------

	private static void planButtons(Minecraft mc, String where) {
		TASKS.add(new Task(where + ": open", m -> base(m, where), 10));
		TASKS.add(new Task(where + ": its buttons", m -> {
			Screen base = QaGame.screen(m);
			List<Task> found = new ArrayList<>();
			for (String label : buttons(base)) {
				found.add(new Task(where + " > " + label, mm -> press(mm, label), 12));
				found.add(new Task(where + " > " + label + " > its buttons", mm -> {
					Screen opened = QaGame.screen(mm);
					List<Task> inner = new ArrayList<>();
					if (opened != null && opened != base && !isBase(mm, opened, where)) {
						String on = opened.getClass().getSimpleName();
						if (kit(opened)) {
							for (String tab : tabs(opened)) {
								inner.add(new Task(where + " > " + label + " > " + tab, x -> clickNode(x, tab), 10));
							}
						} else if (!DENY_SCREEN.matcher(on).find()) {
							for (String inside : buttons(opened)) {
								inner.add(new Task(where + " > " + label + " > " + inside, x -> {
									// Back on the screen first if an earlier button left it (Done, Back): the button again from the base.
									Screen shown = QaGame.screen(x);
									if (shown == null || !shown.getClass().getSimpleName().equals(on)) {
										back(x, null);
										base(x, where);
										press(x, label);
									}
									press(x, inside);
								}, 10));
								inner.add(new Task(where + " > " + label + " > " + inside + ": back", x -> back(x, on), 6));
							}
						}
					}
					inner.add(new Task(where + " > " + label + ": back to the " + where, x -> {
						back(x, null);
						base(x, where);
					}, 8));
					pushFront(inner);
				}, 1));
			}
			pushFront(found);
		}, 1));
	}

	/**
	 * Last, as it may find a crash that ends the run: on each Flint window the title screen opens (a kit screen), every element
	 * that opens another screen ({@link #KIT_OPEN}), pressed, a few frames drawn, and back.
	 */
	private static void planDeep(Minecraft mc) {
		String where = mc.level != null ? "pause" : "title";
		TASKS.add(new Task(where + ": open", m -> base(m, where), 10));
		TASKS.add(new Task(where + ": Flint windows", m -> {
			Screen base = QaGame.screen(m);
			List<Task> found = new ArrayList<>();
			for (String label : buttons(base)) {
				found.add(new Task(where + " > " + label + " (deep)", mm -> {
					back(mm, null);
					base(mm, where);
					press(mm, label);
					Screen opened = QaGame.screen(mm);
					List<Task> inner = new ArrayList<>();
					if (opened != null && kit(opened)) {
						String on = opened.getClass().getSimpleName();
						for (Object[] n : nodes(opened)) {
							String name = String.valueOf(n[0]);
							if (KIT_OPEN.matcher(name).find() && !DENY.matcher(name).find() && !name.toLowerCase(Locale.ROOT).endsWith(" tab")) {
								inner.add(new Task(where + " > " + label + " > " + name, x -> {
									Screen shown = QaGame.screen(x);
									if (shown == null || !shown.getClass().getSimpleName().equals(on)) {
										back(x, null);
										base(x, where);
										press(x, label);
									}
									clickNode(x, name);
								}, 15));
								inner.add(new Task(where + " > " + label + " > " + name + ": back", x -> back(x, on), 8));
							}
						}
					}
					inner.add(new Task(where + " > " + label + ": back to the " + where, x -> {
						back(x, null);
						base(x, where);
					}, 6));
					pushFront(inner);
				}, 10));
			}
			pushFront(found);
		}, 1));
		if (mc.level != null) {
			planHudPageDeep();
		}
	}

	/**
	 * Over the game, the way most windows open there: Right Shift's HUD page, each of its elements that opens another screen
	 * ({@link #KIT_OPEN}: Friends, Cosmetics...), and on a Flint window that opens, each of its own (Minecraft's friends list).
	 */
	private static void planHudPageDeep() {
		Action open = m -> {
			QaGame.setScreen(m, null);
			call(cls("com.flintclient.client.control.ControlCenter"), "requestOpen");
		};
		TASKS.add(new Task("HUD page: open", open, 15));
		TASKS.add(new Task("HUD page: Flint windows", m -> {
			Screen page = QaGame.screen(m);
			List<Task> found = new ArrayList<>();
			if (page != null && kit(page)) {
				for (Object[] n : nodes(page)) {
					String name = String.valueOf(n[0]);
					if (!KIT_OPEN.matcher(name).find() || DENY.matcher(name).find() || name.toLowerCase(Locale.ROOT).endsWith(" tab")) {
						continue;
					}
					found.add(new Task("HUD page > " + name, mm -> {
						open.run(mm);
						clickNode(mm, name);
						Screen opened = QaGame.screen(mm);
						List<Task> inner = new ArrayList<>();
						if (opened != null && kit(opened) && opened != page) {
							String on = opened.getClass().getSimpleName();
							for (Object[] k : nodes(opened)) {
								String inside = String.valueOf(k[0]);
								if (KIT_OPEN.matcher(inside).find() && !DENY.matcher(inside).find() && !inside.toLowerCase(Locale.ROOT).endsWith(" tab")) {
									inner.add(new Task("HUD page > " + name + " > " + inside, x -> {
										Screen shown = QaGame.screen(x);
										if (shown == null || !shown.getClass().getSimpleName().equals(on)) {
											open.run(x);
											clickNode(x, name);
										}
										clickNode(x, inside);
									}, 15));
									inner.add(new Task("HUD page > " + name + " > " + inside + ": back", x -> back(x, on), 8));
								}
							}
						}
						inner.add(new Task("HUD page > " + name + ": closed", x -> QaGame.setScreen(x, null), 6));
						pushFront(inner);
					}, 15));
				}
			}
			pushFront(found);
		}, 1));
	}

	private static void planMenu(Minecraft mc) {
		boolean world = mc.level != null;
		if (world) {
			TASKS.add(new Task("Right Shift: the HUD page", m -> {
				QaGame.setScreen(m, null);
				call(cls("com.flintclient.client.control.ControlCenter"), "requestOpen");
			}, 15));
		}
		TASKS.add(new Task("Right Shift: the window", m -> {
			Screen parent = QaGame.screen(m);
			if (world) {
				QaGame.setScreen(m, null);
				parent = null;
			}
			call(cls("com.flintclient.client.control.rshift.ModMenuScreen"), "showMods");
			call(cls("com.flintclient.client.control.ControlCenter"), "openWindow", parent);
		}, 15));
		TASKS.add(new Task("Right Shift: its tabs", m -> {
			Screen menu = QaGame.screen(m);
			List<Task> found = new ArrayList<>();
			for (String tab : tabs(menu)) {
				found.add(new Task("Right Shift > " + tab, x -> clickNode(x, tab), 12));
			}
			found.add(new Task("Right Shift: closed", x -> {
				Screen s = QaGame.screen(x);
				if (s != null) {
					s.onClose();
				}
			}, 15));
			pushFront(found);
		}, 1));
	}

	// ---------- the safety net itself ----------

	private static void planNet(Minecraft mc) throws Exception {
		Class<?> faults = cls("com.flintclient.client.control.feature.Faults");
		String fps = "fps";
		TASKS.add(new Task("safety net: FPS on", m -> {
			QaGame.setScreen(m, null);
			call(state(fps), "setEnabled", true);
		}, 5));
		TASKS.add(new Task("safety net: FPS made to throw while drawing", m -> {
			expect(1);
			call(faults, "qaInject", fps, false);
		}, 10));
		TASKS.add(new Task("safety net: FPS switched off, the game goes on", m -> {
			int index = (int) call(cls("com.flintclient.client.control.model.ModuleRegistry"), "indexOf", fps);
			boolean off = (boolean) call(faults, "off", index);
			check("safety net: a HUD module that throws is switched off for the session", off && m.level != null);
			call(faults, "rearm");
		}, 5));
		TASKS.add(new Task("safety net: the Right Shift menu", m -> openPage(m, null), 10));
		TASKS.add(new Task("safety net: the menu made to throw while drawing", m -> {
			expect(1);
			call(faults, "qaInject", "", true);
		}, 5));
		TASKS.add(new Task("safety net: the menu closed, the game goes on", m -> {
			waitTicks = 0;
		}, 1));
		TASKS.add(new Task("safety net: after the menu closed", m -> {
			Screen now = QaGame.screen(m);
			boolean closed = now == null || !cls("com.flintclient.client.control.rshift.ModMenuScreen").isInstance(now);
			check("safety net: a Flint screen that throws closes instead of crashing", closed && m.level != null);
			call(faults, "rearm");
		}, 5));
	}

	private static void expect(int faults) {
		expectingFault = true;
		expectedFaults = faults;
	}

	private static void check(String what, boolean ok) {
		if (ok) {
			FlintQa.log("PASS " + what);
		} else {
			fail(what, "did not happen", null);
		}
	}

	// ---------- helpers ----------

	/** Gets to the title screen or the pause menu. */
	private static void base(Minecraft mc, String where) {
		if (where.equals("pause")) {
			Screen s = QaGame.screen(mc);
			if (s == null || !isBase(mc, s, where)) {
				QaGame.setScreen(mc, null);
				mc.pauseGame(false);
			}
		} else if (QaGame.screen(mc) == null || !isBase(mc, QaGame.screen(mc), where)) {
			QaGame.setScreen(mc, null);
		}
	}

	private static boolean isBase(Minecraft mc, Screen s, String where) {
		String n = s.getClass().getSimpleName();
		return where.equals("pause") ? n.contains("PauseScreen") : n.contains("TitleScreen");
	}

	/** Closes screens (Escape, as the player would) until {@code until} shows or nothing does; at most five. */
	private static void back(Minecraft mc, String until) {
		for (int i = 0; i < 5; i++) {
			Screen s = QaGame.screen(mc);
			if (s == null || s.getClass().getSimpleName().contains("TitleScreen") || s.getClass().getSimpleName().contains("PauseScreen")
				|| (until != null && s.getClass().getSimpleName().equals(until))) {
				return;
			}
			s.onClose();
		}
	}

	private static void pushFront(List<Task> tasks) {
		for (int i = tasks.size() - 1; i >= 0; i--) {
			TASKS.addFirst(tasks.get(i));
		}
	}

	/** The labels of a screen's shown, active buttons, without the ones the sweep never presses. */
	private static List<String> buttons(Screen screen) {
		List<String> out = new ArrayList<>();
		if (screen == null) {
			return out;
		}
		for (var child : screen.children()) {
			if (child instanceof AbstractWidget w && w.visible && w.active) {
				String label = w.getMessage().getString().replace('\n', ' ').strip();
				if (label.isEmpty() || out.contains(label)) {
					continue;
				}
				if (DENY.matcher(label).find()) {
					note("button '" + label + "' on " + screen.getClass().getSimpleName() + " (not pressed by the sweep)");
					continue;
				}
				out.add(label);
			}
			if (out.size() >= 40) {
				break;
			}
		}
		return out;
	}

	private static void note(String skipped) {
		if (!SKIPPED.contains(skipped)) {
			SKIPPED.add(skipped);
		}
	}

	private static void press(Minecraft mc, String label) {
		Screen screen = QaGame.screen(mc);
		if (screen == null) {
			throw new IllegalStateException("no screen to press '" + label + "' on");
		}
		for (var child : screen.children()) {
			if (child instanceof AbstractWidget w && w.visible && w.getMessage().getString().replace('\n', ' ').strip().equals(label)) {
				double k = menuToGui(mc, screen);
				double[] at = centre.apply(w);
				clicker.click(screen, at[0] * k, at[1] * k);
				return;
			}
		}
		// A cycling button shows its new value once pressed ("Difficulty: Normal" is then "Difficulty: Hard"): the same name.
		int colon = label.indexOf(": ");
		if (colon > 0) {
			String stem = label.substring(0, colon + 2);
			for (var child : screen.children()) {
				if (child instanceof AbstractWidget w && w.visible && w.getMessage().getString().replace('\n', ' ').strip().startsWith(stem)) {
					double k = menuToGui(mc, screen);
					double[] at = centre.apply(w);
					clicker.click(screen, at[0] * k, at[1] * k);
					return;
				}
			}
		}
		note("button '" + label + "' on " + screen.getClass().getSimpleName() + " (gone by the time it was pressed)");
	}

	private static boolean kit(Screen screen) {
		try {
			return cls("com.flintclient.client.ui.kit.KitScreen").isInstance(screen);
		} catch (ClassNotFoundException e) {
			return false;
		}
	}

	/** A Flint kit screen's tabs: its shown elements named "... tab" (the window's tabs, Cosmetics' shelves...). */
	private static List<String> tabs(Screen screen) throws Exception {
		List<String> out = new ArrayList<>();
		if (screen == null || !kit(screen)) {
			return out;
		}
		for (Object[] n : nodes(screen)) {
			String name = String.valueOf(n[0]);
			if (name.toLowerCase(Locale.ROOT).endsWith(" tab") && !out.contains(name) && !DENY.matcher(name).find()) {
				out.add(name);
			}
		}
		return out;
	}

	private static void clickNode(Minecraft mc, String name) throws Exception {
		Screen screen = QaGame.screen(mc);
		for (Object[] n : nodes(screen)) {
			if (String.valueOf(n[0]).equals(name)) {
				clicker.click(screen, (double) n[1], (double) n[2]);
				return;
			}
		}
		throw new IllegalStateException("'" + name + "' is gone from " + (screen == null ? "the screen" : screen.getClass().getSimpleName()));
	}

	private static List<Object[]> nodes(Screen screen) throws Exception {
		List<Object[]> out = new ArrayList<>();
		Class<?> kit = cls("com.flintclient.client.ui.kit.KitScreen");
		double perUi = (int) kit.getMethod("uiScale").invoke(screen) / (double) Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
		walk(kit.getMethod("root").invoke(screen), perUi, out);
		return out;
	}

	private static void walk(Object node, double perUi, List<Object[]> out) throws ReflectiveOperationException {
		Class<?> n = node.getClass();
		if (!(boolean) n.getMethod("visible").invoke(node)) {
			return;
		}
		if ((boolean) n.getMethod("hasName").invoke(node)) {
			float x = (float) n.getMethod("x").invoke(node);
			float y = (float) n.getMethod("y").invoke(node);
			float w = (float) n.getMethod("width").invoke(node);
			float h = (float) n.getMethod("height").invoke(node);
			out.add(new Object[] {n.getMethod("accessibleName").invoke(node), (x + w / 2) * perUi, (y + h / 2) * perUi, node});
		}
		int count = (int) n.getMethod("childCount").invoke(node);
		for (int i = 0; i < count; i++) {
			walk(n.getMethod("child", int.class).invoke(node, i), perUi, out);
		}
	}

	/** GUI units per layout unit: Flint's own menu screens draw at MenuScale, others at the GUI scale. */
	private static double menuToGui(Minecraft mc, Screen screen) {
		if (!screen.getClass().getName().startsWith("com.flintclient.client.ui.screen.")) {
			return 1.0;
		}
		try {
			double pixels = ((Number) cls("com.flintclient.client.ui.MenuScale").getMethod("pixels").invoke(null)).doubleValue();
			return pixels / (double) Math.max(1, mc.getWindow().getGuiScale());
		} catch (ReflectiveOperationException e) {
			return 1.0;
		}
	}

	// ---------- failures ----------

	private static int faultCount() {
		try {
			return (int) call(cls("com.flintclient.client.control.feature.Faults"), "count");
		} catch (Exception e) {
			return 0;
		}
	}

	/** What the safety net caught since the last look, as failures of {@code context}; then the net armed again. */
	private static void collectFaults(String context) throws Exception {
		Class<?> faults = cls("com.flintclient.client.control.feature.Faults");
		int count = (int) call(faults, "count");
		if (count == faultsSeen) {
			return;
		}
		List<?> records = (List<?>) call(faults, "records");
		boolean unexpected = false;
		for (int i = faultsSeen; i < Math.min(count, records.size()); i++) {
			Object r = records.get(i);
			Throwable error = (Throwable) call(r, "error");
			if (expectingFault && expectedFaults > 0 && String.valueOf(error.getMessage()).startsWith("QA: a failure injected")) {
				expectedFaults--;
				FlintQa.log("sweep: the injected failure was caught: " + r);
				continue;
			}
			unexpected = true;
			fail(context, "Flint's safety net caught: " + r, error);
		}
		if (expectedFaults == 0) {
			expectingFault = false;
		}
		faultsSeen = count;
		// Armed again so the next failure shows too; not after the net's own check's failure, which looks at what it did first.
		if (unexpected) {
			call(faults, "rearm");
		}
	}

	private static void fail(String context, String what, Throwable error) {
		String line = context + ": " + what;
		FAILS.putIfAbsent(context + "#" + FAILS.size(), line);
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

	// ---------- reflection (this mod doesn't compile against Flint) ----------

	private static Class<?> cls(String name) throws ClassNotFoundException {
		return Class.forName(name);
	}

	/** Calls {@code name} on {@code target} (a Class for a static method) with {@code args}, matched by name and count. */
	private static Object call(Object target, String name, Object... args) throws Exception {
		boolean statics = target instanceof Class<?>;
		Class<?> k = statics ? (Class<?>) target : target.getClass();
		for (Class<?> c = k; c != null; c = c.getSuperclass()) {
			for (Method m : c.getDeclaredMethods()) {
				if (m.getName().equals(name) && m.getParameterCount() == args.length && Modifier.isStatic(m.getModifiers()) == statics
					&& fits(m, args)) {
					m.setAccessible(true);
					try {
						return m.invoke(statics ? null : target, args);
					} catch (java.lang.reflect.InvocationTargetException e) {
						Throwable cause = e.getCause();
						if (cause instanceof Exception ex) {
							throw ex;
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
