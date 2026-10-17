package com.flintclient.qa;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dev-only QA driver: runs the script named by {@code -Dflintqa.script}, one step per client tick, writing screenshots
 * to {@code -Dflintqa.out}/screenshots. Every line it logs starts with "QA "; "QA PASS" / "QA FAIL" lines are the
 * results, and "QA DONE failures=N" ends the run. Inert without the property. Never shipped in Flint.
 *
 * <p>Steps (one per line, # for comments): wait-title S, sleep S, log TEXT, shot NAME, screen, widgets, keymap,
 * expect-screen CLASS [S], expect-null-screen [S], click-widget LABEL, expect-widget LABEL, expect-no-widget LABEL,
 * select-server N, key NAME [MODS], type TEXT, create-world NAME, wait-world [S] [SETTLE], leave-world, gui-scale N,
 * file PATH, call CLASS.METHOD [ARGS], await-key NAME KEY (the runner posts a real key press, then answers), quit.
 */
public final class FlintQa implements ClientModInitializer {
	public static final boolean ENABLED = System.getProperty("flintqa.script") != null;
	private static final Logger LOG = LoggerFactory.getLogger("FlintQA");
	private static final List<String[]> STEPS = new ArrayList<>();
	private static Path out;
	private static int step;
	private static long stepStart;
	private static int failures;
	private static boolean seen;
	private static boolean done;

	@Override
	public void onInitializeClient() {
		if (!ENABLED) {
			return;
		}
		try {
			out = Path.of(System.getProperty("flintqa.out", "."));
			Files.createDirectories(out.resolve("screenshots"));
			for (String line : Files.readAllLines(Path.of(System.getProperty("flintqa.script")), StandardCharsets.UTF_8)) {
				String t = line.strip();
				if (!t.isEmpty() && !t.startsWith("#")) {
					STEPS.add(t.split("\\s+"));
				}
			}
			log("loaded " + STEPS.size() + " steps");
		} catch (Exception e) {
			LOG.error("QA could not read the script", e);
		}
	}

	private static void log(String text) {
		LOG.info("QA " + text);
	}

	private static void pass(String text) {
		log("PASS " + text);
	}

	private static void fail(String text) {
		failures++;
		log("FAIL " + text);
	}

	private static String name(Screen s) {
		return s == null ? "none" : s.getClass().getSimpleName();
	}

	private static String rest(String[] c, int from) {
		return String.join(" ", Arrays.copyOfRange(c, from, c.length));
	}

	public static void tick(Minecraft mc) {
		if (done || step >= STEPS.size()) {
			if (!done) {
				done = true;
				log("DONE failures=" + failures);
				mc.stop();
			}
			return;
		}
		String[] c = STEPS.get(step);
		double elapsed = (System.nanoTime() - stepStart) / 1e9;
		if (stepStart == 0) {
			stepStart = System.nanoTime();
			elapsed = 0;
		}
		boolean next;
		try {
			next = run(mc, c, elapsed);
		} catch (Throwable e) {
			LOG.error("QA FAIL step " + String.join(" ", c), e);
			failures++;
			next = true;
		}
		if (next) {
			step++;
			stepStart = 0;
			seen = false;
		}
	}

	/** Runs one step; true when it is finished. */
	private static boolean run(Minecraft mc, String[] c, double elapsed) throws Exception {
		Screen screen = mc.gui.screen();
		switch (c[0]) {
			case "wait-title" -> {
				if (screen != null && screen.getClass().getSimpleName().contains("TitleScreen") && mc.gui.overlay() == null) {
					pass("title screen " + name(screen));
					return true;
				}
				return timeout(elapsed, c.length > 1 ? c[1] : "300", "title screen");
			}
			case "sleep" -> {
				return elapsed >= Double.parseDouble(c[1]);
			}
			case "log" -> log(rest(c, 1));
			case "shot" -> {
				Screenshot.grab(out.toFile(), c[1] + ".png", mc.gameRenderer.mainRenderTarget(), 1, m -> {
				});
				log("shot " + c[1] + " screen=" + name(screen) + " gui=" + mc.getWindow().getGuiScale() + " win=" + mc.getWindow().getWidth() + "x"
					+ mc.getWindow().getHeight());
			}
			case "screen" -> log("screen=" + name(screen));
			case "expect-screen" -> {
				if (screen != null && name(screen).equals(c[1])) {
					pass("screen " + c[1]);
					return true;
				}
				return timeout(elapsed, c.length > 2 ? c[2] : "10", "screen " + c[1] + " (now " + name(screen) + ")");
			}
			case "expect-null-screen" -> {
				if (screen == null) {
					pass("no screen (in game)");
					return true;
				}
				return timeout(elapsed, c.length > 1 ? c[1] : "5", "no screen (now " + name(screen) + ")");
			}
			case "widgets" -> {
				StringBuilder sb = new StringBuilder("widgets on " + name(screen) + ":");
				for (var child : screen.children()) {
					if (child instanceof AbstractWidget w) {
						sb.append(" | '").append(w.getMessage().getString().replace('\n', ' ')).append("' ").append(w.getX()).append(',').append(w.getY())
							.append(' ').append(w.getWidth()).append('x').append(w.getHeight()).append(w.visible ? "" : " hidden").append(w.active ? "" : " inactive");
					}
				}
				log(sb.toString());
			}
			case "keymap" -> {
				StringBuilder sb = new StringBuilder("keymap flint:");
				for (KeyMapping m : mc.options.keyMappings) {
					if (m.getName().contains("flintclient")) {
						sb.append(' ').append(m.getName()).append('=').append(m.saveString());
					}
				}
				log(sb.toString());
			}
			case "click-widget", "expect-widget", "expect-no-widget" -> {
				AbstractWidget found = widget(screen, rest(c, 1));
				String wanted = rest(c, 1);
				if (c[0].equals("expect-widget")) {
					if (found != null) {
						pass("widget '" + wanted + "' present" + (found.active ? "" : " (inactive)"));
					} else {
						fail("widget '" + wanted + "' missing on " + name(screen));
					}
				} else if (c[0].equals("expect-no-widget")) {
					if (found == null) {
						pass("no widget '" + wanted + "'");
					} else {
						fail("widget '" + wanted + "' present on " + name(screen));
					}
				} else if (found == null) {
					fail("widget '" + wanted + "' not found on " + name(screen));
				} else {
					// Flint's menus lay out in menu units (MenuScale); the mouse arrives in GUI units.
					double k = menuToGui(mc, screen);
					double x = (found.getX() + found.getWidth() / 2.0) * k;
					double y = (found.getY() + found.getHeight() / 2.0) * k;
					MouseButtonEvent e = new MouseButtonEvent(x, y, new MouseButtonInfo(1, 0));
					screen.mouseMoved(x, y);
					screen.mouseClicked(e, false);
					Screen after = mc.gui.screen();
					if (after != null) {
						after.mouseReleased(e);
					}
					log("clicked '" + found.getMessage().getString() + "'" + (found.active ? "" : " (inactive)"));
				}
			}
			case "click" -> {
				// click X Y: a left click at GUI coordinates (a list row, say).
				// "mid" for X: the screen's horizontal centre.
				double x = c[1].equals("mid") ? screen.width / 2.0 : Double.parseDouble(c[1]);
				double y = Double.parseDouble(c[2]);
				MouseButtonEvent e = new MouseButtonEvent(x, y, new MouseButtonInfo(1, 0));
				screen.mouseMoved(x, y);
				boolean handled = screen.mouseClicked(e, false);
				Screen after = mc.gui.screen();
				if (after != null) {
					after.mouseReleased(e);
				}
				log("click " + c[1] + "," + c[2] + " handled=" + handled);
			}
			case "select-server" -> {
				Field f = net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen.class.getDeclaredField("serverSelectionList");
				f.setAccessible(true);
				var list = (net.minecraft.client.gui.screens.multiplayer.ServerSelectionList) f.get(screen);
				var entry = list.children().get(Integer.parseInt(c[1]));
				list.setSelected(entry);
				log("selected server " + c[1] + ": " + (entry instanceof net.minecraft.client.gui.screens.multiplayer.ServerSelectionList.OnlineServerEntry o
					? o.getServerData().name : entry.getClass().getSimpleName()));
			}
			case "key" -> {
				KeyEvent e = new KeyEvent(scancode(c[1]), keycode(c[1]), c.length > 2 ? Integer.parseInt(c[2]) : 0);
				log("key " + c[1] + " handled=" + screen.keyPressed(e));
			}
			case "type" -> {
				String text = rest(c, 1);
				for (int i = 0; i < text.length(); i++) {
					screen.charTyped(new CharacterEvent(text.charAt(i)));
				}
			}
			case "create-world" -> mc.execute(() -> {
				try {
					LevelSettings settings = new LevelSettings(c[1], GameType.CREATIVE, LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT);
					mc.createWorldOpenFlows().createFreshLevel(c[1], settings, new WorldOptions(20261016L, false, false),
						WorldPresets::createNormalWorldDimensions, mc.gui.screen());
					log("world creation started: " + c[1]);
				} catch (Exception e) {
					LOG.error("QA FAIL create world", e);
				}
			});
			case "wait-world" -> {
				if (mc.level != null && mc.player != null && mc.gui.overlay() == null) {
					if (screen != null) {
						log("closing " + name(screen));
						mc.gui.setScreen(null);
						seen = false;
						return false;
					}
					if (!seen) {
						seen = true;
						stepStart = System.nanoTime();
						return false;
					}
					if (elapsed >= Double.parseDouble(c.length > 2 ? c[2] : "5")) {
						pass("in world");
						return true;
					}
					return false;
				}
				return timeout(elapsed, c.length > 1 ? c[1] : "180", "world join");
			}
			case "leave-world" -> mc.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
			case "gui-scale" -> {
				mc.options.guiScale().set(Integer.parseInt(c[1]));
				mc.resizeGui();
			}
			case "file" -> {
				Path p = mc.gameDirectory.toPath().resolve(c[1]);
				log("file " + c[1] + " = " + (Files.exists(p) ? Files.readString(p).replace('\n', ' ') : "(missing)"));
			}
			case "call" -> call(mc, c);
			case "await-key" -> {
				// The runner sees this line, posts the real key to the game window, then writes <out>/key-NAME.done.
				if (!seen) {
					seen = true;
					log("await-key " + c[1] + " " + c[2]);
				}
				if (Files.exists(out.resolve("key-" + c[1] + ".done"))) {
					pass("real key " + c[2] + " posted (" + c[1] + ")");
					return true;
				}
				return timeout(elapsed, "45", "real key " + c[2] + " (" + c[1] + ")");
			}
			case "await-signal" -> {
				// await-signal NAME [S]: waits until something outside the game writes <out>/signal-NAME.done.
				if (!seen) {
					seen = true;
					log("await-signal " + c[1]);
				}
				if (Files.exists(out.resolve("signal-" + c[1] + ".done"))) {
					pass("signal " + c[1]);
					return true;
				}
				return timeout(elapsed, c.length > 2 ? c[2] : "120", "signal " + c[1]);
			}
			case "quit" -> {
				done = true;
				log("DONE failures=" + failures);
				mc.stop();
			}
			default -> fail("unknown step " + c[0]);
		}
		return true;
	}

	private static boolean timeout(double elapsed, String limit, String what) {
		if (elapsed >= Double.parseDouble(limit)) {
			fail("timed out waiting for " + what);
			return true;
		}
		return false;
	}

	/** GUI units per layout unit on {@code screen}: Flint's own menu screens draw at MenuScale, others at the GUI scale. */
	private static double menuToGui(Minecraft mc, Screen screen) {
		if (!screen.getClass().getName().startsWith("com.flintclient.client.ui.screen.")) {
			return 1.0;
		}
		try {
			int pixels = (int) Class.forName("com.flintclient.client.ui.MenuScale").getMethod("pixels").invoke(null);
			return pixels / (double) Math.max(1, mc.getWindow().getGuiScale());
		} catch (ReflectiveOperationException e) {
			return 1.0;
		}
	}

	/** The first visible widget whose label equals the words (else starts with them), ignoring case. */
	private static AbstractWidget widget(Screen screen, String words) {
		String wanted = words.toLowerCase(Locale.ROOT);
		for (int pass = 0; pass < 2; pass++) {
			for (var child : screen.children()) {
				if (child instanceof AbstractWidget w && w.visible) {
					String label = w.getMessage().getString().toLowerCase(Locale.ROOT);
					if (pass == 0 ? label.equals(wanted) : label.startsWith(wanted)) {
						return w;
					}
				}
			}
		}
		return null;
	}

	/** SDL scancodes, as 26.3's key events carry them. */
	private static int scancode(String k) {
		return switch (k) {
			case "tab" -> 43;
			case "escape" -> 41;
			case "enter" -> 40;
			case "space" -> 44;
			case "backspace" -> 42;
			case "up" -> 82;
			case "down" -> 81;
			case "left" -> 80;
			case "right" -> 79;
			case "end" -> 77;
			case "rshift" -> 229;
			case "lshift" -> 225;
			default -> {
				if (k.length() == 1 && k.charAt(0) >= 'a' && k.charAt(0) <= 'z') {
					yield 4 + (k.charAt(0) - 'a');
				}
				throw new IllegalArgumentException("key " + k);
			}
		};
	}

	private static int keycode(String k) {
		return switch (k) {
			case "escape" -> 27;
			case "enter" -> 13;
			case "backspace" -> 8;
			case "tab" -> 9;
			case "space" -> 32;
			default -> k.length() == 1 ? k.charAt(0) : 0;
		};
	}

	private static void call(Minecraft mc, String[] c) throws Exception {
		String target = c[1];
		int dot = target.lastIndexOf('.');
		Class<?> cls = Class.forName(target.substring(0, dot));
		Object[] args = new Object[c.length - 2];
		for (int i = 2; i < c.length; i++) {
			args[i - 2] = switch (c[i]) {
				case "current" -> mc.gui.screen();
				case "null" -> null;
				case "true" -> Boolean.TRUE;
				case "false" -> Boolean.FALSE;
				default -> c[i].matches("-?\\d+") ? (Object) Integer.parseInt(c[i]) : c[i];
			};
		}
		for (Method m : cls.getMethods()) {
			if (m.getName().equals(target.substring(dot + 1)) && m.getParameterCount() == args.length && Modifier.isStatic(m.getModifiers())) {
				log("call " + target + " -> " + m.invoke(null, args));
				return;
			}
		}
		fail("no static method " + target + "/" + args.length);
	}
}
