package com.flintclient.legacy.mods;

import com.flintclient.client.control.model.mods.HudMods;
import com.flintclient.legacy.module.Module;
import com.flintclient.legacy.module.Setting;
import java.util.Locale;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.render.platform.GLX;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.entity.PrimedTntEntity;
import org.lwjgl.opengl.GL11;

/**
 * TNT Countdown on 1.8.9: above each lit TNT, the fuse left in seconds, drawn in the world where a nametag would be and turned
 * to face you, as on every line (green to red as it runs out, or Time color with Static color on; the decimals and the shadow
 * are the shared settings). Being part of the world, it stays put above the block and gets smaller with distance as the block does; a faint
 * copy shows through walls, as a nametag's does. 1.8.9 counts the fuse down on this computer from the 4 seconds TNT always
 * starts with. Within 48 blocks. Drawn by PrimedTntRendererMixin, right after the block.
 */
public final class TntTimer extends Module {
	/** The size of a text pixel in blocks: a nametag's. */
	private static final float SCALE = 0.016666668F * 1.6F;

	public final Setting.Int decimals = integer(HudMods.TntCountdown.DECIMALS);
	public final Setting.Bool staticColor = bool(HudMods.TntCountdown.STATIC_COLOR);
	public final Setting.Color timeColor = color(HudMods.TntCountdown.TIME_COLOR);
	public final Setting.Bool shadow = bool(HudMods.TntCountdown.SHADOW);

	public TntTimer() {
		super(HudMods.TntCountdown.ID, "tnttimer");
	}

	/** The timer above {@code tnt}, drawn at {@code x, y, z} (relative to the camera, as its renderer was given). */
	public void draw(PrimedTntEntity tnt, double x, double y, double z, float tickDelta, EntityRenderDispatcher dispatcher, TextRenderer font) {
		if (!enabled() || dispatcher.camera == null || tnt.squaredDistanceTo(dispatcher.camera) > 48 * 48) {
			return;
		}
		float seconds = Math.max(0, (tnt.fuseTimer - tickDelta) / 20.0F);
		String text = String.format(Locale.ROOT, "%." + Math.max(0, Math.min(2, decimals.value())) + "f", seconds);
		int color;
		if (staticColor.on()) {
			color = 0xFF000000 | timeColor.rgb();
		} else {
			float k = Math.max(0, Math.min(1, seconds / 4.0F));
			int r = Math.round(255 * Math.min(1, 2 * (1 - k)));
			int g = Math.round(255 * Math.min(1, 2 * k));
			color = 0xFF000000 | r << 16 | g << 8 | 0x40;
		}
		int left = -font.getWidth(text) / 2;
		GlStateManager.pushMatrix();
		try {
			GlStateManager.translatef((float) x, (float) y + tnt.height + 0.5F, (float) z);
			GL11.glNormal3f(0.0F, 1.0F, 0.0F);
			GlStateManager.rotatef(-dispatcher.cameraYaw, 0.0F, 1.0F, 0.0F);
			GlStateManager.rotatef(dispatcher.cameraPitch, 1.0F, 0.0F, 0.0F);
			GlStateManager.scalef(-SCALE, -SCALE, SCALE);
			// Full light, so it reads the same by night.
			GLX.multiTexCoord2f(GLX.GL_TEXTURE1, 240.0F, 240.0F);
			GlStateManager.disableLighting();
			GlStateManager.depthMask(false);
			GlStateManager.disableDepthTest();
			GlStateManager.enableBlend();
			GlStateManager.blendFuncSeparate(770, 771, 1, 0);
			font.draw(text, left, -4, color & 0x00FFFFFF | 0x40000000);
			GlStateManager.enableDepthTest();
			GlStateManager.depthMask(true);
			font.draw(text, left, -4, color, shadow.on());
		} finally {
			GlStateManager.enableDepthTest();
			GlStateManager.depthMask(true);
			GlStateManager.enableLighting();
			GlStateManager.disableBlend();
			GlStateManager.color4f(1.0F, 1.0F, 1.0F, 1.0F);
			GlStateManager.popMatrix();
		}
	}
}
