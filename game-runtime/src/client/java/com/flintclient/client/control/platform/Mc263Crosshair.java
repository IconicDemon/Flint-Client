/*
 * Adapted from Custom Crosshair Mod by wjbaker and Crosshair Indicator (both CC0 1.0; texts in META-INF/licenses/). Changed
 * for Flint: settings from Flint's Crosshair module; shapes drawn as Minecraft GUI fills (lines one screen pixel wide, as the
 * original's line strips are), so every Minecraft version Flint supports draws them the same way.
 */
package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.feature.CrosshairDrawing;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.IntSetting;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.RgbSetting;
import com.flintclient.client.control.model.mods.PlayerMods.Crosshair;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Matrix3x2fStack;

/**
 * Crosshair, as Custom Crosshair Mod draws it: Minecraft's crosshair is left out and this one is drawn over the whole HUD
 * (and through F1, when visible there) while no screen is open, in its style, size, rotation, scale and place; colored by
 * what you aim at or a rainbow; opening up while your attack recharges or closing in as you draw a bow; with the ender pearl
 * and chorus fruit cooldown rings, the tool and ammunition indicators, the dot and Minecraft's attack indicator. Crosshair
 * Indicator's brackets show while you aim at a player. Fault-isolated; render thread.
 */
public final class Mc263Crosshair {
	private static final HookGuard GUARD = new HookGuard("crosshair",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	private static final int CROSSHAIR = Mods.index(Crosshair.ID);
	private static final Identifier VANILLA = Identifier.withDefaultNamespace("hud/crosshair");
	private static final Identifier BRACKETS = Identifier.fromNamespaceAndPath("flintclient", "crosshair_indicator");
	private static final int VANILLA_STYLE = 0;
	private static final int DEBUG_STYLE = 6;
	/** Drawing units are hundredths of a GUI unit, so the original's fractional edges (gap - 0.5, 2 x thickness - 0.2) keep. */
	private static final float UNIT = 100;
	private static final CrosshairDrawing DRAWING = new CrosshairDrawing();
	private static boolean drawingLoaded;
	private static boolean debugThisFrame;
	private static boolean editWas;
	private static int rainbowTicks;

	private Mc263Crosshair() {
	}

	private static boolean on() {
		return GUARD.on() && Mods.on(CROSSHAIR);
	}

	/** Whether the Crosshair module draws the crosshair (Minecraft's own crosshair method is then skipped). */
	public static boolean replacesVanilla() {
		return on();
	}

	/**
	 * Whether Minecraft's own 3D crosshair (the debug one) is drawn: not while the debug screen is open with the Crosshair
	 * module on, unless it keeps it.
	 */
	public static boolean vanilla3dCrosshair() {
		return !(on() && debugScreen() && !Mods.state(CROSSHAIR).bool(Crosshair.KEEP_DEBUG));
	}

	/** Whether this frame's crosshair is the Debug style: the version layer then draws Minecraft's 3D crosshair. */
	public static boolean debugStyleThisFrame() {
		return debugThisFrame;
	}

	public static CrosshairDrawing drawing() {
		if (!drawingLoaded) {
			drawingLoaded = true;
			DRAWING.load(drawingFile());
		}
		return DRAWING;
	}

	public static Path drawingFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("flintclient").resolve("crosshair-drawn.png");
	}

	private static boolean debugScreen() {
		return Minecraft.getInstance().debugEntries.isOverlayVisible();
	}

	/** Each client tick: the edit key (opens these settings) and the Draw crosshair row (opens the editor). */
	static void tick(boolean playing) {
		if (!GUARD.on()) {
			return;
		}
		try {
			ModuleState s = Mods.state(CROSSHAIR);
			Minecraft mc = Minecraft.getInstance();
			boolean edit = playing && Mods.on(CROSSHAIR) && Mc263Features.keyHeld(s.text(Crosshair.EDIT_KEY));
			if (edit && !editWas) {
				com.flintclient.client.control.rshift.ModMenuScreen.showModule(Crosshair.ID);
				com.flintclient.client.control.ControlCenter.openWindow(null);
			}
			editWas = edit;
			if (s.option(Crosshair.DRAW) != 0) {
				s.set(Crosshair.DRAW, 0);
				ControlState.settings().recordChange(s.id());
				mc.gui.setScreen(new CrosshairDrawScreen(mc.gui.screen()));
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	/** After the whole GUI was extracted, before it is drawn: the crosshair, over everything (no screen open). */
	public static void afterGui(GuiGraphicsExtractor g) {
		debugThisFrame = false;
		if (!on()) {
			return;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			LocalPlayer player = mc.player;
			if (player == null || mc.gui.screen() != null) {
				return;
			}
			ModuleState s = Mods.state(CROSSHAIR);
			if (!visible(mc, player, s)) {
				return;
			}
			int style = debugScreen() && s.bool(Crosshair.KEEP_DEBUG) ? DEBUG_STYLE : s.option(Crosshair.TYPE);
			int x = g.guiWidth() / 2;
			int y = g.guiHeight() / 2;
			int gap = gap(player, s);
			int color = color(mc, s);
			if (s.bool(Crosshair.ITEM_COOLDOWN)) {
				cooldowns(g, player, s, x, y, gap);
			}
			if (s.bool(Crosshair.DOT) && s.option(Crosshair.TYPE) != VANILLA_STYLE) {
				Matrix3x2fStack pose = g.pose();
				pose.pushMatrix();
				pose.translate(x, y);
				arc(g, 0.5F, 0, 360, argb(s, Crosshair.DOT_COLOR, Crosshair.DOT_ALPHA));
				pose.popMatrix();
			}
			Mc263Overlay.indicatorsWithCrosshair(g);
			int renderX = x + s.integer(Crosshair.OFFSET_X);
			int renderY = y + s.integer(Crosshair.OFFSET_Y);
			indicators(g, mc, player, s, renderX, renderY);
			Matrix3x2fStack pose = g.pose();
			pose.pushMatrix();
			try {
				pose.translate(renderX, renderY);
				float scale = s.integer(Crosshair.SCALE) / 100.0F;
				pose.scale(scale, scale);
				pose.rotate(s.integer(Crosshair.ROTATION) * 0.01745329F);
				drawStyle(g, mc, s, style, gap, color);
			} finally {
				pose.popMatrix();
			}
			if (s.bool(Crosshair.PLAYER_INDICATOR) && mc.crosshairPickEntity instanceof Player) {
				g.blitSprite(RenderPipelines.CROSSHAIR, BRACKETS, (g.guiWidth() - 15) / 2, (g.guiHeight() - 15) / 2, 15, 15);
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	private static boolean visible(Minecraft mc, LocalPlayer player, ModuleState s) {
		if (!s.bool(Crosshair.VISIBLE_DEFAULT)) {
			return false;
		}
		if (!s.bool(Crosshair.VISIBLE_HIDDEN_GUI) && com.flintclient.client.compat.McCompat.hud().isHidden()) {
			return false;
		}
		CameraType view = mc.options.getCameraType();
		if (!s.bool(Crosshair.THIRD_PERSON) && !view.isFirstPerson()) {
			return false;
		}
		if (!s.bool(Crosshair.VISIBLE_DEBUG) && debugScreen()) {
			return false;
		}
		if (!s.bool(Crosshair.VISIBLE_SPECTATOR) && player.isSpectator()) {
			return false;
		}
		if (!s.bool(Crosshair.VISIBLE_RANGED) && holding(player, Items.BOW, Items.TRIDENT, Items.CROSSBOW)) {
			return false;
		}
		if (!s.bool(Crosshair.VISIBLE_THROWABLE) && holding(player, Items.ENDER_PEARL, Items.ENDER_EYE, Items.ENDER_EYE)) {
			return false;
		}
		return s.bool(Crosshair.VISIBLE_SPYGLASS) || !player.isScoping();
	}

	/** In the main hand, or in the off hand with the main hand empty. */
	private static boolean holding(LocalPlayer player, Item a, Item b, Item c) {
		ItemStack main = player.getMainHandItem();
		if (main.is(a) || main.is(b) || main.is(c)) {
			return true;
		}
		ItemStack off = player.getOffhandItem();
		return main.isEmpty() && (off.is(a) || off.is(b) || off.is(c));
	}

	private static ItemStack activeItem(LocalPlayer player) {
		return player.isUsingItem() ? player.getUseItem() : player.getMainHandItem();
	}

	/** The gap, opened up while the attack recharges or narrowing as a bow, crossbow or trident is drawn. */
	private static int gap(LocalPlayer player, ModuleState s) {
		int base = s.integer(Crosshair.GAP);
		boolean holding = !player.getOffhandItem().isEmpty() || !player.getMainHandItem().isEmpty();
		boolean attack = s.bool(Crosshair.DYNAMIC_ATTACK);
		boolean bow = s.bool(Crosshair.DYNAMIC_BOW);
		if (player.isSpectator() || !holding || !attack && !bow) {
			return base;
		}
		ItemStack active = activeItem(player);
		float duration = active.is(Items.BOW) ? 20 : active.is(Items.TRIDENT) ? 10 : active.is(Items.CROSSBOW) ? 0 : -1;
		if (bow && duration >= 0) {
			if (active.is(Items.CROSSBOW)) {
				duration = CrossbowItem.getChargeDuration(active, player);
			}
			float progress = Math.min(duration, active.getUseDuration(player) - player.getUseItemRemainingTicks());
			return base + Math.round((duration - progress) * 2);
		}
		if (attack) {
			float strength = player.getAttackStrengthScale(1.0F);
			if (player.getCurrentItemAttackStrengthDelay() > 5.0F && strength < 1.0F) {
				return base + Math.round((1.0F - strength) * 2 * 20.0F);
			}
		}
		return base;
	}

	private static int argb(ModuleState s, RgbSetting color, IntSetting alpha) {
		return s.integer(alpha) << 24 | s.rgb(color);
	}

	/** Players, then hostile and neutral mobs, then animals; else the rainbow or the crosshair's own color. */
	private static int color(Minecraft mc, ModuleState s) {
		Entity target = mc.crosshairPickEntity;
		if (s.bool(Crosshair.PLAYERS) && target instanceof Player) {
			return argb(s, Crosshair.PLAYERS_COLOR, Crosshair.PLAYERS_ALPHA);
		}
		if (s.bool(Crosshair.HOSTILE) && (target instanceof Monster || target instanceof NeutralMob)) {
			return argb(s, Crosshair.HOSTILE_COLOR, Crosshair.HOSTILE_ALPHA);
		}
		if (s.bool(Crosshair.PASSIVE) && target instanceof Animal) {
			return argb(s, Crosshair.PASSIVE_COLOR, Crosshair.PASSIVE_ALPHA);
		}
		if (s.bool(Crosshair.RAINBOW)) {
			rainbowTicks = rainbowTicks + 1 > 125000 ? 0 : rainbowTicks + 1;
			return s.integer(Crosshair.ALPHA) << 24 | rainbow(0) << 16 | rainbow(2) << 8 | rainbow(4);
		}
		return argb(s, Crosshair.COLOR, Crosshair.ALPHA);
	}

	private static int rainbow(float offset) {
		int speed = Mods.state(CROSSHAIR).integer(Crosshair.RAINBOW_SPEED);
		return (int) (Math.sin(rainbowTicks * speed / 20000.0F + offset) * 127.0 + 128.0) & 0xFF;
	}

	/** The ender pearl and chorus fruit rings: clockwise from the top, for what's left of each cooldown. */
	private static void cooldowns(GuiGraphicsExtractor g, LocalPlayer player, ModuleState s, int x, int y, int gap) {
		int color = argb(s, Crosshair.COOLDOWN_COLOR, Crosshair.COOLDOWN_ALPHA);
		int size = Math.max(s.integer(Crosshair.WIDTH), s.integer(Crosshair.HEIGHT));
		int offset = 3;
		for (Item item : new Item[] {Items.ENDER_PEARL, Items.CHORUS_FRUIT}) {
			float cooldown = player.getCooldowns().getCooldownPercent(item.getDefaultInstance(), 0.0F);
			if (cooldown != 0.0F) {
				Matrix3x2fStack pose = g.pose();
				pose.pushMatrix();
				pose.translate(x, y);
				arc(g, gap + size + offset, 0, Math.round(360.0F - 360.0F * cooldown), color);
				pose.popMatrix();
				offset += 3;
			}
		}
	}

	/** The tool damage and ammunition indicators: a half-size icon and its number, beside the crosshair. */
	private static void indicators(GuiGraphicsExtractor g, Minecraft mc, LocalPlayer player, ModuleState s, int x, int y) {
		int drawX = x + s.integer(Crosshair.GAP) + 5;
		int drawY = y + s.integer(Crosshair.GAP) + 5;
		ItemStack tool = player.getMainHandItem();
		if (s.bool(Crosshair.TOOL_DAMAGE) && tool.isDamageableItem()) {
			int left = tool.getMaxDamage() - tool.getDamageValue();
			if (left <= 10) {
				indicator(g, mc, tool, Integer.toString(left), drawX, drawY);
				drawX += 15;
			}
		}
		if (s.bool(Crosshair.PROJECTILES)) {
			ItemStack projectile = player.getProjectile(tool);
			if (projectile != ItemStack.EMPTY) {
				indicator(g, mc, projectile, player.isCreative() ? "" : Integer.toString(projectile.getCount()), drawX, drawY);
			}
		}
	}

	private static void indicator(GuiGraphicsExtractor g, Minecraft mc, ItemStack icon, String text, int x, int y) {
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		pose.scale(0.5F, 0.5F);
		g.item(icon, x * 2 - 8, y * 2 - 8);
		g.text(mc.font, text, (x + 5) * 2, y * 2, 0xFFFFFFFF, true);
		pose.popMatrix();
	}

	// ---------- the styles ----------

	private static void drawStyle(GuiGraphicsExtractor g, Minecraft mc, ModuleState s, int style, int gap, int color) {
		boolean invert = s.bool(Crosshair.INVERT);
		float width = s.integer(Crosshair.WIDTH);
		float height = s.integer(Crosshair.HEIGHT);
		float thickness = s.integer(Crosshair.THICKNESS);
		int outline = argb(s, Crosshair.OUTLINE_COLOR, Crosshair.OUTLINE_ALPHA);
		boolean outlined = s.bool(Crosshair.OUTLINE);
		switch (style) {
			case VANILLA_STYLE -> g.blitSprite(RenderPipelines.CROSSHAIR, VANILLA, -15 / 2, -15 / 2, 15, 15);
			case 1 -> {
				float t = thickness - 0.1F;
				if (outlined) {
					float w = width + 1.0F;
					float h = height + 1.0F;
					float gp = gap - 0.5F;
					bordered(g, -t, -gp - h, t, -gp, outline, color, invert);
					bordered(g, -t, gp, t, gp + h, outline, color, invert);
					bordered(g, -gp - w, -t, -gp, t, outline, color, invert);
					bordered(g, gp, -t, gp + w, t, outline, color, invert);
				} else {
					float at = t - 0.5F;
					rect(g, -at, -gap - height, at, -gap, color, invert);
					rect(g, -at, gap, at, gap + height, color, invert);
					rect(g, -gap - width, -at, -gap, at, color, invert);
					rect(g, gap, -at, gap + width, at, color, invert);
				}
			}
			case 2 -> arc(g, gap, 0, 360, color);
			case 3 -> {
				if (outlined) {
					frame(g, -width - gap + 0.5F, -height - gap + 0.5F, width + gap - 0.5F, height + gap - 0.5F, outline);
					frame(g, -width - thickness - gap - 0.5F, -height - thickness - gap - 0.5F, width + thickness + gap + 0.5F,
						height + thickness + gap + 0.5F, outline);
				}
				rect(g, -width - thickness - gap, -height - thickness - gap, width + thickness + gap, -height - gap, color, invert);
				rect(g, -width - thickness - gap, height + gap, width + thickness + gap, height + thickness + gap, color, invert);
				rect(g, -width - thickness - gap, -gap - height, -width - gap, gap + height, color, invert);
				rect(g, width + gap, -gap - height, width + thickness + gap, gap + height, color, invert);
			}
			case 4 -> {
				float top = -height / 2.0F - gap;
				float bottom = height / 2.0F + gap;
				float side = width / 2.0F + gap;
				line(g, 0, top, side, bottom, color);
				line(g, side, bottom, -side, bottom, color);
				line(g, -side, bottom, 0, top, color);
			}
			case 5 -> {
				line(g, -width, height, 0, 0, color);
				line(g, 0, 0, width, height, color);
			}
			case DEBUG_STYLE -> debugThisFrame = true;
			default -> drawn(g, color, invert);
		}
	}

	/** The drawn crosshair: one GUI unit per pixel, centred. */
	private static void drawn(GuiGraphicsExtractor g, int color, boolean invert) {
		CrosshairDrawing drawing = drawing();
		float ox = drawing.width() / 2.0F;
		float oy = drawing.height() / 2.0F;
		for (int px = 0; px < drawing.width(); px++) {
			for (int py = 0; py < drawing.height(); py++) {
				if (drawing.at(px, py)) {
					rect(g, px - ox, py - oy, px - ox + 1, py - oy + 1, color, invert);
				}
			}
		}
	}

	// ---------- primitives ----------

	/** A filled rectangle with fractional edges (hundredths of a unit), in the current pose. */
	private static void rect(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, int color, boolean invert) {
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		pose.scale(1 / UNIT, 1 / UNIT);
		int a = Math.round(Math.min(x0, x1) * UNIT);
		int b = Math.round(Math.min(y0, y1) * UNIT);
		int c = Math.round(Math.max(x0, x1) * UNIT);
		int d = Math.round(Math.max(y0, y1) * UNIT);
		if (invert) {
			g.fill(RenderPipelines.GUI_INVERT, a, b, c, d, 0xFFFFFFFF);
		} else {
			g.fill(a, b, c, d, color);
		}
		pose.popMatrix();
	}

	/** A filled rectangle with a one-pixel line around it (the Cross style's outlined arms). */
	private static void bordered(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, int border, int fill, boolean invert) {
		rect(g, x0, y0, x1, y1, fill, invert);
		frame(g, x0, y0, x1, y1, border);
	}

	private static void frame(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, int color) {
		line(g, x0, y0, x1, y0, color);
		line(g, x1, y0, x1, y1, color);
		line(g, x1, y1, x0, y1, color);
		line(g, x0, y1, x0, y0, color);
	}

	/** How many GUI units one screen pixel is in the current pose (lines stay one pixel wide, as the original's do). */
	private static float pixel(GuiGraphicsExtractor g) {
		Matrix3x2fStack pose = g.pose();
		float scale = (float) Math.sqrt(Math.abs(pose.m00() * pose.m11() - pose.m01() * pose.m10()));
		return 1.0F / Math.max(1e-4F, scale * (float) Minecraft.getInstance().getWindow().getGuiScale());
	}

	/** A line one screen pixel wide from (x0, y0) to (x1, y1). */
	private static void line(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, int color) {
		float dx = x1 - x0;
		float dy = y1 - y0;
		float length = (float) Math.sqrt(dx * dx + dy * dy);
		if (length <= 0) {
			return;
		}
		float half = pixel(g) / 2;
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		pose.translate(x0, y0);
		pose.rotate((float) Math.atan2(dy, dx));
		rect(g, 0, -half, length, half, color, false);
		pose.popMatrix();
	}

	/** An arc of radius {@code r} around the pose's origin, clockwise from the top, {@code from} to {@code to} degrees. */
	private static void arc(GuiGraphicsExtractor g, float r, int from, int to, int color) {
		int start = Math.max(0, Math.min(from, to));
		int end = Math.min(360, Math.max(from, to));
		if (end <= start) {
			return;
		}
		// The original joins a point every degree; as many short lines as the circle needs to look round.
		float pixels = r / pixel(g);
		int step = Math.max(1, Math.min(30, (int) (360 / Math.max(12, 2 * Math.PI * pixels / 2))));
		float px = 0;
		float py = 0;
		for (int i = start; i <= end; i += step) {
			int deg = Math.min(i, end);
			double rad = Math.toRadians(deg - 90);
			float nx = (float) Math.cos(rad) * r;
			float ny = (float) Math.sin(rad) * r;
			if (i > start) {
				line(g, px, py, nx, ny, color);
			}
			px = nx;
			py = ny;
			if (deg == end) {
				break;
			}
			if (i + step > end && i != end) {
				i = end - step;
			}
		}
	}
}
