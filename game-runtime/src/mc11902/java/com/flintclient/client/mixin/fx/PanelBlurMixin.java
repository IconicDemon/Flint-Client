package com.flintclient.client.mixin.fx;

import java.util.List;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The blur's passes, for its strength (control/fx/PanelBlur): 1.19's post chains have no setUniform (1.20.5 added it with the
 * menu blur), so each pass's Radius is set on its own.
 */
@Mixin(PostChain.class)
public interface PanelBlurMixin {
	@Accessor("passes")
	List<PostPass> flint$passes();
}
