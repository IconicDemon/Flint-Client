package com.flintclient.client.control.rshift;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The in-game chat's "typing…" strip times out as the Flint app's does: 5 s after the friend was last seen typing. */
class FriendsChatTypingTest {
	@Test
	void typingShowsForFiveSecondsAfterTheLastSighting() {
		assertTrue(FriendsChat.typingOn(1000, 1000));
		assertTrue(FriendsChat.typingOn(1000, 5999));
		assertFalse(FriendsChat.typingOn(1000, 6000));
		assertFalse(FriendsChat.typingOn(Long.MIN_VALUE / 2, 1000), "never seen typing");
	}
}
