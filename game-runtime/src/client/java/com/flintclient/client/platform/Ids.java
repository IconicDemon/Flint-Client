package com.flintclient.client.platform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * The game's own ids the menu's pickers offer (every sound, every item) and the sound preview. The lists differ per
 * Minecraft version by nature. Platform class (see {@link Clock}). Render thread.
 */
public final class Ids {
	private Ids() {
	}

	/** Every sound event's id ({@code minecraft:block.note_block.bell}). */
	public static List<String> sounds() {
		List<String> out = new ArrayList<>();
		for (Identifier id : BuiltInRegistries.SOUND_EVENT.keySet()) {
			out.add(id.toString());
		}
		return out;
	}

	/**
	 * Every item but air: its id and its name (its translated name, in registry order). Made without item stacks: on 26.x
	 * the game can't make one before a world's data is loaded (an item's components aren't bound yet), and the menu opens
	 * from the title screen too.
	 */
	public static Map<String, String> items() {
		Map<String, String> out = new LinkedHashMap<>();
		Map<String, Item> byId = new HashMap<>();
		Language language = Language.getInstance();
		for (Item item : BuiltInRegistries.ITEM) {
			Identifier id = BuiltInRegistries.ITEM.getKey(item);
			if (id.getPath().equals("air")) {
				continue;
			}
			out.put(id.toString(), language.getOrDefault(item.getDescriptionId()));
			byId.put(id.toString(), item);
		}
		listed = byId;
		return out;
	}

	/** The items {@link #items()} listed, by id. */
	private static Map<String, Item> listed = Map.of();
	/** The pictures made so far, by id (dropped whenever stacks can't be made). */
	private static final Map<String, Object> PICTURES = new HashMap<>();
	/** Whether the game can make item stacks now, and when that is asked again (at most twice a second). */
	private static boolean stacksWork;
	private static boolean asked;
	private static long askAgainAt;

	/**
	 * One of item {@code id} to draw, made once and kept; null while the game can't make item stacks (26.x before a world's
	 * data is loaded), or for an id this version doesn't have. Render thread.
	 */
	public static @Nullable Object stack(String id) {
		long now = System.nanoTime();
		if (!asked || now - askAgainAt >= 0) {
			asked = true;
			askAgainAt = now + 500_000_000L;
			stacksWork = canMakeStacks();
			if (!stacksWork) {
				PICTURES.clear();
			}
		}
		if (!stacksWork) {
			return null;
		}
		Object picture = PICTURES.get(id);
		if (picture == null) {
			Item item = listed.get(id);
			if (item == null) {
				return null;
			}
			try {
				picture = new ItemStack(item);
			} catch (RuntimeException e) {
				stacksWork = false;
				PICTURES.clear();
				return null;
			}
			PICTURES.put(id, picture);
		}
		return picture;
	}

	private static boolean canMakeStacks() {
		try {
			return !new ItemStack(Items.STONE).isEmpty();
		} catch (RuntimeException e) {
			return false;
		}
	}

	/** Plays sound {@code id} once, as a menu sound at {@code volume} (0 to 1); nothing for an id that isn't one. */
	public static void playSound(String id, float volume) {
		Identifier location = Identifier.tryParse(id);
		if (location == null) {
			return;
		}
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(location), 1.0F,
			Math.max(0.05F, volume)));
	}
}
