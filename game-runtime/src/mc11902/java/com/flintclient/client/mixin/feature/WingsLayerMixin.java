package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.PlayerSkin;
import com.flintclient.client.compat.render.SkinBridge;
import com.flintclient.client.control.feature.CapeElytra;
import com.flintclient.client.control.platform.Mc263CapeElytra;
import com.flintclient.client.control.platform.Mc263StoreCapes;
import com.flintclient.client.mixin.bridge.ElytraModelWingsAccessor;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.ElytraModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ElytraLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft 1.19.2's elytra layer (it picks the texture, sets the model up and draws it as it renders), for Flint Store capes.
 * <ul>
 * <li>A player who wears a Store cape and an elytra (with "Cape design on elytra" on; {@link CapeElytra#use}) gets the
 * cape-derived elytra ({@link Mc263CapeElytra}): for this draw the elytra model's wing parts draw Minecraft's own wing boxes cut
 * to its elytra's silhouette, with the cape's texture, in Minecraft's own elytra pass (armorCutoutNoCull; the glint, when
 * enchanted, stays Minecraft's foil buffer over it).</li>
 * <li>Everyone else: Minecraft's elytra and its texture rule (the skin's elytra texture, else its cape while shown, else the
 * elytra's), except that a Flint Store cape without elytra art isn't put on Minecraft's wings (they would be empty); Minecraft
 * decides with the cape it gave the wearer instead ({@link Mc263StoreCapes#wingsCape}).</li>
 * </ul>
 * 1.19.2 asks the player for its cape (getCloakTextureLocation, which PlayerInfoMixin answers from the skin with the Store cape)
 * where later versions read the skin's: those calls are the ones wrapped. Cape mods also decide whether the wings may wear a
 * cape at all (the Capes mod lets only a cape with elytra art of its own on them, by its elytra option): for a player wearing a
 * Store cape Flint decides that last too, as Minecraft does (the cape they show is the Store cape), so this applies after them.
 */
@Mixin(value = ElytraLayer.class, priority = 1100)
abstract class WingsLayerMixin {
	@Unique
	private static final String RENDER = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V";

	@Shadow
	@Final
	private ElytraModel<?> elytraModel;
	@Unique
	private boolean flint$capeWings;

	@Inject(method = RENDER, at = @At("HEAD"))
	private void flint$capeWingsBegin(PoseStack poseStack, MultiBufferSource buffers, int light, LivingEntity entity, float limbSwing,
		float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo callback) {
		boolean on = false;
		try {
			on = capeWings(entity);
		} catch (RuntimeException | LinkageError e) {
			Mc263CapeElytra.failed(e);
		}
		flint$capeWings = on;
		if (on) {
			ElytraModelWingsAccessor wings = (ElytraModelWingsAccessor) elytraModel;
			Mc263CapeElytra.setup(wings.flint$leftWing(), wings.flint$rightWing(), true);
		}
	}

	@Inject(method = RENDER, at = @At("RETURN"))
	private void flint$capeWingsEnd(PoseStack poseStack, MultiBufferSource buffers, int light, LivingEntity entity, float limbSwing,
		float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo callback) {
		if (flint$capeWings) {
			flint$capeWings = false;
			ElytraModelWingsAccessor wings = (ElytraModelWingsAccessor) elytraModel;
			Mc263CapeElytra.setup(wings.flint$leftWing(), wings.flint$rightWing(), false);
		}
	}

	/** Whether {@code entity}'s elytra is drawn as their Flint Store cape (the layer still draws only an elytra it would draw). */
	@Unique
	private static boolean capeWings(LivingEntity entity) {
		if (!(entity instanceof AbstractClientPlayer player) || !player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
			return false;
		}
		PlayerSkin skin = ((SkinBridge) player).getSkin();
		return CapeElytra.use(Mc263CapeElytra.enabled(), Mc263StoreCapes.storeCape(skin) != null, player.isModelPartShown(PlayerModelPart.CAPE),
			skin.elytraTexture() != null, true);
	}

	/**
	 * Whether the wings may wear the player's cape: Minecraft's answer (and the cape mods', which wrap it) for everyone but a
	 * player wearing a Flint Store cape, whose wings follow Minecraft's rule with that cape.
	 */
	@WrapOperation(method = RENDER, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/AbstractClientPlayer;isCapeLoaded()Z"))
	private boolean flint$wingsWearCape(AbstractClientPlayer player, Operation<Boolean> original) {
		boolean mods = original.call(player);
		return !mods && Mc263StoreCapes.storeCape(((SkinBridge) player).getSkin()) != null ? player.isCapeLoaded() : mods;
	}

	@WrapOperation(method = RENDER, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/player/AbstractClientPlayer;getCloakTextureLocation()Lnet/minecraft/resources/ResourceLocation;"))
	private @Nullable ResourceLocation flint$wingsCape(AbstractClientPlayer player, Operation<ResourceLocation> original) {
		// The cape-derived wings are the Store cape itself (the skin's cape); otherwise Minecraft's wings, with the one exception.
		return flint$capeWings ? original.call(player) : Mc263StoreCapes.wingsCape(((SkinBridge) player).getSkin(), original.call(player));
	}
}
