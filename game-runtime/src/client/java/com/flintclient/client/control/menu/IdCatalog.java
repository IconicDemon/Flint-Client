package com.flintclient.client.control.menu;

import com.flintclient.client.control.model.IdKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * What the list picker offers on Minecraft 26.3: every sound event and every item the game knows (from its registries,
 * so a resource pack's or another mod's are included), with readable names. Built once, when first asked for; sounds are
 * previewed through Minecraft's own sound manager as a menu sound.
 */
final class IdCatalog {
	private static @Nullable List<ListPicker.Item> sounds;
	private static @Nullable List<ListPicker.Item> items;

	private IdCatalog() {
	}

	static List<ListPicker.Item> of(IdKind kind) {
		return kind == IdKind.SOUND ? sounds() : items();
	}

	private static List<ListPicker.Item> sounds() {
		List<ListPicker.Item> list = sounds;
		if (list == null) {
			list = new ArrayList<>();
			for (Identifier id : BuiltInRegistries.SOUND_EVENT.keySet()) {
				list.add(new ListPicker.Item(id.toString(), soundName(id.getPath()), id.toString()));
			}
			list.sort(Comparator.comparing(ListPicker.Item::id));
			sounds = List.copyOf(list);
			list = sounds;
		}
		return list;
	}

	private static List<ListPicker.Item> items() {
		List<ListPicker.Item> list = items;
		if (list == null) {
			list = new ArrayList<>();
			for (Item item : BuiltInRegistries.ITEM) {
				Identifier id = BuiltInRegistries.ITEM.getKey(item);
				if (id.getPath().equals("air")) {
					continue;
				}
				list.add(new ListPicker.Item(id.toString(), new ItemStack(item).getHoverName().getString(), id.toString()));
			}
			list.sort(Comparator.comparing(ListPicker.Item::label, String.CASE_INSENSITIVE_ORDER));
			items = List.copyOf(list);
			list = items;
		}
		return list;
	}

	/** "entity.experience_orb.pickup" → "Entity · Experience orb · Pickup". */
	static String soundName(String path) {
		String[] parts = path.split("\\.");
		StringBuilder out = new StringBuilder();
		for (String part : parts) {
			if (part.isEmpty()) {
				continue;
			}
			if (!out.isEmpty()) {
				out.append(" · ");
			}
			String words = part.replace('_', ' ');
			out.append(Character.toUpperCase(words.charAt(0))).append(words.substring(1));
		}
		return out.toString();
	}

	/** The name shown for {@code id} ("None" for empty, the raw id when this version doesn't have it). */
	static String name(IdKind kind, String id) {
		if (id.isEmpty()) {
			return "None";
		}
		for (ListPicker.Item item : of(kind)) {
			if (item.id().equals(id)) {
				return item.label();
			}
		}
		return id;
	}

	/** Plays sound {@code id} once, as a menu sound at {@code volume} (0 to 1). */
	static void preview(String id, float volume) {
		Identifier location = Identifier.tryParse(id);
		if (location == null) {
			return;
		}
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(location), 1.0F,
			Math.max(0.05F, volume)));
	}

	static String lower(String text) {
		return text.toLowerCase(Locale.ROOT);
	}
}
