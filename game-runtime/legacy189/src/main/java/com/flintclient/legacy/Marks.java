package com.flintclient.legacy;

import com.flintclient.client.control.feature.FlintPlayers;
import com.flintclient.client.control.model.GameOptions;
import com.flintclient.legacy.shared.Shared;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiElement;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.client.render.texture.DynamicTexture;
import net.minecraft.resource.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The Flint mark before the names of Flint players on Minecraft 1.8.9 (nametags and the player list), as the other lines show
 * it. 1.8.9's text can't hold pictures, so a Flint player's name gets a marker that takes no room ({@code §k} then one
 * {@code §r} per mark kind, which the font skips) and two spaces: Minecraft's own measuring leaves room for the mark, and
 * where the name is drawn {@link #draw} takes the marker out and draws the mark's picture in the room. Shown while "Flint
 * icon on nametags" is on, for you and for those Flint presence reports. The pictures are the other lines' badge textures
 * ({@code assets/flintclient/textures/font/tag_<mark>.png}), read from this jar into dynamic textures on first use: 1.8.9's
 * Fabric has no resource loader, so the game's resource manager never sees Flint's assets (bound by name they were the
 * missing-texture checkerboard). Render thread.
 */
public final class Marks {
	private static final String START = "§k";
	private static final String STEP = "§r";
	/** The mark's room: two spaces (8 pixels in Minecraft's font), the picture 5 wide with a little air. */
	private static final String ROOM = "  ";
	/** Each mark's picture once loaded (null: not yet, or it couldn't be read), by tag. */
	private static final Identifier[] PICTURES = new Identifier[FlintPlayers.Tag.values().length];
	private static final boolean[] TRIED = new boolean[PICTURES.length];

	private Marks() {
	}

	/** {@code name} with the marker for {@code player}'s mark before it, or as it is (not on Flint, or marks off). */
	public static String mark(@Nullable UUID player, String name) {
		if (player == null || name.startsWith(START)) {
			return name;
		}
		try {
			if (!Shared.settings().gameBool(GameOptions.FLINT_BADGE)) {
				return name;
			}
			Minecraft mc = Minecraft.getInstance();
			boolean you = mc.player != null && player.equals(mc.player.getUuid());
			FlintPlayers.Tag tag = you ? FlintPlayers.own() : FlintPlayers.tag(player);
			if (tag == null) {
				return name;
			}
			return START + STEP.repeat(tag.ordinal() + 1) + ROOM + name;
		} catch (RuntimeException | LinkageError e) {
			return name;
		}
	}

	/**
	 * Draws {@code text} at ({@code x}, {@code y}) as {@code font} would; a marked name gets its mark's picture in its room
	 * (as bright as the text: the see-through pass of a nametag stays faint) and the marker taken out.
	 */
	public static int draw(TextRenderer font, String text, float x, float y, int color, boolean shadow) {
		int at = text.indexOf(START);
		if (at < 0) {
			return font.draw(text, x, y, color, shadow);
		}
		int kind = 0;
		int end = at + START.length();
		while (text.startsWith(STEP, end)) {
			kind++;
			end += STEP.length();
		}
		String before = text.substring(0, at);
		String shown = before + text.substring(end);
		Identifier picture = kind > 0 && kind <= PICTURES.length ? picture(kind - 1) : null;
		if (picture != null) {
			int alpha = color >>> 24;
			float a = alpha == 0 ? 1.0F : alpha / 255.0F;
			int left = Math.round(x) + font.getWidth(before);
			Minecraft.getInstance().getTextureManager().bind(picture);
			GlStateManager.color4f(1.0F, 1.0F, 1.0F, a);
			GlStateManager.enableBlend();
			GuiElement.drawTexture(left, Math.round(y) - 1, 0.0F, 0.0F, 10, 16, 5, 8, 10.0F, 16.0F);
			GlStateManager.color4f(1.0F, 1.0F, 1.0F, 1.0F);
		}
		return font.draw(shown, x, y, color, shadow);
	}

	/** Mark {@code index}'s picture, read from this jar once (null when it can't be: the name is drawn with its room only). */
	private static @Nullable Identifier picture(int index) {
		if (!TRIED[index]) {
			TRIED[index] = true;
			String name = FlintPlayers.Tag.values()[index].name().toLowerCase(Locale.ROOT);
			try (InputStream in = Marks.class.getResourceAsStream("/assets/flintclient/textures/font/tag_" + name + ".png")) {
				if (in == null) {
					throw new IllegalStateException("not in the jar");
				}
				BufferedImage image = ImageIO.read(in);
				int w = image.getWidth();
				int h = image.getHeight();
				DynamicTexture texture = new DynamicTexture(w, h);
				image.getRGB(0, 0, w, h, texture.getPixels(), 0, w);
				texture.upload();
				Identifier id = new Identifier("flintclient", "marks/tag_" + name);
				Minecraft.getInstance().getTextureManager().register(id, texture);
				PICTURES[index] = id;
			} catch (Exception e) {
				Flint.LOG.warn("Flint: the " + name + " Flint mark couldn't be read; names keep their room without it", e);
			}
		}
		return PICTURES[index];
	}
}
