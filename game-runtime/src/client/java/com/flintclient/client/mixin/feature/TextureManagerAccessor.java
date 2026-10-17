package com.flintclient.client.mixin.feature;

import java.util.Map;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 3D Skins: the textures already registered, looked up without loading anything (getTexture would load a missing one). */
@Mixin(TextureManager.class)
public interface TextureManagerAccessor {
	@Accessor("byPath")
	Map<Identifier, AbstractTexture> flint$byPath();
}
