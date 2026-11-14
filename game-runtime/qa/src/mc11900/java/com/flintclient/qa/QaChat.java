package com.flintclient.qa;

import com.mojang.authlib.GameProfile;
import java.util.List;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoPacket;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.GameType;

/**
 * Chat on Minecraft 1.19 (1.19.0), before 1.19.1's changes (src/mc11902): a command is sent with LocalPlayer.command (signed
 * when there is a key; an offline player has none), a chat line is a plain GuiMessage, and a player entry takes no secure-chat
 * flag. The rest of the 1.19.2 overlay serves 1.19 unchanged.
 */
final class QaChat {
	private QaChat() {
	}

	/** A command as typed in chat, without the slash, as the chat screen sends it. */
	static void command(Minecraft mc, String command) {
		mc.player.command(command);
	}

	/** The chat window's lines as shown now, newest first. */
	@SuppressWarnings("unchecked")
	static List<FormattedCharSequence> lines(Minecraft mc) throws ReflectiveOperationException {
		List<GuiMessage<FormattedCharSequence>> lines = (List<GuiMessage<FormattedCharSequence>>) QaNames.field(ChatComponent.class, "trimmedMessages")
			.get(QaGame.chat(mc));
		return lines.stream().map(GuiMessage::getMessage).toList();
	}

	/** A player-list entry as a server's "add player" makes one (survival, no key), for QaBots. */
	static PlayerInfo playerInfo(Minecraft mc, GameProfile profile) {
		return new PlayerInfo(new ClientboundPlayerInfoPacket.PlayerUpdate(profile, 0, GameType.SURVIVAL, null, null), mc.getServiceSignatureValidator());
	}
}
