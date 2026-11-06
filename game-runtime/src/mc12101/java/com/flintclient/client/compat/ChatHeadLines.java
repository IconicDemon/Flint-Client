package com.flintclient.client.compat;

import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.UtilityMods.Chat;
import com.flintclient.client.control.platform.Mc263Chat;
import com.flintclient.client.control.platform.Mc263ChatHeads;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.UUID;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import org.jspecify.annotations.Nullable;

/**
 * Chat Heads on Minecraft 1.21.1, which has no head glyph to put into a message (1.21.9 added object text): the sender Flint
 * found ({@link Mc263Chat}, from Mc263ChatHeads' detection) gets their head drawn before the message's first line, and the
 * text moves right of it (Before Line; Before Name says why it can't be here). The module's Round heads, 3Dness, Render shadow,
 * right padding and Offset non-player text apply as on 26.x. Render thread only.
 */
public final class ChatHeadLines {
	private static final int CHAT = Mods.index(Chat.ID);
	/** Each row's inset at either side: an 8-pixel face with its corners rounded off. */
	private static final int[] ROUND = {2, 1, 0, 0, 0, 0, 1, 2};

	private ChatHeadLines() {
	}

	/** How far {@code message}'s lines sit right of the chat's edge: a head's width (with its padding), or 0. */
	public static int indent(GuiMessage message) {
		try {
			if (!Mc263ChatHeads.flint$active()) {
				return 0;
			}
			ModuleState chat = Mods.state(CHAT);
			return chat.bool(Chat.ALIGN) || Mc263Chat.flint$sender(message) != null ? Mc263ChatHeads.flint$headWidth() : 0;
		} catch (RuntimeException e) {
			return 0;
		}
	}

	/** Before an entry's first line is drawn at {@code y}: its sender's head at the chat's left edge, {@code alpha} faded. */
	public static void draw(GuiGraphics g, GuiMessage message, int y, float alpha) {
		try {
			UUID sender = Mc263Chat.flint$sender(message);
			if (sender == null || !Mc263ChatHeads.flint$active()) {
				return;
			}
			ModuleState chat = Mods.state(CHAT);
			ClientPacketListener connection = Minecraft.getInstance().getConnection();
			PlayerInfo info = connection == null ? null : connection.getPlayerInfo(sender);
			ResourceLocation skin = info != null ? info.getSkin().texture() : DefaultPlayerSkin.get(sender).texture();
			float threeDee = chat.integer(Chat.THREE_D) / 100F;
			boolean round = chat.bool(Chat.ROUND_AVATARS);
			RenderSystem.enableBlend();
			if (chat.bool(Chat.HEAD_SHADOW)) {
				// As text's shadow: a quarter as bright, one pixel down and right.
				head(g, skin, 1, y + 1, threeDee, round, 0.25F, alpha);
			}
			head(g, skin, 0, y, threeDee, round, 1, alpha);
			RenderSystem.setShaderColor(1, 1, 1, 1);
		} catch (RuntimeException e) {
			RenderSystem.setShaderColor(1, 1, 1, 1);
		}
	}

	/**
	 * The face, then the hat over it, whole or round. With 3Dness t (as Chat Heads draws it) the face moves t right and the
	 * hat spans from the head's left edge 2t wider and t higher and lower, so it stands out around the face.
	 */
	private static void head(GuiGraphics g, ResourceLocation skin, float x, int y, float t, boolean round, float brightness, float alpha) {
		RenderSystem.setShaderColor(brightness, brightness, brightness, alpha);
		layer(g, skin, x + t, y, 1, 8, round);
		layer(g, skin, x, y - t, (8 + 2 * t) / 8, 40, round);
	}

	/** One 8 × 8 layer of the head (u: 8 face, 40 hat) with its top left at {@code x}, {@code y}, {@code scale} times as big. */
	private static void layer(GuiGraphics g, ResourceLocation skin, float x, float y, float scale, int u, boolean round) {
		g.pose().pushPose();
		g.pose().translate(x, y, 0);
		if (scale != 1) {
			g.pose().scale(scale, scale, 1);
		}
		if (!round) {
			g.blit(skin, 0, 0, 8, 8, u, 8, 8, 8, 64, 64);
		} else {
			for (int r = 0; r < 8; r++) {
				int in = ROUND[r];
				g.blit(skin, in, r, 8 - 2 * in, 1, u + in, 8 + r, 8 - 2 * in, 1, 64, 64);
			}
		}
		g.pose().popPose();
	}
}
