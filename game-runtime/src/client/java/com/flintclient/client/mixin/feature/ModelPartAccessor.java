package com.flintclient.client.mixin.feature;

import java.util.List;
import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The maps' mob heads (control.platform.EntityIcons): a model part's boxes and its named children. */
@Mixin(ModelPart.class)
public interface ModelPartAccessor {
	@Accessor("cubes")
	List<ModelPart.Cube> flint$cubes();

	@Accessor("children")
	Map<String, ModelPart> flint$children();
}
