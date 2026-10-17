package com.flintclient.client.mixin.feature;

import java.util.List;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 3D Skins: a model part's boxes, read and swapped (the outer skin layers' flat boxes for per-skin voxels and back). */
@Mixin(ModelPart.class)
public interface ModelPartAccessor {
	@Accessor("cubes")
	List<ModelPart.Cube> flint$cubes();

	@Mutable
	@Accessor("cubes")
	void flint$setCubes(List<ModelPart.Cube> cubes);
}
