package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.Features;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.feature.Waypoint;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.HudMods;
import com.flintclient.client.control.model.mods.PlayerMods;
import com.flintclient.client.control.model.mods.UtilityMods;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2fStack;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft 26.3's side of the modules that draw on the HUD layer or change Minecraft's own HUD pieces: Crosshair, Attack
 * Indicator, Hit Color, Damage Tint, waypoint markers, TNT timers, Tab Editor, Scoreboard, Boss Bar and Nick Hider.
 * Drawing paths allocate nothing in steady state, apart from a few small objects per waypoint or lit TNT on screen and
 * Minecraft's own render-state objects. Each path is fault-isolated. Render thread only.
 */
public final class Mc263Overlay {
	private static final int CROSSHAIR = Mods.index(PlayerMods.Crosshair.ID);
	private static final int ATTACK = Mods.index(PlayerMods.AttackIndicator.ID);
	private static final int HIT = Mods.index(PlayerMods.HitColor.ID);
	private static final int TINT = Mods.index(PlayerMods.DamageTint.ID);
	private static final int WAYPOINTS = Mods.index(UtilityMods.Waypoints.ID);
	private static final int TNT = Mods.index(HudMods.TntCountdown.ID);
	private static final int TAB = Mods.index(UtilityMods.TabEditor.ID);
	private static final int SCORE = Mods.index(UtilityMods.Scoreboard.ID);
	private static final int BOSS = Mods.index(UtilityMods.BossBar.ID);
	private static final int NICK = Mods.index(UtilityMods.NickHider.ID);

	private static final Identifier INDICATOR_FULL = Identifier.withDefaultNamespace("hud/crosshair_attack_indicator_full");
	private static final Identifier INDICATOR_BACK = Identifier.withDefaultNamespace("hud/crosshair_attack_indicator_background");
	private static final Identifier INDICATOR_PROGRESS = Identifier.withDefaultNamespace("hud/crosshair_attack_indicator_progress");

	private static final HookGuard DRAW = new HookGuard("overlay", Mc263Overlay::off);
	private static final HookGuard HUD_PARTS = new HookGuard("hud parts", Mc263Overlay::off);
	private static final HookGuard NAMES = new HookGuard("nick hider", Mc263Overlay::off);

	private static final int MAX_TNT = 24;
	private static final PrimedTnt[] TNTS = new PrimedTnt[MAX_TNT];
	private static int tntCount;
	private static final char[] DIGITS = new char[16];

	/** 0: outlines only, 1: fills only (so one shape's outline never covers another's fill). */
	private static int pass;

	/** Vanilla reached its crosshair this frame (first person, not hidden by the spectator or 3D-crosshair rules). */
	private static boolean crosshairShown;

	private Mc263Overlay() {
	}

	private static void off(String path, RuntimeException error) {
		FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error);
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	// ---------- crosshair and attack indicator ----------

	/**
	 * Where vanilla draws its crosshair sprite: true when the Crosshair module drew its own instead. Also notes that the
	 * crosshair is showing, for the attack indicator and hit markers drawn after it.
	 */
	public static boolean crosshair(GuiGraphicsExtractor graphics) {
		crosshairShown = true;
		if (!DRAW.on() || !Mods.on(CROSSHAIR)) {
			return false;
		}
		try {
			ModuleState state = Mods.state(CROSSHAIR);
			if (state.option(PlayerMods.Crosshair.TYPE) == 0) {
				return false;
			}
			drawCrosshair(graphics, state);
			return true;
		} catch (RuntimeException e) {
			DRAW.fail(e);
			return false;
		}
	}

	/** Whether the Attack Indicator module draws the indicator (vanilla's is then left out). */
	public static boolean ownAttackIndicator() {
		return DRAW.on() && Mods.on(ATTACK);
	}

	/** After vanilla's crosshair method: the third-person crosshair, the attack indicator and hit markers. */
	public static void afterCrosshair(GuiGraphicsExtractor graphics) {
		boolean shown = crosshairShown;
		crosshairShown = false;
		if (!DRAW.on()) {
			return;
		}
		try {
			Minecraft mc = mc();
			if (!shown && Mods.on(CROSSHAIR) && Mods.state(CROSSHAIR).bool(PlayerMods.Crosshair.THIRD_PERSON)
				&& !mc.options.getCameraType().isFirstPerson() && mc.player != null && !mc.player.isSpectator()) {
				ModuleState state = Mods.state(CROSSHAIR);
				if (state.option(PlayerMods.Crosshair.TYPE) != 0) {
					drawCrosshair(graphics, state);
				}
				shown = true;
			}
			if (!shown) {
				return;
			}
			if (Mods.on(ATTACK)) {
				drawAttackIndicator(graphics, Mods.state(ATTACK));
			}
			if (Mods.on(HIT)) {
				drawHitMarker(graphics, Mods.state(HIT));
			}
		} catch (RuntimeException e) {
			DRAW.fail(e);
		}
	}

	private static void drawCrosshair(GuiGraphicsExtractor g, ModuleState state) {
		Minecraft mc = mc();
		if (state.bool(PlayerMods.Crosshair.HIDE_SPECTATOR) && mc.player != null && mc.player.isSpectator()) {
			return;
		}
		int type = state.option(PlayerMods.Crosshair.TYPE);
		int size = state.integer(PlayerMods.Crosshair.SIZE);
		int gap = state.integer(PlayerMods.Crosshair.GAP);
		int thick = state.integer(PlayerMods.Crosshair.THICKNESS);
		boolean invert = state.bool(PlayerMods.Crosshair.INVERT);
		int alpha = Math.round(255 * state.integer(PlayerMods.Crosshair.OPACITY) / 100.0F) << 24;
		int rgb = state.rgb(PlayerMods.Crosshair.COLOR);
		Entity target = mc.crosshairPickEntity;
		if (state.bool(PlayerMods.Crosshair.TARGET_COLOR) && target instanceof LivingEntity && target.isAlive()) {
			rgb = state.rgb(PlayerMods.Crosshair.TARGET_RGB);
		}
		int color = alpha | rgb;
		int outline = state.bool(PlayerMods.Crosshair.OUTLINE) ? alpha | state.rgb(PlayerMods.Crosshair.OUTLINE_COLOR) : 0;
		for (pass = outline != 0 && !invert ? 0 : 1; pass < 2; pass++) {
			drawShape(g, state, mc, type, size, gap, thick, invert, alpha, color, outline);
		}
	}

	private static void drawShape(GuiGraphicsExtractor g, ModuleState state, Minecraft mc, int type, int size, int gap, int thick,
		boolean invert, int alpha, int color, int outline) {
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		try {
			pose.translate(g.guiWidth() / 2.0F, g.guiHeight() / 2.0F);
			if (state.bool(PlayerMods.Crosshair.IGNORE_SCALE)) {
				float k = 1.0F / Math.max(1, mc.getWindow().getGuiScale());
				pose.scale(k, k);
			}
			int half = thick / 2;
			int reach = gap + size;
			switch (type) {
				case 1, 2 -> {
					bar(g, -reach, -half, -gap, thick - half, color, outline, invert);
					bar(g, gap, -half, reach, thick - half, color, outline, invert);
					bar(g, -half, -reach, thick - half, -gap, color, outline, invert);
					bar(g, -half, gap, thick - half, reach, color, outline, invert);
					if (type == 2) {
						int c = reach + 2;
						int arm = Math.max(2, size / 2);
						for (int sx = -1; sx <= 1; sx += 2) {
							for (int sy = -1; sy <= 1; sy += 2) {
								int x = sx * c;
								int y = sy * c;
								bar(g, Math.min(x, x - sx * arm), y - half, Math.max(x, x - sx * arm) + 1, y + thick - half, color, outline, invert);
								bar(g, x - half, Math.min(y, y - sy * arm), x + thick - half, Math.max(y, y - sy * arm) + 1, color, outline, invert);
							}
						}
					}
				}
				case 3 -> {
					int segments = 24;
					float radius = gap + size / 2.0F;
					int len = Math.max(1, Math.round((float) (2 * Math.PI * radius / segments)) + 1);
					for (int i = 0; i < segments; i++) {
						pose.pushMatrix();
						pose.rotate((float) (2 * Math.PI * i / segments));
						pose.translate(0, -radius);
						bar(g, -len / 2, -half, len - len / 2, thick - half, color, outline, invert);
						pose.popMatrix();
					}
				}
				case 4 -> {
					bar(g, -reach, -reach, reach + 1, -reach + thick, color, outline, invert);
					bar(g, -reach, reach + 1 - thick, reach + 1, reach + 1, color, outline, invert);
					bar(g, -reach, -reach + thick, -reach + thick, reach + 1 - thick, color, outline, invert);
					bar(g, reach + 1 - thick, -reach + thick, reach + 1, reach + 1 - thick, color, outline, invert);
				}
				case 5 -> bar(g, -half - 1, -half - 1, thick - half + 1, thick - half + 1, color, outline, invert);
				case 6 -> {
					for (int side = -1; side <= 1; side += 2) {
						pose.pushMatrix();
						pose.rotate((float) Math.toRadians(side * 45));
						bar(g, -half, gap, thick - half, reach, color, outline, invert);
						pose.popMatrix();
					}
				}
				default -> {
				}
			}
			if (state.bool(PlayerMods.Crosshair.DOT) && type != 5) {
				int dot = alpha | state.rgb(PlayerMods.Crosshair.DOT_COLOR);
				bar(g, -half, -half, thick - half, thick - half, dot, outline, invert);
			}
		} finally {
			pose.popMatrix();
		}
	}

	/** One crosshair rectangle (and its one-unit outline first). Inverted ones blend like vanilla's crosshair. */
	private static void bar(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color, int outline, boolean invert) {
		if (invert) {
			if (pass == 1) {
				g.fill(RenderPipelines.CROSSHAIR, x0, y0, x1, y1, 0xFFFFFFFF);
			}
			return;
		}
		if (pass == 0) {
			if (outline != 0) {
				g.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, outline);
			}
			return;
		}
		g.fill(x0, y0, x1, y1, color);
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
		if (player == null) {
			return;
		}
		readSources(player, state);
		int source = com.flintclient.client.control.feature.IndicatorChoice.choose(SOURCES, !state.bool(PlayerMods.AttackIndicator.FULL_ONLY_TARGET));
		shownSource = source;
		if (source == com.flintclient.client.control.feature.IndicatorChoice.NONE) {
			return;
		}
		float strength = SOURCES[source];
		float scale = state.integer(PlayerMods.AttackIndicator.SCALE) / 100.0F;
		int rgb = state.bool(PlayerMods.AttackIndicator.DYNAMIC) ? com.flintclient.client.control.feature.IndicatorChoice.dynamicColor(strength)
			: state.rgb(PlayerMods.AttackIndicator.COLOR);
		int color = 0xFF000000 | rgb;
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		try {
			pose.translate(g.guiWidth() / 2.0F + state.integer(PlayerMods.AttackIndicator.X_OFFSET),
				g.guiHeight() / 2.0F + state.integer(PlayerMods.AttackIndicator.Y_OFFSET));
			pose.scale(scale, scale);
			switch (state.option(PlayerMods.AttackIndicator.SHAPE)) {
				case 0 -> {
					if (strength >= 1.0F && source == com.flintclient.client.control.feature.IndicatorChoice.ATTACK) {
						g.blitSprite(RenderPipelines.CROSSHAIR, INDICATOR_FULL, -8, 9, 16, 16);
					} else {
						int progress = (int) (Math.min(1, strength) * 17.0F);
						g.blitSprite(RenderPipelines.CROSSHAIR, INDICATOR_BACK, -8, 9, 16, 4);
						g.blitSprite(RenderPipelines.CROSSHAIR, INDICATOR_PROGRESS, 16, 4, 0, 0, -8, 9, progress, 4);
					}
				}
				case 1 -> {
					int segments = 32;
					int lit = Math.round(Math.min(1, strength) * segments);
					for (int i = 0; i < segments; i++) {
						pose.pushMatrix();
						pose.rotate((float) (2 * Math.PI * i / segments));
						pose.translate(0, -10);
						g.fill(0, 0, 2, 1, i < lit ? color : 0x40000000);
						pose.popMatrix();
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

	private static void drawHitMarker(GuiGraphicsExtractor g, ModuleState state) {
		if (!state.bool(PlayerMods.HitColor.MARKER)) {
			return;
		}
		long now = Features.nowMs();
		long duration = state.integer(PlayerMods.HitColor.MARKER_DURATION) * 50L;
		long at = Features.COMBAT.hitMarkerAt();
		if (now - at > duration) {
			return;
		}
		boolean kill = now - Features.COMBAT.killAt() <= duration;
		float fade = 1 - (now - at) / (float) duration;
		int rgb = kill ? state.rgb(PlayerMods.HitColor.KILL_COLOR) : state.rgb(PlayerMods.HitColor.MARKER_COLOR);
		int color = Math.round(255 * fade) << 24 | rgb;
		float size = state.integer(PlayerMods.HitColor.MARKER_SIZE) / 100.0F;
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		try {
			pose.translate(g.guiWidth() / 2.0F, g.guiHeight() / 2.0F);
			pose.scale(size, size);
			for (int i = 0; i < 4; i++) {
				pose.pushMatrix();
				pose.rotate((float) Math.toRadians(45 + 90 * i));
				g.fill(-1, 4, 1, 9, color);
				pose.popMatrix();
			}
		} finally {
			pose.popMatrix();
		}
	}

	// ---------- the overlay under Flint's HUD ----------

	/** From the HUD, just before Flint's modules: damage tint, waypoint markers, TNT timers and the damage direction. */
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
				drawWaypoints(graphics, mc);
			}
			if (Mods.on(TNT) && tntCount > 0) {
				drawTnt(graphics, mc, delta.getGameTimeDeltaPartialTick(false));
			}
			if (Mods.on(HIT)) {
				drawDamageDirection(graphics, Mods.state(HIT));
			}
			Mc263Visuals.render(graphics);
		} catch (RuntimeException e) {
			DRAW.fail(e);
		}
	}

	/** From the client tick: the lit TNT near you (at most {@value #MAX_TNT}, within 48 blocks). */
	public static void tick() {
		if (!DRAW.on()) {
			return;
		}
		try {
			tickAttackSound();
			java.util.Arrays.fill(TNTS, null);
			tntCount = 0;
			Minecraft mc = mc();
			ClientLevel level = mc.level;
			if (level == null || mc.player == null || !Mods.on(TNT)) {
				return;
			}
			for (Entity entity : level.entitiesForRendering()) {
				if (entity instanceof PrimedTnt tnt && tnt.distanceToSqr(mc.player) < 48 * 48 && tntCount < MAX_TNT) {
					TNTS[tntCount++] = tnt;
				}
			}
		} catch (RuntimeException e) {
			DRAW.fail(e);
		}
	}

	private static void drawTint(GuiGraphicsExtractor g, ModuleState state, LocalPlayer player) {
		float below = state.integer(PlayerMods.DamageTint.BELOW) * 2.0F;
		float health = player.getHealth();
		float strength = health < below ? 1 - health / below : 0;
		if (state.bool(PlayerMods.DamageTint.ON_HURT)) {
			long since = Features.nowMs() - Features.COMBAT.hurtAt();
			if (since < 400) {
				strength = Math.max(strength, 0.6F * (1 - since / 400.0F));
			}
		}
		if (strength <= 0.01F || player.isDeadOrDying() && health <= 0) {
			return;
		}
		float intensity = state.integer(PlayerMods.DamageTint.INTENSITY) / 100.0F;
		int rgb = state.rgb(PlayerMods.DamageTint.COLOR);
		int w = g.guiWidth();
		int h = g.guiHeight();
		int depth = Math.max(8, Math.min(w, h) / 6);
		int steps = 8;
		for (int i = 0; i < steps; i++) {
			float k = 1 - i / (float) steps;
			int a = Math.round(255 * strength * intensity * k * k * 0.5F);
			if (a <= 0) {
				continue;
			}
			int c = a << 24 | rgb;
			int inset = i * depth / steps;
			int next = (i + 1) * depth / steps;
			g.fill(inset, inset, w - inset, next, c);
			g.fill(inset, h - next, w - inset, h - inset, c);
			g.fill(inset, next, next, h - next, c);
			g.fill(w - next, next, w - inset, h - next, c);
		}
	}

	private static void drawDamageDirection(GuiGraphicsExtractor g, ModuleState state) {
		if (!state.bool(PlayerMods.HitColor.HIT_INDICATOR)) {
			return;
		}
		long since = Features.nowMs() - Features.COMBAT.hurtAt();
		long duration = state.integer(PlayerMods.HitColor.HIT_DURATION) * 50L;
		float yaw = Features.COMBAT.hurtYaw();
		if (since > duration || Float.isNaN(yaw)) {
			return;
		}
		float fade = 1 - since / (float) duration;
		int color = Math.round(220 * fade) << 24 | state.rgb(PlayerMods.HitColor.HIT_COLOR);
		float size = state.integer(PlayerMods.HitColor.HIT_SIZE) / 100.0F;
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		try {
			pose.translate(g.guiWidth() / 2.0F, g.guiHeight() / 2.0F);
			pose.scale(size, size);
			// The server's hurt direction is 90° off "straight ahead" (see Minecraft's hurt tilt).
			pose.rotate((float) Math.toRadians(yaw - 90));
			for (int i = -4; i <= 4; i++) {
				pose.pushMatrix();
				pose.rotate((float) Math.toRadians(i * 6));
				g.fill(-2, -30, 2, -27, color);
				pose.popMatrix();
			}
		} finally {
			pose.popMatrix();
		}
	}

	private static void drawWaypoints(GuiGraphicsExtractor g, Minecraft mc) {
		String dimension = Mc263Features.dimension();
		List<Waypoint> all = Features.waypoints().all();
		if (dimension == null || all.isEmpty()) {
			return;
		}
		ModuleState state = Mods.state(WAYPOINTS);
		boolean names = state.bool(UtilityMods.Waypoints.SHOW_NAME);
		boolean distances = state.bool(UtilityMods.Waypoints.SHOW_DISTANCE);
		int max = state.integer(UtilityMods.Waypoints.MAX_DISTANCE);
		Vec3 eye = mc.gameRenderer.mainCamera().position();
		Font font = mc.font;
		int w = g.guiWidth();
		int h = g.guiHeight();
		for (int i = 0; i < all.size(); i++) {
			Waypoint wp = all.get(i);
			if (!wp.visible() || !wp.dimension().equals(dimension)) {
				continue;
			}
			double distance = wp.distanceTo(eye.x, eye.y, eye.z);
			if (max > 0 && distance > max) {
				continue;
			}
			Vec3 ndc = mc.gameRenderer.projectPointToScreen(new Vec3(wp.x() + 0.5, wp.y() + 0.5, wp.z() + 0.5));
			if (ndc.z > 1.0 || ndc.x < -1.2 || ndc.x > 1.2 || ndc.y < -1.2 || ndc.y > 1.2) {
				continue;
			}
			int sx = (int) Math.round((ndc.x + 1) / 2 * w);
			int sy = (int) Math.round((1 - ndc.y) / 2 * h);
			int color = 0xFF000000 | wp.rgb();
			Matrix3x2fStack pose = g.pose();
			pose.pushMatrix();
			pose.translate(sx, sy);
			pose.rotate((float) Math.toRadians(45));
			g.fill(-4, -4, 4, 4, 0xC0000000);
			g.fill(-3, -3, 3, 3, color);
			pose.popMatrix();
			int line = sy - 8;
			if (distances && wp.showDistance()) {
				int n = HudFormatInt.write(Math.round(distance), DIGITS);
				DIGITS[n++] = 'm';
				String text = new String(DIGITS, 0, n);
				g.text(font, text, sx - font.width(text) / 2, line - 9, 0xFFE8E2DA, true);
				line -= 10;
			}
			if (names && wp.showText()) {
				g.text(font, wp.name(), sx - font.width(wp.name()) / 2, line - 9, color, true);
			}
		}
	}

	private static void drawTnt(GuiGraphicsExtractor g, Minecraft mc, float partialTick) {
		ModuleState state = Mods.state(TNT);
		int decimals = state.integer(HudMods.TntCountdown.DECIMALS);
		boolean fixedColor = state.bool(HudMods.TntCountdown.STATIC_COLOR);
		boolean shadow = state.bool(HudMods.TntCountdown.SHADOW);
		Font font = mc.font;
		for (int i = 0; i < tntCount; i++) {
			PrimedTnt tnt = TNTS[i];
			if (tnt == null || tnt.isRemoved()) {
				continue;
			}
			Vec3 at = tnt.getPosition(partialTick).add(0, tnt.getBbHeight() + 0.5, 0);
			Vec3 ndc = mc.gameRenderer.projectPointToScreen(at);
			if (ndc.z > 1.0 || Math.abs(ndc.x) > 1.1 || Math.abs(ndc.y) > 1.1) {
				continue;
			}
			float seconds = Math.max(0, (tnt.getFuse() - partialTick) / 20.0F);
			String text = String.format(java.util.Locale.ROOT, "%." + decimals + "f", seconds);
			int color;
			if (fixedColor) {
				color = 0xFFFFFFFF;
			} else {
				float k = Math.clamp(seconds / 4.0F, 0, 1);
				int r = Math.round(255 * Math.min(1, 2 * (1 - k)));
				int gr = Math.round(255 * Math.min(1, 2 * k));
				color = 0xFF000000 | r << 16 | gr << 8 | 0x40;
			}
			int sx = (int) Math.round((ndc.x + 1) / 2 * g.guiWidth());
			int sy = (int) Math.round((1 - ndc.y) / 2 * g.guiHeight());
			g.text(font, text, sx - font.width(text) / 2, sy - 4, color, shadow);
		}
	}

	/** Whole numbers into a char buffer (waypoint distances). */
	private static final class HudFormatInt {
		static int write(long value, char[] out) {
			long v = Math.max(0, Math.min(value, 99_999_999));
			int n = 0;
			long d = 1;
			while (v / d >= 10) {
				d *= 10;
			}
			for (; d > 0; d /= 10) {
				out[n++] = (char) ('0' + v / d % 10);
			}
			return n;
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

	/** Ping as a number where vanilla draws its bars; true when it drew (vanilla's bars are then skipped). */
	public static boolean tabPing(GuiGraphicsExtractor g, int slotWidth, int xo, int yo, PlayerInfo info) {
		if (!HUD_PARTS.on() || !Mods.on(TAB) || !Mods.state(TAB).bool(UtilityMods.TabEditor.PING_NUMBER)) {
			return false;
		}
		try {
			int latency = info.getLatency();
			String text = latency < 0 ? "?" : Integer.toString(latency);
			int color = 0xFFAAAAAA;
			if (Mods.state(TAB).bool(UtilityMods.TabEditor.PING_COLOR) && latency >= 0) {
				color = latency < 150 ? 0xFF55FF55 : latency < 300 ? 0xFFFFFF55 : latency < 600 ? 0xFFFFAA00 : 0xFFFF5555;
			}
			Font font = mc().font;
			g.text(font, text, xo + slotWidth - 1 - font.width(text), yo, color, true);
			return true;
		} catch (RuntimeException e) {
			HUD_PARTS.fail(e);
			return false;
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

	/** Before the sidebar is drawn: its scale (about its right edge, mid-height) and offset. */
	public static void scoreboardBegin(GuiGraphicsExtractor g) {
		g.pose().pushMatrix();
		if (!Mods.on(SCORE)) {
			return;
		}
		ModuleState state = Mods.state(SCORE);
		float k = state.integer(UtilityMods.Scoreboard.SCALE) / 100F;
		float ax = g.guiWidth();
		float ay = g.guiHeight() / 2F;
		g.pose().translate(-state.integer(UtilityMods.Scoreboard.X_OFFSET), state.integer(UtilityMods.Scoreboard.Y_OFFSET));
		g.pose().translate(ax, ay);
		g.pose().scale(k, k);
		g.pose().translate(-ax, -ay);
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
		g.fill(x0, y0, x1, y1, (color & 0xFF000000) | rgb);
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

	/** Before the boss bars are drawn: their scale (about the top centre) and offset. */
	public static void bossBegin(GuiGraphicsExtractor g) {
		g.pose().pushMatrix();
		if (!Mods.on(BOSS)) {
			return;
		}
		ModuleState state = Mods.state(BOSS);
		float k = state.integer(UtilityMods.BossBar.SCALE) / 100F;
		float ax = g.guiWidth() / 2F;
		g.pose().translate(state.integer(UtilityMods.BossBar.X_OFFSET), state.integer(UtilityMods.BossBar.Y_OFFSET));
		g.pose().translate(ax, 0);
		g.pose().scale(k, k);
		g.pose().translate(-ax, 0);
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

	/** A nametag's text: your fake nick for you, "Player" for others when hidden; else unchanged. */
	public static Component nameTag(Entity entity, Component name) {
		if (!NAMES.on() || !Mods.on(NICK) || !(entity instanceof Player)) {
			return name;
		}
		try {
			ModuleState state = Mods.state(NICK);
			if (entity == mc().player) {
				return state.bool(UtilityMods.NickHider.HIDE_NICK) ? Component.literal(state.text(UtilityMods.NickHider.FAKE_NICK)) : name;
			}
			return state.bool(UtilityMods.NickHider.HIDE_OTHERS) ? Component.literal("Player") : name;
		} catch (RuntimeException e) {
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
				return state.bool(UtilityMods.NickHider.HIDE_NICK) ? Component.literal(state.text(UtilityMods.NickHider.FAKE_NICK)) : name;
			}
			return state.bool(UtilityMods.NickHider.HIDE_OTHERS) ? Component.literal("Player") : name;
		} catch (RuntimeException e) {
			NAMES.fail(e);
			return name;
		}
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
		} catch (RuntimeException e) {
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
		} catch (RuntimeException e) {
			NAMES.fail(e);
			return skin;
		}
	}
}
