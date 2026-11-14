package com.flintclient.legacy.cosmetics;

import com.flintclient.client.control.feature.CosmeticKinds;
import com.flintclient.client.control.feature.CosmeticPreview;
import com.flintclient.client.cosmetics.CosmeticKind;
import com.flintclient.client.cosmetics.ModelCosmetics;
import com.flintclient.client.cosmetics.ModelCosmetics.Loaded;
import com.flintclient.client.cosmetics.ModelItem;
import com.flintclient.client.cosmetics.ModelPose;
import com.flintclient.client.cosmetics.ModelThumbnails;
import com.flintclient.client.module.social.SocialLink.Item;
import com.flintclient.client.platform.Frame;
import com.flintclient.client.platform.Texture;
import com.flintclient.shaded.gson.JsonObject;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.model.Model;
import net.minecraft.client.render.model.ModelPart;
import net.minecraft.client.render.model.entity.PlayerModel;
import net.minecraft.client.render.platform.GLX;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.client.render.platform.Lighting;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11;

/**
 * The wardrobe on 1.8.9 (26.3's {@code Mc263WardrobeRenderer} and its {@code McCompat.previewAvatar}): the 3D renderer the
 * wardrobe asks for tiles' turntables ({@link ModelThumbnails}, made once off the game's thread) and the preview player, a
 * whole 1.8.9 player model drawn into the wardrobe's box with the skin and cape it is given, wearing the wardrobe's selection
 * and playing your emote, lit as the inventory's doll is. Works on the title screen too (no world, no entity). Render thread.
 */
public final class Wardrobe189 {
	static final Renderer RENDERER = new Renderer();
	private static final Worn189 PREVIEW_WORN = new Worn189(new UUID(0, 0x464C494E54L));
	private static @Nullable PlayerModel wide;
	private static @Nullable PlayerModel slim;
	private static long previewFrame = -1;

	private Wardrobe189() {
	}

	/** What the preview player wears this frame (the wardrobe's selection) and does (your emote). */
	static Worn189 previewWorn(ModelCosmetics<Texture> s) {
		Worn189 w = PREVIEW_WORN;
		if (previewFrame == Cosmetics189.frames) {
			return w;
		}
		previewFrame = Cosmetics189.frames;
		Map<String, Item> wearing = CosmeticPreview.wearing();
		boolean hides = false;
		for (int k = 0; k < w.instances.length; k++) {
			Item item = wearing.get(CosmeticKind.WORN[k].slot);
			ModelItem m = item == null ? null : RENDERER.item(item, s);
			Loaded<Texture> l = m == null || m.kind() != CosmeticKind.WORN[k] ? null : s.request(m);
			Worn189.Instance inst = w.instances[k];
			if (l == null || !l.drawable()) {
				w.instances[k] = null;
			} else if (inst == null || inst.loaded != l) {
				w.instances[k] = new Worn189.Instance(l);
			}
			hides |= l != null && l.item.hidesCape();
		}
		w.hidesCape = hides;
		w.motion = ModelPose.IDLE;
		w.emote = Emotes189.playing();
		w.emoteSeconds = w.emote == null ? 0 : Emotes189.seconds();
		w.emoteWeight = w.emote == null ? 0 : Emotes189.weight();
		w.lastSeen = System.nanoTime();
		return w;
	}

	/**
	 * Draws the player in the pixel box ({@code x}, {@code y}, {@code w}, {@code h}) of {@code frame}: {@code skin} (null: the
	 * account's own), {@code slim} arms, {@code cape} (null: none), turned {@code yaw} degrees from facing the viewer.
	 */
	public static boolean previewPlayer(Frame frame, @Nullable Texture skin, boolean slimArms, @Nullable Texture cape, int x, int y, int w, int h,
		float yaw, float capeLean) {
		if (w < 8 || h < 16) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		PlayerModel model = slimArms ? slim : wide;
		if (model == null) {
			model = new PlayerModel(0.0F, slimArms);
			if (slimArms) {
				slim = model;
			} else {
				wide = model;
			}
		}
		float s = h / 2.1F;
		float cx = x + w / 2.0F;
		float foot = y + h / 2.0F + 0.95F * s;
		frame.pushGame();
		GlStateManager.pushMatrix();
		try {
			GlStateManager.enableDepthTest();
			GlStateManager.depthMask(true);
			GlStateManager.clear(GL11.GL_DEPTH_BUFFER_BIT);
			GlStateManager.enableAlphaTest();
			GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
			GlStateManager.disableCull();
			GlStateManager.shadeModel(GL11.GL_SMOOTH);
			GlStateManager.activeTexture(GLX.GL_TEXTURE1);
			GlStateManager.disableTexture();
			GlStateManager.activeTexture(GLX.GL_TEXTURE0);
			GlStateManager.translatef(cx, foot, 500.0F);
			// Model space is y down with the face on -z; the frame's is y down with the viewer on +z.
			GlStateManager.scalef(s, s, -s);
			GlStateManager.rotatef(-6.0F, 1.0F, 0.0F, 0.0F);
			GlStateManager.rotatef(135.0F, 0.0F, 1.0F, 0.0F);
			Lighting.turnOn();
			GlStateManager.rotatef(-135.0F, 0.0F, 1.0F, 0.0F);
			GlStateManager.rotatef(yaw, 0.0F, 1.0F, 0.0F);
			GlStateManager.translatef(0.0F, -1.5078125F, 0.0F);
			GlStateManager.enableRescaleNormal();
			GlStateManager.color4f(1, 1, 1, 1);
			Worn189 worn = Cosmetics189.worn(null);
			pose(model, worn);
			if (skin != null) {
				GlStateManager.bindTexture(skin.glId());
			} else {
				mc.getTextureManager().bind(com.flintclient.client.platform.Faces.ownSkin());
			}
			for (ModelPart part : new ModelPart[] {model.head, model.body, model.rightArm, model.leftArm, model.rightLeg, model.leftLeg}) {
				part.render(0.0625F);
			}
			for (ModelPart part : new ModelPart[] {model.hat, model.jacket, model.rightSleeve, model.leftSleeve, model.rightPants, model.leftPants}) {
				part.render(0.0625F);
			}
			if (cape != null && (worn == null || !worn.hidesCape)) {
				GlStateManager.bindTexture(cape.glId());
				GlStateManager.pushMatrix();
				GlStateManager.translatef(0.0F, 0.0F, 0.125F);
				GlStateManager.rotatef(6.0F + capeLean, 1.0F, 0.0F, 0.0F);
				GlStateManager.rotatef(180.0F, 0.0F, 1.0F, 0.0F);
				model.renderCape(0.0625F);
				GlStateManager.popMatrix();
			}
			if (worn != null) {
				CosmeticsLayer189.draw(model, worn, 0, true, false, false);
			}
		} finally {
			GlStateManager.disableRescaleNormal();
			Lighting.turnOff();
			GlStateManager.popMatrix();
			GlStateManager.disableDepthTest();
			frame.popGame();
		}
		return true;
	}

	/** The preview's still pose (arms a little out, breathing), with your emote laid over it. */
	private static void pose(PlayerModel model, @Nullable Worn189 worn) {
		for (ModelPart p : new ModelPart[] {model.head, model.body, model.rightArm, model.leftArm, model.rightLeg, model.leftLeg}) {
			p.rotationX = 0;
			p.rotationY = 0;
			p.rotationZ = 0;
		}
		model.head.setPos(0, 0, 0);
		model.body.setPos(0, 0, 0);
		model.rightLeg.setPos(-1.9F, 12, 0.1F);
		model.leftLeg.setPos(1.9F, 12, 0.1F);
		model.rightArm.setPos(-5, 2, 0);
		model.leftArm.setPos(5, 2, 0);
		float t = (System.nanoTime() % 6_283_000_000L) / 1e9F;
		model.rightArm.rotationZ = 0.05F + (float) Math.cos(t * 0.09F * 20) * 0.03F;
		model.leftArm.rotationZ = -0.05F - (float) Math.cos(t * 0.09F * 20) * 0.03F;
		if (worn != null) {
			EmoteModel189.apply(model, worn);
		}
		Model.copyRotation(model.head, model.hat);
		Model.copyRotation(model.body, model.jacket);
		Model.copyRotation(model.rightArm, model.rightSleeve);
		Model.copyRotation(model.leftArm, model.leftSleeve);
		Model.copyRotation(model.rightLeg, model.rightPants);
		Model.copyRotation(model.leftLeg, model.leftPants);
	}

	/** The wardrobe's 3D renderer ({@link CosmeticPreview.Renderer}). */
	static final class Renderer implements CosmeticPreview.Renderer {
		static final int CELL = 64;
		private static final int MAX_ITEMS = 256;
		private final Map<Item, ModelItem> items = new IdentityHashMap<>();
		private final Map<Item, Boolean> none = new IdentityHashMap<>();

		@Nullable ModelItem item(Item item, ModelCosmetics<Texture> s) {
			ModelItem m = items.get(item);
			if (m != null || none.containsKey(item)) {
				return m;
			}
			if (items.size() + none.size() > MAX_ITEMS) {
				items.clear();
				none.clear();
			}
			CosmeticKind kind = CosmeticKind.of(CosmeticKinds.normalize(item.kind()));
			JsonObject model = item.model();
			m = kind == null || model == null ? null : ModelItem.model(item.id(), kind, model, s.endpoint());
			if (m == null && kind != null && model != null) {
				m = ModelItem.linked(item.id(), kind, model);
			}
			if (m == null) {
				none.put(item, Boolean.TRUE);
			} else {
				items.put(item, m);
			}
			return m;
		}

		@Override
		public boolean thumbnail(Frame frame, Item item, int x, int y, int w, int h, float yaw) {
			try {
				ModelCosmetics<Texture> s = Cosmetics189.service();
				ModelItem m = s == null || !Cosmetics189.GUARD.on() ? null : item(item, s);
				if (m == null || m.geometry() == null) {
					return false;
				}
				ModelCosmetics.Thumb<Texture> t = s.thumbnail(m, CELL);
				Texture texture = t.texture;
				if (t.state() != ModelCosmetics.UPLOADED || texture == null) {
					return false;
				}
				int cell = ModelThumbnails.cell(yaw);
				int side = Math.min(w, h);
				int atlas = ModelThumbnails.atlasSize(CELL);
				frame.blit(texture, x + (w - side) / 2, y + (h - side) / 2, (cell % ModelThumbnails.COLUMNS) * CELL,
					(cell / ModelThumbnails.COLUMNS) * CELL, side, side, CELL, CELL, atlas, atlas, -1);
				return true;
			} catch (RuntimeException | LinkageError e) {
				return false;
			}
		}

		@Override
		public boolean draws(String kind) {
			CosmeticKind k = CosmeticKind.of(CosmeticKinds.normalize(kind));
			return k != null && Cosmetics189.GUARD.on();
		}

		@Override
		public boolean previewEmote(Item emote) {
			ModelCosmetics<Texture> s = Cosmetics189.service();
			ModelItem m = s == null ? null : item(emote, s);
			return m != null && m.kind() == CosmeticKind.EMOTE && Emotes189.play(m);
		}
	}
}
