package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Skins;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.SkullModelBase;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.SkullBlockRenderer;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.SkullBlock;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 3D Skins on player heads (placed, worn and held) on Minecraft 1.19.2: every player head's look comes from one skin render
 * type, so each is remembered with its skin, and a player head is drawn with a head model whose hat layer is that skin's
 * voxels. 1.19.2 picks the type from the head's game profile (getRenderType) and draws it in renderSkull. Its skin manager
 * hashes a skin's name each time it is asked for the texture, so each render type is looked up once (Minecraft keeps one per
 * skin texture).
 */
@Mixin(SkullBlockRenderer.class)
abstract class SkullBlockRendererMixin {
	/** The player-head render types already handed to Mc263Skins (weakly, as Mc263Skins keeps them). */
	@Unique
	private static final Set<RenderType> FLINT$SEEN = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

	@Inject(method = "getRenderType", at = @At("RETURN"))
	private static void flint$skin(SkullBlock.Type type, @Nullable GameProfile profile, CallbackInfoReturnable<RenderType> callback) {
		RenderType renderType = callback.getReturnValue();
		if (type == SkullBlock.Types.PLAYER && profile != null && FLINT$SEEN.add(renderType)) {
			Mc263Skins.headSkin(renderType, Minecraft.getInstance().getSkinManager().getInsecureSkinLocation(profile));
		}
	}

	@Inject(method = "renderSkull", at = @At("HEAD"), cancellable = true)
	private static void flint$voxelHead(@Nullable Direction direction, float rotation, float animation, PoseStack pose, MultiBufferSource buffers,
		int light, SkullModelBase model, RenderType type, CallbackInfo callback) {
		SkullModelBase head = Mc263Skins.head(model, type, pose);
		if (head != null) {
			callback.cancel();
			SkullBlockRenderer.renderSkull(direction, rotation, animation, pose, buffers, light, head, type);
		}
	}
}
