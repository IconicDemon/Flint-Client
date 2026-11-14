package com.flintclient.client.compat.item;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.TippedArrowItem;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import org.jspecify.annotations.Nullable;

/**
 * 1.20.5's potion contents over Minecraft 1.19.2's potion NBT (Potion, CustomPotionColor, CustomPotionEffects): the potion
 * (by its registry holder; none for 1.19.2's "empty" potion), a colour of its own and extra effects. Potions, splash and
 * lingering potions and tipped arrows have it (empty when their NBT names nothing), as in 1.20.5, and so does any item whose
 * NBT names a potion.
 */
public record PotionContents(Optional<Holder<Potion>> potion, Optional<Integer> customColor, List<MobEffectInstance> customEffects) {
	public static final PotionContents EMPTY = new PotionContents(Optional.empty(), Optional.empty(), List.of());

	public PotionContents(Holder<Potion> potion) {
		this(Optional.of(potion), Optional.empty(), List.of());
	}

	/** A potion item ({@code Items.SPLASH_POTION}, say) of {@code potion}. */
	public static ItemStack createItemStack(Item item, Holder<Potion> potion) {
		return createItemStack(item, potion.value());
	}

	/** The same, with the potion as 1.19.2's Potions names it. */
	public static ItemStack createItemStack(Item item, Potion potion) {
		return PotionUtils.setPotion(new ItemStack(item), potion);
	}

	/** A potion's registry holder (a free-standing one for a potion the registry doesn't know). */
	public static Holder<Potion> holder(Potion potion) {
		return Registry.POTION.getResourceKey(potion).flatMap(Registry.POTION::getHolder).orElseGet(() -> Holder.direct(potion));
	}

	/** A potion holder's registry name ("minecraft:long_swiftness"), as 1.20.5's Holder.getRegisteredName gives it. */
	public static String registeredName(Holder<Potion> potion) {
		return potion.unwrapKey().map(key -> key.location().toString()).orElse("[unregistered]");
	}

	public boolean is(Holder<Potion> other) {
		return potion.isPresent() && potion.get().value() == other.value();
	}

	public boolean is(Potion other) {
		return potion.isPresent() && potion.get().value() == other;
	}

	/** The potion's effects, then the extra ones. */
	public List<MobEffectInstance> getAllEffects() {
		List<MobEffectInstance> all = new ArrayList<>();
		potion.ifPresent(p -> all.addAll(p.value().getEffects()));
		all.addAll(customEffects);
		return all;
	}

	public boolean hasEffects() {
		return !customEffects.isEmpty() || potion.isPresent() && !potion.get().value().getEffects().isEmpty();
	}

	/** The colour it's drawn in (ARGB): its own, else its effects' mixed (1.19.2's mix). */
	public int getColor() {
		return customColor.isPresent() ? customColor.get() : 0xFF000000 | PotionUtils.getColor(getAllEffects());
	}

	public PotionContents withPotion(Holder<Potion> other) {
		return new PotionContents(Optional.of(other), customColor, customEffects);
	}

	public PotionContents withEffectAdded(MobEffectInstance effect) {
		List<MobEffectInstance> effects = new ArrayList<>(customEffects);
		effects.add(effect);
		return new PotionContents(potion, customColor, List.copyOf(effects));
	}

	static boolean present(ItemStack stack) {
		Item item = stack.getItem();
		if (item instanceof PotionItem || item instanceof TippedArrowItem) {
			return true;
		}
		CompoundTag tag = stack.getTag();
		return tag != null && (tag.contains(PotionUtils.TAG_POTION, Tag.TAG_STRING) || tag.contains(PotionUtils.TAG_CUSTOM_POTION_EFFECTS, Tag.TAG_LIST)
			|| tag.contains(PotionUtils.TAG_CUSTOM_POTION_COLOR, Tag.TAG_ANY_NUMERIC));
	}

	static @Nullable PotionContents read(ItemStack stack) {
		if (!present(stack)) {
			return null;
		}
		CompoundTag tag = stack.getTag();
		if (tag == null) {
			return EMPTY;
		}
		Potion potion = PotionUtils.getPotion(tag);
		Optional<Integer> color = tag.contains(PotionUtils.TAG_CUSTOM_POTION_COLOR, Tag.TAG_ANY_NUMERIC)
			? Optional.of(tag.getInt(PotionUtils.TAG_CUSTOM_POTION_COLOR)) : Optional.empty();
		return new PotionContents(potion == Potions.EMPTY ? Optional.empty() : Optional.of(holder(potion)), color,
			List.copyOf(PotionUtils.getCustomEffects(tag)));
	}

	/**
	 * Writes {@code contents} into the stack's NBT (null clears it). Without a potion and a colour of its own, extra effects
	 * get their mixed colour written too: 1.19.2 draws a potion naming no potion pink, 1.20.5 in its effects' colour.
	 */
	static void write(ItemStack stack, @Nullable PotionContents contents) {
		CompoundTag tag = stack.getTag();
		if (tag != null) {
			tag.remove(PotionUtils.TAG_POTION);
			tag.remove(PotionUtils.TAG_CUSTOM_POTION_EFFECTS);
			tag.remove(PotionUtils.TAG_CUSTOM_POTION_COLOR);
		}
		if (contents == null) {
			return;
		}
		contents.potion.ifPresent(p -> PotionUtils.setPotion(stack, p.value()));
		PotionUtils.setCustomEffects(stack, contents.customEffects);
		if (contents.customColor.isPresent() || contents.potion.isEmpty() && !contents.customEffects.isEmpty()) {
			stack.getOrCreateTag().putInt(PotionUtils.TAG_CUSTOM_POTION_COLOR, contents.getColor() & 0xFFFFFF);
		}
	}
}
