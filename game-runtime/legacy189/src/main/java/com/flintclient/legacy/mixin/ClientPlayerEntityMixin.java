package com.flintclient.legacy.mixin;

import com.flintclient.legacy.StoreCapes189;
import net.minecraft.client.entity.living.player.ClientPlayerEntity;
import net.minecraft.resource.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The cape a player's cape layer draws: the one they equipped in the Flint Store, when the Store says they wear one
 * ({@link StoreCapes189}); otherwise Minecraft's. The layer still checks the wearer's own "Cape" skin part first.
 */
@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerEntityMixin {
	@Inject(method = "getCapeTextureLocation", at = @At("RETURN"), cancellable = true)
	private void flint$storeCape(CallbackInfoReturnable<Identifier> callback) {
		Identifier store = StoreCapes189.cape((ClientPlayerEntity) (Object) this);
		if (store != null) {
			callback.setReturnValue(store);
		}
	}
}
