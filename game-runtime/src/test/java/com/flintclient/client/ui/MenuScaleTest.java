package com.flintclient.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MenuScaleTest {
	@Test
	void theMenuFollowsTheWindowNotTheGuiScale() {
		assertEquals(3.7F, MenuScale.forWindow(2560, 1369), "a 1440p window under a title bar and taskbar");
		assertEquals(3.7F, MenuScale.forWindow(2560, 1440), "1440p full screen (Minecraft's automatic GUI scale is 6 there)");
		assertEquals(2.9F, MenuScale.forWindow(1920, 1080));
		assertEquals(2.7F, MenuScale.forWindow(1920, 1009), "a 1080p window");
		assertEquals(2.0F, MenuScale.forWindow(1280, 720), "never under 2 where it fits: the small text stays readable");
		assertEquals(3.7F, MenuScale.forWindow(3840, 2160), "never over 3.7");
	}

	@Test
	void smallWindowsStillFitTheLayout() {
		float scale = MenuScale.forWindow(854, 480);
		assertEquals(1.6F, scale, "Minecraft's default window");
		assertTrue(480 / scale >= MenuScale.MIN_HEIGHT && 854 / scale >= MenuScale.MIN_WIDTH);
		assertEquals(1.7F, MenuScale.forWindow(900, 720), "a narrow window: the width decides");
		assertEquals(1.0F, MenuScale.forWindow(320, 240), "never under 1");
	}

	@Test
	void scalesComeInTenthStepsAndAlwaysFit() {
		for (int h = 240; h <= 2400; h += 7) {
			int w = h * 16 / 9;
			float scale = MenuScale.forWindow(w, h);
			assertEquals(0.0F, scale * 10 - Math.round(scale * 10), 1e-5F, "height " + h);
			assertTrue(scale <= 1.0F || (w / scale >= MenuScale.MIN_WIDTH && h / scale >= MenuScale.MIN_HEIGHT), "height " + h);
		}
	}
}
