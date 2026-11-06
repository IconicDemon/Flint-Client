package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The third-person distance follows its setting, gliding when smooth, and steps on the setting's grid. */
class CameraDistanceTest {
	private static final ZoomState.Easing LINEAR = ZoomState.Easing.LINEAR;

	@Test
	void followsTheSettingGlidingWhenSmooth() {
		CameraDistance d = new CameraDistance();
		assertEquals(4F, d.at(4F, 0, true, LINEAR), "the first value is used as it is");
		assertEquals(4F, d.at(8F, 1000, true, LINEAR), 1e-4F, "a change starts from where it was");
		long half = CameraDistance.GLIDE_NS / 2;
		assertEquals(6F, d.at(8F, 1000 + half, true, LINEAR), 1e-3F, "halfway");
		assertEquals(8F, d.at(8F, 1000 + CameraDistance.GLIDE_NS, true, LINEAR), 1e-4F, "there");
		assertEquals(2F, d.at(2F, 5_000_000_000L, false, LINEAR), "not smooth: at once");
	}

	@Test
	void aChangeMidGlideStartsFromWhereItIs() {
		CameraDistance d = new CameraDistance();
		d.at(4F, 0, true, LINEAR);
		d.at(8F, 0, true, LINEAR);
		float mid = d.at(8F, CameraDistance.GLIDE_NS / 2, true, LINEAR);
		assertEquals(mid, d.at(2F, CameraDistance.GLIDE_NS / 2, true, LINEAR), 1e-4F, "no jump");
		assertTrue(d.at(2F, CameraDistance.GLIDE_NS, true, LINEAR) < mid);
	}

	@Test
	void stepsStayOnTheGridAndInRange() {
		assertEquals(4.5F, CameraDistance.stepped(4F, 0.5F, 1, 0.05F, 16F));
		assertEquals(3.85F, CameraDistance.stepped(4F, 0.05F, -3, 0.05F, 16F));
		assertEquals(0.05F, CameraDistance.stepped(0.3F, 0.5F, -1, 0.05F, 16F));
		assertEquals(16F, CameraDistance.stepped(15.9F, 0.5F, 1, 0.05F, 16F));
	}
}
