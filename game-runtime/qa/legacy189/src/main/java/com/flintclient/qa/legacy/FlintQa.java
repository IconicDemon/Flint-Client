package com.flintclient.qa.legacy;

import com.flintclient.qa.legacy.mixin.ScreenAccessor;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.menu.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.MultiplayerServerListPinger;
import net.minecraft.client.options.KeyBinding;
import net.minecraft.client.options.ServerListEntry;
import net.minecraft.client.render.Window;
import net.minecraft.client.sound.instance.SimpleSoundInstance;
import net.minecraft.resource.Identifier;
import net.minecraft.text.Text;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.gen.WorldGeneratorType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Keyboard;

/**
 * Dev-only QA driver for Minecraft 1.8.9: runs the script named by {@code -Dflintqa.script}, one step per client tick, writing
 * screenshots to {@code -Dflintqa.out}/screenshots. Every line it logs starts with "QA "; "QA PASS" / "QA FAIL" lines are the
 * results, and "QA DONE failures=N" ends the run. Inert without the property. Never shipped in Flint.
 *
 * <p>Steps (one per line, # for comments): wait-title S, sleep S, log TEXT, shot NAME, screen, expect-screen CLASS [S],
 * expect-null-screen [S], create-world NAME [flat|normal] [SEED], wait-world [S] [SETTLE], command TEXT, key NAME (a key press:
 * to the screen when one is open, else to the key bindings), press NAME down|up, type TEXT, move X Y (the pointer, in window
 * pixels from the top left), click [BUTTON], mouse-down [BUTTON], mouse-drag X Y, mouse-up [BUTTON], scroll N (wheel notches:
 * positive is up), nodes (every named element of a Flint kit screen), reveal-node NAME (focused as the keyboard would, scrolled
 * into view), move-node NAME (the pointer to its middle), click-node NAME and expect-node NAME / expect-no-node NAME (an element of a Flint screen by its accessible
 * name), widgets (every button of a Minecraft screen: its label, place and width), click-widget LABEL (a Minecraft screen's
 * button by its label, equal or else starting with it; an icon button's label is its name), call CLASS.METHOD [ARGS], look YAW PITCH, fps SECONDS NAME (frames drawn over the span), perspective N (0 first person, 1 behind, 2 in front), gui-scale N,
 * java MAJOR (the game runs on that Java), clipboard TEXT (a round trip through Minecraft's clipboard, which is restored),
 * sound ID (plays a sound event), f2 (what F2 does: a screenshot in the game folder and its chat line), ping-server ADDRESS [S]
 * (the multiplayer list's status ping), await-signal NAME [S] (waits for <out>/signal-NAME.done, written from outside), quit.
 */
public final class FlintQa implements ClientModInitializer {
	public static final boolean ENABLED = System.getProperty("flintqa.script") != null;
	private static final Logger LOG = LogManager.getLogger("FlintQA");
	private static final List<String[]> STEPS = new ArrayList<String[]>();
	/** Steps that need the world in view, not a menu. */
	private static final List<String> WORLD_STEPS = Arrays.asList("wait-world", "fps", "turn", "look", "perspective");
	private static final String HOOKS = "com.flintclient.legacy.ui.QaHooks";
	private static File out;
	/** The signal await-signal last announced in the log (once per step). */
	private static String signalAnnounced = "";
	private static int step;
	private static long stepStart;
	private static int failures;
	private static boolean done;
	/** Frames drawn so far (counted by {@link com.flintclient.qa.legacy.mixin.GameRendererMixin}). */
	public static long frames;
	/** When create-world started the world (System.nanoTime), for the join time wait-world logs once. */
	private static long worldStart;
	private static boolean worldJoined;
	/** The turn step's rate in degrees a second (0: still) and the last frame it turned on. */
	private static double turning;
	private static long turnLast;

	/** Each frame: turns the camera by the turn step's rate over this frame's time, as a moving mouse does. */
	public static void turnFrame(Minecraft mc) {
		long now = System.nanoTime();
		if (turning != 0 && turnLast != 0 && mc.player != null) {
			// As Entity's mouse turn does: the previous yaw moves too, so the frame's interpolation doesn't undo it.
			float step = (float) (turning * (now - turnLast) / 1e9);
			mc.player.yaw += step;
			mc.player.lastYaw += step;
		}
		turnLast = now;
	}

	/** The pointer QA moves, in window pixels from the top left; -1 while the real mouse is used. */
	public static int mouseX = -1;
	public static int mouseY = -1;
	private static int heldButton = -1;
	private static long fpsFrames;
	private static int releaseKey = -1;
	private static MultiplayerServerListPinger pinger;
	private static ServerListEntry pinged;

	@Override
	public void onInitializeClient() {
		if (!ENABLED) {
			return;
		}
		try {
			out = new File(System.getProperty("flintqa.out", "."));
			new File(out, "screenshots").mkdirs();
			for (String line : Files.readAllLines(new File(System.getProperty("flintqa.script")).toPath(), StandardCharsets.UTF_8)) {
				String t = line.trim();
				if (!t.isEmpty() && !t.startsWith("#")) {
					STEPS.add(t.split("\\s+"));
				}
			}
			log("loaded " + STEPS.size() + " steps uptimeMs=" + java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() + " threadCpuMs=" + java.lang.management.ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime() / 1000000);
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
		StringBuilder b = new StringBuilder();
		for (int i = from; i < c.length; i++) {
			if (b.length() > 0) {
				b.append(' ');
			}
			b.append(c[i]);
		}
		return b.toString();
	}

	public static void tick(Minecraft mc) {
		if (releaseKey >= 0) {
			KeyBinding.set(releaseKey, false);
			releaseKey = -1;
		}
		if (done || step >= STEPS.size()) {
			if (!done) {
				done = true;
				log("DONE failures=" + failures);
				mc.stop();
			}
			return;
		}
		String[] c = STEPS.get(step);
		if (stepStart == 0) {
			stepStart = System.nanoTime();
		}
		// Benchmarks keep the game behind other windows, and there a screen that pauses the game can open a moment after
		// joining a world (the pause menu when the window isn't focused, or another), which would hold a world step forever.
		// While a step needs the world, such a screen is closed again and named in the log.
		if (mc.screen != null && mc.world != null && mc.player != null && mc.screen.shouldPauseGame() && WORLD_STEPS.contains(c[0])) {
			String name = mc.screen.getClass().getName();
			mc.options.pauseOnUnfocus = false;
			mc.openScreen(null);
			log("closed a screen that paused the game: " + name);
		}
		double elapsed = (System.nanoTime() - stepStart) / 1e9;
		boolean next;
		try {
			next = run(mc, c, elapsed);
		} catch (Throwable e) {
			LOG.error("QA FAIL step " + rest(c, 0), e);
			failures++;
			next = true;
		}
		if (next) {
			step++;
			stepStart = 0;
		}
	}

	private static boolean timeout(double elapsed, String seconds, String what) {
		if (elapsed > Double.parseDouble(seconds)) {
			fail("timed out waiting for " + what);
			return true;
		}
		return false;
	}

	/** The pointer in the open screen's own coordinates. */
	private static int guiX(Minecraft mc) {
		return mouseX * new Window(mc).getWidth() / mc.width;
	}

	private static int guiY(Minecraft mc) {
		return mouseY * new Window(mc).getHeight() / mc.height;
	}

	private static Object hook(String method, Class<?>[] types, Object... args) throws Exception {
		Class<?> hooks = Class.forName(HOOKS);
		return hooks.getMethod(method, types).invoke(null, args);
	}

	private static void moveTo(int x, int y) {
		mouseX = x;
		mouseY = y;
		try {
			hook("move", new Class<?>[] {int.class, int.class}, x, y);
		} catch (ClassNotFoundException ignored) {
			// No Flint runtime (a baseline run).
		} catch (Exception e) {
			LOG.warn("QA could not move Flint's pointer", e);
		}
	}

	private static int key(String name) {
		int code = Keyboard.getKeyIndex(name.toUpperCase(java.util.Locale.ROOT));
		if (code == Keyboard.KEY_NONE) {
			throw new IllegalArgumentException("no key " + name);
		}
		return code;
	}

	/** Runs one step; true when it is finished. */
	private static boolean run(Minecraft mc, String[] c, double elapsed) throws Exception {
		Screen screen = mc.screen;
		String op = c[0];
		if (op.equals("wait-title")) {
			if (screen instanceof TitleScreen) {
				pass("title screen " + name(screen) + " uptimeMs=" + java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() + " cpuMs=" + ((com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime() / 1000000 + " threadCpuMs=" + java.lang.management.ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime() / 1000000);
				return true;
			}
			return timeout(elapsed, c.length > 1 ? c[1] : "300", "title screen");
		} else if (op.equals("sleep")) {
			return elapsed >= Double.parseDouble(c[1]);
		} else if (op.equals("log")) {
			log(rest(c, 1));
		} else if (op.equals("shot")) {
			Screenshot.take(out, c[1] + ".png", mc.width, mc.height, mc.getRenderTarget());
			log("shot " + c[1] + " screen=" + name(screen) + " gui=" + new Window(mc).getScale() + " win=" + mc.width + "x" + mc.height);
		} else if (op.equals("screen")) {
			log("screen=" + name(screen));
		} else if (op.equals("expect-screen")) {
			if (screen != null && name(screen).equals(c[1])) {
				pass("screen " + c[1]);
				return true;
			}
			return timeout(elapsed, c.length > 2 ? c[2] : "0", "screen " + c[1] + " (now " + name(screen) + ")");
		} else if (op.equals("expect-null-screen")) {
			if (screen == null) {
				pass("no screen");
				return true;
			}
			return timeout(elapsed, c.length > 1 ? c[1] : "0", "no screen (now " + name(screen) + ")");
		} else if (op.equals("create-world")) {
			boolean flat = c.length < 3 || c[2].equals("flat");
			long seed = c.length > 3 ? Long.parseLong(c[3]) : 189L;
			WorldSettings settings = new WorldSettings(seed, WorldSettings.GameMode.CREATIVE, true, false,
				flat ? WorldGeneratorType.FLAT : WorldGeneratorType.DEFAULT).enableCommands();
			worldStart = System.nanoTime();
			worldJoined = false;
			mc.startGame(c[1], c[1], settings);
			log("creating world " + c[1]);
		} else if (op.equals("wait-world")) {
			double settle = c.length > 2 ? Double.parseDouble(c[2]) : 3;
			if (mc.world != null && mc.player != null && screen == null) {
				if (!worldJoined && worldStart != 0) {
					worldJoined = true;
					log(String.format(java.util.Locale.ROOT, "world joined %d ms after create-world", (System.nanoTime() - worldStart) / 1000000));
				}
				if (elapsed >= settle) {
					pass("in world");
					return true;
				}
				return false;
			}
			if (mc.world == null || screen != null) {
				stepStart = System.nanoTime() - (long) (Math.min(elapsed, 0) * 1e9);
			}
			return timeout(elapsed, c.length > 1 ? c[1] : "300", "world");
		} else if (op.equals("command")) {
			mc.player.sendChat("/" + rest(c, 1));
		} else if (op.equals("key")) {
			int code = key(c[1]);
			if (screen != null) {
				((ScreenAccessor) screen).flintqa$keyPressed(Keyboard.getKeyName(code).length() == 1 ? Keyboard.getKeyName(code).charAt(0) : '\0', code);
			} else {
				KeyBinding.set(code, true);
				KeyBinding.click(code);
				releaseKey = code;
			}
		} else if (op.equals("press")) {
			int code = key(c[1]);
			boolean down = c[2].equals("down");
			KeyBinding.set(code, down);
			if (down) {
				KeyBinding.click(code);
			}
		} else if (op.equals("type")) {
			String text = rest(c, 1);
			for (int i = 0; i < text.length(); i++) {
				((ScreenAccessor) screen).flintqa$keyPressed(text.charAt(i), 0);
			}
		} else if (op.equals("move")) {
			moveTo(Integer.parseInt(c[1]), Integer.parseInt(c[2]));
		} else if (op.equals("click")) {
			int button = c.length > 1 ? Integer.parseInt(c[1]) : 0;
			((ScreenAccessor) screen).flintqa$mouseClicked(guiX(mc), guiY(mc), button);
			((ScreenAccessor) screen).flintqa$mouseReleased(guiX(mc), guiY(mc), button);
		} else if (op.equals("mouse-down")) {
			heldButton = c.length > 1 ? Integer.parseInt(c[1]) : 0;
			((ScreenAccessor) screen).flintqa$mouseClicked(guiX(mc), guiY(mc), heldButton);
		} else if (op.equals("mouse-drag")) {
			moveTo(Integer.parseInt(c[1]), Integer.parseInt(c[2]));
			((ScreenAccessor) screen).flintqa$mouseDragged(guiX(mc), guiY(mc), Math.max(0, heldButton), 100L);
		} else if (op.equals("mouse-up")) {
			((ScreenAccessor) screen).flintqa$mouseReleased(guiX(mc), guiY(mc), Math.max(0, heldButton));
			heldButton = -1;
		} else if (op.equals("scroll")) {
			Method scrolled = screen.getClass().getMethod("scrolled", int.class);
			scrolled.invoke(screen, Integer.parseInt(c[1]));
		} else if (op.equals("widgets")) {
			StringBuilder b = new StringBuilder();
			for (ButtonWidget w : ((ScreenAccessor) screen).flintqa$buttons()) {
				b.append(" | '").append(w.message).append("' ").append(w.x).append(',').append(w.y).append(' ').append(w.getWidth())
					.append(w.visible ? "" : " hidden").append(w.active ? "" : " inactive");
			}
			log("widgets on " + name(screen) + ":" + b);
		} else if (op.equals("click-widget")) {
			String wanted = rest(c, 1).toLowerCase(java.util.Locale.ROOT);
			ButtonWidget hit = null;
			for (int pass = 0; pass < 2 && hit == null; pass++) {
				for (ButtonWidget w : ((ScreenAccessor) screen).flintqa$buttons()) {
					String label = w.message.toLowerCase(java.util.Locale.ROOT);
					if (w.visible && (pass == 0 ? label.equals(wanted) : label.startsWith(wanted))) {
						hit = w;
						break;
					}
				}
			}
			if (hit == null) {
				fail("no widget " + rest(c, 1) + " on " + name(screen));
			} else {
				int gx = hit.x + hit.getWidth() / 2;
				int gy = hit.y + 10;
				Window window = new Window(mc);
				moveTo(gx * mc.width / window.getWidth(), gy * mc.height / window.getHeight());
				((ScreenAccessor) screen).flintqa$mouseClicked(gx, gy, 0);
				((ScreenAccessor) screen).flintqa$mouseReleased(gx, gy, 0);
				log("clicked '" + hit.message + "'");
			}
		} else if (op.equals("nodes")) {
			log("nodes on " + name(screen) + ":" + hook("names", new Class<?>[0]));
		} else if (op.equals("reveal-node")) {
			String node = rest(c, 1);
			if (Boolean.TRUE.equals(hook("reveal", new Class<?>[] {String.class}, node))) {
				log("revealed node " + node);
			} else {
				fail("no node " + node + " on " + name(screen));
			}
		} else if (op.equals("move-node")) {
			String node = rest(c, 1);
			int[] b = (int[]) hook("bounds", new Class<?>[] {String.class}, node);
			if (b == null) {
				fail("no node " + node + " on " + name(screen));
			} else {
				moveTo(b[0] + b[2] / 2, b[1] + b[3] / 2);
			}
		} else if (op.equals("click-node") || op.equals("expect-node") || op.equals("expect-no-node")) {
			String node = rest(c, 1);
			int[] b = (int[]) hook("bounds", new Class<?>[] {String.class}, node);
			if (op.equals("expect-no-node")) {
				if (b == null) {
					pass("no node " + node);
				} else {
					fail("node " + node + " is shown");
				}
			} else if (b == null) {
				fail("no node " + node + " on " + name(screen));
			} else if (op.equals("expect-node")) {
				pass("node " + node);
			} else {
				moveTo(b[0] + b[2] / 2, b[1] + b[3] / 2);
				((ScreenAccessor) screen).flintqa$mouseClicked(guiX(mc), guiY(mc), 0);
				((ScreenAccessor) screen).flintqa$mouseReleased(guiX(mc), guiY(mc), 0);
				log("clicked " + node);
			}
		} else if (op.equals("call")) {
			call(c);
		} else if (op.equals("await-signal")) {
			// await-signal NAME [S]: waits until something outside the game writes <out>/signal-NAME.done (as on the newer lines).
			if (!signalAnnounced.equals(c[1])) {
				signalAnnounced = c[1];
				log("await-signal " + c[1]);
			}
			if (new File(out, "signal-" + c[1] + ".done").exists()) {
				pass("signal " + c[1]);
				return true;
			}
			return timeout(elapsed, c.length > 2 ? c[2] : "120", "signal " + c[1]);
		} else if (op.equals("look")) {
			mc.player.yaw = Float.parseFloat(c[1]);
			mc.player.pitch = Float.parseFloat(c[2]);
			mc.player.lastYaw = mc.player.yaw;
			mc.player.lastPitch = mc.player.pitch;
		} else if (op.equals("turn")) {
			// turn DEG_PER_S: the camera keeps turning every frame (0 stops).
			turning = Double.parseDouble(c[1]);
		} else if (op.equals("render-distance")) {
			// render-distance CHUNKS: as the Video Settings slider sets it (the world reloads its chunks at the new distance).
			mc.options.viewDistance = Integer.parseInt(c[1]);
			mc.worldRenderer.reload();
			log("render distance " + c[1]);
		} else if (op.equals("frames")) {
			// frames SECONDS NAME [DEG_PER_S]: every frame's time for SECONDS, turning at DEG_PER_S (LegacyFrames).
			if (!LegacyFrames.recording()) {
				if (c.length > 3) {
					turning = Double.parseDouble(c[3]);
				}
				LegacyFrames.begin();
				stepStart = System.nanoTime();
				return false;
			}
			if (elapsed < Double.parseDouble(c[1])) {
				return false;
			}
			turning = 0;
			try {
				log(LegacyFrames.end(c[2], out));
			} catch (Exception e) {
				fail("frames " + c[2] + ": " + e);
			}
		} else if (op.equals("mem")) {
			// mem: the Java heap in use and its limit, after a collection, in MB.
			Runtime rt = Runtime.getRuntime();
			System.gc();
			long used = (rt.totalMemory() - rt.freeMemory()) >> 20;
			log("mem used=" + used + "MB committed=" + (rt.totalMemory() >> 20) + "MB max=" + (rt.maxMemory() >> 20) + "MB");
		} else if (op.equals("fps")) {
			double seconds = Double.parseDouble(c[1]);
			if (fpsFrames == 0) {
				fpsFrames = frames + 1;
				return false;
			}
			if (elapsed < seconds) {
				return false;
			}
			long counted = frames - (fpsFrames - 1);
			fpsFrames = 0;
			log(String.format(java.util.Locale.ROOT, "FPS %s avg=%.1f frames=%d seconds=%.2f", c[2], counted / elapsed, counted, elapsed));
		} else if (op.equals("perspective")) {
			mc.options.perspective = Integer.parseInt(c[1]);
		} else if (op.equals("gui-scale")) {
			mc.options.guiScale = Integer.parseInt(c[1]);
			if (screen != null) {
				Window w = new Window(mc);
				screen.init(mc, w.getWidth(), w.getHeight());
			}
		} else if (op.equals("java")) {
			String spec = System.getProperty("java.specification.version");
			String what = "Java " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ", " + System.getProperty("java.home") + ")";
			if (spec.equals(c[1]) || spec.equals("1." + c[1])) {
				pass("runs on " + what);
			} else {
				fail("runs on " + what + ", not Java " + c[1]);
			}
		} else if (op.equals("clipboard")) {
			// Minecraft's own clipboard (AWT's Toolkit): writes TEXT, reads it back, then puts the owner's clipboard back.
			String before = Screen.getClipboard();
			String text = rest(c, 1);
			Screen.setClipboard(text);
			String read = Screen.getClipboard();
			Screen.setClipboard(before);
			if (text.equals(read)) {
				pass("clipboard round trip \"" + text + "\" win=" + mc.width + "x" + mc.height);
			} else {
				fail("clipboard read \"" + read + "\" after writing \"" + text + "\"");
			}
		} else if (op.equals("sound")) {
			mc.getSoundManager().play(SimpleSoundInstance.of(new Identifier(c[1]), 1.0F));
			pass("played " + c[1]);
		} else if (op.equals("f2")) {
			// What F2 does (Minecraft's keyboard handler): a screenshot in the game folder and its chat line.
			File dir = new File(mc.gameDir, "screenshots");
			int before = dir.isDirectory() ? dir.list().length : 0;
			Text message = Screenshot.take(mc.gameDir, mc.width, mc.height, mc.getRenderTarget());
			mc.gui.getChat().addMessage(message);
			int after = dir.isDirectory() ? dir.list().length : 0;
			if (after == before + 1) {
				pass("F2 screenshot saved: " + message.getString());
			} else {
				fail("F2 screenshot: " + message.getString() + " (files " + before + " -> " + after + ")");
			}
		} else if (op.equals("ping-server")) {
			// The multiplayer list's status ping (Netty over TCP): MOTD, version and round trip.
			if (pinger == null) {
				pinger = new MultiplayerServerListPinger();
				pinged = new ServerListEntry("QA", c[1], false);
				pinger.add(pinged);
				log("pinging " + c[1]);
				return false;
			}
			pinger.tick();
			if (pinged.ping > 0 && pinged.version != null) {
				pass("server " + c[1] + " answered: ping=" + pinged.ping + "ms version=" + pinged.version + " protocol=" + pinged.protocol
					+ " players=" + pinged.onlinePlayers);
			} else if (elapsed < Double.parseDouble(c.length > 2 ? c[2] : "15")) {
				return false;
			} else {
				fail("server " + c[1] + " didn't answer: ping=" + pinged.ping + " motd=" + pinged.motd);
			}
			pinger.cancel();
			pinger = null;
		} else if (op.equals("mod") || op.equals("set")) {
			// mod MODULE on|off, or set MODULE SETTING JSON: through Flint's shared settings model, as the menu changes them.
			Object layout = Class.forName("com.flintclient.client.control.ControlState").getMethod("layout").invoke(null);
			Object module = layout.getClass().getMethod("module", String.class).invoke(layout, c[1]);
			if (module == null) {
				fail("no module " + c[1]);
			} else if (op.equals("mod")) {
				log("mod " + c[1] + " " + c[2] + " -> " + module.getClass().getMethod("setEnabled", boolean.class).invoke(module, c[2].equals("on")));
			} else {
				Object descriptor = module.getClass().getMethod("descriptor").invoke(module);
				Object setting = descriptor.getClass().getMethod("setting", String.class).invoke(descriptor, c[2]);
				Class<?> parser = Class.forName("com.flintclient.shaded.gson.JsonParser");
				Object json = parser.getMethod("parseString", String.class).invoke(null, rest(c, 3));
				if (setting == null) {
					fail("no setting " + c[1] + "/" + c[2]);
				} else {
					Method set = null;
					for (Method m : module.getClass().getMethods()) {
						if (m.getName().equals("setFromJson") && m.getParameterTypes().length == 2) {
							set = m;
						}
					}
					log("set " + c[1] + "/" + c[2] + " " + rest(c, 3) + " -> " + set.invoke(module, setting, json));
				}
			}
		} else if (op.equals("quit")) {
			done = true;
			log("DONE failures=" + failures);
			mc.stop();
		} else {
			fail("unknown step " + op);
		}
		return true;
	}

	/** call CLASS.METHOD [ARGS]: a public static method, its arguments as text (numbers and booleans converted). */
	private static void call(String[] c) throws Exception {
		int dot = c[1].lastIndexOf('.');
		Class<?> owner = Class.forName(c[1].substring(0, dot));
		String method = c[1].substring(dot + 1);
		String[] args = Arrays.copyOfRange(c, 2, c.length);
		for (Method m : owner.getMethods()) {
			if (!m.getName().equals(method) || !Modifier.isStatic(m.getModifiers()) || m.getParameterTypes().length != args.length) {
				continue;
			}
			Object[] values = new Object[args.length];
			Class<?>[] types = m.getParameterTypes();
			for (int i = 0; i < args.length; i++) {
				values[i] = types[i] == int.class ? Integer.valueOf(args[i]) : types[i] == boolean.class ? Boolean.valueOf(args[i])
					: types[i] == float.class ? Float.valueOf(args[i]) : (Object) args[i];
			}
			Object result = m.invoke(null, values);
			log("called " + c[1] + (result != null ? " -> " + result : ""));
			return;
		}
		fail("no method " + c[1] + " with " + args.length + " arguments");
	}

	/** A field by name (for options a step sets). */
	static Field field(Class<?> owner, String name) throws NoSuchFieldException {
		Field f = owner.getDeclaredField(name);
		f.setAccessible(true);
		return f;
	}
}
