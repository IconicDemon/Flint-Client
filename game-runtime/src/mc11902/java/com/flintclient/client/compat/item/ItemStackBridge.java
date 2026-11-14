package com.flintclient.client.compat.item;

import java.util.function.BiConsumer;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * ItemStack: 1.20.5's component getters ({@link DataComponents}, read from 1.19.2's item and NBT) and the few stack methods
 * 1.20 to 1.20.5 added that the shared sources call. Loom adds this interface to Minecraft's ItemStack at compile time
 * ({@code src/mc11902/loom/fabric.mod.json}) and {@code mixin.bridge.ItemStackBridgeMixin} at run time.
 */
public interface ItemStackBridge {
	/** The component's value, or null when the stack has none. */
	default <T> @Nullable T get(DataComponentType<? extends T> type) {
		return type.read((ItemStack) (Object) this);
	}

	default boolean has(DataComponentType<?> type) {
		return type.isPresent((ItemStack) (Object) this);
	}

	default <T> T getOrDefault(DataComponentType<? extends T> type, T fallback) {
		T value = type.read((ItemStack) (Object) this);
		return value != null ? value : fallback;
	}

	/** Gives the stack the component (null takes it away); returns what it had. Only the components Flint sets can be set. */
	@SuppressWarnings("unchecked")
	default <T> @Nullable T set(DataComponentType<? super T> type, @Nullable T value) {
		ItemStack stack = (ItemStack) (Object) this;
		Object had = type.read(stack);
		type.write(stack, value);
		return (T) had;
	}

	/** The stack's own enchantments (not a book's stored ones). */
	default ItemEnchantments getEnchantments() {
		return getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
	}

	/** A copy holding {@code count} (1.20's). */
	default ItemStack copyWithCount(int count) {
		ItemStack stack = (ItemStack) (Object) this;
		if (stack.isEmpty()) {
			return ItemStack.EMPTY;
		}
		ItemStack copy = stack.copy();
		copy.setCount(count);
		return copy;
	}

	/** How long using it takes (1.20.5 asks with the user; 1.19.2's item decides from the stack alone). */
	default int getUseDuration(LivingEntity user) {
		return ((ItemStack) (Object) this).getUseDuration();
	}

	/**
	 * Each attribute modifier the stack gives in {@code slot}, with its attribute's registry holder (1.20.5's attribute
	 * modifiers component; 1.19.2 reads the stack's AttributeModifiers NBT, else its item's own).
	 */
	default void forEachModifier(EquipmentSlot slot, BiConsumer<Holder<Attribute>, AttributeModifier> action) {
		((ItemStack) (Object) this).getAttributeModifiers(slot).forEach((attribute, modifier) -> action.accept(ItemCompat.holder(attribute), modifier));
	}
}
