package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.CombatTracker;
import com.flintclient.client.control.feature.CooldownDurations;
import com.flintclient.client.control.feature.Features;
import com.flintclient.client.control.feature.FogPlan;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.feature.Waypoint;
import com.flintclient.client.control.fx.MotionBlur;
import com.flintclient.client.control.model.KeySetting;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.HudMods;
import com.flintclient.client.control.model.mods.PlayerMods;
import com.flintclient.client.control.model.mods.UtilityMods;
import com.flintclient.client.control.model.mods.VisualMods;
import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft 26.3's side of Flint's feature modules: what the mixins in {@code mixin.feature} call. The decisions live in
 * the version-neutral {@code control.feature} classes and the module settings; this class reads Minecraft's state for
 * them and applies their answers. Every hook path is fault-isolated ({@link HookGuard}): the first exception turns that
 * path off for the session and the game carries on as vanilla. Per-frame paths allocate nothing in steady state.
 * Render thread only.
 */
public final class Mc263Features {
	// Module indices, looked up once.
	private static final int FREELOOK = Mods.index(PlayerMods.Freelook.ID);
	private static final int SPRINT = Mods.index(PlayerMods.ToggleSprint.ID);
	private static final int FOV = Mods.index(VisualMods.FovChanger.ID);
	private static final int TIME = Mods.index(VisualMods.TimeChanger.ID);
	private static final int LIGHTING = Mods.index(VisualMods.Lighting.ID);
	private static final int FOG = Mods.index(VisualMods.FogCustomizer.ID);
	private static final int MENU_BLUR = Mods.index(VisualMods.MenuBlur.ID);
	private static final int GUI_SCALE = Mods.index(VisualMods.GuiScale.ID);
	private static final int MOTION_BLUR = Mods.index(VisualMods.MotionBlur.ID);
	private static final int KILL_SOUNDS = Mods.index(PlayerMods.KillSounds.ID);
	private static final int TITLES = Mods.index(UtilityMods.Titles.ID);
	private static final int WAYPOINTS = Mods.index(UtilityMods.Waypoints.ID);
	private static final int MOMENTUM = Mods.index(VisualMods.Momentum.ID);
	private static final int SCREENSHOTS = Mods.index(UtilityMods.Screenshots.ID);
	private static final int COLOR_GRADE = Mods.index(VisualMods.ColorSaturation.ID);
	private static final int DEATH_INFO = Mods.index(UtilityMods.DeathInfo.ID);

	private static final HookGuard TICK = guard("feature tick");
	private static final HookGuard CAMERA = guard("camera");
	private static final HookGuard INPUT = guard("movement input");
	private static final HookGuard RENDER = guard("render options");
	private static final HookGuard COMBAT = guard("combat");
	private static final HookGuard TEXT = guard("titles");

	/** Key names already resolved to keys. */
	private static final Map<String, InputConstants.Key> KEYS = new HashMap<>();

	private static @Nullable ClientLevel seenLevel;
	private static boolean freelookKeyWas;
	private static boolean createKeyWas;
	private static boolean menuKeyWas;
	private static boolean toggleKeyWas;
	/** The Hide key's step: 0 shows every waypoint, 1 only the near ones, 2 none (wWaypoints' hide cycle). */
	private static int hideStep;
	private static @Nullable CameraType freelookRestore;
	private static int appliedGuiScale = Integer.MIN_VALUE;
	private static boolean wasDead;
	private static @Nullable String lastActionbar;
	private static long lastFrame;

	private Mc263Features() {
	}

	private static HookGuard guard(String name) {
		return new HookGuard(name, (path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	/** Whether the player is playing (in a world, no screen or overlay). */
	static boolean gameplay() {
		Minecraft mc = mc();
		return mc.level != null && mc.player != null && mc.gui.screen() == null && mc.gui.overlay() == null;
	}

	/** Whether the key called {@code name} ({@link KeySetting}) is physically held. */
	static boolean keyHeld(String name) {
		if (name.isEmpty()) {
			return false;
		}
		InputConstants.Key key = KEYS.get(name);
		if (key == null) {
			try {
				key = InputConstants.getKey(name);
			} catch (RuntimeException | LinkageError e) {
				key = InputConstants.UNKNOWN;
			}
			KEYS.put(name, key);
		}
		if (key == InputConstants.UNKNOWN) {
			return false;
		}
		if (key.getType() == com.flintclient.client.compat.McCompat.KEYBOARD) {
			return Mc263ControlPlatform.keyDown(key.getValue());
		}
		// Every mouse button, side buttons included (Minecraft itself only tracks left, middle and right).
		return Mc263ControlPlatform.mouseDown(key.getValue());
	}

	// ---------- the client tick ----------

	/**
	 * Each step of the client tick, in order. Every step has a guard of its own: one that fails (or can't link on this Minecraft)
	 * turns itself off for the session and the others keep running, instead of one fault freezing every feature (a latched
	 * Toggle Sprint or Sneak, say, that could no longer be let go).
	 */
	private static final String[] TICK_STEPS = {
		"tickWorld", "tickMovement", "tickOptions", "tickWaypoints", "tickSpeed", "tickDeath", "ModKeys.tick", "Camera.tick",
		"AutoHide.tick", "Reconnect.tick", "GameOptions.tick", "Visuals.tick", "World.tick", "Capes.tick", "Containers.tick",
		"Nametags.tick", "Tiers.tick", "Presence.tick", "StoreCapes.tick", "Chat.tick", "MoreMods.tick", "HudExtras.tick",
		"LookMods.tick", "LootBeams.tick", "Animations.tick", "Bars.tick", "DarkMode.tick", "Tools.tick", "BetterF3.tick",
		"SoundFilters.tick", "SoundActivity.tick", "HurtCam.tick", "Zoom.tick", "Crosshair.tick", "ArmorHud.tick", "Toasts.tick",
		"ScreenshotClipboard.tick", "OriginalHud.tick", "HealthIndicators.tick", "Maps.tick", "ModelFix.tick", "FallingLeaves.tick",
		"Food.tick", "ChatSafety.tick", "sound.SoundPhysics.tick", "ambient.AmbientSounds.tick", "FriendNotices.tick"};
	/** The step that latches Toggle Sprint and Sneak: while it is off, nothing is held for you. */
	private static final int MOVEMENT_STEP = 1;
	private static final HookGuard[] TICK_GUARDS = new HookGuard[TICK_STEPS.length];

	static {
		for (int i = 0; i < TICK_STEPS.length; i++) {
			TICK_GUARDS[i] = guard("feature tick (" + TICK_STEPS[i] + ")");
		}
	}

	/** From the start of every client tick. */
	public static void tick() {
		if (!TICK.on()) {
			return;
		}
		try {
			Mc263ControlPlatform.watchFocus();
		} catch (RuntimeException | LinkageError e) {
			TICK.fail(e);
			return;
		}
		for (int i = 0; i < TICK_GUARDS.length; i++) {
			HookGuard guard = TICK_GUARDS[i];
			if (!guard.on()) {
				continue;
			}
			try {
				tickStep(i);
			} catch (RuntimeException | LinkageError e) {
				guard.fail(e);
				stepFailed(i);
			}
		}
	}

	private static void tickStep(int step) {
		switch (step) {
			case 0 -> tickWorld();
			case 1 -> tickMovement();
			case 2 -> tickOptions();
			case 3 -> tickWaypoints();
			case 4 -> tickSpeed();
			case 5 -> tickDeath();
			case 6 -> ModKeys.tick(gameplay());
			case 7 -> Mc263Camera.tick();
			case 8 -> Mc263AutoHide.tick();
			case 9 -> Mc263Reconnect.tick();
			case 10 -> Mc263GameOptions.tick();
			case 11 -> Mc263Visuals.tick();
			case 12 -> Mc263World.tick();
			case 13 -> Mc263Capes.tick();
			case 14 -> Mc263Containers.tick();
			case 15 -> Mc263Nametags.tick();
			case 16 -> Mc263Tiers.tick();
			case 17 -> Mc263Presence.tick();
			case 18 -> Mc263StoreCapes.tick();
			case 19 -> Mc263Chat.tick();
			case 20 -> Mc263MoreMods.tick();
			case 21 -> Mc263HudExtras.tick();
			case 22 -> Mc263LookMods.tick();
			case 23 -> Mc263LootBeams.tick();
			case 24 -> Mc263Animations.tick();
			case 25 -> Mc263Bars.tick();
			case 26 -> Mc263DarkMode.tick();
			case 27 -> Mc263Tools.tick();
			case 28 -> Mc263BetterF3.tick(gameplay());
			case 29 -> Mc263SoundFilters.tick();
			case 30 -> Mc263SoundActivity.tick();
			case 31 -> Mc263HurtCam.tick(gameplay());
			case 32 -> Mc263Zoom.tick(gameplay());
			case 33 -> Mc263Crosshair.tick(gameplay());
			case 34 -> Mc263ArmorHud.tick();
			case 35 -> Mc263Toasts.tick();
			case 36 -> Mc263ScreenshotClipboard.tick();
			case 37 -> Mc263OriginalHud.tick();
			case 38 -> Mc263HealthIndicators.tick();
			case 39 -> Mc263Maps.tick();
			case 40 -> Mc263ModelFix.tick();
			case 41 -> Mc263FallingLeaves.tick();
			case 42 -> Mc263Food.tick();
			case 43 -> Mc263ChatSafety.tick();
			case 44 -> com.flintclient.client.control.platform.sound.SoundPhysics.tick();
			case 45 -> com.flintclient.client.control.platform.ambient.AmbientSounds.tick();
			case 46 -> Mc263FriendNotices.tick();
			default -> {
			}
		}
	}

	/** A step that failed lets go of what it held: Toggle Sprint and Sneak's latches, keys Keybind Search pressed. */
	private static void stepFailed(int step) {
		try {
			if (step == MOVEMENT_STEP) {
				Features.MOVEMENT.reset();
			} else if (TICK_STEPS[step].equals("Tools.tick")) {
				Mc263Tools.releaseAll();
			}
		} catch (RuntimeException | LinkageError ignored) {
			// Nothing more can be done for a step that is off.
		}
	}

	private static void tickWorld() {
		Minecraft mc = mc();
		ClientLevel level = mc.level;
		if (level != seenLevel) {
			seenLevel = level;
			Features.worldChanged(level != null);
			Features.waypoints().enter(level == null ? null : worldKey(mc));
			hideStep = 0;
			if (Features.FREELOOK.active()) {
				stopFreelook();
			}
		}
	}

	/** "sp:<save folder>" or "mp:<server address>": whose waypoints these are. */
	private static @Nullable String worldKey(Minecraft mc) {
		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null) {
			Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			Path name = root.getFileName();
			return "sp:" + (name == null ? "world" : name.toString());
		}
		ServerData data = mc.getCurrentServer();
		return data == null ? null : "mp:" + data.ip.toLowerCase(Locale.ROOT).strip();
	}

	private static void tickMovement() {
		Minecraft mc = mc();
		boolean on = Mods.on(SPRINT) && mc.player != null;
		if (!on) {
			Features.MOVEMENT.tick(false, false, false, false);
			return;
		}
		ModuleState sprint = Mods.state(SPRINT);
		// Only presses made while playing count (typing in chat never toggles anything, and a key held through a screen
		// closing isn't a tap).
		Features.MOVEMENT.tick(Mc263ControlPlatform.physicallyDown(mc.options.keySprint), Mc263ControlPlatform.physicallyDown(mc.options.keyShift),
			sprint.bool(PlayerMods.ToggleSprint.SPRINT), sprint.bool(PlayerMods.ToggleSprint.SNEAK), gameplay());
	}

	/** Momentum's speed: where you are at the end of this tick (only while the module is on). */
	private static void tickSpeed() {
		LocalPlayer player = mc().player;
		if (player == null || !Mods.on(MOMENTUM)) {
			Features.SPEED.reset();
			return;
		}
		Features.SPEED.sample(player.getX(), player.getY(), player.getZ());
	}

	/** Modules that set one of Minecraft's own switches: applied when they change, put back when turned off. */
	private static void tickOptions() {
		Minecraft mc = mc();
		// Motion blur.
		ModuleState motion = Mods.state(MOTION_BLUR);
		MotionBlur.configure(Mods.on(MOTION_BLUR), motion.decimal(VisualMods.MotionBlur.STRENGTH), motion.option(VisualMods.MotionBlur.ALGORITHM),
			motion.option(VisualMods.MotionBlur.PROFILE), motion.bool(VisualMods.MotionBlur.REFRESH_SCALING),
			motion.option(VisualMods.MotionBlur.QUALITY), motion.bool(VisualMods.MotionBlur.MOVEMENT));
		// Color Saturation: read each tick (five numbers), applied by the grading pass each frame.
		ModuleState grade = Mods.state(COLOR_GRADE);
		com.flintclient.client.control.fx.ColorGrade.set(Mods.on(COLOR_GRADE), grade.integer(VisualMods.ColorSaturation.HUE),
			grade.integer(VisualMods.ColorSaturation.SATURATION) / 100F, grade.integer(VisualMods.ColorSaturation.BRIGHTNESS) / 100F,
			grade.integer(VisualMods.ColorSaturation.CONTRAST) / 100F, grade.bool(VisualMods.ColorSaturation.GRAYSCALE));
		// GUI scale: Minecraft reads it in resizeGui (see guiScale below); a change re-runs that.
		int scale = Mods.on(GUI_SCALE) ? Mods.state(GUI_SCALE).option(VisualMods.GuiScale.SCALE) : -1;
		if (scale != appliedGuiScale) {
			appliedGuiScale = scale;
			mc.resizeGui();
		}
	}

	private static void tickWaypoints() {
		Minecraft mc = mc();
		LocalPlayer player = mc.player;
		if (player == null || !Mods.on(WAYPOINTS)) {
			createKeyWas = false;
			toggleKeyWas = false;
			menuKeyWas = false;
			return;
		}
		ModuleState state = Mods.state(WAYPOINTS);
		boolean play = gameplay();
		boolean create = play && keyHeld(state.text(UtilityMods.Waypoints.CREATE_KEY));
		if (create && !createKeyWas) {
			toggleWaypoint(mc, player);
		}
		createKeyWas = create;
		boolean menu = play && keyHeld(state.text(UtilityMods.Waypoints.MENU_KEY));
		if (menu && !menuKeyWas) {
			com.flintclient.client.control.rshift.ModMenuScreen.showWaypoints();
			com.flintclient.client.control.ControlCenter.openWindow(null);
		}
		menuKeyWas = menu;
		boolean toggle = play && keyHeld(state.text(UtilityMods.Waypoints.TOGGLE_KEY));
		if (toggle && !toggleKeyWas) {
			int near = state.integer(UtilityMods.Waypoints.HIDE_WITHIN);
			hideStep = hideStep == 0 && near > 0 ? 1 : hideStep == 2 ? 0 : 2;
			actionbar(Component.literal(hideStep == 0 ? "Showing waypoints" : hideStep == 1 ? "Showing waypoints within " + near + " blocks"
				: "Hiding waypoints"));
		}
		toggleKeyWas = toggle;
		boolean nextSet = play && keyHeld(state.text(UtilityMods.Waypoints.NEXT_SET_KEY));
		String dimension = dimension();
		if (nextSet && !nextSetKeyWas && dimension != null) {
			var store = Features.waypoints();
			java.util.List<String> sets = store.sets(dimension);
			String next = sets.get(Math.floorMod(sets.indexOf(store.activeSet(dimension)) + 1, sets.size()));
			store.setActiveSet(dimension, next);
			actionbar(Component.literal("Waypoint set: " + (next.isEmpty() ? "Default" : next)));
		}
		nextSetKeyWas = nextSet;
		deathsReached(player, state, dimension);
	}

	/**
	 * Death waypoints you've got back to go (Remove when reached): one counts once you've been away from it (more than twice
	 * the distance) and then come within it, alive, in its dimension.
	 */
	private static void deathsReached(LocalPlayer player, ModuleState state, @Nullable String dimension) {
		if (dimension == null || !state.bool(UtilityMods.Waypoints.DEATH_REACHED) || player.isDeadOrDying()) {
			return;
		}
		int range = state.integer(UtilityMods.Waypoints.DEATH_RANGE);
		var store = Features.waypoints();
		java.util.List<Waypoint> all = store.all();
		for (int i = 0; i < all.size(); i++) {
			Waypoint w = all.get(i);
			if (!w.death() || !w.dimension().equals(dimension)) {
				continue;
			}
			double d = w.distanceTo(player.getX(), player.getY(), player.getZ());
			if (d > range * 2 + 4) {
				AWAY_FROM_DEATH.add(w);
			} else if (d <= range && AWAY_FROM_DEATH.remove(w)) {
				store.remove(i);
				actionbar(Component.literal("Reached your death point"));
				return;
			}
		}
	}

	/** Death waypoints you've been away from since they were made (or since the game started). */
	private static final java.util.Set<Waypoint> AWAY_FROM_DEATH = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
	private static boolean nextSetKeyWas;

	/**
	 * The moment you die, once: Waypoints' death marker, Screenshot Uploader's death screenshot (as F2 takes it) and Death
	 * Info's line in chat.
	 */
	private static void tickDeath() {
		Minecraft mc = mc();
		LocalPlayer player = mc.player;
		boolean dead = player != null && player.isDeadOrDying();
		if (dead && !wasDead) {
			if (Mods.on(WAYPOINTS) && Mods.state(WAYPOINTS).bool(UtilityMods.Waypoints.ON_DEATH) && mc.level != null) {
				// A death waypoint: shown whatever the active set, the newest few kept.
				// wWaypoints' death waypoint: "Latest Death" with a skull (the one before becomes "Old Death"), grey, a label only.
				var deaths = Features.waypoints();
				java.util.List<Waypoint> all = deaths.all();
				for (int i = 0; i < all.size(); i++) {
					if (all.get(i).death() && all.get(i).name().equals(LATEST_DEATH)) {
						deaths.set(i, all.get(i).withName(OLD_DEATH));
					}
				}
				deaths.add(new Waypoint(LATEST_DEATH, Mth.floor(player.getX()), Mth.floor(player.getY()), Mth.floor(player.getZ()),
					mc.level.dimension().identifier().toString(), DEATH_COLOR, true).withShows(true, true, false, false).withKind(Waypoint.DEATH));
				Features.waypoints().trimDeaths(Mods.state(WAYPOINTS).integer(UtilityMods.Waypoints.DEATH_LIMIT));
			}
			if (Mods.on(SCREENSHOTS) && Mods.state(SCREENSHOTS).bool(UtilityMods.Screenshots.ON_DEATH)) {
				net.minecraft.client.Screenshot.grab(mc.gameDirectory, com.flintclient.client.compat.McCompat.mainRenderTarget(),
					message -> mc.execute(() -> com.flintclient.client.compat.McCompat.hud().getChat().addClientSystemMessage(message)));
			}
			if (Mods.on(DEATH_INFO) && Mods.state(DEATH_INFO).bool(UtilityMods.DeathInfo.CHAT)) {
				com.flintclient.client.compat.McCompat.hud().getChat().addClientSystemMessage(Component.literal("You died at " + where(player)).withStyle(ChatFormatting.GRAY));
			}
		}
		wasDead = dead;
	}

	/** Death Info's line on the death screen ({@code player} is the one who died), or null when it is off. */
	public static @Nullable Component deathScreenLine(Player player) {
		if (!Mods.on(DEATH_INFO) || !Mods.state(DEATH_INFO).bool(UtilityMods.DeathInfo.SCREEN)) {
			return null;
		}
		return Component.literal(where(player)).withStyle(ChatFormatting.GRAY);
	}

	/** "12, 64, -40 in the overworld": the block you are at and its dimension. */
	private static String where(Player player) {
		String dimension = player.level().dimension().identifier().getPath().replace('_', ' ');
		return Mth.floor(player.getX()) + ", " + Mth.floor(player.getY()) + ", " + Mth.floor(player.getZ()) + " in the " + dimension;
	}

	/** Adds a waypoint at your block; returns its index or -1. */
	public static int addWaypointHere(String name, int rgb) {
		Minecraft mc = mc();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) {
			return -1;
		}
		String dimension = mc.level.dimension().identifier().toString();
		int[] at = blockPosition();
		return Features.waypoints().add(new Waypoint(name, at[0], at[1], at[2], dimension, rgb, true)
			.withGroup(Features.waypoints().activeSet(dimension)));
	}

	/** The block you stand on (wWaypoints' standing waypoint: under your feet, or the one you're sunk into), or null outside a world. */
	public static double @Nullable [] position() {
		LocalPlayer player = mc().player;
		return player == null ? null : new double[] {player.getX(), player.getY(), player.getZ()};
	}

	/** The block you stand on, or null outside a world. */
	public static int @Nullable [] blockPosition() {
		LocalPlayer player = mc().player;
		return player == null ? null : standing(player);
	}

	private static int[] standing(LocalPlayer player) {
		double y = player.getY();
		int floor = Mth.floor(y);
		return new int[] {Mth.floor(player.getX()), !player.onGround() ? floor - 1 : y - floor >= 0.5 ? floor : floor - 1, Mth.floor(player.getZ())};
	}

	/** The dimension you're in ("minecraft:overworld"), or null outside a world. */
	public static @Nullable String dimension() {
		ClientLevel level = mc().level;
		return level == null ? null : level.dimension().identifier().toString();
	}

	/** Whether the waypoint markers are showing (the module is on and the Hide key hasn't hidden them all). */
	public static boolean markersShown() {
		return Mods.on(WAYPOINTS) && hideStep != 2;
	}

	/** While the Hide key shows only near waypoints: how near (blocks); otherwise -1. */
	public static int shownWithin() {
		return hideStep == 1 ? Mods.state(WAYPOINTS).integer(UtilityMods.Waypoints.HIDE_WITHIN) : -1;
	}

	public static final String LATEST_DEATH = "\u2620 Latest Death";
	public static final String OLD_DEATH = "\u2620 Old Death";
	public static final int DEATH_COLOR = 0xC8C8C8;
	/** wWaypoints' waypoint palette without its white and black: new waypoints take the next one. */
	private static final int[] PALETTE = {0xFF2F20, 0xFF9F1C, 0xFFE530, 0x21D85B, 0x19B7FF, 0x5D38FF, 0xF045B7};

	/** The colour a new waypoint takes: the next of the palette (Random colors), else Flint orange. */
	public static int nextWaypointColor() {
		if (!Mods.state(WAYPOINTS).bool(UtilityMods.Waypoints.RANDOM_COLORS)) {
			return 0xFF8139;
		}
		int made = 0;
		for (Waypoint w : Features.waypoints().all()) {
			if (!w.death()) {
				made++;
			}
		}
		return PALETTE[made % PALETTE.length];
	}

	/**
	 * The Create key, as wWaypoints' quick waypoint: the block you stand on (sneaking: the block you look at, 16 blocks away at
	 * most) gets a waypoint, or loses the one it has.
	 */
	private static void toggleWaypoint(Minecraft mc, LocalPlayer player) {
		if (mc.level == null) {
			return;
		}
		int bx;
		int by;
		int bz;
		if (player.isShiftKeyDown()) {
			net.minecraft.world.phys.HitResult hit = player.pick(16, 1, false);
			if (!(hit instanceof net.minecraft.world.phys.BlockHitResult block) || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
				actionbar(Component.literal("Look at a block within 16 blocks"));
				return;
			}
			bx = block.getBlockPos().getX();
			by = block.getBlockPos().getY();
			bz = block.getBlockPos().getZ();
		} else {
			int[] at = standing(player);
			bx = at[0];
			by = at[1];
			bz = at[2];
		}
		String dimension = mc.level.dimension().identifier().toString();
		var store = Features.waypoints();
		java.util.List<Waypoint> all = store.all();
		for (int i = 0; i < all.size(); i++) {
			Waypoint w = all.get(i);
			if (!w.death() && w.x() == bx && w.y() == by && w.z() == bz && w.dimension().equals(dimension)) {
				store.remove(i);
				actionbar(Component.literal("Removed " + w.name()));
				return;
			}
		}
		String suggested = store.nextName("Waypoint");
		int x = bx;
		int y = by;
		int z = bz;
		com.flintclient.client.control.rshift.WaypointPromptScreen.Create make = (name, rgb) -> {
			Waypoint made = new Waypoint(name, x, y, z, dimension, rgb, true).withGroup(store.activeSet(dimension));
			if (store.add(made) >= 0) {
				actionbar(Component.literal("Added " + made.name() + " at " + x + ", " + y + ", " + z));
			}
		};
		if (Mods.state(WAYPOINTS).bool(UtilityMods.Waypoints.ASK_NAME)) {
			// A small window asks for the name and colour first (Create or Cancel); the place is the one picked now.
			mc.gui.setScreen(new com.flintclient.client.control.rshift.WaypointPromptScreen(null, suggested, bx, by, bz, placeName(dimension),
				nextWaypointColor(), make));
		} else {
			make.create(suggested, nextWaypointColor());
		}
	}

	/** A dimension's name for people ("Overworld", "The Nether", "The End", else its id's path in words). */
	static String placeName(String dimension) {
		return switch (dimension) {
			case "minecraft:overworld" -> "Overworld";
			case "minecraft:the_nether" -> "The Nether";
			case "minecraft:the_end" -> "The End";
			default -> {
				String path = dimension.substring(dimension.indexOf(':') + 1).replace('_', ' ');
				yield path.isEmpty() ? "" : Character.toUpperCase(path.charAt(0)) + path.substring(1);
			}
		};
	}

	// ---------- movement input ----------

	/** From the end of {@code KeyboardInput.tick}: holds sprint or sneak while their toggle is latched. */
	public static Input movementInput(Input keys) {
		if (!INPUT.on() || !TICK.on() || !TICK_GUARDS[MOVEMENT_STEP].on()) {
			return keys;
		}
		try {
			boolean sprint = Features.MOVEMENT.holdSprint();
			boolean sneak = Features.MOVEMENT.holdSneak();
			if (!sprint && !sneak || !Mods.on(SPRINT)) {
				return keys;
			}
			return new Input(keys.forward(), keys.backward(), keys.left(), keys.right(), keys.jump(), keys.shift() || sneak, keys.sprint() || sprint);
		} catch (RuntimeException | LinkageError e) {
			INPUT.fail(e);
			return keys;
		}
	}

	// ---------- camera: zoom and freelook ----------

	/** Once per frame, before the camera is placed: reads the zoom and freelook keys. */
	public static void beforeCamera() {
		if (!CAMERA.on()) {
			return;
		}
		try {
			long now = System.nanoTime();
			if (now == lastFrame) {
				return;
			}
			lastFrame = now;
			pollFreelook();
			Mc263Camera.pollViews(Features.FREELOOK.active());
		} catch (RuntimeException | LinkageError e) {
			CAMERA.fail(e);
		}
	}

	private static void pollFreelook() {
		Minecraft mc = mc();
		LocalPlayer player = mc.player;
		boolean on = Mods.on(FREELOOK) && player != null;
		ModuleState state = Mods.state(FREELOOK);
		boolean held = on && keyHeld(state.text(PlayerMods.Freelook.KEY));
		boolean active = Features.FREELOOK.active();
		boolean want;
		if (!on) {
			want = false;
		} else if (state.option(PlayerMods.Freelook.MODE) == 0) {
			want = held && (active || gameplay());
		} else {
			want = active;
			if (held && !freelookKeyWas && gameplay()) {
				want = !active;
			}
		}
		freelookKeyWas = held;
		if (want && !active && player != null) {
			Features.FREELOOK.start(player.getYRot(), player.getXRot());
			freelookRestore = mc.options.getCameraType();
			mc.options.setCameraType(state.option(PlayerMods.Freelook.VIEW) == 0 ? CameraType.THIRD_PERSON_BACK : CameraType.THIRD_PERSON_FRONT);
		} else if (!want && active) {
			stopFreelook();
		}
	}

	private static void stopFreelook() {
		Features.FREELOOK.stop();
		CameraType restore = freelookRestore;
		freelookRestore = null;
		if (restore != null) {
			mc().options.setCameraType(restore);
		}
	}

	/** The camera's yaw: freelook's own while it is on. */
	public static float cameraYaw(float vanilla) {
		return Features.FREELOOK.active() ? Features.FREELOOK.yaw() : vanilla;
	}

	public static float cameraPitch(float vanilla) {
		return Features.FREELOOK.active() ? Features.FREELOOK.pitch() : vanilla;
	}

	/**
	 * Mouse movement about to turn the player: freelook takes it (returns true: don't turn the player); otherwise
	 * Zoom slows it through the sensitivity option (Mc263Zoom).
	 */
	public static boolean freelookTurn(double dx, double dy) {
		if (!Features.FREELOOK.active()) {
			return false;
		}
		ModuleState state = Mods.state(FREELOOK);
		Features.FREELOOK.turn(dx, dy, state.bool(PlayerMods.Freelook.INVERT_YAW), state.bool(PlayerMods.Freelook.INVERT_PITCH));
		return true;
	}

	/** From {@code Camera.calculateFov}: the field of view divided by the zoom (Mc263Zoom). */
	public static float fov(float fov) {
		return CAMERA.on() ? Mc263Zoom.fov(fov) : fov;
	}

	/** A mouse wheel notch while playing: true when the zoom or the Camera module took it (the hotbar doesn't move). */
	public static boolean scroll(double amount) {
		if (Mc263Camera.scroll(amount)) {
			return true;
		}
		if (!CAMERA.on()) {
			return false;
		}
		try {
			return Mc263Zoom.scroll(amount);
		} catch (RuntimeException | LinkageError e) {
			CAMERA.fail(e);
			return false;
		}
	}

	/**
	 * From {@code AbstractClientPlayer.getFieldOfViewModifier}: Minecraft's own modifiers, each scaled by the FOV Changer's
	 * percentage; NaN leaves vanilla's answer. The same arithmetic as vanilla, so 100% everywhere is vanilla exactly.
	 */
	public static float fovModifier(AbstractClientPlayer player, boolean firstPerson, float effectScale) {
		if (!CAMERA.on() || !Mods.on(FOV)) {
			return Float.NaN;
		}
		try {
			ModuleState fov = Mods.state(FOV);
			float modifier = 1.0F;
			if (player.getAbilities().flying) {
				modifier *= 1 + 0.1F * fov.integer(VisualMods.FovChanger.FLYING) / 100.0F;
			}
			float walkingSpeed = player.getAbilities().getWalkingSpeed();
			if (walkingSpeed != 0.0F) {
				float speedFactor = (float) player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED) / walkingSpeed;
				float vanilla = (speedFactor + 1.0F) / 2.0F;
				modifier *= 1 + (vanilla - 1) * fov.integer(VisualMods.FovChanger.SPEED) / 100.0F;
			}
			if (player.isUsingItem()) {
				if (player.getUseItem().is(Items.BOW)) {
					float scale = Math.min(player.getTicksUsingItem() / 20.0F, 1.0F);
					modifier *= 1.0F - Mth.square(scale) * 0.15F * fov.integer(VisualMods.FovChanger.BOW) / 100.0F;
				} else if (firstPerson && player.isScoping()) {
					return fov.bool(VisualMods.FovChanger.SPYGLASS) ? 0.1F : 1.0F;
				}
			}
			return Mth.lerp(effectScale, 1.0F, modifier);
		} catch (RuntimeException | LinkageError e) {
			CAMERA.fail(e);
			return Float.NaN;
		}
	}

	// ---------- how the world looks ----------


	/** From the lightmap: the brightness the Lighting module sets, or vanilla's. */
	public static float brightness(float vanilla) {
		if (!RENDER.on() || !Mods.on(LIGHTING)) {
			return vanilla;
		}
		ModuleState state = Mods.state(LIGHTING);
		return state.option(VisualMods.Lighting.MODE) == 0 ? 15.0F : state.integer(VisualMods.Lighting.BRIGHTNESS) / 100.0F;
	}

	/** {@code Options.getMenuBackgroundBlurriness}: the Menu Blur module's amount while on. */
	public static int menuBlur(int vanilla) {
		return Mods.on(MENU_BLUR) ? Mods.state(MENU_BLUR).integer(VisualMods.MenuBlur.AMOUNT) : vanilla;
	}

	/** The GUI scale Minecraft computes the window's scale from: the module's while on (0 = Auto). */
	public static int guiScale(int vanilla) {
		return Mods.on(GUI_SCALE) ? Mods.state(GUI_SCALE).option(VisualMods.GuiScale.SCALE) : vanilla;
	}

	/** From {@code FogRenderer.setupFog}: the Fog Customizer's changes to the fog just computed. */
	public static void fog(FogData fog, FogType type) {
		fog(fog, type, false);
	}

	/**
	 * The Fog Customizer's changes to the fog just computed (distances by control.feature.FogPlan, the same on every line).
	 * {@code skyAndClouds}: 1.21.1's sky pass and its clouds, which No fog and the distance multiplier leave alone.
	 */
	public static void fog(FogData fog, FogType type, boolean skyAndClouds) {
		if (!RENDER.on() || !Mods.on(FOG)) {
			return;
		}
		try {
			ModuleState state = Mods.state(FOG);
			Mc263Fog.apply(fog, type, state, skyAndClouds);
			if (FogPlan.overridesColor(Mc263Fog.medium(type), state)) {
				int rgb = state.rgb(VisualMods.FogCustomizer.COLOR);
				fog.color.set((rgb >> 16 & 0xFF) / 255.0F, (rgb >> 8 & 0xFF) / 255.0F, (rgb & 0xFF) / 255.0F, 1.0F);
			}
		} catch (RuntimeException | LinkageError e) {
			RENDER.fail(e);
		}
	}

	/** The overworld clock the sky reads, while Time Changer is on (the real clock otherwise). */
	public static long skyTicks(long vanilla) {
		if (!RENDER.on() || !Mods.on(TIME)) {
			return vanilla;
		}
		ModuleState state = Mods.state(TIME);
		long timeOfDay;
		if (state.option(VisualMods.TimeChanger.MODE) == 1) {
			LocalTime now = LocalTime.now();
			timeOfDay = Math.floorMod((now.getHour() - 6) * 1000L + now.getMinute() * 1000L / 60, 24000L);
		} else {
			timeOfDay = Math.floorMod((state.integer(VisualMods.TimeChanger.HOUR) - 6) * 1000L, 24000L);
		}
		// Keep the day (the moon phase) and set only the time of day.
		return Math.floorDiv(vanilla, 24000L) * 24000L + timeOfDay;
	}

	// ---------- combat ----------

	/** You attack {@code target}: remember the distance (eyes to the nearest point of its hitbox). */
	public static void attack(Player player, Entity target) {
		if (!COMBAT.on()) {
			return;
		}
		try {
			double distance = Math.sqrt(target.getBoundingBox().distanceToSqr(player.getEyePosition()));
			Features.COMBAT.swing(target.getId(), distance, Features.nowMs());
			Mc263OriginalHud.attacked(target, distance);
			if (Mc263Visuals.alwaysSharpness() && target instanceof LivingEntity && player.getMainHandItem().isEnchanted()) {
				Minecraft.getInstance().particleEngine.createTrackingEmitter(target, net.minecraft.core.particles.ParticleTypes.ENCHANTED_HIT);
			}
		} catch (RuntimeException | LinkageError e) {
			COMBAT.fail(e);
		}
	}

	/** The server's damage event for {@code entity}. */
	public static void damaged(LivingEntity entity, DamageSource source) {
		if (!COMBAT.on()) {
			return;
		}
		try {
			Minecraft mc = mc();
			LocalPlayer player = mc.player;
			if (player == null) {
				return;
			}
			if (entity == player) {
				return;
			}
			if (source.getEntity() == player) {
				Features.COMBAT.landed(entity.getId(), entity instanceof Player, Features.nowMs());
				lastHitKind.put(entity.getId(), killKind(source.getDirectEntity(), player));
				if (lastHitKind.size() > 256) {
					lastHitKind.clear();
				}
			}
		} catch (RuntimeException | LinkageError e) {
			COMBAT.fail(e);
		}
	}

	/** Your hurt animation, with the direction the server gave (degrees relative to where you face). */
	public static void localHurt(Player player, float hurtDir) {
		if (!COMBAT.on() || player != mc().player) {
			return;
		}
		try {
			Features.COMBAT.hurt(hurtDir, Features.nowMs());
		} catch (RuntimeException | LinkageError e) {
			COMBAT.fail(e);
		}
	}

	/** An entity event; 3 is a death. */
	public static void entityEvent(LivingEntity entity, byte id) {
		if (!COMBAT.on() || id != 3) {
			return;
		}
		try {
			if (entity == mc().player) {
				return;
			}
			Integer kind = lastHitKind.remove(entity.getId());
			if (Features.COMBAT.died(entity.getId(), Features.nowMs())) {
				killSound(Features.COMBAT.killWasPlayer(), kind == null ? KILL_MELEE : kind);
			}
		} catch (RuntimeException | LinkageError e) {
			COMBAT.fail(e);
		}
	}

	private static final int KILL_MELEE = 0;
	private static final int KILL_BOW = 1;
	private static final int KILL_ROD = 2;
	private static final int KILL_THROWABLE = 3;
	/** What dealt your last hit to each entity (by id), until it dies or the map is cleared. */
	private static final Map<Integer, Integer> lastHitKind = new HashMap<>();

	/** The kind of kill a hit from {@code direct} (the arrow, the hook, you) would make. */
	private static int killKind(@Nullable Entity direct, Player player) {
		if (direct == null || direct == player) {
			return KILL_MELEE;
		}
		if (direct instanceof net.minecraft.world.entity.projectile.FishingHook) {
			return KILL_ROD;
		}
		if (direct instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow
			&& !(direct instanceof net.minecraft.world.entity.projectile.arrow.ThrownTrident)) {
			return KILL_BOW;
		}
		return direct instanceof net.minecraft.world.entity.projectile.Projectile ? KILL_THROWABLE : KILL_MELEE;
	}

	private static void killSound(boolean player, int kind) {
		if (!Mods.on(KILL_SOUNDS)) {
			return;
		}
		ModuleState state = Mods.state(KILL_SOUNDS);
		if (state.bool(PlayerMods.KillSounds.PLAYERS_ONLY) && !player) {
			return;
		}
		String sound = player ? state.text(PlayerMods.KillSounds.PLAYER) : "";
		int volume = state.integer(PlayerMods.KillSounds.PLAYER_VOLUME);
		if (sound.isEmpty()) {
			switch (kind) {
				case KILL_BOW -> {
					sound = state.text(PlayerMods.KillSounds.BOW);
					volume = state.integer(PlayerMods.KillSounds.BOW_VOLUME);
				}
				case KILL_ROD -> {
					sound = state.text(PlayerMods.KillSounds.ROD);
					volume = state.integer(PlayerMods.KillSounds.ROD_VOLUME);
				}
				case KILL_THROWABLE -> {
					sound = state.text(PlayerMods.KillSounds.THROWABLE);
					volume = state.integer(PlayerMods.KillSounds.THROWABLE_VOLUME);
				}
				default -> {
					sound = state.text(PlayerMods.KillSounds.MELEE);
					volume = state.integer(PlayerMods.KillSounds.MELEE_VOLUME);
				}
			}
		}
		lastKillSound = sound;
		playSound(sound, volume / 100.0F);
	}

	private static String lastKillSound = "";

	/** The sound the last kill played ("" for none), for the dev tour. */
	public static String lastKillSound() {
		return lastKillSound;
	}

	/** Plays sound {@code id} (namespaced) to you only, as a menu sound; nothing for "" or an id this version doesn't know. */
	public static void playSound(String id, float volume) {
		Identifier location = id.isEmpty() ? null : Identifier.tryParse(id);
		if (location == null || !net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.containsKey(location)) {
			return;
		}
		mc().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(location), 1.0F, volume));
	}

	/** The server started a cooldown on {@code group} for {@code ticks}. */
	public static void cooldownStarted(Identifier group, int ticks) {
		CooldownDurations.started(group.toString(), ticks);
	}

	public static void cooldownEnded(Identifier group) {
		CooldownDurations.ended(group.toString());
	}

	// ---------- titles ----------
	// Title Tweaks' rules (scale, auto scale, offsets, opacity, shadow, clear on disconnect), adapted from Title Tweaks by
	// Microcontrollers and Wyvest, LGPL-3.0: see META-INF/licenses/LGPL-3.0-TitleTweaks.txt.

	/** Whether titles and subtitles are drawn (Title Tweaks' Disable titles hides them). */
	public static boolean showTitles() {
		return !Mods.on(TITLES) || !Mods.state(TITLES).bool(UtilityMods.Titles.DISABLE);
	}

	/** Whether Title Tweaks changes how titles are drawn (scale, place, opacity or shadow). */
	public static boolean tweakTitles() {
		return TEXT.on() && Mods.on(TITLES);
	}

	/**
	 * Title Tweaks' extra scale for the title ({@code subtitle} false, drawn at 4×) or the subtitle (2×): Title Scale, made
	 * smaller still when Automatically scale titles is on and it would run past the screen's sides (a title that is just one
	 * or two private-use icon characters isn't).
	 */
	public static float titleScale(Font font, Component text, boolean subtitle, int guiWidth) {
		ModuleState s = Mods.state(TITLES);
		float scale = s.integer(UtilityMods.Titles.SCALE) / 100.0F;
		if (s.bool(UtilityMods.Titles.AUTO_SCALE) && (subtitle || normalTitle(text))) {
			float width = font.width(text) * (subtitle ? 2.0F : 4.0F) + 50.0F;
			if (width > guiWidth) {
				scale = guiWidth / width * s.integer(UtilityMods.Titles.SCALE) / 100.0F;
			}
		}
		return scale;
	}

	/** Whether a title is text, not one or two private-use characters (an icon from a server's font). */
	static boolean normalTitle(Component title) {
		String text = title.getString();
		if (text.isEmpty()) {
			return true;
		}
		char c = text.charAt(0);
		return !(c >= '' && c <= '') || text.length() > 2;
	}

	/** How far up the title ({@code subtitle} false) or the subtitle moves, in its own scaled units. */
	public static int titleOffset(boolean subtitle) {
		return Mods.state(TITLES).integer(subtitle ? UtilityMods.Titles.SUBTITLE_OFFSET : UtilityMods.Titles.TITLE_OFFSET);
	}

	/** The title's colour with its alpha held to Title Opacity. */
	public static int titleColor(int color) {
		int most = (int) (Mods.state(TITLES).integer(UtilityMods.Titles.OPACITY) / 100.0F * 255.0F);
		int alpha = Math.min(color >>> 24, most);
		return alpha << 24 | color & 0xFFFFFF;
	}

	/** Whether titles are drawn without their shadow (and backdrop). */
	public static boolean titleNoShadow() {
		return Mods.state(TITLES).bool(UtilityMods.Titles.NO_SHADOW);
	}

	/** Leaving a world or server: Clear on disconnect wipes a title that would otherwise stay on screen. */
	public static void titlesDisconnect() {
		if (TEXT.on() && Mods.on(TITLES) && Mods.state(TITLES).bool(UtilityMods.Titles.CLEAR_ON_DISCONNECT)) {
			try {
				com.flintclient.client.compat.McCompat.hud().clearTitles();
			} catch (RuntimeException | LinkageError e) {
				TEXT.fail(e);
			}
		}
	}

	/** A title or subtitle arrived; printed to your chat when asked. */
	public static void title(Component text, boolean subtitle) {
		if (!TEXT.on() || !Mods.on(TITLES) || !Mods.state(TITLES).bool(UtilityMods.Titles.PRINT) || text.getString().isBlank()) {
			return;
		}
		try {
			print(subtitle ? "Subtitle" : "Title", text);
		} catch (RuntimeException | LinkageError e) {
			TEXT.fail(e);
		}
	}

	/** An action bar message arrived; printed once per distinct text when asked. */
	public static void actionbar(Component text) {
		if (!TEXT.on() || !Mods.on(TITLES) || !Mods.state(TITLES).bool(UtilityMods.Titles.PRINT_ACTIONBAR)) {
			return;
		}
		try {
			String plain = text.getString();
			if (plain.isBlank() || plain.equals(lastActionbar)) {
				return;
			}
			lastActionbar = plain;
			print("Action bar", text);
		} catch (RuntimeException | LinkageError e) {
			TEXT.fail(e);
		}
	}

	private static void print(String kind, Component text) {
		MutableComponent line = Component.literal("[" + kind + "] ").withStyle(ChatFormatting.DARK_GRAY).append(text.copy());
		com.flintclient.client.compat.McCompat.hud().getChat().addClientSystemMessage(line);
	}
}
