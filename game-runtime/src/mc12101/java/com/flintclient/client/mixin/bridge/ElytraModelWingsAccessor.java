package com.flintclient.client.mixin.bridge;

import net.minecraft.client.model.ElytraModel;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The elytra model's wing parts, private on Minecraft 1.21.1 (the cape-derived elytra draws its wings there, WingsLayerMixin). */
@Mixin(ElytraModel.class)
public interface ElytraModelWingsAccessor {
	@Accessor("leftWing")
	ModelPart flint$leftWing();

	@Accessor("rightWing")
	ModelPart flint$rightWing();
}
