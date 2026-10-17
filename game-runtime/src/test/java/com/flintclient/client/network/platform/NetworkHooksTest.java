package com.flintclient.client.network.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

/** The one place a disconnect reason is read from Minecraft's own text components: only vanilla keys come out. */
class NetworkHooksTest {
	@Test
	void aVanillaReasonGivesItsKey() {
		assertEquals("multiplayer.disconnect.kicked", NetworkHooks.reasonKey(Component.translatable("multiplayer.disconnect.kicked")));
		assertEquals("disconnect.timeout", NetworkHooks.reasonKey(Component.translatable("disconnect.timeout").append(" from 203.0.113.7")),
			"appended text is never read");
	}

	@Test
	void theServersOwnTextGivesNothing() {
		assertNull(NetworkHooks.reasonKey(null));
		assertNull(NetworkHooks.reasonKey(Component.literal("Banned from play.example.net")));
		assertNull(NetworkHooks.reasonKey(Component.translatable("myplugin.kick", "203.0.113.7")), "a plugin's key");
	}

	@Test
	void aWrapperGivesTheVanillaReasonInsideIt() {
		assertEquals("disconnect.unknownHost", NetworkHooks.reasonKey(
			Component.translatable("disconnect.genericReason", Component.translatable("disconnect.unknownHost"))));
		assertEquals("disconnect.loginFailedInfo.invalidSession", NetworkHooks.reasonKey(
			Component.translatable("disconnect.loginFailedInfo", Component.translatable("disconnect.loginFailedInfo.invalidSession"))));
		assertEquals("disconnect.genericReason", NetworkHooks.reasonKey(
			Component.translatable("disconnect.genericReason", "Internal Exception: java.net.SocketException: 203.0.113.7:25565")),
			"an exception message stays out");
		assertEquals("disconnect.genericReason", NetworkHooks.reasonKey(
			Component.translatable("disconnect.genericReason", Component.translatable("myplugin.reason"))));
	}
}
