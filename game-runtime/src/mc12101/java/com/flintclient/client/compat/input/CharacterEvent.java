package com.flintclient.client.compat.input;

/** A typed character, as 1.21.9's {@code CharacterEvent}. */
public record CharacterEvent(int codepoint, int modifiers) {
	public String codepointAsString() {
		return Character.toString(codepoint);
	}

	public boolean isAllowedChatCharacter() {
		return codepoint < 0x10000 && net.minecraft.util.StringUtil.isAllowedChatCharacter((char) codepoint);
	}
}
