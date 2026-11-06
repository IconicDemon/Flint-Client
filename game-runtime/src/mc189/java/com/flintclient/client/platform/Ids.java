package com.flintclient.client.platform;

import com.flintclient.legacy.mixin.SoundManagerAccessor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sound.instance.SimpleSoundInstance;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.resource.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The game's own ids the menu's pickers offer (every sound, every item) and the sound preview, on Minecraft 1.8.9. Platform
 * class (see {@link Clock}). Render thread.
 */
public final class Ids {
	private Ids() {
	}

	/** Every sound's id ({@code minecraft:random.orb}). */
	public static List<String> sounds() {
		List<String> out = new ArrayList<>();
		for (Identifier id : ((SoundManagerAccessor) Minecraft.getInstance().getSoundManager()).flint$registry().keySet()) {
			out.add(id.toString());
		}
		return out;
	}

	/** Every item but air: its id and its name as the game shows it, in registry order. */
	public static Map<String, String> items() {
		Map<String, String> out = new LinkedHashMap<>();
		Map<String, Object> stacks = new HashMap<>();
		for (Identifier id : Item.REGISTRY.keySet()) {
			Item item = Item.REGISTRY.get(id);
			if (item == null || id.getPath().equals("air")) {
				continue;
			}
			ItemStack stack = new ItemStack(item);
			out.put(id.toString(), stack.getHoverName());
			stacks.put(id.toString(), stack);
		}
		pictures = stacks;
		return out;
	}

	/** The stacks {@link #items()} made, by id: the pictures the pickers draw. */
	private static Map<String, Object> pictures = Map.of();

	/** One of item {@code id} to draw (made by {@link #items()}); null before that, or for an id this version doesn't have. */
	public static @Nullable Object stack(String id) {
		return pictures.get(id);
	}

	/** Plays sound {@code id} once, as a menu sound (1.8.9's menu sounds play at one volume); nothing for an id that isn't one. */
	public static void playSound(String id, float volume) {
		if (id.isEmpty()) {
			return;
		}
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.of(new Identifier(id), 1.0F));
	}
}
