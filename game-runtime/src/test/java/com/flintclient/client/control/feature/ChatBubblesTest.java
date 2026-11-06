package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChatBubblesTest {
	private static final UUID STEVE = new UUID(1, 1);
	private static final Set<String> ONLINE = Set.of("Steve", "Alex_2");

	@Test
	void newestFirstFadingInAndOut() {
		ChatBubbles bubbles = new ChatBubbles();
		bubbles.add(STEVE, "first", 0, 48);
		bubbles.add(STEVE, "second", 1000, 48);
		List<ChatBubbles.Line> lines = bubbles.lines(STEVE, 1100, 3, 200, 6000, 200);
		assertEquals(List.of("second", "first"), lines.stream().map(ChatBubbles.Line::text).toList());
		assertEquals(0.5F, lines.get(0).alpha(), 1e-6, "halfway through its fade-in");
		assertEquals(1F, lines.get(1).alpha(), 1e-6);
		assertEquals(0.5F, bubbles.lines(STEVE, 6100, 3, 200, 6000, 200).get(1).alpha(), 1e-6, "halfway through its fade-out");
		assertEquals(List.of("second"), bubbles.lines(STEVE, 6300, 3, 200, 6000, 200).stream().map(ChatBubbles.Line::text).toList(),
			"a gone message is dropped");
		assertEquals(1, bubbles.lines(STEVE, 1100, 1, 200, 6000, 200).size(), "at most as many as asked");
		assertTrue(bubbles.lines(STEVE, 8000, 3, 200, 6000, 200).isEmpty());
		assertTrue(bubbles.isEmpty());
	}

	@Test
	void longMessagesAreCutAndOldOnesForgotten() {
		ChatBubbles bubbles = new ChatBubbles();
		bubbles.add(STEVE, "  hello    there  ", 0, 48);
		assertEquals("hello there", bubbles.lines(STEVE, 0, 3, 0, 1000, 0).get(0).text());
		assertEquals("abcdefg…", ChatBubbles.cut("abcdefghijk", 8));
		assertEquals("abc", ChatBubbles.cut("abc", 8));
		for (int i = 0; i < 20; i++) {
			bubbles.add(STEVE, "m" + i, i, 48);
		}
		assertEquals(ChatBubbles.KEPT, bubbles.lines(STEVE, 20, 99, 0, 1000, 0).size());
		bubbles.prune(5000, 1000);
		assertTrue(bubbles.isEmpty());
	}

	@Test
	void plainTextChatLinesNameTheirSender() {
		assertArrayEquals(new String[] {"Steve", "hi there"}, ChatBubbles.sender("<Steve> hi there", ONLINE::contains));
		assertArrayEquals(new String[] {"Steve", "hi: you"}, ChatBubbles.sender("[VIP] Steve: hi: you", ONLINE::contains));
		assertArrayEquals(new String[] {"Alex_2", "gg"}, ChatBubbles.sender("[Admin]Alex_2 » gg", ONLINE::contains));
		assertArrayEquals(new String[] {"Steve", "ok"}, ChatBubbles.sender("12:30 Steve: ok", ONLINE::contains));
		assertNull(ChatBubbles.sender("Server: restarting soon", ONLINE::contains), "not a player");
		assertNull(ChatBubbles.sender("Steve joined the game", ONLINE::contains), "not chat");
		assertNull(ChatBubbles.sender("Steve: ", ONLINE::contains), "nothing said");
		assertNull(ChatBubbles.sender("Steve whispers to you: psst", ONLINE::contains));
	}
}
