package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.compat.McCompat;
import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.feature.FoodRegen;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.HudMods.Saturation;
import com.flintclient.client.mixin.feature.DebugScreenEntriesInvoker;
import com.flintclient.client.mixin.feature.FoodDataAccessor;
import com.mojang.blaze3d.platform.InputConstants;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.FormattedCharSink;
import net.minecraft.util.StringDecomposer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect;
import net.minecraft.world.item.consume_effects.ConsumeEffect;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.joml.Matrix3x2fStack;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft's side of the Saturation module beyond its counter: AppleSkin's food overlays, with its look, timing and rules.
 * <ul>
 * <li>the saturation overlay: AppleSkin's gold outline (four fill levels) on the drumsticks of the hunger bar;</li>
 * <li>the exhaustion underlay: a dark bar behind the drumsticks that fills from the right toward the next hunger point;</li>
 * <li>while you hold food you could eat (main hand, else the off-hand): the hunger and saturation it would restore and the
 * health it would give back, flashing (a per-tick fade up, hold, fade down and pause, 1.6 s at speed 10);</li>
 * <li>a food's hunger and saturation as icons at the bottom of its tooltip (Never, Shift Only or Always);</li>
 * <li>a debug screen entry, {@code food_stats}: "hunger: 20, sat: 5, exh: 0.00/4".</li>
 * </ul>
 * In single player and on your own LAN world, saturation, exhaustion and the natural regeneration rule are read from the
 * game you host (what AppleSkin's server half sends its client); on a server, saturation is what the server sends and
 * exhaustion stays at zero. Render thread; fault-isolated.
 *
 * <p>Adapted from AppleSkin (squeek502, Unlicense; META-INF/licenses/Unlicense-AppleSkin.txt): {@code HUDOverlayHandler},
 * {@code TooltipOverlayHandler}, {@code FoodHelper} and {@code DebugInfoHudEntry}, and its icons texture
 * ({@code textures/gui/appleskin/icons.png}, with a greyscale copy for the Saturation color).
 */
public final class Mc263Food {
	private static final int MODULE = Mods.index(Saturation.ID);
	private static final HookGuard GUARD = new HookGuard("saturation overlay",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	private static final Identifier ICONS = Identifier.fromNamespaceAndPath("flintclient", "textures/gui/appleskin/icons.png");
	private static final Identifier ICONS_TINT = Identifier.fromNamespaceAndPath("flintclient", "textures/gui/appleskin/icons_tint.png");
	private static final Identifier FULL = Identifier.withDefaultNamespace("hud/food_full");
	private static final Identifier HALF = Identifier.withDefaultNamespace("hud/food_half");
	private static final Identifier EMPTY = Identifier.withDefaultNamespace("hud/food_empty");
	private static final Identifier FULL_HUNGER = Identifier.withDefaultNamespace("hud/food_full_hunger");
	private static final Identifier HALF_HUNGER = Identifier.withDefaultNamespace("hud/food_half_hunger");
	private static final Identifier EMPTY_HUNGER = Identifier.withDefaultNamespace("hud/food_empty_hunger");
	private static final Identifier HEART_FULL = Identifier.withDefaultNamespace("hud/heart/full");
	private static final Identifier HEART_HALF = Identifier.withDefaultNamespace("hud/heart/half");
	private static final Identifier HEART_FULL_HARDCORE = Identifier.withDefaultNamespace("hud/heart/hardcore_full");
	private static final Identifier HEART_HALF_HARDCORE = Identifier.withDefaultNamespace("hud/heart/hardcore_half");
	private static final Identifier CONTAINER = Identifier.withDefaultNamespace("hud/heart/container");
	private static final Identifier CONTAINER_HARDCORE = Identifier.withDefaultNamespace("hud/heart/container_hardcore");

	/** The flash: its unclamped value bounces between -0.5 and 1.5, so it holds clear and full for a moment each way. */
	private static float unclampedFlash;
	private static float flashAlpha;
	private static int flashDirection = 1;

	/** Where Minecraft drew each drumstick (index 0 the rightmost) and each heart this frame. */
	private static final int[] FOOD_X = new int[10];
	private static final int[] FOOD_Y = new int[10];
	private static int foodSeen;
	private static final int[] HEART_X = new int[256];
	private static final int[] HEART_Y = new int[256];
	private static int heartsSeen;

	private Mc263Food() {
	}

	// ---------- each client tick ----------

	/** From the start of every client tick: the flash steps on, and the debug entry follows its switch. */
	public static void tick() {
		if (!GUARD.on()) {
			return;
		}
		try {
			ModuleState state = Mods.state(MODULE);
			float step = 0.125F * state.integer(Saturation.ANIMATION_SPEED) / 10F;
			unclampedFlash += flashDirection * step;
			if (unclampedFlash >= 1.5F) {
				flashDirection = -1;
			} else if (unclampedFlash <= -0.5F) {
				flashDirection = 1;
			}
			flashAlpha = clamp01(unclampedFlash) * clamp01(state.decimal(Saturation.FLASH_ALPHA));
			Debug.sync(state);
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	private static void resetFlash() {
		unclampedFlash = 0;
		flashAlpha = 0;
		flashDirection = 1;
	}

	private static float clamp01(float value) {
		return Math.max(0, Math.min(1, value));
	}

	/** {@code argb} white at {@code alpha} (AppleSkin's ColorHelper). */
	private static int white(float alpha) {
		return (int) Math.floor(alpha * 255.0) << 24 | 0xFFFFFF;
	}

	// ---------- what the game knows ----------

	/** You as the game you host has you (single player, your LAN world), or null on a server. */
	private static @Nullable ServerPlayer hosted(Player player) {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		return server == null ? null : server.getPlayerList().getPlayer(player.getUUID());
	}

	/** Your saturation: live from the game you host, else what the server last sent. */
	public static float saturation(Player player) {
		ServerPlayer own = hosted(player);
		return (own != null ? own.getFoodData() : player.getFoodData()).getSaturationLevel();
	}

	/** Your exhaustion (0-4): from the game you host; on a server the client's own, which stays at zero. */
	private static float exhaustion(Player player) {
		ServerPlayer own = hosted(player);
		return ((FoodDataAccessor) (own != null ? own.getFoodData() : player.getFoodData())).flint$exhaustion();
	}

	/** The natural health regeneration rule of the game you host; on a server assumed on, as AppleSkin does without its server half. */
	private static boolean naturalRegeneration() {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		return server == null || McCompat.naturalRegeneration(server.overworld());
	}

	/** A food: an item with food values that can be consumed. */
	private static @Nullable FoodProperties food(ItemStack stack) {
		return stack.has(DataComponents.CONSUMABLE) ? stack.get(DataComponents.FOOD) : null;
	}

	/** The food you hold that you could eat now: the main hand's, else (with Food in off-hand) the off-hand's; or null. */
	private static @Nullable ItemStack heldFood(Player player, ModuleState state) {
		ItemStack held = player.getMainHandItem();
		FoodProperties food = food(held);
		boolean canEat = food != null && player.canEat(food.canAlwaysEat());
		if (!canEat && state.bool(Saturation.OFFHAND)) {
			held = player.getOffhandItem();
			food = food(held);
			canEat = food != null && player.canEat(food.canAlwaysEat());
		}
		return !held.isEmpty() && canEat ? held : null;
	}

	/** Whether eating it makes you sick (a harmful effect: rotten flesh, raw chicken, spider eyes). */
	private static boolean rotten(ItemStack stack) {
		Consumable consumable = stack.get(DataComponents.CONSUMABLE);
		if (consumable == null) {
			return false;
		}
		for (ConsumeEffect effect : consumable.onConsumeEffects()) {
			if (effect instanceof ApplyStatusEffectsConsumeEffect apply) {
				for (MobEffectInstance instance : apply.effects()) {
					if (instance.getEffect().value().getCategory() == MobEffectCategory.HARMFUL) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** The health eating it would give back (AppleSkin's estimate): natural regeneration, then a regeneration effect it gives. */
	private static float healthIncrement(Player player, ItemStack stack, FoodProperties food, ModuleState state) {
		if (!player.isHurt()) {
			return 0;
		}
		FoodData data = player.getFoodData();
		float health = 0;
		if (state.bool(Saturation.NATURAL_REGEN) && naturalRegeneration()) {
			health = FoodRegen.afterEating(data.getFoodLevel(), saturation(player), exhaustion(player), food.nutrition(), food.saturation());
		}
		Consumable consumable = stack.get(DataComponents.CONSUMABLE);
		if (consumable != null) {
			for (ConsumeEffect effect : consumable.onConsumeEffects()) {
				if (effect instanceof ApplyStatusEffectsConsumeEffect apply) {
					for (MobEffectInstance instance : apply.effects()) {
						if (instance.getEffect().equals(MobEffects.REGENERATION)) {
							health += (float) Math.floor(instance.getDuration() / (double) Math.max(50 >> instance.getAmplifier(), 1));
							break;
						}
					}
				}
			}
		}
		return health;
	}

	private static boolean anyOverlay(ModuleState state) {
		return state.bool(Saturation.FOOD_GAIN) || state.bool(Saturation.OVERLAY) || state.bool(Saturation.HEALTH_GAIN);
	}

	// ---------- where Minecraft drew the bar this frame ----------

	/** From Minecraft's hunger bar: drumstick {@code foodSeen} is at ({@code x}, {@code y}). */
	public static void foodIcon(int x, int y) {
		if (foodSeen < FOOD_X.length) {
			FOOD_X[foodSeen] = x;
			FOOD_Y[foodSeen] = y;
			foodSeen++;
		}
	}

	public static void heartsStart() {
		heartsSeen = 0;
	}

	/** From Minecraft's hearts: a heart sprite at ({@code x}, {@code y}); a new place starts the next heart (they go down). */
	public static void heartIcon(int x, int y) {
		if (heartsSeen > 0 && HEART_X[heartsSeen - 1] == x && HEART_Y[heartsSeen - 1] == y) {
			return;
		}
		if (heartsSeen < HEART_X.length) {
			HEART_X[heartsSeen] = x;
			HEART_Y[heartsSeen] = y;
			heartsSeen++;
		}
	}

	/** Drumstick {@code i} (0 the rightmost): where Minecraft drew it (with Animate on), else its resting place. */
	private static int foodX(int i, int xRight, boolean shake) {
		return shake && i < foodSeen ? FOOD_X[i] : xRight - i * 8 - 9;
	}

	private static int foodY(int i, int y, boolean shake) {
		return shake && i < foodSeen ? FOOD_Y[i] : y;
	}

	// ---------- the hunger bar ----------

	/** Before the hunger bar is drawn: the exhaustion underlay behind it. */
	public static void beforeFood(GuiGraphicsExtractor graphics, Player player, int yLineBase, int xRight) {
		foodSeen = 0;
		if (!GUARD.on() || !Mods.on(MODULE)) {
			return;
		}
		try {
			if (!Mods.state(MODULE).bool(Saturation.EXHAUSTION)) {
				return;
			}
			float ratio = Math.min(1, Math.max(0, exhaustion(player) / FoodRegen.MAX_EXHAUSTION));
			int width = (int) (ratio * 81);
			graphics.blit(RenderPipelines.GUI_TEXTURED, ICONS, xRight - width, yLineBase, 81 - width, 18, width, 9, 256, 256, white(0.75F));
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	/** After the hunger bar is drawn: the saturation overlay, then what held food would restore. */
	public static void foodBar(GuiGraphicsExtractor graphics, Player player, int yLineBase, int xRight) {
		if (!GUARD.on() || !Mods.on(MODULE)) {
			return;
		}
		try {
			ModuleState state = Mods.state(MODULE);
			if (!anyOverlay(state)) {
				return;
			}
			boolean shake = state.bool(Saturation.SHAKE);
			int rgb = state.rgb(Saturation.COLOR);
			FoodData data = player.getFoodData();
			int food = data.getFoodLevel();
			float saturation = saturation(player);
			boolean overlay = state.bool(Saturation.OVERLAY);
			if (overlay) {
				saturationOverlay(graphics, 0, saturation, xRight, yLineBase, 1, state.bool(Saturation.COLOR_OVERLAY) ? rgb : -1, shake);
			}
			ItemStack held = heldFood(player, state);
			if (held == null) {
				resetFlash();
				return;
			}
			if (!state.bool(Saturation.FOOD_GAIN)) {
				return;
			}
			FoodProperties gain = food(held);
			int hunger = gain.nutrition();
			hungerOverlay(graphics, hunger, food, xRight, yLineBase, flashAlpha, rotten(held), shake);
			if (overlay && state.bool(Saturation.SATURATION_GAIN)) {
				int newFood = food + hunger;
				float newSaturation = saturation + gain.saturation();
				float gained = newSaturation > newFood ? newFood - saturation : gain.saturation();
				saturationOverlay(graphics, gained, saturation, xRight, yLineBase, flashAlpha, state.bool(Saturation.COLOR_GAIN) ? rgb : -1, shake);
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	/** AppleSkin's saturation outline from {@code saturation} plus {@code gained}; {@code rgb} -1 for its own gold. */
	private static void saturationOverlay(GuiGraphicsExtractor graphics, float gained, float saturation, int xRight, int y, float alpha, int rgb,
		boolean shake) {
		if (saturation + gained < 0 || alpha <= 0) {
			return;
		}
		int color = rgb < 0 ? white(alpha) : (int) Math.floor(alpha * 255.0) << 24 | rgb & 0xFFFFFF;
		Identifier texture = rgb < 0 ? ICONS : ICONS_TINT;
		float modified = Math.max(0, Math.min(saturation + gained, 20));
		int start = gained != 0 ? (int) Math.max(saturation / 2, 0) : 0;
		int end = (int) Math.ceil(modified / 2);
		for (int i = start; i < end; i++) {
			float effective = modified / 2 - i;
			int u = effective >= 1 ? 27 : effective > 0.5 ? 18 : effective > 0.25 ? 9 : 0;
			graphics.blit(RenderPipelines.GUI_TEXTURED, texture, foodX(i, xRight, shake), foodY(i, y, shake), u, 0, 9, 9, 256, 256, color);
		}
	}

	/** The hunger {@code hunger} more points would fill, flashing on a faint empty drumstick. */
	private static void hungerOverlay(GuiGraphicsExtractor graphics, int hunger, int food, int xRight, int y, float alpha, boolean sick,
		boolean shake) {
		if (hunger <= 0 || alpha <= 0) {
			return;
		}
		int modified = Math.max(0, Math.min(20, food + hunger));
		int start = Math.max(0, food / 2);
		int end = (int) Math.ceil(modified / 2.0F);
		for (int i = start; i < end; i++) {
			int x = foodX(i, xRight, shake);
			int iy = foodY(i, y, shake);
			graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sick ? EMPTY_HUNGER : EMPTY, x, iy, 9, 9, white(alpha * 0.25F));
			boolean half = i * 2 + 1 == modified;
			graphics.blitSprite(RenderPipelines.GUI_TEXTURED, half ? sick ? HALF_HUNGER : HALF : sick ? FULL_HUNGER : FULL, x, iy, 9, 9, white(alpha));
		}
	}

	// ---------- the hearts ----------

	/**
	 * After the hearts are drawn ({@code x}, {@code y}: the first heart; rows {@code rowHeight} apart going up): the health held
	 * food would give back, flashing on a faint container. As AppleSkin, not at 18 hunger or more (you heal anyway), in
	 * peaceful, or while poison, wither or regeneration is changing your health.
	 */
	public static void hearts(GuiGraphicsExtractor graphics, Player player, int x, int y, int rowHeight, float maxHealth) {
		if (!GUARD.on() || !Mods.on(MODULE)) {
			return;
		}
		try {
			ModuleState state = Mods.state(MODULE);
			if (!anyOverlay(state)) {
				return;
			}
			ItemStack held = heldFood(player, state);
			if (held == null) {
				resetFlash();
				return;
			}
			if (!state.bool(Saturation.HEALTH_GAIN) || player.level().getDifficulty() == Difficulty.PEACEFUL || player.getFoodData().getFoodLevel() >= 18
				|| player.hasEffect(MobEffects.POISON) || player.hasEffect(MobEffects.WITHER) || player.hasEffect(MobEffects.REGENERATION)) {
				return;
			}
			float health = player.getHealth();
			float modified = Math.min(health + healthIncrement(player, held, food(held), state), player.getMaxHealth());
			if (modified <= health || flashAlpha <= 0) {
				return;
			}
			boolean shake = state.bool(Saturation.SHAKE);
			boolean hardcore = player.level().getLevelData().isHardcore();
			int fixedModified = (int) Math.ceil(modified);
			int start = (int) Math.max(0, Math.ceil(health) / 2.0);
			int end = (int) Math.max(0, Math.ceil(modified / 2.0F));
			for (int i = start; i < end; i++) {
				int hx = x + i % 10 * 8;
				int hy = y - i / 10 * rowHeight;
				// Where Minecraft drew heart i (drawn from the last down to the first, absorption included).
				int drawn = heartsSeen - 1 - i;
				if (shake && drawn >= 0 && drawn < heartsSeen) {
					hx = HEART_X[drawn];
					hy = HEART_Y[drawn];
				}
				graphics.blitSprite(RenderPipelines.GUI_TEXTURED, hardcore ? CONTAINER_HARDCORE : CONTAINER, hx, hy, 9, 9, white(flashAlpha * 0.25F));
				boolean half = i * 2 + 1 == fixedModified;
				Identifier sprite = half ? hardcore ? HEART_HALF_HARDCORE : HEART_HALF : hardcore ? HEART_FULL_HARDCORE : HEART_FULL;
				graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, hx, hy, 9, 9, white(flashAlpha));
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	// ---------- tooltips ----------

	/**
	 * A food's tooltip lines (as {@code ItemStack.getTooltipLines} returns them): its values as icons after every other line,
	 * per the module's Never / Shift Only / Always. Not for a tooltip the item hides, nor off the render thread (the creative
	 * search builds its index elsewhere).
	 */
	public static void tooltipLines(ItemStack stack, TooltipFlag flag, @Nullable List<Component> lines) {
		if (lines == null || !GUARD.on() || !Mods.on(MODULE) || !Minecraft.getInstance().isSameThread()) {
			return;
		}
		try {
			int when = Mods.state(MODULE).option(Saturation.TOOLTIP);
			if (stack.isEmpty() || !(when == 2 || when == 1 && shift())) {
				return;
			}
			if (!flag.isCreative() && stack.getOrDefault(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT).hideTooltip()) {
				return;
			}
			FoodProperties food = food(stack);
			if (food == null) {
				return;
			}
			FoodIcons icons = new FoodIcons(food.nutrition(), food.saturation(), rotten(stack));
			if (icons.hungerBars > 0) {
				lines.add(new FoodLine(icons));
			}
		} catch (UnsupportedOperationException e) {
			// An unmodifiable list (another mod's): no icons.
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	/** The tooltip component for one of the module's tooltip lines, or null for any other line. */
	public static @Nullable ClientTooltipComponent clientTooltip(FormattedCharSequence line) {
		return line instanceof FoodLine food ? food.icons : null;
	}

	/** A tooltip line that stands for the food icons (empty text; the tooltip turns it into {@link FoodIcons}). */
	private static final class FoodLine implements Component, FormattedCharSequence {
		private static final List<Component> NO_SIBLINGS = new ArrayList<>();
		final FoodIcons icons;

		FoodLine(FoodIcons icons) {
			this.icons = icons;
		}

		@Override
		public Style getStyle() {
			return Style.EMPTY;
		}

		@Override
		public ComponentContents getContents() {
			return PlainTextContents.EMPTY;
		}

		@Override
		public List<Component> getSiblings() {
			return NO_SIBLINGS;
		}

		@Override
		public FormattedCharSequence getVisualOrderText() {
			return this;
		}

		@Override
		public boolean accept(FormattedCharSink sink) {
			return StringDecomposer.iterateFormatted(this, getStyle(), sink);
		}
	}

	/**
	 * AppleSkin's food tooltip: a row of drumsticks for the hunger a food restores (rightmost first, a half at the left end;
	 * in the hunger colours for food that makes you sick) and a row of small saturation icons, each shown as "xN" after one
	 * icon when it would take more than ten.
	 */
	private static final class FoodIcons implements ClientTooltipComponent {
		private final int hunger;
		private final float saturation;
		private final boolean rotten;
		final int hungerBars;
		private final @Nullable String hungerText;
		private final int saturationBars;
		private final @Nullable String saturationText;

		FoodIcons(int hunger, float saturation, boolean rotten) {
			this.hunger = hunger;
			this.saturation = saturation;
			this.rotten = rotten;
			int bars = (int) Math.ceil(Math.abs(hunger) / 2.0F);
			if (bars > 10) {
				hungerText = "x" + (hunger < 0 ? -1 : 1) * bars;
				bars = 1;
			} else {
				hungerText = null;
			}
			hungerBars = bars;
			int satBars = (int) Math.ceil(Math.abs(saturation) / 2.0F);
			if (satBars > 10 || satBars == 0) {
				saturationText = "x" + (saturation < 0 ? -1 : 1) * satBars;
				satBars = 1;
			} else {
				saturationText = null;
			}
			saturationBars = satBars;
		}

		@Override
		public int getHeight(Font font) {
			return 20;
		}

		@Override
		public int getWidth(Font font) {
			int hungerLength = hungerBars * 9 + (hungerText != null ? font.width(hungerText) : 0);
			int saturationLength = saturationBars * 7 + (saturationText != null ? font.width(saturationText) : 0);
			return Math.max(hungerLength, saturationLength);
		}

		@Override
		public void extractImage(Font font, int toolTipX, int toolTipY, int w, int h, GuiGraphicsExtractor graphics) {
			ModuleState state = Mods.state(MODULE);
			int x = toolTipX + (hungerBars - 1) * 9;
			int y = toolTipY;
			Matrix3x2fStack pose = graphics.pose();
			for (int i = 0; i < hungerBars * 2; i += 2) {
				graphics.blitSprite(RenderPipelines.GUI_TEXTURED, EMPTY, x, y, 9, 9);
				// The food's own value faintly, then the value it gives (the same here: no other mod changes it).
				boolean defaultHalf = hunger - 1 == i;
				graphics.blitSprite(RenderPipelines.GUI_TEXTURED, icon(defaultHalf), x, y, 9, 9, white(0.25F));
				if (hunger > i) {
					graphics.blitSprite(RenderPipelines.GUI_TEXTURED, icon(hunger - 1 == i), x, y, 9, 9);
				}
				x -= 9;
			}
			if (hungerText != null) {
				x += 18;
				pose.pushMatrix();
				pose.translate(x, y);
				pose.scale(0.75F, 0.75F);
				graphics.text(font, hungerText, 2, 2, 0xFFAAAAAA);
				pose.popMatrix();
			}
			y += 10;
			float absSaturation = Math.abs(saturation);
			x = toolTipX + (saturationBars - 1) * 7;
			boolean tint = state.bool(Saturation.COLOR_TOOLTIP);
			int rgb = state.rgb(Saturation.COLOR) & 0xFFFFFF;
			for (int i = 0; i < saturationBars * 2; i += 2) {
				float effective = (absSaturation - i) / 2.0F;
				float alpha = absSaturation <= i ? 0.5F : 1;
				int color = tint ? (int) Math.floor(alpha * 255.0) << 24 | rgb : white(alpha);
				int u = effective >= 1 ? 21 : effective > 0.5 ? 14 : effective > 0.25 ? 7 : effective > 0 ? 0 : 28;
				graphics.blit(RenderPipelines.GUI_TEXTURED, tint ? ICONS_TINT : ICONS, x, y, u, saturation >= 0 ? 27 : 34, 7, 7, 256, 256, color);
				x -= 7;
			}
			if (saturationText != null) {
				x += 14;
				pose.pushMatrix();
				pose.translate(x, y);
				pose.scale(0.75F, 0.75F);
				graphics.text(font, saturationText, 2, 1, 0xFFAAAAAA);
				pose.popMatrix();
			}
		}

		private Identifier icon(boolean half) {
			return half ? rotten ? HALF_HUNGER : HALF : rotten ? FULL_HUNGER : FULL;
		}
	}

	private static boolean shift() {
		return Mc263ControlPlatform.keyDown(InputConstants.KEY_LSHIFT) || Mc263ControlPlatform.keyDown(InputConstants.KEY_RSHIFT);
	}

	// ---------- the debug screen ----------

	/**
	 * AppleSkin's debug screen entry, registered with Minecraft as {@code flintclient:food_stats} so Minecraft's Debug Options
	 * list it as AppleSkin's is listed. The module's "Food values in debug screen" and that entry's status are one switch: a
	 * change on either side is copied to the other.
	 */
	static final class Debug implements DebugScreenEntry {
		static final Identifier ID = Identifier.fromNamespaceAndPath("flintclient", "food_stats");
		private static final Identifier GROUP = Identifier.fromNamespaceAndPath("flintclient", "food_debug_info");
		private static final DebugScreenEntryStatus[] STATUS = {DebugScreenEntryStatus.NEVER, DebugScreenEntryStatus.IN_OVERLAY,
			DebugScreenEntryStatus.ALWAYS_ON};
		private static final DecimalFormat SATURATION = new DecimalFormat("#.##");
		private static final DecimalFormat EXHAUSTION = new DecimalFormat("0.00");
		private static final DecimalFormat EXHAUSTION_MAX = new DecimalFormat("#.##");
		private static boolean registered;
		private static int lastFlint = -1;
		private static @Nullable DebugScreenEntryStatus lastVanilla;

		@Override
		public void display(DebugScreenDisplayer lines, @Nullable Level level, @Nullable LevelChunk clientChunk, @Nullable LevelChunk serverChunk) {
			Player player = Minecraft.getInstance().player;
			if (level == null || player == null || !GUARD.on() || !Mods.on(MODULE)) {
				return;
			}
			lines.addToGroup(GROUP, "hunger: " + player.getFoodData().getFoodLevel() + ", sat: " + SATURATION.format(saturation(player)) + ", exh: "
				+ EXHAUSTION.format(exhaustion(player)) + "/" + EXHAUSTION_MAX.format(FoodRegen.MAX_EXHAUSTION));
		}

		static void sync(ModuleState state) {
			Minecraft mc = Minecraft.getInstance();
			DebugScreenEntryList list = mc.debugEntries;
			if (list == null) {
				return;
			}
			if (!registered) {
				registered = true;
				DebugScreenEntriesInvoker.flint$register(ID, new Debug());
				list.rebuildCurrentList();
			}
			int flint = state.option(Saturation.DEBUG);
			DebugScreenEntryStatus vanilla = list.getStatus(ID);
			if (lastFlint < 0 || flint == lastFlint && vanilla != lastVanilla) {
				// At start-up (Minecraft's file is the one that is always saved) and after a change in Debug Options: Flint follows.
				int index = java.util.Arrays.asList(STATUS).indexOf(vanilla);
				if (index >= 0 && index != flint && state.set(Saturation.DEBUG, index)) {
					ControlState.saveAll();
				}
			} else if (flint != lastFlint && STATUS[flint] != vanilla) {
				list.setStatus(ID, STATUS[flint]);
			}
			lastFlint = state.option(Saturation.DEBUG);
			lastVanilla = list.getStatus(ID);
		}
	}
}
