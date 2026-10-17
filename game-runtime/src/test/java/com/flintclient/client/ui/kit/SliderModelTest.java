package com.flintclient.client.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SliderModelTest {
	@Test
	void valuesSnapToTheStepGridAndStayInRange() {
		SliderModel volume = new SliderModel(0, 100, 5);
		assertEquals(60, volume.snap(61.9F));
		assertEquals(65, volume.snap(62.6F));
		assertEquals(0, volume.snap(-20));
		assertEquals(100, volume.snap(140));
		assertEquals(0, volume.snap(Float.NaN));
	}

	@Test
	void decimalStepsDoNotDrift() {
		SliderModel scale = new SliderModel(0.5F, 2.0F, 0.25F);
		assertEquals(1.25F, scale.snap(1.3F));
		SliderModel tenths = new SliderModel(0, 1, 0.1F);
		assertEquals(0.3F, tenths.snap(0.29F));
		assertEquals(0.3F, tenths.stepped(0.2F, 1));
	}

	@Test
	void theEndOfARangeOffTheGridIsStillReachable() {
		SliderModel thirds = new SliderModel(0, 10, 3);
		assertEquals(9, thirds.snap(9.2F));
		assertEquals(10, thirds.snap(9.8F));
		assertEquals(10, thirds.stepped(9, 1));
		assertEquals(10, thirds.valueAt(1));
	}

	@Test
	void steppingDownFromAnOffGridMaximumLandsOnTheLastStop() {
		SliderModel thirds = new SliderModel(0, 10, 3);
		assertEquals(9, thirds.stepped(10, -1));
		assertEquals(6, thirds.stepped(10, -2));
		assertEquals(10, thirds.stepped(10, 1), "already at the top");
		assertEquals(0, thirds.stepped(10, -10));
		SliderModel tenths = new SliderModel(0, 1, 0.1F);
		assertEquals(0.2F, tenths.stepped(0.3F, -1), "float steps count the right stop");
	}

	@Test
	void fractionsMapBothWays() {
		SliderModel padding = new SliderModel(0, 8, 1);
		assertEquals(0.5F, padding.fraction(4));
		assertEquals(4, padding.valueAt(0.52F));
		assertEquals(0, padding.valueAt(-1));
		assertEquals(8, padding.valueAt(3));
		assertEquals(0, new SliderModel(5, 5, 1).fraction(5), "an empty range");
	}

	@Test
	void keyboardStepsMoveByOneOrTenSteps() {
		SliderModel volume = new SliderModel(0, 100, 5);
		assertEquals(65, volume.stepped(60, 1));
		assertEquals(10, volume.stepped(60, -10));
		assertEquals(100, volume.stepped(95, 10));
		assertEquals(0, volume.stepped(3, -1), "from off the grid, snapped first");
		// A step of zero moves by a hundredth of the range.
		assertEquals(0.51F, new SliderModel(0, 1, 0).stepped(0.5F, 1), 1e-6);
	}

	@Test
	void anInvertedRangeIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> new SliderModel(2, 1, 1));
	}
}
