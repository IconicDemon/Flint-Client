package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.feature.CapeElytra;
import com.flintclient.client.control.platform.CapeWingsState;
import com.flintclient.client.control.platform.Mc263CapeElytra;
import com.flintclient.client.control.platform.Mc263StoreCapes;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.object.equipment.ElytraModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.WingsLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.core.ClientAsset;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.equipment.EquipmentAssets;
import net.minecraft.world.item.equipment.Equippable;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The elytra layer, for Flint Store capes.
 * <ul>
 * <li>A player who wears a Store cape and the vanilla elytra (with "Cape design on elytra" on; {@link CapeElytra#use}) gets the
 * cape-derived elytra ({@link Mc263CapeElytra}): the elytra model with the cape's texture, in a back-face-culled cut-out pass,
 * plus the glint pass when the elytra is enchanted, exactly where Minecraft draws its own (0.125 behind the body, the glowing
 * outline included). The render state remembers it ({@link CapeWingsState}) so the elytra model draws the cape's wing mesh
 * instead of its boxes ({@code ElytraModelMixin}).</li>
 * <li>Everyone else: Minecraft's elytra and its texture rule (the skin's elytra texture, else its cape while shown, else the
 * elytra's), with one exception: a Flint Store cape without elytra art isn't put on Minecraft's wings (they would be empty);
 * Minecraft decides with the cape it gave the wearer instead ({@link Mc263StoreCapes#wingsCape}).</li>
 * </ul>
 */
@Mixin(WingsLayer.class)
abstract class WingsLayerMixin {
	@Shadow
	@Final
	private ElytraModel elytraModel;
	@Shadow
	@Final
	private ElytraModel elytraBabyModel;

	@Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;FF)V",
		at = @At("HEAD"), cancellable = true)
	private void flint$capeWings(PoseStack poseStack, SubmitNodeCollector collector, int light, HumanoidRenderState state, float yRot, float xRot,
		CallbackInfo callback) {
		Identifier cape = null;
		try {
			cape = capeWings(state);
		} catch (RuntimeException | LinkageError e) {
			Mc263CapeElytra.failed(e);
		}
		((CapeWingsState) state).flint$capeWings(cape != null);
		if (cape == null) {
			return;
		}
		callback.cancel();
		poseStack.pushPose();
		poseStack.translate(0.0F, 0.0F, 0.125F);
		com.flintclient.client.compat.McCompat.submitCapeWings(collector, state.isBaby ? elytraBabyModel : elytraModel, state, poseStack, cape, light,
			state.outlineColor, state.chestEquipment.hasFoil());
		poseStack.popPose();
	}

	/** The Store cape texture {@code state}'s wings are made of, or null for Minecraft's elytra. */
	private static @Nullable Identifier capeWings(HumanoidRenderState state) {
		if (!(state instanceof AvatarRenderState avatar)) {
			return null;
		}
		Equippable equippable = state.chestEquipment.get(DataComponents.EQUIPPABLE);
		boolean vanillaElytra = equippable != null && equippable.assetId().isPresent() && equippable.assetId().get().equals(EquipmentAssets.ELYTRA);
		Identifier cape = vanillaElytra ? Mc263StoreCapes.storeCape(avatar.skin) : null;
		boolean use = CapeElytra.use(Mc263CapeElytra.enabled(), cape != null, avatar.showCape, avatar.skin.elytra() != null, vanillaElytra);
		return use ? cape : null;
	}

	@WrapOperation(method = "getPlayerElytraTexture", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/entity/player/PlayerSkin;cape()Lnet/minecraft/core/ClientAsset$Texture;"))
	private static ClientAsset.@Nullable Texture flint$wingsCape(PlayerSkin skin, Operation<ClientAsset.Texture> original) {
		return Mc263StoreCapes.wingsCape(skin, original.call(skin));
	}
}
