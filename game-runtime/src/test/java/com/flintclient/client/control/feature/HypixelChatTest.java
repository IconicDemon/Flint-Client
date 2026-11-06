package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The Hypixel mod's automations: only on Hypixel, only for Hypixel's own request line, never as a command flood. */
class HypixelChatTest {
	@Test
	void onlyHypixelsOwnAddressesCount() {
		for (String hypixel : new String[] {"hypixel.net", "mc.hypixel.net", "MC.Hypixel.NET:25565", "hypixel.net.", " stuck.hypixel.net "}) {
			assertTrue(HypixelChat.isHypixel(hypixel), hypixel);
		}
		for (String other : new String[] {"hypixel.net.example.org", "nothypixel.net", "hypixel.network", "play.example.com/hypixel.net", "[::1]:25565", "", "hypixel"}) {
			assertFalse(HypixelChat.isHypixel(other), other);
		}
		assertFalse(HypixelChat.isHypixel(null));
	}

	@Test
	void aRequestIsHypixelsLineNotSomethingAPlayerTyped() {
		String request = "-----------------------------------------------------\nFriend request from [MVP+] Notch\n[ACCEPT] - [DENY] - [BLOCK]\n"
			+ "-----------------------------------------------------";
		assertEquals("Notch", HypixelChat.friendRequestSender(request));
		assertEquals("jeb_", HypixelChat.friendRequestSender("Friend request from jeb_"));
		// Typed in chat, the words follow the sender's name, so they never start the line.
		assertNull(HypixelChat.friendRequestSender("[VIP] Griefer: Friend request from Notch"));
		assertNull(HypixelChat.friendRequestSender("Party > [MVP] Griefer: Friend request from Notch"));
		assertNull(HypixelChat.friendRequestSender("Griefer: hi\nGriefer: Friend request from Notch"));
		assertNull(HypixelChat.friendRequestSender("Friend request from someone_with_a_far_too_long_name"));
	}

	@Test
	void acceptsAreSpacedOut() {
		HypixelChat chat = new HypixelChat();
		assertTrue(chat.mayAccept(10_000));
		assertFalse(chat.mayAccept(10_500), "a repeated line right after doesn't send again");
		assertFalse(chat.mayAccept(10_000 + HypixelChat.ACCEPT_GAP_MS - 1));
		assertTrue(chat.mayAccept(10_000 + HypixelChat.ACCEPT_GAP_MS));
		assertTrue(new HypixelChat().mayAccept(0), "the first request is accepted at once");
	}
}
