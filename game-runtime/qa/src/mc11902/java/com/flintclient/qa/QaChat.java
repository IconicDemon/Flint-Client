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
 * What Minecraft 1.19.1 and 1.19.2 changed with chat signing, for the tour: a command is sent signed (with no key, as an
 * offline player's, it goes unsigned), chat lines carry a message tag, and a player entry knows whether the server enforces
 * secure chat. 1.19 has its own (src/mc11900).
 */
final class QaChat {
	private QaChat() {
	}

	/** A command as typed in chat, without the slash, as the chat screen sends it. */
	static void command(Minecraft mc, String command) {
		mc.player.commandSigned(command, null);
	}

	/** The chat window's lines as shown now, newest first. */
	@SuppressWarnings("unchecked")
	static List<FormattedCharSequence> lines(Minecraft mc) throws ReflectiveOperationException {
		List<GuiMessage.Line> lines = (List<GuiMessage.Line>) QaNames.field(ChatComponent.class, "trimmedMessages").get(QaGame.chat(mc));
		return lines.stream().map(GuiMessage.Line::content).toList();
	}

	/** A player-list entry as a server's "add player" makes one (survival, no key, no secure chat), for QaBots. */
	static PlayerInfo playerInfo(Minecraft mc, GameProfile profile) {
		return new PlayerInfo(new ClientboundPlayerInfoPacket.PlayerUpdate(profile, 0, GameType.SURVIVAL, null, null), mc.getServiceSignatureValidator(),
			false);
	}
}
