package com.flintclient.client.compat.item;

import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * One of 1.20.5's item data components as Minecraft 1.19.2 keeps it: read from (and, for the few Flint sets, written to) the
 * stack's item and NBT. An empty stack has none, as 1.20.5's empty stack has none. The types are {@link DataComponents}'.
 */
public final class DataComponentType<T> {
	private final String name;
	private final Function<ItemStack, @Nullable T> reader;
	private final @Nullable Predicate<ItemStack> present;
	private final @Nullable BiConsumer<ItemStack, @Nullable T> writer;

	DataComponentType(String name, Function<ItemStack, @Nullable T> reader, @Nullable Predicate<ItemStack> present,
		@Nullable BiConsumer<ItemStack, @Nullable T> writer) {
		this.name = name;
		this.reader = reader;
		this.present = present;
		this.writer = writer;
	}

	/** The component's value on {@code stack}, or null when it has none. */
	public @Nullable T read(ItemStack stack) {
		return stack.isEmpty() ? null : reader.apply(stack);
	}

	/** Whether {@code stack} has the component (without reading all of it where a cheaper test says so). */
	public boolean isPresent(ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}
		return present != null ? present.test(stack) : reader.apply(stack) != null;
	}

	/** Gives {@code stack} the component (null takes it away). */
	public void write(ItemStack stack, @Nullable T value) {
		if (writer == null) {
			throw new UnsupportedOperationException("Flint can't set the " + this + " component on Minecraft 1.19.2");
		}
		writer.accept(stack, value);
	}

	@Override
	public String toString() {
		return "minecraft:" + name;
	}
}
