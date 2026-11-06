package com.flintclient.client.control.platform;

import org.jspecify.annotations.Nullable;

/**
 * A model part (Minecraft's {@code ModelPart}, through a mixin) that can draw something of Flint's in place of its own
 * boxes while one is set: 3D Skins' voxel layers, Wavy Capes' cloth. Set right before the part's model is drawn, null to
 * draw the boxes as usual. Render thread.
 */
public interface CustomPart {
	void flint$drawing(@Nullable PartDrawing drawing);

	@Nullable PartDrawing flint$drawing();
}
