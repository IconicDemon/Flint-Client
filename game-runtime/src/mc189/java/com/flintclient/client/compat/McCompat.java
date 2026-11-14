package com.flintclient.client.compat;

import java.nio.file.Path;
import org.lwjgl.Sys;

/**
 * Minecraft 1.8.9's answers to the shared model's version questions. The 26.x and 1.21.x lines each have a McCompat with
 * Minecraft calls; on 1.8.9 only the shared model and Flint's main menu are compiled (src/mc189/shared-sources.rules), so this
 * one holds the constants the model reads and the few calls the menu makes.
 */
public final class McCompat {
	/** 1.8.9 draws nametags as Minecraft always did: no shaped backgrounds (rounded corners, borders). */
	public static final boolean SHAPED_NAMETAGS = false;
	/** 1.8.9's item models have none of the 1.21.x gaps Model Gap Fix fills. */
	public static final boolean ITEM_MODEL_GAPS = false;
	/** Whether this Minecraft has an offhand (1.9 and later), and the items Armor's slots look for that 1.8.9 lacks: shields, tridents, maces. */
	public static final boolean OFFHAND = false;

	private McCompat() {
	}

	/** Minecraft's Options screen over {@code parent} (the main menu's gear). */
	public static net.minecraft.client.gui.screen.Screen optionsScreen(net.minecraft.client.gui.screen.Screen parent,
		com.flintclient.client.mc.Minecraft.Options options) {
		return new net.minecraft.client.gui.screen.menu.options.OptionsScreen(parent, options.game());
	}

	/** Opens a folder or file in the system's own app (Utilities' folders, a screenshot). */
	public static void openPath(Path path) {
		Sys.openURL(path.toUri().toString());
	}

	/** Opens a web address in the browser (the account card's sign-in page). */
	public static void openUri(String uri) {
		Sys.openURL(uri);
	}

	/** The player model the Discord card draws (made once). */
	private static net.minecraft.client.render.model.entity.@org.jspecify.annotations.Nullable PlayerModel previewModel;

	/**
	 * Draws the player the game plays as (their own skin) wearing {@code cape} as a whole 3D player in the pixel box ({@code x},
	 * {@code y}, {@code w}, {@code h}) of {@code frame}'s canvas, turned {@code yaw} degrees, its cape leaning {@code capeLean}
	 * degrees: Minecraft 1.8.9's own player model, drawn through the frame's game layer (as items are). False where the box is too
	 * small to draw in.
	 */
	public static boolean previewPlayer(com.flintclient.client.platform.Frame frame, com.flintclient.client.platform.Texture cape, int x, int y,
		int w, int h, float yaw, float capeLean) {
		if (w < 8 || h < 16) {
			return false;
		}
		net.minecraft.client.render.model.entity.PlayerModel model = previewModel;
		if (model == null) {
			model = new net.minecraft.client.render.model.entity.PlayerModel(0.0F, false);
			previewModel = model;
		}
		net.minecraft.resource.Identifier skin = com.flintclient.client.platform.Faces.ownSkin();
		// Pixels per block: the model stands two blocks tall, head to feet, in the box; its origin is at the neck.
		float s = h / 2.1F;
		float neckY = y + h - 1.5F * s - 0.03F * s;
		frame.pushGame();
		net.minecraft.client.render.platform.GlStateManager.pushMatrix();
		try {
			net.minecraft.client.render.platform.GlStateManager.translatef(x + w / 2.0F, neckY, 150.0F);
			// x mirrored as the game's own entity renderer mirrors it (the screen's y already points down, as the model's does).
			net.minecraft.client.render.platform.GlStateManager.scalef(-s, s, s);
			net.minecraft.client.render.platform.GlStateManager.rotatef(-6.0F, 1.0F, 0.0F, 0.0F);
			net.minecraft.client.render.platform.GlStateManager.rotatef(180.0F + yaw, 0.0F, 1.0F, 0.0F);
			net.minecraft.client.render.platform.GlStateManager.enableDepthTest();
			net.minecraft.client.render.platform.GlStateManager.depthMask(true);
			net.minecraft.client.render.platform.GlStateManager.disableCull();
			net.minecraft.client.render.platform.GlStateManager.enableAlphaTest();
			net.minecraft.client.render.platform.GlStateManager.alphaFunc(org.lwjgl.opengl.GL11.GL_GREATER, 0.1F);
			net.minecraft.client.render.platform.GlStateManager.color4f(1.0F, 1.0F, 1.0F, 1.0F);
			net.minecraft.client.Minecraft.getInstance().getTextureManager().bind(skin);
			float unit = 0.0625F;
			model.head.render(unit);
			model.body.render(unit);
			model.rightArm.render(unit);
			model.leftArm.render(unit);
			model.rightLeg.render(unit);
			model.leftLeg.render(unit);
			// The outer layer (hat, jacket, sleeves, trousers) over it.
			model.hat.render(unit);
			model.jacket.render(unit);
			model.rightSleeve.render(unit);
			model.leftSleeve.render(unit);
			model.rightPants.render(unit);
			model.leftPants.render(unit);
			// The cape, behind the back and leaning out a little, as the game's cape layer draws it.
			net.minecraft.client.render.platform.GlStateManager.bindTexture(cape.glId());
			net.minecraft.client.render.platform.GlStateManager.pushMatrix();
			net.minecraft.client.render.platform.GlStateManager.translatef(0.0F, 0.0F, 0.125F);
			net.minecraft.client.render.platform.GlStateManager.rotatef(6.0F + capeLean, 1.0F, 0.0F, 0.0F);
			net.minecraft.client.render.platform.GlStateManager.rotatef(180.0F, 0.0F, 1.0F, 0.0F);
			model.renderCape(unit);
			net.minecraft.client.render.platform.GlStateManager.popMatrix();
		} finally {
			net.minecraft.client.render.platform.GlStateManager.popMatrix();
			net.minecraft.client.render.platform.GlStateManager.disableDepthTest();
			net.minecraft.client.render.platform.GlStateManager.enableCull();
			frame.popGame();
		}
		return true;
	}
}
