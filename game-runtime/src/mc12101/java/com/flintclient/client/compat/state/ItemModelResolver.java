package com.flintclient.client.compat.state;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** Picks the model an item is drawn with (1.21.4's {@code ItemModelResolver}): on 1.21.1 the item renderer's baked model. */
public final class ItemModelResolver {
	public static final ItemModelResolver INSTANCE = new ItemModelResolver();

	private ItemModelResolver() {
	}

	public void updateForNonLiving(ItemStackRenderState into, ItemStack stack, ItemDisplayContext context, Entity entity) {
		into.stack = stack;
		into.context = context;
		into.model = stack.isEmpty() ? null
			: Minecraft.getInstance().getItemRenderer().getModel(stack, entity.level(), entity instanceof LivingEntity living ? living : null, entity.getId());
	}
}
