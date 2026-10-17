package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.CombatTracker;
import com.flintclient.client.control.feature.CooldownDurations;
import com.flintclient.client.control.feature.Features;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.feature.Waypoint;
import com.flintclient.client.control.feature.ZoomState;
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
	private static final int ZOOM = Mods.index(PlayerMods.Zoom.ID);
	private static final int FREELOOK = Mods.index(PlayerMods.Freelook.ID);
	private static final int SPRINT = Mods.index(PlayerMods.ToggleSprint.ID);
	private static final int FOV = Mods.index(VisualMods.FovChanger.ID);
	private static final int TIME = Mods.index(VisualMods.TimeChanger.ID);
	private static final int LIGHTING = Mods.index(VisualMods.Lighting.ID);
	private static final int FOG = Mods.index(VisualMods.FogCustomizer.ID);
	private static final int MENU_BLUR = Mods.index(VisualMods.MenuBlur.ID);
	private static final int GUI_SCALE = Mods.index(VisualMods.GuiScale.ID);
	private static final int MOTION_BLUR = Mods.index(VisualMods.MotionBlur.ID);
	private static final int HURT_CAM = Mods.index(PlayerMods.HurtCam.ID);
	private static final int KILL_SOUNDS = Mods.index(PlayerMods.KillSounds.ID);
	private static final int TITLES = Mods.index(UtilityMods.Titles.ID);
	private static final int WAYPOINTS = Mods.index(UtilityMods.Waypoints.ID);
	private static final int NAMETAGS = Mods.index(VisualMods.Nametags.ID);
	private static final int MOMENTUM = Mods.index(VisualMods.Momentum.ID);
	private static final int SCREENSHOTS = Mods.index(UtilityMods.Screenshots.ID);
	private static final int COLOR_GRADE = Mods.index(VisualMods.ColorSaturation.ID);

	private static final HookGuard TICK = guard("feature tick");
	private static final HookGuard CAMERA = guard("camera");
	private static final HookGuard INPUT = guard("movement input");
	private static final HookGuard RENDER = guard("render options");
	private static final HookGuard COMBAT = guard("combat");
	private static final HookGuard TEXT = guard("titles");

	/** Key names already resolved to keys. */
	private static final Map<String, InputConstants.Key> KEYS = new HashMap<>();

	private static @Nullable ClientLevel seenLevel;
	private static boolean zoomKeyWas;
	private static boolean freelookKeyWas;
	private static boolean createKeyWas;
	private static boolean toggleKeyWas;
	private static boolean markersHidden;
	private static @Nullable CameraType freelookRestore;
	private static boolean zoomCinematic;
	private static boolean cinematicBefore;
	private static int appliedGuiScale = Integer.MIN_VALUE;
	private static int appliedBlur = -1;
	private static int appliedBlurAmount = -1;
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
	private static boolean gameplay() {
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
			} catch (RuntimeException e) {
				key = InputConstants.UNKNOWN;
			}
			KEYS.put(name, key);
		}
		if (key == InputConstants.UNKNOWN) {
			return false;
		}
		if (key.getType() == InputConstants.Type.KEYBOARD) {
			return Mc263ControlPlatform.keyDown(key.getValue());
		}
		var mouse = mc().mouseHandler;
		return switch (key.getValue()) {
			case InputConstants.MOUSE_BUTTON_LEFT -> mouse.isLeftPressed();
			case InputConstants.MOUSE_BUTTON_RIGHT -> mouse.isRightPressed();
			case InputConstants.MOUSE_BUTTON_MIDDLE -> mouse.isMiddlePressed();
			default -> false;
		};
	}

	// ---------- the client tick ----------

	/** From the start of every client tick. */
	public static void tick() {
		if (!TICK.on()) {
			return;
		}
		try {
			tickWorld();
			tickMovement();
			tickOptions();
			tickWaypoints();
			tickSpeed();
			tickDeathShot();
			Mc263Visuals.tick();
			Mc263World.tick();
			Mc263Nametags.tick();
			Mc263Chat.tick();
		} catch (RuntimeException e) {
			TICK.fail(e);
		}
	}

	private static void tickWorld() {
		Minecraft mc = mc();
		ClientLevel level = mc.level;
		if (level != seenLevel) {
			seenLevel = level;
			Features.worldChanged(level != null);
			Features.waypoints().enter(level == null ? null : worldKey(mc));
			markersHidden = false;
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
		// Only presses made while playing count (typing in chat never toggles anything).
		boolean play = gameplay();
		Features.MOVEMENT.tick(play && Mc263ControlPlatform.physicallyDown(mc.options.keySprint),
			play && Mc263ControlPlatform.physicallyDown(mc.options.keyShift), sprint.bool(PlayerMods.ToggleSprint.SPRINT),
			sprint.bool(PlayerMods.ToggleSprint.SNEAK));
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
		int blur = Mods.on(MOTION_BLUR) ? 1 : 0;
		int amount = Mods.state(MOTION_BLUR).integer(VisualMods.MotionBlur.AMOUNT);
		if (blur != appliedBlur || amount != appliedBlurAmount) {
			appliedBlur = blur;
			appliedBlurAmount = amount;
			MotionBlur.setStrength(amount / 100.0F);
			MotionBlur.setEnabled(blur == 1);
		}
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
			wasDead = false;
			return;
		}
		ModuleState state = Mods.state(WAYPOINTS);
		boolean play = gameplay();
		boolean create = play && keyHeld(state.text(UtilityMods.Waypoints.CREATE_KEY));
		if (create && !createKeyWas) {
			addWaypointHere(Features.waypoints().nextName("Waypoint"), 0xFF8139);
		}
		createKeyWas = create;
		boolean toggle = play && keyHeld(state.text(UtilityMods.Waypoints.TOGGLE_KEY));
		if (toggle && !toggleKeyWas) {
			markersHidden = !markersHidden;
		}
		toggleKeyWas = toggle;
		boolean dead = player.isDeadOrDying();
		if (dead && !wasDead && state.bool(UtilityMods.Waypoints.ON_DEATH)) {
			addWaypointHere(Features.waypoints().nextName("Death"), 0xE53935);
		}
		wasDead = dead;
	}

	private static boolean shotDead;

	/** Screenshot Uploader's Screenshot on death: one screenshot the moment you die, as F2 takes it. */
	private static void tickDeathShot() {
		Minecraft mc = mc();
		LocalPlayer player = mc.player;
		boolean dead = player != null && player.isDeadOrDying();
		if (dead && !shotDead && Mods.on(SCREENSHOTS) && Mods.state(SCREENSHOTS).bool(UtilityMods.Screenshots.ON_DEATH)) {
			net.minecraft.client.Screenshot.grab(mc.gameDirectory, mc.gameRenderer.mainRenderTarget(),
				message -> mc.execute(() -> mc.gui.hud.getChat().addClientSystemMessage(message)));
		}
		shotDead = dead;
	}

	/** Adds a waypoint at your block; returns its index or -1. */
	public static int addWaypointHere(String name, int rgb) {
		Minecraft mc = mc();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) {
			return -1;
		}
		return Features.waypoints().add(new Waypoint(name, Mth.floor(player.getX()), Mth.floor(player.getY()), Mth.floor(player.getZ()),
			mc.level.dimension().identifier().toString(), rgb, true));
	}

	/** Your block position, or null outside a world. */
	public static int @Nullable [] blockPosition() {
		LocalPlayer player = mc().player;
		return player == null ? null : new int[] {Mth.floor(player.getX()), Mth.floor(player.getY()), Mth.floor(player.getZ())};
	}

	/** The dimension you're in ("minecraft:overworld"), or null outside a world. */
	public static @Nullable String dimension() {
		ClientLevel level = mc().level;
		return level == null ? null : level.dimension().identifier().toString();
	}

	/** Whether the waypoint markers are showing (the module is on and the toggle key hasn't hidden them). */
	public static boolean markersShown() {
		return Mods.on(WAYPOINTS) && !markersHidden;
	}

	// ---------- movement input ----------

	/** From the end of {@code KeyboardInput.tick}: holds sprint or sneak while their toggle is latched. */
	public static Input movementInput(Input keys) {
		if (!INPUT.on()) {
			return keys;
		}
		try {
			boolean sprint = Features.MOVEMENT.holdSprint();
			boolean sneak = Features.MOVEMENT.holdSneak();
			if (!sprint && !sneak || !Mods.on(SPRINT)) {
				return keys;
			}
			return new Input(keys.forward(), keys.backward(), keys.left(), keys.right(), keys.jump(), keys.shift() || sneak, keys.sprint() || sprint);
		} catch (RuntimeException e) {
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
			pollZoom(now);
			pollFreelook();
		} catch (RuntimeException e) {
			CAMERA.fail(e);
		}
	}

	private static void pollZoom(long now) {
		boolean on = Mods.on(ZOOM) && mc().player != null;
		ModuleState zoom = Mods.state(ZOOM);
		boolean held = on && keyHeld(zoom.text(PlayerMods.Zoom.KEY));
		boolean active = Features.ZOOM.active();
		boolean want;
		if (!on) {
			want = false;
		} else if (zoom.option(PlayerMods.Zoom.MODE) == 0) {
			want = held && (active || gameplay());
		} else {
			want = active;
			if (held && !zoomKeyWas && gameplay()) {
				want = !active;
			}
		}
		zoomKeyWas = held;
		if (want && !active && !spyglassAllows(zoom.option(PlayerMods.Zoom.SPYGLASS))) {
			want = false;
		}
		Features.ZOOM.set(want, zoom.integer(PlayerMods.Zoom.LEVEL), now);
		if (want != active) {
			Minecraft mc = mc();
			if (want && zoom.bool(PlayerMods.Zoom.CINEMATIC)) {
				zoomCinematic = true;
				cinematicBefore = mc.options.smoothCamera;
				mc.options.smoothCamera = true;
			} else if (!want && zoomCinematic) {
				zoomCinematic = false;
				mc.options.smoothCamera = cinematicBefore;
			}
		}
	}

	/** The Zoom module's spyglass rule: 0 none, 1 anywhere in the inventory, 2 the hotbar, 3 a hand. */
	private static boolean spyglassAllows(int rule) {
		LocalPlayer player = mc().player;
		if (rule == 0) {
			return true;
		}
		if (player == null) {
			return false;
		}
		if (player.getMainHandItem().is(Items.SPYGLASS) || player.getOffhandItem().is(Items.SPYGLASS)) {
			return true;
		}
		if (rule == 3) {
			return false;
		}
		Inventory inventory = player.getInventory();
		int slots = rule == 2 ? Inventory.getSelectionSize() : inventory.getContainerSize();
		for (int i = 0; i < slots; i++) {
			if (inventory.getItem(i).is(Items.SPYGLASS)) {
				return true;
			}
		}
		return false;
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
	 * {@link #turnScale()} slows it while zoomed.
	 */
	public static boolean freelookTurn(double dx, double dy) {
		if (!Features.FREELOOK.active()) {
			return false;
		}
		ModuleState state = Mods.state(FREELOOK);
		Features.FREELOOK.turn(dx, dy, state.bool(PlayerMods.Freelook.INVERT_YAW), state.bool(PlayerMods.Freelook.INVERT_PITCH));
		return true;
	}

	/** How much mouse movement turns you now: 1, or 1/zoom while zoomed with Dynamic sensitivity. */
	public static double turnScale() {
		if (!Features.ZOOM.active() || !Mods.state(ZOOM).bool(PlayerMods.Zoom.LOWER_SENSITIVITY)) {
			return 1;
		}
		return 1 / Math.max(1, currentZoom(System.nanoTime()));
	}

	private static float currentZoom(long now) {
		ModuleState zoom = Mods.state(ZOOM);
		long duration = zoom.bool(PlayerMods.Zoom.SMOOTH) && !com.flintclient.client.ui.kit.Kit.reduced()
			? zoom.integer(PlayerMods.Zoom.DURATION) * 1_000_000L : 0;
		return Features.ZOOM.factor(now, duration, ZoomState.Easing.of(zoom.option(PlayerMods.Zoom.EASING)));
	}

	/** From {@code Camera.calculateFov}: the field of view divided by the zoom. */
	public static float fov(float fov) {
		if (!CAMERA.on()) {
			return fov;
		}
		try {
			float factor = currentZoom(System.nanoTime());
			return factor <= 1.0001F ? fov : fov / factor;
		} catch (RuntimeException e) {
			CAMERA.fail(e);
			return fov;
		}
	}

	/** A mouse wheel notch while playing: true when the zoom took it (the hotbar doesn't move). */
	public static boolean scroll(double amount) {
		if (!CAMERA.on() || amount == 0 || !Features.ZOOM.active() || !Mods.state(ZOOM).bool(PlayerMods.Zoom.SCROLL)) {
			return false;
		}
		try {
			return Features.ZOOM.scroll(amount > 0 ? 1 : -1, System.nanoTime());
		} catch (RuntimeException e) {
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
		} catch (RuntimeException e) {
			CAMERA.fail(e);
			return Float.NaN;
		}
	}

	// ---------- how the world looks ----------

	/** From {@code GameRenderer.bobHurt}: Minecraft's damage tilt times the Hurt Cam strength. */
	public static double hurtTilt(double strength) {
		return Mods.on(HURT_CAM) ? strength * Mods.state(HURT_CAM).integer(PlayerMods.HurtCam.STRENGTH) / 100.0 : strength;
	}

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
		if (!RENDER.on() || !Mods.on(FOG)) {
			return;
		}
		try {
			ModuleState state = Mods.state(FOG);
			if (type == FogType.WATER) {
				if (state.bool(VisualMods.FogCustomizer.CLEAR_WATER)) {
					fog.environmentalStart = Math.max(fog.environmentalStart, 32);
					fog.environmentalEnd = Math.max(fog.environmentalEnd, 256);
					fog.skyEnd = fog.environmentalEnd;
					fog.cloudEnd = fog.environmentalEnd;
				}
				return;
			}
			if (type == FogType.LAVA) {
				if (state.bool(VisualMods.FogCustomizer.CLEAR_LAVA)) {
					fog.environmentalStart = 0;
					fog.environmentalEnd = Math.max(fog.environmentalEnd, 12);
				}
				return;
			}
			if (type != FogType.NONE) {
				return;
			}
			if (state.bool(VisualMods.FogCustomizer.NO_FOG)) {
				fog.environmentalStart = Float.MAX_VALUE / 4;
				fog.environmentalEnd = Float.MAX_VALUE / 2;
				fog.renderDistanceStart = Float.MAX_VALUE / 4;
				fog.renderDistanceEnd = Float.MAX_VALUE / 2;
			} else {
				float k = state.integer(VisualMods.FogCustomizer.DISTANCE) / 100.0F;
				fog.environmentalStart *= k;
				fog.environmentalEnd *= k;
				fog.renderDistanceStart *= k;
				fog.renderDistanceEnd *= k;
			}
			if (state.bool(VisualMods.FogCustomizer.OVERRIDE_COLOR)) {
				int rgb = state.rgb(VisualMods.FogCustomizer.COLOR);
				fog.color.set((rgb >> 16 & 0xFF) / 255.0F, (rgb >> 8 & 0xFF) / 255.0F, (rgb & 0xFF) / 255.0F, 1.0F);
			}
		} catch (RuntimeException e) {
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

	/** Whether your own nametag shows in third person (the Nametags module), else vanilla's answer. */
	public static boolean showOwnName(Entity entity, boolean vanilla) {
		Minecraft mc = mc();
		if (vanilla || entity != mc.player || !Mods.on(NAMETAGS) || !Mods.state(NAMETAGS).bool(VisualMods.Nametags.OWN)) {
			return vanilla;
		}
		return !mc.options.getCameraType().isFirstPerson() && !mc.gui.hud.isHidden() && !entity.isInvisible();
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
			if (Mc263Visuals.alwaysSharpness() && target instanceof LivingEntity && player.getMainHandItem().isEnchanted()) {
				Minecraft.getInstance().particleEngine.createTrackingEmitter(target, net.minecraft.core.particles.ParticleTypes.ENCHANTED_HIT);
			}
		} catch (RuntimeException e) {
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
		} catch (RuntimeException e) {
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
		} catch (RuntimeException e) {
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
		} catch (RuntimeException e) {
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

	/** Whether titles and subtitles are drawn (the Titles module can hide them). */
	public static boolean showTitles() {
		return !Mods.on(TITLES) || Mods.state(TITLES).bool(UtilityMods.Titles.SHOW);
	}

	/** A title or subtitle arrived; printed to your chat when asked. */
	public static void title(Component text, boolean subtitle) {
		if (!TEXT.on() || !Mods.on(TITLES) || !Mods.state(TITLES).bool(UtilityMods.Titles.PRINT) || text.getString().isBlank()) {
			return;
		}
		try {
			print(subtitle ? "Subtitle" : "Title", text);
		} catch (RuntimeException e) {
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
		} catch (RuntimeException e) {
			TEXT.fail(e);
		}
	}

	private static void print(String kind, Component text) {
		MutableComponent line = Component.literal("[" + kind + "] ").withStyle(ChatFormatting.DARK_GRAY).append(text.copy());
		mc().gui.hud.getChat().addClientSystemMessage(line);
	}
}
