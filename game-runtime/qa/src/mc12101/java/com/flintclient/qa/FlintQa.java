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
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.LevelSettings;
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
 * file PATH, call CLASS.METHOD [ARGS], await-key NAME KEY (the runner posts a real key press, then answers), nodes,
 * click-node NAME, reveal-node NAME, expect-node NAME, expect-no-node NAME (a Flint kit screen's element by its accessible name),
 * quit.
 */
public final class FlintQa implements ClientModInitializer {
	public static final boolean ENABLED = System.getProperty("flintqa.script") != null;
	/**
	 * True while the tour itself presses a mouse button through Minecraft's handler: during a tour every other button event
	 * (a real click on the window, which runs behind other windows) is dropped (mixin/MouseHandlerMixin).
	 */
	public static boolean tourClick;
	private static final Logger LOG = LoggerFactory.getLogger("FlintQA");
	private static final List<String[]> STEPS = new ArrayList<>();
	private static Path out;
	private static int step;
	private static long stepStart;
	private static int failures;
	private static boolean seen;
	/** When create-world started the world (System.nanoTime), for the join time wait-world logs once. */
	private static long worldStart;
	private static boolean worldJoined;
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
			log("loaded " + STEPS.size() + " steps uptimeMs=" + java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() + " threadCpuMs=" + java.lang.management.ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime() / 1000000);
		} catch (Exception e) {
			LOG.error("QA could not read the script", e);
		}
	}

	public static void log(String text) {
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
		return s == null ? "none" : QaNames.simpleName(s.getClass());
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
		Screen screen = QaGame.screen(mc);
		switch (c[0]) {
			case "wait-title" -> {
				if (screen != null && name(screen).contains("TitleScreen") && QaGame.overlay(mc) == null) {
					pass("title screen " + name(screen) + " uptimeMs=" + java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() + " cpuMs=" + ((com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime() / 1000000 + " threadCpuMs=" + java.lang.management.ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime() / 1000000);
					return true;
				}
				return timeout(elapsed, c.length > 1 ? c[1] : "300", "title screen");
			}
			case "sleep" -> {
				return elapsed >= Double.parseDouble(c[1]);
			}
			case "log" -> log(rest(c, 1));
			case "shot" -> {
				Screenshot.grab(out.toFile(), c[1] + ".png", QaGame.mainRenderTarget(mc), m -> {
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
					double[] e = {x, y, QaInput.LEFT};
					screen.mouseMoved(x, y);
					screen.mouseClicked(e[0], e[1], (int) e[2]);
					Screen after = QaGame.screen(mc);
					if (after != null) {
						after.mouseReleased(e[0], e[1], (int) e[2]);
					}
					log("clicked '" + found.getMessage().getString() + "'" + (found.active ? "" : " (inactive)"));
				}
			}
			case "click" -> {
				// click X Y [left|right] [MODIFIERS]: a click at GUI coordinates (a list row, say); left unless named, modifiers as
				// SDL's bits (1: Shift, 64: Ctrl), also right after X Y. "mid" for X: the screen's horizontal centre.
				double x = c[1].equals("mid") ? screen.width / 2.0 : Double.parseDouble(c[1]);
				double y = Double.parseDouble(c[2]);
				boolean named = c.length > 3 && (c[3].equals("left") || c[3].equals("right"));
				int button = named && c[3].equals("right") ? QaInput.RIGHT : QaInput.LEFT;
				int modifiers = named ? (c.length > 4 ? Integer.parseInt(c[4]) : 0) : (c.length > 3 ? Integer.parseInt(c[3]) : 0);
				double[] e = {x, y, button};
				screen.mouseMoved(x, y);
				boolean handled = screen.mouseClicked(e[0], e[1], (int) e[2]);
				Screen after = QaGame.screen(mc);
				if (after != null) {
					after.mouseReleased(e[0], e[1], (int) e[2]);
				}
				log("click " + c[1] + "," + c[2] + " handled=" + handled);
			}
			case "nodes" -> {
				// Every named, shown element of a Flint kit screen (its accessible name), for writing tours.
				StringBuilder sb = new StringBuilder("nodes on " + name(screen) + ":");
				for (Object[] n : kitNodes(screen)) {
					sb.append(" | '").append(n[0]).append('\'');
				}
				log(sb.toString());
			}
			case "reveal-node" -> {
				// Focuses the element as the keyboard would, which scrolls it into view; click it in a later step.
				Object[] found = kitNode(screen, rest(c, 1));
				if (found == null) {
					fail("node '" + rest(c, 1) + "' not found on " + name(screen));
				} else {
					Class<?> kit = Class.forName("com.flintclient.client.ui.kit.KitScreen");
					kit.getMethod("focusFromKeyboard", Class.forName("com.flintclient.client.ui.kit.Node")).invoke(screen, found[3]);
					log("revealed node '" + found[0] + "'");
				}
			}
			case "click-node", "expect-node", "expect-no-node" -> {
				// A Flint kit screen's element by its accessible name (equal, else starting with the words, ignoring case).
				String wanted = rest(c, 1);
				Object[] found = kitNode(screen, wanted);
				if (c[0].equals("expect-node")) {
					if (found != null) {
						pass("node '" + wanted + "' present");
					} else {
						fail("node '" + wanted + "' missing on " + name(screen));
					}
				} else if (c[0].equals("expect-no-node")) {
					if (found == null) {
						pass("no node '" + wanted + "'");
					} else {
						fail("node '" + wanted + "' present on " + name(screen));
					}
				} else if (found == null) {
					fail("node '" + wanted + "' not found on " + name(screen));
				} else {
					double x = (double) found[1];
					double y = (double) found[2];
					double[] e = {x, y, QaInput.LEFT};
					screen.mouseMoved(x, y);
					screen.mouseClicked(e[0], e[1], (int) e[2]);
					Screen after = QaGame.screen(mc);
					if (after != null) {
						after.mouseReleased(e[0], e[1], (int) e[2]);
					}
					log("clicked node '" + found[0] + "' at " + Math.round(x) + "," + Math.round(y));
				}
			}
			case "select-server" -> {
				Field f = QaNames.field(net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen.class, "serverSelectionList");
				var list = (net.minecraft.client.gui.screens.multiplayer.ServerSelectionList) f.get(screen);
				var entry = list.children().get(Integer.parseInt(c[1]));
				list.setSelected(entry);
				log("selected server " + c[1] + ": " + (entry instanceof net.minecraft.client.gui.screens.multiplayer.ServerSelectionList.OnlineServerEntry o
					? o.getServerData().name : QaNames.simpleName(entry.getClass())));
			}
			case "key" -> {
				int[] e = QaInput.key(c[1], c.length > 2 ? Integer.parseInt(c[2]) : 0);
				log("key " + c[1] + " handled=" + screen.keyPressed(e[0], e[1], e[2]));
			}
			case "type" -> {
				String text = rest(c, 1);
				for (int i = 0; i < text.length(); i++) {
					screen.charTyped(text.charAt(i), 0);
				}
			}
			case "create-world" -> {
				worldStart = System.nanoTime();
				worldJoined = false;
				mc.execute(() -> {
					try {
						LevelSettings settings = QaGame.creativeLevel(c[1]);
						mc.createWorldOpenFlows().createFreshLevel(c[1], settings, new WorldOptions(20261016L, false, false),
							WorldPresets::createNormalWorldDimensions, QaGame.screen(mc));
						log("world creation started: " + c[1]);
					} catch (Exception e) {
						LOG.error("QA FAIL create world", e);
					}
				});
			}
			case "wait-world" -> {
				if (mc.level != null && mc.player != null && QaGame.overlay(mc) == null) {
					if (!worldJoined && worldStart != 0) {
						worldJoined = true;
						log(String.format(java.util.Locale.ROOT, "world joined %d ms after create-world", (System.nanoTime() - worldStart) / 1_000_000));
					}
					if (screen != null) {
						log("closing " + name(screen));
						QaGame.setScreen(mc, null);
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
			case "leave-world" -> QaGame.leaveWorld(mc);
			case "gui-scale" -> {
				mc.options.guiScale().set(Integer.parseInt(c[1]));
				QaGame.resizeGui(mc);
			}
			case "option" -> {
				// A vanilla option by its accessor ("option showSubtitles true"): a switch, a number or a name.
				Object instance = QaNames.method(mc.options.getClass(), c[1]).invoke(mc.options);
				String v = c[2];
				Object value = v.equals("true") || v.equals("false") ? (Object) Boolean.valueOf(v) : v.matches("-?\\d+") ? (Object) Integer.valueOf(v)
					: v.matches("-?[\\d.]+") ? (Object) Double.valueOf(v) : v;
				Object now = QaNames.method(instance.getClass(), "get").invoke(instance);
				if (now instanceof Enum<?> e && value instanceof String name) {
					// An enum option by its constant's name ("option inactivityFpsLimit minimized").
					value = Enum.valueOf(e.getDeclaringClass().asSubclass(Enum.class), name.toUpperCase(java.util.Locale.ROOT));
				}
				QaNames.method(instance.getClass(), "set", Object.class).invoke(instance, value);
				log("option " + c[1] + " = " + value);
			}
			case "volume" -> {
				// volume CATEGORY VALUE ("volume master 0.0001"): a sound category's slider, so sounds play (and sound mods run)
				// in a game that stays inaudible; tours start muted, where Minecraft skips most sounds altogether.
				net.minecraft.sounds.SoundSource source = net.minecraft.sounds.SoundSource.valueOf(c[1].toUpperCase(java.util.Locale.ROOT));
				mc.options.getSoundSourceOptionInstance(source).set(Double.parseDouble(c[2]));
				log("volume " + source + " = " + c[2]);
			}
			case "cooldown" -> {
				// cooldown TICKS: the held item on cooldown, as an axe leaves a shield (client side only).
				mc.player.getCooldowns().addCooldown(mc.player.getMainHandItem().getItem(), Integer.parseInt(c[1]));
				log("cooldown " + c[1]);
			}
			case "pack" -> {
				// pack ID on|off: a resource pack turned on or off as the Resource Packs screen does (resources reload).
				var repository = mc.getResourcePackRepository();
				boolean changed = c[2].equals("on") ? repository.addPack(c[1]) : repository.removePack(c[1]);
				if (changed) {
					mc.options.updateResourcePacks(repository);
				}
				log("pack " + c[1] + " " + c[2] + " -> " + changed + " " + repository.getSelectedIds());
			}
			case "files" -> {
				Path p = mc.gameDirectory.toPath().resolve(c[1]);
				try (var list = Files.exists(p) ? Files.list(p) : java.util.stream.Stream.<Path>empty()) {
					log("files " + c[1] + " = " + list.map(x -> x.getFileName().toString()).sorted().toList());
				}
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
			case "command" -> {
				// A command as typed in chat, without the slash (the QA world allows them).
				mc.player.connection.sendCommand(rest(c, 1));
				log("command /" + rest(c, 1));
			}
			// Command feedback in the chat would cover a HUD element in the bottom-left corner.
			case "clear-chat" -> mc.execute(() -> QaGame.chat(mc).clearMessages(false));
			case "connect" -> {
				// Joins host[:port] as the server list does (the start of every server connection).
				net.minecraft.client.gui.screens.ConnectScreen.startConnecting(screen == null ? new net.minecraft.client.gui.screens.TitleScreen() : screen,
					mc, net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(c[1]),
					new net.minecraft.client.multiplayer.ServerData("QA", c[1], net.minecraft.client.multiplayer.ServerData.Type.OTHER), false, null);
				log("connecting to " + c[1]);
			}
			case "scroll" -> {
				// A mouse wheel notch (positive: up), through Minecraft's own handler.
				Method m = QaNames.method(net.minecraft.client.MouseHandler.class, "onScroll", long.class, double.class, double.class);
				m.invoke(mc.mouseHandler, mc.getWindow().getWindow(), 0.0, Double.parseDouble(c[1]));
				log("scrolled " + c[1]);
			}
			case "expect-slot" -> {
				int slot = mc.player.getInventory().selected;
				if (slot == Integer.parseInt(c[1])) {
					pass("hotbar slot " + slot);
				} else {
					fail("hotbar slot " + slot + ", expected " + c[1]);
				}
			}
			case "fps" -> log("fps " + mc.getFps() + " windowActive=" + mc.isWindowActive() + " limit="
				+ mc.getWindow().getFramerateLimit());
			case "frames" -> {
				// frames SECONDS NAME [DEG_PER_S]: every frame's time while the camera turns (QaFrames).
				if (!seen) {
					seen = true;
					QaFrames.begin(c.length > 3 ? Double.parseDouble(c[3]) : 0);
					return false;
				}
				if (elapsed < Double.parseDouble(c[1])) {
					return false;
				}
				log(QaFrames.end(c[2], out));
			}
			case "turn" -> {
				// turn DEG_PER_S: the camera keeps turning every frame, as a moving mouse turns it (0 stops).
				QaFrames.turn(Double.parseDouble(c[1]));
				log("turn " + c[1]);
			}
			case "mem" -> {
				// mem: the Java heap in use and its limit, after a collection, in MB (what the game holds, not garbage).
				Runtime rt = Runtime.getRuntime();
				System.gc();
				long used = (rt.totalMemory() - rt.freeMemory()) >> 20;
				log("mem used=" + used + "MB committed=" + (rt.totalMemory() >> 20) + "MB max=" + (rt.maxMemory() >> 20) + "MB");
			}
			case "pause" -> mc.pauseGame(false);
			case "expect-clipboard" -> {
				// expect-clipboard TEXT: what a copy put on the clipboard.
				String clip = mc.keyboardHandler.getClipboard();
				if (clip.equals(rest(c, 1))) {
					pass("clipboard '" + clip + "'");
				} else {
					fail("clipboard '" + clip + "', expected '" + rest(c, 1) + "'");
				}
			}
			case "chat-lines" -> {
				// The chat window's lines as shown now, newest first (what a tab or search leaves).
				StringBuilder sb = new StringBuilder("chat lines:");
				for (net.minecraft.util.FormattedCharSequence line : QaGame.chatLines(mc)) {
					StringBuilder text = new StringBuilder();
					line.accept((index, style, codePoint) -> {
						text.appendCodePoint(codePoint);
						return true;
					});
					sb.append(" | ").append(text);
				}
				log(sb.toString());
			}
			case "drag-node" -> {
				// "drag-node A > B": press on node A, move to node B in steps (as a real drag), release there.
				String[] names = rest(c, 1).split(" > ");
				Object[] from = kitNode(screen, names[0].trim());
				Object[] to = kitNode(screen, names[1].trim());
				if (from == null || to == null) {
					fail("drag-node: node '" + (from == null ? names[0] : names[1]) + "' not found on " + name(screen));
				} else {
					double x0 = (double) from[1];
					double y0 = (double) from[2];
					double x1 = (double) to[1];
					double y1 = (double) to[2];
					int left = QaInput.LEFT;
					screen.mouseMoved(x0, y0);
					screen.mouseClicked(x0, y0, left);
					double px = x0;
					double py = y0;
					for (int k = 1; k <= 12; k++) {
						double nx = x0 + (x1 - x0) * k / 12;
						double ny = y0 + (y1 - y0) * k / 12;
						screen.mouseMoved(nx, ny);
						screen.mouseDragged(nx, ny, left, nx - px, ny - py);
						px = nx;
						py = ny;
					}
					screen.mouseReleased(x1, y1, left);
					log("dragged '" + from[0] + "' to '" + to[0] + "'");
				}
			}
			case "drag" -> {
				// "drag X0 Y0 X1 Y1": press at a GUI point, move to the other in steps (as a real drag), release there.
				double x0 = Double.parseDouble(c[1]);
				double y0 = Double.parseDouble(c[2]);
				double x1 = Double.parseDouble(c[3]);
				double y1 = Double.parseDouble(c[4]);
				int left = QaInput.LEFT;
				screen.mouseMoved(x0, y0);
				screen.mouseClicked(x0, y0, left);
				double px = x0;
				double py = y0;
				for (int k = 1; k <= 12; k++) {
					double nx = x0 + (x1 - x0) * k / 12;
					double ny = y0 + (y1 - y0) * k / 12;
					screen.mouseMoved(nx, ny);
					screen.mouseDragged(nx, ny, left, nx - px, ny - py);
					px = nx;
					py = ny;
				}
				screen.mouseReleased(x1, y1, left);
				log("dragged " + x0 + "," + y0 + " to " + x1 + "," + y1);
			}
			case "log-waypoints" -> {
				// Each waypoint of this world (Flint's classes, by reflection), and where it falls on screen (normalised coordinates).
				Object store = Class.forName("com.flintclient.client.control.feature.Features").getMethod("waypoints").invoke(null);
				for (Object wp : (java.util.List<?>) store.getClass().getMethod("all").invoke(store)) {
					int x = (int) wp.getClass().getMethod("x").invoke(wp);
					int y = (int) wp.getClass().getMethod("y").invoke(wp);
					int z = (int) wp.getClass().getMethod("z").invoke(wp);
					Object ndc = Class.forName("com.flintclient.client.compat.McCompat").getMethod("projectToScreen", net.minecraft.world.phys.Vec3.class).invoke(null, new net.minecraft.world.phys.Vec3(x + 0.5, y + 0.5, z + 0.5));
					log("waypoint " + wp + " ndc=" + ndc + " camera=" + QaGame.mainCamera(mc).getPosition());
				}
			}
			case "view" -> {
				// first, back or front: the camera perspective, as F5 sets it.
				mc.options.setCameraType(switch (c[1]) {
					case "back" -> net.minecraft.client.CameraType.THIRD_PERSON_BACK;
					case "front" -> net.minecraft.client.CameraType.THIRD_PERSON_FRONT;
					default -> net.minecraft.client.CameraType.FIRST_PERSON;
				});
				log("view " + c[1]);
			}
			case "hotbar" -> {
				mc.player.getInventory().selected = Integer.parseInt(c[1]);
				log("hotbar slot " + c[1]);
			}
			case "set", "mod" -> {
				// set MODULE SETTING JSON, or mod MODULE on|off: through Flint's own settings model, as the menu changes them.
				Object layout = Class.forName("com.flintclient.client.control.ControlState").getMethod("layout").invoke(null);
				Object module = layout.getClass().getMethod("module", String.class).invoke(layout, c[1]);
				if (module == null) {
					fail("no module " + c[1]);
				} else if (c[0].equals("mod")) {
					log("mod " + c[1] + " " + c[2] + " -> " + module.getClass().getMethod("setEnabled", boolean.class).invoke(module, c[2].equals("on")));
				} else {
					Object descriptor = module.getClass().getMethod("descriptor").invoke(module);
					Object setting = descriptor.getClass().getMethod("setting", String.class).invoke(descriptor, c[2]);
					if (setting == null) {
						fail("no setting " + c[1] + "/" + c[2]);
					} else {
						Method set = module.getClass().getMethod("setFromJson", Class.forName("com.flintclient.client.control.model.SettingDescriptor"),
							com.google.gson.JsonElement.class);
						log("set " + c[1] + "/" + c[2] + " " + rest(c, 3) + " -> " + set.invoke(module, setting, com.google.gson.JsonParser.parseString(rest(c, 3))));
					}
				}
			}
			case "press" -> {
				// press NAME down|up: a key in the game as a real one arrives: the key state Flint reads mod keys from and
				// Minecraft's keyboard handler (its key mappings: W walks, Tab lists players, E opens the inventory).
				int code = com.mojang.blaze3d.platform.InputConstants.getKey(c[1]).getValue();
				boolean down = c[2].equals("down");
				QaInput.hold(code, down);
				String tail = c[1].substring(c[1].lastIndexOf('.') + 1);
				QaInput.keyPress(mc, code, tail.length() == 1 ? tail.charAt(0) : 0, down);
				log("press " + c[1] + " " + c[2]);
			}
			case "mouse" -> {
				// mouse left|right down|up: a mouse button in the game, through Minecraft's own handler (clicks count for CPS).
				int button = c[1].equals("right") ? QaInput.RIGHT : QaInput.LEFT;
				tourClick = true;
				try {
					QaInput.mouseButton(mc, button, c[2].equals("down"));
				} finally {
					tourClick = false;
				}
				log("mouse " + c[1] + " " + c[2]);
			}
			case "stray-mouse" -> {
				// stray-mouse left|right down|up: a button event the tour didn't make (as a real click on the window would arrive),
				// to check that tours ignore them.
				int button = c[1].equals("right") ? QaInput.RIGHT : QaInput.LEFT;
				QaInput.mouseButton(mc, button, c[2].equals("down"));
				log("stray-mouse " + c[1] + " " + c[2]);
			}
			case "hover" -> {
				// hover X Y: the pointer at GUI coordinates, where Minecraft looks for it (tooltips), without clicking.
				double scale = mc.getWindow().getGuiScale();
				for (String field : new String[] {"xpos", "ypos"}) {
					Field f = QaNames.field(net.minecraft.client.MouseHandler.class, field);
					f.setDouble(mc.mouseHandler, Double.parseDouble(field.equals("xpos") ? c[1] : c[2]) * scale);
				}
				if (screen != null) {
					screen.mouseMoved(Double.parseDouble(c[1]), Double.parseDouble(c[2]));
				}
				log("hover " + c[1] + " " + c[2]);
			}
			case "entities" -> {
				// Every entity within 32 blocks of the player: its type and distance (what a shot shows near the camera).
				StringBuilder sb = new StringBuilder("entities:");
				for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
					if (e != mc.player && e.distanceTo(mc.player) < 32) {
						sb.append(" | ").append(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType())).append(String.format(java.util.Locale.ROOT, " %.1f", e.distanceTo(mc.player)));
					}
				}
				log(sb.toString());
			}
			case "hold-key" -> {
				// hold-key NAME on|off: a keyboard key (key.keyboard.c) held or let go in the key state Flint reads mod keys
				// from; the window stays in the background. Minecraft's own key mappings don't see it.
				int code = com.mojang.blaze3d.platform.InputConstants.getKey(c[1]).getValue();
				QaInput.hold(code, c[2].equals("on"));
				log("hold-key " + c[1] + " (" + code + ") " + c[2]);
			}
			case "attack", "use" -> {
				// A left (attack) or right (use) click in the world, as Minecraft handles one.
				Method m = QaNames.method(Minecraft.class, c[0].equals("attack") ? "startAttack" : "startUseItem");
				log(c[0] + " -> " + m.invoke(mc));
			}
			case "expect-camera" -> {
				// first_person, third_person_back or third_person_front
				String view = mc.options.getCameraType().name().toLowerCase(java.util.Locale.ROOT);
				if (view.equals(c[1])) {
					pass("camera " + view);
				} else {
					fail("camera " + view + ", expected " + c[1]);
				}
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
			double pixels = ((Number) Class.forName("com.flintclient.client.ui.MenuScale").getMethod("pixels").invoke(null)).doubleValue();
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

	/**
	 * The named, shown elements of a Flint kit screen ({@code ui.kit.KitScreen}), read through its public API by reflection
	 * (this mod doesn't compile against Flint): each as {name, centre x, centre y} in GUI units. Hidden subtrees are skipped.
	 */
	private static List<Object[]> kitNodes(Screen screen) {
		List<Object[]> out = new ArrayList<>();
		try {
			Class<?> kit = Class.forName("com.flintclient.client.ui.kit.KitScreen");
			if (!kit.isInstance(screen)) {
				return out;
			}
			double perUi = (int) kit.getMethod("uiScale").invoke(screen) / (double) Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
			walk(kit.getMethod("root").invoke(screen), perUi, out);
		} catch (ReflectiveOperationException e) {
			LOG.error("QA could not read the kit screen", e);
		}
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

	private static Object[] kitNode(Screen screen, String words) {
		String wanted = words.toLowerCase(Locale.ROOT);
		List<Object[]> all = kitNodes(screen);
		for (int pass = 0; pass < 2; pass++) {
			for (Object[] n : all) {
				String name = String.valueOf(n[0]).toLowerCase(Locale.ROOT);
				if (pass == 0 ? name.equals(wanted) : name.startsWith(wanted)) {
					return n;
				}
			}
		}
		return null;
	}

	private static void call(Minecraft mc, String[] c) throws Exception {
		String target = c[1];
		int dot = target.lastIndexOf('.');
		Class<?> cls = Class.forName(target.substring(0, dot));
		Object[] args = new Object[c.length - 2];
		for (int i = 2; i < c.length; i++) {
			args[i - 2] = switch (c[i]) {
				case "current" -> QaGame.screen(mc);
				case "null" -> null;
				case "true" -> Boolean.TRUE;
				case "false" -> Boolean.FALSE;
				default -> c[i].matches("-?\\d+") ? (Object) Integer.parseInt(c[i])
					: c[i].startsWith("uuid:") ? (Object) java.util.UUID.fromString(c[i].substring(5)) : c[i];
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
