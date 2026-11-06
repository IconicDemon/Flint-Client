package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.Features;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.RgbSetting;
import com.flintclient.client.control.model.mods.HudMods;
import com.flintclient.client.control.model.mods.PlayerMods;
import com.flintclient.client.control.model.mods.UtilityMods;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2fStack;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft 26.3's side of the modules that draw on the HUD layer or change Minecraft's own HUD pieces: Crosshair, Attack
 * Indicator, Hit Color, Damage Tint, waypoint markers (drawn by {@link Mc263Waypoints}), TNT timers (in the world, from the TNT's renderer), Tab Editor, Scoreboard, Boss Bar and Nick Hider.
 * Drawing paths allocate nothing in steady state, apart from a few small objects per waypoint or lit TNT on screen and
 * Minecraft's own render-state objects. Each path is fault-isolated. Render thread only.
 */
public final class Mc263Overlay {
	private static final int CROSSHAIR = Mods.index(PlayerMods.Crosshair.ID);
	private static final int ATTACK = Mods.index(PlayerMods.AttackIndicator.ID);
	private static final int HIT = Mods.index(PlayerMods.HitColor.ID);
	private static final int TINT = Mods.index(PlayerMods.DamageTint.ID);
	private static final int TNT = Mods.index(HudMods.TntCountdown.ID);
	private static final int TAB = Mods.index(UtilityMods.TabEditor.ID);
	private static final int SCORE = Mods.index(UtilityMods.Scoreboard.ID);
	private static final int BOSS = Mods.index(UtilityMods.BossBar.ID);
	private static final int NICK = Mods.index(UtilityMods.NickHider.ID);

	private static final Identifier INDICATOR_FULL = Identifier.withDefaultNamespace("hud/crosshair_attack_indicator_full");
	private static final Identifier INDICATOR_BACK = Identifier.withDefaultNamespace("hud/crosshair_attack_indicator_background");
	private static final Identifier INDICATOR_PROGRESS = Identifier.withDefaultNamespace("hud/crosshair_attack_indicator_progress");
	/** Feather's hit indicator: a crescent with a pointer, 205x59, drawn at a quarter size and tinted. */
	private static final Identifier HIT_CRESCENT = Identifier.fromNamespaceAndPath("flintclient", "textures/gui/hit_indicator.png");
	private static final int CRESCENT_W = 205;
	private static final int CRESCENT_H = 59;
	/** Feather's hit marker: four strokes of an X around the crosshair, 16x16. */
	private static final Identifier HIT_MARKER = Identifier.fromNamespaceAndPath("flintclient", "textures/gui/hit_marker.png");
	/** The ring indicator's frames: 33 of 48x48 stacked, empty to full, filling clockwise from the top. */
	private static final Identifier RING = Identifier.fromNamespaceAndPath("flintclient", "textures/gui/attack_ring.png");
	private static final int RING_FRAME = 48;
	private static final int RING_STEPS = 32;

	private static final HookGuard DRAW = new HookGuard("overlay", Mc263Overlay::off);
	private static final HookGuard HUD_PARTS = new HookGuard("hud parts", Mc263Overlay::off);
	private static final HookGuard NAMES = new HookGuard("nick hider", Mc263Overlay::off);

	/** Light for text that glows (the TNT timer reads the same by night). */
	private static final int FULL_BRIGHT = 15728880;

	/** 0: outlines only, 1: fills only (so one shape's outline never covers another's fill). */
	private static int pass;

	/** Vanilla reached its crosshair this frame (first person, not hidden by the spectator or 3D-crosshair rules). */
	private static boolean crosshairShown;

	private Mc263Overlay() {
	}

	private static void off(String path, Throwable error) {
		FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error);
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	// ---------- crosshair and attack indicator ----------

	/**
	 * Where vanilla draws its crosshair sprite (always vanilla's: the Crosshair module skips Minecraft's whole crosshair
	 * instead). Notes that the crosshair is showing, for the attack indicator and hit markers drawn after it.
	 */
	public static boolean crosshair(GuiGraphicsExtractor graphics) {
		crosshairShown = true;
		decideIndicator();
		return false;
	}

	/** Whether the Attack Indicator module draws the indicator (vanilla's is then left out). */
	public static boolean ownAttackIndicator() {
		return DRAW.on() && Mods.on(ATTACK) && !vanillaIndicator;
	}

	/** This frame, Attack Indicator leaves the indicator to Minecraft: Default display, nothing moved or added (exact vanilla). */
	private static boolean vanillaIndicator;
	/** This frame's choice of what the indicator shows ({@link com.flintclient.client.control.feature.IndicatorChoice}). */
	private static int frameSource = com.flintclient.client.control.feature.IndicatorChoice.NONE;

	/**
	 * Once a frame, before Minecraft's indicator: what Attack Indicator shows, and whether Minecraft's own drawing already is
	 * exactly that (the Default display at its own size and place, your attack only, the full sprite as Minecraft shows it).
	 */
	private static void decideIndicator() {
		vanillaIndicator = false;
		frameSource = com.flintclient.client.control.feature.IndicatorChoice.NONE;
		LocalPlayer player = mc().player;
		if (!DRAW.on() || !Mods.on(ATTACK) || player == null) {
			return;
		}
		try {
			ModuleState state = Mods.state(ATTACK);
			readSources(player, state);
			frameSource = com.flintclient.client.control.feature.IndicatorChoice.choose(SOURCES, true);
			boolean attack = frameSource == com.flintclient.client.control.feature.IndicatorChoice.ATTACK;
			boolean asIs = state.option(PlayerMods.AttackIndicator.SHAPE) == 0 && state.integer(PlayerMods.AttackIndicator.SCALE) == 100
				&& state.integer(PlayerMods.AttackIndicator.X_OFFSET) == 0 && state.integer(PlayerMods.AttackIndicator.Y_OFFSET) == 0;
			// Shown full while charged (Hide when charged off) is Flint's to draw; Minecraft shows it full only on a target.
			vanillaIndicator = asIs && attack && (state.bool(PlayerMods.AttackIndicator.FULL_ONLY_TARGET) || SOURCES[frameSource] < 1);
		} catch (RuntimeException | LinkageError e) {
			DRAW.fail(e);
		}
	}

	/** After vanilla's crosshair method: the third-person crosshair, the attack indicator and hit markers. */
	public static void afterCrosshair(GuiGraphicsExtractor graphics) {
		com.flintclient.client.control.platform.ambient.AmbientSounds.renderDebug(graphics);
		boolean shown = crosshairShown;
		crosshairShown = false;
		if (!DRAW.on()) {
			return;
		}
		try {
			if (!shown) {
				return;
			}
			if (Mods.on(ATTACK)) {
				drawAttackIndicator(graphics, Mods.state(ATTACK));
			}
			if (Mods.on(HIT)) {
				drawHitMarker(graphics, Mods.state(HIT));
			}
		} catch (RuntimeException | LinkageError e) {
			DRAW.fail(e);
		}
	}

	/**
	 * With the Crosshair module on (Minecraft's crosshair method skipped): the attack indicator (Attack Indicator's, or
	 * Minecraft's own as Custom Crosshair Mod redraws it) and the hit markers, from the crosshair's own drawing.
	 */
	public static void indicatorsWithCrosshair(GuiGraphicsExtractor g) {
		if (!DRAW.on()) {
			return;
		}
		try {
			decideIndicator();
			// Minecraft draws nothing of its own here, so Attack Indicator draws even its Default look.
			vanillaIndicator = false;
			if (Mods.on(ATTACK)) {
				drawAttackIndicator(g, Mods.state(ATTACK));
			} else {
				vanillaAttackIndicator(g);
			}
			if (Mods.on(HIT)) {
				drawHitMarker(g, Mods.state(HIT));
			}
		} catch (RuntimeException | LinkageError e) {
			DRAW.fail(e);
		}
	}

	/** Minecraft's crosshair attack indicator, where and when Minecraft draws it (its option set to Crosshair). */
	private static void vanillaAttackIndicator(GuiGraphicsExtractor g) {
		Minecraft mc = mc();
		LocalPlayer player = mc.player;
		if (player == null || mc.options.attackIndicator().get() != net.minecraft.client.AttackIndicatorStatus.CROSSHAIR) {
			return;
		}
		float strength = player.getAttackStrengthScale(0.0F);
		boolean full = false;
		Entity target = mc.crosshairPickEntity;
		if (target instanceof LivingEntity && strength >= 1.0F) {
			full = player.getCurrentItemAttackStrengthDelay() > 5.0F && target.isAlive();
		}
		int y = g.guiHeight() / 2 - 7 + 16;
		int x = g.guiWidth() / 2 - 8;
		if (full) {
			g.blitSprite(RenderPipelines.CROSSHAIR, INDICATOR_FULL, x, y, 16, 16);
		} else if (strength < 1.0F) {
			int progress = (int) (strength * 17.0F);
			g.blitSprite(RenderPipelines.CROSSHAIR, INDICATOR_BACK, x, y, 16, 4);
			g.blitSprite(RenderPipelines.CROSSHAIR, INDICATOR_PROGRESS, 16, 4, 0, 0, x, y, progress, 4);
		}
	}

	private static final float[] SOURCES = new float[com.flintclient.client.control.feature.IndicatorChoice.SOURCES];
	private static float lastStrength = 1;

	/** What each of Attack Indicator's sources shows now (NaN: it doesn't apply or is off). */
	private static void readSources(LocalPlayer player, ModuleState state) {
		java.util.Arrays.fill(SOURCES, Float.NaN);
		Minecraft mc = mc();
		if (state.bool(PlayerMods.AttackIndicator.SLEEP) && player.isSleeping()) {
			SOURCES[com.flintclient.client.control.feature.IndicatorChoice.SLEEP] = Math.min(1, player.getSleepTimer() / 100F);
		}
		if (state.bool(PlayerMods.AttackIndicator.BREAKING) && mc.gameMode != null && mc.gameMode.isDestroying()) {
			SOURCES[com.flintclient.client.control.feature.IndicatorChoice.BREAKING] = Math.min(1,
				((com.flintclient.client.mixin.feature.MultiPlayerGameModeAccessor) mc.gameMode).flint$destroyProgress());
		}
		if (player.isUsingItem()) {
			net.minecraft.world.item.ItemStack using = player.getUseItem();
			int ticks = player.getTicksUsingItem();
			if (using.getItem() instanceof net.minecraft.world.item.BowItem) {
				if (state.bool(PlayerMods.AttackIndicator.BOWS)) {
					SOURCES[com.flintclient.client.control.feature.IndicatorChoice.BOW] = net.minecraft.world.item.BowItem.getPowerForTime(ticks);
				}
			} else if (using.getItem() instanceof net.minecraft.world.item.CrossbowItem) {
				if (state.bool(PlayerMods.AttackIndicator.BOWS)) {
					SOURCES[com.flintclient.client.control.feature.IndicatorChoice.BOW] = Math.min(1,
						ticks / (float) net.minecraft.world.item.CrossbowItem.getChargeDuration(using, player));
				}
			} else if (using.get(net.minecraft.core.component.DataComponents.BLOCKS_ATTACKS) != null) {
				int delay = using.get(net.minecraft.core.component.DataComponents.BLOCKS_ATTACKS).blockDelayTicks();
				if (state.bool(PlayerMods.AttackIndicator.SHIELDS) && delay > 0 && ticks < delay) {
					SOURCES[com.flintclient.client.control.feature.IndicatorChoice.SHIELD] = ticks / (float) delay;
				}
			} else if (using.get(net.minecraft.core.component.DataComponents.CONSUMABLE) != null) {
				int duration = using.getUseDuration(player);
				if (state.bool(PlayerMods.AttackIndicator.CONSUMABLES) && duration > 0) {
					SOURCES[com.flintclient.client.control.feature.IndicatorChoice.CONSUMABLE] = Math.min(1, ticks / (float) duration);
				}
			}
		}
		net.minecraft.world.item.ItemStack held = player.getMainHandItem();
		boolean pearl = held.is(net.minecraft.world.item.Items.ENDER_PEARL) && state.bool(PlayerMods.AttackIndicator.PEARL);
		boolean chorus = held.is(net.minecraft.world.item.Items.CHORUS_FRUIT) && state.bool(PlayerMods.AttackIndicator.CHORUS);
		boolean wind = held.is(net.minecraft.world.item.Items.WIND_CHARGE) && state.bool(PlayerMods.AttackIndicator.WIND_CHARGE);
		if (pearl || chorus || wind) {
			float left = player.getCooldowns().getCooldownPercent(held, 0);
			if (left > 0) {
				SOURCES[com.flintclient.client.control.feature.IndicatorChoice.ITEM_COOLDOWN] = 1 - left;
			}
		}
		if (attackCategoryOn(held, state)) {
			SOURCES[com.flintclient.client.control.feature.IndicatorChoice.ATTACK] = player.getAttackStrengthScale(0.0F);
		}
	}

	private static boolean attackCategoryOn(net.minecraft.world.item.ItemStack held, ModuleState state) {
		if (held.is(net.minecraft.tags.ItemTags.SWORDS)) {
			return state.bool(PlayerMods.AttackIndicator.SWORDS);
		}
		if (held.is(net.minecraft.tags.ItemTags.AXES)) {
			return state.bool(PlayerMods.AttackIndicator.AXES);
		}
		if (held.is(net.minecraft.world.item.Items.TRIDENT)) {
			return state.bool(PlayerMods.AttackIndicator.TRIDENTS);
		}
		if (held.is(net.minecraft.tags.ItemTags.SPEARS)) {
			return state.bool(PlayerMods.AttackIndicator.SPEARS);
		}
		if (held.is(net.minecraft.tags.ItemTags.PICKAXES)) {
			return state.bool(PlayerMods.AttackIndicator.PICKAXES);
		}
		if (held.is(net.minecraft.tags.ItemTags.SHOVELS)) {
			return state.bool(PlayerMods.AttackIndicator.SHOVELS);
		}
		return state.bool(PlayerMods.AttackIndicator.OTHER);
	}

	/** From the client tick: the cooldown sound when your attack is charged again. */
	public static void tickAttackSound() {
		LocalPlayer player = mc().player;
		if (player == null || !Mods.on(ATTACK)) {
			lastStrength = 1;
			return;
		}
		ModuleState state = Mods.state(ATTACK);
		float now = player.getAttackStrengthScale(0.0F);
		if (com.flintclient.client.control.feature.IndicatorChoice.justCharged(lastStrength, now) && attackCategoryOn(player.getMainHandItem(), state)) {
			Mc263Features.playSound(state.text(PlayerMods.AttackIndicator.SOUND), state.integer(PlayerMods.AttackIndicator.SOUND_VOLUME) / 100F);
		}
		lastStrength = now;
	}

	private static int shownSource = com.flintclient.client.control.feature.IndicatorChoice.NONE;

	/** Which Attack Indicator source drew last (for the dev tour). */
	public static int indicatorSource() {
		return shownSource;
	}

	private static void drawAttackIndicator(GuiGraphicsExtractor g, ModuleState state) {
		LocalPlayer player = mc().player;
		int source = frameSource;
		shownSource = vanillaIndicator ? source : com.flintclient.client.control.feature.IndicatorChoice.NONE;
		if (player == null || vanillaIndicator || source == com.flintclient.client.control.feature.IndicatorChoice.NONE) {
			return;
		}
		int shape = state.option(PlayerMods.AttackIndicator.SHAPE);
		float strength = SOURCES[source];
		boolean attack = source == com.flintclient.client.control.feature.IndicatorChoice.ATTACK;
		boolean full = attack && strength >= 1.0F;
		if (full && state.bool(PlayerMods.AttackIndicator.FULL_ONLY_TARGET)) {
			// As Minecraft: full only while aiming at something alive to hit, with a weapon slower than 5 ticks.
			Entity target = mc().crosshairPickEntity;
			if (shape != 0 || !(target instanceof LivingEntity) || !target.isAlive() || player.getCurrentItemAttackStrengthDelay() <= 5.0F) {
				return;
			}
		}
		if (shape == 0 && mc().options.attackIndicator().get() != net.minecraft.client.AttackIndicatorStatus.CROSSHAIR) {
			return;
		}
		shownSource = source;
		float scale = state.integer(PlayerMods.AttackIndicator.SCALE) / 100.0F;
		int rgb = state.bool(PlayerMods.AttackIndicator.DYNAMIC) ? com.flintclient.client.control.feature.IndicatorChoice.dynamicColor(strength)
			: state.rgb(PlayerMods.AttackIndicator.COLOR);
		int color = 0xFF000000 | rgb;
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		try {
			// Whole GUI units, as Minecraft places its indicator (guiWidth / 2 - 8).
			pose.translate(g.guiWidth() / 2 + state.integer(PlayerMods.AttackIndicator.X_OFFSET),
				g.guiHeight() / 2 + state.integer(PlayerMods.AttackIndicator.Y_OFFSET));
			pose.scale(scale, scale);
			switch (shape) {
				case 0 -> {
					if (full) {
						g.blitSprite(RenderPipelines.CROSSHAIR, INDICATOR_FULL, -8, 9, 16, 16);
					} else {
						int progress = (int) (Math.min(1, strength) * 17.0F);
						g.blitSprite(RenderPipelines.CROSSHAIR, INDICATOR_BACK, -8, 9, 16, 4);
						g.blitSprite(RenderPipelines.CROSSHAIR, INDICATOR_PROGRESS, 16, 4, 0, 0, -8, 9, progress, 4);
					}
				}
				case 1 -> {
					int step = Math.round(Math.min(1, strength) * RING_STEPS);
					if (step > 0) {
						// White follows Minecraft's crosshair (inverted against what's behind); a chosen color is drawn as is.
						var pipeline = rgb == 0xFFFFFF ? RenderPipelines.CROSSHAIR : RenderPipelines.GUI_TEXTURED;
						g.blit(pipeline, RING, -3, 9, 0, step * RING_FRAME, 6, 6, RING_FRAME, RING_FRAME, RING_FRAME, RING_FRAME * (RING_STEPS + 1), color);
					}
				}
				default -> {
					g.fill(-9, 10, 9, 12, 0x80000000);
					g.fill(-9, 10, -9 + Math.round(18 * Math.min(1, strength)), 12, color);
				}
			}
		} finally {
			pose.popMatrix();
		}
	}

	/**
	 * How long ago {@code at} was; "never" (Long.MIN_VALUE) is forever ago. A plain {@code now - at} overflows to a negative
	 * age for "never", which kept the hit indicator and kill marker on screen before any hit.
	 */
	private static long age(long now, long at) {
		return at == Long.MIN_VALUE || at > now ? Long.MAX_VALUE : now - at;
	}

	private static void drawHitMarker(GuiGraphicsExtractor g, ModuleState state) {
		if (!state.bool(PlayerMods.HitColor.MARKER)) {
			return;
		}
		long now = Features.nowMs();
		long duration = state.integer(PlayerMods.HitColor.MARKER_DURATION) * 50L;
		long at = Features.COMBAT.hitMarkerAt();
		if (age(now, at) > duration) {
			return;
		}
		boolean kill = age(now, Features.COMBAT.killAt()) <= duration;
		float fade = 1 - (now - at) / (float) duration;
		int rgb = kill ? state.rgb(PlayerMods.HitColor.KILL_COLOR) : state.rgb(PlayerMods.HitColor.MARKER_COLOR);
		int color = Math.round(255 * fade) << 24 | rgb;
		float size = state.integer(PlayerMods.HitColor.MARKER_SIZE) / 100.0F;
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		try {
			pose.translate(g.guiWidth() / 2, g.guiHeight() / 2);
			pose.scale(size, size);
			g.blit(RenderPipelines.GUI_TEXTURED, HIT_MARKER, -8, -8, 0, 0, 16, 16, 16, 16, 16, 16, color);
		} finally {
			pose.popMatrix();
		}
	}

	// ---------- the overlay under Flint's HUD ----------

	/** From the HUD, just before Flint's modules: damage tint, waypoint markers and the damage direction. */
	public static void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		if (!DRAW.on()) {
			return;
		}
		try {
			Minecraft mc = mc();
			if (mc.player == null || mc.level == null) {
				return;
			}
			if (Mods.on(TINT)) {
				drawTint(graphics, Mods.state(TINT), mc.player);
			}
			if (Mc263Features.markersShown()) {
				Mc263Waypoints.draw(graphics, mc, delta.getGameTimeDeltaPartialTick(true));
			}
			if (Mods.on(HIT)) {
				drawDamageDirection(graphics, Mods.state(HIT));
			}
			Mc263Visuals.render(graphics);
		} catch (RuntimeException | LinkageError e) {
			DRAW.fail(e);
		}
	}

	/** From the client tick: the attack-charged sound. */
	public static void tick() {
		if (!DRAW.on()) {
			return;
		}
		try {
			tickAttackSound();
		} catch (RuntimeException | LinkageError e) {
			DRAW.fail(e);
		}
	}

	private static void drawTint(GuiGraphicsExtractor g, ModuleState state, LocalPlayer player) {
		float below = state.integer(PlayerMods.DamageTint.BELOW) * 2.0F;
		float health = player.getHealth();
		float strength = health < below ? 1 - health / below : 0;
		if (state.bool(PlayerMods.DamageTint.ON_HURT)) {
			long since = age(Features.nowMs(), Features.COMBAT.hurtAt());
			if (since < 400) {
				strength = Math.max(strength, 0.6F * (1 - since / 400.0F));
			}
		}
		if (strength <= 0.01F || player.isDeadOrDying() && health <= 0) {
			return;
		}
		float intensity = state.integer(PlayerMods.DamageTint.INTENSITY) / 100.0F;
		int rgb = state.rgb(PlayerMods.DamageTint.COLOR);
		int alpha = Math.min(255, Math.round(255 * strength * Math.min(1, intensity * 1.6F)));
		if (alpha <= 0) {
			return;
		}
		// A soft vignette: clear in the middle, the colour gathering towards the edges and corners.
		g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, vignette(), 0, 0, 0, 0, g.guiWidth(), g.guiHeight(), VIGNETTE, VIGNETTE,
			VIGNETTE, VIGNETTE, alpha << 24 | rgb);
	}

	private static final int VIGNETTE = 128;
	private static net.minecraft.resources.@org.jspecify.annotations.Nullable Identifier vignetteId;

	/** A white radial fade (transparent in the middle, opaque at the corners), made once and smoothly stretched. */
	private static net.minecraft.resources.Identifier vignette() {
		if (vignetteId == null) {
			com.mojang.blaze3d.platform.NativeImage image = new com.mojang.blaze3d.platform.NativeImage(VIGNETTE, VIGNETTE, true);
			for (int y = 0; y < VIGNETTE; y++) {
				for (int x = 0; x < VIGNETTE; x++) {
					float dx = (x + 0.5F) / (VIGNETTE / 2.0F) - 1;
					float dy = (y + 0.5F) / (VIGNETTE / 2.0F) - 1;
					float d = (float) Math.sqrt((dx * dx + dy * dy) / 2);
					float t = Math.clamp((d - 0.35F) / 0.65F, 0, 1);
					int a = Math.round(255 * t * t * (3 - 2 * t));
					image.setPixel(x, y, a << 24 | 0xFFFFFF);
				}
			}
			vignetteId = net.minecraft.resources.Identifier.fromNamespaceAndPath(com.flintclient.client.FlintClient.MOD_ID, "damage_vignette");
			Minecraft.getInstance().getTextureManager().register(vignetteId, new Smooth("Flint damage vignette", image));
		}
		return vignetteId;
	}

	private static void drawDamageDirection(GuiGraphicsExtractor g, ModuleState state) {
		if (!state.bool(PlayerMods.HitColor.HIT_INDICATOR)) {
			return;
		}
		long since = age(Features.nowMs(), Features.COMBAT.hurtAt());
		long duration = state.integer(PlayerMods.HitColor.HIT_DURATION) * 50L;
		float yaw = Features.COMBAT.hurtYaw();
		if (since > duration || Float.isNaN(yaw)) {
			return;
		}
		float fade = 1 - since / (float) duration;
		int color = Math.round(127 * fade) << 24 | state.rgb(PlayerMods.HitColor.HIT_COLOR);
		float size = state.integer(PlayerMods.HitColor.HIT_SIZE) / 100.0F;
		// A quarter of the texture at 100%, shrinking with its age (never below a fifth), as Feather's.
		float k = 0.25F * size * Math.max(fade, 0.2F);
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		try {
			pose.translate(g.guiWidth() / 2.0F, g.guiHeight() / 2.0F);
			// The server's hurt direction is 90° off "straight ahead" (see Minecraft's hurt tilt).
			pose.rotate((float) Math.toRadians(yaw - 90));
			pose.translate(0, -15 * size);
			pose.scale(k, k);
			g.blit(RenderPipelines.GUI_TEXTURED, HIT_CRESCENT, -CRESCENT_W / 2, -CRESCENT_H / 2, 0, 0, CRESCENT_W, CRESCENT_H, CRESCENT_W, CRESCENT_H,
				CRESCENT_W, CRESCENT_H, color);
		} finally {
			pose.popMatrix();
		}
	}

	/**
	 * Above each lit TNT, from its renderer: the fuse left, in seconds, where a nametag would be (half a block over the
	 * block), turned to face you. It is part of the world, so it stays put above the block and gets smaller with distance as
	 * the block does; a faint copy shows through walls, as a nametag's does. Within 48 blocks.
	 */
	public static void tntLabel(float fuseTicks, float height, double distanceSq, PoseStack pose, SubmitNodeCollector collector,
		CameraRenderState camera) {
		if (!DRAW.on() || !Mods.on(TNT) || distanceSq > 48 * 48) {
			return;
		}
		try {
			ModuleState state = Mods.state(TNT);
			float seconds = Math.max(0, fuseTicks / 20.0F);
			String text = String.format(java.util.Locale.ROOT, "%." + state.integer(HudMods.TntCountdown.DECIMALS) + "f", seconds);
			int color;
			if (state.bool(HudMods.TntCountdown.STATIC_COLOR)) {
				color = 0xFF000000 | state.rgb(HudMods.TntCountdown.TIME_COLOR);
			} else {
				float k = Math.clamp(seconds / 4.0F, 0, 1);
				int r = Math.round(255 * Math.min(1, 2 * (1 - k)));
				int gr = Math.round(255 * Math.min(1, 2 * k));
				color = 0xFF000000 | r << 16 | gr << 8 | 0x40;
			}
			Font font = mc().font;
			float x = -font.width(text) / 2.0F;
			pose.pushPose();
			try {
				pose.translate(0.0F, height + 0.5F, 0.0F);
				com.flintclient.client.compat.McCompat.rotate(pose, camera.orientation);
				pose.scale(0.025F, -0.025F, 0.025F);
				collector.submitText(pose, x, -4, Component.literal(text).getVisualOrderText(), false, Font.DisplayMode.SEE_THROUGH, FULL_BRIGHT,
					color & 0x00FFFFFF | 0x40000000, 0, 0);
				collector.submitText(pose, x, -4, Component.literal(text).getVisualOrderText(), state.bool(HudMods.TntCountdown.SHADOW),
					Font.DisplayMode.NORMAL, FULL_BRIGHT, color, 0, 0);
			} finally {
				pose.popPose();
			}
		} catch (RuntimeException | LinkageError e) {
			DRAW.fail(e);
		}
	}

	// ---------- tab list ----------

	/** The tab list's panels in the module's colour and opacity (Minecraft's: half-transparent black). */
	public static int tabBackground(int vanilla) {
		if (!Mods.on(TAB)) {
			return vanilla;
		}
		ModuleState state = Mods.state(TAB);
		return Math.round(255 * state.integer(UtilityMods.TabEditor.BACKGROUND_OPACITY) / 100F) << 24 | state.rgb(UtilityMods.TabEditor.BACKGROUND);
	}

	/** Each player's row in the module's colour and opacity. */
	public static int tabRow(int vanilla) {
		if (!Mods.on(TAB)) {
			return vanilla;
		}
		ModuleState state = Mods.state(TAB);
		return Math.round(255 * state.integer(UtilityMods.TabEditor.ROW_OPACITY) / 100F) << 24 | state.rgb(UtilityMods.TabEditor.ROW);
	}

	/** How many players the list holds. */
	public static long tabLimit(long vanilla) {
		return Mods.on(TAB) && Mods.state(TAB).bool(UtilityMods.TabEditor.EXTEND) ? 160 : vanilla;
	}

	/** The list in Minecraft's order, with you moved to the top when asked. */
	public static java.util.List<PlayerInfo> tabOrder(java.util.List<PlayerInfo> vanilla) {
		Minecraft mc = mc();
		if (!Mods.on(TAB) || !Mods.state(TAB).bool(UtilityMods.TabEditor.HOIST) || mc.player == null) {
			return vanilla;
		}
		java.util.UUID me = mc.player.getUUID();
		for (int i = 1; i < vanilla.size(); i++) {
			if (vanilla.get(i).getProfile().id().equals(me)) {
				java.util.List<PlayerInfo> out = new java.util.ArrayList<>(vanilla);
				out.add(0, out.remove(i));
				return out;
			}
		}
		return vanilla;
	}

	private static boolean tabLatched;
	private static boolean tabKeyWas;

	/** Whether the tab list shows: the key held (Minecraft), or, with Toggle tablist, latched by a tap. */
	public static boolean tabShown(boolean held) {
		if (!Mods.on(TAB) || !Mods.state(TAB).bool(UtilityMods.TabEditor.TOGGLE)) {
			tabLatched = false;
			tabKeyWas = held;
			return held;
		}
		if (held && !tabKeyWas) {
			tabLatched = !tabLatched;
		}
		tabKeyWas = held;
		return tabLatched;
	}

	public static boolean tabHideHeader() {
		return Mods.on(TAB) && Mods.state(TAB).bool(UtilityMods.TabEditor.HIDE_HEADER);
	}

	public static boolean tabHideFooter() {
		return Mods.on(TAB) && Mods.state(TAB).bool(UtilityMods.TabEditor.HIDE_FOOTER);
	}

	public static boolean tabHideHeads() {
		return Mods.on(TAB) && Mods.state(TAB).bool(UtilityMods.TabEditor.HIDE_HEADS);
	}

	// Ping View's ping numbers (MIT, karlsson1000): see META-INF/licenses/MIT-PingView.txt.

	/** Whether the player list shows Ping View's numbers in place of the signal bars. */
	private static boolean pingNumbers() {
		return HUD_PARTS.on() && Mods.on(TAB) && Mods.state(TAB).bool(UtilityMods.TabEditor.PING_NUMBER);
	}

	/** The ping column's width: Minecraft's 13 for the bars, 23 more for Ping View's numbers. */
	public static int tabPingWidth(int vanilla) {
		return pingNumbers() ? vanilla + 23 : vanilla;
	}

	/** Ping as Ping View draws it where vanilla draws its bars; true when it drew (vanilla's bars are then skipped). */
	public static boolean tabPing(GuiGraphicsExtractor g, int slotWidth, int xo, int yo, PlayerInfo info) {
		if (!pingNumbers()) {
			return false;
		}
		try {
			ModuleState state = Mods.state(TAB);
			int latency = info.getLatency();
			String text = pingText(state, latency);
			Font font = mc().font;
			g.text(font, text, xo + slotWidth - font.width(text), yo, pingColor(state, latency), state.bool(UtilityMods.TabEditor.PING_SHADOW));
			return true;
		} catch (RuntimeException | LinkageError e) {
			HUD_PARTS.fail(e);
			return false;
		}
	}

	/** "N/A" for an unknown latency, else the milliseconds, with "ms" after when asked. */
	static String pingText(ModuleState state, int latency) {
		return latency < 0 ? "N/A" : state.bool(UtilityMods.TabEditor.PING_MS) ? latency + "ms" : Integer.toString(latency);
	}

	/** Ping View's colours: good under 50 ms, ok under 100, bad under 200, terrible from there, unknown when not known. */
	static int pingColor(ModuleState state, int latency) {
		RgbSetting color = latency < 0 ? UtilityMods.TabEditor.PING_UNKNOWN : latency < 50 ? UtilityMods.TabEditor.PING_GOOD
			: latency < 100 ? UtilityMods.TabEditor.PING_OK : latency < 200 ? UtilityMods.TabEditor.PING_BAD : UtilityMods.TabEditor.PING_TERRIBLE;
		return 0xFF000000 | state.rgb(color);
	}

	/** A player's nametag with Ping View's " (45ms)" after it, when Show ping on nametags is on. */
	public static Component pingTag(Entity entity, Component name) {
		if (!HUD_PARTS.on() || !Mods.on(TAB) || !(entity instanceof net.minecraft.client.player.AbstractClientPlayer player)) {
			return name;
		}
		ModuleState state = Mods.state(TAB);
		if (!state.bool(UtilityMods.TabEditor.PING_NAMETAGS)) {
			return name;
		}
		try {
			net.minecraft.client.multiplayer.ClientPacketListener connection = mc().getConnection();
			PlayerInfo info = connection == null ? null : connection.getPlayerInfo(player.getUUID());
			if (info == null) {
				return name;
			}
			int latency = info.getLatency();
			int color = pingColor(state, latency) & 0xFFFFFF;
			return Component.empty().append(name).append(Component.literal(" (").withStyle(style -> style.withColor(0xAAAAAA)))
				.append(Component.literal(pingText(state, latency)).withStyle(style -> style.withColor(color)))
				.append(Component.literal(")").withStyle(style -> style.withColor(0xAAAAAA)));
		} catch (RuntimeException | LinkageError e) {
			HUD_PARTS.fail(e);
			return name;
		}
	}

	// ---------- scoreboard and boss bars ----------

	public static boolean hideScoreboard() {
		return Mods.on(SCORE) && Mods.state(SCORE).bool(UtilityMods.Scoreboard.HIDE);
	}

	public static boolean scoreboardNumbers() {
		return !Mods.on(SCORE) || Mods.state(SCORE).bool(UtilityMods.Scoreboard.NUMBERS);
	}

	/** The sidebar's background opacity: the module's (the header 10 points darker, as vanilla's is). */
	public static float scoreboardOpacity(float vanilla) {
		if (!Mods.on(SCORE)) {
			return vanilla;
		}
		float base = Mods.state(SCORE).integer(UtilityMods.Scoreboard.OPACITY) / 100.0F;
		return vanilla > 0.35F ? Math.min(1, base + 0.1F) : base;
	}

	public static boolean scoreboardShadow(boolean vanilla) {
		return Mods.on(SCORE) ? Mods.state(SCORE).bool(UtilityMods.Scoreboard.SHADOW) : vanilla;
	}

	private static final float[] PLACED = new float[4];

	/**
	 * Before the sidebar is drawn: Minecraft's own spot for it mapped onto where Flint's HUD placed the module (its
	 * position and scale), from the last HUD frame; Minecraft's spot when the module isn't placed (off, the HUD hidden).
	 */
	public static void scoreboardBegin(GuiGraphicsExtractor g) {
		g.pose().pushMatrix();
		int[] size = Mods.on(SCORE) ? Mc263HudData.sidebarCached() : null;
		if (size == null || !com.flintclient.client.control.hud.render.HudRenderer.lastRect(UtilityMods.Scoreboard.ID, PLACED)) {
			return;
		}
		moveOnto(g, com.flintclient.client.control.hud.VanillaAreas.sidebar(g.guiWidth(), g.guiHeight(), size[0], size[1]));
	}

	/** Maps {@code vanilla} (Minecraft's own rectangle, GUI units) onto {@link #PLACED}, scaled to its width. */
	private static void moveOnto(GuiGraphicsExtractor g, com.flintclient.client.control.hud.HudRect vanilla) {
		if (vanilla.width() <= 0) {
			return;
		}
		float k = PLACED[2] / vanilla.width();
		g.pose().translate(PLACED[0], PLACED[1]);
		g.pose().scale(k, k);
		g.pose().translate(-vanilla.x(), -vanilla.y());
	}

	public static void scoreboardEnd(GuiGraphicsExtractor g) {
		g.pose().popMatrix();
	}

	/** The sidebar's two fills: the title's (first) and the lines' (second), in the module's colours; the border after the lines'. */
	public static void scoreboardFill(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color, boolean title) {
		if (!Mods.on(SCORE)) {
			g.fill(x0, y0, x1, y1, color);
			return;
		}
		ModuleState state = Mods.state(SCORE);
		int rgb = state.rgb(title ? UtilityMods.Scoreboard.TITLE_BACKGROUND : UtilityMods.Scoreboard.BACKGROUND_COLOR);
		// A Transparent title background draws nothing.
		if (!com.flintclient.client.control.model.RgbSetting.isClear(rgb)) {
			g.fill(x0, y0, x1, y1, (color & 0xFF000000) | rgb);
		}
		if (title) {
			scoreboardTop = y0;
		} else if (state.bool(UtilityMods.Scoreboard.BORDER)) {
			g.outline(x0 - 1, scoreboardTop - 1, x1 - x0 + 2, y1 - scoreboardTop + 2, 0xFF000000 | state.rgb(UtilityMods.Scoreboard.BORDER_COLOR));
		}
	}

	private static int scoreboardTop;

	/** The sidebar's default text colour (Minecraft draws white; the server's own colours still win). */
	public static int scoreboardText(int vanilla) {
		return Mods.on(SCORE) ? 0xFF000000 | Mods.state(SCORE).rgb(UtilityMods.Scoreboard.TEXT_COLOR) : vanilla;
	}

	/** Before the boss bars are drawn: Minecraft's spot for them mapped onto where Flint's HUD placed the module. */
	public static void bossBegin(GuiGraphicsExtractor g) {
		g.pose().pushMatrix();
		if (!Mods.on(BOSS) || !com.flintclient.client.control.hud.render.HudRenderer.lastRect(UtilityMods.BossBar.ID, PLACED)) {
			// Minecraft's own spot: Armor's Push bossbars moves them below its strip.
			g.pose().translate(0.0F, Mc263ArmorHud.bossBarPush(g));
			return;
		}
		moveOnto(g, com.flintclient.client.control.hud.VanillaAreas.bossBars(g.guiWidth(), Math.max(1, Mc263HudData.bossBars())));
	}

	public static void bossEnd(GuiGraphicsExtractor g) {
		g.pose().popMatrix();
	}

	public static boolean hideBossBars() {
		return Mods.on(BOSS) && Mods.state(BOSS).bool(UtilityMods.BossBar.HIDE);
	}

	/** Whether the boss bar names are drawn differently from vanilla (shadow off, or a colour of their own). */
	public static boolean bossTextChanged() {
		return Mods.on(BOSS) && (!Mods.state(BOSS).bool(UtilityMods.BossBar.SHADOW) || Mods.state(BOSS).bool(UtilityMods.BossBar.OVERWRITE));
	}

	public static int bossTextColor(int vanilla) {
		return Mods.state(BOSS).bool(UtilityMods.BossBar.OVERWRITE) ? 0xFF000000 | Mods.state(BOSS).rgb(UtilityMods.BossBar.COLOR) : vanilla;
	}

	public static boolean bossTextShadow() {
		return Mods.state(BOSS).bool(UtilityMods.BossBar.SHADOW);
	}

	// ---------- nick hider ----------

	private static @Nullable UUID me() {
		LocalPlayer player = mc().player;
		return player == null ? null : player.getUUID();
	}

	/**
	 * A nametag's text: your fake nick for you, "Player" for others when hidden; else unchanged. Only the name changes: a
	 * server's team prefix and suffix (a rank, a colour) stay ({@link #masked}).
	 */
	public static Component nameTag(Entity entity, Component name) {
		if (!NAMES.on() || !Mods.on(NICK) || !(entity instanceof Player player)) {
			return name;
		}
		try {
			ModuleState state = Mods.state(NICK);
			if (entity == mc().player) {
				return state.bool(UtilityMods.NickHider.HIDE_NICK) ? masked(name, player.getGameProfile().name(), state.text(UtilityMods.NickHider.FAKE_NICK)) : name;
			}
			return state.bool(UtilityMods.NickHider.HIDE_OTHERS) ? masked(name, player.getGameProfile().name(), "Player") : name;
		} catch (RuntimeException | LinkageError e) {
			NAMES.fail(e);
			return name;
		}
	}

	/** A player list name, as {@link #nameTag}. */
	public static Component tabName(PlayerInfo info, Component name) {
		if (!NAMES.on() || !Mods.on(NICK)) {
			return name;
		}
		try {
			ModuleState state = Mods.state(NICK);
			if (info.getProfile().id().equals(me())) {
				return state.bool(UtilityMods.NickHider.HIDE_NICK) ? masked(name, info.getProfile().name(), state.text(UtilityMods.NickHider.FAKE_NICK)) : name;
			}
			return state.bool(UtilityMods.NickHider.HIDE_OTHERS) ? masked(name, info.getProfile().name(), "Player") : name;
		} catch (RuntimeException | LinkageError e) {
			NAMES.fail(e);
			return name;
		}
	}

	/**
	 * {@code name} with the player's real name ({@code real}, a whole word in any case) shown as {@code shown}, piece by piece,
	 * so a server's team prefix and suffix and their colours stay. When the real name would still show (split across colours,
	 * or inside another word) the whole text is {@code shown}, as before: a hidden name never shows.
	 */
	private static Component masked(Component name, String real, String shown) {
		java.util.Map<String, String> names = java.util.Map.of(real, shown);
		net.minecraft.network.chat.MutableComponent out = Component.empty();
		name.visit((style, text) -> {
			out.append(Component.literal(com.flintclient.client.control.feature.NameMask.apply(text, names)).withStyle(style));
			return java.util.Optional.empty();
		}, net.minecraft.network.chat.Style.EMPTY);
		String lower = real.toLowerCase(java.util.Locale.ROOT);
		boolean stillShown = !real.isEmpty() && out.getString().toLowerCase(java.util.Locale.ROOT).contains(lower)
			&& !shown.toLowerCase(java.util.Locale.ROOT).contains(lower);
		return stillShown ? Component.literal(shown) : out;
	}

	/**
	 * A chat message with your name (and others', when hidden) replaced, piece by piece so colours, links and hover text stay.
	 * Only what you see changes: the message, its signature and reporting are untouched.
	 */
	public static Component chatLine(Component line) {
		if (!NAMES.on() || !Mods.on(NICK)) {
			return line;
		}
		try {
			ModuleState state = Mods.state(NICK);
			java.util.Map<String, String> names = new java.util.HashMap<>();
			Minecraft mc = mc();
			LocalPlayer player = mc.player;
			if (player != null && state.bool(UtilityMods.NickHider.HIDE_NICK)) {
				names.put(player.getGameProfile().name(), state.text(UtilityMods.NickHider.FAKE_NICK));
			}
			if (state.bool(UtilityMods.NickHider.HIDE_OTHERS) && mc.getConnection() != null) {
				for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
					if (!info.getProfile().id().equals(me())) {
						names.putIfAbsent(info.getProfile().name(), "Player");
					}
				}
			}
			if (names.isEmpty()) {
				return line;
			}
			net.minecraft.network.chat.MutableComponent out = Component.empty();
			boolean[] changed = {false};
			line.visit((style, text) -> {
				String masked = com.flintclient.client.control.feature.NameMask.apply(text, names);
				changed[0] |= masked != text;
				out.append(Component.literal(masked).withStyle(style));
				return java.util.Optional.empty();
			}, net.minecraft.network.chat.Style.EMPTY);
			return changed[0] ? out : line;
		} catch (RuntimeException | LinkageError e) {
			NAMES.fail(e);
			return line;
		}
	}

	/** A player's skin on your screen: a default one when hidden. */
	public static PlayerSkin skin(PlayerInfo info, PlayerSkin skin) {
		if (!NAMES.on() || !Mods.on(NICK)) {
			return skin;
		}
		try {
			ModuleState state = Mods.state(NICK);
			UUID id = info.getProfile().id();
			boolean self = id.equals(me());
			if (self && state.bool(UtilityMods.NickHider.HIDE_SKIN) || !self && state.bool(UtilityMods.NickHider.HIDE_OTHER_SKINS)) {
				return DefaultPlayerSkin.get(id);
			}
			return skin;
		} catch (RuntimeException | LinkageError e) {
			NAMES.fail(e);
			return skin;
		}
	}

	/** A texture sampled with linear filtering and clamped at its edges (the vignette is stretched over the screen). */
	private static final class Smooth extends net.minecraft.client.renderer.texture.DynamicTexture {
		Smooth(String label, com.mojang.blaze3d.platform.NativeImage image) {
			super(() -> label, image);
			this.sampler = com.flintclient.client.compat.McCompat.linearClampSampler();
		}
	}
}
