package com.flintclient.client.mixin.cosmetics;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Minecraft's own way to make a render type, for the 3D cosmetics' blended faces put in order by Flint ({@code cosmetics/platform/SortedFaces}). */
@Mixin(RenderType.class)
public interface RenderTypeCosmeticsInvoker {
	@Invoker("create")
	static RenderType flint$create(String name, RenderSetup setup) {
		throw new AssertionError();
	}
}
