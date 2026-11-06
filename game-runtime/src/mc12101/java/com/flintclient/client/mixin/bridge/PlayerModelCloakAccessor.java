package com.flintclient.client.mixin.bridge;

import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The player model's cloak part, private on Minecraft 1.21.1 (Wavy Capes draws its cloth there, PlayerCapeModelMixin). */
@Mixin(PlayerModel.class)
public interface PlayerModelCloakAccessor {
	@Accessor("cloak")
	ModelPart flint$cloak();
}
