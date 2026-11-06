package com.flintclient.client.mixin.bridge;

import net.minecraft.client.model.AgeableListModel;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Minecraft 1.21.1's four-legged animals keep their heads behind headParts: the maps' mob heads (EntityIcons) ask for them. */
@Mixin(AgeableListModel.class)
public interface AgeableListModelInvoker {
	@Invoker("headParts")
	Iterable<ModelPart> flint$headParts();

	@Invoker("bodyParts")
	Iterable<ModelPart> flint$bodyParts();
}
