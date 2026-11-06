/*
 * Adapted from BetterF3 (https://github.com/TreyRuffy/BetterF3), MIT licence, Copyright (c) cominixo and TreyRuffy: its
 * modules and their lines, formats and colours, the columns with their background, the font scale and the slide-in
 * animation, auto start, what it hides while open and the F3 + F render distance keys. Full text:
 * META-INF/licenses/MIT-BetterF3.txt.
 */
package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.compat.McCompat;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.ToolMods.CustomF3;
import com.flintclient.client.control.model.mods.ToolMods.CustomF3.F3Module;
import com.flintclient.client.util.FlintInfo;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.client.ClientBrandRetriever;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.block.state.StateHolder;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Custom F3: BetterF3's debug screen in place of Minecraft's. While the screen is open Minecraft's own entries gather nothing
 * (their list is swapped for an id no entry has) and its help lines are left out; each column is drawn here instead: the
 * modules named in the module's left and right lists, in order, each giving its switched-on lines in its own colours, over a
 * background per line, scaled by the font scale and sliding in from the sides (and back out on closing). It can open itself
 * with the game, open the profiler, TPS or ping charts with it, hide the sidebar, the boss bars and the 3D crosshair while
 * it's open, and F3 + F cycles the render distance (Ctrl: simulation distance; Shift: backwards).
 */
public final class Mc263BetterF3 {
	private static final HookGuard GUARD = new HookGuard("custom F3",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	private static final int F3 = Mods.index(CustomF3.ID);
	/** What Minecraft's entry loop is given while this draws: an id no entry has, so none of its lines are gathered. */
	private static final List<Identifier> NONE = List.of(Identifier.fromNamespaceAndPath("flintclient", "custom_f3"));
	private static final int TEXT = 0xFFE0E0E0;
	private static final int LINE_HEIGHT = 9;

	/** The debug screen's toggles of its charts (DebugScreenOverlay, through CustomF3Mixin). */
	public interface Charts {
		boolean flint$fpsCharts();

		void flint$showCharts(boolean profiler, boolean fps);

		void flint$toggleNetworkCharts();
	}

	/** Whether the debug screen shows (DebugScreenEntryList, through DebugScreenEntryListMixin), set without the animation. */
	public interface Entries {
		void flint$setOverlay(boolean visible);
	}

	private Mc263BetterF3() {
	}

	private static boolean on() {
		return GUARD.on() && Mods.on(F3);
	}

	/** Whether BetterF3 draws the debug screen now (the module is on and the screen is open). */
	private static boolean replacing() {
		return on() && Minecraft.getInstance().debugEntries.isOverlayVisible();
	}

	// ---------- the screen ----------

	private static boolean posePushed;

	/** Before the debug screen is gathered: keeps the pose for the font scale. */
	public static void begin(GuiGraphicsExtractor graphics) {
		if (replacing()) {
			posePushed = true;
			graphics.pose().pushMatrix();
		}
	}

	public static void end(GuiGraphicsExtractor graphics) {
		if (posePushed) {
			graphics.pose().popMatrix();
			posePushed = false;
		}
	}

	/** The entries Minecraft gathers lines from: none while this draws the screen. */
	public static Collection<Identifier> enabled(Collection<Identifier> vanilla) {
		return replacing() ? NONE : vanilla;
	}

	/** Whether Minecraft adds its help lines (the debug screen counts as closed for them while this draws it). */
	public static boolean overlayVisible(boolean vanilla) {
		return !replacing() && vanilla;
	}

	/** Once the entries are known: the font scale. */
	public static void scale(GuiGraphicsExtractor graphics) {
		if (replacing()) {
			float scale = Mods.state(F3).decimal(CustomF3.FONT_SCALE);
			graphics.pose().scale(scale, scale);
		}
	}

	/**
	 * Draws a column in place of Minecraft's (which is then skipped): the modules of {@code left}'s list.
	 *
	 * @return whether it drew (Minecraft's column is skipped)
	 */
	public static boolean drawColumn(GuiGraphicsExtractor graphics, boolean left, int screenWidth) {
		if (!replacing()) {
			return false;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			ModuleState s = Mods.state(F3);
			List<Component> lines = lines(mc, s, left);
			Font font = mc.font;
			float scale = s.decimal(CustomF3.FONT_SCALE);
			boolean animate = s.bool(CustomF3.ANIMATIONS);
			int shift = animate ? xPos : 0;
			int background = Math.round(s.integer(CustomF3.BACKGROUND_OPACITY) * 2.55F) << 24 | s.rgb(CustomF3.BACKGROUND);
			boolean shadow = s.bool(CustomF3.SHADOW);
			int columnRight = (int) (screenWidth / scale) - 2;
			for (int i = 0; i < lines.size(); i++) {
				int width = font.width(lines.get(i).getString());
				if (width == 0) {
					continue;
				}
				int x = left ? 2 - shift : columnRight - width + shift;
				int y = 2 + LINE_HEIGHT * i;
				graphics.fill(x - 1, y - 1, x + width + 1, y + LINE_HEIGHT - 1, background);
			}
			for (int i = 0; i < lines.size(); i++) {
				Component line = lines.get(i);
				String text = line.getString();
				if (text.isEmpty()) {
					continue;
				}
				int x = left ? 2 - shift : columnRight - font.width(text) + shift;
				graphics.text(font, line, x, 2 + LINE_HEIGHT * i, TEXT, shadow);
			}
			return true;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return false;
		}
	}

	// ---------- opening and closing ----------

	/** How far the columns are pushed out to their sides (0: in place); 200 when the screen opens, 300 when it's gone. */
	private static int xPos = 200;
	private static long lastAnimationUpdate;
	private static boolean closing;

	/** Each frame the screen draws: moves the columns along, and finishes closing once they're out of sight. */
	public static void animate() {
		if (!replacing()) {
			return;
		}
		try {
			ModuleState s = Mods.state(F3);
			if (!s.bool(CustomF3.ANIMATIONS)) {
				return;
			}
			long time = Util.getMillis();
			if (time - lastAnimationUpdate < 10 || xPos == 0 && !closing) {
				return;
			}
			float speed = s.decimal(CustomF3.ANIMATION_SPEED);
			int step = (100 + xPos) / 10 - 9;
			if (xPos != 0 && !closing) {
				xPos = (int) (xPos / speed);
				xPos -= step;
			}
			if (step == 0) {
				step = 1;
			}
			if (closing) {
				xPos += step;
				xPos = (int) (xPos * speed);
				if (xPos >= 300) {
					closing = false;
					((Entries) Minecraft.getInstance().debugEntries).flint$setOverlay(false);
				}
			}
			lastAnimationUpdate = time;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	/**
	 * Minecraft opening or closing the debug screen: with the animation, closing slides the columns out first (the screen
	 * stays until they're gone) and opening starts them off to the sides; and the charts asked for open with it.
	 *
	 * @return whether this took care of it (Minecraft's own is skipped)
	 */
	public static boolean setOverlay(Entries entries, boolean visible, boolean wanted) {
		if (!on()) {
			return false;
		}
		try {
			ModuleState s = Mods.state(F3);
			if (s.bool(CustomF3.ANIMATIONS)) {
				if (visible && !wanted && !closing) {
					closing = true;
				} else if (!visible && wanted) {
					closing = false;
					xPos = 200;
					entries.flint$setOverlay(true);
					openCharts(s);
				}
			} else if (visible != wanted) {
				entries.flint$setOverlay(wanted);
				if (wanted) {
					openCharts(s);
				}
			}
			return true;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return false;
		}
	}

	/** The charts the module opens with the screen. */
	private static void openCharts(ModuleState s) {
		Charts charts = (Charts) Minecraft.getInstance().getDebugOverlay();
		boolean profiler = s.bool(CustomF3.ALWAYS_PROFILER);
		boolean tps = s.bool(CustomF3.ALWAYS_TPS);
		if (profiler || tps) {
			charts.flint$showCharts(profiler, tps);
		}
		if (s.bool(CustomF3.ALWAYS_PING) && !Minecraft.getInstance().getDebugOverlay().showNetworkCharts()) {
			charts.flint$toggleNetworkCharts();
		}
	}

	/** The debug screen made or reset (a world joined or left): Auto start opens it. */
	public static void autoStart(Charts charts) {
		try {
			if (!on()) {
				return;
			}
			ModuleState s = Mods.state(F3);
			Minecraft mc = Minecraft.getInstance();
			if (!s.bool(CustomF3.AUTO_START) || mc.debugEntries == null) {
				return;
			}
			((Entries) mc.debugEntries).flint$setOverlay(true);
			closing = false;
			charts.flint$showCharts(s.bool(CustomF3.ALWAYS_PROFILER), s.bool(CustomF3.ALWAYS_TPS));
			if (s.bool(CustomF3.ALWAYS_PING)) {
				charts.flint$toggleNetworkCharts();
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	/** With Auto start, the screen shows whenever it's open and there's a world (the HUD not hidden); null: Minecraft decides. */
	public static @Nullable Boolean showDebugScreen() {
		if (!on() || !Mods.state(F3).bool(CustomF3.AUTO_START)) {
			return null;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.debugEntries.isOverlayVisible() && !McCompat.hud().isHidden()) {
			return mc.level != null;
		}
		return null;
	}

	/** Whether Minecraft's check of debug entry {@code id} says no: the 3D crosshair, while the screen is open, if hidden. */
	public static boolean hidesEntry(Identifier id) {
		return id.equals(DebugScreenEntries.THREE_DIMENSIONAL_CROSSHAIR) && replacing() && Mods.state(F3).bool(CustomF3.HIDE_CROSSHAIR);
	}

	public static boolean hideSidebar() {
		return replacing() && Mods.state(F3).bool(CustomF3.HIDE_SIDEBAR);
	}

	public static boolean hideBossbar() {
		return replacing() && Mods.state(F3).bool(CustomF3.HIDE_BOSSBAR);
	}

	/**
	 * F3 + a key: F cycles the render distance (Ctrl: the simulation distance; Shift: backwards), told in chat.
	 *
	 * @return whether it was that key (Minecraft's own handling is skipped)
	 */
	public static boolean debugKey(int key, boolean control, boolean shift, Consumer<Component> feedback) {
		if (!on() || key != com.mojang.blaze3d.platform.InputConstants.KEY_F) {
			return false;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			int by = shift ? -1 : 1;
			if (control) {
				mc.options.simulationDistance().set(Mth.clamp(mc.options.simulationDistance().get() + by, 5, 32));
				feedback.accept(Component.translatable("debug.flintf3.cycle_simulationdistance.message", mc.options.simulationDistance().get()));
			} else {
				mc.options.renderDistance().set(Mth.clamp(mc.options.renderDistance().get() + by, 2, 32));
				feedback.accept(Component.translatable("debug.flintf3.cycle_renderdistance.message", mc.options.renderDistance().get()));
			}
			return true;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return false;
		}
	}

	// ---------- keys ----------

	private static final boolean[] HELD = new boolean[3];

	/** Each client tick: the stand-in keys for F3 and its charts; a screen left closing when the module went off is closed. */
	public static void tick(boolean playing) {
		if (!GUARD.on()) {
			return;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			if (!Mods.on(F3)) {
				if (closing) {
					closing = false;
					((Entries) mc.debugEntries).flint$setOverlay(false);
				}
				return;
			}
			ModuleState s = Mods.state(F3);
			if (edge(0, playing, s.text(CustomF3.ALT_F3))) {
				mc.debugEntries.toggleDebugOverlay();
			}
			if (edge(1, playing, s.text(CustomF3.ALT_PIE))) {
				mc.getDebugOverlay().toggleProfilerChart();
			}
			if (edge(2, playing, s.text(CustomF3.ALT_GRAPH))) {
				mc.getDebugOverlay().toggleFpsCharts();
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	private static boolean edge(int i, boolean playing, String key) {
		boolean down = playing && !key.isEmpty() && Mc263Features.keyHeld(key);
		boolean pressed = down && !HELD[i];
		HELD[i] = down;
		return pressed;
	}

	// ---------- the modules ----------

	/** Modules that only change now and then (BetterF3's): rebuilt every quarter second when performance optimizations are on. */
	private static final long STATIC_REFRESH_MS = 250;
	private static final Map<F3Module, List<Component>> CACHE = new HashMap<>();
	private static final Map<F3Module, Long> CACHED_AT = new HashMap<>();
	private static int cachedRevision = -1;

	/** A column's lines: its modules', in order (none unless in a world with no screen but chat open). */
	private static List<Component> lines(Minecraft mc, ModuleState s, boolean left) {
		List<Component> out = new ArrayList<>();
		if (mc.level == null || mc.gui.screen() != null && !(mc.gui.screen() instanceof ChatScreen)) {
			return out;
		}
		if (cachedRevision != s.revision()) {
			CACHE.clear();
			cachedRevision = s.revision();
		}
		boolean reduced = mc.showOnlyReducedInfo();
		long now = System.currentTimeMillis();
		for (String token : s.text(left ? CustomF3.LEFT : CustomF3.RIGHT).split(",")) {
			String id = token.strip().toLowerCase(Locale.ROOT);
			if (id.isEmpty()) {
				continue;
			}
			if (id.equals("empty") || id.startsWith("empty:")) {
				int count = s.integer(CustomF3.EMPTY_LINES);
				if (id.startsWith("empty:")) {
					try {
						count = Math.clamp(Integer.parseInt(id.substring(6).strip()), 0, 20);
					} catch (NumberFormatException e) {
						// an unreadable count keeps the module's
					}
				}
				for (int i = 0; i < count; i++) {
					out.add(Component.empty());
				}
				space(s, out);
				continue;
			}
			F3Module module = CustomF3.module(id);
			if (module == null || !s.bool(module.enabled)) {
				continue;
			}
			boolean everyFrame = module != CustomF3.MINECRAFT && module != CustomF3.GRAPHICS;
			List<Component> lines = CACHE.get(module);
			Long at = CACHED_AT.get(module);
			if (lines == null || everyFrame || !s.bool(CustomF3.PERFORMANCE) || at == null || now - at >= STATIC_REFRESH_MS
				|| Boolean.TRUE.equals(CACHED_REDUCED.get(module)) != reduced) {
				lines = new ArrayList<>();
				new Out(s, module, reduced, lines).build(mc);
				CACHE.put(module, lines);
				CACHED_AT.put(module, now);
				CACHED_REDUCED.put(module, reduced);
			}
			out.addAll(lines);
			space(s, out);
		}
		return out;
	}

	private static final Map<F3Module, Boolean> CACHED_REDUCED = new HashMap<>();

	private static void space(ModuleState s, List<Component> out) {
		if (s.bool(CustomF3.SPACE_MODULES)) {
			out.add(Component.empty());
		}
	}

	private static String lang(String key, Object... args) {
		return I18n.get(key, args);
	}

	private static MutableComponent styled(Object text, int rgb) {
		return Component.literal(String.valueOf(text)).withStyle(style -> style.withColor(TextColor.fromRgb(rgb)));
	}

	/** "Name: value" in the name and value colours, split at its first colon ({@code text} without one stays plain). */
	private static Component split(String text, int name, int value) {
		int colon = text.indexOf(':');
		if (colon < 0) {
			return Component.literal(text);
		}
		return styled(text.substring(0, colon), name).append(Component.literal(":")).append(styled(text.substring(colon + 1), value));
	}

	/** One module's lines as they're built: each line's switch, the reduced debug screen, and the module's colours. */
	private static final class Out {
		private final ModuleState s;
		private final F3Module module;
		private final boolean reduced;
		private final List<Component> lines;
		private final int name;
		private final int value;

		Out(ModuleState s, F3Module module, boolean reduced, List<Component> lines) {
			this.s = s;
			this.module = module;
			this.reduced = reduced;
			this.lines = lines;
			this.name = s.rgb(module.nameColor);
			this.value = s.rgb(module.valueColor);
		}

		/** Whether line {@code id} shows: switched on, and allowed on the reduced debug screen if that's showing. */
		boolean shows(String id, boolean inReduced) {
			return s.bool(module.line(id)) && (!reduced || inReduced);
		}

		String lineName(String id) {
			return lang("text.flintf3.line." + id);
		}

		/** "Name: value" (or {@code format}), the name in the name colour and the value in the value colour; nothing if empty. */
		void line(String id, boolean inReduced, @Nullable Object val) {
			line(id, inReduced, val, "format.flintf3.default_format");
		}

		void line(String id, boolean inReduced, @Nullable Object val, String format) {
			if (val == null || !shows(id, inReduced)) {
				return;
			}
			if (!(val instanceof Component) && String.valueOf(val).isEmpty()) {
				return;
			}
			Component shown = val instanceof Component component ? component : styled(val, value);
			lines.add(Component.translatable(format, styled(lineName(id), name), shown));
		}

		/** A line laid out by {@code format}: its name (unless it has none) then {@code values}, all in the name colour unless styled. */
		void custom(String id, boolean inReduced, String format, Object... values) {
			if (!shows(id, inReduced)) {
				return;
			}
			String own = lineName(id);
			List<Object> args = new ArrayList<>();
			if (!own.isEmpty()) {
				args.add(styled(own, name));
			}
			args.addAll(List.of(values));
			lines.add(Component.translatable(format, args.toArray()).withStyle(style -> style.withColor(TextColor.fromRgb(name))));
		}

		/** Several "name: value" lines under one switch. */
		void list(String id, boolean inReduced, List<String> values) {
			if (!shows(id, inReduced)) {
				return;
			}
			for (String each : values) {
				lines.add(split(each, name, value));
			}
		}

		void build(Minecraft mc) {
			switch (module.id) {
				case "minecraft" -> minecraft(mc);
				case "fps" -> fps(mc);
				case "graphics" -> graphics(mc);
				case "server" -> server(mc);
				case "coords" -> coords(mc);
				case "chunks" -> chunks(mc);
				case "location" -> location(mc);
				case "entity" -> entity(mc);
				case "sound" -> sound(mc);
				case "help" -> help(mc);
				case "system" -> system(mc);
				case "target" -> target(mc);
				default -> {
					// misc left and right: Minecraft's own lines are all in the modules above; misc right adds Flint's version
				}
			}
			if (module == CustomF3.MISC_RIGHT && shows("misc_right", true)) {
				lines.add(split("Flint Version: " + FlintInfo.flintVersion(), name, value));
			}
		}

		void minecraft(Minecraft mc) {
			line("minecraft", true, SharedConstants.getCurrentVersion().name() + " (" + mc.getLaunchedVersion() + "/"
				+ ClientBrandRetriever.getClientModName() + ")", "format.flintf3.default_no_colon");
		}

		void fps(Minecraft mc) {
			int fps = mc.getFps();
			int limit = mc.options.framerateLimit().get();
			String text = lang("format.flintf3.fps", fps, limit == 260 ? lang("text.flintf3.line.fps.unlimited") : limit,
				mc.options.enableVsync().get() ? lang("text.flintf3.line.fps.vsync") : "").trim();
			int color = fps >= 60 ? s.rgb(CustomF3.FPS_HIGH) : fps >= 20 ? s.rgb(CustomF3.FPS_MEDIUM) : s.rgb(CustomF3.FPS_LOW);
			custom("fps", true, "format.flintf3.no_format", styled(text, color));
		}

		void graphics(Minecraft mc) {
			String stats = McCompat.f3SectionStats();
			Matcher distance = stats == null ? null : VIEW_DISTANCE.matcher(stats);
			line("render_distance", true, distance != null && distance.find() ? distance.group(1) : mc.options.getEffectiveRenderDistance());
			line("graphics", false, McCompat.f3Graphics());
			CloudStatus clouds = mc.options.cloudStatus().get();
			line("clouds", false, lang(clouds == CloudStatus.OFF ? "text.flintf3.line.off" : clouds == CloudStatus.FAST ? "text.flintf3.line.fast"
				: "text.flintf3.line.fancy"));
			line("biome_blend_radius", true, mc.options.biomeBlendRadius().get());
			line("shader", false, McCompat.f3PostEffects());
		}

		void server(Minecraft mc) {
			IntegratedServer integrated = mc.getSingleplayerServer();
			ClientPacketListener connection = mc.getConnection();
			String brand = "";
			if (integrated != null) {
				brand = lang("text.flintf3.line.integrated_server");
			} else if (mc.player != null && connection != null && connection.serverBrand() != null) {
				brand = connection.serverBrand();
			}
			if (integrated != null) {
				custom("server_tick", true, "format.flintf3.server_tick", styled(brand, name),
					styled(Integer.toString(Math.round(integrated.getCurrentSmoothedTickTime())), name));
			} else {
				custom("server_tick", true, "format.flintf3.no_format", styled(brand, name));
			}
			if (connection != null) {
				line("packets_sent", true, Math.round(connection.getConnection().getAverageSentPackets()));
				line("packets_received", true, Math.round(connection.getConnection().getAverageReceivedPackets()));
			}
			if (mc.level != null) {
				TickRateManager ticks = mc.level.tickRateManager();
				String status = "normal";
				if (integrated != null && integrated.tickRateManager().isSprinting()) {
					status = "sprinting";
				} else if (ticks.isSteppingForward()) {
					status = "stepping";
				} else if (ticks.isFrozen()) {
					status = "frozen";
				}
				line("tick_manager_status", true, lang("text.flintf3.line.tick_manager_status." + status));
			}
		}

		void coords(Minecraft mc) {
			Entity camera = mc.getCameraEntity();
			if (camera == null) {
				return;
			}
			int x = s.rgb(CustomF3.COORDS_X);
			int y = s.rgb(CustomF3.COORDS_Y);
			int z = s.rgb(CustomF3.COORDS_Z);
			MutableComponent xyz = styled("X", x).append(styled("Y", y)).append(styled("Z", z));
			custom("player_coords", false, "format.flintf3.coords", xyz, styled(String.format(Locale.ROOT, "%.3f", camera.getX()), x),
				styled(String.format(Locale.ROOT, "%.5f", camera.getY()), y), styled(String.format(Locale.ROOT, "%.3f", camera.getZ()), z));
			BlockPos pos = camera.blockPosition();
			custom("block_coords", false, "format.flintf3.coords", styled(pos.getX(), x), styled(pos.getY(), y), styled(pos.getZ(), z));
			custom("chunk_relative_coords", true, "format.flintf3.coords", styled(pos.getX() & 15, x), styled(pos.getY() & 15, y),
				styled(pos.getZ() & 15, z));
			custom("chunk_coords", false, "format.flintf3.coords", styled(pos.getX() >> 4, x), styled(pos.getY() >> 4, y),
				styled(pos.getZ() >> 4, z));
			Vec3 velocity = velocity(mc, camera);
			int perSecond = Math.min(mc.getFps(), 20);
			custom("velocity", false, "format.flintf3.coords", styled(String.format(Locale.ROOT, "%.3f", velocity.x * perSecond), x),
				styled(String.format(Locale.ROOT, "%.3f", velocity.y * perSecond), y),
				styled(String.format(Locale.ROOT, "%.3f", velocity.z * perSecond), z));
			int red = CustomF3.COORDS.nameColor.defaultValue();
			line("absolute_velocity", false, styled(String.format(Locale.ROOT, "%.3f", velocity.length() * perSecond), red));
			line("horizontal_velocity", false, styled(String.format(Locale.ROOT, "%.3f", velocity.horizontalDistance() * perSecond), red));
		}

		void chunks(Minecraft mc) {
			ClientLevel level = mc.level;
			if (level == null) {
				return;
			}
			int total = s.rgb(CustomF3.CHUNKS_TOTAL);
			String stats = McCompat.f3SectionStats();
			Matcher sections = stats == null ? null : COUNTS.matcher(stats);
			boolean found = sections != null && sections.find();
			custom("chunk_sections", true, "format.flintf3.total", styled(lang("text.flintf3.line.rendered"), value),
				styled(lang("text.flintf3.line.total"), total), styled(found ? sections.group(1) : "0", value), styled(found ? sections.group(2) : "0", total));
			line("chunk_culling", false, mc.smartCull ? styled(lang("text.flintf3.line.enabled"), s.rgb(CustomF3.CHUNKS_ENABLED))
				: styled(lang("text.flintf3.line.disabled"), s.rgb(CustomF3.CHUNKS_DISABLED)));
			int[] queues = McCompat.f3SectionQueues();
			if (queues != null) {
				line("pending_chunks", true, queues[0]);
				line("pending_uploads", true, queues[1]);
				line("available_buffers", true, queues[2]);
			}
			Matcher cache = FIRST_NUMBER.matcher(level.getChunkSource().gatherStats());
			line("client_chunk_cache", true, cache.find() ? cache.group(1) : null);
			line("loaded_chunks", false, level.getChunkSource().getLoadedChunksCount());
			IntegratedServer integrated = mc.getSingleplayerServer();
			ServerLevel server = integrated == null ? null : integrated.getLevel(level.dimension());
			if (server != null) {
				line("loaded_chunks_server", false, server.getChunkSource().getLoadedChunksCount());
			}
			line("forceloaded_chunks", false, server == null ? 0 : server.getForceLoadedChunks().size());
			NaturalSpawner.SpawnState spawn = server == null ? null : server.getChunkSource().getLastSpawnState();
			if (spawn != null) {
				line("spawn_chunks", false, spawn.getSpawnableChunkCount());
			}
			Entity camera = mc.getCameraEntity();
			if (camera != null) {
				BlockPos pos = camera.blockPosition();
				int chunkX = pos.getX() >> 4;
				int chunkZ = pos.getZ() >> 4;
				line("chunk_file", false, String.format(Locale.ROOT, "r.%d.%d.mca (%d, %d)", chunkX >> 5, chunkZ >> 5, chunkX & 31, chunkZ & 31));
			}
		}

		void location(Minecraft mc) {
			ClientLevel level = mc.level;
			Entity camera = mc.getCameraEntity();
			if (level == null || camera == null) {
				return;
			}
			IntegratedServer integrated = mc.getSingleplayerServer();
			BlockPos pos = camera.blockPosition();
			int chunkX = pos.getX() >> 4;
			int chunkZ = pos.getZ() >> 4;
			ServerLevel server = integrated == null ? null : integrated.getLevel(level.dimension());
			LevelChunk clientChunk = level.getChunk(chunkX, chunkZ);
			String light = "";
			String lightServer = "";
			String difficulty = "";
			String slime = "";
			StringBuilder highest = new StringBuilder();
			StringBuilder highestServer = new StringBuilder();
			if (clientChunk.isEmpty()) {
				light = lang("text.flintf3.line.waiting_chunk");
			} else {
				light = lang("format.flintf3.chunklight", level.getChunkSource().getLightEngine().getRawBrightness(pos, 0),
					level.getBrightness(LightLayer.SKY, pos), level.getBrightness(LightLayer.BLOCK, pos));
				net.minecraft.world.level.Level lightLevel = server != null ? server : level;
				LevelLightEngine engine = lightLevel.getChunkSource().getLightEngine();
				lightServer = lang("format.flintf3.chunklight_server", engine.getLayerListener(LightLayer.SKY).getLightValue(pos),
					engine.getLayerListener(LightLayer.BLOCK).getLightValue(pos));
				ChunkAccess serverChunk = server == null ? clientChunk : serverChunk(server, chunkX, chunkZ);
				for (Heightmap.Types type : Heightmap.Types.values()) {
					String label = HEIGHTMAPS.get(type);
					if (type.sendToClient()) {
						int y = clientChunk.getHeight(type, pos.getX(), pos.getZ());
						if (y > -1) {
							highest.append("  ").append(label).append(": ").append(y);
						}
					}
					if (type.keepAfterWorldgen() && server != null) {
						int y = (serverChunk == null ? clientChunk : serverChunk).getHeight(type, pos.getX(), pos.getZ());
						if (y > -1) {
							highestServer.append("  ").append(label).append(": ").append(y);
						}
					}
				}
				if (level.isInsideBuildHeight(pos.getY())) {
					DifficultyInstance local = server != null ? server.getCurrentDifficultyAt(pos)
						: new DifficultyInstance(level.getDifficulty(), McCompat.f3Clock(level), clientChunk.getInhabitedTime(),
							McCompat.f3MoonBrightness(level, pos));
					difficulty = String.format(Locale.ROOT, "%.2f  " + lang("text.flintf3.line.clamped") + ": %.2f", local.getEffectiveDifficulty(),
						local.getSpecialMultiplier());
				}
				if (server != null) {
					boolean slimy = WorldgenRandom.seedSlimeChunk(chunkX, chunkZ, server.getSeed(), 987234911L).nextInt(10) == 0;
					slime = lang(slimy ? "text.flintf3.line.slime_chunk.true" : "text.flintf3.line.slime_chunk.false");
				} else {
					slime = lang("text.flintf3.line.slime_chunk.unknown");
				}
			}
			line("dimension", false, level.dimension().identifier());
			Direction facing = camera.getDirection();
			line("facing", false, String.format("%s (%s)", lang("text.flintf3.line." + facing.toString().toLowerCase(Locale.ROOT)), towards(facing)));
			line("rotation", false, lang("format.flintf3.rotation", String.format("%.1f", Mth.wrapDegrees(camera.getYRot())),
				String.format("%.1f", Mth.wrapDegrees(camera.getXRot()))));
			line("light", false, light);
			line("light_server", false, lightServer);
			line("highest_block", false, highest.toString().trim());
			line("highest_block_server", false, highestServer.toString().trim());
			line("biome", false, String.valueOf(level.registryAccess().lookupOrThrow(Registries.BIOME).getKey(level.getBiome(pos).value())));
			line("local_difficulty", false, difficulty);
			long clock = McCompat.f3Clock(level);
			line("day_ticks", false, clock % 24000L);
			line("days_played", false, clock / 24000L);
			line("slime_chunk", false, slime.trim());
		}

		void entity(Minecraft mc) {
			ClientLevel level = mc.level;
			if (level == null) {
				return;
			}
			int total = s.rgb(CustomF3.ENTITY_TOTAL);
			line("particles", true, mc.particleEngine.countParticles());
			String stats = McCompat.f3EntityStats();
			Matcher counts = stats == null ? null : COUNTS.matcher(stats);
			boolean found = counts != null && counts.find();
			custom("entities", true, "format.flintf3.total", styled(lang("text.flintf3.line.rendered"), value),
				styled(lang("text.flintf3.line.total"), total), styled(found ? counts.group(1) : "0", value), styled(level.getEntityCount(), total));
			IntegratedServer integrated = mc.getSingleplayerServer();
			ServerLevel server = integrated == null ? null : integrated.getLevel(level.dimension());
			NaturalSpawner.SpawnState spawn = server == null ? null : server.getChunkSource().getLastSpawnState();
			if (spawn != null) {
				for (MobCategory category : MobCategory.values()) {
					String id = category.toString().toLowerCase(Locale.ROOT);
					if (module.line(id) != null) {
						line(id, false, spawn.getMobCategoryCounts().getInt(category));
					}
				}
			}
		}

		void sound(Minecraft mc) {
			Matcher counts = SOUNDS.matcher(McCompat.f3SoundStats());
			if (counts.find()) {
				int maximum = s.rgb(CustomF3.SOUND_MAXIMUM);
				String playing = lang("text.flintf3.line.playing");
				String max = lang("text.flintf3.line.maximum");
				custom("sounds", false, "format.flintf3.total", styled(playing, value), styled(max, maximum), styled(counts.group(1), value),
					styled(counts.group(2), maximum));
				custom("ambient_sounds", false, "format.flintf3.total", styled(playing, value), styled(max, maximum), styled(counts.group(3), value),
					styled(counts.group(4), maximum));
			}
			if (mc.player != null) {
				line("mood", false, Math.round(mc.player.getCurrentMood() * 100.0F) + "%");
			}
		}

		void help(Minecraft mc) {
			String visible = lang("text.flintf3.line.visible");
			String hidden = lang("text.flintf3.line.hidden");
			int on = s.rgb(CustomF3.HELP_ENABLED);
			int off = s.rgb(CustomF3.HELP_DISABLED);
			Charts charts = (Charts) mc.getDebugOverlay();
			line("pie_graph_new", true, mc.getDebugOverlay().showProfilerChart() ? styled(visible, on) : styled(hidden, off));
			line("fps_tps_new", true, charts.flint$fpsCharts() ? styled(visible, on) : styled(hidden, off));
			line("ping", true, mc.getDebugOverlay().showNetworkCharts() ? styled(visible, on) : styled(hidden, off));
			line("help", true, lang("text.flintf3.line.help_press"));
		}

		void system(Minecraft mc) {
			DateTimeFormatter format;
			try {
				format = DateTimeFormatter.ofPattern(s.text(CustomF3.TIME_FORMAT));
			} catch (IllegalArgumentException e) {
				format = DateTimeFormatter.ofPattern(CustomF3.TIME_FORMAT.defaultValue());
			}
			long max = Runtime.getRuntime().maxMemory();
			long total = Runtime.getRuntime().totalMemory();
			long used = total - Runtime.getRuntime().freeMemory();
			String memory = String.format("% 2d%% %03d/%03d MB", used * 100L / max, used / 1024L / 1024L, max / 1024L / 1024L);
			int percent = (int) (used * 100L / max);
			ChatFormatting memoryColor = percent >= 90 ? ChatFormatting.RED : percent >= 60 ? ChatFormatting.YELLOW : ChatFormatting.GREEN;
			String[] gpu = McCompat.f3Gpu();
			line("time", true, LocalDateTime.now().format(format));
			line("java_version", true, System.getProperty("java.version"));
			line("memory_usage", true, s.bool(CustomF3.MEMORY_COLOR) ? memoryColor + memory : memory);
			line("allocation_rate", true, String.format("% 2d MB/s", allocationRate(used) / 1024L / 1024L));
			line("allocated_memory", true, String.format("% 2d%% %03dMB", total * 100L / max, total / 1024L / 1024L));
			line("cpu", true, McCompat.f3Cpu());
			line("display", true, String.format("%d x %d (%s)", mc.getWindow().getWidth(), mc.getWindow().getHeight(), gpu[1]));
			line("gpu", true, gpu[0]);
			double utilization = mc.getGpuUtilization();
			line("gpu_utilization", true, utilization > 0 ? utilization > 100 ? ChatFormatting.RED + "100%" : Math.round(utilization) + "%" : "N/A");
			line("graphics_api", true, gpu[2]);
			line("gpu_driver", true, gpu[3]);
		}

		void target(Minecraft mc) {
			Entity camera = mc.getCameraEntity();
			ClientLevel level = mc.level;
			if (camera == null || level == null) {
				return;
			}
			HitResult block = camera.pick(20.0, 0.0F, false);
			HitResult fluid = camera.pick(20.0, 0.0F, true);
			if (block.getType() == HitResult.Type.BLOCK) {
				BlockPos pos = ((BlockHitResult) block).getBlockPos();
				net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
				line("targeted_block", false, pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
				line("id_block", false, String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock())));
				list("block_states", false, states(state));
				list("block_tags", false, state.getBlock().builtInRegistryHolder().tags().map(tag -> "#" + tag.location()).toList());
				if (shows("nothing", false)) {
					lines.add(Component.empty());
				}
			}
			if (fluid.getType() == HitResult.Type.BLOCK) {
				BlockPos pos = ((BlockHitResult) fluid).getBlockPos();
				FluidState state = level.getFluidState(pos);
				line("targeted_fluid", false, pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
				line("id_fluid", false, String.valueOf(BuiltInRegistries.FLUID.getKey(state.getType())));
				list("fluid_states", false, states(state));
				list("fluid_tags", false, state.getType().builtInRegistryHolder().tags().map(tag -> "#" + tag.location()).toList());
				if (shows("nothing2", false)) {
					lines.add(Component.empty());
				}
			}
			Entity entity = mc.crosshairPickEntity;
			if (entity != null) {
				line("targeted_entity", false, String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())));
			}
		}
	}

	// ---------- what the lines read ----------

	private static final Pattern COUNTS = Pattern.compile("(\\d+)/(\\d+)");
	private static final Pattern VIEW_DISTANCE = Pattern.compile("D: (\\d+)");
	private static final Pattern FIRST_NUMBER = Pattern.compile("(\\d+)");
	private static final Pattern SOUNDS = Pattern.compile("(\\d+)/(\\d+)\\D+(\\d+)/(\\d+)");
	private static final Map<Heightmap.Types, String> HEIGHTMAPS = new EnumMap<>(Map.of(Heightmap.Types.WORLD_SURFACE_WG, "SW",
		Heightmap.Types.WORLD_SURFACE, "S", Heightmap.Types.OCEAN_FLOOR_WG, "OW", Heightmap.Types.OCEAN_FLOOR, "O",
		Heightmap.Types.MOTION_BLOCKING, "M", Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, "ML"));

	private static String towards(Direction facing) {
		return switch (facing) {
			case NORTH -> lang("text.flintf3.line.negative_z");
			case SOUTH -> lang("text.flintf3.line.positive_z");
			case WEST -> lang("text.flintf3.line.negative_x");
			case EAST -> lang("text.flintf3.line.positive_x");
			default -> "";
		};
	}

	/** A block's or fluid's properties, "name: value" (true green, false red). */
	private static List<String> states(StateHolder<?, ?> state) {
		List<String> out = new ArrayList<>();
		for (Property<?> property : state.getProperties()) {
			out.add(property(state, property));
		}
		return out;
	}

	private static <T extends Comparable<T>> String property(StateHolder<?, ?> state, Property<T> property) {
		T value = state.getValue(property);
		String shown = Util.getPropertyName(property, value);
		if (Boolean.TRUE.equals(value)) {
			shown = ChatFormatting.GREEN + shown;
		} else if (Boolean.FALSE.equals(value)) {
			shown = ChatFormatting.RED + shown;
		}
		return property.getName() + ": " + shown;
	}

	private static long serverChunkAt = Long.MIN_VALUE;
	private static @Nullable CompletableFuture<@Nullable ChunkAccess> serverChunk;

	/** The integrated server's copy of a chunk (for its heightmaps), once it's loaded; asked for again when the chunk changes. */
	private static @Nullable ChunkAccess serverChunk(ServerLevel server, int chunkX, int chunkZ) {
		long at = (long) chunkX << 32 | chunkZ & 0xFFFFFFFFL;
		if (serverChunk == null || serverChunkAt != at) {
			serverChunkAt = at;
			serverChunk = server.getChunkSource().getChunkFuture(chunkX, chunkZ, ChunkStatus.FULL, false).thenApply(result -> result.orElse(null));
		}
		return serverChunk.getNow(null);
	}

	private static Vec3 previous = Vec3.ZERO;
	private static Vec3 velocity = Vec3.ZERO;
	private static long movedAt;

	/** How far the camera's vehicle moved since it last moved (none once it has stood still for over a tick). */
	private static Vec3 velocity(Minecraft mc, Entity camera) {
		Entity vehicle = camera.getRootVehicle();
		if (mc.level != null) {
			Vec3 now = new Vec3(vehicle.getX(), vehicle.getY(), vehicle.getZ());
			long since = mc.level.getGameTime() - movedAt;
			if (!previous.equals(now)) {
				velocity = previous.subtract(now);
				movedAt = mc.level.getGameTime();
			} else if (since > 1L) {
				velocity = Vec3.ZERO;
			}
			previous = now;
		} else {
			velocity = Vec3.ZERO;
		}
		return velocity;
	}

	private static final List<GarbageCollectorMXBean> COLLECTORS = ManagementFactory.getGarbageCollectorMXBeans();
	private static long rateAt;
	private static long rateBytes = -1;
	private static long rateCollections = -1;
	private static long rate;

	/** Bytes allocated per second, measured twice a second between garbage collections. */
	private static long allocationRate(long allocated) {
		long now = System.currentTimeMillis();
		if (now - rateAt >= 500L) {
			long collections = 0;
			for (GarbageCollectorMXBean collector : COLLECTORS) {
				collections += collector.getCollectionCount();
			}
			if (rateAt != 0L && collections == rateCollections) {
				rate = Math.round((allocated - rateBytes) * (1000.0 / (now - rateAt)));
			}
			rateAt = now;
			rateBytes = allocated;
			rateCollections = collections;
		}
		return rate;
	}
}
