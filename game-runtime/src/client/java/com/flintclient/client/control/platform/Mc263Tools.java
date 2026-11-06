package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.BoolSetting;
import com.flintclient.client.control.model.KeySetting;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.ToolMods.Animations;
import com.flintclient.client.control.model.mods.ToolMods.DropPrevention;
import com.flintclient.client.control.model.mods.ToolMods.KeybindSearch;
import com.flintclient.client.mixin.control.KeyMappingAccessor;
import com.mojang.blaze3d.platform.NativeImage;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Minecraft 26.3's side of the fourth batch: Animations (hit direction, the damage flash's colour, your swing's length,
 * your own potion particles, armor that flashes when hurt, steady hearts), Keybind Search (opened by its key; the chosen
 * keybind is pressed for a moment as a real key would) and Drop Prevention (its own list on top of Inventory's protection:
 * the drop key and throwing are stopped, the hotbar marks protected items, the wheel holds on to them). Fault-isolated.
 */
public final class Mc263Tools {
	private static final HookGuard GUARD = new HookGuard("animation and tool mods",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	private static final int ANIMATIONS = Mods.index(Animations.ID);
	private static final int SEARCH = Mods.index(KeybindSearch.ID);
	private static final int DROP = Mods.index(DropPrevention.ID);
	private static final int COORDINATES = Mods.index(com.flintclient.client.control.model.ModuleId.COORDINATES);
	/** Minecraft's own hurt flash: red over 30% of the colour. */
	private static final int VANILLA_FLASH = 0xB2FF0000;

	private Mc263Tools() {
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	/** Each client tick: the mods' keys, the damage flash's colour, and keybinds Keybind Search pressed being let go. */
	public static void tick() {
		if (!GUARD.on()) {
			return;
		}
		try {
			// First: a keybind Keybind Search pressed is let go even when something after this fails.
			release();
			boolean playing = Mc263Features.gameplay();
			keys(playing);
			flash();
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			releaseAll();
		}
	}

	// ---------- keys ----------

	private static boolean potionHeld;
	private static boolean armorHeld;
	private static boolean searchHeld;
	private static boolean toggleHeld;
	private static boolean listHeld;
	private static boolean copyHeld;

	private static void keys(boolean playing) {
		if (Mods.on(ANIMATIONS)) {
			ModuleState s = Mods.state(ANIMATIONS);
			potionHeld = flip(playing, s, Animations.POTION_KEY, Animations.NO_POTION_PARTICLES, potionHeld, "Own potion particles hidden", "Own potion particles shown");
			armorHeld = flip(playing, s, Animations.ARMOR_KEY, Animations.ARMOR_HURT, armorHeld, "Armor hurt color on", "Armor hurt color off");
		}
		if (Mods.on(SEARCH)) {
			boolean down = playing && pressed(Mods.state(SEARCH), KeybindSearch.KEY);
			if (down && !searchHeld && mc().gui.screen() == null) {
				ModuleState s = Mods.state(SEARCH);
				mc().gui.setScreen(new com.flintclient.client.control.rshift.KeybindSearchScreen(s.integer(KeybindSearch.ROWS),
					s.bool(KeybindSearch.HIDE_BOUND)));
			}
			searchHeld = down;
		}
		if (Mods.on(DROP)) {
			ModuleState s = Mods.state(DROP);
			boolean toggle = playing && pressed(s, DropPrevention.TOGGLE_KEY);
			if (toggle && !toggleHeld) {
				toggleHeld();
			}
			toggleHeld = toggle;
			boolean list = playing && pressed(s, DropPrevention.LIST_KEY);
			if (list && !listHeld) {
				com.flintclient.client.control.rshift.ModMenuScreen.showModule(DropPrevention.ID);
				com.flintclient.client.control.ControlCenter.openWindow(null);
			}
			listHeld = list;
		}
		if (Mods.on(COORDINATES)) {
			boolean copy = playing && pressed(Mods.state(COORDINATES), com.flintclient.client.control.model.ModuleSettings.Coordinates.COPY_KEY);
			if (copy && !copyHeld && mc().player != null) {
				net.minecraft.core.BlockPos at = mc().player.blockPosition();
				String text = at.getX() + " " + at.getY() + " " + at.getZ();
				mc().keyboardHandler.setClipboard(text);
				com.flintclient.client.compat.McCompat.hud().setOverlayMessage(Component.literal("Copied " + text), false);
			}
			copyHeld = copy;
		}
	}

	private static boolean pressed(ModuleState s, KeySetting key) {
		String name = s.text(key);
		return !name.isEmpty() && Mc263Features.keyHeld(name);
	}

	/** A key that turns {@code setting} on and off (saved, and said above the hotbar). Returns whether it is held. */
	private static boolean flip(boolean playing, ModuleState s, KeySetting key, BoolSetting setting, boolean held, String on, String off) {
		boolean down = playing && pressed(s, key);
		if (down && !held) {
			boolean value = !s.bool(setting);
			if (s.set(setting, value)) {
				ControlState.settings().recordChange(s.id());
				ControlState.saveAll();
				com.flintclient.client.compat.McCompat.hud().setOverlayMessage(Component.literal(value ? on : off), false);
			}
		}
		return down;
	}

	// ---------- Animations ----------

	/** The direction your hurt tilt comes from: always the same with Disable hit direction. */
	public static float hitDirection(float direction) {
		return GUARD.on() && Mods.on(ANIMATIONS) && Mods.state(ANIMATIONS).bool(Animations.NO_HIT_DIRECTION) ? 0 : direction;
	}

	/** How many ticks {@code entity}'s arm swing takes: yours longer or shorter with Change swing duration. */
	public static int swing(LivingEntity entity, int ticks) {
		if (!GUARD.on() || entity != mc().player || !Mods.on(ANIMATIONS)) {
			return ticks;
		}
		ModuleState s = Mods.state(ANIMATIONS);
		return s.bool(Animations.SWING) ? Math.max(1, Math.round(ticks * s.decimal(Animations.SWING_MULTIPLIER))) : ticks;
	}

	/** Whether {@code entity}'s potion particles appear (yours don't in first person with Hide own potion particles). */
	public static boolean potionParticles(LivingEntity entity) {
		if (!GUARD.on() || entity != mc().player || !Mods.on(ANIMATIONS) || !Mods.state(ANIMATIONS).bool(Animations.NO_POTION_PARTICLES)) {
			return true;
		}
		return !mc().options.getCameraType().isFirstPerson();
	}

	/** Whether your hearts flash (they don't with Disable health flashing). */
	public static boolean healthBlink(boolean blinking) {
		return blinking && !(GUARD.on() && Mods.on(ANIMATIONS) && Mods.state(ANIMATIONS).bool(Animations.NO_HEALTH_FLASH));
	}

	/** The overlay armor is drawn with while {@link #beginArmor}'s wearer is drawn. */
	private static int armorOverlay = -1;

	/** Before a humanoid's armor is drawn: with Armor hurt color, it takes the wearer's red flash. */
	public static void beginArmor(HumanoidRenderState state) {
		armorOverlay = -1;
		if (GUARD.on() && state.hasRedOverlay && Mods.on(ANIMATIONS) && Mods.state(ANIMATIONS).bool(Animations.ARMOR_HURT)) {
			armorOverlay = OverlayTexture.pack(OverlayTexture.u(0), OverlayTexture.v(true));
		}
	}

	public static void endArmor() {
		armorOverlay = -1;
	}

	public static int armorOverlay(int original) {
		return armorOverlay == -1 ? original : armorOverlay;
	}

	private static int flashShown = VANILLA_FLASH;

	/** Keeps Minecraft's hurt flash (the red rows of its overlay texture) in Animations' colour, or Minecraft's own. */
	private static void flash() {
		int wanted = VANILLA_FLASH;
		if (Mods.on(ANIMATIONS)) {
			ModuleState s = Mods.state(ANIMATIONS);
			if (s.bool(Animations.CUSTOM_DAMAGE)) {
				// The overlay's alpha is how much of the original colour stays.
				int keep = Math.round(255 * (1 - s.integer(Animations.DAMAGE_STRENGTH) / 100.0F));
				wanted = keep << 24 | s.rgb(Animations.DAMAGE_COLOR);
			}
		}
		if (wanted == flashShown) {
			return;
		}
		DynamicTexture texture = ((com.flintclient.client.mixin.feature.OverlayTextureAccessor) mc().gameRenderer.overlayTexture()).flint$texture();
		NativeImage pixels = texture.getPixels();
		if (pixels == null) {
			return;
		}
		for (int y = 0; y < 8; y++) {
			for (int x = 0; x < 16; x++) {
				pixels.setPixel(x, y, wanted);
			}
		}
		texture.upload();
		flashShown = wanted;
	}

	// ---------- Custom Advancements Screen ----------

	private static final int ADVANCEMENTS = Mods.index(com.flintclient.client.control.model.mods.ToolMods.AdvancementsScreen.ID);

	/** The screen to open: Flint's advancements list in place of Minecraft's advancements screen while the mod is on. */
	public static net.minecraft.client.gui.screens.Screen screen(net.minecraft.client.gui.screens.Screen screen) {
		if (screen instanceof net.minecraft.client.gui.screens.advancements.AdvancementsScreen && GUARD.on() && Mods.on(ADVANCEMENTS)) {
			return new com.flintclient.client.control.rshift.AdvancementsListScreen();
		}
		return screen;
	}

	// ---------- Keybind Search ----------

	private static final List<KeyMapping> HELD = new ArrayList<>();
	private static final List<Integer> HELD_TICKS = new ArrayList<>();

	/** Presses {@code mapping} as a key would: down for two ticks, and one click for what counts clicks. */
	public static void press(KeyMapping mapping) {
		KeyMappingAccessor access = (KeyMappingAccessor) mapping;
		access.flint$setClickCount(access.flint$clickCount() + 1);
		mapping.setDown(true);
		HELD.add(mapping);
		HELD_TICKS.add(2);
	}

	/** Lets go of every keybind Keybind Search still holds (its path turned off: nothing would let go of them later). */
	public static void releaseAll() {
		for (int i = HELD.size() - 1; i >= 0; i--) {
			HELD.get(i).setDown(false);
		}
		HELD.clear();
		HELD_TICKS.clear();
	}

	private static void release() {
		for (int i = HELD.size() - 1; i >= 0; i--) {
			int left = HELD_TICKS.get(i) - 1;
			if (left <= 0) {
				HELD.get(i).setDown(false);
				HELD.remove(i);
				HELD_TICKS.remove(i);
			} else {
				HELD_TICKS.set(i, left);
			}
		}
	}

	// ---------- Drop Prevention ----------

	private static String id(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	/** The list the answers below were worked out for, and each kind of item's answer (nothing made per frame or per slot). */
	private static List<String> protectedFor = List.of();
	private static final java.util.Map<net.minecraft.world.item.Item, Boolean> PROTECTED = new java.util.IdentityHashMap<>();

	/** Whether Drop Prevention's list has {@code stack}'s kind of item. */
	private static boolean listed(List<String> items, ItemStack stack) {
		if (items != protectedFor) {
			protectedFor = items;
			PROTECTED.clear();
		}
		return PROTECTED.computeIfAbsent(stack.getItem(), item -> items.contains(id(stack)));
	}

	/** Whether Drop Prevention protects {@code stack}. */
	public static boolean protectedItem(ItemStack stack) {
		if (!GUARD.on() || stack.isEmpty() || !Mods.on(DROP)) {
			return false;
		}
		return listed(Mods.state(DROP).ids(DropPrevention.ITEMS), stack);
	}

	/** Whether Drop Prevention also guards open inventories. */
	public static boolean inInventories() {
		return GUARD.on() && Mods.on(DROP) && Mods.state(DROP).bool(DropPrevention.IN_INVENTORY);
	}

	private static void toggleHeld() {
		LocalPlayer player = mc().player;
		if (player == null || player.getMainHandItem().isEmpty()) {
			return;
		}
		ModuleState s = Mods.state(DROP);
		String id = id(player.getMainHandItem());
		List<String> items = new ArrayList<>(s.ids(DropPrevention.ITEMS));
		boolean add = !items.remove(id);
		if (add) {
			items.add(id);
		}
		if (s.set(DropPrevention.ITEMS, List.copyOf(items))) {
			ControlState.settings().recordChange(DropPrevention.ID);
			ControlState.saveAll();
			com.flintclient.client.compat.McCompat.hud().setOverlayMessage(Component.literal(player.getMainHandItem().getHoverName().getString() + (add ? " protected" : " no longer protected")),
				false);
		}
	}

	/** After the hotbar is drawn: a soft highlight on each slot holding a protected item. */
	public static void hotbar(GuiGraphicsExtractor graphics) {
		if (!GUARD.on() || !Mods.on(DROP)) {
			return;
		}
		try {
			LocalPlayer player = mc().player;
			ModuleState s = Mods.state(DROP);
			int strength = s.integer(DropPrevention.HIGHLIGHT_STRENGTH);
			if (player == null || strength == 0 || com.flintclient.client.compat.McCompat.hud().isHidden()) {
				return;
			}
			int color = Math.round(strength * 2.55F) << 24 | s.rgb(DropPrevention.HIGHLIGHT);
			List<String> items = s.ids(DropPrevention.ITEMS);
			int left = graphics.guiWidth() / 2 - 91;
			int top = graphics.guiHeight() - 22;
			for (int i = 0; i < 9; i++) {
				ItemStack stack = player.getInventory().getItem(i);
				if (!stack.isEmpty() && listed(items, stack)) {
					int x = left + 3 + i * 20;
					graphics.fill(x, top + 3, x + 16, top + 19, color);
				}
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	private static double scrolled;

	/** The hotbar slot a wheel notch moves to: it takes Scroll resistance notches to move off a protected item. */
	public static int scroll(int current, int next) {
		if (next == current || !GUARD.on() || !Mods.on(DROP)) {
			scrolled = 0;
			return next;
		}
		LocalPlayer player = mc().player;
		if (player == null || !protectedItem(player.getInventory().getItem(current))) {
			scrolled = 0;
			return next;
		}
		scrolled += 1;
		if (scrolled + 1e-6 >= Mods.state(DROP).decimal(DropPrevention.SCROLL)) {
			scrolled = 0;
			return next;
		}
		return current;
	}
}
